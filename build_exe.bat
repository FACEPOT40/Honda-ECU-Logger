@echo off
REM Builds dist\H-ECU-Logger.exe (single file, no console window)
pip install -r requirements-dev.txt
pyinstaller --noconfirm --onefile --windowed --name H-ECU-Logger app.py
echo.
echo Done: dist\H-ECU-Logger.exe
pause
