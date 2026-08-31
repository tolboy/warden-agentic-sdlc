@echo off
REM A complete Warden gate cycle that spends nothing: no vendor, no credential, no network.
REM
REM It builds a throwaway Git repository from examples\demo\fixture, then runs the machine
REM half of the loop twice - once against a tree that does not satisfy the contract, once
REM against a tree that does - and prints the evidence both runs left behind.
REM
REM Usage:  examples\demo\run.cmd [--keep]
setlocal enabledelayedexpansion
set "DEMO=%~dp0"
set "DEMO=%DEMO:~0,-1%"
for %%I in ("%DEMO%\..\..") do set "WARDEN_HOME=%%~fI"
set "WARDEN=%WARDEN_HOME%\bin\warden.cmd"
set "KEEP="
if /I "%~1"=="--keep" set "KEEP=1"

if not exist "%WARDEN_HOME%\out\classes" (
  echo.
  echo == building warden
  call "%WARDEN_HOME%\build.cmd" || exit /b 1
)

set "WORK=%TEMP%\warden-demo-%RANDOM%%RANDOM%"
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%" || exit /b 1

xcopy /E /I /Q /H /Y "%DEMO%\fixture" "%WORK%" >nul || exit /b 1
pushd "%WORK%" || exit /b 1

REM Warden pins a base commit and judges the working tree against it, so the fixture needs a
REM real repository - not because Warden wants to commit anything (it never does), but
REM because "what changed" has to mean something.
git init -q || goto :fail
git checkout -q -b main 2>nul
git add -A || goto :fail
git -c user.email=demo@example.invalid -c user.name="Warden demo" commit -q -m "baseline: contract and checker, no result.txt yet" || goto :fail

echo.
echo == 1. validate - is the contract even coherent? (no run directory, no cost)
call "%WARDEN%" validate create-result || goto :fail

echo.
echo == 2. gates on a tree that does not satisfy the contract
REM result.txt is absent, so the acceptance command must fail. A demo that could not show
REM the red half would be proving nothing: a gate that has never refused is not known to work.
call "%WARDEN%" gates create-result --run-id demo-red
if not errorlevel 1 (
  echo UNEXPECTED: gates passed with result.txt absent 1>&2
  goto :fail
)
echo   ^(exit 1 above is the point: gates refused, and wrote why^)

echo.
echo == 3. do the work by hand - this is the line an implementer role would have written
^> result.txt echo WARDEN_OK

echo.
echo == 4. gates on the same tree, now satisfying the contract
call "%WARDEN%" gates create-result --run-id demo-green || goto :fail

echo.
echo == 5. what the two runs left behind
dir /s /b .warden\runs

echo.
echo == 6. the machine report the green run wrote
REM `warden report ^<run-id^>` joins a whole run - every stage, every vendor call, the human
REM decision. This demo never ran a vendor, so there is no run summary to join; the gate's
REM own report is the evidence that exists, and Warden does not invent the rest.
type .warden\runs\demo-green\machine-gate.json

echo.
echo == 7. warden ledger - every run in this project, joined
call "%WARDEN%" ledger || goto :fail

echo.
echo == done
echo Two runs, both recorded: demo-red refused, demo-green passed.
echo No vendor was called, so no money was spent and nothing left this machine.
popd
if defined KEEP (
  echo Scratch repository kept at: %WORK%
) else (
  rmdir /s /q "%WORK%"
  echo Scratch repository removed. Re-run with --keep to poke at it.
)
exit /b 0

:fail
popd
if not defined KEEP rmdir /s /q "%WORK%"
echo demo failed 1>&2
exit /b 1
