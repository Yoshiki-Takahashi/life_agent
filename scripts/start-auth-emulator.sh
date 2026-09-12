#!/bin/sh
set -eu

if ! command -v firebase >/dev/null 2>&1; then
  echo "Firebase CLIが必要です: https://firebase.google.com/docs/cli#install_the_firebase_cli"
  exit 1
fi

cd "$(dirname "$0")/.."
exec firebase emulators:start --only auth --project life-agent-local
