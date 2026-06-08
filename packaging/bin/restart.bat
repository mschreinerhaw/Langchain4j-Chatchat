@echo off
setlocal

call "%~dp0stop.bat"
if errorlevel 1 exit /b %ERRORLEVEL%

call "%~dp0start.bat"
exit /b %ERRORLEVEL%
