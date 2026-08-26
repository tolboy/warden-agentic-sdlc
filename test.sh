#!/usr/bin/env sh
set -e
cd "$(dirname "$0")"
./build.sh
java -cp out/test-classes dev.warden.testing.TestMain
