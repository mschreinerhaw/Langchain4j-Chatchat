#!/bin/bash
# ChatChat 编译验证脚本 (Linux/Mac)

set -e

echo ""
echo "============================================"
echo "  ChatChat 编译验证脚本"
echo "============================================"
echo ""

# 检查Maven
echo "[1/5] 检查Maven安装..."
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven未找到，请先安装Maven"
    exit 1
fi
echo "✅ Maven已安装"

# 检查Java
echo "[2/5] 检查Java版本..."
JAVA_VERSION=$(java -version 2>&1 | grep version | awk '{print $3}')
echo "✅ Java版本: $JAVA_VERSION"

# 清理
echo "[3/5] 清理项目..."
mvn clean -q
echo "✅ 清理完成"

# 编译
echo "[4/5] 编译项目..."
if ! mvn compile -q; then
    echo "❌ 编译失败"
    echo "运行以下命令查看详细错误:"
    echo "  mvn compile"
    exit 1
fi
echo "✅ 编译成功"

# 构建
echo "[5/5] 构建项目..."
if ! mvn package -DskipTests -q; then
    echo "❌ 构建失败"
    exit 1
fi
echo "✅ 构建成功"

echo ""
echo "============================================"
echo "  ✅ 所有编译验证通过！"
echo "============================================"
echo ""
echo "构建的JAR文件:"
echo "  chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar"
echo ""
echo "运行应用:"
echo "  java -jar chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar"
echo ""
echo "访问API文档:"
echo "  http://localhost:8080/swagger-ui.html"
echo ""
