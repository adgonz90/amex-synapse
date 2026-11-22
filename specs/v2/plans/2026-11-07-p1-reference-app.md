# P1 plan 5: archetypes, the consumer parent, samples and the reference app — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish P1 the way a user meets it. Build an integration base that merges plans 2c, 3 and 4. Close the REST/gRPC contract gaps on it (version preconditions and paging). Give `synapse-parent` its build conventions. Ship five samples, one use case each, two archetypes generated in their shape, and the reference app's container image and OpenTelemetry agent recipe. (The reference app's native image moved to P4 on 2026-11-08.)

**Architecture:**
- Plans 2c, 3 and 4 are finished but not on `main`. This plan runs on a local **integration base** in this branch (Task 1), which merges them and applies the trial-merge fixes. Every later task builds on it.
- Parity first (Tasks 2–4). The maintainer decided on 2026-11-07 that protocols must not introduce API contract differences. So `VersionCondition` travels over gRPC in a string `etag` field with REST's ETag token, and a stale one gets REST's code (`PRECONDITION_FAILED`). The cursor code moves to core behind a protocol-neutral API (*Protocol-neutral core API*, below) so one cursor pages over both protocols, and gRPC gets AIP-158 page tokens.
- Then test support and the parent (Tasks 5–8), the samples (Tasks 9–17, roughly one per task), the archetypes (Tasks 18–19), and docs, spec status and a README run-through (Tasks 20–21).
- Every sample inherits `synapse-parent`, as a consumer's project does. The root build reaches samples only for format and license checks.

**Tech Stack:**
- Java 21 (`--release 21`, built on JDK 25), Maven 3.9 through the wrapper.
- Spring Boot 4.1.2 (still the latest GA: the releases page lists 4.2.0-RC2 on 2026-11-05), Spring Framework 7.0.10, Spring gRPC 1.1.2, grpc-java 1.84.1, protobuf 4.36.2, Hibernate 7.4.13, Flyway 12.4.0, Testcontainers 2.0.5. All of these are Boot-managed.
- springdoc 3.2.0 (plan 2c).
- New in this plan:
  - OpenTelemetry Java agent 2.33.1;
  - Maven Archetype 3.4.1 (`maven-archetype-plugin`, `archetype-packaging`);
  - ~~GraalVM Community 25 through `graalvm/setup-graalvm` v1.6.8;~~ and ~~Native Build Tools 1.1.14 (Boot-managed)~~: moved to P4 with the native image (2026-11-08);
  - grpcurl 1.9.3 (README commands only, Docker image pinned by digest; F20).
- Docker Desktop is available: Testcontainers ITs run locally.

**Spec:** [specs/v2/BUILD.md](../BUILD.md) §2 (archetypes), §3.3 (*AOT-ready*, *Native image*, *Environments under AOT*), §3.4, §3.6, §4.1–4.3, §5.3 (agent), §12, §13, §14 gates 1, 2 and 10 (gate 9, native, starts in P4), and §15 P1 (scope, acceptance 15, 17, 18 and 19, and the plan 5 delivery entry). The reasons are the [DESIGN.md](../DESIGN.md) §4.1 rows dated 2026-11-07, and *Open questions*. Plan inputs:
- the 2c, 3 and 4 plans (`specs/v2/plans/2026-11-05-p1-{rest-versioning-cors-openapi,grpc-edge,data-postgres}.md` on their branches);
- the trial-merge report, resolutions and post-fix patches;
- the version-contract design note (`…\scratchpad\{trial-merge-report.md, trial-merge-resolutions.diff, trial-merge-postfix-patches\, design-version-contract.md}` in the coordinator's scratchpad). Pre-flight F1 corrects the note's stale-mismatch choice (its *Decision* 3 and the gRPC table's stale row): a stale version the request sent is `PRECONDITION_FAILED` over both protocols, as REST already answers. Where the note and this plan differ, this plan wins.
- the pre-flight scan (`.superpowers/sdd/2026-11-07-p1-reference-app/preflight.md`, 34 findings) and the ledger's rulings on it; see *Pre-flight rulings applied*.

## Execution base and what this plan relies on

Plan 5 executes only on the integration base built in Task 1. The controller names the branch heads to merge when Task 1 starts. Don't hard-code SHAs. For reference only, at the pre-flight amendment: 2c is at `22ceaedf` (both 2026-11-07 decision commits are in: `d95154a4` turns API docs off by default, `22ceaedf` derives required preconditions from the input record); plan 3 is at `7d05b1ff` (the guava pin in `synapse-bom`, invoker test `bom-guava-pin`); plan 4 has its Task 14 `synapse-data-postgres` module in review and may still change in a fix round, so this plan names its interfaces, never its files' contents. Tasks 12–17 wait for plan 4's Task 14 to be final.

| From | Interface plan 5 relies on | Where (after the merge) |
|---|---|---|
| 2c | `VersionCondition<V>` (`AnyVersion`, `Version(v)`, `of`, `any`, `matches`) | `core/synapse-core/src/main/java/io/americanexpress/synapse/core/conditional/VersionCondition.java` |
| 2c (2026-11-07 decision commits `d95154a4`, `22ceaedf`) | `SynapseErrors.PRECONDITION_REQUIRED` in core (`RestErrors.PRECONDITION_REQUIRED` removed; REST still answers 428); required-ness derived from the record (`.requireIfMatch()` removed; on a write a non-null `VersionCondition` is required, and a read's must be `@Nullable`); REST's stale-write rule (`Preconditions.translate`: `STALE_VERSION` on a request that sent a bound `If-Match` becomes `PRECONDITION_FAILED`, 412); `springdoc.api-docs.enabled` defaulted to `false` at lowest precedence | `core/.../core/error/SynapseErrors.java`; `api/synapse-api-rest/.../internal/binding/{BindingValidator,ConditionalRules}.java`, `.../internal/conditional/Preconditions.java`; `api/synapse-api-rest-openapi/.../autoconfigure/OpenApiDefaultsEnvironmentPostProcessor.java` |
| 2c | REST `.etag(ComponentRef<T, ?>)` (GET/HEAD, or POST with `.created`; no `ETag` on PUT/DELETE), `.header("If-Match", …)`, `.version("2")`, `.sortable(Class<E>)`; `CursorCodec`, `CursorScope`, `InvalidCursorException`; internal `AesGcmCursorCodec`, `CursorKeys`, `InputDigest`, `PageCursors`, `PagingSettings`, `PageQueryReader`, `SortKeys`; `EntityTags`/`ETagValues`; `synapse.api.rest.paging.{max-offset,cursor.*}` (Task 3 moves both to `synapse.paging.*`) | `api/synapse-api-rest/.../api/rest/{RestBindings,RouteSpec}.java`, `.../api/rest/paging/`, `.../internal/paging/`, `.../internal/conditional/`, `.../autoconfigure/SynapseRestProperties.java` |
| 2c | CORS `synapse.api.rest.cors.*`, `synapseCorsFilter`; `synapse-api-rest-openapi` | `api/synapse-api-rest/.../internal/cors/`, `api/synapse-api-rest-openapi/` |
| 3 | `GrpcBindings.service(...)`, `.unary(...)`, `.map`, `.context`, `.notFound`, `.bindAllByName()`; `MessageMapping`, `FieldConverters`; `GrpcErrorStatuses`; `UnaryOperationHandler`; `ReflectionAotCheck` (the pattern for Task 7; Task 8 makes it a build-time switch) | `api/synapse-api-grpc/.../api/grpc/{GrpcBindings,GrpcMethodSpec}.java`, `.../internal/mapping/`, `.../internal/error/`, `.../internal/service/`, `.../internal/aot/ReflectionAotCheck.java` |
| 3 | `@SynapseGrpcTest`, `BoundGrpcOperationMocksCheck`; Boot's `@ImportGrpcClients`, `@LocalGrpcServerPort` | `api/synapse-test-grpc/.../test/grpc/` |
| 3 (`7d05b1ff`) | The guava `33.7.2-jre` CVE pin in `synapse-bom`; invoker test `bom-guava-pin` (`verify.groovy` compares the root's and the BOM's guava versions) | `bom/synapse-bom/pom.xml`; `tests/synapse-build-rules-tests/src/it/bom-guava-pin/` |
| 4 | `@SynapseDataStore(value, access)`, `DataAccess`, `AuditedEntity`/`VersionedEntity`/`IdEntity`, `Auditable`; `SynapseErrors.ALREADY_EXISTS` (`CONFLICT`; plans 3 and 4 define it identically); `synapse.data.auditing.system-user` (default `system`); store keys `synapse.data.stores.<name>.*` | `data/synapse-data-core/.../data/`, `data/synapse-data-jpa/.../data/jpa/` |
| 4 | `@SynapseDataTest("<store>")`, `@StoreConnection("<store>")`, `SynapseContainers.postgres()` / `postgres(key)` (`postgres:18.6-alpine`) | `data/synapse-test-data/.../test/data/` |
| 4 (Task 14, may still change) | `synapse-data-postgres`: `PostgresVendor`, `SynapsePostgresAutoConfiguration`; it brings the PostgreSQL driver and `flyway-database-postgresql` | `data/synapse-data-postgres/` |
| core | `PageQuery`, `PageResult`, `SpringDataPages.from/pageable`, `SynapseOperations.invoke`, `@SynapseOperationTest`, `SynapseArchitecture.forApplication`, `MutableClock`, `JsonFixtures`, `assertThatSynapseException` | `core/synapse-core/.../core/paging/`, `core/synapse-test/.../test/` |

## Global Constraints

**Branch, JDK, language**
- Branch `feature/v2-p1-reference-app` in the clone `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\p5`. Never push, never touch `C:\Users\Tony\VSCode Projects\amex-synapse`. Local `main` gets the squash only when the maintainer says so.
- JDK: `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"` before any `./mvnw`. JDK 21 legs: `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never a global `mvn`.
- The protobuf build needs `-Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"` on this machine (as the trial merge used).
- `--release 21`: no Java 22+ APIs. Records for data carriers. Every package has an `@NullMarked` `package-info.java`; a test package with a main package's name has none.
- Formatting: Spotless applies locally; never hand-format. The license header goes on every file Spotless checks.
- Don't edit `.vscode/` or a pom while a Maven build runs.

**Windows (pre-flight F21, F22)**
- The docker CLI isn't on the agents' PATH. Every step that runs `docker` or `docker compose` first runs `export PATH="/c/Users/Tony/AppData/Local/Programs/DockerDesktop/resources/bin:$PATH"` in Git Bash, and sets `MSYS_NO_PATHCONV=1` so Git Bash doesn't rewrite `/var/run/docker.sock` or `/work`. Mount the clone with `-v "$(pwd -W)":/work`. Scripts that also run on Linux (`image-smoke.sh`) prepend that directory only when `docker` isn't found and the directory exists. Testcontainers needs none of this (it uses the named pipe).
- `LongPathsEnabled` is 0 on this machine, and changing it is a machine setting for the maintainer, not for a task. Keep generated paths short (Task 19), and report a tool that fails on a path over 260 characters rather than working around it silently.
- VS Code Java builds into `target/` too. Close VS Code on the clone during Task 17's container build.

**Spring and Synapse rules (AGENTS.md)**
- Spring Boot first: never re-implement or alias a Boot property. Defaults for Boot keys go through an `EnvironmentPostProcessor` at lowest precedence.
- Every auto-configuration gets `ApplicationContextRunner` tests: it activates, backs off, binds its properties, and stays absent.
- No production concept. The environment is never used in a `@Conditional`. Keys that say where or as whom to connect have no default.
- Every module whose tests start a Spring Boot context has `src/test/resources/application-test.yaml`.
- HMAC (`javax.crypto.Mac`) is disallowed.
- Errors never expose stack traces, class names or internal messages.

**Protocol parity (maintainer decision, 2026-11-07)**
- A REST route and the gRPC method bound to the same operation have the same contract: the same inputs, required-ness, error codes and categories, version tokens and page tokens.
- Required-ness comes from the input record. A non-null `VersionCondition` is required and `@Nullable` is optional.
- Over gRPC the version is the string field `etag` with REST's ETag token, verbatim (`"5"` with its quotes, or `*`).
- A stale version the request sent is `PRECONDITION_FAILED` on both protocols (REST 412, gRPC FAILED_PRECONDITION); a write that sent none and lost a race stays `STALE_VERSION` (REST 409, gRPC ABORTED). This is REST's existing rule (F1).
- A response carries an entity tag only where REST sends an `ETag`: reads and creates, never updates or deletes (F3).
- The documented exceptions, each listed in `grpc.adoc`: a `VersionCondition` on a gRPC read fails startup (REST binds one from `If-None-Match` for HTTP caching; F12; amended in Task 2, Decision 20: a `@Nullable` one binds on a gRPC read as on a REST `GET`, a non-null one fails startup on both, and the remaining difference is that gRPC has no 304), one `VersionCondition` per gRPC message (F26), and gRPC sort keys are the snake_case constant names while REST honours `@JsonProperty` (the cursor scope uses the constant names on both, F5).

**Samples**
- Every sample inherits `io.americanexpress.synapse:synapse-parent` (with `relativePath` to `parent/synapse-parent`).
- Samples use groupId `io.americanexpress.synapse.samples`, and artifactIds equal to their directory names.
- Samples set `maven.install.skip`, `maven.deploy.skip`, `maven.source.skip` and `maven.javadoc.skip` to `true`. They are never published.
- Each sample has:
  - a README whose commands were run against the app, with real output pasted;
  - tests;
  - `application.yaml` mapping `spring.profiles.active: ${ENV}`;
  - `application-local.yaml`;
  - `application-build.yaml` (comment-only is fine), and a placeholder `config-build.yaml` in each library module whose store or client needs settings to start, only in a service that builds with `-Paot` or builds an image: `synapse-parent` runs `process-aot` (only in its opt-in `aot` profile, Task 16b) and the image's AOT-cache training run in the `build` environment. Without the file `process-aot` fails the build, and the training run fails to start, so `synapse-parent`'s `image` profile checks for the file and `-Pimage` fails the build early, in `validate` (Task 17 fix round). Only `spring-boot:build-image` invoked on the command line skips that check, and then Paketo keeps the partial cache and the image build goes on; a Dockerfile's training `RUN` fails `docker build`. Among the samples only the reference bookstore has them. The service archetype (Task 19) generates them, because it ships the Dockerfile with AOT-cache training, each with a comment that it exists for the image's training run and for `-Paot` (maintainer, 2026-11-08). The quickstart archetype (Task 18) generates neither them nor a Dockerfile. Both archetypes' READMEs say how to opt in to `-Paot`, and the quickstart's says that `-Pimage` fails without `application-build.yaml`, and that only the command-line `spring-boot:build-image` skips that check;
  - `src/test/resources/application-test.yaml`;
  - `spring-boot.run.profiles=local` in the bootable module's pom;
  - a one-line `ArchitectureTest`.
- Samples don't invent later-phase features: no clients, Kafka, GraphQL, functions, security or scheduling.

**Tests and builds**
- `*Test` are unit tests (surefire) and `*IT` are integration tests (failsafe); they are never skipped. Each library module with ITs tags one `@Tag("smoke")`.
- `-DskipTests` is never a passing build.
- Library modules keep JaCoCo 80% line / 70% branch, and 90% of changed lines covered. Samples and archetypes are left out of coverage (gate 2).
- Bug fixes start with a failing test.
- Iterate on one test with:
  - `./mvnw -q -pl <module> -am test -Dtest=Class -Dsurefire.failIfNoSpecifiedTests=false`;
  - `./mvnw -q -pl <module> -am verify -Dit.test=ClassIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true` (Task 20: without the last two, Failsafe fails in the `-am` modules that have no such IT, and the coverage gate fails in the module whose other tests didn't run).

  Neither counts as passing. Each task ends with `./mvnw -pl <modules> -am verify`, and the tasks that say so run the full `./mvnw clean verify` in the background.

**Commits**
- Conventional Commits. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only. Never `git add -A`, stash, amend or push. Leave no backup files (`*.bak`, `*.orig`).
- Versions live in the root `pom.xml`. Spring Boot stays on 4.1.2. A Boot-managed version is overridden only as a documented CVE pin.

**Dependency and tool versions chosen (release notes read 2026-11-07)**

| Item | Version | Source read |
|---|---|---|
| `io.opentelemetry.javaagent:opentelemetry-javaagent` | 2.33.1 (SDK 1.67.0; Boot manages API 1.62.0, which the agent bridges) | https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.33.1 and …/tag/v2.33.0; https://opentelemetry.io/docs/zero-code/java/agent/getting-started/ |
| `maven-archetype-plugin`, `archetype-packaging` | 3.4.1 (needs Maven ≥ 3.6.3) | https://github.com/apache/maven-archetype/releases; https://maven.apache.org/archetype/maven-archetype-plugin/integration-test-mojo.html |
| GraalVM (moved to P4, 2026-11-08) | Community 25 (`graalvm-community`, `java-version: '25'`; local container `ghcr.io/graalvm/native-image-community:25.0.2`, verify the tag exists before use) | https://docs.spring.io/spring-boot/system-requirements.html; https://github.com/graalvm/graalvm-ce-builds/releases/tag/jdk-25.0.2; https://www.graalvm.org/release-notes/JDK_25/ |
| `graalvm/setup-graalvm` | v1.6.8, `f70bc8032f90a104502824a54bcb94b6f01715bc` (checked with `git ls-remote`) | https://github.com/graalvm/setup-graalvm/releases |
| Paketo Spring Boot buildpack settings | `BP_JVM_AOTCACHE_ENABLED`, `TRAINING_RUN_JAVA_TOOL_OPTIONS` (`BP_JVM_CDS_ENABLED` is deprecated) | https://github.com/paketo-buildpacks/spring-boot; https://docs.spring.io/spring-boot/how-to/aot-cache.html |
| grpcurl (README only, Docker image `fullstorydev/grpcurl:v1.9.3@sha256:<digest>`; Task 11 reads the digest with `docker buildx imagetools inspect`). Docker Hub has no `v1.9.4` image (pre-flight: `docker manifest inspect` "no such manifest"); GitHub has the v1.9.4 release binary. | 1.9.3 | https://github.com/fullstorydev/grpcurl/releases; https://hub.docker.com/r/fullstorydev/grpcurl/tags |
| Temurin JRE base image (sample Dockerfile) | `eclipse-temurin:25.0.5_8-jre-resolute@sha256:b6e00330337143a2c1a20f1c1c693d4b6ce9a3c30ade8b9d03fc68cc9e3871e7` (Temurin-25.0.5+8, Ubuntu 26.04; Task 17, digest read with `docker buildx imagetools inspect`) | https://api.adoptium.net/v3/info/release_notes/jdk-25.0.5+8 (includes JDK-8388703, an AOT-cache crash fix); https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5+8 |

---

## Decisions taken for the maintainer's review

The maintainer asked for this plan to execute now. Every open question and judgement call got the recommendation below, provisionally. Each row can be reversed at review; the *cost to reverse* says what changes.

| # | Question | Choice | Reason | Alternative (cost to reverse) |
|---|---|---|---|---|
| D1 | Plans 2c, 3 and 4 aren't on `main`. Where does plan 5 run? | A local integration base in this branch (Task 1): merge 2c, then 3, then 4, re-deriving the conflicts against the named heads, then reconcile with plan 4. The interim `Int64Value` converter is skipped; Task 2 follows the merges directly (F8). | Maintainer instruction. The trial merge proved this order. | Wait for `main` (blocks plan 5). |
| D2 | ~~Plans 3 and 4 each added a core code for a unique-value conflict (`ALREADY_EXISTS`, `DUPLICATE`). Which survives?~~ **Overruled (ledger ruling, 2026-11-07).** The premise was stale: plans 3 and 4 both define `ALREADY_EXISTS` identically, by the 2026-11-05 cross-plan ruling, and plan 4 has no `DUPLICATE`. | **New choice:** `ALREADY_EXISTS("The resource already exists", CONFLICT)` stays, and gRPC answers it ALREADY_EXISTS. Nothing is renamed, and Task 1 has no D2 test or commit. (Was: `DUPLICATE`, with `ALREADY_EXISTS` removed in Task 1.) | Both branches already agree; a rename would touch two branches for a name. | `DUPLICATE` (a rename in both branches and their tests). |
| D3 | Missing required `etag` over gRPC. | `PRECONDITION_REQUIRED` → FAILED_PRECONDITION (by category). | One code and category on both protocols. AIP-154 suggests INVALID_ARGUMENT; the per-code override `synapse.api.grpc.errors.status.[PRECONDITION_REQUIRED]=INVALID_ARGUMENT` flips it, and Task 2 tests that. | INVALID_ARGUMENT by default (one mapping row). |
| D4 | A weak tag (`W/"5"`) in a gRPC write `etag`. | `PRECONDITION_FAILED` → FAILED_PRECONDITION, as REST answers 412 before the operation runs. | Same code as REST. | `STALE_VERSION` (ABORTED). |
| D5 | The gRPC `etag` token format. | Verbatim REST header value: `"5"` with quotes, or `*`. More than one tag is INVALID_ARGUMENT. An empty string is absent. | A client copies tokens between protocols. One parser, so the two can't drift. | The bare value `5` (no `*`, no weak syntax). |
| D6 | How the gRPC mapper finds the version field. | By type and a fixed name: a `VersionCondition` component maps to the request's string field `etag`. Startup fails if the field is missing or not a string, or if the input has two `VersionCondition` components. | AIP-154 names the field `etag`. One rule for every route. | By component name; a per-route `.map(...)`. |
| D7 | Where the shared entity-tag parser and formatter live. | Core internal: `io.americanexpress.synapse.core.internal.conditional.EntityTags`, with its own `Tag` type and no spring-web import (F5). REST adapts it to Spring's `ETag`; `ETagValues` stays in REST. | gRPC can't depend on REST, and core has spring-web in test scope only. It's internal, so it isn't public API. | A small shared module (an extra artifact). |
| D8 | How a gRPC response carries the ETag. | `GrpcMethodSpec.etag(ComponentRef<T, ?>)`, the same signature as REST's `.etag` (F11). It writes the strong quoted tag into the response's string field `etag`; the component keeps its own field too (`Book.version`), because every output component needs one. Startup fails if there's no `etag` field, or on a method where REST would send no `ETag` (F3, *Defaults* #2). | Same token as the REST `ETag` header, declared the same way, on the same methods. | An `etag` record component (changes the models); a `Function<O, ?>` (hides which component it reads). |
| D9 | gRPC page tokens: plan 3 proposed a plan 3b. | Build them here (Task 4), on AIP-158 fields. | Parity requires `SearchBooks` over gRPC. 3b doesn't exist. | `SearchBooks` over REST only (breaks the parity decision). |
| D10 | Where `CursorCodec` lives. | Core: `io.americanexpress.synapse.core.paging.{CursorCodec,CursorScope,InvalidCursorException}`, keys `synapse.paging.cursor.{key,previous-keys,max-age}` (was `synapse.api.rest.paging.cursor.*`) and `synapse.paging.max-offset` (was `synapse.api.rest.paging.max-offset`, F5). `AesGcmCursorCodec`, `CursorKeys`, `InputDigest` and `PageCursors` go to `core.internal.paging`, rewritten protocol-neutral as *Protocol-neutral core API* specifies (D44). | One token pages over both protocols. Paging sizes are already under `synapse.paging.*`. Unreleased, so no deprecation is needed. | A gRPC-only codec (tokens differ per protocol). |
| D11 | gRPC sort syntax. | `order_by` per AIP-132: comma-separated keys, each optionally followed by ` desc`. A key is the snake_case form of the `.sortable` enum constant (`PUBLISHED_YEAR` → `published_year`), and binds to the constant's name, as REST's wire names do, so the cursor scope is the same on both. `@JsonProperty` renames a REST wire name only (documented difference, F5). REST keeps `sort=-publishedYear`. | Each protocol follows its own standard over the same keys and semantics. | REST's `sort` syntax over gRPC. |
| D12 | gRPC offset paging. | AIP-158 `skip`, optional in the proto, capped by `synapse.paging.max-offset` as REST's `offset` is (F5). Without it, a method pages only by token (documented). `total_size` is proto3 `optional int32`, unset when the total is unknown. | AIP-158. | Always require `skip`. |
| D13 | The bound-operation mocks check, duplicated in REST and gRPC. | One public `BoundOperationMocksCheck(Class<?> bindingsType, String slice)` in `synapse-test` (`io.americanexpress.synapse.test.slice`). The slices register it under their own bean names. | The two copies are identical except for the type and the message. Both plugins now exist. | Keep both copies. |
| D14 | `springdoc.api-docs.enabled` decides whether beans exist, so it is fixed by an AOT build. | `OpenApiAotCheck`, as plan 3's `ReflectionAotCheck`: in AOT mode, startup fails when the value differs from the build's. Both are build-time switches reported by one core check, so a run that differs in both names both keys in one failure (F4). Docs stay off by default (`d95154a4`); an environment file turns them on. A service whose image publishes its document turns it on in the build environment's file and in each deployed environment's file that serves it; the reference app turns it on only in `application-local.yaml` (D31), and its native verification turns it on for its own build and run (Task 16, moved to P4). | Otherwise an environment file that turns docs on does nothing in a native image, silently. | A WARN (silent mismatch stays possible). |
| D15 | The environment for `process-aot` and the training run. | The Maven property `synapse.build.environment`, default `build`. The service ships `application-build.yaml`. **Amended (Task 16b, maintainer 2026-11-08):** `process-aot` runs only with `-Paot`, so only a service that builds with `-Paot` or builds an image ships it (*Global Constraints → Samples*). | BUILD 3.3 asks the parent to pass one. `local` turns build-time switches on (reflection), and a deployed environment's name is the team's. | `local`; a per-team name with no default. |
| D16 | How the build environment reaches `process-aot` and the image. | `process-aot` `<profiles>` in the parent's `spring-boot-maven-plugin` configuration, on an execution with the id `process-aot`, the id Boot's starter-parent `native` profile uses, so AOT runs once (F30). `build-image` env: `BP_JVM_VERSION=${synapse.image.jvm-version}` (25), `BP_JVM_AOTCACHE_ENABLED=true`, `TRAINING_RUN_JAVA_TOOL_OPTIONS=-Dspring.profiles.active=${synapse.build.environment}`. The parent never sets `BP_SPRING_AOT_ENABLED`: Paketo fails the build for an AOT-processed jar with it and `TRAINING_RUN_JAVA_TOOL_OPTIONS` both set (F25; `parent.adoc` says why). **Corrected (pre-review cleanup, 2026-11-08):** the `<profiles>` route above was replaced by option A (maintainer, 2026-11-08; DESIGN.md, *Build environment reaches AOT as the default profile*). The `process-aot` execution, in the opt-in `aot` profile (D15), passes `--spring.profiles.active=` and `--spring.profiles.default=${synapse.build.environment}` and sets `<profiles combine.self="override"/>`, because `<profiles>` baked the build profile into the generated code, so it was active at run time beside the deployed environment. The training run keeps `-Dspring.profiles.active=${synapse.build.environment}`, now after `-XX:+UseG1GC` (D47). | Boot's documented parameters and Paketo's documented variables. A system property outranks `application.yaml`'s `${ENV}`, so there is still exactly one environment. | A Dockerfile-only recipe. |
| D17 | Java 21 images. | `synapse.image.jvm-version=21` keeps `BP_JVM_AOTCACHE_ENABLED`, and the buildpack trains CDS. Task 17 verifies this. If it doesn't, the parent switches to a profile. | Paketo deprecates `BP_JVM_CDS_ENABLED` in favour of the AOT-cache flag. | A `java21-image` profile. |
| D18 | "The parent manages `synapse-logging-log4j2` and its setup." | The parent manages the version (BOM import) and documents the setup. The exclusions stay in the service module, as the samples and archetypes write them. | Maven can't exclude `spring-boot-starter-logging` globally. Excluding it in managed starters would leave Logback users with no backend, which BUILD §13 forbids ("Teams that don't add it keep Boot's Logback"). | Managed starters with the exclusion (breaks Logback users). |
| D19 | The Mockito agent for consumers. | The parent adds `-javaagent` for `mockito-core` to surefire and failsafe when the module has it, as the root build does. | JDK 21+ warns on self-attach, and later JDKs refuse it. Every consumer hits this. | Leave it to teams. |
| D20 | Which runtime pins the parent repeats, and how they stay in sync. | Every third-party entry of `synapse-bom`, repeated in the parent's `dependencyManagement`. The guava CVE pin is already in the BOM (plan 3, `7d05b1ff`). The `error_prone_annotations` convergence pin moves into the BOM too (provisional, F14: it's on consumers' runtime path; the maintainer decides). The sync check is plan 3's `bom-guava-pin` invoker test, extended to every third-party BOM pin across the root, the BOM and the parent; it runs in every build, so CI needs no extra step (F14). | BUILD §3.4 and §13. One mechanism, which already exists and runs everywhere. | Repeat only the Boot-managed overrides (none today); a separate CI script (was this row's first choice: a second mechanism). |
| D21 | Samples' parent. | `synapse-parent`, as a consumer's project. The root build adds Spotless and the package-info license check over `samples/**` (root-only executions). Error Prone/NullAway and JaCoCo don't apply to samples. CI's diff-cover leaves `samples/` and `archetypes/` out. | The maintainer reviews the framework as users use it, and the archetypes generate the same shape. | The root build parent (all gates, but not a user's build). |
| D22 | JDK 21 smoke for samples. | Samples run all their ITs on the JDK 21 leg: `-Djvm` reaches surefire/failsafe through its user property, but `synapse.it.groups` is a root property. **Corrected (Task 22 fix round, 2026-11-09):** since 69464036 and eb79b7a3 the JDK 21 pull request leg runs none of the samples' ITs: `.config/jdk21-pr-excluded-its.txt` (Failsafe's `excludesFile`) lists every sample's package, and the build-rules test `jdk21-pr-excludes` fails when a sample is missing from it. The samples' ITs run on the JDK 25 leg of every pull request and in the nightly full JDK 21 run; on the PR's JDK 21 leg, the archetype ITs build projects shaped like hello-rest and the reference bookstore. | Acceptance 18 wants the reference ITs on 21. They are short. | Tag-based subsets in each sample (repeats root config). |
| D23 | `rest-books`' gRPC part. | It moves to `grpc-inventory` (Task 11), and `rest-books` becomes the REST-only tour (Task 10). The REST + gRPC "same operation" demo is the reference app's parity suite. | One use case per sample. The gRPC mapping rules need their own domain (timestamps, maps, `oneof`). | Keep gRPC in `rest-books` (two gRPC demos to maintain). |
| D24 | `rest-books`' parent. | It moves to `synapse-parent` too (D21). Its artifactId becomes `rest-books` (groupId `io.americanexpress.synapse.samples`). | The same rule for every sample. | Keep the root parent. |
| D25 | The gRPC sample's name and domain. | `samples/grpc-inventory`: `contract-inventory` holds the records, errors, `.proto` and generated classes; `service-inventory` holds the operations, an in-memory store and `api.grpc`. | The inventory domain needs `Timestamp`, `Duration`, wrappers, enums, repeated, map, nested and `oneof` fields naturally. BUILD 4.1: the `.proto` and generated classes live in `contract-<name>`. | `grpc-catalog` (books again). |
| D26 | The data sample. | `samples/multi-store`:<br>• `data-orders` (JPA, `orders`);<br>• `data-ledger` (JDBC, `ledger`, an `Auditable` with custom columns);<br>• `service-orders`.<br>Two Postgres databases, no primary store, `@Transactional("orders")`/`("ledger")`, readiness opt-in for `orders` only. | The maintainer's list. Two data modules also show module-owned configuration from two jars. | One module with two stores. |
| D27 | The reference app's modules. | `contract-bookstore` (records, `BookErrors`, `.proto`, generated classes), `data-books` (`@SynapseDataStore("books")`, JPA, Flyway) and `service-bookstore` (operations, `api.rest`, `api.grpc`). No business layer in this first cut: operations call the repository. | The maintainer's list. `rest-books` shows business operations. | Add a `business` package (more code, no new framework behaviour). |
| D28 | Reference app writes: is a version required? | `UpdateBookRequest.version` and `DeleteBookRequest.version` are non-null `VersionCondition<Long>`: required, so lost updates are impossible. `*` satisfies "required". | A prescriptive template. `rest-books` shows the optional form. | `@Nullable` on update (shows the 409 race path). |
| D29 | Reference app environments. | `local` (`compose.yaml` Postgres), `test`, `build` (D15) and `dev`, an example deployed environment that the AOT and image runs use (and the native runs in P4), with the URL and credentials from environment variables. | The maintainer named local/test. `build` is required (D15), and a native run can't use `local` (reflection differs from the build). | No `dev` (the runs would use `build`). |
| D30 | Docker Compose. | A plain `compose.yaml` (`postgres:18.6-alpine`) and `docker compose up -d` in the README. No `spring-boot-docker-compose`. | Boot's compose support registers one unqualified `JdbcConnectionDetails`, and Synapse stores need qualified ones (plan 4, *Test connections*). | `spring-boot-docker-compose` (the store wouldn't use it). |
| D31 | ~~Does the reference app serve `/v3/api-docs`? Yes, `springdoc.api-docs.enabled: true` in its `application.yaml`.~~ **Overruled (ledger ruling, 2026-11-07):** the maintainer's decision is "`/v3/api-docs` off by default, an environment file turns it on". | **New choice:** the reference app turns it on in `application-local.yaml` only, with a comment that Synapse's default is off. Its `application.yaml` doesn't set it. The native verification (moved to P4) turns it on for its own build and run: `process-aot` in the `native-it` profile gets `springdoc.api-docs.enabled=true` through `systemPropertyVariables`, and `BookstoreNativeIT` starts the executable with `SPRINGDOC_API_DOCS_ENABLED=true`, so springdoc is still checked in the image (D14 stays). Tests that read `/v3/api-docs` set the property themselves (F4). | Every sample follows the safe default; the native check still covers springdoc. | `application.yaml` (the first choice: docs served in every environment). |
| D32 | Where the native checks run. | **Moved to P4 (maintainer, 2026-11-08):** the `native-it` profile and `BookstoreNativeIT` are built in P4, together with the functions sample's native build. `BookstoreAotIT` stays here. The original choice: `BookstoreNativeIT` in a `native-it` profile runs the parity scenarios and the native checks against the native executable. It runs nightly and in the release build job. Every PR runs the same scenarios against the packaged jar in AOT mode (`BookstoreAotIT`). **Amended (Task 16b, maintainer 2026-11-08):** Spring AOT is opt-in, so `BookstoreAotIT` runs only with `-Paot`, in the nightly `aot-reference` job (JDK 25 and 21), not on pull requests. | Native builds take minutes and need GraalVM. AOT mode on the JVM catches most AOT faults (nightly since Task 16b). | Native on every PR. |
| D33 | A native or AOT check that fails on a Synapse resource or hint. | A missing hint is a bug: fix it in the owning module, starting with a failing `RuntimeHintsPredicates` test. A behaviour that can't hold in a native image **stops the task** for a maintainer decision, except module configuration served from `resource:` URLs, which is decided in advance (D45, moved to P4). Native runs are in P4; in this plan the rule applies to AOT mode. | BUILD §3.3; DESIGN *Open questions* (native, unverified). | Patch around it in the sample. |
| D34 | The tracing path the archetypes default to (DESIGN *Open questions*). | The OpenTelemetry starter. The Java agent is a recipe: a Maven profile `otel-agent` plus `OpenTelemetryAgentConfiguration` (an `OpenTelemetry` bean returning `GlobalOpenTelemetry.get()`, `@ConditionalOnClass(name = "io.opentelemetry.javaagent.bootstrap.AgentInitializer")`, a class the 2.33.1 agent jar contains). | BUILD §5.3 supports both, and the starter needs no extra artifact. The condition is the agent's own class, not a Spring profile: environments never drive conditions. | Agent by default. |
| D35 | Boot's `OTEL_*` mapping by default (DESIGN *Open questions*). | Boot's default (on). Docs show `management.opentelemetry.map-environment-variables=false` for teams whose agent reads `OTEL_*` itself. | No Synapse alias or override of a Boot default without a safety reason. | Off by default for Synapse apps. |
| D36 | The Synapse agent extension (`synapse-opentelemetry-agent-extension`): `inbound.trusted=false` and outbound stripping under the agent, and a JVM-wide baggage propagator. | Not in plan 5. It gets its own plan. Plan 5 documents that `trusted=false` doesn't restart the trace under the agent. | It's a new published artifact not in BUILD §2. It is built against the agent's extension API, its outbound half needs P2 clients, and two open questions decide its scope. | Build it here (a large, separate artifact). |
| D37 | Archetypes in P1 (BUILD said P6; DESIGN *Open questions* lists the set). | Two archetypes now, in `archetypes/`, published with the milestone:<br>• `synapse-archetype-quickstart` (one module, shaped like `hello-rest`);<br>• `synapse-archetype-service` (contract, service and data modules, shaped like `reference-bookstore`).<br>Each has an `archetype:integration-test` that generates a project, builds it and compares its file list with the sample's. **Corrected (Task 22 fix round, 2026-11-09):** each IT's `verify.groovy` compares the generated file list with a static `expected-files.txt`, not with the sample. What keeps the templates and the samples together is the build-rules test `archetype-drift` (added in this round): it compares the code of every quickstart file with hello-rest's, and the service archetype's infrastructure files (image, agent recipe, environment files, the scenario suite's `AppProcess`, `Protocol` and `Target`) with the bookstore's, ignoring comments, blank lines and import order. The service archetype's domain files (`Item`, against the bookstore's `Book`) are not compared. | The maintainer asked for archetype tasks and for samples laid out as the archetype generates them. P6 revisits the set. | P6 only; one archetype with options. |
| D38 | The `keep-alive` convention. | Not set in the samples: Tomcat's and Netty's non-daemon threads keep the JVM alive. The docs say when to add `spring.main.keep-alive=true` (no server, virtual threads only). | BUILD says "where needed". | Always set it. |
| D39 | Readiness and `/health` (acceptance 15). | The reference app maps Boot's groups to `server:/health` with `additional-path` and `include: readinessState` (a user-defined group otherwise includes every indicator; fb54ea49), and `books` opts in to readiness. `multi-store` shows a store that didn't opt in. | Acceptance 15's `/health` part has no owner yet. | — |
| D40 | Samples index. | `samples/README.md`: a table of sample → use case → features → how to run. | Maintainer instruction. | — |
| D41 | The parked rate-limited failure-log policy. | Not in plan 5 (a later phase, own branch; BUILD §15). | Not a P1 sample feature. | — |
| D42 | Archetype ITs need the reactor's snapshots. | `archetype:integration-test` with `localRepositoryPath=${project.build.directory}/it-repo`, filled by `maven-invoker-plugin:install` (with the reactor artifacts the generated project uses as `extraArtifacts`), and a settings file that mirrors the local repository (as `tests/synapse-build-rules-tests`). | The pattern the build-rules tests already use. | Install to `~/.m2` (pollutes it). |
| D43 (F1) | A stale version over gRPC: REST answers a stale `If-Match` `PRECONDITION_FAILED` (412, `STALE_VERSION` kept as the cause; 2c `Preconditions.translate`), while plan 3 answers `STALE_VERSION` → ABORTED. | gRPC copies REST's rule. A `STALE_VERSION` from an operation whose request carried a non-empty `etag` bound to a `VersionCondition` (including `*`) becomes `PRECONDITION_FAILED` with the failure as its cause → FAILED_PRECONDITION. A request with no `etag` (an optional condition left out) that lost a race stays `STALE_VERSION` → ABORTED (REST 409). | REST is already built, documented (`rest.adoc`) and tested this way; parity needs one code and category. AIP-154 and AIP-135 say ABORTED for a mismatch; `synapse.api.grpc.errors.status.[PRECONDITION_FAILED]=ABORTED` flips it (it also flips the weak-tag case, D4). This corrects the design note's *Decision* 3. | Both protocols `STALE_VERSION` (changes REST's built and documented rule). |
| D44 (F5) | Tasks 2–3 said "move unchanged", but `EntityTags`, `InputDigest`, `PageCursors` and `CursorKeys` import spring-web or REST internals, which core doesn't have, and `max-offset` is a REST key. | A protocol-neutral core API, written out in *Protocol-neutral core API*: `EntityTags` with its own `Tag`; `InputDigest.of(input, extra)` drops `PageQuery` and every `VersionCondition` component by type, and takes plugin-supplied `@`-keyed entries (REST passes `@version`, parsed in REST); `PageCursors` takes the operation name, the input, the extra entries and the token's field name; `synapse.paging.max-offset` and `synapse.paging.cursor.*` in `SynapsePagingProperties`. `ETagValues`, `PagingSettings`, `PageQueryReader` and `SortKeys` stay in REST. | Core can't depend on spring-web or a plugin; one digest and one scope make a REST cursor page over gRPC. | A spring-web dependency in core; a shared paging module. |
| D45 (F2) | Module configuration in a native image: `ModuleConfigFiles` fails startup for a URL that `ConfigSources.isLocal` rejects (only `file:` and `nested:`), and a native image serves classpath resources as `resource:` URLs (predicted, unverified). | Decided now; the change and its tests moved to P4 with Task 16 (2026-11-08), and the native run there doesn't stop on it. In a native image (`NativeDetector.inNativeImage()`), a `resource:` URL is local (it does no I/O). Each such URL is its own module root, and its origin is the fixed label `native image`, because the jar name is gone. The duplicate-key check stays and names the file (`config.yaml`) and the label. A failing core test comes first (Task 16 Step 1, moved to P4). | It's the one native difference the scan predicts from the code; deciding it now keeps D33's stop for the unknowns. | Stop in Task 16 (the first draft); a build-time index of module files with their jar names (more machinery). |
| D46 (F3) | REST sends no `ETag` on PUT or DELETE (`RouteSpec.etag`: GET/HEAD, or POST with `.created`); the first draft had gRPC write `etag` on any method and the update scenario expect `"1"`. | gRPC `.etag` follows REST: it's allowed on a method bound to a `QueryOperation` (REST's GET), and on a `CommandOperation` method whose input has no `VersionCondition` component (the create form, REST's POST with `.created`); anywhere else startup fails, naming the method. The parity scenario reads the new ETag with a Get after an update. | Same token on the same methods. gRPC has no `.created` marker, so "a write that names no version" stands in for "create" (maintainer to confirm). | AIP-134's resource-with-etag on Update (a protocol difference); a gRPC `.created()` marker (new API). |
| D47 | The image's garbage collector (added in Task 20b from Task 17's Decision 16 and Task 20's maintainer question Q2). The AOT cache is trained under G1 on a large build machine; in a 512 MB, one-CPU container the JVM's ergonomics pick the Serial collector, and the JVM stopped at start with `AOT cache has aot-linked classes but the archived heap objects cannot be loaded`. | Pin `-XX:+UseG1GC` for the training run and every run, in both image builds: the parent's `TRAINING_RUN_JAVA_TOOL_OPTIONS` and `BPE_APPEND_JAVA_TOOL_OPTIONS`, and the Dockerfiles' training `RUN` and `ENTRYPOINT`. | Left to ergonomics, the collector is a property of the pod, so one image works or fails by its memory limit. Task 20a measured the cause: Serial's default 8 MB initial heap leaves 5504 KB of old generation for 6074 KB of archived heap objects; `-Xms10m` or more starts (`parent.adoc`, *The garbage collector*). G1 needs no `-Xms` to choose. | No pin, with an initial heap that holds the archived objects (a heap setting per service, which grows with its cache); or document only. Cost to reverse: the parent's two variables, two Dockerfiles, parent.adoc's collector section and the smoke tests' 512 MB runs. |

## Only the maintainer can decide (provisional choice in brackets)

1. API contract parity details over gRPC: D3 [FAILED_PRECONDITION], D4 [PRECONDITION_FAILED], D5 [quoted token], D6 [fixed `etag` field], D8 [`.etag(ComponentRef)`], D43 [a stale `etag` is `PRECONDITION_FAILED`, as REST], D46 [`etag` on reads and creates only; "create" = a command with no `VersionCondition`], and the documented differences: a `VersionCondition` on a gRPC read fails startup (F12; amended in Task 2, Decision 20: only a non-null one fails, as on REST, and gRPC has no 304), one per message (F26).
2. The unique-value code name: D2 [ruled: `ALREADY_EXISTS`].
3. gRPC page tokens and the cursor in core, with renamed keys: D9–D12, D44 [built here, `synapse.paging.cursor.*` and `synapse.paging.max-offset`, AIP-132 `order_by`, the protocol-neutral core API].
4. API docs under AOT: D14, D31 [ruled: off by default, on in `application-local.yaml`; startup fails on a mismatch, one failure for every build-time switch].
4a. Module configuration in a native image: D45 [`resource:` URLs are local, origin `native image`]. Moved to P4.
4b. `error_prone_annotations` in `synapse-bom`: D20 [yes, provisional].
4c. Windows `LongPathsEnabled` on the maintainer's machine [left at 0; Task 19 keeps paths short].
5. Tracing default and the `OTEL_*` mapping: D34, D35 [starter; Boot's mapping on].
6. The agent extension: D36 [own plan; is it a published artifact?].
7. Archetypes in P1 and their set: D37 [two, published with M1].
8. The samples set, names and `rest-books` without gRPC: D23–D27 [as listed].
9. Samples on `synapse-parent`, outside the coverage gates: D21, D22.
10. The build environment's name: D15 [`build`].
11. Any native finding under D33 that changes a Synapse behaviour (moved to P4).
12. The image's garbage collector: D47 [G1 pinned for the training run and every run, in both image builds].

## Defaults this plan sets (confirm at review)

| # | Default | Why |
|---|---|---|
| 1 | gRPC `etag` binding rules:<br>• the request field is exactly `string etag`;<br>• `VersionCondition` binds from it only (no `.map` to another field);<br>• one `VersionCondition` component per input message (REST can bind `If-Match` and `If-None-Match`; no P1 route binds both; a documented limit, F26);<br>• on a method bound to a `QueryOperation`, a `VersionCondition` component fails startup. This is a documented protocol difference: REST lets a read bind a `@Nullable` one from `If-None-Match`, because conditional GET is HTTP caching (F12).<br>Amended in Task 2 (Decisions 19 and 20): `.map("if_none_match", ...)` binds the condition from a `string if_none_match` field instead, and a read may bind a `@Nullable` one, as REST's `GET` does; a non-null one fails startup on both protocols. gRPC has no 304 (see `grpc.adoc`, *Differences from REST*). | D6. |
| 2 | `.etag(ComponentRef<T, ?>)` over gRPC takes a component of the output record that the `ConversionService` turns into a string (REST's types: string, number, boolean, enum, `java.time`); `null` leaves the field empty. It's allowed only where REST allows `.etag`: a method bound to a `QueryOperation`, or a `CommandOperation` method whose input has no `VersionCondition` (the create form). Elsewhere startup fails, naming the method (F3, D46). **Corrected (Task 22 fix round, 2026-11-09):** since Task 2 (decision log, plan 5 Task 2) both protocols write and read the token with core's `VersionTokens` codec, never the `ConversionService`: strings, integral numbers and the `java.time` types listed in `rest.adoc` (*Version tokens*). A boolean, an enum or a floating-point version fails startup. | As REST. |
| 3 | AIP-158 request fields:<br>• `int32 page_size`;<br>• `string page_token`;<br>• optional `int32 skip`;<br>• `string order_by` (needed when `.sortable(...)` is declared).<br>Response: one repeated message field for the items (any name), `string next_page_token` (empty when there is no next page; by REST's `Link: next` rule it carries the next cursor, or for a page without one a token with the next offset that REST's `cursor` also accepts), `bool has_next` (REST's `hasNext`; beyond AIP-158, provisional: maintainer to confirm) and, when declared, an `optional int32` or `optional int64 total_size`. Startup fails on a missing field or a wrong type. A proto3 `optional` page field follows REST's rules for a sent value; a plain field's default is unset. | AIP-158/132; D11, D12; Task 4 fix round 1 (C1). |
| 4 | Paging errors over gRPC use REST's codes and categories: size over the cap, `skip` over `synapse.paging.max-offset`, unknown sort key, tampered or replayed token → INVALID_ARGUMENT with the same `ErrorInfo.reason` that REST puts in `code` (`VALIDATION_FAILED`). The violation names the gRPC field (`page_size`, `skip`, `order_by`, `page_token`) where REST names its query parameter. | Parity. |
| 5 | `CursorScope` keeps its meaning. `InputDigest` changes in one rule (F5): it drops `PageQuery` and every `VersionCondition` component by type, where REST dropped the components bound to `If-Match`/`If-None-Match` by route. The two agree on every P1 route, because REST binds those headers only to `VersionCondition` components. The `@version` entry is passed in by the plugin: REST passes the route version (parsed in REST), gRPC passes none. The sort in the scope is the constant names on both protocols. A REST cursor from an unversioned route pages over gRPC for the same operation, filter and sort. | D10, D44. |
| 6 | The generated-key WARN moves to core and names `synapse.paging.cursor.key`. Each plugin calls `PageCursors.reportGeneratedKey()` when at least one of its routes or methods returns `PageResult`, and it's logged at most once per context, even with both plugins. | One codec. |
| 7 | `BoundOperationMocksCheck` beans: `synapseRestBoundOperationMocksCheck` and `synapseGrpcBoundOperationMocksCheck`, each `@ConditionalOnMissingBean(name = …)`. The messages stay as today (`@SynapseRestTest: declare a @MockitoBean …`). | D13. A type-based back-off would let one slice's check hide the other's. |
| 8 | Build-time switches under AOT (F4). Core internal `BuildTimeSwitch` beans, one per property, and one core `BuildTimeSwitchCheck` (`synapseBuildTimeSwitchCheck`, a `SmartInitializingSingleton`, `@ConditionalOnMissingBean(name = …)`) that runs only when `AotDetector.useGeneratedArtifacts()`. It collects every mismatch and fails once, listing them in property-name order. `OpenApiAotCheck` (`synapseOpenApiAotCheck`) is the switch for `springdoc.api-docs.enabled` (resolved now, versus whether springdoc's `SpringDocWebMvcConfiguration` bean exists; the plan first named `OpenApiWebMvcResource`, Tasks 5–9 fix round); plan 3's `ReflectionAotCheck` (`synapseGrpcReflectionAotCheck`) becomes the switch for `spring.grpc.server.reflection.enabled`, with its conditions unchanged.<br>Message: `These properties are fixed at build time in a native image or AOT run, and differ from the build: spring.grpc.server.reflection.enabled is <now> but the build used <then>; springdoc.api-docs.enabled is <now> but the build used <then>.` (only the mismatched ones, in property-name order)<br>Action: `Set each to the build's value for this run. To change one, set it in the build environment's file (application-<build env>.yaml) and in each deployed environment's file, then build again.` | D14, matching plan 3's #21. |
| 9 | Parent properties: `synapse.build.environment=build`, `synapse.image.jvm-version=25`. | D15–D17. |
| 10 | The parent's Mockito agent uses `maven-dependency-plugin:properties` and the `antrun` condition the root build uses, as parent `<plugins>` (inherited), with versions from Boot's plugin management. **Corrected (Task 22 fix round, 2026-11-09):** since 33048776 the condition is `build-helper-maven-plugin:regex-property`, not `antrun` (`parent/synapse-parent/pom.xml`), so builds print no Ant property warnings; `maven-dependency-plugin:properties` still resolves the agent's path. | D19. |
| 11 | Samples' Spotless and license checks are root-only executions (`<inherited>false</inherited>`). Spotless covers `samples/**/src/**/*.java` only; it skips archetype templates, which hold `${package}` and Velocity syntax. The license-header check covers the templates too (`archetypes/**/src/main/resources/archetype-resources/**`) (F18). | D21. |
| 12 | The parity suite's outcome model (F6, F13):<br>`record Outcome(Kind kind, @Nullable ErrorCategory category, @Nullable String code, @Nullable BookView book, @Nullable String etag)` with `enum Kind { OK, CREATED, NO_CONTENT, FAILED }`.<br>On a failure, `code` is REST's problem `code` or gRPC's `ErrorInfo.reason`, and `category` is that code's `ErrorDetails.category()` (looked up in `SynapseErrors` and `BookErrors`), never derived from the transport status. Each protocol client also asserts its own transport status for the category and code (REST 400/404/409/412/428; gRPC INVALID_ARGUMENT/NOT_FOUND/ALREADY_EXISTS/ABORTED/FAILED_PRECONDITION), so a wrong status fails that protocol's run. No `NOT_MODIFIED`: 304 is REST-only and stays in `BookstoreRestTest`. | A protocol-neutral comparison: parity means the same code and category. Both protocols must produce equal outcomes. |
| 13 | Reference app Flyway: `db/migration/books/V1__books.sql`. Table `book`: `id uuid` primary key, `isbn varchar(17)` unique, `title`, `author`, `published_year int`, `version bigint`, and the audit columns from `AuditedEntity`. | Plan 4's bases. |
| 14 | Reference app sort keys: `SortKey { TITLE, PUBLISHED_YEAR }`. REST `sort=title,-publishedYear`; gRPC `order_by="title, published_year desc"`. Page size: the core defaults (20/100). | D11. |
| 15 | Reference app ports: REST 8080, gRPC 9090 (Boot's). In ITs, `server.port=0`, `spring.grpc.server.port=0`, and `management.server.port` unset. | Boot defaults. |
| 16 | The `dev` environment: `config-dev.yaml` in `data-books` sets `url: jdbc:postgresql://books-db.dev.example.internal:5432/books`. Username and password come from `SYNAPSE_DATA_STORES_BOOKS_USERNAME`/`_PASSWORD`. AOT and image runs (and native runs, in P4) override the URL with `SYNAPSE_DATA_STORES_BOOKS_URL`. | BUILD 3.6 *Keys without defaults*. |
| 17 | The `build` environment: `config-build.yaml` sets `url: jdbc:postgresql://127.0.0.1:1/books`, `username: build`, `password: ""`. The training run exits on refresh, so nothing connects (acceptance 17). | D15. |
| 18 | `local`: `compose.yaml` on port 5432, database, user and password `bookstore` (`config-local.yaml`, marked *local compose only*). It turns on `synapse.errors.include-detail`, `spring.grpc.server.reflection.enabled`, `springdoc.api-docs.enabled` (D31) and CORS for `http://localhost:3000`. | Local conveniences per BUILD 3.6. |
| 19 | Archetype properties: `groupId`, `artifactId`, `version`, `package`, and `serviceName` (service only; default `example`). The generated module directories are `contract-${serviceName}`, `data-${serviceName}` and `service-${serviceName}`. | Maven archetype conventions; BUILD 4.1 module names. |
| 20 | The archetypes depend on the Synapse version they're released with: `synapse-parent` `@synapse.version@`, filtered at archetype build time in the template `pom.xml` files only, with `@` delimiters and `useDefaultDelimiters=false`, so Maven filtering never meets Velocity's `${…}` (F18). | One version to bump. |
| 21 | Agent recipe: profile `otel-agent` in `service-bookstore`. `maven-dependency-plugin:copy` puts the agent jar in `target/otel/opentelemetry-javaagent.jar`. It sets `spring-boot.run.jvmArguments=-javaagent:… -Dotel.service.name=bookstore -Dotel.traces.exporter=none -Dotel.metrics.exporter=none -Dotel.logs.exporter=none`. The `copy` execution is bound to `generate-test-resources` in the default build (Task 17 fix round; it was `pre-integration-test`), so `BookstoreAgentIT` runs on every build and `-Potel-agent spring-boot:run`, which builds to `test-compile`, has the jar on a fresh checkout; an Enforcer `requireFileChecksum` in `process-test-resources` fails the build unless the jar has the pinned SHA-256 (`opentelemetry-javaagent.sha256`). The profile only adds the `spring-boot:run` arguments. | D34. |
| 22 | Nightly jobs added: `native-reference` (GraalVM, Docker) and `image-reference` (`build-image` + smoke), both in `report-failure.needs` (F32). **Moved to P4 (2026-11-08):** `native-reference` and its job in the release workflow's secret-free build job (gate 9). Only `image-reference` stays in this plan. | Gate 9; BUILD §13 images. |
| 23 | gRPC stale-write rule (F1, D43): the gRPC operation handler translates `STALE_VERSION` into `PRECONDITION_FAILED` (cause kept) when the request's `etag` was non-empty and bound; otherwise it stays `STALE_VERSION`. | REST's `Preconditions.translate`. |
| 24 | Module config in a native image (F2, D45): `resource:` URLs are local only in a native image; origin label `native image`; the startup report line reads `Synapse module config [config.yaml] native image`. Moved to P4 with Task 16. | No jar name to show. |
| 25 | `AppProcess` (F27): picks free ports up front (`new ServerSocket(0)`, closed before launch) and passes `--server.port` and `--spring.grpc.server.port`; stops with `destroy()`, then `destroyForcibly()` after 30 s. On Windows both are a forced kill, so no test asserts a graceful shutdown. | No port discovery from logs. |
| 26 | The audit system user (F23): every sample write has no principal, so `synapse.data.auditing.system-user` (default `system`) is recorded. `multi-store` and the reference app document it in their READMEs and assert it once each. | Maintainer decision 5 (2026-11-07): document it. |

## Review Focus

1. **A REST ETag replayed over gRPC, and the reverse.** A user reads a book over REST and updates it over gRPC with the `ETag` header's exact value. A stale value must be `PRECONDITION_FAILED` over gRPC (FAILED_PRECONDITION), exactly the code REST answers with 412 (F1, D43). A write that sent no version and lost a race is `STALE_VERSION` on both (409 / ABORTED). Pinned in Task 2: `staleEtagIsFailedPreconditionPreconditionFailed`, `unconditionalLostRaceIsAbortedStaleVersion`; and in Task 14: `etagFromRestUpdatesOverGrpc`, `staleEtagFromGrpcIsRejectedOverRest`.
2. **A cursor issued over REST, then used over gRPC with another sort, or for another operation.** It must be INVALID_ARGUMENT with REST's code, never a page of other data. Pinned in Task 3: `InputDigestTest`/`PageCursorsTest` (the digest drops `VersionCondition` by type, extra entries change it); in Task 4: `cursorReplayedWithAnotherSortIsInvalid`; and in Task 14: `restCursorPagesOverGrpc`.
3. **An AOT-processed app (AOT mode on the JVM here; the native image from P4) started in an environment whose build-time switch differs** (for example `local`, which turns gRPC reflection and the API docs on). Startup must fail and name every key that differs, in one failure, never serve silently without them (F4). Pinned in Task 8: `mismatchFailsInAotMode`, `everyMismatchIsInOneFailure`; and in Task 15: `aotRunInLocalNamesEveryBuildTimeSwitch`.
4. **Module configuration from jars inside a native image.** `config.yaml` from `data-books` must load from its `resource:` URL. The startup report must name its origin as `native image` (D45), and two modules with the same key must still collide. Any other difference from the JVM stops the task (D33). Pinned in Task 16 (moved to P4): `ConfigSourcesTest.resourceUrlIsLocalInANativeImage`, `moduleConfigLoadsInNative`, `startupReportNamesModuleOrigins`. Task 16 moved to P4 (2026-11-08); the JVM baseline stays in Task 15.
5. **A sample README that drifted from the app.** Every command in it must produce the pasted output against a fresh run. Pinned in Task 21, which runs every README and diffs the volatile-masked output.

---

## Protocol-neutral core API (pre-flight F5): what Tasks 2–3 produce and Task 4 consumes

Nothing below imports spring-web (`org.springframework.http.*`, `org.springframework.web.*`) or a plugin (`io.americanexpress.synapse.api.*`). Core keeps spring-web in test scope only. Tasks 2 and 3 move the code with `git mv` where a class keeps its role, then change it to these signatures. A class not listed here stays in its plugin.

**Entity tags (Task 2).** Package `io.americanexpress.synapse.core.internal.conditional` (internal, `@NullMarked`):

```java
public final class EntityTags {
    /** One field value: "*" alone, or an RFC 9110 entity-tag list; same strict grammar as REST's parser today. */
    public static Parsed parse(String value);                  // IllegalArgumentException when empty or off-grammar; the message never repeats the value
    /** Several field lines of one header, as one list (RFC 9110 §5.3): joined with "," and parsed. */
    public static Parsed parse(List<String> fieldValues);
    /** The strong tag as sent on the wire: "\"" + opaque + "\"". */
    public static String strong(String opaque);                // IllegalArgumentException when opaque is empty or has a char outside etagc
    /** RFC 9110 etagc: %x21 / %x23-7E / %x80-FF. */
    public static boolean isEtagc(char c);

    public sealed interface Parsed permits Parsed.Any, Parsed.Tags {
        record Any() implements Parsed {}                      // "*"
        record Tags(List<Tag> tags) implements Parsed {}       // in header order, non-empty, copied
    }
    public record Tag(String opaque, boolean weak) {}
}
```

REST keeps `ETagValues` (it reads a `RouteDefinition`) and adapts: `new org.springframework.http.ETag(tag.opaque(), tag.weak())` where it needs Spring's type, `Parsed.Any` for its old `wildcard()`, and `ETagValues.header(ETag)` returns `EntityTags.strong(tag.tag())`. gRPC uses `EntityTags` directly. Converting the opaque value to `V` is each plugin's job, with the `ConversionService` it already uses for scalars. `restAndGrpcAcceptTheSameToken` (Task 2) pins that both read `"3"` as `Version(3L)`.

**Paging properties (Task 3).** `io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties` (public, already registered by `SynapseCoreAutoConfiguration`) gains two components:

```java
@ConfigurationProperties("synapse.paging")
public record SynapsePagingProperties(
        @DefaultValue("20") int defaultSize,                   // synapse.paging.default-size (unchanged)
        @DefaultValue("100") int maxSize,                      // synapse.paging.max-size (unchanged)
        @DefaultValue("1000") long maxOffset,                  // synapse.paging.max-offset; >= 0 (was synapse.api.rest.paging.max-offset)
        @DefaultValue Cursor cursor) {                         // synapse.paging.cursor.* (was synapse.api.rest.paging.cursor.*)

    public record Cursor(
            @Nullable String key,                              // synapse.paging.cursor.key: base64 AES-256 key; unset or blank → generated at startup
            List<String> previousKeys,                         // synapse.paging.cursor.previous-keys: decrypt only; blank entries ignored
            @Nullable Duration maxAge) {}                      // synapse.paging.cursor.max-age: positive when set; unset → no expiry
}
```

Validation and `toString` move with them unchanged (the key is never printed), and their messages name the new keys. REST's `SynapseRestProperties.Paging` keeps only `parameters` (`synapse.api.rest.paging.parameters.*`). REST's `PagingSettings.of(...)` reads `maxOffset` from core's properties. Every `new SynapsePagingProperties(20, 100)` in tests becomes the four-argument form.

**Cursor codec (Task 3).** Package `io.americanexpress.synapse.core.paging` (public, `@Incubating`), moved from `io.americanexpress.synapse.api.rest.paging` with its contracts unchanged:

```java
public interface CursorCodec {
    String encode(String cursor, CursorScope scope);           // non-empty, A-Z a-z 0-9 - _ only
    String decode(String token, CursorScope scope);            // throws InvalidCursorException for every rejected token
}
public record CursorScope(String operation, List<SortOrder> sort, String inputDigest) {
    public String normalizedSort();                            // "TITLE:ASC,PUBLISHED_YEAR:DESC"; SortOrder.property is the sort enum constant's name on both protocols
}
public final class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String reason);              // DEBUG only; never the token or key material
}
```

Package `io.americanexpress.synapse.core.internal.paging` (internal):

```java
public record CursorKeys(Key current, List<Key> previous, boolean generated) {
    public static CursorKeys from(SynapsePagingProperties.Cursor cursor, SecureRandom random);  // messages name synapse.paging.cursor.key / previous-keys[i], never a value
    public @Nullable Key find(byte[] id);
}

public final class AesGcmCursorCodec implements CursorCodec {   // token format unchanged, so the tests move as they are
    public AesGcmCursorCodec(CursorKeys keys, Clock clock, @Nullable Duration maxAge, SecureRandom random);
    public boolean keyGenerated();
}

public final class InputDigest {
    public InputDigest();                                      // its own canonical JsonMapper (tools.jackson, as today)
    /**
     * SHA-256 (lowercase hex) of the input's filters written canonically: every record component except those of type
     * PageQuery and every component whose type is VersionCondition (by type), plus the extra entries.
     * A PageQuery input, or no input, with no extra entries, digests as {}.
     * @param extra plugin-supplied entries; each key starts with '@' (IllegalArgumentException otherwise), so none can
     *     clash with a component name. REST passes Map.of("@version", "<major.minor.patch>") on a versioned route
     *     (parsed in REST with SemanticApiVersionParser) and Map.of() otherwise; gRPC passes Map.of().
     */
    public String of(@Nullable Object input, Map<String, ?> extra);
}

public final class PageCursors {
    public PageCursors(CursorCodec codec, InputDigest digest);

    /** The token for a raw cursor, bound to CursorScope(operation, sort, digest.of(input, extra)). */
    public String encode(String operation, List<SortOrder> sort, @Nullable Object input, Map<String, ?> extra, String rawCursor);
    // IllegalStateException when the codec returns an empty or non-URL-safe token; the message names the codec class, never the token

    /** The raw cursor in a token; a rejected token is SynapseException VALIDATION_FAILED with Violation(field, "is not valid for this request"). */
    public String decode(String operation, List<SortOrder> sort, @Nullable Object input, Map<String, ?> extra, String token, String field);

    /** The bound input with its PageQuery's token replaced by the raw cursor (a record rebuilt with its canonical constructor); an input without a token as it is. */
    public @Nullable Object unwrap(String operation, @Nullable Object input, Map<String, ?> extra, String field);

    /** The token for the operation's next raw cursor, scoped by the input's PageQuery sort; null without a raw cursor. */
    public @Nullable String wrap(String operation, @Nullable Object input, Map<String, ?> extra, @Nullable String rawCursor);

    /** The input itself when it is a PageQuery, else its PageQuery component, else null. */
    public static @Nullable PageQuery query(@Nullable Object input);

    /** Logs the generated-key WARN (Defaults #6) at most once per instance, when the codec is AesGcmCursorCodec with a generated key. */
    public void reportGeneratedKey();
}
```

`field` is the name the caller used: REST passes its cursor parameter name (`PagingSettings.cursor()`), gRPC passes `page_token`. The logger is `io.americanexpress.synapse.core`. The WARN text: `synapse.paging.cursor.key is not set, so paging cursors are encrypted with a key generated at startup: a cursor is rejected after a restart or on another instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).`

**Beans (Task 3)**, in `SynapseCoreAutoConfiguration`, unconditional apart from back-off, because core can't see either plugin's bindings type:
- `CursorCodec synapseCursorCodec(SynapsePagingProperties, Clock)`, `@ConditionalOnMissingBean(CursorCodec.class)`: `new AesGcmCursorCodec(CursorKeys.from(properties.cursor(), random), clock, properties.cursor().maxAge(), random)` with `new SecureRandom()` (never `getInstanceStrong()`). An invalid key fails startup, as today.
- `InputDigest synapseInputDigest()`, `@ConditionalOnMissingBean`.
- `PageCursors synapsePageCursors(CursorCodec, InputDigest)`, `@ConditionalOnMissingBean`.

REST removes its three beans and keeps `PagingSettings`, `PageQueryReader`, `SortKeys`, `PageLinks` and `PageBody`. REST's `RouteCompiler` calls `reportGeneratedKey()` when a route returns `PageResult`; gRPC's compiler does the same for a method (Task 4).

**What Task 4 consumes:** `PageCursors.unwrap/wrap` with `Map.of()` and the field `page_token`; `PageCursors.query`; `SynapsePagingProperties.defaultSize/maxSize/maxOffset`; `reportGeneratedKey()`.

---

## Pre-flight rulings applied

The pre-flight scan (`preflight.md`, 34 findings) was ruled on in the ledger: every finding accepted with its recommended ruling, plus the specific rulings for F1, F2, F3, F4, F5, F9, F20/F21, F17/F18, Task 6, Tasks 12–13, the audit-user docs and long paths. The earlier ledger rulings on D2 (`ALREADY_EXISTS`) and D31 (API docs on only in `application-local.yaml`) stand. What each one changed in this plan:

| # | Change |
|---|---|
| F1 | gRPC copies REST's stale-write rule (D43, *Defaults* #23): a stale `etag` → `PRECONDITION_FAILED` (FAILED_PRECONDITION), an unconditional lost race → `STALE_VERSION` (ABORTED). Task 2 adds the rule and its tests; Tasks 12 and 14, Review Focus 1, *Protocol parity* and the design-note reference changed. |
| F2 | Decided now (D45, *Defaults* #24): `resource:` module-config URLs are local in a native image, origin `native image`. Task 16 Step 1 adds the core change with a failing test first; D33 and Review Focus 4 changed. (Task 16 moved to P4.) |
| F3 | gRPC `.etag` only on reads and creates (D46, *Defaults* #2); Task 14's update scenario reads the new ETag with a Get; Task 14's gRPC bindings name the methods. |
| F4 | Tests that read `/v3/api-docs` set `springdoc.api-docs.enabled=true` (Task 15); one core check reports every build-time switch mismatch in one failure (Task 8, *Defaults* #8; Task 15's test renamed); `native-it` passes the property to `process-aot` (`systemPropertyVariables`) and to the native process, and runs only `*NativeIT` (Task 16, moved to P4); D14, *Defaults* #8, DESIGN.md *Open questions* corrected. |
| F5 | *Protocol-neutral core API* (above) written out; D44 added; D10, D11, D12, *Defaults* #4–#6 changed; Tasks 2, 3 and 4 follow it. |
| F6 | `Outcome` compares the code and its category; each client asserts its own transport status (*Defaults* #12). |
| F7 | Task 2 Step 0: `git grep -n EntityTags -- core` first; adapt if 2c moved it (at `22ceaedf` it hasn't). |
| F8 | Task 1: `git fetch origin` once the controller names the heads; conflicts re-derived, not replayed; patch `0002` (interim `Int64Value` converter) skipped; the base is verified without `samples/rest-books`, whose gRPC tests are red until Task 2. |
| F9 | `DUPLICATE` → `ALREADY_EXISTS` everywhere (Execution base, Task 1, Tasks 13, 14, 20, the maintainer list); Task 1's D2 test and commit dropped; D2 marked overruled; DESIGN.md 4.1 row rewritten. |
| F10 | Resolved by F1 and F6: the stale scenario's outcome is equal on both protocols. |
| F11 | `bookstore.proto` `Book` has `int64 version` and `string etag`; `option java_package = "io.americanexpress.synapse.samples.bookstore.contract.proto"`; `.etag(ComponentRef<T, ?>)` (D8, Task 2 *Interfaces*). |
| F12 | *Defaults* #1: a `VersionCondition` on a gRPC read fails startup, as a documented protocol difference; "as on a REST read" dropped; Task 2's test comment changed. Provisional; in the maintainer list. |
| F13 | `getIfNoneMatch` and `NOT_MODIFIED` removed from the shared client; 304 stays in `BookstoreRestTest`. |
| F14 | Task 6 rewritten: `7d05b1ff` kept; `bom-guava-pin` extended to every third-party BOM pin across root, BOM and parent; no Java CLI, bash test or CI step; `error_prone_annotations` into the BOM provisionally (D20, maintainer list). |
| F15 | Tasks 6 and 7: `synapse-build-rules-tests` gets `synapse-parent`, `synapse-bom` (pom) and `synapse-core` as test-scope dependencies, so `invoker:install` puts them in `it-repo`. |
| F16 | Tasks 12–13 wait for plan 4 Task 14; `synapse-data-postgres` in `data-orders`, `data-ledger` and `data-books`; Task 12's POST gets `.created(...)` and `.etag(Order::version)`; `application-test.yaml` in each data module. |
| F17 | Tasks 18–19: the expected file list is `projects/<name>/expected-files.txt`, read by `verify.groovy`; no `reference/` directory. |
| F18 | `@synapse.version@` with `useDefaultDelimiters=false`, on the template poms only; README `##` headings escaped for Velocity (`#[[…]]#`); Spotless skips templates and the license check covers them (*Defaults* #11, #20). |
| F19 | READMEs build with `./mvnw -pl <service> -am package -DskipTests` and run `ENV=local java -jar …`; `spring-boot:run` only for a single-module sample after `-am install` (Tasks 9, 10, 15, 21). |
| F20 | grpcurl `fullstorydev/grpcurl:v1.9.3@sha256:<digest>` (digest read in Task 11); `host.docker.internal:9090` without `--network host`, with `--add-host=host.docker.internal:host-gateway` on Linux. |
| F21 | *Global Constraints → Windows*: the Docker Desktop bin directory on PATH and `MSYS_NO_PATHCONV=1` in every Docker-using step and script; Task 16's `docker run` rewritten (moved to P4). |
| F22 | Task 19's IT is `projects/s` with `artifactId=s`, `package=com.example.s`, `serviceName=s`; the longest generated path is reported; `LongPathsEnabled` stays 0 (the maintainer's setting). |
| F23 | The audit system user is documented (Tasks 12 and 15 READMEs, `data.adoc` in Task 20) and asserted once in each of Tasks 12 and 15 (*Defaults* #26). |
| F24 | Task 5's shared-check test uses two fixture bindings types in `synapse-test`'s own tests; the slices keep their registration tests. |
| F25 | The parent never sets `BP_SPRING_AOT_ENABLED`; `parent.adoc` says why (D16, Task 7). |
| F26 | One `VersionCondition` per gRPC message is a documented limit (*Defaults* #1, Task 2's `grpc.adoc`). |
| F27 | `AppProcess` picks free ports and stops with `destroy()`/`destroyForcibly()` (*Defaults* #25, Task 15). |
| F28 | `BookstoreNativeIT` sets `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,metrics` (Task 16, moved to P4). |
| F29 | `grpc-inventory`'s `application.yaml` sets `spring.application.name: service-inventory`. |
| F30 | The parent's `process-aot` execution id is `process-aot` (D16, Tasks 7 and 15). |
| F31 | Task 12's `ReadinessIT` reads the `db` component through `/actuator/health` with `management.endpoint.health.show-components=always` in its test properties. |
| F32 | `image-reference` added to `report-failure.needs` (Task 17, *Defaults* #22). |
| F33 | The `grpc.adoc` examples move to `grpc-inventory` in Task 11, not Task 10. |
| F34 | Task 18 Step 2 checks that `archetype:integration-test` filters `@localRepositoryUrl@` in `settings.xml`; if it doesn't, the URL is passed through the plugin's settings filtering instead. |

The scan's notes without a number are applied too: Task 4 removes `BindingChecks`'s "plan 3b" rule and its Javadoc; Task 10 updates the README's jar paths for the new artifactId; Task 11 quotes the bracketed YAML key `"[OUT_OF_STOCK]"`.

---

## File structure

```
pom.xml                              modules (samples, archetypes), archetype plugin version, root-only
                                     sample Spotless/license executions
bom/synapse-bom/pom.xml              error_prone_annotations pin (the guava pin is already there, plan 3)
parent/synapse-parent/pom.xml        repeated pins, process-aot/build-image config, Mockito agent, properties
.github/workflows/ci.yml             diff-cover excludes samples/ and archetypes/
.github/workflows/nightly.yml        image-reference (native-reference moves to P4)
(.github/workflows/release.yml       native-reference moves to P4)
core/synapse-core/…/core/paging/     CursorCodec, CursorScope, InvalidCursorException (moved)
core/synapse-core/…/core/autoconfigure/SynapsePagingProperties.java   max-offset, cursor.*
core/synapse-core/…/core/internal/paging/       AesGcmCursorCodec, CursorKeys, InputDigest, PageCursors (moved, protocol-neutral)
core/synapse-core/…/core/internal/conditional/  EntityTags (moved, own Tag type)
core/synapse-core/…/core/internal/aot/          BuildTimeSwitch, BuildTimeSwitchCheck
core/synapse-core/…/core/internal/environment/  ConfigSources, ModuleConfigFiles (native resource: URLs; moves to P4)
core/synapse-test/…/test/slice/      BoundOperationMocksCheck
api/synapse-api-grpc/…               etag mapping, .etag, the stale-write rule, page tokens, .sortable
api/synapse-api-rest-openapi/…/internal/aot/OpenApiAotCheck.java
tests/synapse-build-rules-tests/src/it/bom-guava-pin/ (extended), parent-effective-pom/, parent-build-config/
samples/README.md
samples/hello-rest/
samples/rest-books/                  REST only, on synapse-parent
samples/grpc-inventory/{pom.xml, contract-inventory/, service-inventory/}
samples/multi-store/{pom.xml, compose.yaml, data-orders/, data-ledger/, service-orders/}
samples/reference-bookstore/{pom.xml, compose.yaml, contract-bookstore/, data-books/, service-bookstore/}
archetypes/synapse-archetype-quickstart/, archetypes/synapse-archetype-service/
docs/modules/ROOT/pages/             parent.adoc, samples.adoc, opentelemetry-agent.adoc, archetypes.adoc (native.adoc moves to P4)
                                     (new); grpc.adoc, rest.adoc, core.adoc, data.adoc, testing.adoc, environments.adoc,
                                     openapi.adoc
```

---

### Task 1: The integration base (plans 2c, 3 and 4), built and verified

**Files:**
- Modify (merge results): everything the three branches touch. Expected hand resolutions, re-derived against the named heads (F8):
  - with plan 3: the files `trial-merge-report.md` §1 lists are a guide only, because the trial merge used older heads (2c `d8c67bc4`, plan 3 `391529c1`). Resolve each real conflict on its merits; `trial-merge-resolutions.diff` shows the intent;
  - with plan 4:
    - `core/synapse-core/.../error/SynapseErrors.java` and its test (a textual conflict only: both branches add the same `ALREADY_EXISTS`, next to 2c's `PRECONDITION_REQUIRED`; keep one of each);
    - `pom.xml` and `bom/synapse-bom/pom.xml` (modules, dependencyManagement);
    - `AGENTS.md` (layout rows `api/`, `data/`, `tests/`);
    - `docs/modules/ROOT/nav.adoc` (REST, OpenAPI, gRPC, Data, Testing);
    - `docs/modules/ROOT/pages/{core,environments,testing}.adoc`;
    - `specs/v2/{BUILD,DESIGN}.md` (keep every plan's *Done* note and rows, and this plan's delivery entry).

**Interfaces:**
- Produces: the base every later task uses (see *Execution base*). `SynapseErrors` has `ALREADY_EXISTS` (`CONFLICT`, gRPC ALREADY_EXISTS) and `PRECONDITION_REQUIRED`, and no `DUPLICATE` (D2 ruling). `samples/rest-books`' gRPC tests are red on the base until Task 2 (F8).

- [ ] **Step 1: Get the heads from the controller,** then `git fetch origin` so the clone's refs have them (F8: at the scan, the clone's `origin/*` lacked `d95154a4`, `22ceaedf` and `7d05b1ff`). Record the three SHAs in the task report. Check each with `git log -1 <sha>`.
- [ ] **Step 2: Merge 2c** (`git merge --no-ff <2c head>`). The trial merge had no conflicts; with `22ceaedf` there may be some in REST's binding and docs. Resolve and record them.
- [ ] **Step 3: Merge plan 3.** Re-derive each conflict against the named heads (don't replay `trial-merge-resolutions.diff`; use it to see the intent). Watch for the README Configuration paragraph that auto-merges in the wrong place.
- [ ] **Step 4: Post-merge fixes.** Apply the BUILD 6.1/6.3 docs fix from `trial-merge-postfix-patches/` only if the merged text still needs it (re-check; apply by hand if `git am` doesn't apply cleanly). **Skip patch `0002`** (the interim `VersionCondition` ↔ `Int64Value` converter, rest-books gRPC changes): it was cut against older heads, would likely conflict with `22ceaedf`'s `DeleteBookRequest`/`BookMapping`/`BooksApplicationIT`, and Task 2 deletes it anyway. Until Task 2, `samples/rest-books`' gRPC tests fail at startup on the unmapped `VersionCondition`; that's accepted in the merge commits only.
- [ ] **Step 5: Merge plan 4.** `ALREADY_EXISTS` is the same in both branches: resolve the textual conflict in `SynapseErrors` (keep `ALREADY_EXISTS` once and `PRECONDITION_REQUIRED`), and check with `git grep -n "DUPLICATE" -- '*.java' '*.adoc'` that nothing introduces `DUPLICATE`. No test or rename for D2.
- [ ] **Step 6: Look for semantic conflicts:**
  - plan 3's core `PersistenceTypes` versus plan 4's data capability types (the "no entities in the API" check must still see `Identifiable`/`Auditable` types: add a failing test in the module that owns the check if it doesn't);
  - plan 4's ArchUnit rules beside plan 3's;
  - Spring bean name clashes between the data, REST and gRPC auto-configurations.

  Record findings and fixes in the report.
- [ ] **Step 7: Verify the base:**
  - `./mvnw clean verify -pl '!samples/rest-books' -Dprotobuf.sanctioned-executable-path=…` on JDK 25 (background): BUILD SUCCESS, with Docker running;
  - `./mvnw verify -pl samples/rest-books -Dprotobuf.sanctioned-executable-path=…`: the only failures allowed are its gRPC tests (`BooksGrpcTest`, the gRPC case of `BooksApplicationIT`), failing on the unmapped `VersionCondition`. List them in the report. Any other failure stops the task. No task but Task 2 starts until Task 2 makes the whole reactor green;
  - the JDK 21 smoke leg: `./mvnw verify -pl '!samples/rest-books' -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke …`;
  - `CI=true ./mvnw spotless:check`;
  - `node docs/tools/property-tables.mjs`;
  - the Antora build (`cd docs && npm ci && npx antora antora-playbook.yml`).

  Report test counts per module for both runs.
- [ ] **Step 8: Commit** the merges as they are (each merge message ends with the Co-Authored-By trailer, and says that rest-books' gRPC tests are red until Task 2), then any Step 4 docs fix and Step 6 fixes as separate commits.

### Task 2: `VersionCondition` over gRPC: the `etag` field, and `.etag(...)` on responses

This task runs right after Task 1 and makes the whole reactor green again, including `samples/rest-books`' gRPC tests (F8).

**Files:**
- Move: REST `internal/conditional/EntityTags.java` (and its test) → `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/conditional/EntityTags.java` with `git mv`, then change it to the *Protocol-neutral core API* signatures (its own `Tag`, no `org.springframework.http.ETag`). Create that package's `package-info.java`. `ETagValues` stays in REST (it reads a `RouteDefinition`; F5).
- Modify:
  - REST `internal/conditional/{ETagValues,Preconditions}.java`, `internal/binding/RequestBinder.java`, `internal/route/ResponseWriter.java` (use core's `EntityTags`, adapting to Spring's `ETag` where REST needs it);
  - gRPC `GrpcMethodSpec.java` (`.etag`), `internal/mapping/{MessageMapping,RecordReader,RecordWriter}.java`, `internal/binding/BindingChecks.java`, `internal/service/UnaryOperationHandler.java` (the stale-write rule, D43);
  - Create gRPC `internal/conditional/{GrpcPreconditions,package-info}.java` (the translation, as REST's `Preconditions.translate`);
  - `samples/rest-books`: `src/main/proto/books/v1/books.proto` (`string etag` replaces any version field), `api/grpc/BooksGrpcConfiguration.java` and its comment, `BooksGrpcTest`, the gRPC case of `BooksApplicationIT` (Task 10 removes gRPC from `rest-books`, but the reactor is green in between);
  - `docs/.../grpc.adoc` (the `etag` field, the token, the stale rule, and the documented differences: a `VersionCondition` on a read, one per message), and BUILD §6.3 (message mapping: replace any `Int64Value` converter text the base has).
- Test: `core/.../internal/conditional/EntityTagsTest.java` (moved); gRPC `RecordReaderTest`, `RecordWriterTest`, `BindingChecksTest`, `internal/conditional/GrpcPreconditionsTest.java`, `internal/edge/GrpcConditionalIT.java` (new, `@Tag("smoke")`); proto fixture `src/test/proto/synapse/fixture/v1/conditional.proto`.

**Interfaces:**
- Consumes: `VersionCondition`, `SynapseErrors.PRECONDITION_REQUIRED`, `PRECONDITION_FAILED`, `STALE_VERSION` (2c); REST's tag grammar; `ComponentRef` (core `io.americanexpress.synapse.core.binding`).
- Produces: core `EntityTags` exactly as *Protocol-neutral core API* gives it, and:

```java
// gRPC public API (GrpcMethodSpec<I, O>), the same signature as REST's RouteSpec.etag
public <T> GrpcMethodSpec<I, O> etag(ComponentRef<T, ?> component);
// writes EntityTags.strong(conversionService.convert(value, String.class)) to the response's string field "etag";
// allowed only on a QueryOperation method, or on a CommandOperation method whose input has no VersionCondition (D46)

// gRPC internal
public final class GrpcPreconditions {
    /** STALE_VERSION from a request whose bound etag was non-empty → PRECONDITION_FAILED (cause kept); anything else unchanged. */
    public static SynapseException translate(boolean etagSent, SynapseException failure);
}
```

- [ ] **Step 0: Check whether 2c moved the parser** (F7): `git grep -n "class EntityTags" -- core`. At `22ceaedf` it hasn't. If the base has it in core, adapt it to the signatures above instead of moving it again, and record that.
- [ ] **Step 1: Write the failing tests** (D3–D8, D43, D46, *Defaults* #1–#2):

```java
// EntityTagsTest (moved REST cases, adapted to Tag/Parsed, plus): parsesOneValueAndLines(); starIsAny(); rejectsUnquoted(); strongQuotes(); strongRejectsANonEtagcChar()
// RecordReaderTest
@Test void etagFieldBindsVersion()            // etag "\"5\"" → VersionCondition.of(5L) (V = Long via ConversionService)
@Test void starBindsAnyVersion()              // "*" → VersionCondition.any()
@Test void emptyEtagIsAbsent()                // "" → null for @Nullable; for non-null → SynapseException PRECONDITION_REQUIRED
@Test void weakEtagIsPreconditionFailed()     // "W/\"5\"" → PRECONDITION_FAILED (operation not invoked, asserted in the IT)
@Test void twoTagsAreInvalidInput()           // "\"1\", \"2\"" → VALIDATION_FAILED, violation on "etag"
// RecordWriterTest
@Test void etagWritesTheStrongTag()           // .etag(Book::version), version 7 → response etag "\"7\""; the version field keeps 7
// BindingChecksTest
@Test void versionConditionNeedsAStringEtagField()   // message without etag / etag as int64 → startup failure naming method and field
@Test void twoVersionConditionsFail()                // one per message (F26)
@Test void etagWithoutResponseFieldFails()
@Test void etagOnAConditionalWriteFails()            // .etag on a CommandOperation whose input has a VersionCondition → startup failure naming the method (F3)
@Test void etagOnAQueryAndOnACreateIsAllowed()
@Test void versionConditionOnAReadFails()            // QueryOperation input with VersionCondition → startup failure; a documented gRPC difference (F12)
// GrpcPreconditionsTest
@Test void staleVersionWithAnEtagIsPreconditionFailed()   // cause kept
@Test void staleVersionWithoutAnEtagStays()
@Test void otherFailuresAreUnchanged()
// GrpcConditionalIT (in-process server, real pipeline, fixture operations)
@Test void missingRequiredEtagIsFailedPreconditionWithReason()   // FAILED_PRECONDITION, ErrorInfo.reason PRECONDITION_REQUIRED, operation not called
@Test void overrideTurnsItIntoInvalidArgument()      // synapse.api.grpc.errors.status.[PRECONDITION_REQUIRED]=INVALID_ARGUMENT (D3)
@Test void staleEtagIsFailedPreconditionPreconditionFailed()   // etag "\"1\"" on version 2 → FAILED_PRECONDITION, reason PRECONDITION_FAILED, as REST's 412 (F1)
@Test void starOnAConcurrentlyChangedRowIsFailedPrecondition() // "*" sent and the race lost → PRECONDITION_FAILED, as REST
@Test void unconditionalLostRaceIsAbortedStaleVersion()        // optional condition, etag empty, race lost → ABORTED, reason STALE_VERSION (REST 409)
@Test void restAndGrpcAcceptTheSameToken()           // the token REST's ResponseWriter writes for version 3 parses to Version(3) over gRPC
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-grpc -am verify -Dit.test=GrpcConditionalIT …`. Expected: compile errors, then assertion failures.
- [ ] **Step 3: Implement.** Move the tag grammar to core as *Protocol-neutral core API* specifies, and keep the REST tests green (REST's behaviour doesn't change). In the gRPC mapper, a `VersionCondition<V>` component reads the request field `etag` through `EntityTags.parse` and converts the opaque value to `V` with the mapper's `ConversionService`. `.etag(...)` is stored on the method spec, checked by `BindingChecks` (D46), and applied by `RecordWriter`. `UnaryOperationHandler` passes whether a bound `etag` was non-empty to `GrpcPreconditions.translate` when the operation throws.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-grpc,samples/rest-books -am verify`, and the full `./mvnw clean verify -Dprotobuf.sanctioned-executable-path=…` (background): the whole reactor is green again.
- [ ] **Step 5: Commit**: `feat(grpc)!: carry VersionCondition in the etag field with REST's entity-tag token`.

### Task 3: `CursorCodec` moves to core, shared by every protocol

"Move unchanged" isn't possible here: the classes import spring-web and REST internals (F5). Task 3 moves them with `git mv` and rewrites them to *Protocol-neutral core API*, which is the contract Task 4 builds on.

**Files:**
- Move: `api/synapse-api-rest/.../api/rest/paging/{CursorCodec,CursorScope,InvalidCursorException}.java` → `core/synapse-core/.../core/paging/` (unchanged but for the package). Also `.../internal/paging/{AesGcmCursorCodec,CursorKeys,InputDigest,PageCursors}.java` → `core/.../core/internal/paging/` (create its `package-info.java`), changed to the specified signatures: `CursorKeys.from(SynapsePagingProperties.Cursor, …)`; `InputDigest.of(input, extra)` without `RouteDefinition`, `ConditionalRules`, `InputSource` or `SemanticApiVersionParser`; `PageCursors` without `RouteDefinition` or `PagingSettings`.
- Stay in REST: `PagingSettings` (its `maxOffset` now from core), `PageQueryReader`, `SortKeys`, `PageLinks`, `PageBody`. The `@version` parsing (`SemanticApiVersionParser`) moves into REST's caller of `PageCursors`.
- Modify:
  - `core/.../autoconfigure/SynapseCoreAutoConfiguration.java` (beans `synapseCursorCodec`, `synapseInputDigest`, `synapsePageCursors`, as specified), `SynapsePagingProperties` (`max-offset` and the `cursor` record);
  - REST `SynapseRestProperties` (drop `paging.cursor` and `paging.max-offset`), `SynapseRestAutoConfiguration` (drop the three beans), `PagingSettings`, `RouteCompiler` (`reportGeneratedKey()`), `RequestBinder` and `ResponseWriter` (call `unwrap`/`wrap` with the route's operation name, `@version` extra and cursor parameter name);
  - `docs/.../{rest,core}.adoc` (the keys `synapse.paging.max-offset` and `synapse.paging.cursor.*`; the digest rule), `samples/rest-books/src/main/resources/application*.yaml` and README if they set a moved key.
- Test: core `CursorCodecTest`/`AesGcmCursorCodecTest`/`InputDigestTest`/`PageCursorsTest`/`CursorKeysTest` (moved and adapted), `SynapsePagingPropertiesTest` (`max-offset` default 1000 and must not be negative; `cursor.max-age` positive), `SynapseCoreAutoConfigurationTest` (codec present, backs off, binds `synapse.paging.cursor.*` and `synapse.paging.max-offset`, generated-key WARN), core `CoreAotProcessingTest`/runtime-hints test if the codec registers hints; REST tests updated to the new keys.

**Interfaces:**
- Produces: exactly the paging part of *Protocol-neutral core API*: `io.americanexpress.synapse.core.paging.{CursorCodec,CursorScope,InvalidCursorException}`; `SynapsePagingProperties(defaultSize, maxSize, maxOffset, Cursor)`; internal `CursorKeys`, `AesGcmCursorCodec`, `InputDigest.of(@Nullable Object input, Map<String, ?> extra)`, `PageCursors.{encode,decode,unwrap,wrap,query,reportGeneratedKey}`. Keys `synapse.paging.max-offset`, `synapse.paging.cursor.{key,previous-keys,max-age}`.

- [ ] **Step 1: Write the failing tests:**
  - `SynapseCoreAutoConfigurationTest.cursorCodecBindsCoreKeys()` (`synapse.paging.cursor.key` set → no WARN; unset → one WARN after `reportGeneratedKey()`, even when called twice, in every environment, *Defaults* #6), `maxOffsetBindsFromCore()`;
  - `InputDigestTest`: `extraEntriesChangeTheDigest()`, `extraKeyWithoutAtFails()`, `versionConditionComponentsAreLeftOutByType()`, `pageQueryIsLeftOut()`, `noInputIsTheEmptyObject()`, and the moved canonical-form cases;
  - `PageCursorsTest`: `rejectedTokenNamesTheCallersField()` (`field` `page_token` → `Violation("page_token", "is not valid for this request")`), `wrapUsesTheInputsSort()`, `nonUrlSafeTokenIsAnIllegalState()`;
  - REST `RestPagingIT` with `synapse.paging.cursor.key` and `synapse.paging.max-offset` instead of the old keys, and a versioned route whose cursor is still rejected by another version.
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Move the classes** with `git mv` so the history follows, change them to the specified signatures, fix the imports, then register the beans in core. Check with `git grep -n "org.springframework.web\|org.springframework.http\|synapse.api" -- core/synapse-core/src/main` that core imports neither.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-rest-openapi,samples/rest-books -am verify`.
- [ ] **Step 5: Commit**: `refactor(core)!: move the cursor codec to core under synapse.paging.cursor`.

### Task 4: gRPC page tokens (AIP-158)

**Files:**
- Modify:
  - `api/synapse-api-grpc/.../GrpcMethodSpec.java` (`.sortable`);
  - `internal/mapping/{MessageMapping,RecordReader,RecordWriter}.java` (the `PageQuery` reader and `PageResult` writer);
  - `internal/binding/BindingChecks.java`: remove the "fails startup until page tokens (plan 3b)" rule and the Javadoc sentence about it (plan 3 `BindingChecks.java:47` and `:148-152`), and add the field checks;
  - `internal/service/GrpcBindingsCompiler.java` (`reportGeneratedKey()` when a method returns `PageResult`);
  - `grpc.adoc` (page tokens; the sort-key wire names, which ignore `@JsonProperty`; `skip` capped by `synapse.paging.max-offset`); BUILD §6.3 bindings bullet (replace the plan 3b sentence).
- Test: `RecordReaderTest`, `RecordWriterTest`, `BindingChecksTest`, `internal/paging/GrpcPagingIT.java` (new); fixture `src/test/proto/synapse/fixture/v1/paging.proto`.

**Interfaces:**
- Consumes (Task 3, *Protocol-neutral core API*): `PageCursors.unwrap(operation, input, Map.of(), "page_token")`, `PageCursors.wrap(operation, input, Map.of(), rawCursor)`, `PageCursors.query(input)`, `PageCursors.reportGeneratedKey()`; `SynapsePagingProperties.defaultSize()/maxSize()/maxOffset()`. The `PageCursors` bean comes from core; the gRPC auto-configuration defines none.
- Produces: `public <E extends Enum<E>> GrpcMethodSpec<I, O> sortable(Class<E> keys)` (the same signature as REST's). A key binds to `SortOrder.property` = the constant's name, so the cursor scope matches REST's. The field rules are *Defaults* #3.

- [ ] **Step 1: Write the failing tests:**

```java
// RecordReaderTest
@Test void pageSizeAndTokenBindPageQuery()    // page_size 5, page_token "" → PageQuery.first(5); token → ofCursor(decrypted…)
@Test void zeroPageSizeIsTheDefault()         // page_size 0 → synapse.paging.default-size (20)
@Test void skipBindsAnOffset()                // skip 40 → ofOffset(40, …); skip 1001 with synapse.paging.max-offset 1000 → VALIDATION_FAILED, violation "skip"
@Test void orderByUsesAip132()                // "title, published_year desc" → [TITLE asc, PUBLISHED_YEAR desc] (properties are constant names)
@Test void unknownOrderByKeyIsInvalid()       // same code as REST's unknown sort key, violation "order_by"
@Test void tokenViolationNamesPageToken()     // tampered page_token → violation "page_token"
// RecordWriterTest
@Test void pageResultWritesItemsTokenAndTotal()   // items → repeated field; nextCursor encrypted → next_page_token; total 42 → total_size 42; unknown total → unset
// BindingChecksTest
@Test void pagedMethodNeedsAip158Fields()     // missing page_size / page_token / next_page_token, or wrong types → startup failure naming each
@Test void sortableNeedsOrderBy()
// GrpcPagingIT
@Test void pagesThroughEveryItem()            // 45 items, page_size 20 → 20, 20, 5, then empty next_page_token
@Test void tamperedTokenIsInvalidArgument()
@Test void cursorReplayedWithAnotherSortIsInvalid()   // Review Focus 2
@Test void sizeOverTheCapIsInvalidArgument()          // page_size 101 → INVALID_ARGUMENT, same reason as REST
@Test void generatedKeyWarnsOnceWithBothPlugins()     // REST and gRPC both bound, no cursor key → one WARN (Defaults #6)
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** on core's `PageCursors` with `Map.of()` as the extra entries and `page_token` as the field.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-grpc,api/synapse-test-grpc -am verify`.
- [ ] **Step 5: Commit**: `feat(grpc): bind PageQuery and PageResult to AIP-158 page tokens`.

### Task 5: One bound-operation mocks check in `synapse-test`

**Files:**
- Create: `core/synapse-test/src/main/java/io/americanexpress/synapse/test/slice/{BoundOperationMocksCheck,package-info}.java`.
- Modify: `api/synapse-test-rest/.../SynapseRestTestAutoConfiguration.java`, `api/synapse-test-grpc/.../SynapseGrpcTestAutoConfiguration.java`; `testing.adoc` (the class name for replacing it).
- Delete: `api/synapse-test-rest/.../BoundOperationMocksCheck.java`, `api/synapse-test-grpc/.../BoundGrpcOperationMocksCheck.java` and their tests (moved to `core/synapse-test/src/test/java/.../test/slice/BoundOperationMocksCheckTest.java`).

**Interfaces:**
- Produces: `public final class BoundOperationMocksCheck implements BeanFactoryPostProcessor, Ordered { public BoundOperationMocksCheck(Class<?> bindingsType, String slice); }`. The message is `<slice>: declare a @MockitoBean for each operation the bindings use; missing: <Op> (bound by <Class>.<method>)`. Bean names per *Defaults* #7.

- [ ] **Step 1: Write the failing tests:** `BoundOperationMocksCheckTest` (the union of both old tests: missing operation named with its method, static `@Bean` method, instance-registered bindings skipped, runs last). It runs against two fixture bindings types defined in `core/synapse-test`'s own test sources, never `RestBindings` or `GrpcBindings`: both plugins already depend on `synapse-test` in test scope, so a dependency back would be a reactor cycle (F24). The slices keep their own registration tests: `restSliceRegistersItsCheck()`, `grpcSliceRegistersItsCheck()` (`ApplicationContextRunner`: bean present by name; a user bean with that name replaces it).
- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement**, then delete the copies.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-test,api/synapse-test-rest,api/synapse-test-grpc -am verify`.
- [ ] **Step 5: Commit**: `refactor(test): share the bound-operation mocks check between the REST and gRPC slices`.

### Task 6: `synapse-parent`: runtime pins, the sync check and the effective-POM test

Plan 3's `7d05b1ff` already manages the guava pin in `synapse-bom` and checks it against the root in the invoker test `bom-guava-pin`. This task keeps both and extends that check; it adds no second mechanism (F14).

**Files:**
- Modify: `bom/synapse-bom/pom.xml` (add `error_prone_annotations` `2.50.0`, moved from the root, with its convergence comment; provisional, D20); `pom.xml` (the root keeps its entries: comment "repeated from synapse-bom"); `parent/synapse-parent/pom.xml` (repeat every third-party BOM entry in its own `dependencyManagement`, before the BOM import).
- Modify: `tests/synapse-build-rules-tests/src/it/bom-guava-pin/{verify.groovy,pom.xml}` (extend the check, below; update the carrier pom's comment); `tests/synapse-build-rules-tests/pom.xml` (test-scope dependencies `synapse-parent` and `synapse-bom` with `<type>pom</type>`, so `invoker:install` puts them in `target/it-repo`, F15).
- Create: `tests/synapse-build-rules-tests/src/it/parent-effective-pom/{pom.xml,invoker.properties,verify.groovy}`.

**Interfaces:**
- Produces: `bom-guava-pin`'s `verify.groovy` checks every third-party `groupId:artifactId` (not `io.americanexpress.synapse`, not `<scope>import</scope>`) that the BOM's `dependencyManagement` gives a version: the root and the parent must manage the same version, with `${…}` properties resolved within each file. It fails with one line per mismatch: `<g:a>: bom <v1>, root <v2>, parent <v3>` (`<missing>` where a file lacks it). It runs in every build, as today, so CI needs no new step.

- [ ] **Step 1: Write the failing tests:**
  - Extend `bom-guava-pin/verify.groovy` as above. With the parent not yet repeating the pins, the invoker run fails, naming each missing pin. Prove each rule once with a deliberate local edit (a root version that differs; a property-valued version), reverted before committing; record the outputs in the report.
  - Invoker `parent-effective-pom`: a project inheriting `synapse-parent` runs `help:effective-pom -Doutput=…`. `verify` asserts `guava` `33.7.2-jre` in its dependencyManagement, and that it wins when the project also declares a dependency that brings an older guava (the dependency tree shows 33.7.2-jre).
- [ ] **Step 2: Run to verify failure:** `./mvnw -pl tests/synapse-build-rules-tests -am verify -Dinvoker.test=bom-guava-pin,parent-effective-pom`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** the same command, then `./mvnw -pl tests/synapse-build-rules-tests -am verify` (every invoker project).
- [ ] **Step 5: Commit**: `build: repeat the synapse-bom pins in synapse-parent and check that root, BOM and parent agree` (the body says the guava pin is GHSA-xxph-c9ww-hj94 / CVE-2026-102554, already in the BOM since plan 3).

### Task 7: `synapse-parent`: build environment, images and the Mockito agent

**Files:**
- Modify: `parent/synapse-parent/pom.xml`:
  - properties `synapse.build.environment=build` and `synapse.image.jvm-version=25`;
  - `pluginManagement` for `spring-boot-maven-plugin`: a `process-aot` execution with `<id>process-aot</id>` (the id Boot's starter-parent `native` profile uses, so AOT runs once; F30) and `<profiles><profile>${synapse.build.environment}</profile></profiles>`, and `build-image` `<image><env>` with `BP_JVM_VERSION`, `BP_JVM_AOTCACHE_ENABLED=true` and `TRAINING_RUN_JAVA_TOOL_OPTIONS=-Dspring.profiles.active=${synapse.build.environment}`. Never `BP_SPRING_AOT_ENABLED` (F25);
  - `<plugins>`: `maven-dependency-plugin:properties` plus the `antrun` Mockito-agent condition, and surefire/failsafe `argLine` `@{argLine} @{synapse.mockitoAgentArg}` (*Defaults* #10).
- Modify: `tests/synapse-build-rules-tests/pom.xml`: a test-scope dependency on `synapse-core` (and whatever else the probe app needs from the reactor), so `invoker:install` puts it in `target/it-repo` (F15; Task 6 added the parent and the BOM).
- Create: `tests/synapse-build-rules-tests/src/it/parent-build-config/` (an invoker project inheriting the parent, with Mockito in test scope and one test; `verify` checks the effective POM values, that the `process-aot` execution id is `process-aot`, that no `BP_SPRING_AOT_ENABLED` is set, and that the test JVM got `-javaagent:…mockito-core…jar` from surefire's report). Also `parent-build-config-no-mockito/` (no Mockito → no agent and no failure).
- Create: `docs/modules/ROOT/pages/parent.adoc` (pins, logging setup per D18, build environment, images, Mockito agent, keep-alive per D38, and why the parent never sets `BP_SPRING_AOT_ENABLED`: Paketo fails the build for an AOT-processed jar when it and `TRAINING_RUN_JAVA_TOOL_OPTIONS` are both set, per the paketo-buildpacks/spring-boot README).

**Interfaces:**
- Produces: the parent properties above; `${synapse.mockitoAgentArg}` (an empty string when there's no Mockito).

- [ ] **Step 1: Write the failing invoker projects** above. Then the probe test that decides D16: in `parent-build-config`, run `spring-boot:process-aot` on a minimal app whose `application.yaml` maps `spring.profiles.active: ${ENV}`, with an `application-build.yaml`, and `ENV` unset. Expected: AOT processing succeeds, with environment `build`.
- [ ] **Step 2: Run to verify failure:** `./mvnw -pl tests/synapse-build-rules-tests -am verify -Dinvoker.test=parent-build-config*`.
- [ ] **Step 3: Implement.** If Step 1 shows that `profiles` reaches Spring as an additional profile and clashes with `${ENV}` (two environments, or unresolved), stop and report: D16 then switches to `<jvmArguments>-Dspring.profiles.active=…</jvmArguments>`.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit**: `feat(parent): pass the build environment to AOT and the image training run, and attach the Mockito agent`.

### Task 8: API docs under AOT, and one failure for every build-time switch

A run that differs from the build in two switches (`local` turns on both gRPC reflection and the API docs) must name both in one failure, so the result doesn't depend on which check runs first (F4).

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/aot/{BuildTimeSwitch,BuildTimeSwitchCheck,package-info}.java` (or add to the package if it exists).
- Create: `api/synapse-api-rest-openapi/src/main/java/io/americanexpress/synapse/api/rest/openapi/internal/aot/{OpenApiAotCheck,package-info}.java`.
- Modify: `SynapseCoreAutoConfiguration.java` (bean `synapseBuildTimeSwitchCheck`, `@ConditionalOnMissingBean(name = …)`); `SynapseRestOpenApiAutoConfiguration.java` (bean `synapseOpenApiAotCheck`, `@ConditionalOnMissingBean(name = …)`); plan 3's `api/synapse-api-grpc/.../internal/aot/ReflectionAotCheck.java` and its bean `synapseGrpcReflectionAotCheck` (now a `BuildTimeSwitch`, its conditions and tests kept); `openapi.adoc`, `grpc.adoc` and `environments.adoc` (the AOT rule, one failure for all switches).
- Test: `BuildTimeSwitchCheckTest`, `OpenApiAotCheckTest`, `ReflectionAotCheckTest` (adapted), `SynapseRestOpenApiAutoConfigurationTest` and `SynapseCoreAutoConfigurationTest` (beans present, back off), `RestOpenApiAotIT` (extend: AOT-processed context with docs on; started with docs off → failure).

Step 1 checks whether the merged 2c already has such a check (`git grep -n "api-docs" -- '*Aot*'`; the scan found none at `d95154a4`). If it does, reduce this task to the missing tests and record that.

**Interfaces:**
- Produces: *Defaults* #8, and in core internal:

```java
public interface BuildTimeSwitch {
    /** The mismatch with the build, or null when the value now agrees with it or the check doesn't apply. */
    @Nullable Mismatch mismatch();
    record Mismatch(String property, String now, String build) {}
}
public final class BuildTimeSwitchCheck implements SmartInitializingSingleton {
    public BuildTimeSwitchCheck(ObjectProvider<BuildTimeSwitch> switches, BooleanSupplier aotMode);
    // in AOT mode: collects every non-null mismatch; none → nothing; else one SynapseConfigurationException, Defaults #8 text, sorted by property
}
```

- [ ] **Step 1: Check, then write the failing tests:**

```java
// OpenApiAotCheckTest
@Test void mismatchIsReported()            // property false; SpringDocWebMvcConfiguration bean present (was OpenApiWebMvcResource) → Mismatch("springdoc.api-docs.enabled", "false", "true")
@Test void matchIsNull()                   // both on / both off
// BuildTimeSwitchCheckTest
@Test void mismatchFailsInAotMode()        // AotDetector on; one switch differs → SynapseConfigurationException, Defaults #8 text
@Test void everyMismatchIsInOneFailure()   // two switches differ → one exception naming both keys, in property order
@Test void jvmModeNeverChecks()            // AotDetector off → no failure whatever the values
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,api/synapse-api-rest-openapi,api/synapse-api-grpc -am verify`.
- [ ] **Step 5: Commit**: `feat(openapi): fail startup in AOT mode when springdoc.api-docs.enabled differs from the build, with every build-time switch in one failure`.

### Task 9: Samples infrastructure and `samples/hello-rest` (the quick start)

**Files:**
- Modify:
  - `pom.xml`: module `samples/hello-rest`; root-only executions: Spotless over the samples' Java sources, and the license check over the samples and the archetype templates (*Defaults* #11, F18);
  - `.github/workflows/ci.yml`: diff-cover `roots` find adds `-not -path './samples/*' -not -path './archetypes/*'`; the fork-PR jar upload excludes `samples/**` instead of `synapse-sample-*.jar`.
- Create `samples/hello-rest/`:
  - `pom.xml`: parent `synapse-parent`. Dependencies: `synapse-api-rest`, `synapse-logging-log4j2`, `spring-boot-starter-opentelemetry` and `spring-boot-starter-actuator`, each excluding `spring-boot-starter-logging`. Test dependencies: `synapse-test-rest` and `spring-boot-starter-test` (with the exclusion). Properties: `spring-boot.run.profiles=local` and the skip flags.
  - `src/main/java/io/americanexpress/synapse/samples/hello/{HelloApplication,package-info}.java`;
  - `operation/{GetGreetingOperation,package-info}.java` (`QueryOperation<GetGreetingRequest, Greeting>`);
  - `model/{GetGreetingRequest,Greeting,package-info}.java`: `GetGreetingRequest(@NotBlank @Size(max = 40) String name)` and `Greeting(String message)`, where the message is `"Hello, " + name + "!"`;
  - `api/rest/{HelloRestConfiguration,package-info}.java`: `GET /greetings/{name}`;
  - `src/main/resources/application.yaml`: `spring.profiles.active: ${ENV}`, `spring.application.name: hello-rest`, `spring.threads.virtual.enabled: true`;
  - `src/main/resources/application-local.yaml`: `synapse.errors.include-detail: true`, `springdoc` not present;
  - `src/test/resources/application-test.yaml`;
  - `README.md`.
- Test:
  - `src/test/java/.../hello/ArchitectureTest.java`;
  - `operation/GetGreetingOperationTest.java` (`SynapseOperations.invoke`);
  - `api/rest/HelloRestTest.java` (`@SynapseRestTest`);
  - `HelloApplicationIT.java` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`).
- Create: `samples/README.md` (index: one row for now; each later sample task adds its row).

**Interfaces:**
- Produces: the sample layout the quickstart archetype copies (Task 18), and the samples index.

- [ ] **Step 1: Write the failing tests:**

```java
// GetGreetingOperationTest
@Test void greetsByName()                 // invoke(new GetGreetingOperation(), new GetGreetingRequest("Ada")) → message "Hello, Ada!"
@Test void blankNameIsInvalidInput()      // assertThatSynapseException(...).hasCategory(INVALID_INPUT).hasViolation("name")
// HelloRestTest (@SynapseRestTest, @MockitoBean GetGreetingOperation)
@Test void routeBindsThePathVariable()    // GET /greetings/Ada → operation got name "Ada"; 200 JSON {"message": …}
// HelloApplicationIT
@Test void servesTheGreetingWithACorrelationId()   // 200, X-Correlation-Id present
@Test void tooLongNameIsAProblem()                 // 41 chars → 400 application/problem+json, violations[0].field "name", no detail
@Test void unknownRouteIsRouteNotFound()           // GET /nope → 404 code ROUTE_NOT_FOUND
@Test void healthIsUp()                            // /actuator/health → UP
// ArchitectureTest: SynapseArchitecture.forApplication(HelloApplication.class).check()
```

  Also a format probe for the root executions: `CI=true ./mvnw -N spotless:check` fails on a deliberately unformatted file in `samples/hello-rest` (revert the probe before committing).
- [ ] **Step 2: Run to verify failure:** `./mvnw -pl samples/hello-rest -am verify`.
- [ ] **Step 3: Implement.** Then write the README: what it shows; how to run it (F19: `./mvnw -pl samples/hello-rest -am install -DskipTests` once, which installs the Synapse modules it needs, then `./mvnw -pl samples/hello-rest spring-boot:run`; or `./mvnw -pl samples/hello-rest -am package -DskipTests` and `ENV=local java -jar samples/hello-rest/target/hello-rest-*.jar`); `curl` calls (200, 400, 404); the tests and how to run them; and the module's layout. Paste real output from a run.
- [ ] **Step 4: Run to verify pass**, then `CI=true ./mvnw -N spotless:check`.
- [ ] **Step 5: Commit**: `feat(samples): add hello-rest, the quick start, and the samples index`.

### Task 10: `samples/rest-books`: the REST tour, in memory, on `synapse-parent`

**Files:**
- Modify:
  - `samples/rest-books/pom.xml`: parent `synapse-parent`; groupId `io.americanexpress.synapse.samples`, artifactId `rest-books`; drop `synapse-api-grpc`, `synapse-test-grpc`, the protobuf plugin, the JaCoCo excludes and `synapse.errorprone.excludedPaths`; keep `synapse-api-rest-openapi`;
  - `application.yaml` and `application-local.yaml` (drop the gRPC keys);
  - `README.md` (the gRPC section points to `grpc-inventory` and the reference app; the jar paths change with the artifactId: `synapse-sample-rest-books-2.0.0-SNAPSHOT.jar` → `rest-books-2.0.0-SNAPSHOT.jar` everywhere it appears; build with `-am package -DskipTests` and run `ENV=local java -jar …`, F19);
  - `BooksApplicationIT.java` (drop the gRPC test and the gRPC port);
  - `samples/README.md` (row).
- Leave `docs/.../grpc.adoc` alone: its examples move to `grpc-inventory` in Task 11, once that sample exists (F33).
- Delete: `src/main/proto/`, `api/grpc/*`, `src/test/java/.../api/grpc/BooksGrpcTest.java`.

**Interfaces:**
- Consumes: Task 3's `synapse.paging.cursor.*` keys (the README and `application-local.yaml` say how to set a key).

- [ ] **Step 1: Make the change and run the existing tests** (`./mvnw -pl samples/rest-books -am verify`). Every REST test stays green. The gRPC tests move in Task 11, so record which gRPC assertions Task 11 must carry over: the list is in the task report.
- [ ] **Step 2: Re-run every README command** against `ENV=local` and paste the real output.
- [ ] **Step 3: Commit**: `refactor(samples)!: make rest-books the REST-only tour on synapse-parent`.

### Task 11: `samples/grpc-inventory`: gRPC first, the proto in a contract module

**Files:**
- Create `samples/grpc-inventory/`:
  - `pom.xml` (aggregator, parent `synapse-parent`, modules `contract-inventory` and `service-inventory`).
  - `contract-inventory/pom.xml`: protobuf plugin; `protobuf-java` and `grpc-protobuf`/`grpc-stub` as the generated code needs; `jakarta.validation-api`; `synapse-core` (for `ErrorDetails`, `VersionCondition`).
  - `contract-inventory/src/main/proto/inventory/v1/inventory.proto`: service `InventoryService` with `GetItem`, `CreateItem`, `ListItems` (paged), `AdjustStock` and `DeleteItem`. The `Item` message has:
    - `string id`, and `string sku_code` (renamed to the `sku` component with `.map`);
    - `Status status` (an enum), `repeated string tags`, `map<string,string> attributes`, `Location location` (nested);
    - `google.protobuf.Timestamp restocked_at`, `google.protobuf.Duration lead_time`, `google.protobuf.Int32Value reorder_level`;
    - `oneof price { Money fixed = …; Discount discounted = …; }` (needs `.map`);
    - `int64 version` (the record's `version` component: every output component needs a field, F11) and `string etag`, which `.etag(Item::version)` fills on `GetItem` and `CreateItem` only (D46).
  - `contract-inventory/src/main/java/io/americanexpress/synapse/samples/inventory/contract/{Item,ItemStatus,Location,Price,…,InventoryErrors,package-info}.java` (`InventoryErrors`: `ITEM_NOT_FOUND`, `OUT_OF_STOCK` (`CONFLICT`)).
  - `service-inventory/pom.xml`: `synapse-api-grpc`, logging, OpenTelemetry starter, actuator; test: `synapse-test-grpc`.
  - `service-inventory/src/main/java/.../inventory/{InventoryApplication,operation/*,data/InMemoryItems,api/grpc/InventoryGrpcConfiguration}.java`.
  - Resources: `application.yaml` (`spring.application.name: service-inventory`, which is the `ErrorInfo` domain (F29); `synapse.api.grpc.context.metadata.channel: x-channel`; the per-operation override written with the bracketed key quoted, `synapse.api.grpc.operations.adjust-stock.errors.status: { "[OUT_OF_STOCK]": FAILED_PRECONDITION }`), `application-local.yaml` (`spring.grpc.server.reflection.enabled: true`, `synapse.errors.include-detail: true`), `application-test.yaml`.
  - `README.md` (grpcurl through `docker run --rm fullstorydev/grpcurl:v1.9.3@sha256:<digest> -plaintext host.docker.internal:9090 …`, without `--network host`; on Linux add `--add-host=host.docker.internal:host-gateway`; read the digest first with `docker buildx imagetools inspect fullstorydev/grpcurl:v1.9.3` and paste it (F20); Git Bash needs the Docker Desktop bin directory on PATH (*Global Constraints → Windows*); list, describe, calls, errors with `ErrorInfo`), and the `samples/README.md` row.
- Modify: `docs/.../grpc.adoc` (examples point to `grpc-inventory`; moved here from Task 10, F33).
- Test (`service-inventory`):
  - `ArchitectureTest` (`forApplication(InventoryApplication.class)`: generated classes are exempt; the contract package is protocol-free);
  - `operation/InventoryOperationsTest` (`@SynapseOperationTest`, `MutableClock`);
  - `api/grpc/InventoryGrpcTest` (`@SynapseGrpcTest`, carrying Task 10's list);
  - `InventoryApplicationIT` (real Netty on port 0).

**Interfaces:**
- Consumes: Tasks 2 and 4 (`etag`, `.etag(...)`, page tokens, `.sortable`).

- [ ] **Step 1: Write the failing tests:**

```java
// InventoryGrpcTest (@SynapseGrpcTest; @MockitoBean per operation; @ImportGrpcClients(types = InventoryServiceBlockingStub.class))
@Test void mapsEveryFieldKind()            // Timestamp↔Instant, Duration, Int32Value↔@Nullable Integer, enum by name, repeated, map, nested
@Test void oneofNeedsItsMapping()          // fixed and discounted prices round-trip through .map(...)
@Test void renamedFieldUsesMap()           // sku_code ↔ Item.sku
@Test void unknownItemIsNotFoundWithReason()      // NOT_FOUND, ErrorInfo.reason ITEM_NOT_FOUND, domain service-inventory
@Test void invalidRequestHasBadRequest()          // blank sku → INVALID_ARGUMENT, BadRequest field "sku_code"
@Test void perOperationOverride()                 // OUT_OF_STOCK on adjust-stock → FAILED_PRECONDITION; on another op → ABORTED
@Test void channelComesFromMetadata()             // x-channel → RequestContext attribute
@Test void listPagesWithTokens()                  // page_size 2 over 5 items
@Test void missingSliceMockIsNamed()              // a nested config without one @MockitoBean → the Task 5 message
// InventoryApplicationIT
@Test void correlationIdIsEchoed()                // x-correlation-id sent → same in headers
@Test void expiredDeadlineIsDeadlineExceeded()    // withDeadlineAfter(1, NANOSECONDS) → DEADLINE_EXCEEDED, operation not run
@Test void reflectionIsOffByDefault()             // reflection stub → UNIMPLEMENTED
@Nested class Local { @Test void reflectionListsTheService() }   // property spring.grpc.server.reflection.enabled=true (what application-local sets)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -pl samples/grpc-inventory/service-inventory -am verify -Dprotobuf.sanctioned-executable-path=…`.
- [ ] **Step 3: Implement**, then run the README commands against `ENV=local` and paste the output.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit**: `feat(samples): add grpc-inventory, a gRPC-first service with its proto in a contract module`.

### Task 12: `samples/multi-store`: two named stores

Starts only when plan 4's Task 14 (`synapse-data-postgres`) is final and merged into the base (F16).

**Files:**
- Create `samples/multi-store/`:
  - `pom.xml`, plus `compose.yaml` with two `postgres:18.6-alpine` services: `orders-db` on 5433 and `ledger-db` on 5434.
  - `data-orders/`:
    - `pom.xml`: `synapse-data-jpa` and `synapse-data-postgres` (the PostgreSQL driver and `flyway-database-postgresql`, F16); test: `synapse-test-data`, `spring-boot-starter-test`;
    - `src/main/java/.../multistore/data/orders/{OrdersStore,Order,OrderRepository,package-info}.java` (`@SynapseDataStore(value = "orders", access = DataAccess.JPA)`; `Order extends AuditedEntity`);
    - `src/main/resources/db/migration/orders/V1__orders.sql`;
    - `META-INF/synapse/config.yaml` (`synapse.data.stores.orders.hikari.maximum-pool-size: 5`, `health.readiness: true`);
    - `META-INF/synapse/config-local.yaml` (URL and credentials for compose);
    - `src/test/resources/application-test.yaml`.
  - `data-ledger/`: `pom.xml` (`synapse-data-jdbc` and `synapse-data-postgres`); `{LedgerStore,LedgerEntry,LedgerRepository}` (`access = DataAccess.JDBC`; `LedgerEntry implements Auditable` with custom columns `entry_created`, `entry_created_by`, …), `db/migration/ledger/V1__ledger.sql`, the same two module files without readiness, and `src/test/resources/application-test.yaml`.
  - `service-orders/`:
    - operations `PlaceOrderOperation` (`@Transactional("orders")` writes the order; then a ledger entry through a `@Transactional("ledger")` call, with a comment that cross-store writes are not atomic), `GetOrderOperation`, `CancelOrderOperation` (`DeleteOrderRequest(UUID id, VersionCondition<Long> version)`, required);
    - `api/rest/OrdersRestConfiguration` (`POST /orders` with `.created(...)` and `.etag(Order::version)`, F16; `GET /orders/{id}` with `.etag`; `DELETE /orders/{id}` with `If-Match`);
    - `application.yaml` (`management.endpoint.health.probes.enabled: true`, `management.endpoint.health.group.readiness.additional-path: server:/health`), `application-local.yaml`, `application-test.yaml`;
    - `README.md` (built and run per F19; it says that writes without a signed-in principal record `synapse.data.auditing.system-user`, `system` by default, in `created_by`/`entry_created_by`, F23), and the `samples/README.md` row.
- Test:
  - `data-orders/.../OrderRepositoryIT` (`@SynapseDataTest("orders")`, `@StoreConnection("orders") static … = SynapseContainers.postgres("orders")`, and a test-only `@SpringBootConfiguration` in the test sources);
  - `data-ledger/.../LedgerRepositoryIT` (`@SynapseDataTest("ledger")`);
  - `service-orders`: `ArchitectureTest`, `OrdersRestTest` (`@SynapseRestTest`), `OrdersApplicationIT`, `ReadinessIT`, `UnqualifiedTransactionalIT`.

**Interfaces:**
- Consumes: plan 4's store API, `@SynapseDataTest`, `@StoreConnection`, `SynapseContainers`.

- [ ] **Step 1: Write the failing tests:**

```java
// OrderRepositoryIT
@Test void auditingAndUuidV7FromTheClock()      // MutableClock; createdAt == clock; id.version() == 7
@Test void staleWriteIsStaleVersion()           // two copies, second save → through SynapseOperations.invoke → STALE_VERSION
@Test void onlyTheOrdersStoreStarts()           // no ledgerDataSource bean
// LedgerRepositoryIT
@Test void customAuditColumnsAreFilled()
// OrdersApplicationIT (two containers: postgres("orders"), postgres("ledger"))
@Test void placeWritesBothStores()              // POST → 201 + Location + ETag "\"0\""; one row in each database
@Test void placeRecordsTheSystemUser()          // no principal → orders.created_by == "system" and ledger entry_created_by == "system" (F23)
@Test void staleIfMatchIs412()                  // DELETE with If-Match "\"5\"" → 412, code PRECONDITION_FAILED (REST's rule, F1); with "\"0\"" → 204
@Test void eachStoreRanItsOwnMigrations()       // flyway_schema_history in each database
@Test void moduleFilesFromTwoJarsLoad()         // the startup report lists both data modules' config.yaml (OutputCapture)
// ReadinessIT (own, unshared containers; test property management.endpoint.health.show-components=always, F31)
@Test void ledgerDownKeepsReadinessUp()         // stop ledger container → /health UP (readiness); /actuator/health shows component db → ledger DOWN
@Test void ordersDownTakesReadinessDown()       // stop orders container → /health 503 DOWN
// UnqualifiedTransactionalIT
@Test void unqualifiedTransactionalFailsStartup()   // SpringApplication with an extra @Transactional bean → plan 4's *Unqualified @Transactional* message
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -pl samples/multi-store/service-orders -am verify` (Docker).
- [ ] **Step 3: Implement**, then run the README (`docker compose up -d` with the Docker Desktop bin directory on PATH, `-am package -DskipTests`, `ENV=local java -jar …`, curl calls) and paste the output.
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit**: `feat(samples): add multi-store, two named stores with JPA and JDBC on Postgres`.

### Task 13: `samples/reference-bookstore`: the contract and data modules

**Files:**
- Create `samples/reference-bookstore/`:
  - `pom.xml`, and `compose.yaml` (`postgres:18.6-alpine`, port 5432, database, user and password `bookstore`).
  - `contract-bookstore/`:
    - `src/main/java/io/americanexpress/synapse/samples/bookstore/contract/{Book,CreateBookRequest,GetBookRequest,UpdateBookRequest,DeleteBookRequest,SearchBooksRequest,BookErrors,package-info}.java`. The records: `Book(UUID id, String isbn, String title, String author, @Nullable Integer publishedYear, long version)`; `UpdateBookRequest(UUID id, @NotBlank …, VersionCondition<Long> version)` and `DeleteBookRequest(UUID id, VersionCondition<Long> version)` with non-null versions (D28); `SearchBooksRequest(@Nullable String author, PageQuery page)` with `SortKey { TITLE, PUBLISHED_YEAR }`; `BookErrors.BOOK_NOT_FOUND`.
    - `src/main/proto/bookstore/v1/bookstore.proto`: `option java_package = "io.americanexpress.synapse.samples.bookstore.contract.proto";` (F11). `BookService` with `CreateBook`, `GetBook`, `SearchBooks`, `UpdateBook` and `DeleteBook` (`google.protobuf.Empty`). `UpdateBookRequest` and `DeleteBookRequest` have `string etag`; `Book` has `int64 version` (the record's component, which REST also keeps in the body) and `string etag` (filled on `GetBook` and `CreateBook` only, D46); `SearchBooksRequest`/`Response` use the AIP-158 fields. Comments say each field's REST counterpart.
    - `pom.xml`: the protobuf plugin, `protobuf-java`, `grpc-protobuf`/`grpc-stub`, `jakarta.validation-api`, `synapse-core`.
  - `data-books/`:
    - `pom.xml`: `synapse-data-jpa` and `synapse-data-postgres` (F16); test: `synapse-test-data`, `spring-boot-starter-test`;
    - `src/main/java/.../bookstore/data/books/{BooksStore,StoredBook,BookRepository,package-info}.java` (`@SynapseDataStore("books")`; `StoredBook extends AuditedEntity`; `BookRepository extends JpaRepository<StoredBook, UUID>`, with `Page<StoredBook> findByAuthorContainingIgnoreCase(String author, Pageable page)` and `boolean existsByIsbn(String isbn)`);
    - `db/migration/books/V1__books.sql` (*Defaults* #13);
    - `META-INF/synapse/config.yaml` (pool 10, `health.readiness: true`), and `config-local.yaml`, `config-build.yaml`, `config-dev.yaml` (*Defaults* #16–#18).
  - `samples/README.md` row (filled in by Task 15).
- Test: `data-books/src/test/java/.../BookRepositoryIT.java` (`@SynapseDataTest("books")`), the test-only `@SpringBootConfiguration`, and `src/test/resources/application-test.yaml`.

Starts only when plan 4's Task 14 is final and merged into the base (F16).

**Interfaces:**
- Consumes: plan 4's store API and `synapse-data-postgres` (by name: `PostgresVendor`, `SynapsePostgresAutoConfiguration`).
- Produces: the contract records and generated stubs (`io.americanexpress.synapse.samples.bookstore.contract.proto.BookServiceGrpc`), and `BookRepository`/`StoredBook`, which Task 14 consumes.

- [ ] **Step 1: Write the failing tests:**

```java
// BookRepositoryIT
@Test void migratesAndValidates()               // context starts (schema validation passed); flyway V1 applied
@Test void isbnIsUnique()                       // second book same isbn, flushed → through SynapseOperations.invoke → ALREADY_EXISTS (CONFLICT)
@Test void versionStartsAtZeroAndIncrements()
@Test void searchesByAuthorIgnoringCase()       // "le guin" finds "Ursula K. Le Guin"; Pageable sort by title
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl samples/reference-bookstore/data-books -am verify`.
- [ ] **Step 5: Commit**: `feat(samples): add the reference bookstore's contract and data modules`.

### Task 14: Reference bookstore: the service, REST and gRPC bindings, and the parity suite

**Files:**
- Create `samples/reference-bookstore/service-bookstore/`:
  - `pom.xml`: `contract-bookstore`, `data-books`, `synapse-api-rest`, `synapse-api-rest-openapi`, `synapse-api-grpc`, `synapse-data-jpa`, `synapse-data-postgres`, `synapse-logging-log4j2`, the OpenTelemetry starter and actuator (logging excluded). Test: `synapse-test-rest`, `synapse-test-grpc`, `synapse-test-data`, `spring-boot-starter-test`. `spring-boot.run.profiles=local`.
  - `src/main/java/io/americanexpress/synapse/samples/bookstore/BookstoreApplication.java`;
  - `operation/{CreateBookOperation,GetBookOperation,SearchBooksOperation,UpdateBookOperation,DeleteBookOperation,BookMapping,package-info}.java`. Update and delete read the entity, check `version.matches(entity.getVersion())` (→ `STALE_VERSION`), then apply. JPA's `@Version` guards the race. Both protocols' edges turn that `STALE_VERSION` into `PRECONDITION_FAILED`, because these requests always carry a version (D28, D43).
  - `api/rest/{BookstoreRestConfiguration,package-info}.java`:
    - `POST /books` (`.created`, `.etag(Book::version)`);
    - `GET /books/{id}` (`.notFound(BOOK_NOT_FOUND)`, `.etag`);
    - `GET /books` (`.sortable(SortKey.class)`);
    - `PUT /books/{id}` and `DELETE /books/{id}` (`.header("If-Match", …)`; no `.etag`, as REST allows none there).
  - `api/grpc/{BookstoreGrpcConfiguration,package-info}.java`: every method explicit with `.unary(...)`; `GetBook` with `.notFound(BOOK_NOT_FOUND)` and `.etag(Book::version)`; `CreateBook` with `.etag(Book::version)`; `SearchBooks` with `.sortable(SortKey.class)`; `UpdateBook` and `DeleteBook` without `.etag` (D46);
  - `config/package-info.java`.
  - Resources: `application.yaml` (`${ENV}`, `spring.application.name: bookstore`, virtual threads, the health groups and `server:/health` with `include: readinessState` (D39); it doesn't set `springdoc.api-docs.enabled`, D31), `application-local.yaml` (*Defaults* #18, including `springdoc.api-docs.enabled: true` with a comment that Synapse's default is off), `application-build.yaml` (empty, with a comment on what belongs there), `application-dev.yaml` (a comment-only example), and `src/test/resources/application-test.yaml` (`synapse.errors.include-detail: true` for the slices only).
- Test:
  - `ArchitectureTest`;
  - `operation/BookOperationsTest` (`@SynapseOperationTest`, with `@MockitoBean BookRepository`);
  - `api/rest/BookstoreRestTest` (`@SynapseRestTest`);
  - `api/grpc/BookstoreGrpcTest` (`@SynapseGrpcTest`);
  - `scenario/{BookstoreClient,RestBookstoreClient,GrpcBookstoreClient,Outcome,Protocol,BookstoreScenarios}.java`;
  - `BookstoreJvmIT` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@StoreConnection("books")`, extends `BookstoreScenarios`).

**Interfaces:**
- Consumes: Tasks 2–4 and 13.
- Produces (test sources, reused by Tasks 15 and 16):

```java
public interface BookstoreClient {      // one per protocol; outcomes normalized per Defaults #12; each also asserts its own transport status
    Outcome create(String isbn, String title, String author, @Nullable Integer year);
    Outcome get(UUID id);                // no getIfNoneMatch: 304 has no gRPC form (F13); it stays in BookstoreRestTest
    Outcome update(UUID id, @Nullable String etag, String isbn, String title, String author, @Nullable Integer year);
    Outcome delete(UUID id, @Nullable String etag);
    SearchOutcome search(@Nullable String author, int size, @Nullable String token, @Nullable String sort);   // sort in REST form; gRPC client converts; null = not sent (an empty order_by is a violation, as REST's empty sort=)
}
public record Target(URI rest, String grpcHost, int grpcPort) {}
public abstract class BookstoreScenarios {
    protected abstract Target target();
    @ParameterizedTest @EnumSource(Protocol.class) void …   // each scenario below, per protocol
}
```

- [ ] **Step 1: Write the failing tests.** Each scenario asserts the same `Outcome` over REST and gRPC:

```java
@ParameterizedTest @EnumSource(Protocol.class) void createReturnsTheBookAndItsEtag(Protocol p)   // CREATED (REST 201 + Location), etag "\"0\"", book.version 0
void duplicateIsbnIsConflictAlreadyExists(Protocol p)      // FAILED, category CONFLICT, code ALREADY_EXISTS (REST 409, gRPC ALREADY_EXISTS)
void blankTitleIsInvalidInputOnTitle(Protocol p)           // FAILED, INVALID_INPUT, violation "title"
void unknownBookIsNotFound(Protocol p)                     // FAILED, NOT_FOUND, BOOK_NOT_FOUND
void getReturnsTheSameBookAndEtag(Protocol p)
void updateWithTheCurrentEtagBumpsTheVersion(Protocol p)   // OK, book.version 1, no etag in the response (F3); a Get then returns etag "\"1\""
void staleEtagIsPreconditionFailed(Protocol p)             // FAILED, category PRECONDITION_FAILED, code PRECONDITION_FAILED (REST 412, gRPC FAILED_PRECONDITION; F1); nothing changed
void missingEtagIsPreconditionRequired(Protocol p)         // FAILED, PRECONDITION_FAILED, code PRECONDITION_REQUIRED (REST 428, gRPC FAILED_PRECONDITION); nothing changed
void starEtagUpdatesAnyVersion(Protocol p)
void weakEtagIsPreconditionFailed(Protocol p)              // FAILED, PRECONDITION_FAILED, code PRECONDITION_FAILED
void deleteWithTheEtagRemovesTheBook(Protocol p)           // NO_CONTENT / Empty; then NOT_FOUND
void searchPagesInTheSameOrder(Protocol p)                 // 5 books, size 2, sort "title" → same ids, same page boundaries
void correlationIdIsEchoed(Protocol p)
@Test void etagFromRestUpdatesOverGrpc()                   // Review Focus 1: REST GET's ETag value as the gRPC etag → OK; the same value again → FAILED_PRECONDITION, PRECONDITION_FAILED
@Test void staleEtagFromGrpcIsRejectedOverRest()           // gRPC GetBook's etag, book updated, then sent as If-Match → 412 PRECONDITION_FAILED
@Test void restCursorPagesOverGrpc()                       // Review Focus 2: REST nextCursor as gRPC page_token → page 2
@Test void grpcPageTokenPagesOverRest()                    // the reverse: gRPC next_page_token (cursor and offset forms) as REST cursor → the same page 2
@ParameterizedTest @EnumSource(Protocol.class) void offsetPagedSearchSignalsTheNextPage(Protocol p) // a Spring Data Page result (data-books' query; SpringDataPages.from(Page) gives no cursor, as from(Slice)): REST hasNext + Link next, gRPC has_next + next_page_token (Task 4 C1)
@ParameterizedTest @EnumSource(Protocol.class) void emptyAndZeroPageFieldsAnswerAsRest(Protocol p) // size 0, empty token, empty sort, offset 0: the same outcome on both (optional page fields, Task 13)
@ParameterizedTest @EnumSource(Protocol.class) void unsortedSearchIsByTitleThenId(Protocol p) // an unset sort orders by title, ties by id, as bookstore.proto documents (Task 13 review)
@Test void generatedKeyWarnsOnceWithBothPlugins()          // BookstoreJvmIT, no cursor key set: exactly one generated-key WARN with REST and gRPC both live (Task 4 review M4)
// BookOperationsTest: create maps and saves; update with Version(3) on version 2 → STALE_VERSION without save; delete AnyVersion deletes
// BookstoreRestTest / BookstoreGrpcTest: binding-level cases (path id vs body id 400; If-None-Match 304 on GET, REST only; gRPC field mapping incl. version and etag)
// BookstoreGrpcTest also carries the gRPC slice tests removed from rest-books in Task 10 (assertions listed in task-10-report.md, items 1, 3–7, 9–12):
//   getBookMapsTheRecord, updateEtagBindsTheVersionOrAny, deleteBookAnswersEmpty, deleteWithoutEtagIsPreconditionRequired,
//   staleEtagIsFailedPrecondition, weakEtagIsFailedPreconditionBeforeTheOperation, and, adapted to this contract (Task 13 review):
//   updateWithoutEtagIsPreconditionRequired (the update's VersionCondition is required here, so item 3's null binding doesn't apply),
//   duplicateIsbnIsAlreadyExists (ALREADY_EXISTS, not item 12's CONFLICT → ABORTED). Items 7 (Empty request) and 10 (lost race without an etag)
//   are dropped: the bookstore has five operations and every write sends a version; both stay covered by the gRPC plugin's own tests.
// BookstoreJvmIT also carries items 13–14: sameOperationOverRestAndGrpc (acceptance 2) beside etagFromRestUpdatesOverGrpc
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -pl samples/reference-bookstore/service-bookstore -am verify -Dprotobuf.sanctioned-executable-path=…`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, and on JDK 21: `… verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"` (acceptance 18, JVM part).
- [ ] **Step 5: Commit**: `feat(samples): serve the reference bookstore over REST and gRPC with one scenario suite`.

### Task 15: Reference bookstore: operations, AOT mode and the README

**Files:**
- Modify: `service-bookstore/pom.xml`:
  - `process-aot` in the default build (the parent's configuration and its execution id `process-aot`, F30; environment `build`, which leaves the API docs and gRPC reflection off);
  - a failsafe execution for `BookstoreAotIT`, which runs after `package`;
  - `BookstoreExitOnRefreshIT`.
- Create:
  - `src/test/java/.../scenario/AppProcess.java` (*Defaults* #25, F27): launches `java [-Dspring.aot.enabled=true] -jar <jar>` with env vars and args (P4 adds a native executable). It picks free ports up front (`new ServerSocket(0)`, closed before launch) and passes `--server.port=<p>` and `--spring.grpc.server.port=<q>`, then exposes them as a `Target`. It waits for `/health` UP within 60 s and captures stdout. It stops the process with `destroy()`, then `destroyForcibly()` after 30 s; on Windows both are a forced kill, so no test asserts a graceful shutdown.
  - `BookstoreAotIT.java`: extends `BookstoreScenarios` against the jar in AOT mode, with `ENV=dev` and `SYNAPSE_DATA_STORES_BOOKS_{URL,USERNAME,PASSWORD}` from a Testcontainers Postgres.
  - `BookstoreExitOnRefreshIT.java`.
  - `BookstoreOperationalIT.java`: health, CORS, OpenAPI, the audit user and the startup report, on the JVM.
- Create: `samples/reference-bookstore/README.md` (layout, environments, `docker compose up -d`, the run per F19 (`./mvnw -pl samples/reference-bookstore/service-bookstore -am package -DskipTests`, then `ENV=local java -jar samples/reference-bookstore/service-bookstore/target/service-bookstore-*.jar`), curl and grpcurl calls (grpcurl as in Task 11, F20), ETag round trip across protocols (read the ETag with a GET, since updates return none), paging, health, `/v3/api-docs` (served in `local` only), the audit user (`created_by` is `synapse.data.auditing.system-user`, `system` by default, F23), tests, AOT/image/agent pointers), and the full `samples/README.md` row.

**Interfaces:**
- Consumes: `BookstoreScenarios` (Task 14), the parent's build environment (Task 7) and the build-time switch check (Task 8).
- Produces: `AppProcess` (used by Tasks 16–17).

- [ ] **Step 1: Write the failing tests:**

```java
// BookstoreOperationalIT (JVM, SpringBootTest)
@Test void healthAndReadinessOnAdditionalPath()      // GET /health → 200 UP; readiness includes db/books (opted in) — acceptance 15
@Test void readinessDownWhenTheStoreIsDown()         // own container stopped → /health 503
@Test void corsPreflightForTheConfiguredOrigin()     // property synapse.api.rest.cors.allowed-origins=http://localhost:3000 → 200 with Access-Control-Allow-Origin; other origin → 403 CORS_REJECTED
@Test void apiDocsListEveryRoute()                   // test property springdoc.api-docs.enabled=true (off by default, F4); /v3/api-docs: 5 operations, If-Match required on PUT and DELETE, 428 documented; saved to target/api-docs-jvm.json
@Test void apiDocsAreOffByDefault()                  // without the property → /v3/api-docs 404 ROUTE_NOT_FOUND
@Test void writesRecordTheSystemUser()               // POST /books, no principal → book.created_by == "system" (F23)
@Test void startupReportNamesModuleOrigins()         // "Synapse module config [config.yaml] data-books" in the report (OutputCapture)
// BookstoreAotIT (packaged jar, -Dspring.aot.enabled=true): inherits every scenario
@Test void aotRunInLocalNamesEveryBuildTimeSwitch()  // ENV=local → exits non-zero; one failure names both spring.grpc.server.reflection.enabled and springdoc.api-docs.enabled (Review Focus 3, F4)
// BookstoreExitOnRefreshIT
@Test void startsWithNoDatabaseReachable()           // ENV=build, -Dspring.context.exit=onRefresh → exit 0 (acceptance 17; the training run's conditions)
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then write the README with real output from `docker compose up -d` (Docker Desktop bin directory on PATH) and the `package` + `ENV=local java -jar` run (F19).
- [ ] **Step 5: Commit**: `feat(samples): run the reference bookstore in AOT mode and document it`.

### Task 16: Reference bookstore: native image (nightly and release) (moved to P4)

Moved to P4 by the maintainer's decision on 2026-11-08. The reference app's native image is built together with the functions sample's, in one native plan, so the GraalVM CI and the nightly workflow are built once for both. Task 16's Step 1 core change goes with it: D45 / *Defaults* #24, `resource:` module-config URLs in a native image. Nothing in this plan needs a native build. Tasks 15 (AOT mode on the JVM) and 17 (the JVM container image with the AOT cache, and the OpenTelemetry agent recipe) stay here. See BUILD.md §15, P4.

### Task 16b: Spring AOT processing becomes opt-in (maintainer, 2026-11-08)

The maintainer decided that Spring AOT processing is opt-in in P1 and goes fully into P4 with the native image. The framework's AOT-safe code stays and stays tested: the build-time switch checks, the hints, the environment rules (option A, its review rulings I1/I2) and their ITs in the framework modules.

**Files:**
- `parent/synapse-parent/pom.xml`: move the `process-aot` execution (id `process-aot`, `--spring.profiles.active=` and `--spring.profiles.default=${synapse.build.environment}`, `<profiles combine.self="override"/>`) into a Maven profile `aot`, off by default. The image env (Task 17) stays in `pluginManagement`. Invoker projects: without `-Paot`, no `process-aot` runs; with it, the current assertions hold.
- Samples: `hello-rest`, `rest-books`, `grpc-inventory` and `multi-store` drop `application-build.yaml` and their data modules' `config-build.yaml`. The reference bookstore keeps them and documents the opt-in. Its `BookstoreAotIT` and `aotRunInLocalNamesEveryBuildTimeSwitch` run only under `-Paot`. CI: a nightly job runs the bookstore with `-Paot`; the pull-request legs don't.
- Global Constraints: the `application-build.yaml` rule applies only to services that use `-Paot`. The archetypes (Tasks 18–19) don't generate it; their READMEs say how to opt in.
- Docs and specs: `parent.adoc`, `environments.adoc` and the samples' READMEs; BUILD.md (P1 scope, 3.3/3.6 and the acceptance items that name AOT mode: AOT in P1 is opt-in, with full AOT and native in P4); a DESIGN row "Spring AOT opt-in in P1 (decided 2026-11-08, maintainer)".
- Task 17's image training run must still start. Decide whether the training run uses another environment, needs no build file, or stays limited to the bookstore, and record the choice. The JVM AOT cache (JEP 483) doesn't need Spring AOT.

- [ ] **Step 1: Write the failing tests** (invoker: no process-aot by default; samples build without the build files).
- [ ] **Step 2: Implement.** **Step 3:** the full build without `-Paot`, then the bookstore and the invoker tests with `-Paot`.
- [ ] **Step 4: Commit**: `build(parent)!: make Spring AOT processing opt-in with the aot profile`, with a BREAKING CHANGE footer.

### Task 17: Reference bookstore: container image and the OpenTelemetry agent recipe

**Files:**
- Create:
  - `service-bookstore/Dockerfile`: multi-stage. `java -Djarmode=tools -jar … extract`, then a training run with `-XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -Dspring.profiles.active=build`, then `ENTRYPOINT java -XX:AOTCache=app.aot -jar …`. The Temurin JRE base image is pinned by digest; read the Adoptium release notes for the version first.
  - `samples/reference-bookstore/scripts/image-smoke.sh`: start Postgres and the image on one Docker network with `ENV=dev` and the store variables, poll `/health` until UP, then call `POST /books`, and print the training line from the build log. When `docker` isn't found and the Docker Desktop bin directory exists, it prepends that directory to PATH, and it sets `MSYS_NO_PATHCONV=1` for its volume and socket arguments (F21).
  - Profile `otel-agent` in `service-bookstore/pom.xml` (*Defaults* #21).
  - `src/main/java/.../bookstore/config/OpenTelemetryAgentConfiguration.java` (D34).
  - `src/test/java/.../BookstoreAgentIT.java`.
  - `docs/modules/ROOT/pages/opentelemetry-agent.adoc`:
    - the recipe;
    - `-Dotel.*` and `OTEL_*`;
    - `management.opentelemetry.map-environment-variables=false` when the agent alone should read `OTEL_*`;
    - the duplicate server spans (agent and Boot) and how to turn one off;
    - `inbound.trusted=false` doesn't restart the trace under the agent until the extension plan.
  - The `docs/modules/ROOT/pages/parent.adoc` image section: heap sizing for small pods (Paketo memory calculator `BPL_JVM_HEAD_ROOM`, `-XX:MaxRAMPercentage` for the Dockerfile; fetch and cite Paketo's Java reference).
- Modify: `.github/workflows/nightly.yml` (job `image-reference`: `./mvnw -Pimage -pl …/service-bookstore -am package` (`synapse-parent`'s `image` profile; the command-line `-am spring-boot:build-image` fails in every module without a main class), then `scripts/image-smoke.sh`, and the same for the Dockerfile; add `image-reference` to `report-failure.needs` (F32); least-privilege `permissions`). Run `actionlint`/`zizmor` on it, or say they aren't installed.

**Interfaces:**
- Consumes: the parent's build-image config (Task 7) and `AppProcess` (Task 15).

- [ ] **Step 1: Write the failing tests:**

```java
// OpenTelemetryAgentConfigurationTest (ApplicationContextRunner)
@Test void absentWithoutTheAgent()                 // no bean named openTelemetryAgent; Boot's OpenTelemetry stays
@Test void activeWhenTheAgentClassIsPresent()      // a test class loader exposing io.opentelemetry.javaagent.bootstrap.AgentInitializer → bean returns GlobalOpenTelemetry.get()
// BookstoreAgentIT (AppProcess: java -javaagent:<agent jar from target/otel> -jar, ENV=dev, otel exporters none)
@Test void startsWithTheTracingRequirementMet()    // no tracing-required failure; /health UP
@Test void inboundTraceReachesSynapseLogs()        // traceparent 00-<trace>-<span>-01 on DELETE /books/<unknown> (If-Match "0"; a GET of an unknown book is the binding's empty result and logs no failure line) → the WARN failure line's traceId and the agent's trace_id == <trace>, correlationId present (there is no trace.id field)
@Test void correlationBaggageReachesTheOperation() // X-Correlation-Id c-1 on DELETE /books/<unknown> → the failure line carries correlationId c-1
// Fix round (review I1): OpenTelemetryAgentConfigurationTest also has the AOT check's cases, and BookstoreAotIT (-Paot)
// aotRunUnderTheAgentFailsFast: the AOT-processed jar under the agent fails at startup (OpenTelemetryAgentAotConfiguration)
```

  For the image: run `./mvnw -Pimage … package` with `synapse.image.jvm-version=25` and then `=21`. Check the build log for the AOT-cache training (25) and the CDS archive (21) (D17). If 21 produces neither, stop and report.
- [ ] **Step 2: Run to verify failure. Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl samples/reference-bookstore/service-bookstore -am verify`, `./mvnw -Pimage … package` with `bash samples/reference-bookstore/scripts/image-smoke.sh <image>`, and `docker build` of the Dockerfile followed by the same smoke.
- [ ] **Step 5: Commit**: `feat(samples): add the reference bookstore's image, Dockerfile and OpenTelemetry agent recipe`.

### Task 18: `synapse-archetype-quickstart`

**Files:**
- Modify: `pom.xml` (module `archetypes/synapse-archetype-quickstart`; properties `maven-archetype-plugin.version=3.4.1`, and the `archetype-packaging` extension 3.4.1, with the source comment).
- Create `archetypes/synapse-archetype-quickstart/`:
  - `pom.xml` (packaging `maven-archetype`; parent the root build; resource filtering of the template `pom.xml` files only, with `@synapse.version@`, `<delimiters><delimiter>@</delimiter></delimiters>` and `useDefaultDelimiters=false`, per *Defaults* #20 (F18); every other template is copied unfiltered);
  - `src/main/resources/META-INF/maven/archetype-metadata.xml`;
  - `src/main/resources/archetype-resources/…`: `hello-rest`'s files, with `${package}`, `${artifactId}` and `${groupId}` substituted, and its tests. Like `hello-rest`, it has no build environment file (`application-build.yaml`) and no Dockerfile (Task 16b). Its README says how to opt in to Spring AOT (`-Paot` with an `application-build.yaml`) and that `-Pimage` needs `application-build.yaml` too, because the parent's AOT-cache training run starts in `build`: the parent's `image` profile fails the build early without it, and only the command-line `spring-boot:build-image` skips that check. Velocity drops lines that start with `##`, so the README's Markdown headings are escaped (`#[[## Heading]]#`), and `verify.groovy` checks they survive (F18);
  - `src/test/resources/projects/basic/{archetype.properties,goal.txt}` (`goal.txt` = `verify`);
  - `src/test/resources/projects/basic/expected-files.txt`: the expected file list, read by `verify.groovy`. There is no `reference/` directory: `archetype:integration-test` compares a `reference/` directory with the generated project as a full copy (F17);
  - `src/test/resources/settings.xml` (mirrors the local repository, as `tests/synapse-build-rules-tests/src/it/settings.xml`);
  - a `maven-invoker-plugin:install` execution into `target/it-repo` (D42).

**Interfaces:**
- Consumes: the `hello-rest` layout (Task 9).

- [ ] **Step 1: Write the failing IT:** `projects/basic` generates `com.example:greetings` with package `com.example.greetings`, and its build (`verify`) passes: operation test, slice test, architecture test, IT. Its `verify.groovy` asserts:
  - the file list equals `expected-files.txt` (`hello-rest`'s list with the package path replaced);
  - `application.yaml` maps `${ENV}`;
  - the README keeps its `##` headings;
  - the pom's parent is `synapse-parent` at the reactor version.
- [ ] **Step 2: Run to verify failure:** `./mvnw -pl archetypes/synapse-archetype-quickstart -am verify`. Check here whether `archetype:integration-test` filters the `@localRepositoryUrl@` token in `settings.xml` (the goal page mentions settings filtering but not that token; F34). If it doesn't, pass the URL through the plugin's settings filtering instead, and record which.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass.**
- [ ] **Step 5: Commit**: `feat(archetypes): add the quickstart archetype, shaped like hello-rest`.

### Task 19: `synapse-archetype-service`

**Files:**
- Create `archetypes/synapse-archetype-service/` as in Task 18, with `archetype-metadata.xml` modules `contract-__serviceName__`, `data-__serviceName__` and `service-__serviceName__` (*Defaults* #19). The resources are the reference bookstore with one aggregate renamed `Item`:
  - create and get over REST and gRPC;
  - one Flyway migration and module config files;
  - `compose.yaml` and the environment files (`local`, `build`, `dev`, `test`). The `build` files (`application-build.yaml` and the data module's `config-build.yaml`) stay because the project ships the Dockerfile with AOT-cache training (maintainer, 2026-11-08, Task 16b review I1). Each carries a comment that it exists for the image's training run and for `-Paot`, and the README says how to opt in to Spring AOT with `-Paot`;
  - the `otel-agent` profile and the Dockerfile, with its AOT-cache training run in `build`;
  - the operation, slice, data and architecture tests, and a two-protocol scenario IT.

**Interfaces:**
- Consumes: the reference bookstore layout (Tasks 13–17).

Windows paths (F22): `LongPathsEnabled` is 0. With `-Paot`, the generated project's AOT sources sit under Synapse's own long package names (the scan estimated 307 characters with `projects/basic` and `service-orders`); without `-Paot` (the default since Task 16b) no AOT sources are generated, but keep the short layout so a `-Paot` build of the generated project also fits. So the IT project is `projects/s`, with `groupId=com.example`, `artifactId=s`, `package=com.example.s` and `serviceName=s`, which puts the generated project at `target/test-classes/projects/s/project/s` with modules `contract-s`, `data-s` and `service-s`.

- [ ] **Step 1: Write the failing IT:** `projects/s` (as above) builds with `verify` (Docker for Testcontainers, with the PATH from *Global Constraints → Windows* only if a step calls `docker`). Its `verify.groovy` reads `projects/s/expected-files.txt` (F17), and asserts the three module directories, `@SynapseDataStore("s")`, `db/migration/s/V1__s.sql`, and that every module has its `package-info.java` with `@NullMarked`.
- [ ] **Step 2: Run to verify failure.** Report the longest path under `target/test-classes/projects/s` (`find … | awk '{ print length, $0 }' | sort -n | tail -1`). If protoc or another native tool fails on a long path, stop and report it with the path; don't move the clone or change the machine setting.
- [ ] **Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl archetypes/synapse-archetype-service -am verify`.
- [ ] **Step 5: Commit**: `feat(archetypes): add the service archetype, shaped like the reference bookstore`.

### Task 20: Docs, spec status and the full build

**Files:**
- Create: `docs/modules/ROOT/pages/{samples,archetypes}.adoc`. `samples.adoc` mirrors `samples/README.md`; `archetypes.adoc` covers `mvn archetype:generate` for both archetypes and what they generate.
- Modify:
  - `docs/modules/ROOT/nav.adoc` (Parent, Samples, Archetypes, OpenTelemetry agent; the Native entry moves to P4);
  - `testing.adoc` (the shared mocks check, the parity-suite pattern);
  - `core.adoc` (`synapse.paging.cursor.*`, `synapse.paging.max-offset`, `ALREADY_EXISTS`, `PRECONDITION_REQUIRED`, the build-time switch check);
  - `data.adoc` (the audit system user: writes without a principal record `synapse.data.auditing.system-user`, `system` by default, F23);
  - `grpc.adoc`;
  - `AGENTS.md` (layout rows `samples/` and `archetypes/`);
  - `specs/v2/BUILD.md` §15: a *Done (plan 5)* note. It covers acceptance 15 (`/health`), 17 (reference app), 18 (the JVM part; the native part moves to P4), 19 (every tool used by the reference app), 7 (parity of the document, on the JVM; the native part moves to P4), and the parity decision. Mark the delivery entry done.
- [ ] **Step 1: Write the docs.** Every property mentioned exists in the metadata.
- [ ] **Step 2: Build the docs:** `cd docs && npm ci && npx antora antora-playbook.yml`, and `node docs/tools/property-tables.mjs`. Check the `synapse.paging.cursor.*` and `synapse.paging.max-offset` rows, and that no `synapse.api.rest.paging.cursor.*` or `synapse.api.rest.paging.max-offset` row is left.
- [ ] **Step 3: Full build on JDK 25:** `./mvnw clean verify -Dprotobuf.sanctioned-executable-path=…` (background, Docker on). Expected: BUILD SUCCESS, including the invoker ITs (the extended `bom-guava-pin` pin check among them) and the archetype ITs. Run `CI=true ./mvnw spotless:check`.
- [ ] **Step 4: JDK 21 leg:** `./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke …`. Report test counts per module for both runs.
- [ ] **Step 5: Commit**: `docs: document the parent, samples, archetypes and agent recipe`.

### Task 21: Every sample README, run against the running app

**Files:**
- Modify: each `samples/*/README.md` where the output differs.

- [ ] **Step 1: For each sample** (`hello-rest`, `rest-books`, `grpc-inventory`, `multi-store`, `reference-bookstore`):
  1. start it as its README says, from a clean clone state (`docker compose down -v` first where there is one);
  2. run every command in order;
  3. compare the output with the pasted text, masking volatile values (ids, timestamps, correlation ids, ETag numbers only when the README says they vary);
  4. stop it.

  Use a fresh `ENV=local` run per sample, started as the README says (F19: `-am package -DskipTests` and `java -jar`, or `spring-boot:run` after `-am install` for `hello-rest`). Docker and grpcurl commands run with the Docker Desktop bin directory on PATH and `MSYS_NO_PATHCONV=1` (F21), and grpcurl is the digest-pinned `v1.9.3` image against `host.docker.internal:9090` (F20). Record per sample: the commands run, and any differences.
- [ ] **Step 1b: Check every relative link** in `samples/README.md` and each `samples/*/README.md` resolves to a file or directory in the tree (Task 10 review: rest-books links to `grpc-inventory` and `reference-bookstore` before they exist).
- [ ] **Step 2: Fix each difference.** Change the README when the app is right. When the app is wrong, open a fix with a failing test in its own commit.
- [ ] **Step 3: Leave nothing running:** stop the apps and `docker compose down` for each sample.
- [ ] **Step 4: Commit**: `docs(samples): re-run every README against the running app`.

### Task 22: The P1 deep review, with the maintainer, through the samples

Added on 2026-11-08 by the maintainer's decision. Plans 2c, 3 and 4 were squashed into `main` before the review, so the review now happens here, and its fixes land as separate commits.

**Scope:** everything P1 built since `main` 208065f2: plan 2c (`69a7ea50`), plan 3 (`23be0766`), plan 4 (`03afc712`) and this plan. The review starts from the samples: how a user writes, configures, runs and tests a service.

- [ ] **Step 1: Whole-branch review.** Run on the most capable model, against `208065f2..HEAD`, with BUILD.md, DESIGN.md and AGENTS.md as the authority. Order the findings by severity. Each one names its sample or module and the user-visible effect.
- [ ] **Step 2: Walk-through with the maintainer.** For each sample, take its README, its configuration and its tests in turn. Also take the open decisions in the review log (`decisions-for-review.md`) and the "provisional" rulings in each plan's ledger. The maintainer decides each one. Start with the AOT build-environment decision (option A, 2026-11-08), which the maintainer asked to revisit: walk through an explainer of how Spring AOT records profiles, the probe results, and options A–D.
- [ ] **Step 3: Fix.** Each accepted finding or changed decision is its own commit, a `fix:` or a `refactor:`, starting with a failing test where behavior changes. Re-run the affected sample READMEs.
- [ ] **Step 4: Full build:** the JDK 25 `CI=true clean verify` (Docker on), the JDK 21 smoke leg, and the docs build. Report the counts per module.
- [ ] **Step 5:** Squash this plan into local `main` and ask the maintainer about pushing. Nothing is pushed without the maintainer's word.

---

## Not in this plan

| Item | Where it goes |
|---|---|
| The Synapse OpenTelemetry agent extension: `inbound.trusted=false` and outbound baggage stripping under the agent, and a JVM-wide baggage propagator (D36) | Its own plan; open questions in DESIGN |
| Outbound baggage stripping in clients | P2 |
| The rate-limited failure-log policy (one WARN per occurrence, the stack once per window, the translator WARN, "dropped N") | Later phase, own branch (BUILD §15) |
| Samples for clients, Kafka, GraphQL, functions, security and scheduling | P2–P4 (BUILD §15) |
| The archetype set beyond these two | P6 |
| Plan 2c's parked REST items (final-review minors, `RestBindings.routes()` exposing an internal type) | The next REST plan |
| The servlet gRPC transport beside REST | Later, on the maintainer's decision |
| Liquibase | The maintainer's license decision |
| The reference app's native image, nightly and on release (was Task 16), with the `resource:` URL rule (D45) and Flyway's native resource scanning | P4, one native plan with the functions sample (maintainer, 2026-11-08) |
| How a native `spring-boot:build-image` (`-Pnative`, `BP_NATIVE_IMAGE`) treats the AOT cache training run: `synapse-parent` sets `BP_JVM_AOTCACHE_ENABLED=true` and `TRAINING_RUN_JAVA_TOOL_OPTIONS` unconditionally (Task 7), and the Paketo README doesn't say how they combine with a native build (Opus review of Tasks 5–9, M5) | P4, the same native plan |
