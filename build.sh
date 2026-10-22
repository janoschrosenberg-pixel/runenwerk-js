#!/bin/sh
# baut runenjs.jar (braucht ein JDK ab 17); der Java Language Server (Eclipse jdtls) kommt mit in die Jar.
# Ohne jdtls bauen: OHNE_JDTLS=1 sh build.sh
set -e
cd "$(dirname "$0")"
# feste Version: 1.61.0 ist die neueste, die mit Java 21 laeuft (ab 1.62 braucht jdtls Java 25)
JDTLS_URL=https://download.eclipse.org/jdtls/milestones/1.61.0/jdt-language-server-1.61.0-202609031315.tar.gz
if [ -z "$OHNE_JDTLS" ] && [ ! -f vendor/jdtls/1.61.0 ]; then
  echo "lade jdtls (einmalig, ca. 50 MB) ..."
  rm -rf vendor/jdtls
  mkdir -p vendor/jdtls
  curl -sSL "$JDTLS_URL" | tar xz -C vendor/jdtls
  touch vendor/jdtls/1.61.0
fi
rm -rf out
mkdir -p out
javac --release 17 -encoding UTF-8 -d out $(find src -name '*.java')
cp -R resources/. out/
if [ -z "$OHNE_JDTLS" ]; then
  mkdir -p out/jdtls
  for d in plugins features config_win config_mac config_mac_arm config_linux config_linux_arm; do
    [ -d "vendor/jdtls/$d" ] && cp -R "vendor/jdtls/$d" out/jdtls/
  done
  # Version = Name des jdtls-Kern-Plugins; aendert sie sich, entpackt der Editor neu
  ls vendor/jdtls/plugins | grep '^org.eclipse.jdt.ls.core_' > out/jdtls/VERSION
fi
jar --create --file runenjs.jar --main-class runen.Main -C out .
echo "fertig: runenjs.jar  (starten: java -jar runenjs.jar [ARBEITSORDNER])"
