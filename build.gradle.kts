plugins {
    java
    id("org.springframework.boot") version "3.5.0"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.callwise"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["twilioSdkVersion"] = "10.6.0"
extra["logstashEncoderVersion"] = "7.4"
extra["testcontainersVersion"] = "1.21.0"
extra["wiremockVersion"] = "3.9.1"

dependencies {
    // Web layer: REST controllers, Tomcat, Jackson
    implementation("org.springframework.boot:spring-boot-starter-web")

    // JPA + Hibernate (PostgreSQL persistence)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // /actuator/health for docker-compose healthcheck and admin probe
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Bean Validation (jakarta.validation) for DTOs and request bodies
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Liquibase: schema migrations on startup
    implementation("org.liquibase:liquibase-core")

    // Twilio SDK: type-safe TwiML builder (escapes XML correctly)
    implementation("com.twilio.sdk:twilio:${property("twilioSdkVersion")}")

    // Structured JSON logs (logstash encoder for Logback)
    implementation("net.logstash.logback:logstash-logback-encoder:${property("logstashEncoderVersion")}")

    // PostgreSQL driver (runtime)
    runtimeOnly("org.postgresql:postgresql")

    // Lombok: only for JPA entities (CLAUDE.md convention)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // ===== Test =====
    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // TestContainers: real Postgres in tests (CLAUDE.md: no H2)
    testImplementation("org.testcontainers:junit-jupiter:${property("testcontainersVersion")}")
    testImplementation("org.testcontainers:postgresql:${property("testcontainersVersion")}")

    // WireMock: stub Anthropic / Groq HTTP in unit + integration tests
    testImplementation("org.wiremock:wiremock-standalone:${property("wiremockVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Integration tests follow the *IT.java naming convention; unit tests are *Test.java
    systemProperty("spring.profiles.active", "test")
    // Forward Docker Desktop's user-scoped socket so TestContainers can discover Docker on macOS.
    // Falls back to whatever the user has exported (CI typically sets DOCKER_HOST=unix:///var/run/docker.sock).
    System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
}

// Lombok must not leak into the executable jar (matches Maven config)
tasks.bootJar {
    archiveFileName.set("app.jar")
}
