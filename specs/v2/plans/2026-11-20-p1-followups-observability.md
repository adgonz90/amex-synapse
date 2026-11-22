# P1 follow-ups: observability and logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the maintainer's observability and logging decisions of 2026-11-19/20: one masking hardening pass (decision 10), the liveness probe default (4), Logback as the default backend with Log4j2 opt-in (14), the service-only tracing module `synapse-tracing-opentelemetry` (15, reverted form), the OpenTelemetry agent ITs only under `-Potel-agent` (22), and the image docs items of decision 32 (8, 9).

**Architecture:**
- Masking first (Tasks 1–5), because it touches the pipeline's failure line, which the later logging tasks only move between backends:
  1. one cause walk and one sanitizer in core (`core.internal.error`), used by core and data;
  2. every place Synapse logs or records a failure goes through the sanitizer;
  3. whole-value masking for types Jackson writes with their own serializer.
- Health (Task 6) is independent and small.
- Logging (Tasks 7–8): the ECS default moves into core, a WARN covers burst properties without the Log4j2 module, and the Log4j2 module gets the M2 fix. Synapse ships nothing for Logback.
- Tracing (Task 9): the tracing module, which also carries the OpenTelemetry agent recipe as an auto-configuration, with the permanent unsampled-correlation test.
- Samples and archetypes switch to Logback and the tracing module in one task (Task 10), then the agent ITs move under `-Potel-agent` (Task 11).
- Docs-only items and spec status (Task 12), the full build (Task 13) and the final review (Task 14).
- Every behaviour change starts with a failing test (AGENTS.md *Bug fixes*). Docs that describe a behaviour change are in the same task as the change.

**Tech Stack:** Java 21 (`--release 21`, built on JDK 25), Maven 3.9.16 through the wrapper, Spring Boot 4.1.2 (unchanged), Micrometer 1.17.2 and Micrometer Tracing 1.7.2, OpenTelemetry 1.62.0, Log4j2 and Logback as Boot 4.1.2 manages them, Jackson 3.1.7, Hibernate Validator as Boot manages it, JUnit 5, AssertJ, Testcontainers 2.0.5, Maven Archetype 3.4.1. No new version anywhere: the new module's dependencies (`spring-boot-micrometer-tracing-opentelemetry`, `spring-boot-opentelemetry`, `micrometer-tracing-bridge-otel`, `opentelemetry-api`, `opentelemetry-exporter-otlp`) are all Boot-managed and already resolve in test scope in this build.

**Spec:**
- The authority on behaviour: [BUILD.md](../BUILD.md) §2, §5.3, §5.6, §5.8, §5.10, §13 and §14 item 1, and [DESIGN.md](../DESIGN.md) §4.1, rows *Probe group defaults*, *Logging default*, *Image garbage collector and memory*, *Tracing dependency*, *Tracing bridge module*, *Masking hardening*, *Structured log format*, *Failure-log burst limit semantics* and *OpenTelemetry agent tests only under `-Potel-agent`*. This plan's commit wrote those rows.
- Inputs, in the coordinator's scratchpad `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\00343b11-a124-44cb-94d4-0764a5fe68bf\scratchpad\`:
  - `research2-observability.md`: decisions 4, 10, 14 and 22. **Its decision-15 section is superseded** (decision 15 was reverted);
  - `research2-tracing-bridge.md`: the module's contents (its option (c));
  - `probe-unsampled-correlation.md`: the probe that Task 9 turns into a permanent test;
  - `research2-plan5-leftovers.md`, items 8 and 9.

  Where this plan and a memo differ, this plan wins, and the *Decisions taken* table says why.
- Conventions: [AGENTS.md](../../../AGENTS.md) and the last plan ([2026-11-13-p1-review-fixes.md](2026-11-13-p1-review-fixes.md)).

## Execution base

- **Base:** After Tony's review, the four follow-up plans' docs commits are merged into local `main` as one docs commit, with the DESIGN.md conflicts resolved once (both sides' additions and both sides' deletions kept). Each plan's feature branch starts from local `main` at that commit, or later, once the earlier plans in the order contract, data, observability, API surface are squashed. This plan is third: its branch starts once the contract and data plans are squashed.
- Branch `feature/v2-fu-observability` in the clone the coordinator names.
- **What the earlier plans change under this one, and what must survive here:**
  - The contract plan reorders the REST exception resolver (MVC handlers first) and adds the duplicate-cookie counter. Task 4 finds the REST edge's unhandled-error line by its text (`Unhandled error for`), not by today's class and line. This plan doesn't touch the counter.
  - The contract plan adds `ViolationPaths.of(Path)` in `core.internal.pipeline` (package-private): the one path format callers get. Task 1 logs violation paths with it too, and makes the class `public` so `core.internal.error` can call it.
  - The contract plan adds a call token to `SanitizedViolations` (`transient @Nullable Object call`, set once by `OperationPipeline.rejected`, read by `OperationPipeline.raisedByAnotherCall`). Task 1 moves `SanitizedViolations` to `core.internal.error`, so the token must survive the move: it stays a `transient` field, reached through `public @Nullable Object call()` and `public void markCall(Object call)` (a second call throws `IllegalStateException`), and the contract plan's nested-call tests (`NestedInvocationTest`) stay green unchanged.
  - The data plan adds `FailureLogKeys` (core `internal.pipeline`) and a loop in `OperationInterceptor.logFailure` that adds each key-value a failure carries (`TransactionConflictException`'s `sqlState`). Task 3 rewrites `logFailure`: the loop keeps reading the **original** failure, never the sanitized copy (a copy of a subclass is a plain `SynapseException`, O3, so it carries no keys). Task 3 pins it with a test.
  - The data plan adds `TRANSACTION_CONFLICT` and its classifier, which may walk causes with `CauseChain.MAX_DEPTH`. Task 2 converts every use of that constant it finds at the base.
  - This plan creates the package `core.internal.error` and its `package-info.java` (Task 1). The API-surface plan, which runs later, adds `ErrorMessages` to the same package and doesn't create the `package-info.java` again.
- Line numbers below are at `5ea62ec7`; grep for the quoted text when they have moved.
- [ ] **Baseline:** before Task 1, run the JDK 25 full build once (Task 13, Step 1's command) and record the test counts per module and any failure. A failure that exists at the base is reported to the maintainer, not fixed silently in a task.

## Global Constraints

**Branch, JDK, language**
- Never push. Never touch `C:\Users\Tony\VSCode Projects\amex-synapse`. Local `main` gets the squash only when the maintainer says so.
- Before any `./mvnw`: `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"`. For JDK 21 legs: `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never use a global `mvn`.
- Every build that reaches `api/synapse-api-grpc` or a gRPC sample adds `-Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"` (this machine only). It is written `-Dprotobuf…=…` below.
- Never `./mvnw install`. Run samples with `-am package` and `java -jar`, as their READMEs do. The archetype ITs use their own `target/it-repo`.
- `--release 21`: no Java 22+ APIs. Use records for data carriers.
- Every new package has an `@NullMarked` `package-info.java`. A test package with a main package's name has none.
- Formatting: Spotless applies locally, so never hand-format. The license header goes on every new Java file.
- Don't edit a pom or `.vscode/` while a Maven build runs (VS Code Java builds into the same `target/`).
- `LongPathsEnabled` is 0: keep new paths short, and report a tool that fails on a path over 260 characters.

**Windows**
- Every step that runs `docker` first runs `export PATH="/c/Users/Tony/AppData/Local/Programs/DockerDesktop/resources/bin:$PATH"; export MSYS_NO_PATHCONV=1`. Testcontainers needs none of this.

**Spring and Synapse rules (AGENTS.md)**
- Spring Boot first: never re-implement or alias a Boot property. A default for a Boot key goes in through an `EnvironmentPostProcessor` at lowest precedence (`DefaultPropertiesPropertySource`), skipped when the application set its own default.
- Every auto-configuration a task touches gets `ApplicationContextRunner` tests: it activates, backs off, binds its properties, and stays absent.
- A new Synapse bean backs off **by bean name** (`@ConditionalOnMissingBean(name = …)`), and the docs don't call it replaceable (BUILD §3.5). This plan adds two beans, both in the tracing module (Task 9): `synapseOpenTelemetryAgentAotCheck`, an internal bean that backs off by that name, and `openTelemetryAgent`, whose type is public OpenTelemetry API, so it backs off by the `OpenTelemetry` type, as Boot's SDK does (O11). Its other new startup code is an `EnvironmentPostProcessor` and an `ApplicationListener`, both in `spring.factories`, with no bean.
- No production concept: the environment is never used in a `@Conditional`.
- Errors never expose stack traces, class names or internal messages to callers. This plan changes only what is logged and recorded on the server side; no response changes.
- **The masking principle (decision 10):** Synapse writes no value from a caller's input or from a `@Masked` member into a log line or span it produces, except the payload line's masked JSON.
- Bug fixes start with a failing test that reproduces the bug.

**Protocol parity**
- Nothing here changes a contract. Where REST and gRPC both log (the unhandled-error lines, the DEBUG lines about unreadable input), Task 4 changes both the same way.

**Samples and archetypes**
- A task that changes a sample or an archetype template re-runs the affected README commands against the running app, from a clean state (`docker compose down -v` first where there is one), and pastes real output where it changed. If no command's output can change, the task says why in its report.
- `archetype-drift` (`tests/synapse-build-rules-tests/src/it/archetype-drift/verify.groovy`) stays green: a change to a compared file is made on both sides in the same commit.
- A new IT in a sample needs its package in `.config/jdk21-pr-excluded-its.txt`. A new IT in a root-build module carries `@Tag("smoke")` on at least one class (the `smoke-its` build-rules test).

**Tests and builds**
- `*Test` are unit tests (surefire); `*IT` are integration tests (failsafe). Neither is ever skipped. `-DskipTests` is never a passing build.
- Per-module JaCoCo floors: 80% line, 70% branch. 90% of changed lines must be covered.
- To iterate on one test (neither counts as passing):

  ```bash
  ./mvnw -q -pl <module> -am test -Dtest=Class -Dsurefire.failIfNoSpecifiedTests=false
  ./mvnw -q -pl <module> -am verify -Dit.test=ClassIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
  ```

  Each task ends with `./mvnw -pl <its modules> -am verify` (plus the protobuf flag where needed), Docker on.

**Docs**
- Every property a page names exists in the configuration metadata. After a docs change: `(cd docs && npm ci && npx antora antora-playbook.yml)`, then, from the repository root, `node docs/tools/property-tables.mjs`. Expected: no broken xref, no property row removed.

**Commits**
- Conventional Commits; `!` marks a breaking change. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only. Never `git add -A`, stash, amend or push. Leave no `*.bak` or `*.orig` files.

**Execution** (the maintainer's standing rules): every task is implemented on Opus. Each task gets an Opus review; Sonnet is used only for a fix's re-review. Task 14 is an Opus whole-branch review. At most five subagents run at once.

---

## Decisions taken for the maintainer's review

Each row is a call the decisions and memos left open, made provisionally so the task can run. Each can be reversed at review; the last column says what that costs.

| # | Item | Question left open | Choice | Reason | Alternative (cost to reverse) |
|---|---|---|---|---|---|
| O1 | 10 | Where the shared walk and sanitizer live, and their names. | New package `io.americanexpress.synapse.core.internal.error`: `Causes` (the walks), `SanitizedViolations` (moved from `internal.pipeline`, made public), `SanitizedSqlFailure` (the database stand-in) and `LoggedFailures` (`sanitize`, `forSpan`). | The edges (REST, gRPC) and data need them, so they leave the pipeline package; `internal` keeps them out of the API. | Keep them in `internal.pipeline`, made public (one package, mixed concerns). |
| O2 | 10 | The walk order once suppressed exceptions count. | Pre-order depth-first: an exception, its cause's subtree, then each suppressed exception's subtree; every instance once (identity set); no depth cap. The stand-in's message writes a suppressed hop as `suppressed <SimpleName>`. | It finds what a printer prints, in the order it prints it. | Causes first, then all suppressed (breadth-first; same result set). |
| O3 | 10 | What `sanitize` returns when a `SynapseException` holds the violation below it (item 3). | A copy of that `SynapseException` (same details, arguments, violations, message and stack trace) whose cause is the sanitized cause. Only a chain whose top isn't a `SynapseException` is replaced by the `SanitizedViolations` stand-in. A copy of a user's `SynapseException` subclass is a plain `SynapseException`; `core.adoc` says so. | The line's `errorCode`, the ECS `error.type` and the observation's error stay the Synapse error. | Replace the whole failure with the stand-in (`error.type` becomes `SanitizedViolations`). |
| O4 | 10 | What "a span never carries more than the failure line" means per category. | The operation span records `LoggedFailures.forSpan(failure)`: the cause is the sanitized cause for `INTERNAL`, and for `UNAVAILABLE` and `TIMEOUT` with a cause; every other category records no cause, except a cause that is already safe (a `SanitizedViolations`, as every pipeline rejection has), which is kept. It is the failure itself when nothing changes (so every `VALIDATION_FAILED` from the pipeline is recorded as the same instance), otherwise a copy (O3). So `OperationObservationContext.getError()` can be a copy, not the thrown instance; its Javadoc and `core.adoc` *Observability* say so, for anyone whose `ObservationHandler` checks the error's class or identity. The business span applies `forSpan` to a `SynapseException` and `sanitize` to anything else. | A client error's cause (a data exception whose message can quote a value) is never logged, so the span mustn't keep it. | Record the failure as is unless it holds a violation (spans keep client errors' raw causes). |
| O5 | 10 | How a value-built template is logged (item 6). | `path: @Name`, the constraint annotation's simple name with `@` (`number: @Size`, `card: @ValidCard`). A single-key template stays `path: {key}`. | The `@` tells a reader it is the constraint, not a message. | The bare name (`number: Size`). |
| O6 | 10 | Item 11 named only `RequestBinder`'s DEBUG lines. | gRPC's three DEBUG lines that log a conversion's message (`FieldConverter.Reading.rejected`, `FieldConverters`'s two "rejected the values" lines) change the same way. | Parity of what each edge writes; the same Jackson and conversion messages quote input. | REST only. |
| O7 | 10 | What "declares a `@Masked` member" means for whole-value masking (items 7, 8). | A `@Masked` field, record component or method on the class or a superclass, cached per class. | The places `@Masked` may sit (`@Target`). Interfaces' methods are read too, since Jackson reads annotations from them. | Fields and record components only. |
| O8 | 14 | Where the WARN for burst properties without the Log4j2 module runs. | `FailureBurstSettingsListener`, an `ApplicationListener<ApplicationPreparedEvent>` in core's `spring.factories` (logging is initialized by then). It detects the module by the class name `io.americanexpress.synapse.logging.log4j2.SynapseLoggingProperties`. | No bean, so no back-off question, and it runs for every application, slices included. | A `SmartInitializingSingleton` bean (needs a name back-off and runs only with auto-configuration). |
| O9 | 14 | Where the backend-neutral logging docs go. | A new page `logging.adoc` (*Logging*): Logback as the default, the ECS section moved from `logging-log4j2.adoc` with its `#ecs` anchor, text output, `DuplicateMessageFilter` and async notes. `logging-log4j2.adoc` keeps only the opt-in. | Every xref to `logging-log4j2.adoc#ecs` is about ECS, not Log4j2. | Keep ECS in the Log4j2 page (wrong for the default). |
| O10 | 15 | **Asked in the dispatch: should Synapse default `logging.pattern.correlation` so plain-text console logs show the correlation id?** | **No default; docs only.** `logging.adoc` shows `logging.pattern.correlation=[%X{traceId:-}-%X{spanId:-}] [%X{correlationId:-}] ` for Logback text output, and a test in `synapse-logging-logback-tests` pins that the documented line renders the id. | Text appears only when a team turns ECS off, and ECS already has the field. The key is Boot's: Boot fills it (`[traceId-spanId]`) only with tracing, so a Synapse value would replace Boot's own rendering in every service with a copy Synapse must keep in step (re-implementing a Boot default), and it would change the file pattern too. | Default it at lowest precedence through the logging EPP (one key; Synapse then owns the text layout of the correlation part). |
| O11 | 15 | How the agent recipe's two configuration classes (maintainer: move them into the module) become library code. | One auto-configuration, `io.americanexpress.synapse.tracing.opentelemetry.autoconfigure.OpenTelemetryAgentAutoConfiguration`, ordered `before` Boot's `OpenTelemetrySdkAutoConfiguration`: the `openTelemetryAgent` bean (`GlobalOpenTelemetry.get()`) in a nested configuration `@ConditionalOnClass(name = "io.opentelemetry.javaagent.bootstrap.AgentInitializer")`, and the AOT check `synapseOpenTelemetryAgentAotCheck` with no class condition. The agent bean backs off **by the `OpenTelemetry` type**, as Boot's SDK does, because that type is public OpenTelemetry API, not a Synapse internal (review N3; controller ruling, round 2); the AOT check, a Synapse internal, backs off by its `synapse…` name, and the docs don't call it replaceable. Dependency scopes mirror Boot's starter (the two Boot modules compile, the bridge and the exporter runtime), plus `opentelemetry-api` compile for `GlobalOpenTelemetry`. A real `src/main/java` gives the module its Javadoc and sources jars and the coverage gate, so no placeholder is needed. | The maintainer's call, on the `synapse-logging-log4j2` pattern (that module has its own code too). Boot's SDK backs off on any `OpenTelemetry` bean, so ordering before it is what makes the agent's SDK win. With the type back-off, a service's own `OpenTelemetry` bean wins with or without the agent, so no failure appears only where the agent runs, and the API-surface plan's `synapse…` name guard passes. | Back off by name (round 1; two `OpenTelemetry` beans under the agent when a service defines its own under another name). |
| O12 | 15 | Where the permanent unsampled-correlation test lives (the memo asked for one; none exists at 0.0 today). | In the new module, as `TracingModuleIT` (smoke), on `synapse-api-rest` in test scope: it proves the module's exact dependency set, at `management.tracing.sampling.probability=0.0`, with the probe's assertions. REST only. | The shipped set is what must give correlation; the mechanism is core's baggage, the same under gRPC. | Also a gRPC copy (one more IT; nothing protocol-specific to find). |
| O13 | 15 | `synapse-test`'s own tracer set (memo item 3). | Unchanged: it keeps the tracer-only set (bridge, no exporter) and doesn't depend on the new module. | Library jars never depend on the module, and `synapse-test` is a published Synapse jar. The overlap is two artifacts. | `synapse-test` depends on the module (one list; brings the exporter into every consumer's tests). |
| O14 | 22 | The AOT scenario that runs under the agent (`BookstoreAotIT.aotRunUnderTheAgentFailsFast`, and the archetype's copy) needs the agent jar, which only `-Potel-agent` copies now. | It moves to its own class, `BookstoreAgentAotIT` (archetype: `ServiceAgentAotIT`), which runs only with both `-Paot` and `-Potel-agent`. Failsafe's excludes become two properties, `bookstore.its.aot` (default `**/*AotIT.java`) and `bookstore.its.agent` (default `**/*Agent*IT.java`); each profile sets its own to `none`. The nightly `aot-reference` job adds `-Potel-agent`. | Profiles that each override `<excludes>` can't combine; two properties can. `-Paot verify` alone keeps working without the agent. | `-Paot` copies the agent too (the agent comes back into every AOT build). |
| O15 | 22 | How a missing property forward is caught (memo: a separate nightly step that greps the log). | The archetype module's `otel-agent` profile also sets `synapse.it.otel-agent=true` (default `false`), and the filtered `verify.groovy` reads it: when true, it requires a passing `ServiceAgentIT` and the copied jar; when false, it asserts `ServiceAgentIT` didn't run. | The check runs in every build, not only nightly, and fails on either mistake. | The nightly grep step (catches only the "true" case, only nightly). |
| O16 | 10 (maintainer, round 1) | How "an `SQLException` shows only its SQLState, vendor code and class" is built, and where the switch comes from. | `LoggedFailures.sanitize(thrown, logDatabaseMessages)`: without detail, a chain that holds an `SQLException` (and no constraint violation, which wins) is replaced from its top by a `SanitizedSqlFailure`, whose message is the classes from the top to the `SQLException` (as O2 writes them) and `SQLState <state>, vendor code <code> (<class name>)`, with the `SQLException`'s stack trace and no cause. A `SynapseException` on top is kept as a copy (O3). The wrappers' own messages go too, because Spring's and Hibernate's repeat the SQL and the database's text. With `synapse.errors.log-database-messages=true` (a new property, default `false`; maintainer, round 2) the chain is logged as it is. `synapse.errors.include-detail` keeps its one meaning, detail sent to callers. | The maintainer chose a separate switch (round 2), so turning on database text for an operator never sends detail to callers. The pipeline gets it through `OperationPipeline.Builder.logDatabaseMessages(boolean)`, which `SynapseOperationAutoConfiguration` sets from `SynapseErrorProperties.logDatabaseMessages()`; the edges read the same properties bean. | Reuse `synapse.errors.include-detail` (round 1; it then did two jobs); keep the wrappers' messages (they repeat the text). |
| O17 | 15 (maintainer, round 1) | The `OTEL_SDK_DISABLED` probe found no gap (`probe-otel-sdk-disabled.md`: with the variable, Boot's `management.opentelemetry.enabled=false`, or `otel.sdk.disabled=true`, the check passes and the MDC, baggage and outbound propagation keep the correlation id). | The tracing check doesn't change. `TracingModuleIT` gains a nested class with `management.opentelemetry.enabled=false` (the property the variable maps to) that pins it. | The decision was "fail the check if correlation breaks"; it doesn't. | Fail the check on `enabled=false` anyway (would refuse a working setup). |

---

## Review Focus

These are the inputs the decisions imply but no single happy-path test covers, most likely first. Each one is pinned by a test in the task named.

1. **A violation where the old walks stopped.** A `ConstraintViolationException` held as a suppressed exception of a commit failure, one 15 causes deep, and a cyclic chain (`A` caused by `B` caused by `A`) with no violation: the first two must be sanitized everywhere they are logged or recorded, and the third must end, not hang. Pinned in Task 1: `findsASuppressedViolationOfACause`, `findsAViolation50CausesDeep`, `aCyclicChainWithoutAViolationEnds`; Task 2: `aCycleEnds`.
2. **A database message that quotes a value.** A `NOT_FOUND` or `ALREADY_EXISTS` translated from a data exception whose message holds the caller's value (`Key (email)=(…) already exists`): its WARN line has no cause, so its span mustn't have one either. An `INTERNAL` caused by a PostgreSQL error whose text quotes the row (`Failing row contains (…)`): the ERROR line and the span show only the SQLState, vendor code and class, unless `synapse.errors.log-database-messages` is on. Pinned in Task 3: `clientErrorSpanHasNoCause`, `aRealPostgresErrorIsLoggedByStateOnly`.
3. **A platform that sets only `liveness.additional-path`.** With a store opted in to readiness, the store must never be a liveness member, so a database outage doesn't restart every pod. Pinned in Task 6: `StoreReadinessIT$AtTheGroupsAdditionalPath.storesNeverJoinLiveness`.
4. **A team that sets an OTLP endpoint, or adds Boot's starter next to the module.** With `management.opentelemetry.tracing.export.otlp.endpoint` set, a span exporter exists; without it none; and no OTLP `MeterRegistry` exists either way unless Boot's starter is added. Pinned in Task 9: `noSpanExporterWithoutAnEndpoint`, `anEndpointCreatesTheSpanExporter`, `noOtlpMeterRegistry`.
5. **The archetype IT with and without `-Dotel-agent`.** Without it, `ServiceAgentIT` must not run and the build must pass; with it, `ServiceAgentIT` must run and pass. A forward that silently fails must fail the build. Pinned in Task 11: the two `verify.groovy` branches, run once each in Step 4.

---

## File structure

New files (main code and build):

| Path | Responsibility |
|---|---|
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/error/package-info.java` | `@NullMarked` |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/error/Causes.java` | The whole-chain walks with a cycle guard (Task 1) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/error/SanitizedViolations.java` | Moved from `internal/pipeline` (Task 1) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/error/SanitizedSqlFailure.java` | The stand-in for a chain with an `SQLException` (Task 1, O16) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/error/LoggedFailures.java` | `sanitize` (Task 1), `forSpan` (Task 3) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/masking/WholeValueMask.java` | The `"***"` serializer for a type masked whole (Task 5) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/autoconfigure/LoggingDefaultsEnvironmentPostProcessor.java` | The ECS default, moved from the Log4j2 module (Task 7) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/autoconfigure/FailureBurstSettingsListener.java` | The WARN for burst properties without the Log4j2 module (Task 7) |
| `core/synapse-tracing-opentelemetry/pom.xml` | The tracing module (Task 9) |
| `core/synapse-tracing-opentelemetry/src/main/java/io/americanexpress/synapse/tracing/opentelemetry/autoconfigure/{package-info,OpenTelemetryAgentAutoConfiguration}.java` | The agent recipe, moved from the samples and the service archetype (Task 9, O11) |
| `core/synapse-tracing-opentelemetry/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Registers it (Task 9) |
| `docs/modules/ROOT/pages/logging.adoc` | The backend-neutral logging page (Task 7) |

Everything else modifies existing files, listed in each task.

---

### Task 1: One cause walk and one sanitizer in core (decision 10, items 4, 5, 6, 15, 16; database text)

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/error/{package-info,Causes,SanitizedSqlFailure,LoggedFailures}.java`.
- Move (`git mv`): `core/.../core/internal/pipeline/SanitizedViolations.java` → `core/.../core/internal/error/SanitizedViolations.java`, and its test `SanitizedViolationsTest.java` the same way. The contract plan's call token moves with it (*Execution base*).
- Modify:
  - `core/.../core/internal/pipeline/ViolationPaths.java` (the contract plan's): `public final class`, `public static String of(Path)`; nothing else changes;
  - `core/.../core/internal/pipeline/OperationInterceptor.java` (`translate`, ~L276; its Javadoc ~L258-275 loses "{@value SanitizedViolations#MAX_DEPTH} exceptions … no suppressed exception");
  - `core/.../core/internal/pipeline/OperationPipeline.java` (~L232, L274: the import; `rejected` and `raisedByAnotherCall` use `markCall` and `call()`);
  - `core/synapse-logging-log4j2/src/shared-it/java/io/americanexpress/synapse/logging/lines/FailureLineStructuredFormatIT.java` (~L149, L162: `"number: ${validatedValue} is too long"` becomes `"number: @Size"`);
  - `docs/modules/ROOT/pages/core.adoc` *Masking* (~L338-365): the walk ("the failure and its whole cause chain and suppressed exceptions"), paths (the same path the caller gets, so a map key is never written) and templates (single key, or the constraint's name); a database error is shown by SQLState, vendor code and class only, unless `synapse.errors.log-database-messages` is on (and Hibernate logs the same text itself, on `org.hibernate.orm.jdbc.error`, so a service that wants it gone sets `logging.level.org.hibernate.orm.jdbc.error=off`; the stand-in keeps the driver's stack trace, not the wrappers', so Spring's frames are not in it); drop the "10 or more causes deep, or suppressed" and "template … or a property path" bullets;
  - `docs/modules/ROOT/pages/data.adoc`: one sentence in the error-translation section on what the failure line shows of a database error, and that Hibernate logs the database's text itself on `org.hibernate.orm.jdbc.error` (silenced by `logging.level.org.hibernate.orm.jdbc.error=off`; the name is `SQLExceptionLogging.ERROR_NAME` in hibernate-core 7.4.13, read with `javap`).
- Test: create `core/.../core/internal/error/{CausesTest,LoggedFailuresTest}.java`; modify the moved `SanitizedViolationsTest` and `core/.../core/internal/pipeline/OperationInterceptorTest.java`.

**Interfaces:**
- Consumes: `ViolationPaths.of(jakarta.validation.Path)` (contract plan; made public here): dots between names, `[i]` for an indexed element, nothing for a set element or a map key, no container-node names.
- Produces (all `public`, because `internal.pipeline`, the API plugins and data use them):

```java
// Causes (final, no instances)
/** thrown, then each cause; every instance once (identity); no depth cap. */
public static List<Throwable> chain(Throwable thrown);
/** Pre-order: an exception, its cause's subtree, then each suppressed exception's subtree; every instance once (O2). */
public static List<Throwable> withSuppressed(Throwable thrown);

// SanitizedViolations (public final class, extends RuntimeException; constructors stay private)
public static SanitizedViolations ofRejection(Collection<? extends ConstraintViolation<?>> constraints, List<String> fixed);
public static SanitizedViolations replacing(Throwable thrown, ConstraintViolationException found); // message: the classes from thrown to found, " > " between them, a suppressed hop as "suppressed <SimpleName>", then ": " and the violations
public static @Nullable ConstraintViolationException find(Throwable thrown);   // first in Causes.withSuppressed
public static @Nullable SanitizedViolations in(Throwable failure);             // first in Causes.withSuppressed
public String violations();
public @Nullable Object call();          // the contract plan's call token
public void markCall(Object call);       // set once; a second call throws IllegalStateException
static String describe(ConstraintViolation<?> violation);  // ViolationPaths.of(path) + ": {key}" when the template matches ^\{[^{}]+\}$, else + ": @<annotation simple name>" (O5)

// SanitizedSqlFailure (public final class, extends RuntimeException; no cause, no suppressed)
public static SanitizedSqlFailure replacing(Throwable thrown, SQLException found);  // message: the classes from thrown to found (as replacing above), then ": SQLState <state>, vendor code <code> (<found's class name>)"; found's stack trace (the driver's; the wrappers' frames go, and the docs say so)

// LoggedFailures (final, no instances)
/**
 * thrown itself when Causes.withSuppressed(thrown) holds no ConstraintViolationException and (unless logDatabaseMessages)
 * no SQLException; otherwise the violation stand-in (which wins) or the SQL stand-in, with a SynapseException on top
 * kept as a copy (O3, O16).
 */
public static Throwable sanitize(Throwable thrown, boolean logDatabaseMessages);
```

- `MAX_DEPTH` is removed. The violations key and the stand-in keep today's form: items sorted, joined by `", "`.

- [ ] **Step 1: Write the failing tests** (Review Focus 1 and 2):

```java
// CausesTest
@Test void chainListsEachCauseOnce()
@Test void chainStopsAtACycle()                         // A caused by B caused by A (initCause) → [A, B]
@Test void chainHasNoDepthCap()                         // 50 nested causes → 51 entries
@Test void withSuppressedVisitsCauseBeforeSuppressed()  // A(cause B, suppressed C(cause D)) → [A, B, C, D]
@Test void withSuppressedStopsAtACycle()                // C suppressed in A, A suppressed in C → [A, C]
// SanitizedViolationsTest (moved; the depth tests replaced)
@Test void findsAViolation50CausesDeep()
@Test void findsASuppressedViolation()
@Test void findsASuppressedViolationOfACause()          // TransactionSystemException → cause RollbackException with the violation suppressed
@Test void aCyclicChainWithoutAViolationEnds()          // assertTimeoutPreemptively(Duration.ofSeconds(1), …) → null
@Test void theLoggedPathIsTheCallersPath()              // for each case of the contract plan's ViolationPathsTest (map key, set element, list index, map value property): describe(v) starts with ViolationPaths.of(v.getPropertyPath()) + ": "; a key "4111111111111111" never appears
@Test void singleKeyTemplateIsLogged()                  // @Size default message → "{jakarta.validation.constraints.Size.message}"
@Test void valueBuiltTemplateIsTheConstraintName()      // @Size(message = "${validatedValue} is too long") → "number: @Size"; a custom @ValidCard validator with buildConstraintViolationWithTemplate("bad " + value) → "card: @ValidCard"
@Test void replacingNamesASuppressedHop()               // "TransactionSystemException > RollbackException > suppressed ConstraintViolationException: …"
@Test void theCallTokenSurvivesTheMove()                // markCall(token) then call() isSameAs token; a second markCall throws IllegalStateException
// LoggedFailuresTest (the SQL cases: new DataIntegrityViolationException("… Key (email)=(a@b.c) …", new SQLException("ERROR: … Key (email)=(a@b.c) already exists", "23505", 7)))
@Test void aCleanChainIsReturnedAsIs()                  // isSameAs, with and without detail
@Test void aViolationIsReplaced()                       // RuntimeException(cause CVE) → SanitizedViolations
@Test void aSynapseExceptionOnTopIsKept()               // SynapseException(UNAVAILABLE, CVE) → SynapseException, same code, args, message and stack trace; cause is SanitizedViolations
@Test void aSuppressedViolationIsReplaced()
@Test void anSqlExceptionShowsOnlyItsStateCodeAndClass()   // → SanitizedSqlFailure; message ends "SQLState 23505, vendor code 7 (java.sql.SQLException)"; no "a@b.c" and no "Key (" anywhere in the printed stack trace
@Test void anSqlExceptionUnderASynapseExceptionIsReplacedBelowIt()   // SynapseException(INTERNAL, that chain) → a SynapseException copy whose cause is SanitizedSqlFailure
@Test void logDatabaseMessagesKeepsTheDatabaseText()          // sanitize(chain, true) isSameAs chain
@Test void aViolationWinsOverAnSqlException()           // both in one chain → SanitizedViolations
@Test void thePrintedStackTraceHoldsNoValue()           // printStackTrace into a StringWriter: no "4111111111111111" and no "a@b.c" for each case above without detail
// OperationInterceptorTest
@Test void aSuppressedViolationIsInternal()             // the operation throws RuntimeException with a suppressed CVE → INTERNAL, cause SanitizedViolations
@Test void aViolationFifteenCausesDeepIsInternal()
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='CausesTest,SanitizedViolationsTest,LoggedFailuresTest,OperationInterceptorTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compile errors, then the depth, suppressed, path, template and SQL tests fail.
- [ ] **Step 3: Implement** `Causes`, the move (the token included), `describe` on `ViolationPaths.of`, `SanitizedSqlFailure` and `sanitize`. `find` and `in` use `Causes.withSuppressed`. `OperationInterceptor.translate` keeps its rule (only a violation that is the failure itself and of the input reaches the translators); it now sees deeper and suppressed violations too. The callers of `sanitize` are wired in Tasks 3 and 4.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,core/synapse-logging-log4j2,tests/synapse-logging-logback-tests -am verify` (the shared failure-line IT runs on both backends; the contract plan's `NestedInvocationTest` and `ViolationPathsTest` stay green unchanged).
- [ ] **Step 5: Commit**: `fix(core): find constraint violations in the whole cause chain and suppressed exceptions, log them by the caller's path, and show a database error by its state only`.

### Task 2: Data walks the whole cause chain (decision 10, item 16)

**Files:**
- Modify:
  - `data/synapse-data-core/src/main/java/io/americanexpress/synapse/data/internal/error/CauseChain.java` (`MAX_DEPTH` removed; `match` iterates `Causes.chain(exception)`; `MAX_REPLACED = 3` stays; class Javadoc: "the thrown exception and its whole cause chain, each exception once");
  - `.../data/internal/error/LostConnections.java` (the three loops at ~L132, L156, L177);
  - every other main class that uses `CauseChain.MAX_DEPTH` at the base (`grep -rn "CauseChain.MAX_DEPTH" data/`; the data plan's conflict classifier among them);
  - `docs/modules/ROOT/pages/data.adoc`: any "10 causes" wording (`grep -n "cause" docs/modules/ROOT/pages/data.adoc`).
- Test: `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/error/{CauseChainTest,LostConnectionsTest}.java`.

**Interfaces:**
- Consumes: `Causes.chain(Throwable)` (Task 1). Produces: no new type. `CauseChain.translate`'s signature is unchanged.

- [ ] **Step 1: Write the failing tests** (replace `CauseChainTest`'s `MAX_DEPTH == 10` assertion):

```java
// CauseChainTest
@Test void findsAnError30CausesDeep()      // a DuplicateKeyException under 30 RuntimeException wrappers → ALREADY_EXISTS
@Test void aCycleEnds()                    // A caused by B caused by A, no error → null, within 1 s
@Test void rollbackReplacementIsStillBounded()   // four nested TransactionSystemExceptions with application exceptions → only three are followed (unchanged)
// LostConnectionsTest
@Test void aStateDeepInTheChainCounts()    // SQLException("x", "08006") 20 causes down → lost
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest='CauseChainTest,LostConnectionsTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core,data/synapse-data-jdbc,data/synapse-data-jpa,data/synapse-data-postgres -am verify`.
- [ ] **Step 5: Commit**: `fix(data): walk a failure's whole cause chain, with a cycle guard, to translate it`.

### Task 3: The failure line and both spans carry only sanitized causes (decision 10, items 2, 3; database text)

**Files:**
- Modify:
  - `core/.../core/internal/error/LoggedFailures.java` (`forSpan`);
  - `core/.../core/internal/pipeline/OperationPipeline.java`: `Builder logDatabaseMessages(boolean logDatabaseMessages)` (default `false`), handed to both interceptors;
  - `core/.../core/autoconfigure/SynapseOperationAutoConfiguration.java` (where the pipeline is built): `.logDatabaseMessages(errorProperties.logDatabaseMessages())` from `SynapseErrorProperties`, which gains `@DefaultValue("false") boolean logDatabaseMessages` (`synapse.errors.log-database-messages`; its Javadoc is the metadata description: "Whether Synapse's failure lines and spans include a database error's own message. Off by default in every environment; the SQLState, vendor code and class are always logged."). The record's `includeDetail` Javadoc drops "the full detail is always logged server-side". Every `new SynapseErrorProperties(` in tests (grep) gets the third argument;
  - `core/.../core/internal/pipeline/OperationInterceptor.java`: `logFailure` (~L333-361) logs `LoggedFailures.sanitize(failure, logDatabaseMessages)` for `INTERNAL` and `LoggedFailures.sanitize(cause, logDatabaseMessages)` for `UNAVAILABLE`/`TIMEOUT` with a cause, and reads the `violations` key with `SanitizedViolations.in(<the logged throwable>)`. The data plan's `FailureLogKeys` loop keeps reading the **original** `failure` (*Execution base*). The `catch (Throwable thrown)` block records `observation.error(LoggedFailures.forSpan(failure, logDatabaseMessages))` (~L203). The `Error` branch (~L199) is unchanged;
  - `core/.../core/internal/pipeline/BusinessInterceptor.java` `observed` (~L91): `observation.error(thrown instanceof SynapseException s ? LoggedFailures.forSpan(s, logDatabaseMessages) : LoggedFailures.sanitize(thrown, logDatabaseMessages))`; the `outcome`/`error.type` tags keep reading the raw `failure`;
  - `core/.../core/observability/OperationObservationContext.java` (`error()` Javadoc: the recorded error can be a copy carrying only what the failure line logs, O4);
  - `core.adoc` *Masking*: the item-3 bullet and the business-span bullet leave the "can still reach" list; a copied failure is a plain `SynapseException`, so a subclass's own type and fields are not on the line or the span (O3); *Observability* gains: "A span records the failure with only the cause the failure line logs: none for a client error. The recorded error can be a copy of the thrown exception, so an `ObservationHandler` shouldn't rely on its identity or subclass."
- Test: `core/.../core/internal/error/LoggedFailuresTest.java`, `core/.../core/internal/pipeline/{OperationInterceptorTest,BusinessInterceptorTest}.java`, `core/.../core/autoconfigure/{SynapseOperationAutoConfigurationTest,SynapseCoreAutoConfigurationTest}.java`; create `data/synapse-data-postgres/src/test/java/io/americanexpress/synapse/data/postgres/DatabaseErrorLogIT.java` (Testcontainers PostgreSQL, as the module's other ITs; not smoke).

**Interfaces:**
- Produces:

```java
// LoggedFailures
/**
 * The failure as the failure line logs it: cause = sanitize(cause, logDatabaseMessages) for INTERNAL, and for UNAVAILABLE
 * and TIMEOUT with a cause; for every other category no cause, unless the cause is a SanitizedViolations, which is
 * kept. The failure itself when that changes nothing; otherwise a SynapseException with the same details, args,
 * violations, message and stack trace (O3, O4).
 */
public static SynapseException forSpan(SynapseException failure, boolean logDatabaseMessages);
// OperationPipeline.Builder
public Builder logDatabaseMessages(boolean logDatabaseMessages);
```

- A copy is built with `SynapseException.ofViolations(details, violations)` when there are violations, else `new SynapseException(details, args.toArray())`, then `initCause(cause)` when there is one, and `setStackTrace(failure.getStackTrace())`.

- [ ] **Step 1: Write the failing tests** (Review Focus 2):

```java
// LoggedFailuresTest
@Test void forSpanKeepsACleanInternalFailure()            // isSameAs
@Test void forSpanDropsTheCauseOfAClientError()           // NOT_FOUND with a cause → getCause() null; code, args, message and stack trace equal
@Test void forSpanSanitizesTheCauseOfUnavailable()        // UNAVAILABLE caused by a CVE → cause is SanitizedViolations
@Test void forSpanKeepsViolations()                       // VALIDATION_FAILED ofViolations → same violations, no cause, isSameAs
@Test void forSpanKeepsAPipelineRejection()               // VALIDATION_FAILED whose cause is a SanitizedViolations (as OperationPipeline.rejected builds it) → isSameAs
@Test void forSpanShowsAnInternalDatabaseErrorByStateOnly()   // INTERNAL caused by the Task 1 SQL chain → cause SanitizedSqlFailure; with logDatabaseMessages → isSameAs
// OperationInterceptorTest (a test ObservationHandler captures context.getError(); OutputCaptureExtension for the line)
@Test void synapseExceptionWithAViolationCauseIsLoggedSanitized()  // service throws SynapseException(UNAVAILABLE, CVE about a @Masked value): the WARN line has no value, errorCode UNAVAILABLE; and the same with INTERNAL at ERROR
@Test void clientErrorSpanHasNoCause()                    // a translator maps a DuplicateKeyException("Key (email)=(a@b.c) already exists") to ALREADY_EXISTS → recorded error has code ALREADY_EXISTS and no cause
@Test void internalSpanHasTheSanitizedCause()             // operation throws a CVE → recorded error INTERNAL, cause SanitizedViolations
@Test void internalLineShowsADatabaseErrorByStateOnly()   // operation throws the Task 1 SQL chain → ERROR line has "SQLState 23505", no "a@b.c"; with logDatabaseMessages(true) the line has "a@b.c"
@Test void failureLogKeysComeFromTheOriginalFailure()     // a SynapseException subclass implementing FailureLogKeys {"sqlState": "40P01"}, category INTERNAL, caused by a CVE: the line is sanitized and still has sqlState=40P01
// BusinessInterceptorTest
@Test void businessSpanErrorIsSanitized()                 // business throws RuntimeException(cause CVE with the masked value) → recorded error's printed stack trace has no value; error.type tag still _OTHER
@Test void businessSpanOfAClientErrorHasNoCause()
// SynapseOperationAutoConfigurationTest
@Test void logDatabaseMessagesReachesThePipeline()       // synapse.errors.log-database-messages=true → an INTERNAL SQL failure's line keeps the database text; unset → it doesn't
@Test void includeDetailDoesNotLogDatabaseMessages()        // synapse.errors.include-detail=true alone → the line has the SQLState, not the text
// SynapseCoreAutoConfigurationTest
@Test void bindsLogDatabaseMessages()                     // ApplicationContextRunner: unset → false; synapse.errors.log-database-messages=true → true; include-detail and log-database-messages bind independently
// DatabaseErrorLogIT (synapse-data-postgres)
@Test void aRealPostgresErrorIsLoggedByStateOnly()        // a statement the data translator leaves INTERNAL whose PostgreSQL text quotes the row (for example a CHECK violation, 23514, "Failing row contains (…secret-4111…)"; name the state used in the report): the ERROR line has the SQLState and no "secret-4111"
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='LoggedFailuresTest,OperationInterceptorTest,BusinessInterceptorTest,SynapseOperationAutoConfigurationTest,SynapseCoreAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`, and the IT command for `DatabaseErrorLogIT` in `data/synapse-data-postgres`, Docker on.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,core/synapse-test,core/synapse-logging-log4j2,tests/synapse-logging-logback-tests,data/synapse-data-postgres,api/synapse-api-rest,api/synapse-api-grpc -am verify -Dprotobuf…=…`, Docker on. The edges' observation ITs (`RestEdgeIT`, `GrpcEdgeIT`) must stay green; a test that asserted the recorded error's identity or cause is updated to O4 and named in the report.
- [ ] **Step 5: Commit**: `fix(core): log and record only sanitized causes, give a span no more than the failure line, and show database errors by state`.

### Task 4: The edges' unhandled-error lines and DEBUG lines (decision 10, items 1, 11)

**Files:**
- Modify:
  - REST: the method that writes `"Unhandled error for {} {}"` (at `5ea62ec7`, `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/error/RestExceptionClassifier.java:139`, `logUnhandled`; the contract plan may have moved it) logs `LoggedFailures.sanitize(failure, properties.logDatabaseMessages())`, with the `SynapseErrorProperties` the REST error beans already take;
  - REST: `api/synapse-api-rest/.../internal/binding/RequestBinder.java`, the six DEBUG lines (~L471, L668, L779, L893, L907, L944): log the component (or record type) and `e.getClass().getName()`, never the exception or its message;
  - gRPC: `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/internal/error/SynapseGrpcExceptionHandler.java` `logUnhandled` (~L105) logs `LoggedFailures.sanitize(failure, logDatabaseMessages)`, from the `SynapseErrorProperties` its `GrpcStatusWriter` already reads;
  - gRPC: `.../internal/mapping/FieldConverter.java` (`Reading.rejected`, ~L159: drop the "stays in the server's debug log" comment) and `.../internal/mapping/FieldConverters.java` (~L714, L717), as for REST (O6);
  - `core.adoc` *Masking*: the edges bullet leaves the list; one sentence says that a DEBUG line about unreadable input names the exception class, not its message.
- Test: `api/synapse-api-rest/src/test/java/.../internal/error/RestErrorsIT.java`, `.../internal/binding/RequestBinderTest.java`; `api/synapse-api-grpc/src/test/java/.../internal/error/SynapseGrpcExceptionHandlerTest.java` and the module's `FieldConverters` tests (`FieldConvertersTest` if it exists; otherwise the class that tests `Reading`, named in the report).

**Interfaces:**
- Consumes: `LoggedFailures.sanitize(Throwable, boolean)` (Task 1). Produces: nothing new.

- [ ] **Step 1: Write the failing tests** (each captures output with `OutputCaptureExtension`; DEBUG tests raise the logger `io.americanexpress.synapse.api.rest` / `.api.grpc` to DEBUG for the test):

```java
// RestErrorsIT
@Test void escapeHatchViolationIsLoggedSanitized()   // a @Validated @RestController method, @Size(max = 4, message = "${validatedValue} is too long") String pan, called with "4111111111111111": 500 as today; the ERROR line has "pan" and "@Size" and no "4111111111111111"
@Test void escapeHatchDatabaseErrorIsLoggedByState() // an escape-hatch controller that throws the Task 1 SQL chain: 500; the ERROR line has "SQLState 23505", no "a@b.c"
// RequestBinderTest
@Test void unreadableBodyDebugLineHasNoValue()        // a LocalDate body property sent as "secret-4111": DEBUG line names the component and the exception class, not "secret-4111"
@Test void unreadableVersionDebugLineHasNoValue()     // If-Match "W/\"secret-4111\"" on a Long version
// SynapseGrpcExceptionHandlerTest
@Test void handWrittenServiceViolationIsLoggedSanitized()   // a CVE about a @Masked value → INTERNAL to the caller as today; the ERROR line has no value
@Test void handWrittenServiceDatabaseErrorIsLoggedByState()
// FieldConverters tests
@Test void rejectedValueDebugLineHasNoValue()          // a string that fails a LocalDate conversion: DEBUG line has the path and the class, not the value
```

- [ ] **Step 2: Run to verify failure:** the unit tests with the module's `test` command, and `RestErrorsIT` with the IT command for `api/synapse-api-rest`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest,api/synapse-api-grpc,api/synapse-test-rest,api/synapse-test-grpc -am verify -Dprotobuf…=…`.
- [ ] **Step 5: Commit**: `fix(rest,grpc): log unhandled failures sanitized and unreadable input without its message`.

### Task 5: Types Jackson writes with their own serializer are masked whole (decision 10, items 7–10, 12–14)

**Files:**
- Create: `core/.../core/internal/masking/WholeValueMask.java`.
- Modify:
  - `core/.../core/internal/masking/MaskingModule.java`: the modifier overrides `modifySerializer` (`@JsonValue` and module serializers); `setupModule` inserts an annotation introspector whose `findSerializer` returns `WholeValueMask` for a class that declares a `@Masked` member (class-level `@JsonSerialize(using = …)`), O7. Both only on the log mapper, because only `LogMasking` registers the module;
  - `core/.../core/masking/Masked.java` Javadoc (the whole-value rule; `toString()` isn't covered);
  - `core.adoc` *Masking*: rewrite the section's remaining lists into what is guaranteed (BUILD 5.6) and what is documented only: a value logged through `toString()` (a record's generated `toString` prints every component; override it on records with `@Masked` components), a `SynapseException` argument (never pass a `@Masked` value as one: it is part of the message, the failure line and the span status), and a library that logs the exception itself. Recommend `HibernateConstraintValidatorContext.addMessageParameter` over building a template with the value.
- Test: `core/.../core/internal/masking/MaskingModuleTest.java`.

**Interfaces:**
- Produces (package-private in `internal.masking`):

```java
/** Writes "***" for the whole value; logs one DEBUG line per type the first time ("{} was logged as \"***\": Jackson writes it with its own serializer, so its @Masked members can't be masked one by one"). */
final class WholeValueMask extends ValueSerializer<Object> { static WholeValueMask forType(Class<?> type); }
/** Whether the class or a superclass or interface declares a @Masked field, record component or method; cached. */
static boolean declaresMasked(Class<?> type);   // in MaskingModule
```

- Before writing, check Jackson 3.1.7's exact signatures with `javap` on the jar in `~/.m2`: `tools.jackson.databind.ser.ValueSerializerModifier#modifySerializer`, `tools.jackson.databind.introspect.JacksonAnnotationIntrospector#findSerializer`, and the bean serializer's base class (`BeanSerializerBase`), which `modifySerializer` leaves alone. Record them in the report.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void jsonValueTypeWithAMaskedMemberIsMaskedWhole()   // record Pan(@Masked String value) { @JsonValue String json() { return value; } } in Card(Pan pan, String holder) → {"pan":"***","holder":"A"}
@Test void classSerializerTypeIsMaskedWhole()              // @JsonSerialize(using = PanSerializer.class) class Pan { @Masked String value; } → "***"
@Test void moduleSerializerTypeIsMaskedWhole()             // a SimpleModule serializer for Pan registered on the base mapper → "***"
@Test void aTypeWithoutMaskedMembersKeepsItsSerializer()   // a @JsonValue type with no @Masked member → its own output
@Test void aBeanStillMasksMemberByMember()                 // today's output, kept
@Test void theBaseMapperIsUntouched()                      // the JsonMapper LogMasking was built from still writes the real values for all three types
@Test void maskingWholeLogsOneDebugLinePerType()           // two values of Pan → one DEBUG line
```

- [ ] **Step 2: Run to verify failure** (today the first three write the value).
- [ ] **Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,core/synapse-logging-log4j2,tests/synapse-logging-logback-tests -am verify`.
- [ ] **Step 5: Commit**: `fix(core): mask a type with its own serializer whole, and state what masking guarantees`.

### Task 6: The liveness default, and both stand down without the probes (decision 4)

**Files:**
- Modify:
  - `core/.../core/autoconfigure/HealthDefaultsEnvironmentPostProcessor.java`: one generalised rule per group (`readiness` → `readinessState`, `liveness` → `livenessState`), each decided alone; nothing is set when `spring.autoconfigure.exclude` lists `PROBES_AUTO_CONFIGURATION`; class Javadoc;
  - `data/synapse-data-core/src/main/java/.../internal/health/StoreReadinessGroups.java` (Javadoc: stores never join liveness);
  - `core.adoc` (~L561-567: both groups, and the exclusion rule with its `@SpringBootApplication(exclude = …)` limit), `data.adoc` health section (one sentence: stores never join liveness, even with only `liveness.additional-path` set).
- Test: `core/.../core/autoconfigure/HealthDefaultsEnvironmentPostProcessorTest.java`; `data/synapse-data-core/src/test/java/.../internal/health/StoreReadinessIT.java`.

**Interfaces:**
- Produces (in the EPP; the order stays `ConfigDataEnvironmentPostProcessor.ORDER + 2`):

```java
static final String READINESS_INCLUDE = "management.endpoint.health.group.readiness.include";  // renamed from INCLUDE_PROPERTY
static final String LIVENESS_INCLUDE = "management.endpoint.health.group.liveness.include";
static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";
/** Bound with Binder as a List<String> (a list, a comma string or indexed keys), trimmed; true when it holds PROBES_AUTO_CONFIGURATION. */
private static boolean probesExcluded(ConfigurableEnvironment environment);
private static boolean configuresTheGroup(ConfigurableEnvironment environment, ConfigurationPropertyName group, ConfigurationPropertyName include);
```

- [ ] **Step 1: Write the failing tests** (Review Focus 3):

```java
// HealthDefaultsEnvironmentPostProcessorTest (the existing "a liveness key sets nothing" assertion in noReadinessKeySetsNothing becomes the liveness case below)
@Test void livenessAdditionalPathAppliesTheLivenessDefault()   // only liveness.additional-path → liveness include livenessState; readiness include absent
@Test void eachGroupIsDecidedAlone()                           // only readiness.additional-path → readiness include set; liveness include absent
@Test void userLivenessIncludeWins()                           // liveness.include: [livenessState, diskSpace] → both, nothing else
@Test void anExcludedProbesAutoConfigurationSetsNothing()      // spring.autoconfigure.exclude as a list, a comma string with spaces, and include[1] form → neither include
@Test void anotherExclusionKeepsTheDefaults()                  // exclude lists another class → both defaults as configured
// StoreReadinessIT$AtTheGroupsAdditionalPath: add liveness.additional-path=server:/livez
@Test void storesNeverJoinLiveness()                           // liveness members == [livenessState]; db/books not a member; /livez UP while the store is DOWN
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest=HealthDefaultsEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`, and the `StoreReadinessIT` IT command for `data/synapse-data-core`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,data/synapse-data-core,samples/multi-store/service-orders,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`, Docker on. No sample sets a liveness key, so no README output changes; say so in the report.
- [ ] **Step 5: Commit**: `fix(core): default the liveness group's include too, and set neither default without Boot's probes`.

### Task 7: The ECS default moves into core, and burst properties without Log4j2 WARN (decision 14)

**Files:**
- Create: `core/.../core/autoconfigure/{LoggingDefaultsEnvironmentPostProcessor,FailureBurstSettingsListener}.java`; `docs/modules/ROOT/pages/logging.adoc` (O9, O10).
- Modify:
  - `core/synapse-core/src/main/resources/META-INF/spring.factories` (register both);
  - `core/synapse-logging-log4j2/src/main/java/.../log4j2/Log4j2DefaultsEnvironmentPostProcessor.java`: keeps only the Logback-present check; Javadoc says the ECS default is core's;
  - `docs/modules/ROOT/nav.adoc` (*Logging* before *Log4j2 logging*); the xrefs to `logging-log4j2.adoc#ecs` in `core.adoc`, `rest.adoc` (~L1077), `grpc.adoc` (~L780) and `opentelemetry-agent.adoc` (~L174) point at `logging.adoc#ecs`; `core.adoc` *Logging* (~L420) links `logging.adoc` instead of the Log4j2 page.
- Move: `core/synapse-logging-log4j2/src/test/java/.../log4j2/Log4j2DefaultsPrecedenceTest.java` and its three `log4j2-defaults-*.properties` → `core/synapse-core/src/test/java/.../core/autoconfigure/LoggingDefaultsPrecedenceTest.java` and `core/synapse-core/src/test/resources/logging-defaults-*.properties`.
- Test: create `core/.../core/autoconfigure/{LoggingDefaultsEnvironmentPostProcessorTest,FailureBurstSettingsListenerTest}.java`; trim `Log4j2DefaultsEnvironmentPostProcessorTest` to the Logback check; add the O10 test to `tests/synapse-logging-logback-tests/src/test/java/io/americanexpress/synapse/logging/logback/TextCorrelationPatternIT.java` (with its `package-info.java`; `@Tag("smoke")`).

**Interfaces:**
- Produces:

```java
final class LoggingDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String STRUCTURED_FORMAT_PROPERTY = "logging.structured.format.console";  // default "ecs"
    // getOrder(): ConfigDataEnvironmentPostProcessor.ORDER + 1; same skip-if-the-application-set-a-default guard as today's Log4j2 EPP
}
final class FailureBurstSettingsListener implements ApplicationListener<ApplicationPreparedEvent> {
    static final String PREFIX = "synapse.logging.failure-burst";
    static final String LOG4J2_MODULE_CLASS = "io.americanexpress.synapse.logging.log4j2.SynapseLoggingProperties";
    FailureBurstSettingsListener();                    // LOG4J2_MODULE_CLASS
    FailureBurstSettingsListener(String moduleClass);  // tests
}
```

- The WARN, once per application, when any source holds a key under `PREFIX` (`ConfigurationPropertySources.get(environment)`, `containsDescendantOf`) and the module class is absent: `synapse.logging.failure-burst.* is set, but the burst limit needs synapse-logging-log4j2 (Log4j2), which is not on the class path, so the setting has no effect. Remove the properties, or add the module (see "Log4j2 logging" in the docs).`
- `logging.adoc` holds: Logback is the default, brought by `spring-boot-starter-actuator` and `spring-boot-starter-test` (a service without a starter gets no backend, and SLF4J's no-op drops every line); the *ECS JSON by default* section moved whole from `logging-log4j2.adoc` (anchor `#ecs`), now saying `synapse-core` sets the default; *Text output* with O10's pattern; a warning against Logback's `DuplicateMessageFilter` on `io.americanexpress.synapse.operation` (every failure line has the raw message `Operation {} failed with {} ({}): {}`, and the filter compares raw messages, so it drops all but the first few); *Async* (Logback's `AsyncAppender` drops TRACE, DEBUG and INFO when its queue is 80% full, so payload lines go first; `discardingThreshold=0` keeps everything); no burst limit on Logback (point to platform or log-shipper limits, or the Log4j2 module).

- [ ] **Step 1: Write the failing tests:**

```java
// LoggingDefaultsEnvironmentPostProcessorTest
@Test void defaultIsEcs()
@Test void applicationValueWins()                 // logging.structured.format.console=logstash in application properties
@Test void anApplicationsOwnDefaultWins()         // SpringApplication.setDefaultProperties
@Test void isRegisteredInSpringFactories()
// LoggingDefaultsPrecedenceTest (moved; same cases, now against core)
// FailureBurstSettingsListenerTest (OutputCaptureExtension)
@Test void warnsWhenSetWithoutTheModule()         // rate=10 only → one WARN with the text above
@Test void silentWithTheModule()                  // new FailureBurstSettingsListener(String.class.getName())
@Test void silentWhenNotSet()
@Test void anEnvironmentVariableCounts()          // SYNAPSE_LOGGING_FAILUREBURST_RATE
// TextCorrelationPatternIT (Logback; logging.structured.format.console set empty so Boot writes text (the test asserts the line isn't JSON), O10's pattern set, a tracer from synapse-test)
@Test void theDocumentedPatternPrintsTheCorrelationId()   // a failing operation's text line contains "[<correlation id>]"
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='LoggingDefaults*Test,FailureBurstSettingsListenerTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement**, write `logging.adoc` and the xrefs, and build the docs (Global Constraints, *Docs*).
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,core/synapse-logging-log4j2,tests/synapse-logging-logback-tests -am verify`. `FailureLineStructuredFormatIT` runs on Logback with core's default now.
- [ ] **Step 5: Commit**: `feat(core): default ECS console logs for either backend, and warn when burst settings have no effect`.

### Task 8: Log4j2 becomes opt-in, and its burst limit covers WARN only (decision 14, M2)

**Files:**
- Modify:
  - `core/synapse-logging-log4j2/src/main/java/.../log4j2/FailureBurstLimitListener.java` (`Level.WARN`; re-install on reconfiguration);
  - `core/synapse-logging-log4j2/pom.xml` (`<description>`: an opt-in Log4j2 backend);
  - `core/synapse-logging-log4j2/src/main/java/.../log4j2/package-info.java` and `SynapseLoggingProperties.java` Javadoc (the WARN semantics; property descriptions in the configuration metadata say "WARN and less severe");
  - `docs/modules/ROOT/pages/logging-log4j2.adoc`: title *Log4j2 logging (opt-in)*; *Add the module* excludes the logging starter from `spring-boot-starter-actuator` and `spring-boot-starter-test` (no OpenTelemetry starter any more); the ECS section is gone (Task 7); *Burst limit*: WARN and less severe share the budget, ERROR and FATAL always pass, and the filter is installed again after a reconfiguration; the agent's Log4j MDC fields stay documented here (`log4j-context-data`);
  - `docs/modules/ROOT/pages/parent.adoc` (~L27-29): services use Logback; the parent manages `synapse-logging-log4j2` for teams that opt in.
- Test: `core/synapse-logging-log4j2/src/test/java/.../log4j2/FailureBurstLimitListenerTest.java`, `Log4j2LoggingIT.java`.

**Interfaces:**
- Produces (package-private):

```java
/** Puts a BurstFilter at Level.WARN on the failure logger, and keeps one PropertyChangeListener per LoggerContext for LoggerContext.PROPERTY_CONFIG that installs it again on the new configuration. A second call replaces the listener, never adds one. */
static void install(LoggerContext context, float rate, int maxBurst);
```

- Before writing, confirm `org.apache.logging.log4j.core.LoggerContext#PROPERTY_CONFIG`, `addPropertyChangeListener` and `removePropertyChangeListener` with `javap` on the Boot-managed `log4j-core` jar, and record the version.

- [ ] **Step 1: Write the failing tests:**

```java
// FailureBurstLimitListenerTest (rate 0.001, max-burst 2)
@Test void errorsPassAfterAWarnFlood()            // 5 WARN then 1 ERROR on the failure logger → 2 WARN and the ERROR reach the appender
@Test void warnsShareTheBudget()                  // 5 WARN → 2
@Test void theFilterSurvivesAReconfigure()        // install, context.reconfigure() → the failure logger has one BurstFilter at WARN
@Test void installingTwiceKeepsOneListenerAndOneFilter()   // install twice, reconfigure → one BurstFilter
// Log4j2LoggingIT
@Test void aWarnFloodDoesNotSilenceInternalFailures()      // an app with the burst properties: 10 UNAVAILABLE calls then one INTERNAL → the INTERNAL ERROR line is logged
```

- [ ] **Step 2: Run to verify failure** (today the ERROR is dropped, and the filter is gone after `reconfigure()`).
- [ ] **Step 3: Implement**, and update the docs.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-logging-log4j2 -am verify`, then build the docs.
- [ ] **Step 5: Commit**: `fix(logging)!: limit only WARN failure lines on Log4j2, keep the limit across reconfiguration, and make Log4j2 opt-in`.

### Task 9: The `synapse-tracing-opentelemetry` module, with the agent recipe's configuration (decision 15, reverted form)

**Files:**
- Create:
  - `core/synapse-tracing-opentelemetry/pom.xml`;
  - `core/synapse-tracing-opentelemetry/src/main/java/io/americanexpress/synapse/tracing/opentelemetry/autoconfigure/{package-info,OpenTelemetryAgentAutoConfiguration}.java` (O11), from the bookstore's `config/OpenTelemetryAgentConfiguration.java` and `config/OpenTelemetryAgentAotConfiguration.java`; the samples' and archetype's copies are deleted in Task 10, when they switch to the module;
  - `core/synapse-tracing-opentelemetry/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
  - tests: `core/synapse-tracing-opentelemetry/src/test/java/io/americanexpress/synapse/tracing/opentelemetry/autoconfigure/OpenTelemetryAgentAutoConfigurationTest.java` (from the bookstore's `OpenTelemetryAgentConfigurationTest`, its agent class loader included; same package as main, so no `package-info`), `.../src/test/java/io/americanexpress/synapse/tracing/opentelemetry/{package-info,TracingModuleIT}.java`, `.../src/test/resources/application-test.yaml` (the one-line comment, as in `synapse-logging-logback-tests`).
- Modify:
  - root `pom.xml`: `<module>core/synapse-tracing-opentelemetry</module>` after `core/synapse-logging-log4j2`; a `dependencyManagement` entry;
  - `bom/synapse-bom/pom.xml` (the managed entry);
  - `core/.../core/autoconfigure/SynapseContextAutoConfiguration.java` (~L156-163): the action names `io.americanexpress.synapse:synapse-tracing-opentelemetry` instead of Boot's starter; the agent sentence says the module already uses the agent's SDK;
  - `docs/modules/ROOT/pages/core.adoc` *Tracing is required* (~L445-465): the module; the metrics bullet becomes "the module brings no metrics registry, so nothing pushes metrics; adding Boot's `spring-boot-starter-opentelemetry` as well brings back Boot's OTLP metrics push to `localhost:4318`"; spans are exported once `management.opentelemetry.tracing.export.otlp.endpoint` (or `OTEL_EXPORTER_OTLP_ENDPOINT`) is set; correlation doesn't depend on sampling (Boot samples 10%) or on `OTEL_SDK_DISABLED` (O17);
  - `docs/modules/ROOT/pages/opentelemetry-agent.adoc` (~L3-50, *The recipe*): the module carries the configuration class, so the recipe is the module plus `-javaagent:`; the class listing becomes a description of what the auto-configuration does, with its two bean names; a service's own `OpenTelemetry` bean wins, with or without the agent; the AOT section names the auto-configuration's check.
- Test: `OpenTelemetryAgentAutoConfigurationTest`, `TracingModuleIT` (`@Tag("smoke")`); `core/.../core/autoconfigure/SynapseContextAutoConfigurationTest.java` (the message assertion).

**Interfaces:**
- Produces:

```java
@AutoConfiguration(beforeName = "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass(OpenTelemetry.class)
public final class OpenTelemetryAgentAutoConfiguration {
    static final String AGENT_CLASS = "io.opentelemetry.javaagent.bootstrap.AgentInitializer";
    static final String AGENT_BEAN = "openTelemetryAgent";   // the AOT check looks for this name
    static final String DESCRIPTION, ACTION;   // today's text; DESCRIPTION names "OpenTelemetryAgentAutoConfiguration's @ConditionalOnClass" and keeps the prefix "The OpenTelemetry Java agent is running, but this AOT-processed application was built without it"

    /** No class condition, so AOT-generated code always has it. */
    @Bean @ConditionalOnMissingBean(name = "synapseOpenTelemetryAgentAotCheck")
    static SmartInitializingSingleton synapseOpenTelemetryAgentAotCheck(ConfigurableListableBeanFactory beanFactory);   // fails only when the agent class is present, AOT mode is on and no OpenTelemetry bean other than Boot's SDK exists

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = AGENT_CLASS)
    static final class Agent {
        @Bean @ConditionalOnMissingBean(OpenTelemetry.class)   // by type, as Boot's SDK backs off: OpenTelemetry is public OTel API
        OpenTelemetry openTelemetryAgent();   // GlobalOpenTelemetry.get()
    }
}
```

- The module's POM (Boot manages every version):
  - compile: `org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry`, `org.springframework.boot:spring-boot-opentelemetry`, `io.opentelemetry:opentelemetry-api`, `io.americanexpress.synapse:synapse-core` (for `SynapseConfigurationException`), `org.jspecify:jspecify`;
  - runtime: `io.micrometer:micrometer-tracing-bridge-otel` (excluding `aopalliance:aopalliance`, as Boot does), `io.opentelemetry:opentelemetry-exporter-otlp`;
  - never `micrometer-registry-otlp` or `spring-boot-starter`;
  - test: `synapse-api-rest`, `spring-boot-starter-test`, `spring-boot-resttestclient`;
  - it has `src/main/java`, so the root build's `java-module` profile gives it the coverage gate, Javadoc and sources jars, with no special build section.

- [ ] **Step 1: Write the failing tests** (Review Focus 4; the probe in `probe-unsampled-correlation.md` is the model for the unsampled cases):

```java
// OpenTelemetryAgentAutoConfigurationTest (ApplicationContextRunner with this and Boot's OpenTelemetrySdkAutoConfiguration; the moved cases kept)
@Test void absentWithoutTheAgent()                        // no openTelemetryAgent bean; Boot's SDK is the OpenTelemetry bean
@Test void activeWhenTheAgentClassIsPresent()             // agent class loader → openTelemetryAgent is GlobalOpenTelemetry.get(); Boot's SDK backs off
@Test void backsOffToTheServicesOwnOpenTelemetry()        // a user OpenTelemetry bean under any name, with the agent class present → that one is the only OpenTelemetry; the context starts
@Test void theServicesOwnOpenTelemetryWithoutTheAgent()   // the same bean without the agent class → that one; Boot's SDK backs off as today
@Test void theAotCheckBacksOffByName()                    // a user bean named synapseOpenTelemetryAgentAotCheck → that one
@Test void aotRunUnderTheAgentWithTheServicesOwnBeanStarts()   // AOT mode, agent class present, a user OpenTelemetry bean → starts
@Test void aotRunUnderTheAgentWithoutItsBeanFails()       // moved: DESCRIPTION and ACTION
@Test void aotRunUnderTheAgentWithItsBeanStarts()         // moved
@Test void jvmRunUnderTheAgentIsUnaffected()              // moved
@Test void aotRunWithoutTheAgentStarts()                  // moved
@Test void isRegisteredInAutoConfigurationImports()
// TracingModuleIT: @SpringBootTest(RANDOM_PORT), management.tracing.sampling.probability=0.0, a QueryOperation bound by a @RestController, a JDK HttpServer as the outbound target, a RestClient built with the ObservationRegistry
@Test void theTracerIsOpenTelemetrys()                    // Tracer bean instanceof OtelTracer; the tracing check passed (context started)
@Test void noSpanExporterWithoutAnEndpoint()              // no SpanExporter bean
@Test void noOtlpMeterRegistry()                          // ClassUtils.isPresent("io.micrometer.registry.otlp.OtlpMeterRegistry") is false
@Test void unsampledRequestCarriesTheCallersId()          // X-Correlation-Id c-1: span not sampled; MDC correlationId, traceId, spanId match; baggage; outbound baggage "correlationId=c-1"; outbound traceparent flag 00…-…-02; response echo
@Test void unsampledRequestCarriesAGeneratedId()          // no header: a UUID, the same checks
@Test void unsampledFailureLineCarriesTheIdInTheMdc()     // a CONFLICT operation: the WARN line's MDC has correlationId, traceId and spanId of the failing span
// TracingModuleIT$WithAnEndpoint (nested, management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces)
@Test void anEndpointCreatesTheSpanExporter()             // one SpanExporter bean
// TracingModuleIT$WithTheSdkDisabled (nested, management.opentelemetry.enabled=false, the property OTEL_SDK_DISABLED maps to; O17)
@Test void correlationSurvivesADisabledSdk()              // the context starts (the check passes); MDC correlationId and outbound baggage carry c-1, as probe-otel-sdk-disabled.md recorded
// SynapseContextAutoConfigurationTest
@Test void noTracerFailureNamesTheModule()                // the action contains "io.americanexpress.synapse:synapse-tracing-opentelemetry"
```

- [ ] **Step 2: Run to verify failure:** the module doesn't exist yet, so first add it to the reactor with only its POM and tests, then `./mvnw -q -pl core/synapse-tracing-opentelemetry -am verify -Dit.test=TracingModuleIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true` with the runtime dependencies commented out: the context fails with the tracing check. Record the output, then restore them. The auto-configuration tests fail to compile, and `noTracerFailureNamesTheModule` fails on today's message.
- [ ] **Step 3: Implement** the auto-configuration, the POM, the root and BOM entries, the message and the docs.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,core/synapse-tracing-opentelemetry,bom/synapse-bom,tests/synapse-build-rules-tests -am verify` (the Enforcer's convergence and bans and the coverage gate run on the new module; `smoke-its` sees its smoke tag).
- [ ] **Step 5: Commit**: `feat(tracing): add synapse-tracing-opentelemetry, the tracing a service module declares, with the agent recipe`.

### Task 10: Samples and archetypes move to Logback and the tracing module (decisions 14, 15)

**Files:**
- Modify, in every service POM (`samples/hello-rest/pom.xml`, `samples/rest-books/pom.xml`, `samples/grpc-inventory/service-inventory/pom.xml`, `samples/multi-store/service-orders/pom.xml`, `samples/reference-bookstore/service-bookstore/pom.xml`, `archetypes/synapse-archetype-quickstart/src/main/resources/archetype-resources/pom.xml`, `archetypes/synapse-archetype-service/src/main/resources/archetype-resources/service-__serviceName__/pom.xml`):
  - remove `synapse-logging-log4j2` and its comment;
  - replace `spring-boot-starter-opentelemetry` with `io.americanexpress.synapse:synapse-tracing-opentelemetry`, comment: "Synapse carries the correlation id and context attributes as tracing baggage, so tracing is required. Spans are exported once management.opentelemetry.tracing.export.otlp.endpoint is set.";
  - remove the `spring-boot-starter-logging` exclusions (three per POM); the actuator dependency's comment says it also brings Logback.
- Remove the `management.otlp.metrics.export.enabled: false` block (and an emptied `management.otlp` parent) from the seven `application.yaml` files listed in `research2-tracing-bridge.md` §3.
- Delete the agent recipe's user-code copies, which the module now carries (Task 9): `samples/reference-bookstore/service-bookstore/src/main/java/.../bookstore/config/{OpenTelemetryAgentConfiguration,OpenTelemetryAgentAotConfiguration,package-info}.java` and its `src/test/java/.../config/OpenTelemetryAgentConfigurationTest.java`, and the same four files in the service archetype template (`service-__serviceName__/src/main/java/config/`, `src/test/java/config/`). Update what names them: the two POMs' agent comments, `BookstoreAgentIT`, `BookstoreAotIT`, `ServiceAgentIT` and `ServiceAotIT` Javadoc and expected messages (the `DESCRIPTION` prefix is unchanged), the bookstore and service READMEs, the archetype's `expected-files.txt`, and `verify.groovy`'s list of passing tests (`OpenTelemetryAgentConfigurationTest` leaves it).
- `parent/synapse-parent/pom.xml`: no change. It declares no logging or tracing dependency (the BOM import manages both modules); confirm with `grep -n "log4j\|logback\|opentelemetry" parent/synapse-parent/pom.xml` and say so in the report.
- Modify the archetype modules' own POMs: `archetypes/synapse-archetype-quickstart/pom.xml` (~L72, L152) and `archetypes/synapse-archetype-service/pom.xml` (~L97, L192) depend on and list `synapse-tracing-opentelemetry` instead of `synapse-logging-log4j2` (the reactor order and `target/it-repo`).
- Modify both archetypes' `src/test/resources/projects/*/verify.groovy`: the generated service POM declares `synapse-tracing-opentelemetry`, and has no `synapse-logging-log4j2`, no `spring-boot-starter-opentelemetry` and no `spring-boot-starter-logging`; `expected-files.txt` unchanged unless a file was removed.
- Modify `tests/synapse-build-rules-tests/src/it/archetype-drift/verify.groovy` only if a compared line moved.
- Docs and READMEs: the "It uses `synapse-logging-log4j2`" lines (hello-rest:6, grpc-inventory:7, multi-store:9, reference-bookstore:10, rest-books:5, both archetype READMEs), rest-books (~L839 "from `synapse-logging-log4j2`" becomes "from `synapse-core`'s default"; ~L938 the OTLP sentence goes), the layout lines that list "OTLP" (hello-rest:185, rest-books:990, quickstart README:197), `archetypes.adoc` (~L47), `opentelemetry-agent.adoc` *What changes in the logs* (~L160-176): the agent's Logback MDC instrumentation (`logback-mdc`) adds `trace_id`, `span_id` and `trace_flags` (opentelemetry-java-instrumentation `instrumentation/logback/logback-mdc-1.0/javaagent/README.md`, and the agent's *Suppressing instrumentation* page, both fetched 2026-11-20); turn it off with `-Dotel.instrumentation.logback-mdc.enabled=false`; the Log4j2 case moves to `logging-log4j2.adoc`.
- Test: the samples' existing suites; both archetype ITs; `BookstoreAgentIT` and `ServiceAgentIT` still assert `trace_id` (now from the Logback instrumentation).

**Interfaces:**
- Consumes: `synapse-tracing-opentelemetry` (Task 9), core's ECS default (Task 7).

- [ ] **Step 1: Write the failing assertions:** the two `verify.groovy` changes, and in the bookstore's and the service archetype template's operational IT (`BookstoreOperationalIT`, `ServiceOperationalIT`, which start the packaged jar): `consoleLinesAreEcsOnLogback()`: the startup output names no Log4j2 class, a failure line parses as JSON with `log.level` and `correlationId`, and the jar's `BOOT-INF/lib` holds `logback-classic` and no `log4j-core`. Run them: they fail on today's Log4j2 jars.
- [ ] **Step 2: Implement** the POM, YAML, README and docs changes, both sides of `archetype-drift` in this commit.
- [ ] **Step 3: Run to verify pass:** `./mvnw -pl samples/hello-rest,samples/rest-books,samples/grpc-inventory/service-inventory,samples/multi-store/service-orders,samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-quickstart,archetypes/synapse-archetype-service,tests/synapse-build-rules-tests -am verify -Dprotobuf…=…`, Docker on. If `BookstoreAgentIT` or `ServiceAgentIT` finds no `trace_id` on Logback, stop and report the log line: the docs change above depends on it.
- [ ] **Step 4: Re-run the READMEs** whose output shows log lines or startup output: hello-rest, rest-books (the console section), grpc-inventory, multi-store and the bookstore (including its agent section), from a clean state, and paste the new Logback ECS lines where they differ. Each run also confirms that no `WARN` about `localhost:4318` appears in a minute of idle running (paste the grep).
- [ ] **Step 5: Commit**: `feat(samples,archetypes)!: use Boot's Logback and synapse-tracing-opentelemetry`.

### Task 11: The OpenTelemetry agent ITs run only under `-Potel-agent` (decision 22)

**Files:**
- Modify `samples/reference-bookstore/service-bookstore/pom.xml`:
  - move the `otel-agent` copy (`maven-dependency-plugin`) and `otel-agent-checksum` (Enforcer) executions into the `otel-agent` profile, which also gets `<activation><property><name>otel-agent</name></property></activation>` and keeps `spring-boot.run.jvmArguments`;
  - failsafe `<excludes>`: `${bookstore.its.aot}` and `${bookstore.its.agent}` (properties, defaults `**/*AotIT.java` and `**/*Agent*IT.java`); the `aot` profile sets `bookstore.its.aot=none` instead of overriding `<excludes>`; the `otel-agent` profile sets `bookstore.its.agent=none` (O14);
  - the comments that say "in every build".
- Move `BookstoreAotIT.aotRunUnderTheAgentFailsFast` into a new `samples/reference-bookstore/service-bookstore/src/test/java/io/americanexpress/synapse/samples/bookstore/BookstoreAgentAotIT.java` (same package; no new package-info).
- The same in the service archetype template: `archetypes/synapse-archetype-service/src/main/resources/archetype-resources/service-__serviceName__/pom.xml` (properties `service.its.aot`, `service.its.agent`) and `.../src/test/java/ServiceAotIT.java` → new `ServiceAgentAotIT.java`; the archetype's `archetype-metadata.xml` if it lists test files by name, and `projects/s/expected-files.txt`.
- Modify `archetypes/synapse-archetype-service/pom.xml`: property `synapse.it.otel-agent` (default `false`); a profile `otel-agent` (activated by `-Potel-agent` or the property `otel-agent`), modelled on the `protobuf-sanctioned-executable-path` profile, that sets `synapse.it.otel-agent=true` and adds `<otel-agent>true</otel-agent>` to `maven-archetype-plugin`'s `<properties>` (O15).
- Modify `archetypes/synapse-archetype-service/src/test/resources/projects/s/verify.groovy` (~L141-149): `ServiceAgentIT` leaves the unconditional list; `def agent = '@synapse.it.otel-agent@' == 'true'`; when true, require the jar `service-s/target/otel/opentelemetry-javaagent.jar` and a passing `ServiceAgentIT`; when false, assert `!log.contains('in com.example.s.ServiceAgentIT')`, as for `ServiceAotIT`.
- Modify `.github/workflows/nightly.yml`:
  - `full-jdk21`: `-Potel-agent` on its `verify`;
  - `aot-reference`: `-Potel-agent` on both steps, and `-Dit.test=BookstoreAotIT,BookstoreAgentAotIT` on the JDK 21 step;
  - a new job `otel-agent` ("OpenTelemetry Java agent ITs on JDK 25"), `timeout-minutes: 60`, the same pinned checkout and `setup-java` (Java 25) steps as `mutation-testing`, running `./mvnw --batch-mode --no-transfer-progress -Potel-agent -pl samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-service -am verify`;
  - `report-failure.needs` gains `otel-agent`.
- Docs: `opentelemetry-agent.adoc` (~L10-11: "runs the jar under the agent in every build" becomes "with `-Potel-agent`, in the nightly build"), the bookstore README (~L989, L1049, L1064, L1073: `-Potel-agent` copies the agent; `-Potel-agent package` gets it), the service archetype README (its *OpenTelemetry Java agent* section), `archetypes.adoc`, and AGENTS.md *Tests* (the agent ITs run only under `-Potel-agent`, nightly).
- Test: the bookstore's and the archetype's ITs; `verify.groovy`.

- [ ] **Step 1: Write the failing assertions:** the `verify.groovy` branches. Run `./mvnw -pl archetypes/synapse-archetype-service -am verify -Dprotobuf…=…`: it fails, because `ServiceAgentIT` still runs while `synapse.it.otel-agent` is false.
- [ ] **Step 2: Implement** the POMs, the IT split, the workflow and the docs.
- [ ] **Step 3: Run to verify pass, without the profile:** `./mvnw -pl samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-service -am verify -Dprotobuf…=…`, Docker on. Expected: no `target/otel` directory in either service, no `*Agent*IT` in the logs, green.
- [ ] **Step 4: Run with it** (Review Focus 5): the same command with `-Potel-agent`. Expected: the agent jar is copied and checked, `BookstoreAgentIT` and `ServiceAgentIT` pass. Then `-Paot -Potel-agent -pl samples/reference-bookstore/service-bookstore -am verify`: `BookstoreAotIT` and `BookstoreAgentAotIT` pass; and `-Paot` alone: `BookstoreAgentAotIT` doesn't run.
- [ ] **Step 5: Lint the workflow** with actionlint and zizmor as plan 5 did; if they can't run here, record that and rely on CI's `workflow-lint`.
- [ ] **Step 6: Re-run the bookstore README's agent commands** with `-Potel-agent` and paste the output where it changed.
- [ ] **Step 7: Commit**: `build(samples,archetypes): run the OpenTelemetry agent and its ITs only under -Potel-agent, nightly`.

### Task 12: Docs-only items and spec status

**Files:**
- Modify:
  - `docs/modules/ROOT/pages/parent.adoc` *The garbage collector*: the pin is kept; when the image's JDK moves to a new feature release, the 512 MB, one-CPU smoke test runs with and without the pin; on JDK 27 or later G1 is the default everywhere (JEP 523, https://openjdk.org/jeps/523), so the pin can go unless the Java 21 image still needs it. *Heap size* / image memory: unchanged guidance, plus one sentence that the parent bakes in no memory defaults because memory is the deployment's choice (decision 32, items 8 and 9);
  - `AGENTS.md`: the *Logging* bullet (Logback by default through the service's starters; `synapse-logging-log4j2` is opt-in, managed by `synapse-parent`, declared only by a `service-*` module); the layout rows for `core/synapse-logging-log4j2` ("Opt-in Log4j2 backend, optional failure-log burst limit") and a new `core/synapse-tracing-opentelemetry` row; the `tests/` row (synapse-logging-logback-tests now tests the default backend);
  - `README.md` (root): the same layout rows (~L63);
  - `specs/v2/BUILD.md` §15 P1: an *Observability follow-up (plan 2026-11-20)* entry listing what landed, the O-table IDs, and what only later runs can check (the nightly `otel-agent` job and the Linux-only checks);
  - `specs/v2/DESIGN.md`: any row the review changed.
- [ ] **Step 1: Write the docs.**
- [ ] **Step 2: Build the docs** (Global Constraints, *Docs*).
- [ ] **Step 3: Commit**: `docs: record the observability follow-up, the G1 pin rule and the logging defaults`.

### Task 13: Full build

- [ ] **Step 1: JDK 25, as CI runs it** (background, Docker on):

  ```bash
  CI=true ./mvnw clean verify -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

  Expected: BUILD SUCCESS, including the invoker build-rules tests (`archetype-drift`, `jdk21-pr-excludes`, `smoke-its`) and both archetype ITs, with no agent IT run.
- [ ] **Step 2: The JDK 21 pull-request leg:**

  ```bash
  ./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke -Djacoco.skip=true \
    -Dfailsafe.excludesFile="$(pwd -W)/.config/jdk21-pr-excluded-its.txt" \
    -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

- [ ] **Step 3: The nightly agent job locally:** the `otel-agent` job's command from Task 11 on JDK 25, with `-Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"` added (the bookstore is a gRPC sample).
- [ ] **Step 4: Report** the test counts per module for each run, next to the baseline's, and explain every count that went down (the agent ITs leave the default build by design).
- [ ] **Step 5:** No commit unless a fix was needed. A fix is its own `fix:` commit with a failing test first, and the runs repeat after it.

### Task 14: Final whole-branch review

- [ ] **Step 1:** An Opus review of the branch from its base, with BUILD.md, DESIGN.md, AGENTS.md, this plan and the four memos as the authority. Order the findings by severity; each names its module or sample and the user-visible effect. The review checks especially:
  - the masking principle against every `LOG.`, `.log(`, `setCause(` and `observation.error(` in Synapse's main code (grep), listing each line Synapse writes with a throwable or an input;
  - that the log lines' and spans' contents are the same on REST and gRPC;
  - that no behaviour change landed without a failing test first;
  - that the decisions table matches what was built.
- [ ] **Step 2: Fix** each accepted finding in its own commit, with a failing test where behaviour changes. A fix gets a Sonnet re-review; repeat Task 13 after the last fix.
- [ ] **Step 3: Report** to the maintainer: the commits, the decisions table (O1–O17) for confirmation, what only the nightly run can show (the `otel-agent` job, the JDK 21 agent run), and the *Not in this plan* questions. Squash into local `main` and push only on the maintainer's word.

---

## Not in this plan

**Needs the maintainer:** nothing. The three questions of the first draft were answered on 2026-11-20: the agent configuration moves into the module (Task 9), database text is shown by state only (Tasks 1, 3, 4), and `OTEL_SDK_DISABLED` was probed and needs no change (O17).

**Adopt later:**

| Item | Phase |
|---|---|
| A startup WARN when SLF4J has no provider (a service with no starter loses every line) | P6; needs care, since SLF4J's no-provider fallback is internal. |
| Running the agent ITs on pull requests that change the agent pin (a `ci.yml` paths filter) | Optional; the nightly run is the minimum. |
| An ArchUnit rule against `@Masked` record components on records with the generated `toString` | P6, if ArchUnit can tell the generated one apart (unchecked). |
| The OpenTelemetry JDK `HttpClient` sender instead of okhttp (drops about 2.6 MB of okhttp, okio and Kotlin) | Unverified; P6. |
