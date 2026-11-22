# P1 plan 2c: `VersionCondition`, REST API versioning, CORS and OpenAPI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the REST plugin for P1. It gains:
- core `VersionCondition<V>`, required for `If-Match`/`If-None-Match` on writes, so `*` and an absent header never look alike;
- API versioning: `.version("2")` serves a route at `/v2/...` on Spring Framework 7's versioning, and unversioned routes keep working;
- CORS: `synapse.api.rest.cors.*`, applied by an edge filter through a `corsConfigurationSource` bean, with problem+json rejections;
- OpenAPI 3.1 at `/v3/api-docs` in a new module, `synapse-api-rest-openapi`: springdoc 3.2.0 serves it, and a Synapse customizer and schema generator build it from the resolved routes (acceptance 7).

`samples/rest-books` shows all four.

**Architecture:**
- **`VersionCondition`** is a sealed core type. The REST binder builds it from the entity tag it already parses, and `BindingValidator` requires it on write routes.
- **API versioning:**
  - `.version(...)` is a route declaration. The route's path becomes `/v<version>` + the declared path, and `RouteCompiler` adds Spring's `RequestPredicates.version`.
  - A Synapse `WebMvcConfigurer`, ordered before Boot's, adds a `PathApiVersionResolver` that parses only versioned paths, makes the version optional and lists the declared versions as supported.
- **CORS:** one new servlet filter right after the context filter. It covers routes, the 405/OPTIONS fallback, controllers and the problems the later filters write.
- **OpenAPI:**
  - springdoc doesn't document a plain `RouterFunction` bean. A Synapse `OpenApiCustomizer` therefore adds an operation for every resolved route (read from the `synapseRestRoutes` bean), with schemas from a generator built on the app's Jackson 3 `JsonMapper`, JSpecify nullness and Jakarta Validation metadata.
  - An `EnvironmentPostProcessor` sets springdoc's toggles at lowest precedence.

**Tech Stack:**
- Java 21 (`--release 21`, built on JDK 25).
- Spring Boot 4.1.2 and Spring Framework 7.0.10:
  - `org.springframework.web.accept` versioning;
  - `RequestPredicates.version`;
  - `CorsFilter` and `DefaultCorsProcessor`;
  - `UrlBasedCorsConfigurationSource`.
- Jackson 3.1.7 (`tools.jackson`).
- springdoc-openapi 3.2.0, with swagger-core/swagger-models 2.2.55 (Jackson 2.21.7, managed by Boot's `jackson-2-bom`).
- Hibernate Validator 9.1 metadata API.
- JUnit 5, AssertJ, `RestTestClient`, `WebApplicationContextRunner`.

**Spec:** [specs/v2/BUILD.md](../BUILD.md):
- §5.1 (`VersionCondition`);
- §6.1 (protocol-only codes);
- §6.2: *Conditional requests*, *API versioning*, *CORS*, *OpenAPI*;
- §3.2 (Boot keys, lowest-precedence defaults);
- §3.6 (environments);
- §15 P1 acceptance 7.

The reasons are the [DESIGN.md](../DESIGN.md) §4.1 rows dated 2026-11-05 for P1 plan 2c (and the `VersionCondition` row, decided 2026-11-04). The 2a and 2b ledger rulings are binding behaviour already in the code; this plan cites them as 2a-R<n> and 2b-R<n>.

**Maintainer decisions this plan builds on** (2026-11-05 brief):
- D1. Task 1 is `VersionCondition` (2b-R22 decision). It is required on write routes, the sample's PUT and DELETE switch to it, and the docs drop the `.from(...)` recipe.
- D2. The rest of 2c is what BUILD.md assigns: API versioning on Boot/Spring 7 (no alias, no re-implementation); CORS with a `*` WARN in every environment; OpenAPI 3.1 via springdoc 3.x in its own module (Swagger UI off, API docs on).
- D3. Environments: no production concept. Spring Boot tests run as `test` with an `application-test.yaml`, and tests outside Synapse's slices declare `@ActiveProfiles("test")`.
- D4. Dependency versions go in the root `pom.xml`, after the release notes are read. Enforcer bans and convergence apply.

## Global Constraints

**Branch, JDK and language**
- Branch: `feature/v2-p1-rest-versioning-cors-openapi` from local `main` (208065f2); the plan and spec commit is its first commit. **Never push.** Merge to `main` locally only when the maintainer says so (squash, as for the earlier plans).
- JDK: run `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"` before any `./mvnw`. JDK 21 runs use `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never a global `mvn`.
- Compile with `--release 21`: no Java 22+ APIs. Prefer records for data carriers.
- Every package has a `package-info.java` annotated `@NullMarked`, and nullable types are `@Nullable` (JSpecify). A test package that has the same name as a main package has no `package-info.java`. Error Prone and NullAway must pass.
- Formatting: run the build, and Spotless applies the profile locally. Don't hand-format. Every file carries the license header.
- Don't edit `.vscode/` or a pom while a Maven build runs (VS Code shares `target/`).

**Behaviour fixed by the spec (copy exactly)**
- Synapse never adds an alias for a Boot or springdoc property. Versioning is tuned with `spring.mvc.apiversion.*`, springdoc with `springdoc.*`, and Actuator CORS with `management.endpoints.web.cors.*`.
- No production concept: the CORS `*` WARN is logged in every environment, and nothing branches on the environment's name. No `@Conditional` depends on a value an environment file may change (CORS origins included).
- `synapse-api-rest-openapi` depends on `springdoc-openapi-starter-webmvc-api` **without** `spring-boot-starter-logging`, which is banned in Synapse jars. No Swagger UI dependency.
- Swagger UI is off, the API docs are on, and their version is 3.1, all at lowest precedence.

**Auto-configuration, properties, library code**
- Every bean is `@ConditionalOnMissingBean` (by type or by name). Every new bean gets `WebApplicationContextRunner` tests: it activates, backs off to a user bean, binds its properties, and is absent when its condition fails.
- Properties are typed `@ConfigurationProperties` records under `synapse.*`, with Javadoc on every component. Invalid values are an `IllegalArgumentException` that names the key (the existing style).
- No remote I/O during bean creation. No `@ComponentScan`, no `@Import` of framework packages, no `@EnableWebMvc`, no config files in library jars. SLF4J API only. Never expose stack traces, exception class names or internal messages.
- Code outside `..internal..` packages is public API. New implementation classes go in `io.americanexpress.synapse.api.rest.internal.{version,cors}` and `io.americanexpress.synapse.api.rest.openapi.internal`.
- Plan against the code as it is. Behaviour from the earlier rulings stays:
  - 2a-R4: resolved routes;
  - 2a-R13: status table;
  - 2a-R30: implicit HEAD and OPTIONS;
  - 2a-R34: reserved output headers;
  - 2a-R43–R45: route order, the fallback, the shared classifier;
  - 2a-R46: `ClientErrorFilter`;
  - 2b *Defaults* #20–#29: entity tags, preconditions, the `STALE_VERSION` translation.

**Tests**
- Unit tests are `*Test` (surefire); integration tests are `*IT` (failsafe). A module with ITs tags at least one fast test `@Tag("smoke")`.
- Spring Boot tests outside the Synapse slices declare `@ActiveProfiles("test")`. Every module whose tests start a Spring Boot context has `src/test/resources/application-test.yaml`, which may be comment-only.
- JaCoCo floor per module: 80% line / 70% branch; 90% of changed lines covered. `-DskipTests` is never a passing build.
- Assert outcomes: status, code, headers, the operation's invocation count, the documented JSON (`$.paths['/v2/books/{id}'].get...`). "No exception" is not an assertion.
- Routes with `.version(...)` need Spring's API version strategy, so tests that run them go through MockMvc or a server, never a direct `RouterFunction.route(...)` call.

**Commits**
- Conventional Commits. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage by explicit path (`git add <path> …`), never `git add -A` or `git add .`. Never stash, amend or push.
- Spring Boot stays on 4.1.2.

**Commands**
- Iterating on a unit test: `./mvnw -q -pl <module> -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`.
- Iterating on an IT: `./mvnw -q -pl <module> -am verify -Dit.test=ClassNameIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`.

Neither counts as a passing build. Every task ends with `./mvnw -pl <module> -am verify` for each module it touched. Task 1, Task 5 and Task 11 run the full `./mvnw verify`, in the background (it takes several minutes).

## Spec changes this plan records

Committed with this plan (`docs: plan REST versioning, CORS and OpenAPI (P1 plan 2c)`), before Task 1. The diff is the record:
- **BUILD.md:**
  - §5.1: a `VersionCondition<V>` row.
  - §6.1: the protocol-only codes `CORS_REJECTED` 403, `MISSING_API_VERSION` 400 and `UNSUPPORTED_API_VERSION` 400.
  - §6.2:
    - *Conditional requests*: `VersionCondition` on writes replaces "`*` binds `null`".
    - *API versioning*: the `/v<version>` first segment, the optional version, the `use.path-segment` startup failure, the deprecation hook.
    - *CORS*: the defaults, the exposed headers, the edge filter, management paths, `CORS_REJECTED`.
    - *OpenAPI*: the 3.1 pin, and controllers documented by springdoc.
  - §15: the plan 2c delivery bullet, with this plan's link and acceptance 7.
- **DESIGN.md:**
  - §4.1 rows *`VersionCondition`*, *API version paths*, *Optional API version*, *CORS edge filter* and *OpenAPI generation*;
  - the §4.3 *API versioning* and *CORS* rows annotated;
  - §8 sources fetched for this plan.
- **AGENTS.md:** no change in this commit. Task 6 adds `synapse-api-rest-openapi` to the `api/` layout row when it creates the module (the row lists modules that exist).

`BUILD.md` has no §4.1 decision table (its §4.1 is *Modules*). The brief's "BUILD.md §4.1 rows" for `VersionCondition` are recorded as the §5.1 contract row and the §6.2 text, plus the DESIGN.md §4.1 row.

## Defaults this plan sets (confirm at review)

> **Superseded in part on 2026-11-07** (maintainer decisions, DESIGN.md §4.1): `springdoc.api-docs.enabled` defaults
> to `false` (#17); `.requireIfMatch()` is gone, and a non-null `VersionCondition` makes its header required, 428
> `SynapseErrors.PRECONDITION_REQUIRED` without it (#4, #5 action text, #6 for `DeleteBookRequest`/`BookRemoval`, #21,
> #22, and the Task 7 test outline). The rows below are kept as written, for the record.

| # | Default | Why |
|---|---|---|
| 1 | `VersionCondition` lives in the new package `io.americanexpress.synapse.core.conditional` and is **not** `@Incubating`. It is `sealed` (permits `AnyVersion`, `Version`), with `any()`, `of(V)` and `boolean matches(V current)`. `toString()` gives `*` and `"<value>"`. | Model records use it, so it is stable API. "conditional" is RFC 9110's term, and gRPC can map to it in plan 3. |
| 2 | "Write route" means every method except `GET` and `HEAD`, as in `Preconditions` (2b), so a later `PATCH` is covered. On `GET`/`HEAD` a plain `@Nullable` type stays allowed, and a `VersionCondition` also works there. | D1 says write routes. Reads only bind these headers for application use; the 304 check doesn't read the component. |
| 3 | A `VersionCondition` component binds only from a header named `If-Match` or `If-None-Match` (declared or by a `RestConventions` rule), or from `.context`/`.from`. Any other source fails startup, including the default body source on `POST`/`PUT`. A raw or wildcard `VersionCondition` (`VersionCondition<?>`) fails startup. | It has no wire form other than an entity tag. |
| 4 | Binding: absent → `null`; `*` → `VersionCondition.any()`; one tag (strong, or weak) → `VersionCondition.of(<opaque value converted to V>)`. The violation texts and the 412 rules of 2b *Defaults* #22–#25 are unchanged (a weak `If-Match` still binds, then `Preconditions` answers 412). `.requireIfMatch()` and the `STALE_VERSION` translation are unchanged. | The 2b parser and preconditions already decide everything but the value. |
| 5 | Startup messages (`<R.c>` is `Record.component`, `<T>` the component's type name):<br>• plain type on a write: problem `the component <R.c> is bound from <header> on <METHOD>, but its type is <T>: a write needs a VersionCondition, so that * and an absent header stay apart`; action `Declare it as @Nullable VersionCondition<<T boxed>>: Version(v) for one entity tag, AnyVersion for *, null without the header.`<br>• raw or wildcard: problem `the component <R.c> is a VersionCondition without a version type`; action `Declare the version type, for example VersionCondition<Long>.`<br>• wrong source: problem `the component <R.c> is a VersionCondition bound from <source>`; action `Bind it with .header("If-Match", ...) or .header("If-None-Match", ...), or supply it with .context or .from.` | One message per mistake; each names the fix. |
| 6 | The sample's request records hold the `VersionCondition`; the operations map it to the business models' existing `@Nullable Long version` (`Version(v)` → `v`; `AnyVersion` and `null` → `null`) with a `switch`. `ReviseBook`/`RemoveBook` are unchanged: in this sample, update and delete need the book to exist, so `*` and no header do the same thing there, except for the edge's 412/409 choice. | Shows the pattern match where the protocol value enters; the business layer keeps its own model (BUILD §4.2). |
| 7 | `.version(String)` is a route declaration (`RouteDeclaration.Version`). The served path is `"/v" + version + path`, or `"/v" + version` for `/`. A version must match `\d+(\.\d+){0,2}` (`1`, `1.2`, `1.2.3`): no `v` prefix, no `+` baseline, no blank. It is declared at most once per route. The same method and declared path in two versions that parse equal (`2` and `2.0`) fails startup. | Spring's `SemanticApiVersionParser` accepts `major[.minor[.patch]]` after a `v`. Exact versions keep literal paths honest; baselines can come on request. |
| 8 | `RouteDefinition` gains `@Nullable String version` as its last component, and `declaredPath()` (the path without the `/v<version>` prefix). `path()` is the served path everywhere: path variables, duplicates, specificity order, the 405 fallback, the exposure inventory and OpenAPI. On versioned routes, `Deprecation` and `Sunset` join the reserved output headers. | No other code needs to know about versions; Spring writes those two headers. |
| 9 | `SynapseApiVersioning` (`internal.version`, bean `synapseApiVersioning`, `Ordered.HIGHEST_PRECEDENCE`) implements `WebMvcConfigurer.configureApiVersioning`. With no versioned route it does nothing, so Spring's versioning stays off. Otherwise it:<br>• adds `new PathApiVersionResolver(0, path -> <path within the application matches a versioned route's template>)`;<br>• calls `setVersionRequired(false)`;<br>• calls `addSupportedVersions(<declared versions>)`.<br>Boot's adapter runs after it, so a `spring.mvc.apiversion.*` value wins. It fails startup when `spring.mvc.apiversion.use.path-segment` is set (message: *Defaults* #10). | DESIGN 4.1 *Optional API version*: an EPP default is impossible (Spring's `Assert.state` without a resolver), and Boot's segment resolver breaks unversioned routes (probe). |
| 10 | The `use.path-segment` failure: problem `spring.mvc.apiversion.use.path-segment is set while REST routes are versioned (<METHOD> <path> (<operation>), ...)`; action `Remove spring.mvc.apiversion.use.path-segment. Synapse puts a route's version in its first path segment and parses only those paths; Boot's path-segment resolver would parse every path, unversioned routes included.` | Names the cause and the fix. |
| 11 | New `RestErrors`:<br>• `MISSING_API_VERSION("The request must name an API version", INVALID_INPUT, 400)`;<br>• `UNSUPPORTED_API_VERSION("The requested API version is not supported", INVALID_INPUT, 400)`;<br>• `CORS_REJECTED("The cross-origin request is not allowed", FORBIDDEN, 403)`.<br>`RestExceptionClassifier` maps `MissingApiVersionException` to the first and `InvalidApiVersionException` (`NotAcceptableApiVersionException` included) to the second, before its generic `ErrorResponse` branch. Both are 4xx client errors, logged as the classifier logs client errors (2a-R44). | Spring raises them only when Boot settings turn on `required`, header or query versions. A version no route declares never reaches them: it is 404 (probe). |
| 12 | `synapse.api.rest.cors`: `Cors(List<String> allowedOrigins, @DefaultValue({"GET","HEAD","POST","PUT","DELETE"}) List<String> allowedMethods, @DefaultValue("*") List<String> allowedHeaders, List<String> exposedHeaders, @DefaultValue("false") boolean allowCredentials, @DefaultValue("30m") Duration maxAge)`. Validation:<br>• an origin is `*` or `http(s)://host[:port]` (no path, query, trailing slash or other `*`);<br>• methods are `*` or standard method names;<br>• `max-age` ≥ 0;<br>• `*` with `allow-credentials=true` → IAE `synapse.api.rest.cors.allow-credentials can't be true while synapse.api.rest.cors.allowed-origins contains *: browsers refuse credentials for any origin. List the exact origins.` | BUILD §6.2. The methods default is what Synapse routes serve. `*` for request headers is Spring's own permit default and carries no secret. |
| 13 | The source: `SynapseCorsConfigurationSource extends UrlBasedCorsConfigurationSource` (`internal.cors`), bean `corsConfigurationSource`, `@ConditionalOnMissingBean(name = "corsConfigurationSource")`. It is **not** conditional on `RestBindings` or on the origins. Its configuration:<br>• no origins → `null` for every request (CORS off);<br>• otherwise one configuration on `/**`, and `null` for management paths (the `ManagementPaths.excludedPrefixes` match the concurrency filter uses).<br>Exposed headers are, in this order and without duplicates (ignoring case): the correlation header (`synapse.correlation.header`), `ETag`, `Link`, `Location`, `Retry-After`, `Deprecation`, `Sunset`, then `exposed-headers`. | Back-off by name: `HandlerMappingIntrospector` is also a `CorsConfigurationSource`, so back-off by type would always fire. A `UrlBasedCorsConfigurationSource` instance is what Spring Security picks up (7.1.2 reference). |
| 14 | `*` in `allowed-origins` logs, once at startup, on `io.americanexpress.synapse.api.rest`, at WARN: `synapse.api.rest.cors.allowed-origins contains *: any website can call this API from a visitor's browser. List the exact origins instead (for example https://app.example.com).` | BUILD §6.2; no production concept. |
| 15 | `SynapseCorsFilter` (`internal.cors`, bean `synapseCorsFilter`) runs at `HIGHEST_PRECEDENCE + 3`, on REQUEST and ERROR dispatches. On a request:<br>• no configuration for the request → the chain runs;<br>• a rejection → a `CORS_REJECTED` problem through `ProblemWriter`, and the chain doesn't run;<br>• a valid pre-flight → 200, no body, and the chain doesn't run;<br>• an actual request → the CORS headers are set, then the chain runs.<br>Spring's `DefaultCorsProcessor` decides; Synapse overrides only `rejectRequest`, which signals the filter. On an ERROR dispatch, the processor skips a response that already has `Access-Control-Allow-Origin`. `ConcurrencyLimitFilter`, `RequestSizeLimitFilter` and `ClientErrorFilter` move to `+ 4`, `+ 5` and `+ 6`. | DESIGN 4.1 *CORS edge filter*. The renumbering is unreleased internal ordering, so this is the cheapest moment; the docs' "order body-reading filters after" line becomes `+ 6`. |
| 16 | Module `api/synapse-api-rest-openapi`, packages `io.americanexpress.synapse.api.rest.openapi.autoconfigure` and `…openapi.internal`, with no other public API. Root pom: `<springdoc.version>3.2.0</springdoc.version>` (comment: "not managed by Spring Boot; 3.2.x is built on Boot 4.1.2, see https://github.com/springdoc/springdoc-openapi/releases/tag/v3.2.0"), and `dependencyManagement` entries for `synapse-api-rest-openapi` and `org.springdoc:springdoc-openapi-starter-webmvc-api`. `synapse-bom` lists both (springdoc with the literal `3.2.0`, as for ArchUnit). `synapse-parent` imports the BOM and needs nothing: springdoc is not a Boot-managed version. | BUILD §3.4: the third-party libraries Synapse adds go in the BOM. |
| 17 | `OpenApiDefaultsEnvironmentPostProcessor` (order `ConfigDataEnvironmentPostProcessor.ORDER + 1`) adds, through `DefaultPropertiesPropertySource.addOrMerge` and only for keys the application's own default properties don't set (as `RestDefaultsEnvironmentPostProcessor` does): `springdoc.swagger-ui.enabled=false`, `springdoc.api-docs.enabled=true`, `springdoc.api-docs.version=openapi_3_1`. When `springdoc.api-docs.version` resolves to `openapi_3_0`, creating the customizer bean fails startup: problem `springdoc.api-docs.version is openapi_3_0, but Synapse documents its REST routes in OpenAPI 3.1 (JSON Schema 2020-12)`; action `Remove springdoc.api-docs.version, or set it to openapi_3_1.` | BUILD §6.2. The generator writes 3.1 constructs (`type: [..., "null"]`). |
| 18 | Schemas (`SchemaGenerator`):<br>• `String` → `string`; `UUID` → `string`/`uuid`; `URI` → `string`/`uri`; `boolean` → `boolean`;<br>• `byte`/`short`/`int` → `integer`/`int32`; `long` → `integer`/`int64`; `BigInteger` → `integer`; `float`/`double` → `number`/`float`/`double`; `BigDecimal` → `number`;<br>• `Instant`, `OffsetDateTime`, `ZonedDateTime` → `string`/`date-time`; `LocalDate` → `string`/`date`; `Duration` → `string`/`duration`; other `java.time` types → `string`;<br>• `byte[]` → `string`/`byte`; other arrays, `List` and `Collection` → `array` (`Set` adds `uniqueItems`); `Map<String, V>` → `object` with `additionalProperties`;<br>• `Optional<T>` → `T`, nullable;<br>• an enum → a `string` component whose `enum` lists each constant as the app's `JsonMapper` writes it;<br>• a record → a component, `$ref '#/components/schemas/<name>'`.<br>A record component's properties are named by Jackson 3 introspection of the app's mapper, in component order: serialization for outputs; deserialization (`JsonNames`) for inputs. `required` lists the non-nullable components (JSpecify, `ResolvedComponent.nullable()`). A nullable property is `types: [<t>, "null"]`, or `anyOf: [{$ref}, {type: "null"}]` for a reference.<br>Anything else is `{}`, and its type is reported once (the WARN in *Defaults* #22). | BUILD §6.2: names match what the app's `JsonMapper` writes. The formats assume Boot's Jackson 3 defaults (ISO dates), which the docs state. |
| 19 | Component names: the simple name. A generic record parameterization is `<Raw>Of<Arg1>And<Arg2>` (`EnvelopeOfBook`). A record whose deserialization names differ from its serialization names gets a second component, `<name>Input`. When two types claim one name, both use their fully qualified name (dots kept; OpenAPI allows `.`). Components are memoized per `ResolvableType` (one walk per distinct type, a `$ref` on revisit, so recursion and reuse stay linear). The generator stops after 10,000 distinct types, and the type that would exceed it is `{}` with the WARN. | DESIGN 4.1 *OpenAPI generation* (the 2b-R24 lesson). |
| 20 | Constraints come from the Jakarta `Validator` bean's metadata (`getConstraintsForClass(record).getConstraintsForProperty(component)`, `Default` group, composed constraints flattened, container elements applied to `items`/`additionalProperties`):<br>• `@NotNull` → required;<br>• `@NotBlank` → required, `minLength: 1`;<br>• `@NotEmpty` → required, `minLength`/`minItems: 1`;<br>• `@Size` → `minLength`/`maxLength` or `minItems`/`maxItems`;<br>• `@Min`/`@Max` → `minimum`/`maximum`;<br>• `@DecimalMin`/`@DecimalMax` → `minimum`/`maximum`, or `exclusiveMinimum`/`exclusiveMaximum` when not inclusive;<br>• `@Positive` → `exclusiveMinimum: 0`; `@PositiveOrZero` → `minimum: 0`; `@Negative` → `exclusiveMaximum: 0`; `@NegativeOrZero` → `maximum: 0`;<br>• `@Pattern` → `pattern`; `@Email` → `format: email`.<br>Other constraints are not described. | What the pipeline validates is what the document states. |
| 21 | Operations (`RouteDocumenter`), one per declared route (the implicit `HEAD` of a `GET` and the fallback `OPTIONS` are not documented):<br>• `operationId` is the operation name, with `-2`, `-3` … on its later routes in route order;<br>• `tags` is `[<@OperationGroup value>]` when there is one.<br>Parameters:<br>• path variables: `in: path`, required;<br>• query, `QueryRecord` and cookie sources: required when not nullable;<br>• headers: `If-Match` and `If-None-Match` are `string` with the description `An entity tag ("5"), or *.`, and `If-Match` is required on `.requireIfMatch()` routes;<br>• paging: `cursor` `string`; `offset` `integer`/`int64` 0–max-offset; `size` `integer` 1–max-size, default default-size (from `PagingSettings`); `sort` only with `.sortable`, `array` of `string` enum `[key, -key, …]` with `style: form`, `explode: false`;<br>• on every operation: the correlation header and the deadline header, optional.<br>`.context` and `.from` components are not documented. The request body (`application/json`) is the input record when every component comes from the body, otherwise an inline object of the body components; `.body(...)` gives that component's schema. It is required when a body component is non-nullable. | Everything a client sends; nothing the server derives. |
| 22 | Responses:<br>• success:<br>&nbsp;&nbsp;– `.status(n)` → `n`; `.status(fn)` → `2XX`; `.created` → 201 + `Location`; `.accepted` → 202 + `Location`;<br>&nbsp;&nbsp;– `void`/`Void`, or `DELETE` without `.status` → 204; otherwise 200;<br>&nbsp;&nbsp;– `.emptyAs(n)` adds `n` without content;<br>• success bodies: the output's schema; `List` → array; `PageResult` → the `<Item>Page` component `{items, nextCursor?, hasNext, total?}` with its members named by introspecting `PageBody`; header-mapped components left out unless `.alsoInBody()` (then an inline object);<br>• success headers: output headers; `ETag`, `Cache-Control` and `Vary` on `.etag` responses; `Link` on paged `GET`/`HEAD`;<br>• 304 (with `ETag`, `Cache-Control`, `Vary`) on `.etag` `GET` routes;<br>• problem responses, `application/problem+json` with the `SynapseProblem` schema (members `type`, `title`, `status`, `detail`, `code`, `correlationId`, `violations[]` of `SynapseViolation` `{field, message}`; required: `type`, `title`, `status`, `code`, `correlationId`). Each status comes from `RestErrorStatuses.statusFor(code, operation)`, so status overrides show, and its description lists the codes:<br>&nbsp;&nbsp;– `VALIDATION_FAILED`/`MALFORMED_REQUEST` when the input isn't `Void`;<br>&nbsp;&nbsp;– the `.notFound` code (default `NOT_FOUND`) for an `Optional` output or a route with path variables;<br>&nbsp;&nbsp;– `PAYLOAD_TOO_LARGE` and `UNSUPPORTED_MEDIA_TYPE` with a request body;<br>&nbsp;&nbsp;– `PRECONDITION_FAILED` on write routes that bind `If-Match` or `If-None-Match`; `STALE_VERSION` on write routes that bind `If-Match`; `PRECONDITION_REQUIRED` with `.requireIfMatch()`;<br>&nbsp;&nbsp;– `default` for every other status (406, 500, 503, 504 and overrides).<br>• The correlation header is on every response (`components.headers`). | Acceptance 7: statuses and problem responses, the ones the edge really sends. |
| 23 | `SynapseOpenApiCustomizer` (bean `synapseRestOpenApiCustomizer`, an `OpenApiCustomizer`, `@ConditionalOnBean(RestBindings.class)`) documents the routes once when it is created and logs one WARN listing every type documented as `{}` (`OpenAPI: no schema for <type> (<route>); documented as {}`). Each `customise(OpenAPI)` documents again, without logging, and merges: Synapse's operations replace springdoc's for the same path and method, other methods and paths are kept, and Synapse's components replace same-named ones. | springdoc caches the document; a type the generator can't describe shows at startup, not at the first docs request. |
| 24 | The REST slice (`@SynapseRestTest`) does not include the OpenAPI auto-configuration. | The slice tests the edge; `/v3/api-docs` is tested with `@SpringBootTest`. |
| 25 | Task 9 (memoizing the paging startup walk, 2b-R24) is in this plan **subject to the maintainer's confirmation**. Nothing else depends on it, so it can be dropped or moved without touching the other tasks. | It was parked "for a follow-up" with no plan; the OpenAPI generator applies the same idea. |

## Review Focus

1. **Unversioned routes after the first `.version(...)`.** Every unversioned Synapse route, escape-hatch controller and error dispatch must answer exactly as before. Spring requires a version once versioning is on, and the probe answered 400 to all of them. Pinned in Task 3: `unversionedRoutesAndControllersKeepWorking`.
2. **CORS headers on Synapse's own error responses.** A browser can read only responses that carry `Access-Control-Allow-Origin`. The 503 of the concurrency limit, the 413 of the size limit, a 405 from the fallback, a binding 400, and an error that reaches the error controller must all carry it for an allowed origin. Pinned in Task 5: `problemResponsesCarryCorsHeaders`.
3. **`If-None-Match: *` versus no header on a create-only `PUT`.** The operation must receive `AnyVersion` for one and `null` for the other. A `Long` bound from `If-Match` on a `PUT` must fail startup and name the type and the fix. Pinned in Task 1: `wildcardAndAbsentBindDifferently`, `plainTypeOnAWriteFailsStartup`.
4. **Documented names under a non-default naming strategy.** With `spring.jackson.property-naming-strategy=SNAKE_CASE`, every property in a request body schema, a response schema and the page schema must equal a JSON key the API actually reads or writes. Pinned in Task 8: `documentedNamesMatchTheWireUnderSnakeCase`.
5. **`Vary` on an ETag route under CORS.** The response must keep both `Vary: Accept` (the ETag route) and Spring's `Vary: Origin, Access-Control-Request-Method, Access-Control-Request-Headers`. A shared cache keyed on one of them would serve the wrong representation or the wrong CORS headers. Pinned in Task 5: `etagRouteVaryKeepsOriginAndAccept`.

---

## File structure

```
core/synapse-core/src/main/java/io/americanexpress/synapse/core/
  conditional/          VersionCondition, package-info                                  (Task 1)
api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/
  RouteSpec (version), RestErrors (3 codes)
  internal/binding/     BindingValidator, RequestBinder, RouteDeclaration (+Version), RouteDefinition (+version)
  internal/version/     SynapseApiVersioning, package-info                              (Task 3)
  internal/cors/        SynapseCorsConfigurationSource, SynapseCorsFilter, package-info (Tasks 4–5)
  internal/route/       RouteCompiler (version predicate), OperationRoutes (public)
  internal/error/       RestExceptionClassifier, ClientErrorFilter (+6)
  internal/edge/        ConcurrencyLimitFilter (+4), RequestSizeLimitFilter (+5)
  autoconfigure/        SynapseRestProperties (+Cors), SynapseRestAutoConfiguration
api/synapse-api-rest-openapi/                                                          (Tasks 6–8)
  pom.xml
  src/main/java/io/americanexpress/synapse/api/rest/openapi/
    autoconfigure/      SynapseRestOpenApiAutoConfiguration, OpenApiDefaultsEnvironmentPostProcessor, package-info
    internal/           SchemaGenerator, RouteDocumenter, SynapseOpenApiCustomizer, package-info
  src/main/resources/META-INF/spring.factories, META-INF/spring/…AutoConfiguration.imports
  src/test/resources/application-test.yaml
pom.xml, bom/synapse-bom/pom.xml, AGENTS.md (layout row)
samples/rest-books/     (Task 1: VersionCondition; Task 10: version 2, CORS, OpenAPI)
docs/modules/ROOT/      nav.adoc, pages/rest.adoc, pages/openapi.adoc (new), pages/core.adoc
```

**Existing code these tasks build on** (checked at 208065f2):
- **`RouteSpec` and route definitions:**
  - `RouteSpec` records declarations as `RouteDeclaration` records.
  - `RouteSpec.definition()` calls `RouteDefinition.declared(method, path, operation, declarations, response)`.
  - `RouteDefinition` has 11 components, the last being `PagingRules paging` and `ConditionalRules conditional`.
  - `resolved(...)` copies the identity components.
- **`BindingValidator`:**
  - `validate` resolves each route with a `RouteResolver`, whose duplicate key is `METHOD + path` with the variables blanked.
  - `conditionalHeader(sources, name)` already finds the one `If-Match`/`If-None-Match` source and requires `@Nullable`.
  - `isWrite` means `POST`/`PUT` only (for bodies); `Preconditions.isRead` treats every method except `GET`/`HEAD` as checked.
- **`RequestBinder`:**
  - `header(InputSource.Header)` parses the two headers with `EntityTags.parse` and converts `parsed.tags().getFirst().tag()` with `convert(component, values)`.
  - `checkBindable` → `checkConvertible` checks `String` → the component type for header sources.
- **Routes and the fallback:**
  - `RouteCompiler.predicate(method, route)` is `NOT_ERROR_DISPATCH.and(method(...)).and(path(route.path()))`.
  - `OperationRoutes` (package-private, `internal.route`) reads the `RouteDefinition`s out of a router function.
  - `MethodNotAllowedMapping` builds its templates from `OperationRoutes.of(...)`.
- **Errors and filters:**
  - `RestExceptionClassifier.classify` maps Spring exceptions and falls back to `ErrorResponse` status → `StatusErrors.forStatus` (a version exception is `MALFORMED_REQUEST` today).
  - Filter orders: inbound propagation `HIGHEST_PRECEDENCE`, Boot observation `+1`, context `+2`, concurrency `+3`, size `+4`, client error `+5`.
  - `ProblemWriter.write(request, response, SynapseException)` uses `resetBuffer()`, which keeps headers already set.
- **Other beans:** `PagingSettings`, `RestErrorStatuses.statusFor(ErrorDetails, @Nullable String operation)`, `JsonNames.of(JsonMapper, Class<?>)` and `ManagementPaths.excludedPrefixes(Environment, ClassLoader)` are public in their internal packages.
- **Sample:**
  - `UpdateBookRequest.version` and `DeleteBookRequest.version` are `@Nullable Long`.
  - `UpdateBookOperation`/`DeleteBookOperation` copy them into `BookRevision`/`BookRemoval`.
- **Docs:** `rest.adoc` §*`If-None-Match` on writes* holds the `.from(...)` recipe (around lines 609–635).

**Library facts this plan relies on** (verified this session):
- **springdoc 3.2.0.** Release notes read at https://github.com/springdoc/springdoc-openapi/releases/tag/v3.2.0 (and the releases list):
  - the 3.2.x line is "built on Spring Boot 4.1.2", released 24 Oct;
  - 3.1.1 upgraded swagger-core to 2.2.55;
  - 3.0.2 added support for Spring's API versioning with functional endpoints.

  From the POMs: its parent is `spring-boot-starter-parent` 4.1.2, and `springdoc-openapi-starter-common` depends on `spring-boot-starter`, swagger-core-jakarta 2.2.55 and Jackson 2 (`jackson-databind`, `-dataformat-yaml`, `-datatype-jsr310`), managed by Boot's `jackson-2-bom` 2.21.7.
- **Dependency probe.** A pom inheriting the root build (`scratchpad/plan2c/deps`):
  - with the `spring-boot-starter-logging` exclusion, every enforcer rule passes, convergence included;
  - without it, `BannedDependencies` fails on `spring-boot-starter-logging:4.1.2`.
- **Runtime probe** (`scratchpad/plan2c/runtime`, Boot 4.1.2, springdoc 3.2.0):
  - Literal routes `/v2/books/{id}` and `/v1/books/{id}` with `RequestPredicates.version(...)`, plus `/books/{id}`, with a `WebMvcConfigurer` adding `PathApiVersionResolver(0, versionedPathPredicate)` and `setVersionRequired(false)`: `/v2`, `/v1` and `/books` answer 200; `/v3/books/7` is 404.
  - Without `setVersionRequired(false)`, `/books/7` is 400.
  - With `spring.mvc.apiversion.use.path-segment=0`, `/books/7` is 400.
  - springdoc serves `"openapi":"3.1.0"` at `/v3/api-docs` with `"paths":{}`: it does not document the `RouterFunction` bean. An `OpenApiCustomizer` bean is called with empty paths.
- **spring-web/webmvc 7.0.10** (`javap`):
  - `PathApiVersionResolver(int, Predicate<RequestPath>)`: the predicate is `versionPathPredicate`, true for a versioned path; it returns `null` otherwise.
  - `ApiVersionConfigurer`: `useVersionResolver(...)`, `setVersionRequired(boolean)`, `addSupportedVersions(String...)`. `getApiVersionStrategy()` returns `null` without resolvers, but first asserts that no parser, required flag, default or supported version was set (`Assert.state(isNotCustomized())`).
  - `AbstractHandlerMapping.getHandler`: the version is resolved first; its error is thrown only when the mapping found a handler, and never on an ERROR dispatch.
  - `RequestPredicates.version(Object)`: the predicate needs the request's strategy and version attribute (`Assert.state`, "Expect API version attribute"). `RouterFunctionMapping` detects supported versions from it (`SupportedVersionVisitor`) when detection is on.
  - `SemanticApiVersionParser`: `^(\d+)(\.(\d+))?(\.(\d+))?$` after skipping leading non-digits, so `v2` parses as 2.
  - `MissingApiVersionException` and `InvalidApiVersionException` (with its subclass `NotAcceptableApiVersionException`) are `ResponseStatusException`s.
  - `StandardApiVersionDeprecationHandler.configureVersion(String)` → `VersionSpec.setDeprecationDate/Link`, `setSunsetDate/Link`.
  - `AbstractServerResponse` writes headers with `addHeader`, so `Link` and `Vary` set earlier on the servlet response are kept.
  - `CorsFilter(CorsConfigurationSource)` + `setCorsProcessor`; `DefaultCorsProcessor.rejectRequest(ServerHttpResponse)` is `protected`.
  - `HandlerMappingIntrospector` implements `CorsConfigurationSource`.
- **Boot 4.1.2** (`javap` and the metadata):
  - `WebMvcAutoConfigurationAdapter.configureApiVersioning` applies `spring.mvc.apiversion.{required,default,supported,detect-supported,use.header,use.query-parameter,use.path-segment,use.media-type-parameter}` only when set, then the `ApiVersionResolver` beans, the `ApiVersionParser` bean and the `ApiVersionDeprecationHandler` bean.
  - springdoc's metadata has `springdoc.api-docs.enabled`, `springdoc.api-docs.version`, `springdoc.api-docs.path` and `springdoc.swagger-ui.enabled`.
  - Auto-configurations: `org.springdoc.core.configuration.SpringDocConfiguration` and `org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration`.
  - `org.springdoc.core.customizers.OpenApiCustomizer.customise(OpenAPI)`.
  - swagger-models 2.2.55 has `JsonSchema`, `Schema.types(Set)`, `anyOf`, `exclusiveMinimumValue`, `_const` and `SpecVersion`.
- **Spring Security 7.1.2 reference** (https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html): "Spring Security will automatically configure CORS only if a `UrlBasedCorsConfigurationSource` instance is present", and not with more than one `CorsConfigurationSource` bean.
  - **Not verified:** how it treats `HandlerMappingIntrospector` (also a `CorsConfigurationSource`) next to Synapse's bean. Spring Security isn't on this repository's classpath; `synapse-security` (P4) verifies it.

---

### Task 1: `VersionCondition` for `If-Match` and `If-None-Match` on writes

**Files:**
- Create: `core/synapse-core/src/main/java/io/americanexpress/synapse/core/conditional/{VersionCondition,package-info}.java`
- Modify:
  - `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/{BindingValidator,RequestBinder}.java`;
  - `.../api/rest/RouteSpec.java` (the `.header(...)` Javadoc: on a write, the `If-Match`/`If-None-Match` component is a `@Nullable VersionCondition<V>`).
- Modify (every write-route fixture that binds `If-Match`/`If-None-Match` to a plain type: `grep -rn 'header("If-\(None-\)\?Match"' --include=*.java api samples`):
  - `RestConditionalIT`, `BindingValidatorTest`, `RequestBinderTest`, `PreconditionsTest`, `OperationHandlerTest`, `ResponseWriterTest`, `InputDigestTest`, `RestAotProcessingTest`, `SynapseRestAutoConfigurationTest` and their fixtures;
  - `api/synapse-test-rest/.../slice/SynapseRestTestIT.java`.
- Modify (sample, under `samples/rest-books/src/main/java/io/americanexpress/synapse/samples/books/`):
  - `model/{UpdateBookRequest,DeleteBookRequest}.java`;
  - `operation/{UpdateBookOperation,DeleteBookOperation}.java`;
  - `api/rest/BooksRestConfiguration.java` (the PUT/DELETE comments);
  - `samples/rest-books/README.md` (the sentences that say `*` binds `null`).
- Modify (docs): `docs/modules/ROOT/pages/rest.adoc`:
  - *`If-Match` on writes*: the record uses `@Nullable VersionCondition<Long> version`; the table's `version` column reads `null`, `Version(5)`, `AnyVersion`; the example operation uses a `switch`;
  - *`If-None-Match` on writes*: the `.from(...)` recipe is replaced by `PutBookRequest(@Nullable VersionCondition<Long> unless)`, where `AnyVersion` means "create only" and `Version(v)` means "unless it is at `v`".
- Modify (docs): `docs/modules/ROOT/pages/core.adoc` (a `VersionCondition` entry next to `PageQuery`).
- Test:
  - `core/.../core/conditional/VersionConditionTest.java`;
  - the REST tests above;
  - `samples/rest-books/src/test/java/.../{BooksApplicationIT,api/rest/BooksRestTest,operation/BookOperationsTest}.java`.

**Interfaces:**
- Produces:

```java
package io.americanexpress.synapse.core.conditional;
public sealed interface VersionCondition<V> permits VersionCondition.AnyVersion, VersionCondition.Version {
    static <V> VersionCondition<V> any();          // one shared AnyVersion instance
    static <V> VersionCondition<V> of(V version);  // Version(version); null → NullPointerException
    boolean matches(V current);                    // AnyVersion: true; Version: value().equals(current)
    record AnyVersion<V>() implements VersionCondition<V> { }   // toString() "*"
    record Version<V>(V value) implements VersionCondition<V> { } // non-null value; toString() "\"" + value + "\""
}
```

  - Binding per *Defaults* #4.
  - Startup checks per *Defaults* #2, #3 and #5. The `@Nullable` requirement and the one-source-per-header check stay.
  - `checkConvertible` checks `String` → `V` (the resolved type argument) for a `VersionCondition` component.

- [ ] **Step 1: Write the failing tests**

```java
// VersionConditionTest
@Test void anyMatchesEveryVersion()                 // any().matches(1L), matches(99L) true; any() == any()
@Test void versionMatchesOnlyItsValue()             // of(5L).matches(5L) true, matches(6L) false; of(null) → NPE
@Test void toStringShowsTheWireForm()               // "*" and "\"5\""
@Test void switchCoversEveryCase()                  // switch (c) { case null -> "none"; case AnyVersion<Long> a -> "any";
                                                    //   case Version<Long>(Long v) -> "v" + v; } for null, any(), of(5L)
// RequestBinderTest (UpdateBookRequest(UUID id, @Nullable VersionCondition<Long> version, String title), PUT)
@Test void strongTagBindsVersion()                  // If-Match "\"5\"" → of(5L)
@Test void wildcardAndAbsentBindDifferently()       // Review Focus 3: If-Match * → any(); no header → null;
                                                    // If-None-Match * on PutBookRequest.unless → any(); absent → null
@Test void weakTagBindsItsValue()                   // W/"5" → of(5L) (Preconditions answers 412: unchanged)
@Test void listMalformedAndUnconvertibleKeepTheirViolations() // the 2b texts: "must be a single entity tag",
                                                    // "must be an entity tag", "is not a valid value" ("\"abc\"")
@Test void readRouteStillBindsPlainTypes()          // GET with .header("If-None-Match", R::tag), String tag: "\"x\"" → "x"; * → null
// BindingValidatorTest
@Test void plainTypeOnAWriteFailsStartup()          // Review Focus 3: PUT, @Nullable Long bound from If-Match → message of
                                                    // Defaults #5 naming "UpdateBookRequest.version", "PUT", "Long", "VersionCondition<Long>";
                                                    // same for If-None-Match on POST and If-Match on DELETE
@Test void rawOrWildcardVersionConditionFails()     // VersionCondition (raw) and VersionCondition<?> → Defaults #5 text
@Test void versionConditionFromAnotherSourceFails() // .query, .cookie, .header("X-Version", …), default body on PUT → Defaults #5 text
@Test void versionConditionFromContextOrFromIsAllowed()
@Test void unconvertibleVersionTypeFails()          // VersionCondition<Object> → "can't be converted from a string"
// RestConditionalIT (fixture records switched to VersionCondition)
@Test void createOnlyPutUsesAnyVersion()            // PUT /notes/{id} with .header("If-None-Match", PutNoteRequest::unless); the fixture
                                                    // operation throws PRECONDITION_FAILED when it sees any() and the note exists:
                                                    // If-None-Match * on a missing note → 200 created; again → 412; no header → operation sees
                                                    // null → 200 overwrite
// BooksApplicationIT / BooksRestTest: the existing PUT/DELETE tests pass unchanged (status, codes, 428, 412, 409)
// BookOperationsTest
@Test void versionConditionMapsToTheRevision()      // of(3L) → BookRevision.version 3; any() and null → null
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core,api/synapse-api-rest -am test -Dtest='VersionConditionTest,RequestBinderTest,BindingValidatorTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors (no `VersionCondition`), then the new assertions fail.
- [ ] **Step 3: Implement** the type, the binder branch (wrapping the converted tag in `of(...)`, or `any()` for `*`), the validator checks and the convertibility check. Switch every listed fixture and the sample (*Defaults* #6). Update `RouteSpec.header` Javadoc and the two docs sections.
- [ ] **Step 4: Run** `./mvnw -pl core/synapse-core -am verify`, `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest -am verify`, `./mvnw -pl samples/rest-books -am verify`, then the full `./mvnw verify` (background). Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit**: `feat!: bind If-Match and If-None-Match on writes as VersionCondition`.

### Task 2: `.version(...)` on routes

**Files:**
- Modify:
  - `.../api/rest/RouteSpec.java` (`version`);
  - `.../internal/binding/{RouteDeclaration,RouteDefinition,BindingValidator}.java`;
  - every test or fixture that calls the `RouteDefinition` constructor or `declared(...)` directly (`grep -rn "new RouteDefinition(\|RouteDefinition.declared(" --include=*.java api`).
- Test: `.../api/rest/RestBindingsTest.java`, `.../internal/binding/BindingValidatorTest.java`, `.../internal/route/MethodNotAllowedHandlerTest.java`

**Interfaces:**
- Produces:
  - `public RouteSpec<I, O> version(String version)`. Its Javadoc: the route is served at `/v<version>` + its path; `major[.minor[.patch]]`; once per route; versioning is turned on by the first versioned route.
  - `record RouteDeclaration.Version(String version)`.
  - The `RouteDefinition` component `@Nullable String version` (last), set by `declared(method, path, operation, declarations, response)` from the first `Version` declaration; `path()` is the served path.
  - `public String declaredPath()`.
  - `resolved(...)` keeps `version`.
  - Validator checks: *Defaults* #7 and #8.

- [ ] **Step 1: Write the failing tests**

```java
// RestBindingsTest
@Test void versionPrefixesThePath()                // .get("/books/{id}", op).version("2") → path "/v2/books/{id}", version "2",
                                                   // declaredPath "/books/{id}"; .get("/", op).version("1.1") → "/v1.1"; unversioned: version null
// BindingValidatorTest
@Test void versionMustBeMajorMinorPatch()          // "2+", "v2", "", " 2", "1.2.3.4" → SynapseConfigurationException naming the
                                                   // route and "major[.minor[.patch]]"; "1", "1.2", "1.2.3" pass
@Test void versionIsDeclaredOnce()                 // .version("1").version("2") → "declared once"
@Test void sameRouteInTwoVersionsIsAllowed()       // GET /books v1 and v2 → two resolved routes
@Test void equalVersionsOfOneRouteFail()           // GET /books v "2" and v "2.0" → "versions 2 and 2.0 are equal"
@Test void pathVariablesOfVersionedRoutesStillBind() // /books/{id} v2 → Path source for id
@Test void deprecationAndSunsetAreReservedOnVersionedRoutes() // output .header("Deprecation", …) on a versioned route → reserved;
                                                   // on an unversioned route → allowed
// MethodNotAllowedHandlerTest
@Test void versionedRoutesUseTheirServedPaths()   // routes GET /v2/books/{id} → DELETE /v2/books/1 → 405 Allow "GET, HEAD, OPTIONS";
                                                   // /books/1 → not matched (test() false)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am test -Dtest='RestBindingsTest,BindingValidatorTest,MethodNotAllowedHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors, then failures.
- [ ] **Step 3: Implement.** Compare equal versions with `new SemanticApiVersionParser().parseVersion(...)`, grouped by method and `declaredPath()`.
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): declare route versions with .version(...)`.

### Task 3: Spring API versioning for versioned routes

**Files:**
- Create: `.../api/rest/internal/version/{SynapseApiVersioning,package-info}.java`
- Modify:
  - `.../internal/route/RouteCompiler.java` (`.and(RequestPredicates.version(route.version()))` on versioned routes and their implicit `HEAD`);
  - `.../api/rest/RestErrors.java` (`MISSING_API_VERSION`, `UNSUPPORTED_API_VERSION`);
  - `.../internal/error/RestExceptionClassifier.java`;
  - `.../autoconfigure/SynapseRestAutoConfiguration.java`.
- Test:
  - `.../internal/version/SynapseApiVersioningTest.java`;
  - `.../autoconfigure/SynapseRestAutoConfigurationTest.java`;
  - `.../RestErrorsTest.java`;
  - `.../internal/error/SynapseRestExceptionResolverTest.java`;
  - `.../internal/version/RestVersioningIT.java` (new).

**Interfaces:**
- Consumes: `RouteDefinition.version()`, `declaredPath()` (Task 2).
- Produces:
  - `public final class SynapseApiVersioning implements WebMvcConfigurer, Ordered`, with the constructor `SynapseApiVersioning(ObjectProvider<RestBindings> bindings, Environment environment)` and the behaviour of *Defaults* #9–#10. Its templates are parsed as `RequestPredicates.path` parses them (as `MethodNotAllowedHandler` does).
  - Bean `synapseApiVersioning`: `@ConditionalOnBean(RestBindings.class)`, `@ConditionalOnMissingBean(name = "synapseApiVersioning")`.
  - Error codes and mapping per *Defaults* #11.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseApiVersioningTest (a test subclass of ApiVersionConfigurer exposes getApiVersionStrategy())
@Test void withoutVersionedRoutesVersioningStaysOff()   // strategy null; configurer untouched
@Test void versionedRoutesAddAnOptionalPathVersion()    // strategy resolves "/v2/books/1" → "v2", "/books/1" → null,
                                                        // validateVersion(null, request) does not throw; supported contains 2
@Test void pathSegmentPropertyFailsStartup()            // spring.mvc.apiversion.use.path-segment=1 → Defaults #10 text, listing
                                                        // "GET /v2/books/{id} (get-book-v2)"
@Test void runsBeforeBootsAdapter()                     // getOrder() == HIGHEST_PRECEDENCE
// SynapseRestAutoConfigurationTest
@Test void apiVersioningBeanActivatesAndBacksOff()      // bean present with RestBindings; user bean named synapseApiVersioning wins;
                                                        // absent without RestBindings
// RestErrorsTest: statusesAndCategoriesMatchTheSpec gains the two codes (400, INVALID_INPUT)
// SynapseRestExceptionResolverTest
@Test void apiVersionExceptionsHaveTheirCodes()         // MissingApiVersionException → MISSING_API_VERSION 400;
                                                        // InvalidApiVersionException("9"), NotAcceptableApiVersionException → UNSUPPORTED_API_VERSION 400
// RestVersioningIT (@SpringBootTest, MOCK; @ActiveProfiles("test"); GET /books/{id} unversioned, v1 and v2 of /books/{id},
//                  an escape-hatch @RestController GET /legacy/ping)
@Tag("smoke") @Test void versionedRoutesAreServedUnderTheirVersion()  // /v1/books/1 → v1 body; /v2/books/1 → v2 body; each operation called once
@Test void unversionedRoutesAndControllersKeepWorking() // Review Focus 1: /books/1 200, /legacy/ping 200, /books/not-a-uuid 400
                                                        // VALIDATION_FAILED, unknown /nothing 404 ROUTE_NOT_FOUND, POST /books with a body over
                                                        // the limit → 413 PAYLOAD_TOO_LARGE, PUT /books/1 with an undecodable form body
                                                        // (a=%zz) → 400 through the error path
@Test void unknownVersionIs404()                        // /v3/books/1 → 404 ROUTE_NOT_FOUND
@Test void methodNotAllowedOnAVersionedPath()           // DELETE /v2/books/1 → 405 Allow "GET, HEAD, OPTIONS"; HEAD /v2/books/1 → 200
@Test void requiredByBootAppliesToEveryRoute()          // spring.mvc.apiversion.required=true → /books/1 → 400 MISSING_API_VERSION; /v2/books/1 → 200
@Test void headerVersionNoRouteServesIs400()            // spring.mvc.apiversion.use.header=API-Version → GET /books/1 + API-Version: 9 →
                                                        // 400 UNSUPPORTED_API_VERSION; /v2/books/1 without the header → 200
@Test void deprecatedVersionSendsSpringsHeaders()       // a StandardApiVersionDeprecationHandler bean: configureVersion("1")
                                                        // .setDeprecationDate(2026-01-01Z).setSunsetDate(2027-01-01Z)
                                                        // .setDeprecationLink(URI "https://example.com/v1") → /v1/books/1 has Deprecation, Sunset,
                                                        // and Link rel="deprecation"; /v2/books/1 has none; a paged v1 route keeps its rel="next" Link too
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am verify -Dit.test=RestVersioningIT -Dtest='SynapseApiVersioningTest,RestErrorsTest,SynapseRestExceptionResolverTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: failures: without the predicate and resolver, `/v3` is still 404, but the deprecation headers, the 400 codes and the required case fail.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): serve versioned routes on Spring's API versioning`.

### Task 4: CORS settings and the `corsConfigurationSource` bean

**Files:**
- Create: `.../api/rest/internal/cors/{SynapseCorsConfigurationSource,package-info}.java`
- Modify:
  - `.../autoconfigure/SynapseRestProperties.java` (`@DefaultValue Cors cors`);
  - `.../autoconfigure/SynapseRestAutoConfiguration.java`;
  - `src/main/resources/META-INF/additional-spring-configuration-metadata.json` (only if the processor doesn't take the descriptions from the Javadoc).
- Test: `.../autoconfigure/{SynapseRestPropertiesTest,SynapseRestAutoConfigurationTest}.java`, `.../internal/cors/SynapseCorsConfigurationSourceTest.java`

**Interfaces:**
- Produces:
  - `SynapseRestProperties.Cors` (*Defaults* #12), with Javadoc on every component.
  - `public static SynapseCorsConfigurationSource of(SynapseRestProperties.Cors cors, String correlationHeader, List<String> managementPrefixes)` (*Defaults* #13–#14). It logs the `*` WARN once, when it is called.
  - Bean `corsConfigurationSource` (type `UrlBasedCorsConfigurationSource`). It takes `SynapseRestProperties`, `SynapseCorrelationProperties`, `Environment` and `ResourceLoader` (for the management prefixes).

- [ ] **Step 1: Write the failing tests**

```java
// SynapseRestPropertiesTest
@Test void corsDefaultsMatchTheSpec()              // origins [], methods [GET, HEAD, POST, PUT, DELETE], headers [*], exposed [],
                                                   // credentials false, maxAge 30m
@Test void rejectsInvalidOrigins()                 // "https://a.example.com/", "https://a.example.com/x", "https://*.example.com",
                                                   // "a.example.com" → IAE naming "synapse.api.rest.cors.allowed-origins"; "http://localhost:3000" ok
@Test void rejectsWildcardWithCredentials()        // ["*"] + allowCredentials → IAE with the Defaults #12 text
@Test void rejectsUnknownMethodsAndNegativeMaxAge()
// SynapseCorsConfigurationSourceTest
@Test void noOriginsMeansNoConfiguration()         // getCorsConfiguration(GET /books) == null
@Test void configurationCarriesTheSettings()       // origins, methods, headers, allowCredentials, maxAge 1800
@Test void synapseHeadersAreAlwaysExposed()        // correlation "X-Correlation-Id" + exposed ["X-Books-Total", "etag"] →
                                                   // [X-Correlation-Id, ETag, Link, Location, Retry-After, Deprecation, Sunset, X-Books-Total]
@Test void managementPathsAreLeftToBoot()          // prefixes ["/actuator"] → /actuator/health null, /books config
@Test void wildcardOriginWarnsOnce(CapturedOutput)  // ["*"] → one WARN with the Defaults #14 text, in any active profile
// SynapseRestAutoConfigurationTest
@Test void corsSourceIsAlwaysPresent()             // with and without RestBindings; empty without origins
@Test void corsSourceBacksOffByName()              // user bean named corsConfigurationSource wins (a HandlerMappingIntrospector doesn't count)
@Test void bindsCorsProperties()                   // allowed-origins[0]=https://app.example.com, max-age=10m, exposed-headers=X-A → read back
@Test void wildcardWithCredentialsFailsStartup()   // context fails; root cause IAE with the Defaults #12 text
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am test -Dtest='SynapseRestPropertiesTest,SynapseCorsConfigurationSourceTest,SynapseRestAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): add synapse.api.rest.cors and the corsConfigurationSource bean`.

### Task 5: The CORS edge filter

**Files:**
- Create: `.../api/rest/internal/cors/SynapseCorsFilter.java`
- Modify:
  - `.../api/rest/RestErrors.java` (`CORS_REJECTED`);
  - `.../internal/edge/{ConcurrencyLimitFilter,RequestSizeLimitFilter}.java` and `.../internal/error/ClientErrorFilter.java` (orders `+4`, `+5`, `+6`, with their Javadoc);
  - `.../autoconfigure/SynapseRestAutoConfiguration.java` (`synapseCorsFilter`).
- Test:
  - `.../internal/cors/{SynapseCorsFilterTest,RestCorsIT}.java`;
  - the order assertions in `ConcurrencyLimitFilterTest`, `RequestSizeLimitFilterTest`, `ClientErrorFilterTest` and `SynapseRestAutoConfigurationTest`;
  - `RestErrorsTest`.

**Interfaces:**
- Consumes: the `corsConfigurationSource` bean (Task 4), `ProblemWriter`.
- Produces:
  - `public SynapseCorsFilter(CorsConfigurationSource source, ProblemWriter writer)`, an `OncePerRequestFilter` with `getOrder()` `HIGHEST_PRECEDENCE + 3`. `shouldNotFilterErrorDispatch()` returns false, and `doFilterNestedErrorDispatch` applies the same processing, so an error dispatch nested in the REQUEST dispatch is covered too (`OncePerRequestFilter` otherwise passes it straight down the chain).
  - Behaviour: *Defaults* #15.
  - Bean `synapseCorsFilter`: a `FilterRegistrationBean`, dispatcher types `REQUEST` and `ERROR`, `@ConditionalOnMissingBean(name = "synapseCorsFilter")`, present in every servlet application with the plugin.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseCorsFilterTest (MockHttpServletRequest/Response, a stub ProblemWriter recording calls)
@Test void withoutConfigurationTheChainRuns()
@Test void rejectionWritesACorsRejectedProblem()     // Origin https://evil.example → writer called with CORS_REJECTED; chain not called
@Test void validPreflightIsAnsweredHere()            // OPTIONS + Origin + Access-Control-Request-Method: PUT → 200, ACAO, ACAM, Max-Age 1800; chain not called
@Test void actualRequestGetsHeadersBeforeTheChain()  // GET + Origin → ACAO and ACEH set before chain.doFilter (asserted inside the chain)
@Test void errorDispatchKeepsHeadersAlreadySet()     // ERROR dispatch with ACAO present → not duplicated, chain runs
@Test void ordersAreRenumbered()                     // cors +3, concurrency +4, size +5, client error +6
// RestCorsIT (@SpringBootTest, RANDOM_PORT; @ActiveProfiles("test");
//            synapse.api.rest.cors.allowed-origins=https://app.example.com; max-concurrent-requests=1 in one nested class)
@Tag("smoke") @Test void preflightIsAllowed()        // OPTIONS /books/1, Origin app, ACRM PUT, ACRH If-Match → 200, ACAO app, ACAM has PUT;
                                                     // no operation runs
@Test void preflightOnAFallbackPath()                // OPTIONS /books (GET-only route), ACRM DELETE → 200 CORS answer (methods are global);
                                                     // the actual DELETE /books → 405 with ACAO (fallback covered)
@Test void disallowedOriginIs403Problem()            // Origin https://evil.example → 403 application/problem+json code CORS_REJECTED,
                                                     // correlationId present, no ACAO
@Test void actualRequestExposesSynapseHeaders()      // GET /books/1 + Origin app → ACAO app; ACEH has X-Correlation-Id, ETag, Link, Location
@Test void problemResponsesCarryCorsHeaders()        // Review Focus 2, Origin app: 404 ROUTE_NOT_FOUND, 405 fallback, 400 binding,
                                                     // 413 size filter, 503 concurrency (nested class, a blocked operation), and an error reaching the
                                                     // error controller (a form body that can't be decoded on PUT, 400) → each has ACAO app
@Test void etagRouteVaryKeepsOriginAndAccept()       // Review Focus 5: GET /books/1 (.etag) + Origin app → Vary values include Accept and Origin
@Test void corsIsOffWithoutOrigins()                 // nested class without origins: Origin https://evil.example → 200, no ACAO, no 403
@Test void managementPathsAreNotTouched()            // /actuator/health (management dependency in test scope) + Origin evil → no 403 from Synapse
@Test void credentialsEchoTheOrigin()                // nested class allow-credentials=true → ACAC true, ACAO app (never *)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest -am verify -Dit.test=RestCorsIT -Dtest='SynapseCorsFilterTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement.** If `spring-boot-actuator-autoconfigure` isn't already a test dependency of `synapse-api-rest`, add it in test scope for `managementPathsAreNotTouched` (the module has other Actuator ITs; check its pom first).
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest -am verify`, then the full `./mvnw verify` (background): the new filter order applies to the sample too.
- [ ] **Step 5: Commit**: `feat(rest): apply CORS with an edge filter and problem+json rejections`.

### Task 6: The `synapse-api-rest-openapi` module and its schema generator

**Files:**
- Create:
  - `api/synapse-api-rest-openapi/pom.xml`;
  - `api/synapse-api-rest-openapi/src/main/java/io/americanexpress/synapse/api/rest/openapi/internal/{SchemaGenerator,package-info}.java`.
- Modify: `pom.xml` (module, the `springdoc.version` property, `dependencyManagement`), `bom/synapse-bom/pom.xml`, `AGENTS.md` (the `api/` row: "API plugins, their test slices and add-ons (synapse-api-rest, synapse-test-rest, synapse-api-rest-openapi)").
- Test: `api/synapse-api-rest-openapi/src/test/java/io/americanexpress/synapse/api/rest/openapi/internal/SchemaGeneratorTest.java` (+ fixture records in the same package)

**Interfaces:**
- Produces:
  - **The module pom.**
    - Parent: the root build. Description: "Synapse OpenAPI 3.1 for REST bindings, served by springdoc."
    - Compile: `synapse-api-rest`, `org.springdoc:springdoc-openapi-starter-webmvc-api` with an `<exclusion>` of `org.springframework.boot:spring-boot-starter-logging`, and `jspecify`.
    - Test: the same as `synapse-api-rest` (`synapse-test`, `spring-boot-starter-test`, `spring-boot-starter-opentelemetry`, `spring-boot-resttestclient`, `micrometer-tracing-test`, `spring-core-test`).
  - **The generator:**

```java
public final class SchemaGenerator {
    public SchemaGenerator(JsonMapper mapper, jakarta.validation.Validator validator);
    public Schema<?> output(ResolvableType type);        // serialization view; a $ref for records and enums
    public Schema<?> input(ResolvableType type);         // deserialization view
    public Schema<?> outputObject(Class<?> record, Predicate<String> components); // inline object of some components
    public Schema<?> inputObject(Class<?> record, Predicate<String> components);
    public Schema<?> page(ResolvableType itemType);      // the <Item>Page component (Defaults #22)
    public Schema<?> parameter(ResolvedComponent component); // type + constraints, no nullability (parameters carry `required`)
    public Map<String, Schema<?>> components();          // sorted by name
    public Set<String> undescribed();                    // type names documented as {}
}
```

  - Every schema is a swagger `JsonSchema` with `SpecVersion.V31`. The rules: *Defaults* #18–#20.

- [ ] **Step 1: Write the failing tests**

```java
// SchemaGeneratorTest (JsonMapper.builder().build() unless stated; Validation.buildDefaultValidatorFactory().getValidator())
@Test void simpleTypesHaveTheirTypesAndFormats()      // a record with every type of Defaults #18 → types/formats as listed
@Test void recordsBecomeComponentsWithRequired()      // Book(UUID id, String title, @Nullable Integer year) → $ref Book;
                                                      // required [id, title]; year types [integer, null]
@Test void nullableReferenceUsesAnyOf()               // @Nullable Address → anyOf [$ref Address, {type: null}]
@Test void enumsListTheirWireValues()                 // enum with @JsonProperty("by-title") constant → enum ["by-title", "YEAR"]
@Test void namesFollowTheMappersNamingStrategy()      // SNAKE_CASE mapper: publishedAt → "published_at", in output and input
@Test void inputAndOutputNamesCanDiffer()             // @JsonProperty(access = READ_ONLY) component → Book has it, BookInput doesn't
@Test void constraintsBecomeKeywords()                // every constraint of Defaults #20, including List<@NotBlank String> → items.minLength 1
                                                      // and @DecimalMin(value = "0", inclusive = false) → exclusiveMinimum 0
@Test void recursiveAndSharedTypesAreWalkedOnce()     // Node(String name, List<Node> children) → one component, a $ref inside;
                                                      // the 2b Grow2<T> shape (Optional<Grow2<List<T>>>, Optional<Grow2<Set<T>>>) finishes
                                                      // in < 1 s with ≤ 64 components (assertTimeoutPreemptively)
@Test void genericRecordsAreNamedByTheirArguments()   // Envelope<Book> → EnvelopeOfBook; Pair<Book, Author> → PairOfBookAndAuthor
@Test void clashingSimpleNamesUseQualifiedNames()     // a.Book and b.Book → "…a.Book", "…b.Book"
@Test void unknownTypesAreEmptyAndReported()          // a component of type Object / Thread → {} and undescribed() contains it
@Test void pageObjectFollowsPageBody()                // page(Book) → BookPage {items [$ref Book], nextCursor, hasNext, total}, required [items, hasNext]
@Test void visitBudgetStopsTheWalk()                  // a generator with a budget of 3 types → the 4th distinct type is {} and reported
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest-openapi -am test -Dtest=SchemaGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors, then failures.
- [ ] **Step 3: Implement.** The visit budget (*Defaults* #19) is a package-private constructor argument, so the test can lower it.
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest-openapi -am verify` (the enforcer runs here: convergence and bans must pass) and `./mvnw -pl bom/synapse-bom verify`.
- [ ] **Step 5: Commit**: `feat(openapi): add synapse-api-rest-openapi with a Jackson 3 schema generator`.

### Task 7: Documenting the resolved routes

**Files:**
- Create: `api/synapse-api-rest-openapi/src/main/java/io/americanexpress/synapse/api/rest/openapi/internal/RouteDocumenter.java`
- Modify: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/route/OperationRoutes.java` (`public final class`, `public static List<RouteDefinition> of(...)`)
- Test: `api/synapse-api-rest-openapi/src/test/java/.../openapi/internal/RouteDocumenterTest.java` (routes resolved through `BindingValidator.validate`, as `RouteFixtures` does in `synapse-api-rest`)

**Interfaces:**
- Consumes: `SchemaGenerator` (Task 6); `RouteDefinition` and its rules; `PagingSettings`; `RestErrorStatuses`.
- Produces:

```java
public final class RouteDocumenter {
    public RouteDocumenter(SchemaGenerator schemas, PagingSettings paging, RestErrorStatuses statuses,
            String correlationHeader, String deadlineHeader);
    public Fragment document(List<RouteDefinition> routes);
    public record Fragment(Map<String, PathItem> paths, Map<String, Schema<?>> schemas, Map<String, Header> headers,
            Set<String> undescribed) { }
}
```

  Rules: *Defaults* #21–#22. The `SynapseProblem` and `SynapseViolation` schemas and the correlation header are in the fragment's components.

- [ ] **Step 1: Write the failing tests**

```java
// RouteDocumenterTest (the books fixture: GET /books/{id} Optional + .notFound + .etag, GET /books paged + .sortable(SortKey),
//                      POST /books .created + .etag, PUT /books/{id} If-Match, DELETE /books/{id} If-Match + .requireIfMatch,
//                      GET /books/stats with an output header + .alsoInBody, GET /v2/books/{id} .version("2"), a QueryRecord convention)
@Test void everyDeclaredRouteHasOneOperation()        // 8 operations; operationIds are the operation names; no HEAD/OPTIONS operations
@Test void pathQueryCookieAndHeaderParameters()       // id in path required; author in query optional; X-Correlation-Id and Request-Timeout optional
@Test void pagingParametersFollowTheSettings()        // cursor, offset (max 1000), size (1..100, default 20), sort enum [title, -title, year, -year]
                                                      // style form, explode false; renamed parameters (paging.parameters.size=page_size) are used
@Test void conditionalHeadersAndResponses()           // GET /books/{id}: 200 ETag/Cache-Control/Vary, 304; PUT: If-Match optional, 412 and 409;
                                                      // DELETE: If-Match required, 428 PRECONDITION_REQUIRED, 412, 409 STALE_VERSION
@Test void successStatusesAndHeaders()                // POST 201 + Location + ETag; DELETE 204; stats 200 + X-Books-Total header and body property;
                                                      // .status(fn) → "2XX"; .emptyAs(204) adds 204
@Test void requestBodiesHoldOnlyBodyComponents()      // PUT body has isbn/title/author/year, not id/version; required when non-nullable
@Test void problemResponsesUseTheEdgesStatuses()      // 400 lists VALIDATION_FAILED, MALFORMED_REQUEST; 404 lists BOOK_NOT_FOUND; 413/415 on bodies;
                                                      // "default" on every operation; all application/problem+json $ref SynapseProblem
@Test void statusOverridesChangeTheDocumentedStatus() // statuses with [STALE_VERSION]=422 → PUT documents 422, not 409
@Test void versionedRoutesAreDocumentedAtTheirPath()  // /v2/books/{id} present, /books/{id} separate
@Test void pagedResponsesUseThePageComponent()        // GET /books 200 $ref BookPage; Link header on 200
@Test void groupsBecomeTags()                         // an @OperationGroup("books") operation → tags [books]
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest-openapi -am test -Dtest=RouteDocumenterTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest,api/synapse-api-rest-openapi -am verify`.
- [ ] **Step 5: Commit**: `feat(openapi): document every resolved REST route`.

### Task 8: springdoc integration, defaults and `/v3/api-docs`

**Files:**
- Create (under `api/synapse-api-rest-openapi/src/main/`):
  - `java/.../openapi/internal/SynapseOpenApiCustomizer.java`;
  - `java/.../openapi/autoconfigure/{SynapseRestOpenApiAutoConfiguration,OpenApiDefaultsEnvironmentPostProcessor,package-info}.java`;
  - `resources/META-INF/spring.factories` (the EPP);
  - `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Create: `api/synapse-api-rest-openapi/src/test/resources/application-test.yaml` (a comment saying why it exists)
- Test (under `api/synapse-api-rest-openapi/src/test/java/.../openapi/`):
  - `autoconfigure/{SynapseRestOpenApiAutoConfigurationTest,OpenApiDefaultsEnvironmentPostProcessorTest}.java`;
  - `RestOpenApiIT.java`, `RestOpenApiAotIT.java`.

**Interfaces:**
- Consumes: `RouteDocumenter` (Task 7), `OperationRoutes.of`, the `synapseRestRoutes` bean.
- Produces:
  - `@AutoConfiguration(after = {SynapseRestAutoConfiguration.class, SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class})`, with `@ConditionalOnWebApplication(type = SERVLET)` and `@ConditionalOnClass(OpenApiCustomizer.class)`.
  - Bean `synapseRestOpenApiCustomizer` (*Defaults* #23). It takes:
    - `@Qualifier("synapseRestRoutes") RouterFunction<?>`;
    - Boot's `JsonMapper` (or a default one);
    - the `jakarta.validation.Validator` bean (or `Validation.buildDefaultValidatorFactory().getValidator()`);
    - `PagingSettings`, `RestErrorStatuses`, `SynapseCorrelationProperties` and `SynapseRestProperties`.

    It applies the 3.0 check of *Defaults* #17.
  - `OpenApiDefaultsEnvironmentPostProcessor` (*Defaults* #17).
  - No `RuntimeHintsRegistrar` unless `RestOpenApiAotIT` shows a gap: the bound types already get binding hints from core's `OperationTypesAotProcessor`.

- [ ] **Step 1: Write the failing tests**

```java
// OpenApiDefaultsEnvironmentPostProcessorTest
@Test void setsSpringdocDefaultsAtLowestPrecedence()  // empty env → the three values; application.yaml springdoc.swagger-ui.enabled=true wins;
                                                      // an existing defaultProperties value for a key is kept
// SynapseRestOpenApiAutoConfigurationTest (WebApplicationContextRunner)
@Test void customizerActivatesWithBindingsAndSpringdoc()
@Test void customizerBacksOffToAUserBean()            // a bean named synapseRestOpenApiCustomizer
@Test void absentWithoutBindingsOrSpringdoc()         // no RestBindings; FilteredClassLoader(OpenApiCustomizer.class)
@Test void openApi30FailsStartup()                    // springdoc.api-docs.version=openapi_3_0 → the Defaults #17 message
@Test void undescribedTypesWarnOnceAtStartup(CapturedOutput) // a route whose output has an Object component → one WARN naming it
// RestOpenApiIT (@SpringBootTest RANDOM_PORT; @ActiveProfiles("test"); the Task 7 books fixture + an escape-hatch @RestController)
@Tag("smoke") @Test void apiDocsDescribeEveryRoute()  // acceptance 7: GET /v3/api-docs → "openapi" starts "3.1"; every declared route's
                                                      // path and method present with request/response schemas, the success status and
                                                      // application/problem+json responses; components has Book, BookPage, SynapseProblem
@Test void apiDocsCanBeTurnedOff()                    // springdoc.api-docs.enabled=false → 404 ROUTE_NOT_FOUND
@Test void swaggerUiDefaultsOff()                     // environment springdoc.swagger-ui.enabled == "false"; /swagger-ui.html → 404
@Test void escapeHatchControllerStaysDocumented()     // the controller's path present (springdoc's own operation)
@Test void documentedNamesMatchTheWireUnderSnakeCase() // Review Focus 4: spring.jackson.property-naming-strategy=SNAKE_CASE →
                                                      // the property names of the Book, BookPage and PUT body schemas each equal the keys of a real
                                                      // GET /books/{id}, GET /books and an accepted PUT body
// RestOpenApiAotIT (an AOT-generated context as RestAotProcessingTest does it in synapse-api-rest)
@Test void generatedContextServesApiDocs()            // /v3/api-docs has /books/{id}
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl api/synapse-api-rest-openapi -am verify -Dit.test='RestOpenApiIT,RestOpenApiAotIT' -Dtest='OpenApiDefaultsEnvironmentPostProcessorTest,SynapseRestOpenApiAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement.** If the AOT test fails for want of a hint, add `SynapseRestOpenApiRuntimeHints` (registered in `META-INF/spring/aot.factories`), with a `RuntimeHintsPredicates` test, and say so in the report.
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest-openapi -am verify`.
- [ ] **Step 5: Commit**: `feat(openapi): serve REST bindings at /v3/api-docs through springdoc`.

### Task 9 (maintainer to confirm, *Defaults* #25): Memoize the paging startup walk (2b-R24)

**Files:**
- Modify: `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/internal/binding/BindingValidator.java` (`TypeWalk`, `nestedPage`, `digestible`); `specs/v2/DESIGN.md` §4.1 (a row *Paging walk (2026-11-05, P1 plan 2c)* recording the change)
- Test: `.../internal/binding/BindingValidatorTest.java`

**Interfaces:**
- Produces:
  - `TypeWalk` keeps a result per finished parameterization (`ResolvableType.toString()` key) and returns it on a revisit. Recursion through the current path keeps today's answers.
  - `MAX_TYPE_VISITS` counts distinct parameterizations.
  - When the budget runs out, the check fails with its own `SynapseConfigurationException`: problem `the <input|output> type <T> is too large to check: more than 10,000 distinct types`; action `Split the model, or bind the large part with .context or .from.` It no longer reuses "can't be bound into a paging cursor".

- [ ] **Step 1: Write the failing tests**

```java
@Test void sharedTypesAreWalkedOnce()                 // a 4-level shape reusing one record in every component (2.3k visits today) →
                                                      // accepted, and the walk visits ≤ the number of distinct types (package-private counter)
@Test void budgetHasItsOwnMessage()                   // a TypeWalk budget of 5 on a 6-type input → "too large to check", not "paging cursor"
@Test void existingWalkResultsStay()                  // the existing nestedPage/digestible cases (recursive records, generic siblings) unchanged
```

- [ ] **Step 2: Run to verify failure.** Steps 3–4: implement, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `perf(rest): memoize the paging startup type walk`.

### Task 10: `samples/rest-books`: version 2, CORS and OpenAPI

**Files:**
- Modify:
  - `samples/rest-books/pom.xml` (`synapse-api-rest-openapi`);
  - `samples/rest-books/src/main/resources/application-local.yaml` (`synapse.api.rest.cors.allowed-origins: [http://localhost:3000]`, with a comment);
  - `api/rest/BooksRestConfiguration.java`;
  - `README.md`.
- Create (under `samples/rest-books/src/main/java/io/americanexpress/synapse/samples/books/`): `model/BookV2.java`, `operation/GetBookV2Operation.java`, mapping in `operation/BookMapping.java`
- Test: `BooksApplicationIT.java`, `api/rest/BooksRestTest.java`, `operation/BookOperationsTest.java`

**What it shows:**
- `GET /v2/books/{id}`: `.get("/books/{id}", getBookV2).version("2").notFound(BookErrors.BOOK_NOT_FOUND).etag(BookV2::version)`. It returns `BookV2(UUID id, String isbn, String title, List<String> authors, @Nullable Integer year, long version)`, with `authors` split from the stored author on `", "`. The comment explains that `/books/{id}` keeps serving version 1 unchanged.
- OpenAPI at `/v3/api-docs`, with a comment in `BooksRestConfiguration` pointing to it.
- CORS for a local front end in `application-local.yaml` only.

- [ ] **Step 1: Write the failing tests**

```java
// BooksApplicationIT (runs as test)
@Test void versionTwoServesTheNewShape()          // GET /v2/books/{id} → authors list, ETag; GET /books/{id} unchanged; /v3/books/{id} → 404
@Test void apiDocsListEveryRoute()                // /v3/api-docs has /books, /books/{id}, /books/stats, /v2/books/{id}; Book and BookV2 schemas
@Test void corsIsOffInTest()                      // Origin http://localhost:3000 → no ACAO (application-test.yaml sets no origins)
// BookOperationsTest
@Test void bookV2SplitsAuthors()                  // "A, B" → [A, B]; "A" → [A]
// BooksRestTest (@SynapseRestTest)
@Test void versionTwoRouteWorksInTheSlice()
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -pl samples/rest-books -am verify`.
- [ ] **Step 3: Implement.** Keep the layering (`ArchitectureTest` must pass).
- [ ] **Step 4: Run** `./mvnw -pl samples/rest-books -am verify`.
- [ ] **Step 5: Update `README.md` and verify every example by running the app.**
  1. Build with `./mvnw -pl samples/rest-books -am package -DskipTests` (only to produce the jar; Step 4 is the passing build).
  2. Start `java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar --spring.profiles.active=local`. Use another port with `--server.port=18080` if 8080 is taken, and say so.
  3. In Git Bash, against a fresh start, run **every** `curl` block of the README in document order. Paste the real output, trimming only volatile values (ids, correlation ids, dates, tokens), and say so where they appear.
  4. New sections:
     - *Versions*: `GET /v2/books/{id}`, `/books/{id}` beside it, `/v3/...` → 404.
     - *CORS*: a pre-flight with `Origin: http://localhost:3000` → 200 with `Access-Control-Allow-Origin`; `Origin: https://evil.example` → 403 `CORS_REJECTED`; a note that only `application-local.yaml` turns it on.
     - *OpenAPI*: `curl -s http://localhost:8080/v3/api-docs | head -c 600`, and how to add Swagger UI locally (the springdoc UI starter plus `springdoc.swagger-ui.enabled: true` in `application-local.yaml`).
  5. The PUT/DELETE texts explain `If-Match: *` with `VersionCondition` (from Task 1), and *Not here yet* is removed or emptied.

  An example whose output differs from the README is fixed in the README. If it reveals a library or sample bug, stop and report it; don't paper over it.
- [ ] **Step 6: Commit**: `feat(samples): show API versions, CORS and OpenAPI in rest-books`.

### Task 11: Docs, spec status and the full build

**Files:**
- Create: `docs/modules/ROOT/pages/openapi.adoc`
- Modify:
  - `docs/modules/ROOT/nav.adoc` (*OpenAPI* under *Guides*, after *REST*);
  - `docs/modules/ROOT/pages/rest.adoc`;
  - `docs/modules/ROOT/pages/environments.adoc` (CORS origins as a per-environment example, one line);
  - `specs/v2/BUILD.md` §15 P1 (a *Done (plan 2c)* note).

- [ ] **Step 1: Write the docs.** `rest.adoc`:
  - Remove the L40 NOTE ("not available yet (plan 2c)").
  - A new *API versioning* section:
    - `.version`, the served path and the version format;
    - optional versions and unversioned routes;
    - 404 for unknown versions;
    - which `spring.mvc.apiversion.*` keys apply, and `use.path-segment` failing startup;
    - `required=true` applying to every route;
    - deprecation with a `StandardApiVersionDeprecationHandler` bean (example);
    - the two error codes.
  - A new *CORS* section:
    - the properties and defaults (*Defaults* #12–#14);
    - the exposed headers;
    - pre-flight and rejection behaviour (`CORS_REJECTED`);
    - management paths left to `management.endpoints.web.cors.*`;
    - Spring Security using the same `corsConfigurationSource`;
    - per-environment origins in `application-<env>.yaml`;
    - `@CrossOrigin` can't widen Synapse's settings while they are on;
    - origins behind a proxy need Boot's forwarded-header handling to look same-origin.
  - *HEAD and OPTIONS*: "CORS pre-flight requests are answered by `synapseCorsFilter` before that".
  - *Edge filters*: the table with `synapseCorsFilter` at `+ 3` and the new orders, and "after `HIGHEST_PRECEDENCE + 6`".
  - *Errors*: the three codes in the `RestErrors` table.
  - *Configuration*: the CORS keys.

  `openapi.adoc`:
  - the dependency;
  - what is documented (*Defaults* #18–#22), with a short excerpt of the sample's JSON;
  - springdoc's toggles and the three Synapse defaults;
  - adding Swagger UI;
  - the `info` section through an `OpenAPI` bean;
  - escape-hatch controllers documented by springdoc (Jackson 2 names);
  - limits:
    - `.context`/`.from` components are not documented;
    - date formats assume ISO strings;
    - `{}` types and the WARN;
    - groups not supported;
    - the REST slice doesn't serve the document;
    - native images unverified.
- [ ] **Step 2: Build the docs** (`cd docs && npx antora antora-playbook.yml`, as CI does). Check that the property table lists every `synapse.api.rest.cors.*` key with a description.
- [ ] **Step 3: Update BUILD.md §15 P1** with "Done (plan 2c, REST versioning, CORS and OpenAPI)":
  - acceptance 7 (`/v3/api-docs` with every route, schemas, statuses and problem responses);
  - 10 (REST and OpenAPI modules: the new beans' context-runner tests and AOT tests);
  - `VersionCondition` on writes;
  - API versioning;
  - CORS.

  In the delivery list, mark plan 2c done.
- [ ] **Step 4: Run the full build on JDK 25:** `./mvnw clean verify` (background). Expected: BUILD SUCCESS.
- [ ] **Step 5: Run the JDK 21 smoke leg:** `./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke`. Expected: BUILD SUCCESS. Report the test counts per module for both runs.
- [ ] **Step 6: Commit**: `docs: document REST versioning, CORS and OpenAPI`.

---

## Not in this plan

| Item | Where it goes |
|---|---|
| Parked in 2b: revisit `RequestBinder`'s body methods (`POST`/`PUT`) when `PATCH` is added. (`.requireIfMatch`'s method list is gone since 2026-11-07: a required precondition is a non-null `VersionCondition`, and `PATCH` is covered by the read/write split in `BindingValidator.conditionalHeader` and `Preconditions`, where every method but `GET`/`HEAD` is a write.) | P4 (PATCH, DESIGN *Open questions*) |
| `If-None-Match`/`If-Match` precondition evaluation before `execute` (`.precondition(...)`), `Last-Modified` | Later, on request (DESIGN 4.1, 2026-11-04) |
| Parked in 2a: the violation field name `value` for cross-parameter violations, and `getObjectName()` for global errors of escape-hatch controllers | Maintainer decision pending; no plan assigned |
| Parked in 2a: a startup check that every bound operation is the pipeline proxy (core now exempts test doubles, so the original blocker is gone) | Maintainer to assign (fits plan 3, which adds the second plugin with the same need) |
| Parked in 2a: the 405/OPTIONS fallback runs no `HandlerInterceptor`s (interceptor-based auth would let callers see `Allow`) | P4 (`synapse-security`). Its CORS half is Task 5 here. |
| Parked in 2a (minors): `NOT_ERROR_DISPATCH` shows as `unknown(...)` in the Actuator mappings; on mixed Synapse and controller paths, `Allow` lists only the controller's methods; a declared `.head()` on a less specific template takes `HEAD` from a more specific `GET`; `.status(Function)` isn't range-checked at runtime; the welcome page beats Synapse's 405 on `GET /` | Later, on request |
| gRPC mapping of `VersionCondition` and `STALE_VERSION` → ABORTED; gRPC page tokens | 3 |
| springdoc groups (`GroupedOpenApi`) and the REST slice serving `/v3/api-docs` | On request |
| CORS origin patterns (`https://*.example.com`) | On request |
| Version baselines (`.version("2+")`) and a configurable version segment | On request |
| Native verification of springdoc, the schema generator's reflection and the CORS filter | 5 (native nightly) |
| Spring Security's use of `corsConfigurationSource` next to `HandlerMappingIntrospector` | P4 (`synapse-security`) |
| ETag representation suffix and `Vary` for the XML codec | P4 |
