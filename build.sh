#!/bin/sh
# baut runenjs.jar (braucht ein JDK ab 17); der Java Language Server (Eclipse jdtls) kommt mit in die Jar.
# Ohne jdtls bauen: OHNE_JDTLS=1 sh build.sh
set -e
cd "$(dirname "$0")"
JDTLS_URL=https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz
if [ -z "$OHNE_JDTLS" ] && [ ! -d vendor/jdtls/plugins ]; then
  echo "lade jdtls (einmalig, ca. 50 MB) ..."
  mkdir -p vendor/jdtls
  curl -sSL "$JDTLS_URL" | tar xz -C vendor/jdtls
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
  # Version = Name des Launchers; aendert sie sich, entpackt der Editor neu
  ls vendor/jdtls/plugins | grep '^org.eclipse.equinox.launcher_' > out/jdtls/VERSION
fi
jar --create --file runenjs.jar --main-class runen.Main -C out .
echo "fertig: runenjs.jar  (starten: java -jar runenjs.jar [ARBEITSORDNER])"
