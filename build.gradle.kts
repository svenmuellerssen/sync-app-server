plugins {
	kotlin("jvm") version "1.9.25"
	kotlin("plugin.spring") version "1.9.25"
	id("org.springframework.boot") version "3.5.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "de.sync.app"
version = "0.0.1-SNAPSHOT"
description = "Sync App Server"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-neo4j")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
	testImplementation("org.testcontainers:junit-jupiter:1.21.3")
	testImplementation("org.testcontainers:neo4j:1.21.3")
	testImplementation("org.testcontainers:testcontainers:1.21.3")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// neo4j-harness brings its own SLF4J provider — force Logback so Spring Boot doesn't fail
	systemProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider")
	// Docker Desktop 29+ on Windows: \\.\pipe\docker_engine_linux is the real WSL2 engine pipe.
	// docker-java reads System.getProperty("api.version") before DOCKER_API_VERSION env var.
	// Minimum required API version for Docker 29 is 1.40.
	environment("DOCKER_HOST", "npipe:////./pipe/docker_engine_linux")
	systemProperty("api.version", "1.41")
}
