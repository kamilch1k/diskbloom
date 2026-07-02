@echo off
setlocal
cd /d "%~dp0"

where javaw >nul 2>nul && (set "JAVAW=javaw") || (set "JAVAW=C:\Program Files\BellSoft\LibericaJDK-25-Full\bin\javaw.exe")
where javac >nul 2>nul && (set "JAVAC=javac") || (set "JAVAC=C:\Program Files\BellSoft\LibericaJDK-25-Full\bin\javac.exe")

rem rebuild (fast) so double-clicking always runs the latest code
dir /s /b src\*.java > "%TEMP%\diskbloom-src.txt"
"%JAVAC%" --add-modules javafx.controls,javafx.swing -d out @"%TEMP%\diskbloom-src.txt"
if errorlevel 1 ( echo Build failed. & pause & exit /b 1 )

rem launch the GUI (javaw = no console); optional folder arg is passed through
start "" "%JAVAW%" --add-modules javafx.controls,javafx.swing -cp out dev.diskbloom.Launcher %*
