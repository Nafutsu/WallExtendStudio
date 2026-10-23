@echo off
setlocal
set "GRADLE_VERSION=9.7.1"
set "BASE_DIR=%USERPROFILE%\.gradle\wallpaper-studio-dists"
set "DIST_DIR=%BASE_DIR%\gradle-%GRADLE_VERSION%"
if not exist "%DIST_DIR%\bin\gradle.bat" (
  if not exist "%BASE_DIR%" mkdir "%BASE_DIR%"
  echo Downloading Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $base=$env:USERPROFILE+'\.gradle\wallpaper-studio-dists'; $zip=$base+'\gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -LiteralPath $zip -DestinationPath $base -Force"
  if errorlevel 1 exit /b 1
)
call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
