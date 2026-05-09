@echo off
echo ============================================
echo RAG-KW Docker 启动脚本
echo ============================================
echo.

REM 检查 .env 文件是否存在
if not exist .env (
    echo [警告] .env 文件不存在，正在创建...
    copy .env.example .env
    echo [提示] 请编辑 .env 文件，填入你的配置
    echo.
    pause
    exit /b 1
)

REM 检查 Docker 是否安装
where docker >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [错误] Docker 未安装或未添加到 PATH
    pause
    exit /b 1
)

REM 启动服务
echo [信息] 正在启动 RAG-KW 服务...
docker-compose up -d

REM 检查启动状态
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ============================================
    echo 启动成功！
    echo ============================================
    echo.
    echo 访问地址：http://localhost:8080
    echo.
    echo 查看日志：docker-compose logs -f rag-kw
    echo 停止服务：docker-compose down
    echo.
) else (
    echo.
    echo [错误] 启动失败，请检查日志
    docker-compose logs
)

pause