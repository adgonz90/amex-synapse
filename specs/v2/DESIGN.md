# Synapse 2 design and decisions

**What this is.** The long-lived design record for Synapse 2: goals, principles, every decision with its reasons and
the alternatives rejected, the 2.x roadmap, open questions, the v1 facts the design relies on, and the sources
consulted. **What to build** is in [BUILD.md](BUILD.md); when the two disagree, fix one of them. They must never
silently diverge. New design work starts here, and new decisions are recorded here and reflected in BUILD.md.

History:
- 2026-10-27: spec interview.
- 2026-10-27 to 2026-10-30: module-by-module decision rounds (service, API plugins, business, client, data,
  messaging, core/test).
- 2026-10-31, first review: model ownership, contract jars, native image, framework errors.
- 2026-10-31, second review: five independent reviewers (application developer, framework maintainer, operator,
  security, consistency/standards), followed by eight decision rounds with the maintainers. It produced the lean GA
  scope and the split into BUILD.md and this document.

---

## 1. Summary
Synapse v1 (0.4.x) is a set of Spring Boot 3 libraries that let teams build layered services quickly with
prescriptive conventions. In practice it has four overlapping web stacks, no Spring Boot auto-configuration, data
modules that re-implement Boot, untested "new" modules and several data-loss and security bugs (section 7).

Synapse 2 is a ground-up rewrite on Spring Boot 4 / Spring Framework 7, built with JDK 25 and running on Java 21+. It
keeps Synapse's goals and layered structure. The central change: **services are protocol-agnostic operations**, and
**API plugins** (REST, gRPC, GraphQL, cloud functions, Kafka subscribers) expose them by adding a starter plus a small
binding. Every module is real Boot auto-configuration proven by tests, with secure defaults.

## 2. Goals and non-goals

### Goals (2.0.0 GA)
1. **Protocol plugins.** A developer writes an operation once as protocol-free code and exposes it over REST, gRPC,
   GraphQL, AWS Lambda or Kafka by adding the plugin and a few lines of binding.
2. **Correct by construction.** Every module is a Boot auto-configuration that backs off to user beans and is covered
   by context-runner tests and real-infrastructure ITs. No configuration exists that is never triggered.
3. **Secure by default.** No internals or stack traces leak to callers; sensitive data is masked in responses and
   logs; clients have sane timeouts and SSRF protection; inbound context and identity have explicit trust rules; the
   supply chain is signed, attested and scanned.
4. **Minimal developer work, easy to update.** One parent or BOM aligned with Boot, conventions over configuration,
   sensible defaults that are overridable, semver with API-compatibility checks, an automated changelog and a
   migration guide.
5. **True to the original structure.** The layers stay: service, business, subscriber (now an inbound plugin bound in
   the service module), client, data, publisher, framework/core, utility, along with the prescriptive consumer layout,
   archetypes and the error model.
6. **Production-ready operations.** Bounded inbound load, a defined shutdown, readiness that can't cascade, fast
   startup paths (AOT cache, native where verified), and a stable observability contract.

### Non-goals
- Re-implementing anything Spring Boot already auto-configures. Synapse only customizes Boot, and builds additional
  instances (named stores, clients from bindings) with Boot's builders.
- Source or binary compatibility with 0.4.x; a migration guide replaces it.
- Porting v1's deprecated or duplicate stacks; a reactive stack in 2.0 (a 2.x add-on).
- SOAP clients.
- Library jars choosing an authentication mechanism or a logging backend. (The consumer parent makes Log4j2 the
  prescriptive default; the libraries depend only on SLF4J.)
- In-process authorization frameworks; authorization stays external (e.g. OPA).
- v1's date/number/telephone/email/string/collection/URL wrappers, `CryptoUtil`, `DeepCopyUtils`, `BaseModel`, and
  global `BigDecimal`/`Double` serializers.
- Framework code that needs Java 22+ APIs; multi-release jars.

### Anti-goals (failure even if it "works")
- Two ways to do the same thing (v1's four web stacks).
- Synapse properties that shadow Boot properties, so a Spring developer's setting silently does nothing.
- A GA surface too large for the maintainers to keep compatible on every Boot upgrade.

## 3. Principles
- **Spring Boot first:** customize Boot; never replace its beans; Boot keys for what Boot builds, Synapse keys for what
  Synapse builds; no aliases.
- **Protocol-free core:** operations, business and models never see protocol types; protocol concerns live in plugins.
- **Every module owns its model; a caller speaks the callee's model.**
- **Explicit over magic:** bindings are visible beans, compiler-checked through method references, validated at
  startup.
- **Safe defaults, explicit opt-ins:** retries, readiness dependencies, payload logging, async logging, Actuator write
  operations and trusting inbound context at public edges are all explicit choices.
- **Lean core, add-ons for the rest:** anything with its own dependency or a narrow audience is an add-on.

---

## 4. Decision record
Each row: why it was decided and what was rejected (from the decision rounds), and how the second review on
2026-10-31 changed it. The decision itself, as it must be built, is in BUILD.md. Search BUILD.md for the area name.

### 4.1 Decisions from the second review (2026-10-31)
| Area | Decision | Why | Rejected alternatives |
|---|---|---|---|
| GA scope | Lean GA; reactive stack, GCP functions, GraphQL client, token relay, Resilience4j, DB2/Cassandra/Couchbase, read replicas, multi-tenancy, Kafka exactly-once/named clusters/dedupe/batch/replay/outbox/registry add-ons and the protobuf REST codec become 2.x add-ons. | About 36 artifacts with a second (reactive) stack and nine datastores was too large to keep compatible on every Boot minor; reactive would bring back the duplicate-stack problem. | Incubating extras at GA; full scope. |
| Versioning | Synapse semver decoupled from Spring's cadence; major tracks Boot's major; minor = features or a raised Boot baseline; patch = fixes and dependency/CVE bumps any time; compatibility matrix in docs; only the latest minor supported. | Synapse ships its own features and must ship CVE bumps between Boot releases. springdoc follows the same model (major per Boot generation, own minors, compatibility matrix). | One Synapse minor per Boot minor; previous-minor security window; LTS minors; startup compatibility check (Spring Cloud style). |
| 0.4.x end of life | GA + 6 months. | Boot 3.5 open-source support ended 2026-06-30, so Spring CVEs can't be patched openly for longer. | Best-effort for 12 months; commercial Boot 3.5 support. |
| Native image | AOT-ready everywhere; native verified for core, REST, gRPC, AWS functions. | Native for every module would need native tests for every driver and library, and bean-defining properties freeze at AOT time. | Native for every module; JVM only. |
| Client configuration namespace | `synapse.client.<name>.*` stays the single place; Boot's per-name keys don't apply; `defaults` reserved. | One place per client ("otherwise two places"); the abstraction is the point. | Boot keys for transport + Synapse extras; both with precedence. |
| Boot aliases | Dropped: health path, gRPC sizes, JSON naming, OpenAPI toggle, API-versioning properties. | A Spring developer's standard key must work; defaults are set at lowest precedence on Boot's own keys. | Keep aliases with documented precedence; keep only the health alias. |
| Client API | Typed protocol-free interface per downstream, bound by method reference; `ClientOperation` internal. | Typed, navigable, no string qualifiers; matches Spring HTTP interfaces, Quarkus, Helidon and Micronaut while staying protocol-neutral. | Generic-type injection with `@Client`; Spring `@HttpExchange` interfaces (HTTP-specific). |
| Consumer layout | data-, client-, publisher- modules from day one; events in `contract-<service>` from the start. | Uniform, reuse-ready layout as in v1. | Package first for every layer; both via archetype. |
| Business beans | `BusinessOperation` stays mandatory. | Uniform typed, validated, traced business calls. | Optional with plain beans; multi-method stereotype. |
| Binding references | Method references everywhere. | Compiler-checked and refactor-safe (Spring Data Commons 4.1 TypedPropertyPath precedent, with native metadata). | Strings validated at startup; both. |
| Client error handling | Per-call handler → per-client handler → authenticator default → mirror table; property status map dropped. | Seven precedence levels were too hard to reason about. | Keep all seven; handlers only. |
| Proto ↔ record mapping | Descriptor-based mapping by name at runtime, startup-verified, explicit overrides for `oneof`/renames. | Zero mapping code for most methods; no Synapse code generator to maintain; no existing records generator found. | Generated records/adapters; MapStruct with a protobuf SPI. |
| Operation kinds | `ServiceOperation` sealed into Query/Command. | Intent is always explicit; protocol defaults and retry safety never depend on a forgotten marker. | Plain = command. |
| Data configuration | All stores named under `synapse.data.stores.<name>`; Synapse builds every store and runs migrations per store. | Uniform for 1 or N stores; one place, like clients; Boot migrates only one DataSource. | Boot for the primary + Synapse for extras; both for a single store. |
| API versioning default | Off until the first `.version()`; then path-segment with a predicate. | Spring 7 requires a version by default once enabled (400 otherwise). | Always on with a default version; leave it to Boot entirely. |
| Inbound context trust | Trusted with hard limits; `trusted=false` at public edges; security values only from verified credentials. | Spoofed tenant headers; CVE-2026-59323 (unbounded baggage). | Untrusted by default; docs only. |
| Identity | Principal only from Spring Security; JWT re-validation by default; sidecar trusted header opt-in. | Works with OPA/Envoy meshes; Envoy's jwt_authn strips client copies of its payload header. | JWT only; trusted header only; optional authorizer SPI. |
| Kafka control endpoint | Read-only by default; pause/resume need explicit access and Spring Security. | Boot only requires authentication (no role) for actuators; per-pod effects. | Read-only only; as originally decided. |
| GraphQL limits | Depth/complexity limits, operations cap, introspection off. | OWASP GraphQL guidance. | Depth only; Boot defaults. |
| Inbound load | Fixed concurrency cap per plugin + default server deadline. | With virtual threads nothing else bounds in-flight work; small pods get OOM-killed. | Adaptive limiter add-on; gateway only. |
| Shutdown | Defined order with a SIGTERM-under-load test. | HTTP, gRPC, Kafka and publishers otherwise stop independently. | Boot defaults + docs. |
| Readiness | App state only; every store/client opt-in. | Avoid cascading unreadiness from shared or saturated dependencies. | Primary store by default; all stores. |
| Startup I/O | No remote I/O during bean creation. | AOT-cache training, CRaC/SnapStart and rollouts during broker blips. | Off switch; docs. |
| Lambda | Decided in the P4 plan. | Needs a prototype (SnapStart vs native, flushing, generators). | JVM+SnapStart now; native now. |
| Observability contract | Stable tags operation/kind/outcome/error.category/error.code; business span switch. | SLOs need outcome tags; tags become public API. | Minimal + Boot defaults. |
| Logging default | Log4j2 synchronous via parent/archetype; async opt-in. | Team preference for Log4j2; Log4j recommends sync unless benchmarks show a gain. | Log4j2 in every starter; archetype only; async default. |
| Containers | Parent configures build-image with Java 25 and AOT cache; archetype adds keep-alive. | The AOT cache needs a Java 25 runtime; worker apps exit without keep-alive under virtual threads. | Docs only; nothing. |
| Kafka timing | Pausing back-off + per-record timeout; no deadlines on messages. | Spring Kafka's sleeping back-off can exceed max.poll.interval.ms. | Pausing back-off only; timeout only. |
| Scheduling | `synapse-scheduling` add-on at GA. | One dependency for locks, per-run context, CronJob exit codes and Batch conventions. | Core support + docs/sample (recommended by the review); docs only. |
| Release security | Secret-free builds, environment-only publishing, rulesets, tag-on-main check. | A compromised test or plugin dependency could steal signing keys; unreviewed tags. | Rulesets only; as is. |
| API stability | `@Incubating`, property metadata diff, internal-import rule, deprecate-then-remove-in-major. | japicmp alone misses property renames and internal use. | japicmp + incubating; japicmp only. |
| CI | Full tests on JDK 25, smoke on JDK 21, full JDK 21 nightly; thin vendor modules. | PR time and coverage gates vs nightly-only commercial databases. | Everything on every PR; changed modules only. |
| Version pins | Runtime convergence pins in `synapse-bom`. | Consumers get the versions Synapse tested. | Root only. |
| problem+json `type` | `about:blank` + status-phrase title unless a base URI is configured, then per-code types. | RFC 9457 title rule; per-code types identify problems and link docs. | Always per-code (URN); always about:blank. |
| Tracing dependency (builder check) | `synapse-core` requires Micrometer Tracing; service modules add the OpenTelemetry bridge; startup fails if tracing/baggage is off while Synapse context is used. | One propagation mechanism; no silent loss of correlation across services. | Synapse fallback propagation without tracing; correlation-only fallback. |
| Log4j2 placement (builder check) | The parent manages `synapse-logging-log4j2`; each `service-*` module declares it (the archetype adds it). | Published library modules (contract, client, data, publisher, business) must not carry a logging backend; Maven profile activation can't see POM properties, so a parent-only switch isn't possible. | Parent profile by file presence; packaging-based activation; every module. |

### 4.2 Implementation calls made while writing BUILD.md (2026-10-31)
These follow from the decisions or the standards; maintainers can change any of them.
- Conditional requests: strong ETags (version + representation suffix), preconditions evaluated before `execute`
  (412), version clash without `If-Match` is 409 (RFC 9110).
- 401 carries `WWW-Authenticate`; 405 carries `Allow`; 304 carries `ETag`/`Cache-Control`/`Vary` (RFC 9110).
- 202 sends `Location` and also links the status resource in the body (RFC 9110 defines `Location` only for 201/3xx).
- gRPC: `CONFLICT` → ABORTED, unique violations → ALREADY_EXISTS, the code in `google.rpc.ErrorInfo.reason`
  (gRPC status-code guidance).
- `Idempotency-Key` sent as a quoted RFC 8941 string (IETF httpapi draft).
- CloudEvents: `ce_source` default `urn:service:<app>`; `ce_type` = configurable prefix + publisher name;
  `ce_time` = occurrence time when known (CloudEvents 1.0).
- UUIDv7 monotonic within a millisecond (RFC 9562 §6.2) using the `Clock`; documented as not a security capability;
  base entities implement `Persistable`.
- Error-code map keys use bracket notation (Boot's map binding strips other characters).
- Actuator endpoint id `kafkasubscribers` (Boot warns on hyphenated ids).
- OpenAPI 3.1.
- Observation is the outermost pipeline step; only the outermost invocation logs a failure.
- The protocol-free ArchUnit list also covers `org.springframework.http..` and `org.springframework.graphql..`.
- Paging: per-route sort allow-list, max offset, HMAC-signed opaque cursors (secret generated per start if unset).
- Bound input/output types must not be persistence entities (ArchUnit + startup check).
- Client path variables reject `.`/`..`; header logging by allow-list; token-endpoint traffic never logged.
- Kafka DLT headers via a Synapse `ExceptionHeadersCreator` (no stack trace, message or class name).
- Redis JSON with typed serializers or an application-package `PolymorphicTypeValidator`.
- Context limits default to 16 entries / 4 KB.
- Per-operation settings under `synapse.operations.<operation-name>.*`; client logging as
  `synapse.client.<name>.logging.level`.
- Subscriber name: `.named(...)`, default the bound operation name.
- Kafka subscriber metric `synapse.kafka.subscribers.unknown.types`.
- RestConventions apply by type (a consequence of method-reference bindings).
- Non-production profiles listed in `synapse.environment.non-production-profiles` (default `dev,local,test`).
- Scheduling add-on phase P4; samples `scheduled-and-batch` added.
- Snapshot PR publishing via a `workflow_run` job from `main` that publishes prebuilt artifacts.
- Wrapper checksum, Maven dependency submission, GitHub App token preferred for release-please, OpenSSF Scorecard in
  P0.
- From the builder check: data stores build their EntityManagerFactory without JDBC metadata access (explicit dialect
  and database version) and run migrations then schema validation in `SmartLifecycle.start()` before inbound plugins,
  which keeps the no-remote-I/O rule (Hibernate `allow_jdbc_metadata_access`, verified in the P1 plan).
- `@Masked` applies to API responses (a masking writer, not Boot's shared mapper) and to everything Synapse logs; never
  to outbound requests, events or persisted data.
- Model, operation, business and contract packages must be `@NullMarked`; in unmarked packages components count as
  nullable.
- A run is production unless an active profile is in the non-production list (fail-safe); test slices activate `test`.
- `synapse-parent` repeats the BOM's runtime pins in its own dependency management, because Boot's inherited management
  wins over an imported BOM.
- Naming: operations `<UseCase>Operation` (default name drops the suffix), inputs `<UseCase>Request`.
- Synapse jars depend on Boot's `spring-boot-grpc-server`/`-client` modules, never the gRPC starters (which bring a
  logging starter).

### 4.3 Decisions from the interview and module rounds (2026-10-27 to 2026-10-31)
| Area | Why | Rejected alternatives | 2026-10-31 review |
|---|---|---|---|
| Release line | Clean slate; the "V2" name matches the version. | New groupId (splits the brand); in-place incremental migration (drags v1 design along); 1.0.0 (confusing next to "V2"); a 6-month tail | Changed: 0.4.x reaches end of life 6 months after GA (not 12). Spring Boot 3.5, which 0.4.x runs on, left open-source support on 2026-06-30 (endoflife.date), so Spring CVE fixes can no longer be shipped openly. |
| Java | Runs on both LTS lines. The only notable loss is final Scoped Values; use Micrometer context-propagation instead. Runtime gains (JEP 491 virtual threads without pinning, compact headers, AOT cache) still apply on 25. | Java 25 only; Java 17; MR-JARs | Unchanged. |
| Platform | Latest platform; Boot 4.1 adds gRPC auto-config (Spring gRPC 1.1, Boot-owned `spring-boot-starter-grpc-server`/`-client`) and an `InetAddressFilter` for HTTP clients. Sources read 2026-10-31: Boot 4.0 and 4.1 release notes, Boot 4.0 migration guide, GitHub milestones. | Boot 3.5 | Unchanged. |
| Build tool | Maven deploys to a local staging directory and a small script publishes it through Sonatype's Central Publisher API, so the build needs no publishing plugin or secrets; makes a consumer parent POM and Maven archetypes possible; enterprise familiarity. | Gradle Kotlin DSL (faster cached builds, but no consumer parent or archetypes, and third-party Central publishing) | Unchanged. Changed 2026-11-01 (P0 build hardening): `central-publishing-maven-plugin` and `maven-gpg-plugin` are no longer used. The secret-free build job stages artifacts with `deploy -DaltDeploymentRepository=staging::file:…`; the publish job signs, bundles and uploads them with `.github/scripts/central-publish.sh` (release) or PUTs them to the snapshot repository (snapshots). |
| CI/CD | Maintainer decision. | CircleCI | Unchanged. |
| Branching | Most widely used OSS model (e.g. Spring Boot, Kubernetes, Node). Avoids wrong-base PRs; release-please, Dependabot and CodeQL all assume one default branch. | Gitflow; long-lived `v2` branch; separate repository | Unchanged. |
| Release automation | Automated, auditable releases with no commits from CI. | maven-release-plugin via `workflow_dispatch`; JReleaser; release-please's beta-style prereleases (`2.0.0-beta01`) | Release workflow is called by release-please (tags made with the default GITHUB_TOKEN start no workflows). Builds run without secrets; a separate environment job signs, publishes and attests. |
| Snapshots | Lets teams try a fix before release without publishing unreviewed fork code or exposing the signing key. | v1 behavior (every branch, one version); `main` only; PR snapshots on every push; `2.0.0-123-SNAPSHOT` (reads like a build number) | Changed: snapshot publishing moved to a secret-free build plus a publish job in the `snapshots` environment (limited to main); PR snapshots are published by a workflow_run job from prebuilt artifacts, never by PR code. |
| Programming model | Halves the surface area early; reactive teams are still served at GA. | Both from day 1; reactive first; never | Changed: the reactive stack is a 2.x add-on, not part of 2.0. Virtual threads + MVC cover request/response services; reactive would bring back a second stack (the v1 problem) and R2DBC drops JPA features. |
| Native image | Faster startup and lower memory for functions and scale-to-zero services; it must be designed in from P1, because retrofitting hints later is costly. | JVM at GA with native later; not supported | Changed: AOT-ready everywhere; native officially supported and tested only for core, REST, gRPC and AWS functions. Boot's ApplicationContextRunner does not run in a native image, bean-defining properties freeze at AOT time, and SnapStart does not support native images. |
| Virtual threads | Blocking I/O throughput for the imperative model; Boot already supports it. | Off and documented; decide in the P1 plan | Added: inbound concurrency limits (thread-pool settings no longer bound work with virtual threads; Boot docs), keep-alive for worker apps (Boot recommends spring.main.keep-alive). |
| Context propagation | One propagation mechanism shared with traces and logs; services get the direct access an earlier internal version lacked. | A separate Synapse ThreadLocal and headers next to OTel (two systems that can disagree); OTel only with no service access | Changed: inbound context is trusted only within hard limits and can be switched off at public edges; security-relevant values never come from headers or baggage (CVE-2026-59323 showed unbounded baggage can exhaust memory). Synapse removes non-allow-listed baggage from outbound client calls. |
| Core packaging | One dependency and one version for consumers; Boot's guidance says splitting auto-config and starter modules is optional when there are no optional features. | Contracts jar + core; Boot-style code/autoconfigure/starter split per module | Unchanged. |
| Operation contract | Protocol-agnostic unit that maps 1:1 to functions and GraphQL fields and groups naturally into gRPC services and REST resources. Successor to v1's `BaseService.execute`. | Annotated multi-method service classes; plain beans with no contract | Unchanged. |
| Contract naming | "Operation" is the term OpenAPI (`operationId`), GraphQL and API design guides use for one callable unit, and it avoids clashing with gRPC "service" (a group of methods) and Spring `@Service`. | `SynapseOperation` + `BusinessOperation`; a `*Service` family (v1's `BaseService` continuity, but overloaded) | Unchanged. |
| Business layer | Same typed, validated, traced contract as operations, without extra modules. A proxy rather than a v1-style `final execute()` base class, because a final template method silently disables `@Transactional`. | Always a business module; plain beans with only a convention; a `@BusinessService` stereotype; a v1-style base class; a shared model module; entities everywhere | Kept mandatory BusinessOperation (reviewers suggested optional; maintainers kept it for uniform typed, validated, traced business calls). |
| Model ownership | One rule for every boundary: no shared "god model", entities shaped by storage and records shaped by each use case; other services and subscribers use exactly the types the provider compiles against, with no code generation in client modules (gRPC needs a published, in-sync artifact anyway). | Separate API and domain records with two mappers per flow; entities hidden behind data-layer store beans; consumers defining their own records (tolerant reader); generating client types from published OpenAPI/proto files; `.proto` published as source only; events owned by the `publisher-*` module | Rule kept; consequence of "modules from day one": a service that publishes events needs its contract module from the start. |
| Error codes location | Business code raises most domain errors and may not import `model`. | A shared `error` package; keeping them in `model` with an ArchUnit exception | Codes callers may match live in the contract enum (an enum can't be "re-exported"). |
| Operation kinds | Type-checked intent shared by every protocol, so REST and GraphQL can't disagree about whether an operation is safe to retry. | Annotations (`@Query`/`@Command`, not type-checked); no kind (every binding repeats intent) | Changed: ServiceOperation is sealed into Query/Command, so intent is always explicit; a CommandOperation can't be bound to GET (RFC 9110 safe methods). |
| Transactions | Explicit and standard. Automatic transactions would hold connections open across slow remote client calls. | Automatic transaction by operation kind; data-layer-only transactions | Unchanged. |
| Operation composition | Pragmatic; matches how teams build, with consistent behavior on every call. | Forbidden via ArchUnit; allowed with an ArchUnit warning | Unchanged. |
| Authorization | Matches how the maintainers' organization authorizes APIs; keeps the framework small. | Spring method security as the Synapse convention; Synapse `@RequiresPermission`; `OperationAuthorizer` SPI; first-class OPA module | Unchanged. |
| Paging | A single convention across protocols; fixes v1's double-slicing `PageResponse`. | Spring Data `Pageable`/`Page` in service modules (offset-only, unstable JSON shape); no paging in core | Unchanged. |
| Operation logging | PII-safe by default without losing failures. | One INFO line per call; no pipeline logging | Clarified: only the outermost invocation logs a failure; observation is the outermost pipeline step so validation and deadline failures are observed. |
| Deadlines | Stops wasted work and retry storms after the caller has given up. | Per-client timeouts only | Added: a default deadline when the caller sends none; deadlines never travel on messages (a relative deadline is meaningless on a queued record). |
| API plugins | Delivers the original "plugin" intent without forcing REST semantics onto RPC. | Per-protocol base classes (v1 style); contract-first codegen | Unchanged. |
| REST binding | The HTTP surface is visible in one place and type-checked, with no HTTP leaking into services. Spring-native routing, security and observability. | `@HttpExchange` interface (more magic); controllers only (boilerplate) | Unchanged. |
| Input binding | Input records stay protocol-free; the typical route declares nothing; typos fail at boot, not in production. | Annotations on records (protocol leaks into services); explicit-only mapping; method references or a generated metamodel for names; automatic query binding on all verbs | Changed: bindings use method references for record components (compiler-checked, refactor-safe; same technique as Spring Data Commons 4.1 TypedPropertyPath). App-wide conventions therefore apply by type, not by component name. |
| Response mapping | Operations return plain values; the HTTP shape lives in the binding, and the common cases need no declarations. | Explicit status on every route; a `Result<T>` return type with HTTP hints in operations | Clarified against RFC 9110: strong ETags, preconditions evaluated before execute (412), version clash without If-Match is 409, 304 carries validators, 405 carries Allow, 401 carries WWW-Authenticate. |
| gRPC binding | Exposure is visible in one class, and large services need one line. | Explicit only; implicit convention with no bean; generated base classes only | Added: descriptor-based record mapping (resolves the proto ↔ record open question); methods referenced by generated descriptors. Boot 4.1 owns the gRPC starters (Spring gRPC 1.1). |
| GraphQL binding | One binding model across RPC-style protocols; N+1 handled by default. | Explicit only; controllers only | Added: depth/complexity limits, operations-per-request cap, introspection off (OWASP GraphQL cheat sheet; Boot allows introspection by default). |
| Function binding | Depends on verifying Spring Cloud Function compatibility with Boot 4 (open question). | Every operation auto-registered as a Function | Still open (P4 plan), together with the Lambda operating model. Fixed requirements added (bound operations only, no routing function; CVE-2022-22963 class). |
| CORS | Secure default with guard rails. | Spring's standard config only; per-route CORS in bindings | Unchanged. |
| Browser security headers | Internal APIs stay lean; external APIs get hardening by adding one starter. | Always on in `synapse-api-rest`; on only when Spring Security is absent | Unchanged. |
| Request size limits | Spring MVC has no JSON body limit of its own; large enterprise tokens commonly exceed 8 KB of headers and fail with an undiagnosable 400. | Body: 10 MB default; no Synapse limit. Headers: a Synapse alias property; keeping 8 KB; 32 KB | Changed: gRPC limits use Boot's spring.grpc.server.inbound.* keys (no Synapse alias). |
| Binary content | File upload/download without protocol types in operations or hand-rolled controllers. | Escape-hatch controllers only; deferred | Added: downloads always send nosniff and attachment with an RFC 6266 filename (stored-XSS and header-injection risk). |
| Health | A DB outage takes pods out of rotation without restarts; one flaky downstream service can't remove the whole service. | Boot defaults only; readiness including client circuit breakers; porting v1's `HealthCheckController` | Changed: readiness reflects the app's own state by default; every store and client opts in (a shared or saturated dependency must not remove every pod). /health uses Boot's additional-path; the Synapse alias is dropped. |
| OpenAPI | The code is the single source of truth, so docs can't drift. | Generation plus a contract-first check against a committed `openapi.yaml` (possible later addition); contract-first only; no OpenAPI | springdoc 3.x supports Boot 4 (3.2.0 built on Boot 4.1.2). Toggles use springdoc's properties; the Synapse alias is dropped. OpenAPI 3.1. |
| Correlation id | Survives where traces break (gateways that restart traces, batch jobs, async hops), and external callers can supply it. | Trace id as the correlation id; v1's `Correlation-ID` as the default; accepting both names | Validated inbound (length and character set) to prevent log injection and header smuggling. |
| Input conflicts | Catches client bugs and tampering without making API evolution lockstep. | Strict (reject unknown fields too); declared location wins silently | Unchanged. |
| Binding location | One module per service, as in v1; adding a protocol is one dependency and one class. | Separate bootable `app` module (Maven-enforced boundary, two modules per deployable); per-protocol `api-*` modules; both layouts in archetypes | Unchanged. |
| v1 deprecation | Keeps `develop` strictly CVE-only. | Class-level `@Deprecated` in a 0.4.30; bundling it into the next CVE patch; docs-only notice | Unchanged. |
| Response shape | Standard; matches OpenAPI tooling and generated clients. | Optional `{data, meta}` envelope; always-on envelope | Unchanged. |
| API versioning | Uses the platform feature instead of a Synapse-specific one; path versions are visible in logs and gateways. Boot 4.0's release notes confirm `spring.mvc.apiversion.*`/`spring.webflux.apiversion.*` and the `ApiVersionResolver`/`ApiVersionParser`/`ApiVersionDeprecationHandler` beans (read 2026-10-31). | Header default; no Synapse versioning | Changed: off until a binding uses .version(); then path-segment with a predicate so unversioned routes keep working (Spring 7 requires a version by default once enabled). Boot's spring.mvc.apiversion.* keys are the only properties. |
| Config model | Boot convention; fixes v1's manual wiring. | Explicit `@Enable…`/`@Import`; disabled-until-enabled flags | Added rule: Synapse owns configuration of what Synapse builds (clients from bindings, named stores); Boot owns the rest; no aliases for Boot properties. |
| Artifacts | Fewest artifacts; still Boot-idiomatic; the parent is the "consistent standards" lever. | Spring-style split autoconfigure/starter (about 2x artifacts); BOM only | Unchanged. |
| BOM contents | Avoids two sources of truth for Boot versions. | A BOM that imports Boot | Added: runtime convergence pins live in the BOM so consumers get tested versions. |
| Error model | A design proven in internal use at American Express, minus the separate status-mapping file; keeps the layering clean. | Separate mapping file per protocol; HTTP status on the interface; v1 `ErrorResponse` shape; Spring defaults only | Added: SynapseErrors framework enum; ExceptionTranslator SPI so core has no data/security dependency. |
| Error exposure | Prod-safe; mirrors Boot's `server.error.include-message`. | Per-code exposure flag; always show detail | Unchanged. |
| Framework error codes | Every response has the same shape and a stable code, without HTTP concepts in the protocol-neutral categories. | New categories for 405/413/415; Spring's default responses for protocol errors | Unchanged; SynapseErrors now covers every category the translators produce. |
| HTTP error format | Standard, and supported by Spring `ProblemDetail`. | v1 ad-hoc JSON | Changed: type about:blank with the status-phrase title unless synapse.errors.type-base-uri is set, then a per-code type (RFC 9457). |
| Logging | Keeps the team's Log4j2 preference without locking consumers in. | Logback default; mandatory Log4j2 (v1 approach) | Changed: Log4j2 stays the prescriptive default through synapse-parent and the archetype, but synchronous (Log4j recommends sync unless benchmarks show a gain; async can lose events and needs extra config). Library jars never force a backend. |
| Observability | One model across protocols. | Micrometer only | Added a stable metric/span contract (operation, kind, outcome, error.category, error.code) and a business-span switch. |
| Security | Safe without blocking non-OAuth shops or local development. | A security starter on by default; leave it all to consumers | Added: identity only from Spring Security (JWT by default, sidecar trusted header opt-in); browser headers in synapse-security; startup exposure inventory. |
| Data layer | Keeps the familiar v1 module names without v1's 11 hand-wired copies. | Per-technology only; drop data modules | Unchanged. |
| Data access model | Zero-code repositories; no forced inheritance; legacy schemas can still use Synapse features. JPA can only inherit mappings from a `@MappedSuperclass` class, so interfaces carry capabilities and the optional bases carry mappings. | `DataOperation<I, O>` per access; repositories plus an optional `DataOperation`; embeddable building blocks; one mandatory `BaseEntity` | Unchanged. |
| Entity ids | Good defaults for distributed services without excluding numeric ids. | DB sequence `Long` as the default; no default | Unchanged. |
| Named data stores | Enterprise services commonly use several databases, even several of the same technology (e.g. Postgres + Oracle), while Boot configures only one per technology. Uniform naming removes the hand-written multi-datasource setup every team copied in v1. | Boot per technology with names only for extras (asymmetric); named stores for JDBC/JPA only | Changed: every store is named under synapse.data.stores.<name> and built by Synapse, including a single one; per-store migrations (Boot runs Flyway for one DataSource only). Readiness is opt-in per store. |
| Read replicas | The common scale-out need with no code changes. | Replica as a separate named store; post-GA | Deferred to 2.x (design kept, see Roadmap). |
| JPA vs JDBC | Familiar to most enterprise teams, and Hibernate provides tenancy and `@SoftDelete`. | JDBC default; JPA only | Unchanged. |
| Redis | One module for both common Redis roles, safe by default. | Store only; a separate cache module | Unchanged. |
| Data testing | Fast, realistic ITs that know about named stores; `@DataJpaTest` doesn't. | Containers only; docs only | Unchanged. |
| Schema migrations | Explicit, versioned schema changes; fixes v1's unchecked Hibernate settings. | Flyway only; no Synapse opinion | Unchanged. |
| Soft delete and multi-tenancy | Reuses Hibernate's built-in features; the tenant flows from the edge without manual plumbing; no cross-tenant leaks by default. | Docs only for both; defer to post-GA | Multi-tenancy deferred to 2.x; its tenant must come from a verified credential (see Roadmap). Soft delete unchanged (docs). |
| Datastores (GA) | Maintainer decision; heavy images kept off the PR path. | Subset only | Changed: GA covers PostgreSQL, MySQL/MariaDB, Oracle, SQL Server, MongoDB, Redis. DB2, Cassandra, Couchbase are 2.x add-ons. |
| Clients (GA) | Covers outbound protocols with the same defaults as inbound. | Keep SOAP | Changed: GraphQL client is a 2.x add-on. |
| Client model | Minimal developer work per call; callers stay protocol-neutral, so a downstream service can move from REST to gRPC without touching callers; same `*Operation` vocabulary. | Spring HTTP interface clients (`@HttpExchange`) as the model; one class per call (v1 style); both models | Changed: callers use a typed protocol-free interface per downstream, bound by method reference (Spring HTTP interfaces, Quarkus, Helidon and Micronaut all use typed interfaces); ClientOperation stays internal. |
| Client add-ons | Pay only for what you use; small CVE surface; common mechanisms done once and securely. | Everything built in and classpath-activated; SPI only | Token relay and Resilience4j add-ons deferred to 2.x. |
| Client configuration | One place to look per client, documented in the generated property tables. | Boot per-group transport properties + Synapse extras (split config; Boot groups exist only for HTTP interfaces); code-only config | Kept one Synapse namespace ("otherwise two places"); Boot's per-name client keys don't apply to Synapse clients and trigger a startup warning; name "defaults" reserved. |
| Wire format per client/publisher | A downstream with a different naming convention is the client module's concern, not its callers'. | App-wide naming only; per-call mapper settings | Unchanged. |
| Client resilience | No hidden extra load on downstream services; when retries are enabled, they're safe by construction. | Safe retries on by default; Resilience4j built in for every client | Spring Framework 7 core retry confirmed (RetryTemplate/RetryPolicy). Circuit breaker/bulkhead add-on deferred to 2.x. |
| Client health | Visible to operators without cascading outages. | Never in health; readiness by default | Changed: readiness opt-in per client; informational circuit-breaker health comes with the 2.x resilience add-on. |
| Outbound idempotency | Safe POST retries without assuming every downstream service supports keys. | No POST retries in P2; always sending a key on POST | Unchanged. |
| Downstream error mapping | Downstream 4xx often stems from caller data passed through, so mirroring is the default. But our own credential or scope problems are our fault: they must not be blamed on the caller, and they must alert us. A downstream service that uses 403 for per-resource decisions is mapped explicitly on that call. | Pure mirror including 401/403; refresh then 503; 401 straight to 500 with no refresh; mirroring 403 by default under service credentials; a fixed table in core | Changed: the per-client property status map is removed (see Client error hooks). |
| Client error hooks | Teams can express any downstream contract in code, while the framework's safe defaults remain the net when they return `useDefault()`. | Declarative mappings only; mapped exceptions handed to handlers (defaults first); handlers that can also request retries | Changed: two levels (per-call, per-client) before the defaults, instead of seven (Quarkus uses a similar prioritized mapper model). |
| Downstream error bodies | Works for well-behaved APIs with no config, and for legacy APIs without pushing response checks into every caller. | Raw body only; problem+json only; no support for failures inside 200 (callers check) | Unchanged. |
| Client SSRF protection | Internal calls to private addresses just work; the risky case is explicit. | Block metadata ranges only; deny private ranges unless allow-listed (breaks internal calls) | Unchanged. |
| Header propagation | No accidental leaks to third parties, and callers can't inject downstream headers. | Forward all `X-*` headers; per-call declarations | Unchanged. |
| ClientOperation injection | No boilerplate in the common case; ambiguity never resolves silently. | Typed client interface per call; always by name | Superseded by typed client interfaces (no generic-type injection or string qualifiers). |
| gRPC/GraphQL clients | Callers are protocol-neutral for every outbound protocol. | Bindings for REST only (raw stubs/`HttpSyncGraphQlClient` elsewhere) | gRPC client in GA; GraphQL client deferred to 2.x. |
| Client logging | Failures are diagnosable without opting in; PII-safe by default. | Off by default; basic on by default | Changed: header logging uses an allow-list; token-endpoint traffic never logged. |
| Messaging | Safe default with flexibility; fixes v1's data-loss bugs. A neutral contract keeps a future broker from becoming a second programming model. | Required explicit choice; at-least-once only; Kafka types in the contract; Spring Cloud Stream binders (large extra release train that overlaps the bindings DSL) | Changed: exactly-once deferred to 2.x (it only covers Kafka-to-Kafka; database writes stay at-least-once). |
| Subscriber bindings | One rule for every inbound protocol; subscribers get the operation pipeline (correlation, deadline, validation, observation, translation). | Separate `subscriber-*` module (v1); package first, module when shared | Changed: the operation's input type is the event (from the producer's contract); operations referenced by bean; subscriber name defined (.named, default operation name). |
| Kafka framework modules | Same split as `synapse-client-core`; a subscriber-only app doesn't get publisher bindings. | Shared code inside the publisher module; one merged `synapse-kafka` module; renaming to `synapse-api-kafka`; consumer package `api.subscriber` | Unchanged. |
| Publisher model | Safe by default and consistent with the synchronous operation model; async is explicit. | `CompletableFuture` only (a forgotten future drops failures, v1's bug class); thin `KafkaTemplate` wrapper (Kafka types leak into business code) | Unchanged. |
| Message format | Easy start; schema enforcement for teams that need it. | JSON only; Avro + registry by default; Protobuf by default | Unchanged. |
| Event metadata | Standard header names that tools and non-Synapse consumers understand; a dedupe key and a routing type without parsing the body; the same attributes carry over to future broker modules. | Synapse-specific headers; structured mode (payload wrapper); no envelope | Changed against CloudEvents 1.0: ce_source is an absolute URI (URN), ce_type gets a configurable reverse-DNS prefix, ce_time is the occurrence time when known. |
| Multi-type topics | One topic per entity keeps all events for a key ordered. | One type per topic; decide in the P3 plan | Unchanged. |
| Subscriber failures | Reuses the error model; bad messages reach the DLT immediately instead of holding the partition. | Retry everything then DLT; no retries | Unchanged. |
| Subscriber retry mode | Ordering is the safe default; long waits don't stall partitions when opted in. | Retry topics by default (breaks ordering, extra topics to provision); blocking only | Changed: blocking retries use Spring Kafka's ContainerPausingBackOffHandler (no rebalances from back-off) plus a per-record processing timeout; the backoff-vs-poll-interval startup check is dropped. |
| Transactional outbox | Closes the dual-write gap (publish then rollback, or commit then a failed publish) that Kafka transactions can't cover. | Document the pattern only; not in Synapse | Deferred to 2.x; Spring Modulith's Event Publication Registry is the first candidate. |
| Duplicate events | At-least-once delivery and the outbox both produce duplicates; CloudEvents gives a ready key. | Docs only; on by default (forces a data store on every worker) | Deferred to 2.x (dedupe store). |
| Kafka clusters | Consistent with named data stores; common enterprise setup (domain + audit/analytics clusters). | Single cluster in 2.0; always named | Deferred to 2.x (named clusters). |
| Batch and concurrency | Fixes v1's batch subscriber that never invoked processing; bulk writes stay possible. | Per-record only; batch by default | Batch listeners deferred to 2.x; per-record processing and concurrency stay. |
| Messaging logging | Consistent with operations and clients; DLT sends are always visible. | Log every record; off by default | Unchanged. |
| Topic provisioning | A missing DLT blocks a partition on the first bad message; production topics are owned by platform teams/IaC. | WARN by default; always auto-create | Unchanged. |
| Consumer group id | Each binding has its own offsets and scaling. | Boot's single shared group id; always explicit | Unchanged. |
| Kafka control | Replaces v1's unauthenticated GET start/stop endpoints; incident handling without ad-hoc tooling. | Status + pause only; no control surface | Changed: endpoint id kafkasubscribers (Boot warns on hyphenated ids); read-only by default; pause/resume need explicit access and Spring Security; DLT replay deferred to 2.x. |
| Messaging testing | Same approach as `@SynapseDataTest`; makes the P3 acceptance tests and consumer tests cheap to write. | Containers only; embedded broker | Unchanged. |
| Operation testing | Tests that call `execute()` directly skip validation and error translation; most tests stay fast. | Slice only; plain JUnit only | Unchanged. |
| API binding testing | Catches mapping mistakes fast, without a database. | Full app tests only; startup checks only | Unchanged. |
| Client testing | Simple default with no extra dependency; transport behavior is proven once by the framework instead of by every team. | WireMock default; `MockRestServiceServer` only | Unchanged. |
| Contract testing | Keeps 2.0 scope down; schema artifacts already exist. | Pact add-on; Spring Cloud Contract add-on | Unchanged. |
| Utilities | Preserves v1's prescriptive value without its global side effects. `@Masked` moved to core (not utilities) because logging and JSON both need it. | Port everything; drop everything; keep formatting serializers | JSON naming uses Boot's spring.jackson.property-naming-strategy (synapse.json.naming dropped). |
| Samples | Teaches by scenario; replaces v1's ~38 drifting sample modules. | One sample per module; template repo | Changed: serverless sample is AWS only; added scheduled-and-batch. |
| Archetypes | v1 archetypes target the deprecated stack. | Template repo only | Unchanged. |
| Docs | v1 READMEs drifted; versioning is built in without copies or stopgap plugins; familiar to Spring users. | Zensical (no native versioning yet); Docusaurus (per-version copies); Material for MkDocs + mike (end of upstream fixes in May 2027); READMEs only; Javadoc only | Generation runs through the Antora Collector extension; the build fails on undocumented properties. |
| Quality gates | Prevents v1-style drift. | Moderate; minimal | Changed: CI split by JDK (full on 25, smoke on 21, full 21 nightly); OSV-Scanner fails PRs only on newly introduced vulnerabilities; coverage no-data bypass closed; API-stability toolkit. |
| Delivery | De-risks the central design first. | — | Changed: phases P0-P6 (reactive phase removed). |

---

## 5. Roadmap: 2.x add-ons
Designs already agreed for deferred features. They are not built in 2.0; each needs a plan when scheduled.

| Add-on | Agreed design so far |
|---|---|
| Reactive stack | `ReactiveServiceOperation<I, O>` returning `Mono`/`Flux` and its pipeline; `synapse-api-rest-reactive` (WebFlux, same `RestBindings` semantics); `synapse-client-rest-reactive`; `synapse-data-r2dbc` with vendor support (Postgres, MySQL, SQL Server, Oracle; DB2 driver maturity to check); reactive Mongo/Redis/Cassandra/Couchbase; BlockHound in tests. 2.0 must not block it. |
| `synapse-api-function-gcp` | Same binding model as AWS once the P4 plan settles it. |
| `synapse-client-graphql` | `GraphQlClientBindings` on `HttpSyncGraphQlClient`, documents in `src/main/resources/graphql-client/`; `errors[]` raises `SynapseException` even on HTTP 200. |
| `synapse-client-auth-token-relay` | Forwards the caller's token or performs token exchange (RFC 8693, preferred); must check the token audience and is forbidden on `dynamic-target` clients. 401/403 mirror, logged at WARN. |
| `synapse-client-resilience4j` | Circuit breaker and bulkhead per client; informational health (`UP`/`CIRCUIT_OPEN`), never in readiness unless opted in. Spring Framework 7's `@ConcurrencyLimit` is a dependency-free bulkhead alternative to weigh. |
| DB2, Cassandra, Couchbase | Same named-store model and vendor-module shape. |
| Read replicas | `synapse.data.stores.<name>.replica.*`; `@Transactional(readOnly = true)` routed to the replica through a routing DataSource behind a lazy connection proxy; replica lag documented. |
| Multi-tenancy | Opt-in `discriminator` (JPA only; startup fails on native/JDBC repositories without an opt-out; cache keys prefixed with the tenant) or `schema` mode. The tenant comes only from a verified credential (e.g. a JWT claim mapping), never from a header or baggage. A missing tenant fails the call; it never means "all tenants". |
| Kafka exactly-once | Kafka transactions + `read_committed` for Kafka-to-Kafka flows; database writes remain at-least-once (documented, startup warning). |
| Kafka named clusters | `synapse.kafka.clusters.<name>.*` built from Boot's Kafka properties; `.cluster("audit")` on bindings. |
| Kafka dedupe | Per-subscriber store keyed by topic + `ce_source` + `ce_id`, TTL; marker committed with the business change when on the same JDBC store (requires a transaction-ownership decision: Synapse never opens transactions). |
| Kafka batch listeners | `.batch()` binding to an operation taking `List<I>` with protocol-free failure reporting (index of the failing record). |
| DLT replay | Replay as a one-shot job holding a lock (not a per-pod Actuator write), keeping `ce_id` so dedupe applies. |
| Transactional outbox | First candidate: Spring Modulith's Event Publication Registry and Kafka externalization; otherwise a Synapse table compatible with Debezium's outbox router. Changes `publish()` semantics inside a transaction. |
| Schema-registry add-ons | `synapse-kafka-avro`, `synapse-kafka-protobuf`; Confluent vs Apicurio (license, Boot 4 fit). |
| Kafka share groups | Spring Kafka 4.0 supports share consumers (KIP-932, "queues"); an option for work that doesn't need per-partition order. |
| Protobuf REST codec | Content negotiation for `application/x-protobuf` on REST routes. |
| Contract testing | Docs and possibly an add-on once teams show which tool they use (OpenAPI diff, buf breaking, Pact, Spring Cloud Contract). |
| OpenRewrite recipes | v1 → V2 and between 2.x minors. |
| Field-level encryption | Replacing v1 `CryptoUtil` properly (KMS / Spring Security crypto). |

---

## 6. Open questions
| Question | Owner | Decide by |
|---|---|---|
| Create `main` in upstream `americanexpress/synapse`, make it the default branch, move CI to GitHub Actions, provision Central token and GPG key in protected environments, enable rulesets. | Maintainers / americanexpress org | Before upstream releases (work proceeds on the fork) |
| AWS functions: binding model (HTTP-triggered reuse `RestBindings`; event-triggered `FunctionBindings`) and Lambda operating model (SnapStart vs native, functions profile, snapshot-safe generators, telemetry flushing). | Maintainers, P4 plan | P4 |
| PATCH / partial updates: RFC 7396 JSON Merge Patch with a core tri-state `Patch<T>` mapped from gRPC `FieldMask` and GraphQL argument presence, vs PUT only, vs JSON Patch. P1 ships PUT only. | Maintainers, P4 plan | P4 |
| Inbound idempotency keys (`Idempotency-Key` on our own routes): opt-in per route with a pluggable response store, vs key in `RequestContext` only, vs not in Synapse. (Outbound keys are decided.) | Maintainers | Before 2.0 GA or as a 2.x add-on |
| Access control per deployment model: how `synapse-security` integrates with each supported deployment, namely OpenShift/Kubernetes with an Istio sidecar (OPA via Envoy `ext_authz`, replacing today's custom sidecar authz engine), Istio ambient (waypoints), plain Docker without a mesh, and cloud functions. For each model, the plan must say where authorization is enforced, which paths can bypass it (gateway-only policies, pods without sidecars, PERMISSIVE mTLS, excluded ports, ambient traffic that skips the waypoint), and whether the app must enforce or verify anything itself. Raised by Tony on 2026-11-01. | Maintainers, P4 plan | P4 |
| Kafka health: does Boot 4 provide a Kafka health indicator, or does Synapse need one (readiness opt-in like other dependencies)? | Implementer, P3 plan | P3 |
| Client readiness signal (what a client's health contributor reports at GA without the resilience add-on). | Implementer, P2 plan | P2 |
| Default sizes: concurrency limits, default/max deadlines, paging (20/100 proposed), client timeouts and pools, Kafka retries (3 attempts, 1 s→10 s proposed), subscriber `max.poll.records` and timeout, GraphQL depth/complexity (15/1000 proposed). | Implementer, phase plans | P1–P4 |
| MVC test client for `@SynapseRestTest` (`MockMvcTester` vs `RestTestClient`). | Implementer, P1 plan | P1 |
| WireMock's Boot 4 / Jetty / Jackson fit; ShedLock's Boot 4 fit. | Implementer | P2 / P4 |
| Archetype set (one parameterized vs one per module type). | Maintainers, P6 plan | P6 |

---

## 7. v1 → V2 module map
| v1 | V2 |
|---|---|
| `service-rest`, `service-imperative`, `api-rest-imperative`, `framework-api-docs` | `synapse-core` + `synapse-api-rest` |
| `service-reactive-rest`, `service-reactive`, `api-rest-reactive` | 2.x reactive add-on |
| `service-graphql` | `synapse-api-graphql` |
| `function` | `synapse-api-function-aws` (GCP: 2.x add-on) |
| `framework-exception` | `synapse-core` (errors) |
| `framework-logging` | `synapse-logging-log4j2` |
| `framework-test`, `service-test`, `client-test` | `synapse-test` |
| `client-rest` | `synapse-client-rest` |
| `client-graphql` | 2.x add-on |
| `client-soap` | dropped |
| `data-*` | `synapse-data-<vendor>` over `synapse-data-jpa`/`-jdbc` for Postgres, MySQL/MariaDB, Oracle, SQL Server (`mssql` becomes `sqlserver`), MongoDB, Redis; DB2, Cassandra, Couchbase: 2.x add-ons |
| `publisher-kafka`, `subscriber-kafka` | `synapse-publisher-kafka`, `synapse-subscriber-kafka` (+ `synapse-kafka-core`), rebuilt |
| `utilities-common`, `utility-*` | `synapse-core` (JSON conventions, `@Masked`), `synapse-utilities` (validators), `synapse-test` (fixtures); the rest dropped |
| `archetype-*` | regenerated (P6) |

v1 deprecation: `service-rest`, `service-reactive-rest` and `service-graphql` are deprecated only through
`package-info.java` (which compilers ignore). Accepted: V2 has new artifact names, and the migration guide plus the
0.4.x end-of-life notice cover it. `develop` stays CVE-only until its end of life.

---

## 8. Sources consulted
Fetched during the decision rounds and reviews (2026-10-27 to 2026-10-31):
- Spring Boot 4.0 and 4.1 release notes, 4.0 migration guide, reference docs (REST clients and InetAddressFilter,
  tracing and baggage, observability, Actuator endpoints and access, graceful shutdown, SpringApplication and virtual
  threads, AOT cache, native images, GraphQL, developing auto-configuration, application properties appendix);
  spring.io support policy; endoflife.date (Spring Boot).
- Spring Framework 7 reference: API versioning (MVC), resilience (`@Retryable`, `RetryTemplate`, `@ConcurrencyLimit`),
  observability (`@Scheduled` observation), `MockRestServiceServer`/`MockRestResponseCreators`, client testing.
- Spring Kafka reference: error handling and back-off handlers, Kafka queues (share consumers).
- Spring Data Commons 4.1: property paths (`TypedPropertyPath`).
- Spring Modulith 2.0/2.1: events reference, 2.0 GA announcement.
- Spring Cloud 2025.1.2 (Oakwood) announcement; Spring Cloud compatibility verifier properties.
- springdoc-openapi releases and site.
- Spring Security advisory CVE-2026-59323 (Micrometer Brave W3C baggage); CVE-2022-22963.
- CloudEvents 1.0 spec and Kafka protocol binding; CNCF graduation announcement.
- RFC 9110, RFC 9457, RFC 9562, RFC 8941 (via the IETF Idempotency-Key draft), gRPC status codes.
- OWASP REST Security, GraphQL, SSRF cheat sheets; OWASP API Security Top 10 (2023).
- OPA Envoy plugin docs; Envoy `jwt_authn` filter docs.
- Log4j async logging manual; Apache release catalog (Log4j 3.0 still beta).
- AWS Lambda SnapStart docs (via the operator review).
- Antora versioning and Collector extension docs; Zensical, Docusaurus, Material for MkDocs announcements.
- GitHub Actions docs (events, reusable workflows, environments, Dependabot options); OSV-Scanner action and
  configuration docs; zizmor audits; release-please docs; Sonatype Central Portal publishing and snapshots.
- Quarkus REST Client and Funqy guides; Helidon declarative client docs; ShedLock releases; MapStruct protobuf SPI.

---

## 9. v1 facts relied on
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
