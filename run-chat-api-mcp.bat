@echo off
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-chat-api-mcp.ps1" %*
exit /b %ERRORLEVEL%
