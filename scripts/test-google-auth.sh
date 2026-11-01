#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME and start an emulator}"
adb="${ANDROID_HOME}/platform-tools/adb"
compose_file="${repository_root}/backend/compose.weekend7.yaml"
api_pid=""
reverse=""
output_dir="${repository_root}/android/app/build/google-auth"
cd "${repository_root}/backend/services/core-api"
uv run python - <<'PY'
import socket
import urllib.request
for port in (18007, 15437):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))
with urllib.request.urlopen('http://127.0.0.1:9099/', timeout=5) as response:
    assert response.status == 200
PY
"${adb}" get-state
cleanup() {
    if [[ -n "${reverse}" ]]; then "${adb}" reverse --remove tcp:18007 || true; fi
    if [[ -n "${api_pid}" ]]; then
        kill "${api_pid}" 2>/dev/null || true
        wait "${api_pid}" 2>/dev/null || true
    fi
    docker compose -p lifeagent-google-auth-e2e -f "${compose_file}" down --volumes
}
trap cleanup EXIT
docker compose -p lifeagent-google-auth-e2e -f "${compose_file}" up -d --wait
export DATABASE_URL=postgresql+psycopg://lifeagent:lifeagent_e2e@127.0.0.1:15437/lifeagent
export AUTH_BACKEND=firebase FIREBASE_PROJECT_ID=life-agent-local FIREBASE_AUTH_EMULATOR_HOST=127.0.0.1:9099
export GOAL_PLANNER_BACKEND=fake PROGRESS_PARSER_BACKEND=fake ADVISOR_BACKEND=fake REPLANNER_BACKEND=fake COACH_BACKEND=fake
uv run alembic upgrade head
uv run uvicorn life_agent_core.main:app --host 127.0.0.1 --port 18007 &
api_pid=$!
for _ in {1..30}; do
    kill -0 "${api_pid}"
    if curl --fail --silent http://127.0.0.1:18007/health >/dev/null; then break; fi
    sleep 1
done
curl --fail --silent http://127.0.0.1:18007/health >/dev/null
"${adb}" reverse tcp:18007 tcp:18007
reverse=true
"${adb}" shell input keyevent KEYCODE_WAKEUP
"${adb}" shell wm dismiss-keyguard
cd "${repository_root}/android"
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
"${adb}" install -r app/build/outputs/apk/debug/app-debug.apk
"${adb}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [[ "$("${adb}" shell getprop ro.build.version.sdk | tr -d '\r')" -ge 37 ]]; then
    "${adb}" shell pm grant com.yoshiki.lifeagent android.permission.ACCESS_LOCAL_NETWORK
fi
mkdir -p "${output_dir}"
"${adb}" shell am instrument -w -e authEmulator true -e class com.yoshiki.lifeagent.GoogleAuthFlowTest \
    com.yoshiki.lifeagent.test/androidx.test.runner.AndroidJUnitRunner | tee "${output_dir}/instrumentation.txt"
rg -q 'OK \([0-9]+ tests?\)' "${output_dir}/instrumentation.txt"
