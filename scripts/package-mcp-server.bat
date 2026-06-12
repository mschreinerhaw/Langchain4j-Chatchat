@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package-mcp-server.ps1" %*
exit /b %ERRORLEVEL%
