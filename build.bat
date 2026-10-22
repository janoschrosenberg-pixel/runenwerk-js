@echo off
rem baut runenjs.jar (braucht ein JDK ab 17); jdtls 1.61.0 (laeuft mit Java 21) kommt mit in die Jar (ohne: set OHNE_JDTLS=1)
cd /d "%~dp0"
if not defined OHNE_JDTLS if not exist vendor\jdtls\1.61.0 (
  echo lade jdtls ^(einmalig, ca. 50 MB^) ...
  if exist vendor\jdtls rmdir /s /q vendor\jdtls
  mkdir vendor\jdtls
  curl -sSL -o vendor\jdtls.tar.gz https://download.eclipse.org/jdtls/milestones/1.61.0/jdt-language-server-1.61.0-202609031315.tar.gz || exit /b 1
  tar xzf vendor\jdtls.tar.gz -C vendor\jdtls || exit /b 1
  del vendor\jdtls.tar.gz
  type nul > vendor\jdtls\1.61.0
)
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > out\quellen.txt
javac --release 17 -encoding UTF-8 -d out @out\quellen.txt || exit /b 1
del out\quellen.txt
xcopy /e /i /q resources out >nul
if not defined OHNE_JDTLS (
  for %%d in (plugins features config_win config_mac config_mac_arm config_linux config_linux_arm) do if exist vendor\jdtls\%%d xcopy /e /i /q vendor\jdtls\%%d out\jdtls\%%d >nul
  dir /b vendor\jdtls\plugins\org.eclipse.jdt.ls.core_* > out\jdtls\VERSION
)
jar --create --file runenjs.jar --main-class runen.Main -C out . || exit /b 1
echo fertig: runenjs.jar  (starten: run.bat [ARBEITSORDNER])
