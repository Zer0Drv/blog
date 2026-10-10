# blog 后端生产镜像：多阶段构建（Maven → JRE 25 精简运行时）
# 构建：docker build -t ghcr.io/zer0drv/blog-backend:latest .
# 运行配置全部走环境变量（DB_HOST/DB_PORT/JWT_SECRET/MINIO_* 等），见 .env.example
# 说明：测试由 CI（ci.yml, H2）把关，镜像构建跳过测试以加快出包；Flyway 迁移打进 jar，启动时自动执行
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# 先只拷 pom 拉依赖，利用层缓存：src 不变时依赖层不重建
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# 运行时：JRE 25（与构建 JDK 大版本一致），非 root 运行
FROM eclipse-temurin:25-jre
WORKDIR /app

# *.jar 只匹配可执行 jar（-jar.original 以 .original 结尾不匹配）
COPY --from=build /app/target/*.jar app.jar

RUN useradd -r -u 10001 app && chown app:app /app
USER app

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
