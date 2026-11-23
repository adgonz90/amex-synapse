# P1 follow-ups: API surface and packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settle Synapse 2's public surface before M1: the parity check fails closed under lazy initialization, the OpenAPI customizer follows springdoc's toggle, two `@Incubating` SPIs (status mapping and inbound call limits) replace the internal beans users were told to replace, every internal bean backs off by name, `@Incubating` marks what is provisional, and only the quickstart archetype is published. The plan-5 Minors and the AOT and leftover doc items the maintainer confirmed land with it.

**Architecture:**
- The maintainer settled every item on 2026-11-19/20 (decisions 8, 20, 21, 25, 26, 29-31, 32, 33 and 34 of the discussion pack, after research round 2). This plan only builds them. The order:
  1. the parity check (Task 1) and the OpenAPI toggle (Task 2), small and independent;
  2. the two SPIs (Tasks 3 and 4), which add public types and beans;
  3. `@Incubating` and the two moved utilities (Task 5), which creates the new rules-test module;
  4. the back-off conversion (Task 6), last among the code tasks, so its guard sees every bean the earlier tasks and the earlier follow-up plans added;
  5. packaging and the plan-5 Minors (Tasks 7-9), then the doc-only items and the spec status (Task 10), the full build (Task 11) and the final review (Task 12).
- Every behaviour change starts with a failing test (AGENTS.md *Bug fixes*). A task documents its own behaviour change in `docs/`. Task 10 holds only the doc-only items.

**Tech Stack:** Java 21 (`--release 21`, built on JDK 25), Maven 3.9.16 through the wrapper, Spring Boot 4.1.2 (the 4.2 upgrade is on hold), Spring Framework 7.0.10, Spring gRPC 1.1.2, grpc-java 1.84.1, springdoc 3.2.0, ArchUnit 1.5.1 (already managed in the root `pom.xml`), JUnit 5, AssertJ, Testcontainers, Maven Archetype 3.4.1. No new dependency. Task 5's japicmp check is a probe outside the build: its version is chosen at execution after reading its release notes, and cited.

**Spec:**
- Authority on behaviour: [BUILD.md](../BUILD.md) §2 (*Archetypes* row), §3.1, §3.5 (the `@Incubating` rule, the back-off rule and the public extension points), §5.9 (the call limiter), §6.1 (*Parity check timing*, *Errors*: the status mappers), §13 (*AOT profile*), §15 P4 (*Direction*) and P6 (the 2026-11-20 notes). The reasons: [DESIGN.md](../DESIGN.md) §4.1, the rows dated 2026-11-20 (*Parity check under lazy initialization*, *Archetypes in M1*, *License header in generated files*, *AOT follow-ups*, *Public extension points*, *`@Incubating` rule*, *Plan-5 leftovers*) and the reversed *API docs off by default* row. Conventions: [AGENTS.md](../../../AGENTS.md).
- Research, in the coordinator's scratchpad `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\00343b11-a124-44cb-94d4-0764a5fe68bf\scratchpad\`: `research2-api-surface.md` (decisions 8, 20, 21, 25, 26), `research2-plan5-leftovers.md` (items 1, 4, 6, 10, 11), `research2-33-34.md` (33, and 34 except M8) and `research2-aot.md` (29-31). Their *design details* sections are binding; where this plan departs, the decisions table says why.

## Execution base

- After Tony's review, the four follow-up plans' docs commits are merged into local `main` as one docs commit, with the DESIGN.md conflicts resolved once (both sides' additions and both sides' deletions kept). Each plan's feature branch starts from local `main` at that commit, or later, once the earlier plans in the order contract, data, observability, API surface are squashed.
- This plan is **last** in that order, so its branch `feature/v2-fu-api-surface` starts from local `main` once the contract, data and observability plans are squashed, in the clone `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\q4` (Git Bash `/c/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/q4`). The clone's `origin` is the main repository, so fetch just before branching and branch from what that fetch brings of its local `main` (`git fetch origin`, then `git switch -c feature/v2-fu-api-surface origin/main`); check that `origin/main` is the commit local `main` points to in `C:\Users\Tony\VSCode Projects\amex-synapse` (read it with `git ls-remote origin refs/heads/main`, not in that checkout).
- What the earlier plans change under this one, to re-read before the task that depends on it:
  - **Contract:** Synapse's REST exception resolver is no longer a bean. It is inserted as two instances, with a `SynapsePaths` helper, through a `WebMvcConfigurer.extendHandlerExceptionResolvers` bean that backs off by name: read the contract plan for their positions (one after `ExceptionHandlerExceptionResolver`). Nothing in this plan's tasks depends on them beyond the configurer's name (Task 6). REST-only codes `CLIENT_ERROR` and `SERVER_ERROR` keep the status of a Spring exception that Synapse has no code for, and `RestErrorStatuses.statusFor(SynapseException, String)` decides in the order: configured override, then the kept status, then the built-in and category statuses. Task 3 builds the REST SPI on that method. Read before Task 3. Possibly also changes to `BindingParityCheck` (REST versions compared with gRPC by major version): read before Task 1.
  - **Data:** `TRANSACTION_CONFLICT` and new data beans. Read before Task 6.
  - **Observability:** a `synapse-tracing-opentelemetry` module, and the ECS default moved into core. It also creates the package `core.internal.error` (with its `package-info.java`), where Task 5 moves `ErrorMessages`. Read before Task 5, Task 6 (its beans join the guard) and Task 9 (the archetype's generated modules).
- [ ] **Baseline:** before Task 1, run Task 11, Step 1 once and record the test counts per module and any failure. A failure at the base goes to the maintainer; it isn't fixed silently in a task.

## Global Constraints

**Branch, JDK, language**
- Never push, except Task 12 Step 4's throwaway branch on Tony's go. Never touch `C:\Users\Tony\VSCode Projects\amex-synapse`. Local `main` gets the squash only when the maintainer says so.
- Before any `./mvnw`: `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"`. JDK 21 legs: `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never a global `mvn`.
- Every build that reaches `api/synapse-api-grpc`, a gRPC sample or the service archetype adds `-Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"` (written `-Dprotobuf…=…` below).
- Never `./mvnw install` (or `deploy`, which installs first) into `~/.m2/repository`. Task 7's staging deploy and Task 8's install run only with `-Dmaven.repo.local` under the probe directory.
- `--release 21`: no Java 22+ APIs. Records for data carriers.
- Every new package has an `@NullMarked` `package-info.java`; a test package with a main package's name has none.
- Formatting: Spotless applies locally, so never hand-format. The license header goes on every new Java file.
- Don't edit a pom or `.vscode/` while a Maven build runs (VS Code Java builds into the same `target/`).
- `LongPathsEnabled` is 0 on this machine: keep new paths short, and report a tool that fails on a path over 260 characters, with the path.
- Probes go in `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\probe\api-surface\`, never in the clone. Leave no Java process running.
- Docker: steps that run `docker` first run `export PATH="/c/Users/Tony/AppData/Local/Programs/DockerDesktop/resources/bin:$PATH"; export MSYS_NO_PATHCONV=1`. Testcontainers needs none of this.

**Spring and Synapse rules**
- Spring Boot first: never re-implement or alias a Boot property; a default for a Boot key goes through an `EnvironmentPostProcessor` at lowest precedence.
- Every auto-configuration a task touches gets `ApplicationContextRunner` tests: it activates, backs off, binds its properties, and stays absent when its condition isn't met.
- A public extension point backs off by its public type. Every other Synapse bean backs off **by name**, its name starts with `synapse`, and the docs and Javadoc don't call it replaceable (BUILD 3.5).
- No production concept: the environment is never used in a `@Conditional`.
- Errors never expose stack traces, class names or internal messages.
- **Parity:** protocols must not differ in contract. REST's problem `code` equals gRPC's `ErrorInfo.reason`. A status mapper returns a status only, never a code, message or body.
- No HMAC (`javax.crypto.Mac`).

**Samples and archetypes**
- A task that changes a sample or an archetype template re-runs the affected README commands against the running app, from a clean state (`docker compose down -v` first where there is one), and pastes real output where it changed. If no output can change, the task report says why.
- `tests/synapse-build-rules-tests/src/it/archetype-drift` stays green: a change to a compared file is made on both sides in the same commit.

**Tests and builds**
- `*Test` are unit tests (surefire), `*IT` integration tests (failsafe); neither is skipped. `-DskipTests` is never a passing build.
- JaCoCo floors per module: 80% line, 70% branch; 90% of changed lines covered.
- Iterate on one test (neither counts as passing):

  ```bash
  ./mvnw -q -pl <module> -am test -Dtest=Class -Dsurefire.failIfNoSpecifiedTests=false
  ./mvnw -q -pl <module> -am verify -Dit.test=ClassIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
  ```

  Each task ends with `./mvnw -pl <its modules> -am verify` (plus the protobuf flag where needed), Docker on.

**Commits**
- Conventional Commits; `!` marks a breaking change. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only. Never `git add -A`, stash, amend or push. No `*.bak` or `*.orig` files.

**Execution** (the maintainer's standing rules): every task is implemented on Opus and gets an Opus review; Sonnet only for a fix's re-review. Task 12 is an Opus whole-branch review. At most five subagents at once.

---

## Decisions taken for the maintainer's review

The decisions and memos settle the design. These are the calls they left open, made so the tasks can run. Each can be reversed at review; the last column says what that costs.

| # | Item | Question left open | Choice | Reason | Alternative (cost to reverse) |
|---|---|---|---|---|---|
| A1 | 8 | The memo says a late binding is "compared on the spot"; the decision says startup fails when one registers late. | Every registration after the check fails startup, matching or not. Re-adding a declaration that is already registered is a no-op. | The decision's wording, and fail-closed: a late binding means a lazy bean, and the fix is to make it eager, not to compare it later. | Compare on the spot and fail only on a mismatch (one branch in `add`). |
| A2 | 26 | How a replacement mapper reuses Synapse's defaults. | Each mapper interface has a static `defaultStatus(…)`: the fixed and category statuses without overrides (gRPC's includes the stale-etag `ABORTED` rule). A replacement makes the override properties inert, and startup WARNs naming each one that is set. | Boot's `HttpCodeStatusMapper` model: a replacement owns the mapping. The helper saves re-implementing the category table; the WARN follows the Q5 policy ("a bean either works or Synapse WARNs"). | A public default class users extend (more public API); a mapper that returns `null` to defer (not Boot's model). |
| A3 | 26 | The mappers' signatures. | Both take the exception: one abstract method each, `statusFor(SynapseException, @Nullable String)`. No `ErrorDetails` overload on the interfaces; the internal defaults keep their own. | Each protocol's default reads more than the code: gRPC the stale-etag cause, REST the status kept from a Spring exception (the contract plan's order: override, kept status, category). Symmetric across protocols, and a replacement can decide the kept status too (maintainer, fix round 1, I1). | The memo's `ErrorDetails` form for REST, with the kept status outside the mapper (the contract plan would drop "a configured status still wins" for the two kept codes). |
| A4 | 26 | A mapper that throws or answers a non-error status. | The edge logs ERROR once per code with the mapper's class and answers 500 (REST: a status outside 400-599) or `INTERNAL` (gRPC: `OK`, or a throw). | An error path must never fail, and a bad mapping must be visible. | Fail the request with the mapper's exception (leaks nothing, but loses the problem body). |
| A5 | 26 | A permit's outcome. | Netflix's adapters (`ConcurrencyLimitServletFilter`, `ConcurrencyLimitServerInterceptor`, read from `main` on 2026-11-20): gRPC `DEADLINE_EXCEEDED` or a client cancel → `DROPPED`, any other close → `SUCCESS`, a throw before close → `IGNORED`. REST: chain returned → `SUCCESS`, except a 504 → `DROPPED`; chain threw → `IGNORED`; async `onTimeout` → `DROPPED`, `onError` → `IGNORED`, `onComplete` → as a returned chain. | An adaptive limiter's semantics come from the library it adapts. REST's 504 mirrors gRPC's `DEADLINE_EXCEEDED`, so both edges feed a limiter alike. | Netflix's servlet rule exactly (no 504 case). |
| A6 | 26 | A user's `InboundCallLimiter` bean under another name is silently unused. | Core WARNs at startup for every `InboundCallLimiter` bean whose name isn't `synapse<Protocol>CallLimiter`, naming the two names to use. A replaced limiter ignores the plugin's limit property; the docs say so, without a WARN. | The Q5 policy, for the one trap the name back-off creates. A limit property has a default, so "set" can't be told from "defaulted" without reading sources. | No WARN (docs only). |
| A7 | 26 | Packages. | `io.americanexpress.synapse.api.rest.error`, `io.americanexpress.synapse.api.grpc.error` and `io.americanexpress.synapse.core.limit`, new public packages; the semaphore default in `core.internal.limit`. | The memo's packages. `GrpcConcurrencyLimit` disappears into the default. | The plugins' root packages. |
| A8 | 26 | Bean names after the conversion. | Every converted bean is renamed to start with `synapse` (and `synapseRest…` / `synapseGrpc…` in the plugins): for example `routeCompiler` → `synapseRestRouteCompiler`, `contextBinder` → `synapseContextBinder`. Confirmed by the maintainer ("yes, as described"). It lands as four commits by module group (core, REST, gRPC, data), each building green. | A back-off by a generic name (`routeCompiler`, `problemWriter`) would let an unrelated user bean switch Synapse's off. Four commits keep each review small. | Keep the names (no renames in tests). |
| A9 | 26 | Where the back-off guard lives. | A new never-published module, `tests/synapse-api-rules-tests`, depending on every published library module, with one ArchUnit test over every `@AutoConfiguration` class. Task 5 creates it for its `@Incubating` test. | One place sees every module, as `tests/synapse-data-mixed-tests` does for data; P6's public-API gate (M1) can join it. | A copy of the test in each module. |
| A10 | 26 | Ordering constants for user handlers (memo: "publish the order constants"). | None now: the docs state each order (gRPC: Synapse's handler is last, `Ordered.LOWEST_PRECEDENCE`; REST: after `ExceptionHandlerExceptionResolver`, from the contract plan). Public ordering constants stay in P6 (review fixes A13). | Users can already order before Synapse; a constant is public API the P6 pass should design once. | Add `SynapseOrders` now. |
| A11 | 20 | Guarding against an accidental publish. | `central-publish.sh validate` refuses any staged `synapse-archetype-service` file, tested in `test-central-publish.sh`. | Central never deletes a release, so a mistaken publish of a frozen archetype is permanent. Effort S. | `maven.deploy.skip` alone. |
| A12 | 32 | `java.sql.Timestamp` versions: document or leave as a P5 note? | Document now: one sentence in `rest.adoc` (version types) and `data.adoc` (optimistic locking): JPA allows it, Synapse doesn't; use `Instant` or `LocalDateTime`. | A user meets it at the first `@Version`, which plan 4 already supports; two sentences. | A P5 note. |
| A13 | 34 | M3's "run it once" against the no-install rule. | The README states `./mvnw install -DskipTests` and `-DarchetypeVersion=2.0.0-SNAPSHOT`; Task 8 runs it with `-Dmaven.repo.local` in the probe directory, times it, and generates both archetypes from that repository. | Proves the route without touching `~/.m2`. | Run it into `~/.m2` (against the standing rule). |
| A14 | 34 | M5: no-op `@Tag("smoke")` in the samples. | Remove the tags from the 8 sample files and "tagged `smoke`" from the rest-books README; narrow AGENTS.md's rule to the modules of the root build. | A tag that does nothing teaches the wrong rule. | Keep them, called illustrative. |
| A15 | 34 | B-M3: which host port, and the store name. | Host port `15432` in the bookstore's and the service archetype's `compose.yaml` and `config-local.yaml`; the container keeps 5432. The bookstore's local database becomes `books`, as in `config-dev.yaml` and `config-build.yaml`. | Avoids a local PostgreSQL on 5432; one database name per store. `multi-store` already uses 5433/5434. | Keep 5432 (Spring's own guides map default ports). |
| A16 | 34 | M6, M7, M9 and B-M6, which the memo marks optional or "no". | Not in this plan: BUILD's P6 notes list them (B-M6 after the masking follow-up settles what masking promises). | The decision schedules M2, M3, M5 and B-M3 before M1, and M1/M4 for P6. | Fold M7 (XS-S) into Task 8. |
| A17 | 32 | Review A's M3 list also names `PageResult.hasNext` as provisional. | Not marked: the maintainer kept `has_next` mandatory (decision 33), so it is no longer provisional. | The rule marks only provisional design. | Mark it (one annotation). |

---

## Review Focus

Inputs the decisions imply but no happy-path test covers, most likely first. Each is pinned by a test in the task named.

1. **An explicitly `@Lazy` bindings bean.** With or without `spring.main.lazy-initialization`, a `@Lazy` `GrpcBindings` must fail startup even when it matches REST; a `@Lazy` `RestBindings` is still compiled by the eager routes bean and checked; a `@Bean` method declared as `BindableService` is checked under lazy initialization. Task 1: `anExplicitlyLazyGrpcBindingsFailsStartup`, `anExplicitlyLazyRestBindingsIsChecked`, `aBindableServiceReturnTypeIsCheckedUnderLazyInit`.
2. **A user's mapper that misbehaves.** One that answers 200 or 999 over REST, `OK` over gRPC, or throws, must still give the caller a problem (500 / `INTERNAL`) and log the mapper once. Task 3: `aMapperStatusOutsideTheErrorRangeIs500`, `aThrowingMapperIs500`, `aMapperAnsweringOkIsInternal`.
3. **A limiter bean under the wrong name.** `@Bean InboundCallLimiter myLimiter` must WARN that nothing uses it, and one plugin's replacement must not become the other plugin's. Task 4: `aLimiterWithAnotherNameWarns`, `replacingTheRestLimiterKeepsTheGrpcOne`.
4. **An async REST request.** Its permit is released exactly once, with `DROPPED` on an async timeout and `IGNORED` on an async error, never twice when the container calls more than one listener method. Task 4: `asyncTimeoutReleasesOnceDropped`, `asyncErrorThenCompleteReleasesOnce`.
5. **API docs off at build time, on at run time.** With the customizer gone, the AOT switch check must still be there to fail such a run; with docs on, an undescribed type must still WARN. Task 2: `aotCheckExistsWithDocsOff`, `docsOnStillWarnsForAnUndescribedType`.

---

## File structure

New files (main code):

| Path | Responsibility |
|---|---|
| `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/error/{RestErrorStatusMapper,package-info}.java` | REST status-mapping SPI |
| `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/error/{GrpcErrorStatusMapper,package-info}.java` | gRPC status-mapping SPI |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/limit/{InboundCallLimiter,package-info}.java` | Inbound call limiter SPI |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/limit/{SemaphoreCallLimiter,CallLimiterNameCheck,package-info}.java` | The default limiter; the A6 WARN |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/error/ErrorMessages.java` | Moved from `core.error`, into the package the observability plan creates (its `package-info.java` is that plan's) |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/operation/{OperationNames,package-info}.java` | Moved from `core.operation` |
| `tests/synapse-api-rules-tests/pom.xml` and its tests | Never published: `IncubatingApiTest` (Task 5), `BackOffRulesTest` (Task 6) |

Everything else modifies existing files, listed in each task.

---

### Task 1: The parity check fails closed on late bindings (decision 8)

**Files:**
- Modify:
  - `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/binding/BindingDeclarations.java` (seal; late `add`);
  - `.../internal/binding/BindingParityCheck.java` (seal the registry at the end of `afterSingletonsInstantiated`, whether or not it found mismatches; keep whatever comparison the contract plan left);
  - `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/autoconfigure/SynapseGrpcAutoConfiguration.java` (`synapseGrpcBindingsLazyInitializationExcludeFilter` returns `LazyInitializationExcludeFilter.forBeanTypes(BindableService.class)`; Javadoc);
  - docs: `grpc.adoc` (~L78-83 and ~L952-954: remove the `BindableService` caveat; say every `BindableService` bean is eager under lazy initialization, and an explicitly `@Lazy` bindings bean fails startup), `core.adoc` (~L545-551, *Parity*: the same, and the failure).
- Test: `core/synapse-core/src/test/java/.../internal/binding/{BindingDeclarationsTest (create),BindingParityCheckTest}.java`; `api/synapse-api-grpc/src/test/java/.../autoconfigure/SynapseGrpcAutoConfigurationTest.java`; `samples/reference-bookstore/service-bookstore/src/test/java/.../BindingParityTest.java` (both plugins).

**Interfaces:**
- Produces (core internal):

```java
// BindingDeclarations
/** After seal(): a declaration already registered is a no-op; any other throws SynapseConfigurationException. */
public synchronized void add(BindingDeclaration declaration);
/** Called by BindingParityCheck once it has compared every binding. */
public synchronized void seal();
public synchronized boolean sealed();
```

- The late failure: description `The <protocol> binding <binding> of operation '<name>' was compiled after core's parity check between protocols ran, so it can't be compared with the operation's other bindings.`, action `Don't declare the bindings bean @Lazy, and declare its @Bean method's return type as GrpcBindings, BindableService or RestBindings, so it is created with the other singletons.` (`protocol` and `binding` from the `BindingDeclaration`.)

- [ ] **Step 1: Write the failing tests** (Review Focus 1):

```java
// BindingDeclarationsTest
@Test void aRegistrationAfterSealFails()                 // message names protocol, binding and operation
@Test void aDuplicateAfterSealIsANoOp()
@Test void registrationsBeforeSealAreKept()
// BindingParityCheckTest
@Test void theCheckSealsTheRegistry()
@Test void theCheckSealsEvenWhenItFindsNothingToCompare()
// SynapseGrpcAutoConfigurationTest (ApplicationContextRunner, spring.main.lazy-initialization=true where named)
@Test void theFilterMatchesEveryBindableService()        // filter.isExcluded(name, def, BindableService.class) and a plain ImplBase type
@Test void aBindableServiceReturnTypeIsCheckedUnderLazyInit()   // @Bean BindableService returning GrpcBindings, lazy-init on → its declaration is registered before the check
@Test void anExplicitlyLazyGrpcBindingsFailsStartup()   // @Lazy @Bean GrpcBindings, lazy-init off, server started → context fails with the late-binding message
// BindingParityTest (bookstore: both plugins)
@Test void anExplicitlyLazyRestBindingsIsChecked()       // @Lazy @Bean RestBindings whose .notFound differs from gRPC → parity failure, not a late one
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='BindingDeclarationsTest,BindingParityCheckTest' -Dsurefire.failIfNoSpecifiedTests=false`, then the gRPC test with `-pl api/synapse-api-grpc` and `-Dprotobuf…=…`. Expected: compile errors, then `aBindableServiceReturnTypeIsCheckedUnderLazyInit` and `anExplicitlyLazyGrpcBindingsFailsStartup` start cleanly. If `anExplicitlyLazyGrpcBindingsFailsStartup` already fails for another reason (Spring gRPC 1.1.2 discovers services differently from the `main` branch the research read), record the actual behaviour, keep the test's expectation, and say so in the report.
- [ ] **Step 3: Implement** the seal and the filter. `GrpcBindingsCompiler` and `RouteCompiler` stay as they are: the throw comes from `add`.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-grpc,samples/reference-bookstore/service-bookstore,samples/grpc-inventory/service-inventory -am verify -Dprotobuf…=…`. Every sample must start unchanged.
- [ ] **Step 5: Commit**: `fix!: fail startup when a binding compiles after the parity check, and keep every BindableService eager`.

### Task 2: The OpenAPI customizer follows springdoc's toggle (decision 25)

**Files:**
- Modify:
  - `api/synapse-api-rest-openapi/src/main/java/io/americanexpress/synapse/api/rest/openapi/autoconfigure/SynapseRestOpenApiAutoConfiguration.java`: `@ConditionalOnBooleanProperty(name = "springdoc.api-docs.enabled", matchIfMissing = true)` on `synapseRestOpenApiCustomizer`; remove the environment-read path filters (`PATHS_TO_MATCH`, `PATHS_TO_EXCLUDE`, the `else` branch) and the `Environment` parameter's second use; `SpringDocConfigProperties` becomes a required parameter if springdoc always defines it while enabled (check `SpringDocConfiguration` 3.2.0), otherwise keep `ObjectProvider` and treat absence as no filters; Javadoc drops "also while the document is off". `synapseOpenApiAotCheck` stays unconditional.
  - docs: `openapi.adoc` (~L203-208: the customizer exists only while the docs are on, so a route that can't be described is reported where the document is served; the testing section ~L340-346 gains the one-test recipe: fetch `/v3/api-docs` with the docs on and assert no schema is `{}`).
  - `samples/reference-bookstore/service-bookstore/src/test/java/.../BookstoreOperationalIT.java` (`WithApiDocs`) and the service archetype's `service-__serviceName__/src/test/java/ServiceOperationalIT.java`: the no-empty-schema assertion. The archetype file is in `archetype-drift`'s list if the bookstore one is; change both sides alike.
- Test: `api/synapse-api-rest-openapi/src/test/java/.../autoconfigure/SynapseRestOpenApiAutoConfigurationTest.java`.

**Interfaces:**
- Consumes: nothing new. The assertion walks `components.schemas.*`, and every `requestBody.content.*.schema` and `responses.*.content.*.schema` under `paths`, and fails naming the JSON pointer of each schema that is an empty object.

- [ ] **Step 1: Write the failing tests** (Review Focus 5):

```java
@Test void docsOffMeansNoCustomizer()                 // springdoc.api-docs.enabled=false → no synapseRestOpenApiCustomizer, no WARN in the captured log
@Test void aotCheckExistsWithDocsOff()                // synapseOpenApiAotCheck present with the docs off
@Test void docsOnCreatesTheCustomizer()               // true → bean present
@Test void docsUnsetCreatesTheCustomizer()            // property absent from the runner's environment (matchIfMissing; the Synapse EPP that defaults it to false doesn't run in a bare runner)
@Test void docsOnStillWarnsForAnUndescribedType()     // docs on, a route whose output has an Object component → the existing WARN
@Test void openApi30FailsOnlyWithTheDocsOn()          // version openapi_3_0 + docs off → starts
// BookstoreOperationalIT.WithApiDocs, ServiceOperationalIT: noSchemaIsEmpty()
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest-openapi -am test -Dtest=SynapseRestOpenApiAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: `docsOffMeansNoCustomizer` and `openApi30FailsOnlyWithTheDocsOn` fail. Run the two ITs: `noSchemaIsEmpty` should pass already; if one fails, the sample has an undescribed type: stop and report it.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest-openapi,samples/rest-books,samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-service,tests/synapse-build-rules-tests -am verify -Dprotobuf…=…`, Docker on. README commands: the bookstore README's `/v3/api-docs` run (~L694) can't change (docs on in `local`); say so in the report.
- [ ] **Step 5: Commit**: `feat(openapi)!: skip Synapse's OpenAPI customizer while springdoc's API docs are off`.

### Task 3: Status-mapping SPIs (decision 26)

Re-read the contract plan's `RestErrorStatuses.statusFor(SynapseException, String)` first: `RestErrorStatuses` implements the REST SPI with that method and its order (override, kept status, category), unchanged.

**Files:**
- Create: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/error/{RestErrorStatusMapper,package-info}.java`, `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/error/{GrpcErrorStatusMapper,package-info}.java`.
- Modify:
  - REST: `internal/error/RestErrorStatuses.java` (`implements RestErrorStatusMapper`); `internal/error/ProblemWriter.java` and the contract plan's resolver (take `RestErrorStatusMapper`; apply A4's guard where the status is chosen); `autoconfigure/SynapseRestErrorAutoConfiguration.java` (bean `synapseRestErrorStatusMapper`, `@ConditionalOnMissingBean(RestErrorStatusMapper.class)`; bean `synapseRestStatusOverridesCheck`, a `SmartInitializingSingleton` that WARNs when the mapper isn't a `RestErrorStatuses` and an override is set, by name);
  - gRPC: `internal/error/GrpcErrorStatuses.java` (`implements GrpcErrorStatusMapper`), `internal/error/GrpcStatusWriter.java` (takes the interface; A4's guard), `autoconfigure/SynapseGrpcErrorAutoConfiguration.java` (`synapseGrpcErrorStatusMapper` by type on the interface; `synapseGrpcStatusOverridesCheck` by name);
  - OpenAPI: `RouteDocumenter`, `SynapseOpenApiCustomizer`, `SynapseRestOpenApiAutoConfiguration` take `RestErrorStatusMapper`; the documenter asks it for each documented code with `new SynapseException(details)` (no kept status: a Synapse route never raises a Spring exception's);
  - docs: `rest.adoc` and `grpc.adoc` *Errors* (the mapper bean after the override properties: Boot's model, a replacement owns the mapping, `defaultStatus`, the WARN, A4's guard, and that it returns a status only), `openapi.adoc` (documented statuses come from the mapper).
- Test: `RestErrorStatusesTest`, `GrpcErrorStatusesTest`, `SynapseRestErrorAutoConfigurationTest`, `SynapseGrpcErrorAutoConfigurationTest`, `ProblemWriterTest`, `GrpcStatusWriterTest` (or where the writers are tested today), `SynapseRestOpenApiAutoConfigurationTest`.

**Interfaces:**
- Produces:

```java
package io.americanexpress.synapse.api.rest.error;
/** Decides the HTTP status of an error Synapse answers. Returns a status only: the code and the body never change. */
@Incubating @FunctionalInterface
public interface RestErrorStatusMapper {
    /** @return a status from 400 to 599 */
    int statusFor(SynapseException error, @Nullable String operationName);
    /** The status with no override configured: the status kept from a Spring exception (CLIENT_ERROR, SERVER_ERROR), a RestErrors code's own status, 428 for PRECONDITION_REQUIRED, else the category's. */
    static int defaultStatus(SynapseException error);
}

package io.americanexpress.synapse.api.grpc.error;
@Incubating @FunctionalInterface
public interface GrpcErrorStatusMapper {
    /** @return any code but OK */
    Status.Code statusFor(SynapseException error, @Nullable String operationName);
    /** The status with no override configured: ABORTED for a stale etag (AIP-154), the built-in codes' fixed statuses, else the category's. */
    static Status.Code defaultStatus(SynapseException error);
}
```

- `@Incubating` here is today's annotation; Task 5 changes its retention.
- The A4 guard is a package-private helper in each plugin's `internal.error`, logging at ERROR once per (mapper class, code) with the code and the mapper's class name, never the exception's message.
- The WARN: `<key> is set, but the bean '<name>' (<class>) replaces Synapse's status mapping, which reads it, so it has no effect.` One line per key, `synapse.api.rest.errors.status.[CODE]` and `synapse.api.rest.operations.<op>.errors.status.[CODE]` (gRPC alike).

- [ ] **Step 1: Write the failing tests** (Review Focus 2):

```java
// SynapseRestErrorAutoConfigurationTest
@Test void defaultMapperIsSynapses()                    // RestErrorStatusMapper bean is a RestErrorStatuses, named synapseRestErrorStatusMapper
@Test void aUserMapperReplacesIt()                      // user @Bean RestErrorStatusMapper → only that one; the problem for NOT_FOUND has its status and code NOT_FOUND
@Test void overridesWithAUserMapperWarn()               // + synapse.api.rest.errors.status.[NOT_FOUND]=410 → WARN naming the key and the bean
@Test void overridesWithoutAUserMapperDontWarn()
@Test void aMapperStatusOutsideTheErrorRangeIs500()     // mapper answers 200 and, separately, 999 → 500, code unchanged, one ERROR line across two requests
@Test void aThrowingMapperIs500()
@Test void defaultStatusIgnoresOverrides()              // RestErrorStatusMapper.defaultStatus(new SynapseException(NOT_FOUND)) == 404 with an override configured
@Test void defaultStatusKeepsASpringStatus()            // the contract plan's CLIENT_ERROR exception kept at 410 → defaultStatus == 410
@Test void theDefaultMapperKeepsTheContractOrder()     // [CLIENT_ERROR]=400 configured, kept 410 → 400; nothing configured → 410
@Test void aUserMapperDecidesAKeptSpringStatus()        // escape-hatch controller throws ResponseStatusException(410); user mapper → 422 → 422, code CLIENT_ERROR
// SynapseGrpcErrorAutoConfigurationTest (mirror) +
@Test void aMapperAnsweringOkIsInternal()
@Test void defaultStatusKeepsTheStaleEtagRule()         // GrpcErrorStatusMapper.defaultStatus(stale-caused PRECONDITION_FAILED) == ABORTED
@Test void aUserMapperDoesNotChangeTheReason()          // mapper → UNAVAILABLE for NOT_FOUND; ErrorInfo.reason still NOT_FOUND
// SynapseRestOpenApiAutoConfigurationTest
@Test void theDocumentUsesTheUserMapper()                // mapper → 410 for NOT_FOUND → the route documents 410, not 404
```

- [ ] **Step 2: Run to verify failure** (compile errors first). Commands as in *Global Constraints*, per module.
- [ ] **Step 3: Implement.** Existing override and category tests on `RestErrorStatuses` and `GrpcErrorStatuses` stay green unchanged.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest,api/synapse-api-rest-openapi,api/synapse-api-grpc,api/synapse-test-rest,api/synapse-test-grpc,samples/grpc-inventory/service-inventory,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`. No README output changes (no sample defines a mapper); say so.
- [ ] **Step 5: Commit**: `feat: status-mapping SPIs for REST and gRPC`.

### Task 4: The inbound call limiter SPI (decision 26)

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/limit/{InboundCallLimiter,package-info}.java`; `.../core/internal/limit/{SemaphoreCallLimiter,CallLimiterNameCheck,package-info}.java`.
- Modify:
  - core: `autoconfigure/SynapseOperationAutoConfiguration.java` (or the core auto-configuration that holds startup checks): bean `synapseCallLimiterNameCheck`, by name;
  - REST: `internal/edge/ConcurrencyLimitFilter.java` (takes an `InboundCallLimiter`; the permit holder releases once with A5's outcome); `autoconfigure/SynapseRestAutoConfiguration.java` (bean `synapseRestCallLimiter`, `@ConditionalOnMissingBean(name = "synapseRestCallLimiter")`, a `SemaphoreCallLimiter` of `max-concurrent-requests`; the filter bean injects `@Qualifier("synapseRestCallLimiter")`);
  - gRPC: `internal/edge/SynapseGrpcContextInterceptor.java` (~L160-245: the limiter and outcomes); delete `internal/edge/GrpcConcurrencyLimit.java`, moving `isUncounted` into the interceptor or a small `GrpcUncountedServices` helper; `autoconfigure/SynapseGrpcAutoConfiguration.java` (`synapseGrpcCallLimiter` by name, from `max-concurrent-calls`);
  - docs: `core.adoc` (the SPI), `rest.adoc` and `grpc.adoc` (*Concurrency*: the bean names, that a replacement ignores the limit property, the outcome table, what is never counted), BUILD §5.9 already says this.
- Test: create `core/.../internal/limit/{SemaphoreCallLimiterTest,CallLimiterNameCheckTest}.java`; modify `ConcurrencyLimitFilterTest`, `SynapseGrpcContextInterceptorTest`, `SynapseRestAutoConfigurationTest`, `SynapseGrpcAutoConfigurationTest`; delete `GrpcConcurrencyLimitTest` (its cases move to `SemaphoreCallLimiterTest`).

**Interfaces:**
- Produces:

```java
package io.americanexpress.synapse.core.limit;
/** Admits or rejects an inbound call. Each plugin has its own bean, named synapse<Protocol>CallLimiter. */
@Incubating @FunctionalInterface
public interface InboundCallLimiter {
    /** @return a permit, or empty to reject the call; never waits */
    Optional<Permit> tryAcquire();
    /** Released exactly once by the edge. */
    @Incubating interface Permit { void release(Outcome outcome); }
    /** As Netflix concurrency-limits' Listener: onSuccess, onIgnore, onDropped. */
    enum Outcome { SUCCESS, IGNORED, DROPPED }
}
// core.internal.limit
public final class SemaphoreCallLimiter implements InboundCallLimiter {
    public SemaphoreCallLimiter(int maxConcurrent);   // < 1 → IllegalArgumentException; a second release of one permit is a no-op
}
public final class CallLimiterNameCheck implements SmartInitializingSingleton { public CallLimiterNameCheck(ListableBeanFactory beanFactory); }
```

- `CallLimiterNameCheck` WARNs once per `InboundCallLimiter` bean whose name doesn't match `synapse[A-Z][A-Za-z]*CallLimiter`: `The InboundCallLimiter bean '<name>' is not used: Synapse's plugins use the beans named synapseRestCallLimiter and synapseGrpcCallLimiter. Name your bean after the plugin it limits.`

- [ ] **Step 1: Write the failing tests** (Review Focus 3, 4):

```java
// SemaphoreCallLimiterTest
@Test void admitsUpToTheLimitThenRejects()
@Test void aReleasedPermitIsReusable()
@Test void aSecondReleaseIsANoOp()
@Test void aLimitBelowOneFails()
// ConcurrencyLimitFilterTest (a recording limiter)
@Test void aReturnedChainIsSuccess()
@Test void a504IsDropped()
@Test void aThrowingChainIsIgnored()
@Test void asyncTimeoutReleasesOnceDropped()          // onTimeout then onComplete → one release, DROPPED
@Test void asyncErrorThenCompleteReleasesOnce()       // IGNORED
@Test void aRejectionIs503WithRetryAfter()            // empty Optional → 503, Retry-After: 1 (unchanged)
@Test void managementPathsNeverAcquire()
// SynapseGrpcContextInterceptorTest
@Test void anOkCloseIsSuccess()
@Test void aDeadlineExceededCloseIsDropped()
@Test void aCancelIsDropped()
@Test void aThrowBeforeCloseIsIgnored()
@Test void healthAndReflectionNeverAcquire()
@Test void aRejectionIsUnavailableWithRetryInfo()
// auto-configuration tests
@Test void eachPluginHasItsOwnLimiter()                // both plugins: synapseRestCallLimiter and synapseGrpcCallLimiter, distinct
@Test void replacingTheRestLimiterKeepsTheGrpcOne()
@Test void theLimitPropertySizesTheDefault()           // max-concurrent-requests=2 → third concurrent acquire is empty
@Test void aLimiterWithAnotherNameWarns()              // CallLimiterNameCheckTest
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Rejection responses, `Retry-After` and `RetryInfo` stay byte-identical.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-grpc,samples/rest-books,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`. No README shows a rejection; say so.
- [ ] **Step 5: Commit**: `feat: an InboundCallLimiter SPI for the REST and gRPC concurrency limits`.

### Task 5: The `@Incubating` rule, and two utilities move to `internal` (decision 32, items 4 and 6)

**Files:**
- Create: `tests/synapse-api-rules-tests/pom.xml` (copy `tests/synapse-data-mixed-tests/pom.xml`'s properties block: never installed or deployed, no source or Javadoc jars, the `argLine` note; test-scope dependencies on every published library module: core, test, logging modules, the API plugins and their test slices, OpenAPI, the data modules, and the observability plan's `synapse-tracing-opentelemetry`; plus ArchUnit); register it in the root `pom.xml` `<modules>` next to the other `tests/` modules; `src/test/java/io/americanexpress/synapse/tests/api/{IncubatingApiTest,package-info}.java`; `core/.../core/internal/error/ErrorMessages.java` (the package and its `package-info.java` come from the observability plan) and `core/.../core/internal/operation/{OperationNames,package-info}.java` (moved with `git mv`).
- Modify:
  - `core/.../core/Incubating.java`: `@Retention(RetentionPolicy.RUNTIME)`; Javadoc: the BUILD 3.5 rule in one sentence;
  - mark `@Incubating`: `data/synapse-data-jdbc/.../SynapseJdbcTransactionManager.java`, `data/synapse-data-jpa/.../SynapseJpaTransactionManager.java`, `data/synapse-data-core/.../CommitOutcomeUnknownException.java`; then every public type the contract, data and observability plans added whose DESIGN row says provisional (grep DESIGN.md for "provisional" in rows dated 2026-11-20 or later, and list what you marked in the report);
  - every importer of the two moved classes (`ProblemWriter`, `GrpcStatusWriter`, `SynapseOperationAutoConfiguration`, `SynapseRestErrorAutoConfiguration`, `SynapseGrpcErrorAutoConfiguration`, `RouteDefinition`, `RouteDocumenter`, `GrpcMethodSpec`, `MethodDeclaration`, `ServiceCompiler`, `OperationDescriptor`, `OperationProxyVerifier`, and their tests; re-grep);
  - docs: `data.adoc` (the two managers and `CommitOutcomeUnknownException` are incubating, as `rest.adoc` says of `CursorCodec`), `core.adoc` (where `@Incubating` is described, or a short *API stability* note: incubating API may change in a minor);
  - `specs/v2/BUILD.md` §3.5: the japicmp probe's result (Step 5);
  - the `tests/` rows of `AGENTS.md` and `README.md` (*Repository layout*): add `synapse-api-rules-tests` (every auto-configuration's back-off and the `@Incubating` marks; never published). BUILD §2's *Not published* line already names it.
- Test: `IncubatingApiTest`; the moved classes' tests move with them (`git mv`).

**Interfaces:**
- Produces: `io.americanexpress.synapse.core.internal.error.ErrorMessages` and `io.americanexpress.synapse.core.internal.operation.OperationNames`, same members as today. The `errorMessages` bean's back-off is converted in Task 6.

- [ ] **Step 1: Write the failing tests:**

```java
// IncubatingApiTest
@Test void incubatingIsVisibleAtRuntime()     // Incubating.class retention RUNTIME
@ParameterizedTest @ValueSource(classes = {SynapseJdbcTransactionManager.class, SynapseJpaTransactionManager.class,
        CommitOutcomeUnknownException.class, RestErrorStatusMapper.class, GrpcErrorStatusMapper.class,
        InboundCallLimiter.class, CursorCodec.class, EntityIdGenerator.class, DataStoreVendor.class})
void provisionalApiIsIncubating(Class<?> type)  // type.isAnnotationPresent(Incubating.class); add the types the earlier plans marked
@Test void theUtilitiesAreInternal()            // ArchUnit: no class named ErrorMessages or OperationNames outside ..internal..
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl tests/synapse-api-rules-tests -am test -Dprotobuf…=…`. Expected: every case fails (CLASS retention makes `isAnnotationPresent` false).
- [ ] **Step 3: Implement**, then grep `docs/`, `samples/` and `archetypes/` for `core.error.ErrorMessages` and `core.operation.OperationNames`; there should be none.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-rest-openapi,api/synapse-api-grpc,data/synapse-data-core,data/synapse-data-jdbc,data/synapse-data-jpa,tests/synapse-api-rules-tests -am verify -Dprotobuf…=…`.
- [ ] **Step 5: japicmp probe** (in the probe directory, not the build): read the japicmp release notes, pick the current release and cite the URL. Build two tiny jars that differ by a removed public method on a class annotated `@io.americanexpress.synapse.core.Incubating` (RUNTIME), run japicmp's standalone jar with `--exclude "@io.americanexpress.synapse.core.Incubating"` and `--error-on-binary-incompatibility`, and once more with a CLASS-retention copy of the annotation. Record both outcomes, the version and the command in BUILD §3.5 (one sentence), so the P6 gate starts from a fact.
- [ ] **Step 6: Commit**: `feat(core)!: incubating API is marked at run time, and two internal utilities move to internal`.

### Task 6: Every internal bean backs off by name (decision 26, BUILD 3.5)

Read the earlier follow-up plans' auto-configurations first: their beans are in scope.

**Files:**
- Create: `tests/synapse-api-rules-tests/src/test/java/io/americanexpress/synapse/tests/api/BackOffRulesTest.java`.
- Modify: every `*AutoConfiguration.java` whose bean the test reports (today about 34: core's `CorrelationIdPolicy`, `ContextBinder`, `InputDigest`, `PageCursors`, `InflightTracker`, `OperationDrainLifecycle`, `ConstraintViolationTranslator`, `OperationPipeline`, `ErrorMessages`; REST's `DeadlinePolicy`, `RouteCompiler`, `BindingValidator`, `PagingSettings`, `RequestBinder`, `ResponseWriter`, `ETagValues`, `ExposureInventory`, `ProblemTitles`, `ProblemWriter`; gRPC's `GrpcDeadlinePolicy`, `ServiceCompiler`, `GrpcBindingsCompiler`, `ExposureInventory`, `GrpcStatusWriter`, `SynapseGrpcExceptionHandler`; data's `StoreFactory`, `FlywayStoreFactory`, `JpaStoreFactory`, `JdbcStoreFactory`, `AuditStamps`, `DataStartupReport`; plus whatever the earlier plans added). Each becomes `@ConditionalOnMissingBean(name = "<synapse… name>")`, renamed per A8, with the Javadoc line "Internal: backs off by name only." instead of "Define a bean of this type to replace it". Injection points that relied on the old names (`@Qualifier`) follow.
- Docs: `rest.adoc` (~L1308-1309) and `grpc.adoc` (~L859-862): replace "The other beans (…) back off when you define your own" with the public extension points (status mapper, call limiter, `CursorCodec`, `OperationObservationConvention`, Spring's handler interfaces, and the named edge filters the docs already describe) and the sentence "Synapse's other beans are internal: they back off only by name, and their types may change in any release." Grep `docs/` for "back off when you define", "replace it" and each old bean name, and fix every hit the same way.
- Tests that use the old names: grep `src/test` for each old name in quotes and update.

**Interfaces:**
- `BackOffRulesTest` imports `io.americanexpress.synapse..` (production classes only) with ArchUnit, and checks every method annotated `@Bean` in a class annotated `@AutoConfiguration`:
  - a **type back-off** is one with neither `name` nor `type`/`value` set (the type is the raw return type) or with `type`/`value` set (those types). Each such type must be a public extension point, which the test tells apart from an internal or generic type this way:
    - a Synapse type outside `..internal..` (`io.americanexpress.synapse..`) qualifies without listing;
    - a third-party type qualifies only if it is on the test's `PUBLIC_THIRD_PARTY_TYPES` allow-list, each entry with a one-line reason: today `java.time.Clock` (the application's one clock, as Boot's own beans read it), `io.opentelemetry.api.OpenTelemetry` (the observability plan's agent bean; Boot's `OpenTelemetrySdkAutoConfiguration` backs off on the same type, so a user's bean wins with or without the agent), `org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider` (the executor Boot's gRPC server takes; confirm how Boot 4.1.2's gRPC server auto-configuration reads it and write that as the reason) and `org.springframework.core.task.support.ContextPropagatingTaskDecorator` (Boot's task executor builders take a unique `TaskDecorator`; confirm in Boot 4.1.2 and write that as the reason);
    - anything else fails, an `..internal..` type and a generic infrastructure type (`SmartInitializingSingleton`, `FilterRegistrationBean`, `BeanPostProcessor`) alike, since a type back-off on those lets any unrelated user bean switch Synapse's off. A third-party type found at execution that isn't listed is either converted to a name back-off or added with its reason, and the report says which;
  - if it sets `name`, each name must start with `synapse`, unless the name is a Boot-convention name the docs document as replaceable (today `corsConfigurationSource` and `dbHealthContributor`; listed in the test with a comment each).
  The failure lists every violation as `<AutoConfiguration>#<method>: <rule>`.

- The conversion lands as **four commits by module group**, each building green on its own: **core** (`synapse-core`, `synapse-test`, the logging modules and the observability plan's `synapse-tracing-opentelemetry`), **REST** (`synapse-api-rest`, `synapse-api-rest-openapi`, `synapse-test-rest`), **gRPC** (`synapse-api-grpc`, `synapse-test-grpc`) and **data** (the `data/` modules). `BackOffRulesTest` checks the groups listed in its `GROUPS` constant (a list of package prefixes); each commit adds its group, and the last one lists all four.

- [ ] **Step 1: Write the failing test** `BackOffRulesTest#internalBeansBackOffByName` and `#internalBeanNamesStartWithSynapse` and `#thirdPartyTypeBackOffsAreListed` (a fixture auto-configuration with a bare back-off on `SmartInitializingSingleton` fails it; one on `Clock` passes), with `GROUPS` listing all four groups.
- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl tests/synapse-api-rules-tests -am test -Dprotobuf…=…`. Expected: about 34 violations; paste the list into the task report. Then reduce `GROUPS` to the core group for the first commit.
- [ ] **Step 3: Convert** each reported bean, one group at a time (core, REST, gRPC, data), with its auto-configuration test: replace the type back-off test with `backsOffByName` (a user bean with the new name wins and Synapse's is absent) and add `aUserBeanOfTheInternalTypeUnderAnotherNameFailsStartup` once per module (two beans of the internal type: the context fails with `NoUniqueBeanDefinitionException` at the first injection by type; the docs say nothing about it, since the type is internal).
- [ ] **Step 4: For each group:** run `./mvnw -pl <the group's modules>,<the samples that use them>,tests/synapse-api-rules-tests -am verify -Dprotobuf…=…` (Docker on) with the group added to `GROUPS`, fix the docs and tests of that group's beans, and commit: `refactor(core)!: core's internal beans back off by name only`, then `refactor(rest)!: …`, `refactor(grpc)!: …`, `refactor(data)!: …`.
- [ ] **Step 5: After the last group:** the full `./mvnw verify -Dprotobuf…=…` (Docker on), and a grep for "Define a bean of this type to replace it" under `src/main`: only public extension points may still say it. A fix here goes into its own commit.

### Task 7: Only the quickstart archetype is published (decision 20)

**Files:**
- Modify:
  - `archetypes/synapse-archetype-service/pom.xml`: `<maven.deploy.skip>true</maven.deploy.skip>` with the comment `Not published until P6 (decided 2026-11-20): built, and its IT runs in every build. Frozen: fixes only.`;
  - `.github/scripts/central-publish.sh` (`validate`: a `NOT_PUBLISHED=(synapse-archetype-service)` list; any staged file under one fails with `::error::<artifactId> is not published until P6: <file>`), `.github/scripts/test-central-publish.sh` (the case);
  - `docs/modules/ROOT/pages/archetypes.adoc` (the table and the service section: not published in milestones; build it from source with `./mvnw -pl archetypes/synapse-archetype-service -am install -DskipTests`, then generate with `-DarchetypeVersion=<the version built>`), `README.md` (~L21-25), `AGENTS.md` and `README.md` layout rows (`archetypes/`), `archetypes/javadoc-placeholder/README.md`.
- Test: `test-central-publish.sh`; the service archetype IT (must still run).

- [ ] **Step 1: Write the failing test** in `test-central-publish.sh`: a staging directory with `io/americanexpress/synapse/synapse-archetype-service/<v>/synapse-archetype-service-<v>.jar` beside a valid artifact → `validate` exits non-zero naming the file; the same directory without it passes.
- [ ] **Step 2: Run to verify failure:** `bash .github/scripts/test-central-publish.sh`.
- [ ] **Step 3: Implement** the list and the pom property.
- [ ] **Step 4: Run to verify pass:** the script test; then `./mvnw -pl archetypes/synapse-archetype-service -am verify -Dprotobuf…=…` (the IT runs), and `./mvnw -q -pl archetypes/synapse-archetype-service help:evaluate -Dexpression=maven.deploy.skip -DforceStdout` prints `true`. Also run the staging deploy the release uses, into a probe directory, without the release profile's signing: `./mvnw -pl archetypes/synapse-archetype-quickstart,archetypes/synapse-archetype-service -am deploy -DskipTests -Dmaven.repo.local=<probe>/m2 -DaltDeploymentRepository=staging::file:<probe>/staging -Dprotobuf…=…` (`deploy` runs `install` first, so the local repository must be the probe's; delete `<probe>/m2` afterwards) (this one `-DskipTests` run checks staging only and is not a build pass), and confirm the staging directory has the quickstart and no service archetype.
- [ ] **Step 5: Commit**: `build(archetypes)!: publish only the quickstart archetype until P6`.

### Task 8: Plan-5 Minors M2, M3 and M5 (decision 34)

**Files:**
- Modify:
  - M2: `archetypes/synapse-archetype-service/src/main/resources/archetype-resources/service-__serviceName__/src/test/java/scenario/ItemScenarios.java:136`: drop "It is the smoke test"; describe it as the one fast check that both APIs serve the store (and the matching bookstore file if `archetype-drift` compares it).
  - M3: `README.md` (~L23-25): `./mvnw install -DskipTests` (no Docker; the measured minutes from Step 2), `-DarchetypeVersion=2.0.0-SNAPSHOT`, and that it installs the service archetype too, which isn't published (Task 7).
  - M5: `AGENTS.md:59` (now "Every module of the root build that has integration tests tags at least one of them `@Tag("smoke")`… The samples inherit `synapse-parent` and have no smoke filter."); remove `@Tag("smoke")` (and an unused `Tag` import) from the 8 sample files (`grep -rln '@Tag("smoke")' samples`); `samples/rest-books/README.md` (~L961-962: drop "tagged `smoke`").
- Test: none new (wording). The proof is Step 2's run and the build.

- [ ] **Step 1: Edit** the three items.
- [ ] **Step 2: Run M3's route** (A13): `time ./mvnw install -DskipTests -Dmaven.repo.local="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/probe/api-surface/m2" -Dprotobuf…=…`; then, in an empty probe directory, `archetype:generate` for each archetype with `-DarchetypeVersion=2.0.0-SNAPSHOT` and the same `maven.repo.local`, and `./mvnw verify` in the generated quickstart (Docker not needed). Put the measured minutes in the README. Delete the `m2` directory afterwards.
- [ ] **Step 3: Run:** `./mvnw -pl samples/rest-books,samples/grpc-inventory/service-inventory,samples/multi-store/service-orders,samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-service,tests/synapse-build-rules-tests -am verify -Dprotobuf…=…`, Docker on. The JDK 21 leg's exclude list (`.config/jdk21-pr-excluded-its.txt`) is unaffected: say so.
- [ ] **Step 4: Commit**: `docs: correct the smoke-tag rule, the install route and a scenario comment`.

### Task 9: A non-default host port and one database name (decision 34, B-M3)

**Files:**
- Modify:
  - `samples/reference-bookstore/compose.yaml` (`"15432:5432"`, `POSTGRES_DB: books`; a comment that the host port avoids a local PostgreSQL on 5432), `.../data-books/src/main/resources/META-INF/synapse/config-local.yaml` (`jdbc:postgresql://localhost:15432/books`), `samples/reference-bookstore/README.md` (every `5432` that is the host port, `/bookstore` → `/books`, the layout lines ~L47 and ~L65, the env-var example ~L888); `scripts/image-smoke.sh` keeps the container's 5432 (check it reaches the DB by container name);
  - the service archetype's `compose.yaml` (`"15432:5432"`), `data-__serviceName__/.../config-local.yaml` (`localhost:15432`), `README.md` (~L48, ~L60, ~L137); check its `config-dev.yaml`/`config-build.yaml` already name the database `${serviceName}`;
  - `tests/synapse-build-rules-tests/src/it/archetype-drift/verify.groovy` only if it compares these files and needs a substitution.
- Test: the bookstore's and the archetype's ITs use Testcontainers (random ports) and must stay green.

- [ ] **Step 1: Edit.** Grep both trees for `5432` and `/bookstore` and classify each hit (host port, container port, database name) in the report.
- [ ] **Step 2: Re-run the bookstore README** from a clean state (`docker compose down -v`, `docker compose up -d`, the build, `ENV=local java -jar …`), and paste the changed output lines (the `Database: jdbc:postgresql://localhost:15432/books` line and any other). Re-run the archetype README's local run in the IT's generated project (`target/test-classes/projects/s/project/…`) the same way and paste its changed line.
- [ ] **Step 3: Run:** `./mvnw -pl samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-service,tests/synapse-build-rules-tests -am verify -Dprotobuf…=…`, Docker on.
- [ ] **Step 4: Commit**: `fix(samples): map local PostgreSQL to host port 15432 and name the bookstore's database books`.

### Task 10: Doc-only items and spec status

**Files:**
- Modify:
  - **32, Timestamp (A12):** `rest.adoc` after the version-type table (~L618): "JPA also allows `java.sql.Timestamp` for `@Version`; it isn't a version type here, so use `Instant` or `LocalDateTime`. A `UUID` version goes in a `String`." `data.adoc` *optimistic locking* (~L643): one sentence pointing there.
  - **32, hyphens and long paths:** the service archetype's `README.md` (~L108-113) and `archetypes.adoc` (~L126-128): keep the numbers, and replace "enable long paths for `-Paot`" / "only long paths make a build practical" with: keep the directory short, or try enabling long paths, which is unverified (Windows needs the registry value and a `longPathAware` manifest in every tool the build runs; Microsoft, *Maximum Path Length Limitation*). The hyphen rule is unchanged.
  - **32, ArchUnit:** `testing.adoc` after the `-tests.jar` caveat (~L367): "Classes under a `target/test-classes/` path are skipped. For another layout, import the classes yourself, with your own `ImportOption`, and call `check(classes)`."
  - **AOT 29-31:** `parent.adoc` *aot-cloud-platform* (~L90-118): add that spring-cloud-kubernetes 5.0.x doesn't support Spring AOT or native images (quote its reference index and link it), that `none` removes its beans from the AOT build, so the flag is for a service that has it on the class path but doesn't need it under AOT, and otherwise don't use `-Paot` with it. `environments.adoc` *AOT and native images* (~L413): quote Boot's how-to *Conditions* ("The beans which are created while ahead-of-time processing at build time are then always created when running the application and can't be switched off" and "Profiles which only change configuration properties that don't influence conditions are supported without limitations"), then: a `@Profile("kafka")` bean exists in every AOT run when the build environment's group has `kafka`, and in none when it doesn't, whatever the deployed environment's group says. Stale AOT code: check that `parent.adoc` (~L44-46), the bookstore README and the Dockerfiles still say `clean`; change nothing if they do.
  - `specs/v2/BUILD.md` §15 P1: a *Follow-ups: API surface (plan 2026-11-20)* note listing what landed (by decision number), this plan's decisions table IDs, and what only CI or a later run can verify (the release workflow's staging with the real profile; the japicmp gate itself, P6).
  - `specs/v2/DESIGN.md`: nothing left open by this plan; if an execution finding needs the maintainer, add a §6 row with the question and a lean.
- [ ] **Step 1: Write the docs.** Every property named exists in the configuration metadata.
- [ ] **Step 2: Build the docs** from the repository root: `(cd docs && npm ci && npx antora antora-playbook.yml)`, then `node docs/tools/property-tables.mjs`. Expected: no broken xref, no property row removed.
- [ ] **Step 3: Commit**: `docs: version types, long paths, ArchUnit layouts and AOT notes; record the API-surface follow-ups`.

### Task 11: Full build

- [ ] **Step 1: JDK 25, as CI runs it** (background, Docker on; about 20 minutes):

  ```bash
  CI=true ./mvnw clean verify -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

  Expected: BUILD SUCCESS, including `tests/synapse-api-rules-tests`, the invoker build-rules tests and both archetype ITs.
- [ ] **Step 2: The JDK 21 pull-request leg:**

  ```bash
  ./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke -Djacoco.skip=true \
    -Dfailsafe.excludesFile="$(pwd -W)/.config/jdk21-pr-excluded-its.txt" \
    -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

- [ ] **Step 3: Report** the test counts per module for both runs next to the baseline's, and explain every count that went down (Task 4 deletes `GrpcConcurrencyLimitTest`; its cases move).
- [ ] **Step 4:** No commit unless a fix was needed: a fix is its own `fix:` commit with a failing test first, and both runs repeat after it.

### Task 12: Final whole-branch review

- [ ] **Step 1:** An Opus review of `<base>..HEAD`, with BUILD.md, DESIGN.md, AGENTS.md, this plan and the four research memos as the authority. Findings ordered by severity, each naming its module and the user-visible effect. It checks especially:
  - parity: no SPI can change a code or a body, and the OpenAPI document reads the same mapper as the edge;
  - that `BackOffRulesTest` covers every auto-configuration, the earlier plans' included, and no doc still calls an internal bean replaceable;
  - that no behaviour change landed without a failing test first, and the decisions table matches what was built.
- [ ] **Step 2: Fix** each accepted finding in its own commit, with a failing test where behaviour changes. A fix gets a Sonnet re-review; repeat Task 11 after the last fix.
- [ ] **Step 3: Report** to the maintainer: the commits, the decisions table (A1-A17) for confirmation, the japicmp probe's result, and anything under *Not in this plan*. Squash into local `main` and push only on the maintainer's word.
- [ ] **Step 4: CI dry run (decision 12, second run).** After all four plans are squashed into local `main`, and only on Tony's go, push a throwaway `ci/dry-run-N` branch to the fork. It holds one squashed commit of `main`'s tree plus a commit adding `ci/**` push triggers to `ci.yml` and `nightly.yml`, with nightly's report-failure job disabled. Watch both workflows, and report every failure. Never push without that go.

---

## Carried over from the contract follow-up (to decide)

The contract follow-up's final review (2026-11-22) deferred two gRPC items here. This plan's author decides, for each,
whether it becomes a task or moves to *Not in this plan*.

| # | Item | Today | Cost |
|---|---|---|---|
| C1 | The startup check for a `@Nullable` item type (gRPC, and OpenAPI's nullable items) sees record components only. | An operation whose output is itself `List<@Nullable T>` or `PageResult<@Nullable T>` isn't caught at startup: its type is resolved through `ResolvableType`, which has no type-use annotations. A `null` item fails the call at runtime as `INTERNAL` (`holds a null element`), and OpenAPI documents the items non-null. | `List`: about 30 lines and a test, reading the operation class's annotated interfaces and generic superclasses up to `Operation`'s output argument (Spring has no helper). `PageResult<@Nullable T>` also needs the type argument's annotations carried through the type variable, which is read by its own annotations today. |
| C2 | Allow `List<@Nullable SomeEnum>` over gRPC. | Startup fails for every `@Nullable` item type of a repeated or map field. An enum whose zero value is unspecified could carry the `null` per element, as it already does for a single `@Nullable` enum. | Small: exempt such enum items from the check and read or write the zero value as `null` per element, with tests. It widens what gRPC accepts, so it needs the maintainer's yes. |

---

## Not in this plan

| Item | Where |
|---|---|
| M8: REST names `offset` literally in the cursor-with-offset violation | The contract follow-up plan. |
| M1: the ArchUnit public-API gate; M4: the wider drift check | P6 (BUILD §15, P6 notes). `tests/synapse-api-rules-tests` is the natural home for M1. |
| M6 (smoke-gate leniencies), M7 (paging texts in three places), M9 (the `postgres-image-pin` regex) | P6 (A16). |
| B-M6: a sample that shows masking and payload logging | After the masking follow-up settles what masking promises (A16). |
| Decision 21: the license header in generated files | No change (DESIGN 4.1 *License header in generated files*); revisit with the Amex OSPO before a published release carries a grant. |
| Decision 27 (scope review) and 28 (Liquibase) | Their own branches. |
| I4 and Q6 | Before GA (BUILD §15, P6 notes); I4 with the Boot 4.2 upgrade, which is on hold. |
| `server.forward-headers-strategy` in `parent.adoc` | At the Boot 4.2 upgrade (BUILD §13). |
| Public ordering constants (A10) | P6 API pass. |
