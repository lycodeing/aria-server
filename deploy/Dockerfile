FROM --platform=linux/amd64 eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY app.jar app.jar
# 容器内存限制感知 + 最多使用 70% 堆
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=70.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
