@echo off
rem baut runenjs.jar (braucht ein JDK ab 17)
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > out\quellen.txt
javac --release 17 -encoding UTF-8 -d out @out\quellen.txt || exit /b 1
del out\quellen.txt
xcopy /e /i /q resources out >nul
jar --create --file runenjs.jar --main-class runen.Main -C out . || exit /b 1
echo fertig: runenjs.jar  (starten: run.bat [ARBEITSORDNER])
