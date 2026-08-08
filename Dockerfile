# 构建阶段: Maven 多模块打包(阿里云镜像加速, 国内拉依赖不卡)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 阿里云 Maven 镜像,后续所有 mvn 都走它
COPY deploy/maven-settings.xml /root/.m2/settings.xml
# 拷全部源码(.dockerignore 已排除 target/.git/.idea/deploy/gitops 等)
COPY . .
# 只构建 ruoyi-admin 及其依赖模块(-am),跳测试,加速
RUN mvn -B -q clean package -pl ruoyi-admin -am -DskipTests

# 运行阶段: 精简 JRE
FROM eclipse-temurin:17-jre
WORKDIR /app
# Spring Boot repackage 后只有一个 ruoyi-admin.jar
COPY --from=build /build/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar
# prod profile 已把 MySQL/Redis 指向 192.168.149.128; pod 可达节点 IP
ENV SERVER_PORT=8080 SPRING_PROFILES_ACTIVE=prod JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
