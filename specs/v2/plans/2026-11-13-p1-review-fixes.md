# P1 review fixes: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the P1 review's recommendations that need no maintainer decision. That covers the design "adopt" items and the code findings marked "no Tony", on top of `main` (4a4a6e4e, all of P1 squashed). Each item is its own reviewable commit.

**Architecture:**
- The tasks follow the design research's *Proposed order for the implementation branch*, steps 1–6, without the items that wait for the maintainer:
  1. the core and gRPC contract (Tasks 1–2);
  2. code-finding fixes (Tasks 3–11);
  3. service ergonomics in the samples and the archetype YAML (Tasks 12–13);
  4. parity enforcement (Task 14);
  5. docs and process, including the Q5 data-bean policy (Tasks 15–17);
  6. archetype hygiene (Task 18).

  Then the docs and spec status (Task 19), the full build (Task 20) and the final review (Task 21).
- The code findings that need no decision (I5, M1, M3, M8, M9, M10, M11, M13) aren't in the research's order. They go in step 2, after the two policy-governed fixes (Q12/I2, Q11/I3), because that step is "code-finding fixes".
- Every behaviour change starts with a failing test (AGENTS.md *Bug fixes*). Docs that describe a behaviour change in the same task as the change. Task 19 holds only the doc-only items and the spec status.

**Tech Stack:** Java 21 (`--release 21`, built on JDK 25), Maven 3.9.16 through the wrapper, Spring Boot 4.1.2 (unchanged; the 4.2 upgrade is a separate branch), Spring Framework 7.0.10, Spring gRPC 1.1.2, grpc-java 1.84.1, Hibernate 7.4.13, Flyway 12.4.0, Jackson 3.1.7, JUnit 5, AssertJ, ArchUnit, Testcontainers 2.0.5, Maven Archetype 3.4.1. One dependency change: `spring-boot-flyway` (Boot-managed, no version) becomes an `optional` compile dependency of `synapse-data-core` (Task 16).

**Spec:**
- Inputs, in the coordinator's scratchpad `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\00343b11-a124-44cb-94d4-0764a5fe68bf\scratchpad\`:
  - `research-design-recommendations.md`: the items D2, D6, D10, D12, D14, D19, D21b, D31b, T9, T10, Q3, Q5, Q11, Q12 and E2/N1, and its *Proposed order*;
  - `research-code-findings.md`: the findings I2, I3, I5, M1, M3, M4, M5, M8, M9, M10, M11 and M13;
  - `fable-review.md`, for context only.

  Where this plan and a research file differ, this plan wins, and the *Decisions taken for the maintainer's review* table says why.
- The authority on behaviour: [BUILD.md](../BUILD.md) §3.3, §3.5, §5.2, §5.10, §6.2, §6.3, §8.1 and §15 (P1). The reasons: [DESIGN.md](../DESIGN.md) §4.1 *Protocol parity* (decided 2026-11-07, maintainer) and *Open questions*. Conventions: [AGENTS.md](../../../AGENTS.md) and plan 5 ([2026-11-07-p1-reference-app.md](2026-11-07-p1-reference-app.md)).

## Execution base

- Branch `feature/v2-p1-review-fixes` in the clone `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\p5` (Git Bash: `/c/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p5`). It was created from `origin/main` at `4a4a6e4e` (`feat!: reference application, samples and archetypes (P1 plan 5)`), and this plan is its first commit. `origin` is the main repository `C:\Users\Tony\VSCode Projects\amex-synapse`.
- Leave the clone's other branches alone (`feature/v2-p1-reference-app` and the rest).
- [ ] **Baseline:** before Task 1, run the JDK 25 full build once (Task 20, Step 1's command) and record the test counts per module and any failure. A failure that exists at the base is reported to the maintainer, not fixed silently in a task.

## Global Constraints

**Branch, JDK, language**
- Never push. Never touch `C:\Users\Tony\VSCode Projects\amex-synapse`. Local `main` gets the squash only when the maintainer says so.
- Before any `./mvnw`: `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"`. For JDK 21 legs: `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never use a global `mvn`.
- Every build that reaches `api/synapse-api-grpc` or a gRPC sample adds `-Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"` (this machine only).
- Never `./mvnw install`: nothing goes into `~/.m2/repository`. Run samples with `-am package` and `java -jar`, as their READMEs do. The archetype ITs use their own `target/it-repo` (plan 5 D42). The wrapper's Maven distribution under `~/.m2/wrapper` is not the repository, and the repository's own `./mvnw` already uses it.
- `--release 21`: no Java 22+ APIs. Use records for data carriers.
- Every new package has an `@NullMarked` `package-info.java`. A test package with a main package's name has none.
- Formatting: Spotless applies locally, so never hand-format. The license header goes on every new Java file.
- Don't edit a pom or `.vscode/` while a Maven build runs (VS Code Java builds into the same `target/`).

**Windows**
- The docker CLI isn't on the agents' PATH. Every step that runs `docker` or `docker compose` first runs:

  ```bash
  export PATH="/c/Users/Tony/AppData/Local/Programs/DockerDesktop/resources/bin:$PATH"
  export MSYS_NO_PATHCONV=1
  ```

  Testcontainers needs none of this (it uses the named pipe).
- `LongPathsEnabled` is 0 on this machine. Keep new paths short; for example, a new archetype IT project gets a one-letter name. Report a tool that fails on a path over 260 characters, with the path. Don't move the clone or change the setting.

**Spring and Synapse rules (AGENTS.md)**
- Spring Boot first:
  - never re-implement or alias a Boot property;
  - defaults for Boot keys go through an `EnvironmentPostProcessor` at lowest precedence, so any value the application sets wins.
- Every auto-configuration a task touches gets `ApplicationContextRunner` tests: it activates, backs off, binds its properties, and stays absent.
- A new Synapse bean that is not a public extension point backs off **by bean name** (`@ConditionalOnMissingBean(name = …)`), and the docs don't call it replaceable. That is the T10 rule, which Task 19 writes into BUILD §3.5.
- No production concept: the environment is never used in a `@Conditional`. Every safety setting defaults to its safe value.
- Errors never expose stack traces, class names or internal messages. Violations reach the caller only for the caller's own input (Task 4).
- Layering: protocol concerns (HTTP statuses, gRPC codes) live only in the API plugins. Core code and `ErrorInfo.reason` are protocol-neutral.
- Bug fixes start with a failing test that reproduces the bug.

**Protocol parity (maintainer decision, 2026-11-07)**
- A REST route and the gRPC method bound to the same operation have the same contract: the same inputs, required-ness, error **codes** (REST's problem `code` equals gRPC's `ErrorInfo.reason`) and categories, version tokens and page tokens.
- The transport status is each protocol's mapping of the code. After Task 2, a stale `etag` sent over gRPC is `PRECONDITION_FAILED` (as REST's 412) with the status `ABORTED` (AIP-154). A weak or unbound tag stays `FAILED_PRECONDITION`.
- After Task 14, startup fails when the REST and gRPC bindings of one operation declare `.notFound`, `.etag` or `.sortable` differently.

**Samples and archetypes**
- A task that changes a sample or an archetype template re-runs the affected README commands against the running app, from a clean state (`docker compose down -v` first where there is one), and pastes real output where it changed. If no command's output can change, the task says why in its report.
- The build-rules test `archetype-drift` (`tests/synapse-build-rules-tests/src/it/archetype-drift/verify.groovy`) stays green. A change to a compared file is made on both sides in the same commit.
- A new IT in a sample needs its package in `.config/jdk21-pr-excluded-its.txt`; the `jdk21-pr-excludes` build-rules test fails otherwise.

**Tests and builds**
- `*Test` are unit tests (surefire); `*IT` are integration tests (failsafe). Neither is ever skipped. `-DskipTests` is never a passing build.
- Per-module JaCoCo floors: 80% line, 70% branch. 90% of changed lines must be covered.
- To iterate on one test (neither counts as passing):

  ```bash
  ./mvnw -q -pl <module> -am test -Dtest=Class -Dsurefire.failIfNoSpecifiedTests=false
  ./mvnw -q -pl <module> -am verify -Dit.test=ClassIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
  ```

  Each task ends with `./mvnw -pl <its modules> -am verify` (plus the protobuf flag where needed), Docker on.

**Commits**
- Conventional Commits; `!` marks a breaking change. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only. Never `git add -A`, stash, amend or push. Leave no `*.bak` or `*.orig` files.
- Versions live in the root `pom.xml`. Spring Boot stays on 4.1.2.

**Execution** (the maintainer's standing rules): every task is implemented on Opus. Each task gets an Opus review; Sonnet is used only for a fix's re-review. Task 21 is an Opus whole-branch review. At most two subagents run at once.

---

## Decisions taken for the maintainer's review

Each row is a choice the research left open, made provisionally so the task can run. Each can be reversed at review; the last column says what that costs.

| # | Item | Question the research left open | Choice | Reason | Alternative (cost to reverse) |
|---|---|---|---|---|---|
| R1 | D2 | How the status mapping knows a `PRECONDITION_FAILED` came from a stale write. | `GrpcErrorStatuses.statusFor(SynapseException, @Nullable String)`: `ABORTED` when the code is `PRECONDITION_FAILED` and the direct cause is a `SynapseException` with `STALE_VERSION` (what `GrpcPreconditions.translate` builds), unless a per-operation or global `[PRECONDITION_FAILED]` override is configured. | No new flag or type. The cause is already kept for the log. | A marker subclass from `GrpcPreconditions` (one more internal type). |
| R2 | D2 | Whether a `[STALE_VERSION]` override applies to the translated stale case. | No. Only `[PRECONDITION_FAILED]` overrides apply, because the code on the wire is `PRECONDITION_FAILED`. | The override key names the code the caller sees. | Also honour `[STALE_VERSION]` (one more lookup). |
| R3 | D2 | How the protocol-neutral scenario suite checks a status that now depends on more than the code. | `GrpcBookstoreClient` keeps asserting the status by category, except for `PRECONDITION_FAILED`. For that code it records `lastStatus()`, and the scenarios that send a stale `etag` assert `ABORTED`, while the weak-tag scenario asserts `FAILED_PRECONDITION`. | `Outcome` stays protocol-neutral, so its equality across protocols holds. | An expected-status field in `Outcome` (breaks cross-protocol equality). |
| R4 | D6 | Which inputs the pipeline checks, how it decides "required", and the violation. | `ServiceOperation` inputs that are records only. A component is required when its type is `VersionCondition` and `ResolvedComponent.nullable()` is false (the rule the plugins use). The check runs after Bean Validation, so an input that is invalid as well as missing its version is `VALIDATION_FAILED`. The violation is `{<component name>, "is required; pass the version read with the resource, or VersionCondition.any()"}`. | One nullability rule for every caller. The research says "after Bean Validation". The component name follows today's rule for REST field names (Q2 is still open). | Check before Bean Validation (428 before 400); a non-record input (reflection over fields). |
| R5 | Q12/I2 | Whether a single list value is split on commas. The design research says never; the code research says not for query values, but keep it for headers. | Query parameters: never split (`?tag=a%2Cb` binds `["a,b"]`). Headers: split, because HTTP defines a list-valued field's comma form as equal to repeated lines. A scalar target with more than one value is `must have a single value` for both. | Follows the code research, which looked at HTTP list headers specifically. It's the same "one value" rule on both sources. | No comma split for headers either (one line in `RequestBinder`). |
| R6 | Q11/I3 | Where the input-only rule is applied, and how a business operation's input violation becomes `INTERNAL`. | (a) `OperationInterceptor.translate` sends a `ConstraintViolationException` to the translators only when every violation's `getRootBean()` is the operation's input (identity). Otherwise it is `INTERNAL`, with the exception as the cause. `ConstraintViolationTranslator` stays. (b) `BusinessInterceptor` throws `INTERNAL`, with the `VALIDATION_FAILED` failure as its cause, when the pipeline rejects a business input. | (a) An operation that validates its own input by hand still answers 400. (b) One place. The ERROR log keeps the violations through the cause, and nothing reaches the caller. | (a) Delete the translator (simpler, but hand validation becomes 500). (b) Convert in the outer interceptor, so a business operation called alone in a test still says `VALIDATION_FAILED` (needs a marker type). |
| R7 | M1 | The research's optional choice: is SQLState class 53 retryable? | Class `53` (insufficient resources, for example `53300 too_many_connections`) → `UNAVAILABLE`, but never a lost connection, so never `COMMIT_OUTCOME_UNKNOWN`. Class `54` → `INTERNAL`. `57P05` (idle session timeout) joins the session-ended states. | 53xxx is the server's transient state, not the caller's fault, and keeps today's status. 54xxx is deterministic. | 53xxx → `INTERNAL` (one translator branch). |
| R8 | M3 | How lazy the gRPC field path is. | Build `path[i]` only in the failure branches for scalar elements (and map values), the case a packed `repeated int32` amplifies. Message elements keep the eager path, because a message element's own size dwarfs its path. | Smallest change to `FieldConverter`'s `String path` contract. It removes the ~33 B per element the probe measured. | A lazy `FieldPath` type through every converter (larger refactor; still one object per element). |
| R9 | M4 | Several stores and an unqualified Flyway `Callback` or `JavaMigration`: WARN or failure? Where customizers run relative to the store's own settings. | A WARN that names the bean and says that no store uses it (Q5's policy: "a Spring developer's bean either works or Synapse WARNs at startup"). With one store, unqualified ones apply. `FlywayConfigurationCustomizer`s run after `migrations.flyway.*`. The store's DataSource and locations are applied after them, so they always win. A customizer that sets `initSql` fails startup, as `migrations.flyway.init-sql` does. | Q5's policy; it matches how `HibernatePropertiesCustomizer` is applied. | Fail startup on the unqualified bean (one branch). |
| R10 | M5 | Honour `EntityManagerFactoryBuilderCustomizer` or only WARN? | Only WARN: once per bean of `EntityManagerFactoryBuilderCustomizer` or `PersistenceUnitManager`, and once when `EntityScanPackages` has packages, while a JPA store exists. Reported through a new `StoreInfrastructure.ignoredBeanTypes()` in `DataStartupReport`. | Q5's list marks the customizer "not honoured", and the research makes honouring it optional. | Honour store-qualified customizers (one call before `build()`). |
| R11 | D10 | What is compared, and with several REST routes for one operation. | The effective `.notFound` code (default `NOT_FOUND`), the `.etag` component name (or none), and the `.sortable` key set (constant names; REST's `sortable(String...)` as written). Every REST binding is compared with every gRPC binding of the same operation; bindings within one protocol aren't compared with each other. REST's `.emptyAs(...)` isn't compared, because it is REST-only. | Parity is a cross-protocol rule. Two REST versions of a route may differ by design. | Also compare within a protocol (could break versioned routes). |
| R12 | D10 | Where unknown operation names in `synapse.api.rest.operations.*` and `synapse.api.grpc.operations.*` are reported. | `OperationProxyVerifier`'s existing scan of `synapse.operations` reads more prefixes, contributed by each plugin as an internal `OperationSettingsPrefix` bean. One WARN per prefix, in the core message's form. | Reuses the core scan and its message. Core knows no plugin key. | A scan in each plugin (two copies). |
| R13 | D19 | Which module sets the default, and when. | `synapse-core`, as `HealthDefaultsEnvironmentPostProcessor`. It sets the key only when Boot's `org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration` is on the class path and `management.endpoint.health.probes.enabled` isn't `false`. | Every service has core. Without actuator, a key would only show up in the environment. | Data-core only (a REST-only service with actuator would lack it). |
| R14 | D14 | Signature details. | `pageable(PageQuery, Function<String, String>, SortOrder...)`. A `null` or blank mapped property is an `IllegalArgumentException` naming the key. `then` orders whose property is already in the sort are skipped. | The research's signature. | `List<SortOrder>` for `then`. |
| R15 | D31b | How the wrapper files get in, and how the IT checks them. | Byte-for-byte copies of the repository's `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties` (wrapper 3.3.4, `only-script`, Maven 3.9.16), as **unfiltered** fileSets. `archetype-drift` asserts they equal the repository's. A post-generate script sets `mvnw` executable (quickstart gets one). `verify.groovy` runs `mvnw --version` (`mvnw.cmd` on Windows) in the generated project, and asserts the executable bit where the file system has POSIX permissions. | One wrapper version to bump, which the build already checks. The executable bit can only be checked on Linux (CI). | Run the whole generated build through `mvnw` (a second full build per IT). |
| R16 | E2 | How CI passes `--spring.main.cloud-platform=none` to `process-aot`. | Documented, not set in the parent: `-Dspring-boot.aot.jvmArguments=-Dspring.main.cloud-platform=none`, if Boot 4.1.2's `process-aot` goal page lists that user property. If it doesn't, document appending an `<argument>` in the service's own `process-aot` execution. Task 15, Step 3 checks the goal page and records which. | The research: "Don't add it to the parent." | Set it in the parent (fixes platform decisions for every service). |
| R17 | I5 | How to add the edge. | `after` gains `GrpcServerAutoConfiguration.class` (already on the compile path: `SynapseGrpcAutoConfiguration` imports it). The security `afterName` stays. | The research's change. | `afterName` string. |
| R18 | M8 | The user scoped M8 to "(doc)". | Docs only: `grpc.adoc` and the `RecordWriter` Javadoc say the overflow leaves `total_size` unset **without a log line**, that AIP-158 prescribes `int32`, and that `int64` holds any total. No log is added. | The scope given. | The research's once-per-field log (about 15 lines and a test). |
| R19 | M10 | "Only if cheap": what counts as cheap. | In scope if the change is the message plus one test, under about 30 changed lines. Otherwise skip it, and record it under *Not in this plan* as P6. | The scope given. | — |
| R20 | Q3 | The research puts the Q3 doc fix in step 1. | It is the last step of Task 2 (its own `docs(rest)` commit), so the step-1 parity review sees it. | The order given. | Task 19. |

---

## Review Focus

These are the inputs the research implies but no single happy-path test covers, most likely first. Each one is pinned by a test in the task named.

1. **A gRPC client that retries `ABORTED` at a higher level** must get `ABORTED` only for a real stale write. A weak tag, a missing `etag`, and a `PRECONDITION_FAILED` that an operation throws itself (not from a stale version) stay `FAILED_PRECONDITION`. A configured `[PRECONDITION_FAILED]` override still wins. Pinned in Task 2: `operationsOwnPreconditionFailedStaysFailedPrecondition`, `weakAndUnboundStayFailedPrecondition`, `configuredOverrideWinsOverTheStaleDefault`.
2. **Validation the caller didn't cause.** An operation that validates its own input by hand still answers 400 with violations. A JPA entity that fails `@Size` at flush, a business input, or a collaborator's bean answers 500 `INTERNAL`, with no `violations` and no property path in the body, on REST and gRPC. Pinned in Task 4: `handValidationOfTheInputIsValidationFailed`, `entityViolationAtFlushIsInternalWithoutViolations`, `businessInputViolationIsInternal`.
3. **A header list next to a query list.** `X-Tags: a, b` must still bind `["a","b"]`, `?tag=a%2Cb` must bind `["a,b"]`, and a repeated `X-Id` on a `Long` must be one violation, not a silently chosen value. A `.queryRecord` component follows the same rule. Pinned in Task 3: `headerListSplitsOnCommas`, `queryListKeepsAnEncodedComma`, `repeatedScalarHeaderIsSingleValueViolation`, `repeatedQueryRecordComponentIsSingleValueViolation`.
4. **A user's own readiness `include`, in any form.** A YAML list, a comma string, an indexed `include[0]`, or `include` set only in an environment file must replace Synapse's default entirely. `exclude` alone keeps the default include, and `probes.enabled: false` sets nothing. Pinned in Task 12: `userListIncludeWins`, `indexedIncludeWins`, `environmentFileIncludeWins`, `excludeAloneKeepsTheDefaultInclude`, `probesOffSetsNothing`.
5. **An operation bound by two REST routes (two API versions) and one gRPC method.** The check must compare each REST route with the gRPC method, and must not fail because the two REST versions differ from each other. Pinned in Task 14: `eachRestRouteIsComparedWithTheGrpcMethod`, `twoRestVersionsMayDifferFromEachOther`.

---

## File structure

New files (main code):

| Path | Responsibility |
|---|---|
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/autoconfigure/HealthDefaultsEnvironmentPostProcessor.java` | D19: the readiness group's default `include` |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/binding/BindingDeclaration.java` | D10: one binding's declarations (record) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/binding/BindingDeclarations.java` | D10: the registry each plugin fills |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/binding/BindingParityCheck.java` | D10: fails startup on a mismatch |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/OperationSettingsPrefix.java` | D10: a plugin's per-operation settings prefix (record) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/masking/MaskedPropertyWriter.java` | M9 |
| `archetypes/synapse-archetype-quickstart/src/main/resources/META-INF/archetype-post-generate.groovy` | D31b: makes `mvnw` executable |
| `archetypes/*/src/main/resources/archetype-resources/{mvnw,mvnw.cmd,.mvn/wrapper/maven-wrapper.properties}` | D31b |

Everything else modifies existing files, listed in each task.

---

### Task 1: The pipeline rejects a missing required `VersionCondition` (D6)

**Files:**
- Modify: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/OperationPipeline.java`, `.../internal/pipeline/OperationInterceptor.java` (call the check after `pipeline.validate(input)`), `core/.../core/conditional/VersionCondition.java` (Javadoc: "the pipeline answers a missing one `PRECONDITION_REQUIRED` before the operation runs, for every caller"), `docs/modules/ROOT/pages/core.adoc` (~L229: "never" now holds for every caller, with the rule and the violation).
- Test: create `core/synapse-core/src/test/java/io/americanexpress/synapse/core/internal/pipeline/OperationPipelineTest.java` (fixtures from `PipelineFixtures`).

**Interfaces:**
- Produces (package-private in `OperationPipeline`):

```java
/** For a ServiceOperation input record: PRECONDITION_REQUIRED, one violation per required VersionCondition component that is null. */
void requirePreconditions(@Nullable Object input);
/** Cached per input type; the same List instance on every call. Empty for non-records. */
List<RecordComponent> requiredVersionComponents(Class<?> type);
```

- Consumes: `ResolvedComponent(Class<?> recordType, RecordComponent component).nullable()` (core `internal.binding`), `SynapseErrors.PRECONDITION_REQUIRED`, `SynapseException.ofViolations`.

- [ ] **Step 1: Write the failing tests** (R4):

```java
// OperationPipelineTest (input records: Update(String id, VersionCondition<Long> version),
// Optional(String id, @Nullable VersionCondition<Long> version), Plain(String id))
@Test void requiredVersionConditionNullIsPreconditionRequired()   // code PRECONDITION_REQUIRED; violations == [("version", "is required; pass the version read with the resource, or VersionCondition.any()")]; operation not called
@Test void nullableVersionConditionNullRuns()
@Test void anyVersionRuns()                                     // VersionCondition.any() → operation called
@Test void invalidInputMissingItsVersionIsValidationFailed()     // @NotBlank id = "" and version null → VALIDATION_FAILED (Bean Validation first)
@Test void businessInputIsNotChecked()                           // BusinessOperation with Update(id, null) → runs
@Test void nonRecordInputIsNotChecked()
@Test void requiredComponentsAreComputedOncePerType()            // requiredVersionComponents(Update.class) isSameAs a second call
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest=OperationPipelineTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compile errors, then the first and fourth tests fail (NPE or the operation runs).
- [ ] **Step 3: Implement** `requirePreconditions` and the `ConcurrentHashMap<Class<?>, List<RecordComponent>>` cache. `OperationInterceptor` calls it right after `pipeline.validate(input)`; `BusinessInterceptor` doesn't call it.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-grpc -am verify -Dprotobuf.sanctioned-executable-path=…`. The plugins' own `PRECONDITION_REQUIRED` tests must stay green: they answer before the pipeline runs.
- [ ] **Step 5: Update DESIGN.md** *Open questions*, the "Required preconditions beyond REST" row: mark (c) decided, citing this plan.
- [ ] **Step 6: Commit**: `feat(core): the pipeline answers a missing required VersionCondition PRECONDITION_REQUIRED`.

### Task 2: A stale `etag` over gRPC is `ABORTED` (D2), and the Q3 citation fix

**Files:**
- Modify:
  - `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/internal/error/GrpcErrorStatuses.java` (the new overload);
  - `.../internal/error/GrpcStatusWriter.java:130` (call the overload with the exception);
  - `.../internal/conditional/GrpcPreconditions.java` (Javadoc: status `ABORTED`, code `PRECONDITION_FAILED`).
- Samples:
  - `samples/reference-bookstore/service-bookstore/src/test/java/.../scenario/{GrpcBookstoreClient,BookstoreScenarios}.java` (R3);
  - `.../api/grpc/BookstoreGrpcTest.java` (`staleEtagIsFailedPrecondition` becomes `staleEtagIsAborted`);
  - `.../BookstoreJvmIT.java` (the race Javadoc and its gRPC assertion);
  - `samples/reference-bookstore/README.md` (the gRPC stale-update output).
- Docs:
  - `docs/modules/ROOT/pages/grpc.adoc` (~L358-396 and the category table ~L617-626: `PRECONDITION_FAILED` is `FAILED_PRECONDITION` except a stale `etag`, which is `ABORTED`; the override example);
  - `specs/v2/BUILD.md` §6.3;
  - `specs/v2/DESIGN.md` §4.1 *Protocol parity* row and the plan 5 D43 note;
  - `docs/modules/ROOT/pages/rest.adoc` ~L581 (Q3).
- Test: `api/synapse-api-grpc/src/test/java/.../internal/error/GrpcErrorStatusesTest.java`, `.../internal/error/SynapseGrpcExceptionHandlerTest.java`, `.../internal/edge/GrpcConditionalIT.java` (update `staleEtagIsFailedPreconditionPreconditionFailed` and `starOnAConcurrentlyChangedRowIsFailedPrecondition`).

**Interfaces:**
- Produces:

```java
// GrpcErrorStatuses
/** statusFor(error.details(), operationName), except ABORTED for PRECONDITION_FAILED caused by STALE_VERSION when no [PRECONDITION_FAILED] override applies. */
public Status.Code statusFor(SynapseException error, @Nullable String operationName);
// GrpcBookstoreClient (test)
@Nullable Status.Code lastStatus();
```

- [ ] **Step 1: Write the failing tests** (R1–R3, Review Focus 1):

```java
// GrpcErrorStatusesTest
@Test void staleCauseIsAborted()                         // new SynapseException(PRECONDITION_FAILED, new SynapseException(STALE_VERSION)) → ABORTED
@Test void weakAndUnboundStayFailedPrecondition()        // PRECONDITION_FAILED with no cause, and with a non-stale cause → FAILED_PRECONDITION
@Test void operationsOwnPreconditionFailedStaysFailedPrecondition()
@Test void configuredOverrideWinsOverTheStaleDefault()   // global [PRECONDITION_FAILED]=FAILED_PRECONDITION and, separately, per-operation → that status
@Test void staleVersionOverrideDoesNotApplyToTheTranslatedCode()   // [STALE_VERSION]=UNAVAILABLE configured; stale-caused PRECONDITION_FAILED → ABORTED (R2)
// GrpcConditionalIT (rename and change the expectation)
@Test void staleEtagIsAbortedPreconditionFailed()        // ABORTED, ErrorInfo.reason PRECONDITION_FAILED
@Test void starOnAConcurrentlyChangedRowIsAborted()
@Test void weakEtagIsFailedPreconditionBeforeTheOperation()   // unchanged expectation, now asserted here too
// SynapseGrpcExceptionHandlerTest
@Test void staleWriteFromAnOperationIsAborted()
// BookstoreScenarios: staleEtagIsPreconditionFailed and etagFromRestUpdatesOverGrpc assert grpc.lastStatus() == ABORTED;
// the weak-tag scenario (~L211) asserts FAILED_PRECONDITION; Outcome expectations are unchanged.
// BookstoreGrpcTest: staleEtagIsAborted (ABORTED, reason PRECONDITION_FAILED)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-grpc -am verify -Dit.test=GrpcConditionalIT -Dtest=GrpcErrorStatusesTest,SynapseGrpcExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true -Dprotobuf.sanctioned-executable-path=…`. Expected: FAILED_PRECONDITION where ABORTED is expected.
- [ ] **Step 3: Implement** the overload and the `GrpcStatusWriter` call. The category default stays `FAILED_PRECONDITION`. In `GrpcBookstoreClient.status(...)`, `PRECONDITION_FAILED` is no longer asserted by category: record it in `lastStatus()` (R3).
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-grpc,samples/reference-bookstore/service-bookstore,samples/grpc-inventory/service-inventory -am verify -Dprotobuf.sanctioned-executable-path=…`. The `grpc-inventory` suite must stay green unchanged (its `FAILED_PRECONDITION` is an `OUT_OF_STOCK` override, not a stale write).
- [ ] **Step 5: Re-run the bookstore README's gRPC update commands**, including the stale one, against `ENV=local` (Docker PATH from *Global Constraints*), and paste the new `ABORTED` output.
- [ ] **Step 6: Commit**: `feat(grpc)!: answer a stale etag ABORTED, as AIP-154 says`.
- [ ] **Step 7: Q3.** In `rest.adoc` ~L581, replace "a write's response carries no validator" with RFC 9110 §9.3.4's condition: a validator only if the representation was saved without transformation and the validator reflects it. Then add that a Synapse write always transforms the stored representation (version and audit fields), so a `PUT` answers no `ETag`. Commit: `docs(rest): cite RFC 9110's condition for a validator on a PUT response`.

### Task 3: One rule for repeated query parameters and headers (Q12, I2)

**Files:**
- Modify: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/RequestBinder.java` (`convert(ResolvedComponent, TypeDescriptor, List<String>)` and its callers, ~L700-800), `docs/modules/ROOT/pages/rest.adoc` ("Values", ~L137, and the violations table).
- Test: `api/synapse-api-rest/src/test/java/.../internal/binding/RequestBinderTest.java`.

**Interfaces:**
- Produces: the violation message `"must have a single value"`, the same text as `PageQueryReader.SINGLE`. Move the constant to one place both read; for example, a package-visible constant in `internal.binding` that `PageQueryReader` imports.
- `convert` learns the value's source (query or header), because only headers split on commas (R5).

- [ ] **Step 1: Write the failing tests** (Review Focus 3):

```java
@Test void repeatedScalarQueryIsSingleValueViolation()     // ?id=1&id=2, Long → VALIDATION_FAILED, ("id", "must have a single value")
@Test void repeatedStringQueryIsNotJoined()                // ?q=a&q=b, String → the same violation (today "a,b")
@Test void queryListKeepsAnEncodedComma()                  // ?tag=a%2Cb, List<String> → ["a,b"]
@Test void repeatedQueryListBindsEveryValue()              // ?tag=a&tag=b → ["a","b"] (unchanged)
@Test void repeatedScalarHeaderIsSingleValueViolation()    // X-Id: 1 and X-Id: 2, Long → ("X-Id"'s component name, "must have a single value")
@Test void headerListSplitsOnCommas()                      // X-Tags: a, b → ["a","b"]
@Test void repeatedQueryRecordComponentIsSingleValueViolation()   // .queryRecord with a Long component sent twice
@Test void pagingRepeatsAreStillOneViolationEach()        // ?size=1&size=2 → exactly one violation on size
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am test -Dtest=RequestBinderTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement.** With more than one value and a target that is neither a collection nor an array, add the violation and return `FAILED`. For a collection target with one query value, convert `List.of(value)` from `STRINGS`; a header keeps today's `STRING → target` path, which splits.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest,samples/rest-books -am verify`. Grep the samples' READMEs for repeated query parameters or headers (`grep -n "&\(\w\+\)=.*&\1=" samples/*/README.md`). If one exists, re-run it; otherwise record that no README command repeats a value.
- [ ] **Step 5: Commit**: `fix(rest)!: reject a repeated value for a single-valued parameter or header`.

### Task 4: Only the caller's own input becomes a 400 (Q11, I3)

**Files:**
- Modify:
  - `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/OperationInterceptor.java` (`translate(Throwable, @Nullable Object input)`, ~L246);
  - `.../internal/pipeline/BusinessInterceptor.java` (`execute`, ~L67);
  - `.../internal/pipeline/ConstraintViolationTranslator.java` (Javadoc: it now sees only violations of the input);
  - docs: `core.adoc` ~L166, `data.adoc` ~L560, `testing.adoc` ~L17 (a business operation's input violation is `INTERNAL`; assert it with `assertThatSynapseException(...).hasCode("INTERNAL")` and read the cause).
- Test: `core/synapse-core/src/test/java/.../internal/pipeline/{OperationInterceptorTest,BusinessInterceptorTest,NestedInvocationTest}.java`; a new `data/synapse-data-jpa/src/test/java/io/americanexpress/synapse/data/jpa/internal/EntityValidationIT.java` (`@Tag` not smoke; H2, as the module's other ITs).

**Interfaces:**
- Consumes: `jakarta.validation.ConstraintViolation.getRootBean()`.
- Produces: no new type. `OperationInterceptor.translate` gains the input parameter (private).

- [ ] **Step 1: Write the failing tests** (R6, Review Focus 2):

```java
// OperationInterceptorTest
@Test void collaboratorViolationIsInternal()              // execute throws a ConstraintViolationException about another object → INTERNAL, cause kept, no violations; ERROR log line
@Test void handValidationOfTheInputIsValidationFailed()    // execute throws a ConstraintViolationException whose root bean is the input → VALIDATION_FAILED with its violations
@Test void mixedRootsAreInternal()                         // one violation on the input, one elsewhere → INTERNAL
// BusinessInterceptorTest (replaces validatesBusinessInput)
@Test void businessInputViolationIsInternal()              // INTERNAL; its cause is a SynapseException VALIDATION_FAILED with the violations
// NestedInvocationTest
@Test void nestedBusinessInputViolationIsInternalAtTheEdge()   // a service operation calling a business operation with an invalid model → INTERNAL, no violations
// EntityValidationIT (data-jpa, with SynapseJpaTransactionManager, then with a replaced plain JpaTransactionManager)
@Test void entityViolationAtFlushIsInternalWithoutViolations()  // @Size(max = 3) column saved with "abcd" → INTERNAL, violations empty
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='OperationInterceptorTest,BusinessInterceptorTest,NestedInvocationTest' -Dsurefire.failIfNoSpecifiedTests=false`, and the IT with the IT command for `data/synapse-data-jpa`.
- [ ] **Step 3: Implement.** `translate` returns `new SynapseException(SynapseErrors.INTERNAL, thrown)` for a `ConstraintViolationException` with any violation whose root bean isn't the input (or with no input), before the translator loop. `BusinessInterceptor` catches the pipeline's `VALIDATION_FAILED` from `validate` only (not from `invocation.proceed()`) and throws `INTERNAL` with it as the cause.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,core/synapse-test,data/synapse-data-jpa,api/synapse-api-rest,api/synapse-api-grpc,samples/rest-books -am verify -Dprotobuf.sanctioned-executable-path=…` (`rest-books` shows business operations; its tests must stay green or be updated to the new rule, each change named in the report). Re-run any `rest-books` README command that answers a business-input violation; otherwise say none does.
- [ ] **Step 5: Commit**: `fix(core)!: report only the operation input's constraint violations to the caller`.

### Task 5: Synapse's gRPC exception handler is last by an edge, not a tie-break (I5), and the `total_size` doc (M8)

**Files:**
- Modify: `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/autoconfigure/SynapseGrpcErrorAutoConfiguration.java` (`after` gains `GrpcServerAutoConfiguration.class`, R17; class Javadoc), `docs/modules/ROOT/pages/grpc.adoc` ("Exception handlers" ~L687: a handler from an auto-configuration answers first only with `@Order` below `LOWEST_PRECEDENCE`, or `before = SynapseGrpcErrorAutoConfiguration.class`; ~L518: M8 per R18), `.../internal/mapping/RecordWriter.java` (Javadoc ~L112, R18).
- Test: `api/synapse-api-grpc/src/test/java/.../autoconfigure/SynapseGrpcErrorAutoConfigurationTest.java`.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void grpcAdviceAnswersWithoutTheSecurityAutoConfiguration()   // GrpcServerAutoConfiguration, no GrpcServerSecurityAutoConfiguration, a @GrpcAdvice bean → the advice's status
@Test void anOrderedHandlerFromAnotherAutoConfigurationAnswersFirst()  // an org.acme auto-configuration, @Order(0), before = SynapseGrpcErrorAutoConfiguration → its status
@Test void synapseErrorAutoConfigurationSortsAfterTheServer()      // AutoConfigurations.of(...) order: GrpcServerAutoConfiguration before SynapseGrpcErrorAutoConfiguration
```

- [ ] **Step 2: Run to verify failure** (the first or third fails today if the order depends on the transitive edge; if both pass already, record that the first pins today's behaviour and the third is the failing one).
- [ ] **Step 3: Implement.** Check that Boot reports no `AutoConfigure cycle detected` (`SynapseGrpcAutoConfiguration` is `before` `GrpcServerAutoConfiguration`).
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-grpc,api/synapse-test-grpc -am verify -Dprotobuf.sanctioned-executable-path=…` (`SynapseGrpcTestIT.appliesTheApplicationsGrpcAdvice` stays green).
- [ ] **Step 5: Commit**: `fix(grpc): order Synapse's exception handler after Boot's gRPC server explicitly`, then `docs(grpc): say that an int32 total_size overflow is not logged`.

### Task 6: No path string per repeated element (M3)

**Files:**
- Modify: `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/internal/mapping/FieldConverters.java` (`list`, ~L291-312, and the map converter's values), `.../internal/mapping/FieldConverter.java` (`Reading`: an element-aware convert, R8).
- Test: `api/synapse-api-grpc/src/test/java/.../internal/mapping/FieldConvertersTest.java` (create it if the module tests `FieldConverters` elsewhere; then add these to that class instead and record which).

**Interfaces:**
- Produces (package-private in `FieldConverter.Reading`):

```java
/** Converts an element; builds parent + "[" + index + "]" only when it records a violation or logs. */
@Nullable Object convertElement(FieldConverter converter, Object value, String parentPath, int index);
```

- [ ] **Step 1: Write the failing tests:**

```java
@Test void aMillionInt32ElementsReadWithoutViolations()
@Test void elementPathsAllocateNothingPerValidElement()   // com.sun.management.ThreadMXBean#getThreadAllocatedBytes around read() of 1_000_000 int32: < 16 bytes per element
@Test void anInvalidElementStillNamesItsIndex()           // the existing tags[1] case, kept
@Test void anInvalidMapValueStillNamesItsKey()
```

- [ ] **Step 2: Run to verify failure.** Record the measured bytes per element before the change. If it is already below 16 for a reason the research didn't see, set the bound between the before and after measurements and record both numbers.
- [ ] **Step 3: Implement** `convertElement`. Scalar and enum elements, and map values, use it; message elements keep the eager path (R8).
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-grpc -am verify -Dprotobuf.sanctioned-executable-path=…`. Record the after-change measurement.
- [ ] **Step 5: Commit**: `perf(grpc): build a repeated element's path only when it fails`.

### Task 7: A lost connection is decided by its SQLState first (M1)

**Files:**
- Modify: `data/synapse-data-core/src/main/java/io/americanexpress/synapse/data/internal/error/LostConnections.java` (`isLostConnection(Throwable)`, `causedBy`, `SESSION_ENDED` gains `57P05`), `.../internal/error/DataExceptionTranslator.java` (R7: class `53` → `UNAVAILABLE`, `54` → `INTERNAL`), `docs/modules/ROOT/pages/data.adoc` (the lost-connection rule and the two classes).
- Test: create `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/error/LostConnectionsTest.java`; modify `DataExceptionTranslatorTest.java` and `data/synapse-data-jdbc/src/test/java/io/americanexpress/synapse/data/jdbc/SynapseJdbcTransactionManagerTest.java`.

**Interfaces:**
- `LostConnections.causedBy(Throwable)` keeps its signature. New rule: if any `SQLException` in the first `CauseChain.MAX_DEPTH` causes has a non-null SQLState, the answer is `isLostConnection(thatState)` for the first one found. Only when none has a state do the exception types decide.

- [ ] **Step 1: Write the failing tests:**

```java
// LostConnectionsTest (@ParameterizedTest over DataAccessResourceFailureException wrapping new SQLException("x", state))
@ParameterizedTest @ValueSource(strings = {"53100", "53300", "54001", "57000", "57P04"}) void resourceFailuresWithAStateAreNotLost(String state)
@ParameterizedTest @ValueSource(strings = {"08006", "08001", "57P01", "57P05"}) void connectionStatesAreLost(String state)
@Test void aResourceFailureWithoutAStateIsLost()           // pool timeout: DataAccessResourceFailureException with no SQLException → true
// DataExceptionTranslatorTest
@Test void insufficientResourcesAreUnavailable()           // 53300 → UNAVAILABLE
@Test void programLimitIsInternal()                        // 54001 → INTERNAL
// SynapseJdbcTransactionManagerTest
@Test void aCommitRejectedForDiskFullIsNotOutcomeUnknown() // commit fails with 53100 → not CommitOutcomeUnknownException
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest='LostConnectionsTest,DataExceptionTranslatorTest' -Dsurefire.failIfNoSpecifiedTests=false`, and the same for `data/synapse-data-jdbc`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jdbc,data/synapse-data-jpa,data/synapse-data-postgres -am verify`.
- [ ] **Step 5: Commit**: `fix(data): decide a lost connection by its SQLState before its exception type`.

### Task 8: `@Masked` beside `@JsonSerialize` masks the component (M9)

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/masking/MaskedPropertyWriter.java` (`extends BeanPropertyWriter`, using its protected copy constructor; overrides `serializeAsProperty` and `serializeAsElement` to write the name and the mask, ignoring any assigned serializer).
- Modify: `.../internal/masking/MaskingModule.java` (`changeProperties` replaces each `@Masked` writer in the returned list), `core/.../core/masking/LogMasking.java` (~L57: a DEBUG line with the failure's class name only, never its message, when `toJson` falls back to `<unserializable …>`).
- Test: create `core/synapse-core/src/test/java/io/americanexpress/synapse/core/internal/masking/MaskingModuleTest.java`.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void maskedWithItsOwnSerializerIsMasked()   // record Card(@Masked @JsonSerialize(using = ToStringSerializer.class) Long pan, String holder) → {"pan":"****************","holder":"A"}
@Test void maskedWithoutASerializerIsUnchanged()  // today's output, kept
@Test void anUnmaskedComponentKeepsItsSerializer()
```

- [ ] **Step 2: Run to verify failure:** today the first gives `<unserializable Card>`.
- [ ] **Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core -am verify`.
- [ ] **Step 5: Commit**: `fix(core): mask a component that has its own serializer`.

### Task 9: `RequestBinder` and `JsonNames` use Jackson's public API (M11)

**Files:**
- Modify: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/RequestBinder.java` (~L445-447: `try (JsonParser p = bodyMapper.treeAsTokens(node)) { p.nextToken(); … (DeserializationContext) p.objectReadContext() }`, no `_deserializationContext()` and no `assignParser`), `.../internal/binding/JsonNames.java` (~L199: a context from `treeAsTokens(NullNode)` or `mapper.reader().forType(...)`).
- Test: `RequestBinderTest`, `JsonNamesTest` (if it exists; otherwise the `RequestBinderTest` cases cover `JsonNames`).

- [ ] **Step 1: Write the guarding tests**, if they're missing: `componentJsonFormatIsHonoured()` (`@JsonFormat(pattern = "dd.MM.yyyy") LocalDate`), `componentJsonDeserializeIsHonoured()`, `snakeCaseNamesAreReadThroughJsonNames()`. Run them green on today's code. This is a refactor with no behaviour change, so they guard it instead of failing first.
- [ ] **Step 2: Add the failing guard:** an ArchUnit test in `api/synapse-api-rest`, `NoJacksonInternalsTest`. It imports the module's main classes and asserts `noClasses().should().callMethodWhere(target(owner(resideInAPackage("tools.jackson..")).and(name(startsWith("_")))))`. Run it: it fails on today's two calls.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `refactor(rest): read request bodies through Jackson's public API`.

### Task 10: The error controller can be proxied (M13)

**Files:**
- Modify: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/error/SynapseErrorController.java` (drop `final`, as Boot's `BasicErrorController` is).
- Test: `api/synapse-api-rest/src/test/java/.../autoconfigure/SynapseRestErrorAutoConfigurationTest.java`.

- [ ] **Step 1: Write the failing test:** `aControllerAspectDoesNotBreakStartup()`. Use an `ApplicationContextRunner` with `AopAutoConfiguration`, an `@Aspect` with `@Around("@within(org.springframework.stereotype.Controller)")`, and `spring.aop.proxy-target-class=true`. The context must start, and the error controller bean must still answer a `/error` dispatch with the Synapse problem.
- [ ] **Step 2: Run to verify failure** (`Cannot subclass final class`). **Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `fix(rest): let aspects proxy Synapse's error controller`.

### Task 11: Point the mock-proxy failure at spring-test (M10, if cheap)

Do this only within R19's limit; otherwise skip it, record it, and go on.

**Files:**
- Modify: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/OperationProxyVerifier.java` (when `verifyProxied` fails for a bean whose class name contains `$MockitoMock$`, the description adds: "spring-test's bean override registry was not found under `org.springframework.test.context.bean.override.internalBeanOverrideRegistry`; Synapse may need an update for this spring-test version").
- Test: `core/synapse-core/src/test/java/.../autoconfigure/OperationStartupChecksTest.java`.

- [ ] **Step 1: Write the failing tests:** `anUnmarkedMockNamesTheRegistry()` (a Mockito mock of an operation, registered without the registry bean, fails with that text), and `registryNameMatchesSpringTest()` (reads `BeanOverrideRegistry.BEAN_NAME` by reflection with `setAccessible` and compares it with the checker's constant).
- [ ] **Step 2: Run to verify failure. Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core -am verify`.
- [ ] **Step 5: Commit**: `fix(core): say when a mocked operation fails because spring-test changed`.

### Task 12: A default readiness group `include` (D19)

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/autoconfigure/HealthDefaultsEnvironmentPostProcessor.java` (R13).
- Modify:
  - `core/synapse-core/src/main/resources/META-INF/spring.factories` (register it);
  - `data/synapse-data-core/src/main/java/.../internal/health/StoreReadinessGroups.java` (Javadoc ~L41-44);
  - `specs/v2/BUILD.md` §5.10 (the readiness bullet becomes "Synapse defaults `include` to `readinessState` at lowest precedence; an application's `include` replaces it");
  - `docs/modules/ROOT/pages/data.adoc` (~L720-740);
  - `docs/modules/ROOT/pages/core.adoc` (the defaults list).
- Remove the `include: readinessState` lines and their four-line comments from:
  - `samples/reference-bookstore/service-bookstore/src/main/resources/application.yaml`;
  - `samples/multi-store/service-orders/src/main/resources/application.yaml`;
  - `archetypes/synapse-archetype-service/src/main/resources/archetype-resources/service-__serviceName__/src/main/resources/application.yaml`.

  Keep the `additional-path` line and its "Not a default" comment.
- Update the READMEs: `samples/multi-store/README.md` (~L309), `samples/reference-bookstore/README.md` (the `/health` explanation) and the service archetype README (~L271).
- Test:
  - create `core/synapse-core/src/test/java/.../autoconfigure/HealthDefaultsEnvironmentPostProcessorTest.java`;
  - modify `data/synapse-data-core/src/test/java/.../internal/health/StoreReadinessIT.java` (the nested `AtTheGroupsAdditionalPath` drops its explicit `include=readinessState` and must stay green).

**Interfaces:**
- Produces: `final class HealthDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered`, ordered `ConfigDataEnvironmentPostProcessor.ORDER + 1`. It adds `management.endpoint.health.group.readiness.include=readinessState` to `DefaultPropertiesPropertySource` with the same skip-if-present guard as `RestDefaultsEnvironmentPostProcessor`.

- [ ] **Step 1: Write the failing tests** (Review Focus 4):

```java
// HealthDefaultsEnvironmentPostProcessorTest (SpringApplication-level where the binder matters)
@Test void defaultIsReadinessState()
@Test void userListIncludeWins()                  // include: [readinessState, ping] in application.yaml → both, nothing else
@Test void indexedIncludeWins()                   // include[0]=ping → [ping]
@Test void environmentFileIncludeWins()           // set only in application-e1.yaml with ENV=e1
@Test void excludeAloneKeepsTheDefaultInclude()   // exclude=ping → include is still readinessState
@Test void probesOffSetsNothing()                 // management.endpoint.health.probes.enabled=false → key absent
@Test void withoutBootHealthSetsNothing()         // FilteredClassLoader without AvailabilityProbesAutoConfiguration → key absent
// StoreReadinessIT$AtTheGroupsAdditionalPath: only additional-path set → members readinessState and db/books, not db/legacy or ping
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest=HealthDefaultsEnvironmentPostProcessorTest -Dsurefire.failIfNoSpecifiedTests=false`, and the `StoreReadinessIT` IT command for `data/synapse-data-core`.
- [ ] **Step 3: Implement**, and remove the sample and archetype lines (both sides of `archetype-drift` in this commit).
- [ ] **Step 4: Run to verify pass:**
  - `./mvnw -pl core/synapse-core,data/synapse-data-core,samples/multi-store/service-orders,samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-service,tests/synapse-build-rules-tests -am verify -Dprotobuf.sanctioned-executable-path=…`, Docker on. `ServiceOperationalIT`, `BookstoreOperationalIT` and the multi-store ITs assert `/health` unchanged.
  - Re-run the `/health` commands in the `multi-store` and `reference-bookstore` READMEs; the output must be byte-identical.
- [ ] **Step 5: Commit**: `feat(core): default the readiness group to readinessState`.

### Task 13: `SpringDataPages.pageable` with a key mapper and tie-breakers (D14)

**Files:**
- Modify: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/paging/SpringDataPages.java` (the overload; the one-argument Javadoc points to it), `samples/reference-bookstore/service-bookstore/src/main/java/.../operation/SearchBooksOperation.java` (drop `properties()` and the `PageQuery` rebuild; keep `property(SortKey)`), `docs/modules/ROOT/pages/core.adoc` (paging), `docs/modules/ROOT/pages/data.adoc` (*Sorting*).
- Test: `core/synapse-core/src/test/java/io/americanexpress/synapse/core/paging/SpringDataPagesTest.java`.

**Interfaces:**
- Produces:

```java
/**
 * As pageable(query), with each key mapped to an entity property by {@code property}, then each {@code then} order
 * appended unless its property is already sorted on.
 * @throws IllegalArgumentException if the query uses a cursor, or {@code property} returns null or blank for a key (the message names the key)
 */
public static Pageable pageable(PageQuery query, Function<String, String> property, SortOrder... then);
```

- [ ] **Step 1: Write the failing tests:**

```java
@Test void keysAreMappedToProperties()            // sort PUBLISHED_YEAR desc, mapper → "publishedYear" → Sort.Order.desc("publishedYear")
@Test void tieBreakersAreAppended()               // then title asc, id asc → [publishedYear desc, title asc, id asc]
@Test void aTieBreakerAlreadySortedIsSkipped()    // sort TITLE desc, then title asc, id asc → [title desc, id asc]
@Test void aBlankMappingNamesTheKey()             // mapper → "" for "X" → IllegalArgumentException containing "X"
@Test void aCursorQueryIsStillRejected()
@Test void offsetAndSizeAreKept()
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement**, and rewrite `SearchBooksOperation.execute` as `SpringDataPages.pageable(page, key -> property(SortKey.valueOf(key)), new SortOrder("title", SortOrder.Direction.ASC), new SortOrder("id", SortOrder.Direction.ASC))`. Its Javadoc's ordering promise is unchanged.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf.sanctioned-executable-path=…` (the parity suite's sorted-search scenarios are the regression test). Re-run the bookstore README's search commands (REST `sort=` and gRPC `order_by`); the output must be byte-identical.
- [ ] **Step 5: Commit**: `feat(core): map sort keys to entity properties in SpringDataPages.pageable`.

### Task 14: REST and gRPC must declare an operation alike (D10)

**Files:**
- Create in `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/binding/`: `BindingDeclaration.java`, `BindingDeclarations.java`, `BindingParityCheck.java`. Create `core/.../internal/pipeline/OperationSettingsPrefix.java`.
- Modify:
  - `core/.../autoconfigure/SynapseOperationAutoConfiguration.java` (the beans `synapseBindingDeclarations` and `synapseBindingParityCheck`, each `@ConditionalOnMissingBean(name = …)`);
  - `core/.../internal/pipeline/OperationProxyVerifier.java` (R12: scan the extra prefixes);
  - REST: `api/synapse-api-rest/src/main/java/.../internal/route/RouteCompiler.java` (register each compiled route); `.../autoconfigure/SynapseRestAutoConfiguration.java` (or wherever `RouteCompiler` is built: pass the registry, and add the bean `synapseRestOperationSettingsPrefix`);
  - gRPC: `api/synapse-api-grpc/src/main/java/.../internal/service/GrpcBindingsCompiler.java` (register each compiled method); `.../autoconfigure/SynapseGrpcAutoConfiguration.java` (the bean `synapseGrpcOperationSettingsPrefix`);
  - docs: `core.adoc` (*Parity*: the startup check and its message), `rest.adoc`, `grpc.adoc` (the same paragraph, and the unknown-name WARN).
- Test:
  - create `core/synapse-core/src/test/java/.../internal/binding/BindingParityCheckTest.java`;
  - modify `core/.../autoconfigure/SynapseOperationAutoConfigurationTest.java` (the two beans: present, backing off by name);
  - create a two-plugin context-runner test, `api/synapse-api-grpc/src/test/java/.../autoconfigure/BindingParityIT.java` (the gRPC module has the REST plugin in test scope; if it doesn't, put it in `samples/reference-bookstore/service-bookstore` as `BindingParityTest` and record that).

**Interfaces:**
- Produces (core internal):

```java
public record BindingDeclaration(String protocol, String operationName, String binding,
        String notFoundCode, @Nullable String etagComponent, @Nullable Set<String> sortKeys) { }
// binding: "GET /books/{id}" or "bookstore.v1.Bookstore/GetBook", for the message
public final class BindingDeclarations {
    public void add(BindingDeclaration declaration);
    public List<BindingDeclaration> forOperation(String operationName);
}
public final class BindingParityCheck implements SmartInitializingSingleton {
    public BindingParityCheck(BindingDeclarations declarations);
}
public record OperationSettingsPrefix(String prefix) { }   // "synapse.api.rest.operations", "synapse.api.grpc.operations"
```

- The failure is a `SynapseConfigurationException`, with the description `Operation '<name>' is bound differently over REST (<binding>) and gRPC (<binding>): <declaration> is <rest value> over REST and <grpc value> over gRPC.` and the action `Declare .<declaration>(...) the same way on both bindings: protocols must not differ in contract.` Every mismatch is listed in one failure, ordered by operation name and then declaration.
- Registration happens when a plugin compiles its bindings during bean creation, so the check (run in `afterSingletonsInstantiated`) sees every binding. If either compiler runs later than that, stop and report it; don't move the check to an event without a decision.

- [ ] **Step 1: Write the failing tests** (R11, R12, Review Focus 5):

```java
// BindingParityCheckTest
@Test void aNotFoundMismatchFailsNamingBothBindings()
@Test void anEtagMismatchFails()                       // .etag(Book::version) over REST only
@Test void aSortableMismatchFails()                    // REST sortable(SortKey.class), gRPC none
@Test void matchingDeclarationsStart()
@Test void aSingleProtocolOperationStarts()
@Test void eachRestRouteIsComparedWithTheGrpcMethod()  // two REST routes, one differs from gRPC → fails naming that route
@Test void twoRestVersionsMayDifferFromEachOther()     // two REST routes differ, gRPC binds none → starts
@Test void everyMismatchIsInOneFailure()
// OperationProxyVerifier (in its existing test class)
@Test void unknownRestOperationSettingsWarn()          // synapse.api.rest.operations.no-such-op.errors.status.[X]=410 → WARN naming no-such-op and the prefix
@Test void unknownGrpcOperationSettingsWarn()
// BindingParityIT: both plugins, the bookstore-like fixture declared alike → starts; .notFound differs → startup fails
```

- [ ] **Step 2: Run to verify failure:** the new core tests fail to compile; the IT starts where it should fail.
- [ ] **Step 3: Implement.** REST registers the route's effective `notFound` (default `SynapseErrors.NOT_FOUND`), the `.etag` component's name, and the `.sortable` keys. gRPC registers the same from `GrpcMethodSpec`.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-grpc,samples/reference-bookstore/service-bookstore,samples/grpc-inventory/service-inventory,samples/rest-books -am verify -Dprotobuf.sanctioned-executable-path=…`. Every sample must start unchanged. If one doesn't, it has a real parity gap: stop and report it rather than change the sample.
- [ ] **Step 5: Commit**: `feat!: fail startup when REST and gRPC declare an operation differently`.

### Task 15: The AOT-processing message names the real cause (E2/N1)

**Files:**
- Modify:
  - `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/environment/EnvironmentName.java` (`requireNoActiveProfile`, ~L139-153);
  - `docs/modules/ROOT/pages/environments.adoc` and `parent.adoc`: a CI build that runs `-Paot` inside a cloud platform (for example a Kubernetes pod) can pass `spring.main.cloud-platform=none` to `process-aot`, R16. Add the caveat that this fixes platform-dependent decisions at build time: config-data `on-cloud-platform` activation, `@ConditionalOnCloudPlatform`, and the `server.forward-headers-strategy` default.
- Test: `core/synapse-core/src/test/java/.../internal/environment/EnvironmentNameTest.java`.

- [ ] **Step 1: Write the failing tests:**

```java
@Test void anIncludedProfileBlamesTheInclude()          // spring.profiles.include=metrics during processing → message names "metrics" and spring.profiles.include; action: move it to spring.profiles.group.<env>
@Test void aProfileNotFromIncludeBlamesALibrary()      // active profile "kubernetes" added programmatically, include empty → message names "kubernetes" and says a library or the platform added it; the action names --spring.main.cloud-platform=none and doesn't mention spring.profiles.include
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement.** Bind `spring.profiles.include` and split the active profiles into those it names and the rest. Each group gets its own sentence and action, and both appear when both groups are non-empty.
- [ ] **Step 4: Check R16** on Boot 4.1.2's Maven plugin reference, `process-aot` goal page (fetch it). Record whether the `spring-boot.aot.jvmArguments` user property exists, and document that route or the `<argument>` route.
- [ ] **Step 5: Run to verify pass:** `./mvnw -pl core/synapse-core -am verify`.
- [ ] **Step 6: Commit**: `fix(core): name the library, not spring.profiles.include, when it activated a profile during AOT processing`.

### Task 16: Stores honour Boot's Flyway beans (Q5, M4)

**Files:**
- Modify:
  - `data/synapse-data-core/pom.xml` (`spring-boot-flyway` becomes `<optional>true</optional>` at compile scope, with a comment: "for `FlywayConfigurationCustomizer`; the consumer has it when it uses Boot's Flyway support");
  - `data/synapse-data-core/src/main/java/.../internal/migration/FlywayStoreMigration.java` (`flyway(...)` takes the customizers, callbacks and migrations);
  - `.../internal/migration/FlywayStoreFactory.java` (look them up with `StoreBeans.applicationBeans(beanFactory, store, FlywayConfigurationCustomizer.class, "FlywayConfigurationCustomizer")` when the class is present, plus store-qualified `Callback` and `JavaMigration` beans, R9);
  - `.../internal/store/DataStartupReport.java` (the R9 WARN);
  - `docs/modules/ROOT/pages/data.adoc` `#migrations`.
- Test: `data/synapse-data-core/src/test/java/.../internal/migration/FlywayStoreMigrationTest.java`, a new `FlywayStoreFactoryTest.java` (`ApplicationContextRunner`, H2), `DataStartupReportTest.java`.

**Interfaces:**
- Produces:

```java
// FlywayStoreMigration
public static Flyway flyway(StoreDefinition store, DataSource dataSource, SynapseDataStoreProperties properties,
        ClassLoader classLoader, List<Consumer<FluentConfiguration>> customizers, List<Callback> callbacks,
        List<JavaMigration> javaMigrations);
// customizers are FlywayConfigurationCustomizer::customize (data-core avoids a hard type reference when the class is absent).
// Order: migrations.flyway.* → customizers → callbacks and javaMigrations → the store's dataSource and locations last;
// initSql set by a customizer → SynapseConfigurationException naming the customizer bean.
```

- [ ] **Step 1: Write the failing tests** (R9):

```java
@Test void aCustomizerTakesEffect()                         // unqualified FlywayConfigurationCustomizer sets baselineOnMigrate → configuration has it
@Test void aStoreQualifiedCustomizerRunsAfterAnUnqualifiedOne()
@Test void aCustomizerCannotMoveTheStore()                  // customizer sets dataSource/locations → the store's own win
@Test void aCustomizerSettingInitSqlFails()
@Test void aStoreQualifiedCallbackRunsAfterMigrate()
@Test void aStoreQualifiedJavaMigrationIsApplied()
@Test void anUnqualifiedCallbackAppliesToTheOnlyStore()
@Test void anUnqualifiedCallbackWithTwoStoresWarns()         // DataStartupReport: WARN names the bean and "is not used by any store"; not applied
@Test void withoutSpringBootFlywayCustomizersAreSkipped()    // FilteredClassLoader hides FlywayConfigurationCustomizer → starts, migrates
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jpa,data/synapse-data-jdbc,data/synapse-data-postgres,tests/synapse-data-mixed-tests -am verify`, Docker on. The Enforcer (dependency convergence, banned dependencies) must stay green with the new optional dependency.
- [ ] **Step 5: Commit**: `feat(data): apply Boot's Flyway customizers, callbacks and Java migrations to stores`.

### Task 17: JPA beans that stores ignore are named at startup, and the Q5 policy

**Files:**
- Modify:
  - `data/synapse-data-core/src/main/java/.../internal/store/StoreInfrastructure.java` (`default List<String> ignoredBeanTypes() { return List.of(); }`);
  - `.../internal/store/DataStartupReport.java` (one WARN per existing bean of an ignored type whose class is present, and the `EntityScanPackages` case, R10);
  - `data/synapse-data-jpa/src/main/java/.../internal/JpaStoreInfrastructure.java` (returns `org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryBuilderCustomizer` and `org.springframework.orm.jpa.persistenceunit.PersistenceUnitManager`);
  - docs: `docs/modules/ROOT/pages/data.adoc` (a new section *Boot beans a store uses*, Q5's list):
    - honoured: `HibernatePropertiesCustomizer`, naming strategies, and (Task 16) `FlywayConfigurationCustomizer`, store-qualified `Callback`/`JavaMigration`, unqualified ones with one store;
    - not honoured, with a startup WARN: `EntityManagerFactoryBuilderCustomizer`, `PersistenceUnitManager`, `@EntityScan`;
    - not honoured, decision pending: unqualified `@ServiceConnection`/`JdbcConnectionDetails`, see M6 under *Not in this plan*;
  - `specs/v2/DESIGN.md` §2 *Anti-goals* (the Synapse-properties line gains: "…, and a Spring developer's bean that a Synapse store silently ignores: it either works or Synapse WARNs at startup").
- Test: `data/synapse-data-core/src/test/java/.../internal/store/DataStartupReportTest.java`, `data/synapse-data-jpa/src/test/java/.../internal/JpaStoreFactoryTest.java` (or a new `JpaIgnoredBeansTest` with an `ApplicationContextRunner`).

- [ ] **Step 1: Write the failing tests:**

```java
@Test void aBuilderCustomizerIsNamedAsIgnored()     // JPA store + EntityManagerFactoryBuilderCustomizer bean "myCustomizer" → one WARN naming myCustomizer and its type
@Test void aPersistenceUnitManagerIsNamedAsIgnored()
@Test void entityScanPackagesAreNamedAsIgnored()    // @EntityScan("com.acme") on a config class → WARN naming the package
@Test void noJpaStoreNoWarning()                    // JDBC store only + the customizer bean → no WARN
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jpa,data/synapse-data-jdbc -am verify`.
- [ ] **Step 5: Commit**: `feat(data): warn about Boot JPA beans that stores don't use, and list the beans stores honour`.

### Task 18: Generated projects ship the Maven wrapper (D31b)

**Files:**
- Create, for each of `archetypes/synapse-archetype-quickstart` and `archetypes/synapse-archetype-service`, under `src/main/resources/archetype-resources/`: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (byte copies of the repository's). Also create `archetypes/synapse-archetype-quickstart/src/main/resources/META-INF/archetype-post-generate.groovy`.
- Modify:
  - both `archetype-metadata.xml` files: one `filtered="false"` fileSet for the wrapper files, with a comment on why (Velocity, and line endings: an unfiltered file is copied as is, so `mvnw` keeps LF and `mvnw.cmd` keeps CRLF);
  - the service archetype's `archetype-post-generate.groovy` (`new File(project, 'mvnw').setExecutable(true, false)` after the name check; the quickstart's new script does only that);
  - both generated `.gitattributes` (add `*.cmd text eol=crlf` and `mvnw text eol=lf`);
  - both READMEs (`./mvnw` instead of `mvn`; the minimum-Maven note becomes "the wrapper downloads Maven 3.9.16");
  - every archetype template file that says `mvn ` in a command (Dockerfile, comments), now `./mvnw `;
  - `tests/synapse-build-rules-tests/src/it/archetype-drift/verify.groovy` (remove the `'./mvnw ': 'mvn '` substitution from both maps, and assert that the three wrapper files in each archetype equal the repository's, byte for byte);
  - both `projects/*/expected-files.txt`;
  - both `verify.groovy` files (R15);
  - `docs/modules/ROOT/pages/archetypes.adoc`.
- Test: the two archetype ITs and the `archetype-drift` build-rules test.

- [ ] **Step 1: Write the failing assertions:**
  - `archetype-drift`: the wrapper files must exist and equal the repository's;
  - each `verify.groovy`:
    - `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties` exist;
    - the properties equal the repository's;
    - `mvnw` has no `\r` and `mvnw.cmd` has `\r\n`;
    - on a POSIX file system, `Files.isExecutable(mvnw)`;
    - `mvnw --version` (Windows: `cmd /c mvnw.cmd --version`) in the generated project exits 0 and prints `Apache Maven 3.9.16`;
  - the expected-file lists gain the three files.
- [ ] **Step 2: Run to verify failure:** `./mvnw -pl archetypes/synapse-archetype-quickstart,archetypes/synapse-archetype-service,tests/synapse-build-rules-tests -am verify -Dprotobuf.sanctioned-executable-path=…`, Docker on.
- [ ] **Step 3: Implement.** If the archetype plugin drops the `.mvn` directory, report it with the plugin's log line and stop. Don't rename the directory.
- [ ] **Step 4: Run to verify pass**, the same command. Report the longest generated path under `archetypes/synapse-archetype-service/target/test-classes/projects/s` (the wrapper adds `.mvn/wrapper/`).
- [ ] **Step 5: Re-run the generated projects' README commands.** Use the IT's generated projects (`target/test-classes/projects/{basic,s}/project/…`): `./mvnw verify` in each, with Docker for the service project. Paste real output only where a README shows output.
- [ ] **Step 6: Commit**: `feat(archetypes): ship the Maven wrapper in generated projects`.

Linux CI is the only place the executable bit is checked for real. Say so in the task report and in Task 19's BUILD note.

### Task 19: Docs-only items and spec status

**Files:**
- Modify:
  - `docs/modules/ROOT/pages/parent.adoc`, `samples/reference-bookstore/README.md`, `specs/v2/BUILD.md` §3.3, `specs/v2/DESIGN.md` §4.1 row *Spring AOT opt-in in P1*: **T9**, "`-Paot` is experimental until P4: it may change without notice". The README change is a label next to existing commands; no command's output changes, so no re-run is needed (say so in the report);
  - `specs/v2/BUILD.md` §3.5: **T10**, a new bullet: "A bean the docs call replaceable has a public type outside `..internal..`, or the docs don't call it replaceable. Synapse's internal beans back off by bean name. The P6 API pass applies this rule to the existing beans";
  - `docs/modules/ROOT/pages/grpc.adoc` (paging section): **D12**, one sentence: `has_next` is the only signal for the capped case, where AIP-158's empty `next_page_token` would mean "end" although more items exist beyond `synapse.paging.max-offset`;
  - `docs/modules/ROOT/pages/data.adoc`: **D21b**, one sentence: a PostgreSQL store without migrations sets `migrations.enabled: false`, and a service with no migrating store can exclude `flyway-database-postgresql` from `synapse-data-postgres`;
  - `specs/v2/BUILD.md` §15 P1: a *Review fixes (plan 2026-11-13)* entry listing what landed, the decisions table's IDs, and the checks that only Linux CI or later runs can make (Task 18's executable bit; R16 if unverified);
  - `specs/v2/DESIGN.md` §6 *Open questions*: close the rows this plan decided (D6 (c) if Task 1 didn't). Add one row per *Not in this plan* item marked "needs Tony" that DESIGN doesn't already hold, with its question and the research's lean.
- [ ] **Step 1: Write the docs.** Every property named exists in the configuration metadata.
- [ ] **Step 2: Build the docs:** `cd docs && npm ci && npx antora antora-playbook.yml`, then `node docs/tools/property-tables.mjs`. Expected: no broken xref, and no property row removed.
- [ ] **Step 3: Commit**: `docs: label AOT experimental, state the replaceable-bean rule, and record the P1 review fixes`.

### Task 20: Full build

- [ ] **Step 1: JDK 25, as CI runs it** (background, Docker on; about 15 minutes):

  ```bash
  CI=true ./mvnw clean verify -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

  Expected: BUILD SUCCESS, including the invoker build-rules tests (`archetype-drift`, `jdk21-pr-excludes`) and both archetype ITs. `CI=true` checks formatting instead of applying it.
- [ ] **Step 2: The JDK 21 pull-request leg:**

  ```bash
  ./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke -Djacoco.skip=true \
    -Dfailsafe.excludesFile="$(pwd -W)/.config/jdk21-pr-excluded-its.txt" \
    -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

- [ ] **Step 3: Report** the test counts per module for both runs, next to the baseline's (*Execution base*), and explain every count that went down.
- [ ] **Step 4:** No commit unless a fix was needed. A fix is its own `fix:` commit with a failing test first, and both runs repeat after it.

### Task 21: Final whole-branch review

- [ ] **Step 1:** An Opus review of `4a4a6e4e..HEAD`, with BUILD.md, DESIGN.md, AGENTS.md, this plan and the two research files as the authority. Order the findings by severity; each names its module or sample and the user-visible effect. The review checks especially:
  - the parity rule after Tasks 2 and 14;
  - that no behaviour change landed without a failing test first;
  - that the decisions table matches what was built.
- [ ] **Step 2: Fix** each accepted finding in its own commit, with a failing test where behaviour changes. A fix gets a Sonnet re-review; repeat Task 20 after the last fix.
- [ ] **Step 3: Report** to the maintainer: the commits, the decisions table (R1–R20) for confirmation, and the *Not in this plan* questions. Squash into local `main` and push only on the maintainer's word.

---

## Not in this plan

**Needs the maintainer** (the research's question and lean):

| Item | Question | Research's lean |
|---|---|---|
| T1 | May the agent push a throwaway branch to `adgonz90/amex-synapse` to run `ci.yml`, and dispatch `nightly.yml` once? | Yes, before any push of `main`; also the manual Ctrl+C and Paketo checks. |
| D18 | Should keys that run SQL on each connection (`hikari.connection-init-sql`, `migrations.flyway.init-sql`, `hikari.connection-test-query`, PgJDBC `options`) be allowed with a startup WARN instead of failing startup? | Yes. Keep every target, identity and secret ban; write "one place says where and who" as the purpose. |
| D20 | May Synapse depend on, or document, a non-OSI (FSL) library (Liquibase 5)? | No for 2.0; a 2.x add-on candidate in DESIGN §5. |
| D21a | Map serialization failure and deadlock (40001, 40P01; also 55P03) to a new `CONFLICT` code (409 / `ABORTED`, WARN), to `UNAVAILABLE`, or keep `INTERNAL`? | A new `TRANSACTION_CONFLICT` in `CONFLICT`, before M1. |
| D25 | Is Log4j2 an Amex platform requirement or a preference? If a preference, should Logback become the default before M1, with `synapse-logging-log4j2` opt-in? | Switch unless there is a platform requirement. No Enforcer ban either way. |
| D34 / item 6 | Should core write the correlation id to the MDC itself without a tracer, and the "tracing required" check move to the outbound plugins (P2/P3)? | Yes. Then the quick start drops the OTel starter and the OTLP line; keep the OTLP line until answered. |
| D35 | Keep the agent recipe in the service archetype, but run the agent copy, checksum and `ServiceAgentIT` only under `-Potel-agent`? | Yes. |
| D30 | Publish only the quickstart archetype in M1, and keep the service archetype built and tested but not deployed, frozen until P6? | Yes. |
| D31a | May generated projects carry no American Express header, and should the archetype README say generated files may be used under any license? | Yes to both (with Amex OSPO if needed). |
| Q1 / I1 | In escape-hatch controllers, should MVC's `@ExceptionHandler`/`@ControllerAdvice` run before Synapse's resolver, `ErrorResponse` statuses be kept, and Spring Security's exceptions propagate? | Yes. The resolver goes after `ExceptionHandlerExceptionResolver`; map unknown statuses by class. |
| Q2 / I8 | Should REST violations name what the client sent (JSON property, query or header name), as gRPC does? | Yes. Decide before M1. |
| Q8 | Hold a scope review before the P2 plan: which of P3–P5 stay in 2.0 GA? | Yes. |
| I6 | What should store readiness report for a saturated pool (UP to avoid cascades, or DOWN like Boot)? Should Synapse default PgJDBC `socketTimeout`? | UP only while no thread waits and a borrow succeeded recently; the socket timeout is a separate decision. |
| I7 | Which internal beans are public extension points? This changes the AGENTS.md "replace every bean" rule. | SPI interfaces for status mapping, the handler, the deadline policy and the limit; no back-off by type for compilers, inventories and factories. P6 API pass. |
| M2 | Should the Log4j2 burst limit cover WARN only (ERROR always passes), and be re-installed on reconfiguration? | Yes. Coupled to D25. |
| M6 | May a single store use a unique unqualified `JdbcConnectionDetails` (`@ServiceConnection`, Docker Compose)? | Yes, single store only; several stores fail naming the bean. It also gives Q10's single-store Compose. |
| M7 | Should a downstream `StatusRuntimeException` escaping a hand-written gRPC service pass through, or keep only its code? | Decide (a) document, or (b) sanitise unless `include-detail`. |
| M12 | Should the OpenAPI customizer be absent when `springdoc.api-docs.enabled=false`? This reverses DESIGN.md:200. | The research gives no lean; it reverses a maintainer decision. |

**Adopt later** (phase as the research gives it):

| Item | Phase |
|---|---|
| T7: Boot 4.2 upgrade, after the I4 canaries | Its own branch after 4.2.0 GA (due 2026-11-19), before M1. Read the 4.2 release notes and migration guide first. |
| I4: Boot-internals canaries (`BootInternalsCanaryTest`), identifying files by `Origin`, and the upgrade-checkpoints list in BUILD.md | The same branch, before the Boot 4.2 upgrade. |
| M14: `resource:` URLs as local in a native image | P4 (already decided, BUILD.md:1555). |
| T10: the full public-API pass (japicmp, `@Incubating`, I7) | P6. |
| D21c: AuditorAware back-off, qualifier rules, `JdbcCustomConversions`, JPA customizers, health thread, `.notFound(code, field)`, servlet transport, Swagger UI in the BOM, snakeyaml, PG 15 default, 2c Task 9 | P6. |
| A10: build-time switch checks versus "always build plus a runtime switch" | P4. |
| A13: public ordering constants | P6. |
| Q6: external configuration (ConfigMaps, `configtree:`, Cloud Config) by `Origin` | P6, before GA. |
| Q10: Docker Compose for stores | Single store with M6 (needs Tony), before M1; multi-store in P5 or 2.x. |
| E5: the environment check fully stands down during AOT processing; `application-build.yaml` optional without stores | P4. |
| S9: trim the samples' YAML comments | P6 docs pass. |
| S10: `ManagementPaths` and guard-rails | P6. |
| M10, if R19 skipped it | P6. |

**Kept as they are** (no change beyond the sentences in Tasks 2 and 19): D12, D21b, A1, A9, Q3, E1, E4, E6.
