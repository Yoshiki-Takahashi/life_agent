#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
core_api_dir="${repository_root}/backend/services/core-api"
if [[ -z "${FIRESTORE_EMULATOR_HOST:-}" ]]; then
    exec uv run --no-project python "${repository_root}/scripts/run-with-firestore.py" -- bash "$0" "$@"
fi
core_api_pid=""
android_reverse=""
output_dir="${W7_OUTPUT_DIR:-${repository_root}/android/app/build/weekend7}"

if [[ "${1:-}" != "" && "${1:-}" != "--android" ]]; then
    echo "Usage: $0 [--android]" >&2
    exit 2
fi
cd "${core_api_dir}"
uv run python - <<'PY'
import socket
for port in (18007,):
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', port))
PY
cleanup() {
    if [[ -n "${android_reverse}" ]]; then
        "${ANDROID_HOME}/platform-tools/adb" reverse --remove tcp:18007 || true
    fi
    if [[ -n "${core_api_pid}" ]]; then
        kill "${core_api_pid}" 2>/dev/null || true
        wait "${core_api_pid}" 2>/dev/null || true
    fi
}
trap cleanup EXIT

export AUTH_BACKEND=fake GOAL_PLANNER_BACKEND=fake PROGRESS_PARSER_BACKEND=fake ADVISOR_BACKEND=fake REPLANNER_BACKEND=fake COACH_BACKEND=fake
uv run uvicorn life_agent_core.main:app --host 127.0.0.1 --port 18007 &
core_api_pid=$!
for _ in {1..30}; do
    kill -0 "${core_api_pid}"
    if curl --fail --silent http://127.0.0.1:18007/health >/dev/null; then break; fi
    sleep 1
done
curl --fail --silent http://127.0.0.1:18007/health >/dev/null
W7_API_URL=http://127.0.0.1:18007 uv run pytest "${repository_root}/tests/e2e/test_progress_concurrency.py"

if [[ "${1:-}" == "--android" ]]; then
    : "${ANDROID_HOME:?Set ANDROID_HOME and start an emulator}"
    adb="${ANDROID_HOME}/platform-tools/adb"
    "${adb}" get-state
    "${adb}" reverse tcp:18007 tcp:18007
    android_reverse="true"
    "${adb}" shell input keyevent KEYCODE_WAKEUP
    "${adb}" shell wm dismiss-keyguard
    cd "${repository_root}/android"
    ./gradlew -Plifeagent.useCloudFirebase=false testDebugUnitTest assembleDebug assembleDebugAndroidTest
    "${adb}" install -r app/build/outputs/apk/debug/app-debug.apk
    "${adb}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    mkdir -p "${output_dir}"
    instrumentation_args=(-e networkE2E true)
    if [[ -n "${W7_ANDROID_TEST_CLASS:-}" ]]; then
        instrumentation_args+=(-e class "${W7_ANDROID_TEST_CLASS}")
    fi
    "${adb}" shell am instrument -w "${instrumentation_args[@]}" \
        com.yoshiki.lifeagent.test/androidx.test.runner.AndroidJUnitRunner | tee "${output_dir}/instrumentation.txt"
    rg -q 'OK \([0-9]+ tests?\)' "${output_dir}/instrumentation.txt"
    "${adb}" reverse --remove tcp:18007
    android_reverse=""
fi
