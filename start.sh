#!/bin/bash

echo "============================================"
echo "RAG-KW Docker 启动脚本"
echo "============================================"
echo ""

# 检查 .env 文件是否存在
if [ ! -f .env ]; then
    echo "[警告] .env 文件不存在，正在创建..."
    cp .env.example .env
    echo "[提示] 请编辑 .env 文件，填入你的配置"
    echo ""
    read -p "按回车键继续..."
    exit 1
fi

# 检查 Docker 是否安装
if ! command -v docker &> /dev/null; then
    echo "[错误] Docker 未安装"
    exit 1
fi

# 启动服务
echo "[信息] 正在启动 RAG-KW 服务..."
docker-compose up -d

# 检查启动状态
if [ $? -eq 0 ]; then
    echo ""
    echo "============================================"
    echo "启动成功！"
    echo "============================================"
    echo ""
    echo "访问地址：http://localhost:8080"
    echo ""
    echo "查看日志：docker-compose logs -f rag-kw"
    echo "停止服务：docker-compose down"
    echo ""
else
    echo ""
    echo "[错误] 启动失败，请检查日志"
    docker-compose logs
    exit 1
fi