@echo off
REM ChatChat 编译验证脚本
REM 用于验证所有修复是否成功

echo.
echo ============================================
echo  ChatChat 编译验证脚本
echo ============================================
echo.

REM 检查Maven
echo [1/5] 检查Maven安装...
mvn -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Maven未找到，请先安装Maven
    exit /b 1
)
echo ✅ Maven已安装

REM 检查Java
echo [2/5] 检查Java版本...
for /f tokens^=2 %%A in ('java -version 2^>^&1 ^| find "version"') do set JAVA_VERSION=%%A
echo ✅ Java版本: %JAVA_VERSION%

REM 清理
echo [3/5] 清理项目...
call mvn clean -q
if %errorlevel% neq 0 (
    echo ❌ 清理失败
    exit /b 1
)
echo ✅ 清理完成

REM 编译
echo [4/5] 编译项目...
call mvn compile -q
if %errorlevel% neq 0 (
    echo ❌ 编译失败
    echo 运行以下命令查看详细错误:
    echo   mvn compile
    exit /b 1
)
echo ✅ 编译成功

REM 构建
echo [5/5] 构建项目...
call mvn package -DskipTests -q
if %errorlevel% neq 0 (
    echo ❌ 构建失败
    exit /b 1
)
echo ✅ 构建成功

echo.
echo ============================================
echo  ✅ 所有编译验证通过！
echo ============================================
echo.
echo 构建的JAR文件:
echo   chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar
echo.
echo 运行应用:
echo   java -jar chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar
echo.
echo 访问API文档:
echo   http://localhost:8080/swagger-ui.html
echo.
pause
