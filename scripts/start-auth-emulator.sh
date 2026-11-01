#!/bin/sh
set -eu

if ! command -v firebase >/dev/null 2>&1; then
  echo "Firebase CLIが必要です: https://firebase.google.com/docs/cli#install_the_firebase_cli"
  exit 1
fi

cd "$(dirname "$0")/.."
# Emulator users are local test data. Keep exports outside Git and private to this user.
umask 077
state_dir=".secrets/firebase-auth-emulator"
mkdir -p "$state_dir"
if [ -f "$state_dir/firebase-export-metadata.json" ]; then
  exec firebase emulators:start --only auth --project life-agent-local \
    --import "$state_dir" --export-on-exit "$state_dir"
fi
exec firebase emulators:start --only auth --project life-agent-local \
  --export-on-exit "$state_dir"
