@echo off
call "%~dp0_find-adb.bat"
set APK=%~dp0..\murmur.apk
for %%f in ("%~dp0..\*.apk") do set APK=%%f
if not exist "%APK%" (
  echo Downloading the newest Murmur build...
  powershell -Command "Invoke-WebRequest -Uri https://github.com/xennon3/Apps/releases/download/murmur-latest/Murmur.apk -OutFile '%~dp0..\murmur.apk'"
  set APK=%~dp0..\murmur.apk
)
echo Installing %APK% (your messages and settings stay)...
%ADB% install -r "%APK%"
pause
