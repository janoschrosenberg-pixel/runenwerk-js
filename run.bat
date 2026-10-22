@echo off
cd /d "%~dp0"
if not exist runenjs.jar call build.bat
start "" javaw -Xss8m -jar runenjs.jar %*
