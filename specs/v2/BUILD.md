# Synapse 2 build spec

**What this is.** The implementation spec for Synapse 2.0.0: what to build, how it must behave, and how each phase
is proven done. It contains only decided ("ready") work. The reasons behind each decision, the alternatives that were
rejected, the sources, the v1 history, the open questions and the 2.x roadmap are in [DESIGN.md](DESIGN.md).

**How to use it.**
- Build one phase at a time (see *Phases*). Each phase starts with a written plan that a maintainer approves. The plan
  settles anything this spec marks *"decided in the Pn plan"*.
- Read [AGENTS.md](../../AGENTS.md) first: it holds the engineering rules and quality gates that apply to every change.
- If something here is unclear or seems wrong, or an item is listed in DESIGN.md *Open questions*, ask the maintainers.
  Don't decide it in code.
- "Must" and "never" are requirements. Property names, type names and defaults in this spec are normative.

Status: approved for implementation, 2026-10-31.

---

## 1. Baseline

| Item | Requirement |
|---|---|
| Java | Build with JDK 25 (enforced); compile with `--release 21`; no Java 22+ APIs; no multi-release jars. Tests run on JDK 21 and 25. |
| Platform | Spring Boot 4.x (scaffolded on 4.1.2), Spring Framework 7, Jakarta EE 11, Jackson 3 (`tools.jackson`), JSpecify nullness. Each phase plan re-checks the latest Boot GA and its release notes (4.2.0 is expected in November 2026). |
| Build | Maven 3.9.x through the wrapper; GitHub Actions; publishing to Maven Central (Central Portal). |
| Coordinates | groupId `io.americanexpress.synapse`, version `2.0.0` (milestones `2.0.0-M1` …). Java packages `io.americanexpress.synapse.<area>.<feature>`; every package is `@NullMarked`. |
| Versioning | Synapse semver, independent of Spring's release cadence. The **major** tracks the Boot major (Synapse 2.x = Boot 4.x). A **minor** adds Synapse features or raises the minimum Boot minor. A **patch** carries fixes and dependency/CVE bumps (including Boot patches) and ships whenever needed. The docs keep a compatibility matrix (Synapse version → supported Boot versions). Only the **latest minor** receives fixes. |
| v1 | 0.4.x lives on `develop` and reaches end of life **6 months after 2.0.0 GA**; until then it gets third-party CVE fixes where a compatible fix exists (Spring Boot 3.5 itself is out of open-source support since 2026-06-30). |
| Programming model | Imperative: Spring MVC and blocking clients on virtual threads. No reactive stack in 2.0 (a 2.x add-on); the core contracts must allow a reactive variant later without breaking changes. |

---

## 2. Artifacts in 2.0.0

Published artifacts (all `io.americanexpress.synapse:<artifactId>`). Every feature jar contains its code and its
auto-configuration; there are no separate starter or autoconfigure artifacts.

| Artifact | Phase | Purpose |
|---|---|---|
| `synapse-bom` | P0 | Versions of every Synapse artifact and the third-party libraries Synapse adds or pins (see *Dependency management*). Does not import Boot's BOM. |
| `synapse-parent` | P0 | Consumer parent; extends `spring-boot-starter-parent`. Build conventions, image build, default logging (see *Consumer parent*). |
| `synapse-core` | P1 | Contracts, invocation pipeline, `RequestContext`, errors, correlation, deadlines, JSON conventions, `@Masked`, `Clock`, observability contract, environment checks and module configuration files, AOT hints. |
| `synapse-test` | P1 | Test support: operation harness and slices, ArchUnit rules, Testcontainers definitions, fixtures, AOT hint assertions. |
| `synapse-test-rest` | P1 | @SynapseRestTest: the REST slice. Depends on synapse-test and synapse-api-rest. (gRPC and data slices get their own synapse-test-<plugin> artifacts in their plans.) |
| `synapse-logging-log4j2` | P1 | Log4j2 backend for consumers (synchronous by default), structured JSON (ECS), optional failure-log burst limit. |
| `synapse-api-rest` | P1 (XML codec and `BinaryContent` in P4) | REST plugin: `RestBindings`, problem+json, paging, conditional requests, versioning, CORS, limits. |
| `synapse-api-rest-openapi` | P1 (plan 2c) | OpenAPI 3.1 for `RestBindings` routes, served by springdoc (3.x) at /v3/api-docs: a Synapse customizer built from the resolved routes, with Synapse's own schema generator on Jackson 3. Depends on synapse-api-rest. |
| `synapse-api-grpc` | P1 | gRPC plugin on Boot's gRPC server support. |
| `synapse-data-jpa`, `synapse-data-jdbc` | P1 | Shared data behaviour: named stores, auditing, ids, error translation, migrations per store. |
| `synapse-data-postgres` | P1 | PostgreSQL vendor module. |
| `synapse-client-core` | P2 | Client wiring shared by protocols: authenticator SPI, error decoding, logging, retries, propagation. |
| `synapse-client-rest`, `synapse-client-grpc` | P2 | REST and gRPC client plugins. |
| `synapse-client-auth-oauth2` | P2 | OAuth2 client-credentials authenticator. |
| `synapse-kafka-core` | P3 | Shared Kafka wiring: CloudEvents headers, serde, observation. Never added directly by consumers. |
| `synapse-publisher-kafka`, `synapse-subscriber-kafka` | P3 | Kafka publisher (client role) and subscriber (inbound plugin role). |
| `synapse-api-graphql` | P4 | GraphQL plugin on Spring for GraphQL. |
| `synapse-api-function-aws` | P4 | AWS Lambda adapter. |
| `synapse-security` | P4 | Optional security starter (OAuth2 resource server, identity source, browser security headers). |
| `synapse-scheduling` | P4 | Scheduled and batch jobs: locks, per-run context, CronJob runner, Spring Batch conventions. |
| `synapse-data-mysql`, `-oracle`, `-sqlserver`, `-mongodb`, `-redis` | P5 | Vendor/technology modules. |
| `synapse-utilities` | P6 | Validators (`@OneOf`, `@AnyOf`, `@RequireIfPresent`, date-format check). |
| Archetypes | P6 | Generated from the reference app (set decided in the P6 plan). |

Not published: `tests/synapse-build-rules-tests` (build-rule tests) and `samples/*`.

Deferred to 2.x add-ons (do not build in 2.0): reactive stack (WebFlux plugin, reactive client, R2DBC, reactive
NoSQL); `synapse-api-function-gcp`; `synapse-client-graphql`; `synapse-client-auth-token-relay`;
`synapse-client-resilience4j` (circuit breaker, bulkhead); DB2, Cassandra and Couchbase modules; read replicas;
multi-tenancy; Kafka exactly-once, named clusters, dedupe store, batch listeners, DLT replay, transactional outbox,
Avro/Protobuf registry add-ons; protobuf REST codec. See DESIGN.md *Roadmap*.

---

## 3. Rules for every module

### 3.1 Auto-configuration
- Every feature is a Spring Boot auto-configuration: `@AutoConfiguration`, registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, conditional on its key classes
  and properties, with every bean `@ConditionalOnMissingBean` (or contributed through a customizer) so users can
  replace it.
- Customize Boot through its customizer beans. Never define Boot's own single-instance infrastructure beans
  (`JsonMapper`, `KafkaTemplate`, `RestClient.Builder`, Boot's `DataSource`). Synapse builds *its own* instances
  (named data stores, clients built from bindings, per-client mappers) with Boot's builders and property binding,
  never by hand.
- Boot 4 is modular (`spring-boot-<technology>` modules, auto-configuration under `org.springframework.boot.<technology>`).
  Synapse library jars depend on those modules, not on starters, and order themselves against the relocated
  auto-configuration classes.
- No `@ComponentScan` or `@Import` of framework packages, no `@EnableWebMvc`/`@EnableWebFlux`, no
  `configureMessageConverters` replacement, no `application.properties`, `META-INF/synapse/` files or logging
  configuration in Synapse's own library jars. (A consumer's library modules ship their settings in
  `META-INF/synapse/config*.yaml`; see *Environments and module configuration*.)
- Every auto-configuration has `ApplicationContextRunner` tests: it activates under its conditions, backs off when the
  user defines the bean, binds its properties, and stays absent when its condition isn't met.

### 3.2 Configuration properties
- Synapse properties live under `synapse.*`, are typed `@ConfigurationProperties` records with Javadoc on every
  property (the docs build fails on a missing description), and appear in the generated property reference.
- **Synapse owns the configuration of what Synapse builds; Boot owns the rest.** Clients built from Synapse bindings
  and named data stores are configured only under `synapse.*`. Everything Boot builds (server, Jackson, health,
  springdoc, gRPC server, API versioning) is configured with Boot's own keys. Synapse never adds an alias for a Boot
  property. Where Synapse wants a different default for a Boot property, an `EnvironmentPostProcessor` sets it at the
  lowest precedence, so the standard Boot key still overrides it.
- Map-style namespaces use a dedicated level or a reserved name: `synapse.data.stores.<name>`,
  `synapse.kafka.subscribers.<name>`, `synapse.kafka.publishers.<name>`; for clients, `synapse.client.<name>` with the
  name `defaults` reserved for `synapse.client.defaults.*` (startup fails if a client is named `defaults`).
- Map keys that contain characters other than letters, digits, `-` and `.` (such as error codes) are written in
  brackets: `synapse.api.rest.errors.status.[BOOK_NOT_FOUND]=423`. Docs and examples always use this form.
- There is no production concept. Every safety setting defaults to its safe value in every environment, and an
  environment file turns a convenience on explicitly (see *Environments and module configuration*).

### 3.3 Startup and AOT
- **No remote I/O during bean creation.** Checks that need infrastructure (topics, schema validation, connectivity)
  run in `SmartLifecycle.start()` and either fail startup (where this spec says "fail") or report through health.
  Data stores follow the same rule: the EntityManagerFactory is built without JDBC metadata access (the vendor module
  sets the dialect; the database version comes from `synapse.data.stores.<name>.database-version` or the vendor
  default), and migrations then schema validation run in `SmartLifecycle.start()`, in a phase before any inbound
  plugin starts (see *Named stores*). The P1 plan verifies Hibernate 7's `hibernate.boot.allow_jdbc_metadata_access`
  behaviour.
- **AOT-ready:** every module ships `RuntimeHints` for what it creates or reflects on (pipeline proxies, record
  components referenced by bindings, method-reference lambdas, beans built from bindings), asserted with
  `RuntimeHintsPredicates`. No runtime classpath scanning. Bindings are resolved during Boot's AOT processing where
  possible so startup checks still run.
- **Native image:** officially supported and tested (nightly) for core, REST, gRPC and AWS functions; best effort for
  other modules until each gets its own native test. Properties that decide which beans exist are fixed at AOT build
  time; the property reference marks them *build-time*.
- **Environments under AOT:** the environment is never used in a `@Conditional`, and environment files change values
  only. The set of clients and stores (the `<name>` keys under `synapse.client` and `synapse.data.stores`) belongs in
  files that don't depend on the environment (`config.yaml`, `application.yaml`). In AOT mode, startup fails when an
  environment file adds such a name. Synapse ships resource hints for `META-INF/synapse/config*.yaml` and
  `config*.properties`. AOT processing (`process-aot`) and the AOT-cache training run of `build-image` prepare the
  environment too, so they need an environment as well; the archetype and parent pass one (plan 5).

### 3.4 Dependency management
- Versions live in the root `pom.xml`. Overriding a Boot-managed version is allowed only as a CVE pin with the
  advisory ID and a removal condition.
- `dependencyConvergence` is enforced. Every pin that affects consumers' runtime classpath goes into `synapse-bom`
  (the root build imports the same list), with a comment and a removal condition. `synapse-parent` repeats them
  directly (see *Consumer parent*). A CI check keeps the root POM, the BOM and the parent in sync.
- Test libraries stay in test scope, except in `synapse-test` and the `synapse-test-<plugin>` slice artifacts, which exist to provide them (its POM overrides the
  ban; an invoker test proves the ban still holds elsewhere).

### 3.5 API stability
- Public API is everything outside `..internal..` packages. `japicmp` against the last release fails on
  binary-incompatible changes in minor and patch releases (from 2.0.0 GA).
- `@Incubating` (in `synapse-core`) marks APIs that may still change in a minor; japicmp ignores them.
- A gate diffs each module's `spring-configuration-metadata.json` against the last release: a removed or renamed
  property must be kept as `@DeprecatedConfigurationProperty` with its replacement.
- Deprecations happen in a minor; removal only in the next major.
- `SynapseArchitecture.forApplication(…)` includes a rule that consumer code never imports
  `io.americanexpress.synapse..internal..`.

### 3.6 Environments and module configuration
- **The environment is the one active Spring profile.** Each service's `application.yaml` maps the platform's
  variable: `spring.profiles.active: ${ENV}`. Synapse code never reads `ENV`. `SPRING_PROFILES_ACTIVE`,
  `--spring.profiles.active` and `@ActiveProfiles` work as usual, and the highest source wins. The environment is the
  value of `spring.profiles.active` plus any profile added with `SpringApplication.setAdditionalProfiles`. Feature
  profiles go in `spring.profiles.include` or profile groups, which don't count.
- **No default and no fallback.** Local runs use `local` (`spring-boot.run.profiles=local` for the Maven plugin, or the
  IDE run configuration). Tests use `test`: the Synapse slices activate it, and every module whose tests start a
  Spring Boot context has `src/test/resources/application-test.yaml`, which may be empty.
- **Names are case-sensitive.** Synapse does no case handling.
- **An environment is valid only when the service has a file for it:** `application-<env>.yaml`, `.yml` or
  `.properties` (`<name>-<env>.*` when `spring.config.name` is set). The file counts when Boot loaded it from any
  location, or when it is on the classpath (`classpath:/` or `classpath:/config/`), even when it is empty. Module
  `config-<env>` files are optional and never make a name valid.
- **Startup fails** when:
  - there is no environment: `ENV` unset (Synapse explains Boot's raw "Profile '${ENV}' must contain a letter..."
    error), a blank value, or only `spring.profiles.default`;
  - the value of `spring.profiles.active` in the source that sets it falls back to a default, such as `${ENV:local}`
    (even when `ENV` is set);
  - there is more than one;
  - the environment is not valid. This is treated exactly like no environment. The message names the files looked for
    and says that names are case-sensitive;
  - any config document sets `spring.config.activate.on-profile`. The message says to use `application-<env>.yaml`
    instead.
- **Module-owned configuration.** Each library module of a consumer project (`client-*`, `data-*`, `publisher-*`,
  `business-*`) ships its settings in `META-INF/synapse/config.yaml` and, for values that differ per environment,
  `META-INF/synapse/config-<env>.yaml` (`.properties` is read too; `.yml` is not). The service references nothing.
  One `EnvironmentPostProcessor` in `synapse-core` runs right after Boot's config data, finds these files with
  `ClassLoader.getResources` on the fixed names (no pattern scanning), and adds them below all of Boot's config data and
  above Boot's default properties.
- **Precedence**, lowest first: module `config.yaml` < module `config-<env>.yaml` < service `application.yaml` <
  service `application-<env>.yaml` < environment variables and the command line. The service always wins over a
  module, silently.
- **Module checks** (startup fails):
  - two modules set the same key. Keys are compared as Boot's canonical property names, so `base-url` and `baseUrl`
    collide. A module's `config.yaml` and its own `config-<env>.yaml` may overlap: that is the override;
  - a module file sets `spring.profiles.*`, `spring.config.*` or `synapse.environment.*`;
  - a jar or directory that ships `META-INF/synapse/` also ships a root `application.*`;
  - a module ships the same file as both `.yaml` and `.properties`.
- **Safe in every environment.** Every safety setting defaults to its safe value, and an environment file turns a
  convenience on (for example `synapse.errors.include-detail: true` in `application-local.yaml`). Synapse never
  branches on the environment's name.
- **Startup report.** After startup, Synapse logs one INFO block. The first line names the environment and where it was
  set (`Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)`). Then it lists every property
  source, highest priority first, by name and origin: command line, environment variables, each config file with the
  service or the module jar or directory it came from, defaults. It never logs values.
- **Keys without defaults.** Keys that say *where* to connect or *who* to connect as have no Synapse default: a
  client's `base-url`; a store's `url`, `username` and `password`. A module that forgets an environment file then
  fails binding at startup. Credentials come from platform environment variables. Tuning keys (timeouts, retries,
  pools, logging) keep safe defaults.

---

## 4. Consumer project structure

### 4.1 Modules
A consumer application is a multi-module Maven project. Module types are fixed from day one:

```
app-name/
  service-<name>    bootable deployable. Operations, the service's own models, business logic used only here,
                    and protocol bindings:
      <base>/operation/        ServiceOperation beans (Query/Command)
      <base>/model/            the service's models: the records its operations take and return
      <base>/business/         BusinessOperation beans, business models, business error enums
      <base>/api/rest/         RestBindings, RestConventions
      <base>/api/grpc/         GrpcBindings (+ src/main/proto until it moves to contract-<name>)
      <base>/api/graphql/      GraphQlBindings (+ src/main/resources/graphql)
      <base>/api/kafka/        KafkaBindings (subscribers)
  contract-<name>   the service's published contract: model records other services use, error codes callers may
                    match, the events the service publishes, .proto/GraphQL schema files and the Java classes
                    generated from the .proto at build time. Required from day one when the service publishes
                    events; otherwise created when another service first needs the models.
  business-<name>   business logic shared by more than one deployable (same rules as the business package)
  data-<name>       entities (the data module's own model) and Spring Data repositories for one named store
  client-<name>     a typed client interface for one downstream service plus its bindings and error enums; typed
                    with the downstream's contract-<name> models
  publisher-<name>  publisher bindings for a service's events (event records come from its contract-<name>)
```

Configuration files (see *Environments and module configuration*):
- `service-<name>`: `application.yaml` (with `spring.profiles.active: ${ENV}`) and one `application-<env>.yaml` per
  environment, including `application-local.yaml`.
- `business-`, `data-`, `client-` and `publisher-` modules: `META-INF/synapse/config.yaml` and, where values differ
  per environment, `META-INF/synapse/config-<env>.yaml`. Never a root `application.*`.
- Every module whose tests start a Spring Boot context: `src/test/resources/application-test.yaml`.

### 4.2 Model ownership
- **Every module owns its model, and a caller uses the callee's model and does any mapping itself.**
  - A service owns its models; API plugins bind directly to them (there are no separate "API records").
  - A business owns its models; an operation calling it maps to them.
  - A data module owns its entities; business or operation code uses them through repositories.
  - A client module is typed with the called service's contract models and builds the protocol request.
  - A publisher module publishes the event records from the service's contract.
- **Error enums live with the code that raises them:** business errors in `business`, operation-only errors in
  `operation`, client errors in the `client-*` module. Codes that callers may match are defined in
  `contract-<name>` when it exists (the business code then uses the contract enum).

### 4.3 Layering rules
Enforced at package level by ArchUnit rules shipped in `synapse-test` (`SynapseArchitecture.forApplication(App.class)`,
or `forRoots("com.acme.pricing")` for a module without an application class). The root is explicit, never inferred: a
layer is the first package segment below a root (`<root>.operation..`, `<root>.business..`), so a root such as
`com.acme.data.orders` works. Only classes under the roots are checked, and a dependency on a class outside them is
never a layer. The archetype and reference app contain a one-line test that applies them. A team can exclude one rule
only with a written reason: `SynapseArchitecture.forApplication(App.class).except(rule, "reason")`.
- **Protocol-free:** `operation..`, `model..`, `business..` and contract model packages never import
  `org.springframework.web..`, `org.springframework.http..`, `jakarta.servlet..`, `io.grpc..`, `graphql..`,
  `org.springframework.graphql..`, `com.google.protobuf..`, `org.apache.kafka..`, `org.springframework.kafka..` or
  `io.americanexpress.synapse.api..`. Classes generated from `.proto` are exempt.
- **Call directions:** `api → operation`; `operation → business | client | data | publisher` (and other operations);
  `business → business | client | data | publisher`. This is an allow-list: every other edge between layers is
  forbidden (matrix below). One rule per target layer, so a team can exclude one direction; `CALL_DIRECTIONS` is all
  of them.
- **Forbidden:** `business → operation | api`, and `business →` the service's own `model` package except
  `model.event..` / the contract's event package.
- **Operations call business code through the contract:** an operation depends on business code only through types
  that implement `BusinessOperation`, and calls only their `execute`. A business Spring bean, interface or abstract
  class that isn't a `BusinessOperation` is off limits to operations; business models (everything non-bean in
  `business.model`), sealed results, error enums, exceptions, annotations and value objects are used directly. An
  operation calls no `execute` overload, constructs no business code other than those values (`new PriceBook()`
  bypasses the pipeline; a non-record builder or helper in `business` can't be constructed by an operation), and calls
  no static method of another business class (a static call whose business callee ArchUnit can't resolve, because its
  class is not in the imported set, is not flagged). Code used only inside the business layer (helpers, strategies,
  mappers, validators, listeners, configuration) need not implement `BusinessOperation`.
- **No entities in the API:** the input or output type of a bound operation is never a persistence entity
  (`@Entity`, `@Document`, `@Table` or a type implementing a Synapse data capability); also checked at startup.
- **No Synapse internals:** consumer code never imports `..internal..`.
- **Null-marked models:** `operation..`, `model..`, `business..` and contract packages are `@NullMarked` (the archetype
  generates their `package-info.java`). Binding checks, OpenAPI `required` lists and gRPC wrapper mapping read
  JSpecify nullness; in an unmarked package every component counts as nullable.

Call-direction matrix (row: the layer of a class; column: a layer it may depend on). A layer may always use itself.
Business → `model` is allowed here and narrowed to `model.event` by the *Forbidden* rule.

| from \ may use | api | operation | business | client | data | publisher | model | contract |
|---|---|---|---|---|---|---|---|---|
| api | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ |
| operation | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| business | ✗ | ✗ | ✓ | ✓ | ✓ | ✓ | `model.event` | ✓ |
| client | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✓ |
| data | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✓ |
| publisher | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | `model.event` | ✓ |
| model | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ |
| contract | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✓ |
| no layer (e.g. `config`) | ✗ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

---

## 5. Core (`synapse-core`)

### 5.1 Contracts
| Type | Definition |
|---|---|
| `ServiceOperation<I, O>` | `O execute(I input)`. **Sealed**: `permits QueryOperation, CommandOperation`. `I` and `O` are typically records. Returning `Optional<T>` means "may be absent"; `void`/`null` means no content. |
| `QueryOperation<I, O>` | Non-sealed. Reads, no state change. Safe to retry; REST GET/HEAD and GraphQL `Query`. |
| `CommandOperation<I, O>` | Non-sealed. Changes state. REST POST/PUT/PATCH/DELETE and GraphQL `Mutation`. |
| `BusinessOperation<I, O>` | `O execute(I input)`. A separate interface, never bindable to a protocol (bindings reject it at startup). Mandatory for business logic that operations call: the only way an operation calls business code. |
| `PublisherOperation<E>` | `void publish(E event)` blocks until the broker acknowledges; `CompletableFuture<Void> publishAsync(E event)`. Broker-neutral. |
| `RequestContext` | Read-only view: correlation id, principal (verified identity, if any), locale, deadline (optional), string attributes. `RequestContext.current()`. Never contains servlet, gRPC or GraphQL types. |
| `PageQuery`, `PageResult<T>` | `PageQuery`: cursor **or** offset, size, sort. `PageResult<T>`: items, next cursor, optional total. One-call conversion from Spring Data `Page`/`Slice`. |
| `BinaryContent` | Protocol-neutral file/stream content: stream supplier, content type, filename, size (see *REST*). |
| `ErrorDetails` | `String code(); String messageTemplate(); ErrorCategory category();` implemented by team enums. |
| `ErrorCategory` | `INVALID_INPUT, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, PRECONDITION_FAILED, RATE_LIMITED, UNAVAILABLE, TIMEOUT, INTERNAL`. Protocol-neutral, domain-only. |
| `SynapseException` | `SynapseException(ErrorDetails, Object... args)` and `(ErrorDetails, Throwable, Object... args)`. Message formatted from the template with `MessageFormat`; a `MessageSource` entry keyed by `code()` overrides it. |
| `SynapseErrors` | The framework's own `ErrorDetails` enum: `MALFORMED_REQUEST`, `VALIDATION_FAILED` (`INVALID_INPUT`), `NOT_FOUND`, `UNAUTHENTICATED`, `FORBIDDEN`, `CONFLICT`, `STALE_VERSION` (`CONFLICT`: a version check or optimistic lock failed), `PRECONDITION_FAILED`, `RATE_LIMITED`, `UNAVAILABLE`, `DEADLINE_EXCEEDED` (`TIMEOUT`), `INTERNAL`, each with its matching category unless stated. |
| `ExceptionTranslator` | SPI: turns a non-Synapse exception into a `SynapseException`. Modules contribute translators (data access, Spring Security, clients, Kafka); core has no data or security dependency. |
| `@OperationName`, `@OperationGroup` | Operation classes are named `<UseCase>Operation` (inputs `<UseCase>Request`, outputs nouns such as `Book`). The operation name defaults to the class name without the `Operation` suffix, in kebab-case (`GetBookOperation` → `get-book`); `@OperationName("…")` overrides it; `@OperationGroup("books")` groups operations into a gRPC service/REST resource and drives docs. |
| `@Incubating` | Marks evolving public API (see *API stability*). |
| `Clock` bean | UTC `java.time.Clock` (`@ConditionalOnMissingBean`) used for deadlines, auditing, `ce_time`, UUIDv7 and token expiry. |

### 5.2 Invocation pipeline
Applied by wrapping every `ServiceOperation` bean (AOP proxy or decorator registered by auto-configuration), so API
plugins, `@RestController` escape hatches, subscribers, scheduled jobs and tests get identical behaviour. Order,
outermost first:
1. **Observation** `synapse.operation` (see *Observability*). Nested calls produce child observations.
2. **Context:** use the current `RequestContext`, or create one (generated correlation id, no principal) when there
   is none (scheduled jobs, runners, direct calls). Nested calls reuse it.
3. **Deadline:** if the deadline has passed, fail with `TIMEOUT` without executing.
4. **Validation:** Jakarta Validation on `I` (skipped when the type has no constraints); failure is `INVALID_INPUT`
   with violations.
5. **Execute.** A Spring `@Transactional` boundary, if present, sits inside the Synapse advisor (Synapse never opens
   transactions itself).
6. **Translation:** `SynapseException` passes through; other exceptions go through the `ExceptionTranslator`s;
   anything left becomes `INTERNAL` (cause logged, never exposed).
7. **Logging:** failures only, by the outermost invocation only (see *Logging*). Successful calls log nothing.

`BusinessOperation` beans get a reduced pipeline: validation on `I` and a child observation `synapse.business`
(configurable, see *Observability*); no context, deadline, translation or logging steps. `@Transactional` works on
business `execute`; the operation is the preferred transaction boundary and a business bean joins it by default.

An operation may call other operations (same pipeline, same `RequestContext`).

### 5.3 Context propagation
- **Tracing is required.** `synapse-core` depends on Boot's Micrometer Tracing module; service modules add the
  OpenTelemetry bridge (`spring-boot-starter-opentelemetry`, added by the archetype). Startup fails with a clear message
  when no tracer is present or tracing/baggage is disabled while Synapse context is in use. The message names the
  cause: when baggage was turned off by `OTEL_PROPAGATORS` without `baggage` (Boot maps `OTEL_*` variables to
  properties), it names that variable and the fix; with the OpenTelemetry Java agent, the app keeps the starter and
  defines an `OpenTelemetry` bean returning `GlobalOpenTelemetry.get()`. `synapse.observability.require-tracing=false`
  (default `true`) turns the check off (one INFO line if tracing cannot carry the context), and a bean named
  `synapseTracingRequirementCheck` replaces it.
- `RequestContext` reads one Synapse context value (correlation id, allow-listed attributes, principal, locale,
  deadline) registered with Micrometer's context-propagation library (`ThreadLocalAccessor`). Whenever Synapse opens a
  context (an inbound edge, or the pipeline when there is none), it opens tracing baggage for the correlation id and
  the allow-listed attributes in the same scope, so propagation and MDC go through tracing. Those are Boot baggage
  fields (`management.tracing.baggage.remote-fields` / `.correlation.fields`); Synapse merges its field names into the
  user's lists at startup, because Boot's OpenTelemetry bridge reads them only from properties. The context value
  keeps `RequestContext` working where baggage is a no-op (Boot's sliced tests use a no-op `Tracer`). OpenTelemetry is
  the supported tracing bridge. All of it follows the request across threads; Synapse registers a
  `ContextPropagatingTaskDecorator` (it backs off only if the user defines a `ContextPropagatingTaskDecorator` or turns
  on Boot's `spring.task.execution.propagate-context`; other `TaskDecorator` beans are composed with it).
  `RequestContext.toString()` shows the correlation id, locale, deadline, whether a principal is present and the
  attribute names, never the principal or the attribute values, so logging the context leaks nothing.
- Inbound edges (API plugins, subscribers) set the context; operations and business code only read it.
- **Inbound trust:** allow-listed attributes (`synapse.api.rest.context.headers.<attribute>=<Header>`,
  `synapse.api.grpc.context.metadata.<attribute>=<key>`) and baggage are accepted by default, within hard limits
  (`synapse.context.inbound.max-entries`, default 16; `synapse.context.inbound.max-size`, default 4 KB; excess is
  dropped and logged once). A public-facing service sets `synapse.context.inbound.trusted=false`: inbound context
  headers and baggage are dropped and the trace is restarted at the edge. At the REST edge this works on headers:
  before tracing extracts them, the edge hides `traceparent`, `tracestate`, `baggage`, the B3 headers and the remote
  baggage-field headers, so the server span starts a new trace. The OpenTelemetry Java agent extracts before any
  filter, so under the agent the Synapse agent extension (plan 5) does this; until then `trusted=false` does not
  restart the trace under the agent (documented).
- **Never for security:** values used for security decisions (tenant, roles) never come from headers or baggage,
  only from the verified credential (see *Security*). The principal and locale never travel as baggage.
- **Outbound:** correlation id, W3C trace context and the remaining deadline are always forwarded on client calls.
  Other attributes are forwarded only when listed: `synapse.client.defaults.propagate=tenant,channel`, overridable per
  client (empty for third-party APIs). Synapse removes non-listed baggage entries from outbound client requests.
  It does so on the outbound `baggage` header and the remote baggage-field headers (primitive in P1 plan 2a, wired
  into clients in P2; under the agent, in the plan-5 extension).
  `Authorization` and cookies are never propagated. Deadlines never travel on messages.

### 5.4 Correlation id
- Header `X-Correlation-Id` (`synapse.correlation.header`). Accepted only if it is 1–128 characters of
  `[A-Za-z0-9._:-]` (`synapse.correlation.max-length`, default 128); otherwise replaced with a new UUID and the original
  logged once at WARN, sanitized and truncated. Generated when absent.
- Echoed on responses; present in MDC, logs, problem+json, gRPC metadata and Kafka headers; tagged on the current span.

### 5.5 Deadlines
- REST reads a relative `Request-Timeout: <milliseconds>` header (`synapse.api.rest.deadline.header`; a Synapse
  convention, not a standard); gRPC uses its native deadline. Requests without one get
  `synapse.api.rest.deadline.default` (and the gRPC/GraphQL equivalents). Requested budgets are capped by
  `synapse.api.rest.deadline.max`. Defaults: `default` 10 s, `max` 30 s. Malformed values are ignored.
- Scheduled and runner invocations have no deadline unless `synapse.scheduling` sets one. Kafka records use the
  subscriber's processing timeout (see *Subscriber*).
- Clients cap their timeouts to the remaining budget and forward it.

### 5.6 JSON and masking
- Synapse uses Boot's `JsonMapper` and customizes it only through Jackson 3 builder customizers. Naming and other
  Jackson settings use Boot's own properties (e.g. `spring.jackson.property-naming-strategy`). Client and publisher
  bindings may declare their own wire format (see *Clients*, *Publisher*).
- `@Masked` (maskers: all characters, fixed length `***`, last four) redacts fields in **everything Synapse logs**
  (payload logging masks before it logs, so it works with any backend). Applications can use the same masking for
  their own log lines. It never applies to API responses, outbound client requests, published events or persisted
  data. (v1's national-identifier, telephone and postal-code serializers formatted values and are not maskers; they
  are not carried over.)

### 5.7 Observability
- Micrometer Observation around every operation, binding, client call and Kafka record; export through Boot's own
  OpenTelemetry/OTLP support.
- **Metric and span contract** (stable API): `synapse.operation` with low-cardinality tags `operation`, `kind`
  (`query`/`command`), `outcome` (`success`/`error`), `error.category`, `error.code` and `error.type` (bounded by the
  error enums; `error.type` is OpenTelemetry's standard key and equals `error.code`; all three are `none` on success).
  A JVM `Error` propagates untranslated and is tagged `error.category=INTERNAL`, `error.code=ERROR`.
  Business: `synapse.business` (tags `business`, `outcome` and `error.type`), controlled by
  `synapse.observability.business=spans|metrics|off` (default `spans`). Business exceptions are not translated, so its
  `error.type` is the `SynapseException` code, `ERROR` for a JVM `Error`, `_OTHER` for any other exception and `none`
  on success. `outcome` stays beside `error.type` (revisit before GA).
- Synapse enables Spring's `@Scheduled` observation (`tasks.scheduled.execution`) by registering the
  `ObservationRegistry`.
- The docs recommend tail sampling in the collector (Boot samples 10% by default), so error traces are kept.

### 5.8 Logging
- Synapse jars depend only on the SLF4J API and never choose a backend.
- Operations: failures only, on the logger `io.americanexpress.synapse.operation`. Client-error categories at WARN;
  `INTERNAL` at ERROR with the stack trace. `UNAVAILABLE` and `TIMEOUT` log the cause's stack trace at WARN when the
  failure has a cause (a translated downstream fault); Synapse's own drain rejection and deadline check have none and
  stay one line. Each line carries operation, code, category and correlation id. Payload logging is opt-in per
  operation (`synapse.operations.<operation-name>.logging.payloads=true`) at DEBUG with `@Masked` fields redacted,
  on the logger `io.americanexpress.synapse.payload`, a sibling of the failure logger so failure-logger filters never
  apply to payloads.
- `synapse-logging-log4j2`: Log4j2 2.x, **synchronous** by default, Boot structured JSON logging (defaults
  `logging.structured.format.console=ecs` at lowest precedence), an optional burst limit on failure logs (off unless
  `synapse.logging.failure-burst.rate` and `.max-burst` are set; a Log4j2 `BurstFilter` at ERROR on the failure logger,
  so WARN and ERROR failure lines are limited and FATAL never is). Masking is done by Synapse before logging (see *JSON
  and masking*). Async is opt-in (add `com.lmax:disruptor` and set one documented property). It ships no
  configuration file that would override the application's.

### 5.9 Inbound load and shutdown
- **Concurrency limit:** every inbound plugin caps concurrent in-flight work (`synapse.api.rest.max-concurrent-requests`,
  `synapse.api.grpc.max-concurrent-calls`, `synapse.api.graphql.max-concurrent-requests`; Kafka is bounded by its
  concurrency). When full, it answers `UNAVAILABLE` (REST 503 with `Retry-After`) without running the operation.
  Defaults: REST 200, counting every request except Actuator management paths (which are never limited), with
  `Retry-After: 1`; the gRPC and GraphQL defaults are set in their plans. (With virtual threads, Boot's thread-pool settings no longer limit concurrency.)
- **Shutdown order** (Spring waits for each phase at most `spring.lifecycle.timeout-per-shutdown-phase`): stop inbound
  (HTTP, gRPC, GraphQL, Kafka consumers, schedulers) → drain in-flight operations (the pipeline counts them) → flush
  publishers (`publishAsync` futures) → close clients and pools. The drain thread itself waits at most 10 minutes, so a
  phase Spring has stopped waiting for does not leave it waiting forever. During the drain the pipeline rejects with
  `UNAVAILABLE` only an outermost call that finds no `RequestContext` (a `@Scheduled` method or runner); a call in an
  existing or inherited context (an admitted request fanning out to other threads) is admitted and awaited. The docs give the Kubernetes formula
  (preStop delay plus the phases < `terminationGracePeriodSeconds`).

### 5.10 Health
- Spring Boot Actuator probes. Liveness reflects internal state only. **Readiness reflects the application's own
  state only by default.** Each data store and client can opt in: `synapse.data.stores.<name>.health.readiness=true`,
  `synapse.client.<name>.health.readiness=true`. A store's readiness check uses its own short timeout so a busy pool
  doesn't flip it.
- Load-balancer paths use Boot's health-group `additional-path` (e.g. `/health`); Synapse adds no alias.
- Actuator exposure stays at Boot's default (health only).

---

## 6. API plugins

### 6.1 Common to every inbound plugin
- **Bindings** are beans in the service module's `api.<protocol>` package. They reference operations by bean and
  record components by **method reference** (accessor), never by string, e.g. `.header("If-Match", UpdateBookRequest::version)`.
  Method-reference lambdas are serializable and resolved to component names (as Spring Data's `TypedPropertyPath`
  does); Synapse ships their native metadata.
- **Startup checks** (the app fails to start; the message names the route and component): a referenced component
  isn't part of the route's input or output type; a non-nullable input component has no source; two sources target
  the same component; a `BusinessOperation` is bound; a `CommandOperation` is bound to GET/HEAD or a GraphQL `Query`
  field, or a `QueryOperation` to a GraphQL `Mutation`; a bound type is a persistence entity.
- **Input conflicts:** the declared location wins; if another location carries the same component with a different
  value (path `/books/1`, body `"id": 2`), the request fails with `INVALID_INPUT`. Unknown fields are ignored.
- **Errors:** each plugin maps `ErrorCategory` to its protocol (table below). Overrides are keyed by error code,
  globally or per operation (`synapse.api.rest.errors.status.[CODE]`, `synapse.api.rest.operations.<op>.errors.status.[CODE]`,
  `synapse.api.grpc.errors.status.[CODE]`); precedence: per-operation > global > category default.
- **Exposure inventory:** at startup each plugin logs every exposed operation with protocol, route/method and port.

| Category | REST | gRPC | GraphQL (`ErrorType`) |
|---|---|---|---|
| INVALID_INPUT | 400 | INVALID_ARGUMENT (+ `google.rpc.BadRequest`) | BAD_REQUEST |
| UNAUTHENTICATED | 401 (+ `WWW-Authenticate`) | UNAUTHENTICATED | UNAUTHORIZED |
| FORBIDDEN | 403 | PERMISSION_DENIED | FORBIDDEN |
| NOT_FOUND | 404 | NOT_FOUND | NOT_FOUND |
| CONFLICT | 409 | ABORTED (a unique-constraint violation: ALREADY_EXISTS) | BAD_REQUEST |
| PRECONDITION_FAILED | 412 | FAILED_PRECONDITION | BAD_REQUEST |
| RATE_LIMITED | 429 | RESOURCE_EXHAUSTED | INTERNAL_ERROR |
| UNAVAILABLE | 503 | UNAVAILABLE | INTERNAL_ERROR |
| TIMEOUT | 504 | DEADLINE_EXCEEDED | INTERNAL_ERROR |
| INTERNAL | 500 | INTERNAL | INTERNAL_ERROR |

- **Error exposure:** responses always include the code, the correlation id and validation violations. The detail
  (the formatted message) is included only when `synapse.errors.include-detail` is `true` (default `false`, in every
  environment; `application-local.yaml` and test files turn it on). A `MessageSource` entry that equals the code (Boot's `use-code-as-default-message`) counts as no entry. Never stack
  traces (encrypted or not), exception class names or internal messages. Problem bodies carry no RFC 9457 `instance`;
  the `correlationId` member is the lookup key. Full detail and cause are logged server-side.
- **Protocol-only failures** never reach an operation; the plugin answers them in the same error shape with fixed
  codes: REST `ROUTE_NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405 (+ `Allow`), `NOT_ACCEPTABLE` 406,
  `PAYLOAD_TOO_LARGE` 413, `UNSUPPORTED_MEDIA_TYPE` 415, `MALFORMED_REQUEST` 400, `PRECONDITION_FAILED` 412 (a weak `If-Match`, or an `If-Match`/`If-None-Match` that the route doesn't bind), `PRECONDITION_REQUIRED` 428 (`.requireIfMatch()` without `If-Match`); gRPC `UNIMPLEMENTED` and
  `RESOURCE_EXHAUSTED` (message too large).

### 6.2 REST (`synapse-api-rest`)
**Bindings.** A `RestBindings` bean compiled to Spring MVC functional routes:
```java
@Bean
RestBindings books(GetBookOperation getBook, SearchBooksOperation searchBooks, CreateBookOperation createBook,
                   UpdateBookOperation updateBook) {
    return RestBindings.builder()
        .get("/books/{id}", getBook)
            .body(BookResult::book).etag(BookResult::version).notFound(BookErrors.BOOK_NOT_FOUND)
        .get("/books", searchBooks)
        .post("/books", createBook).created(book -> "/books/" + book.id())
        .put("/books/{id}", updateBook).header("If-Match", UpdateBookRequest::version)
        .build();
}
```
The verb is always explicit. `@RestController` classes that inject operations remain the escape hatch and get the same
pipeline, error mapping and correlation handling.

**Input binding** (a per-route declaration beats an app convention, which beats a default convention):

| Location | Default | Declared form |
|---|---|---|
| Path variable | `{id}` → component `id` (template name = component name) | `.path("bookId", GetBookRequest::id)`; a path variable that matches no component fails at startup |
| Query parameter | simple components (string, number, boolean, enum, date/time, lists of these) by name, **GET/HEAD only** | `.query(SearchBooksRequest::author)`, `.query("sort_by", SearchBooksRequest::sort)` |
| Framework types | `PageQuery` binds from the query-string parameters `cursor`, `offset`, `size`, `sort` on any verb (names under `synapse.api.rest.paging.parameters.*`). It may be the whole input, and an input has at most one. A query component that reuses a paging parameter name fails startup. | none |
| Body | POST/PUT/PATCH: the JSON body fills every component not bound elsewhere | `.body(UpdateBookRequest::changes)` binds the body to one component |
| Header / cookie | never implicit | `.header("If-Match", UpdateBookRequest::version)`, `.cookie(...)` |
| Request context | none | `.context(UpdateBookRequest::requestedBy, ctx -> ctx.principal())` |
| Custom | none | `.from(UpdateBookRequest::region, request -> ...)` (may use protocol types; it lives in `api..`) |

App-wide conventions are declared once in a `RestConventions` bean and apply **by type**: every component of a shared
record type (e.g. `.query(WriteOptions.class)` binds all of `WriteOptions` from the query string) and framework
types. Values are converted with Spring's `ConversionService`; validation runs once on the assembled record.

**Paging safety:** sort keys must be allow-listed per route (`.sortable(SearchBooksRequest.SortKey.class)` or a set of
names). The syntax is `sort=title,-published` (a comma list; `-` means descending). An unknown key, or `sort` on a
route without `.sortable`, is `INVALID_INPUT`. Offsets are capped (`synapse.api.rest.paging.max-offset`, default
1000). Page size defaults to `synapse.paging.default-size` (20) and is capped by `synapse.paging.max-size` (100). The
plugin checks size and offset before the pipeline and reports violations under the parameter names.

Cursors are opaque. The edge wraps an operation's cursor with a `CursorCodec` bean, which an app can replace. By
default it is AES-256-GCM authenticated encryption, bound to the route's operation, the sort and a digest of the other
inputs (not the size), with a key id and an issue time. A tampered, foreign, mismatched or expired cursor is
`INVALID_INPUT` on `cursor`.

The key comes from `synapse.api.rest.paging.cursor.key` (base64, 256 bits). `cursor.previous-keys` are accepted for
decryption during rotation. `cursor.max-age` is unset by default (no expiry). When no key is set, one is generated per
start, with a startup WARN (in every environment) that cursors then fail across restarts and instances. An operation's
raw cursor never reaches the client.

**Response mapping:**

| Case | Default | Route option |
|---|---|---|
| GET with a value | 200 | `.status(...)` |
| `Optional` empty | 404 problem+json, code `NOT_FOUND` | `.notFound(BookErrors.BOOK_NOT_FOUND)`; `.emptyAs(204)` |
| Empty list | 200 with `[]` | none |
| `PageResult` | 200 with `{"items":[…],"nextCursor":"…","hasNext":true,"total":42}` (`nextCursor` and `total` omitted when null); GET/HEAD also send `Link: <relative URL>; rel="next"` with the next cursor, or with the next offset while it is within `max-offset` | none (output `.header`, `.body` and `.etag` fail startup) |
| POST | 201 + `Location` when `.created(fn)` is declared; otherwise 200 | `.status(...)` |
| Accepted for async processing | none | `.accepted(fn)`: 202 + `Location` of the status resource, also linked in the body |
| `void`/`null` | 204 | `.status(...)` |
| DELETE | 204 | `.status(200)` to return a body |
| Depends on the output | none | `.status(r -> r.queued() ? 202 : 200)` |

- `.header("X-RateLimit-Remaining", BookResult::remaining)` writes an output component to a response header; such a
  component is removed from the body unless `.alsoInBody()`. `.body(BookResult::book)` makes one component the body.
  App-wide header rules go in `RestConventions` by type.
- **Conditional requests** (RFC 9110). `.etag(BookResult::version)` emits a strong `ETag` from a simple output
  component: the version, plus a representation suffix when the route serves several media types. It is sent on
  GET/HEAD and on a POST's 201, never on PUT or DELETE (§9.3.4). ETag'd GET/HEAD responses carry
  `Cache-Control: no-cache` and `Vary: Accept`. `.etag` on a List or `PageResult` output fails startup.

  On GET/HEAD the plugin evaluates `If-None-Match` after `execute` against the output's tag (weak comparison). On a
  match it answers 304 with `ETag`, `Cache-Control` and `Vary` and no body.

  On unsafe methods the operation's conditional write enforces the precondition. `If-Match` (and `If-None-Match`)
  bound to a component are parsed as entity tags (`.header("If-Match", UpdateBookRequest::version)`):
  - one strong tag is unquoted and converted;
  - `*` binds `null` (the component must be `@Nullable`);
  - a list or an unquoted value is `INVALID_INPUT`;
  - a weak `If-Match` is 412 without calling the operation.

  A `STALE_VERSION` failure is 412 `PRECONDITION_FAILED` when the request carried a bound `If-Match`, and 409
  otherwise. Other failures map as usual, and a missing resource is 404. An `If-Match` or `If-None-Match` on an unsafe
  route that doesn't bind it is 412: the precondition can't be verified. `If-Unmodified-Since` and
  `If-Modified-Since` are ignored (Synapse emits no `Last-Modified`). `.requireIfMatch()` answers 428
  `PRECONDITION_REQUIRED` when `If-Match` is absent (`*` counts as present).
- Errors are RFC 9457 `application/problem+json` with extension members `code`, `correlationId` and `violations[]`
  (each `{field, message}`). With `synapse.errors.type-base-uri` unset, `type` is `about:blank` and `title` is the HTTP
  status phrase; when set, `type` is the base URI plus the code and `title` comes from the category. `status` always
  equals the real status. `violations` is omitted when empty. With `type-base-uri` set, `type` is the base URI, `/`
  and the code, and the category title can be overridden with the `MessageSource` key `synapse.category.<CATEGORY>`.
  A 401 without `synapse-security` carries `WWW-Authenticate: Bearer`.
- No generic `{data, meta}` wrapper: a single resource is the body itself, and a `PageResult` is the page object above.
  The correlation id, the paging `Link` and `ETag` travel in headers.

**API versioning:** off unless a binding uses `.version("2")`. Then Synapse enables Spring Framework 7's API versioning
with a path-segment strategy and a predicate so that only versioned routes are parsed; versioned routes require a
version, unversioned routes keep working. All of it is tunable with Boot's `spring.mvc.apiversion.*` properties.
Deprecated versions emit Spring's deprecation/sunset headers.

**CORS:** off by default. `synapse.api.rest.cors.allowed-origins`, `.allowed-methods`, `.allowed-headers`,
`.exposed-headers`, `.allow-credentials`, `.max-age`, applied to all routes through a `CorsConfigurationSource` bean
(which Spring Security also uses when present). Startup fails on `*` with credentials and warns on `*` in every
environment.

**Limits:** request body 1 MB (`synapse.api.rest.max-request-size`; per route `.maxRequestSize(...)`), rejected with
413 without reading the whole body. Header size uses Boot's `server.max-http-request-header-size`; Synapse sets its
default to 16 KB at lowest precedence.

**OpenAPI** (plan 2c, module `synapse-api-rest-openapi`): a Synapse customizer built from the resolved routes
generates OpenAPI 3.1 from `RestBindings`, `RestConventions` and the record types: Jakarta Validation constraints,
statuses, mapped headers, problem+json responses, paging parameters, `ETag`/`If-Match`, versions. It uses Synapse's own
schema generator on Jackson 3, so property names match what the app's `JsonMapper` writes, and springdoc (3.x) serves
the result at `/v3/api-docs`. Toggles use springdoc's own properties; Synapse defaults Swagger UI to off and the API
docs to on, at lowest precedence.

**Binary content (P4):** `BinaryContent` components map to multipart parts or a raw body on input and stream back as a
download on output. Responses always carry `X-Content-Type-Options: nosniff` and `Content-Disposition: attachment`
with an RFC 6266-encoded filename. Content is streamed, never fully buffered.

**Codecs:** JSON (P1); XML via `tools.jackson.dataformat:jackson-dataformat-xml` 3.x (P4), with
`application/problem+xml` errors. The protobuf REST codec is a 2.x add-on.

### 6.3 gRPC (`synapse-api-grpc`)
- Built on Boot's gRPC server module (`spring-boot-grpc-server`, Spring gRPC 1.1; never the starter, which brings a
  logging starter; standalone Netty or
  Servlet HTTP/2). Synapse error mapping plugs into Boot's gRPC exception handling; Boot's observation interceptor is
  reused. Message and metadata limits use Boot's `spring.grpc.server.inbound.message.max-size` and
  `spring.grpc.server.inbound.metadata.max-size`; Synapse defaults the metadata limit to 16 KB at lowest precedence.
- `GrpcBindings.service(BookServiceGrpc.getServiceDescriptor())` lists `.unary(BookServiceGrpc.getGetBookMethod(), getBook)`
  entries; `.bindAllByName()` binds every remaining method to the operation with the matching name (`GetBook` →
  `get-book`). Startup fails if a method is unbound, bound twice, or its types can't be mapped. Unary only in 2.0.
  A hand-written `*ImplBase` subclass remains the escape hatch, with the same interceptors.
- **Message mapping:** request and response messages map to records **by name at runtime using protobuf descriptors**
  (`snake_case` → `camelCase`), verified at startup. Built in: `Timestamp`↔`Instant`, `Duration`, wrapper types ↔
  nullable components, enums by name, repeated ↔ `List`, maps, nested messages. `oneof` fields and renamed fields use
  an explicit override in the binding (`.map(...)`).
- Errors: status per the table; the Synapse code travels as `google.rpc.ErrorInfo` (`reason` = code) in the status
  details, plus `google.rpc.BadRequest` for violations. Server reflection is off by default: Synapse defaults Boot's
  reflection switch to off at lowest precedence (key verified in plan 3), and an environment file turns it on.

### 6.4 GraphQL (`synapse-api-graphql`, P4)
- Spring for GraphQL, schema-first. `GraphQlBindings` with `.query("bookById", getBook)`, `.mutation(...)` and
  `.bindAllByName()`; arguments bind by name (input objects → nested records).
- Nested fields: `.field("Book", "author", getAuthors).batched(Book::authorId)` where `getAuthors` is a
  `QueryOperation<List<K>, Map<K, V>>`; it runs on Spring for GraphQL batch loading (one call per response).
- Errors carry `extensions.code` and `extensions.correlationId`. `@QueryMapping`/`@SchemaMapping` controllers remain
  the escape hatch.
- **Limits:** `synapse.api.graphql.max-depth` and `synapse.api.graphql.max-complexity` (proposed 15 and 1000,
  confirmed in the P4 plan), a cap on operations per request, the request size limit, and introspection off
  (`spring.graphql.schema.introspection.enabled=false` at lowest precedence).

### 6.5 AWS functions (`synapse-api-function-aws`, P4)
- In scope for 2.0, on the Spring Cloud release train compatible with the Boot version in use (2025.1.2 supports
  Boot 4.1 with Spring Cloud Function 5.0.x as of 2026-10-31).
- **Decided in the P4 plan** (see DESIGN.md *Open questions*): the binding model (proposal on record: HTTP-triggered
  functions reuse `RestBindings`; event-triggered functions use a `FunctionBindings` bean naming the exposed
  operations) and the Lambda operating model (SnapStart vs native, a functions profile, snapshot-safe generators,
  telemetry flushing).
- Fixed requirements: only operations named in bindings are exposed; a fixed function definition with Spring Cloud
  Function's routing function and header-based routing disabled (CVE-2022-22963 class); the principal comes only from
  the authorizer context; HTTP-triggered functions use the REST error mapping; event-triggered functions surface
  failures to the platform for retry or dead-lettering.

---

## 7. Clients (`synapse-client-*`, P2)

### 7.1 Model
- A `client-<name>` module declares a **typed, protocol-free interface** per downstream service and binds it:
  ```java
  public interface Inventory {
      Stock getStock(GetStock request);          // GetStock/Stock come from contract-inventory
      Reservation reserve(Reserve request);
  }

  @Bean
  RestClientBindings inventory() {
      return RestClientBindings.client("inventory", Inventory.class)
          .get("/stock/{sku}", Inventory::getStock)
          .post("/reservations", Inventory::reserve).idempotent()
          .build();
  }
  ```
  Callers inject `Inventory`. Moving the downstream from REST to gRPC changes only the binding
  (`GrpcClientBindings.client("inventory", Inventory.class).unary(InventoryGrpc.getGetStockMethod(), Inventory::getStock)`).
- Each bound method is backed internally by a `ClientOperation<I, O>` (per-call pipeline: observation, deadline,
  retries, error decoding, logging). Record components map to path, query, header and body by the REST input rules
  in reverse; gRPC uses the descriptor mapping. URLs are built only from URI templates (encoding guaranteed); path
  variable values `.` and `..` (including encoded forms) are rejected.
- REST clients are built on Spring's `RestClient` through Boot's builders, so Boot's observation and SSL bundles
  apply. gRPC clients use Boot's gRPC client module (`spring-boot-grpc-client`).

### 7.2 Configuration
`synapse.client.<name>.*` is the single place for a Synapse client (defaults inherited from `synapse.client.defaults.*`;
the name `defaults` is reserved). Keys: `base-url`, `connect-timeout`, `read-timeout`, `max-connections`, `ssl.bundle`,
`auth.type` and `auth.*`, `retry.*`, `logging.level`, `propagate`, `dynamic-target`, `allowed-hosts`, `json.*`,
`health.readiness`. Synapse provides safe defaults for the tuning keys (sized in the P2 plan). `base-url` has no
default (see *Keys without defaults* in 3.6): a client module sets it in its `config-<env>.yaml` files, and its
`config.yaml` holds the rest. Boot's per-name client keys (`spring.http.serviceclient.<name>.*`,
`spring.grpc.client.channel.<name>.*`) don't apply to Synapse clients; startup warns when one is set for a Synapse
client name.

### 7.3 Behaviour
- **Wire format:** the binding owns it (`.json(...)` naming and Jackson settings), applied through a client-specific
  mapper built from Boot's `JsonMapper` configuration; callers never see it.
- **Authentication:** a `ClientAuthenticator` SPI; each client selects one with `auth.type`. `synapse-client-auth-oauth2`
  provides client credentials (token caching and refresh via Spring Security's OAuth2 client). mTLS uses
  `ssl.bundle`. Teams implement the SPI for in-house schemes.
- **SSRF:** a client only calls the host of its `base-url`; requests resolving elsewhere are refused and redirects are
  not followed. Clients with `dynamic-target=true` must set `allowed-hosts`, and get Boot's `InetAddressFilter`
  blocking private, link-local and cloud-metadata ranges unless allow-listed.
- **Resilience:** only timeouts are on by default (capped by the request deadline). Retries are opt-in per client
  (`retry.*`, on Spring Framework 7's `RetryTemplate`/`RetryPolicy`) and apply only to idempotent calls (GET, HEAD,
  PUT, DELETE, gRPC queries, POSTs marked `.idempotent()`) on connection errors, 502, 503, 504 and 429 (honouring
  `Retry-After`), with exponential backoff and jitter, never past the deadline.
- **Idempotency keys:** `.idempotent()` sends one `Idempotency-Key` per logical call (an RFC 8941 string, quoted), or
  `.idempotent(Reserve::requestId)` takes it from an input component. The same key is reused on every retry.
- **Downstream errors:** mapped to a `SynapseException` (a `ClientCallException` carrying category, client and
  operation names, downstream code and a retryable flag; no protocol types). Default mirror table: 400/422 →
  `INVALID_INPUT`, 404 → `NOT_FOUND` (or an empty result when the method returns `Optional`), 409 → `CONFLICT`,
  412 → `PRECONDITION_FAILED`, 429 → `RATE_LIMITED`, 5xx and connection errors → `UNAVAILABLE`, timeout →
  `TIMEOUT`; gRPC and GraphQL statuses mirror through the inverse of the inbound table. **401/403 depend on whose
  credentials were sent** (the authenticator decides): with service credentials, a 401 triggers one token refresh and
  retry; if that fails, or for any 403, the caller gets `INTERNAL`, the client logs ERROR and increments
  `synapse.client.auth.failures{client}`. With no authenticator, mirror. Downstream messages, bodies and violations
  are never forwarded.
- **Error hooks, two levels then defaults:** (1) per-call handler in the binding
  (`.onError((failure, input) -> ...)`; `.onStatus(403, InventoryErrors.NOT_ACCESSIBLE)` and `.onErrorCode(...)` are
  shortcuts), (2) per-client `ClientErrorHandler` bean (qualified by the client interface), (3) the authenticator
  default, (4) the mirror table. Handlers receive a `ClientFailure` (status, headers, downstream code, size-capped raw
  and typed body, cause) and return `raise(exception)`, `recover(value)` or `useDefault()`. Transport steps
  (retries, the token refresh) run first.
- **Error bodies:** problem+json is parsed by default (including Synapse's `code`). `.errorBody(Type.class, Type::code)`
  parses any other shape, including the success type. `.failWhen(resp -> ..., Resp::errorCode)` turns a 2xx response
  into a failure that goes through the same handlers (unmapped: `INTERNAL`).
- **Logging:** failures by default (one WARN with client, operation, method, URL template, status, duration,
  correlation id). `logging.level=basic|headers|full` adds every call at INFO, then an allow-list of headers, then
  bodies at DEBUG (`@Masked` redacted, size-capped). `Authorization`, cookies and token-endpoint traffic are never
  logged.
- **Health:** a client contributes to readiness only with `health.readiness=true`; the signal is decided in the P2 plan.

---

## 8. Data (`synapse-data-*`)

### 8.1 Named stores
- **Every store is named and configured under `synapse.data.stores.<name>.*`** (url, credentials, pool, vendor
  settings, migrations, `primary`, `health.readiness`). A data module declares its store with
  `@SynapseDataStore("books")` on a configuration class; entity and repository packages derive from that class's
  package.
- A store's `url`, `username` and `password` have no default (see *Keys without defaults* in 3.6). A data module sets
  `url` in its `config-<env>.yaml` files; credentials come from environment variables
  (`SYNAPSE_DATA_STORES_<NAME>_PASSWORD`). Pool and vendor settings keep safe defaults in `config.yaml`.
- Synapse builds each store, including a single one, with Boot's builders: DataSource (Hikari), EntityManagerFactory
  or JDBC infrastructure, transaction manager, repositories, health contributor, metrics. Boot's single-DataSource
  auto-configuration backs off; `spring.datasource.*` is not used (startup warns if set).
- With one store it is the primary. With several, one may be `primary=true` for unqualified `@Transactional`; others
  are selected with `@Transactional("legacy")`. An unqualified `@Transactional` with several stores and no primary fails
  at startup. Cross-store transactions are not atomic (documented).
- **Migrations per store:** Flyway by default (Liquibase supported), run by Synapse per store from
  `db/migration/<store>` in `SmartLifecycle.start()`, followed by schema validation against the entities (Hibernate's
  schema validator), before any inbound plugin starts. When a migration tool is present, Synapse sets `ddl-auto=validate`, and startup fails if
  `ddl-auto` is `create`, `create-drop` or `update` unless the store allows it explicitly
  (`synapse.data.stores.<name>.allow-ddl-auto=true`, name confirmed in plan 4), which belongs in
  `application-local.yaml` or a test file. Teams that migrate in a separate
  job disable it per store.
- Startup logs the connection budget (stores × pool size) to help size databases.

### 8.2 Access model
- Developers write entities and plain Spring Data repositories; business and operation code inject repositories.
- Capability interfaces: `Identifiable<ID>`, `Versioned` (optimistic locking), `Auditable` (created/modified at/by).
  Optional `@MappedSuperclass` bases: `IdEntity<ID>`, `VersionedEntity<ID>`, `AuditedEntity<ID>` (and Mongo
  equivalents in P5). Plain entities still work without the features.
- **Ids:** bases default to app-generated UUIDv7 (RFC 9562, monotonic within a millisecond using the `Clock`; not a
  security capability); `Long` sequence variants exist. Bases implement `Persistable` (new when the version is null)
  and compare by id, proxy-safe.
- **Auditing:** fills created/modified timestamps and user (`AuditorAware` from the security context, else
  `synapse.data.auditing.system-user`, default `system`).
- **Error translation** (an `ExceptionTranslator`): optimistic-lock failures → `STALE_VERSION` (`CONFLICT`); unique-constraint failures → `CONFLICT` (with a code that gRPC maps to ALREADY_EXISTS), query timeout → `TIMEOUT`, connection failure →
  `UNAVAILABLE`, others → `INTERNAL`.
- JPA is the default in archetype and samples; JDBC is equally supported (same interfaces, auditing, stores,
  translation). Soft delete: documented use of Hibernate's `@SoftDelete`.

### 8.3 Redis (`synapse-data-redis`, P5)
Spring Data Redis as a store (named stores, repositories/templates) and as Boot's cache. Serialization is JSON only,
never JDK serialization, with typed serializers per cache/template or a `PolymorphicTypeValidator` that allows only
application packages. Key prefixes and default TTLs per store (`synapse.data.stores.<name>.cache.*`).

---

## 9. Messaging (Kafka, P3)

### 9.1 Shared
- `synapse-kafka-core` (internal to the other two): CloudEvents header codec, JSON serde (Boot's Jackson), correlation
  and trace headers, observation (Spring Kafka observation enabled for producers and listeners).
- Built on Boot's Kafka auto-configuration and `spring.kafka.*` for the connection. Message format is JSON.
- **Event metadata:** CloudEvents 1.0, binary content mode, written by Synapse (no CloudEvents SDK). The value is the
  plain payload; headers `ce_specversion=1.0`, `ce_id` (UUIDv7), `ce_source` (`synapse.kafka.source`, default
  `urn:service:<spring.application.name>`), `ce_type` (`synapse.kafka.type-prefix` + `.` + publisher name; the docs
  recommend a reverse-DNS prefix), `ce_time` (the event's own time if it provides one, else publish time),
  `content-type`, plus the correlation header and `traceparent`. Records without `ce_*` headers are still accepted.

### 9.2 Publisher (`synapse-publisher-kafka`)
- `KafkaPublisherBindings.topic("book-events", BookCreated.class).key(BookCreated::isbn).named("book-created")` produces a
  `PublisherOperation<BookCreated>` bean, injected by type. Event records come from the service's contract.
- `publish` blocks until acknowledged, bounded by the request deadline and `synapse.kafka.publishers.<name>.send-timeout`,
  and throws `UNAVAILABLE`/`TIMEOUT`; `publishAsync` completes exceptionally instead. Producer idempotence is always on.
- A `publish` inside an active transaction logs a one-time WARN (no outbox in 2.0).
- The binding may declare its wire format (`.json(...)`).

### 9.3 Subscriber (`synapse-subscriber-kafka`)
- `KafkaBindings` in the service module's `api.kafka` package: `topic("book-events").to(recordSale)` reads the record
  value into the bound operation's input type (the event record from the producer's contract; unknown fields ignored).
  Multi-type topics route by `ce_type`: `topic("book-events").on("book-created", recordSale).on("book-deleted", removeStock)`;
  unknown types are skipped and counted (`synapse.kafka.subscribers.unknown.types{subscriber,type}`), or sent to the
  DLT with `unknown-type=dlt`. Duplicate `.on` types fail startup. `.header(name, Input::component)` maps headers.
- **Subscriber name:** `.named("...")`, default the bound operation's name. Per-subscriber settings live under
  `synapse.kafka.subscribers.<name>.*`; the default group id is `<spring.application.name>.<subscriber name>`
  (startup fails if neither is available).
- **Delivery:** `delivery=at-least-once` (default: commit after success or DLT) or `at-most-once` (commit before
  processing; loss documented).
- **Failures by category:** straight to the DLT: deserialization failures, `INVALID_INPUT`, `NOT_FOUND`, `CONFLICT`,
  `PRECONDITION_FAILED`, `FORBIDDEN`, `UNAUTHENTICATED`. Retried with backoff, then DLT: `UNAVAILABLE`, `TIMEOUT`,
  `RATE_LIMITED`, `INTERNAL`. Overridable per subscriber (`retry.retryable`, `retry.non-retryable`).
- **Retries:** blocking by default, using Spring Kafka's `ContainerPausingBackOffHandler` (the container pauses during
  back-off and keeps polling, so retries never exceed `max.poll.interval.ms`; order is preserved). Proposed defaults:
  3 attempts, 1 s doubling to 10 s (confirmed in the P3 plan). `retry.mode=topics` opts into Spring Kafka's
  non-blocking retry topics.
- **Processing timeout:** each record's operation runs with a deadline of `synapse.kafka.subscribers.<name>.timeout`
  (default derived from `max.poll.interval.ms` / `max.poll.records` with headroom). Synapse lowers the default
  `max.poll.records` for subscribers (sized in the P3 plan) and exports a poll-interval headroom metric. Deadlines
  never travel on messages.
- **DLT:** records keep their original headers and add the code, category, attempt count and correlation id, using a
  Synapse `ExceptionHeadersCreator` (no stack trace, message or class name headers). Logged at ERROR without payload.
- **Topics:** never created unless `synapse.kafka.topics.auto-create=true` (local/test). At start
  (`SmartLifecycle`), every subscribed, DLT and retry topic is checked: `synapse.kafka.topics.verify=fail` (default)
  | `warn` | `off`.
- `concurrency` per subscriber (defaults to Boot's `spring.kafka.listener.concurrency`); Synapse backs off when the
  user defines their own `CommonErrorHandler` or listener container factory, and documents that `@KafkaListener`
  methods bypass Synapse bindings.
- **Header trust:** the payload type always comes from the binding (Jackson type headers are ignored); correlation ids
  from Kafka headers are validated like REST ones; topic write ACLs are the trust boundary (documented).
- **Control endpoint:** Actuator endpoint `kafkasubscribers`: read (subscribers, state, group id, lag) by default;
  pause/resume only with `management.endpoint.kafkasubscribers.access=unrestricted` and Spring Security present
  (startup fails otherwise); `synapse-security` adds a role check. It acts on one pod only (documented).
- **Logging:** retry attempts at WARN, DLT sends at ERROR; payloads opt-in
  (`synapse.kafka.subscribers.<name>.logging.payloads`, masked).

---

## 10. Scheduling (`synapse-scheduling`, P4)
- **Locks:** ShedLock-based locking for `@Scheduled` methods (lock table on a Synapse named store; version verified
  for Boot 4 in the P4 plan).
- **Per-run context:** each scheduled or runner invocation gets a `RequestContext`, correlation id and optional
  deadline (`synapse.scheduling.deadline`); core already enables Spring's `@Scheduled` observation.
- **CronJob runner:** a run-once mode for Kubernetes CronJobs (`synapse.scheduling.run-once=<job>`) that executes one
  job and exits, mapping the outcome to an exit code (0 on success, a documented non-zero code per error category).
- **Spring Batch conventions:** job repository on a Synapse named store; chunk steps call operations; job and step
  observation.
- Generated worker and scheduler apps set `spring.main.keep-alive=true` (virtual threads are daemon threads).

---

## 11. Security (`synapse-security`, P4)
- Optional starter, never forced. OAuth2 resource server (JWT) defaults; secures REST, gRPC and GraphQL alike;
  locks Actuator down.
- **Identity source:** `RequestContext` reads the principal only from Spring Security's `Authentication` (or the
  function's authorizer context). Default `synapse.security.identity.source=jwt`: the app validates the forwarded JWT
  itself. Opt-in `trusted-header`: reads the verified-claims header written by a sidecar (e.g. Envoy `jwt_authn`
  `forward_payload_header`), allowed only when every path to the app goes through the sidecar (documented).
- Authorization stays external (e.g. OPA via Envoy ext_authz). Synapse maps Spring Security's authentication and
  access-denied exceptions to `UNAUTHENTICATED`/`FORBIDDEN` whenever Spring Security is present.
- **Browser security headers** (OWASP REST Security Cheat Sheet): `X-Content-Type-Options: nosniff`,
  `Cache-Control: no-store`, `Content-Security-Policy: frame-ancestors 'none'`, `X-Frame-Options: DENY`, and HSTS
  where TLS terminates at the app. Teams without the starter set them at the gateway (documented).

---

## 12. Test support (`synapse-test`)
| Tool | Phase | Behaviour |
|---|---|---|
| `SynapseOperations.invoke(op, input)`, `RequestContexts.with(...).run(...)` | P1 | Runs the real pipeline around a plain instance without Spring; works for `BusinessOperation` too. |
| `@SynapseOperationTest` | P1 (client fakes P2, publisher fakes P3) | Boots operations, business beans and the pipeline only; replaces client interfaces and `PublisherOperation`s with fakes or mocks. |
| `assertThatSynapseException(...)` | P1 | `.hasCode(...)`, `.hasCategory(...)`, `.hasViolation(field)`. |
| @SynapseRestTest (synapse-test-rest) | P1 | Plugin plus the app's bindings and controllers; the test declares a @MockitoBean for each bound operation (the slice fails fast and names any missing one); RestTestClient bound to MockMvc (the same client works against a running server). |
| @SynapseGrpcTest (synapse-test-grpc) | P1 | Plugin plus the app's bindings with operations mocked; grpc-java's in-process server. |
| `@SynapseDataTest("<store>")` | P1 | Starts only that store's repositories, migrations and auditing, against shared Testcontainers with per-name `@ServiceConnection`. |
| `SynapseArchitecture.forApplication(…)` | P1 | The layering rules (section 4.3). |
| Mutable test `Clock`, JSON fixture loaders | P1 | |
| `@SynapseClientTest("<client>")` | P2 | `MockRestServiceServer` bound to the client's builder by default; `server = STUB` starts WireMock in-JVM and points the client's `base-url` at it (WireMock's Boot 4 fit verified in the P2 plan). gRPC clients test against an in-process server. |
| `@SynapseKafkaTest`, publisher capture, in-memory `PublisherOperation` fake | P3 | Kafka-only slice against a shared Testcontainers Kafka; helpers to send records with `ce_*` headers, await results, read DLTs. |
| `@SynapseGraphQlTest` | P4 | |
| Native/AOT assertions | P1 | Helpers for `RuntimeHintsPredicates` tests. |

The slices activate the `test` profile and turn nothing else on. The module's `src/test/resources/application-test.yaml`
must exist (see *Environments and module configuration*); test conveniences such as `synapse.errors.include-detail: true`
go there.

---

## 13. Consumer parent (`synapse-parent`)
- Extends `spring-boot-starter-parent`; imports `synapse-bom`; its `synapse.version` must equal its own version
  (enforced).
- **Logging:** the parent manages `synapse-logging-log4j2` and its setup; each `service-*` module declares it (the
  archetype adds it), so published library modules (contract, client, data, publisher, business) stay backend-free.
  Teams that don't add it keep Boot's Logback; Synapse masking works with either.
- **Tracing:** the archetype adds `spring-boot-starter-opentelemetry` to `service-*` modules.
- **Environments:** the archetype maps `spring.profiles.active: ${ENV}` in each service's `application.yaml`, sets
  `spring-boot.run.profiles=local`, and generates `application-local.yaml` (local conveniences turned on, such as
  `synapse.errors.include-detail: true`), `src/test/resources/application-test.yaml` in every module with Spring Boot
  tests, and `META-INF/synapse/config.yaml` in each library module (see *Environments and module configuration*).
- **Version pins:** because a parent's inherited dependency management (Boot's) wins over an imported BOM, the parent
  declares Synapse's runtime pins directly in its own `dependencyManagement` (kept identical to `synapse-bom` by the
  CI sync check, proven by an invoker test with an effective-POM assertion). BOM-only users import `synapse-bom` before
  `spring-boot-dependencies` (documented).
- **Images:** configures `spring-boot:build-image` with a Java 25 runtime and AOT-cache training
  (`BP_JVM_AOTCACHE_ENABLED=true`); Java 21 runtimes get CDS only. The archetype adds `spring.main.keep-alive=true`
  where needed, `spring.threads.virtual.enabled=true`, a sample Dockerfile and docs on heap sizing for small pods.
- Parent plugins that serve Synapse's own build are `<inherited>false</inherited>`. The release and snapshot profiles
  (`synapse-release`, `synapse-snapshot`) live in the root build, not in the parent, so consumers never inherit them.

---

## 14. Quality gates and CI
All must pass for a phase to be done; enforced in CI on every pull request unless noted.

1. **Build and tests:** PRs run all unit and integration tests on JDK 25, and unit tests plus one smoke integration
   test per module on JDK 21 (`-Djvm=<jdk21>/bin/java`). The full JDK 21 run is nightly. Testcontainers ITs for
   open-source stores and Kafka run on PRs; Oracle and SQL Server ITs run nightly, in the release workflow, and on PRs
   that change those modules. Vendor modules stay thin (dependencies, defaults, context-runner tests) so they meet
   coverage without their database.
2. **Coverage:** per-module JaCoCo floor (80% line, 70% branch, unit + integration merged; samples excluded); at
   least 90% of changed lines covered (diff-cover). A jar module with main sources but no execution data fails the
   build (closes JaCoCo's "skip when no data" gap), and diff-cover fails when reports are missing.
3. **Mutation testing:** PIT nightly on opted-in modules (`synapse-core` and each plugin's error mapping); score ≥ 70%
   (provisional). The nightly job opens an issue on failure.
4. **Static analysis and format:** Error Prone + NullAway (JSpecify mode); Spotless with the Synapse Eclipse profile,
   import order, LF and license header (applied locally, checked in CI); Javadoc built on the JDK 25 PR leg.
5. **Enforcer:** Java 25 for the build, Maven ≥ 3.9.6, `dependencyConvergence`, `banDuplicatePomDependencyVersions`,
   banned dependencies (`slf4j-ext`, `log4j:log4j`, JUnit 4, test libraries outside test scope except in
   `synapse-test` and the `synapse-test-<plugin>` slice artifacts, `spring-boot-starter-logging` in Synapse jars). Rules are tested by invoker projects in
   `tests/synapse-build-rules-tests`.
6. **Auto-configuration tests** for every auto-configuration (section 3.1); **AOT hint tests** for every module.
7. **Property metadata:** every `synapse.*` property has a description (enforced by the docs build) and the
   metadata-diff gate passes (from GA).
8. **Security:** dependency review (fails on High for new dependencies); OSV-Scanner (PRs fail only on vulnerabilities
   they introduce; a non-blocking full scan on `main`, nightly and on demand reports to code scanning; ignores need a
   reason and an `ignoreUntil` date: 3 months default, 1 year max); CodeQL; secret scanning; actionlint and zizmor
   (auditor persona) on workflow changes; OpenSSF Scorecard; Maven dependency graph submitted for dependency review;
   Maven wrapper pinned by checksum.
9. **Native:** the reference app and the functions sample are built and tested as native images nightly and in the
   release workflow.
10. **Samples and archetypes** build and pass their ITs; archetypes generate a building project (from P6).
11. **API stability** (from GA): japicmp, `@Incubating`, metadata diff, internal-import rule (section 3.5).

**Release and snapshots.**
- Conventional Commits; PR titles linted; squash merges.
- release-please (Maven strategy) maintains the release PR; milestones are set with `release-as`.
- **Builds run without secrets.** A release or snapshot build and its tests run in a job with no secrets and upload
  their artifacts; a separate job in a protected environment only signs (release), publishes and attests them.
- Releases: `release` environment limited to `main`, required reviewers with self-review prevented (upstream; the
  single-maintainer fork has no required reviewers), a tag ruleset
  (only maintainers create `v*`), and the workflow checks that the tag commit is on `main`. Central deployments are
  auto-published once validated. SBOM (CycloneDX) attached; build provenance attested.
- Snapshots: `2.0.0-SNAPSHOT` from `main`; `<version>-pr<N>-SNAPSHOT` for same-repository PRs carrying the
  `publish-snapshot` label. The PR build runs secret-free and uploads its artifacts; a separate `workflow_run`-triggered
  job, which runs from `main` in the `snapshots` environment (deployments limited to `main`), downloads and publishes
  those prebuilt artifacts without executing any PR code. Fork PRs attach jars to the CI run. The snapshot job skips
  non-SNAPSHOT versions.
- Dependabot: Maven, Actions, docs npm and the CI pip lock on `main`. No Dependabot on `develop`: its security updates
  only target the default branch (see DESIGN.md 4.1).
- Every GitHub Action pinned by SHA; least-privilege `permissions:`; repository setup in
  [.github/REPOSITORY_SETUP.md](../../.github/REPOSITORY_SETUP.md).

**Docs.** Antora site in `docs/`, versioned from git branches/tags, published to GitHub Pages; property tables
generated from configuration metadata; per-module guides; error-code and category tables; v1 → V2 migration guide.

---

## 15. Phases
Each phase starts with an approved plan, ends with all gates green, docs pages for its modules and a milestone
release, and is built on `feature/v2-p<n>-…` branches merged to `main`. P0 ships no milestone.

### P0: Repository and build
**Done:** v1 modules removed; root build parent, BOM, consumer parent, `synapse-core` skeleton; gates; workflows
(CI, nightly, CodeQL, dependency review, OSV-Scanner, workflow lint, release-please, release, snapshot, docs, PR
title); Antora skeleton with generated property tables; build-rule invoker tests; CONTRIBUTING, SECURITY,
REPOSITORY_SETUP.
Hardening: the JaCoCo/diff-cover no-data bypass, the enforcer exception for `synapse-test`, the snapshot job skipping
non-SNAPSHOT versions, Javadoc on the JDK 25 CI leg, pinned `maven-help-plugin` and diff-cover dependencies, the Mockito
agent argument only for modules that have Mockito, the pinned Antora UI bundle with the `prerelease` flag driven by the
version, and REPOSITORY_SETUP check names matching job names. Release and snapshot workflows split into secret-free
build jobs and environment publish jobs, with a tag-on-main check, documented rulesets and the `snapshots` environment
limited to `main`. CI matrix per gate 1 (full on JDK 25, smoke on JDK 21, full JDK 21 nightly), nightly failure
issues, Maven wrapper checksum, Maven dependency submission, OpenSSF Scorecard workflow
(Dependabot `develop` entry later removed by maintainer decision). Snapshot versions after a milestone
(`2.0.1-M1-SNAPSHOT`, `2.0.1-M1-pr<N>-SNAPSHOT`) accepted by the publish checks and the docs version; the snapshot
publish checks moved into a tested script. v1 side dropped by maintainer decision (2026-11-01): no work on 0.4.x.
Verified locally: `./mvnw verify` on JDK 25, the JDK 21 smoke run, Javadoc, the docs tool tests and Antora build,
actionlint, zizmor, and the Central publish and snapshot check script tests. Deferred review findings are tracked in
[plans/2026-11-01-p0-follow-ups.md](plans/2026-11-01-p0-follow-ups.md).

**Remaining scope:** what needs GitHub and waits for a push to the fork, which the maintainer controls:
- First, make `main` the default branch (fork, later upstream): snapshot publishing, scheduled and manually started
  workflows, Dependabot and release-please depend on it (see REPOSITORY_SETUP).
- Demonstrate the rejected PR (failing test, formatting error, NullAway error), then revert it.
- Run the release-please dry run and record the version it proposes for `main` afterwards.
- Snapshot publishing from `main` and from a labelled same-repo PR; nothing published for fork PRs.
- Docs CI: the Antora site building in CI with a generated property table.

**Acceptance:**
- `./mvnw verify` passes locally on JDK 25 and in CI (JDK 25 full, JDK 21 smoke).
- A PR with a failing test, a formatting error and a NullAway error is rejected (demonstrated on the fork, then
  reverted); a module without tests fails the coverage gate.
- release-please opens a release PR in a fork dry run, proposing `2.0.0-M1` and updating every `pom.xml` and
  `synapse-parent`'s `synapse.version`; the version it proposes for `main` afterwards is recorded.
- Snapshot publishing works from `main` and from a labelled same-repo PR without the signing key reaching any build
  job; nothing is published for fork PRs; it skips cleanly without secrets.
- The Antora site builds in CI with a generated property table.

### P1: Core, REST, gRPC, Postgres (2.0.0-M1)
**Scope:** `synapse-core` (sections 5.1–5.10), `synapse-test` (P1 rows), `synapse-logging-log4j2`, `synapse-api-rest`
(JSON; everything in 6.1–6.2 except XML and `BinaryContent`), `synapse-api-grpc` (6.3), `synapse-data-jpa`,
`synapse-data-jdbc`, `synapse-data-postgres` (8.1–8.2), `synapse-parent` conventions (section 13), and
`samples/reference-bookstore` (first cut): `CreateBookOperation`, `GetBookOperation`, `SearchBooksOperation` (paged), `UpdateBookOperation`, `DeleteBookOperation`
over REST and gRPC on Postgres with Flyway.

**Acceptance (each backed by a test):**
1. An app with `synapse-api-rest`, one operation bean and one `RestBindings` bean serves the endpoint with no other
   Synapse configuration.
2. Adding `synapse-api-grpc` and a `.proto` exposes the same operation over gRPC with no change to operation or model
   classes, with records mapped by descriptor (including `Timestamp`, wrappers, enums, repeated fields); a `oneof`
   needs an explicit `.map(...)`. The ArchUnit rules fail on a probe class in `operation..` importing
   `org.springframework.web`.
3. `SynapseException(BookErrors.BOOK_NOT_FOUND, id)` yields REST 404 problem+json with `code` and `correlationId`, no
   `detail` unless `include-detail=true`, `type` `about:blank` and the
   status-phrase title (a per-code `type` when `type-base-uri` is set); gRPC `NOT_FOUND` with `ErrorInfo.reason` = code. A per-operation
   override changes the REST status for one operation only, configured with a bracketed key.
4. Validation failures yield REST 400 with `violations[]` and gRPC `INVALID_ARGUMENT` with `BadRequest`.
5. An unexpected exception yields 500/`INTERNAL`; no stack trace or class name in any response.
6. Correlation id: accepted from the header/metadata, generated if absent, returned, in MDC, logs and spans; an
   invalid value (newline, bad characters, over 128) is replaced and never logged unsanitized.
7. `/v3/api-docs` (OpenAPI 3.1) includes every route with schemas, statuses and problem responses.
8. Data: a single store and two named stores (`books`, `legacy` on a second Postgres container) are each configured
   under `synapse.data.stores.<name>` and built by Synapse with their own DataSource, EntityManagerFactory,
   transaction manager, repositories, health contributor and Flyway migrations (`db/migration/<store>`);
   `@Transactional("legacy")` targets the right one; an unqualified `@Transactional` without a primary fails at
   startup; `spring.datasource.url` set logs a warning. Auditing fills timestamps and user for `Auditable` entities
   (base class or custom columns); `@Version` conflicts raise `STALE_VERSION` (`CONFLICT`); UUIDv7 ids are generated before insert and
   `Persistable.isNew` works; `ddl-auto=update` fails startup unless the store allows it.
9. `@Masked` fields are masked in payload log output and appear unmasked in responses.
10. Every auto-configuration passes the context-runner rules; every module passes its AOT hint tests.
11. Service model: a nested operation call produces a child span and reuses the correlation id; a `@Transactional`
    operation that throws rolls back; an expired gRPC deadline fails with `DEADLINE_EXCEEDED` before `execute`; a
    REST call without `Request-Timeout` gets the default deadline; a successful call logs nothing and a failing
    nested call logs exactly one line (outermost); a page size above the cap is `INVALID_INPUT`; an operation that is
    neither Query nor Command can't compile; binding a `CommandOperation` to GET fails at startup. Business: a
    `BusinessOperation` call produces a `synapse.business` span and validates input; a `@Transactional` business bean
    joins the operation's transaction; binding a `BusinessOperation` fails at startup; the ArchUnit rules catch an
    operation that calls a business bean that isn't a `BusinessOperation` (or a method other than `execute`) and a
    `business..` class importing `operation..`.
12. Input binding and response mapping: `UpdateBookRequest` receives its id from the path, `version` from `If-Match`, a
    `WriteOptions` component from the query string via `RestConventions`, and the rest from the body; a mismatched
    body id returns 400; `PageQuery` binds on GET and POST; an unknown sort key is 400; a cursor that was tampered with, or replayed for another operation or with another sort, is 400; `.created` yields 201 + `Location`; `.accepted` yields 202 + `Location`; an empty `Optional` yields the
    route's `.notFound` code; a header-mapped output component is absent from the body; `If-None-Match` yields 304 with `ETag`, also on HEAD; a stale `If-Match` yields 412 without changing the resource, including when a concurrent write wins after the operation's read; a version clash without `If-Match` yields 409; a weak `If-Match` yields 412 without calling the operation; `.requireIfMatch()` without `If-Match` yields 428; renaming a record
    component breaks compilation of the binding, not startup.
13. Context: an allow-listed header lands in `RequestContext`, survives `@Async` and virtual threads, appears in MDC;
    with `synapse.context.inbound.trusted=false` it is dropped; baggage over the limits is dropped and logged once.
14. Load and shutdown: with the concurrency limit reached, REST answers 503 with `Retry-After` without running the
    operation; SIGTERM under load completes in-flight requests and rejects new ones within the configured phases.
15. Readiness is UP with a store down unless that store opted in; `/health` is served through Boot's `additional-path`.
16. Protocol-level failures (unknown route, wrong method with `Allow`, unsupported media type, malformed JSON, body
    over the limit, gRPC unimplemented method) return the documented codes in the standard error shape.
17. The app starts with `-Dspring.context.exit=onRefresh` and no database reachable (no remote I/O in bean creation).
18. The reference app's ITs pass on JDK 21 and 25 against Testcontainers Postgres; the reference app builds and passes
    its ITs as a native image (nightly).
19. The reference app's tests use `SynapseOperations.invoke`, `@SynapseOperationTest`, `@SynapseRestTest`,
    `@SynapseGrpcTest`, `@SynapseDataTest` and `SynapseArchitecture.forApplication(…)`.
20. With a fixed test `Clock`, auditing timestamps, deadlines and UUIDv7 ids follow it; an operation invoked from
    `@Scheduled` gets a generated correlation id and no principal.
21. Environments: with `spring.profiles.active: ${ENV}` and `ENV` unset, startup fails with Synapse's explanation;
    `ENV=e2` without an `application-e2.*` file fails, and so does `ENV=E2` with only `application-e2.yaml` in a jar;
    two active profiles fail; an `on-profile` document fails. Module `config.yaml` and `config-<env>.yaml` files from
    two jars load below the service's files with the documented precedence, under environment variables and the command
    line; the same key in two modules fails. The startup report lists every source by name and origin, never a value.

**Done (plan 1, core):** `synapse-core`, `synapse-test` and `synapse-logging-log4j2` are built and documented. They
cover acceptance items 2 (ArchUnit probe), 6 (core part), 9, 10 (core modules), 11 (core part), 13 (core part), 19
(`SynapseOperations.invoke`, `@SynapseOperationTest`, `SynapseArchitecture.forApplication(…)`) and 20. REST, gRPC,
data and the reference app come in later plans.

**Done (plan 2a, REST edge):** `synapse-api-rest` and `synapse-test-rest` are built and documented (`rest.adoc`, and
the `@SynapseRestTest` section of `testing.adoc`). They cover acceptance items 1, 3 (REST), 4 (REST), 5 (REST),
6 (REST), 10 (REST modules), 11 (REST: the default deadline without `Request-Timeout`, a `CommandOperation` bound to
GET failing startup), 12 (path, body and `RestConventions` query binding, a mismatched body id, `.created`,
`.accepted`, `.notFound`, a header-mapped output component absent from the body, a renamed component breaking
compilation), 13 (REST), 14 (REST), 16 (REST), 17 (REST) and 19 (`@SynapseRestTest` is provided; the reference app
uses it in plan 5). Plan 2b covers the rest of 11 (page size over the cap via REST) and 12 (`PageQuery`, unknown sort key, tampered cursor, `version` from `If-Match`, 304, 412, 409); plan 2c covers 7 (OpenAPI).

**Done (plan 2b, REST paging and conditional requests):** paging (`PageQuery` binding, `.sortable` allow-lists, the
offset cap, the page object and `Link`, AES-256-GCM cursors through a replaceable `CursorCodec`) and conditional
requests (`.etag`, 304, `If-Match` binding, `STALE_VERSION`, `.requireIfMatch()`) are built in `synapse-api-rest`,
documented in `rest.adoc` (*Paging*, *Conditional requests*) and `core.adoc`, and shown in `samples/rest-books`. They
cover acceptance items 11 (REST: a page size over the cap is `INVALID_INPUT`), 12 (`PageQuery` on GET and POST, an
unknown sort key, tampered cursors and cursors replayed for another operation or with another sort, `version` from
`If-Match`, 304 also on HEAD, 412 including a race lost after the read, 409, a weak `If-Match`, 428) and 10 (REST: the
new beans' context-runner tests and AOT hint tests). Plan 2c covers 7 (OpenAPI), API versioning and CORS.

**Done (environments plan):** the environment as the one active profile and its startup checks (exactly one, a
service file for it, no `on-profile` documents, Synapse's explanation of an unset `${ENV}`), module
`META-INF/synapse/config*.yaml` and `.properties` files with their precedence and checks, the AOT check on client and
store names, and the startup report are built in `synapse-core`. The production concept is gone: `include-detail` is
off by default, the generated cursor key logs a WARN in every environment, and `SynapseEnvironment` and
`synapse.environment.non-production-profiles` are removed. Every test in this repository runs as `test`, and
`samples/rest-books` runs as `local`. Documented in `environments.adoc`, with `core.adoc`, `rest.adoc` and
`testing.adoc` updated. They cover acceptance items 21 and 3 (the environment part: no `detail` unless
`include-detail=true`). The module files and source names in a native image are not verified yet (DESIGN.md *Open
questions*; plan 5).

**Carried to later P1 plans (plan 1 settled the core side):**
- Plan 2a (REST edge), all done (see the *Done (plan 2a)* note above):
  - The edge opens the Synapse scope inside the server observation's span, with an IT asserting MDC and baggage on an
    `@Async` thread.
  - Reject an allow-listed attribute named `correlationId`.
  - Edges close their scopes on the opening thread; a scope closed out of order or on another thread logs a WARN.
  - `ContextBinder.open` needs a span in scope: the edge opens the Synapse scope inside the server observation, or
    there is no same-thread MDC and no async baggage.
  - Slices that mock operations (`@WebMvcTest`-style) rely on the test-double exemption (`@MockitoBean` replacements
    are not checked for the pipeline proxy).
  - Edges overwrite or strip inbound baggage for `correlationId` and for the Synapse attribute fields.
  - `inbound.trusted=false` and outbound stripping work at the header level under the OpenTelemetry starter.
  - Problem responses never carry a stack trace.
  - Verify the `INBOUND` shutdown phase against `WebServerGracefulShutdownLifecycle`.
- Plan 2b (REST paging and conditional requests): paging safety, conditional requests, and the `rest-books` sample extended with both. Done (see the *Done (plan 2b)* note above).
- Environments plan (environments and module configuration)
  ([plans/2026-11-05-p1-environments.md](plans/2026-11-05-p1-environments.md)): the single active profile as the
  environment and its startup checks, module `META-INF/synapse/config*.yaml` files, the startup report, no production
  concept (`include-detail` off by default, the cursor-key WARN in every environment, `SynapseEnvironment` removed),
  `application-test.yaml` wherever tests start a context, and `rest-books` running as `local`. It covers acceptance 21
  and the environment part of 3. Done (see the *Done (environments plan)* note above).
- Plan 2c (REST versioning, CORS, OpenAPI): API versioning, CORS (including the 405/OPTIONS fallback mapping, which has no CORS configuration today), and OpenAPI in `synapse-api-rest-openapi`.
- Plan 3 (gRPC edge):
  - The REST edge's Synapse fields include only REST attribute names; with both plugins, include
    `synapse.api.grpc.context.metadata` names so trusted-mode REST callers can't set gRPC attribute baggage
    unvalidated.
  - Server reflection off by default through a Synapse default on Boot's key at lowest precedence (6.3).
- Plan 5 (archetype, parent, reference app):
  - The OpenTelemetry Java agent recipe: `management.opentelemetry.map-environment-variables` and `-Dotel.*`
    guidance, an archetype profile that defines the `GlobalOpenTelemetry` `OpenTelemetry` bean, and an agent IT.
  - Later, a Synapse agent extension (`synapse-opentelemetry-agent-extension`). Whether the `OTEL_*` mapping is on by
    default and a JVM-wide baggage propagator in that extension are open questions (DESIGN.md *Open questions*).
  - `inbound.trusted=false` (trace restart, baggage dropped) and outbound baggage stripping under the OpenTelemetry
    Java agent, in the Synapse agent extension.
  - The archetype's environment files (section 13, *Environments*), and an environment for `process-aot` and the
    AOT-cache training run (3.3), verified with the first native and image builds.
  - The first native test checks, in a native image, that `ClassLoader.getResources` returns one URL per jar for
    `META-INF/synapse/config*.yaml`, that Boot's `Config resource` source names are unchanged, and that the service's
    `application-<env>.yaml` is found (DESIGN.md *Open questions*).
- Later phase, own branch: a rate-limited failure-log policy (a one-line WARN on every occurrence, the stack once per
  window, the translator WARN included, and a "dropped N" summary for the burst limit).

### P2: Clients (M2)
**Scope:** `synapse-client-core`, `synapse-client-rest`, `synapse-client-grpc`, `synapse-client-auth-oauth2`, client
rows of `synapse-test`; section 7. Sample `internal-grpc`: service A calls service B over gRPC using B's
`contract-b` (records, `.proto` and generated classes) through a typed client interface.

**Acceptance:**
- Callers inject the typed interface; switching its binding from REST to gRPC changes no caller code.
- Each downstream status mirrors to its category with no downstream detail in our response; a 5xx with an empty
  body raises an exception; an empty or HTML error body reaches handlers as raw text.
- 401/403: under OAuth2 client credentials a 401 triggers one refresh and retry, a second 401 yields 500, an ERROR log
  and `synapse.client.auth.failures`; a 403 yields 500 unless the binding maps it.
- Handlers: per-call `.onError` can raise, recover or fall through; the per-client handler applies to every method and
  the per-call one wins; business code can catch `ClientCallException`; `.failWhen` maps a 200 carrying an error code.
- Retries: off by default (a 503 isn't retried); when enabled a GET is retried, a plain POST isn't, and an
  `.idempotent()` POST is retried with the same quoted `Idempotency-Key`.
- SSRF: a redirect to another host is refused; a `dynamic-target` client is blocked from `169.254.169.254`; `..` in a
  path variable is rejected; query/path values with `&`, `/`, `#` and non-ASCII characters are encoded.
- Only allow-listed attributes and baggage entries reach the downstream; `Authorization` and cookies never do.
- Header redaction works; token-endpoint traffic is never logged.
- Synapse's own tests prove real timeouts, TLS, the SSRF host pin and redirects with WireMock; `@SynapseClientTest`
  works with the default mock server and with `server = STUB`.
- The OAuth2 add-on obtains, caches and refreshes a token against a mock authorization server.
- A `spring.http.serviceclient.<name>.*` key set for a Synapse client name logs a startup warning; a client named
  `defaults` fails startup.

### P3: Kafka (M3)
**Scope:** `synapse-kafka-core`, `synapse-publisher-kafka`, `synapse-subscriber-kafka`, Kafka rows of `synapse-test`;
section 9. Sample `event-driven-kafka`.

**Acceptance (Testcontainers Kafka):**
- at-least-once: a failing operation is retried with the pausing back-off (no rebalance during a back-off longer than
  `max.poll.interval.ms`), then lands on the DLT; the offset commits only after success or DLT.
- at-most-once: committed before processing (loss documented and tested).
- Poison pill: no infinite redelivery. Category rules: an `INVALID_INPUT` failure goes to the DLT on the first attempt;
  `UNAVAILABLE` is retried first. DLT records carry code, category and attempt count and no exception headers.
- `retry.mode=topics` uses retry topics; the default keeps per-key order.
- A record whose processing exceeds the subscriber timeout fails with `TIMEOUT`; no deadline header is ever written.
- Publisher: `publish` throws `UNAVAILABLE`/`TIMEOUT` on send failure and honours the deadline; `publishAsync`
  completes exceptionally; business code using `PublisherOperation` compiles without Kafka.
- Records carry the CloudEvents headers (absolute `ce_source`, prefixed `ce_type`), the correlation header and
  `traceparent`, with a plain JSON value; a record without `ce_*` headers is consumed by a single-type binding; a
  multi-type topic routes by `ce_type`, unknown types skipped and counted (or sent to the DLT).
- Startup fails when a subscribed topic or its DLT is missing (`verify=fail`); `auto-create=true` creates them.
- Default group id `<app>.<subscriber>`; the `kafkasubscribers` endpoint is read-only by default and pause/resume
  need explicit access and Spring Security.
- A DLT send logs one ERROR without payload; spans link producer and consumer.
- `@SynapseKafkaTest` and the publisher capture are used by the module's tests and the sample.

### P4: GraphQL, functions, security, scheduling, REST codecs (M4)
**Scope:** `synapse-api-graphql` (6.4), `synapse-api-function-aws` (6.5; the plan decides the binding and operating
model), `synapse-security` (section 11), `synapse-scheduling` (section 10), REST XML codec and `BinaryContent`
(6.2), `@SynapseGraphQlTest`. Samples `public-rest-api` (JWT, problem+json, OpenAPI), `graphql-bff`,
`serverless-functions` (AWS), `scheduled-and-batch`.

**Acceptance:**
- A shared operation contract test suite runs the P1 `BookErrors` operations through GraphQL and the function adapter.
- GraphQL: depth/complexity limits reject oversized queries; introspection is off by default; batched fields make one
  operation call per response; a `CommandOperation` under `Query` fails at startup.
- Functions: only bound operations are exposed; routing by header/expression is impossible (negative test); local
  invoker tests pass; the functions sample builds and passes as a native image (nightly).
- Security: 401 without a token (with `WWW-Authenticate`), 403 on scope mismatch, Actuator locked down, browser
  headers on success and error responses, `trusted-header` mode reads a sidecar header and rejects requests without it.
- Scheduling: only one instance runs a locked job; a run-once job exits with the documented code; a Spring Batch job
  stores its repository on a named store and calls an operation per chunk.
- Content negotiation for JSON and XML, including `application/problem+xml`; `BinaryContent` upload/download streams
  without buffering and sends `nosniff` and `attachment`.

### P5: Datastores (M5)
**Scope:** `synapse-data-mysql` (MySQL and MariaDB), `-oracle`, `-sqlserver` (JPA/JDBC), `-mongodb`, `-redis`
(section 8.3); Testcontainers definitions for each.

**Acceptance:** for each module, context tests prove a single named store and a second store of the same technology
are built with Boot's builders; a CRUD + auditing + optimistic-locking IT runs (open-source stores on PRs; Oracle and
SQL Server nightly); Redis store and cache serializers are JSON with restricted typing, never JDK serialization;
`@SynapseDataTest("<store>")` starts only that store.

### P6: GA hardening (2.0.0)
**Scope:** `synapse-utilities`; archetypes generated from the reference app; docs complete (every module, property
tables, error tables, compatibility matrix); v1 → V2 migration guide covering every module; API-stability gates
active (japicmp baseline, metadata diff); security review with a threat model per plugin; reference sample complete
across all plugins; `SECURITY.md` and the v1 README announce the 0.4.x end-of-life date (GA + 6 months); release
2.0.0.

**Acceptance:** all gates green including nightly; every module documented; every sample and archetype built in CI;
the migration guide covers every row of the module map in DESIGN.md.

---

## 16. Decided in phase plans
These are settled at the start of the named phase (details in DESIGN.md *Open questions*): default sizes for
the gRPC and GraphQL concurrency limits and deadlines (their plans) and client settings (P2); the client readiness
signal (P2); WireMock's Boot 4 fit (P2); Kafka retry defaults, subscriber `max.poll.records` and timeout defaults (P3);
the AWS function binding and Lambda operating model, GraphQL limit values, ShedLock's Boot 4 fit (P4); the archetype
set (P6).
