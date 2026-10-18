@echo off
rem Builds dist\LogoSync.exe (one file, no install needed). Run on Windows with Python 3.12+.
cd /d "%~dp0"
python -m pip install -r requirements.txt -r requirements-dev.txt || exit /b 1
python -m pytest -q || exit /b 1
python -m PyInstaller --noconfirm --clean --onefile --windowed --name LogoSync ^
  --icon assets\icon.ico --add-data "assets\icon.png;assets" run.py || exit /b 1
echo.
echo Built dist\LogoSync.exe
