# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
RUN apk add --no-cache maven
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# 以非 root 執行。改動前沒有 USER 指令 → 容器以 root 跑，
# 而 WORKDIR 是 /app，因此任何「可寫入任意路徑」的漏洞（例如附件上傳的
# path traversal，見 security-audit P0-1）都能覆寫 /app/app.jar，
# 下次重啟即為 RCE。app.jar 刻意保持 root 所有且不可被 appuser 寫入。
RUN addgroup -S app && adduser -S -G app appuser
USER appuser

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
