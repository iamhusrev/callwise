# ===== Build stage =====
# Gradle 8.10 + JDK 21. Cache deps before copying sources.
FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /workspace

# Copy build scripts first so the dep-resolution layer caches independently of source changes
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

# Now bring in sources and build the boot jar (skip tests — CI runs them)
COPY src ./src
RUN gradle --no-daemon bootJar -x test

# ===== Runtime stage =====
# Slim JRE-only image. ~80% smaller than the build image.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user (defense in depth)
RUN addgroup -S app && adduser -S -G app app
USER app

COPY --from=build /workspace/build/libs/app.jar app.jar

EXPOSE 8080

# Run with conservative heap (suits 1G containers); expose actuator on /actuator/health
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
