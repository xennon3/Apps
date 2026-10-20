@echo off
call "%~dp0_find-adb.bat"
set OUT=%LOGS%\live-%STAMP%.txt
echo Watching Murmur's log. Use the phone to reproduce the problem, then press Ctrl+C.
echo Saving to %OUT%
%ADB% logcat -c
powershell -Command "& %ADB% logcat -v time | Select-String -Pattern 'murmur|Murmur|litert|AndroidRuntime|AmbientService' | Tee-Object -FilePath '%OUT%'"
