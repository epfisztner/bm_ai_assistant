# syntax=docker/dockerfile:1

# --- build: compile and package the executable jar (tests run outside the image: they need Docker) ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/expert-knowledge-assistant-*.jar extract --layers --launcher --destination extracted

# --- runtime: JRE only, non-root, layered for cache-friendly rebuilds ---
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --home-dir /app app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
RUN mkdir -p /app/snapshots && chown app /app/snapshots
USER app
ENV SNAPSHOT_DIR=/app/snapshots
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
