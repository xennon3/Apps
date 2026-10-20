@echo off
call "%~dp0_find-adb.bat"
set OUT=%LOGS%\snapshot-%STAMP%.txt
echo Gathering a snapshot into %OUT% ...
(
echo ===== Device =====
%ADB% shell getprop ro.product.model
%ADB% shell getprop ro.build.version.release
%ADB% shell dumpsys package app.murmur ^| findstr /C:"versionName" /C:"versionCode"
echo ===== Murmur log (recent) =====
for /f "usebackq" %%p in (`%ADB% shell pidof app.murmur`) do %ADB% logcat -d -v time --pid=%%p -t 4000
echo ===== Why Android stopped Murmur =====
%ADB% shell dumpsys activity exit-info app.murmur
echo ===== Ambient service =====
%ADB% shell dumpsys activity services app.murmur
echo ===== Battery =====
%ADB% shell dumpsys battery
%ADB% shell dumpsys deviceidle whitelist
%ADB% shell dumpsys power ^| findstr /C:"mWakefulness" /C:"mBatterySaverEnabled" /C:"mDeviceIdleMode"
echo ===== Permissions =====
%ADB% shell dumpsys package app.murmur
%ADB% shell appops get app.murmur
echo ===== Memory =====
%ADB% shell dumpsys meminfo app.murmur
echo ===== Location =====
%ADB% shell dumpsys location
) > "%OUT%" 2>&1
echo Done: %OUT%
pause
