@echo off
REM Builds dist\HondaECULogger.exe (single file, no console window)
pip install -r requirements-dev.txt
pyinstaller --noconfirm --onefile --windowed --name HondaECULogger app.py
echo.
echo Done: dist\HondaECULogger.exe
pause
