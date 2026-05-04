@echo off
REM =============================================================================
REM  run-build.bat - Launch the reproducible croc-app build on Windows
REM  Run this from the folder containing Dockerfile + build.sh
REM =============================================================================

setlocal DisableDelayedExpansion

REM EDIT THESE
set "KEYSTORE_FILE=%~dp0key.jks"
set "KEY_ALIAS=ALIAS"
set /p KEYSTORE_PASS=<kspass.txt
set /p KEY_PASS=<kpass.txt
set "REPO_BRANCH=master"
REM  Output folder on Windows where the signed APK lands:
set "OUTPUT_DIR=%~dp0output"
REM END CONFIG

if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

echo [*] Building Docker image...
docker build -t croc-app-builder .
if errorlevel 1 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo [*] Running reproducible build inside container...
docker run --rm ^
    -e "REPO_BRANCH=%REPO_BRANCH%" ^
    -e "KEYSTORE_PASS=%KEYSTORE_PASS%" ^
    -e "KEY_ALIAS=%KEY_ALIAS%" ^
    -e "KEY_PASS=%KEY_PASS%" ^
    -v "%KEYSTORE_FILE%:/secrets/release.jks:ro" ^
    -v "%OUTPUT_DIR%:/output" ^
    croc-app-builder ^
    bash /build/build.sh

if errorlevel 1 (
    echo ERROR: Build or signing failed. Check output above.
    exit /b 1
)

echo.
echo [*] Done! Signed APK is in: %OUTPUT_DIR%
endlocal
