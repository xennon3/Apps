#!/bin/sh
. "$(dirname "$0")/_find-adb.sh"
OUT="$LOGS/live-$STAMP.txt"
echo "Watching Murmur's log. Reproduce the problem, then press Ctrl+C. Saving to $OUT"
"$ADB" logcat -c
"$ADB" logcat -v time | grep --line-buffered -iE 'murmur|litert|AndroidRuntime|AmbientService' | tee "$OUT"
