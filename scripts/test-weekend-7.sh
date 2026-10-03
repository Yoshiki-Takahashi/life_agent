#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
core_api_dir="${repository_root}/backend/services/core-api"
compose_file="${repository_root}/backend/compose.weekend7.yaml"
core_api_pid=""
output_dir="${W7_OUTPUT_DIR:-${repository_root}/android/app/build/weekend7}"

if [[ "${1:-}" != "" && "${1:-}" != "--android" ]]; then
    echo "Usage: $0 [--android]" >&2
    exit 2
fi
cd "${core_api_dir}"
uv run python - <<'PY'
import socket
for port in (18007, 15437):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))
PY
cleanup() {
    if [[ -n "${core_api_pid}" ]]; then
        kill "${core_api_pid}" 2>/dev/null || true
        wait "${core_api_pid}" 2>/dev/null || true
    fi
    docker compose -p lifeagent-weekend7-e2e -f "${compose_file}" down --volumes
}
trap cleanup EXIT

docker compose -p lifeagent-weekend7-e2e -f "${compose_file}" up -d --wait
export DATABASE_URL=postgresql+psycopg://lifeagent:lifeagent_e2e@127.0.0.1:15437/lifeagent
export AUTH_BACKEND=fake GOAL_PLANNER_BACKEND=fake PROGRESS_PARSER_BACKEND=fake ADVISOR_BACKEND=fake
uv run alembic upgrade head
uv run uvicorn life_agent_core.main:app --host 127.0.0.1 --port 18007 &
core_api_pid=$!
for _ in {1..30}; do
    kill -0 "${core_api_pid}"
    if curl --fail --silent http://127.0.0.1:18007/health >/dev/null; then break; fi
    sleep 1
done
curl --fail --silent http://127.0.0.1:18007/health >/dev/null
W7_API_URL=http://127.0.0.1:18007 uv run pytest "${repository_root}/tests/e2e/test_weekend_7.py"

if [[ "${1:-}" == "--android" ]]; then
    : "${ANDROID_HOME:?Set ANDROID_HOME and start an emulator}"
    adb="${ANDROID_HOME}/platform-tools/adb"
    "${adb}" get-state
    "${adb}" reverse tcp:18007 tcp:18007
    "${adb}" shell input keyevent KEYCODE_WAKEUP
    "${adb}" shell wm dismiss-keyguard
    cd "${repository_root}/android"
    ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
    "${adb}" install -r app/build/outputs/apk/debug/app-debug.apk
    "${adb}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    mkdir -p "${output_dir}"
    "${adb}" shell am instrument -w -e networkE2E true \
        com.yoshiki.lifeagent.test/androidx.test.runner.AndroidJUnitRunner | tee "${output_dir}/instrumentation.txt"
    rg -q 'OK \([0-9]+ tests?\)' "${output_dir}/instrumentation.txt"
    "${adb}" pull /sdcard/Android/data/com.yoshiki.lifeagent/files/ "${output_dir}/"
    "${adb}" reverse --remove tcp:18007
fi
