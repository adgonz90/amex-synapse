# Synapse V2 spec — 2026-10-27

Status: **Approved for implementation** (decisions agreed by the maintainers on 2026-10-27). Implement phase by phase; each phase starts with a written plan that a maintainer approves.

## Summary
Synapse v1 (0.4.x) is a set of Spring Boot 3 libraries that let teams build layered services quickly with prescriptive conventions. In practice it has four overlapping web stacks, no Spring Boot auto-configuration, data modules that re-implement Boot, untested "new" modules and several data-loss and security bugs (see *v1 facts relied on*).
V2 (`2.0.0`) is a ground-up rewrite on **Spring Boot 4 / Spring Framework 7**, built with JDK 25 and **running on Java 21+**. It keeps Synapse's original goals and layered structure.
The central change: **services are protocol-agnostic operations**, and **API plugins** (REST, gRPC, GraphQL, cloud functions; later reactive REST) expose them by adding a starter plus a small binding.
Every module ships as real Boot auto-configuration and is proven by unit tests, `ApplicationContextRunner` tests and Testcontainers integration tests under strict CI gates. Defaults are secure.

## Goals / Non-goals

### Goals (2.0.0 GA must deliver all)
1. **Protocol plugins.** A developer writes an operation once, in a service module that has no protocol dependencies. They expose it over REST (JSON/XML/protobuf), gRPC, GraphQL or AWS/GCP functions by adding the plugin starter and at most a few lines of binding.
2. **Correct by construction.** Every module is a Boot auto-configuration that backs off to user beans and is covered by context-runner tests and real-infrastructure ITs. No configuration exists that is never triggered.
3. **Secure by default.** No internals or stack traces leak to callers, sensitive data is masked in responses and logs, clients have sane timeouts and SSRF protection, and the supply chain is signed and scanned.
4. **Minimal developer work, easy to update.** One parent POM or BOM aligned with Spring Boot. Conventions instead of configuration. Semver with API-compatibility checks, an automated changelog and a migration guide.
5. **True to the original structure.** The layer and module types stay: service, business, subscriber, client, data, publisher, framework/core, utility. So do the prescriptive conventions: consumer project layout, archetypes and the error model.

### Non-goals
- Re-implementing anything Spring Boot already auto-configures (DataSource, connection factories, Kafka clients, Jackson mapper, gRPC server, observability exporters). Synapse only *customizes* Boot.
- Source or binary compatibility with 0.4.x APIs. A migration guide replaces compatibility.
- Porting v1's deprecated or duplicate stacks (`service-rest`, `service-reactive-rest`, `api-rest-*`, `service-imperative/reactive`).
- SOAP client support.
- Forcing an authentication mechanism (an optional security starter exists) or a logging backend on consumers.
- v1's date/number/telephone/email/string/collection/URL utility wrappers, `CryptoUtil`, `DeepCopyUtils`, `BaseModel`, and global `BigDecimal`/`Double` serializers.
- Framework code that needs Java 22+ APIs, or multi-release JARs.
- Field-level encryption (deferred; see Open questions).

## Decisions
| Area | Decision | Rationale | Alternatives rejected |
|---|---|---|---|
| Release line | New major **2.0.0** under groupId `io.americanexpress.synapse`, with new artifact names. 0.4.x gets CVE-only patches for **12 months after 2.0.0 GA**, then EOL. Milestones `2.0.0-M1…` ship after each phase. | Clean slate; the "V2" name matches the version. | New groupId (splits the brand); in-place incremental migration (drags v1 design along); 1.0.0 (confusing next to "V2"); a 6-month tail |
| Java | Build with **JDK 25**, compile with `--release 21`, no multi-release JARs. CI tests on JDK **21 and 25**. | Runs on both LTS lines. The only notable loss is final Scoped Values; use Micrometer context-propagation instead. Runtime gains (JEP 491 virtual threads without pinning, compact headers, AOT cache) still apply on 25. | Java 25 only; Java 17; MR-JARs |
| Platform | **Spring Boot 4.x**: latest GA at implementation time (scaffolded on **4.1.2**), Spring Framework 7, Jakarta EE 11, **Jackson 3** (`tools.jackson`), JSpecify nullness. | Latest platform; Boot 4.1 adds gRPC auto-config and HTTP-client SSRF mitigation. | Boot 3.5 |
| Build tool | **Maven 3.9.x + Maven Wrapper** (scaffolded on 3.9.16; 3.10.0 exists and can be adopted later). | Official Sonatype `central-publishing-maven-plugin`; `actions/setup-java` handles settings/GPG; makes a consumer parent POM and Maven archetypes possible; enterprise familiarity. | Gradle Kotlin DSL (faster cached builds, but no consumer parent or archetypes, and third-party Central publishing) |
| CI/CD | **GitHub Actions** for V2 (`main`). CircleCI stays only for v1 maintenance on `develop`. Publish to Maven Central (Central Portal). | Maintainer decision. | CircleCI |
| Branching | **Trunk-based on `main`**: short-lived `feature/*`/`fix/*` branches, releases as tags, optional short-lived `release/x.y` for hardening. **`develop` becomes the 0.4.x maintenance line** (optional later rename to `0.4.x`). The repository had no `main` branch (as of 2026-10-27), so `main` is created from `develop` at 0.4.29 and V2 starts with a commit that removes the v1 modules; history is kept, no orphan branch. | Most widely used OSS model (e.g. Spring Boot, Kubernetes, Node). Avoids wrong-base PRs; release-please, Dependabot and CodeQL all assume one default branch. | Gitflow; long-lived `v2` branch; separate repository |
| Release automation | **Conventional Commits + release-please.** A release PR carries the version bump and CHANGELOG. Merging creates a tag `v*`, and the tag workflow builds, GPG-signs, publishes to Central, attaches a CycloneDX SBOM and creates a GitHub build-provenance attestation. Snapshots publish from `main`. | Automated, auditable releases with no commits from CI. | maven-release-plugin via `workflow_dispatch`; JReleaser |
| Programming model | **Imperative first**: Spring MVC + virtual threads. A **reactive plugin is the last phase before GA (P6)** and must be part of 2.0.0. | Halves the surface area early; reactive teams are still served at GA. | Both from day 1; reactive first; never |
| Operation contract | **One class per operation** implementing `SynapseOperation<I, O>` (record in/out, one `execute` method). It has no protocol imports. The framework applies validation, observation, correlation and error translation around **every** invocation, whoever the caller is. | Protocol-agnostic unit that maps 1:1 to functions and GraphQL fields and groups naturally into gRPC services and REST resources. Successor to v1's `BaseService.execute`. | Annotated multi-method service classes; plain beans with no contract |
| API plugins | **Protocol-agnostic operations + one adapter starter per protocol.** RPC-style protocols (gRPC, GraphQL, functions) bind by name or group. REST uses a declarative DSL. HTTP/protobuf and XML are REST codecs, not separate protocols. | Delivers the original "plugin" intent without forcing REST semantics onto RPC. | Per-protocol base classes (v1 style); contract-first codegen |
| REST binding | **Java DSL bean** (`RestBindings`) in the application/API layer, compiled to Spring MVC functional routes. Synapse generates OpenAPI from it. **Escape hatch:** plain `@RestController` classes that inject operations get the same cross-cutting behavior. | The HTTP surface is visible in one place and type-checked, with no HTTP leaking into services. Spring-native routing, security and observability. | `@HttpExchange` interface (more magic); controllers only (boilerplate) |
| Config model | **Spring Boot auto-configuration** in every feature jar: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `@ConditionalOn*` back-off, typed `@ConfigurationProperties` under **`synapse.*`**, and `spring-boot-configuration-processor` metadata. No `@ComponentScan`/`@Import` of framework packages is required of users. | Boot convention; fixes v1's manual wiring. | Explicit `@Enable…`/`@Import`; disabled-until-enabled flags |
| Artifacts | **Feature jars** (code + auto-config in one jar, e.g. `synapse-api-rest`) + **`synapse-bom`** + **`synapse-parent`** (extends `spring-boot-starter-parent`; carries consumer build conventions). | Fewest artifacts; still Boot-idiomatic; the parent is the "consistent standards" lever. | Spring-style split autoconfigure/starter (about 2x artifacts); BOM only |
| BOM contents | `synapse-bom` manages Synapse artifacts plus the third-party libraries Synapse adds beyond Boot (e.g. springdoc, Spring Cloud Function adapters, gRPC/protobuf if not Boot-managed). It does **not** import Boot's BOM. Consumers either use `synapse-parent` (which brings Boot) or import `spring-boot-dependencies` + `synapse-bom`. Each Synapse minor documents its supported Boot version. | Avoids two sources of truth for Boot versions. | A BOM that imports Boot |
| Error model | `SynapseException(ErrorDetails details, Object... args)` (plus a cause overload). Teams implement **`ErrorDetails`** as their own enum: `code()`, `messageTemplate()` (`{0}` placeholders) and `category()`. **`ErrorCategory`** is protocol-neutral (domain semantics, not HTTP). Each plugin maps category → protocol status. Overrides are API-side and keyed by error code, globally or per operation. Precedence: per-operation > global > category default. Services never see HTTP, gRPC or GraphQL types. | A design proven in internal use at American Express, minus the separate status-mapping file; keeps the layering clean. | Separate mapping file per protocol; HTTP status on the interface; v1 `ErrorResponse` shape; Spring defaults only |
| Error exposure | Responses always include the code, a generic title (from the category), the correlation id and validation field violations. The formatted detail message is included only when `synapse.errors.include-detail=true` (default **false**). Full detail and cause are always logged server-side. Never stack traces. | Prod-safe; mirrors Boot's `server.error.include-message`. | Per-code exposure flag; always show detail |
| HTTP error format | **RFC 9457** `application/problem+json`, with extension members `code`, `correlationId` and `violations[]`. | Standard, and supported by Spring `ProblemDetail`. | v1 ad-hoc JSON |
| Logging | Synapse jars depend only on the **SLF4J 2 API** (no `slf4j-ext`/XLogger, no backend). An opt-in **`synapse-logging-log4j2`** starter (included by `synapse-parent`/archetypes) swaps out Boot's logging, enables async loggers, uses Boot structured JSON logging and applies `@Masked` redaction. Logback remains usable. Jars ship **no** logging config files. | Keeps the team's Log4j2 preference without locking consumers in. | Logback default; mandatory Log4j2 (v1 approach) |
| Observability | Micrometer **Observation** around every operation, binding, client call and Kafka record. **OpenTelemetry/OTLP** export for traces and metrics through Boot's own support. A correlation id is propagated across REST/gRPC/GraphQL/functions/clients/Kafka. | One model across protocols. | Micrometer only |
| Security | **Secure defaults, pluggable auth.** No internals in responses; sensitive-header and `@Masked` redaction in logs; client timeouts and SSRF `InetAddressFilter`; request size limits; validation on every operation; minimal actuator exposure. AuthN/Z through Spring Security, with an optional **`synapse-security`** starter (OAuth2 resource server/JWT defaults) that is never forced. Supply chain: signed artifacts, provenance attestation, SBOM, dependency and vulnerability scanning, CodeQL, actions pinned by SHA. | Safe without blocking non-OAuth shops or local development. | A security starter on by default; leave it all to consumers |
| Data layer | **Per-vendor modules kept**, as **thin layers over shared per-technology modules**. `synapse-data-jpa`/`-jdbc` (and `-r2dbc` in P6) hold the shared behavior: auditing, BaseEntity, conventions. Vendor modules (`synapse-data-postgres`, …) add the driver, vendor defaults and Testcontainers test support. **Boot creates every DataSource/connection factory.** | Keeps the familiar v1 module names without v1's 11 hand-wired copies. | Per-technology only; drop data modules |
| Datastores (GA) | First-class and Testcontainers-tested: PostgreSQL, MySQL/MariaDB, Oracle, SQL Server, DB2, MongoDB, Redis, Cassandra, Couchbase. Commercial databases (Oracle/SQL Server/DB2) run in the **nightly** CI job and the release job, not on every PR. | Maintainer decision; heavy images kept off the PR path. | Subset only |
| Clients (GA) | `synapse-client-rest` (RestClient + Spring 7 HTTP interface clients via `@ImportHttpServices`), `synapse-client-grpc`, `synapse-client-graphql`; reactive REST client in P6. **SOAP dropped.** | Covers outbound protocols with the same defaults as inbound. | Keep SOAP |
| Messaging | `synapse-publisher-kafka` and `synapse-subscriber-kafka` on Boot's Kafka auto-config. Subscribers bind topics to operations. Per-subscriber `synapse.kafka.subscribers.<name>.delivery` = `at-least-once` (**default**: commit after success, backoff retries, then DLT) \| `at-most-once` \| `exactly-once` (Kafka transactions + `read_committed`). The publisher always has idempotence on and surfaces send failures (no fire-and-forget). | Safe default with flexibility; fixes v1's data-loss bugs. | Required explicit choice; at-least-once only |
| Utilities | **Keep proven value only.** JSON conventions as **Jackson 3 builder customizers** in `synapse-core` (`synapse.json.naming=camel\|snake`, ISO-8601 dates; never replace Boot's mapper). `@Masked` annotation + maskers in `synapse-core` (shared by JSON output and log redaction). Validators (`@OneOf`, `@AnyOf`, `@RequireIfPresent`, date-format check) in `synapse-utilities`. Classpath JSON fixture loaders in `synapse-test`. Everything else is dropped. | Preserves v1's prescriptive value without its global side effects. `@Masked` moved to core (not utilities) because logging and JSON both need it. | Port everything; drop everything; keep formatting serializers |
| Samples | One **canonical multi-module reference app** (bookstore) plus **use-case samples**: public REST/JSON API (OAuth2, problem+json, OpenAPI), internal gRPC microservices, event-driven Kafka, serverless functions (AWS + GCP), GraphQL BFF. All are built and integration-tested in CI and never published to Central. | Teaches by scenario; replaces v1's ~38 drifting sample modules. | One sample per module; template repo |
| Archetypes | Regenerated from the reference app in P7, and CI-verified by generating a project and building it. | v1 archetypes target the deprecated stack. | Template repo only |
| Docs | Markdown docs site in the repo (tool chosen in P0: MkDocs or Antora), versioned per release, published to GitHub Pages by CI. A per-module reference page includes a property table generated from configuration metadata. The REST plugin produces OpenAPI; gRPC server reflection is enabled in dev profiles. | v1 READMEs drifted. | READMEs only; Javadoc only |
| Quality gates | **Strict**; see *Quality gates*. | Prevents v1-style drift. | Moderate; minimal |
| Delivery | Phases P0–P7 as vertical slices (see *Delivery plan*); P1 proves the plugin model with REST + gRPC + Postgres. | De-risks the central design first. | — |

## Architecture

### Repository layout on `main` (V2)
```
synapse/                               (root aggregator; internal build parent = conventions for Synapse's own modules)
  bom/synapse-bom
  parent/synapse-parent                (consumer parent; extends spring-boot-starter-parent)
  core/
    synapse-core                       operations, invoker, errors, correlation, JSON customizers, @Masked
    synapse-test                       test support (operation test harness, fixtures, protocol test helpers, Testcontainers helpers)
    synapse-utilities                  validators (P7)
    synapse-logging-log4j2
    synapse-security                   optional OAuth2 resource-server defaults (P4)
  api/
    synapse-api-rest                   MVC + RestBindings DSL + problem+json + OpenAPI; JSON default, XML/protobuf codecs (P4)
    synapse-api-grpc
    synapse-api-graphql                (P4)
    synapse-api-function-aws           (P4)
    synapse-api-function-gcp           (P4)
    synapse-api-rest-reactive          (P6)
  client/
    synapse-client-rest, synapse-client-grpc, synapse-client-graphql (P2); synapse-client-rest-reactive (P6)
  data/
    synapse-data-jpa, synapse-data-jdbc (P1), synapse-data-r2dbc (P6)
    synapse-data-postgres (P1); -mysql, -oracle, -sqlserver, -db2, -mongodb, -redis, -cassandra, -couchbase (P5)
  publisher/synapse-publisher-kafka    (P3)
  subscriber/synapse-subscriber-kafka  (P3)
  archetype/                           (P7)
  samples/                             (not deployed)
    reference-bookstore/ (multi-module), public-rest-api/, internal-grpc/, event-driven-kafka/, serverless-functions/, graphql-bff/
  docs/
```
Java packages: `io.americanexpress.synapse.<area>.<feature>` (e.g. `…synapse.api.rest`). Every package is `@NullMarked` (JSpecify).

### Dependency diagram
```
                    synapse-bom   synapse-parent (-> spring-boot-starter-parent)
                                         |
  consumer app ------------------------------------------------------------+
      |            |              |                |               |       |
 synapse-api-rest  synapse-api-grpc  synapse-api-graphql  synapse-api-function-*   synapse-subscriber-kafka
      \____________|______________|________________|_______________/        |
                                  |                                         |
                             synapse-core  <-------------------------------+
                                  ^        ^                   ^
                                  |        |                   |
              synapse-client-*  synapse-data-jpa/jdbc/r2dbc  synapse-publisher-kafka
                                     ^
                     synapse-data-postgres / -mysql / -oracle / ...

 synapse-logging-log4j2 -> synapse-core (for @Masked)      synapse-security -> spring-boot-starter-oauth2-resource-server
 synapse-test -> synapse-core (test scope for consumers)
 Rule: no api-* module depends on another api-* module; synapse-core has no protocol dependencies (no spring-web, grpc, graphql).
```

### Consumer project layout (prescribed; same spirit as the v1 README)
```
app-name/
  app            bootable module: depends on service-* + chosen synapse-api-* plugins; holds RestBindings / gRPC / GraphQL bindings
  service-*      operations (SynapseOperation beans) — depends only on synapse-core (+ business/client/data/publisher modules)
  business-*     optional shared business logic (plain jars, synapse-core only)
  subscriber-*   Kafka subscribers binding topics to operations
  client-*, data-*, publisher-*
```
The architecture rule "`service-*` must not depend on any `synapse-api-*`, `spring-web` or gRPC/GraphQL artifact" is enforced in the reference sample with an ArchUnit test, and the same test is generated by the archetype.

### Core programming model (`synapse-core`)
- `SynapseOperation<I, O>`: `O execute(I input)`. `I` and `O` are typically records. Returning `Optional<T>` means "may be absent" (REST: 404 by default when empty). `Void`/`null` means no content.
- Operation name: the bean name in kebab-case by default; overridable with `@OperationName("create-book")`. Optional `@OperationGroup("books")` groups operations into a gRPC service or REST resource and drives documentation.
- **Invocation pipeline:** applied by wrapping every `SynapseOperation` bean (AOP proxy or a decorator registered by auto-config), so plugins, `@RestController` escape hatches, subscribers and tests all get identical behavior:
  1. Correlation context is established.
  2. Jakarta Validation runs on `I`; failure produces `INVALID_INPUT` with violations.
  3. A Micrometer Observation (`synapse.operation`, low-cardinality tag `operation`) wraps the call.
  4. `execute` runs.
  5. Exceptions are translated: `SynapseException` passes through; anything else becomes `INTERNAL`, with the cause logged and never exposed.
- `RequestContext` (protocol-neutral, read-only in services): correlation id, authenticated principal name (if any), locale, plus a string attribute map that plugins populate. It never contains servlet, gRPC or GraphQL types.
- **Errors:**
  - `ErrorDetails { String code(); String messageTemplate(); ErrorCategory category(); }`
  - `ErrorCategory { INVALID_INPUT, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, PRECONDITION_FAILED, RATE_LIMITED, UNAVAILABLE, TIMEOUT, INTERNAL }`
  - `SynapseException(ErrorDetails, Object... args)`, `SynapseException(ErrorDetails, Throwable, Object... args)`. The message is formatted from the template (`MessageFormat` semantics). An optional `MessageSource` entry keyed by `code()` overrides the template (i18n).
- **Default category mappings** (each plugin owns its own):

| Category | REST | gRPC | GraphQL (`ErrorType`) |
|---|---|---|---|
| INVALID_INPUT | 400 | INVALID_ARGUMENT (+ `google.rpc.BadRequest` details) | BAD_REQUEST |
| UNAUTHENTICATED | 401 | UNAUTHENTICATED | UNAUTHORIZED |
| FORBIDDEN | 403 | PERMISSION_DENIED | FORBIDDEN |
| NOT_FOUND | 404 | NOT_FOUND | NOT_FOUND |
| CONFLICT | 409 | ABORTED | BAD_REQUEST |
| PRECONDITION_FAILED | 412 | FAILED_PRECONDITION | BAD_REQUEST |
| RATE_LIMITED | 429 | RESOURCE_EXHAUSTED | INTERNAL_ERROR |
| UNAVAILABLE | 503 | UNAVAILABLE | INTERNAL_ERROR |
| TIMEOUT | 504 | DEADLINE_EXCEEDED | INTERNAL_ERROR |
| INTERNAL | 500 | INTERNAL | INTERNAL_ERROR |

  GraphQL errors always carry `extensions.code` and `extensions.correlationId`. Functions: HTTP-triggered functions use the REST mapping; event-triggered functions surface a failure to the platform so it can retry or dead-letter.
- Override properties (API side only): `synapse.api.rest.errors.status.<CODE>=423`, `synapse.api.rest.operations.<operation-name>.errors.status.<CODE>=409`, and likewise `synapse.api.grpc.errors.status.<CODE>=ABORTED`.

### Configuration namespaces
`synapse.errors.*`, `synapse.json.*`, `synapse.correlation.*` (header name; default to be decided in P1), `synapse.api.rest.*`, `synapse.api.grpc.*`, `synapse.api.graphql.*`, `synapse.api.function.*`, `synapse.client.rest.<client-name>.*` (and grpc/graphql), `synapse.kafka.*`, `synapse.logging.*`, `synapse.security.*`. Every property has metadata (description + default) and appears in the docs site.

### Auto-configuration rules (apply to every module)
- Each auto-config class is `@AutoConfiguration`, conditional on its key classes (`@ConditionalOnClass`) and, where relevant, `@ConditionalOnWebApplication`/properties. Every bean is `@ConditionalOnMissingBean` (or uses a customizer), so users can replace it.
- Customize Boot through customizer beans (`*BuilderCustomizer`, `*ContainerFactory` customizers, `ObservationFilter`, etc.). Never define Boot's primary infrastructure beans: `DataSource`, `ObjectMapper`/`JsonMapper`, `KafkaTemplate`, `RestClient.Builder`.
- No `@EnableWebMvc`/`@EnableWebFlux`, no `configureMessageConverters` replacement, no component scanning of framework packages, no `application.properties`/logging config shipped in jars.

## Compatibility & migration
- **Breaking:** V2 shares no API with 0.4.x. Consumers migrate with the guide (P7). OpenRewrite recipes are an open question.
- **v1 → V2 module map:**

| v1 | V2 |
|---|---|
| `service-rest`, `service-imperative`, `api-rest-imperative`, `framework-api-docs` | `synapse-core` + `synapse-api-rest` |
| `service-reactive-rest`, `service-reactive`, `api-rest-reactive` | `synapse-api-rest-reactive` (P6) |
| `service-graphql` | `synapse-api-graphql` |
| `function` | `synapse-api-function-aws` / `-gcp` |
| `framework-exception` | `synapse-core` (errors) |
| `framework-logging` | `synapse-logging-log4j2` |
| `framework-test`, `service-test`, `client-test` | `synapse-test` |
| `client-rest` | `synapse-client-rest` (+ `-reactive` P6) |
| `client-graphql` | `synapse-client-graphql` |
| `client-soap` | dropped |
| `data-*` | `synapse-data-<same vendor>` over `synapse-data-jpa`/`-jdbc`/`-r2dbc` (`mssql` becomes `sqlserver`) |
| `publisher-kafka`, `subscriber-kafka` | same names, rebuilt |
| `utilities-common`, `utility-*` | `synapse-core` (JSON conventions, `@Masked`), `synapse-utilities` (validators), `synapse-test` (fixtures); the rest dropped |
| `archetype-*` | regenerated (P7) |

- **v1 maintenance:**
  - `develop` holds 0.4.x and receives CVE-only patches until GA + 12 months. CircleCI keeps building it.
  - v1's CircleCI config has a workflow that deploys releases on `main`, but no `main` branch existed before V2, so it never ran. On `develop`, delete that dead workflow so it can't fire against V2's `main`. v1 releases continue through the `release/*` workflow (maven-release-plugin).
  - `SECURITY.md` gets a supported-versions table.

## Quality gates (all must pass for a phase to be done; enforced in CI on every PR unless noted)
1. `./mvnw verify` on JDK 25 runs unit tests **and** integration tests (failsafe is **not** skipped). Tests also run on a JDK 21 runtime: CI's matrix job compiles on JDK 25 and passes `-Djvm=<JDK 21 java>` to surefire/failsafe (works locally too).
2. Testcontainers ITs for open-source stores and Kafka on every PR. Oracle, SQL Server and DB2 ITs run in the **nightly** workflow and the release workflow.
3. **Test quality, three layers** (decided 2026-10-27):
   - A **per-module floor**: JaCoCo `check` with line ≥ **80%** and branch ≥ **70%**, on merged unit + integration data (BUNDLE). Samples are excluded.
   - **Changed lines**: at least **90%** of the lines changed by a PR must be covered (`diff-cover` on JaCoCo XML in the CI job; no third-party service).
   - **Mutation testing**: PIT runs nightly (`-Pmutation`) on modules that opt in (`<pitest.skip>false</pitest.skip>`): `synapse-core` and each API plugin's error-mapping code. Minimum mutation score is **70%** (provisional; revisit once P1 code exists).

   A blanket 90% floor was rejected because it encourages coverage-padding tests (as in v1).
4. Error Prone + **NullAway** (JSpecify mode) fail the build. Formatting uses Synapse's own **Eclipse formatter profile** (`.config/synapse-java-format.xml`) via Spotless, with an import order (`java|javax|jakarta`, others, `io.americanexpress.synapse`, statics), LF line endings and the license header. **Local builds apply formatting; CI (`CI=true`) checks it and fails on violations.**
   - Style: 4-space indent, 120 columns, continuation indent of 2 levels, long call chains one call per line, `@formatter:off/on` escape tags.
   - Deliberately lenient: author-chosen line breaks and up to one blank line are preserved, and comments and Javadoc are never reflowed.
   - The same XML is imported by IDEs (VS Code/Eclipse natively, IntelliJ via Eclipse-profile import).
   - The Eclipse engine version is pinned (`eclipse-formatter.version`, currently 4.41), so Spotless upgrades can't silently reformat code. Bump it deliberately, in its own commit.
   - An `.editorconfig` sets editor basics for all file types (UTF-8, LF, final newline, trimmed whitespace, 4-space indent, 2-space YAML/JSON).
   - No IDE-specific settings files are committed. IDE setup is documented instead.
5. Maven Enforcer:
   - `requireJavaVersion` 25 for the build, `requireMavenVersion`, `dependencyConvergence`/`requireUpperBoundDeps`
   - **banned dependencies** in compile/runtime scope: `slf4j-ext`, `log4j:log4j`, JUnit 4 (`commons-logging` is allowed: Spring Framework 7 depends on commons-logging 1.3 as its logging bridge), any test library outside test scope, `spring-boot-starter-logging` in Synapse jars
6. Every auto-configuration has `ApplicationContextRunner` tests covering activation, back-off when the user defines the bean, property binding, and absence when its condition is unmet.
7. Every module has `spring-boot-configuration-processor` metadata; a build check fails if a `@ConfigurationProperties` field lacks a description.
8. Security gates:
   - GitHub dependency review on PRs, fails on High or worse
   - OSV-Scanner or OWASP Dependency-Check, fails on High or worse with a documented suppression file
   - CodeQL; secret scanning
   - GitHub Actions pinned by commit SHA, with least-privilege `permissions:`
9. Every sample builds, and its ITs pass in CI. The archetypes (from P7) generate a project that builds.
10. From 2.0.0 GA: **japicmp** against the last release fails on binary-incompatible changes in non-major versions.
11. Dependency overrides of Boot-managed versions are allowed only as documented CVE pins with a removal criterion (v1 discipline). Dependabot (Maven + GitHub Actions) is grouped weekly.

## Delivery plan
Each phase:
- Starts with a plan a maintainer approves.
- Ends with all quality gates green, docs pages for its modules, and a milestone release (`2.0.0-M<n>`) once release automation exists.
- Is done on `feature/v2-p<n>-…` branches merged to `main`.

### P0: Repo cutover and build skeleton
- **Scope:**
  - **v1 side (on `develop`):** delete the dead `release-maven-central-deploy-workflow` (filter `main`) from `.circleci/config.yml`. Add a short note in the v1 README that 0.4.x is in maintenance and V2 lives on `main`.
  - **V2 side (on `main`, starting from current `main`):** one commit removes all v1 modules (history kept), then add:
    - root aggregator and internal build parent, `synapse-bom`, `synapse-parent`, an empty `synapse-core` with one trivial auto-config and test
    - Maven Wrapper (3.9.x), Enforcer, Spotless, Error Prone/NullAway, JaCoCo check, failsafe enabled, CycloneDX, `central-publishing-maven-plugin`, GPG signing (release profile)
    - GitHub Actions workflows: `ci.yml` (PR/main: JDK 25 build + JDK 21/25 tests + gates), `nightly.yml`, `codeql.yml`, `dependency-review.yml`, `release-please.yml`, `release.yml` (tag → sign, publish, SBOM, attestation), `snapshot.yml`, `docs.yml`
    - Dependabot config; `SECURITY.md` (supported versions), `CONTRIBUTING.md` (Conventional Commits, CLA), PR title lint, docs site skeleton
- **Acceptance:**
  - `./mvnw verify` passes locally on JDK 25 and in CI on JDK 21/25.
  - A PR with a deliberately failing unit test, formatting error and NullAway error is rejected (demonstrated, then reverted).
  - release-please opens a release PR in a dry run on a fork.
  - The snapshot publish job succeeds, or is explicitly deferred with a recorded reason (Central Portal snapshot support to be verified).
  - The v1 CircleCI config on `develop` no longer has a workflow filtered on `main`.
- **Tests:** context-runner test for the trivial auto-config; an enforcer rule test (a banned dependency fails the build).

### P1: Core + REST + gRPC + Postgres (2.0.0-M1)
- **Scope:**
  - `synapse-core`: operations, invocation pipeline, `RequestContext`, errors, correlation, Jackson 3 customizers, `@Masked`
  - `synapse-test`: operation test harness, JSON fixtures, Testcontainers `@ServiceConnection` helpers
  - `synapse-logging-log4j2`
  - `synapse-api-rest`: JSON only; `RestBindings` DSL, problem+json, OpenAPI from bindings, `@RestController` escape hatch
  - `synapse-api-grpc`: on Boot 4.1 gRPC auto-config / Spring gRPC. Binds operations to generated service methods; the proto↔record mapping approach is decided in the P1 plan.
  - `synapse-data-jpa`, `synapse-data-jdbc`, `synapse-data-postgres`
  - `samples/reference-bookstore` (first cut): `CreateBook`, `GetBook`, `SearchBooks` (paged), `DeleteBook` over REST and gRPC on Postgres with Flyway. Core supplies paging records `PageQuery`/`PageResult<T>`; the REST plugin maps `page`, `size`, `sort` query parameters.
- **Acceptance (each backed by a test):**
  1. An app with `synapse-api-rest`, one operation bean and one `RestBindings` bean serves the endpoint with no other Synapse configuration.
  2. Adding `synapse-api-grpc` + proto exposes the **same** operation over gRPC with no change to the service module. An ArchUnit test proves the service module has no `spring-web`, gRPC or Synapse API dependency.
  3. One `SynapseException(BookErrors.NOT_FOUND, id)` yields REST 404 problem+json with `code`, `correlationId`, and no `detail` by default; with `synapse.errors.include-detail=true` the formatted detail appears. The same exception yields gRPC `NOT_FOUND` with the code in trailers. A per-operation override changes the REST status for one operation only.
  4. A validation failure yields REST 400 with `violations[]`, and gRPC `INVALID_ARGUMENT` with `BadRequest` field violations.
  5. An unexpected `RuntimeException` yields 500/`INTERNAL`. No stack trace or exception class name appears in any response (asserted).
  6. The correlation id is accepted from the inbound header or gRPC metadata, generated if absent, returned on the response, present in MDC, JSON logs and Observation.
  7. `/v3/api-docs` includes every DSL route with request/response schemas.
  8. With `synapse-data-postgres` and only standard `spring.datasource.*` (or `@ServiceConnection`), the context contains Boot's DataSource and **no Synapse-defined DataSource/EntityManagerFactory**. JPA auditing fills created/modified timestamps and user (`AuditorAware` from the security context, else a configurable system user). `@Version` optimistic locking raises `CONFLICT`. `BaseEntity` equality is by id (proxy-safe).
  9. `@Masked` fields are masked in JSON responses and in log output.
  10. Every auto-config passes the context-runner rules (gate 6).
  11. The reference sample's ITs run against Postgres via Testcontainers in CI on JDK 21 and 25.
- **Design constraint:** the operation contract and pipeline must allow a reactive variant (`ReactiveSynapseOperation<I, O>` returning `Mono`/`Flux`) in P6 without breaking changes.

### P2: Outbound clients (M2)
- **Scope:**
  - `synapse-client-rest`: HTTP interface clients via `@ImportHttpServices`, with per-client properties for base URL, connect/read timeouts and max connections. Also:
    - SSRF `InetAddressFilter` (deny private ranges unless allow-listed)
    - correlation/trace header propagation
    - an upstream error decoder mapping status → `SynapseException` with an `ErrorCategory`, and empty error bodies handled
    - redacted logging (no `Authorization`/cookies)
    - opt-in resilience via Spring Framework 7 `@Retryable`/`@ConcurrencyLimit`
    - URL building only via URI templates (encoding guaranteed)
  - `synapse-client-grpc`: channel defaults, deadlines, metadata propagation, status → `SynapseException`.
  - `synapse-client-graphql`: `HttpSyncGraphQlClient`; a GraphQL `errors[]` entry raises `SynapseException` even on HTTP 200.
  - Sample `internal-grpc` (service A calls service B over gRPC).
- **Acceptance:** WireMock- or in-process-server tests for each error/timeout path, including a 5xx with an empty body raising an exception, and query/path values containing `&`, `/`, `#` and non-ASCII encoded correctly. SSRF block test. Header redaction test.

### P3: Kafka (M3)
- **Scope:** `synapse-subscriber-kafka` binds topics to operations (`synapse.kafka.subscribers.<name>.topics/operation/delivery/retry.*/dlt.*`). `synapse-publisher-kafka` is a typed publisher returning a `CompletableFuture` and failing loudly. Both propagate correlation and observation through headers. Sample `event-driven-kafka`.
- **Acceptance (Testcontainers Kafka):**
  - at-least-once: a failing operation is retried N times, then lands on the DLT, and the offset commits only after success or DLT
  - at-most-once: committed before processing, documented loss
  - exactly-once: transactional read-process-write
  - poison-pill: no infinite redelivery
  - publisher send failure surfaces to the caller
  - batch subscribers actually invoke the operation for every record

### P4: GraphQL, codecs, functions, security starter (M4)
- **Scope:**
  - `synapse-api-graphql`: Spring for GraphQL, schema-first, with a `GraphQlBindings` DSL mapping query/mutation fields to operations.
  - REST codecs: XML (Jackson 3 XML; verify availability) and protobuf content negotiation.
  - `synapse-api-function-aws`/`-gcp`: Spring Cloud Function release train compatible with the Boot version (verify), operations registered as `Function` beans.
  - `synapse-security`.
  - Samples `public-rest-api` (OAuth2 JWT, problem+json, OpenAPI, rate-limit example), `graphql-bff`, `serverless-functions`.
- **Acceptance:**
  - The same `BookErrors` operation tests from P1 run across GraphQL and function adapters (shared contract test suite in `synapse-test`).
  - Content-negotiation tests for JSON, XML and protobuf.
  - Function adapter tests use the AWS/GCP local invokers.
  - Security starter tests: 401 without a token, 403 on scope mismatch, actuator locked down.

### P5: Remaining datastores (M5)
- **Scope:** `synapse-data-mysql` (MySQL/MariaDB), `-oracle`, `-sqlserver`, `-db2` (JPA/JDBC), `-mongodb`, `-redis`, `-cassandra`, `-couchbase` (Spring Data). Each module: auditing where the store supports it, vendor defaults and Testcontainers support.
- **Acceptance:** for each module, a context test proves Boot creates the connection infrastructure. A CRUD + auditing + optimistic-locking IT runs (open-source stores on PR; commercial ones nightly). Redis key/value serializers are explicitly String/JSON (not JDK serialization), and Boot's `spring.data.redis.*` properties work unchanged.

### P6: Reactive (M6)
- **Scope:**
  - `ReactiveSynapseOperation` + pipeline
  - `synapse-api-rest-reactive` (WebFlux; the same `RestBindings` DSL semantics)
  - `synapse-client-rest-reactive` (WebClient / reactive HTTP interfaces)
  - `synapse-data-r2dbc` + R2DBC support in the postgres, mysql, sqlserver and oracle modules (DB2: see Open questions)
  - reactive repositories for mongodb/redis/cassandra/couchbase
  - gRPC/GraphQL accept reactive operations
- **Acceptance:** P1/P2 contract tests pass for reactive variants, including errors raised asynchronously. No blocking calls on event-loop threads (BlockHound in tests).

### P7: GA hardening (2.0.0)
- **Scope:**
  - `synapse-utilities` (validators)
  - archetypes generated from the reference app (set of archetypes: see Open questions)
  - docs site complete with property tables
  - v1 → V2 migration guide
  - japicmp baseline configured
  - security review (threat model per plugin), OpenSSF Scorecard
  - reference sample complete across all plugins
  - `SECURITY.md` announces the 0.4.x EOL date (GA + 12 months)
  - release 2.0.0
- **Acceptance:** all quality gates green, including nightly. Every module has docs. Every sample and archetype is built in CI. The migration guide covers every row of the module map.

## Open questions
| Question | Owner | Blocks |
|---|---|---|
| Create `main` in upstream `americanexpress/synapse` (it has none today) and make it the default branch, move CI to GitHub Actions, and provision Central Portal token + GPG key as GitHub secrets (with a protected `release` environment) | Maintainers / americanexpress org | P0 release/snapshot jobs on upstream (work can proceed on a fork meanwhile) |
| proto ↔ record mapping for gRPC (hand-written mappers vs MapStruct vs generating records/adapters from `.proto`) | Maintainers, P1 plan review | P1 gRPC plugin |
| Default correlation header name (`X-Correlation-Id` vs v1's `Correlation-ID`) | Maintainers, P1 plan | P1 |
| problem+json `type` URI base (e.g. configurable `synapse.errors.type-base-uri`, default `about:blank`) | Implementer proposes in P1 plan | P1 |
| Docs tool (MkDocs Material vs Antora) | Implementer proposes | P0 docs workflow |
| Central Portal SNAPSHOT publishing availability for this namespace | Implementer verifies in P0 | Snapshot workflow |
| DB2 reactive (R2DBC) driver maturity; drop DB2 reactive if none | Implementer investigates in P6 plan | P6 |
| Spring Cloud train + function adapters compatible with the chosen Boot 4.x | Implementer verifies in P4 plan | P4 |
| Archetype set (one parameterized vs one per module type: app/service, client, data, publisher, subscriber, function) | Maintainers, P7 plan | P7 |
| OpenRewrite migration recipes (v1→V2 and between 2.x minors) as an "easy update" aid | Maintainers | P7 (optional) |
| Field-level encryption module (replacing v1 `CryptoUtil` properly, e.g. via KMS/Spring Security crypto) | Maintainers | Post-GA |

## v1 facts relied on
Verify on the `develop` branch (v1). Paths are relative to the repo root. The v1 Java root is `src/main/java/io/americanexpress/synapse/...`.
- **Duplicate stacks:**
  - deprecated: `service/synapse-service-rest` (`.../service/rest/package-info.java` has `@Deprecated(since = "0.4.0", forRemoval = true)`), `service/synapse-service-reactive-rest`
  - newer: `api/synapse-api-rest-imperative`, `api/synapse-api-rest-reactive`, `service/synapse-service-imperative`, `service/synapse-service-reactive`
  - the newer stack has no tests and no sample/archetype consumers; all samples and archetypes use `synapse-service-rest`/`-reactive-rest` (check the sample `pom.xml`s under `service/service-samples/*` and `archetype/*/src/main/resources/archetype-resources/pom.xml`)
- **No auto-configuration:** no `AutoConfiguration.imports` or `spring.factories` anywhere; wiring is `@ComponentScan`/`@Import`, e.g. `api/synapse-api-rest-imperative/.../api/rest/imperative/config/BaseApiImperativeRestConfig.java`.
- **New imperative controllers bind without `@RequestBody`/`@Valid`:** `api/synapse-api-rest-imperative/.../controller/BaseCreateImperativeRestController.java` (also Update, Delete, GetMono, ReadPoly).
- **Pagination double-slice bug:** `service/synapse-service-imperative/.../service/imperative/model/PageResponse.java`. The constructor slices `responses`, then `getResponsesForPage()` slices again.
- **Data modules re-implement Boot:**
  - `data/synapse-data-postgres/.../data/postgres/config/BasePostgresDataConfig.java` (requires top-level `hibernate.*` keys, hard-coded leak detection)
  - `data/synapse-data-mysql/.../config/BaseMySqlDataConfig.java` (`spring.mysql.datasource.*`, unpooled `MysqlDataSource`)
  - `data/synapse-data-oracle/.../config/BaseOracleDataConfig.java` (`spring.oracle.datasource.*`, unpooled)
  - `data/synapse-data-redis/.../config/BaseRedisDataConfig.java` (`setHashKeySerializer` called twice instead of `setKeySerializer`; reads `spring.data.redis.host.name`)
- **Error model origin:** `framework/synapse-framework-exception/.../framework/exception/ApplicationClientException.java`, `ApplicationServerException.java`, `model/ErrorCode.java` (closed enum), `model/HttpStatus.java` (deprecated copy), `src/main/resources/error-messages.properties`.
- **Stack traces "encrypted" into responses with a default key:** `api/synapse-api-rest-imperative/.../controller/exceptionhandler/ControllerExceptionHandler.java` uses `utility/synapse-utilities-common/.../utilities/common/cryptography/CryptoUtil.java` (PBEWithMD5AndDES, hard-coded fallback password, zero salt). The reactive handlers return stack traces in plain text: `api/synapse-api-rest-reactive/.../exceptionhandler/ControllerExceptionHandler.java`.
- **Global Jackson side effects:** `utility/synapse-utilities-common/.../utilities/common/config/UtilitiesCommonConfig.java` (4 ObjectMapper beans, camelCase `@Primary`, `CurrencySerializer` for every `BigDecimal`, `MoneyDeserializer` rounding to 2 dp, `DecimalSerializer` for every `Double`).
- **Prescriptive utilities kept in V2:**
  - masking serializers in `utility/synapse-utilities-common/.../serialization/` (`AllCharactersMask*`, `FixedLengthMask*`, `LastFourCharactersMaskStringSerializer`, `NationalIdentifierStringSerializer`, `TelephoneStringSerializer`, `PostalCodeStringSerializer`)
  - validators in `.../validator/` (`OneOf`, `AnyOf`, `RequireIfPresent`, `CheckDateFormat`)
  - fixtures `.../io/ClasspathObjectFactory.java`, `.../io/IOUtils.java`
- **Kafka data-loss bugs:**
  - `subscriber/synapse-subscriber-kafka/.../subscriber/kafka/subscriber/BaseKafkaPolySubscriber.java`: the returned `Runnable` is discarded
  - `BaseKafkaMonoSubscriber.java`: acks before async processing
  - `errorhandler/BaseKafkaSubscriberErrorHandler.java`: `seek(offset++)` re-seeks the failing offset
  - `controller/KafkaSubscriberSupportController.java`: unauthenticated GET start/stop endpoints
- **Client issues:**
  - `client/synapse-client-rest/.../client/rest/handler/BaseReactiveRestResponseErrorHandler.java`: an empty error body swallows the error
  - `.../handler/BaseRestResponseErrorHandler.java`: `(HttpStatus)` cast
  - `.../model/QueryParameter.java` `format()`: no URL encoding
  - `.../helper/ReactiveRestClientLoggingExchangeFilterFunction.java`: logs all headers at INFO
- **Library jars ship app config:** `framework/synapse-framework-logging/src/main/resources/log4j2.xml` and `log4j2-test.xml` (root TRACE); `service/synapse-service-graphql/src/main/resources/application.properties`.
- **Build baseline:**
  - `./mvnw clean verify` passes with 850 unit tests on JDK 21
  - integration tests are skipped (`maven-failsafe-plugin` `<skipITs>true</skipITs>` in the root `pom.xml`)
  - SpotBugs reports 121 findings without failing the build
  - the JaCoCo `coverage-check` execution is defined in `pluginManagement` but not bound by modules
  - CVE pins live in the root `pom.xml` `dependencyManagement` with removal notes
- **v1 release path:** `.circleci/config.yml` (on `develop`) workflow `release-maven-central-deploy-workflow` is filtered on `main`, but the repository had no `main` branch as of 2026-10-27 (`git ls-remote` showed only `develop`), so it never ran. Real v1 releases go through `release/*` (maven-release-plugin `release:prepare release:perform`). Snapshot jobs run on `develop`, `feature/*`, `bugfix/*` and `dependabot/*`.
- **Original goals and structure:** the root `README.md` (layer and module types, recommended consumer layout, "two exceptions" error model, convention over configuration).
