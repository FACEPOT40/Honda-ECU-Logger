@echo off
REM Builds the signed phone app for GitHub Releases: dist\H-ECU-Logger-<version>.apk
REM Needs android\keystore.properties (see android\keystore.properties.example and README).
setlocal
cd /d "%~dp0"

if not exist "android\keystore.properties" (
    echo android\keystore.properties not found: create your signing key first, see README "Release APK".
    exit /b 1
)
if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call android\gradlew.bat -p android testDebugUnitTest assembleRelease || exit /b 1

for /f "tokens=3" %%v in ('findstr /c:"versionName = " android\app\build.gradle.kts') do set VER=%%~v
if not exist dist mkdir dist
copy /y android\app\build\outputs\apk\release\app-release.apk "dist\H-ECU-Logger-%VER%.apk" >nul || exit /b 1

echo.
echo Done: dist\H-ECU-Logger-%VER%.apk
echo Remember: raise versionCode in android\app\build.gradle.kts before the next release.
