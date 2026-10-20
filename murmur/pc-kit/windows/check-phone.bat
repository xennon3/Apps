@echo off
call "%~dp0_find-adb.bat"
%ADB% devices -l
echo.
echo Phone model:
%ADB% shell getprop ro.product.model
echo Murmur installed:
%ADB% shell dumpsys package app.murmur | findstr /C:"versionName"
echo.
echo If you see "unauthorized", unlock the phone and tap Allow on the USB debugging prompt.
pause
