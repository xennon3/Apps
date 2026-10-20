#!/bin/sh
. "$(dirname "$0")/_find-adb.sh"
"$ADB" devices -l
echo "Phone model: $("$ADB" shell getprop ro.product.model)"
echo "Murmur: $("$ADB" shell dumpsys package app.murmur | grep -m1 versionName)"
echo 'If you see "unauthorized", unlock the phone and tap Allow on the USB debugging prompt.'
