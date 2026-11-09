# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew --no-daemon test bootJar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home app && mkdir -p /data && chown app:app /data
COPY --from=build /workspace/build/libs/backend-0.0.1-SNAPSHOT.jar /app/app.jar
ENV SERVER_ADDRESS=0.0.0.0 PORT=8080 DB_URL=jdbc:h2:file:/data/backend ASSET_STORAGE_ROOT=/data/assets
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s CMD curl --fail --silent http://127.0.0.1:8080/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
