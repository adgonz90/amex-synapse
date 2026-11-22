# P1 environments plan: environments and module configuration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the environment the one active Spring profile, checked at startup, and let each consumer library module own its configuration in `META-INF/synapse/config.yaml` and `config-<env>.yaml`. Remove the production concept: error detail and the generated cursor-key report stop depending on the environment, and `SynapseEnvironment` goes away. Every test in this repository runs as `test`, and `samples/rest-books` runs as `local`.

**Architecture:** One `EnvironmentPostProcessor` in `synapse-core`, ordered right after Boot's config data, does four things in order:
1. resolves the single environment name (`spring.profiles.active` plus `SpringApplication` additional profiles);
2. checks that the service has a file for it, and that no config document uses `spring.config.activate.on-profile`;
3. loads the module files from fixed resource names and inserts them below Boot's config data, after the collision and ownership checks;
4. in AOT mode, checks that environment files add no client or store names.

A failure analyzer explains Boot's unresolved `${ENV}` error. An `ApplicationStartedEvent` listener logs the startup report. The logic lives in `io.americanexpress.synapse.core.internal.environment`; the EPP, analyzer and listener are package-private classes in `core.autoconfigure`, registered in `spring.factories` next to the existing ones.

**Tech Stack:**
- Java 21 (`--release 21`, built on JDK 25).
- Spring Boot 4.1.2 (`EnvironmentPostProcessor`, `ConfigDataEnvironmentPostProcessor.ORDER`, `YamlPropertySourceLoader`, `PropertiesPropertySourceLoader`, `ConfigurationPropertySources`, `ConfigurationPropertyName`, `TextResourceOrigin`, `FailureAnalyzer`).
- Spring Framework 7.0.10 (`AotDetector`, `RuntimeHints`).
- JUnit 5, AssertJ, Boot's `OutputCaptureExtension`, `RuntimeHintsPredicates`.
- No new dependency.

**Spec:** [specs/v2/BUILD.md](../BUILD.md) §3.6 (new), §3.1, §3.2, §3.3 (*Environments under AOT*), §4.1, §6.1 (*Error exposure*), §6.2 (cursor key), §12, §13, §15 P1 acceptance 3 and 21. The reasons are the [DESIGN.md](../DESIGN.md) §4.1 rows dated "2026-11-05, P1 environments plan". The maintainer's binding decisions for this plan are numbered D1–D13 below where a task depends on one.

## Global Constraints

**Branch, JDK and language**
- Branch: `feature/v2-p1-environments` (created from local `main` at a17edf57; the spec commit is its first commit). **Never push.** Merge to `main` locally only when the maintainer says so (squash, as for the earlier plans).
- JDK: run `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"` before any `./mvnw`. JDK 21 runs use `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never a global `mvn`.
- Compile with `--release 21`: no Java 22+ APIs. Prefer records for data carriers.
- Every package has a `package-info.java` annotated `@NullMarked`; nullable types are `@Nullable` (JSpecify). Error Prone and NullAway must pass. A test package with the same name as a main package has no `package-info.java` of its own.
- Formatting: run the build, and Spotless applies the profile locally. Don't hand-format. Every file carries the license header (YAML and `.properties` test resources too, as `#` comments, if Spotless checks them; follow what the build does).
- Don't edit `.vscode/` or a pom while a Maven build runs (VS Code shares `target/`).

**Behaviour fixed by the spec (copy exactly)**
- The environment is the one active profile. Synapse code never reads `ENV`. Exactly one; no default; no fallback. Names are case-sensitive; Synapse does no case handling.
- Valid only when the service has `application-<env>.yaml|yml|properties`: Boot loaded a `Config resource` source for it from any location, or the classpath resource exists (an empty or comment-only file still counts). Invalid is treated exactly like none. Module `config-<env>` files never make a name valid.
- Any loaded config document with `spring.config.activate.on-profile` fails startup.
- Module files: `META-INF/synapse/config.yaml` and `META-INF/synapse/config-<env>.yaml` (and `.properties`), found with `application.getClassLoader().getResources(...)` on fixed names. **No pattern scanning.**
- Precedence, low to high: module `config.yaml` < module `config-<env>.yaml` < service `application.yaml` < service `application-<env>.yaml` < environment variables and the command line. Module sources sit below every `Config resource` source and above `defaultProperties`.
- Startup fails on: the same key in two modules (canonical `ConfigurationPropertyName`; a module's own `config.yaml` and `config-<env>.yaml` may overlap); a module file setting `spring.profiles.*`, `spring.config.*` or `synapse.environment.*`; a jar or directory shipping `META-INF/synapse/` that also ships a root `application.*`.
- The startup report never prints a property value.
- No production concept: no API, property or log level depends on the environment's name.

**Library code**
- No remote I/O. No `@ComponentScan`, no `@Import` of framework packages. Synapse's own jars never ship `META-INF/synapse/` or `application.*` files.
- SLF4J API only in beans. The EPP runs before logging starts and logs nothing; it fails with `SynapseConfigurationException(description, action)`, which the existing `SynapseConfigurationFailureAnalyzer` prints.
- Code outside `..internal..` packages is public API. New implementation classes go in `io.americanexpress.synapse.core.internal.environment`.

**Tests**
- Unit tests are `*Test` (surefire); integration tests are `*IT` (failsafe). A module that gains an IT class tags at least one fast test `@Tag("smoke")`.
- JaCoCo floor per module: 80% line / 70% branch; 90% of changed lines covered. `-DskipTests` is never a passing build.
- An EPP is not an auto-configuration: it is tested with direct calls and with real `SpringApplication` runs over fixture jars (`ConfigFixtures`, Task 5). Fixture runs **never use the environment name `test`**: the fixture class loader delegates parent-first, and the module's own `src/test/resources/application-test.yaml` would answer.
- Assert outcomes: resolved property values, the exact failure description and action, the exact report text.

**Commits**
- Conventional Commits. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage by explicit path (`git add <path> …`), never `git add -A` or `git add .`. Never stash, amend or push.
- Spring Boot stays on 4.1.2.

**Commands.**
- While iterating on a unit test: `./mvnw -q -pl core/synapse-core -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`.
- While iterating on an IT: `./mvnw -q -pl core/synapse-core -am verify -Dit.test=ClassNameIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`.

Neither counts as a passing build. Every task ends with `./mvnw -pl <module> -am verify` for each module it touched. From Task 5 on, run the full `./mvnw verify` (in the background; several minutes): the EPP applies to every module's Spring Boot tests.

## Spec changes this plan records

Committed together with this plan (`docs: specify environments and module configuration (P1 environments plan)`), before Task 1:
- **BUILD.md:** §2 `synapse-core` purpose; §3.1 (no `META-INF/synapse/` or `application.*` in Synapse's own jars); §3.2 (the *Non-production rails* bullet replaced); §3.3 (*Environments under AOT*, including `process-aot` and the training run); new §3.6 *Environments and module configuration*; §4.1 (configuration files per module type); §6.1 *Error exposure*; §6.2 cursor-key WARN and CORS `*` WARN in every environment; §6.3 gRPC reflection off by default; §7.2 and §8.1 (*Keys without defaults*); §8.1 `ddl-auto` guard with an explicit per-store switch; §12 note on the slices; §13 *Environments*; §15 acceptance 3, 8 and new 21; the delivery list (this plan after 2b, before 2c; plan 3 and plan 5 items).
- **DESIGN.md:** history; principle *Safe defaults*; 4.1 rows *Environment*, *Module-owned configuration*, *No production concept*, *Tests and local runs*, *Keys without defaults*, *Environments under AOT*, *Startup report* (2026-11-05); the 2026-11-03 *Error detail default* row and the 4.2 non-production lines marked superseded; the 4.1 *Cursors* row and the 4.3 *Error exposure* row annotated; two open questions (the build-time module rule, native verification); sources.
- **AGENTS.md:** the *No hidden wiring* bullet (Synapse's own jars; consumer modules ship `META-INF/synapse/config*.yaml`); the *Errors* bullet; a new *Environments* bullet.

## Defaults this plan sets (confirm at review)

| # | Default | Why |
|---|---|---|
| 1 | `SynapseEnvironment`, its package `io.americanexpress.synapse.core.environment`, `SynapseEnvironmentProperties` and `synapse.environment.non-production-profiles` are **removed**, not reduced to a `name()`. `synapse.environment.*` stays reserved (module files may not set it). | Its only member was `isProduction()`. With every user reworked (#2, #3) nothing reads it, and a `name()` would invite branching on names, which D8 and the AOT rule forbid. Unreleased, so no compatibility cost. Spring's `Environment` already has the profile. |
| 2 | `synapse.errors.include-detail` becomes `boolean`, default `false` (no tri-state). `SynapseErrorProperties.includeDetail(SynapseEnvironment)` is removed; `ProblemWriter` reads `includeDetail()`. | D8: safe in every environment; turned on per environment file. |
| 3 | The generated cursor key is reported at **WARN in every environment** (still once, still only when a route returns `PageResult`). No new setting. | The risk (cursors fail after a restart or on another instance) is the same everywhere. A local run that wants silence sets a key from an environment variable. |
| 4 | The test slices turn nothing on. In this repository, `api/synapse-test-rest` and `samples/rest-books` turn `include-detail` on in their `application-test.yaml`; `api/synapse-api-rest` tests that need detail set it per test class. | D8/D9: test conveniences live in the test file, visible to the team. |
| 5 | The environment is `spring.profiles.active` (bound with `Binder`, placeholders resolved, blank values dropped) plus `SpringApplication.getAdditionalProfiles()`, deduplicated in that order. `spring.profiles.default`, `include` and groups never count. | D1; `SpringApplicationBuilder.profiles(...)` sets additional profiles, so builder-based tests work. |
| 6 | The file check uses Boot's `spring.config.name` (default `application`; a list), so `books-e2.yaml` counts when `spring.config.name=books`. Classpath lookups try `<name>-<env>.{yaml,yml,properties}` at `classpath:/` and `classpath:/config/` (Boot's default classpath locations). | D4 says "the service has a file for it"; Boot's own lookup uses the config name. |
| 7 | `on-profile` detection (D5) re-reads every resource behind a loaded `Config resource` source (found through its `TextResourceOrigin`), plus the classpath `<name>.*` and `<name>-<env>.*` files of #6, with Boot's loaders, and fails on any document that has the key. | An inactive document (`on-profile: e5` while running `e2`) is never added to the environment, so the loaded sources alone can't see it. |
| 8 | Module files are read as `.yaml` and `.properties` (not `.yml`). A module that ships the same file in both formats fails startup. | D12's hint pattern names both; one format per file keeps the order defined. |
| 9 | The root-file check (D7) looks for `<name>.{yaml,yml,properties}` and `<name>-<env>.{yaml,yml,properties}` next to `META-INF/`. A module's own tests therefore put overrides in `src/test/resources/application-test.yaml`, never in `src/test/resources/META-INF/synapse/`. | Boot loads only the first `classpath:application-e2.yaml` too, so a module's env file can hide the service's (probe). |
| 10 | Collisions compare names from `ConfigurationPropertySources.from(source)` (`IterableConfigurationPropertySource#stream`), cut before the first indexed element: `hosts[0]` in one module and `hosts[1]` in another collide on `hosts`. A module is identified by its classpath root URL; messages use the origin label (#12). | `base-url`/`baseUrl` equality comes from `ConfigurationPropertyName`; Boot binds a list from one source only, so split lists would silently lose elements. |
| 11 | Module source names: `Synapse module config [<file>] <origin>` (for example `Synapse module config [config-e2.yaml] client-accounts-1.4.0.jar`), with ` (document #n)` for later YAML documents. All module environment sources come first (classpath order), then all module base sources. Re-running the EPP removes sources with the prefix first. | Names carry what the report needs; idempotent for parent and child contexts. |
| 12 | Origin labels, from the URL minus the resource path: a jar → its file name (the innermost jar for Boot's `jar:nested:` URLs); `…/<module>/target/classes/` → `<module>`; `…/<module>/target/test-classes/` → `<module> (test)`; any other directory → its path. | The example in D10 (`client-accounts-1.4.0.jar`, `service-books`). |
| 13 | The report is logged on `ApplicationStartedEvent` (after refresh, so `server.ports` and other late sources are in), on the logger `io.americanexpress.synapse.environment`, as one INFO event. It skips `configurationProperties`, `random`, stub sources and empty enumerable sources. | D10: after startup, every source, names only. |
| 14 | AOT check (D12): AOT mode is `AotDetector.useGeneratedArtifacts()`. Names are the third element under `synapse.client` (except `defaults`) and the fourth under `synapse.data.stores`. Environment files are module `config-<env>.*` and service `<name>-<env>.*` sources; environment-independent files are module `config.*` and service `<name>.*` sources. Other sources are ignored. | Only files decide the set of clients and stores. |
| 15 | The failure analyzer for D2 walks the cause chain for an `IllegalStateException` whose message matches `^Profile '(\$\{[^}]+\})' must`. | Boot 4.1.2 wraps it: `IllegalStateException("Invalid profile property value found in Environment under 'spring.profiles.active'", cause)` (`ProfilesValidator`, checked with `javap`). |
| 16 | `samples/rest-books` sets the pom property `spring-boot.run.profiles=local` (so `-Dspring-boot.run.profiles=…` still overrides it), maps `spring.profiles.active: ${ENV}`, and ships `application-local.yaml` with `synapse.errors.include-detail: true`. `BooksApplicationIT` runs as `test` and pins `synapse.errors.include-detail=false` to keep checking the safe defaults. | D9. |

Message texts (copy exactly; `<…>` are filled in):

| Case | Description | Action |
|---|---|---|
| No environment | `No environment is set: spring.profiles.active has no value.` + (when `spring.profiles.default` is set) ` spring.profiles.default (<value>) does not count.` | `Set exactly one environment, for example ENV=e2 when application.yaml maps spring.profiles.active: ${ENV}. Run locally with -Dspring-boot.run.profiles=local or an IDE run configuration; Synapse's test slices activate test.` |
| Several | `Exactly one environment must be active, but there are <n>: <a>, <b>.` | `Keep one environment in spring.profiles.active and move feature profiles to spring.profiles.include or a profile group.` |
| Unresolved `${ENV}` (analyzer) | `No environment is set: spring.profiles.active is '${ENV}', and ENV has no value.` | `Set ENV to the name of the environment (the service has an application-<env>.yaml for each). Run locally with -Dspring-boot.run.profiles=local or an IDE run configuration; Synapse's test slices activate test.` |
| No service file | `The environment '<env>' has no file in the service: none of <name>-<env>.yaml, <name>-<env>.yml, <name>-<env>.properties was loaded or found on the classpath. Environment names are case-sensitive.` | `Add <name>-<env>.yaml to the service (it may be empty), or set the environment to the name of an existing file.` |
| `on-profile` | `<file> sets spring.config.activate.on-profile.` | `Move the document's settings to <name>-<env>.yaml. Synapse uses one file per environment and no on-profile documents.` |
| Collision | `<key> is set by two modules: <origin A> (<file A>) and <origin B> (<file B>).` | `Set it in one module only. The service's application files can override any module value.` |
| Forbidden key | `<origin> (<file>) sets <key>. Module files may not set spring.profiles.*, spring.config.* or synapse.environment.*.` | `Remove it from the module; the service decides the run.` |
| Root file | `<origin> ships META-INF/synapse/ and also <file> at its root, which can hide the service's own file.` | `Move the module's settings to META-INF/synapse/config.yaml or config-<env>.yaml and remove <file>.` |
| Both formats | `<origin> ships both <file>.yaml and <file>.properties.` | `Keep one of them.` |
| AOT name | `<file> (<origin>) adds the <client\|store> '<name>', which no environment-independent file declares. In AOT mode the set of clients and stores is fixed at build time.` | `Declare <prefix>.<name> in config.yaml or application.yaml, and set only its values per environment.` |

## Review Focus

1. **`SPRING_PROFILES_ACTIVE` on the platform as well as `ENV`.** The environment variable outranks `application.yaml`'s `${ENV}`, so the environment is its value and the report says so; it must not count as a second profile. Pinned in Task 5: `anEnvironmentVariableOverridesTheMapping`.
2. **A module list split across modules.** `synapse.client.accounts.allowed-hosts[0]` in one jar and `[1]` in another must fail as a collision on `allowed-hosts`, not silently drop one element. Pinned in Task 7: `listIndexesCollideOnTheList`.
3. **A service run from a Boot fat jar.** Module files come from `jar:nested:…/!BOOT-INF/lib/<jar>!/META-INF/synapse/…` and service files from `…/!BOOT-INF/classes/!/…`; the report and the messages must name the inner jar and the fat jar, never a raw URL. Pinned in Task 7: `originNamesNestedJars`.
4. **An empty environment file outside the classpath** (a ConfigMap holding only comments). Boot creates no source for it, so it doesn't count. Startup must fail with the *No service file* message, which says what was looked for, never start silently. Pinned in Task 6: `anEmptyExternalFileDoesNotCount`; documented in `environments.adoc` (Task 11).
5. **A profile that comes only from `spring.profiles.default`** (for example `spring.profiles.default: local` in `application.yaml`). That is the fallback D2 forbids: startup fails, and the message says the default doesn't count. Pinned in Task 5: `blankValueAndDefaultProfileDoNotCount`.

---

## File structure

```
core/synapse-core/src/main/java/io/americanexpress/synapse/core/
  environment/                 SynapseEnvironment, package-info            (deleted, Task 3)
  autoconfigure/               SynapseErrorProperties (boolean includeDetail), SynapseCoreAutoConfiguration
                               (no environment bean), SynapseEnvironmentProperties (deleted),
                               SynapseEnvironmentPostProcessor, UnresolvedEnvironmentFailureAnalyzer,
                               ConfigurationReportListener
  internal/environment/        EnvironmentName, ConfigSources, ServiceConfigFiles, ModuleConfigFiles,
                               AotEnvironmentCheck, ConfigurationReport, package-info
  internal/aot/                SynapseCoreRuntimeHints (module config resources)
core/synapse-core/src/main/resources/META-INF/spring.factories   (EPP, analyzer, listener)
api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/
  internal/error/ProblemWriter, internal/paging/PageCursors, internal/route/RouteCompiler,
  autoconfigure/SynapseRestAutoConfiguration, autoconfigure/SynapseRestErrorAutoConfiguration
src/test/resources/application-test.yaml in: core/synapse-core, core/synapse-test, core/synapse-logging-log4j2,
  api/synapse-api-rest, api/synapse-test-rest, samples/rest-books
samples/rest-books/            application.yaml, application-local.yaml, pom.xml, README.md
docs/modules/ROOT/             nav.adoc, pages/environments.adoc (new), core.adoc, rest.adoc, testing.adoc
```

**Existing code these tasks change** (checked at a17edf57):
- `SynapseEnvironment` (`isProduction()`, `fromProfiles(...)`) is built in `SynapseCoreAutoConfiguration.synapseEnvironment(Environment, SynapseEnvironmentProperties)`.
- Users of `isProduction()`:
  - `SynapseErrorProperties.includeDetail(SynapseEnvironment)`, called by `ProblemWriter.write` (`ProblemWriter` takes the environment as its fifth constructor argument; `SynapseRestErrorAutoConfiguration` passes it);
  - `PageCursors.reportGeneratedKey(List<RouteDefinition>, SynapseEnvironment)`, called by `RouteCompiler.compile`; `RouteCompiler` takes it as its fifth constructor argument (`SynapseRestAutoConfiguration` passes it).
  - Nothing else in main code. Tests that build these with `() -> true` / `() -> false` are listed per task.
- `BaggageFieldsEnvironmentPostProcessor` (order `LOWEST_PRECEDENCE`) and `SynapseConfigurationFailureAnalyzer` are registered in `core/synapse-core/src/main/resources/META-INF/spring.factories`. The baggage EPP runs after the new one, so it sees module values.
- `@SynapseOperationTest` and `@SynapseRestTest` carry `@ActiveProfiles("test")`; spring-test sets it through a property source named `active-test-profiles` (research probe), which outranks `application.yaml`.

**Library facts this plan relies on** (verified for this plan against Boot 4.1.2, unless marked):
- `SpringApplication.getAdditionalProfiles()` and `getClassLoader()` are public (`javap`).
- `ConfigDataEnvironmentPostProcessor.ORDER` is public; Boot's default config locations are `optional:classpath:/;optional:classpath:/config/` and `optional:file:./;optional:file:./config/;optional:file:./config/*/` (`javap` constants of `ConfigDataEnvironment`).
- `ProfilesValidator` throws `IllegalStateException("Profile '%s' must contain a letter, digit or allowed char (%s)")` wrapped in `IllegalStateException("Invalid profile property value found in Environment under '%s'", cause)` (`javap`).
- From the probes (`envprobe2`, `envprobe3`, Boot 4.1.2): a `Config resource` source is named `Config resource 'class path resource [application-e2.yaml]' via location 'optional:classpath:/'` (`file [ext\application-e7.yaml]` for a file location; ` (document #1)` for later documents); an empty, comment-only or `---`-only YAML file and an empty `.properties` file produce no source; `getResources("META-INF/synapse/config.yaml")` returns one URL per jar on the JVM; an inactive `on-profile` document is not in the environment.
- Not verified: the same in a native image (DESIGN.md *Open questions*; this repository has no native test leg yet).

---

### Task 1: Error detail is an explicit setting

**Files:**
- Modify:
  - `core/synapse-core/src/main/java/io/americanexpress/synapse/core/autoconfigure/SynapseErrorProperties.java`;
  - `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/error/ProblemWriter.java`;
  - `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/autoconfigure/SynapseRestErrorAutoConfiguration.java`;
  - `api/synapse-test-rest/src/main/java/io/americanexpress/synapse/test/rest/SynapseRestTest.java` (Javadoc: "activates the `test` profile; test settings such as `synapse.errors.include-detail` go in the module's `application-test.yaml`").
- Create:
  - `api/synapse-test-rest/src/test/resources/application-test.yaml` (`synapse.errors.include-detail: true`, with a comment saying why);
  - `samples/rest-books/src/test/resources/application-test.yaml` (the same).
- Test (modify):
  - `core/.../autoconfigure/SynapseCoreAutoConfigurationTest.java`, `SynapseCoreAutoConfigurationIT.java`;
  - `api/.../internal/error/ProblemWriterTest.java`, `RestErrorsIT.java` (replace `spring.profiles.active=dev` with `synapse.errors.include-detail=true`);
  - `api/.../internal/route/RestRoutesIT.java` (the nested class's `@ActiveProfiles("dev")` becomes `@TestPropertySource(properties = "synapse.errors.include-detail=true")`);
  - `api/.../internal/conditional/RestConditionalIT.java` (line 370 comment: "Detail is hidden by default; the violation still says how to retry.");
  - every test that builds a `ProblemWriter` or `SynapseErrorProperties(null, null)`: `SynapseRestAutoConfigurationTest` (L880), `SynapseRestErrorAutoConfigurationTest` (L153, L176), `ConcurrencyLimitFilterTest` (L54), `ClientErrorFilterTest` (L69), `SynapseErrorControllerTest` (L68, L202), `SynapseRestExceptionResolverTest` (L95), `ProblemWriterTest` (L222);
  - `samples/rest-books/.../api/rest/BooksRestTest.java` (L151 comment: "application-test.yaml turns detail on").

**Interfaces:**
- Produces:
  - `public record SynapseErrorProperties(@DefaultValue("false") boolean includeDetail, @Nullable URI typeBaseUri)`. The `includeDetail` Javadoc: "Whether error responses include the formatted detail message. Off by default in every environment; turn it on in an environment file such as application-local.yaml. Detail messages can contain sensitive data; the full detail is always logged server-side, and stack traces are never returned." `includeDetail(SynapseEnvironment)` is deleted.
  - `public ProblemWriter(RestErrorStatuses statuses, ErrorMessages messages, ProblemTitles titles, SynapseErrorProperties properties, JsonMapper mapper, String correlationHeader)`.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseCoreAutoConfigurationTest (replaces errorDetailDefaultsByEnvironmentAndAnExplicitValueWins)
@ParameterizedTest
@CsvSource({"'',,false", "dev,,false", "test,,false", "local,,false", "prod,true,true", "dev,false,false"})
void errorDetailIsHiddenUnlessIncludeDetailIsTrue(String profiles, @Nullable Boolean explicit, boolean included)
        // spring.profiles.active=<profiles> (+ include-detail=<explicit>) → getBean(SynapseErrorProperties).includeDetail() == included
// SynapseCoreAutoConfigurationIT
@Test void coreAutoConfigurationIsAppliedThroughImportsFile()   // includeDetail() is false (was: null and resolved false)
// ProblemWriterTest: writer(boolean includeDetail, @Nullable String typeBaseUri)
@Test void detailOnlyWhenIncludeDetailIsTrue()                 // true → "detail" present; false → absent
// ConfigurationMetadataTest (existing check keeps passing: Boolean, defaultValue false, description starts "Whether")
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core,api/synapse-api-rest -am test -Dtest='SynapseCoreAutoConfigurationTest,ProblemWriterTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors on the new signatures.
- [ ] **Step 3: Implement** the record and constructor above; `ProblemWriter.write` includes the detail when `properties.includeDetail()`. Update every listed test construction (drop the `() -> false` argument; `SynapseErrorProperties(false, null)`). Update the `ProblemWriter` and `SynapseRestErrorAutoConfiguration` Javadoc (no "production").
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core -am verify`, `./mvnw -pl api/synapse-api-rest -am verify`, `./mvnw -pl api/synapse-test-rest -am verify` and `./mvnw -pl samples/rest-books -am verify`. `SynapseRestTestIT` and `BooksRestTest` still see `detail` through their `application-test.yaml`; `BooksApplicationIT` (no profile yet) still sees none.
- [ ] **Step 5: Commit**: `feat!: hide error detail unless synapse.errors.include-detail is true`.

### Task 2: The generated cursor key is reported at WARN in every environment

**Files:**
- Modify: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/paging/PageCursors.java`, `.../internal/route/RouteCompiler.java`, `.../autoconfigure/SynapseRestAutoConfiguration.java`.
- Test (modify): `.../internal/paging/PageCursorsTest.java`, `.../internal/route/RouteCompilerTest.java` (L59, L150, L182: drop the last argument; rename the local `production` to `generatedKeyCompiler`), `.../autoconfigure/SynapseRestAutoConfigurationTest.java` (L546).

**Interfaces:**
- Produces: `public void reportGeneratedKey(List<RouteDefinition> routes)` (WARN, once, only when the key was generated and a route returns `PageResult`); `public RouteCompiler(BindingValidator validator, RequestBinder binder, ResponseWriter writer, PageCursors cursors)`.

- [ ] **Step 1: Write the failing test**

```java
// PageCursorsTest (replaces generatedKeyWarnsInProductionOnlyForPagedRoutes)
@Test void generatedKeyWarnsInEveryEnvironmentOnlyForPagedRoutes()
        // [plain, search] → one WARN on "io.americanexpress.synapse.api.rest" with GENERATED_KEY_TEXT
        // [plain] and [] → nothing; [all] → one WARN (was INFO outside production)
// existing configuredKeyIsNeverReported stays (L201-203, minus the argument)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am test -Dtest='PageCursorsTest,RouteCompilerTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors.
- [ ] **Step 3: Implement** the two signatures; the Javadoc says "at WARN" with no environment.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat: report a generated cursor key at WARN in every environment`.

### Task 3: Remove `SynapseEnvironment` and `synapse.environment.non-production-profiles`

**Files:**
- Delete: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/environment/SynapseEnvironment.java`, `.../core/environment/package-info.java`, `.../core/autoconfigure/SynapseEnvironmentProperties.java`.
- Modify: `.../core/autoconfigure/SynapseCoreAutoConfiguration.java` (drop the bean, the `@EnableConfigurationProperties` entry, the `Arrays` import and the Javadoc mention).
- Test (modify):
  - `core/.../autoconfigure/SynapseCoreAutoConfigurationTest.java`: delete `productionUnlessANonProductionProfileIsActive`, `nonProductionProfilesAreConfigurable`, the user-environment back-off test (L158-164) and the two `bindsDefaults` lines;
  - `core/.../autoconfigure/SynapseContextAutoConfigurationTest.java` (L87, L106, L430, L440);
  - `core/.../autoconfigure/SynapseCoreAutoConfigurationIT.java`;
  - `core/.../autoconfigure/ConfigurationMetadataTest.java`;
  - `core/synapse-test/.../operation/AutoConfigureSynapseOperationTest.java` (L66);
  - `core/synapse-test/.../operation/slice/SynapseOperationTestIT.java` (L61, L83);
  - `api/synapse-test-rest/.../slice/SynapseRestTestIT.java` (L57, L161).

**Interfaces:**
- Consumes: Tasks 1 and 2 (no main code reads `SynapseEnvironment` any more).
- Produces: nothing. `synapse.environment.*` has no keys.

- [ ] **Step 1: Write the failing test**

```java
// ConfigurationMetadataTest
@Test void hasNoEnvironmentProperties()      // no metadata property name starts with "synapse.environment."
// SynapseOperationTestIT / SynapseRestTestIT: the slice still runs as test
assertThat(environment.getActiveProfiles()).containsExactly("test");   // replaces isProduction() == false
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest=ConfigurationMetadataTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: `hasNoEnvironmentProperties` fails on `synapse.environment.non-production-profiles`.
- [ ] **Step 3: Implement**: delete the three files, update `SynapseCoreAutoConfiguration` and the listed tests. `grep -rn "SynapseEnvironment\|non-production\|nonProduction" --include=*.java .` (excluding `target/`) returns nothing.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,core/synapse-test,api/synapse-test-rest -am verify`.
- [ ] **Step 5: Commit**: `feat!: remove SynapseEnvironment and synapse.environment.non-production-profiles`.

### Task 4: Every Spring Boot test runs in the `test` environment

No behaviour change yet: this prepares every test that starts a Spring Boot context for Task 5, which fails startup without an environment, and Task 6, which requires the file.

**Files:**
- Create (each comment-only, saying "The test environment: Synapse requires a file for the active environment; this module's tests need no settings of their own"):
  - `core/synapse-core/src/test/resources/application-test.yaml`;
  - `core/synapse-test/src/test/resources/application-test.yaml`;
  - `core/synapse-logging-log4j2/src/test/resources/application-test.yaml`, `log4j2-defaults-test-test.properties`, `log4j2-defaults-none-test.properties` (the last two for `Log4j2DefaultsPrecedenceTest`'s `spring.config.name`, see *Defaults* #6);
  - `api/synapse-api-rest/src/test/resources/application-test.yaml`.
- Modify (add the `test` environment):
  - `@ActiveProfiles("test")` on: `core/.../autoconfigure/SynapseCoreAutoConfigurationIT`, `SynapseOperationIT`; `core/.../internal/context/OutboundBaggagePolicyIT`; `api/.../internal/conditional/RestConditionalIT`; `.../internal/edge/InboundPropagationIT`, `RestEdgeIT`, `RestLimitsIT`; `.../internal/error/RestErrorsIT`; `.../internal/paging/RestPagingIT`; `.../internal/route/EscapeHatchRoutesIT`, `RestRoutesIT`; `samples/rest-books/.../BooksApplicationIT` (which also adds `properties = "synapse.errors.include-detail=false"` and a Javadoc that says it checks the safe defaults);
  - `.profiles("test")` on the `SpringApplicationBuilder`s in `api/.../internal/edge/GracefulShutdownIT` (L134), `.../internal/paging/RestPagingIT` (L326) and `core/synapse-logging-log4j2/.../Log4j2LoggingIT` (L127);
  - `application.setAdditionalProfiles("test")` in `Log4j2DefaultsPrecedenceTest.application(...)`;
  - `--spring.profiles.active=test` in `api/.../autoconfigure/ContextExitOnRefreshIT`'s process arguments.
- Tests that use `ApplicationContextRunner`, or call an EPP directly with `new SpringApplication()`, don't start an application and are not changed.

**Interfaces:**
- Produces: every module whose tests start a Spring Boot context has `src/test/resources/application-test.yaml` (`api/synapse-test-rest` and `samples/rest-books` since Task 1).

- [ ] **Step 1: Write the failing test**: in `SynapseCoreAutoConfigurationIT`, add

```java
@Test void runsInTheTestEnvironment()   // environment.getActiveProfiles() containsExactly "test"
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am verify -Dit.test=SynapseCoreAutoConfigurationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`. Expected: FAIL, no active profile.
- [ ] **Step 3: Implement** every change listed above.
- [ ] **Step 4: Verify:** `grep -rln -E "@SpringBootTest|SpringApplicationBuilder|SpringApplication|@SynapseRestTest|@SynapseOperationTest" --include=*IT.java --include=*Test.java core api samples`, and check that each hit sets `test` or doesn't start an application. Then the full build: `./mvnw verify`. Expected: BUILD SUCCESS with the same test counts as before plus one.
- [ ] **Step 5: Commit**: `test: run every Spring Boot test in the test environment`.

### Task 5: One environment per run

**Files:**
- Create:
  - `core/synapse-core/src/main/java/io/americanexpress/synapse/core/internal/environment/package-info.java`, `EnvironmentName.java`, `ConfigSources.java`;
  - `core/synapse-core/src/main/java/io/americanexpress/synapse/core/autoconfigure/SynapseEnvironmentPostProcessor.java`, `UnresolvedEnvironmentFailureAnalyzer.java`.
- Modify: `core/synapse-core/src/main/resources/META-INF/spring.factories` (the EPP under `org.springframework.boot.EnvironmentPostProcessor`, the analyzer under `org.springframework.boot.diagnostics.FailureAnalyzer`).
- Test (create): `core/.../internal/environment/EnvironmentNameTest.java`, `ConfigSourcesTest.java`, `ConfigFixtures.java` (test helper); `core/.../autoconfigure/SynapseEnvironmentPostProcessorTest.java`, `UnresolvedEnvironmentFailureAnalyzerTest.java`.

**Interfaces:**
- Produces:

```java
public record EnvironmentName(String name, String setBy) {
    // name: the single environment. setBy: "spring.profiles.active=<raw value>, <label of the highest source holding it>"
    // (raw = PropertySource.getProperty, so "${ENV}" stays unresolved), or "SpringApplication additional profiles".
    public static EnvironmentName resolve(ConfigurableEnvironment environment, Set<String> additionalProfiles);
}
public final class ConfigSources {
    public static String label(PropertySource<?> source);
}
final class SynapseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    SynapseEnvironmentPostProcessor();                          // aotMode = AotDetector::useGeneratedArtifacts (used from Task 8)
    SynapseEnvironmentPostProcessor(BooleanSupplier aotMode);
    public int getOrder();                                      // ConfigDataEnvironmentPostProcessor.ORDER + 1
}
final class UnresolvedEnvironmentFailureAnalyzer implements FailureAnalyzer { }
```

  - `label`: `commandLineArgs` → `command line arguments`; `systemProperties` → `Java system properties`; `systemEnvironment` → `environment variables`; `defaultProperties` → `defaults`; `active-test-profiles` → `@ActiveProfiles`; `Inlined Test Properties` → `test properties`; a `Config resource '… [<file>]' …` name → `<file>`, plus ` (document #n)` when present; a module source (Task 7) → its file name; anything else → its name.
  - Messages: *Defaults*, message table (rows *No environment*, *Several*, *Unresolved `${ENV}`*). The descriptions never contain property values other than the environment names and the raw `spring.profiles.active` value.
  - `ConfigFixtures` (test): `static Path jar(Path dir, String fileName, Map<String, String> entries)`, `static Path directory(Path dir, String name, Map<String, String> entries)` (entry path → UTF-8 content), `static URLClassLoader classLoader(Path... roots)` (parent: the test class loader), `static SpringApplication application(ClassLoader classLoader, Map<String, Object> environmentVariables)`. The application has `WebApplicationType.NONE`, banner off, an empty `@Configuration` source and a `DefaultResourceLoader` on the class loader. Its `StandardEnvironment` has the `systemEnvironment` source replaced by a `SystemEnvironmentPropertySource` over the given map, so the machine's variables never leak in.

- [ ] **Step 1: Write the failing tests**

```java
// EnvironmentNameTest (StandardEnvironment with MapPropertySources named as Boot names them)
@Test void nameComesFromSpringProfilesActiveAndSaysWhereItWasSet()
        // source "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'"
        // with spring.profiles.active=${ENV}, and systemEnvironment ENV=e2 → ("e2", "spring.profiles.active=${ENV}, application.yaml")
@Test void anEnvironmentVariableOverridesTheMapping()           // Review Focus 1
        // a SystemEnvironmentPropertySource "systemEnvironment" with SPRING_PROFILES_ACTIVE=e3 and ENV=e2, above application.yaml → ("e3", "spring.profiles.active=e3, environment variables")
@Test void additionalProfilesCount()                            // no property, additional {test} → ("test", "SpringApplication additional profiles")
@Test void sameNameTwiceIsOne()                                 // active e2 + additional {e2} → "e2"
@Test void includedProfilesDoNotCount()                         // active e2, spring.profiles.include=kafka-local → "e2"
@Test void noEnvironmentFails()                                 // SynapseConfigurationException, description and action exactly as the table
@Test void blankValueAndDefaultProfileDoNotCount()              // Review Focus 5: active "" and spring.profiles.default=local
        // → description "No environment is set: spring.profiles.active has no value. spring.profiles.default (local) does not count."
@Test void moreThanOneFails()                                   // "e2,kafka-local" → "Exactly one environment must be active, but there are 2: e2, kafka-local."
@Test void activeAndAdditionalTogetherFail()                    // active e2 + additional {test} → there are 2: e2, test
@Test void namesAreCaseSensitive()                              // "E2" → name "E2"
// ConfigSourcesTest
@ParameterizedTest void labelsBootSources()                     // every mapping listed under Interfaces, incl. "(document #1)" and a file location
// SynapseEnvironmentPostProcessorTest
@Test void runsRightAfterConfigData()                           // getOrder() == ConfigDataEnvironmentPostProcessor.ORDER + 1
@Test void isRegisteredInSpringFactories()                      // SpringFactoriesLoader lists the EPP and UnresolvedEnvironmentFailureAnalyzer
@Test void startupFailsWithoutAnEnvironment()                   // ConfigFixtures: service dir with application.yaml (one key), no profile → SynapseConfigurationException
@Test void startsWithOneEnvironment()                           // --spring.profiles.active=e2 with application-e2.yaml → context starts
// UnresolvedEnvironmentFailureAnalyzerTest
@Test void explainsAnUnresolvedPlaceholder()                    // application.yaml "spring.profiles.active: ${ENV}", no ENV → run fails; analyze(failure)
        // description "No environment is set: spring.profiles.active is '${ENV}', and ENV has no value."; action as the table
@Test void ignoresOtherFailures()                               // IllegalStateException("x") and "Profile 'a b' must ..." (no placeholder) → null
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='EnvironmentNameTest,ConfigSourcesTest,SynapseEnvironmentPostProcessorTest,UnresolvedEnvironmentFailureAnalyzerTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors.
- [ ] **Step 3: Implement** `EnvironmentName.resolve` (bind `spring.profiles.active` with `Binder.get(environment)` as `Bindable.listOf(String.class)`, trim, drop blanks, then add the additional profiles, keeping first-seen order), `ConfigSources.label`, the EPP (resolve only, for now; the analyzer pattern is *Defaults* #15) and the registrations. The EPP Javadoc lists the four steps of the *Architecture* paragraph.
- [ ] **Step 4: Run to verify pass**, then the full build: `./mvnw verify`. Every module's Spring Boot tests now need their environment (Task 4).
- [ ] **Step 5: Commit**: `feat: require exactly one active profile as the environment`.

### Task 6: The service file for the environment, and no `on-profile` documents

**Files:**
- Create: `core/.../internal/environment/ServiceConfigFiles.java`.
- Modify: `core/.../autoconfigure/SynapseEnvironmentPostProcessor.java` (after resolving: `requireEnvironmentFile`, then `rejectOnProfileDocuments`, with `application.getClassLoader()`).
- Test (create): `core/.../internal/environment/ServiceConfigFilesTest.java`.

**Interfaces:**
- Consumes: `EnvironmentName`, `ConfigFixtures` (Task 5).
- Produces:

```java
public final class ServiceConfigFiles {
    public static List<String> configNames(ConfigurableEnvironment environment);    // spring.config.name, default ["application"]
    public static boolean isEnvironmentFile(String sourceName, String configName, String environment);
    public static boolean isBaseFile(String sourceName, String configName);
    public static void requireEnvironmentFile(ConfigurableEnvironment environment, String name, ClassLoader classLoader);
    public static void rejectOnProfileDocuments(ConfigurableEnvironment environment, String name, ClassLoader classLoader);
}
```

  - `isEnvironmentFile`: the name starts with `Config resource `, contains `<configName>-<env>.` preceded by `[`, `/` or `\`, and the extension `yaml`, `yml` or `properties` is followed by `]`. `isBaseFile` is the same for `<configName>.`.
  - `requireEnvironmentFile`: valid when any source matches `isEnvironmentFile` for any config name, or `classLoader.getResource` finds `<configName>-<env>.<ext>` or `config/<configName>-<env>.<ext>`. Otherwise the *No service file* message, for the first config name.
  - `rejectOnProfileDocuments`: *Defaults* #7, *on-profile* message, where `<file>` is the resource's file name (plus ` (document #n)` for the n-th document).

- [ ] **Step 1: Write the failing tests**

```java
@Test void aLoadedEnvironmentFileMakesTheEnvironmentValid()      // sources named as Boot names them; also "file [ext\application-e7.yaml]"
@Test void anEmptyFileOnTheClasspathCounts()                    // service dir: application-e3.yaml empty, -e4 comment-only, -e8 "---" only, -e6.properties empty → each valid
@Test void aConfigDirectoryFileCounts()                         // classpath:/config/application-e2.yaml
@Test void aFileInAnExternalLocationCounts()                    // --spring.config.additional-location=optional:file:<tmp>/ holding application-e7.yaml with one key
@Test void anEmptyExternalFileDoesNotCount()                    // Review Focus 4: the same with a comment-only file → the "No service file" message, exactly
@Test void aMissingFileFails()                                  // e9 → "The environment 'e9' has no file in the service: none of application-e9.yaml, application-e9.yml, application-e9.properties was loaded or found on the classpath. Environment names are case-sensitive."
@Test void wrongCaseInAJarFails()                               // service jar with application-e2.yaml, ENV E2 → fails
@Test void onlyAModuleFileDoesNotMakeANameValid()               // module jar with META-INF/synapse/config-e2.yaml, no application-e2.* → fails
@Test void springConfigNameIsHonoured()                         // spring.config.name=books: books-e2.yaml valid; only application-e2.yaml → fails naming books-e2.*
@Test void anInactiveOnProfileDocumentFails()                   // application.yaml with a second document "on-profile: e5", running e2 → "application.yaml (document #2) sets spring.config.activate.on-profile."
@Test void anActiveOnProfileDocumentFails()                     // the same running e5 (with application-e5.yaml present)
@Test void anOnProfileDocumentInAnExternalFileFails()           // additional-location file with an on-profile document
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest=ServiceConfigFilesTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors.
- [ ] **Step 3: Implement** `ServiceConfigFiles` and the two EPP calls. Resources behind sources come from `OriginLookup.getOrigin(key)` of any key → `TextResourceOrigin.getResource()`; files are reloaded with `YamlPropertySourceLoader` or `PropertiesPropertySourceLoader` by extension.
- [ ] **Step 4: Run to verify pass**, then `./mvnw verify`.
- [ ] **Step 5: Commit**: `feat: require a service file for the environment and forbid on-profile documents`.

### Task 7: Module configuration files

**Files:**
- Create: `core/.../internal/environment/ModuleConfigFiles.java`.
- Modify: `core/.../internal/environment/ConfigSources.java` (`origin(URL, String)`; `label` of a module source), `core/.../autoconfigure/SynapseEnvironmentPostProcessor.java` (load and add after the service checks).
- Test (create): `core/.../internal/environment/ModuleConfigFilesTest.java`; (modify) `ConfigSourcesTest.java`.

**Interfaces:**
- Consumes: `ServiceConfigFiles.configNames` (Task 6), `ConfigFixtures` (Task 5).
- Produces:

```java
public final class ModuleConfigFiles {
    public static final String SOURCE_PREFIX = "Synapse module config ";
    // Highest first: every module's config-<env> sources (classpath order), then every module's config sources.
    public static List<PropertySource<?>> load(ClassLoader classLoader, String environment, List<String> configNames);
    // Removes earlier SOURCE_PREFIX sources, then inserts below the lowest "Config resource" source,
    // else before "defaultProperties", else last.
    public static void addTo(MutablePropertySources sources, List<PropertySource<?>> moduleSources);
    public static boolean isEnvironmentSource(String sourceName);   // SOURCE_PREFIX + "[config-"
}
// ConfigSources
public static String origin(URL url, String resourcePath);         // *Defaults* #12
```

  - Fixed names, in this order: `META-INF/synapse/config-<env>.yaml`, `config-<env>.properties`, `config.yaml`, `config.properties`.
  - Checks and messages: *Defaults* #8–#11 and the message table (*Collision*, *Forbidden key*, *Root file*, *Both formats*). Forbidden prefixes are compared with `ConfigurationPropertyName.isAncestorOf` or equality on `spring.profiles`, `spring.config`, `synapse.environment`.

- [ ] **Step 1: Write the failing tests**

```java
// ModuleConfigFilesTest (fixture jars client-accounts-1.4.0.jar, data-books-1.4.0.jar; service dir; ENV=e2)
@Test void precedenceFromModuleDefaultsToTheCommandLine()
        // key a: module config.yaml only                          → module value
        // key b: module config.yaml + module config-e2.yaml       → config-e2 value
        // key c: module config-e2.yaml + service application.yaml → service value
        // key d: application.yaml + application-e2.yaml           → application-e2 value
        // key e: application-e2.yaml + env var                    → env var value
        // key f: env var + --command-line                         → command line value
        // key g: module config.yaml + setDefaultProperties        → module value
@Test void sourcesSitBetweenConfigDataAndDefaults()             // exact order of names, ending ... application-e2, application, the 2 config-e2 sources, the 2 config sources, defaultProperties
@Test void readsPropertiesFiles()                               // config.properties and config-e2.properties
@Test void otherEnvironmentsFilesAreIgnored()                   // config-e1.yaml present, running e2 → not loaded
@Test void sameKeyInTwoModulesFails()                           // base-url in one jar, baseUrl in rogue-1.0.jar → "synapse.client.accounts.base-url is set by two modules: client-accounts-1.4.0.jar (config.yaml) and rogue-1.0.jar (config.yaml)."
@Test void listIndexesCollideOnTheList()                        // Review Focus 2: allowed-hosts[0] vs allowed-hosts[1] → fails on synapse.client.accounts.allowed-hosts
@Test void aModuleMayOverrideItsOwnConfigInItsEnvironmentFile() // same key in its config.yaml and config-e2.yaml → starts, config-e2 wins
@ParameterizedTest @ValueSource(strings = {"spring.profiles.active", "spring.profiles.include", "spring.config.import",
        "spring.config.activate.on-profile", "synapse.environment.anything"})
void moduleFilesMayNotDecideTheRun(String key)                  // → "client-accounts-1.4.0.jar (config.yaml) sets <key>. Module files may not ..."
@ParameterizedTest @ValueSource(strings = {"application.yaml", "application.properties", "application-e2.yml"})
void aModuleWithARootApplicationFileFails(String file)
@Test void sameFileInBothFormatsFails()                         // config.yaml and config.properties in one jar
@Test void aModuleDirectoryWorksLikeAJar()                      // a target/classes-style directory instead of a jar
@Test void rerunningReplacesTheModuleSources()                  // postProcessEnvironment twice on one environment → each module source once
@Test void noModuleFilesAddNothing()
// ConfigSourcesTest
@ParameterizedTest void originNamesJarsAndModuleDirectories()
        // jar:file:/r/client-accounts-1.4.0.jar!/META-INF/synapse/config.yaml           → client-accounts-1.4.0.jar
        // file:/w/client-accounts/target/classes/META-INF/synapse/config.yaml           → client-accounts
        // file:/w/service-books/target/test-classes/application-test.yaml (path "application-test.yaml") → service-books (test)
        // file:/w/out/META-INF/synapse/config.yaml                                       → /w/out
@ParameterizedTest void originNamesNestedJars()                 // Review Focus 3
        // jar:nested:/app/books.jar/!BOOT-INF/lib/client-accounts-1.4.0.jar!/META-INF/synapse/config.yaml → client-accounts-1.4.0.jar
        // jar:nested:/app/books.jar/!BOOT-INF/classes/!/application.yaml                                → books.jar
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='ModuleConfigFilesTest,ConfigSourcesTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors.
- [ ] **Step 3: Implement** `ModuleConfigFiles`, `ConfigSources.origin(URL, String)` and the EPP step. Load each URL with Boot's loader for its extension, through a `UrlResource`.
- [ ] **Step 4: Run to verify pass**, then `./mvnw verify`.
- [ ] **Step 5: Commit**: `feat: load module configuration from META-INF/synapse/config files`.

### Task 8: Environments under AOT

**Files:**
- Create: `core/.../internal/environment/AotEnvironmentCheck.java`.
- Modify: `core/.../autoconfigure/SynapseEnvironmentPostProcessor.java` (last step, when `aotMode.getAsBoolean()`), `core/.../internal/aot/SynapseCoreRuntimeHints.java` (two resource patterns; Javadoc).
- Test (create): `core/.../internal/environment/AotEnvironmentCheckTest.java`; (modify) `core/.../internal/aot/SynapseCoreRuntimeHintsTest.java`, `core/.../autoconfigure/SynapseEnvironmentPostProcessorTest.java`.

**Interfaces:**
- Consumes: `ServiceConfigFiles.isEnvironmentFile`/`isBaseFile` (Task 6), `ModuleConfigFiles.isEnvironmentSource`/`SOURCE_PREFIX` (Task 7).
- Produces: `public static void verify(ConfigurableEnvironment environment, String name, List<String> configNames)` (*Defaults* #14, *AOT name* message); resource hint patterns `META-INF/synapse/config*.yaml` and `META-INF/synapse/config*.properties`.

- [ ] **Step 1: Write the failing tests**

```java
// AotEnvironmentCheckTest
@Test void anEnvironmentFileThatAddsAClientFails()       // config.yaml declares accounts; config-e2.yaml sets synapse.client.payments.base-url
        // → "config-e2.yaml (client-accounts-1.4.0.jar) adds the client 'payments', which no environment-independent file declares. ..."
@Test void aServiceEnvironmentFileThatAddsAStoreFails()  // application-e2.yaml sets synapse.data.stores.legacy.url → "... adds the store 'legacy' ..."
@Test void valuesOfDeclaredNamesMayChange()              // accounts declared in config.yaml, base-url per env → passes
@Test void clientDefaultsIsNotAName()                    // synapse.client.defaults.read-timeout in config-e2.yaml → passes
@Test void namesAreComparedCanonically()                 // declared "data-books" in config.yaml, "dataBooks" in config-e2 → passes
// SynapseEnvironmentPostProcessorTest
@Test void checksNamesOnlyInAotMode()                    // the AOT fixture: new SynapseEnvironmentPostProcessor(() -> true) fails; () -> false passes
// SynapseCoreRuntimeHintsTest
@Test void registersModuleConfigResources()              // RuntimeHintsPredicates.resource().forResource(...) for
        // META-INF/synapse/config.yaml, config-e2.yaml, config.properties, config-e2.properties → all match
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am test -Dtest='AotEnvironmentCheckTest,SynapseEnvironmentPostProcessorTest,SynapseCoreRuntimeHintsTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors and the missing-hint failure.
- [ ] **Step 3: Implement** the check, the EPP step and the hints.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core -am verify`.
- [ ] **Step 5: Commit**: `feat: keep the set of clients and stores fixed per build under AOT`.

### Task 9: The startup report, and the end-to-end IT

**Files:**
- Create: `core/.../internal/environment/ConfigurationReport.java`, `core/.../autoconfigure/ConfigurationReportListener.java`.
- Modify: `core/.../internal/environment/ConfigSources.java` (`origin(PropertySource<?>)`), `core/synapse-core/src/main/resources/META-INF/spring.factories` (the listener under `org.springframework.context.ApplicationListener`).
- Test (create): `core/.../internal/environment/ConfigurationReportTest.java`, `core/.../internal/environment/EnvironmentStartupIT.java` (`@Tag("smoke")` on at least `startsAndReportsAServiceWithTwoModules`).

**Interfaces:**
- Consumes: `EnvironmentName` (Task 5), `ConfigSources.label`/`origin(URL, String)` (Tasks 5, 7), `ModuleConfigFiles.SOURCE_PREFIX` (Task 7).
- Produces:

```java
// ConfigSources: the origin of a Config resource (through its TextResourceOrigin's resource URL and path)
// or of a module source (from its name); null for any other source.
public static @Nullable String origin(PropertySource<?> source);
public final class ConfigurationReport {
    public static String render(ConfigurableEnvironment environment, EnvironmentName name);
}
final class ConfigurationReportListener implements ApplicationListener<ApplicationStartedEvent> { }
    // EnvironmentName.resolve(context environment, event.getSpringApplication().getAdditionalProfiles()),
    // then one INFO event on "io.americanexpress.synapse.environment"
```

  - Format: line 1 `Synapse environment: <name> (<setBy>)`; line 2 `Configuration, highest priority first:`; then `  <n>. <label>` per source, in property-source order, with sources skipped as in *Defaults* #13. When a source has an origin, its label is padded with spaces to the longest label that has an origin, plus two, and the origin follows.

- [ ] **Step 1: Write the failing tests**

```java
// ConfigurationReportTest
@Test void firstLineNamesTheEnvironmentAndWhereItWasSet()  // "Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)"
@Test void listsSourcesHighestFirstWithOrigins()          // exact text block for: commandLineArgs, systemEnvironment, two Config resources, two module sources, defaultProperties
@Test void neverPrintsValues()                             // sources holding "s3cr3t" and "jdbc:postgresql://db/books" → render contains neither
@Test void skipsViewsAndEmptySources()                     // configurationProperties, random, a StubPropertySource and an empty MapPropertySource are not listed
// EnvironmentStartupIT (ConfigFixtures; OutputCaptureExtension)
@Test void startsAndReportsAServiceWithTwoModules()
        // service-books-1.0.jar: application.yaml (spring.profiles.active: ${ENV}, a key), application-e2.yaml (a key);
        // client-accounts-1.4.0.jar and data-books-1.4.0.jar: config.yaml and config-e2.yaml (as in envprobe2);
        // env vars ENV=e2 and SYNAPSE_CLIENT_ACCOUNTS_MAXCONNECTIONS=99; --synapse.client.accounts.base-url=http://cmdline
        // → resolved values as in BUILD acceptance 21, and the output contains these lines in this order
        //   (Boot may list further sources, such as applicationInfo, after them):
        //   Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)
        //   Configuration, highest priority first:
        //     1. command line arguments
        //     2. Java system properties
        //     3. environment variables
        //     4. application-e2.yaml        service-books-1.0.jar
        //     5. application.yaml           service-books-1.0.jar
        //     6. config-e2.yaml             client-accounts-1.4.0.jar
        //     7. config-e2.yaml             data-books-1.4.0.jar
        //     8. config.yaml                client-accounts-1.4.0.jar
        //     9. config.yaml                data-books-1.4.0.jar
        //   (padding per the format rule above) and none of the configured values
@Test void unsetEnvIsExplained()                           // no ENV → output contains "APPLICATION FAILED TO START" and "ENV has no value"
@Test void wrongCaseFailsWithTheServiceInAJar()            // ENV=E2 → the "No service file" description
@Test void twoModulesWithOneKeyFail()                      // rogue-1.0.jar repeats a key → the collision description
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core -am verify -Dit.test=EnvironmentStartupIT -Dtest=ConfigurationReportTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors.
- [ ] **Step 3: Implement** the report, `origin(PropertySource<?>)`, the listener and its registration.
- [ ] **Step 4: Run to verify pass**, then `./mvnw verify`.
- [ ] **Step 5: Commit**: `feat: log the environment and its configuration sources at startup`.

### Task 10: `samples/rest-books` runs as `local`

**Files:**
- Modify: `samples/rest-books/src/main/resources/application.yaml`, `samples/rest-books/pom.xml`, `samples/rest-books/README.md`, `samples/rest-books/src/test/java/io/americanexpress/synapse/samples/books/BooksApplicationIT.java` (Javadoc only, if Task 4 left it stale).
- Create: `samples/rest-books/src/main/resources/application-local.yaml`.

**Interfaces:**
- Consumes: the whole feature (Tasks 1–9).

- [ ] **Step 1: Write the configuration.**
  - `application.yaml`:
    - `spring.profiles.active: ${ENV}`, with a comment: the platform sets `ENV`; local runs use `local`; tests use `test`;
    - the header comment drops the *Profiles* paragraph about `dev` and non-production profiles;
    - the `errors` comment says detail is off unless `include-detail` is true, and that `application-local.yaml` turns it on;
    - the cursor-key comment says every start logs a WARN.
  - `application-local.yaml`: `synapse.errors.include-detail: true`, commented as the local convenience.
  - `pom.xml`: `<spring-boot.run.profiles>local</spring-boot.run.profiles>` in `<properties>`.
  - `README.md`:
    - *Run it*: `ENV=local java -jar …` (and `./mvnw … spring-boot:run` runs as `local`); what a start without `ENV` prints;
    - the INFO report in *What to look for in the logs*;
    - the cursor-key line is a WARN;
    - *Production mode* becomes *Safe defaults* (`ENV=local java -jar … --synapse.errors.include-detail=false`, and detail disappears);
    - the feature table row "`detail` only outside production" becomes "`detail` only when `include-detail` is on (local)";
    - the *Tests* row for `BooksApplicationIT` (runs as `test` with `include-detail=false`).
- [ ] **Step 2: Build:** `./mvnw -pl samples/rest-books -am verify`. Expected: BUILD SUCCESS.
- [ ] **Step 3: Run it:**
  - `ENV=local java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar`: the log has `Synapse environment: local (spring.profiles.active=${ENV}, application.yaml)`, and `curl -s localhost:8080/books/00000000-0000-0000-0000-00000000dead` shows `detail`;
  - the same without `ENV`: `APPLICATION FAILED TO START` with "ENV has no value";
  - `ENV=e9`: the "No service file" description.

  Record the three outputs in the task report.
- [ ] **Step 4: Commit**: `feat(samples): run rest-books as the local environment`.

### Task 11: Docs, spec status and the full build

**Files:**
- Create: `docs/modules/ROOT/pages/environments.adoc`.
- Modify: `docs/modules/ROOT/nav.adoc` (an *Environments* entry under *Guides*, after *Core*); `docs/modules/ROOT/pages/core.adoc` (*Errors* paragraph, *Native image and AOT*, *Configuration* list without `synapse.environment.*`); `docs/modules/ROOT/pages/rest.adoc` (L454 the cursor-key WARN; L643 and L691-692 detail hidden by default); `docs/modules/ROOT/pages/testing.adoc` (L66, L114: the slices activate `test`, the module needs `application-test.yaml`, and test settings go there); `specs/v2/BUILD.md` §15 P1 (a *Done* note).

- [ ] **Step 1: Write `environments.adoc`:**
  - the model: one active profile mapped from `${ENV}`; the startup failures with their messages; case sensitivity, including that an IDE run on a case-insensitive file system may load a file whose name differs in case while a jar won't;
  - service files: `application-<env>.yaml` per environment, empty allowed, `spring.config.name`; an external env file must set at least one key (Review Focus 4); no `on-profile`;
  - module files: names, formats, the precedence table, the checks, a full example (D10's layout);
  - safe defaults and turning conveniences on (`include-detail`; later plans add reflection and `ddl-auto`);
  - keys without defaults; credentials from environment variables;
  - local runs (`spring-boot.run.profiles`, IDE run configuration, `ENV=local`); tests (`application-test.yaml`; `@ActiveProfiles("e2")` on a test class replaces `test`, but on a subclass it merges and fails, so use `inheritProfiles = false`);
  - the startup report, with an example;
  - AOT and native: the rules, the hints, and that native behaviour is not verified yet.
- [ ] **Step 2: Build the docs** (`cd docs && npx antora antora-playbook.yml`, as CI runs it). Check that the property table has no `synapse.environment.*` row and that `synapse.errors.include-detail` shows default `false`.
- [ ] **Step 3: Update BUILD.md §15 P1** with "Done (environments plan)": covered acceptance 21 and the environment part of 3; the delivery-list bullet says "Done".
- [ ] **Step 4: Run the full build on JDK 25:** `./mvnw clean verify` (in the background). Expected: BUILD SUCCESS, including the invoker tests and coverage gates.
- [ ] **Step 5: Run the JDK 21 smoke leg:** `./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke`. Expected: BUILD SUCCESS. Report the test counts per module for both runs.
- [ ] **Step 6: Commit**: `docs: document environments and module configuration`.

---

## Covered by later plans

| Spec item | Plan |
|---|---|
| CORS `*` WARN in every environment | 2c |
| gRPC server reflection off by default through a Synapse default on Boot's key, turned on by an environment file | 3 |
| `synapse.data.stores.<name>.allow-ddl-auto` (name confirmed there); store `url`/`username`/`password` without defaults; data modules' `config*.yaml` | 4 |
| The archetype's `${ENV}` mapping, `spring-boot.run.profiles=local`, `application-local.yaml`, `application-test.yaml` per module, `META-INF/synapse/config.yaml` per library module; an environment for `process-aot` and the AOT-cache training run; the native check of module resources and source names | 5 |
| Client `base-url` without a default; client modules' `config*.yaml` | P2 |
| A build-time `synapse-test` rule for a module that forgot an environment file for a key with a default | Later (DESIGN.md *Open questions*) |
