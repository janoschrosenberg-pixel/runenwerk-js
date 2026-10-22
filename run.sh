#!/bin/sh
cd "$(dirname "$0")"
[ -f runenjs.jar ] || sh build.sh
exec java -Xss8m -jar runenjs.jar "$@"
