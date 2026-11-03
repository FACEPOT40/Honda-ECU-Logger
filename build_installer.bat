@echo off
REM Builds dist\H-ECU-Logger-Setup-<version>.exe: the app .exe plus an installer with the FTDI driver.
REM Needs Inno Setup 6 (winget install JRSoftware.InnoSetup) and FTDI's driver folder
REM CDM-v2.12.36.20-WHQL-Certified in this folder (not in git: it's FTDI's, download it from ftdichip.com).
setlocal
cd /d "%~dp0"

if not exist "CDM-v2.12.36.20-WHQL-Certified\ftdibus.inf" (
    echo FTDI driver folder CDM-v2.12.36.20-WHQL-Certified not found next to this script.
    exit /b 1
)
set ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe
if not exist "%ISCC%" set ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe
if not exist "%ISCC%" (
    echo Inno Setup 6 not found. Install it with: winget install JRSoftware.InnoSetup
    exit /b 1
)

pip install -r requirements-dev.txt || exit /b 1
pyinstaller --noconfirm --onefile --windowed --name H-ECU-Logger --icon assets\icon.ico --add-data "assets\icon.ico;assets" app.py || exit /b 1

for /f "tokens=3" %%v in ('findstr /b /c:"VERSION = " app.py') do set VER=%%~v
"%ISCC%" /DAppVersion=%VER% installer\H-ECU-Logger.iss || exit /b 1

echo.
echo Done: dist\H-ECU-Logger-Setup-%VER%.exe
