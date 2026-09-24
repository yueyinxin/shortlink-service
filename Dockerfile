# =============================================================================
# 多阶段构建
#
# 阶段一用 Maven + JDK 编译打包，阶段二只保留 JRE 与产物。
# 这样做的收益是镜像体积：完整 JDK + Maven 本地仓库约 700MB，
# 而只含 JRE 与 fat jar 的运行时镜像约 200MB。体积直接影响部署时的拉取耗时，
# 在服务器带宽有限时（例如本项目作者的环境）这个差别很实际。
#
# 另一个收益是安全：构建工具（Maven、编译器、源码）不会进入生产镜像，
# 攻击者即使拿到镜像也无法从中获取源码或构建链的凭据。
# =============================================================================

# ------------------------------------------------------------------ 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 单独复制 pom.xml 并预下载依赖。
# Docker 的层缓存以文件内容为键：只要 pom.xml 没变，后续修改源码时
# 这一层会被复用，不必重新下载上百 MB 的依赖 —— 这是把 COPY pom.xml
# 与 COPY src 分开的唯一目的，顺序不能调换。
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src

# 跳过测试：测试已在 CI 流水线中执行过（见 .github/workflows/ci.yml）。
# 在镜像构建里重复跑测试会让构建时间翻倍，且失败原因难以区分
# ——是"代码有问题"还是"镜像构建环境有问题"。
RUN mvn -B -q clean package -DskipTests

# ------------------------------------------------------------------ 运行阶段
FROM eclipse-temurin:17-jre-alpine

# 以非 root 用户运行。容器内的 root 与宿主机的 root 在默认配置下并非完全隔离，
# 一旦应用被攻破（例如反序列化漏洞），root 身份会让攻击者更容易横向移动。
# 创建一个无登录权限的系统用户，成本几乎为零。
RUN addgroup -S app && adduser -S -G app app

WORKDIR /app

COPY --from=builder --chown=app:app /build/target/*.jar app.jar

USER app

EXPOSE 8080

# 健康检查使用 Actuator 的 health 端点。
# 不用 TCP 端口探测：端口监听成功不代表应用可用（可能仍在执行 Flyway 迁移，
# 或数据库连接尚未建立），后者会导致编排系统过早把流量导入。
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# JVM 参数说明：
#   MaxRAMPercentage      —— 让 JVM 按容器内存上限的百分比设置堆，而不是读取宿主机的总内存。
#                            不设置时 JVM 可能按宿主机内存（如 32GB）分配堆，
#                            在容器内存受限时会被 OOM Killer 杀掉，且从日志上看不出原因。
#   UseG1GC               —— Java 17 的默认 GC，显式写出以表明这是有意的选择
#   ExitOnOutOfMemoryError—— OOM 后立即退出而不是带病运行，让编排系统重启容器。
#                            堆溢出后继续运行的服务通常已经无法正常处理请求，
#                            但健康检查可能仍然通过，造成持续的错误响应。
#   XX:+HeapDumpOnOutOfMemoryError —— 保留现场供事后分析。注意堆转储文件可能很大，
#                            生产环境应配合 -XX:HeapDumpPath 指向有容量保障的卷。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError"

# 用 exec 形式而非 shell 形式，保证 java 进程成为 PID 1。
# shell 形式（CMD java ...）会让 /bin/sh 成为 PID 1，SIGTERM 不会传递给 java，
# 于是容器停止时应用无法优雅关闭 —— 正在处理中的请求被直接切断，
# 且 Spring 的 @PreDestroy 钩子不会执行。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
