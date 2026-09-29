# Server Build Baseline Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Establish a reproducible JDK 21 / Kotlin / Ktor build and verify the selected dependency combination.

**Architecture:** An independent Gradle Kotlin/JVM project in the existing `server/` repository. Test-only compatibility probes exercise the selected libraries; production infrastructure and business modules follow in later steps.

**Tech Stack:** Gradle Wrapper, version catalog, strict dependency locking, Kotlin/JVM, Ktor/Netty, Exposed JDBC, PostgreSQL, HikariCP, Flyway, Koin, kotlinx libraries, JUnit.

**Spec:** The user's accepted first-step scope in this conversation: create Gradle configuration and verify dependency resolution before infrastructure or authentication implementation.

## Global Constraints

- Work directly in the newly cloned, empty `server/` repository as requested; do not create branches, commits, or pushes.
- Use JDK 21 for compilation and tests; do not change the machine-wide default JDK or client project.
- Pin stable releases and record resolved transitive dependencies; no dynamic or prerelease dependencies.
- Do not require a running PostgreSQL, Redis, Docker, or external credentials for this step.
- Do not claim the six production acceptance gates or cross-platform contract compatibility are complete.

## Review Focus

- Default machine JDK differs from the project JDK: document selection and configure daemon/toolchain criteria.
- Transitive Ktor and Kotlin versions differ from direct declarations: inspect resolved dependencies and load Koin with Ktor.
- Resolution reports can contain failures without failing the task: explicitly resolve artifacts in a failing verification task.
- Serialization/compiler and JDBC/Flyway provider incompatibilities: compile serializers and load the real provider classes.
- Test-only Netty listeners must bind loopback, use an ephemeral port, and close resources on failure.

## Task 1: Reproducible build and compatibility verification

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, Gradle Wrapper and lockfiles, `src/test/kotlin/com/kuikly/server/DependencyCompatibilityTest.kt`, `src/test/resources/logback-test.xml`, `README.md`.

**Interfaces:** `./gradlew verifyDependencies` resolves all dependency configurations; `./gradlew clean check` resolves, compiles, and runs compatibility tests without external services.

- [x] Inspect existing repository, client compiler version, local toolchains, Maven metadata, and official compatibility documentation.
- [x] Create exact version catalog, JVM toolchain settings, repository policy, dependency resolution verification, and lock policy.
- [x] Generate and verify Gradle Wrapper; configure the Gradle daemon for JDK 21.
- [x] Add focused compatibility probes for Ktor/Netty/Koin/serialization, coroutine MDC, and JDBC/Flyway provider loading.
- [x] Generate dependency locks and run the full build without updating locks.
- [x] Review generated files, resolved versions, test results, and document reproducible commands and remaining scope.

## Execution Notes

- Configuration changes are verified through Gradle itself and compatibility probes; no application behavior is introduced in this step.
- The empty repository has no HEAD or existing tests. Worktree/commit-based bookkeeping does not apply; preserve this plan as the execution record.
- Client Kotlin is 2.1.21. Server Kotlin is selected independently; cross-platform sharing is deliberately deferred until target compatibility is tested.
- Maven access initially failed because Gradle did not inherit the operating system proxy. Per-invocation JVM proxy settings resolved the failure; no proxy address or credentials were added to project configuration.
- Gradle's daemon update task attempted to resolve JDK download repositories. The project uses a local JDK only, so portable `toolchainVersion=21` daemon criteria were written directly instead of configuring a download provider.
- The first compatibility compile rejected an unsupported `configure` argument on the selected Ktor `embeddedServer` overload. The probe now uses its supported host/port overload and retains loopback binding and finally-based cleanup.
- `verifyDependencies test --write-locks` passed: four tests, zero failures; dependency locks generated.
- `./gradlew clean check --offline --console=plain` passed from the default JDK 26 shell using the project's JDK 21 daemon/toolchain criteria: four tests, zero failures, all six tasks executed, no lock updates.
- Wrapper JAR SHA-256 matched the official value: `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`.
- Lock review: all application Ktor artifacts resolve to 3.5.2; application Kotlin stdlib/reflect resolve to 2.3.21. Older reflect/coroutine versions appear only in Kotlin compiler tooling configurations and are intentionally not forced into application alignment.
- Independent read-only source review found no material defects. Production functionality, database integration and cross-platform contracts remain deferred as specified.
