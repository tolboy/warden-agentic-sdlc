#!/usr/bin/env sh
# Build with javac only. No Gradle, no Maven, no network — a tool that gates other people's
# work should be buildable offline from a bare JDK.
set -e
cd "$(dirname "$0")"
RELEASE="${WARDEN_JAVA_RELEASE:-21}"
# Only what this script produces. `out` is gitignored, which makes it the natural place to
# park a live run's configuration or a reviewer's saved reproductions — and `rm -rf out`
# destroyed all of it on the next build, unrecoverably, because nothing there is tracked.
rm -rf out/classes out/test-classes out/sources.txt out/test-sources.txt
mkdir -p out/classes out/test-classes
find src/main/java -name '*.java' > out/sources.txt
javac --release "$RELEASE" -d out/classes @out/sources.txt
find src/test/java -name '*.java' > out/test-sources.txt
javac --release "$RELEASE" -d out/test-classes @out/sources.txt @out/test-sources.txt
echo "built (release $RELEASE)"
