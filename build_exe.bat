@echo off
REM Builds dist\H-ECU-Logger.exe (single file, no console window)
pip install -r requirements-dev.txt
pyinstaller --noconfirm --onefile --windowed --name H-ECU-Logger --icon assets\icon.ico --add-data "assets\icon.ico;assets" app.py
echo.
echo Done: dist\H-ECU-Logger.exe
pause
