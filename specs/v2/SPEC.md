# Synapse V2 spec — 2026-10-27

Status: **Approved for implementation** (decisions agreed by the maintainers on 2026-10-27). Implement phase by phase; each phase starts with a written plan that a maintainer approves.

## Summary
Synapse v1 (0.4.x) is a set of Spring Boot 3 libraries that let teams build layered services quickly with prescriptive conventions. In practice it has four overlapping web stacks, no Spring Boot auto-configuration, data modules that re-implement Boot, untested "new" modules and several data-loss and security bugs (see *v1 facts relied on*).
V2 (`2.0.0`) is a ground-up rewrite on **Spring Boot 4 / Spring Framework 7**, built with JDK 25 and **running on Java 21+**. It keeps Synapse's original goals and layered structure.
The central change: **services are protocol-agnostic operations**, and **API plugins** (REST, gRPC, GraphQL, cloud functions; later reactive REST) expose them by adding a starter plus a small binding.
Every module ships as real Boot auto-configuration and is proven by unit tests, `ApplicationContextRunner` tests and Testcontainers integration tests under strict CI gates. Defaults are secure.

## Goals / Non-goals

### Goals (2.0.0 GA must deliver all)
1. **Protocol plugins.** A developer writes an operation once, as protocol-free code (no HTTP, gRPC or GraphQL types). They expose it over REST (JSON/XML/protobuf), gRPC, GraphQL or AWS/GCP functions by adding the plugin starter and at most a few lines of binding.
2. **Correct by construction.** Every module is a Boot auto-configuration that backs off to user beans and is covered by context-runner tests and real-infrastructure ITs. No configuration exists that is never triggered.
3. **Secure by default.** No internals or stack traces leak to callers, sensitive data is masked in responses and logs, clients have sane timeouts and SSRF protection, and the supply chain is signed and scanned.
4. **Minimal developer work, easy to update.** One parent POM or BOM aligned with Spring Boot. Conventions instead of configuration. Semver with API-compatibility checks, an automated changelog and a migration guide.
5. **True to the original structure.** The layers stay: service, business, subscriber, client, data, publisher, framework/core, utility. (In consumer projects, subscribers now live in the service module's `api.kafka` package rather than a `subscriber-*` module.) So do the prescriptive conventions: consumer project layout, archetypes and the error model.

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
| Release automation | **Conventional Commits + release-please** (`maven` strategy, which updates every `pom.xml`). A release PR carries the version bump and CHANGELOG. Merging creates a tag `v*`, and the tag workflow builds, GPG-signs, publishes to Central, attaches a CycloneDX SBOM and creates a GitHub build-provenance attestation. Milestones keep the `2.0.0-M1…` names, set explicitly per milestone through release-please's version override; the P0 fork dry run must prove this works, and if it doesn't, milestones are tagged by hand and release-please takes over at GA. | Automated, auditable releases with no commits from CI. | maven-release-plugin via `workflow_dispatch`; JReleaser; release-please's beta-style prereleases (`2.0.0-beta01`) |
| Snapshots | **Three tiers.** Releases: `2.0.0` (signed). `main`: `2.0.0-SNAPSHOT` on every merge. Pull requests: `2.0.0-pr123-SNAPSHOT`, published only for PRs whose branch is in the upstream repo, and only while a maintainer has applied the `publish-snapshot` label (republished on each push). PRs from forks never publish, because GitHub withholds secrets from them and `pull_request_target` must not build PR code; they attach their jars to the CI run as artifacts instead. Snapshot jobs get only the Central token and skip GPG signing (Central doesn't validate snapshots), so the signing key reaches only the release job. Known caveat: Maven sorts `2.0.0-pr123-SNAPSHOT` above `2.0.0` (checked with Maven's `ComparableVersion`), so consumers should not resolve "latest" from the snapshot repository; Central deletes snapshots after 90 days. v1 today publishes `0.4.x-SNAPSHOT` from `develop` and every feature/bugfix/dependabot branch to the same version, so builds overwrite each other. Sources read this session: GitHub Actions events reference (fork secrets, `pull_request_target` warning), Sonatype Central Portal snapshots page (90-day cleanup, no validation). | Lets teams try a fix before release without publishing unreviewed fork code or exposing the signing key. | v1 behavior (every branch, one version); `main` only; PR snapshots on every push; `2.0.0-123-SNAPSHOT` (reads like a build number) |
| Programming model | **Imperative first**: Spring MVC + virtual threads. A **reactive plugin is the last phase before GA (P6)** and must be part of 2.0.0. | Halves the surface area early; reactive teams are still served at GA. | Both from day 1; reactive first; never |
| Virtual threads | **On in generated projects.** `synapse-parent`'s archetype and the reference app set `spring.threads.virtual.enabled=true` in the generated `application.yml` (never inside a library jar). Synapse's own tests run with virtual threads both on and off. The docs cover carrier-thread pinning on Java 21 (blocking inside `synchronized`, e.g. some JDBC drivers; largely fixed by JEP 491 on Java 24+) and how to switch it off. | Blocking I/O throughput for the imperative model; Boot already supports it. | Off and documented; decide in the P1 plan |
| Context propagation | **`RequestContext` is a read-only view over the OpenTelemetry/Micrometer context; Synapse writes no propagators of its own.** Transport: the correlation id and allow-listed attributes (e.g. `tenant`) are registered as Boot baggage fields (`management.tracing.baggage.remote-fields` / `.correlation.fields`, contributed by Synapse auto-config). They are extracted at every inbound edge, injected on every client and Kafka call, and copied into MDC; a remote field named `X-Correlation-Id` is sent as that header, so the chosen header name stays. The deadline is the exception: it's relative, so each outbound call recomputes `Request-Timeout`. Threads: Synapse registers a `ContextPropagatingTaskDecorator` (backs off if the user defines one), so context follows `@Async`, executors and virtual threads. Access: `RequestContext.current()` is set by edges (API plugins, subscribers) and read by operations and business code. Security: inbound baggage is untrusted; keys not on the allow-list are dropped at the edge, the total size is limited, and baggage is never used for authorization. The principal and locale never travel as baggage. Clients forward only what their `propagate` allow-list names (see *Header propagation*). Sources read this session: Spring Boot 4.1.2 tracing and observability reference (baggage properties; `spring.task.execution.propagate-context` is opt-in). | One propagation mechanism shared with traces and logs; services get the direct access an earlier internal version lacked. | A separate Synapse ThreadLocal and headers next to OTel (two systems that can disagree); OTel only with no service access |
| Core packaging | **One `synapse-core` jar** holding the contracts, the invocation pipeline and the Boot wiring. Every module type depends on it. Its auto-config is conditional, so it stays inert in library modules until a Boot app starts. Layering is guarded by the ArchUnit rules in `synapse-test`. Public API lives in documented packages; implementation lives in `…synapse.core.internal` (and `…internal` in every module), which japicmp excludes and the docs mark unsupported. | One dependency and one version for consumers; Boot's guidance says splitting auto-config and starter modules is optional when there are no optional features. | Contracts jar + core; Boot-style code/autoconfigure/starter split per module |
| Operation contract | **One class per operation** implementing `ServiceOperation<I, O>` (record in/out, one `execute` method). It has no protocol imports. The framework applies validation, observation, correlation and error translation around **every** invocation, whoever the caller is. | Protocol-agnostic unit that maps 1:1 to functions and GraphQL fields and groups naturally into gRPC services and REST resources. Successor to v1's `BaseService.execute`. | Annotated multi-method service classes; plain beans with no contract |
| Contract naming | The **`*Operation`** family with a layer prefix: `ServiceOperation<I, O>` is the service-layer root (exposable; a plain one is a command), `QueryOperation`/`CommandOperation` extend it, and `BusinessOperation<I, O>` is a separate, never-exposable contract. Telemetry names follow: spans `synapse.operation` and `synapse.business`. | "Operation" is the term OpenAPI (`operationId`), GraphQL and API design guides use for one callable unit, and it avoids clashing with gRPC "service" (a group of methods) and Spring `@Service`. | `SynapseOperation` + `BusinessOperation`; a `*Service` family (v1's `BaseService` continuity, but overloaded) |
| Business layer | **Business logic only; it may call client, data and publisher code, never protocol, `api`, `operation` or `model` code.** It starts as the service module's `business` package and becomes a `business-*` module only when shared by a second deployable. Every business **bean** implements **`BusinessOperation<I, O>`** (ArchUnit-enforced), wrapped by a proxy pipeline that provides validation and a `synapse.business` child span. Business owns its domain types; operations map API records to and from them. `@Transactional` is allowed, with the operation preferred as the boundary. | Same typed, validated, traced contract as operations, without extra modules; API contracts stay separate from domain logic. A proxy rather than a v1-style `final execute()` base class, because a final template method silently disables `@Transactional`. | Always a business module; plain beans with only a convention; a `@BusinessService` stereotype; a v1-style base class; a shared model module; entities everywhere |
| Operation kinds | **Marker sub-interfaces** `QueryOperation<I, O>` (reads, no state change) and `CommandOperation<I, O>` (changes state), both extending `ServiceOperation<I, O>`. A plain `ServiceOperation` is treated as a command (the safe default). Plugins use the kind for defaults and checks: the REST DSL's default verb (GET vs POST), GraphQL query vs mutation (binding a command under `Query` fails at startup), and retry safety. | Type-checked intent shared by every protocol, so REST and GraphQL can't disagree about whether an operation is safe to retry. | Annotations (`@Query`/`@Command`, not type-checked); no kind (every binding repeats intent) |
| Transactions | **Standard Spring `@Transactional`** on the operation, chosen by the developer. Synapse never opens transactions itself. The Synapse pipeline advisor is ordered **outside** the transaction advisor, so the transaction runs inside the observation and exception translation sees the transaction's outcome (rollback applies to the original exception). | Explicit and standard. Automatic transactions would hold connections open across slow remote client calls. | Automatic transaction by operation kind; data-layer-only transactions |
| Operation composition | An operation **may inject and call another operation**. The call goes through the same pipeline: a nested Observation (child span), validation of the inner input, and the **same** `RequestContext`/correlation id (not re-established). Shared logic that isn't a use case belongs in the business layer (`BusinessOperation`). | Pragmatic; matches how teams build, with consistent behavior on every call. | Forbidden via ArchUnit; allowed with an ArchUnit warning |
| Authorization | **External; Synapse stays out.** Access control is enforced outside the application (e.g. OPA at the gateway, Envoy `ext_authz` or a sidecar). Synapse ships no authorization annotation, SPI or OPA module. API plugins expose the verified caller identity in `RequestContext` for auditing and logging. Adopters who want in-process checks use standard Spring Security method security themselves; Synapse maps Spring Security's access-denied/authentication exceptions to `FORBIDDEN`/`UNAUTHENTICATED` when Spring Security is on the classpath. | Matches how the maintainers' organization authorizes APIs; keeps the framework small. | Spring method security as the Synapse convention; Synapse `@RequiresPermission`; `OperationAuthorizer` SPI; first-class OPA module |
| Paging | Core defines protocol-neutral **`PageQuery`** (cursor **or** offset, size, sort) and **`PageResult<T>`** (items, next cursor, optional total). Size is capped by `synapse.paging.max-size` (protects against huge-page requests). One-call conversion from a Spring Data `Page`/`Slice`. Each plugin maps it natively: REST query parameters + `Link` header, gRPC `page_token`/`page_size`, GraphQL connections. Names avoid clashing with Spring Data's `PageRequest`/`Page`. | A single convention across protocols; fixes v1's double-slicing `PageResponse`. | Spring Data `Pageable`/`Page` in service modules (offset-only, unstable JSON shape); no paging in core |
| Operation logging | **Failures by default, payloads opt-in.** Successful calls produce no log line (traces and metrics cover them). Failures in client-error categories log at WARN, and `INTERNAL` logs at ERROR with the stack trace; both include operation, code, category and correlation id. Input/output payload logging is opt-in per operation (`synapse.logging.operations.<operation-name>.payloads=true`) at DEBUG, with `@Masked` fields redacted. | PII-safe by default without losing failures. | One INFO line per call; no pipeline logging |
| Deadlines | **Propagated.** Plugins read an incoming deadline into `RequestContext`: natively for gRPC, and for REST from a **relative** timeout header, `Request-Timeout: <milliseconds>` by default. The header name is set with `synapse.api.rest.deadline.header`. A server-side cap, `synapse.api.rest.deadline.max`, limits requested budgets. A relative value is used, as gRPC's `grpc-timeout` is, to avoid clock skew. No header means no deadline. If the deadline has already passed, the pipeline fails fast with `TIMEOUT`. Synapse clients cap their timeout to the remaining budget and forward the deadline. Without an incoming deadline, the configured client timeouts apply. | Stops wasted work and retry storms after the caller has given up. | Per-client timeouts only |
| API plugins | **Protocol-agnostic operations + one adapter starter per protocol.** RPC-style protocols (gRPC, GraphQL, functions) bind by name or group. REST uses a declarative DSL. HTTP/protobuf and XML are REST codecs, not separate protocols. | Delivers the original "plugin" intent without forcing REST semantics onto RPC. | Per-protocol base classes (v1 style); contract-first codegen |
| REST binding | **Java DSL bean** (`RestBindings`) in the application/API layer, compiled to Spring MVC functional routes. Synapse generates OpenAPI from it. **Escape hatch:** plain `@RestController` classes that inject operations get the same cross-cutting behavior. | The HTTP surface is visible in one place and type-checked, with no HTTP leaking into services. Spring-native routing, security and observability. | `@HttpExchange` interface (more magic); controllers only (boilerplate) |
| Input binding | **The binding maps protocol locations to input-record components; records carry no annotations.** Conventions cover the common case: path variables by name; query parameters by name on GET/HEAD; the body fills the remaining components on POST/PUT/PATCH; framework types such as `PageQuery` bind by type on any verb. Everything else is declared, using component names as **strings validated at startup**. Repeated fields are declared once in an app-wide `RestConventions` bean. Cross-cutting headers (tenant and the like) go into `RequestContext` attributes, not input records. Headers never bind implicitly. Full rules are under *Input binding*. | Input records stay protocol-free; the typical route declares nothing; typos fail at boot, not in production. | Annotations on records (protocol leaks into services); explicit-only mapping; method references or a generated metamodel for names; automatic query binding on all verbs |
| Response mapping | **Conventions plus route options, mirroring input binding.** Default status depends on method and result: GET 200, empty `Optional` 404, POST 201 with `.created(...)` (else 200), null/void 204, DELETE 204. A route can override the status: fixed (`.status(202)`), `.accepted(...)` (202 + `Location`), or computed from the output (`.status(fn)`). Output components can map to **response headers** (`.header("ETag", "version")`), per route or app-wide in `RestConventions`. `.etag(...)` gives `ETag`/`304` support. Full rules are under *Response mapping*. | Operations return plain values; the HTTP shape lives in the binding, and the common cases need no declarations. | Explicit status on every route; a `Result<T>` return type with HTTP hints in operations |
| gRPC binding | **Explicit `GrpcBindings` bean with a by-name shortcut.** In `api.grpc`, `GrpcBindings.service(<ServiceDescriptor>)` lists `.unary("Method", operation)` entries. `.bindAllByName()` connects every remaining proto method to the operation with the matching name (`GetBook` → `get-book`/`@OperationName`). Explicit entries override the shortcut and carry per-method options such as `.metadata(...)`. Startup fails if a proto method is unbound or bound twice, or its types can't be mapped. Unary calls in P1; server-streaming later. A hand-written `BookServiceImplBase` subclass stays available as the escape hatch, with the same Synapse interceptors (errors, correlation, deadlines, observation). | Exposure is visible in one class, and large services need one line. | Explicit only; implicit convention with no bean; generated base classes only |
| GraphQL binding | **`GraphQlBindings` bean, same shape as gRPC.** `.query(field, op)`, `.mutation(field, op)` and `.bindAllByName()`, with arguments bound by name per the input rules. At startup, a `CommandOperation` under `Query`, or a `QueryOperation` under `Mutation`, fails. Nested fields that need an operation use `.field("Book", "author", getAuthors).batched()`, which runs on Spring for GraphQL batch loading: one operation call per response, not per item, so no N+1. `@QueryMapping`/`@SchemaMapping` controllers remain the escape hatch, with Synapse error mapping, correlation and observation. | One binding model across RPC-style protocols; N+1 handled by default. | Explicit only; controllers only |
| Function binding | **Deferred to the P4 plan.** Proposal on record: HTTP-triggered functions reuse `RestBindings` inside the function, and event-triggered functions use a `FunctionBindings` bean exposed as Spring Cloud Function beans. | Depends on verifying Spring Cloud Function compatibility with Boot 4 (open question). | Every operation auto-registered as a Function |
| CORS | **Off by default; allow-list properties.** `synapse.api.rest.cors.allowed-origins`, `-methods`, `-headers`, `-allow-credentials` and `-max-age` apply to all bindings, functional routes included. Startup fails on `*` combined with credentials, and warns on `*` in prod profiles. | Secure default with guard rails. | Spring's standard config only; per-route CORS in bindings |
| Request size limits | **Body: Synapse default 1 MB.** `synapse.api.rest.max-request-size` (per-route `.maxRequestSize(...)` for uploads). Over the limit returns 413 problem+json (`PAYLOAD_TOO_LARGE`) without reading the whole body. Jackson 3 read constraints stay at their defaults and are documented. gRPC: `synapse.api.grpc.max-inbound-message-size`, default 4 MB as in grpc-java. **Headers:** Boot's `server.max-http-request-header-size` remains the only setting; Synapse adds no alias. When the user hasn't set it, `synapse-api-rest` raises the default from 8 KB to **16 KB**, using a lowest-precedence property source from an `EnvironmentPostProcessor`, not a properties file in the jar. The gRPC plugin does the same for max inbound metadata size (`synapse.api.grpc.max-inbound-metadata-size`, 16 KB; grpc-java's default is 8 KB). The P1 plan checks whether the container's header rejection (a bare 400) can be logged at WARN with the size and the limit. The docs have one "limits" page: body 1 MB, headers 16 KB, gRPC message 4 MB, gRPC metadata 16 KB. | Spring MVC has no JSON body limit of its own; large enterprise tokens commonly exceed 8 KB of headers and fail with an undiagnosable 400. | Body: 10 MB default; no Synapse limit. Headers: a Synapse alias property; keeping 8 KB; 32 KB |
| Health | **Spring Boot Actuator probes plus a Synapse alias path.** Liveness covers internal state only (`livenessState`, Boot default). Following Boot's guidance, liveness never checks external systems, because a shared dependency outage would otherwise restart every pod. Readiness is `readinessState` plus Boot's health indicators for the datastores configured through `synapse-data-*` modules: a DataSource connection check, and a ping for Mongo/Redis/Cassandra/Couchbase. Downstream clients are **not** in readiness by default; client health is decided with the Client module. `synapse.api.rest.health-path` (e.g. `/health`, for load balancers that can't be reconfigured) returns the readiness result. Everything can be overridden with standard `management.endpoint.health.group.*` properties. Actuator exposure stays minimal (health only). | A DB outage takes pods out of rotation without restarts; one flaky downstream service can't remove the whole service. | Boot defaults only; readiness including client circuit breakers; porting v1's `HealthCheckController` |
| OpenAPI | **Generated from the bindings, served via springdoc.** Synapse builds the OpenAPI paths from `RestBindings`, `RestConventions` and the record types: Jakarta Validation constraints, statuses (including `.created`/`.accepted`), mapped headers, problem+json error responses and API versions. It contributes them to springdoc (e.g. through an `OpenApiCustomizer`), which serves `/v3/api-docs`. Swagger UI is off by default and enabled in dev profiles. Toggle: `synapse.api.rest.openapi.enabled`. Verify in the P1 plan that springdoc supports Spring Boot 4.1; if it doesn't, Synapse serves the document itself. | The code is the single source of truth, so docs can't drift. | Generation plus a contract-first check against a committed `openapi.yaml` (possible later addition); contract-first only; no OpenAPI |
| Correlation id | **`X-Correlation-Id`**, separate from the trace id. The header name is configurable (`synapse.correlation.header`, e.g. v1's `Correlation-ID`). A UUID is generated if the id is absent. It's echoed on responses and present in MDC/logs, problem+json, gRPC metadata and Kafka headers, and tagged on the current trace. | Survives where traces break (gateways that restart traces, batch jobs, async hops), and external callers can supply it. | Trace id as the correlation id; v1's `Correlation-ID` as the default; accepting both names |
| Input conflicts | **Reject mismatches, ignore unknown fields.** The location declared in the binding wins. If another location carries the same field with a different value (e.g. path `/books/1` with body `"id": "2"`), the result is `INVALID_INPUT`. Unknown fields are ignored (tolerant reader; Boot's Jackson default), so additive API changes don't break older clients. Records bind only known components, so ignoring unknown fields can't cause mass assignment. | Catches client bugs and tampering without making API evolution lockstep. | Strict (reject unknown fields too); declared location wins silently |
| Binding location | **Bindings live in the service module**, in `api.<protocol>` packages next to the operations; there is no separate app or API module. Operation and model packages stay protocol-free, enforced by a package-level ArchUnit test (see *Consumer project layout*). | One module per service, as in v1; adding a protocol is one dependency and one class. | Separate bootable `app` module (Maven-enforced boundary, two modules per deployable); per-protocol `api-*` modules; both layouts in archetypes |
| v1 deprecation | **No further change on `develop`.** v1's `service-rest`, `service-reactive-rest` and `service-graphql` are deprecated only through `package-info.java`, which compilers ignore (per the `java.lang.Deprecated` Javadoc). That is accepted: V2 has new artifact names, and the migration guide and the 0.4.x EOL notice in `SECURITY.md` cover it. | Keeps `develop` strictly CVE-only. | Class-level `@Deprecated` in a 0.4.30; bundling it into the next CVE patch; docs-only notice |
| Response shape | **No envelope.** REST returns the operation output as the body. Metadata travels in headers (correlation id, paging `Link`, `ETag`), and errors are problem+json. gRPC and GraphQL use their native shapes. | Standard; matches OpenAPI tooling and generated clients. | Optional `{data, meta}` envelope; always-on envelope |
| API versioning | **Spring Framework 7 built-in API versioning**, exposed in the REST DSL (`.version("2")`, which maps to the functional `version(...)` predicate). The default strategy is a **path segment** (`/v1/books/...`), switchable to header, query parameter or media-type parameter via `synapse.api.rest.versioning.*`. Deprecated versions emit Spring's deprecation/sunset hints. A contract change means a new operation (or binding) per version. gRPC and GraphQL evolve through their own schema mechanisms (proto packages, field deprecation). | Uses the platform feature instead of a Synapse-specific one; path versions are visible in logs and gateways. Whether Boot 4 exposes its own `spring.mvc.apiversion.*` properties is unverified; check in the P1 plan and reuse them if so. | Header default; no Synapse versioning |
| Config model | **Spring Boot auto-configuration** in every feature jar: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `@ConditionalOn*` back-off, typed `@ConfigurationProperties` under **`synapse.*`**, and `spring-boot-configuration-processor` metadata. No `@ComponentScan`/`@Import` of framework packages is required of users. | Boot convention; fixes v1's manual wiring. | Explicit `@Enable…`/`@Import`; disabled-until-enabled flags |
| Artifacts | **Feature jars** (code + auto-config in one jar, e.g. `synapse-api-rest`) + **`synapse-bom`** + **`synapse-parent`** (extends `spring-boot-starter-parent`; carries consumer build conventions). | Fewest artifacts; still Boot-idiomatic; the parent is the "consistent standards" lever. | Spring-style split autoconfigure/starter (about 2x artifacts); BOM only |
| BOM contents | `synapse-bom` manages Synapse artifacts plus the third-party libraries Synapse adds beyond Boot (e.g. springdoc, Spring Cloud Function adapters, gRPC/protobuf if not Boot-managed). It does **not** import Boot's BOM. Consumers either use `synapse-parent` (which brings Boot) or import `spring-boot-dependencies` + `synapse-bom`. Each Synapse minor documents its supported Boot version. | Avoids two sources of truth for Boot versions. | A BOM that imports Boot |
| Error model | `SynapseException(ErrorDetails details, Object... args)` (plus a cause overload). Teams implement **`ErrorDetails`** as their own enum: `code()`, `messageTemplate()` (`{0}` placeholders) and `category()`. **`ErrorCategory`** is protocol-neutral (domain semantics, not HTTP). Each plugin maps category → protocol status. Overrides are API-side and keyed by error code, globally or per operation. Precedence: per-operation > global > category default. Services never see HTTP, gRPC or GraphQL types. | A design proven in internal use at American Express, minus the separate status-mapping file; keeps the layering clean. | Separate mapping file per protocol; HTTP status on the interface; v1 `ErrorResponse` shape; Spring defaults only |
| Error exposure | Responses always include the code, a generic title (from the category), the correlation id and validation field violations. The formatted detail message is included only when `synapse.errors.include-detail=true` (default **false**). Full detail and cause are always logged server-side. Never stack traces. | Prod-safe; mirrors Boot's `server.error.include-message`. | Per-code exposure flag; always show detail |
| HTTP error format | **RFC 9457** `application/problem+json`, with extension members `code`, `correlationId` and `violations[]`. | Standard, and supported by Spring `ProblemDetail`. | v1 ad-hoc JSON |
| Logging | Synapse jars depend only on the **SLF4J 2 API** (no `slf4j-ext`/XLogger, no backend). An opt-in **`synapse-logging-log4j2`** starter (included by `synapse-parent`/archetypes) swaps out Boot's logging, enables async loggers, uses Boot structured JSON logging and applies `@Masked` redaction. Logback remains usable. Jars ship **no** logging config files. | Keeps the team's Log4j2 preference without locking consumers in. | Logback default; mandatory Log4j2 (v1 approach) |
| Observability | Micrometer **Observation** around every operation, binding, client call and Kafka record. **OpenTelemetry/OTLP** export for traces and metrics through Boot's own support. A correlation id is propagated across REST/gRPC/GraphQL/functions/clients/Kafka. | One model across protocols. | Micrometer only |
| Security | **Secure defaults, pluggable auth.** No internals in responses; sensitive-header and `@Masked` redaction in logs; client timeouts and SSRF `InetAddressFilter`; request size limits; validation on every operation; minimal actuator exposure. Authentication through Spring Security, with an optional **`synapse-security`** starter (OAuth2 resource server/JWT defaults) that is never forced. Authorization is external (see *Authorization*). Supply chain: signed artifacts, provenance attestation, SBOM, dependency and vulnerability scanning, CodeQL, actions pinned by SHA. | Safe without blocking non-OAuth shops or local development. | A security starter on by default; leave it all to consumers |
| Data layer | **Per-vendor modules kept**, as **thin layers over shared per-technology modules**. `synapse-data-jpa`/`-jdbc` (and `-r2dbc` in P6) hold the shared behavior: auditing, capability interfaces and optional base entities, conventions, named stores. Vendor modules (`synapse-data-postgres`, …) add the driver, vendor defaults and Testcontainers test support. **Connection infrastructure is never hand-built:** a single store uses Boot's own auto-configuration, and named stores are built with Boot's builders and property binding (`DataSourceBuilder`/Hikari, `EntityManagerFactoryBuilder`, the Spring Data Mongo/Redis factories). | Keeps the familiar v1 module names without v1's 11 hand-wired copies. | Per-technology only; drop data modules |
| Data access model | **Spring Data repositories, nothing forced.** Developers write entities and plain Spring Data repository interfaces (derived queries, `@Query`, specifications) with zero implementation code, and business code injects repositories. Synapse features key off **small capability interfaces**: `Identifiable<ID>`, `Versioned` (optimistic locking → `CONFLICT`, REST `ETag`), `Auditable` (created/modified at/by) and `TenantScoped`. Teams either implement them on their own entities with their own column names, or extend an **optional** `@MappedSuperclass` base that implements a combination (`IdEntity<ID>`, `VersionedEntity<ID>`, `AuditedEntity<ID>`, plus Mongo/other-store equivalents). Plain entities with none of these still work; they just don't get those features. The pipeline translates Spring's `DataAccessException`s: optimistic-lock and unique-constraint failures → `CONFLICT`, query timeout → `TIMEOUT`, connection failure → `UNAVAILABLE`, others → `INTERNAL`. Query spans/metrics come from Boot/Micrometer instrumentation. | Zero-code repositories; no forced inheritance; legacy schemas can still use Synapse features. JPA can only inherit mappings from a `@MappedSuperclass` class, so interfaces carry capabilities and the optional bases carry mappings. | `DataOperation<I, O>` per access; repositories plus an optional `DataOperation`; embeddable building blocks; one mandatory `BaseEntity` |
| Entity ids | Optional bases are generic on the id type. The default helper is **app-generated UUIDv7**: time-ordered and index-friendly, known before insert, and not enumerable in URLs. Sequence-based `Long` variants are also provided. Teams can always map their own `@Id`. Base-entity equality is by id and proxy-safe. | Good defaults for distributed services without excluding numeric ids. | DB sequence `Long` as the default; no default |
| Named data stores | **Every `data-*` module names its store**, for example `@SynapseDataStore("books")` on a configuration class in the module. Entity and repository packages are derived from that class's package. Configuration is uniform: `synapse.data.<name>.*` (url, credentials, pool, vendor settings), the same shape as `synapse.client.<name>.*`. Per name, Synapse builds the DataSource (or Mongo/Redis/Cassandra/Couchbase client), EntityManagerFactory, transaction manager, repositories, health indicator and metrics, all with Boot's builders. With **one** store it's automatically primary, and Boot's standard properties (`spring.datasource.*`, `@ServiceConnection`) also work. With several, one may be `primary=true` for unqualified `@Transactional`; others are selected with `@Transactional("legacy")` (qualifier = store name). Testcontainers `@ServiceConnection` is supported per name through `synapse-test`. **Cross-store transactions are not atomic** (no XA; documented). Readiness includes every named store's health. | Enterprise services commonly use several databases, even several of the same technology (e.g. Postgres + Oracle), while Boot configures only one per technology. Uniform naming removes the hand-written multi-datasource setup every team copied in v1. | Boot per technology with names only for extras (asymmetric); named stores for JDBC/JPA only |
| Read replicas | `synapse.data.<name>.replica.url` (plus credentials and pool) adds a replica to a named store. `@Transactional(readOnly = true)` is routed to the replica and everything else to the primary, using Spring's routing DataSource behind a lazy connection proxy, so the decision happens at the first SQL statement. Repositories and business code are unchanged. Replica lag is documented: read-your-own-writes needs the primary. | The common scale-out need with no code changes. | Replica as a separate named store; post-GA |
| JPA vs JDBC | **JPA is the default** in the archetype and reference sample. `synapse-data-jdbc` is an equal citizen: the same capability interfaces, auditing, named stores and error translation, plus docs on when to prefer it (simple aggregates, heavy SQL, batch). Vendor modules work with either. | Familiar to most enterprise teams, and Hibernate provides tenancy and `@SoftDelete`. | JDBC default; JPA only |
| Redis | `synapse-data-redis` covers **both** Spring Data Redis as a store (named stores, repositories/templates) **and** caching through Boot's `spring.cache` with Redis. Both use JSON serialization configured by Synapse, **never JDK serialization** (a deserialization-RCE risk). Also: per-service key prefixes, default TTLs and cache metrics. Cache settings live under `synapse.data.<name>.cache.*`. | One module for both common Redis roles, safe by default. | Store only; a separate cache module |
| Data testing | `synapse-test` provides per-vendor Testcontainers definitions with `@ServiceConnection` support for named stores. It adds a `@SynapseDataTest("<store>")` slice that starts only that store's repositories, migrations and auditing (no web layer). Containers are shared singletons across test classes to keep CI fast. Commercial DB images run nightly only. | Fast, realistic ITs that know about named stores; `@DataJpaTest` doesn't. | Containers only; docs only |
| Schema migrations | **Flyway by default; Liquibase supported**, both through Boot's own auto-configuration. Synapse adds conventions only: `db/migration`, naming, and Testcontainers tests that apply every migration. Migrations run at startup by default; teams that migrate in a separate job set `spring.flyway.enabled=false`. **Safety rail:** when a migration tool is present, Synapse defaults `spring.jpa.hibernate.ddl-auto=validate` (startup fails if entities don't match the schema), and it fails startup if `ddl-auto` is `create`, `create-drop` or `update` outside dev/test profiles. | Explicit, versioned schema changes; fixes v1's unchecked Hibernate settings. | Flyway only; no Synapse opinion |
| Soft delete and multi-tenancy | **Soft delete:** no Synapse feature; documented use of Hibernate's `@SoftDelete`. **Multi-tenancy: opt-in**, off by default. `synapse.data.tenancy.mode=discriminator` (a tenant column, filtered automatically) or `schema` (schema per tenant). Hibernate's tenant resolver reads the `RequestContext` attribute named by `synapse.data.tenancy.context-attribute` (e.g. `tenant`, populated by the inbound context-header config). A missing tenant fails the call; it never means "all tenants". | Reuses Hibernate's built-in features; the tenant flows from the edge without manual plumbing; no cross-tenant leaks by default. | Docs only for both; defer to post-GA |
| Datastores (GA) | First-class and Testcontainers-tested: PostgreSQL, MySQL/MariaDB, Oracle, SQL Server, DB2, MongoDB, Redis, Cassandra, Couchbase. Commercial databases (Oracle/SQL Server/DB2) run in the **nightly** CI job and the release job, not on every PR. | Maintainer decision; heavy images kept off the PR path. | Subset only |
| Clients (GA) | Protocol client plugins `synapse-client-rest`, `synapse-client-grpc` and `synapse-client-graphql`, plus a reactive REST client in P6. **SOAP dropped.** The plugins do all the protocol wiring; developers declare calls. | Covers outbound protocols with the same defaults as inbound. | Keep SOAP |
| Client model | **`ClientOperation<I, O>` + outbound bindings**, symmetric with the inbound side. A consumer `client-*` module declares `RestClientBindings.client("inventory").get("/stock/{sku}", GetStock.class, Stock.class).named("get-stock")` (and gRPC/GraphQL equivalents). Each entry becomes a `ClientOperation<I, O>` bean that business code and operations inject; they never see protocol types. Record components map to path, query, header and body by the input-binding rules applied in reverse. Built on Spring's `RestClient`, so Boot's observation, SSL bundles and HTTP client settings apply. Escape hatch: a Synapse-configured `RestClient` bean per client. | Minimal developer work per call; callers stay protocol-neutral, so a downstream service can move from REST to gRPC without touching callers; same `*Operation` vocabulary. | Spring HTTP interface clients (`@HttpExchange`) as the model; one class per call (v1 style); both models |
| Client add-ons | **Optional add-on jars plugging into a `ClientAuthenticator` SPI** shared by REST/gRPC/GraphQL clients. Adding a jar makes the mechanism available, and each client selects one with `synapse.client.<name>.auth.type`. Initial set: `synapse-client-auth-oauth2` (client credentials, token caching/refresh via Spring Security's OAuth2 client) and `synapse-client-auth-token-relay` (forward the caller's token, or token exchange). AWS SigV4 is a candidate. mTLS needs no add-on: `synapse.client.<name>.ssl.bundle` uses Boot SSL bundles. Teams implement the SPI for in-house schemes. Other optional features (e.g. resilience) follow the same add-on pattern where they bring extra dependencies. | Pay only for what you use; small CVE surface; common mechanisms done once and securely. | Everything built in and classpath-activated; SPI only |
| Client configuration | **One namespace per client: `synapse.client.<name>.*`.** It holds `base-url`, `connect-timeout`, `read-timeout`, pool size, `ssl.bundle`, `auth.*`, `logging` and resilience settings. `synapse.client.defaults.*` is inherited by every client. The keys are the same for REST, gRPC and GraphQL clients. Synapse applies them through Boot's builders/customizers (nothing re-implemented), and Boot's global HTTP client properties remain the lowest default. | One place to look per client, documented in the generated property tables. | Boot per-group transport properties + Synapse extras (split config; Boot groups exist only for HTTP interfaces); code-only config |
| Client resilience | **Everything off by default except timeouts** (`synapse.client.<name>.connect-timeout`/`read-timeout`, always set, capped by the request deadline). Retries are opt-in per client (`synapse.client.<name>.retry.*`, on Spring Framework 7's core retry support). Once enabled, they apply only to idempotent calls (GET/HEAD/PUT/DELETE, gRPC/GraphQL queries, or POSTs marked `.idempotent()`) and only on connection errors, 502/503/504 and 429 (honoring `Retry-After`). They use exponential backoff with jitter and never exceed the remaining deadline. Circuit breaker and bulkhead come from an add-on, `synapse-client-resilience4j`, configured per client. | No hidden extra load on downstream services; when retries are enabled, they're safe by construction. | Safe retries on by default; Resilience4j built in for every client |
| Client health | With the circuit-breaker add-on, each client contributes an **informational** health component (`UP`/`CIRCUIT_OPEN` with details) to `/actuator/health`. It is **not** in readiness or liveness by default, so an open breaker never removes pods. A hard dependency can opt in with `synapse.client.<name>.health.readiness=true`. | Visible to operators without cascading outages. | Never in health; readiness by default |
| Outbound idempotency | **Opt-in per call:** `.idempotent()` on a POST binding generates one `Idempotency-Key` per logical call, or `.idempotent("idempotencyKey")` takes it from an input component for end-to-end idempotency. The **same key is reused on every retry**, which makes the POST retryable. This only helps when the downstream service honors the header. The inbound (server) side remains an open question. | Safe POST retries without assuming every downstream service supports keys. | No POST retries in P2; always sending a key on POST |
| Downstream error mapping | **Mirror, auth-aware for 401/403.** A downstream failure becomes a `SynapseException` whose category mirrors the downstream status: 400/422 → `INVALID_INPUT`, 404 → `NOT_FOUND` (or an empty result when the `ClientOperation` returns `Optional`), 409 → `CONFLICT`, 412 → `PRECONDITION_FAILED`, 429 → `RATE_LIMITED`, 5xx and connection errors → `UNAVAILABLE`, timeout → `TIMEOUT`. gRPC status codes and GraphQL error types mirror the same way, through the inverse of the inbound tables. **401/403 depend on whose credentials were sent, and the client's `ClientAuthenticator` add-on decides:** (1) **Service credentials** (OAuth2 client credentials, API key, mTLS): our credential problem never reaches our caller as 401/403. On 401, refreshable authenticators (OAuth2) evict the cached token, fetch a new one and **retry once**, even with retries otherwise off. If that fails, or the credential isn't refreshable, and for any 403, the caller gets `INTERNAL` (500, generic), the client logs ERROR "authentication/authorization rejected by downstream" and increments `synapse.client.auth.failures{client}` for alerting. (2) **Token relay** (the caller's own token is forwarded): 401 → `UNAUTHENTICATED`, 403 → `FORBIDDEN`, with no retry. A relay misconfiguration on our side also surfaces as 401, so it's logged at WARN with the client name. (3) **No auth, or credentials supplied by the caller as request data:** mirror. Custom authenticators declare their own behavior through the SPI. **Precedence, most specific wins:** per-call `.onStatus(403, AccountErrors.ACCOUNT_NOT_ACCESSIBLE)` / `.onErrorCode(...)`, then per-client `synapse.client.<name>.errors.status.<code>=<CATEGORY>`, then the authenticator default, then the mirror table. The downstream message, body and field violations are **never forwarded** to our caller; they're logged with the client failure. A caller's own bad credentials never reach this path, because they're rejected at our edge before any downstream call. | Downstream 4xx often stems from caller data passed through, so mirroring is the default. But our own credential or scope problems are our fault: they must not be blamed on the caller, and they must alert us. A downstream service that uses 403 for per-resource decisions is mapped explicitly on that call. | Pure mirror including 401/403; refresh then 503; 401 straight to 500 with no refresh; mirroring 403 by default under service credentials; a fixed table in core |
| Client error hooks | **The implementing client always gets the first say.** There are three programmatic levels plus the declarative mappings. (1) **Per-call handler** in the binding: `.onError((failure, input) -> ...)`. (2) **Client-wide `ClientErrorHandler` bean** (`@Client("inventory")`, or global), for a downstream with its own error scheme. (3) **Callers catch `ClientCallException`**, a `SynapseException` subtype carrying protocol-neutral facts: category, client and operation name, downstream error code, retryable flag; no HTTP types. Handlers receive a `ClientFailure` (status/code, headers, downstream code, raw and typed body, cause; protocol details are allowed because handlers live in the client module) and return one of: `raise(new SynapseException(...))`, which throws a different error or category; `recover(value)`, which returns a fallback output so the call succeeds; or `useDefault()`, which falls through. **Order:** transport steps first (configured retries, the one-time 401 token refresh), so handlers see the final failure. Then: per-call `.onError`, the client-wide handler, `.onStatus`/`.onErrorCode`, `synapse.client.<name>.errors.status.*`, the authenticator default, and finally the mirror table. | Teams can express any downstream contract in code, while the framework's safe defaults remain the net when they return `useDefault()`. | Declarative mappings only; mapped exceptions handed to handlers (defaults first); handlers that can also request retries |
| Downstream error bodies | **Optional typed parsing, plus `failWhen` for failures inside a 2xx response.** **No error class needed.** For an empty body, plain text or a proxy's HTML page, handlers still get the status, headers and the size-capped raw body. `downstreamCode()` is empty and the defaults apply. **problem+json by default.** It's parsed automatically, including Synapse's `code` extension, so Synapse-to-Synapse calls get `downstreamCode()` with zero config. **Other schemes.** `.errorBody(Type.class, Type::code)` on the client or the call parses the error into any type, including the success response type itself when a downstream uses one shape for both. **Failure inside 200.** `.failWhen(resp -> ..., Resp::errorCode)` turns a 2xx response into a `ClientFailure`, common in legacy/partner APIs. It goes through the same handler chain. An unmapped one becomes `INTERNAL`, is logged, and is recorded as an error in traces and metrics. Error bodies are never forwarded to our caller. | Works for well-behaved APIs with no config, and for legacy APIs without pushing response checks into every caller. | Raw body only; problem+json only; no support for failures inside 200 (callers check) |
| Client SSRF protection | **Host fixed by configuration.** A client may only call the host of its configured `base-url`. A request that resolves elsewhere (templated host, redirect) is refused, and redirects are not followed by default. Clients that need dynamic targets (webhooks, user-supplied URLs) must declare `synapse.client.<name>.dynamic-target=true` with `allowed-hosts` patterns. They then get the `InetAddressFilter` (Boot 4.1 HTTP-client mitigation) blocking private, link-local and cloud-metadata ranges unless allow-listed. | Internal calls to private addresses just work; the risky case is explicit. | Block metadata ranges only; deny private ranges unless allow-listed (breaks internal calls) |
| Header propagation | Correlation id, W3C trace context and the remaining deadline are **always** forwarded. Nothing else is forwarded by default. `RequestContext` attributes (from the inbound cross-cutting headers) are forwarded only when listed: `synapse.client.defaults.propagate=tenant,channel`, overridable per client (e.g. empty for third-party APIs). `Authorization` and cookies are never propagated; forwarding a caller's token is the token-relay add-on's job. | No accidental leaks to third parties, and callers can't inject downstream headers. | Forward all `X-*` headers; per-call declarations |
| ClientOperation injection | **By generic type, with an `@Client` qualifier when ambiguous.** Unique `ClientOperation<I, O>` types inject by type. If two calls share I/O types, startup fails with a clear message and the caller adds `@Client("<client>.<operation>")` (a `synapse-core` annotation). The docs encourage a distinct record type per call. | No boilerplate in the common case; ambiguity never resolves silently. | Typed client interface per call; always by name |
| gRPC/GraphQL clients | **Same model.** `GrpcClientBindings.client("pricing").unary(<MethodDescriptor>, QuoteRequest.class, Quote.class)` over generated stubs, using the proto ↔ record mapping (open question). `GraphQlClientBindings.client("catalog").query("bookByIsbn", BookQuery.class, Book.class)` with documents in `src/main/resources/graphql-client/`. Both produce `ClientOperation` beans with the same `synapse.client.<name>.*` keys, auth add-ons, logging, error mirroring and resilience. A GraphQL `errors[]` response raises `SynapseException` even on HTTP 200. gRPC deadlines come from the request deadline. | Callers are protocol-neutral for every outbound protocol. | Bindings for REST only (raw stubs/`HttpSyncGraphQlClient` elsewhere) |
| Client logging | **Failures by default.** Observation is always on (spans plus Boot's `http.client.requests` metrics). On 4xx/5xx, timeout or connection error the client logs one WARN with the client and operation names, method, URL **template** (never the expanded URL), status, duration and correlation id. This is accepted to overlap with the calling operation's failure log, because it keeps downstream detail next to the client. Opt-in per client via `synapse.client.<name>.logging`: `basic` (every call at INFO, without bodies), `headers` (adds headers; `Authorization`, `Cookie`, `Set-Cookie` and configured names redacted) or `full` (adds bodies at DEBUG, `@Masked` fields redacted, size-capped). | Failures are diagnosable without opting in; PII-safe by default. | Off by default; basic on by default |
| Messaging | **Broker-neutral contract, Kafka as the only V2.0 implementation.** `PublisherOperation<E>` and the message metadata types live in `synapse-core` and contain no Kafka types. `synapse-publisher-kafka` and `synapse-subscriber-kafka` build on Boot's Kafka auto-config. Other brokers (SQS, Pub/Sub, RabbitMQ, Service Bus) can be added later as separate modules on the same contract, without changing business code. Subscribers bind topics to operations. Per-subscriber `synapse.kafka.subscribers.<name>.delivery` = `at-least-once` (**default**: commit after success, backoff retries, then DLT) \| `at-most-once` \| `exactly-once` (Kafka transactions + `read_committed`). The publisher always has idempotence on and surfaces send failures (no fire-and-forget). | Safe default with flexibility; fixes v1's data-loss bugs. A neutral contract keeps a future broker from becoming a second programming model. | Required explicit choice; at-least-once only; Kafka types in the contract; Spring Cloud Stream binders (large extra release train that overlaps the bindings DSL) |
| Subscriber bindings | **A `KafkaBindings` bean in the service module's `api.kafka` package**, like `RestBindings`: `bindings.topic("book-events", BookCreated.class).to("record-sale")`. The record value is the input; `.header(name, component)` maps headers (see *Input binding*). A subscriber is an inbound protocol, so it follows the same layout rule as REST/gRPC; the artifact keeps the name `synapse-subscriber-kafka`. A worker-only deployable is a service module with only `api.kafka`. The consumer layout has no `subscriber-*` module type. | One rule for every inbound protocol; subscribers get the operation pipeline (correlation, deadline, validation, observation, translation). | Separate `subscriber-*` module (v1); package first, module when shared |
| Kafka framework modules | **`synapse-subscriber-kafka` plays the API-plugin role for subscribers, and `synapse-publisher-kafka` the client role for publishers; both share `synapse-kafka-core`.** The subscriber module auto-configures, on top of Boot's Kafka auto-config: listener containers and group ids, payload deserialization into the input record, `ce_*`/correlation headers into `RequestContext`, invocation through the operation pipeline, category-aware retries and the DLT, dedupe, topic verification, observation and the Actuator endpoint. The publisher module builds `PublisherOperation<E>` beans from `KafkaPublisherBindings`. `synapse-kafka-core` holds what both need: named clusters, the CloudEvents header codec, correlation/trace headers, JSON serde and observation setup; the registry add-ons depend on it too. Consumers never add `synapse-kafka-core` directly. The consumer writes only bindings beans. The artifact names keep v1's publisher/subscriber pairing; the docs explain the subscriber's API-plugin role. | Same split as `synapse-client-core`; a subscriber-only app doesn't get publisher bindings. | Shared code inside the publisher module; one merged `synapse-kafka` module; renaming to `synapse-api-kafka`; consumer package `api.subscriber` |
| Publisher model | **`PublisherOperation<E>`**, declared once in a publisher bindings bean (`KafkaPublisherBindings.topic("book-events", BookCreated.class).key(BookCreated::isbn).named("book-created")`) and injected by type like `ClientOperation` (`@Publisher("<name>")` qualifier when ambiguous; startup fails on ambiguity). **`publish(event)` blocks until the broker acknowledges**, bounded by the `RequestContext` deadline and `synapse.kafka.publishers.<name>.send-timeout`, and throws `SynapseException` (`UNAVAILABLE` or `TIMEOUT`) on failure. **`publishAsync(event)`** returns a `CompletableFuture` for high-throughput code. Business code never sees `KafkaTemplate`, `ProducerRecord` or topic names. | Safe by default and consistent with the synchronous operation model; async is explicit. | `CompletableFuture` only (a forgotten future drops failures, v1's bug class); thin `KafkaTemplate` wrapper (Kafka types leak into business code) |
| Message format | **JSON by default** through Boot's Jackson 3 mapper; no schema registry needed. **Avro and Protobuf with a schema registry are opt-in add-on modules** (`synapse-kafka-avro`, `synapse-kafka-protobuf`). Which registry client to use (Confluent serializers vs Apicurio, which have different licenses) and the add-ons' phase are open questions. | Easy start; schema enforcement for teams that need it. | JSON only; Avro + registry by default; Protobuf by default |
| Event metadata | **CloudEvents 1.0, binary content mode, written by Synapse (no CloudEvents SDK).** The record value stays the plain payload; metadata goes in headers: `ce_specversion=1.0`, `ce_id` (UUIDv7), `ce_source` (default `spring.application.name`, overridable via `synapse.kafka.source`), `ce_type` (default the publisher name, overridable per publisher), `ce_time` (publish time), `content-type`; plus `X-Correlation-Id` and W3C `traceparent`. `source` + `id` is the dedupe key. Subscribers accept records without `ce_*` headers (id and type are then absent), so plain Kafka producers can feed Synapse. Sources read this session: CloudEvents spec and Kafka protocol binding (github.com/cloudevents/spec); CNCF graduation announcement (2024-01-25). The SDK was skipped because its JSON module depends on Jackson 2 and Jackson 3 support is an open request (cloudevents/sdk-java#703). | Standard header names that tools and non-Synapse consumers understand; a dedupe key and a routing type without parsing the body; the same attributes carry over to future broker modules. | Synapse-specific headers; structured mode (payload wrapper); no envelope |
| Multi-type topics | **Supported: subscribers route by `ce_type`.** `bindings.topic("book-events").on("book-created", BookCreated.class).to("record-sale").on("book-deleted", BookDeleted.class).to("remove-stock")`. Single-type topics keep the short form `topic("orders", OrderPlaced.class).to("place-order")`. Unknown types are skipped and counted (`synapse.kafka.subscriber.unknown.types{subscriber,type}`), or sent to the DLT with `synapse.kafka.subscribers.<name>.unknown-type=dlt`. Startup fails if two `.on(...)` entries share a type. | One topic per entity keeps all events for a key ordered. | One type per topic; decide in the P3 plan |
| Subscriber failures | **Retry by error category.** Sent straight to the DLT (retrying can't help): deserialization failures, `INVALID_INPUT`, `NOT_FOUND`, `CONFLICT`, `PRECONDITION_FAILED`, `FORBIDDEN`, `UNAUTHENTICATED`. Retried with backoff, then sent to the DLT: `UNAVAILABLE`, `TIMEOUT`, `RATE_LIMITED`, `INTERNAL`. Overridable per subscriber with `synapse.kafka.subscribers.<name>.retry.retryable` / `.non-retryable` (category lists). DLT records keep the original headers and add the failure's error code, category, attempt count and correlation id, never stack traces. | Reuses the error model; bad messages reach the DLT immediately instead of holding the partition. | Retry everything then DLT; no retries |
| Subscriber retry mode | **Blocking retries by default; retry topics opt-in.** Default: in-place retries with exponential backoff, then DLT, preserving per-key ordering. Proposed defaults 3 attempts, 1 s doubling to a 10 s maximum (confirm in the P3 plan). `synapse.kafka.subscribers.<name>.retry.mode=topics` switches to Spring Kafka's non-blocking retry topics for long backoffs (minutes), giving up ordering for the failed record. Startup fails if the total blocking backoff can exceed `max.poll.interval.ms`. | Ordering is the safe default; long waits don't stall partitions when opted in. | Retry topics by default (breaks ordering, extra topics to provision); blocking only |
| Transactional outbox | **Opt-in `synapse-publisher-outbox` add-on.** Inside an active `@Transactional`, `publish()` writes the event to an outbox table in the same data store (Flyway/Liquibase migration provided) and returns; a relay publishes it after commit and marks it sent (at-least-once, so consumers must tolerate duplicates). The table layout is compatible with Debezium's outbox router, so teams can switch to CDC. Without the add-on, a `publish()` inside an active transaction logs a one-time WARN naming the publisher. The add-on's phase is an open question. | Closes the dual-write gap (publish then rollback, or commit then a failed publish) that Kafka transactions can't cover. | Document the pattern only; not in Synapse |
| Duplicate events | **Opt-in dedupe store per subscriber; off by default.** The docs require subscriber operations to be idempotent. `synapse.kafka.subscribers.<name>.dedupe.store=<data store name>` uses a JDBC table (migration provided) or a Redis store, keyed by `ce_source` + `ce_id`, with `dedupe.ttl` (proposed 7 days; confirm in the P3 plan). A seen event is skipped and counted. When the dedupe store is the same JDBC store as the operation's transaction, the processed marker commits with the business change. Records without `ce_id` can't be deduped and log a one-time WARN per subscriber. | At-least-once delivery and the outbox both produce duplicates; CloudEvents gives a ready key. | Docs only; on by default (forces a data store on every worker) |
| Kafka clusters | **Named clusters, same model as data stores.** One cluster: Boot's `spring.kafka.*` unchanged. Additional clusters: `synapse.kafka.clusters.<name>.*` (bootstrap servers, `ssl.bundle`, SASL, client properties), built from Boot's Kafka properties and factories, never by hand. Publishers and subscribers choose one with `.cluster("audit")`; omitted means the primary. Startup fails on an unknown cluster name. | Consistent with named data stores; common enterprise setup (domain + audit/analytics clusters). | Single cluster in 2.0; always named |
| Batch and concurrency | **Per-record by default; batch opt-in.** Each record invokes the operation once through the full pipeline. `.batch()` binds a topic to an operation taking `List<I>`; on failure it reports the failing record's index, so earlier records commit and the failing one follows the retry/DLT rules. Concurrency per subscriber: `synapse.kafka.subscribers.<name>.concurrency` (defaults to Boot's `spring.kafka.listener.concurrency`). | Fixes v1's batch subscriber that never invoked processing; bulk writes stay possible. | Per-record only; batch by default |
| Messaging logging | **Same as clients: failures only.** A retry attempt logs at WARN; a DLT send at ERROR with topic, partition, offset, error code, category, attempt count and correlation id, never the payload. Payloads are opt-in via `synapse.logging.kafka.<name>.payloads` (masked). Spring Kafka's observation is enabled for producers and listeners so spans and metrics link across services (property names verified in the P3 plan). Consumer lag comes from Micrometer's Kafka metrics. | Consistent with operations and clients; DLT sends are always visible. | Log every record; off by default |
| Topic provisioning | **Verify at startup; creation opt-in.** Synapse never creates topics unless `synapse.kafka.topics.auto-create=true` (intended for local and test: creates subscribed, DLT and retry topics with configured partitions and replication). At startup it checks that every subscribed topic, DLT and retry topic exists: `synapse.kafka.topics.verify=fail` (**default**) \| `warn` \| `off`. Verification needs the Describe ACL; teams without it set `warn`. | A missing DLT blocks a partition on the first bad message; production topics are owned by platform teams/IaC. | WARN by default; always auto-create |
| Consumer group id | **Default `<spring.application.name>.<subscriber name>`**, overridable with `synapse.kafka.subscribers.<name>.group-id`. Startup fails if `spring.application.name` is unset and no group id is given. The docs warn that renaming the app or subscriber starts a new group (offsets then follow `auto.offset.reset`). | Each binding has its own offsets and scaling. | Boot's single shared group id; always explicit |
| Kafka control | **A `synapse-kafka` Actuator endpoint.** Read: subscribers, state, group id, lag. Write (POST): pause/resume a subscriber, and replay DLT records to the original topic (offset range or all, with a required max count). It follows Boot's endpoint exposure rules (not exposed over HTTP unless configured) and is secured like other Actuator endpoints. Replays keep `ce_id`, so dedupe still applies, and add a `synapse-replayed` header. | Replaces v1's unauthenticated GET start/stop endpoints; incident handling without ad-hoc tooling. | Status + pause only; no control surface |
| Messaging testing | **`@SynapseKafkaTest` slice plus test helpers in `synapse-test`.** The slice starts only Kafka wiring against a shared Testcontainers Kafka (`@ServiceConnection`). Helpers: send a record with `ce_*` headers and await the bound operation's result; capture what a `PublisherOperation` sent (topic, key, headers, payload); read a DLT. An in-memory `PublisherOperation` fake supports unit tests of business code. | Same approach as `@SynapseDataTest`; makes the P3 acceptance tests and consumer tests cheap to write. | Containers only; embedded broker |
| Operation testing | **A harness and a slice in `synapse-test`.** (1) No Spring: `SynapseOperations.invoke(op, input)` runs the real pipeline (validation, translation, context) around a plain instance, and `RequestContexts.with(...).run(...)` sets context; meant for most tests. The same works for `BusinessOperation`. (2) `@SynapseOperationTest` boots operations, business beans and the pipeline only (no web, Kafka or data) and replaces every `ClientOperation` and `PublisherOperation` with a fake or mock automatically. Assertions: `assertThatSynapseException(...).hasCode(BookErrors.NOT_FOUND)` / `.hasCategory(...)` / `.hasViolation(field)`. | Tests that call `execute()` directly skip validation and error translation; most tests stay fast. | Slice only; plain JUnit only |
| API binding testing | **Per-protocol slices.** `@SynapseRestTest` loads the REST plugin and the app's `RestBindings`, with operations mocked, and asserts status, headers and problem+json through Boot 4's MVC test client (`MockMvcTester` or `RestTestClient`; chosen in the P1 plan). `@SynapseGrpcTest` does the same with grpc-java's in-process server; `@SynapseGraphQlTest` follows in P4. Kafka uses `@SynapseKafkaTest`. | Catches mapping mistakes fast, without a database. | Full app tests only; startup checks only |
| Client testing | **`@SynapseClientTest("<client>")` uses `MockRestServiceServer` by default; a real stub server is opt-in.** The default binds to the client's `RestClient.Builder` (Spring Framework 6.1+) and covers what teams own: paths, headers, error mapping, `onError` hooks, and simulated failures via `withException(new SocketTimeoutException(...))` and the status helpers. `@SynapseClientTest(value = "inventory", server = STUB)` starts WireMock in the test JVM on a random port and points `synapse.client.inventory.base-url` at it, for real delays (`withFixedDelay`) and faults. Synapse's own client-module tests use WireMock to prove transport behavior once: real timeouts, TLS/mTLS, the SSRF host pin, redirect refusal and retries. gRPC clients test against grpc-java's in-process server. WireMock's Boot 4 / Jetty / Jackson fit is verified in the P2 plan. Sources read this session: Spring Framework 7.0 `MockRestServiceServer` and `MockRestResponseCreators` javadoc and the client-testing reference (an in-memory request factory; Spring recommends mock web servers for transport-level tests); WireMock Spring Boot and fault-simulation docs (`CONNECTION_RESET_BY_PEER` is unreliable on Windows). | Simple default with no extra dependency; transport behavior is proven once by the framework instead of by every team. | WireMock default; `MockRestServiceServer` only |
| Contract testing | **Out of 2.0, documented.** No contract-testing module. The docs show how to use the generated OpenAPI, `.proto` and GraphQL schemas with existing tools: breaking-change diffs in CI (e.g. oasdiff, buf breaking), Pact or Spring Cloud Contract. It can become an add-on in a 2.x minor once teams show which tool they use. | Keeps 2.0 scope down; schema artifacts already exist. | Pact add-on; Spring Cloud Contract add-on |
| Utilities | **Keep proven value only.** JSON conventions as **Jackson 3 builder customizers** in `synapse-core` (`synapse.json.naming=camel\|snake`, ISO-8601 dates; never replace Boot's mapper). `@Masked` annotation + maskers in `synapse-core` (shared by JSON output and log redaction). Validators (`@OneOf`, `@AnyOf`, `@RequireIfPresent`, date-format check) in `synapse-utilities`. Classpath JSON fixture loaders in `synapse-test`. Everything else is dropped. | Preserves v1's prescriptive value without its global side effects. `@Masked` moved to core (not utilities) because logging and JSON both need it. | Port everything; drop everything; keep formatting serializers |
| Samples | One **canonical multi-module reference app** (bookstore) plus **use-case samples**: public REST/JSON API (OAuth2, problem+json, OpenAPI), internal gRPC microservices, event-driven Kafka, serverless functions (AWS + GCP), GraphQL BFF. All are built and integration-tested in CI and never published to Central. | Teaches by scenario; replaces v1's ~38 drifting sample modules. | One sample per module; template repo |
| Archetypes | Regenerated from the reference app in P7, and CI-verified by generating a project and building it. | v1 archetypes target the deprecated stack. | Template repo only |
| Docs | **Antora** (AsciiDoc) docs site in the repo, for framework users: getting started, a guide per module, the generated property reference, error-code and category tables, the v1→V2 migration guide and Javadoc links. Contributor material stays in `CONTRIBUTING.md` and `AGENTS.md`. Versions come from git branches/tags (the `version` key in `antora.yml`), with a version switcher; published to GitHub Pages by CI. A per-module reference page includes a property table generated from configuration metadata as AsciiDoc partials during the Maven build. The REST plugin produces OpenAPI; gRPC server reflection is enabled in dev profiles. Sources read this session: Antora versioning docs; Spring blog on Spring Framework's move to Antora (2023); Zensical versioning docs (temporary mike fork); Docusaurus versioning docs; Material for MkDocs maintenance announcement (fixes end 2027-05-05). | v1 READMEs drifted; versioning is built in without copies or stopgap plugins; familiar to Spring users. | Zensical (no native versioning yet); Docusaurus (per-version copies); Material for MkDocs + mike (end of upstream fixes in May 2027); READMEs only; Javadoc only |
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
    synapse-test                       test support: operation harness + @SynapseOperationTest, @SynapseRestTest/@SynapseGrpcTest, @SynapseClientTest, @SynapseDataTest, @SynapseKafkaTest, Testcontainers helpers, fixtures, ArchUnit rules
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
    synapse-client-core (ClientAuthenticator SPI, shared wiring, logging, error decoding) (P2)
    synapse-client-rest, synapse-client-grpc, synapse-client-graphql (P2); synapse-client-rest-reactive (P6)
    synapse-client-auth-oauth2, synapse-client-auth-token-relay (P2); further add-ons as needed
  data/
    synapse-data-jpa, synapse-data-jdbc (P1), synapse-data-r2dbc (P6)
    synapse-data-postgres (P1); -mysql, -oracle, -sqlserver, -db2, -mongodb, -redis, -cassandra, -couchbase (P5)
  messaging/synapse-kafka-core         shared Kafka wiring: named clusters, CloudEvents headers, serde, observation (P3)
  publisher/synapse-publisher-kafka    (P3)
  subscriber/synapse-subscriber-kafka  (P3)
  messaging/synapse-kafka-avro, synapse-kafka-protobuf   schema-registry add-ons (phase: open question)
  publisher/synapse-publisher-outbox   transactional outbox add-on (phase: open question)
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
  service-*      bootable module, one per deployable service (as in v1). Depends on synapse-core, the chosen
                 synapse-api-* plugins and business/client/data/publisher modules.
    <base>/operation/      operations (ServiceOperation beans): protocol-free
    <base>/model/          API-facing input/output records, ErrorDetails enums: protocol-free
    <base>/business/       business logic (BusinessOperation beans + domain types): protocol-free
    <base>/api/rest/       RestBindings configuration
    <base>/api/grpc/       gRPC bindings (+ src/main/proto)
    <base>/api/graphql/    GraphQL bindings (+ src/main/resources/graphql)
    <base>/api/kafka/      KafkaBindings (subscribers: topics -> operations)
  business-*     business logic shared by more than one deployable (jar: synapse-core + client/data/publisher modules,
                 no protocol dependencies). Same rules as the business package.
  client-*, data-*, publisher-*
```
There is no separate "app" or per-protocol API module: bindings live in the service module's `api.<protocol>` packages (Kafka subscribers included), so adding a protocol means adding one plugin dependency and one bindings class. Business logic starts in the service module's `business` package and moves to a `business-*` module only when a second deployable needs it.

**Layering rules**, enforced at **package** level by ArchUnit rules that **ship in `synapse-test`** (`SynapseArchitecture.rules()`, plus each rule individually). The archetype and the reference app contain a one-line test that calls them, so rule fixes and new rules reach every project on a Synapse upgrade. A team can exclude a single rule only with a documented reason (`SynapseArchitecture.rules().except(rule, "reason")`):
- **Protocol-free:** `operation..`, `model..` and `business..` must not import `org.springframework.web..`, `jakarta.servlet..`, `io.grpc..`, `graphql..`, `com.google.protobuf..`, `org.apache.kafka..`, `org.springframework.kafka..` or `io.americanexpress.synapse.api..`.
- **Allowed call directions:**
  - `api → operation`
  - `operation → business | client | data | publisher` (and other operations)
  - `business → business | client | data | publisher`
- **Forbidden:** `business` → `operation`, `model` or `api`.
- **Business beans use the contract:** every Spring bean in `business..` (package or module) implements `BusinessOperation`. Non-bean helpers, value objects and package-private classes are exempt.

### Core programming model (`synapse-core`)
- `ServiceOperation<I, O>`: `O execute(I input)`. `I` and `O` are typically records. Returning `Optional<T>` means "may be absent" (REST: 404 by default when empty). `Void`/`null` means no content.
- Kinds: `QueryOperation<I, O>` and `CommandOperation<I, O>` extend `ServiceOperation<I, O>`. A plain `ServiceOperation` counts as a command.
- Paging: `PageQuery` (cursor or offset, size, sort) and `PageResult<T>` (items, next cursor, optional total). The size defaults to `synapse.paging.default-size` and is capped by `synapse.paging.max-size`. Proposed defaults are 20 and 100; confirm them in the P1 plan.
- Operation name: the bean name in kebab-case by default; overridable with `@OperationName("create-book")`. Optional `@OperationGroup("books")` groups operations into a gRPC service or REST resource and drives documentation.
- **Invocation pipeline:** applied by wrapping every `ServiceOperation` bean (AOP proxy or a decorator registered by auto-config), so plugins, `@RestController` escape hatches, subscribers and tests all get identical behavior:
  1. Correlation context is established. A nested call (one operation calling another) reuses the existing context.
  2. If the `RequestContext` deadline has already passed, the call fails with `TIMEOUT` without executing.
  3. Jakarta Validation runs on `I`; failure produces `INVALID_INPUT` with violations.
  4. A Micrometer Observation (`synapse.operation`, low-cardinality tag `operation`) wraps the call. Nested calls produce child spans.
  5. `execute` runs. A Spring `@Transactional` boundary, if present, sits here, inside the Synapse advisor.
  6. Exceptions are translated: `SynapseException` passes through; anything else becomes `INTERNAL`, with the cause logged and never exposed. Failures are logged as described under *Operation logging*.
- `BusinessOperation<I, O>`: `O execute(I input)`. A separate interface, not a `ServiceOperation`, so it can never be bound to a protocol (bindings reject it at startup). Business owns its **domain types**: operations map their API-facing `model` records to and from domain records, and business code may also take data entities or client DTOs. **Business pipeline:** the same proxy mechanism as operations, but only:
  1. Jakarta Validation on `I` (`INVALID_INPUT`).
  2. A child Observation `synapse.business` (low-cardinality tag `business`, the bean name in kebab-case).

  It adds no correlation, deadline, logging or exception-translation steps: the calling operation already does those, so nothing is logged twice. `@Transactional` works on `execute`. The convention is that the operation is the transaction boundary; a business bean declares `@Transactional` only when it must be atomic on its own, and with default propagation it joins the operation's transaction. The docs warn against `REQUIRES_NEW` and against remote client calls inside a transaction.
- `RequestContext` (protocol-neutral, read-only in services; `RequestContext.current()` is a view over the OpenTelemetry/Micrometer context, see *Context propagation*) holds:
  - the correlation id
  - the verified caller identity (principal name), if any
  - the locale
  - an optional deadline
  - a string attribute map that plugins populate

  It never contains servlet, gRPC or GraphQL types.
- `PublisherOperation<E>`: `void publish(E event)` (blocks until the broker acknowledges) and `CompletableFuture<Void> publishAsync(E event)`. Broker-neutral; implementations come from publisher modules such as `synapse-publisher-kafka`. Correlation id, trace context and deadline travel as message headers.
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

### Input binding (API plugins)
An operation's input record says nothing about where its values come from. Each protocol's binding, in the service module's `api.<protocol>` package, decides that. Values are converted with Spring's `ConversionService`, and Jakarta Validation runs once, on the fully assembled record (pipeline step 3).

**REST sources, in order of precedence (a per-route declaration beats an app convention, which beats a default convention):**

| Location | Default convention | Declared form |
|---|---|---|
| Path variable | `{id}` → component `id` | `.path("bookId", "id")` to rename; a path variable that matches no component fails at startup |
| Query parameter | simple components (string, number, boolean, enum, date/time, lists of these) by name, **GET/HEAD only** | `.query("dryRun")`, `.query("sort_by", "sort")`; app-wide via `RestConventions` |
| Framework types | `PageQuery` binds from `cursor`, `offset`, `size`, `sort` on **any verb** (names configurable under `synapse.api.rest.paging.*`) | none |
| Body | POST/PUT/PATCH: the JSON body fills every component not bound elsewhere | `.body("changes")` to bind the body to one component |
| Header / cookie | **never implicit** | `.header("If-Match", "version")`, `.cookie(...)` per route |
| Request context | none | `.context("requestedBy", ctx -> ctx.principal())` |
| Custom | none | `.from("region", request -> ...)`; the lambda may use protocol types because it lives in `api..` |

```java
// once per application
@Bean
RestConventions restConventions() {
    return RestConventions.builder()
        .query("dryRun")             // any route, any verb, wherever the input has a 'dryRun' component
        .query(WriteOptions.class)   // every component of this shared record from the query string
        .build();
}

// routes declare only their exceptions
rest.get("/books/{id}", getBook);           // id <- path
rest.get("/books", searchBooks);            // author, PageQuery <- query
rest.post("/books/search", searchBooks);    // PageQuery <- query (by type), filters <- body
rest.put("/books/{id}", updateBook)         // id <- path, dryRun <- convention, rest <- body
    .header("If-Match", "version");
```

**Cross-cutting headers** (tenant, channel, client id) are not mapped into input records. They're configured once and land in `RequestContext` attributes, for example `synapse.api.rest.context.headers.tenant=X-Tenant-Id`, and gRPC metadata likewise under `synapse.api.grpc.context.metadata.*`. Operations read them from the context. Route-specific headers, such as `If-Match` → `version`, can still be bound per route.

**Startup checks** (the application fails to start, and the message names the route and component):
- a declared name is not a component of the input record (with a "did you mean" hint)
- a non-nullable component has no source
- two declared sources target the same component

**Conflicts at runtime:** see *Input conflicts*. For example, `PUT /books/42` with body `"id": "99"` is `INVALID_INPUT`, and the same id in both places is accepted. When `.body("changes")` is used and the body type also has a component with the same name as a path-bound one, the same check applies.

**Other protocols:**

| Protocol | By name | Declared |
|---|---|---|
| gRPC | request message fields (proto `snake_case` → `camelCase`) | `.metadata(key, component)`; the deadline is native |
| GraphQL | field arguments (input objects → nested records) | `.header(...)` from the HTTP request; `.source(...)` for values from the parent object |
| Functions (HTTP-triggered) | same rules as REST | same as REST |
| Functions (event-triggered) | the payload | `.attribute(name, component)` for message attributes |
| Kafka subscribers (P3) | the record value | `.header(name, component)` |

### Response mapping (API plugins)
The operation returns a plain value. The REST binding decides the status, the headers and what goes in the body. Like input binding, component names are strings checked at startup, and app-wide rules live in `RestConventions`.

**Status:**

| Case | Default | Route option |
|---|---|---|
| GET with a value | 200 | `.status(...)` |
| `Optional` empty | 404 problem+json with generic code `NOT_FOUND` | `.notFound(BookErrors.BOOK_NOT_FOUND)` for a specific code; `.emptyAs(204)` |
| Empty list or `PageResult` | 200 with empty items (never 404) | none |
| POST | 201 + `Location` when `.created(b -> "/books/" + b.id())` is declared; otherwise 200 | `.status(...)` |
| Accepted for async processing | none | `.accepted(r -> "/jobs/" + r.jobId())`: 202 + `Location` for the status resource |
| null/void result | 204 | `.status(...)` |
| DELETE | 204 | `.status(200)` to return a body |
| Depends on the output | none | `.status(r -> r.queued() ? 202 : 200)`. The lambda lives in `api..`, so the operation stays HTTP-free. |

Errors always come from `ErrorCategory` (see *Errors*), never from these options.

**Headers and body:**
- `.header("X-RateLimit-Remaining", "remaining")` writes an output component to a response header. App-wide rules go in `RestConventions.builder().responseHeader(...)`.
- A component mapped to a header is **removed from the body by default**. `.header("ETag", "version").alsoInBody()` keeps it in both places, and the OpenAPI schema reflects whichever is chosen.
- `.body("book")` makes one component the body. Otherwise the whole output record is the body, minus any header-only components.
- An empty `Optional` can also be avoided by having the operation throw `SynapseException(BookErrors.BOOK_NOT_FOUND, id)` itself. Both paths produce the same problem+json shape.
- `.etag("version")` (or app-wide for a type) emits `ETag`. `If-None-Match` on GET returns `304`. A failed `If-Match` check in the operation/data layer raises `PRECONDITION_FAILED` (412).

```java
record BookResult(Book book, long version, int remaining) {}

rest.get("/books/{id}", getBook)
    .body("book")
    .etag("version")
    .header("X-RateLimit-Remaining", "remaining");
// 200, ETag: "7", X-RateLimit-Remaining: 41, body = the Book JSON
```

**Startup checks:** an unknown component name, a component mapped both to a header and as `.body(...)`, and `.created`/`.accepted` on a route whose output can't produce the location all fail at startup.

gRPC and GraphQL return the output message or object as-is. gRPC can map components to response metadata with `.trailer(...)`/`.header(...)`; GraphQL has no header mapping.

### Configuration namespaces
`synapse.errors.*`, `synapse.json.*`, `synapse.correlation.*` (header name, default `X-Correlation-Id`), `synapse.paging.*`, `synapse.logging.operations.<operation-name>.*`, `synapse.api.rest.*`, `synapse.api.grpc.*`, `synapse.api.graphql.*`, `synapse.api.function.*`, `synapse.client.defaults.*` and `synapse.client.<client-name>.*` (all client protocols), `synapse.kafka.*` (`synapse.kafka.subscribers.<name>.*`, `synapse.kafka.publishers.<name>.*`, `synapse.kafka.clusters.<name>.*`, `synapse.kafka.topics.*`), `synapse.logging.*`, `synapse.security.*`. Every property has metadata (description + default) and appears in the docs site.

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
10. From 2.0.0 GA: **japicmp** against the last release fails on binary-incompatible changes in non-major versions. `…internal` packages are excluded.
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
    - GitHub Actions workflows: `ci.yml` (PR/main: JDK 25 build + JDK 21/25 tests + gates), `nightly.yml`, `codeql.yml`, `dependency-review.yml`, `release-please.yml` (release PR; on merge it calls `release.yml`, because tags created with the default `GITHUB_TOKEN` don't trigger other workflows), `release.yml` (reusable + manual: sign with Bouncy Castle, publish, SBOM, attestation, in the protected `release` environment), `snapshot.yml`, `docs.yml`, `pr-title.yml`
    - Dependabot config (Maven, Actions, docs npm); `SECURITY.md` (supported versions), `CONTRIBUTING.md` (Conventional Commits, CLA, squash merges, release steps), PR title lint, Antora docs skeleton with generated property tables
    - publishing profiles `synapse-release` and `synapse-snapshot` in the root, `synapse-bom` and `synapse-parent` (the latter two don't inherit the root build parent); an enforcer rule keeps `synapse-parent`'s `synapse.version` equal to its own version
    - **Repository setup (maintainers, UI-only):** a protected `release` environment with required reviewers holding `CENTRAL_USERNAME`, `CENTRAL_TOKEN`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`; repository secrets `CENTRAL_USERNAME`/`CENTRAL_TOKEN` for snapshots; optional `RELEASE_PLEASE_TOKEN` so CI runs on release PRs; a `publish-snapshot` label; Pages source set to GitHub Actions; squash merging only (assumed by `CONTRIBUTING.md` and the PR-title check; maintainers to confirm)
- **Acceptance:**
  - `./mvnw verify` passes locally on JDK 25 and in CI on JDK 21/25.
  - A PR with a deliberately failing unit test, formatting error and NullAway error is rejected (demonstrated, then reverted).
  - release-please opens a release PR in a dry run on a fork, proposing `2.0.0-M1` and updating every `pom.xml` and `synapse-parent`'s `synapse.version`; the version it proposes for main after the milestone is recorded (expected `2.0.0-SNAPSHOT`; if not, adjust the config).
  - `snapshot.yml` publishes `2.0.0-SNAPSHOT` from `main` and `2.0.0-pr<N>-SNAPSHOT` from a labelled same-repo PR without the GPG key, does nothing for fork PRs, and skips with a notice when the Central secret is absent.
  - The Antora site builds in CI with a version switcher and one generated property table.
  - The v1 CircleCI config on `develop` no longer has a workflow filtered on `main`.
- **Tests:** context-runner test for the trivial auto-config; an enforcer rule test (a banned dependency fails the build).

### P1: Core + REST + gRPC + Postgres (2.0.0-M1)
- **Scope:**
  - `synapse-core`: operations, invocation pipeline, `RequestContext`, errors, correlation, Jackson 3 customizers, `@Masked`
  - `synapse-test`: operation harness (`SynapseOperations.invoke`) and `@SynapseOperationTest`, `@SynapseRestTest`/`@SynapseGrpcTest`, `assertThatSynapseException`, JSON fixtures, Testcontainers `@ServiceConnection` helpers, `SynapseArchitecture.rules()`
  - `synapse-logging-log4j2`
  - `synapse-api-rest`: JSON only; `RestBindings` DSL, problem+json, OpenAPI from bindings, `@RestController` escape hatch
  - `synapse-api-grpc`: on Boot 4.1 gRPC auto-config / Spring gRPC. Binds operations to generated service methods; the proto↔record mapping approach is decided in the P1 plan.
  - `synapse-data-jpa`, `synapse-data-jdbc`, `synapse-data-postgres`
  - `samples/reference-bookstore` (first cut): `CreateBook`, `GetBook`, `SearchBooks` (paged), `DeleteBook` over REST and gRPC on Postgres with Flyway. Core supplies paging records `PageQuery`/`PageResult<T>` (cursor or offset); the REST plugin maps query parameters and the `Link` header, and gRPC maps `page_token`/`page_size`.
- **Acceptance (each backed by a test):**
  1. An app with `synapse-api-rest`, one operation bean and one `RestBindings` bean serves the endpoint with no other Synapse configuration.
  2. Adding `synapse-api-grpc` + proto exposes the **same** operation over gRPC with no change to the operation or model classes (only a new `api.grpc` bindings class). The package-level ArchUnit test proves `operation..`/`model..` import no protocol types, and fails when a probe class that imports `org.springframework.web` is added to `operation..`.
  3. One `SynapseException(BookErrors.NOT_FOUND, id)` yields REST 404 problem+json with `code`, `correlationId`, and no `detail` by default; with `synapse.errors.include-detail=true` the formatted detail appears. The same exception yields gRPC `NOT_FOUND` with the code in trailers. A per-operation override changes the REST status for one operation only.
  4. A validation failure yields REST 400 with `violations[]`, and gRPC `INVALID_ARGUMENT` with `BadRequest` field violations.
  5. An unexpected `RuntimeException` yields 500/`INTERNAL`. No stack trace or exception class name appears in any response (asserted).
  6. The correlation id is accepted from the inbound header or gRPC metadata, generated if absent, returned on the response, present in MDC, JSON logs and Observation.
  7. `/v3/api-docs` includes every DSL route with request/response schemas.
  8. With `synapse-data-postgres` and a single store configured only through standard `spring.datasource.*` (or `@ServiceConnection`), the context contains Boot's DataSource and **no Synapse-built DataSource/EntityManagerFactory**. With two named stores (`books` on Postgres, `legacy` on a second Postgres container standing in for another vendor), each gets its own DataSource, EntityManagerFactory, transaction manager, repositories and health indicator. `@Transactional("legacy")` targets the right one, and an ambiguous unqualified `@Transactional` without a `primary` fails at startup. JPA auditing fills created/modified timestamps and user (`AuditorAware` from the security context, else a configurable system user) for `Auditable` entities, whether they extend `AuditedEntity` or implement the interface with custom columns. `@Version` optimistic locking raises `CONFLICT`. Base-entity equality is by id (proxy-safe). UUIDv7 ids are generated before insert. With Flyway present, `ddl-auto` is `validate`, and `update` in a non-dev profile fails startup.
  9. `@Masked` fields are masked in JSON responses and in log output.
  10. Every auto-config passes the context-runner rules (gate 6).
  11. Service-model behavior:
      - An operation that calls another operation produces a child span and reuses the correlation id.
      - A `@Transactional` operation that throws a `SynapseException` rolls back.
      - An expired gRPC deadline fails with `DEADLINE_EXCEEDED` before `execute` runs.
      - A successful call writes no log line, and a failure writes exactly one, at the level for its category.
      - A page size above `synapse.paging.max-size` is rejected with `INVALID_INPUT`.
      - Business layer:
        - A `BusinessOperation` called from an operation produces a `synapse.business` child span and validates its input.
        - A `@Transactional` business bean joins the operation's transaction.
        - Binding a `BusinessOperation` to REST fails at startup.
        - The ArchUnit test fails for a business bean that doesn't implement `BusinessOperation`, and for a `business..` class that imports `operation..`.
  12. Input binding:
      - `UpdateBook` receives its id from the path, `version` from `If-Match`, `dryRun` from a `RestConventions` rule, and the rest from the body.
      - A mismatched body id returns 400.
      - A `PageQuery` binds on both GET and POST.
      - A tenant header configured under `synapse.api.rest.context.headers.*` appears in `RequestContext`.
      - A misspelled component name in a binding fails application startup with a message naming the route.
      - Response mapping:
        - `.created(...)` yields 201 with `Location`; `.accepted(...)` yields 202 with `Location`.
        - An empty `Optional` yields 404 with the route's `.notFound(...)` code.
        - An output component mapped with `.header(...)` appears as a header and not in the body.
        - `.etag(...)` plus `If-None-Match` yields 304.
  13. The reference sample's ITs run against Postgres via Testcontainers in CI on JDK 21 and 25.
  14. Context propagation: a `tenant` attribute and the correlation id received on a REST call are readable through `RequestContext.current()` inside an `@Async` method and on a virtual thread, appear in MDC, and are forwarded to an outbound call only when allow-listed. An inbound baggage key not on the allow-list is dropped. The principal never appears in outbound baggage.
  15. Test support: the reference app's tests use `SynapseOperations.invoke`, `@SynapseOperationTest`, `@SynapseRestTest` and `@SynapseGrpcTest`, and a one-line `SynapseArchitecture.rules()` test. Synapse's own tests pass with `spring.threads.virtual.enabled` both true and false.
- **Design constraint:** the operation contract and pipeline must allow a reactive variant (`ReactiveServiceOperation<I, O>` returning `Mono`/`Flux`) in P6 without breaking changes.

### P2: Outbound clients (M2)
- **Scope:**
  - `ClientOperation<I, O>` in `synapse-core`; `synapse-client-core` (SPI, shared wiring); the `-auth-oauth2` and `-auth-token-relay` add-ons
  - `synapse-client-rest`: `RestClientBindings` producing `ClientOperation` beans on Spring's `RestClient`, configured by `synapse.client.<name>.*` (base URL, connect/read timeouts, max connections, SSL bundle, auth, logging). Also:
    - SSRF: host pinned to the configured `base-url`, no redirects by default; `InetAddressFilter` for clients that declare `dynamic-target`
    - correlation/trace/deadline propagation, plus allow-listed `RequestContext` attributes
    - a downstream error decoder that mirrors status → `ErrorCategory` (see *Downstream error mapping*), never forwards downstream detail, supports per-call/per-client overrides, and handles empty error bodies
    - failure logging by default, plus opt-in `basic`/`headers`/`full` levels with redaction (no `Authorization`/cookies)
    - resilience off by default except timeouts; opt-in safe retries (Spring Framework 7 core retry); the `synapse-client-resilience4j` add-on for circuit breaker/bulkhead and informational health; `.idempotent()` POSTs with a reused `Idempotency-Key`
    - URL building only via URI templates (encoding guaranteed)
  - `synapse-client-grpc`: `GrpcClientBindings` producing `ClientOperation` beans; channel defaults, deadlines, metadata propagation, status mirrored to `SynapseException`.
  - `synapse-client-graphql`: `GraphQlClientBindings` on `HttpSyncGraphQlClient`; a GraphQL `errors[]` entry raises `SynapseException` even on HTTP 200.
  - Sample `internal-grpc` (service A calls service B over gRPC).
- **Acceptance:** WireMock- or in-process-server tests for each error/timeout path (Synapse's own tests prove real timeouts, TLS, SSRF pin and redirects over HTTP; `@SynapseClientTest` works with the default `MockRestServiceServer` and with `server = STUB`), including:
  - A 5xx with an empty body raises an exception.
  - Query/path values containing `&`, `/`, `#` and non-ASCII characters are encoded correctly.
  - SSRF: a redirect to another host is refused, and a `dynamic-target` client is blocked from `169.254.169.254`.
  - Header redaction works.
  - Each downstream status mirrors to its category, with no downstream detail in our response.
  - 401/403 handling:
    - Under OAuth2 client credentials, a 401 triggers one token refresh and retry. A second 401 yields 500 to our caller, an ERROR log and `synapse.client.auth.failures`.
    - A 403 under service credentials yields 500 unless `.onStatus(403, ...)` maps it.
    - Under token relay, 401/403 mirror.
  - Error hooks:
    - A per-call `.onError` can `raise` a team error, `recover` with a fallback value, or fall through with `useDefault`.
    - A client-wide handler applies to every call of that client, and the per-call handler wins over it.
    - Business code can catch `ClientCallException` and read its category and downstream code.
    - `.failWhen` turns a 200 response carrying an error code into a mapped failure.
    - An empty or HTML error body reaches the handler as raw text, with no exception from parsing.
  - Retries:
    - With retries disabled (the default), a 503 is not retried.
    - With retries enabled, a GET is retried and a plain POST is not.
    - An `.idempotent()` POST is retried with the same `Idempotency-Key`.
  - Only allow-listed context attributes are propagated.
  - Ambiguous `ClientOperation` injection fails at startup, and `@Client` resolves it.
  - The OAuth2 add-on obtains, caches and refreshes a client-credentials token (mock authorization server).
  - The same business code works against a REST and a gRPC `ClientOperation` with identical I/O types.

### P3: Kafka (M3)
- **Scope:** `synapse-kafka-core` (shared clusters, headers, serde, observation). `synapse-subscriber-kafka` binds topics to operations through `KafkaBindings` in the service module's `api.kafka` package (`synapse.kafka.subscribers.<name>.topics/operation/delivery/retry.*/dlt.*`). `synapse-publisher-kafka` implements `PublisherOperation<E>` (blocking `publish`, async `publishAsync`, failing loudly) from `KafkaPublisherBindings`. JSON payloads by default. Both propagate correlation and observation through headers. Sample `event-driven-kafka`.
- **Acceptance (Testcontainers Kafka):**
  - at-least-once: a failing operation is retried N times, then lands on the DLT, and the offset commits only after success or DLT
  - at-most-once: committed before processing, documented loss
  - exactly-once: transactional read-process-write
  - poison-pill: no infinite redelivery
  - category-aware retries: an `INVALID_INPUT` failure reaches the DLT on the first attempt; an `UNAVAILABLE` failure is retried with backoff first; DLT records carry the error code, category and attempt count
  - `retry.mode=topics` routes retries through retry topics; the default blocking mode preserves per-key order
  - publisher send failure surfaces to the caller: `publish` throws `UNAVAILABLE`/`TIMEOUT`, and `publishAsync` completes exceptionally
  - `publish` honors the `RequestContext` deadline and the send timeout
  - business code using `PublisherOperation<E>` compiles without any Kafka dependency
  - published records carry `ce_specversion`, `ce_id` (UUIDv7), `ce_source`, `ce_type`, `ce_time`, `content-type`, `X-Correlation-Id` and `traceparent`, and the value is the plain JSON payload
  - a multi-type topic routes each `ce_type` to its operation; an unknown type is skipped and counted (or DLT'd when configured); a record without `ce_*` headers is still consumed by a single-type binding
  - batch subscribers actually invoke the operation for every record; a `.batch()` failure commits the records before the failing one and retries/DLTs from it
  - dedupe: with `dedupe.store` set, a redelivered event (same `ce_source` + `ce_id`) is skipped; with a shared JDBC store, a rolled-back operation leaves no processed marker
  - a second named cluster (`synapse.kafka.clusters.<name>`) is used by a publisher and a subscriber selected with `.cluster(...)`; an unknown name fails startup
  - a DLT send logs one ERROR line without the payload; spans link producer and consumer through `traceparent`
  - startup fails when a subscribed topic or its DLT is missing (`verify=fail`); `auto-create=true` creates them
  - default group id is `<app>.<subscriber>`
  - the `synapse-kafka` Actuator endpoint pauses/resumes a subscriber and replays DLT records (max count enforced); it is not exposed over HTTP by default
  - `@SynapseKafkaTest` and the publisher capture helper are used by the module's own tests and the `event-driven-kafka` sample

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
- **Acceptance:** for each module, a context test proves Boot creates the connection infrastructure for a single store, and that a second named store of the same technology is built with Boot's builders. A CRUD + auditing + optimistic-locking IT runs (open-source stores on PR; commercial ones nightly). Redis key/value serializers are explicitly String/JSON (not JDK serialization) for both the store and `@Cacheable` caches, and Boot's `spring.data.redis.*` properties work unchanged for a single store. A replica-configured store routes `readOnly` transactions to the replica container, verified by checking which container received the query. `@SynapseDataTest("<store>")` starts only that store.

### P6: Reactive (M6)
- **Scope:**
  - `ReactiveServiceOperation` + pipeline
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
| PATCH / partial updates: RFC 7396 JSON Merge Patch with a core tri-state `Patch<T>` (absent/null/value) mapped from gRPC `FieldMask` and GraphQL argument presence, vs PUT only, vs JSON Patch. P1 ships PUT only. | Maintainers, P4 plan | P4 (REST codecs) |
| Kafka health indicator: does Boot 4 provide one, or does `synapse-*-kafka` need its own (and should it join readiness)? | Implementer verifies in P3 plan | P3 |
| Transactional outbox add-on (`synapse-publisher-outbox`): ship in P3 or after P5 (it needs a data store), relay design (polling vs CDC-only) and table schema | Maintainers, P3 plan | P3 (or later) |
| Schema-registry add-ons (`synapse-kafka-avro`, `-protobuf`): Confluent serializers vs Apicurio (license and Boot 4 compatibility), and whether they ship in P3 or later | Maintainers, P3 plan | P3 (or later) |
| Idempotency keys (`Idempotency-Key`, IETF httpapi draft): opt-in per route with a pluggable response store (JDBC/Redis), vs key in `RequestContext` only, vs not in Synapse. Decide together with client retries. | Maintainers, P2 plan | P2 |
| problem+json `type` URI base (e.g. configurable `synapse.errors.type-base-uri`, default `about:blank`) | Implementer proposes in P1 plan | P1 |


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
