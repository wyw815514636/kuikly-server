# Server Infrastructure Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deliver a runnable Ktor service with validated configuration, PostgreSQL lifecycle and transactions, consistent HTTP errors, request logging, and health probes.

**Architecture:** Keep JDBC/Exposed in infrastructure and expose a framework-free transaction port to application use cases. Ktor composes infrastructure through Koin. Flyway migration is an explicit command; server startup validates schema before accepting traffic. Database resources close after application shutdown.

**Tech Stack:** Existing pinned stack plus Testcontainers 2.0.5 and PostgreSQL 17.11 for integration tests.

**Spec:** The accepted conversation baseline and instruction to continue step 2, infrastructure and core plugins. Authentication/user flows remain step 3.

## Global Constraints

- Work in the existing server directory; no commits, branches, pushes, client edits, or shared databases.
- Preserve HTTP status semantics, never return SQL/stack traces, and propagate cancellation.
- JDBC remains blocking: bound execution parallelism to the pool, configure pool/driver/statement timeouts, disable automatic transaction retry, keep transaction blocks synchronous and short.
- Credentials come from environment configuration, never default production credentials or configuration `toString` output.
- All database integration tests use disposable PostgreSQL containers. Missing Docker fails the integration task rather than silently skipping it.
- Existing token-family policy remains accepted. Refresh grace/retry storage and immediate-revocation semantics are deliberately not implemented here.

## Review Focus

- Invalid/missing configuration must fail before starting listeners, without echoing credentials.
- Migration validation failure and normal shutdown must release database resources.
- Cancellation during a blocking transaction must be checked before commit once JDBC returns; JDBC interruption itself is bounded by database/driver timeouts.
- Unknown JSON fields, validation failures, business errors and missing routes preserve status codes and request IDs; concurrent requests do not share MDC.
- Readiness must fail during draining or database failure while process liveness stays independent.

## Task 1: Configuration and HTTP foundation

**Files:** `config/ServerConfig.kt`, `application/BusinessException.kt`, `plugins/*.kt`, `api/*.kt`, `src/main/resources/application.yaml`, unit/HTTP tests under `src/test/kotlin/com/kuikly/server`.

**Interfaces:** `ServerConfig.load(ApplicationConfig)`, `Application.configureHttp()`, `Application.configureHealthRoutes(ReadinessProbe)`, `BusinessException(FailureKind, code, publicMessage)`.

- [x] Add configuration and HTTP tests before implementation; observe missing implementation failure.
- [x] Implement strict configuration and the standard JSON error/request-ID/validation plugins.
- [x] Run unit and HTTP tests, covering all seven error statuses, cancellation and concurrent MDC isolation.

## Task 2: PostgreSQL lifecycle and application entrypoint

**Files:** `application/TransactionRunner.kt`, `application/ReadinessProbe.kt`, `infrastructure/database/*.kt`, `Application.kt`, `db/migration/V1__create_application_schema.sql`, database integration tests and test-only V2 fixture.

**Interfaces:** `TransactionRunner.execute(block: () -> T)`, `DatabaseRuntime.open(DatabaseSettings)`, `DatabaseRuntime.migrate(DatabaseSettings)`, `ReadinessProbe.isReady()`, `DatabaseRuntime.beginShutdown()`, `DatabaseRuntime.close()`.

- [x] Add real PostgreSQL tests for migration/validation, multi-table rollback, cancellation before commit, query timeout and pool shutdown.
- [x] Implement synchronous transaction boundaries on a limited IO dispatcher, explicit migration and startup validation.
- [x] Wire EngineMain, Koin, health routes and shutdown events; add lifecycle tests.
- [x] Run unit and integration tasks; inspect all failures and fix root causes.

## Task 3: Delivery validation

**Files:** build configuration, dependency locks, README and `.env.example`.

- [x] Update dependency locks, verify no dependency family drift and build an application distribution.
- [x] Run `clean check installDist` without rewriting locks, including database integration tests.
- [x] Exercise the packaged service with an isolated database, health/readiness HTTP calls and shutdown.
- [x] Obtain independent read-only review while final verification runs; fix material findings and update documentation.

## Execution Record

- Existing user-approved architecture and the explicit continuation instruction authorize this implementation; the plan records concrete choices without repeating an approval gate.
- Initial Docker daemon was stopped; starting the already-installed Docker Desktop is necessary to run the authorized PostgreSQL tests.
- Tests separate into `test` (no Docker) and `integrationTest`; `check` requires both.
- Configuration/HTTP tests were written first; the first compilation failed on absent production types as expected. The initial implementation then passed 11 ordinary tests and 7 PostgreSQL integration tests.
- Independent review found migration connections incorrectly inherited short OLTP statement/socket deadlines. A slow migration regression failed with PostgreSQL SQLSTATE 57014 before the fix. Migrations now use a separately validated timeout (default 300 seconds), with a correspondingly longer socket timeout; application queries retain their original limits.
- Added restricted-role startup and actual database-outage readiness coverage following review. No material findings remain; no additional review round was necessary after the regression and full suite passed.
- Final command: `./gradlew clean check installDist --offline --console=plain` — BUILD SUCCESSFUL, 11 tasks executed, 11 ordinary tests plus 10 real PostgreSQL integration tests, no skips or failures, no lock updates.
- Packaged-process verification ran `migrate`, started the application against a separate temporary PostgreSQL container, checked `/health`, `/ready`, JSON 404 plus request-ID correlation, parsed JSON MDC logs, sent SIGTERM and verified zero remaining application database sessions. Temporary containers were removed.
- Authentication, token rotation/revocation, authorization gates, OpenTelemetry distributed tracing, capacity, HA, backup/restore and shared cross-platform DTOs remain later-stage work, not implicitly completed by this infrastructure step.
