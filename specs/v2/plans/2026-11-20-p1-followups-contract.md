# P1 follow-ups, contract and parity: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the maintainer's 2026-11-19/20 decisions on the REST/gRPC contract and error handling: decisions 3, 5, 6, 9, 13, 16 with the parity fixes, 24, the required-scalar, offset-cap and update-response questions, the docs-only items of decision 32, and the M8 bug of decision 34.

**Architecture:**
- Core first (Tasks 1–3): the pipeline's rejection carries its call, parity pairs by major version, and core writes one violation path format that gRPC reads.
- Then REST binding (Tasks 4–7): wire names, empty collections, required scalars over gRPC (it changes the sample protos, so it sits before the REST-only cookie change), the cookie rule and counter.
- Then the error edges (Tasks 8–10): Spring MVC's handlers first with Synapse's answer on Synapse paths (Task 8), the kept Spring statuses (Task 9), and a hand-written gRPC service's status (Task 10).
- Then paging (Task 11), the docs-only items and spec status (Task 12), the full build (Task 13) and the final review (Task 14).
- Every behaviour change starts with a failing test (AGENTS.md *Bug fixes*). The docs that describe a change are in the same task. Each task is its own commit or commits.

**Tech Stack:** Java 21 (`--release 21`, built on JDK 25), Maven 3.9.16 through the wrapper, Spring Boot 4.1.2 (unchanged; the 4.2 upgrade is on hold), Spring Framework 7.0.10, Spring gRPC 1.1.2, grpc-java 1.84.1, Jackson 3.1.7, Micrometer (Boot-managed), JUnit 5, AssertJ. One dependency change: `org.springframework.security:spring-security-web` in **test** scope of `synapse-api-rest` (Boot-managed, no version; Task 8), as `synapse-api-grpc` already has `spring-security-config` in test scope.

**Spec:**
- Decisions (settled, don't reopen): the decision log lines "- 2026-11-19 Tony" and "- 2026-11-20 Tony" in `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\p5\.superpowers\sdd\2026-11-13-p1-review-fixes\progress.md`, and `research2-summary.md` sections C and D.
- Memos, binding where they settle a design detail, in `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\00343b11-a124-44cb-94d4-0764a5fe68bf\scratchpad\`: `research2-contract-errors.md` (main), `research-duplicate-cookies.md`, `research2-observability.md` (decision 6 only), `research2-33-34.md` (D12, Q3, M8), `research2-plan5-leftovers.md` (items 2, 3 and 5).
- The authority on behaviour: [BUILD.md](../BUILD.md) §5.2, §6.1, §6.2 (*Input binding*, *Paging safety*, *Errors from Spring MVC*), §6.3 and P2, as this plan's docs commit wrote them. The reasons: [DESIGN.md](../DESIGN.md) §4.1, the rows after *Protocol parity* (decided 2026-11-19/20). Where this plan and a memo differ, this plan wins, and the decisions table says why.

## Execution base

- After Tony's review, the four follow-up plans' docs commits are merged into local `main` as one docs commit, with the DESIGN.md conflicts resolved once (both sides' additions and both sides' deletions kept). Each plan's feature branch starts from local `main` at that commit, or later, once the earlier plans in the order contract, data, observability, API surface are squashed.
- The merged docs commit also applies the security-deferral edits of the scope proposal (BUILD §2 and §15: `synapse-security` leaves P4 for 2.x), separately from the rest of that cut, so BUILD doesn't list the module in P4 while this plan's BUILD §11 note calls it deferred (review n5). That is the coordinator's merge step, not a task here.
- This plan is first in that order: `feature/v2-fu-contract` starts from local `main` at the merged docs commit, in a clone of its own (for example `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\q1`).
- [ ] **Baseline:** before Task 1, run Task 13 Step 1's command once and record the test counts per module and any failure. A failure that exists at the base is reported to the maintainer, not fixed silently.

## Coordination with the other follow-up plans

- **Data** owns `TRANSACTION_CONFLICT` end to end, its REST and gRPC status included. This plan adds no data code.
- **Observability** owns decision 10 (masking hardening), which includes map keys in *logged* paths (`SanitizedViolations`). Task 3 adds `ViolationPaths.of(Path)` and uses it for the paths callers see. The observability plan then calls the same `ViolationPaths.of` for the logged path, so the log shows the path the caller gets (one format, no `[*]` variant), and it may widen the access of `ViolationPaths` and of Task 1's call token when it moves classes. Don't edit `SanitizedViolations` here beyond Task 1's token field.
- **API surface** adds a status-mapping SPI and converts type back-offs to name back-offs, after this plan. This plan prepares for it:
  - the status of an error is decided in one method per plugin, `RestErrorStatuses.statusFor(SynapseException, @Nullable String operation)` (new, Task 9) and `GrpcErrorStatuses.statusFor(SynapseException, @Nullable String)` (exists). Both are the seam: the REST status-mapper SPI takes the `SynapseException` and the operation, as gRPC's does, and its default implementation keeps this plan's order: a configured override for the code (per operation, then global), then the kept Spring status (Task 9), then the built-in and category status. So `synapse.api.rest.errors.status.[CLIENT_ERROR]=400` applies;
  - the REST resolver is no longer a bean (Task 8), so for REST the "handler SPI" question is answered by Spring's own `@ControllerAdvice`;
  - beans this plan adds, all backing off **by name**: `synapseRestExceptionResolverConfigurer` (Task 8), `synapseRestCookieDuplicates` (Task 7);
  - beans this plan changes that still back off **by type** (the API-surface plan converts them): `restErrorStatuses`, `problemWriter`, `problemTitles`, `requestBinder` (REST); `grpcErrorStatuses`, `grpcStatusWriter` and the context interceptor bean (gRPC; read their exact names in `SynapseGrpcErrorAutoConfiguration` and `SynapseGrpcAutoConfiguration`).

## Global Constraints

**Branch, JDK, language**
- Never push. Never touch `C:\Users\Tony\VSCode Projects\amex-synapse`. Local `main` gets the squash only when the maintainer says so.
- Before any `./mvnw`: `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"`. JDK 21 legs: `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never a global `mvn`.
- Every build that reaches `api/synapse-api-grpc`, `api/synapse-test-grpc`, a gRPC sample or the service archetype adds `-Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"` (written `-Dprotobuf…=…` below).
- Never `./mvnw install`. Samples run with `-am package` and `java -jar`, as their READMEs do.
- `--release 21`: no Java 22+ APIs (no `ScopedValue`). Records for data carriers.
- Every new package has an `@NullMarked` `package-info.java`; a test package with a main package's name has none. (This plan adds no package.)
- Spotless formats locally; never hand-format. The license header goes on every new Java file.
- Don't edit a pom or `.vscode/` while a Maven build runs.
- `LongPathsEnabled` is 0: keep new paths short.

**Windows**
- Steps that run `docker` first run `export PATH="/c/Users/Tony/AppData/Local/Programs/DockerDesktop/resources/bin:$PATH"; export MSYS_NO_PATHCONV=1`. Testcontainers needs none of it.

**Spring and Synapse rules (AGENTS.md)**
- Spring Boot first: never re-implement or alias a Boot property. Synapse never turns `spring.mvc.problemdetails.enabled` off (Task 8 only WARNs).
- Every auto-configuration a task touches gets `ApplicationContextRunner` tests: it activates, backs off (by name), binds its properties, and stays absent when its condition isn't met.
- A new internal bean backs off by bean name (`@ConditionalOnMissingBean(name = …)`), and the docs don't call it replaceable (BUILD §3.5, T10).
- No production concept; the environment is never in a `@Conditional`.
- Errors never expose stack traces, class names or internal messages. A reason or description reaches the caller only under `synapse.errors.include-detail`.
- Layering: HTTP statuses and gRPC codes live only in the API plugins; core's path format is protocol-neutral.

**Protocol parity (maintainer, 2026-11-07, as refined on 2026-11-20)**
- REST's problem `code` equals gRPC's `ErrorInfo.reason`. Violation paths have one format (BUILD §6.1, *Violation names*) and each protocol's own names.
- Parity is compared per major version (Task 2). Absent collections bind empty on both protocols (Task 5); unsent required scalars are `must not be null` on both (Task 6).

**Samples and archetypes**
- A task that changes a sample, a proto or an archetype template re-runs the affected README commands against the running app, from a clean state (`docker compose down -v` first where there is one), and pastes real output where it changed. If no command's output can change, the task report says why.
- `tests/synapse-build-rules-tests/src/it/archetype-drift` stays green: a compared file changes on both sides in one commit.

**Tests and builds**
- `*Test` (surefire) and `*IT` (failsafe) are never skipped; `-DskipTests` is never a passing build. JaCoCo floors 80% line / 70% branch per module; 90% of changed lines covered.
- To iterate on one test (neither counts as passing):

  ```bash
  ./mvnw -q -pl <module> -am test -Dtest=Class -Dsurefire.failIfNoSpecifiedTests=false
  ./mvnw -q -pl <module> -am verify -Dit.test=ClassIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
  ```

  Each task ends with `./mvnw -pl <its modules> -am verify` (plus `-Dprotobuf…` where needed), Docker on.

**Commits**
- Conventional Commits; `!` marks a breaking change. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only. Never `git add -A`, stash, amend or push. Leave no `*.bak` or `*.orig` files.

**Execution** (the maintainer's standing rules): every task is implemented on Opus and reviewed on Opus; Sonnet only for a fix's re-review. Task 14 is an Opus whole-branch review. At most five subagents at once (maintainer, 2026-11-19).

---

## Decisions taken for the maintainer's review

The maintainer's decisions and the memos settle almost everything. Each row is a smaller call this plan made so a task can run; each can be reversed at review.

| # | Item | Question left open | Choice | Reason | Alternative (cost to reverse) |
|---|---|---|---|---|---|
| C1 | 3 | Which calls apply the "raised by another call" rule. | Every call's `translate`, not only the outermost. The outcome at the edge is the same; a middle call's observation also says `INTERNAL`. | One rule, no depth check; matches the same-thread wrap. | Outermost only (one condition). |
| C2 | 3 | Where the call token lives. | `OperationInterceptor` creates `new Object()` per call and passes it to `OperationPipeline.validate(input, call)` and `requirePreconditions(input, call)`, which store it in a `transient` field of the `SanitizedViolations` cause. `BusinessInterceptor` passes a fresh token of its own, so a business rejection that its caller unwraps and rethrows is `INTERNAL`, as BUILD §5.2 says (pre-flight ruling R-2, 2026-11-21; the plan first had `null`, which would let that unwrapped rejection pass as `VALIDATION_FAILED`). | The memo's mechanism; the pipeline builds the rejection, so it stamps it. | The interceptor stamps after catching (one more mutator on the cause). |
| C3 | 5 | How a version is read, and a floor rule. | gRPC: the proto package's last segment matching `^v(\d+)(p\d+)?((alpha|beta)\d*)?$` (AIP-185/191); REST: the major of `.version(...)` by Spring's `SemanticApiVersionParser`. No floor rule: a binding that pairs with no binding of the other protocol (a gRPC `v1` method with only REST `v2` routes) isn't compared, and startup logs one WARN listing every such binding (maintainer, 2026-11-20, review M5). | The decision names no floor; the memo marks it optional. The WARN keeps the unpaired case visible. | Add the floor (one more check and message). |
| C4 | 16 | A map value's sub-path. | Kept, without the key: `authorsByRank.countryCode` (gRPC's documented `authors_by_rank.country_code`). A constraint on a key or a whole value names the map. | Today's documented gRPC behaviour; the memo leaves it open. | Stop at the map field (simpler, less precise). |
| C5 | 16 | REST names for `.body(component)` and for unnameable parts. | A path under a whole-body component starts at the body (`changes.title` → `title`); the component itself keeps its name. A segment Jackson can't name (its own deserializer, a delegating creator) cuts the path there, as gRPC fails closed. | The body is the JSON root; the memo's "deepest resolvable prefix". | Keep the component segment (names something the client never sent). |
| C6 | parity | Collections the empty rule covers. | `Collection` subtypes and `Map`, at the top level from every source, and in nested body records through the binder's own copy of the mapper (Boot's `JsonMapper` bean is untouched). Jackson finds a config override by the property's exact raw type (`ConfigOverrides.findOverride` is a `Map.get`), so the copy registers `Nulls.AS_EMPTY` for each declared raw type the binder supports: `Collection`, `List`, `Set`, `SortedSet`, `NavigableSet`, `Map`, `SortedMap`, `NavigableMap` (review I2). Arrays keep today's rule. A nested `@Nullable` collection also binds empty (only a top-level `@Nullable` one binds `null`). | gRPC maps `repeated` to `List` and `map` to `Map`; one rule for every depth. | Top level only (a nested absent list stays `null` over REST but is `[]` over gRPC). |
| C7 | scalars | What counts as a scalar and what is exempt. | A request component whose leaf field is a non-repeated, non-message field (string, bytes, numbers, bool, enum), primitives included. Presence is `FieldDescriptor.hasPresence()`, or an enum with an unspecified zero (the existing `hasPresence` helper). Exempt: the token fields `etag`/`if_none_match`, the page fields, `.context` components, response mapping. | The existing presence rule; the exempt fields have their own documented empty semantics. | Also exempt enums without an unspecified zero (would keep a silent default). |
| C8 | 6 | Counter details. | Description "Requests that sent a cookie bound to a single-valued component more than once; the first copy was used", base unit `requests`; one increment per request per single-valued cookie component with two or more copies. DEBUG line on `io.americanexpress.synapse.api.rest`: `Request sent cookie {} more than once; the first copy is used`. | The observability memo's text. | — |
| C9 | 13 | Status precedence, codes and logging for a kept status. | A configured status for the code (per operation, then global) wins, then the kept status, then the built-in and category status. The API-surface plan's REST status-mapper SPI takes the `SynapseException` and keeps this order in its default (review I1, maintainer), so `[CLIENT_ERROR]=400` applies. `CLIENT_ERROR` ("The request was rejected", `INVALID_INPUT`, DEBUG) and `SERVER_ERROR` ("The server could not complete the request", `INTERNAL`, WARN without a stack). The container's error dispatch keeps an unknown bare status the same way; 500 maps to `INTERNAL` explicitly. | Overrides keyed by code keep working; one table for resolver and error controller. | Kept status wins over overrides; leave the error dispatch as is. |
| C10 | 13 | Errors raised before a handler is found (`handler == null`) on a Synapse route's path. | Decided by the maintainer (2026-11-20, review): on a Synapse path Synapse answers them before any application advice, so the shape is always Synapse's problem. A second instance of the resolver sits at index 0 of the composite. It answers only when the handler is `null` or a Synapse handler and the request path matches a Synapse route's served path (any method, the `/v<N>` prefix included). Otherwise it returns `null` and the normal order runs. Escape-hatch controllers keep Spring's flow, MVC handlers first. | The maintainer's call: a missing API version or a 405/415 on a Synapse path is part of the Synapse API, so it carries `code` and `correlationId`. | Plain Spring order for `handler == null` (advice answers, possibly in Spring's shape). |
| C11 | 24 | How a status written by Synapse is told apart at `close()`. | Task 10 Step 1 first records which statuses reach `ContextClosingCall.close`. Reading the code at 5ea62ec7: the exception handler, `reject()` and the fallback registry close the outer call, and bound operations throw, so only a hand-written service's `onError`/`onCompleted` and `UNKNOWN` with a cause should reach it. If no Synapse-built status reaches it, `close()` rewrites every non-OK status except `UNKNOWN` with a non-status cause, with no mark (review M4). If one does, the writer records each `Status` it builds on the call's `GrpcCallState` (identity), and `close()` skips those. Never the memo's "Synapse's `ErrorInfo` domain in the trailers". | The default domain `synapse` is shared by every service without `spring.application.name`, and the correlation id travels downstream, so a forwarded Synapse status would look like ours. An in-process mark can't be forged. | The memo's domain check (simpler, weaker). |
| C12 | 24 | The inverse table and logging. | `INVALID_ARGUMENT`, `OUT_OF_RANGE` → `VALIDATION_FAILED` (no violations); `UNAUTHENTICATED` → `UNAUTHENTICATED`; `PERMISSION_DENIED` → `FORBIDDEN`; `NOT_FOUND` → `NOT_FOUND`; `ALREADY_EXISTS` → `ALREADY_EXISTS`; `ABORTED` → `CONFLICT`; `FAILED_PRECONDITION` → `PRECONDITION_FAILED`; `RESOURCE_EXHAUSTED` → `RATE_LIMITED`; `UNAVAILABLE` → `UNAVAILABLE`; `DEADLINE_EXCEEDED` → `DEADLINE_EXCEEDED`; any other → `INTERNAL`. One DEBUG line per rewritten status with its code and original description, on `io.americanexpress.synapse.api.grpc`. A status from `@GrpcAdvice` or Spring Security's handler is left alone. | The memo's "category's code" made concrete; advice is the application's own answer. Hand-written services only, so no parity effect. `grpc.adoc` notes that `FAILED_PRECONDITION` maps to the code a weak or stale etag also uses, and that `VALIDATION_FAILED` here carries no violations (review M7). | Map `INVALID_ARGUMENT` to `MALFORMED_REQUEST`; no log line. |
| C13 | offset cap | Token kind, message and link. | The capped token is the existing offset token (`o:<next>`), past the cap; `unwrap` answers any offset token past the cap with `has reached the offset limit (<cap>); narrow the query or use keyset paging` (also one issued under a higher cap). REST's `Link: next` carries it as the `cursor` parameter. A REST `offset=` parameter past the cap keeps `must be less than or equal to <cap>`. A cursor page that returns no cursor still gets no token. | No new token kind; the client follows the link like any cursor. | A distinct token kind (one more payload); the same message for the `offset` parameter. |

---

## Review Focus

The inputs the decisions imply but a happy-path test wouldn't cover, most likely first. Each is pinned by a test in the task named.

1. **Every gRPC request proto that binds a non-null scalar with a plain proto3 field now fails startup.** The samples, the service archetype and the test fixtures must all be migrated, and the failure must name the component and the field so a user can fix theirs. Pinned in Task 6: `requiredScalarWithoutPresenceFailsNamingTheField`, `nestedRequiredScalarWithoutPresenceFails`, `mappedOverrideIsChecked`, and every sample's suite green.
2. **A rejection that crosses threads.** A nested call run with `CompletableFuture.supplyAsync` (commonPool, no context propagation) or `@Async`, unwrapped and rethrown, must be `INTERNAL` at the edge; an escape-hatch controller doing the same with its own bound input must still get 400; a `VALIDATION_FAILED` that user code builds must pass. Pinned in Task 1: `commonPoolRejectionRethrownIsInternal`, `asyncRejectionRethrownIsInternal`, `controllerCallOnAnotherThreadIsValidationFailed`, `userBuiltValidationFailedPasses`.
3. **A JSON body whose names differ from Java's**, with `SNAKE_CASE`, one `@JsonProperty`, a per-class `@JsonNaming`, a list of records, a scalar list and a map with a key containing dots and brackets: the violation fields are the wire names and no part of the key appears. Pinned in Task 4: `nestedBodyPathUsesJsonNamesPerType`, `jsonPropertyWinsOverTheNamingStrategy`, `mapKeyNeverAppearsInARestPath`, and Task 3: `mapKeyIsNeverWritten`.
4. **A catch-all `@ControllerAdvice(Exception.class)` in an application that also has Synapse routes.** Synapse routes must keep Synapse's problems, also for errors raised before a handler is found on their paths; controllers get the advice; Spring Security's exceptions still reach Security. Pinned in Task 8: `synapseRouteKeepsSynapsesProblemWithACatchAllAdvice`, `preHandlerErrorOnASynapsePathIsSynapsesProblem`, `preHandlerErrorOnAControllerPathGoesToTheAdvice`, `controllerAdviceAnswersFirst`, `accessDeniedPropagatesToSecurity`.
5. **A client that only follows tokens across the offset cap, on either protocol, including a REST token used over gRPC.** It must get a token, then a clear 400/`INVALID_ARGUMENT` naming its own token field; a page at the real end gets no token. Pinned in Task 11: `cappedPageLinksTheTokenAsCursor`, `followingTheCappedLinkIsTheOffsetLimitViolation`, `cappedPageHasANextPageToken`, `restCappedTokenOverGrpcNamesPageToken`, `noTokenAtTheRealEnd`.

---

## File structure

New files (main code):

| Path | Responsibility |
|---|---|
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/ViolationPaths.java` | Task 3: one path format from a Jakarta `Path` |
| `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/WireNames.java` | Task 4: core path → REST wire path, per route |
| `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/CookieDuplicates.java` | Task 7: the DEBUG line and the counter |
| `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/error/SynapseRestExceptionResolverConfigurer.java` | Task 8: inserts the two resolver instances |
| `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/error/SynapsePaths.java` | Task 8: whether a request path is a Synapse route's served path |
| `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/error/KeptStatusException.java` | Task 9: an MVC status Synapse keeps, its headers and reason |

Everything else modifies existing files, listed per task.

---

### Task 1: A rejection raised by another call is `INTERNAL` on any thread (decision 3)

**Files:**
- Modify: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/OperationInterceptor.java` (the call token at ~L130; `translate` ~L276 gains the new first rule), `.../internal/pipeline/OperationPipeline.java` (`validate` ~L235, `requirePreconditions`, `rejected` ~L270), `.../internal/pipeline/SanitizedViolations.java` (only the `transient` token field and its accessors; nothing else, see *Coordination*), `.../internal/pipeline/BusinessInterceptor.java` (passes a fresh token, R-2).
- Docs: `docs/modules/ROOT/pages/core.adoc` ~L62-69 (replace "Outermost is per thread … becomes `INTERNAL`" with the identity rule, BUILD §5.2's wording).
- Test: `core/synapse-core/src/test/java/io/americanexpress/synapse/core/internal/pipeline/{NestedInvocationTest,OperationInterceptorTest,OperationPipelineTest}.java`; `api/synapse-api-rest/src/test/java/io/americanexpress/synapse/api/rest/internal/route/EscapeHatchRoutesIT.java`.

**Interfaces:**
- Produces (package-private, core `internal.pipeline`):

```java
// OperationPipeline
void validate(@Nullable Object input, Object call);              // call: the raising call's token; BusinessInterceptor passes a fresh one (R-2)
void requirePreconditions(@Nullable Object input, Object call);
/** True when the failure is a pipeline rejection (its cause is a SanitizedViolations from rejected(...)) raised by a call other than {@code call}. */
static boolean raisedByAnotherCall(SynapseException failure, Object call);
// SanitizedViolations
private transient @Nullable Object call;   // set once by OperationPipeline.rejected; never serialized
@Nullable Object call();                   // R-4
void markCall(Object call);                // R-4: a second call throws IllegalStateException
```

- `translate(Throwable, @Nullable Object input, Object call)`: first rule, before the `SynapseException` pass-through: `raisedByAnotherCall(synapse, call)` → `new SynapseException(SynapseErrors.INTERNAL, synapse)` (C1). The same-thread wrap at ~L179-184 stays.

- [ ] **Step 1: Write the failing tests** (Review Focus 2):

```java
// NestedInvocationTest (an outer ServiceOperation calls an inner one whose input has @NotBlank name)
@Test void commonPoolRejectionRethrownIsInternal()      // CompletableFuture.supplyAsync(() -> inner.execute(new In(""))).join() unwrapped from CompletionException and rethrown → INTERNAL; getCause() is the VALIDATION_FAILED rejection
@Test void asyncRejectionRethrownIsInternal()           // the same through an @Async method on a context-propagating executor
@Test void rawThreadRejectionRethrownIsInternal()       // new Thread(...), the SynapseException captured and rethrown
@Test void userBuiltValidationFailedPasses()            // outer throws SynapseException.ofViolations(VALIDATION_FAILED, [("isbn", "is taken")]) → VALIDATION_FAILED with that violation
@Test void ownRejectionStaysValidationFailed()          // outer's own invalid input → VALIDATION_FAILED (unchanged)
@Test void preconditionRequiredFromAnotherThreadIsInternal()
// OperationPipelineTest
@Test void rejectionRecordsItsCall()                    // validate(invalid, token) → raisedByAnotherCall(e, token) false, (e, new Object()) true
@Test void unwrappedBusinessRejectionIsInternal()       // R-2: an operation unwraps a business rejection from its INTERNAL and rethrows it → INTERNAL
// EscapeHatchRoutesIT
@Test void controllerCallOnAnotherThreadIsValidationFailed()   // a controller does supplyAsync(() -> op.execute(boundInput)).join(), unwraps, rethrows → 400 with the violation
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='NestedInvocationTest,OperationPipelineTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compile errors, then the three "rethrown" tests get `VALIDATION_FAILED`.
- [ ] **Step 3: Implement.** The token is created next to `outermost` (~L134) and passed down; `rejected(...)` sets it on the `SanitizedViolations` it creates.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,core/synapse-test,api/synapse-api-rest,api/synapse-api-grpc -am verify -Dprotobuf…=…`.
- [ ] **Step 5: Commit:** `fix(core): a rejection raised by another call is INTERNAL on any thread`.

### Task 2: Parity is compared per major version (decision 5)

**Files:**
- Modify: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/binding/BindingDeclaration.java` (new last component), `.../internal/binding/BindingParityCheck.java` (the pair condition; class Javadoc), `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/route/RouteCompiler.java` ~L152, `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/internal/service/GrpcBindingsCompiler.java` ~L104.
- Docs: `core.adoc` *Parity* (~L531-533: "Each REST route is compared with the gRPC method …" becomes the major-version rule, with AIP-185 as the reason), `rest.adoc` and `grpc.adoc` where they repeat it (grep "every REST version").
- Test: `core/.../internal/binding/BindingParityCheckTest.java`, `api/synapse-api-grpc/src/test/java/.../internal/service/GrpcBindingsCompilerTest.java`, `api/synapse-api-rest/src/test/java/.../internal/route/RouteCompilerTest.java`, `api/synapse-api-grpc/src/test/java/.../autoconfigure/SynapseGrpcAutoConfigurationTest.java` (constructor calls), `samples/reference-bookstore/service-bookstore/src/test/java/.../BindingParityTest.java` (stays green unchanged).

**Interfaces:**

```java
public record BindingDeclaration(String protocol, String operationName, String binding, String notFoundCode,
        @Nullable String etagComponent, @Nullable Set<String> sortKeys, @Nullable Integer majorVersion) { }
// GrpcBindingsCompiler (package-private)
static @Nullable Integer packageVersion(String fullServiceName);   // "bookstore.v1.Bookstore" → 1; C3's pattern on the package's last segment
// BindingParityCheck: a pair over two protocols is compared when either majorVersion is null or both are equal
```

- An operation bound over two protocols whose binding pairs with no binding of the other protocol is logged once, in one WARN on `io.americanexpress.synapse.core` that lists every such binding (maintainer, 2026-11-20, review M5): `These bindings have no binding of the same major version over the other protocol, so their contract is not compared: [<operation>: <binding>, ...]. Bind the same version over both protocols, or ignore this if the version is meant for one protocol only.` An operation bound over one protocol only is not listed.

- [ ] **Step 1: Write the failing tests:**

```java
// BindingParityCheckTest
@Test void restV2MayDifferFromGrpcV1()                  // REST "GET /v2/books/{id}" (2) notFound X, gRPC v1 notFound NOT_FOUND → starts
@Test void restV1IsComparedWithGrpcV1()                 // REST v1 differs from gRPC v1 → fails naming "GET /v1/books/{id}"
@Test void unversionedRestIsComparedWithEveryGrpcMethod()
@Test void unversionedGrpcIsComparedWithEveryRestVersion()   // gRPC majorVersion null, REST v1 and v2 differ from it → both mismatches in one failure
@Test void minorRestVersionsPairWithTheMajor()          // REST "1.2" (major 1) vs gRPC v1 → compared
@Test void unpairedBindingsAreOneWarn()                 // gRPC v1 with only REST v2 routes, and a second such operation → one WARN listing both; starts
@Test void singleProtocolOperationIsNotListed()         // REST v1 and v2 only → no WARN
// GrpcBindingsCompilerTest
@Test void packageVersionIsTheTrailingVSegment()        // bookstore.v1.Bookstore→1, a.v2beta1.S→2, a.v1p1beta1.S→1, a.v10.S→10, a.b.S→null, S→null, a.v1x.S→null
// RouteCompilerTest
@Test void declarationCarriesTheRoutesMajorVersion()    // .version("2.1") → 2; unversioned → null
```

- [ ] **Step 2: Run to verify failure** (`BindingParityCheckTest`, then the two plugin tests).
- [ ] **Step 3: Implement.** REST: `new SemanticApiVersionParser().parseVersion(route.version()).getMajor()` (the parser `BindingValidator` already uses).
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-grpc,samples/reference-bookstore/service-bookstore,samples/grpc-inventory/service-inventory -am verify -Dprotobuf…=…`.
- [ ] **Step 5: Commit:** `feat: compare REST and gRPC bindings per major version`.

### Task 3: One violation path format in core, and gRPC reads it (decision 16, part 1)

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/ViolationPaths.java`.
- Modify: `.../internal/pipeline/OperationPipeline.java:246`, `.../internal/pipeline/ConstraintViolationTranslator.java:53` (both use `ViolationPaths.of`); `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/internal/error/GrpcStatusWriter.java` (`protoPath` and `PathReader`, ~L180-260: a `.` after a segment whose `name[]` entry is a map value descends into it; map-key reading and `MAX_TRIES` go, since core never writes a key; the fail-closed cut stays).
- Docs: `core.adoc` *Errors* (a short paragraph with BUILD §5.2's path rule and examples) and *Masking* (map keys are never in a violation's field); `grpc.adoc` ~L312-314 (`tags[2]` is now also true for a scalar item; the map sentence says the key is never written).
- Test: create `core/synapse-core/src/test/java/io/americanexpress/synapse/core/internal/pipeline/ViolationPathsTest.java` (a real `Validator`); modify `api/synapse-api-grpc/src/test/java/.../internal/error/GrpcStatusWriterTest.java`, `.../internal/service/GrpcBindingsIT.java`; fix expectations that assert `<list element>` paths (grep the repo's tests for `element>`).

**Interfaces:**

```java
final class ViolationPaths {
    /** Dots between property names; [i] for an indexed (list or array) element, scalar items included; nothing for a
     *  Set element; a map key is never written; container element node names (<list element>, <iterable element>,
     *  <map key>, <map value>) are never written. */
    static String of(jakarta.validation.Path path);
}
```

- [ ] **Step 1: Write the failing tests** (records under test in `ViolationPathsTest`):

```java
@Test void propertyIsItsName()                         // "title"
@Test void nestedPropertyIsDotted()                    // "author.name"
@Test void scalarListItemKeepsItsIndex()               // List<@NotBlank String> tags = ["a", ""] → "tags[1]"
@Test void listOfRecordsKeepsIndexAndProperty()        // "items[2].sku"
@Test void arrayItemKeepsItsIndex()
@Test void setElementHasNoIndex()                      // Set<@NotBlank String> roles → "roles"
@Test void mapKeyIsNeverWritten()                      // Map<@NotBlank String, String> labels with key "" → "labels"; a key "a.b[c]" never appears
@Test void wholeMapValueNamesTheMap()                  // Map<String, @NotBlank String> → "labels"
@Test void propertyInsideAMapValueContinuesWithTheValue()   // Map<String, Author> with a blank name under key "x]y" → "authorsByRank.name"
@Test void noContainerNodeNameIsEverWritten()          // no result contains '<'
// GrpcStatusWriterTest
@Test void scalarListItemKeepsItsIndexOverGrpc()       // protoPath("tags[0]", paths) → "tags[0]"
@Test void mapValuePropertyWithoutAKey()               // "authorsByRank.countryCode" → "authors_by_rank.country_code"
@Test void pagingViolationFromThePipelineNamesTheField()   // "page.size" → "page_size"
// GrpcBindingsIT
@Test void scalarItemViolationNamesTheItem()           // request tags ["a", ""] on List<@NotBlank String> → BadRequest field "tags[1]"
```

- [ ] **Step 2: Run to verify failure.** Expected: `ViolationPaths` missing; `tags[1].<list element>`; gRPC `tags`.
- [ ] **Step 3: Implement** `ViolationPaths.of` over `Path.Node`s: for a node `isInIterable()` with an index, append `[index]`; with a key or neither, append nothing; then append the node's name unless it is null, empty or starts with `<`. Leave `SanitizedViolations` alone (*Coordination*).
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,core/synapse-test,api/synapse-api-rest,api/synapse-api-grpc,api/synapse-test-grpc,samples/rest-books -am verify -Dprotobuf…=…`. Report every test expectation that changed, with its old and new path.
- [ ] **Step 5: Commit:** `feat(core)!: one violation path format, with item indexes and no map keys`.

### Task 4: REST violations use wire names (decision 16, part 2)

**Files:**
- Create: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/WireNames.java`.
- Modify:
  - `.../internal/binding/RequestBinder.java` (every `new Violation(component.name(), …)` at ~L659, 673, 712, 717, 829, 880 and the conflict violation uses the source's wire name);
  - `.../internal/binding/JsonNames.java` (the reading view for any bean type);
  - `.../internal/route/RouteCompiler.java` (build one `WireNames` per route) and `.../internal/route/OperationHandler.java` (set the request attribute just before `execute`);
  - `.../internal/edge/RestRequestAttributes.java` (the attribute);
  - `.../internal/error/ProblemWriter.java` (`write` renames violations when the attribute is set; `JsonMapper mapper()` getter);
  - `.../internal/error/RestExceptionClassifier.java` (~L190-197: `MethodArgumentNotValidException` paths through `WireNames.body`; `classify`/`errorFor` take the mapper), and its callers `SynapseRestExceptionResolver`, `SynapseErrorController`, `ClientErrorFilter`.
- Docs: `rest.adoc` ~L211 ("The violation `field` is the component name" → BUILD §6.2's wire-name rule, with a SNAKE_CASE example and C5's two rules) and *Escape hatch* (`@Valid` body violations use JSON names; map keys are dropped).
- Test: `api/synapse-api-rest/src/test/java/.../internal/binding/{RequestBinderTest,JsonNamesTest}.java`, create `.../internal/binding/WireNamesTest.java`, `.../internal/route/RestRoutesIT.java`, `.../internal/route/EscapeHatchRoutesIT.java`; sample tests that assert REST violation fields (grep `"field"` in `samples/*/src/test`).

**Interfaces:**

```java
public final class WireNames {
    /** The route's names: each component's declared source name (query parameter, header, cookie, path variable),
     *  a body component's JSON name as Jackson reads it, the paging parameter names for a PageQuery component, and
     *  the component name for .context, .from and a whole-body component (C5). */
    public static WireNames of(RouteDefinition route, JsonMapper bodyMapper, PagingSettings paging);
    /** A core path (Task 3's format) in wire names; a first segment no component has is returned unchanged; a segment
     *  Jackson can't name cuts the path there. */
    public String rename(String path);
    public List<Violation> rename(List<Violation> violations);
    /** A body path from its root type, for controllers: Spring's form too, where [n] on a list or array is kept and any
     *  other bracket (a map key) is dropped. */
    public static String body(JsonMapper mapper, Class<?> rootType, String path);
}
// JsonNames
/** Internal property name → the JSON name Jackson reads, for a record (as of(...)) or any bean type; aliases excluded. */
public static Map<String, String> read(JsonMapper mapper, Class<?> type);
// RestRequestAttributes
public static final String WIRE_NAMES;
public static @Nullable WireNames wireNames(HttpServletRequest request);
```

- Per-type names are cached (`ConcurrentHashMap<Class<?>, Map<String, String>>`) inside `WireNames`.
- Binder violations are already wire names: the attribute is set only after binding succeeds, so `ProblemWriter` never renames them twice.

- [ ] **Step 1: Write the failing tests** (Review Focus 3):

```java
// RequestBinderTest (a SNAKE_CASE mapper unless said otherwise)
@Test void bodyViolationUsesTheJsonName()               // publishedYear missing → ("published_year", "must not be null")
@Test void jsonPropertyWinsOverTheNamingStrategy()      // @JsonProperty("yr") → "yr"
@Test void aliasIsNotTheViolationName()                 // @JsonAlias("year") sent as "year": "x" → "published_year"
@Test void queryViolationUsesTheParameterName()         // .query("sort_by", R::sort), invalid → "sort_by"
@Test void headerViolationUsesTheHeaderName()           // .header("X-Id", R::id), "abc" for a Long → "X-Id"
@Test void cookieViolationUsesTheCookieName()
@Test void pathViolationUsesTheVariableName()           // .path("bookId", R::id) → "bookId"
@Test void conflictUsesTheDeclaredName()                // path bookId=1, body "id": 2 → ("bookId", "conflicts with the value from the path")
// WireNamesTest
@Test void nestedBodyPathUsesJsonNamesPerType()         // "author.countryCode" → "author.country_code"; @JsonNaming(KebabCaseStrategy) on Author → "author.country-code"
@Test void listIndexIsKept()                            // "items[2].skuCode" → "items[2].sku_code"
@Test void mapValuePropertyUsesTheValuesJsonName()      // "authorsByRank.countryCode" → "authors_by_rank.country_code"
@Test void mapKeyNeverAppearsInARestPath()              // body("labels[a.b]].x") on Map<String, Label> → "labels.x"
@Test void unnamedPartCutsThePath()                     // a component typed with its own deserializer: "book.title" → "book"
@Test void contextComponentKeepsItsName()
@Test void bodyComponentPathStartsAtTheBody()           // .body(R::changes): "changes.title" → "title"; "changes" → "changes"
@Test void pagingPathUsesTheParameterNames()            // "page.size" → "size"; with parameters.size=per_page → "per_page"
@Test void unknownPathIsUnchanged()                     // "isbn" on a route without isbn → "isbn"
// RestRoutesIT (spring.jackson.property-naming-strategy=SNAKE_CASE)
@Test void pipelineViolationUsesTheJsonName()           // @Size(max = 3) on publishedYear's record → {"field":"published_year", ...}
// EscapeHatchRoutesIT
@Test void validBodyViolationUsesTheJsonName()          // @Valid @RequestBody with SNAKE_CASE → "published_year"
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** The walker resolves each segment against the current type: a record or bean through `JsonNames.read`; a `Collection` or array segment's `[i]` moves to the element type; a `Map` moves to the value type for the next name; an `Optional` unwraps. Generic types come from the record component's `getGenericType()`.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest,samples/rest-books,samples/hello-rest,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`. Re-run any README command whose output shows a violation `field` that changed (grep `"field"` in `samples/*/README.md`), or report that none changed.
- [ ] **Step 5: Commit:** `feat(rest)!: name violations with the wire names the client sent`.

### Task 5: An unsent collection binds empty over REST (parity)

**Files:**
- Modify: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/RequestBinder.java` (`bodyValue` ~L655, `bind` ~L693-718, `read` ~L880; the constructor builds the binder's mapper copy, C6).
- Docs: `rest.adoc` *Input binding* (a bullet: an absent or `null` collection or map binds empty; `@NotEmpty` for "at least one"; a `@Nullable` top-level collection stays `null`; arrays keep `must not be null`) and the violations table's first row; `grpc.adoc` *Presence* (a `repeated`/`map` field is never missing; use `@NotEmpty`); `core.adoc` *Parity* (one line pointing to both).
- Test: `RequestBinderTest`, `RestRoutesIT`, `api/synapse-api-grpc/src/test/java/.../internal/service/GrpcBindingsIT.java`.

**Interfaces:**
- No new type. The top-level rule is in the binder itself, so it also covers query, header, cookie and path sources.
- Nested records (C6, review I2): Jackson looks a config override up by the property's **exact** raw type (`ConfigOverrides.findOverride(Class)` is a `Map.get`, and `POJOPropertyBuilder` asks with the property's own raw type), so an override on `Collection.class` never applies to a `List<String>` component. The binder's mapper is `mapper.rebuild()` with `withConfigOverride(type, o -> o.setNullHandling(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY)))` for each of `Collection`, `List`, `Set`, `SortedSet`, `NavigableSet`, `Map`, `SortedMap` and `NavigableMap` (Boot's `JsonMapper` bean is untouched). Once found, the same null provider answers the absent case (`PropertyValueBuffer` asks it for the absent value), so absent and `null` both bind empty. A component declared with another collection type (`ArrayList`, `LinkedHashSet`, a Guava type) isn't covered by the overrides: Step 3 then uses a `ValueDeserializerModifier` instead (a `BeanDeserializerModifier`-style hook that wraps every `Collection` and `Map` property's null provider), and records which mechanism it used.

- [ ] **Step 1: Write the failing tests:**

```java
// RequestBinderTest
@Test void absentBodyListBindsEmpty()           // List<String> tags, body {} → []
@Test void jsonNullListBindsEmpty()             // {"tags": null} → []
@Test void absentQueryListBindsEmpty()
@Test void absentHeaderListBindsEmpty()
@Test void absentCookieListBindsEmpty()
@Test void absentSetAndMapBindEmpty()
@Test void nestedRecordListBindsEmpty()         // {"author": {"name": "a"}}, Author(String name, List<String> aliases) → aliases []
@Test void nullableTopLevelListStaysNull()
@Test void absentArrayIsStillMissing()          // String[] codes absent → ("codes", "must not be null")
// RestRoutesIT and GrpcBindingsIT, the same operation (@NotEmpty List<String> tags) on both
@Test void notEmptyGivesTheSameViolationOnBothProtocols()   // REST body {} → 400 {"field":"tags","message":"must not be empty"}; gRPC no tags → INVALID_ARGUMENT, FieldViolation("tags", "must not be empty")
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** with the per-type overrides; add `nestedArrayListComponentBindsEmpty` (a nested `ArrayList<String>` component). If it fails with the overrides, switch to the deserializer modifier and keep the test.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest,api/synapse-api-grpc,samples/rest-books,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`.
- [ ] **Step 5: Commit:** `feat(rest)!: bind an absent or null collection as empty, as gRPC does`.

### Task 6: A required scalar over gRPC needs a field with presence

**Files:**
- Modify: `api/synapse-api-grpc/src/main/java/io/americanexpress/synapse/api/grpc/internal/mapping/MessageMapping.java` (next to the `@Nullable` presence check at ~L590; only when reading a request, C7).
- Protos (every request field bound to a non-null scalar component becomes `optional`):
  - `samples/reference-bookstore/contract-bookstore/src/main/proto/bookstore/v1/bookstore.proto` (for example `CreateBookRequest.isbn/title/author`, `GetBookRequest.id`, `UpdateBookRequest.id/isbn/title/author`, `DeleteBookRequest.id`);
  - `samples/grpc-inventory/contract-inventory/src/main/proto/inventory/v1/inventory.proto`;
  - `archetypes/synapse-archetype-service/src/main/resources/archetype-resources/contract-__serviceName__/src/main/proto/__serviceName__/v1/__serviceName__.proto` (with the bookstore in one commit if `archetype-drift` compares them);
  - test fixtures: `api/synapse-api-grpc/src/test/proto/synapse/fixture/v1/{catalog,conditional,paging}.proto`, `api/synapse-test-grpc/src/test/proto/synapse/slice/v1/catalog.proto`;
  - Java code that builds those requests with the generated builders keeps compiling (an `optional` field keeps its setter).
- Docs: `grpc.adoc` *Presence* (~L241-244: "a non-null component bound to one is never missing" becomes the startup rule and its reason), the *Startup failures* table (the new message), *Message mapping* example protos; the sample READMEs' proto excerpts, if any.
- Test: `api/synapse-api-grpc/src/test/java/.../internal/mapping/{MessageMappingTest,RecordReaderTest}.java`.

**Interfaces:**
- One failure lists every offending component at once (review M6), so a service with many plain scalars fixes them in one pass. Today a `MappingException` stops a method at its first problem, and `ServiceCompiler` stops at the first failing method (~L156-161). So:
  - `MessageMapping` collects the presence problems of the whole request tree and throws one `MappingException` after mapping it, if the request has no other problem;
  - `ServiceCompiler` keeps compiling the service's other methods when a method fails only on presence, then throws one `SynapseConfigurationException` naming every such method;
  - any other mapping problem still fails at once, as today.
- The description, one line per component: `<Record>.<component> is required, but <message>.<field> has no presence, so an unsent field would bind its default`. The action: `Declare these fields optional (proto3 optional), use a wrapper type, or make the components @Nullable.`
- The scope is the full reach the maintainer confirmed (2026-11-20, review "For Tony" 1): every non-null scalar of every request message, nested messages and primitives included, also in a message that a response reuses. Don't narrow it.

- [ ] **Step 1: Write the failing tests** (Review Focus 1):

```java
// MessageMappingTest
@Test void requiredScalarWithoutPresenceFailsNamingTheField()   // R(String title) ↔ string title = 1 → the message above, naming R.title and the message's title
@Test void everyRequiredScalarIsListedInOneFailure()            // R(String title, String author, int pages), all plain → one MappingException with three lines
// ServiceCompilerTest
@Test void presenceProblemsOfEveryMethodAreOneFailure()         // two methods with plain required scalars → one SynapseConfigurationException naming both methods
@Test void otherMappingProblemsStillFailAtOnce()                 // an unmappable type in the first method → today's failure
@Test void requiredPrimitiveWithoutPresenceFails()              // int count ↔ int32 count
@Test void requiredScalarWithOptionalBinds()
@Test void requiredWrapperBinds()                               // Integer ↔ google.protobuf.Int32Value
@Test void requiredEnumWithUnspecifiedZeroBinds()
@Test void requiredEnumWithoutUnspecifiedZeroFails()
@Test void nestedRequiredScalarWithoutPresenceFails()           // author.name inside a message field
@Test void mappedOverrideIsChecked()                            // .map("meta.code", R::code) to a plain string → fails naming meta.code
@Test void tokenAndPageFieldsAreExempt()                        // VersionCondition ↔ string etag; PageQuery ↔ page fields
@Test void repeatedFieldIsNotAScalar()                          // List<String> ↔ repeated string → binds
@Test void responseScalarsAreNotChecked()
// RecordReaderTest
@Test void unsentOptionalRequiredScalarIsMustNotBeNull()        // → FieldViolation("title", "must not be null")
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-grpc -am test -Dtest='MessageMappingTest,RecordReaderTest' -Dsurefire.failIfNoSpecifiedTests=false -Dprotobuf…=…`.
- [ ] **Step 3: Implement** the check, then migrate the protos. Run each module's tests after its proto changes; a test that relied on an unsent plain field binding `""` changes to send the value or to expect `must not be null`, each change named in the report.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-grpc,api/synapse-test-grpc,samples/grpc-inventory/contract-inventory,samples/grpc-inventory/service-inventory,samples/reference-bookstore/contract-bookstore,samples/reference-bookstore/service-bookstore,archetypes/synapse-archetype-service,tests/synapse-build-rules-tests -am verify -Dprotobuf…=…`, Docker on.
- [ ] **Step 5: Re-run the gRPC README commands** of `grpc-inventory` and `reference-bookstore` (and the generated service project's, if its README has any), add one that sends a request without a required field and paste the `must not be null` output.
- [ ] **Step 6: Commit:** `feat(grpc)!: require presence for a required scalar field, as REST reports it missing`.

### Task 7: A repeated cookie takes the first copy, with a DEBUG line and a counter (decision 6)

**Files:**
- Create: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/CookieDuplicates.java`.
- Modify: `.../internal/binding/RequestBinder.java` (the cookie case ~L730, `cookies` ~L798, `convert` Javadoc ~L816; the constructor takes the recorder; `checkBindable` registers each single-valued cookie binding), `api/synapse-api-rest/src/main/java/.../autoconfigure/SynapseRestAutoConfiguration.java` (~L405-415: the bean `synapseRestCookieDuplicates`, `@ConditionalOnBean(RestBindings.class)`, `@ConditionalOnMissingBean(name = "synapseRestCookieDuplicates")`, from `ObjectProvider<MeterRegistry>`; `requestBinder` takes it).
- Docs: `rest.adoc` ~L147-160 (rewrite the cookie bullet: first copy as `@CookieValue`, DEBUG by name, the counter; the `__Host-` recommendation with its RFC 6265bis/MDN basis; the `List` and `@Size(max = 1) List<String>` patterns; headers and query parameters keep their 400) and the violations table row ~L205 (drop "or cookie"); a *Metrics* note next to it naming `synapse.rest.cookie.duplicates` and its tags.
- Test: `RequestBinderTest` (replace `repeatedCookieIsSingleValueViolation`), `api/synapse-api-rest/src/test/java/.../autoconfigure/SynapseRestAutoConfigurationTest.java`, `RestRoutesIT`.

**Interfaces:**

```java
public final class CookieDuplicates {
    public static final String METER = "synapse.rest.cookie.duplicates";
    public static CookieDuplicates none();                       // no registry: register and record do nothing
    public CookieDuplicates(@Nullable MeterRegistry registry);
    void register(String operation, String cookie);              // at startup, per single-valued cookie binding: the series exists at 0
    void record(String operation, String cookie);                // +1 and the DEBUG line (C8)
}
public RequestBinder(JsonMapper mapper, ConversionService conversionService, PagingSettings paging, CookieDuplicates duplicates);
```

- Tags: `cookie` = the declared name from `InputSource.Cookie.name()` only, never a name read from the request; `operation` = the route's operation name.

- [ ] **Step 1: Write the failing tests:**

```java
// RequestBinderTest (SimpleMeterRegistry)
@Test void repeatedCookieTakesTheFirstCopy()                // session=a; session=b → "a"; counter{cookie=session, operation=<op>} == 1
@Test void repeatedCookieIsLoggedAtDebugByNameOnly()        // one DEBUG line naming "session", containing neither "a" nor "b"
@Test void listCookieGetsEveryCopyAndIsNotCounted()         // List<String> → ["a", "b"]; counter 0
@Test void singleCookieIsNotCounted()
@Test void withoutARegistryTheFirstCopyStillBinds()         // CookieDuplicates.none()
// SynapseRestAutoConfigurationTest
@Test void cookieCounterIsRegisteredAtStartup()             // MeterRegistry bean + a route .cookie("session", ...) → counter present at 0 with both tags
@Test void listCookieBindingIsNotRegistered()
@Test void cookieRecorderWorksWithoutARegistry()            // no MeterRegistry → context starts; the bean exists
@Test void cookieRecorderBacksOffByName()                   // a user bean named synapseRestCookieDuplicates is used
@Test void cookieRecorderIsAbsentWithoutBindings()
// RestRoutesIT
@Test void sizeMaxOneListRejectsDuplicates()                // @Size(max = 1) List<String> session, two cookies → 400 on "session"
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest,samples/rest-books -am verify`. Re-run a README command that sends a repeated cookie, if any (grep `Cookie:` in `samples/*/README.md`), or report none.
- [ ] **Step 5: Commit:** `fix(rest): take the first copy of a repeated cookie, as @CookieValue does, and count it`.

### Task 8: Spring MVC's handlers answer before Synapse's resolver, except on Synapse paths (decision 13, part 1)

**Files:**
- Create: `api/synapse-api-rest/src/main/java/.../internal/error/SynapseRestExceptionResolverConfigurer.java`, `.../internal/error/SynapsePaths.java`.
- Modify:
  - `.../internal/error/SynapseRestExceptionResolver.java` (not `Ordered`; returns `null` for a committed response and for Security's two exceptions; an optional `SynapsePaths` guard for the index-0 instance);
  - `.../autoconfigure/SynapseRestErrorAutoConfiguration.java` (~L111-115: the resolver bean goes; the configurer bean `synapseRestExceptionResolverConfigurer`, `@ConditionalOnMissingBean(name = …)`, which gets the `synapseRestRoutes` router function only as an `ObjectProvider` qualified by that name and resolves it inside `extendHandlerExceptionResolvers`, never in its constructor (review N1); the problem-details WARN);
  - `api/synapse-api-rest/pom.xml` (test scope `org.springframework.security:spring-security-web`, no version).
- Docs: `rest.adoc` *Escape hatch* (~L1357-1363 rewritten: your `@ExceptionHandler`s and `@ControllerAdvice` answer first for your controllers; Synapse routes keep Synapse's mapping, also for errors raised before a handler is found on their paths (a missing or invalid API version, a 405/415), so their shape is always Synapse's problem; on a path that a Synapse route and a controller share (`GET /books` bound, `POST /books` a controller), an error raised before a handler is found, such as a 415 from the controller's own mapping lookup, is Synapse's too, and the controller's advice doesn't see it (review n1); to change a Synapse route's error, use status overrides or an `ExceptionTranslator`; the problem-details WARN; Security's exceptions propagate), *Controllers, Actuator and Spring Security*; `testing.adoc` if it says advice doesn't run (grep `ControllerAdvice`).
- Test: `api/synapse-api-rest/src/test/java/.../internal/error/SynapseRestExceptionResolverTest.java`, create `.../internal/error/SynapsePathsTest.java`, `.../autoconfigure/SynapseRestErrorAutoConfigurationTest.java`, `.../internal/route/EscapeHatchRoutesIT.java`. `SynapseRestTypeExcludeFilter` keeps `ControllerAdvice` (now correct; no change).

**Interfaces:**

```java
public final class SynapseRestExceptionResolverConfigurer implements WebMvcConfigurer {
    /** Takes the routes lazily: Spring MVC's configuration class gets every WebMvcConfigurer injected while it is
     *  created, and synapseRestRoutes needs its mvcConversionService (RouteCompiler → RequestBinder), so a
     *  constructor argument built from the routes would close a bean cycle (review N1). */
    public SynapseRestExceptionResolverConfigurer(ProblemWriter writer,
            @Qualifier("synapseRestRoutes") ObjectProvider<RouterFunction<?>> routes);
    /** Builds SynapsePaths here, from routes.getIfAvailable() (SynapsePaths.none() without routes), then inserts the
     *  Synapse-paths instance at index 0 and the main instance right after the ExceptionHandlerExceptionResolver
     *  instance (at index 1 when an application removed that one). */
    @Override public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers);
}
public final class SynapsePaths {
    public static SynapsePaths none();
    /** The served paths of the routes (OperationRoutes.of(...), the /v<N> prefix included), as PathPatterns. */
    public static SynapsePaths of(RouterFunction<?> routes);
    /** True when the request's path, within the application, matches a Synapse route's served path, whatever the method. */
    public boolean matches(HttpServletRequest request);
}
// SynapseRestExceptionResolver
public SynapseRestExceptionResolver(ProblemWriter writer);                       // the main instance: answers everything except the two cases below
public static SynapseRestExceptionResolver forSynapsePaths(ProblemWriter writer, SynapsePaths paths);
                                                                                 // answers only when the handler is null, an OperationHandler or a
                                                                                 // MethodNotAllowedHandler, and paths.matches(request); otherwise null
static boolean isSecurityException(Throwable ex);   // its class or a superclass is org.springframework.security.access.AccessDeniedException
                                                    // or org.springframework.security.core.AuthenticationException; the cause chain is not walked
```

- Both instances return `null` for a committed response and for a Security exception, so neither takes those from Security.
- The order is then: `DefaultErrorAttributes` (a bean, `HIGHEST_PRECEDENCE`), the composite with the Synapse-paths instance, `ExceptionHandlerExceptionResolver`, the main instance, `ResponseStatusExceptionResolver` and `DefaultHandlerExceptionResolver` (C10).
- The WARN (once, on `io.americanexpress.synapse.api.rest`, when the configurer is created and `spring.mvc.problemdetails.enabled` is `true`): `spring.mvc.problemdetails.enabled is true: Spring Boot's ProblemDetailsExceptionHandler answers Spring MVC's exceptions from your controllers in Spring's problem shape instead of Synapse's (no code or correlationId). Synapse routes are not affected.`

- [ ] **Step 1: Write the failing tests** (Review Focus 4):

```java
// SynapseRestErrorAutoConfigurationTest
@Test void resolversRunInTheDecidedOrder()                  // composite: Synapse-paths instance, ExceptionHandlerExceptionResolver, main instance, ResponseStatusExceptionResolver, DefaultHandlerExceptionResolver; no SynapseRestExceptionResolver bean
@Test void configurerBacksOffByName()
@Test void withoutRoutesThePathsInstanceMatchesNothing()    // no synapseRestRoutes bean → SynapsePaths.none()
@Test void contextWithRealMvcAndARouteStarts()              // review N1: WebApplicationContextRunner with the full WebMvcAutoConfiguration (and its
                                                            // dependencies, as SynapseRestAutoConfigurationTest wires them), both Synapse REST
                                                            // auto-configurations and a RestBindings bean with one route → context starts (no
                                                            // BeanCurrentlyInCreationException), and the Synapse-paths instance matches that route's path
@Test void problemDetailsEnabledWarns()                     // the WARN text; and none without the property
// SynapseRestExceptionResolverTest
@Test void withoutExceptionHandlerExceptionResolverTheMainInstanceIsSecond()
@Test void securityExceptionsAreNotResolved()               // both instances: AccessDeniedException, a BadCredentialsException → null
@Test void committedResponseIsNotResolved()
@Test void pathsInstanceIgnoresAControllerHandler()         // a HandlerMethod handler on a Synapse path → null
// SynapsePathsTest
@Test void servedPathMatchesWhateverTheMethod()             // route GET /v2/books/{id}; DELETE /v2/books/7 matches; /v3/books/7 and /books/7 don't
@Test void contextPathIsLeftOut()                           // server.servlet.context-path=/api; /api/books/7 matches /books/{id}
// EscapeHatchRoutesIT
@Test void controllerAdviceAnswersFirst()                   // @ControllerAdvice @ExceptionHandler(IllegalStateException) → its 418 body
@Test void localExceptionHandlerAnswersFirst()
@Test void synapseRouteKeepsSynapsesProblemWithACatchAllAdvice()   // @ControllerAdvice @ExceptionHandler(Exception) present; a Synapse route's operation throws BOOK_NOT_FOUND → 404 problem, code BOOK_NOT_FOUND
@Test void preHandlerErrorOnASynapsePathIsSynapsesProblem()        // catch-all advice extending ResponseEntityExceptionHandler; spring.mvc.apiversion.required=true and no version on a Synapse path → 400 problem, code MISSING_API_VERSION, correlationId; a 415 on a Synapse path → UNSUPPORTED_MEDIA_TYPE
@Test void preHandlerErrorOnAControllerPathGoesToTheAdvice()       // the same missing version on a controller's path → the advice's answer
@Test void preHandlerErrorOnASharedPathIsSynapsesProblem()         // review n1: GET /books bound by Synapse, POST /books a controller with consumes = JSON;
                                                                   // POST /books as text/plain (a 415 from the controller's lookup, no handler) → Synapse's
                                                                   // UNSUPPORTED_MEDIA_TYPE problem, not the controller's advice
@Test void accessDeniedPropagatesToSecurity()               // an ExceptionTranslationFilter (spring-security-web) with an entry point that calls sendError(401) and an AccessDeniedHandlerImpl; controller throws AccessDeniedException as an authenticated user → 403 problem, code FORBIDDEN (via the error dispatch); BadCredentialsException → 401, UNAUTHENTICATED, WWW-Authenticate kept
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am verify -Dit.test=EscapeHatchRoutesIT -Dtest='SynapseRestErrorAutoConfigurationTest,SynapseRestExceptionResolverTest,SynapsePathsTest' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- [ ] **Step 3: Implement.** Check that Boot's `WebMvcAutoConfiguration` calls `extendHandlerExceptionResolvers` on every `WebMvcConfigurer` bean (it does through `DelegatingWebMvcConfiguration`), and that nothing else registers the resolver. `SynapsePaths` uses Spring's `PathPatternParser` on the served paths and the request's path within the application (`UrlPathHelper` or `ServletRequestPathUtils`, as Spring's handler mappings do).
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest,api/synapse-api-rest-openapi,samples/rest-books,samples/hello-rest,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`. The Enforcer must stay green with the test-scope dependency.
- [ ] **Step 5: Commit:** `feat(rest)!: let Spring MVC's exception handlers answer first for controllers`.

### Task 9: A Spring status outside Synapse's table is kept (decision 13, part 2)

**Files:**
- Create: `api/synapse-api-rest/src/main/java/.../internal/error/KeptStatusException.java`.
- Modify:
  - `.../internal/error/RestExceptionClassifier.java` (the `ErrorResponse` branch ~L216-221 and the `@ResponseStatus` walk; `SERVER_ERROR` logged at WARN without a stack);
  - `.../internal/error/StatusErrors.java` (`forStatus` returns `null` outside the table; 500 → `INTERNAL`);
  - `.../internal/error/SynapseErrorController.java` (~L84: a status outside the table keeps its status);
  - `.../internal/error/RestErrorStatuses.java` (the new overload, the seam the API-surface plan's SPI takes over; see *Coordination*), `.../internal/error/ProblemWriter.java` (`statusOf` uses it; the reason as `detail` under include-detail);
  - `.../internal/error/RestProtocolException.java` (a protected constructor with a cause);
  - `api/synapse-api-rest/src/main/java/.../RestErrors.java` (`CLIENT_ERROR`, `SERVER_ERROR`, C9).
- Docs: `rest.adoc` *Errors* (the `RestErrors` table gains the two codes, "status kept"; the precedence sentence: override, then kept status, then the fixed or category status; the container table's last two rows; the logging paragraph).
- Test: `api/synapse-api-rest/src/test/java/.../internal/error/{SynapseErrorControllerTest,RestErrorStatusesTest,ProblemWriterTest,RestErrorsIT}.java`, `.../internal/route/EscapeHatchRoutesIT.java`.

**Interfaces:**

```java
public final class KeptStatusException extends RestProtocolException {
    public KeptStatusException(ErrorDetails details, @Nullable Integer status, Map<String, String> headers, @Nullable String reason, Throwable cause);
    public @Nullable Integer status();   // set for CLIENT_ERROR and SERVER_ERROR only
    public @Nullable String reason();
}
// RestErrorStatuses: the one seam for a REST status (the API-surface plan's mapper SPI takes the same arguments and keeps this order in its default)
/** A configured status for the code (per operation, then global), then a KeptStatusException's status, then statusFor(details, op). */
public int statusFor(SynapseException error, @Nullable String operationName);
// RestErrors
CLIENT_ERROR("The request was rejected", INVALID_INPUT, 400), SERVER_ERROR("The server could not complete the request", INTERNAL, 500)
```

- Mapping: a top-level `ErrorResponse` (after the existing specific branches), then a `ResponseStatusException` or `@ResponseStatus` (`AnnotatedElementUtils.findMergedAnnotation`) on the exception or along its cause chain, as Spring's `ResponseStatusExceptionResolver` does. The headers are `ErrorResponse.getHeaders()`; the reason is `getReason()`, `@ResponseStatus.reason`, or `getBody().getDetail()`.

- [ ] **Step 1: Write the failing tests:**

```java
// EscapeHatchRoutesIT
@Test void unprocessableKeeps422WithClientError()           // ResponseStatusException(422) → 422, code CLIENT_ERROR
@Test void goneKeeps410()
@Test void badGatewayKeeps502WithServerErrorAtWarn()        // 502, SERVER_ERROR, one WARN line without a stack trace
@Test void responseStatusConflictIs409Conflict()            // @ResponseStatus(CONFLICT) on the exception class → 409, CONFLICT
@Test void responseStatusOnACauseIsFound()                  // RuntimeException(cause annotated with @ResponseStatus(GONE)) → 410
@Test void headersAreCopied()                               // ResponseStatusException(503) with Retry-After: 5 → header kept
@Test void reasonIsDetailOnlyWithIncludeDetail()            // reason "quota" → detail "quota" with include-detail, absent without
// RestErrorStatusesTest
@Test void configuredStatusForTheCodeWinsOverTheKeptStatus()   // [CLIENT_ERROR]=400, kept 422 → 400; per-operation beats global
@Test void keptStatusWinsOverTheFixedStatus()               // CLIENT_ERROR kept 422, nothing configured → 422
@Test void withoutAKeptStatusTheOldOrderHolds()             // any other SynapseException → statusFor(details, op)
// SynapseErrorControllerTest
@Test void bareUnknownStatusKeepsItsStatus()                // error dispatch with status 418 → 418, CLIENT_ERROR; 502 → 502, SERVER_ERROR
@Test void bare500IsInternal()
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am verify -Dit.test=EscapeHatchRoutesIT -Dtest='RestErrorStatusesTest,SynapseErrorControllerTest,ProblemWriterTest' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`.
- [ ] **Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest,samples/rest-books -am verify`.
- [ ] **Step 5: Commit:** `feat(rest)!: keep a Spring status outside Synapse's table, with CLIENT_ERROR and SERVER_ERROR`.

### Task 10: A hand-written gRPC service's status keeps only its code (decision 24)

**Files:**
- Modify:
  - `api/synapse-api-grpc/src/main/java/.../internal/error/SynapseGrpcExceptionHandler.java` (~L71-76: a thrown `StatusException`/`StatusRuntimeException` goes through `writeForeign`; class Javadoc);
  - `.../internal/error/GrpcStatusWriter.java` (`writeForeign`; and, only if Step 1 finds that a Synapse-built status reaches `close()`, `build` records each status on the call state);
  - `.../internal/error/GrpcErrorStatuses.java` (the inverse table, C12);
  - `.../internal/edge/GrpcCallState.java` (the written-status mark, only in that case);
  - `.../internal/edge/SynapseGrpcContextInterceptor.java` (`ContextClosingCall.close`, ~L265-273, rewrites a foreign status with the `GrpcStatusWriter` the interceptor already takes, constructor ~L117).
- Docs: `grpc.adoc` *Exception handlers* (~L728-733), *Escape hatch* (~L936-940), *Errors* (a behaviour-change note: a deliberate `Status.X.withDescription(...)` loses its description by default; throw a `SynapseException` with a code and template, or set include-detail; `@GrpcAdvice` answers are left alone; the P2 origin marker is coming, BUILD P2; the inverse table, with two notes: `FAILED_PRECONDITION` maps to `PRECONDITION_FAILED`, the code a weak or stale etag also uses, and `VALIDATION_FAILED` here carries no violations, review M7).
- Test: `api/synapse-api-grpc/src/test/java/.../internal/error/{SynapseGrpcExceptionHandlerTest,GrpcErrorStatusesTest,GrpcStatusWriterTest}.java`, `.../internal/edge/{SynapseGrpcContextInterceptorTest,GrpcAsyncCompletionIT}.java`, `api/synapse-test-grpc` (`SynapseGrpcTestIT.appliesTheApplicationsGrpcAdvice` stays green).

**Interfaces:**

```java
// GrpcErrorStatuses
public static ErrorDetails codeFor(Status.Code code);   // C12's table
// GrpcStatusWriter
/** code kept; description only under include-detail; ErrorInfo(reason = codeFor(code), domain, correlationId) always;
 *  the foreign grpc-status-details-bin always dropped; other foreign trailers kept only under include-detail. */
public StatusException writeForeign(Status status, Metadata trailers, @Nullable GrpcCallState state);
// GrpcCallState, only if Step 1 finds a Synapse-built status at close()
void written(Status status);
boolean isWritten(Status status);    // identity
```

- `ContextClosingCall.close(status, trailers)` passes unchanged: OK; `UNKNOWN` with a cause that isn't a `StatusException`/`StatusRuntimeException` (Spring gRPC's `GrpcExceptionHandledServerCall` hands that cause to the handlers, verified in 1.1.2 by javap); and, with the mark, a status `isWritten`. Anything else non-OK goes through `writeForeign` (C11).

- [ ] **Step 1: Record what reaches `close()`** (review M4). Add a temporary probe test in `SynapseGrpcContextInterceptorTest` or `GrpcAsyncCompletionIT` that records every `Status` passed to `ContextClosingCall.close` for: a bound operation that fails with a `SynapseException`, one that throws an unexpected exception, a concurrency-limit rejection, an unknown method, and a hand-written service's `onError` with a status and with a plain exception. Expected from the code at 5ea62ec7: only the last two (the others close the outer call). Write the result in the task report. If no Synapse-built status reaches `close()`, drop the mark and the `written`/`isWritten` methods; otherwise keep them. Then delete the probe.
- [ ] **Step 2: Write the failing tests:**

```java
// SynapseGrpcExceptionHandlerTest
@Test void thrownStatusKeepsOnlyItsCode()        // SRE(NOT_FOUND "row 7 in table x", trailers {x-internal: 1, grpc-status-details-bin with ErrorInfo domain "other"}) → NOT_FOUND, description "", no x-internal, details == [ErrorInfo(reason NOT_FOUND, our domain, correlationId)]
@Test void thrownStatusKeepsItsDescriptionWithIncludeDetail()   // description and x-internal kept; the foreign details still dropped
// GrpcErrorStatusesTest
@Test void inverseTableMapsEveryCode()           // every Status.Code → C12's code (ABORTED → CONFLICT, OUT_OF_RANGE → VALIDATION_FAILED, DATA_LOSS → INTERNAL, ...)
// GrpcAsyncCompletionIT (a hand-written service)
@Test void onErrorStatusKeepsOnlyItsCode()       // responseObserver.onError(PERMISSION_DENIED "secret") from another thread → PERMISSION_DENIED, description "", ErrorInfo FORBIDDEN with the call's correlation id
@Test void onErrorWithAPlainExceptionStillReachesTheHandlers()   // onError(new IllegalStateException()) → INTERNAL via the handler, logged at ERROR (unchanged)
@Test void boundOperationsStatusIsNotRewritten() // a bound operation's BOOK_NOT_FOUND keeps reason BOOK_NOT_FOUND and its description under include-detail
@Test void grpcAdviceStatusIsLeftAlone()         // a @GrpcAdvice returns FAILED_PRECONDITION "advice" → description "advice"
```

- [ ] **Step 3: Run to verify failure. Step 4: Implement. Step 5: Run to verify pass:** `./mvnw -pl api/synapse-api-grpc,api/synapse-test-grpc,samples/grpc-inventory/service-inventory,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`.
- [ ] **Step 6: Commit:** `feat(grpc)!: keep only the code of a hand-written service's own status unless include-detail`.

### Task 11: The capped page gets a token that fails clearly, and M8

**Files:**
- Modify: `core/synapse-core/src/main/java/.../internal/paging/PageCursors.java` (`next` ~L231-250, `unwrap` ~L166-184, Javadoc), `.../internal/paging/PagingViolations.java`, `api/synapse-api-rest/src/main/java/.../internal/route/ResponseWriter.java` (~L170-177), `.../internal/paging/PageLinks.java` (Javadoc only), `.../internal/paging/PageQueryReader.java:78` (M8).
- Docs: `rest.adoc` *Paging* (the `Link` bullets ~L430-437: past the cap the link carries the token as `cursor`; the violations table row ~L364-365: the new message for a token), `grpc.adoc` ~L539-562 (the `next_page_token` rule list; `has_next` stays required, and "(which allows extra fields)" becomes "AIP-158 doesn't forbid other fields, and its linter doesn't flag them"; the "only signal for the capped case" paragraph is replaced: the token is now empty only at the real end), `core.adoc` *Paging* where it states the cap.
- Test: `core/.../internal/paging/PageCursorsTest.java`, `api/synapse-api-rest/src/test/java/.../internal/paging/{PageQueryReaderTest,RestPagingIT}.java`, `.../internal/route/ResponseWriterTest.java`, `api/synapse-api-grpc/src/test/java/.../internal/paging/GrpcPagingIT.java`.

**Interfaces:**

```java
// PagingViolations
public static String offsetLimitReached(long maxOffset);   // "has reached the offset limit (" + maxOffset + "); narrow the query or use keyset paging"
// PageCursors
/** True when the page has a next page, no next cursor, the request had no cursor, and its offset plus size passes maxOffset. */
public static boolean pastCap(@Nullable Object input, PageResult<?> page, long maxOffset);
// next(...): past the cap returns the sealed offset token (o:<next>) instead of null (C13)
// unwrap(...): an offset token past the cap → VALIDATION_FAILED {field, offsetLimitReached(maxOffset)}
```

- [ ] **Step 1: M8, failing test first:**

```java
// PageQueryReaderTest
@Test void cursorWithRenamedOffsetNamesTheRenamedParameter()   // parameters.offset=skip; ?cursor=x&skip=5 → {cursor, "must not be combined with skip"}
```

  Run it (fails: "offset"), fix `notCombinedWith(settings.offset())`, run it (passes). Commit: `fix(rest): name the configured offset parameter when a cursor is combined with it`.
- [ ] **Step 2: Write the failing tests** (Review Focus 5):

```java
// PageCursorsTest
@Test void nextPastTheCapIsAToken()                       // offset 990, size 20, cap 1000, hasNext → non-null
@Test void capTokenFailsWithTheOffsetLimitMessage()       // unwrap → VALIDATION_FAILED {page_token, "has reached the offset limit (1000); narrow the query or use keyset paging"}
@Test void tokenFromAHigherCapGetsTheSameMessage()
@Test void noTokenAtTheRealEnd()                          // hasNext false → null
@Test void cursorPageWithoutACursorStillHasNoToken()
@Test void nextWithinTheCapIsUnchanged()
// RestPagingIT
@Test void cappedPageLinksTheTokenAsCursor()              // GET ?offset=990&size=20 → Link </…?size=20&cursor=<t>>; rel="next", body nextCursor == <t>, hasNext true
@Test void followingTheCappedLinkIsTheOffsetLimitViolation()   // GET the link → 400 {cursor, the message}
@Test void offsetParameterPastTheCapKeepsItsMessage()     // ?offset=1001 → "must be less than or equal to 1000"
// GrpcPagingIT
@Test void cappedPageHasANextPageToken()
@Test void usingTheCappedTokenIsInvalidArgument()         // FieldViolation("page_token", the message)
@Test void restCappedTokenOverGrpcNamesPageToken()        // an unversioned REST route's capped token sent as page_token
```

- [ ] **Step 3: Run to verify failure. Step 4: Implement.** `ResponseWriter` passes the body token to `addNextLink` when the page has a next cursor or `PageCursors.pastCap(input, page, maxOffset)`.
- [ ] **Step 5: Run to verify pass:** `./mvnw -pl core/synapse-core,api/synapse-api-rest,api/synapse-api-grpc,samples/reference-bookstore/service-bookstore,samples/rest-books -am verify -Dprotobuf…=…`. Re-run any README paging command near the cap (grep `offset=` in `samples/*/README.md`), or report none.
- [ ] **Step 6: Commit:** `feat: send a next-page token at the offset cap that fails clearly when used`.

### Task 12: Docs-only items and spec status

**Files:**
- Modify:
  - `docs/modules/ROOT/pages/grpc.adoc` *Versions* (decision 9: "Synapse emits strong tags only" in the etag table, with BUILD §6.3's reason; update responses: neither protocol returns the new token on an update, clients re-read, a departure from AIP-154 kept for one contract on both protocols; a missing resource is `NOT_FOUND` with or without an `etag`, RFC 9110 §13.2.1);
  - `grpc.adoc` *Errors*, under the CONFLICT row (decision 32, item 3): "A code of your own that means a duplicate create (such as `ISBN_TAKEN`) is `ABORTED` like any `CONFLICT`, which clients may retry at a higher level. Map it to `ALREADY_EXISTS`, which they don't retry: `synapse.api.grpc.errors.status.[ISBN_TAKEN]=ALREADY_EXISTS`." And one line in `core.adoc` next to "Throw `ALREADY_EXISTS` when…" pointing there;
  - `rest.adoc` *Conditional requests* (decision 32, item 2): "An update or delete of a missing resource is 404 whether or not it sends `If-Match`: the request would be 404 without it, and RFC 9110 §13.2.1 says such failures take precedence over preconditions. An upsert that would create the resource answers a false `If-Match` with 412 instead (see above).";
  - `rest.adoc` *Cursor keys* (decision 32, item 5): rotate at least every 2 years (NIST SP 800-57 Part 1 Rev 5 §5.3, through the OWASP Key Management Cheat Sheet) and before 2^31^ tokens under one key (half of SP 800-38D's 2^32^, a stated margin), whichever comes first; set `max-age` (AIP-158's three days as a reference point) and drop the old key from `previous-keys` one `max-age` after the switch; without `max-age`, an old key stays as long as clients may hold its tokens. `grpc.adoc` *Page tokens* links to it;
  - `specs/v2/BUILD.md` §15 P1: the contract follow-up bullet becomes a *Done (contract follow-up)* note: what landed, the C1–C13 IDs, and what only Linux CI or a later run checks;
  - `specs/v2/DESIGN.md` §4.1: the 2026-11-20 rows say "built" with this plan's name where the code differs from what the row says (C11's mechanism).
- [ ] **Step 1: Write the docs.** Every property named exists in the configuration metadata; every RFC/AIP section cited is the one the memos quote.
- [ ] **Step 2: Build the docs** from the repository root: `(cd docs && npm ci && npx antora antora-playbook.yml)`, then `node docs/tools/property-tables.mjs`. Expected: no broken xref, no property row removed.
- [ ] **Step 3: Commit:** `docs: weak etags, update responses, If-Match on a missing resource, the ALREADY_EXISTS override and cursor-key rotation`.

### Task 13: Full build

- [ ] **Step 1: JDK 25, as CI runs it** (background, Docker on; about 18 minutes):

  ```bash
  CI=true ./mvnw clean verify -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

  Expected: BUILD SUCCESS, including the invoker build-rules tests and both archetype ITs.
- [ ] **Step 2: The JDK 21 pull-request leg:**

  ```bash
  ./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke -Djacoco.skip=true \
    -Dfailsafe.excludesFile="$(pwd -W)/.config/jdk21-pr-excluded-its.txt" \
    -Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"
  ```

- [ ] **Step 3: Report** the test counts per module for both runs next to the baseline, and explain every count that went down.
- [ ] **Step 4:** No commit unless a fix was needed; a fix is its own `fix:` commit with a failing test first, and both runs repeat after it.

### Task 14: Final whole-branch review

- [ ] **Step 1:** An Opus review of the branch from its base (the merged docs commit on local `main`) to `HEAD`, with BUILD.md, DESIGN.md, AGENTS.md, this plan and the memos as the authority. Findings ordered by severity, each naming its module or sample and the user-visible effect. It checks especially:
  - parity after Tasks 2–6 and 11: the same code, path and message on both protocols for the same input;
  - that nothing a caller sees carries a map key, a downstream description or an MVC reason without include-detail;
  - that no behaviour change landed without a failing test first;
  - that the decisions table matches what was built, and that the beans named in *Coordination* are the ones that exist.
- [ ] **Step 2: Fix** each accepted finding in its own commit, with a failing test where behaviour changes; a Sonnet re-review per fix; repeat Task 13 after the last fix.
- [ ] **Step 3: Report** to the maintainer: the commits, C1–C13 for confirmation, and anything Linux CI must still check. Squash into local `main` and push only on the maintainer's word.

---

## Not in this plan

| Item | Where |
|---|---|
| `TRANSACTION_CONFLICT` (decision 18) and its REST/gRPC mapping | Data follow-up |
| Map keys and other values in *logged* paths (decision 10) | Observability follow-up (can reuse `ViolationPaths`) |
| The status-mapping SPI and name back-offs for the beans in *Coordination* | API-surface follow-up |
| The client-side origin marker that remaps forwarded gRPC codes (decision 24). The marker is a cause, so a forwarded `UNKNOWN` carrying it matches `ContextClosingCall`'s "UNKNOWN with a non-status cause" rule and would go to the exception handlers (`INTERNAL`, logged at ERROR) instead of being remapped; the P2 design must handle it (Task 10 review) | P2 (BUILD.md P2) |
| Under `include-detail`, a downstream `x-correlation-id` trailer forwarded by a hand-written service survives next to Synapse's when headers were already sent (fix: `CorrelationEchoCall.close` discards the key from the trailers when `headersSent`; Task 10 review) | Deferred (a convenience setting only; Synapse's id is in the headers and `ErrorInfo`) |
| Method security inside an operation: pass Spring Security's exceptions through the pipeline unwrapped, or translate them in core (research memo, decision 13 point 6) | With the security module, deferred to 2.x (BUILD §11) |
| `etag` on gRPC update responses and list items (research 33 Q3, option B) | Not planned: the maintainer kept one contract on both protocols |
