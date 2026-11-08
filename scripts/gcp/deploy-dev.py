"""Deploy one tested main commit, verify its candidate pair, then switch traffic."""

import base64
import json
import os
import re
import signal
import subprocess
import tempfile
from pathlib import Path

import httpx


class DeploymentError(RuntimeError):
    pass


def serving_traffic(service: dict) -> dict[str, int]:
    traffic = {}
    for item in service["status"]["traffic"]:
        if item.get("percent", 0):
            revision = item["revisionName"]
            traffic[revision] = traffic.get(revision, 0) + item["percent"]
    if sum(traffic.values()) != 100:
        raise DeploymentError("Expected an existing service serving 100 percent of traffic")
    return traffic


class Deployment:
    def __init__(self):
        self.project = os.environ["PROJECT_ID"]
        self.region = os.environ["REGION"]
        self.sha = os.environ["GITHUB_SHA"]
        run = f"{os.environ['GITHUB_RUN_ID']}-{os.environ['GITHUB_RUN_ATTEMPT']}"
        # Keep the tag short enough for the service's tagged DNS hostname.
        # The run ID and attempt are unique; the full commit is recorded below.
        self.tag = f"deploy-{run}"
        self.services = {
            "core": os.environ["CORE_API_SERVICE"],
            "planner": os.environ["GOAL_PLANNER_SERVICE"],
        }
        self.images = {
            "core": os.environ["CORE_API_IMAGE"],
            "planner": os.environ["GOAL_PLANNER_IMAGE"],
        }
        self.accounts = {
            "core": os.environ["CORE_API_RUNTIME_SERVICE_ACCOUNT"],
            "planner": os.environ["GOAL_PLANNER_RUNTIME_SERVICE_ACCOUNT"],
        }
        self.allowed_uid = os.environ["ALLOWED_USER_ID"]
        if os.environ.get("GITHUB_REF") != "refs/heads/main" or not self.allowed_uid.strip():
            raise DeploymentError("Only main with an explicit Firebase access policy can deploy")
        if not re.fullmatch(r"[a-f0-9]{40}", self.sha) or len(self.tag) > 63:
            raise DeploymentError("Invalid deployment identity")
        for image in self.images.values():
            if not re.search(r"@sha256:[a-f0-9]{64}$", image):
                raise DeploymentError("Deploy images by digest, never by a mutable tag")
        self.before = {}
        self.candidates = {}
        self.staged = []
        self.promoting = False
        self.result = {"commit": self.sha, "images": self.images, "status": "preparing"}

    def cloud(self, *args):
        result = subprocess.run(
            [
                "gcloud",
                *args,
                f"--project={self.project}",
                f"--region={self.region}",
                "--quiet",
                "--format=json",
            ],
            capture_output=True,
            text=True,
            check=False,
            timeout=600,
        )
        if result.returncode:
            # Do not dump commands/env files or child output containing credentials.
            raise DeploymentError(f"gcloud {' '.join(args[:3])} failed")
        return json.loads(result.stdout) if result.stdout.strip() else {}

    def current(self):
        result = subprocess.run(
            ["git", "ls-remote", "origin", "refs/heads/main"],
            capture_output=True,
            text=True,
            check=True,
        )
        refs = result.stdout.split()
        return bool(refs) and refs[0] == self.sha

    def describe(self, kind):
        return self.cloud("run", "services", "describe", self.services[kind])

    def traffic(self, kind, revisions):
        target = ",".join(f"{name}={percent}" for name, percent in revisions.items())
        self.cloud(
            "run",
            "services",
            "update-traffic",
            self.services[kind],
            f"--to-revisions={target}",
        )
        if serving_traffic(self.describe(kind)) != revisions:
            raise DeploymentError(f"Traffic verification failed: {kind}")

    def stage(self, kind, environment, *extra):
        suffix = self.tag
        revision = f"{self.services[kind]}-{suffix}"
        if len(revision) > 63 or any("|" in v or "\n" in v for v in environment.values()):
            raise DeploymentError("Invalid revision name or environment value")
        self.staged.append(kind)  # A failed CLI call may still have created its tag.
        self.cloud(
            "run",
            "deploy",
            self.services[kind],
            f"--image={self.images[kind]}",
            f"--service-account={self.accounts[kind]}",
            "--no-traffic",
            f"--tag={self.tag}",
            f"--revision-suffix={suffix}",
            "--min-instances=0",
            "--max-instances=1",
            "--update-env-vars=^|^" + "|".join(f"{k}={v}" for k, v in environment.items()),
            *extra,
        )
        service = self.describe(kind)
        if service["status"]["latestReadyRevisionName"] != revision:
            raise DeploymentError(f"Candidate did not become ready: {kind}")
        tagged = next(item for item in service["status"]["traffic"] if item.get("tag") == self.tag)
        if (
            tagged["revisionName"] != revision
            or serving_traffic(service) != self.before[kind]["traffic"]
        ):
            raise DeploymentError(f"Staging changed serving traffic: {kind}")
        self.candidates[kind] = {"revision": revision, "url": tagged["url"]}
        self.result["candidates"] = self.candidates

    def check_http(self, url):
        with httpx.Client(timeout=30) as client:
            if client.get(url + "/health").status_code != 200:
                raise DeploymentError("Core candidate health check failed")
            for headers in ({}, {"Authorization": "Bearer invalid-deployment-check"}):
                if client.get(url + "/api/v1/goals", headers=headers).status_code != 401:
                    raise DeploymentError("Core must reject missing/invalid Firebase tokens")

    def check_pair(self, core_env):
        self.check_http(self.candidates["core"]["url"])
        # A private Job uses the candidate Core image and its runtime identity. Only
        # that process substitutes test authentication; serving revisions never do.
        script = Path(__file__).parents[1] / "check-deployment.py"
        job_env = {
            **core_env,
            "CORE_CANDIDATE_URL": self.candidates["core"]["url"],
            "CHECK_SCRIPT": base64.b64encode(script.read_bytes()).decode(),
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "job-env.json"
            path.write_text(json.dumps(job_env))
            self.cloud(
                "run",
                "jobs",
                "deploy",
                "lifeagent-deploy-check",
                f"--image={self.images['core']}",
                f"--service-account={self.accounts['core']}",
                "--tasks=1",
                "--parallelism=1",
                "--max-retries=0",
                "--task-timeout=240s",
                "--command=uv",
                "--args=^|^run|--no-sync|python|-c|import base64,os;"
                "exec(compile(base64.b64decode(os.environ['CHECK_SCRIPT']),"
                "'check-deployment.py','exec'))",
                f"--env-vars-file={path}",
            )
        self.result["verification_job"] = "lifeagent-deploy-check"
        execution = self.cloud("run", "jobs", "execute", "lifeagent-deploy-check", "--wait")
        self.result["verification_job"] = execution.get("metadata", {}).get(
            "name", "lifeagent-deploy-check"
        )

    def remove_tag(self, kind):
        self.cloud(
            "run",
            "services",
            "update-traffic",
            self.services[kind],
            f"--remove-tags={self.tag}",
        )

    def run(self):
        rollback_ok = True
        try:
            if not self.current():
                self.result["status"] = "skipped-outdated"
                return
            for kind in self.services:
                service = self.describe(kind)
                self.before[kind] = {
                    "url": service["status"]["url"],
                    "traffic": serving_traffic(service),
                }
            self.result["previous"] = self.before
            providers = {
                key: "openai"
                for key in (
                    "GOAL_PLANNER_PROVIDER",
                    "PROGRESS_PARSER_PROVIDER",
                    "ADVISOR_PROVIDER",
                    "REPLANNER_PROVIDER",
                    "COACH_PROVIDER",
                )
            }
            self.stage(
                "planner",
                providers,
                "--no-allow-unauthenticated",
                "--port=8001",
                f"--update-secrets=OPENAI_API_KEY={os.environ['OPENAI_SECRET_NAME']}:latest",
            )
            self.cloud(
                "run",
                "services",
                "add-iam-policy-binding",
                self.services["planner"],
                f"--member=serviceAccount:{self.accounts['core']}",
                "--role=roles/run.invoker",
            )
            policy = self.cloud("run", "services", "get-iam-policy", self.services["planner"])
            if any(
                member in ("allUsers", "allAuthenticatedUsers")
                for binding in policy.get("bindings", [])
                for member in binding.get("members", [])
            ):
                raise DeploymentError("Planner must remain private")
            core_env = dict(
                AUTH_BACKEND="firebase",
                ALLOWED_USER_ID=self.allowed_uid,
                FIREBASE_PROJECT_ID=self.project,
                FIRESTORE_PROJECT_ID=self.project,
                FIRESTORE_DATABASE_ID="(default)",
                MAINTENANCE_MODE="false",
                GOAL_PLANNER_BACKEND="http",
                GOAL_PLANNER_URL=self.candidates["planner"]["url"],
                GOAL_PLANNER_ID_TOKEN_AUDIENCE=self.before["planner"]["url"],
                GOAL_PLANNER_TIMEOUT_SECONDS="25",
                PROGRESS_PARSER_BACKEND="http",
                ADVISOR_BACKEND="http",
                REPLANNER_BACKEND="http",
                COACH_BACKEND="http",
                FEEDBACK_GITHUB_REPOSITORY=os.getenv("FEEDBACK_GITHUB_REPOSITORY")
                or "Yoshiki-Takahashi/life_agent",
            )
            feedback_secret = os.getenv("FEEDBACK_GITHUB_SECRET_NAME")
            feedback_secret_option = (
                f"--update-secrets=FEEDBACK_GITHUB_TOKEN={feedback_secret}:latest"
                if feedback_secret
                else "--remove-secrets=FEEDBACK_GITHUB_TOKEN,OPENAI_API_KEY,DB_PASSWORD"
            )
            self.stage(
                "core",
                core_env,
                "--allow-unauthenticated",
                "--port=8000",
                "--clear-cloudsql-instances",
                feedback_secret_option,
                *(["--remove-secrets=OPENAI_API_KEY,DB_PASSWORD"] if feedback_secret else []),
            )
            self.check_pair(core_env)
            if not self.current():
                self.result["status"] = "skipped-outdated"
                return
            self.promoting = True
            # New Core is already pinned to its tested Planner tag, so it never
            # depends on the timing of the Planner's default-URL traffic switch.
            for kind in ("core", "planner"):
                self.traffic(kind, {self.candidates[kind]["revision"]: 100})
            self.check_http(self.before["core"]["url"])
            self.result["status"] = "deployed"
        except BaseException:
            self.result["status"] = "failed-before-cutover"
            if self.staged:
                for kind in ("core", "planner"):
                    try:
                        self.traffic(kind, self.before[kind]["traffic"])
                    except Exception:
                        rollback_ok = False
                        self.result.setdefault("rollback_errors", []).append(kind)
                if not rollback_ok:
                    self.result["status"] = "rollback-failed"
                elif self.promoting:
                    self.result["status"] = "rolled-back"
            raise
        finally:
            # Successful Core revisions need their Planner tag even after a later
            # rollback. Keep that tag; remove this run's unused tags on failure.
            for kind in self.staged:
                keep = kind == "planner" and (
                    self.result["status"] == "deployed" or not rollback_ok
                )
                if not keep:
                    try:
                        self.remove_tag(kind)
                    except Exception:
                        self.result.setdefault("cleanup_warnings", []).append(kind)
            Path("deployment-result.json").write_text(json.dumps(self.result, indent=2))
            summary = os.getenv("GITHUB_STEP_SUMMARY")
            if summary:
                with Path(summary).open("a") as file:
                    file.write(
                        "## Backend deployment\n\n```json\n"
                        + json.dumps(self.result, indent=2)
                        + "\n```\n"
                    )
            print(f"Deployment {self.sha}: {self.result['status']}")


def interrupted(signum, frame):
    raise DeploymentError("Deployment interrupted; attempting traffic recovery")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, interrupted)
    Deployment().run()
