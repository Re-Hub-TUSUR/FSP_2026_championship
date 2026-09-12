FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace
COPY . .
# Кэш Gradle переживает пересборки: зависимости скачиваются один раз, а не при каждом изменении исходников.
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked \
    GRADLE_USER_HOME=/home/gradle/.gradle gradle --no-daemon bootJar \
    && JAR=$(find /workspace/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1) \
    && test -n "$JAR" \
    && cp "$JAR" /workspace/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
