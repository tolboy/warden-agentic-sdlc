@echo off
REM Build with javac only. No Gradle, no Maven, no network.
setlocal
cd /d "%~dp0"
if "%WARDEN_JAVA_RELEASE%"=="" set WARDEN_JAVA_RELEASE=21
REM Delete only what this script produces. `out` is gitignored, which makes it the natural
REM place to park a live run's configuration or a reviewer's saved reproductions, and
REM `rmdir /s /q out` destroyed all of it on the next build, unrecoverably, because nothing
REM there is tracked. Measured: a review's probe scripts and a live run's config directory
REM were both lost to a routine test.cmd.
if exist out\classes rmdir /s /q out\classes
if exist out\test-classes rmdir /s /q out\test-classes
if not exist out mkdir out
mkdir out\classes
mkdir out\test-classes
dir /s /b src\main\java\*.java > out\sources.txt
javac --release %WARDEN_JAVA_RELEASE% -d out\classes @out\sources.txt || exit /b 1
dir /s /b src\test\java\*.java > out\test-sources.txt
REM Compile tests and production sources together. On JDK 25 for Windows, javac can fail to
REM rediscover freshly emitted classes from a relative classpath during the same batch run.
REM This keeps the production output clean while making the offline test build deterministic.
javac --release %WARDEN_JAVA_RELEASE% -d out\test-classes @out\sources.txt @out\test-sources.txt || exit /b 1
echo built (release %WARDEN_JAVA_RELEASE%)
