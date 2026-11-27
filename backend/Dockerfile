# 构建阶段：不依赖宿主机 Maven
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline
COPY config ./config
COPY src ./src
RUN mvn -B -q -DskipTests package

# 分层提取：COPY 顺序按 Spring Boot 官方文档，从最少变化到最常变化
FROM eclipse-temurin:17-jre-jammy AS layers
WORKDIR /layers
COPY --from=build /src/target/yuzhua-server.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# 运行时：非 root，装 curl 供 healthcheck 使用
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app \
 && useradd --system --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --chown=app:app --from=layers /layers/extracted/dependencies/ ./
COPY --chown=app:app --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --chown=app:app --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --chown=app:app --from=layers /layers/extracted/application/ ./
# 种子数据里的医生/宠物/症状图片，SQL 中引用 /api/uploads/seed/*.jpg
COPY --chown=app:app uploads/seed ./uploads/seed
# 只给上传目录写权限，避免对整层再 chown 复制一遍
RUN mkdir -p /app/uploads && chown app:app /app/uploads
USER app
ENV TZ=Asia/Shanghai
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "application.jar"]