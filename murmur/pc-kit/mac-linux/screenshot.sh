#!/bin/sh
. "$(dirname "$0")/_find-adb.sh"
"$ADB" exec-out screencap -p > "$LOGS/screen-$STAMP.png"
echo "Saved $LOGS/screen-$STAMP.png"
