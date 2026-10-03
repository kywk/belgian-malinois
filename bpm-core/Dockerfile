# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
RUN apk add --no-cache maven
# Pre-fetch dependencies (cached layer, only re-runs when pom.xml changes)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
# Build
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

# 應用在執行期會寫入這兩個目錄（bpm.upload.dir 與 bpm.bpmn-definitions-dir，
# 預設都是 WORKDIR 底下的相對路徑）。改成非 root 之後必須預先建立並轉移
# 所有權 —— 否則 appuser 無法在 root 擁有的 /app 下建目錄，附件上傳與
# BPMN 部署會在執行期失敗。
# app.jar 本身刻意保持 root 所有：appuser 不可寫入，正是這次要防的事。
RUN mkdir -p /app/uploads /app/bpmn-definitions \
    && chown -R appuser:app /app/uploads /app/bpmn-definitions

USER appuser

EXPOSE 8080

# 執行期 JVM 記憶體參數。用 ENV 讓 JVM 自己讀取，任何以 java 啟動的
# process 都套用，不限目前的 ENTRYPOINT。
#
# -XX:MaxRAMPercentage=75：heap 隨容器的 cgroup 記憶體上限縮放
#   （JDK 10+ 的 UseContainerSupport 預設開啟，JDK 21 不需要明寫）。
#   刻意不寫死 -Xmx：日後調高 compose／k8s 的 memory limit 時 heap
#   會跟著長，不會出現「limit 2G、-Xmx 4G」而 JVM 被 kernel OOM kill。
#   留 25% 給 metaspace、native memory、執行緒堆疊與 NIO buffer；
#   這是 container-aware JVM 的常見起點 —— 比例再高，尖峰時 native
#   部分容易先撞到上限；再低則白白浪費可用的 heap。
# -XX:+ExitOnOutOfMemoryError：真的 OOM 時直接結束 process，讓
#   orchestrator（compose 的 restart: unless-stopped／k8s）重啟；
#   否則 heap 已壞的 JVM 會繼續服務，錯誤只會延後爆發。
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "app.jar"]
