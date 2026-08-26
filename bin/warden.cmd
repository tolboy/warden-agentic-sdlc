@echo off
REM Launcher. Conductor and Orca call this; neither needs to know it is Java.
setlocal
set WARDEN_HOME=%~dp0..
java -cp "%WARDEN_HOME%\out\classes" dev.warden.Main %*
