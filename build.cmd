@echo off
REM Build with javac only. No Gradle, no Maven, no network.
setlocal
cd /d "%~dp0"
if "%WARDEN_JAVA_RELEASE%"=="" set WARDEN_JAVA_RELEASE=21
if exist out rmdir /s /q out
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
