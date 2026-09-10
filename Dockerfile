# =============================================================================
# lilac-ai-router 后端镜像（多阶段构建）
#
#   stage 1  builder : maven 3.9 + eclipse-temurin 17   → 打出 Spring Boot fat jar
#   stage 2  runtime : eclipse-temurin 17 jre           → 只有 jar，不含源码/构建工具/本地配置
#
# 构建（必须在仓库根目录执行，context = .）：
#   docker build -t lilac-ai-router:latest .
#
# ⚠️ 必须先有 .dockerignore：本地的 backend/src/main/resources/application-dev.yml
#    被 .gitignore 忽略，clone 下来不存在，但本地存在。没有 .dockerignore 时
#    `COPY backend/src` 会把它打进镜像 —— 典型的"本地跑没事，镜像里藏密钥"。
# =============================================================================


# -----------------------------------------------------------------------------
# Stage 1：构建
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 1) 先只拷 pom.xml，把"下载依赖"单独做成一层：改业务代码时不会重新拉依赖
COPY lilac-ai-router-sdk/pom.xml ./lilac-ai-router-sdk/pom.xml
RUN mvn -B -q -f lilac-ai-router-sdk/pom.xml dependency:go-offline || true

# 2) SDK 必须先 install 到本地仓库：backend 依赖 com.lilac:lilac-ai-router-sdk:1.0.0，
#    中央仓库没有这个坐标，不先装会直接依赖解析失败（这是本项目本地构建的第一个坑）
COPY lilac-ai-router-sdk/src ./lilac-ai-router-sdk/src
RUN mvn -B -f lilac-ai-router-sdk/pom.xml install -DskipTests

# 3) backend 依赖预热
COPY backend/pom.xml ./backend/pom.xml
RUN mvn -B -q -f backend/pom.xml dependency:go-offline || true

# 4) 编译打包（Flyway 迁移脚本在 src/main/resources/db/migration，会一起进 jar）
COPY backend/src ./backend/src
RUN mvn -B -f backend/pom.xml package -DskipTests


# -----------------------------------------------------------------------------
# Stage 2：运行
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl：健康检查探针用（JRE 镜像默认不带）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 只取 repackage 后的 fat jar（backend-*.jar 不会匹配到 .jar.original）
COPY --from=builder /build/backend/target/backend-*.jar /app/app.jar

# 非 root 运行
RUN groupadd -r app \
    && useradd -r -g app -d /app -s /sbin/nologin app \
    && chown -R app:app /app
USER app

# logback-spring.xml 输出到相对路径 logs/，挂卷持久化 + 方便外部采集
VOLUME ["/app/logs"]
EXPOSE 9090

ENV SPRING_PROFILES_ACTIVE=prod \
    TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# 启动探针留 90s：Spring 上下文 + Flyway 迁移 + Redis 连接建立
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:9090/api/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
