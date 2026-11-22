# P1 follow-ups, data: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the data decisions the maintainer settled on 2026-11-19/20: decisions 7, 11, 17, 18 (end to end), 19, 23 and 28, and the BUILD note for decision 32's item 7. Each item is its own reviewable commit.

**Architecture:**
- Error translation first (Tasks 1-4): 40003 becomes an unknown outcome, then the new `TRANSACTION_CONFLICT` code with its classification in data-core, its vendor hook, the failure line's SQLState key, and both plugins' tests and docs.
- Then store health (Task 5), the per-connection SQL rule (Task 6), the four startup WARNs and the connection-details failure (Tasks 7-9), and the Liquibase docs and spec status (Task 10). Then the full build (Task 11) and the final review (Task 12).
- Every behaviour change starts with a failing test (AGENTS.md *Bug fixes*). Docs that describe a behaviour change are in the same task as the change.
- No task adds a bean. The one bean whose factory method changes (`synapseDataExceptionTranslator`, Task 2) keeps its back-off by name, so the API-surface plan, which runs last and converts the remaining back-offs by type to back-offs by name, has nothing to convert here.

**Tech Stack:** Java 21 (`--release 21`, built on JDK 25), Maven 3.9.16 through the wrapper, Spring Boot 4.1.2 (unchanged), Spring Framework 7.0.10, HikariCP (Boot-managed), PgJDBC 42.7.x (Boot-managed), Flyway 12.4.0, Hibernate 7.4.13, Testcontainers 2.0.5, JUnit 5, AssertJ. No dependency or version changes.

**Spec:**
- The decisions: the maintainer's decision log (2026-11-19 and 2026-11-20 lines) in `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\p5\.superpowers\sdd\2026-11-13-p1-review-fixes\progress.md`, and in the coordinator's scratchpad `C:\Users\Tony\AppData\Local\Temp\claude\ceef885cbf307e16\00343b11-a124-44cb-94d4-0764a5fe68bf\scratchpad\`:
  - `research2-summary.md`, sections C and D (adopted);
  - `research2-data.md`, decisions 7, 11, 17, 18 (classification), 19 and 23: each *Design details the follow-up plan needs settled* is binding;
  - `research2-contract-errors.md`, decision 18 (the code, the protocol mapping and what it promises);
  - `research2-api-surface.md`, decision 28;
  - `research2-plan5-leftovers.md`, item 7.

  Where this plan and a memo differ, this plan wins, and the *Decisions taken for the maintainer's review* table says why.
- The authority on behaviour: [BUILD.md](../BUILD.md) §5.10, §8.1, §8.2 and §15 (P5, P6), as edited in this plan's commit. The reasons: [DESIGN.md](../DESIGN.md) §4.1 (the seven rows dated 2026-11-20 after *Open-in-view*), §5 and §6. Conventions: [AGENTS.md](../../../AGENTS.md) and the last plan ([2026-11-13-p1-review-fixes.md](2026-11-13-p1-review-fixes.md)).

## Execution base

- After Tony's review, the four follow-up plans' docs commits are merged into local `main` as one docs commit, with the DESIGN.md conflicts resolved once (both sides' additions and both sides' deletions kept). Each plan's feature branch starts from local `main` at that commit, or later, once the earlier plans in the order contract, data, observability, API surface are squashed.
- So this plan's branch, `feature/v2-fu-data` in a scratchpad clone, starts from local `main` once the contract plan is squashed into it.
- What the contract plan changes that this plan relies on:
  - On Synapse paths, Synapse answers pre-handler errors (a missing or invalid API version, and the 405/415 of annotated lookup) before any application advice; escape-hatch controllers keep Spring's normal flow, with MVC handlers first. It adds the REST-only codes `CLIENT_ERROR` and `SERVER_ERROR` for kept Spring statuses Synapse has no code for. The REST status is decided by `RestErrorStatuses.statusFor(SynapseException, String operation)`: the configured override, then a kept Spring status, then the category's status.
  - `TRANSACTION_CONFLICT` fits that without a change to either: it is a `SynapseException` an operation's pipeline raises, with no kept Spring status, so it gets an override if one is configured, else its category's 409; the status-to-code table for an MVC `ErrorResponse` still maps 409 to `CONFLICT`, never to `TRANSACTION_CONFLICT`. Task 4 checks both against the contract plan's code, not against `5ea62ec7`.
- The observability plan runs after this one and changes the pipeline's failure line (masking). Task 3 adds a three-line hook to that method (`logFailure`), kept small so that plan rebases over it; it adds no key that plan's masking must handle (an SQLState is a fixed five-character code).
- The API-surface plan runs last. This plan adds no bean (see *Architecture*), so its back-off conversion has nothing new here.
- [ ] **Baseline:** before Task 1, run the JDK 25 full build once (Task 11, Step 1's command) and record the test counts per module and any failure. A failure at the base is reported to the maintainer, not fixed silently in a task.

## Global Constraints

**Branch, JDK, language**
- Never push. Never touch `C:\Users\Tony\VSCode Projects\amex-synapse`. Local `main` gets the squash only when the maintainer says so.
- Before any `./mvnw`: `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"`. For JDK 21 legs: `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never use a global `mvn`. Never `./mvnw install`.
- Every build that reaches `api/synapse-api-grpc` or a gRPC sample adds `-Dprotobuf.sanctioned-executable-path="C:/Users/Tony/AppData/Local/Temp/claude/ceef885cbf307e16/p3x"` (written `-Dprotobuf…=…` below).
- `--release 21`: no Java 22+ APIs. Records for data carriers.
- Every new package has an `@NullMarked` `package-info.java` (this plan adds none). A test package with a main package's name has none.
- Spotless formats locally; never hand-format. The license header goes on every new Java file.
- `LongPathsEnabled` is 0: keep new paths short.
- Don't edit a pom or `.vscode/` while a Maven build runs (VS Code Java builds into the same `target/`).
- Docker steps first run `export PATH="/c/Users/Tony/AppData/Local/Programs/DockerDesktop/resources/bin:$PATH"; export MSYS_NO_PATHCONV=1`. Testcontainers needs none of this.

**Spring and Synapse rules (AGENTS.md)**
- Spring Boot first: never re-implement or alias a Boot property. Logging levels are Boot's `logging.level.*`; this plan adds no Synapse key.
- Every auto-configuration a task touches gets `ApplicationContextRunner` tests: it activates, backs off, binds its properties, and stays absent.
- An internal Synapse bean backs off by bean name, and the docs don't call it replaceable (BUILD §3.5, the T10 rule).
- No production concept: the environment is never used in a `@Conditional`, and the docs never suggest `@Profile`.
- Errors never expose stack traces, class names or internal messages. A log line names a key or a bean, never a property value, a URL or SQL.
- Protocol concerns live only in the API plugins. Data-core's code is protocol-neutral; REST's problem `code` equals gRPC's `ErrorInfo.reason`.

**Data rules this plan sets (BUILD §8.1, §8.2)**
- A store connects where its `url`, `username` and `password` say, and as that login user. Bans on targets, login users and secrets stay exactly as they are.
- Startup WARNs about beans and settings are never failures, except the one failure Task 9 extends (a store without a `url`).
- Every WARN this plan adds is logged once per store and key or bean at startup, sorted, on a sub-logger a team can silence with `logging.level`: bean WARNs on `io.americanexpress.synapse.data.ignored`, per-connection SQL WARNs on `io.americanexpress.synapse.data.connectionsql` (D6).

**Tests and builds**
- `*Test` are unit tests (surefire); `*IT` are integration tests (failsafe). Neither is ever skipped; `-DskipTests` is never a passing build.
- JaCoCo floors: 80% line, 70% branch per module; 90% of changed lines covered.
- To iterate (neither counts as passing):

  ```bash
  ./mvnw -q -pl <module> -am test -Dtest=Class -Dsurefire.failIfNoSpecifiedTests=false
  ./mvnw -q -pl <module> -am verify -Dit.test=ClassIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
  ```

  Each task ends with `./mvnw -pl <its modules> -am verify` (plus the protobuf flag where needed), Docker on.
- A task that changes a sample re-runs the affected README commands against the running app; if no command's output can change, the task report says why.

**Commits**
- Conventional Commits; `!` marks a breaking change. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage explicit paths only. Never `git add -A`, stash, amend or push. Leave no `*.bak` or `*.orig` files.

**Execution** (the maintainer's standing rules): every task is implemented on Opus. Each task gets an Opus review; Sonnet only for a fix's re-review. Task 12 is an Opus whole-branch review. At most five subagents at once.

---

## Decisions taken for the maintainer's review

Each row is a choice the decisions and memos left open, made so the task can run. Each can be reversed at review; the last column says what that costs.

| # | Item | Question left open | Choice | Reason | Alternative (cost to reverse) |
|---|---|---|---|---|---|
| D1 | 18 | How the pipeline's WARN line gets the SQLState (contract memo, detail 5), when core knows no SQL. | A core-internal interface `FailureLogKeys` (in `core.internal.pipeline`); `logFailure` adds its key-values. Data-core's internal `TransactionConflictException extends SynapseException implements FailureLogKeys` carries `sqlState` (and `sqlErrorCode` when a vendor recognised the code). | Core doesn't know data's error codes or which ones are conflicts; no public API; the key never reaches the caller. | No key (metrics tag the code already): drop Task 3. |
| D2 | 7 | Whether the transaction managers treat 40003 at commit as an unknown outcome too, or only the translator. | Both managers throw `CommitOutcomeUnknownException` for 40003 at commit, as for a lost connection (`LostConnections.commitOutcomeUnknown`). | The manager's exception is what code outside an operation (a `TransactionTemplate`) sees; 40003 means the same there. | Translator only (one call in each manager). |
| D3 | 7 | Move class 53 behind the vendor now ("unless trivial")? | Not now: a BUILD P5 note (in this plan's spec edits). | A per-vendor rule needs each vendor to recognise its own driver's exception type, a design the first P5 vendor makes anyway; `DataStoreVendor` is `@Incubating`, so a default method later is additive. | Move it in Task 2 with the conflict hook (one method, the PostgreSQL vendor checks `PSQLException` and class 53). |
| D4 | 18 | How the translator reaches the vendors. | `DataExceptionTranslator(List<DataStoreVendor>)`; the bean takes `ObjectProvider<DataStoreVendor>` (ordered stream). The no-argument constructor stays, with no vendors, for tests. | The translator has no store context; every vendor is asked, and each recognises only its own driver's exceptions. | — |
| D5 | 18 | The message template. | `The request conflicted with a concurrent request and was not applied` (Tony, fix round 1) | It names neither locks nor deadlocks, and it doesn't tell every caller to resend, since work outside the store's transaction is not undone. | Any other text (one constant). |
| D6 | 11, 23 | Sub-loggers, and which WARNs move. | `io.americanexpress.synapse.data.ignored` for every "not used by any store" or "has no effect" bean WARN, the existing R9 (`Callback`/`JavaMigration`) and R10 (JPA builder beans) ones included; `io.americanexpress.synapse.data.connectionsql` for Task 6 (no hyphen, so `LOGGING_LEVEL_IO_AMERICANEXPRESS_SYNAPSE_DATA_CONNECTIONSQL` can set it too). The budget INFO line, the ignored-keys WARNs and the transaction-manager WARN stay on `io.americanexpress.synapse.data`. | One `logging.level` silences one kind of WARN; the memos recommend both loggers. | Only the four new WARNs move. |
| D7 | 11 | Which auto-configured beans are left out, and how they are recognised without creating them. | Only Spring Boot's own (Tony, fix round 1): the bean has a factory method, and that method's declaring class (read as `FactoryMethods` reads definitions, also in AOT mode) is in a package under `org.springframework.boot.`, which also covers the configurations Boot's auto-configurations import (such as `DataSourceConfiguration`). The bean class never counts (fix round 2, N2): a bean without a factory method is not Boot's, so the `@ServiceConnection` details that Boot registers as `new RootBeanDefinition(detailsClass, supplier)` (`jdbcConnectionDetailsFor…`) are named by WARN 4 and the no-`url` failure. Only that registrar was checked (javap of `ConnectionDetailsRegistrar` in `spring-boot-testcontainers-4.1.2.jar`, plan review round 1), and `ServiceConnectionIT` covers only it. Docker Compose's details are expected to be registered the same way, but its registrar was not checked. The rule is safe either way: a bean without a factory method is never treated as Boot's. Any other auto-configured bean that no store uses gets the WARN, which then names its defining class: a definition from a class listed by `ImportCandidates.load(AutoConfiguration.class, …)`, or nested in one, adds ` It is defined by the auto-configuration <class>.` | A company platform starter that auto-configures a bean no store uses is the case the WARN exists for; naming the auto-configuration tells the team where the bean comes from. Task 7's test confirms which Boot beans exist next to stores. | Every listed auto-configuration excluded (silent for platform starters). |
| D8 | 11 | Where the store-scoped types are declared. | `StoreInfrastructure.storeScopedTypes()` (JPA: the two naming strategies and `HibernatePropertiesCustomizer`; JDBC: `NamingStrategy` and `JdbcCustomConversions`); data-core adds `FlywayConfigurationCustomizer` for every access kind. | Each module already owns its types' lookups. | A table in data-core (names JPA and JDBC types it doesn't depend on). |
| D9 | 11 | WARN 1's text when an unqualified single-choice bean is unused because every store has its own or a qualified one, or a store-qualified one loses to the store's own bean. | `… is not used by any store: every JPA store uses its own or a qualified one.` and `… its qualifier names the store 'books', which uses its own booksPhysicalNamingStrategy.` | The memo's variants don't cover these; same form. | — |
| D10 | 17 | Details of the progress rule. | The window is Hikari's `connectionTimeout` capped at 30 s (Hikari stores `0` as `Integer.MAX_VALUE`, so it caps too). The last completed borrow, from either path, is remembered; before any has completed, a saturated store is UP while its borrow is younger than the window. DOWN texts: `The connection failed.` and `No connection within <window> ms.` | The memo's rule, with the start-up case decided on the safe side for readiness (UP). | DOWN until a first borrow completes (a pod saturated at startup would never become ready). |
| D11 | 23 | How data-core knows a vendor's session-settings parameter. | `DataStoreVendor.sessionSettingsProperties()` (default empty; PostgreSQL `options`), matched in any case as driver properties, and as URL parameters through a new `JdbcUrls.parameterNames`. The WARN text names the database product (`databaseProductName()`, else `name()`) and leaves out PostgreSQL's `-c role` examples, which `data.adoc` gives. | One hook for both forms; P5 vendors fill it. | PostgreSQL-only code in data-core. |
| D12 | 19 | The memo's optional "(from Docker Compose or @ServiceConnection)" hint. | Left out. | It needs Boot's `ContainerImageMetadata` type, which this plan didn't verify; the bean name already says it (`jdbcConnectionDetailsFor…`). | Add it after reading the type in Boot 4.1.2. |
| D13 | 19 | The public helper's shape. | `public final class StoreConnections` in `io.americanexpress.synapse.test.data`, with `static JdbcConnectionDetails of(String store, JdbcDatabaseContainer<?> container)`; the store name is only for `toString()`. The API-surface plan's `@Incubating` pass covers it. | The memo's helper; the name keeps the string form that never shows the URL. | `of(container)` with a generic string form. |
| D14 | 18 | Where the protocol mapping is tested, when the plugins don't depend on data-core. | The reference bookstore's `BookstoreRestTest` and `BookstoreGrpcTest` (both plugins, data-core, mocked operations), with `DataErrors.TRANSACTION_CONFLICT` itself. | Tests the real code, its category and both protocols' answers in one module. | A stub code in each plugin's own tests (proves only the category). |
| D15 | 28 | Whether `data.adoc` says more than the memo's wording. | The memo's three sentences only. | The memo leaves more as optional; factual is enough. | Add "setting it to `true` runs Boot's Liquibase outside the store model, unsupported". |
| D16 | 19 | How the failure lists many beans. | Up to 5 names, then `and N more`, as `UnqualifiedTransactionCheck` lists methods. | The memo's "as the transaction check does". | — |

---

## Review Focus

These are the inputs the decisions imply but no single happy-path test covers, most likely first. Each one is pinned by a test in the task named.

1. **An ambiguous commit dressed as a lock failure.** A 40003 under Spring's `PessimisticLockingFailureException`, or a 40001 below a lost connection at commit, must be `COMMIT_OUTCOME_UNKNOWN`, never a retryable `TRANSACTION_CONFLICT`. Pinned in Tasks 1 and 2: `statementCompletionUnknownUnderALockTypeIsOutcomeUnknown`, `aConflictBelowALostConnectionAtCommitIsOutcomeUnknown`.
2. **Class 40 that is not a conflict.** A deferred constraint failing at commit (40002) under `PessimisticLockingFailureException`, and a bare 40000, stay `INTERNAL`. Pinned in Task 2: `integrityFailureAtCommitIsNotAConflict`, `bareClass40IsNotAConflict`.
3. **A saturated but healthy pool under ordinary load.** With threads waiting and connections still handed off, the store stays UP and the probe returns at once; only a pool that serves no connection for its `connectionTimeout` is DOWN. Pinned in Task 5: `aSaturatedHealthyPoolIsUpWithoutWaiting` and the `PostgresStoreReadinessIT` cases.
4. **Correct setups stay quiet.** Spring Boot's own auto-configured beans next to stores (another library's auto-configured bean that no store uses does WARN, naming its auto-configuration), the source bean of a store-qualified adapter, a bean qualified for an existing store of the right kind, and a Flyway bean guarded on `migrations.enabled` log no WARN. Pinned in Tasks 7-9: `bootsOwnBeansDoNotWarn`, `anAdaptersSourceBeanDoesNotWarn`, `aBeanQualifiedForItsStoreDoesNotWarn`, `aGuardedFlywayBeanDoesNotWarn`.
5. **A secret in per-connection SQL.** `connection-init-sql: SET app.key = 'secret'` starts with one WARN that names the key, and the output never contains the value. Pinned in Task 6: `initSqlWarnsWithoutItsValue`.

---

## File structure

New files (main code), with `DC` = `data/synapse-data-core/src/main/java/io/americanexpress/synapse/data`:

| Path | Responsibility |
|---|---|
| `DC/internal/error/TransactionConflicts.java` | 18: is this exception a transaction conflict |
| `DC/internal/error/TransactionConflictException.java` | 18, D1: the conflict's `SynapseException`, with its log keys |
| `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/FailureLogKeys.java` | D1: key-values a failure adds to the pipeline's failure line |
| `DC/internal/store/ConnectionSql.java` | 23: the per-connection SQL WARNs |
| `DC/internal/store/StoreScopedType.java` | 11, D8: a bean type stores pick per store (record) |
| `DC/internal/store/AutoConfiguredBeans.java` | 11, D7: is a bean defined by Spring Boot, and which auto-configuration defines it |
| `DC/internal/store/UnusedStoreBeans.java` | 11: WARNs 1 and 3 |
| `DC/internal/store/UnusedConnectionDetails.java` | 11 WARN 4 and 19: the `JdbcConnectionDetails` no store uses |
| `data/synapse-test-data/src/main/java/io/americanexpress/synapse/test/data/StoreConnections.java` | 19, D13 |

Everything else modifies existing files, listed in each task.

---

### Task 1: SQLState 40003 is an unknown commit outcome (decision 7)

**Files:**
- Modify: `DC/internal/error/LostConnections.java` (`outcomeUnknown`: 08007 or 40003; new `commitOutcomeUnknown`), `DC/internal/error/DataExceptionTranslator.java` (Javadoc), `data/synapse-data-jdbc/src/main/java/io/americanexpress/synapse/data/jdbc/SynapseJdbcTransactionManager.java` and `data/synapse-data-jpa/src/main/java/io/americanexpress/synapse/data/jpa/SynapseJpaTransactionManager.java` (the `LostConnections.causedBy` call in `doCommit` becomes `commitOutcomeUnknown`, D2; the JPA manager's flush before the commit keeps `causedBy`, since 40003's ambiguity is a COMMIT's; Javadoc), `DC/CommitOutcomeUnknownException.java` (Javadoc: or the database said the completion is unknown), `docs/modules/ROOT/pages/data.adoc` (*Error translation*: the `COMMIT_OUTCOME_UNKNOWN` row names 40003; *A connection lost while committing*: a sentence that CockroachDB-style ambiguous commits report 40003; the class-53 row says 53400 (configuration_limit_exceeded) is `UNAVAILABLE` too, though a statement can exceed it every time).
- Test: `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/error/{LostConnectionsTest,DataExceptionTranslatorTest}.java`, `data/synapse-data-jdbc/src/test/java/io/americanexpress/synapse/data/jdbc/SynapseJdbcTransactionManagerTest.java`, `data/synapse-data-jpa/src/test/java/io/americanexpress/synapse/data/jpa/SynapseJpaTransactionManagerTest.java`.

**Interfaces:**
- Produces (in `LostConnections`):

```java
/** Whether a commit's failure leaves its outcome unknown: causedBy(failure), or an SQLException of state 08007 or 40003 in the first CauseChain.MAX_DEPTH causes. */
public static boolean commitOutcomeUnknown(Throwable failure);
```

- `outcomeUnknown(Throwable)` keeps its signature and matches `40003` next to `08007`.

- [ ] **Step 1: Write the failing tests** (Review Focus 1):

```java
// DataExceptionTranslatorTest
@Test void statementCompletionUnknownIsOutcomeUnknown()                  // TransactionSystemException(SQLException("x", "40003")) → COMMIT_OUTCOME_UNKNOWN
@Test void statementCompletionUnknownUnderALockTypeIsOutcomeUnknown()    // PessimisticLockingFailureException wrapping 40003 → COMMIT_OUTCOME_UNKNOWN
// LostConnectionsTest
@Test void statementCompletionUnknownIsNotALostConnection()              // isLostConnection("40003") is false; commitOutcomeUnknown(40003 chain) is true
// SynapseJdbcTransactionManagerTest and SynapseJpaTransactionManagerTest
@Test void aCommitWhoseCompletionIsUnknownHasAnUnknownOutcome()          // commit fails with SQLException state 40003 → CommitOutcomeUnknownException, no rollback attempted
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest='LostConnectionsTest,DataExceptionTranslatorTest' -Dsurefire.failIfNoSpecifiedTests=false`, and the manager tests in `data/synapse-data-jdbc` and `data/synapse-data-jpa`. Expected: `INTERNAL` where `COMMIT_OUTCOME_UNKNOWN` is expected; the managers roll back.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jdbc,data/synapse-data-jpa,data/synapse-data-postgres -am verify`, Docker on.
- [ ] **Step 5: Commit**: `fix(data): treat SQLState 40003 as an unknown commit outcome`.

### Task 2: `TRANSACTION_CONFLICT`, classified by exact SQLState (decision 18, data side)

**Files:**
- Create: `DC/internal/error/TransactionConflicts.java`.
- Modify:
  - `DC/DataErrors.java` (the constant, D5, with Javadoc: the store's transaction was rolled back, so resending the same request is safe as far as the store is concerned; side effects outside it, such as calls and messages, are not undone; distinct from `STALE_VERSION`, which needs a re-read);
  - `DC/vendor/DataStoreVendor.java` (the hook);
  - `DC/internal/error/DataExceptionTranslator.java` (vendors, D4; the check's place; Javadoc);
  - `DC/autoconfigure/SynapseDataAutoConfiguration.java` (`synapseDataExceptionTranslator(ObjectProvider<DataStoreVendor> vendors)`, still `@ConditionalOnMissingBean(name = DATA_EXCEPTION_TRANSLATOR)`);
  - `data/synapse-data-postgres/src/test/java/io/americanexpress/synapse/data/postgres/PostgresErrorTranslationIT.java` (class Javadoc; the four lock and serialization cases);
  - `docs/modules/ROOT/pages/data.adoc`: *Error translation* (a `TRANSACTION_CONFLICT` row; the observed table's 55P03, 40001 and 40P01 rows become `TRANSACTION_CONFLICT`; remove "Whether lock failures should become a retryable code is still open"), and a new section `[#transaction-conflict]` *A transaction that lost a race: `TRANSACTION_CONFLICT`* after *A connection lost while committing* (the members and non-members, the precedence, the commit case, what the code promises, retry advice: retry the whole operation at once with jitter and a small attempt cap, safe for an operation that is one transaction; on databases that roll back only the statement, an operation that catches the failure and continues, or that ran several auto-committed statements, has partly committed work).
- Test: create `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/error/TransactionConflictsTest.java`; modify `DataExceptionTranslatorTest`, `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/autoconfigure/SynapseDataAutoConfigurationTest.java`, `SynapseJdbcTransactionManagerTest`, `SynapseJpaTransactionManagerTest`, `data/synapse-data-jpa/src/test/java/io/americanexpress/synapse/data/jpa/internal/JpaExceptionTranslatorTest.java`, `PostgresErrorTranslationIT`.

**Interfaces:**
- Produces:

```java
// DataErrors
TRANSACTION_CONFLICT("The request conflicted with a concurrent request and was not applied", ErrorCategory.CONFLICT)
// DataStoreVendor
/** Whether the driver's exception is a transaction conflict by a vendor code (1205 means different things per vendor, so a vendor checks its own driver's exception type). False by default. */
default boolean transactionConflict(SQLException exception) { return false; }
// TransactionConflicts (internal, public for the data modules)
public static final Set<String> STATES = Set.of("40001", "40P01", "55P03");
public TransactionConflicts(List<DataStoreVendor> vendors);
/** The exception itself, not its causes: an SQLException whose state is in STATES, or that a vendor recognises. */
public boolean isConflict(Throwable exception);
// DataExceptionTranslator
public DataExceptionTranslator();                               // no vendors
public DataExceptionTranslator(List<DataStoreVendor> vendors);
```

- `errorFor` order: unknown outcome, `STALE_VERSION`, `ALREADY_EXISTS`, `DEADLINE_EXCEEDED`, **`TRANSACTION_CONFLICT`**, then the lost-connection and class-53 check. `CauseChain` walks the causes, so the predicate looking only at `SQLException`s finds the state under any wrapper. Never test `PessimisticLockingFailureException` or `CannotAcquireLockException`.
- The PostgreSQL vendor keeps the default (data-core's states cover it); `JpaExceptionTranslator` is unchanged.

- [ ] **Step 1: Write the failing tests** (Review Focus 1 and 2):

```java
// TransactionConflictsTest
@ParameterizedTest @ValueSource(strings = {"40001", "40P01", "55P03"}) void conflictStates(String state)
@ParameterizedTest @ValueSource(strings = {"40000", "40002", "40003", "23505", "23P01", "57014", "08006"}) void otherStatesAreNot(String state)
@Test void aNonSqlExceptionIsNot()                       // a PessimisticLockingFailureException itself → false (only its SQLException cause counts)
@Test void aVendorRecognisesItsOwnCode()                 // a stub vendor whose transactionConflict returns true for errorCode 1205 → true; without it → false
// DataExceptionTranslatorTest (@ParameterizedTest where natural)
@Test void serializationFailureIsAConflict()             // CannotAcquireLockException(SQLException 40001) → TRANSACTION_CONFLICT
@Test void deadlockIsAConflict()                         // PessimisticLockingFailureException(40P01)
@Test void lockNotAvailableIsAConflict()                 // UncategorizedSQLException(55P03)
@Test void aConflictAtCommitIsAConflict()                // TransactionSystemException whose application exception is a 40001 DataAccessException
@Test void integrityFailureAtCommitIsNotAConflict()      // PessimisticLockingFailureException(40002) → INTERNAL (translate returns null)
@Test void bareClass40IsNotAConflict()                   // 40000 → null
@Test void aConflictBelowALostConnectionAtCommitIsOutcomeUnknown()   // CommitOutcomeUnknownException(… 40001 …) → COMMIT_OUTCOME_UNKNOWN
@Test void aTimeoutIsNotAConflict()                      // QueryTimeoutException(57014) → DEADLINE_EXCEEDED
// JpaExceptionTranslatorTest (data translator first, as the pipeline orders them)
@Test void jpaLockExceptionsWithAConflictStateAreConflicts()   // jakarta PessimisticLockException and LockTimeoutException over Hibernate's exception over SQLException 40P01 / 55P03
@Test void aJpaLockExceptionWithoutAStateStaysInternal()        // no SQLException in the chain → neither translator answers
// SynapseJdbcTransactionManagerTest and SynapseJpaTransactionManagerTest
@Test void aSerializationFailureAtCommitIsAConflict()    // commit throws SQLException 40001 → not CommitOutcomeUnknownException; the translator gives TRANSACTION_CONFLICT
// SynapseDataAutoConfigurationTest
@Test void theTranslatorAsksTheVendors()                 // a DataStoreVendor bean whose hook returns true → the bean translates a plain SQLException("x", "99999") to TRANSACTION_CONFLICT
// (keep registersTheDataExceptionTranslator, aUserBeanNamedLikeTheTranslatorReplacesSynapses, and the FilteredClassLoader(HikariDataSource) absence test green)
// PostgresErrorTranslationIT: lockTimeoutIsInternal, jpaLockTimeoutIsInternal, serializationFailureIsInternal, deadlockIsInternal
//   become …IsAConflict, asserting TRANSACTION_CONFLICT with the same SQLStates; add
@Test void serializableConflictAtCommitIsAConflict()     // two SERIALIZABLE transactions with read/write skew; the second commit fails 40001 → TRANSACTION_CONFLICT
```

- [ ] **Step 2: Run to verify failure:** the unit tests above, then `./mvnw -q -pl data/synapse-data-postgres -am verify -Dit.test=PostgresErrorTranslationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true`. Expected: compile errors, then `INTERNAL` where `TRANSACTION_CONFLICT` is expected.
- [ ] **Step 3: Implement.** If PostgreSQL raises the read/write-skew failure at the last statement rather than at COMMIT, keep the IT asserting the code and record where it was raised; the manager unit tests above are what pin the commit path.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jdbc,data/synapse-data-jpa,data/synapse-data-postgres,tests/synapse-data-mixed-tests -am verify`, Docker on.
- [ ] **Step 5: Commit**: `feat(data)!: report serialization failures, deadlocks and lock timeouts as TRANSACTION_CONFLICT`.

### Task 3: The conflict's failure line carries its SQLState (decision 18, D1)

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/FailureLogKeys.java`, `DC/internal/error/TransactionConflictException.java`.
- Modify: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/pipeline/OperationInterceptor.java` (`logFailure`: after the existing keys, add each of the failure's `FailureLogKeys`; Javadoc), `DC/internal/error/DataExceptionTranslator.java` (`translate` returns a `TransactionConflictException` for `TRANSACTION_CONFLICT`), `docs/modules/ROOT/pages/data.adoc` (*TRANSACTION_CONFLICT*: the WARN line and its `sqlState` key; repeated 40P01s point to a lock-ordering bug), `docs/modules/ROOT/pages/core.adoc` (the failure line's keys: a module may add its own, for example `sqlState`).
- Test: `core/synapse-core/src/test/java/io/americanexpress/synapse/core/internal/pipeline/OperationInterceptorTest.java`, `DataExceptionTranslatorTest`, `core/synapse-logging-log4j2/src/shared-it/java/io/americanexpress/synapse/logging/lines/FailureLineStructuredFormatIT.java` (it runs on Log4j2 and, through `tests/synapse-logging-logback-tests`, on Logback).

**Interfaces:**

```java
// core internal.pipeline
/** A failure that adds key-values to the pipeline's failure line. Keys have no dot and never share a name with an MDC entry; values are safe to log. Never sent to the caller. Internal. */
public interface FailureLogKeys { Map<String, String> failureLogKeys(); }
// data internal.error
public final class TransactionConflictException extends SynapseException implements FailureLogKeys {
    TransactionConflictException(Throwable cause, @Nullable String sqlState, @Nullable Integer vendorErrorCode);
    // failureLogKeys(): "sqlState" when non-null; "sqlErrorCode" only when a vendor recognised the code
}
// TransactionConflicts gains: the first conflicting SQLException in a chain (CauseChain.MAX_DEPTH), for the keys
public @Nullable SQLException conflict(Throwable failure);
```

- [ ] **Step 1: Write the failing tests:**

```java
// OperationInterceptorTest
@Test void aFailuresOwnLogKeysAreOnTheLine()          // an operation throws a SynapseException subclass implementing FailureLogKeys {"sqlState": "40P01"} → the WARN line has the key; no stack trace
// DataExceptionTranslatorTest
@Test void aConflictCarriesItsState()                 // 40P01 → TransactionConflictException, failureLogKeys() == {sqlState=40P01}; getMessage() has no state
// FailureLineStructuredFormatIT (each Format)
@Test void writesTheTransactionConflictLine()         // an operation throws PessimisticLockingFailureException(SQLException 40P01) → WARN, no stack trace, code TRANSACTION_CONFLICT, category CONFLICT, sqlState 40P01, and the line is written (not dropped by the encoder)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest=OperationInterceptorTest -Dsurefire.failIfNoSpecifiedTests=false`, the data-core unit test, then the shared IT through `./mvnw -pl core/synapse-logging-log4j2,tests/synapse-logging-logback-tests -am verify`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl core/synapse-core,data/synapse-data-core,core/synapse-logging-log4j2,tests/synapse-logging-logback-tests -am verify`.
- [ ] **Step 5: Commit**: `feat(data): log a transaction conflict's SQLState on the operation's failure line`.

### Task 4: `TRANSACTION_CONFLICT` over REST and gRPC (decision 18, protocol side)

No plugin code changes: the `CONFLICT` category is already REST 409 and gRPC `ABORTED`, and neither plugin sends `Retry-After` or `RetryInfo` for it. This task pins that, after the contract plan's resolver rewrite, and documents it.

**Files:**
- Modify:
  - `samples/reference-bookstore/service-bookstore/src/test/java/io/americanexpress/synapse/samples/bookstore/api/rest/BookstoreRestTest.java` and `.../api/grpc/BookstoreGrpcTest.java` (D14);
  - `docs/modules/ROOT/pages/rest.adoc` (after the category table: the data modules' `TRANSACTION_CONFLICT` is 409 without `Retry-After`; RFC 9110 defines none for 409);
  - `docs/modules/ROOT/pages/grpc.adoc` (after the category table: `TRANSACTION_CONFLICT` is `ABORTED` without `RetryInfo`; AIP-194: retry the whole operation, not the single call; mesh and gRPC retry policies don't retry `ABORTED` by default);
  - `docs/modules/ROOT/pages/core.adoc` (*Errors*: one sentence pointing to the data modules' codes, `COMMIT_OUTCOME_UNKNOWN` and `TRANSACTION_CONFLICT`, in `data.adoc`).
- Test: the two sample tests.

- [ ] **Step 1: Write the tests** (they pin behaviour that exists by category, so they may pass at once; record that in the report):

```java
// BookstoreRestTest
@Test void aTransactionConflictIs409WithoutRetryAfter()   // mocked update operation throws new SynapseException(DataErrors.TRANSACTION_CONFLICT) → 409, problem code TRANSACTION_CONFLICT, no Retry-After header, no detail unless include-detail
// BookstoreGrpcTest
@Test void aTransactionConflictIsAbortedWithoutRetryInfo() // same → ABORTED, ErrorInfo.reason TRANSACTION_CONFLICT, no RetryInfo in grpc-status-details-bin
```

- [ ] **Step 2: Run:** `./mvnw -q -pl samples/reference-bookstore/service-bookstore -am test -Dtest='BookstoreRestTest,BookstoreGrpcTest' -Dsurefire.failIfNoSpecifiedTests=false -Dprotobuf…=…`. If either fails, the contract plan's resolver order changed the answer: stop and report it with the response; don't patch around it.
- [ ] **Step 3: Write the docs.** Check against the contract plan's code that an MVC `ErrorResponse` 409 still maps to `CONFLICT` (not to `TRANSACTION_CONFLICT`) and that `CLIENT_ERROR` and `SERVER_ERROR` don't apply to a `SynapseException`; say so in the report.
- [ ] **Step 4: Run to verify:** `./mvnw -pl samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`, Docker on. No README command's output changes (only tests and docs change); say so in the report.
- [ ] **Step 5: Commit**: `test(samples): pin TRANSACTION_CONFLICT as 409 and ABORTED without retry hints`, with the docs as `docs: document TRANSACTION_CONFLICT over REST and gRPC`.

### Task 5: Store readiness under saturation, judged by progress (decision 17)

**Files:**
- Modify: `DC/internal/health/StoreHealthIndicator.java` (the saturated path; class Javadoc), `docs/modules/ROOT/pages/data.adoc` (*Health and readiness*: replace "When the pool has connections and none is idle, the indicator is `UP` with `pool: busy`, without borrowing one" with the rule; the dead-database consequence (DOWN within about one `connectionTimeout` plus one probe period); and the probe budget: Kubernetes' default `timeoutSeconds` is 1 s and `health.timeout` defaults to 1 s, so the unsaturated path can use the whole budget: set the probe's `timeoutSeconds` above `health.timeout`, or lower `health.timeout`).
- Test: `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/health/StoreHealthIndicatorTest.java`; create `data/synapse-data-postgres/src/test/java/io/americanexpress/synapse/data/postgres/PostgresStoreReadinessIT.java`.

**Interfaces:**
- The package-private constructor gains the clock: `StoreHealthIndicator(String store, DataSource dataSource, Duration timeout, ThreadFactory threads, LongSupplier nanoTime)`; the public constructor passes `System::nanoTime`.
- Produces (package-private): `Duration saturationWindow()`: the pool's `getConnectionTimeout()`, capped at 30 s (D10); `null` pool → not used.
- The saturated path (`total > 0 && idle == 0`): start a borrow if none is in flight (the existing single flight, now also recording its start time), never wait on it, and answer:
  - DOWN `The connection failed.` when the last completed borrow failed or found an invalid connection;
  - DOWN `No connection within <window ms> ms.` when the borrow in flight started longer than the window ago;
  - otherwise UP with `pool: busy` and `waiting: <getThreadsAwaitingConnection()>`.
- The unsaturated path is unchanged, except that a completed borrow is remembered as the last result.

- [ ] **Step 1: Write the failing tests** (Review Focus 3; a stub `HikariDataSource` subclass or Mockito spy gives the MXBean values and `getConnectionTimeout()`):

```java
// StoreHealthIndicatorTest
@Test void aSaturatedHealthyPoolIsUpWithoutWaiting()     // saturated, 3 waiting, borrow blocked in getConnection on a CountDownLatch → health() returns UP {pool=busy, waiting=3} while the latch is still closed (no wall-clock bound)
@Test void aSaturatedPoolStartsOneBackgroundBorrow()     // three checks → one borrow thread created
@Test void aSaturatedPoolIsDownWhenTheBorrowOutlivesTheWindow()   // connectionTimeout 2 s, nanoTime advanced 2.1 s → DOWN "No connection within 2000 ms."
@Test void aSaturatedPoolIsDownWhenTheLastBorrowFailed() // last borrow threw SQLTransientConnectionException → DOWN "The connection failed."
@Test void aSaturatedPoolRecoversWhenABorrowSucceeds()   // after the failure, a borrow completes valid → UP busy
@Test void theWindowIsTheConnectionTimeoutCappedAt30Seconds()     // 5 s → 5 s; 60 s → 30 s; Integer.MAX_VALUE ms (Hikari's 0) → 30 s
@Test void aStoreSaturatedBeforeAnyBorrowCompletedIsUp() // D10
// keep aBusyPoolStaysUp, aBusyWrappedPoolStaysUp and every unsaturated test green
// PostgresStoreReadinessIT (pool maximum-pool-size 2, connection-timeout 2s, health.readiness true)
@Test void aSaturatedPoolThatServesConnectionsIsUp()     // 6 threads loop "SELECT pg_sleep(0.2)" for 5 s; every check during the run is UP (busy), and each check returns while the pool is still saturated (getIdleConnections() == 0 read after health() returns), not after a borrow
@Test void aPoolThatServesNoConnectionIsDownWithinItsTimeout()    // 2 connections held in "SELECT pg_sleep(30)", plus waiters → poll health() every 200 ms for at most 15 s until DOWN, and DOWN never before connection-timeout has passed since saturation; UP again after the sleeps are cancelled (pg_cancel_backend)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest=StoreHealthIndicatorTest -Dsurefire.failIfNoSpecifiedTests=false`, then the IT command for `data/synapse-data-postgres` with `-Dit.test=PostgresStoreReadinessIT`. Expected: UP where DOWN is expected; no `waiting` detail.
- [ ] **Step 3: Implement.** If `aSaturatedPoolThatServesConnectionsIsUp` reports DOWN because Hikari doesn't hand a returned connection to a waiting thread first (the memo's uncertainty), stop and report the measured borrow times; don't widen the window.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-postgres -am verify`, Docker on. `StoreReadinessIT` stays green.
- [ ] **Step 5: Commit**: `fix(data): judge a saturated store's readiness by whether its pool still serves connections`.

### Task 6: SQL that runs on each connection is allowed with a WARN (decision 23)

**Files:**
- Create: `DC/internal/store/ConnectionSql.java`.
- Modify:
  - `DC/internal/store/StoreFactory.java` (`rejectConnectionOverrides` no longer throws for `connection-init-sql` and `connection-test-query`; `dataSource` WARNs for them and for the vendor's session-settings property and URL parameter; `rejectBlockedDriverProperties` unchanged);
  - `DC/internal/store/StoreKeyOverride.java` (remove `initSql`, `testQuery` and `connectionSql`);
  - `DC/internal/migration/FlywayStoreMigration.java` (`init-sql` and a customizer's `initSql` WARN instead of failing; remove `customizedInitSql`);
  - `DC/internal/store/JdbcUrls.java` (`parameterNames`);
  - `DC/vendor/DataStoreVendor.java` (the hook, D11);
  - `data/synapse-data-postgres/src/main/java/io/americanexpress/synapse/data/postgres/PostgresVendor.java` (`options` leaves `BLOCKED_DRIVER_PROPERTIES`; `sessionSettingsProperties()` returns it; class and method Javadoc: "where and as which login user");
  - `docs/modules/ROOT/pages/data.adoc`: *Rejected keys* opens with "A store's `url`, `username` and `password` say where it connects and as which login user, in one place"; the init-SQL bullet and its table row move to a new subsection `[#connection-sql]` *Keys that WARN* (the five WARN texts, the sub-logger, PostgreSQL's `-c role=…` and `-c search_path=…` as what the WARN is about, that `options=-c search_path=…` can reach what the banned `currentSchema` property would, and what a failing init SQL does, from Step 1's last test); *PostgreSQL*: `options` leaves the rejected list and is named under *Keys that WARN*; *Migrations*: the customizer `initSql` sentence becomes the WARN.
- Test: `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/store/{StoreChecksTest,VendorChecksTest,JdbcUrlsTest}.java` (`StoreChecksTest` holds today's `hikariConnectionInitSqlIsRejected` and `hikariConnectionTestQueryIsRejected`), `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/migration/FlywayStoreMigrationTest.java` (today's `initSqlIsRejected` and `aCustomizerThatSetsInitSqlIsNamed`) and `FlywayStoreFactoryTest.java` (today's `aCustomizerSettingInitSqlFails`), `data/synapse-data-postgres/src/test/java/io/americanexpress/synapse/data/postgres/PostgresVendorTest.java` and `PostgresStoreChecksTest.java`, and a new `data/synapse-data-postgres/src/test/java/io/americanexpress/synapse/data/postgres/PostgresConnectionSqlIT.java`.

**Interfaces:**

```java
// DataStoreVendor
/** Driver properties and URL parameters that apply server settings to each new connection, such as PgJDBC's options: allowed, with a startup WARN. Matched in any case. Empty by default. */
default Set<String> sessionSettingsProperties() { return Set.of(); }
// JdbcUrls
/** The URL's parameter names, percent-decoded and in lower case, as hasCredentials reads them. */
public static Set<String> parameterNames(String url);
// ConnectionSql (logger io.americanexpress.synapse.data.connectionsql); each names the key or bean, never the value
public static void initSql(String store, String key);            // hikari.connection-init-sql
public static void flywayInitSql(String store, String key);      // migrations.flyway.init-sql (as the user spelled it)
public static void customizerInitSql(String store, String bean);
public static void testQuery(String store, String key, @Nullable String product);
public static void sessionSettingsProperty(String store, String key, String product);
public static void sessionSettingsInUrl(String store, String parameter, String product);
```

- The texts (the memo's; "…" in a line is the common tail `Synapse doesn't read it, so the store's statements can run with another role, schema or settings than its url and username say (SET ROLE, SET search_path).`):
  - `The store 'books' sets synapse.data.stores.books.hikari.connection-init-sql: SQL that runs on each new connection. …`
  - `The store 'books' sets synapse.data.stores.books.migrations.flyway.init-sql: SQL that runs on each new connection of its migrations. …`
  - `The FlywayConfigurationCustomizer bean roleCustomizer sets initSql for the store 'books': SQL that runs on each new connection of its migrations. …`
  - `The store 'books' sets synapse.data.stores.books.hikari.connection-test-query: SQL that runs on each connection check. HikariCP recommends not setting it for drivers that support Connection.isValid(), as PostgreSQL's does. …` (without a product: `… for drivers that support Connection.isValid(). …`)
  - `The store 'books' sets the PostgreSQL driver property options (synapse.data.stores.books.hikari.data-source-properties.options): server settings applied to each new connection, which can change the role or schema the store uses.`
  - `The store 'books' has an options parameter in synapse.data.stores.books.url: server settings applied to each new connection, which can change the role or schema the store uses.`

- [ ] **Step 1: Write the failing tests** (Review Focus 5):

```java
// StoreChecksTest (replace hikariConnectionInitSqlIsRejected and hikariConnectionTestQueryIsRejected)
@Test void initSqlWarnsWithoutItsValue()           // connection-init-sql: "SET app.key = 'secret'" → starts; exactly one WARN naming the key; output doesn't contain "secret"
@Test void testQueryWarnsWithHikarisAdvice()       // connection-test-query → one WARN with "HikariCP recommends not setting it"
@Test void identityKeysStillFail()                 // hikari.username, hikari.jdbc-url, data-source-properties.user, hikari.schema → the same failures as today
@Test void theWarnUsesTheConnectionSqlLogger()     // logging.level."io.americanexpress.synapse.data.connectionsql"=error (SpringApplication-level, YAML key) → no WARN
// FlywayStoreMigrationTest (replace initSqlIsRejected and aCustomizerThatSetsInitSqlIsNamed)
@Test void flywayInitSqlWarns()
@Test void aCustomizerThatSetsInitSqlWarnsNamingTheBean()
// FlywayStoreFactoryTest (replace aCustomizerSettingInitSqlFails)
@Test void aCustomizerSettingInitSqlWarnsAndMigrates()
// PostgresVendorTest / PostgresStoreChecksTest
@Test void optionsIsASessionSettingsProperty()     // and no longer in blockedDriverProperties()
@Test void optionsAsADriverPropertyWarns()         // hikari.data-source-properties.OPTIONS (any case) → one WARN naming the key
@Test void optionsInTheUrlWarns()                  // jdbc:postgresql://db/books?options=-c%20search_path%3Dx → one WARN
@Test void currentSchemaIsStillRejected()
// JdbcUrlsTest
@Test void parameterNamesAreDecodedAndLowerCase()  // ?Options=a&%73ervice=b → {options, service}
// PostgresConnectionSqlIT (Docker)
@Test void initSqlRunsOnEachConnection()           // connection-init-sql "SET application_name = 'x'" → SHOW application_name is x
@Test void aFailingInitSqlIs…()                    // connection-init-sql "SELECT 1/0", migrations off: record what the first repository call gives (expected UNAVAILABLE: Hikari treats it as a connection failure) and name the test after it; with migrations on, startup fails while migrating. If the first call isn't UNAVAILABLE, stop and report; don't change the translation
```

- [ ] **Step 2: Run to verify failure:** the unit tests in `data/synapse-data-core` and `data/synapse-data-postgres`. Expected: startup fails where a WARN is expected.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jpa,data/synapse-data-jdbc,data/synapse-data-postgres -am verify`, Docker on.
- [ ] **Step 5: Commit**: `feat(data): allow SQL that runs on each connection, with a startup WARN`.

### Task 7: A store-scoped bean no store uses, and `@EntityScan`, get a WARN (decision 11, WARNs 1 and 3)

**Files:**
- Create: `DC/internal/store/StoreScopedType.java`, `DC/internal/store/AutoConfiguredBeans.java`, `DC/internal/store/UnusedStoreBeans.java`.
- Modify:
  - `DC/internal/store/StoreInfrastructure.java` (`storeScopedTypes()`);
  - `DC/internal/store/StoreBeans.java` (`applicationBeanNames`; the per-store DEBUG lines stay);
  - `DC/internal/store/FactoryMethods.java` (`definingClassName`);
  - `DC/internal/store/DataStartupReport.java` (logs WARNs 1 and 3 on `io.americanexpress.synapse.data.ignored`, and moves the R9 and R10 WARNs to it, D6; the `EntityScanPackages` case leaves `warnAboutIgnoredBeans`);
  - `data/synapse-data-jpa/src/main/java/io/americanexpress/synapse/data/jpa/internal/JpaStoreInfrastructure.java` (`storeScopedTypes()`; `EntityScanPackages` leaves `ignoredBeanTypes()`);
  - `data/synapse-data-jdbc/src/main/java/io/americanexpress/synapse/data/jdbc/internal/JdbcStoreInfrastructure.java` (`storeScopedTypes()`);
  - `docs/modules/ROOT/pages/data.adoc` (*Boot beans a store uses*: drop "Some cases don't meet the aim yet" and its first and third bullets; state the WARN texts, the exclusions and the logger).
- Test: create `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/store/{UnusedStoreBeansTest,AutoConfiguredBeansTest}.java`; modify `DataStartupReportTest`, `FactoryMethodsTest`, `data/synapse-data-jpa/src/test/java/io/americanexpress/synapse/data/jpa/internal/JpaIgnoredBeansTest.java`, and add a JDBC counterpart `data/synapse-data-jdbc/src/test/java/io/americanexpress/synapse/data/jdbc/internal/JdbcUnusedBeansTest.java` (`ApplicationContextRunner`, H2).

**Interfaces:**

```java
public record StoreScopedType(String typeName, String suffix, boolean everyApplies, @Nullable DataAccess access) { }
// everyApplies: true for customizer types the factories apply all of (applicationBeans), false for the single choice (own bean, else qualified, else unqualified or primary); access null: every store
// StoreInfrastructure
default List<StoreScopedType> storeScopedTypes() { return List.of(); }
// StoreBeans
/** The names applicationBeansByName would return, in the same order, without creating any bean. */
public static List<String> applicationBeanNames(ConfigurableListableBeanFactory beanFactory, String store, Class<?> type, String suffix);
// FactoryMethods
/** The class that defines a bean: its factory method's declaring class (also in AOT mode), else its bean class; null when unknown. */
public static @Nullable String definingClassName(BeanDefinition definition);
// AutoConfiguredBeans (D7)
/** Whether Spring Boot itself defines the bean: it has a factory method whose declaring class is in a package under org.springframework.boot. A bean without a factory method is never Boot's, whatever its bean class (D7, N2). */
public static boolean isBootDefined(ConfigurableListableBeanFactory beanFactory, String beanName);
/** The auto-configurations ImportCandidates.load(AutoConfiguration.class, …) lists. */
public static Set<String> autoConfigurations(@Nullable ClassLoader classLoader);
/** The listed auto-configuration that defines the bean (its defining class, or a class enclosing it), or null. */
public static @Nullable String autoConfigurationOf(ConfigurableListableBeanFactory beanFactory, String beanName, Set<String> autoConfigurations);
// UnusedStoreBeans
/** WARN 1 for each StoreScopedType whose class is present, sorted by type then bean name; then WARN 3. No bean is created. */
public static List<String> warnings(ConfigurableListableBeanFactory beanFactory, List<StoreDefinition> stores, List<StoreScopedType> types, @Nullable ClassLoader classLoader);
```

- A bean is used when some store of its access kind would pick it: for a single choice, `applicationBeanName` for each such store that has no own `<prefix><suffix>` bean; for `everyApplies`, `applicationBeanNames`. Excluded: every store's own bean, and beans Spring Boot defines (`isBootDefined`). Any other auto-configured bean warns like an application's, and its line ends with ` It is defined by the auto-configuration com.acme.platform.PlatformJpaAutoConfiguration.` (`autoConfigurationOf`, D7).
- WARN 1 texts (D9 adds the last two):
  - `The HibernatePropertiesCustomizer bean auditCustomizer is not used by any store: its qualifier 'bokks' names no JPA store (JPA stores: books).`
  - `… its qualifier 'ledger' names the JDBC store 'ledger'.`
  - `… it has a qualifier annotation other than @Qualifier.`
  - `… the service has no JPA store.`
  - `… every JPA store uses its own or a qualified one.`
  - `… its qualifier names the store 'books', which uses its own booksPhysicalNamingStrategy.`
  - For data-core's `FlywayConfigurationCustomizer` (any kind) the kind is left out: `… names no store (stores: books, ledger).`
- WARN 3: `@EntityScan names com.acme, which no store uses: a store's entities (JPA) and aggregates (JDBC) are those in and below its store class's package.` (packages sorted, joined with `, `), while any store exists.

- [ ] **Step 1: Write the failing tests** (Review Focus 4):

```java
// UnusedStoreBeansTest and JdbcUnusedBeansTest / JpaIgnoredBeansTest (ApplicationContextRunner, OutputCaptureExtension)
@Test void aQualifierThatNamesNoStoreWarns()
@Test void aQualifierThatNamesAStoreOfTheOtherKindWarns()       // JPA customizer @Qualifier("ledger"), ledger is JDBC
@Test void anotherQualifierTypeWarns()
@Test void aKindNoStoreHasWarns()                               // HibernatePropertiesCustomizer, JDBC stores only
@Test void anUnqualifiedBeanEveryStoreOverridesWarns()          // PhysicalNamingStrategy unqualified; the only JPA store has a qualified one
@Test void aBeanQualifiedForItsStoreDoesNotWarn()
@Test void aQualifiedFlywayCustomizerNamingNoStoreWarns()
@Test void bootsOwnBeansDoNotWarn()                             // Boot's DataSource, JDBC repositories, Hibernate JPA and Flyway auto-configurations next to stores → no WARN; record which Boot beans of these types exist
@Test void aPlatformStartersBeanWarnsNamingItsAutoConfiguration() // an org.acme auto-configuration (listed through ImportCandidates in the test) defines a HibernatePropertiesCustomizer no store uses → WARN ending "It is defined by the auto-configuration org.acme….PlatformAutoConfiguration."
@Test void entityScanWarnsWithJdbcStoresOnly()                  // @EntityScan("com.acme"), one JDBC store → WARN 3
@Test void entityScanWarnsOnceWithBothKinds()
@Test void theWarningsUseTheIgnoredLogger()                     // also for the R9 Callback and R10 builder-customizer lines
// AutoConfiguredBeansTest
@Test void aBeanFromABootAutoConfigurationIsBootDefined()
@Test void aBeanFromAConfigurationBootImportsIsBootDefined()     // DataSourceConfiguration's nested Hikari class
@Test void aBeanFromAnotherAutoConfigurationIsNotBootDefinedButNamesIt()
@Test void anApplicationBeanIsNeither()
@Test void aBeanWithoutAFactoryMethodIsNotBootDefined()          // RootBeanDefinition(Boot's PropertiesJdbcConnectionDetails-like class under org.springframework.boot., instance supplier) → false
@Test void anAotDefinitionIsReadFromItsResolvedFactoryMethod()  // RootBeanDefinition with only setResolvedFactoryMethod
// FactoryMethodsTest
@Test void definingClassNameReadsTheFactoryMethodsClass()
```

- [ ] **Step 2: Run to verify failure:** the tests above in `data/synapse-data-core`, `data/synapse-data-jpa` and `data/synapse-data-jdbc`. Expected: no WARN (DEBUG only).
- [ ] **Step 3: Implement.** If `bootsOwnBeansDoNotWarn` finds a bean that a Boot auto-configuration brings in from a class outside `org.springframework.boot.` (D7's rule doesn't exclude it), stop and report its name and defining class.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jpa,data/synapse-data-jdbc,data/synapse-data-postgres,tests/synapse-data-mixed-tests,samples/multi-store/service-orders,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`, Docker on. Start the multi-store and reference-bookstore samples as their READMEs do and check that their startup logs gain no WARN. If one does, its sample has a real unused bean: stop and report it.
- [ ] **Step 5: Commit**: `feat(data): warn about a store-scoped bean that no store uses, and about @EntityScan`.

### Task 8: The Flyway beans of a store that doesn't migrate get a WARN (decision 11, WARN 2)

**Files:**
- Modify: `DC/internal/migration/FlywayStoreFactory.java` (`beansWithoutEffect`), `DC/internal/store/DataStartupReport.java` (logs them on `io.americanexpress.synapse.data.ignored`), `docs/modules/ROOT/pages/data.adoc` (*Migrations* and *Boot beans a store uses*: the WARN and the way out, below).
- Test: `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/migration/FlywayStoreFactoryTest.java`.

**Interfaces:**

```java
// FlywayStoreFactory
/** WARN 2 lines: for each store with migrations.enabled false, each FlywayConfigurationCustomizer (when spring-boot-flyway is present), Callback and JavaMigration that would apply to it, and its own <prefix>FlywayConfigurationCustomizer. Sorted; no bean created. */
public static List<String> beansWithoutEffect(ConfigurableListableBeanFactory beanFactory, Environment environment);
```

- "Would apply": qualified with the store, or unqualified while it is the only store. An unqualified customizer with several stores warns only when every store has migrations off (then once, naming every store).
- Text: `The Flyway Callback bean auditCallback has no effect: the store 'books' doesn't migrate (synapse.data.stores.books.migrations.enabled is false).` (`The FlywayConfigurationCustomizer bean …`, `The Flyway JavaMigration bean …`; with several stores `… the stores 'books' and 'ledger' don't migrate (… migrations.enabled is false for each).`)
- The way out, in `data.adoc`: guard the bean on the store's key, `@ConditionalOnBooleanProperty(name = "synapse.data.stores.books.migrations.enabled", matchIfMissing = true)` (a condition on a key, not on the environment's name).

- [ ] **Step 1: Write the failing tests** (Review Focus 4):

```java
@Test void aQualifiedCallbackOfAStoreThatDoesNotMigrateWarns()
@Test void anUnqualifiedJavaMigrationOfTheOnlyStoreWarns()
@Test void theStoresOwnCustomizerWarns()
@Test void anUnqualifiedCustomizerWarnsOnlyWhenNoStoreMigrates()   // two stores, one off → no WARN; both off → one WARN naming both
@Test void aGuardedFlywayBeanDoesNotWarn()                         // @ConditionalOnBooleanProperty(matchIfMissing = true) on the key set false → no bean, no WARN
@Test void aStoreThatMigratesDoesNotWarn()
```

- [ ] **Step 2: Run to verify failure. Step 3: Implement. Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-data-jpa,data/synapse-data-jdbc -am verify`.
- [ ] **Step 5: Commit**: `feat(data): warn about Flyway beans of a store whose migrations are off`.

### Task 9: Connection details a store doesn't use: the WARN, the failure, and the dev patterns (decisions 11 WARN 4, and 19)

**Files:**
- Create: `DC/internal/store/UnusedConnectionDetails.java`, `data/synapse-test-data/src/main/java/io/americanexpress/synapse/test/data/StoreConnections.java`.
- Modify:
  - `DC/internal/store/StoreFactory.java` (`noUrl(store)` names the unused beans when there are any);
  - `DC/internal/store/DataStartupReport.java` (WARN 4 on `io.americanexpress.synapse.data.ignored`);
  - `data/synapse-test-data/src/main/java/io/americanexpress/synapse/test/data/StoreConnectionContextCustomizerFactory.java` (its `ContainerConnectionDetails` moves into `StoreConnections`, which the customizer uses);
  - `docs/modules/ROOT/pages/data.adoc` (*Keys without defaults*: the new failure; *Boot beans a store uses*: replace "Not honoured, decision pending" with the rule, WARN 4 and the three dev patterns below);
  - `docs/modules/ROOT/pages/testing.adoc` (*@SynapseDataTest* limits: "Containers defined by `@Bean` methods are not supported" becomes the `StoreConnections.of` pattern; a dev-time `SpringApplication.from(...).with(...)` example in `src/test/java`).
- Test: create `data/synapse-data-core/src/test/java/io/americanexpress/synapse/data/internal/store/UnusedConnectionDetailsTest.java`; modify `StoreChecksTest` (or the class holding the no-url failure test); create `data/synapse-test-data/src/test/java/io/americanexpress/synapse/test/data/StoreConnectionsTest.java` and `data/synapse-test-data/src/test/java/io/americanexpress/synapse/test/data/slice/{ContainerBeanIT,ServiceConnectionIT}.java`.

**Interfaces:**

```java
// UnusedConnectionDetails
/** The JdbcConnectionDetails beans no store uses, sorted: not qualified with a declared store, not defined by Spring Boot (AutoConfiguredBeans.isBootDefined), and not a dependency of a store-qualified JdbcConnectionDetails. It first gets every store-qualified details bean, so an adapter's dependency is registered. */
public static List<String> names(ConfigurableListableBeanFactory beanFactory, @Nullable ClassLoader classLoader);
// StoreConnections (synapse-test-data, public)
public static JdbcConnectionDetails of(String store, JdbcDatabaseContainer<?> container);   // reads the container on each call; toString names the store only
```

- WARN 4: `The JdbcConnectionDetails bean jdbcConnectionDetailsForBooksDb is not used by any store: a store uses only a JdbcConnectionDetails qualified with its name, such as @Qualifier("books"), and otherwise connects where synapse.data.stores.<name>.url says.` A details bean from an auto-configuration other than Spring Boot's ends with the same ` It is defined by the auto-configuration <class>.` as WARN 1 (D7).
- The failure (a store with no `url`, and unused details beans; up to 5 names, D16; without such beans today's message is unchanged):
  - description: `The store 'books' has no url, and the JdbcConnectionDetails bean jdbcConnectionDetailsForBooksDb is not used: a store uses only a JdbcConnectionDetails qualified with its name.` (plural: `the JdbcConnectionDetails beans a, b are not used`)
  - action: `Set synapse.data.stores.books.url in the data module's config-<env>.yaml, or give a JdbcConnectionDetails bean @Qualifier("books"); in a test, put @StoreConnection("books") on the container field.`
- The dev patterns in `data.adoc` (none uses `@Profile`):
  1. Compose by hand (the samples' way, kept first): `docker compose up -d`, and the store's `url`, `username` and `password` in `config-local.yaml`;
  2. a container `@Bean` in a dev-time `@TestConfiguration` with `@Bean @Qualifier("books") JdbcConnectionDetails books(PostgreSQLContainer container) { return StoreConnections.of("books", container); }`;
  3. Boot's Docker Compose support, in the same dev-time configuration: `@Bean @Qualifier("books") JdbcConnectionDetails booksStoreConnection(@Qualifier("jdbcConnectionDetailsForBooksDb") JdbcConnectionDetails compose) { return compose; }`. The source bean's name follows the compose service name (`books-db` → `jdbcConnectionDetailsForBooksDb`), and no WARN names it.

- [ ] **Step 1: Write the failing tests** (Review Focus 4):

```java
// UnusedConnectionDetailsTest (ApplicationContextRunner, H2, OutputCaptureExtension)
@Test void anUnqualifiedDetailsBeanWarnsWhenTheStoreHasAUrl()
@Test void anUnqualifiedDetailsBeanIsNamedWhenTheStoreHasNoUrl()  // startup fails with the description and action above
@Test void sixUnusedBeansListFiveAndOneMore()
@Test void anAdaptersSourceBeanDoesNotWarn()                        // jdbcConnectionDetailsForBooksDb + @Qualifier("books") adapter injecting it by name → starts, no WARN
@Test void aStoreQualifiedDetailsBeanDoesNotWarn()
@Test void bootsPropertiesConnectionDetailsDoesNotWarn()            // with DataSourceAutoConfiguration on: no WARN (record whether Boot defines one next to stores)
@Test void noDetailsBeansKeepTodaysNoUrlMessage()
// StoreConnectionsTest
@Test void readsTheContainerOnEachCall()                            // StubJdbcContainer whose URL changes
@Test void itsStringFormNamesTheStoreOnly()
// ContainerBeanIT (Docker; synapse-test-data's slice fixture)
@Test void aContainerBeanReachesTheStoreThroughQualifiedDetails()  // @Bean PostgreSQLContainer + @Bean @Qualifier("books") StoreConnections.of(...) → the repository works; Boot's Testcontainers lifecycle started the container before the store read its URL
@Test void aStoreConnectionFieldWinsOverAServiceConnection()        // @StoreConnection("books") field plus a @ServiceConnection container → Boot skips the latter, the store uses the former, no WARN
// ServiceConnectionIT (Docker; synapse-test-data's slice fixture; a real @ServiceConnection PostgreSQLContainer field, no @StoreConnection) (N2)
@Test void aServiceConnectionIsNamedWhenTheStoreHasNoUrl()          // store without url → startup fails; the description names Boot's bean (jdbcConnectionDetailsFor…), as Boot's ConnectionDetailsRegistrar named it
@Test void aServiceConnectionWarnsWhenTheStoreHasAUrl()             // same field, store url set → starts; WARN 4 names that bean
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest=UnusedConnectionDetailsTest -Dsurefire.failIfNoSpecifiedTests=false`, the `synapse-test-data` unit test, then its ITs with `-Dit.test='ContainerBeanIT,ServiceConnectionIT'`. Expected: no WARN; today's no-url message; `StoreConnections` doesn't exist.
- [ ] **Step 3: Implement.** If `aContainerBeanReachesTheStoreThroughQualifiedDetails` fails because the container isn't started when the store reads the URL, stop and report it (the memo's uncertainty); don't start the container in `StoreConnections`.
- [ ] **Step 4: Run to verify pass:** `./mvnw -pl data/synapse-data-core,data/synapse-test-data,data/synapse-data-postgres,samples/reference-bookstore/service-bookstore -am verify -Dprotobuf…=…`, Docker on.
- [ ] **Step 5: Commit**: `feat(data): name the connection details a store doesn't use, and make container details public for dev runs`.

### Task 10: Liquibase docs and the spec status (decisions 28 and 32 item 7)

**Files:**
- Modify:
  - `docs/modules/ROOT/pages/data.adoc` (*Migrations*, the Liquibase sentence becomes the memo's, D15: "Synapse doesn't support Liquibase in 2.0. It sets `spring.liquibase.enabled=false` by default, so Boot's Liquibase doesn't run against the primary data source while beans are created. Use Flyway, which each store runs (see <<migrations>>)." The `spring.sql.init` sentence stays);
  - `specs/v2/BUILD.md` §15 P1: a *Follow-ups, data (plan 2026-11-20)* entry after the *Review fixes* entry, in its style: what landed (by decision number), this plan's decisions D1-D16, and what only a later run can check (for example the Hikari hand-off under real load, if Task 5 recorded a doubt);
  - `specs/v2/DESIGN.md`: only if a task changed a decided behaviour (each such change named in the review report).
- [ ] **Step 1: Write the docs.** Every property named exists in the configuration metadata.
- [ ] **Step 2: Build the docs:** `(cd docs && npm ci && npx antora antora-playbook.yml)`, then, from the repository root, `node docs/tools/property-tables.mjs`. Expected: no broken xref, and no property row removed.
- [ ] **Step 3: Commit**: `docs(data): say Liquibase is not in 2.0, and record the data follow-ups`.

### Task 11: Full build

- [ ] **Step 1: JDK 25, as CI runs it** (background, Docker on; about 20 minutes):

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

- [ ] **Step 3: Report** the test counts per module for both runs, next to the baseline's, and explain every count that went down.
- [ ] **Step 4:** No commit unless a fix was needed. A fix is its own `fix:` commit with a failing test first, and both runs repeat after it.

### Task 12: Final whole-branch review

- [ ] **Step 1:** An Opus review of the branch from the contract plan's squash commit to `HEAD`, with BUILD.md, DESIGN.md, AGENTS.md, this plan and the memos as the authority. Order the findings by severity; each names its module and the user-visible effect. It checks especially:
  - that nothing classifies a conflict by Spring's exception type, and that 40002 and 40003 can't become `TRANSACTION_CONFLICT`;
  - that no new log line or failure prints a value, a URL or SQL;
  - that a correct setup (the samples, the archetypes' generated projects) starts without a new WARN;
  - that the decisions table matches what was built.
- [ ] **Step 2: Fix** each accepted finding in its own commit, with a failing test where behaviour changes. A fix gets a Sonnet re-review; repeat Task 11 after the last fix.
- [ ] **Step 3: Report** to the maintainer: the commits, the decisions table (D1-D16) for confirmation, and anything stopped on. Squash into local `main` and push only on the maintainer's word.

---

## Not in this plan

| Item | Where it goes |
|---|---|
| Class 53 behind `DataStoreVendor` (decision 7) | Before the first P5 vendor (BUILD.md P5; D3). |
| SQL Server, Oracle and MySQL conflict codes through `transactionConflict` | P5, with each vendor module (BUILD.md P5). |
| PgJDBC `socketTimeout` default | DESIGN.md §6, open, no lean (decision 17's separate half). |
| Resolving `COMMIT_OUTCOME_UNKNOWN` with `pg_xact_status` | DESIGN.md §5, 2.x. |
| Liquibase support | DESIGN.md §5, 2.x add-on candidate (decision 28). |
| The plan-4 "later" items | BUILD.md P6 (decision 32, item 7; in this plan's spec edits). |
| `@Incubating` on `StoreConnections` and the new `DataStoreVendor` methods | The API-surface plan's `@Incubating` pass (`DataStoreVendor` is already `@Incubating`). |
