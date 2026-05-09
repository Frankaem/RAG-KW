# ============================================
# RAG-KW Docker 镜像构建文件
# ============================================

# ---------- 构建阶段 ----------
FROM maven:3.8-openjdk-17 AS build
WORKDIR /app

# 复制 pom.xml 并下载依赖（利用 Docker 缓存层）
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源代码并构建
COPY src ./src
RUN mvn clean package -DskipTests -B

# ---------- 运行阶段 ----------
FROM openjdk:17-slim

# 设置工作目录
WORKDIR /app

# 创建非 root 用户（安全最佳实践）
RUN groupadd -r ragkw && useradd -r -g ragkw ragkw

# 从构建阶段复制 jar 包
COPY --from=build /app/target/*.jar app.jar

# 修改文件所有者
RUN chown -R ragkw:ragkw /app

# 切换到非 root 用户
USER ragkw

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM 参数优化
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]