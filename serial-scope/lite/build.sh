#!/bin/sh
# Builds "Serial Scope Lite" (a tiny launcher .exe + index.html) into ../dist/
# Needs the MinGW cross compiler: apt-get install gcc-mingw-w64-x86-64
set -e
cd "$(dirname "$0")"
OUT=../dist/lite/Serial-Scope-Lite
rm -rf ../dist/lite && mkdir -p "$OUT"
x86_64-w64-mingw32-windres launcher.rc -O coff -o launcher.res
x86_64-w64-mingw32-gcc -O2 -s -municode -mwindows launcher.c launcher.res -o "$OUT/Serial Scope.exe" -lshlwapi -lshell32
rm launcher.res
cp ../index.html "$OUT/index.html"
cp README.txt "$OUT/README.txt"
(cd ../dist/lite && rm -f ../Serial-Scope-Lite.zip && zip -qr ../Serial-Scope-Lite.zip Serial-Scope-Lite)
ls -la ../dist/Serial-Scope-Lite.zip
