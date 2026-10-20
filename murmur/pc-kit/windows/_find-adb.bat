@echo off
rem Finds adb: next to this folder, in a parent platform-tools folder, or on PATH.
set ADB=adb
if exist "%~dp0..\..\adb.exe" set ADB="%~dp0..\..\adb.exe"
if exist "%~dp0..\adb.exe" set ADB="%~dp0..\adb.exe"
if exist "%~dp0adb.exe" set ADB="%~dp0adb.exe"
if exist "C:\platform-tools\adb.exe" set ADB="C:\platform-tools\adb.exe"
if not exist "%~dp0..\logs" mkdir "%~dp0..\logs"
set LOGS=%~dp0..\logs
set STAMP=%DATE:~-4%%DATE:~4,2%%DATE:~7,2%-%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%
set STAMP=%STAMP: =0%
