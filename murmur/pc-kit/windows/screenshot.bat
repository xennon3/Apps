@echo off
call "%~dp0_find-adb.bat"
%ADB% exec-out screencap -p > "%LOGS%\screen-%STAMP%.png"
echo Saved %LOGS%\screen-%STAMP%.png
pause
