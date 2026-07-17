@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart.ps1" %*
exit /b %ERRORLEVEL%
