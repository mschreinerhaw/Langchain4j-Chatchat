@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0git-auto-commit.ps1" %*
exit /b %ERRORLEVEL%
