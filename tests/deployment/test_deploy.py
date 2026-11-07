"""External deployment boundaries: stage, verify, promote, and recover on failure."""

import importlib.util
import json
import subprocess
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

spec = importlib.util.spec_from_file_location(
    "deploy_dev", Path(__file__).parents[2] / "scripts/gcp/deploy-dev.py"
)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

SHA = "a" * 40
DIGEST = "@sha256:" + "b" * 64


@pytest.fixture
def cloud(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    for key, value in dict(
        PROJECT_ID="test-project",
        REGION="asia-northeast1",
        GITHUB_REF="refs/heads/main",
        GITHUB_SHA=SHA,
        GITHUB_RUN_ID="123",
        GITHUB_RUN_ATTEMPT="1",
        CORE_API_SERVICE="core",
        GOAL_PLANNER_SERVICE="planner",
        CORE_API_IMAGE="registry/core" + DIGEST,
        GOAL_PLANNER_IMAGE="registry/planner" + DIGEST,
        CORE_API_RUNTIME_SERVICE_ACCOUNT="core@test",
        GOAL_PLANNER_RUNTIME_SERVICE_ACCOUNT="planner@test",
        ALLOWED_USER_ID="allowed-user",
        OPENAI_SECRET_NAME="provider-key",
        GITHUB_STEP_SUMMARY=str(tmp_path / "summary.md"),
    ).items():
        monkeypatch.setenv(key, value)
    state = FakeCloud()
    monkeypatch.setattr(subprocess, "run", state.run)
    real_client = httpx.Client
    monkeypatch.setattr(
        httpx,
        "Client",
        lambda **kwargs: real_client(**kwargs, transport=httpx.MockTransport(state.request)),
    )
    return state


class FakeCloud:
    def __init__(self):
        self.services = {
            kind: dict(
                status=dict(
                    url=f"https://{kind}.run.app",
                    latestReadyRevisionName=f"{kind}-old",
                    traffic=[dict(revisionName=f"{kind}-old", percent=100)],
                )
            )
            for kind in ("core", "planner")
        }
        self.calls = []
        self.head_calls = 0
        self.outdated_at = None
        self.fail_job = False
        self.interrupt_job = False
        self.fail_promotion = None
        self.fail_rollback = None
        self.bad_http = False
        self.bad_serving_http = False
        self.stage_changes_traffic = False
        self.public_planner = False
        self.checked = False
        self.environment = {}

    def request(self, request):
        if request.url.path == "/health":
            bad = self.bad_http or (
                self.bad_serving_http and self.checked and request.url.host == "core.run.app"
            )
            return httpx.Response(503 if bad else 200)
        return httpx.Response(401)

    def run(self, command, **kwargs):
        if command[:2] == ["git", "ls-remote"]:
            self.head_calls += 1
            current = self.head_calls != self.outdated_at
            return SimpleNamespace(stdout=(SHA if current else "c" * 40) + "\trefs/heads/main\n")
        assert command[0] == "gcloud"
        args = command[1:]
        self.calls.append(args)
        output = {}
        code = 0
        if args[:3] == ["run", "services", "describe"]:
            output = self.services[args[3]]
        elif args[:2] == ["run", "deploy"]:
            kind = args[2]
            assert "--no-traffic" in args
            tag = next(a.split("=", 1)[1] for a in args if a.startswith("--tag="))
            suffix = next(a.split("=", 1)[1] for a in args if a.startswith("--revision-suffix="))
            revision = f"{kind}-{suffix}"
            service = self.services[kind]
            service["status"]["latestReadyRevisionName"] = revision
            service["status"]["traffic"].append(
                dict(
                    tag=tag,
                    revisionName=revision,
                    url=f"https://{tag}---{kind}.run.app",
                )
            )
            self.environment[kind] = next(a for a in args if a.startswith("--update-env-vars="))
            if self.stage_changes_traffic:
                service["status"]["traffic"] = [
                    {"revisionName": revision, "percent": 100},
                    service["status"]["traffic"][-1],
                ]
        elif args[:3] == ["run", "services", "get-iam-policy"]:
            output = dict(bindings=[dict(members=["allUsers"])]) if self.public_planner else {}
        elif args[:3] == ["run", "services", "update-traffic"]:
            kind = args[3]
            option = next(a for a in args if a.startswith(("--to-revisions=", "--remove-tags=")))
            if option.startswith("--to-revisions="):
                traffic = dict(
                    (revision, int(percent))
                    for revision, percent in (
                        v.split("=") for v in option.split("=", 1)[1].split(",")
                    )
                )
                promotion = f"{kind}-old" not in traffic
                if promotion:
                    assert self.checked, "Never promote unverified candidates"
                if (promotion and self.fail_promotion == kind) or (
                    not promotion and self.fail_rollback == kind
                ):
                    return SimpleNamespace(returncode=1, stdout="", stderr="private detail")
                tags = [item for item in self.services[kind]["status"]["traffic"] if "tag" in item]
                self.services[kind]["status"]["traffic"] = tags + [
                    dict(revisionName=name, percent=percent) for name, percent in traffic.items()
                ]
            else:
                tag = option.split("=", 1)[1]
                self.services[kind]["status"]["traffic"] = [
                    item
                    for item in self.services[kind]["status"]["traffic"]
                    if item.get("tag") != tag
                ]
        elif args[:3] == ["run", "jobs", "deploy"]:
            path = next(a.split("=", 1)[1] for a in args if a.startswith("--env-vars-file="))
            self.job_environment = json.loads(Path(path).read_text())
        elif args[:3] == ["run", "jobs", "execute"]:
            if self.interrupt_job:
                raise KeyboardInterrupt
            code = int(self.fail_job)
            self.checked = not self.fail_job
        return SimpleNamespace(returncode=code, stdout=json.dumps(output), stderr="")


def result():
    return json.loads(Path("deployment-result.json").read_text())


def test_verified_candidate_pair_uses_pinned_digest_identity_and_audience(cloud):
    deployment = deploy.Deployment()
    deployment.run()
    assert result()["status"] == "deployed"
    assert deploy.serving_traffic(cloud.services["core"]) == {f"core-{deployment.tag}": 100}
    assert deploy.serving_traffic(cloud.services["planner"]) == {f"planner-{deployment.tag}": 100}
    core_env = cloud.environment["core"]
    assert f"GOAL_PLANNER_URL=https://{deployment.tag}---planner.run.app" in core_env
    assert "GOAL_PLANNER_ID_TOKEN_AUDIENCE=https://planner.run.app" in core_env
    assert "AUTH_BACKEND=firebase|ALLOWED_USER_ID=allowed-user" in core_env
    planner_deploy = next(args for args in cloud.calls if args[:3] == ["run", "deploy", "planner"])
    assert "--no-allow-unauthenticated" in planner_deploy
    assert "--update-secrets=OPENAI_API_KEY=provider-key:latest" in planner_deploy
    assert "allowed-user" not in Path("deployment-result.json").read_text()
    assert cloud.job_environment["GOAL_PLANNER_URL"].endswith("---planner.run.app")
    assert any(
        t.get("tag") == deployment.tag for t in cloud.services["planner"]["status"]["traffic"]
    )
    assert not any(
        t.get("tag") == deployment.tag for t in cloud.services["core"]["status"]["traffic"]
    )


@pytest.mark.parametrize("failure", ["job", "http", "planner-public"])
def test_failed_candidate_checks_keep_existing_traffic(cloud, failure):
    cloud.fail_job = failure == "job"
    cloud.bad_http = failure == "http"
    cloud.public_planner = failure == "planner-public"
    with pytest.raises(deploy.DeploymentError):
        deploy.Deployment().run()
    assert result()["status"] == "failed-before-cutover"
    for kind in ("core", "planner"):
        assert deploy.serving_traffic(cloud.services[kind]) == {f"{kind}-old": 100}
    for args in cloud.calls:
        for arg in args:
            if arg.startswith("--to-revisions="):
                assert "-old=100" in arg


@pytest.mark.parametrize("kind", ["core", "planner"])
def test_cutover_failure_restores_both_prior_traffic_maps(cloud, kind):
    cloud.fail_promotion = kind
    with pytest.raises(deploy.DeploymentError):
        deploy.Deployment().run()
    assert result()["status"] == "rolled-back"
    for service in ("core", "planner"):
        assert deploy.serving_traffic(cloud.services[service]) == {f"{service}-old": 100}


def test_rollback_failure_is_reported_and_keeps_the_required_planner_tag(cloud):
    cloud.fail_promotion = "planner"
    cloud.fail_rollback = "core"
    with pytest.raises(deploy.DeploymentError):
        deploy.Deployment().run()
    assert result()["status"] == "rollback-failed"
    assert any("tag" in item for item in cloud.services["planner"]["status"]["traffic"])


@pytest.mark.parametrize("phase", [1, 2])
def test_superseded_main_commit_is_never_promoted(cloud, phase):
    cloud.outdated_at = phase
    deploy.Deployment().run()
    assert result()["status"] == "skipped-outdated"
    for kind in ("core", "planner"):
        assert deploy.serving_traffic(cloud.services[kind]) == {f"{kind}-old": 100}


@pytest.mark.parametrize(
    "setting,value",
    [
        ("GITHUB_REF", "refs/heads/feature"),
        ("ALLOWED_USER_ID", ""),
        ("CORE_API_IMAGE", "registry/core:latest"),
    ],
)
def test_bad_deployment_settings_never_mutate_cloud(cloud, monkeypatch, setting, value):
    monkeypatch.setenv(setting, value)
    with pytest.raises(deploy.DeploymentError):
        deploy.Deployment().run()
    assert cloud.calls == []


def test_post_cutover_http_failure_restores_the_original_pair(cloud):
    cloud.bad_serving_http = True
    with pytest.raises(deploy.DeploymentError):
        deploy.Deployment().run()
    assert result()["status"] == "rolled-back"
    for kind in ("core", "planner"):
        assert deploy.serving_traffic(cloud.services[kind]) == {f"{kind}-old": 100}


def test_unexpected_stage_traffic_change_is_detected_and_restored(cloud):
    cloud.stage_changes_traffic = True
    with pytest.raises(deploy.DeploymentError):
        deploy.Deployment().run()
    assert not cloud.checked
    assert deploy.serving_traffic(cloud.services["planner"]) == {"planner-old": 100}


def test_interruption_recovers_without_removing_a_live_dependency(cloud):
    cloud.interrupt_job = True
    with pytest.raises(KeyboardInterrupt):
        deploy.Deployment().run()
    assert result()["status"] == "failed-before-cutover"
    for kind in ("core", "planner"):
        assert deploy.serving_traffic(cloud.services[kind]) == {f"{kind}-old": 100}


def test_rollback_preserves_an_existing_split_instead_of_using_latest(cloud):
    cloud.services["core"]["status"]["traffic"] = [
        {"revisionName": "core-old", "percent": 70},
        {"revisionName": "core-older", "percent": 30},
    ]
    cloud.fail_promotion = "planner"
    with pytest.raises(deploy.DeploymentError):
        deploy.Deployment().run()
    assert deploy.serving_traffic(cloud.services["core"]) == {"core-old": 70, "core-older": 30}
