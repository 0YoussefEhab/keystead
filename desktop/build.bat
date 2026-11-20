@echo off
cd /d "%~dp0"
echo Installing build tools...
python -m pip install --upgrade pywebview pyinstaller || goto :err
echo Building Keystead.exe...
taskkill /f /im MyVault.exe >nul 2>&1
taskkill /f /im Keystead.exe >nul 2>&1
python -m PyInstaller --noconfirm --onefile --windowed --name Keystead --icon keystead.ico --add-data "MyVault.html;." myvault_app.py || goto :err
echo.
echo Done! Your app is at: %~dp0dist\Keystead.exe
explorer "%~dp0dist"
pause
exit /b 0
:err
echo.
echo Something went wrong. Copy the error above and send it to Claude.
pause
