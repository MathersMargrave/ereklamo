@echo off
cd /d "%~dp0"
java -cp "lib/*" Main.java %*
pause
