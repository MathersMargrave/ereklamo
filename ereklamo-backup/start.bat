@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo  ===== E-Reklamo server =====
echo.
echo  On this laptop open:
echo     http://localhost:8080/index.html
echo.
echo  Other devices on the SAME Wi-Fi open one of these
echo  (pick the one starting with 192.168 or 10.):
for /f "tokens=2 delims=:" %%a in ('ipconfig ^| findstr /c:"IPv4"') do (
    set "ip=%%a"
    set "ip=!ip: =!"
    echo     http://!ip!:8080/index.html
)
echo.
echo  Keep this window open while presenting. Press Ctrl+C to stop.
echo  =============================
echo.

java -cp "lib/*" Main.java

pause
