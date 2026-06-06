@echo off
setlocal
cd /d "%~dp0\.."

if not exist "logs" mkdir logs
if not exist "lib" mkdir lib

java %JAVA_OPTS% -jar chatchat-mcp-server.jar --spring.config.additional-location=optional:file:./config/
