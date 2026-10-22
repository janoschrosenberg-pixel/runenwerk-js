#!/bin/sh
# baut runenjs.jar (braucht ein JDK ab 17)
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac --release 17 -encoding UTF-8 -d out $(find src -name '*.java')
cp -R resources/. out/
jar --create --file runenjs.jar --main-class runen.Main -C out .
echo "fertig: runenjs.jar  (starten: java -jar runenjs.jar [ARBEITSORDNER])"
