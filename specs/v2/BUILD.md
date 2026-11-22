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
| `synapse-logging-log4j2` | P1 | Opt-in Log4j2 backend for consumers (synchronous by default) with an optional failure-log burst limit. Logback, Boot's default, is the default backend; the ECS default is in `synapse-core`. |
| `synapse-tracing-opentelemetry` | P1 (observability follow-up) | The tracing a service module declares: Micrometer Tracing's OpenTelemetry bridge and the OTLP span exporter, without an OTLP metrics registry, plus the OpenTelemetry Java agent recipe's auto-configuration. No properties. Never a dependency of a library module. |
| `synapse-api-rest` | P1 (XML codec and `BinaryContent` in P4) | REST plugin: `RestBindings`, problem+json, paging, conditional requests, versioning, CORS, limits. |
| `synapse-api-rest-openapi` | P1 (plan 2c) | OpenAPI 3.1 for `RestBindings` routes, served by springdoc (3.x) at /v3/api-docs: a Synapse customizer built from the resolved routes, with Synapse's own schema generator on Jackson 3. Depends on synapse-api-rest. |
| `synapse-api-grpc` | P1 | gRPC plugin on Boot's gRPC server support. |
| `synapse-test-grpc` | P1 (plan 3) | @SynapseGrpcTest: the gRPC slice, on Boot's in-process test transport. Depends on synapse-test and synapse-api-grpc. |
| `synapse-data-core` | P1 (plan 4) | Shared data foundation under `synapse-data-jpa` and `-jdbc`, never added directly: named stores (DataSource, JDBC templates, migrations, health), capability interfaces, ids, auditing, error translation, the vendor SPI. |
| `synapse-data-jpa`, `synapse-data-jdbc` | P1 | Store access on `synapse-data-core`: JPA (EntityManagerFactory, repositories, entity bases) or Spring Data JDBC. |
| `synapse-data-postgres` | P1 | PostgreSQL vendor module. |
| `synapse-test-data` | P1 (plan 4) | `@SynapseDataTest`, per-store test connections (`@StoreConnection`) and a shared Testcontainers PostgreSQL. Depends on synapse-test and synapse-data-core. |
| `synapse-client-core` | P2 | Client wiring shared by protocols: authenticator SPI, error decoding, logging, retries, propagation. |
| `synapse-client-rest`, `synapse-client-grpc` | P2 | REST and gRPC client plugins. |
| `synapse-client-auth-oauth2` | P2 | OAuth2 client-credentials authenticator. |
| `synapse-kafka-core` | P3 | Shared Kafka wiring: CloudEvents headers, serde, observation. Never added directly by consumers. |
| `synapse-publisher-kafka`, `synapse-subscriber-kafka` | P3 | Kafka publisher (client role) and subscriber (inbound plugin role). |
| `synapse-api-graphql` | P4 | GraphQL plugin on Spring for GraphQL. |
| `synapse-api-function-aws` | P4 | AWS Lambda adapter. |
| `synapse-scheduling` | P4 | Scheduled and batch jobs: locks, per-run context, CronJob runner, Spring Batch conventions. |
| `synapse-data-mysql`, `-oracle`, `-sqlserver`, `-mongodb`, `-redis` | P5 | Vendor/technology modules. |
| `synapse-utilities` | P6 | Validators (`@OneOf`, `@AnyOf`, `@RequireIfPresent`, date-format check). |
| Archetypes | P1 (plan 5; set revisited in P6) | `synapse-archetype-quickstart` (one service module, shaped like `samples/hello-rest`) and `synapse-archetype-service` (contract, service and data modules, shaped like `samples/reference-bookstore`). Only the quickstart is published until P6 (decided 2026-11-20, maintainer): the service archetype sets `maven.deploy.skip=true`, so neither the release nor the snapshot publish carries it. It is still built, and its IT runs in every build. It is frozen: no new features (no client or Kafka modules), only fixes that keep it building. |

Not published: `tests/*` (`synapse-build-rules-tests`, the build-rule tests; the cross-module tests, including
`synapse-api-rules-tests`, which checks every auto-configuration's back-off and the `@Incubating` marks) and
`samples/*`.

Deferred to 2.x add-ons (do not build in 2.0): reactive stack (WebFlux plugin, reactive client, R2DBC, reactive
NoSQL); `synapse-api-function-gcp`; `synapse-client-graphql`; `synapse-client-auth-token-relay`;
`synapse-client-resilience4j` (circuit breaker, bulkhead); DB2, Cassandra and Couchbase modules; read replicas;
multi-tenancy; Kafka exactly-once, named clusters, dedupe store, batch listeners, DLT replay, transactional outbox,
Avro/Protobuf registry add-ons; protobuf REST codec; `synapse-security`, the optional security starter (OAuth2
resource server, identity source, browser security headers; section 11), deferred whole by the maintainer on
2026-11-20. See DESIGN.md *Roadmap*.

---

## 3. Rules for every module

### 3.1 Auto-configuration
- Every feature is a Spring Boot auto-configuration: `@AutoConfiguration`, registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, conditional on its key classes
  and properties, with every bean `@ConditionalOnMissingBean` (or contributed through a customizer), so it can be
  replaced where the docs say so. A public extension point backs off by its public type, or by a documented
  `synapse…` name where each plugin has its own bean of one type (the call limiters); an internal bean backs off by
  its name only (see *API stability*).
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
  plugin starts (see *Named stores*). Verified for Hibernate 7.4 in plan 4: with
  `hibernate.boot.allow_jdbc_metadata_access=false` and an explicit dialect, Hibernate takes the version from
  `jakarta.persistence.database-product-version` and opens no connection. Every schema action (validation, and
  `create` or `update` where a store allows them) also runs in `start()`, never while the EntityManagerFactory is built.
- **AOT-ready:** every module ships `RuntimeHints` for what it creates or reflects on (pipeline proxies, record
  components referenced by bindings, method-reference lambdas, beans built from bindings), asserted with
  `RuntimeHintsPredicates`. No runtime classpath scanning. Bindings are resolved during Boot's AOT processing where
  possible so startup checks still run.
- **Native image:** officially supported and tested (nightly) for core, REST, gRPC and AWS functions; the nightly
  native test starts in P4, with the reference app and the functions sample. Best effort for
  other modules until each gets its own native test. Properties that decide which beans exist are fixed at AOT build
  time; the property reference marks them *build-time*.
- **Environments under AOT:** the environment is never used in a `@Conditional`, and environment files change values
  only. The set of clients and stores (the `<name>` keys under `synapse.client` and `synapse.data.stores`) belongs in
  files that don't depend on the environment (`config.yaml`, `application.yaml`). In AOT mode, startup fails when an
  environment file adds such a name. Synapse ships resource hints for `META-INF/synapse/config*.yaml` and
  `config*.properties`. AOT processing (`process-aot`) and the AOT-cache training run of `build-image` prepare the
  environment too, so they need an environment as well; the parent passes one (plan 5): the build
  environment, `build` by default (see *Consumer parent*), as the default profile for `process-aot`, so it is never
  active at run time.
- **Spring AOT processing is opt-in in P1** (decided 2026-11-08, maintainer): the parent runs `process-aot` only with
  its `aot` profile (`-Paot`), and a build without it generates no AOT code. The rules above hold for every module
  either way and stay tested in the framework modules. `-Paot` is experimental until P4: it may change without notice.
  In P4, with the native image, AOT becomes full (DESIGN.md,
  *Spring AOT opt-in in P1*).

### 3.4 Dependency management
- Versions live in the root `pom.xml`. Overriding a Boot-managed version is allowed only as a CVE pin with the
  advisory ID and a removal condition.
- `dependencyConvergence` is enforced. Every pin that affects consumers' runtime classpath goes into `synapse-bom`
  (the root build imports the same list), with a comment and a removal condition. `synapse-parent` repeats them
  directly (see *Consumer parent*). A CI check keeps the root POM, the BOM and the parent in sync.
  Example: the guava CVE pin (GHSA-xxph-c9ww-hj94 / CVE-2026-102554) is in the root POM and the BOM; an invoker test
  keeps the two versions equal.
- Test libraries stay in test scope, except in `synapse-test` and the `synapse-test-<plugin>` slice artifacts, which exist to provide them (its POM overrides the
  ban; an invoker test proves the ban still holds elsewhere).

### 3.5 API stability
- Public API is everything outside `..internal..` packages. `japicmp` against the last release fails on
  binary-incompatible changes in minor and patch releases (from 2.0.0 GA).
- `@Incubating` (in `synapse-core`, `RUNTIME` retention so tooling can read it) marks APIs that may still change in a
  minor; japicmp ignores them. The rule (decided 2026-11-20, maintainer): a public type or member whose design is
  provisional (a DESIGN.md row marked provisional, or an open question about it) carries `@Incubating`, and so does
  every new SPI until a minor release has shipped it. Settling the row removes the mark. The P6 API pass reviews every
  `@Incubating` before GA. The docs call such an API "incubating".
- A gate diffs each module's `spring-configuration-metadata.json` against the last release: a removed or renamed
  property must be kept as `@DeprecatedConfigurationProperty` with its replacement.
- Deprecations happen in a minor; removal only in the next major.
- `SynapseArchitecture.forApplication(…)` includes a rule that consumer code never imports
  `io.americanexpress.synapse..internal..`.
- A bean the docs call replaceable has a public type outside `..internal..`, or the docs don't call it replaceable.
  Synapse's internal beans back off by bean name, and their names start with `synapse`. Every type a
  `@ConditionalOnMissingBean` names, or the return type of a bare one, is public; a build test over every
  auto-configuration enforces it (the API-surface follow-up plan, 2026-11-20, converts the existing beans before M1).
- The public extension points beyond Spring's own (decided 2026-11-20, maintainer), all `@Incubating`:
  - **Status mapping**, one per protocol, on the model of Boot's `HttpCodeStatusMapper`: properties for the simple
    case (`synapse.api.<protocol>.errors.status.[CODE]`), a bean for full control. `RestErrorStatusMapper`
    (`io.americanexpress.synapse.api.rest.error`) returns an HTTP status and `GrpcErrorStatusMapper`
    (`io.americanexpress.synapse.api.grpc.error`) a `Status.Code`. Both take the `SynapseException` and the
    operation name, because each protocol's default reads more than the code (REST: a Spring exception's kept
    status; gRPC: the stale-etag cause). A mapper returns only a status: it never changes
    the code, the message or the body, so REST's problem `code` still equals gRPC's `ErrorInfo.reason`. Synapse's
    default implements it and backs off by type.
  - **Inbound call limits:** `InboundCallLimiter` (`io.americanexpress.synapse.core.limit`), protocol-neutral, on the
    model of Netflix concurrency-limits' `Limiter`: `tryAcquire()` returns a permit or none, and the permit is
    released once with an outcome (`SUCCESS`, `IGNORED`, `DROPPED`). Synapse's default is a semaphore sized by the
    plugin's limit property. Each plugin has its own limiter bean, `synapseRestCallLimiter` and
    `synapseGrpcCallLimiter`, which back off by name, so one plugin's replacement never becomes the other's.
  - **No Synapse handler SPI:** Spring's `HandlerExceptionResolver`, `@ControllerAdvice` and `ErrorController` (REST)
    and Spring gRPC's `GrpcExceptionHandler` and `@GrpcAdvice` are the extension points; Synapse's own handlers back
    off by name. **No deadline-policy SPI:** the deadline properties are the tuning surface (REST resolves the
    deadline before routing, so a per-operation policy would be a design change).
  - Compilers, inventories, factories, writers and policies stay internal.

### 3.6 Environments and module configuration
- **The environment is the one active Spring profile.** Each service's `application.yaml` maps the platform's variable:
  `spring.profiles.active: ${ENV}`. Synapse code never reads `ENV`. `SPRING_PROFILES_ACTIVE`, `--spring.profiles.active`
  and `@ActiveProfiles` work as usual, and the highest source wins. The environment is the value of
  `spring.profiles.active` plus any profile added with `SpringApplication.setAdditionalProfiles`. Feature profiles go in
  the environment's profile group, `spring.profiles.group.<env>` (including the build environment's,
  `spring.profiles.group.build`), not in `spring.profiles.include` (decided 2026-11-08: an included profile is active,
  and during AOT processing Boot then ignores the default profile). Groups and includes don't count, and profiles that
  libraries add at run time (`kubernetes`, `cloud`) are allowed. **One exception, AOT processing** (decided 2026-11-08):
  while Spring's AOT processor runs (the system property `spring.aot.processing` is set, outside a run of AOT-generated
  artifacts), a single `spring.profiles.default` is the environment when `spring.profiles.active` is empty, and no
  profile may be active. That is how the parent passes the build environment to `process-aot` (with `-Paot`; opt-in in
  P1, 3.3), because Spring's AOT code generation activates at run time every profile that was active during processing,
  and no default profile.
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
    error), a blank value, or only `spring.profiles.default` (except during AOT processing, above);
  - the value of `spring.profiles.active` in the source that sets it falls back to a default, such as `${ENV:local}`
    (even when `ENV` is set);
  - there is more than one;
  - in AOT mode, the AOT-generated code activates a profile (the generated `<Main>__EnvironmentPostProcessor`, which
    Boot writes only when a profile was active during processing); a profile that a library adds at run time is not
    checked;
  - during AOT processing with the environment from `spring.profiles.default`, any profile is active (this fails the
    build);
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
  convenience on (for example `synapse.errors.include-detail: true` or `springdoc.api-docs.enabled: true` in
  `application-local.yaml`). Synapse never branches on the environment's name.
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
| `RequestContext` | Read-only view: correlation id, principal (verified identity, if any; empty at the REST and gRPC edges in 2.0, because the security module that sets it there is deferred to 2.x, 6.1; AWS functions take it from the authorizer context, 6.5), locale, deadline (optional), string attributes. `RequestContext.current()`. Never contains servlet, gRPC or GraphQL types. |
| `PageQuery`, `PageResult<T>` | `PageQuery`: cursor **or** offset, size, sort. `PageResult<T>`: items, next cursor, optional total. One-call conversion from Spring Data `Page`/`Slice`. |
| `VersionCondition<V>` | The version a conditional write depends on, protocol-neutral and sealed: `AnyVersion` (any current version; REST `If-Match: *`) or `Version(v)` (exactly `v`; one entity tag). A `null` component means the request named no version. `matches(current)` compares. On a write route, every input component bound from `If-Match` or `If-None-Match` is a `VersionCondition<V>` (startup check), so `*` and an absent header never look alike. Its nullability is the operation's contract on every protocol: `@Nullable` makes the condition optional, and a non-null component makes it required (a request without it is `PRECONDITION_REQUIRED` before the operation runs). |
| `BinaryContent` | Protocol-neutral file/stream content: stream supplier, content type, filename, size (see *REST*). |
| `ErrorDetails` | `String code(); String messageTemplate(); ErrorCategory category();` implemented by team enums. |
| `ErrorCategory` | `INVALID_INPUT, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, PRECONDITION_FAILED, RATE_LIMITED, UNAVAILABLE, TIMEOUT, INTERNAL`. Protocol-neutral, domain-only. |
| `SynapseException` | `SynapseException(ErrorDetails, Object... args)` and `(ErrorDetails, Throwable, Object... args)`. Message formatted from the template with `MessageFormat`; a `MessageSource` entry keyed by `code()` overrides it. |
| `SynapseErrors` | The framework's own `ErrorDetails` enum: `MALFORMED_REQUEST`, `VALIDATION_FAILED` (`INVALID_INPUT`), `NOT_FOUND`, `UNAUTHENTICATED`, `FORBIDDEN`, `CONFLICT`, `STALE_VERSION` (`CONFLICT`: a version check or optimistic lock failed), `ALREADY_EXISTS` (`CONFLICT`: a resource with the same identity exists, such as a unique-constraint violation; gRPC answers it ALREADY_EXISTS), `PRECONDITION_FAILED`, `PRECONDITION_REQUIRED` (`PRECONDITION_FAILED`: the input requires a precondition the request lacks; REST 428), `RATE_LIMITED`, `UNAVAILABLE`, `DEADLINE_EXCEEDED` (`TIMEOUT`), `INTERNAL`, each with its matching category unless stated. |
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
   with violations. Then, for an input record, each required `VersionCondition` component (not `@Nullable`) that is
   `null` fails the call with `PRECONDITION_REQUIRED`, one violation per component, so no caller reaches an operation
   without a required condition (plan 2026-11-13, D6). Only the outermost call's input is the caller's own: in a
   nested call either failure is `INTERNAL`, with it as the logged cause (plan 2026-11-13, Q11). Each rejection
   records the call that raised it, so the rule holds on every thread: a call that receives a pipeline rejection
   raised by another call (an operation run on another thread, its failure unwrapped from a `Future` and rethrown)
   turns it into `INTERNAL`, with the rejection as the logged cause. A `SynapseException` that code builds itself is
   not a pipeline rejection and passes as it is (maintainer, 2026-11-19; plan 2026-11-20 contract, decision 3).
   A violation's field is the path of the rejected value: property names joined with dots, and `[index]` for a list
   or array element, a scalar element included (`tags[0]`, `items[2].sku`). Jakarta's container element node names
   (`<list element>` and the like) are left out, a `Set` element has no index, and a map key is never written: a
   constraint on a key or a whole value names the map (`labels`), and one inside a value continues with the value's
   property (`authorsByRank.countryCode`). Each API plugin renames the path to its wire names (6.1).
5. **Execute.** A Spring `@Transactional` boundary, if present, sits inside the Synapse advisor (Synapse never opens
   transactions itself).
6. **Translation:** `SynapseException` passes through; other exceptions go through the `ExceptionTranslator`s;
   anything left becomes `INTERNAL` (cause logged, never exposed). A `ConstraintViolationException` reaches the
   translators only when it is the thrown exception and every violation's root bean is the outermost call's input
   (by identity); any other one, thrown or in the cause chain (a collaborator's method validation, an entity at
   flush or commit), is `INTERNAL` without violations, and its logged cause names each violation by path and message
   template, not by its interpolated message (Q11), for an exception the pipeline finds: the failure or one of its
   first 9 causes, not a suppressed one (the cases this misses are in `core.adoc`, *Masking*).
7. **Logging:** failures only, by the outermost invocation only (see *Logging*). Successful calls log nothing.

`BusinessOperation` beans get a reduced pipeline: validation on `I` and a child observation `synapse.business`
(configurable, see *Observability*); no context, deadline, translation or logging steps. A rejected business input
is `INTERNAL`, with the `INVALID_INPUT` failure as its cause: the service built it, so it is never the caller's
fault (Q11). `@Transactional` works on business `execute`; the operation is the preferred transaction boundary
and a business bean joins it by default.

An operation may call other operations (same pipeline, same `RequestContext`).

### 5.3 Context propagation
- **Tracing is required.** `synapse-core` depends on Boot's Micrometer Tracing module; service modules add
  `synapse-tracing-opentelemetry` (added by the archetypes): Boot's Micrometer Tracing OpenTelemetry bridge and the
  OTLP span exporter, without Micrometer's OTLP metrics registry. Boot exports no span until an endpoint is set
  (`management.opentelemetry.tracing.export.otlp.endpoint` or `OTEL_EXPORTER_OTLP_ENDPOINT`), and metrics export
  stays the team's choice. Startup fails with a clear message, which names that module, when no tracer is present or
  tracing/baggage is disabled while Synapse context is in use. The message names the
  cause: when baggage was turned off by `OTEL_PROPAGATORS` without `baggage` (Boot maps `OTEL_*` variables to
  properties), it names that variable and the fix. With the OpenTelemetry Java agent, the app keeps the module, whose
  auto-configuration makes the agent's SDK (`GlobalOpenTelemetry.get()`) the `OpenTelemetry` bean when the agent's
  class is present and the service defines none (it backs off by the `OpenTelemetry` type, as Boot's SDK does), and fails an AOT-processed start under the agent. `OTEL_SDK_DISABLED=true` doesn't break
  correlation (probed 2026-11-20), so the check accepts it. `synapse.observability.require-tracing=false`
  (default `true`) turns the check off (one INFO line if tracing cannot carry the context), and a bean named
  `synapseTracingRequirementCheck` replaces it.
- Boot's Micrometer Tracing owns the MDC (its baggage correlation fields); `synapse-core` never writes the MDC itself.
  Correlation doesn't depend on sampling: on an unsampled request the MDC, the baggage and outbound propagation carry
  the correlation id as on a sampled one (pinned by a test at `management.tracing.sampling.probability=0.0`).
  Synapse sets no sampling default (Boot's is 0.1).
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
  `synapse.api.rest.deadline.max`. Defaults: `default` 10 s, `max` 30 s. Malformed values are ignored. gRPC uses
  `synapse.api.grpc.deadline.default` and `.max` with the same defaults; the cap applies to the `RequestContext`
  deadline only, since a server can't change the caller's gRPC deadline.
- Scheduled and runner invocations have no deadline unless `synapse.scheduling` sets one. Kafka records use the
  subscriber's processing timeout (see *Subscriber*).
- Clients cap their timeouts to the remaining budget and forward it.

### 5.6 JSON and masking
- Synapse uses Boot's `JsonMapper` and customizes it only through Jackson 3 builder customizers. Naming and other
  Jackson settings use Boot's own properties (e.g. `spring.jackson.property-naming-strategy`). Client and publisher
  bindings may declare their own wire format (see *Clients*, *Publisher*).
- `@Masked` (maskers: all characters, fixed length `***`, last four) redacts fields in the payload lines Synapse
  logs (payload logging masks before it logs, so it works with any backend). Applications can use the same masking
  for their own log lines. A type Jackson writes with its own serializer (`@JsonValue`, a class-level
  `@JsonSerialize(using = …)`, a module serializer) and that declares a `@Masked` member is written whole as `"***"`.
  It never applies to API responses, outbound client requests, published events or persisted data. (v1's
  national-identifier, telephone and postal-code serializers formatted values and are not maskers; they are not
  carried over.)
- **What Synapse guarantees beyond the payload line:** Synapse writes no value from a caller's input or from a
  `@Masked` member into a log line or span it produces itself.
  - Constraint violations are logged by the same path the caller gets (a map key is never written), and by message
    template only when the template is a single message key; otherwise by the constraint's name.
  - A `ConstraintViolationException` anywhere in a logged or recorded failure (the whole cause chain and the
    suppressed exceptions, with a cycle guard) is replaced by that sanitized form.
  - An `SQLException` in a logged or recorded failure's cause chain shows only its SQLState, vendor code and class,
    never its message, and the wrappers above it lose their messages too (they repeat the database's text). The
    database's text appears only with `synapse.errors.log-database-messages=true` (default `false`), a log setting
    separate from `synapse.errors.include-detail`, which only decides what callers get. The stand-in keeps the
    driver's stack trace. Hibernate logs the database's text itself (`org.hibernate.orm.jdbc.error`); the docs give
    the `logging.level` line that silences it.
  - Both rules apply to the operation's failure line, a `SynapseException`'s logged cause, the REST and gRPC edges'
    unhandled-error lines and both span kinds. A span records no more than the failure line logs.
  - DEBUG lines about unreadable input name the exception class, never its message.
  - Not covered, and documented: a value logged through its `toString()`, a `SynapseException` argument (it is part
    of the message), and a library that logs the exception itself.

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
- **Backend:** Logback, Boot's default, through the starters a service declares (`spring-boot-starter-actuator`,
  `spring-boot-starter-test`). Synapse ships nothing Logback-specific: no module, no configuration file and no burst
  limit. `synapse-core` defaults `logging.structured.format.console=ecs` at lowest precedence, for either backend.
  The docs warn against Logback's `DuplicateMessageFilter` on the failure logger: every failure line has the same raw
  message, so it would drop all but the first few.
- `synapse-logging-log4j2` (opt-in): Log4j2 2.x, **synchronous** by default, and an optional burst limit on failure
  logs (off unless `synapse.logging.failure-burst.rate` and `.max-burst` are set; a Log4j2 `BurstFilter` at WARN on the
  failure logger, so WARN and less severe failure lines share the budget and ERROR and FATAL always pass; it is
  installed again whenever the Log4j2 configuration changes). Startup fails when Logback is also present. When the
  burst properties are set without the module, startup logs a WARN that they have no effect. Masking is done by
  Synapse before logging (see *JSON and masking*). Async is opt-in (add `com.lmax:disruptor` and set one documented
  property). It ships no configuration file that would override the application's.

### 5.9 Inbound load and shutdown
- **Concurrency limit:** every inbound plugin caps concurrent in-flight work (`synapse.api.rest.max-concurrent-requests`,
  `synapse.api.grpc.max-concurrent-calls`, `synapse.api.graphql.max-concurrent-requests`; Kafka is bounded by its
  concurrency). When full, it answers `UNAVAILABLE` (REST 503 with `Retry-After`) without running the operation.
  Defaults: REST 200, counting every request except Actuator management paths (which are never limited), with
  `Retry-After: 1`; gRPC 200, counting every call except the gRPC health and reflection services, answered
  `UNAVAILABLE` with `google.rpc.RetryInfo` (1 s); the GraphQL default is set in its plan. (With virtual threads,
  Boot's thread-pool settings no longer limit concurrency.) The limit is an `InboundCallLimiter` bean per plugin
  (3.5), so an adaptive limiter can replace the semaphore. A permit's outcome follows Netflix concurrency-limits'
  adapters: `DROPPED` for a call that ran out of time (REST 504 or an async timeout; gRPC `DEADLINE_EXCEEDED`) or
  that the client cancelled (gRPC), `IGNORED` when the handler threw past the edge, `SUCCESS` otherwise. What is
  never counted (management paths, gRPC health and reflection) is unchanged.
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
  `synapse.client.<name>.health.readiness=true`. A store's readiness check uses its own short timeout
  (`health.timeout`). A saturated pool (connections, none idle) is judged by progress, not by waiting: the check
  answers at once from its own borrow, which runs in the background, and the store is DOWN only when that borrow
  failed, or got no connection within the pool's `connectionTimeout` (at most 30 s). Otherwise it is UP with
  `pool: busy` and the number of waiting threads (decided 2026-11-20, maintainer). Whether Synapse defaults PgJDBC's
  `socketTimeout` is a separate open question (DESIGN.md §6).
- Load-balancer paths use Boot's health-group `additional-path` (e.g. `/health`); Synapse adds no alias. Setting a
  key of the `readiness` group defines the group, and Boot then keeps it instead of its own probe group, whose only
  member is `readinessState`; a group without `include` has every indicator. So when an application configures the
  readiness group, for example with `additional-path`, Synapse defaults its `include` to `readinessState` at lowest
  precedence; an application's `include` replaces it; otherwise Synapse sets no `include`, and Boot's probe group
  keeps its settings (the stores that opt in still join it). The readiness setup is then the group's
  `additional-path` alone (stores that opt in are added to the group).
- The same rule applies to the `liveness` group: when an application sets a `management.endpoint.health.group.liveness.*`
  key other than `include`, Synapse defaults its `include` to `livenessState` at lowest precedence, so a bare
  `additional-path` never puts the stores in liveness. Neither default is set when
  `spring.autoconfigure.exclude` lists Boot's `AvailabilityProbesAutoConfiguration` (an exclusion through
  `@SpringBootApplication(exclude = …)` isn't visible to Synapse; documented).
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
- **Parity check timing** (decided 2026-11-20, maintainer): core's parity check between protocols runs once every
  singleton exists. Each plugin keeps its bindings eager under `spring.main.lazy-initialization=true` with a
  `LazyInitializationExcludeFilter` (REST: the `synapseRestRoutes` bean by name; gRPC: every `BindableService` bean
  by type, which Spring gRPC creates at server start anyway). A binding that registers after the check has run (an
  explicitly `@Lazy` bindings bean, for example) fails startup, naming the binding: no binding is ever left unchecked.
- **Identity in 2.0** (security deferred whole to 2.x, maintainer, 2026-11-20; section 11): no edge reads Spring
  Security's `Authentication`. The REST and gRPC edges open the context without a principal, so on those edges
  `RequestContext.principal()` is empty in 2.0 and audits record the system user unless the application supplies
  its own `AuditorAware<String>` (8.2). AWS functions (6.5, P4) are the exception: the function plugin takes the
  principal from the authorizer context, which is its own feature and not part of the security module. A service
  can still authenticate with Boot's resource-server starter, and the REST edge passes Spring Security's exceptions
  from escape-hatch controllers through to Security (6.2, *Errors from Spring MVC*), but method security inside an
  operation answers `INTERNAL` (section 11).
- **Input conflicts:** the declared location wins; if another location carries the same component with a different
  value (path `/books/1`, body `"id": 2`), the request fails with `INVALID_INPUT`. Unknown fields are ignored.
- **Collections:** a non-null collection (`List`, `Set`, `Collection`) or `Map` component is never `null`. Absent, or
  JSON `null` over REST, it binds empty on every protocol, as proto3 binds an absent `repeated` or `map` field
  (proto3 can't tell absent from empty). "At least one item" is declared with `@NotEmpty` (or `@Size(min = 1)`),
  AIP-203's meaning of a required repeated field, and gives the same violation on every protocol. A `@Nullable`
  collection component of the input record stays legal for REST-only operations (absent binds `null`; one inside a
  nested body record binds empty); gRPC can't bind it (6.3). Responses are
  unchanged (plan 2026-11-20 contract).
- **Required scalars:** a non-null scalar component (a string, number, boolean, enum or bytes, a primitive included)
  is missing when the caller doesn't send it, on every protocol: REST answers `must not be null`, and so does gRPC,
  because a gRPC binding of one must use a field with presence (proto3 `optional`, a wrapper, or an enum whose zero
  value is unspecified); startup fails otherwise, listing every such component and field in one failure (6.3;
  plan 2026-11-20 contract).
- **Violation names:** a violation's field is the core path (5.2) in the protocol's wire names: REST the JSON
  property as Jackson reads it, the query parameter, header, cookie or path variable as declared (6.2); gRPC the proto
  field path (6.3). One format on both: dots plus `[index]`, as `google.rpc.BadRequest.FieldViolation.field`
  prescribes; never JSON Pointer. A map key is never written.
- **Parity check:** startup fails when the bindings of one operation over two protocols declare `.notFound`,
  `.etag` or `.sortable` differently (plan 2026-11-13, D10). The check pairs bindings by major version, as AIP-185
  ties the proto package's `vN` to the REST path's version: a gRPC method whose package ends in `vN` (`v1`,
  `v1beta1`) is compared with the REST routes of version N (`.version("1")`, `"1.2"`). When either binding of a pair
  is unversioned, they are compared, as before. A binding with no counterpart of its version over the other protocol
  (a gRPC `v1` method with only REST `v2` routes) is not compared, and startup lists every such binding in one WARN
  (maintainer, 2026-11-20; plan 2026-11-20 contract, decision 5).
- **Errors:** each plugin maps `ErrorCategory` to its protocol (table below). Overrides are keyed by error code,
  globally or per operation (`synapse.api.rest.errors.status.[CODE]`, `synapse.api.rest.operations.<op>.errors.status.[CODE]`,
  `synapse.api.grpc.errors.status.[CODE]`, `synapse.api.grpc.operations.<op>.errors.status.[CODE]`); precedence:
  per-operation > global > (gRPC only) `ABORTED` for a stale `etag` (6.3) > a built-in code's fixed status (REST: a
  `RestErrors` code's own status, 428 for `PRECONDITION_REQUIRED`; gRPC: `ALREADY_EXISTS`, `METHOD_NOT_FOUND`)
  > category default. A status-mapper bean (`RestErrorStatusMapper`, `GrpcErrorStatusMapper`; see 3.5) replaces
  this whole table: the override properties then have no effect, and startup WARNs naming each one that is set.
  A mapper reads the error and returns a status; it can't change the code. REST's default keeps the contract plan's
  order (6.2): a configured override, then the status kept from a Spring exception Synapse has no code for
  (`CLIENT_ERROR`, `SERVER_ERROR`), then the built-in and category statuses; a replacement mapper receives the same
  exception and decides all of them.
- **Exposure inventory:** at startup each plugin logs every exposed operation with protocol, route/method and port.

| Category | REST | gRPC | GraphQL (`ErrorType`) |
|---|---|---|---|
| INVALID_INPUT | 400 | INVALID_ARGUMENT (+ `google.rpc.BadRequest`) | BAD_REQUEST |
| UNAUTHENTICATED | 401 (+ `WWW-Authenticate`) | UNAUTHENTICATED | UNAUTHORIZED |
| FORBIDDEN | 403 | PERMISSION_DENIED | FORBIDDEN |
| NOT_FOUND | 404 | NOT_FOUND | NOT_FOUND |
| CONFLICT | 409 | ABORTED (the code `ALREADY_EXISTS`: ALREADY_EXISTS) | BAD_REQUEST |
| PRECONDITION_FAILED | 412 | FAILED_PRECONDITION (a stale `etag`: ABORTED, see 6.3) | BAD_REQUEST |
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
  `PAYLOAD_TOO_LARGE` 413, `UNSUPPORTED_MEDIA_TYPE` 415, `MALFORMED_REQUEST` 400, `PRECONDITION_FAILED` 412 (a weak `If-Match`, or an `If-Match`/`If-None-Match` that the route doesn't bind), `CORS_REJECTED` 403 (a cross-origin request the CORS settings don't allow), `MISSING_API_VERSION` 400 and `UNSUPPORTED_API_VERSION` 400 (when Boot's `spring.mvc.apiversion.*` settings make Spring reject a request's version); gRPC `UNIMPLEMENTED` with `METHOD_NOT_FOUND` (an unknown method) and
  `INVALID_ARGUMENT` with `MALFORMED_REQUEST` (request bytes that aren't a valid message). One case keeps the
  protocol's own shape: a gRPC message over the size limit is grpc-java's `RESOURCE_EXHAUSTED`, without `ErrorInfo`
  (see 6.3).
- **Required preconditions** are not protocol-only: a non-null `VersionCondition` component makes the precondition
  part of the operation's input contract, and every plugin answers a request without it with core's
  `PRECONDITION_REQUIRED` (category `PRECONDITION_FAILED`) before the operation runs. REST maps it to 428 (RFC 6585
  §3).

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
pipeline and correlation handling. Their errors get Synapse's mapping after the application's own handlers: Spring
MVC's `@ExceptionHandler` methods and `@ControllerAdvice` classes answer first (see *Errors from Spring MVC* below;
maintainer, 2026-11-20, decision 13, which reverses the earlier "same error mapping" rule).

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

A component that is not a collection or an array takes one value. A repeated query parameter or header line for it is
`must have a single value` (plan 2026-11-13, Q12). A repeated cookie is the exception: a single-valued cookie
component takes the first copy, as Spring's `@CookieValue` does, because a browser stores cookies, a sibling host can
plant a second copy, and a 400 would last until a copy expires. The duplicate is logged at DEBUG by cookie name only,
and counted by the Micrometer counter `synapse.rest.cookie.duplicates` (tags `cookie`, the declared binding name, and
`operation`; registered at startup for each single-valued cookie binding, a no-op without a `MeterRegistry`). A
`List` component gets every copy and is never counted; `@Size(max = 1) List<String>` rejects duplicates for a
component that wants it (maintainer, 2026-11-20, decision 6).

**Paging safety:** sort keys must be allow-listed per route (`.sortable(SearchBooksRequest.SortKey.class)` or a set of
names). The syntax is `sort=title,-published` (a comma list; `-` means descending). An unknown key, or `sort` on a
route without `.sortable`, is `INVALID_INPUT`. Offsets are capped (`synapse.paging.max-offset`, default
1000). Page size defaults to `synapse.paging.default-size` (20) and is capped by `synapse.paging.max-size` (100). The
plugin checks size and offset before the pipeline and reports violations under the parameter names.

When the next offset of a page would pass the cap while `hasNext` is true, the page still gets a next token (in the
body's `nextCursor`, and on GET/HEAD in `Link: next` as the `cursor` parameter), so a client that follows tokens
never stops silently. The token fails when used: `VALIDATION_FAILED` on the token's parameter, with the message
`has reached the offset limit (1000); narrow the query or use keyset paging` (the cap's value). gRPC's
`next_page_token` follows the same rule (6.3), so the token is empty only at the real end of the collection, as
AIP-158 asks (maintainer, 2026-11-20).

Cursors are opaque. The edge wraps an operation's cursor with core's `CursorCodec` bean, which every API plugin shares
and an app can replace. By default it is AES-256-GCM authenticated encryption, bound to the operation, the sort and a
digest of the other inputs (not the size, and not a `VersionCondition`), with a key id and an issue time. A tampered,
foreign, mismatched or expired cursor is `INVALID_INPUT` on `cursor`.

The key comes from `synapse.paging.cursor.key` (base64, 256 bits). `cursor.previous-keys` are accepted for
decryption during rotation. `cursor.max-age` is unset by default (no expiry). When no key is set, one is generated per
start, with a startup WARN (in every environment) that cursors then fail across restarts and instances. An operation's
raw cursor never reaches the client.

**Response mapping:**

| Case | Default | Route option |
|---|---|---|
| GET with a value | 200 | `.status(...)` |
| `Optional` empty | 404 problem+json, code `NOT_FOUND` | `.notFound(BookErrors.BOOK_NOT_FOUND)`; `.emptyAs(204)` |
| Empty list | 200 with `[]` | none |
| `PageResult` | 200 with `{"items":[…],"nextCursor":"…","hasNext":true,"total":42}` (`nextCursor` and `total` omitted when null); GET/HEAD also send `Link: <relative URL>; rel="next"` with the next cursor, or with the next offset while it is within `max-offset`, and past it with the capped token (above) | none (output `.header`, `.body` and `.etag` fail startup) |
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
  - on a write route the component is a `VersionCondition<V>`: one strong tag binds `Version(v)`, its
    unquoted value converted to `V`; `*` binds `AnyVersion`; no header binds `null` when the component is
    `@Nullable`, and is 428 `PRECONDITION_REQUIRED` when it isn't (see below). A component of any other type
    bound from either header on a write route fails startup, and a `VersionCondition` binds from nothing else (except
    `.context`/`.from`). On `GET`/`HEAD` a plain `@Nullable` component still works (`*` and no header bind `null`);
  - a list or an unquoted value is `INVALID_INPUT`;
  - a weak `If-Match` is 412 without calling the operation.

  A `STALE_VERSION` failure is 412 `PRECONDITION_FAILED` when the request carried a bound `If-Match`, and 409
  otherwise. Other failures map as usual, and a missing resource is 404, with or without `If-Match`: the request
  would be 404 without it, and RFC 9110 §13.2.1 says such a failure takes precedence over preconditions (an upsert
  that would create answers a false `If-Match` 412). An `If-Match` or `If-None-Match` on an unsafe
  route that doesn't bind it is 412: the precondition can't be verified. `If-Unmodified-Since` and
  `If-Modified-Since` are ignored (Synapse emits no `Last-Modified`). Whether a precondition is required is the
  operation's input contract, not a route setting: a write whose `If-Match` or `If-None-Match` component is a
  non-null `VersionCondition` answers a request without that header 428 `PRECONDITION_REQUIRED`, with a violation per
  missing header, before the operation runs (`*` counts as present). It comes after binding's 400s and the 412s
  above. A `@Nullable` component keeps the header optional. On `GET`/`HEAD` the component must be `@Nullable`.
- Errors are RFC 9457 `application/problem+json` with extension members `code`, `correlationId` and `violations[]`
  (each `{field, message}`; the field in REST's wire names: a body property as Jackson reads it, with the mapper's
  naming strategy and `@JsonProperty`, aliases excluded; a query parameter, header, cookie or path variable as the
  binding declares it; a `.context` or `.from` component, or a part of the path Jackson can't name, by its component
  name, the path cut there). With `synapse.errors.type-base-uri` unset, `type` is `about:blank` and `title` is the HTTP
  status phrase; when set, `type` is the base URI plus the code and `title` comes from the category. `status` always
  equals the real status. `violations` is omitted when empty. With `type-base-uri` set, `type` is the base URI, `/`
  and the code, and the category title can be overridden with the `MessageSource` key `synapse.category.<CATEGORY>`.
  A 401 without `synapse-security` carries `WWW-Authenticate: Bearer`.
- No generic `{data, meta}` wrapper: a single resource is the body itself, and a `PageResult` is the page object above.
  The correlation id, the paging `Link` and `ETag` travel in headers.

**Errors from Spring MVC** (maintainer, 2026-11-20, decision 13; it reverses the rule that Synapse's resolver runs
before Spring's, for escape-hatch controllers only). Synapse's resolver is no longer a `HandlerExceptionResolver`
bean. A `WebMvcConfigurer` bean (`synapseRestExceptionResolverConfigurer`, backing off by name) adds two instances of
it through `extendHandlerExceptionResolvers`. The order is Boot's `DefaultErrorAttributes`, then:
1. Synapse's resolver for Synapse paths, first in Spring's composite. It answers only an exception raised on a path
   that a Synapse route serves (any method, the `/v<N>` prefix included) whose handler is a Synapse handler or none
   was found (`handler == null`: a missing or invalid API version, a 405 or 415 from annotated-mapping lookup). So on
   a Synapse path the answer is always Synapse's problem, before any application advice (maintainer, 2026-11-20).
   That includes a path a Synapse route shares with a controller: a 415 from the controller's own mapping lookup has
   no handler yet, so it is Synapse's. The configurer resolves the routes lazily, inside
   `extendHandlerExceptionResolvers`, because Spring MVC's configuration needs every `WebMvcConfigurer` while the
   routes need its conversion service.
2. `ExceptionHandlerExceptionResolver`: the application's `@ExceptionHandler` methods and `@ControllerAdvice` classes,
   for its controllers (Synapse routes are `HandlerFunction`s, so advice never sees their failures).
3. Synapse's main resolver, right after it (second when an application removed it). It answers every other exception.
4. `ResponseStatusExceptionResolver` and `DefaultHandlerExceptionResolver`.

Both Synapse instances return `null` in two cases: a committed response, and Spring Security's
`AccessDeniedException` and `AuthenticationException` (subclasses included, matched by class name, so Synapse needs no
Security dependency), which propagate to Security's `ExceptionTranslationFilter`.

For an `ErrorResponse` (`ResponseStatusException` included), then for `@ResponseStatus` on the exception's class,
looked up along the cause chain as Spring's `ResponseStatusExceptionResolver` does:
- a status in the status table keeps its Synapse code (409 is `CONFLICT`);
- any other status keeps its own status (422, 410, 502) with a REST-only code: `CLIENT_ERROR` (category
  `INVALID_INPUT`, logged at DEBUG) for 4xx, `SERVER_ERROR` (category `INTERNAL`, logged at WARN without a stack
  trace, because it was raised on purpose) for 5xx;
- the status is decided in one place, `RestErrorStatuses.statusFor(SynapseException, operation)`: a configured status
  for the code (per operation, then global) first, then the kept status, then a built-in code's fixed status and
  the category's. So `synapse.api.rest.errors.status.[CLIENT_ERROR]=400` applies. The REST status-mapper SPI of the
  API-surface follow-up takes the same `SynapseException` and operation, as gRPC's does, and its default keeps this
  order;
- the exception's headers are copied (`Allow`, `Retry-After`);
- the reason (`getReason()`, `@ResponseStatus.reason`, an `ErrorResponse`'s own problem detail) is the `detail` only
  when `synapse.errors.include-detail` is `true`.

The servlet container's error dispatch maps a bare status by the same table, with the same two codes for a status
outside it. While `spring.mvc.problemdetails.enabled` is `true`, startup logs one WARN: Boot's
`ProblemDetailsExceptionHandler` then answers Spring MVC's exceptions from controllers in Spring's problem shape, not
Synapse's (Synapse paths are not affected). Synapse doesn't turn it off.

**API versioning:** off unless a binding uses `.version("2")`. A versioned route is served with `v` and its version as
the first path segment: `.get("/books/{id}", getBook).version("2")` serves `/v2/books/{id}`. A version is
`major[.minor[.patch]]`. Synapse then enables Spring Framework 7's API versioning with a path-segment resolver and a
predicate so that only the paths of versioned routes are parsed, and makes the version optional (Spring requires one by
default), so unversioned routes keep working; a versioned route carries its version in its path. A version that no route
declares is 404 `ROUTE_NOT_FOUND`. The rest is tunable with Boot's `spring.mvc.apiversion.*` properties (`supported`,
`detect-supported`, `default`, `use.header` and the other resolvers for escape-hatch controllers, and `required`, which
then applies to every route). `spring.mvc.apiversion.use.path-segment` fails startup while a route is versioned:
Boot's resolver would parse every path. Deprecated versions emit Spring's `Deprecation`, `Sunset` and `Link` headers
through a bean of Spring's `ApiVersionDeprecationHandler`, which Boot applies.

**CORS:** off by default. `synapse.api.rest.cors.allowed-origins` (exact origins such as `https://app.example.com`, or
`*`), `.allowed-methods` (default `GET, HEAD, POST, PUT, DELETE`), `.allowed-headers` (default `*`), `.exposed-headers`
(added to the response headers Synapse writes, which are always exposed: the correlation header, `ETag`, `Link`,
`Location`, `Retry-After`, `Deprecation`, `Sunset`), `.allow-credentials` (default `false`), `.max-age` (default 30
minutes). An edge filter applies them to every path except Actuator's management paths (Boot's
`management.endpoints.web.cors.*` covers those), on REQUEST and ERROR dispatches, through a
`UrlBasedCorsConfigurationSource` bean named `corsConfigurationSource` (which Spring Security also uses when present).
A rejected cross-origin request is 403 `CORS_REJECTED` problem+json. Startup fails on `*` with credentials and warns on
`*` in every environment.

**Limits:** request body 1 MB (`synapse.api.rest.max-request-size`; per route `.maxRequestSize(...)`), rejected with
413 without reading the whole body. Header size uses Boot's `server.max-http-request-header-size`; Synapse sets its
default to 16 KB at lowest precedence.

**OpenAPI** (plan 2c, module `synapse-api-rest-openapi`): a Synapse customizer built from the resolved routes
generates OpenAPI 3.1 from `RestBindings`, `RestConventions` and the record types: Jakarta Validation constraints,
statuses, mapped headers, problem+json responses, paging parameters, `ETag`/`If-Match`, versions. It uses Synapse's own
schema generator on Jackson 3, so property names match what the app's `JsonMapper` writes, and springdoc (3.x) serves
the result at `/v3/api-docs`. Toggles use springdoc's own properties; Synapse defaults Swagger UI to off, the API
docs to off and their version to OpenAPI 3.1, at lowest precedence. An environment's file turns the API docs on
(`springdoc.api-docs.enabled: true`), as for every exposure (3.6). Under AOT that toggle is build-time: springdoc
3.2.0's configuration is `@ConditionalOnProperty(springdoc.api-docs.enabled, matchIfMissing = true)`, so the value at
`process-aot` decides whether AOT artifacts serve the document in every environment, and `openapi.adoc` says how to
set it for the build (the build environment's `application-build.yaml`, or
`-Dspring-boot.aot.jvmArguments=-Dspringdoc.api-docs.enabled=true`).
Plan 5 adds a startup check (below). While the document is off, the startup pass still honours springdoc's path
filters, read from the environment. Escape-hatch controllers are documented by springdoc
itself (with its own, Jackson 2 based, schemas).

**Binary content (P4):** `BinaryContent` components map to multipart parts or a raw body on input and stream back as a
download on output. Responses always carry `X-Content-Type-Options: nosniff` and `Content-Disposition: attachment`
with an RFC 6266-encoded filename. Content is streamed, never fully buffered.

**Codecs:** JSON (P1); XML via `tools.jackson.dataformat:jackson-dataformat-xml` 3.x (P4), with
`application/problem+xml` errors. The protobuf REST codec is a 2.x add-on.

### 6.3 gRPC (`synapse-api-grpc`)
- Built on Boot's gRPC server module (`spring-boot-grpc-server` 4.1.2, Spring gRPC 1.1.2; never the starter, which
  brings a logging starter). Like the starter, the plugin depends on `io.grpc:grpc-netty` (a standalone Netty server on
  `spring.grpc.server.port`, default 9090) and `io.grpc:grpc-services` (reflection and the gRPC health service).
  Shaded Netty works by exclusion (Boot's documented swap). The servlet transport (`grpc-servlet-jakarta` on the
  servlet container's HTTP/2) is not supported together with `synapse-api-rest` in P1: the REST edge would also
  filter gRPC calls, so startup fails when both are active (decided in plan 3; revisit in a later plan).
- Synapse error mapping plugs into Boot's gRPC exception handling (a `GrpcExceptionHandler` bean, ordered last);
  Boot's observation interceptor is reused. Message and metadata limits use Boot's
  `spring.grpc.server.inbound.message.max-size` (Boot's default 4 MB) and `spring.grpc.server.inbound.metadata.max-size`;
  Synapse defaults the metadata limit to 16 KB at lowest precedence.
- **The edge** is two global interceptors around Boot's: Synapse's edge interceptor runs first and hides or strips
  trace and baggage metadata before Boot's observation interceptor extracts it (as the REST edge does with headers);
  Synapse's context interceptor runs inside the server observation and Boot's exception handling. It opens the
  Synapse scope for every listener callback, so escape-hatch services get the same context.
  - Correlation id: the metadata key is `synapse.correlation.header` in lower case (`x-correlation-id`). It is echoed
    in the response headers, or in the trailers when the call ends without headers.
  - Attributes: `synapse.api.grpc.context.metadata.<attribute>=<key>`; keys must be valid ASCII metadata keys (not
    `-bin`), checked at startup. The locale comes from `accept-language` metadata when present.
  - Deadline: the gRPC deadline, capped by `synapse.api.grpc.deadline.max`; without one,
    `synapse.api.grpc.deadline.default` (see *Deadlines*).
  - Concurrency: `synapse.api.grpc.max-concurrent-calls` (see *Inbound load and shutdown*).
  - When `spring.threads.virtual.enabled` is true, Synapse gives Boot's server a virtual-thread executor through
    Boot's `GrpcServerExecutorProvider`.
- **Bindings:** a `GrpcBindings` bean binds one gRPC service and is itself a `BindableService`, so Boot discovers it
  and applies the same global interceptors as to a hand-written `*ImplBase` service (the escape hatch).
  `GrpcBindings.service(BookServiceGrpc.getServiceDescriptor())` lists `.unary(BookServiceGrpc.getGetBookMethod(), getBook)`
  entries; `.bindAllByName()` binds every remaining method to the operation with the matching name (`GetBook` →
  `get-book`). Startup fails if a method is unbound, bound twice, or its types can't be mapped. Unary only in 2.0.
  Per method: `.map("field.path", Record::component)`, `.context(Record::component, ctx -> …)`,
  `.notFound(code)`, `.etag(Record::component)` and `.sortable(SortKey.class)`, as in REST. `PageQuery` and
  `PageResult` bind to AIP-158's fields: `int32 page_size` (unset is the default size), `string page_token`, an optional
  `int32 skip` capped by `synapse.paging.max-offset`, and `string order_by` (AIP-132; sort keys are the lower-case
  constant names, `@JsonProperty` is not read); the response's one repeated message field holds the items,
  `string next_page_token` the next page's token (by REST's `Link: next` rule: a cursor, or a next offset for a page
  without one, and past `synapse.paging.max-offset` the capped token that fails when used, 6.2; empty only on the
  last page, as AIP-158 asks), `bool has_next` REST's `hasNext` (required; AIP-158 doesn't forbid other fields, and
  its linter doesn't flag them; plan 2026-11-20 contract) and, when
  declared, an `optional int32` or `int64 total_size` the total. A missing field or a wrong type fails startup. Page
  tokens use core's cursor codec, so a token issued over REST pages over gRPC for the same operation, filters and
  sort, and the reverse; paging errors are REST's codes, with the gRPC field named (plan 5).
- **Message mapping:** request and response messages map to records **by name at runtime using protobuf descriptors**
  (`snake_case` → `camelCase`), verified at startup. Synapse reads and writes `DynamicMessage`s over the generated
  descriptors and never instantiates generated message classes. Built in: `Timestamp`↔`Instant`, `Duration`, wrapper
  types ↔ nullable components, enums by name, repeated ↔ `List`, maps, nested messages, `google.protobuf.Empty` ↔
  `Void`, and strings converted with Spring's `ConversionService` (UUID, dates). A nullable component needs a field
  with presence (a wrapper, a message or proto3 `optional`). So does a non-null scalar component of a request (6.1,
  *Required scalars*): startup fails when one is bound to a field without presence, naming the component and the
  field, so an unsent field is `must not be null`, as over REST, never `""` or `0`. The token fields (`etag`,
  `if_none_match`) and the page fields keep their own rules. A non-null `List` or `Map` binds a `repeated` or `map`
  field, which has no presence, so absent is empty (6.1, *Collections*). `oneof` fields and renamed fields use an explicit
  override in the binding (`.map(...)`). Request fields without a component are ignored; a record component without a
  field fails startup.
- **Versions** (AIP-154, with REST's tokens and rules): a `VersionCondition<V>` component of the input record binds
  from one of two fixed request fields, each with its REST header's semantics: `string etag` (If-Match; the default)
  or `string if_none_match` (If-None-Match; `.map("if_none_match", ...)`, as REST's `.header("If-None-Match", ...)`).
  Startup fails if the field is missing, not a singular string or in a real `oneof`, if two components share a field,
  if one is nested or supplied by `.context`, if a plain component maps to a token field, or if a non-null one is on a
  `QueryOperation`. The value is the REST header value verbatim (`"5"` with its quotes, or `*`), parsed by core's
  shared entity tag grammar (`core.internal.conditional.EntityTags`), and the tag's value is read by core's version
  token codec (`VersionTokens`: plain decimal, ISO-8601 or the string, locale-independent), which REST uses for
  `ETag`, `If-Match` and `If-None-Match` too; one set of version types on both protocols. Empty is no condition. On a
  write (a `CommandOperation`), as REST: a non-null component without a value is `PRECONDITION_REQUIRED` (by category
  `FAILED_PRECONDITION`) with the header's own advice, a weak `etag` is `PRECONDITION_FAILED` (a weak
  `if_none_match` binds its value), and a non-empty token field no component binds is `PRECONDITION_FAILED`, all
  before the operation runs. `STALE_VERSION` from a request that sent an `etag` becomes the code `PRECONDITION_FAILED`
  (REST's 412) with the status `ABORTED` (AIP-154; plan 2026-11-13, D2), unless a `[PRECONDITION_FAILED]` status is
  configured; without one it stays `STALE_VERSION` (`ABORTED`, REST's 409). Every other `PRECONDITION_FAILED` (weak,
  unbound, or the operation's own) is `FAILED_PRECONDITION`: Synapse emits only strong tags, so a weak one is never a
  stale version of the resource but a request that can't succeed in any state (strong comparison, RFC 9110 §13.1.1),
  and `ABORTED` would invite read-modify-write retries (maintainer, 2026-11-20, decision 9). A read binds a weak tag's
  value and ignores an unbound token, and gets no 304. `.etag(Record::component)` writes the strong tag to the
  response's `string etag` field, on a `QueryOperation` or a `CommandOperation` whose input has no `VersionCondition`
  only (REST sends no `ETag` on `PUT`/`DELETE`). So neither protocol returns the new version's token on an update, and
  clients re-read: a departure from AIP-154 ("must be provided by the server on output"), kept so that one contract
  holds on both protocols (maintainer, 2026-11-20). A missing resource is `NOT_FOUND` with or without an `etag`, as
  over REST (6.2).
- Errors: status per the table; the Synapse code travels as `google.rpc.ErrorInfo` (`reason` = code, `domain` =
  `spring.application.name`, or `synapse` when it is unset, `metadata.correlationId`; AIP-193) in the status details, plus `google.rpc.BadRequest`
  for violations (field paths in proto names, in 6.1's format). The status description is the detail, only when
  `synapse.errors.include-detail` is true. A hand-written service's own status (a thrown `StatusException` or
  `StatusRuntimeException`, or one passed to `responseObserver.onError(...)`, typically a downstream call's failure
  forwarded) keeps only its code unless `include-detail` is true: its description, its `grpc-status-details-bin` and
  its other trailers are dropped, and Synapse's `ErrorInfo` is always added, with the code that the status maps back to
  (`NOT_FOUND`, `ABORTED` → `CONFLICT`, otherwise the category's code, an unknown status `INTERNAL`). A status from the
  application's own `@GrpcAdvice` or Spring Security's handler is the application's answer and is left alone
  (maintainer, 2026-11-20, decision 24). A malformed request message is `INVALID_ARGUMENT` with `MALFORMED_REQUEST`.
  An unknown method is `UNIMPLEMENTED` with the code `METHOD_NOT_FOUND`. A message over the size limit is rejected by
  grpc-java with `RESOURCE_EXHAUSTED` and carries no `ErrorInfo`: grpc-java checks the size only once the call has
  requested its message, so the call reaches the interceptors (`interceptCall`, then `onCancel`), but no message is
  delivered, the operation is not invoked, no exception handler runs and the concurrency permit is released (verified
  in grpc-java 1.84.1; *Protocol-only failures* in 6.1 notes the exception).
- Server reflection is off by default: Synapse sets Boot's `spring.grpc.server.reflection.enabled` (verified in Boot
  4.1.2) to `false` at lowest precedence, and an environment file turns it on. The key decides whether a bean exists,
  so it is build-time under AOT: in AOT mode startup fails when its value differs from the one the build used.
- **Exposure inventory:** `Synapse gRPC exposes <service>/<method> -> <operation> on port <port>`, logged when the
  server starts.

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
  package. A store's beans are named after it (`booksDataSource`, `booksTransactionManager`, …) and carry the
  qualifier `books`. A bean with that name in the `@SynapseDataStore` class replaces Synapse's. With both
  `synapse-data-jpa` and `synapse-data-jdbc` present, each store names its access
  (`@SynapseDataStore(value = "books", access = DataAccess.JDBC)`).
- Keys under `synapse.data.stores.<name>`: `url`, `username`, `password`, `driver-class-name`, `primary`,
  `database-version`, `allow-ddl-auto`, `hikari.*` (Hikari's own settings, bound the way Boot binds
  `spring.datasource.hikari.*`), `jpa.*` (Boot's `JpaProperties` shape, with `jpa.hibernate.ddl-auto` and
  `jpa.hibernate.naming.*`), `jdbc.template.*`, `migrations.enabled`, `migrations.locations`, `migrations.flyway.*`
  (Flyway's own configuration keys), `health.readiness`, `health.timeout`. `primary` and the set of names decide
  which beans exist, so they are build-time properties under AOT.
- A store's `url`, `username` and `password` have no default (see *Keys without defaults* in 3.6). A data module sets
  `url` in its `config-<env>.yaml` files; credentials come from environment variables
  (`SYNAPSE_DATA_STORES_<NAME>_PASSWORD`). Pool and vendor settings keep safe defaults in `config.yaml`.
- Synapse builds each store, including a single one, with Boot's builders: DataSource (Hikari), EntityManagerFactory
  or JDBC infrastructure, transaction manager, repositories, health contributor, metrics. Boot's single-DataSource
  auto-configuration backs off; `spring.datasource.*` is not used (startup warns if set).
- With one store it is the primary. With several, one may be `primary=true` for unqualified `@Transactional`; others
  are selected with `@Transactional("legacy")`. An unqualified `@Transactional` with several stores and no primary fails
  at startup. Cross-store transactions are not atomic (documented).
- **A store connects where its `url`, `username` and `password` say, and as that login user.** Keys that would
  change the target, the login user or a secret fail startup, naming the key and never its value (`hikari.jdbc-url`,
  `hikari.username`, the driver's target and user properties, the Flyway and JPA connection keys, credentials in the
  URL). SQL or server settings that run on each new connection (`hikari.connection-init-sql`,
  `hikari.connection-test-query`, `migrations.flyway.init-sql`, a `FlywayConfigurationCustomizer` that sets `initSql`,
  and a vendor's session-settings parameter such as PgJDBC's `options`, as a driver property or in the URL) are
  allowed with one startup WARN per store and key that names the key, never the value: they can change the role or
  schema the store's statements run with (decided 2026-11-20, maintainer).
- **Connection details:** a store uses only a Boot `JdbcConnectionDetails` qualified with its name, with one store
  as with several. A store without a `url` whose service has unqualified details beans (from `@ServiceConnection` or
  Boot's Docker Compose support) fails startup naming them; with a `url`, the startup WARN below names them
  (decided 2026-11-20, maintainer). `synapse-test-data` has a public factory for qualified details from a container
  `@Bean`, and the docs show a qualified adapter for Docker Compose; neither uses `@Profile`.
- **Beans a store doesn't use get a startup WARN, never a failure** (decided 2026-11-20, maintainer): a store-scoped
  bean that no store uses (a qualifier that names no store, or a store of the other access kind, a qualifier type
  other than `@Qualifier`, a kind of bean no store has); a Flyway customizer, `Callback` or `JavaMigration` of a store
  whose migrations are off; `@EntityScan` while any store exists; an unqualified `JdbcConnectionDetails` that no store
  uses. Spring Boot's own auto-configured beans (a `@Bean` method in an `org.springframework.boot.` class, which
  covers the configurations Boot imports), and a bean a store-qualified adapter consumes, are left out. A bean Boot
  registers without a factory method, such as a `@ServiceConnection`'s details, is not left out. Any other
  auto-configured bean warns, naming its defining class.
- **Migrations per store:** Flyway (Liquibase is not in 2.0, decided 2026-11-20; DESIGN.md §5), run by Synapse per
  store from
  `db/migration/<store>` in `SmartLifecycle.start()`, followed by schema validation against the entities (Hibernate's
  schema validator), before any inbound plugin starts. When a migration tool is present, Synapse sets `ddl-auto=validate`, and startup fails if
  `ddl-auto` is `create`, `create-drop` or `update` unless the store allows it explicitly
  (`synapse.data.stores.<name>.allow-ddl-auto=true`, name confirmed in plan 4), which belongs in
  `application-local.yaml` or a test file. Without a migration tool the default is `none`. Synapse runs the schema
  action itself in `start()`, after the migrations. Teams that migrate in a separate job turn the migrations off per
  store (`migrations.enabled=false`); validation still runs.
- `spring.jpa.open-in-view` defaults to `false`, set at lowest precedence (operations are the transaction boundary,
  and entities never reach the API).
- Startup logs the connection budget (stores × pool size) to help size databases.

### 8.2 Access model
- Developers write entities and plain Spring Data repositories; business and operation code inject repositories.
- Capability interfaces: `Identifiable<ID>`, `Versioned` (optimistic locking), `Auditable` (created/modified at/by).
  Optional `@MappedSuperclass` bases: `IdEntity`, `VersionedEntity`, `AuditedEntity` (UUIDv7 ids) and `LongIdEntity`,
  `LongVersionedEntity`, `LongAuditedEntity` (database sequence), with Mongo
  equivalents in P5. Plain entities still work without the features.
- **Ids:** bases default to app-generated UUIDv7 (RFC 9562, monotonic within a millisecond using the `Clock`; not a
  security capability); `Long` sequence variants exist. Bases implement `Persistable` (new when the version is null;
  `IdEntity` and `LongIdEntity`, which have no version, are new when the id is null) and compare by id, proxy-safe.
- **Auditing:** fills created/modified timestamps and user (`AuditorAware` from the `RequestContext` principal, which
  only Spring Security sets, else `synapse.data.auditing.system-user`, default `system`). An application
  `AuditorAware<String>` bean replaces that source. Until the 2.x security module sets a verified principal, the
  principal is empty at the REST and gRPC edges (6.1), so every write made there records the system user unless the
  application supplies its own `AuditorAware<String>` (documented in `data.adoc`).
- **Error translation** (an `ExceptionTranslator`): optimistic-lock failures → `STALE_VERSION` (`CONFLICT`);
  unique-constraint failures → `ALREADY_EXISTS` (`CONFLICT`; gRPC ALREADY_EXISTS); query timeout →
  `DEADLINE_EXCEEDED` (`TIMEOUT`); connection failure → `UNAVAILABLE`; other data access failures → `INTERNAL`.
  A commit whose outcome is unknown (a lost connection while committing, SQLState 08007 or 40003 anywhere in the
  causes) → `COMMIT_OUTCOME_UNKNOWN` (`INTERNAL`), checked first. A transaction the database rolled back because it
  lost a race (SQLState 40001, PostgreSQL's 40P01 and 55P03, and codes a vendor module recognises) →
  `TRANSACTION_CONFLICT` (`CONFLICT`: REST 409, gRPC `ABORTED`, logged at WARN with the SQLState, no `Retry-After`),
  classified by exact state, never by Spring's `PessimisticLockingFailureException` (decided 2026-11-20, maintainer).
  SQLState class 53 is `UNAVAILABLE`; class 53 is PostgreSQL's and DB2's, not the SQL standard's, so it moves behind
  the vendor interface before P5.
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
  (startup fails otherwise). Synapse adds no role check in 2.0: the 2.x security module adds one; until then the
  application secures the endpoint in its own Spring Security configuration. It acts on one pod only (documented).
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

## 11. Security (`synapse-security`, deferred to 2.x; the agreed design)
The maintainer deferred all of `synapse-security` to 2.x on 2026-11-20 (DESIGN.md 4.1 *Security deferred to 2.x*
and *Roadmap*). This section stays the agreed design for that module; nothing in it is built in 2.0, except that
AWS functions take the principal from the authorizer context, a feature of the function plugin (6.5). Until it
ships:
- `RequestContext.principal()` is empty at the REST and gRPC edges: no code in core, REST, gRPC or data reads Spring
  Security's `Authentication`, and both edges open the context without a principal (6.1).
- Audits record the system user (`synapse.data.auditing.system-user`, default `system`) on every write made there,
  unless the application defines its own `AuditorAware<String>` bean, which replaces that source (8.2).
- Kafka pause and resume have no Synapse role check. They still need
  `management.endpoint.kafkasubscribers.access=unrestricted` and Spring Security present, or startup fails (9.3);
  the application secures the endpoint in its own Spring Security configuration.
- Method security inside an operation answers `INTERNAL` (500): the pipeline turns an `AccessDeniedException` or
  `AuthenticationException` thrown there into `INTERNAL` before any edge sees it, unless the application registers
  its own `ExceptionTranslator` for those exceptions (the last bullet below).
- The *access control per deployment model* question (DESIGN.md *Open questions*), the browser security headers,
  the Actuator lock-down, `trusted-header` mode and the check of Spring Security's use of the
  `corsConfigurationSource` bean (P1, plan 2c note) move to 2.x with the module.

The agreed design:
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
- **Method security inside an operation** (to settle with the security module, which the maintainer deferred to 2.x
  on 2026-11-20): in P1 the pipeline turns an `AccessDeniedException` or `AuthenticationException` thrown inside an
  operation (method security on the operation bean) into `INTERNAL` before any edge sees it, while the REST edge
  passes Security's exceptions from controllers through to `ExceptionTranslationFilter` (6.2, *Errors from Spring
  MVC*). The security module decides whether the pipeline passes them through unwrapped, so Spring Security answers
  with the right challenge (a REST 401 then keeps `WWW-Authenticate`, as RFC 9110 requires), or translates them in
  core to `UNAUTHENTICATED`/`FORBIDDEN`. Passing them through is the Spring-first choice (research round 2,
  decision 13).

---

## 12. Test support (`synapse-test`)
| Tool | Phase | Behaviour |
|---|---|---|
| `SynapseOperations.invoke(op, input)`, `RequestContexts.with(...).run(...)` | P1 | Runs the real pipeline around a plain instance without Spring; works for `BusinessOperation` too. |
| `@SynapseOperationTest` | P1 (client fakes P2, publisher fakes P3) | Boots operations, business beans and the pipeline only; replaces client interfaces and `PublisherOperation`s with fakes or mocks. |
| `assertThatSynapseException(...)` | P1 | `.hasCode(...)`, `.hasCategory(...)`, `.hasViolation(field)`. |
| @SynapseRestTest (synapse-test-rest) | P1 | Plugin plus the app's bindings and controllers; the test declares a @MockitoBean for each bound operation (the slice fails fast and names any missing one); RestTestClient bound to MockMvc (the same client works against a running server). |
| @SynapseGrpcTest (synapse-test-grpc) | P1 | Plugin plus the app's bindings and hand-written services; the test declares a @MockitoBean for each bound operation (the slice fails fast and names any missing one); Boot's in-process test transport (`@AutoConfigureTestGrpcTransport`), with stubs from `@ImportGrpcClients`. |
| `@SynapseDataTest("<store>")` (synapse-test-data) | P1 | Starts only that store's beans, repositories, migrations and auditing, in a transaction on that store that rolls back. The store connects through `@StoreConnection("<store>")` on a container (a shared Testcontainers PostgreSQL is provided). Boot's `@ServiceConnection` registers only one JDBC connection per context, so it can't name stores. |
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
- **Logging:** services use Boot's Logback, which their starters bring; the parent adds no logging dependency. The
  parent manages `synapse-logging-log4j2` (through the BOM) for teams that opt in to Log4j2; only a `service-*` module
  declares it, with the `spring-boot-starter-logging` exclusions, so published library modules (contract, client,
  data, publisher, business) stay backend-free. Synapse masking works with either backend.
- **Tracing:** the parent manages `synapse-tracing-opentelemetry` (through the BOM); each `service-*` module declares
  it (the archetypes add it), and library modules never do. No sample or archetype sets
  `management.otlp.metrics.export.enabled`: without Micrometer's OTLP registry nothing pushes metrics.
- **Environments:** the archetype maps `spring.profiles.active: ${ENV}` in each service's `application.yaml`, sets
  `spring-boot.run.profiles=local`, and generates `application-local.yaml` (local conveniences turned on, such as
  `synapse.errors.include-detail: true`), `src/test/resources/application-test.yaml` in every module with Spring Boot
  tests, and `META-INF/synapse/config.yaml` in each library module (see *Environments and module configuration*).
- **Version pins:** because an explicit managed entry wins over every imported BOM, including a consumer's own import
  (such as `guava-bom`), which Maven processes before the inherited `synapse-bom` import, the parent declares Synapse's
  runtime pins directly in its own `dependencyManagement` (kept identical to `synapse-bom` by the CI sync check, proven
  by an invoker test whose project imports an older `guava-bom`). BOM-only users import `synapse-bom` before
  `spring-boot-dependencies` (documented).
- **Images:** configures `spring-boot:build-image` with a Java 25 runtime and AOT-cache training
  (`BP_JVM_AOTCACHE_ENABLED=true`); Java 21 runtimes get CDS only. The parent and the Dockerfiles pin G1 for the
  training run and every run of the image (plan 5, D47; kept 2026-11-20): left to the
  JVM, a 512 MB, one-CPU pod gets the Serial collector, whose default 8 MB initial heap can't hold the cache's archived
  heap objects, so the JVM stops at start (measured; `parent.adoc`, *The garbage collector*). When the image's JDK
  moves to a new feature release, the 512 MB, one-CPU smoke test runs with the pin and without it. The pin can go
  once the image JDK is 27 or later, where G1 is the default in every environment (JEP 523), unless the Java 21
  image still needs it. Image memory (`BPL_JVM_THREAD_COUNT`, head room) stays the deployment's choice: the parent
  bakes in no memory defaults, and `parent.adoc` documents them. The archetype adds
  `spring.main.keep-alive=true` where needed, `spring.threads.virtual.enabled=true`, and the service archetype a
  Dockerfile with the same training run; `parent.adoc` documents heap sizing for small pods.
- **AOT profile (plan 5, decided 2026-11-08):** the `process-aot` execution is in the parent's Maven profile `aot`, off
  by default, in that profile's `pluginManagement`, as Boot's `native` profile declares it. Without `-Paot` no
  `process-aot` runs. The image settings stay in the default `pluginManagement`. Invoker projects prove both.
  Confirmed 2026-11-20 (maintainer): the parent doesn't pass `--spring.main.cloud-platform=none` to `process-aot`.
  `parent.adoc` documents it as a per-build flag, and says that spring-cloud-kubernetes 5.0.x doesn't support Spring
  AOT or native images, so a pod's `kubernetes` profile failing processing is the right outcome. Stale AOT code after
  a later plain build stays docs only (`clean`). At the Boot 4.2 upgrade, recheck `parent.adoc`'s
  `server.forward-headers-strategy` paragraph: 4.2 deprecates that key for `server.request.header.forwarded.strategy`,
  and Jetty and Netty weren't checked.
- **Build environment (plan 5):** the Maven property `synapse.build.environment` (default `build`) names the environment
  that `process-aot` and the image's training run use (`TRAINING_RUN_JAVA_TOOL_OPTIONS`). `process-aot` gets it as
  the default profile (`--spring.profiles.active=` and `--spring.profiles.default=…`), never as an active profile, so
  the AOT-generated code activates no profile at run time (3.6; DESIGN 2026-11-08). A service that builds with
  `-Paot`, or builds an image (the training run starts in the build environment with or without Spring AOT), ships
  `application-build.yaml` with the values that are fixed at build time under AOT, equal to every deployed
  environment's, and its library modules' placeholder `config-build.yaml` where a store needs one; a service that does
  neither ships none. The training run exits on refresh, so no database is reached. Without the file the training run
  fails to start, so `synapse-parent`'s `image` profile (`-Pimage package`, which binds `build-image-no-fork` in each
  module that declares `spring-boot-maven-plugin`) checks for it with the Enforcer and fails the build early, in
  `validate`. Only `spring-boot:build-image` invoked on the command line skips that check, and then Paketo's Spring Boot
  buildpack (5.40.5) keeps the partial cache and the image build succeeds, with Synapse's message in its log; a
  Dockerfile's training `RUN` fails `docker build` (`parent.adoc`, *Images*). A `local` environment that turns a
  build-time switch on (gRPC reflection, the API docs) is for JVM runs only.
- **Tests (plan 5):** surefire and failsafe get the Mockito agent (`-javaagent`) when `mockito-core` is on the test
  classpath, as the root build does.
- **Samples and archetypes (plan 5):** every sample inherits `synapse-parent`, as a consumer's project does. The
  archetypes generate projects shaped like `samples/hello-rest` and `samples/reference-bookstore`.
- Parent plugins that serve Synapse's own build are `<inherited>false</inherited>`. The release and snapshot profiles
  (`synapse-release`, `synapse-snapshot`) live in the root build, not in the parent, so consumers never inherit them.

---

## 14. Quality gates and CI
All must pass for a phase to be done; enforced in CI on every pull request unless noted.

1. **Build and tests:** PRs run all unit and integration tests on JDK 25, and unit tests plus one smoke integration
   test per module on JDK 21 (`-Djvm=<jdk21>/bin/java`). The samples, which inherit `synapse-parent` and don't read the
   smoke filter, run no ITs on the JDK 21 pull request leg (`.config/jdk21-pr-excluded-its.txt`); their ITs run on JDK
   25 on every pull request. The full JDK 21 run is nightly. The OpenTelemetry Java agent ITs (the reference
   bookstore's and the service archetype's generated project's) run only under `-Potel-agent`, which also copies and
   checks the agent jar: nightly, in the full JDK 21 run and in a JDK 25 `otel-agent` job, never on pull requests.
   Testcontainers ITs for
   open-source stores and Kafka run on PRs; Oracle and SQL Server ITs run nightly, in the release workflow, and on PRs
   that change those modules. Vendor modules stay thin (dependencies, defaults, context-runner tests) so they meet
   coverage without their database.
2. **Coverage:** per-module JaCoCo floor (80% line, 70% branch, unit + integration merged; samples excluded); at
   least 90% of changed lines covered (diff-cover). A jar module with main sources but no execution data fails the
   build (closes JaCoCo's "skip when no data" gap), and diff-cover fails when reports are missing.
3. **Mutation testing:** PIT nightly on opted-in modules (`synapse-core` and each plugin's error mapping); score ≥ 70%
   (provisional). The nightly job opens an issue on failure. PIT runs unit tests only (`*IT` excluded): integration
   tests are failsafe's, and in PIT's single test JVM a `@SpringBootTest` would reconfigure logging for the unit tests.
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
   - **Known gap (open, logged 2026-11-08; the maintainer chose to fix it later):** the OSV-Scanner PR gate scans less
     than it claims. About 25 poms that depend on reactor `2.0.0-SNAPSHOT` artifacts (every published api, data,
     logging and test module, the archetypes and the tests modules) fail transitive resolution, so the PR scan skips
     them, and the reusable workflow only checks that a results file exists, so the job goes green. `synapse-bom` and
     `synapse-parent` contribute 0 packages, so a CVE pin that regresses in the BOM wouldn't show in the scan either;
     the `bom-guava-pin` invoker test still guards the guava pin. Dependabot still covers the full tree after merge.
     Proposed fix: scan the reactor's CycloneDX SBOM on base and head, and fail closed on an exit code other than 0 or
     1 and on resolution failures. Found in plan 5's Task 20a review, finding I2 (`task-20a-review.md`); DESIGN.md
     *Open questions*.
9. **Native (from P4):** the reference app and the functions sample are built and tested as native images nightly and in the
   release workflow.
10. **Samples and archetypes** build and pass their ITs; archetypes generate a building project (from P1 plan 5).
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
over REST and gRPC on Postgres with Flyway. The samples `hello-rest`, `rest-books`, `grpc-inventory` and
`multi-store`, and the two archetypes (plan 5). **Spring AOT is opt-in in P1** (decided 2026-11-08, maintainer):
`synapse-parent` runs `process-aot` only with its `aot` Maven profile, and only the reference bookstore uses it (its
AOT ITs run nightly). Every module stays AOT-ready (3.3: hints, build-time switch checks, the environment rules under
AOT, and their tests). Full AOT and the native image are in P4.

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
7. `/v3/api-docs` (OpenAPI 3.1), turned on by the environment's file, includes every route with schemas, statuses
   and problem responses; without that setting it is 404.
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
    route's `.notFound` code; a header-mapped output component is absent from the body; `If-None-Match` yields 304 with `ETag`, also on HEAD; a stale `If-Match` yields 412 without changing the resource, including when a concurrent write wins after the operation's read; a version clash without `If-Match` yields 409; a weak `If-Match` yields 412 without calling the operation; a write whose input requires `If-Match` (a non-null `VersionCondition`) yields 428 without it; renaming a record
    component breaks compilation of the binding, not startup.
13. Context: an allow-listed header lands in `RequestContext`, survives `@Async` and virtual threads, appears in MDC;
    with `synapse.context.inbound.trusted=false` it is dropped; baggage over the limits is dropped and logged once.
14. Load and shutdown: with the concurrency limit reached, REST answers 503 with `Retry-After` without running the
    operation; SIGTERM under load completes in-flight requests and rejects new ones within the configured phases.
15. Readiness is UP with a store down unless that store opted in; `/health` is served through Boot's `additional-path`.
16. Protocol-level failures (unknown route, wrong method with `Allow`, unsupported media type, malformed JSON, body
    over the limit, gRPC unimplemented method) return the documented codes in the standard error shape.
17. The app starts with `-Dspring.context.exit=onRefresh` and no database reachable (no remote I/O in bean creation).
18. The reference app's ITs pass on JDK 21 and 25 against Testcontainers Postgres. (Its native-image build and ITs are
    in P4's acceptance.)
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
requests (`.etag`, 304, `If-Match` binding, `STALE_VERSION`, a required `If-Match` with 428, declared per route until
a non-null `VersionCondition` replaced that on 2026-11-07) are built in `synapse-api-rest`,
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
`synapse.environment.non-production-profiles` are removed. Every test in this repository runs as `test`, except a
test that proves an environment's own file (rest-books' `BooksLocalEnvironmentIT` runs as `local`, and plan 5's tests
that start the reference bookstore's jar set `ENV` to `dev`, `build` or `local`), and `samples/rest-books` runs as
`local`. Documented in `environments.adoc`, with `core.adoc`, `rest.adoc` and
`testing.adoc` updated. They cover acceptance items 21 and 3 (the environment part: no `detail` unless
`include-detail=true`). The module files and source names in a native image are not verified yet (DESIGN.md *Open
questions*; P4).

**Done (plan 2c, REST versioning, CORS and OpenAPI):** `VersionCondition` on writes (`If-Match` and `If-None-Match`
bind `Version(v)`, `AnyVersion` for `*` and `null` without the header; a plain type on a write fails startup; a
non-null `VersionCondition` makes the header required, 428 `PRECONDITION_REQUIRED` without it), API
versioning (`.version("2")` serves `/v2/...`, the version is optional, a version no route declares is 404, Boot's
`spring.mvc.apiversion.*` tunes the rest, `use.path-segment` fails startup, deprecation through a bean of Spring's
`ApiVersionDeprecationHandler`, which Boot applies) and CORS (`synapse.api.rest.cors.*`, off by default, the
`synapseCorsFilter` edge filter on REQUEST and ERROR dispatches, `CORS_REJECTED`, management paths left to Boot) are built in
`synapse-api-rest`. OpenAPI 3.1 at `/v3/api-docs` is built in `synapse-api-rest-openapi`, served by springdoc 3.2.0
with Synapse's own schema generator on Jackson 3, off by default and turned on by an environment's file. Documented in `rest.adoc` (*API versioning*, *CORS*), `openapi.adoc`,
`core.adoc` and `environments.adoc`, and shown in `samples/rest-books` (version 2 of the read, CORS for a local front
end, the document). They cover acceptance items 7 (`/v3/api-docs` with every route, its schemas, statuses and problem
responses) and 10 (REST and OpenAPI modules: the new beans' context-runner tests and AOT tests). Spring Security's use
of the `corsConfigurationSource` bean is verified by the security module (2.x, section 11); springdoc, the schema
generator and the CORS filter in a native image by the native build (P4).

**Done (plan 3, gRPC edge):** `synapse-api-grpc` and `synapse-test-grpc` are built and documented (`grpc.adoc`, the
`@SynapseGrpcTest` section of `testing.adoc`, and `core.adoc` and `environments.adoc` updated), and
`samples/rest-books` serves its operations over gRPC too. Core gains `SynapseErrors.ALREADY_EXISTS`, and the REST
edge strips the gRPC attribute names from caller baggage as well. They cover acceptance items 2 (the same operations
over gRPC with no change to operation or model classes; `Timestamp`, wrappers, enums, repeated fields; a `oneof`
needs `.map(...)`), 3 (gRPC: `NOT_FOUND` with `ErrorInfo.reason`, a per-operation override), 4 (gRPC:
`INVALID_ARGUMENT` with `BadRequest`), 5 (gRPC: `INTERNAL`), 6 (gRPC: the `x-correlation-id` metadata), 10 (gRPC
modules), 11 (an expired gRPC deadline fails with `DEADLINE_EXCEEDED` before `execute`), 13 (gRPC), 14 (gRPC:
`UNAVAILABLE` with `RetryInfo` at the limit; in-flight calls complete and new ones are refused on shutdown), 16
(gRPC: `UNIMPLEMENTED` with `METHOD_NOT_FOUND`), 17 (gRPC) and 19 (`@SynapseGrpcTest` is provided; the reference app
uses it in plan 5). gRPC page tokens (plan 3b, proposed), for which `PageQuery` and `PageResult` failed startup in a
gRPC binding, were built in plan 5 (see 6.3). Open: the servlet transport together with `synapse-api-rest`, which fails
startup until the REST edge skips gRPC requests; the native image (`grpc-netty`, protobuf runtime reflection, record
and enum reflection, `writeReplace` on component-reference lambdas; P4); and `inbound.trusted=false` under the
OpenTelemetry Java agent (plan 5 documents that it doesn't restart the trace there; the agent extension's own plan
builds it).

**Done (plan 4, data):** `synapse-data-core`, `synapse-data-jpa`, `synapse-data-jdbc`, `synapse-data-postgres` and
`synapse-test-data` are built and documented (`data.adoc`, the `@SynapseDataTest` section of `testing.adoc`, and
`core.adoc`, `rest.adoc` and `environments.adoc` updated). Named stores (pool, JDBC templates, Flyway migrations and
the schema action in `start()`, JPA or Spring Data JDBC with a transaction manager and repositories per store),
health with the readiness opt-in, error translation (`STALE_VERSION`, `ALREADY_EXISTS`, `DEADLINE_EXCEEDED`,
`UNAVAILABLE`), UUIDv7 ids, auditing, the entity bases, the `ddl-auto` guard and the PostgreSQL vendor are tested on
H2 and, for PostgreSQL, with Testcontainers. They cover acceptance items 8, 10 (data modules: context-runner rules,
AOT hint tests, and an AOT-processed context with JPA and JDBC stores), 15 (the store part: readiness reflects a
store only when it opts in), 17 (stores: no connection while beans are created), 19 (`@SynapseDataTest` is provided;
the reference app uses it in plan 5) and 20 (auditing timestamps and UUIDv7 ids follow the test `Clock`). Under AOT,
a store's name and `primary` are fixed at build time, and a run-time change fails startup. Liquibase waited for the
maintainer's licence decision (decided 2026-11-20: not in 2.0, DESIGN.md §5); native verification of per-store Flyway scanning is in
P4.

**Done (plan 5, samples, archetypes, parent and the reference app):** on the integration base of plans 2c, 3 and 4, REST
and gRPC have one contract (maintainer decision, 2026-11-07): `VersionCondition` over gRPC binds the string `etag` field
with REST's ETag token, responses carry an `etag` on reads and creates, and a stale version the request sent is
`PRECONDITION_FAILED` on both protocols; `CursorCodec` and its keys are in core (`synapse.paging.cursor.*`,
`synapse.paging.max-offset`), so one next-page token, by REST's `Link: next` rule, fills REST's `nextCursor` and gRPC's
`next_page_token`; gRPC pages with AIP-158 fields, `optional` page fields following REST's rules, and `has_next`. The
bound-operation mocks check is shared in `synapse-test`; core's build-time switch check fails an AOT run in one failure
for every switch that differs from the build (`springdoc.api-docs.enabled`, `spring.grpc.server.reflection.enabled`).
`synapse-parent` (section 13) has the pins and their invoker checks, the build environment, Spring AOT opt-in with
`-Paot`, the `image` profile with its build-file check, G1 pinned for the training run and every run of an image
(provisional, maintainer question Q2), and the Mockito agent. The samples found and fixed these in the framework: the
failure line's keys `errorCode` and `errorCategory`, with `correlationId` from the MDC or as a key-value, never both;
context attribute names that collide with Boot's ECS fields or the reserved log fields, or nest under each other, fail
startup; `COMMIT_OUTCOME_UNKNOWN` for a commit that lost its connection; the public store transaction managers, with a
WARN when one is replaced; `SpringDataPages.from(Page)` tells the next page from the offset; and a store's `FactoryBean`
definitions carry their object type, so a JPA store starts beside gRPC. The samples `hello-rest`, `rest-books`,
`grpc-inventory`, `multi-store` and `reference-bookstore` each have a README run against the app; the archetypes
`synapse-archetype-quickstart` and `synapse-archetype-service` each have an IT that generates a project and builds it,
and the service archetype rejects an invalid `serviceName`; the reference bookstore and the service archetype carry the
OpenTelemetry Java agent recipe, with the agent's checksum enforced and an AOT-mode start under the agent failing fast.
Documented in `parent.adoc`, `samples.adoc`, `archetypes.adoc`, `opentelemetry-agent.adoc`, `testing.adoc` (the shared
mocks check, the scenario suite), `core.adoc`, `grpc.adoc`, `rest.adoc`, `data.adoc` and `environments.adoc`. They cover
acceptance items 15 (`/health` through Boot's `additional-path`, with the opted-in store in readiness, and 503 with the
store down: `BookstoreOperationalIT`), 17 (the reference app: the packaged jar starts in `build` with
`-Dspring.context.exit=onRefresh` and no database reachable, as an image's training run does:
`BookstoreExitOnRefreshIT`), 18 (the JVM part: the reference app's ITs pass against Testcontainers PostgreSQL on JDK 25
on every pull request and on JDK 21 nightly (and passed on JDK 21.0.12.1 when this note was written); its native-image
build and ITs are in P4), 19 (the reference app's tests use `SynapseOperations` (`BookRepositoryIT`),
`@SynapseOperationTest` (`BookOperationsTest`), `@SynapseRestTest`, `@SynapseGrpcTest`, `@SynapseDataTest` and
`SynapseArchitecture.forApplication(…)`), 7 (the reference app's document on the JVM: every operation, `If-Match`
required on `PUT` and `DELETE`, 428 documented, and 404 without the setting, `BookstoreOperationalIT`, saved to
`target/api-docs-jvm.json`; the native document's parity with it is in P4), and the parity decision: one scenario suite
(`BookstoreScenarios`) runs every case over REST and gRPC and expects the same outcome, with an `ETag` and a page token
carried from one protocol to the other, against the test context (`BookstoreJvmIT`, every build) and, with `-Paot`,
against the packaged jar in AOT mode (`BookstoreAotIT`, nightly). Open: the native image (P4); the agent extension, with
`inbound.trusted=false` and outbound baggage stripping under the agent (its own plan); the provisional decisions in the
plan's review table and the maintainer questions of its Task 20, for the Task 22 review; the OSV-Scanner PR gate's
known gap (section 14, gate 8); the reference bookstore README's Paketo image; and two DESIGN.md *Open questions*
that are past their due point, for the maintainer to decide or move to a named later phase: Liquibase (due "Before P1
ends"; plan 4 builds Flyway only; decided 2026-11-20: not in 2.0, DESIGN.md §5), and part (c) of "Required preconditions beyond REST" (due in plan 3; parts (a) and
(b) were answered in plan 5): the pipeline doesn't check a non-null `VersionCondition`, so a caller other than an API
plugin can pass `null`. Task 21 re-ran every sample README
against the app and fixed the one drift it found (the agent's JDBC span). Locally, the README's Paketo image could
not be built: the buildpack's download of the Liberica JRE from github.com returned HTTP 504 in Task 21 and again in
the pre-review cleanup (2026-11-08). The Paketo image build was verified on Linux CI on 2026-11-20, in the Nightly
job *Reference bookstore images (build-image, Dockerfile)*, run 44873771200 on the throwaway branch `ci/dry-run-1`.
Still unverified: the README's Paketo log lines and its `image-smoke.sh` run of the Paketo image, as the README
shows them. The Dockerfile image was verified.

**Review fixes (plan 2026-11-13, `plans/2026-11-13-p1-review-fixes.md`):** the fixes from the P1 review, on the
branch `feature/v2-p1-review-fixes` (base 4a4a6e4e). What landed:
- Core: the pipeline answers a `null` required `VersionCondition` `PRECONDITION_REQUIRED` for every caller, one
  violation per component (D6; 5.2); only the operation input's own constraint violations reach the caller, while a
  business or nested input's, a collaborator's or an entity's are `INTERNAL`, logged at ERROR with paths and message
  templates, not interpolated messages, for violations the pipeline finds in the failure or its first 9 causes (not
  suppressed; the cases this misses are in `core.adoc`, *Masking*) (Q11, I3); `@Masked` masks a component that has
  its own serializer (M9); the readiness group's `include` defaults to `readinessState` only when the application
  configures the group (D19; 5.10); `SpringDataPages.pageable` maps sort keys to entity properties and adds
  tie-breakers (D14); the AOT-processing failure names a library-added profile apart from an included one (E2/N1).
- REST: a repeated value for a single-valued query parameter, header or cookie is `must have a single value` (for a
  cookie, the contract follow-up returns to the first copy, 6.2), header
  lines are joined like one line, and only a list header ignores empty items (RFC 9110 §5.6.1.2), while query,
  path and cookie lists keep them as a gRPC `repeated` field does (Q12, I2; maintainer, 2026-11-19); request bodies
  are read through Jackson's public API (M11); the error controller can be proxied (M13); RFC 9110's condition for a
  validator on a `PUT` is cited (Q3).
- gRPC: a stale `etag` is `ABORTED` (D2; 6.3); Synapse's exception handler is ordered after Boot's server explicitly
  (I5); a repeated element's path is built only when it fails (M3); an `int32` `total_size` overflow is documented as
  unlogged (M8).
- Both plugins: startup fails when the REST and gRPC bindings of one operation declare `.notFound`, `.etag` or
  `.sortable` differently, also under lazy initialization for a `GrpcBindings` bean whose `@Bean` method declares
  that type (D10).
- Data: a lost connection is any exception in the chain that says so, with Spring's resource-failure types decided by
  the first state below them, and class 53 `UNAVAILABLE` but never an unknown commit outcome (M1); stores honour Boot's
  `FlywayConfigurationCustomizer`, `Callback` and `JavaMigration` beans (Q5, M4); the Boot JPA beans stores ignore get
  a startup WARN, and `data.adoc` lists the beans stores honour (Q5, M5).
- Archetypes: generated projects ship the Maven Wrapper (D31b).
- Docs and spec: `-Paot` is labelled experimental until P4 (T9; 3.3); the replaceable-bean rule is in 3.5 (T10);
  `grpc.adoc` says `has_next` is the only signal for a page capped by `synapse.paging.max-offset` (D12); `data.adoc`
  says how a PostgreSQL store goes without migrations (D21b); and the reviews' parked Minors.

The provisional choices are the plan's decisions R1-R20, plus the controller's rulings in the review log (the
reconciled lost-connection rule for M1, the conditional readiness default for D19, the lazy-initialization filters
for D10, and R19's skip of M10 to P6). The maintainer's questions they raised are in DESIGN.md §6.

Verified locally (Windows, Docker on): at 206d0df0 the JDK 25 `CI=true ./mvnw clean verify` passed (BUILD SUCCESS,
about 18 minutes), and so did the JDK 21 pull request leg (BUILD SUCCESS, about 12 minutes); the branch's base,
4a4a6e4e, passed its baseline build too. Not verified, because only Linux CI or a later run can check it, or because
no run has so far:
- the generated `mvnw`'s executable bit: the archetype ITs assert it only on a file system with POSIX permissions, so
  Windows runs skip it and Linux CI checks it;
- the post-generate scripts' warning when the bit can't be set, and the archetype ITs' timeout branch for
  `mvnw --version`: no run exercises either, Linux CI included, since there `setExecutable` succeeds and the wrapper
  answers in time;
- `../mvnw spring-boot:run` in a generated service project (the service archetype README's *Run it*): never run;
- the quickstart archetype README's *Run it* commands in a generated project, `./mvnw package -DskipTests` then
  `ENV=local java -jar target/<artifactId>-<version>.jar`, and `./mvnw spring-boot:run`: not run with the wrapper
  (this branch ran only `./mvnw verify` in a generated quickstart);
- the Paketo image (`spring-boot:build-image`): its build failed when the buildpack's download of the Liberica JRE
  from github.com returned HTTP 504 (plan 5 Task 21 and its pre-review cleanup), so the bookstore README's Paketo log
  lines and its `image-smoke.sh` run of that image are unverified; the Dockerfile image was verified;
- R16: `-Dspring-boot.aot.jvmArguments=-Dspring.main.cloud-platform=none` was checked against the 4.1.2
  `process-aot` goal page and the plugin's descriptor, not run on a build agent inside a cloud platform;
- the nightly-only jobs (the full JDK 21 leg and `aot-reference`), which run only once the branch is pushed.

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
- Plan 2c (REST versioning, CORS, OpenAPI)
  ([plans/2026-11-05-p1-rest-versioning-cors-openapi.md](plans/2026-11-05-p1-rest-versioning-cors-openapi.md)):
  `VersionCondition` for `If-Match`/`If-None-Match` on writes (first), API versioning, CORS (including the 405/OPTIONS
  fallback mapping), and OpenAPI in `synapse-api-rest-openapi`. It covers acceptance 7. Done (see the *Done (plan 2c)*
  note above).
- Plan 3 (gRPC edge) ([plans/2026-11-05-p1-grpc-edge.md](plans/2026-11-05-p1-grpc-edge.md)): `synapse-api-grpc`,
  `synapse-test-grpc` and gRPC in `samples/rest-books`. It covers acceptance 2, 3 (gRPC), 4 (gRPC), 5 (gRPC),
  6 (gRPC), 10 (gRPC modules), 11 (the expired gRPC deadline), 13 (gRPC), 14 (gRPC), 16 (gRPC), 17 (gRPC) and 19
  (`@SynapseGrpcTest` is provided). Carried in:
  - The REST edge's Synapse fields include only REST attribute names; with both plugins, include
    `synapse.api.grpc.context.metadata` names so trusted-mode REST callers can't set gRPC attribute baggage
    unvalidated.
  - Server reflection off by default through a Synapse default on Boot's key at lowest precedence (6.3).
  - `STALE_VERSION` → ABORTED (plan 2b).

  Done (see the *Done (plan 3)* note above).
- Plan 3b (gRPC page tokens), proposed by plan 3 and needed by plan 5's `SearchBooks` over gRPC: `PageQuery` and
  `PageResult` over gRPC with AIP-158 fields, and whether `CursorCodec` moves to core for them (plan 2b left this to
  plan 3). Not a plan of its own: plan 5 built both on its integration base (see the *Done (plan 5)* note above).
- Plan 4 (data) ([plans/2026-11-05-p1-data-postgres.md](plans/2026-11-05-p1-data-postgres.md)): `synapse-data-core`,
  `-jpa`, `-jdbc`, `-postgres` and `synapse-test-data` (8.1–8.2, the store part of 5.10, `@SynapseDataTest`).
  It covers acceptance 8, 10 (data modules), 15 (stores), 17 (stores), 19 (`@SynapseDataTest` is provided; the
  reference app uses it in plan 5) and 20 (auditing and ids). Liquibase waited for a maintainer decision (not in 2.0, decided 2026-11-20). Done (see
  the *Done (plan 4)* note above).
- Plan 5 (archetypes, parent, samples, reference app)
  ([plans/2026-11-07-p1-reference-app.md](plans/2026-11-07-p1-reference-app.md)). It runs on a local integration
  base that merges plans 2c, 3 and 4 (its Task 1), because they are not on `main` yet. Its *Decisions taken for the
  maintainer's review* table records every call it made provisionally.
  - On the integration base, for REST and gRPC parity (maintainer decision, 2026-11-07): `VersionCondition` over gRPC
    as a string `etag` field carrying REST's ETag token (AIP-154), and responses' `etag` on reads and creates, where
    REST sends an `ETag`; a stale version the request sent is `PRECONDITION_FAILED` on both protocols, as REST already
    answers; `CursorCodec` moved to core behind a protocol-neutral API, with its keys and the offset cap under
    `synapse.paging.*`, so both protocols share one cursor; gRPC page tokens (AIP-158), which plan 3 left to a plan 3b.
  - The bound-operation mocks check of `@SynapseRestTest` and `@SynapseGrpcTest`, shared in `synapse-test`.
  - `synapse-parent` (section 13): the runtime pins repeated from `synapse-bom` (the guava pin is already in the BOM,
    from plan 3), plan 3's invoker check extended so the root, the BOM and the parent agree on every pin, and the
    effective-POM invoker test; the build environment for `process-aot` and the training run; `build-image` on Java
    25 with the AOT cache; the Mockito agent for tests.
  - Spring AOT opt-in (maintainer, 2026-11-08): `process-aot` only in the parent's `aot` profile; only the reference
    bookstore keeps the build environment's files and shows the opt-in, and its AOT ITs run in a nightly job with
    `-Paot` (JDK 25 and 21), not on pull requests. The other samples and the quickstart archetype have no build
    environment file. The service archetype generates them for its Dockerfile's AOT-cache training run, each with a
    comment that it exists only for that (maintainer, 2026-11-08). Both archetypes' READMEs say how to opt in.
  - Under AOT, `springdoc.api-docs.enabled` decides whether beans exist: in AOT mode startup fails when it differs
    from the build's value, as gRPC reflection does, and one failure names every such switch that differs.
  - Samples, each with a README verified against the running app, tests and a place in CI: `hello-rest` (quick
    start), `rest-books` (the REST tour, in memory; its gRPC part moves out), `grpc-inventory` (gRPC first, proto in a
    contract module), `multi-store` (two named stores, JPA and JDBC), and `reference-bookstore` (contract, service
    and data modules; one scenario suite over REST and gRPC; Postgres with Flyway, `compose.yaml`); a
    `samples/README.md` index.
  - The archetypes `synapse-archetype-quickstart` and `synapse-archetype-service`, each with an IT that generates a
    project and builds it.
  - The image (`build-image` with the AOT cache, a sample Dockerfile, heap sizing docs), nightly.
  - The OpenTelemetry Java agent recipe: `management.opentelemetry.map-environment-variables` and `-Dotel.*`
    guidance, a Maven profile that attaches the agent, the `GlobalOpenTelemetry` `OpenTelemetry` bean, and an agent
    IT.
  - Not in plan 5: the Synapse agent extension (`synapse-opentelemetry-agent-extension`), with `inbound.trusted=false`
    (trace restart, baggage dropped) and outbound baggage stripping under the agent; its open questions are in
    DESIGN.md *Open questions*.
  - Not in plan 5 either: the reference app's native image (its Task 16), moved to P4 by the maintainer on
    2026-11-08.

  Done (see the *Done (plan 5)* note above).
- Contract follow-up ([plans/2026-11-20-p1-followups-contract.md](plans/2026-11-20-p1-followups-contract.md)),
  the first of the four follow-up plans (then data, observability, API surface): a nested rejection is `INTERNAL` on
  every thread (5.2); parity by major version, one violation path format with wire names, an unsent collection binds
  empty, and required scalars need presence over gRPC (6.1); a repeated cookie takes the first copy, Spring MVC's
  handlers answer before Synapse's resolver, and the capped page's token (6.2); a hand-written gRPC service's status
  keeps only its code (6.3); and the docs-only items (the weak-tag reason, update responses, `If-Match` on a missing
  resource, the `ALREADY_EXISTS` override, the cursor-key rotation guideline). `TRANSACTION_CONFLICT` and its status
  mapping are in the data follow-up.
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

**Downstream gRPC statuses (to design in the P2 plan; maintainer, 2026-11-20, decision 24).** In P1 a hand-written
gRPC service's status keeps only its code (6.3), so a downstream `INVALID_ARGUMENT` or `NOT_FOUND` that the service
forwards still blames Synapse's caller. Google's design guide asks a server to move the blame ("a server that
receives an INVALID_ARGUMENT error from another service should propagate an INTERNAL to its own caller"). P2 adds a
client-side origin marker: a Synapse global client interceptor on the channels Spring gRPC creates attaches a marker
cause to every non-OK status it receives, so the stub's `StatusRuntimeException` carries it. The server side then
remaps a marked status: the caller-blaming codes (`INVALID_ARGUMENT`, `NOT_FOUND`, `ALREADY_EXISTS`,
`FAILED_PRECONDITION`, `OUT_OF_RANGE`, `UNAUTHENTICATED`, `PERMISSION_DENIED`) become `INTERNAL`, `UNAVAILABLE` and
`DEADLINE_EXCEEDED` are kept, and an unmarked (deliberate) status keeps its code. It needs no heuristics; it doesn't
cover stubs on channels the application builds by hand, which the docs say. P2's own clients throw `SynapseException`
anyway.

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

### P4: GraphQL, functions, scheduling, REST codecs (M4)
**Scope:** `synapse-api-graphql` (6.4), `synapse-api-function-aws` (6.5; the plan decides the binding and operating
model), `synapse-scheduling` (section 10), REST XML codec and `BinaryContent` (6.2), `@SynapseGraphQlTest`. Samples
`public-rest-api` (JWT from Boot's resource-server starter, problem+json, OpenAPI), `graphql-bff`,
`serverless-functions` (AWS), `scheduled-and-batch`. `synapse-security` is not in P4: the maintainer deferred it
whole to 2.x on 2026-11-20 (section 11).
- One native plan, with the functions sample (moved from P1 plan 5 by the maintainer on 2026-11-08, so the GraalVM CI
  and the nightly workflow are built once for both):
  - The reference app's native image (nightly and in the release workflow) and its checks: `grpc-netty`, protobuf,
    springdoc and swagger-models, the schema generator, the CORS filter, Hibernate entity bases, per-store Flyway
    scanning, and, for `META-INF/synapse/config*.yaml`, one URL per jar from `ClassLoader.getResources` (a native
    image's `resource:` URLs count as local there, with the origin `native image`), Boot's `Config resource` source
    names and the service's `application-<env>.yaml` (DESIGN.md *Open questions*).
  - Full Spring AOT (opt-in in P1, decided 2026-11-08): the plan decides how far `process-aot` goes beyond the
    parent's `aot` profile (for example on by default, or with the native profile, which today gets the build
    environment's arguments only together with `aot`), and where the build environment's files are then required.
  - Direction (confirmed 2026-11-20, maintainer): P4's AOT effort goes into the native image, which needs full Spring
    AOT and keeps option A's rules (the build environment as the default profile, no active profile during
    processing, the baked-profile check, the build-time switches). JVM startup is served by the image's AOT cache
    without Spring AOT, so `-Paot` on the JVM isn't extended or made the default until Spring's modular AOT
    (spring-framework #35471) shows whether bean registration can be left out. Option A assumes Spring's AOT
    generators don't retain *default* profiles, which no Spring doc promises: `BakedProfiles` and the nightly
    `BookstoreAotIT` are its canary, rechecked on every Framework or Boot minor upgrade.

**Acceptance:**
- A shared operation contract test suite runs the P1 `BookErrors` operations through GraphQL and the function adapter.
- GraphQL: depth/complexity limits reject oversized queries; introspection is off by default; batched fields make one
  operation call per response; a `CommandOperation` under `Query` fails at startup.
- Functions: only bound operations are exposed; routing by header/expression is impossible (negative test); local
  invoker tests pass; the functions sample builds and passes as a native image (nightly).
- Native: the reference app builds and passes its ITs as a native image (nightly and in the release workflow); with
  the `resource:` URL rule (module configuration is read from a native image, origin `native image`) and Flyway's
  native resource scanning.
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

**Before the first P5 vendor** (decided 2026-11-20, maintainer): the SQLState class 53 rule (insufficient resources →
`UNAVAILABLE`) moves from `synapse-data-core` behind `DataStoreVendor`, since class 53 is PostgreSQL's and DB2's, not
the SQL standard's; data-core keeps only the standard states. Each vendor module recognises its own driver's
transaction-conflict codes through `DataStoreVendor.transactionConflict` (vendor codes collide: 1205 is a deadlock
victim on SQL Server and a lock wait timeout on MySQL): SQL Server 1205 and 1222, Oracle ORA-00060, ORA-08177,
ORA-00054 and ORA-30006, MySQL 1213, 1205 and 3572. Each vendor lists the driver properties that would change where
or as which login user a store connects (rejected) and those that apply session settings (allowed with a WARN), as
PostgreSQL's `options` is. Re-check on each vendor that `TRANSACTION_CONFLICT`'s promise holds (Oracle rolls back only
the statement; Spring rolls back the transaction when the exception leaves it).

### P6: GA hardening (2.0.0)
**Scope:** `synapse-utilities`; archetypes generated from the reference app; docs complete (every module, property
tables, error tables, compatibility matrix); v1 → V2 migration guide covering every module; API-stability gates
active (japicmp baseline, metadata diff); security review with a threat model per plugin; reference sample complete
across all plugins; `SECURITY.md` and the v1 README announce the 0.4.x end-of-life date (GA + 6 months); release
2.0.0. Also in P6, recorded 2026-11-20:
- The API pass reviews every `@Incubating` (3.5) and adds an ArchUnit gate over every published package outside
  `..internal..`: no public or protected member, parameter, return type, supertype or type argument resolves to
  `..internal..` (plan-5 review M1; today two hand-listed tests cover the REST and gRPC bindings only).
- The `archetype-drift` check compares normalised poms too (without the parent, the coordinates and the
  `maven.*.skip` properties), and derives the service file list as every file minus the domain files (plan-5 review
  M4), or the archetypes are generated from the reference app, which removes the drift.
- The plan-5 review's other Minors: the smoke-gate leniencies (M6), the paging texts kept in three places (M7), the
  `postgres-image-pin` regex (M9), and a sample that shows masking and payload logging (B-M6, after the masking
  follow-up settles what masking promises).
- Still to do before GA: environment files recognised by their property source's `Origin` rather than Boot's source
  names (I4, with the Boot-internals canaries), and external configuration (ConfigMaps, `configtree:`, `optional:`
  imports, an empty external file) counting as an environment's file (Q6; DESIGN.md *Open questions*).

Also in P6, the items plan 4 parked as "later" (confirmed 2026-11-20, maintainer): the `AuditorAware` back-off, the
store qualifier rules, an application `JdbcCustomConversions`, JPA naming strategies and customizers (the startup
WARNs of 2026-11-20 cover the beans stores don't use), the store health check on a platform thread,
`.notFound(code, field)`, the gRPC servlet transport, Swagger UI in the BOM, snakeyaml, the PostgreSQL 15 default
`database-version`, and plan 2c's Task 9.

**Acceptance:** all gates green including nightly; every module documented; every sample and archetype built in CI;
the migration guide covers every row of the module map in DESIGN.md.

---

## 16. Decided in phase plans
These are settled at the start of the named phase (details in DESIGN.md *Open questions*): default sizes for
the GraphQL concurrency limit and deadlines (P4) and client settings (P2); the client readiness
signal (P2); WireMock's Boot 4 fit (P2); Kafka retry defaults, subscriber `max.poll.records` and timeout defaults (P3);
the AWS function binding and Lambda operating model, GraphQL limit values, ShedLock's Boot 4 fit (P4); the archetype
set (P6).
