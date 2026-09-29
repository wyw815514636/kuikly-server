import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.kuikly.server"
version = "0.1.0-SNAPSHOT"

application {
    mainClass.set("com.kuikly.server.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform(libs.kotlin.bom))
    implementation(platform(libs.ktor.bom))
    implementation(platform(libs.coroutines.bom))
    implementation(libs.bundles.ktor)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.swagger)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.koin)
    implementation(libs.bundles.coroutines)
    implementation(libs.serialization.json)
    implementation(libs.datetime)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.openapi.validator)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

val verifyDependencies by tasks.registering {
    group = "verification"
    description = "Resolves all resolvable configurations and fails on missing artifacts or lock mismatches."
    doLast {
        configurations.filter { it.isCanBeResolved }.sortedBy { it.name }.forEach { configuration ->
            val artifacts = configuration.resolve()
            logger.lifecycle("Resolved ${configuration.name}: ${artifacts.size} artifacts")
        }
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests against disposable PostgreSQL containers; requires Docker."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.check {
    dependsOn(verifyDependencies, integrationTest)
}

tasks.wrapper {
    gradleVersion = "9.3.1"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06"
    networkTimeout = 60_000
}
