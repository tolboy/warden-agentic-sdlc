@echo off
setlocal
cd /d "%~dp0"
call build.cmd || exit /b 1
java -cp out\test-classes dev.warden.testing.TestMain
