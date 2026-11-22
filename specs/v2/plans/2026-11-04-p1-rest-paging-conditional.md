# P1 plan 2b of 5: REST paging safety and conditional requests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the paging and conditional-request parts of `synapse-api-rest` (acceptance 11 and 12), and extend `samples/rest-books` to show them. The plugin gains:
- `PageQuery` binding from the query string on any verb, with size and offset caps checked at the edge and violations named after the wire parameters;
- per-route sort allow-lists (`.sortable(...)`, `sort=title,-published`);
- opaque cursors: a replaceable `CursorCodec` bean whose default is AES-256-GCM authenticated encryption bound to the operation, the sort and the other inputs, with key rotation;
- `PageResult` responses as a page object (`{items, nextCursor?, hasNext, total?}`) plus `Link: <…>; rel="next"` on GET/HEAD, and the operation's raw cursor never reaching the client;
- `.etag(...)` with `If-None-Match` → 304 (also on HEAD), `Cache-Control: no-cache` and `Vary: Accept`;
- `If-Match` parsed as entity tags into the bound version component; the operation's write-time version check is the precondition. A new core code `STALE_VERSION` becomes 412 `PRECONDITION_FAILED` when a bound `If-Match` was sent and 409 otherwise. A weak or unbindable precondition is 412, and `.requireIfMatch()` gives 428.

API versioning, CORS and OpenAPI are plan 2c.

**Architecture:** Everything stays inside the handler of a Synapse route; no new servlet filter. `RouteSpec` records the new declarations (`.sortable`, `.etag`, `.requireIfMatch`). `BindingValidator` resolves them into the route definition (R4): a paging source, sort keys, the ETag component and the bound `If-Match`/`If-None-Match` headers. `OperationHandler` runs, in order:
1. the Accept check (2a);
2. binding: `PageQuery` from the paging parameters, entity tags from the precondition headers (400s);
3. cursor unwrapping (400);
4. preconditions (412/428);
5. the operation;
6. the `STALE_VERSION` translation;
7. the response.

`ResponseWriter` writes the page object, wraps the next cursor, adds `Link`, and adds `ETag`/304. Core gains one error code.

**Tech Stack:**
- Java 21 (`--release 21`, built on JDK 25).
- Spring Boot 4.1.2 and Spring Framework 7.0.10 (functional endpoints; `org.springframework.http.ETag` as the tag value type).
- Jackson 3.1.7 (`tools.jackson`) with `com.fasterxml.jackson.annotation` 2.21.
- JDK `javax.crypto` (AES/GCM/NoPadding), `java.security.MessageDigest` (SHA-256) and `SecureRandom`. No new dependency.
- JUnit 5, AssertJ, `RestTestClient`.

**Spec:** [specs/v2/BUILD.md](../BUILD.md) §5.1, §6.1, §6.2 (*Input binding*, *Paging safety*, *Response mapping*, *Conditional requests*), §8.2, §15 P1 acceptance 11 and 12, and the reasons in [specs/v2/DESIGN.md](../DESIGN.md). The §4.1 rows dated "2026-11-04, P1 plan 2b" record this plan's maintainer decisions; see *Spec changes this plan records*. The 2a ledger rulings (`.superpowers/sdd/2026-11-03-p1-rest-edge/progress.md`) are binding behaviour already in the code; this plan cites them as R<n>.

## Global Constraints

**Branch, JDK and language**
- Branch: `feature/v2-p1-rest-paging` from local `main` (a263109a). **Never push.** Merge to `main` locally only when the maintainer says so (squash, as for 2a).
- JDK: run `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"` before any `./mvnw`. JDK 21 runs use `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never a global `mvn`.
- Compile with `--release 21`: no Java 22+ APIs. Prefer records for data carriers.
- Every package has a `package-info.java` annotated `@NullMarked`. Nullable types are `@Nullable` (JSpecify). Error Prone and NullAway must pass.
- Formatting: run the build, and Spotless applies the profile locally. Don't hand-format. Every file carries the license header.

**Auto-configuration and properties**
- Every bean is `@ConditionalOnMissingBean` (by type or name). Every new bean gets `WebApplicationContextRunner` tests: it activates, backs off to a user bean, binds its properties, and is absent when its condition fails.
- Properties are typed `@ConfigurationProperties` records under `synapse.*`, with Javadoc on every component. Never an alias for a Boot key.
- Map keys with characters other than letters, digits, `-` and `.` are bracketed in docs, tests and examples.

**Library code**
- No remote I/O during bean creation. No `@ComponentScan`, no `@Import` of framework packages, no `@EnableWebMvc`, no config files in library jars.
- SLF4J API only. Never expose stack traces, exception class names or internal messages. **Never log or return a cursor token, an operation's raw cursor, or key material**, not even at DEBUG.
- **No HMAC anywhere.** `javax.crypto.Mac` is not used: not for cursors, key ids or key derivation (maintainer decision D4). Digests are SHA-256 through `MessageDigest`; encryption is AES-256-GCM through `Cipher`.
- Code outside `..internal..` packages is public API. New implementation classes go in `io.americanexpress.synapse.api.rest.internal.paging` and `…internal.conditional`.
- Plan against the code as it is. Behaviour from the 2a rulings stays:
  - R4: resolved routes;
  - R13: status table;
  - R22: no output headers on List;
  - R24: query string only, never the servlet parameter map;
  - R27: per-property Jackson reads;
  - R30: implicit HEAD, OPTIONS;
  - R34: simple output header types;
  - R43–R45: route order and the fallback;
  - R44: shared classifier;
  - R46/R47: `ClientErrorFilter`.

**Tests**
- Unit tests are `*Test` (surefire); integration tests are `*IT` (failsafe). The new IT classes each tag at least one fast test `@Tag("smoke")`.
- JaCoCo floor per module: 80% line / 70% branch. `-DskipTests` is never a passing build.
- Assert outcomes (status, code, violation field and message, headers, operation invocation counts), not just "no exception".

**Commits**
- Conventional Commits (`feat:`, `fix:`, `test:`, `docs:`, `build:`). Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage by explicit path (`git add <path> …`), never `git add -A` or `git add .`.
- Spring Boot stays on 4.1.2.

**Commands.**
- While iterating on a unit test: `./mvnw -q -pl api/synapse-api-rest -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`.
- While iterating on an IT: `./mvnw -q -pl api/synapse-api-rest -am verify -Dit.test=ClassNameIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`.

Neither counts as a passing build. Every task ends with `./mvnw -pl <module> -am verify` for each module it touched.

## Spec changes this plan records

These edits are committed together with this plan (`docs: record the P1 plan 2b decisions`) on the branch, before Task 1.

**BUILD.md**
1. §2 table:
   - Replace the `synapse-api-rest` purpose "REST plugin: `RestBindings`, problem+json, OpenAPI via springdoc, versioning, CORS, limits." with "REST plugin: `RestBindings`, problem+json, paging, conditional requests, versioning, CORS, limits."
   - Add a row after it:
     `| synapse-api-rest-openapi | P1 (plan 2c) | OpenAPI 3.1 for RestBindings routes, served by springdoc (3.x) at /v3/api-docs: a Synapse customizer built from the resolved routes, with Synapse's own schema generator on Jackson 3. Depends on synapse-api-rest. |` (module name: *Defaults* #32).
2. §5.1 `SynapseErrors` row: after "`CONFLICT`," insert "`STALE_VERSION` (`CONFLICT`: a version check or optimistic lock failed),". Change "each with its matching category" to "each with its matching category unless stated".
3. §6.1 *Protocol-only failures*: after "`MALFORMED_REQUEST` 400" insert ", `PRECONDITION_FAILED` 412 (a weak `If-Match`, or an `If-Match`/`If-None-Match` that the route doesn't bind), `PRECONDITION_REQUIRED` 428 (`.requireIfMatch()` without `If-Match`)".
4. §6.2 *Input binding* table, row *Framework types*, Default column: replace with
   > "`PageQuery` binds from the query-string parameters `cursor`, `offset`, `size`, `sort` on any verb (names under `synapse.api.rest.paging.parameters.*`). It may be the whole input, and an input has at most one. A query component that reuses a paging parameter name fails startup."
5. §6.2 *Paging safety* paragraph: replace with
   > "**Paging safety:** sort keys must be allow-listed per route (`.sortable(SearchBooksRequest.SortKey.class)` or a set of names). The syntax is `sort=title,-published` (a comma list; `-` means descending). An unknown key, or `sort` on a route without `.sortable`, is `INVALID_INPUT`. Offsets are capped (`synapse.api.rest.paging.max-offset`, default 1000). Page size defaults to `synapse.paging.default-size` (20) and is capped by `synapse.paging.max-size` (100). The plugin checks size and offset before the pipeline and reports violations under the parameter names.
   >
   > Cursors are opaque. The edge wraps an operation's cursor with a `CursorCodec` bean, which an app can replace. By default it is AES-256-GCM authenticated encryption, bound to the route's operation, the sort and a digest of the other inputs (not the size), with a key id and an issue time. A tampered, foreign, mismatched or expired cursor is `INVALID_INPUT` on `cursor`.
   >
   > The key comes from `synapse.api.rest.paging.cursor.key` (base64, 256 bits). `cursor.previous-keys` are accepted for decryption during rotation. `cursor.max-age` is unset by default (no expiry). When no key is set, one is generated per start, with a startup WARN in production profiles that cursors then fail across restarts and instances. An operation's raw cursor never reaches the client."
6. §6.2 *Response mapping* table: replace the row `| Empty list / PageResult | 200 with empty items | none |` with two rows:
   - `| Empty list | 200 with [] | none |`
   - `| PageResult | 200 with {"items":[…],"nextCursor":"…","hasNext":true,"total":42} (nextCursor and total omitted when null); GET/HEAD also send Link: <relative URL>; rel="next" with the next cursor, or with the next offset while it is within max-offset | none (output .header, .body and .etag fail startup) |`
7. §6.2 *Conditional requests* bullet: replace with
   > "**Conditional requests** (RFC 9110). `.etag(BookResult::version)` emits a strong `ETag` from a simple output component: the version, plus a representation suffix when the route serves several media types. It is sent on GET/HEAD and on a POST's 201, never on PUT or DELETE (§9.3.4). ETag'd GET/HEAD responses carry `Cache-Control: no-cache` and `Vary: Accept`. `.etag` on a List or `PageResult` output fails startup.
   >
   > On GET/HEAD the plugin evaluates `If-None-Match` after `execute` against the output's tag (weak comparison). On a match it answers 304 with `ETag`, `Cache-Control` and `Vary` and no body.
   >
   > On unsafe methods the operation's conditional write enforces the precondition. `If-Match` (and `If-None-Match`) bound to a component are parsed as entity tags (`.header("If-Match", UpdateBookRequest::version)`):
   > - one strong tag is unquoted and converted;
   > - `*` binds `null` (the component must be `@Nullable`);
   > - a list or an unquoted value is `INVALID_INPUT`;
   > - a weak `If-Match` is 412 without calling the operation.
   >
   > A `STALE_VERSION` failure is 412 `PRECONDITION_FAILED` when the request carried a bound `If-Match`, and 409 otherwise. Other failures map as usual, and a missing resource is 404. An `If-Match` or `If-None-Match` on an unsafe route that doesn't bind it is 412: the precondition can't be verified. `If-Unmodified-Since` and `If-Modified-Since` are ignored (Synapse emits no `Last-Modified`). `.requireIfMatch()` answers 428 `PRECONDITION_REQUIRED` when `If-Match` is absent (`*` counts as present)."
8. §6.2 bullet "No response envelope; metadata travels in headers (correlation id, paging `Link`, `ETag`)." → "No generic `{data, meta}` wrapper: a single resource is the body itself, and a `PageResult` is the page object above. The correlation id, the paging `Link` and `ETag` travel in headers."
9. §6.2 *OpenAPI* paragraph: replace with
   > "**OpenAPI** (plan 2c, module `synapse-api-rest-openapi`): a Synapse customizer built from the resolved routes generates OpenAPI 3.1 from `RestBindings`, `RestConventions` and the record types: Jakarta Validation constraints, statuses, mapped headers, problem+json responses, paging parameters, `ETag`/`If-Match`, versions. It uses Synapse's own schema generator on Jackson 3, so property names match what the app's `JsonMapper` writes, and springdoc (3.x) serves the result at `/v3/api-docs`. Toggles use springdoc's own properties; Synapse defaults Swagger UI to off and the API docs to on, at lowest precedence."
10. §8.2 *Error translation*: "optimistic-lock and unique-constraint failures → `CONFLICT` (unique violations use a code that gRPC maps to ALREADY_EXISTS)" → "optimistic-lock failures → `STALE_VERSION` (`CONFLICT`); unique-constraint failures → `CONFLICT` (with a code that gRPC maps to ALREADY_EXISTS)".
11. §15 P1 acceptance 8: "`@Version` conflicts raise `CONFLICT`" → "`@Version` conflicts raise `STALE_VERSION` (`CONFLICT`)".
12. §15 P1 acceptance 12, two replacements:
    - "a cursor that was tampered with is 400" → "a cursor that was tampered with, or replayed on another route or with another sort, is 400";
    - "`If-None-Match` yields 304 with `ETag`; a stale `If-Match` yields 412; a version clash without `If-Match` yields 409" → "`If-None-Match` yields 304 with `ETag`, also on HEAD; a stale `If-Match` yields 412 without changing the resource, including when a concurrent write wins after the operation's read; a version clash without `If-Match` yields 409; a weak `If-Match` yields 412 without calling the operation; `.requireIfMatch()` without `If-Match` yields 428".
13. §15 *Done (plan 2a)* note: "Plan 2b covers 7 (OpenAPI) and the rest of 11 (page size over the cap via REST) and 12 (…)" → "Plan 2b covers the rest of 11 (page size over the cap via REST) and 12 (`PageQuery`, unknown sort key, tampered cursor, `version` from `If-Match`, 304, 412, 409); plan 2c covers 7 (OpenAPI)."
14. §15 *Carried to later P1 plans*: replace "Plan 2b (REST features): paging safety, conditional requests, API versioning, CORS, OpenAPI." with two bullets:
    - "Plan 2b (REST paging and conditional requests): paging safety, conditional requests, and the `rest-books` sample extended with both."
    - "Plan 2c (REST versioning, CORS, OpenAPI): API versioning, CORS (including the 405/OPTIONS fallback mapping, which has no CORS configuration today), and OpenAPI in `synapse-api-rest-openapi`."

**DESIGN.md**
1. §4.1: add rows dated "2026-11-04, P1 plan 2b":

   | Area | Decision | Why | Rejected alternatives |
   |---|---|---|---|
   | REST plan split (2026-11-04, P1 plan 2b) | Plan 2b: paging safety, conditional requests, the sample. Plan 2c: API versioning, CORS, OpenAPI. Replaces the plan-2b list in the 2026-11-03 *REST plan split* row. | OpenAPI (new dependency, its own generator) is the largest unknown and shouldn't hold back acceptance 11 and 12; OpenAPI must describe paging and ETags, so it lands last either way. | One plan of about 14 tasks; three plans. |
   | Paged response shape (2026-11-04, P1 plan 2b) | A `PageResult` is written as a page object `{items, nextCursor?, hasNext, total?}` (null members omitted), plus a relative `Link: <…>; rel="next"` on GET/HEAD (next cursor, or next offset within `max-offset`). "No response envelope" means no generic `{data, meta}` wrapper. | No standard defines page bodies. Almost every guideline and API puts the next token in the body: AIP-158, Azure, Zalando, JSON:API, OData, Stripe, Slack, Kubernetes. The body form works for POST-bound `PageQuery` (acceptance 12), for browsers without CORS exposure, and for gRPC parity. `Link` serves GitHub-style clients. | `Link` only (POST and browsers can't page); a bare array plus `X-Total-Count` (contradicts "200 with empty items", and an array can't later become an object without a break). |
   | Paging parameters (2026-11-04, P1 plan 2b) | `synapse.api.rest.paging.parameters.{cursor,offset,size,sort}`, `max-offset`, `cursor.*`. A route query component may not reuse a paging name. An input has at most one `PageQuery`, and `PageQuery` may be the whole input. Violations use the wire names, and the edge checks size and offset caps before the pipeline (no double report with core's `page.size`). | Grouped names read cleanly; wire names are what callers sent. | Flat `*-parameter` keys; core's `<component>.size` violation names. |
   | Sort syntax (2026-11-04, P1 plan 2b) | `sort=title,-published`. Keys are allow-listed per route with `.sortable(...)`. An unknown key, or `sort` on a route without `.sortable`, is 400. | Short and common in public APIs (JSON:API style). | Spring Data `sort=title,desc` (comma ambiguity); `sort=title:asc`. |
   | Cursors (2026-11-04, P1 plan 2b) | A replaceable `CursorCodec` bean. The default is AES-256-GCM authenticated encryption bound to the operation, the sort and a digest of the other inputs (not the size). It has a key id, `previous-keys` for rotation and an issue time, with expiry off by default. The key comes from configuration, or is generated per start with a production WARN. Every failure is 400 on `cursor`. Replaces "HMAC-signed opaque cursors" (4.2). | Maintainer: HMAC is disallowed internally (reason to be confirmed). Encryption also satisfies AIP-158 ("must not be user-parseable") and Zalando (encrypted tokens), so operations can't leak hidden values through cursors. JDK only, no dependency. A replaceable bean admits approved or KMS-backed crypto. | HMAC-SHA256 sign-only (the 2026-10-31 spec); pass-through of the operation's cursor. |
   | Conditional writes (2026-11-04, P1 plan 2b) | The operation's write-time version check is the precondition. Core code `STALE_VERSION` (`CONFLICT`); the REST edge answers it 412 `PRECONDITION_FAILED` when a bound `If-Match` was sent, else 409; gRPC (plan 3) maps it to ABORTED. Replaces "preconditions evaluated before `execute`" (4.2 and the 4.3 *Response mapping* row). | It is atomic: the condition can't race the write (RFC 9110 §13.1.1 "MUST NOT perform the requested method"). There is no extra read. It matches Kubernetes, S3 and EF Core. A pre-read must be backed by the same write check anyway. | A declared pre-read `.precondition(...)` (possible later opt-in); remapping the whole `CONFLICT` category to 412 (a duplicate ISBN must stay 409). |
   | `If-Match` binding (2026-11-04, P1 plan 2b) | For header sources named `If-Match`/`If-None-Match`: one strong tag is unquoted and converted; `*` binds `null` (`@Nullable` component, startup check); a weak `If-Match` is 412 at the edge; a list or an unquoted value is 400; a missing resource is 404 from the operation. An unbound `If-Match`/`If-None-Match` on unsafe methods is 412. `If-Unmodified-Since` is ignored. The startup check that path/header/cookie/query components convert from a string is folded in. | A weak tag can never match under strong comparison (§8.8.3.2). Ignoring an unverifiable precondition is a silent lost update (Azure guideline). §13.1.4 requires ignoring `If-Unmodified-Since` without a modification date. | Binding the first tag of a list; leniently accepting unquoted values; ignoring unbound `If-Match`. |
   | Required preconditions (2026-11-04, P1 plan 2b) | `.requireIfMatch()` per route answers 428 `PRECONDITION_REQUIRED` (a protocol-only code) when `If-Match` is absent; `If-Match: *` counts as conditional. Startup fails if the route doesn't bind `If-Match`. | RFC 6585 §3; Zalando lists 428 as "use". | A global switch; deferring 428. |
   | ETags on reads (2026-11-04, P1 plan 2b) | `.etag(Output::component)`: a simple output component formatted by the conversion service, emitted as a strong tag. `If-None-Match` (weak comparison) → 304 with `ETag`, also for HEAD. ETag'd GET/HEAD responses and their 304 carry `Cache-Control: no-cache` and `Vary: Accept`. `.etag` on List or `PageResult` outputs fails startup; the component stays in the body. | The version is what `If-Match` binds back, so one value serves reads and writes. `no-cache` forces revalidation. `Vary: Accept` anticipates the P4 XML codec. | `.etag(Function)` computed tags; a body hash; `private, no-cache`; no caching headers. |
   | Write-response ETags (2026-11-04, P1 plan 2b) | `ETag` is sent on GET/HEAD/304 and on POST (201) only, never on PUT or DELETE. Clients chain PUTs with the `version` in the response body. | RFC 9110 §9.3.4: no validator in a PUT response unless the stored representation equals the request content, which a server-assigned version breaks. | `ETag` on every write (Azure guideline); `ETag` on PUT with a documented caveat. |
   | `PageResult` output options (2026-11-04, P1 plan 2b) | Output `.header`, `.body` and `.etag` on a `PageResult` route fail startup, as for List (2a R22). | A page has no single value for a header or tag. | Allowing `.header("X-Total-Count", PageResult::total)`. |
   | OpenAPI (2026-11-04, decided for plan 2c) | A separate module, `synapse-api-rest-openapi` (changes BUILD §2). A Synapse customizer builds the document from the resolved routes, with Synapse's own schema generator on Jackson 3. | Dependency isolation: springdoc brings Jackson 2, swagger-core and `spring-boot-starter`, whose logging starter is banned in Synapse jars. A generator on Jackson 3 introspection documents exactly the names and nullness the runtime uses. | A compile dependency of `synapse-api-rest`; an optional dependency; springdoc's `RouterOperation` DSL; swagger-core `ModelConverters` (Jackson 2 introspection, names drift under Boot's Jackson 3 settings). |

2. §4.2:
   - Replace "Conditional requests: strong ETags (version + representation suffix), preconditions evaluated before `execute` (412), version clash without `If-Match` is 409 (RFC 9110)." with "Conditional requests: strong ETags (version + representation suffix); preconditions on writes enforced by the operation's conditional write, 412 with a bound `If-Match` and 409 without (changed 2026-11-04, see 4.1)."
   - Replace "Paging: per-route sort allow-list, max offset, HMAC-signed opaque cursors (secret generated per start if unset)." with "Paging: per-route sort allow-list, max offset, opaque encrypted cursors (AES-256-GCM by default through a replaceable `CursorCodec`; key generated per start if unset; changed 2026-11-04, see 4.1)."
3. §4.3:
   - *Response mapping* row, last column: replace "preconditions evaluated before execute (412)" with "preconditions on writes enforced by the operation's conditional write (412 with `If-Match`, 409 without; changed 2026-11-04)".
   - *OpenAPI* row, last column: append " Changed 2026-11-04: a separate module (`synapse-api-rest-openapi`) with a Synapse customizer and Synapse's own schemas on Jackson 3 (see 4.1)."
   - *Response shape* row, last column: "Unchanged." → "Unchanged; clarified 2026-11-04: a `PageResult` is a page object, not a generic envelope (see 4.1)."

**AGENTS.md**
- No edit in this plan. The `api/` layout row describes the modules that exist; plan 2c adds `synapse-api-rest-openapi` to it when it creates the module. No other AGENTS.md text depends on these decisions. The *Errors* and *Spring Boot first* rules are unchanged.

## Defaults this plan sets (confirm at review)

| # | Default | Why |
|---|---|---|
| 1 | Paging parameters come from the **query string on every verb** (R24), never from a JSON body. A POST search pages by re-sending the same body to `…?cursor=<token>`. | One source for every verb; the body stays the operation's. |
| 2 | A paging parameter that is repeated (`size=10&size=20`) is a violation `{size, "must have a single value"}`. `sort` may repeat and the values are joined in order (`sort=title&sort=-year` equals `sort=title,-year`). An empty value (`size=`, `cursor=`) is `"is not a valid value"`. | No silent "first wins" on caps. |
| 3 | Violation texts (field = the configured parameter name):<br>• size not an integer → "is not a valid value"; below 1 → "must be greater than or equal to 1"; above max → "must be less than or equal to <max-size>";<br>• offset not an integer (including overflow) → "is not a valid value"; below 0 → "must be greater than or equal to 0"; above max → "must be less than or equal to <max-offset>";<br>• cursor and offset together → `{cursor, "must not be combined with offset"}`;<br>• a cursor the codec rejects → `{cursor, "is not valid for this request"}` (one text for every reason);<br>• `sort` without `.sortable` → `{sort, "is not supported by this route"}`;<br>• an unknown key → `{sort, "has a key that is not allowed (allowed: title, year)"}`;<br>• an empty element or a lone `-` → `{sort, "is not a valid value"}`;<br>• a repeated key → `{sort, "names a key more than once"}`. | Matches core's texts where they overlap. No caller input is echoed back. |
| 4 | The offset cap is inclusive: `offset == max-offset` is accepted. The offset `Link` is left out when the next offset would exceed `max-offset`. | "Capped at 1000" reads inclusive. |
| 5 | Sort keys:<br>• enum keys use `@JsonProperty` on the constant when present, otherwise the constant name in lowerCamel (`PUBLISHED_AT` → `publishedAt`);<br>• `SortOrder.property` receives the enum's `name()`; for `.sortable(String...)` it is the name itself;<br>• keys are case-sensitive;<br>• no `sort` → an empty sort list (the operation applies its default order). | Stable for operations (`SortKey.valueOf(order.property())`), JSON-like on the wire. |
| 6 | `.sortable` needs a route that binds `PageQuery` (startup failure otherwise). It is declared once per route (a second call fails) and is not available on `RestConventions`. Wire names must match `[A-Za-z0-9_.]+`, and two keys with one wire name fail startup. | Spec: per-route allow-list. |
| 7 | A `PageQuery` component's source is always the paging parameters. A declared source (`.query`, `.header`, `.body`, `.from`, …) or a `RestConventions` rule for it fails startup. The paging-name clash check applies only to routes that bind `PageQuery`. It covers implicit, declared and `QueryRecord` query names. | Routes without paging keep their `size` filters. |
| 8 | `CursorCodec` is public SPI in `io.americanexpress.synapse.api.rest.paging`, marked `@Incubating`:<br>• `String encode(String cursor, CursorScope scope)` and `String decode(String token, CursorScope scope)`; `decode` throws `InvalidCursorException` for any token it rejects;<br>• `record CursorScope(String operation, List<SortOrder> sort, String inputDigest)`.<br>Plan 3 decides whether gRPC page tokens share it from core. Confirmed by the maintainer 2026-11-04. | REST owns the cursor keys today; incubating leaves room to move. |
| 9 | Default token: base64url without padding of `0x01 ‖ kid(4) ‖ nonce(12) ‖ AES-256-GCM ciphertext+tag(16)`.<br>• Plaintext: `iat` (8 bytes, big-endian epoch seconds from the `Clock` bean) ‖ the raw cursor (UTF-8).<br>• AAD: `0x01 ‖ kid ‖ "synapse-cursor" ‖ length-prefixed operation name ‖ length-prefixed normalized sort ("TITLE:ASC,YEAR:DESC") ‖ the input digest`.<br>• A new 96-bit nonce from `SecureRandom` for every token, and a new `Cipher` per call (Java refuses a key+nonce reuse on one instance; verified).<br>• Raw cursors longer than 512 UTF-8 bytes fail `encode` (`IllegalStateException`, 500: an operation bug). Tokens longer than 1024 characters are rejected before decoding. | Binding is in the AAD, which keeps the token small. Length prefixes prevent ambiguous concatenation. |
| 10 | Keys:<br>• `synapse.api.rest.paging.cursor.key` is base64 (standard or URL-safe alphabet, padding optional) and must decode to exactly 32 bytes;<br>• `cursor.previous-keys` (a list) are used only to decrypt;<br>• `cursor.max-age` is a `Duration`, unset (no expiry); when set it must be positive;<br>• the key id is the first 4 bytes of SHA-256(`"synapse-cursor-key-id:"` ‖ key) (a hash, not a MAC);<br>• a bad length or bad base64, or two keys with one id, fail startup with a message that names the property and never the value;<br>• `SynapseRestProperties.Paging.Cursor.toString()` masks the keys. | AES-256 needs exactly 256 bits. A derived id needs no extra setting. |
| 11 | Without `cursor.key`: 32 random bytes at startup. When at least one route returns `PageResult`, the route compiler logs once on `io.americanexpress.synapse.api.rest`: WARN in production, INFO otherwise. The text: "synapse.api.rest.paging.cursor.key is not set, so paging cursors are encrypted with a key generated at startup: a cursor fails with 400 after a restart or on another instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32)." | The sharp WARN the decision asks for, without noise for apps that don't page. |
| 12 | Input digest: SHA-256 (lowercase hex) of the input record without its `PageQuery` component. The record is written as a JSON object keyed by component name, with the app's `JsonMapper` and `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`.<br>• Components bound from `If-Match`/`If-None-Match` are left out.<br>• `.context(...)` and `.from(...)` components are included, so a cursor can't be replayed by another principal or tenant. A component that changes on every request (a correlation id) therefore makes cursors fail (documented).<br>• `PageQuery` as the whole input has the digest of `{}`. | Binds filters as AIP-158/Zalando ask; `size` is excluded (AIP-158). |
| 13 | Cursor failures log one DEBUG line on `io.americanexpress.synapse.api.rest` with the reason (malformed, unknown key id, authentication failed, expired). They never log the token or its contents, and the client sees one message for all reasons. | No oracle; operators can still diagnose. |
| 14 | The page body is an internal record `PageBody(items, nextCursor, hasNext, total)` serialized with the app's `JsonMapper`, so member names follow the app's naming strategy. `nextCursor` and `total` are `@JsonInclude(NON_NULL)`. | Consistent with every other body. |
| 15 | `Link`:<br>• the target is the request URI plus its raw query string with the `cursor` and `offset` parameters removed (the others keep their order and encoding), then `cursor=<token>` or `offset=<n>` appended;<br>• `rel="next"` only, on GET and HEAD only;<br>• the offset link is sent only when the request had no cursor and the result has `hasNext` without a `nextCursor`; `size` is repeated only if the client sent it. | Relative per the decision; no dependence on forwarded headers. |
| 16 | `PageResult` must be the operation's direct output. `Optional<PageResult<…>>` and `List<PageResult<…>>` fail startup. Output `.header`/`.body`/`.etag`/`.alsoInBody` on `PageResult` fail startup, and convention `responseHeader` rules skip it (R22). | One shape per route. |
| 17 | Escape-hatch `@RestController`s that return `PageResult` are **not** wrapped: Spring's converters serialize the raw cursor. This is documented as a warning in `rest.adoc`, with no guard in this plan. **Confirmed by the maintainer 2026-11-04.** | The edge only knows Synapse routes; a guard changes core JSON. |
| 18 | ETag values:<br>• the component types are the output-header simple set (string, number, boolean, enum, `java.time`; not UUID, per R21/R34), formatted with `mvcConversionService`;<br>• a `null` value means no `ETag`, no caching headers and no 304;<br>• a value with a character outside RFC 9110 `etagc` (`"`, space, control characters, DEL) is an `IllegalStateException` → 500 INTERNAL (an operation bug, logged). | Same type rules as output headers; never a malformed header. |
| 19 | `.etag` is allowed on GET (and the implicit HEAD), and on POST with `.created(...)` (201 only). It fails startup on PUT and DELETE, on POST without `.created`, when declared twice, and with a component that isn't part of the output value record. | The decision's "never PUT/DELETE", made a startup error instead of a silent drop. |
| 20 | `If-None-Match` on GET/HEAD:<br>• evaluated only when the response would be 200 with an `ETag`;<br>• the strict parser (#22) with weak comparison (`ETag.compare(other, false)`); `*` matches;<br>• a malformed `If-None-Match` is ignored (answer 200).<br>The 304 carries `ETag`, `Cache-Control: no-cache`, `Vary: Accept` and the correlation id, and no body, `Content-Type`, `Content-Length` or output headers. | RFC 9110 §13.2.1 and §15.4.5. |
| 21 | Reserved output header names (R34's list) gain `ETag` on every route, and `Cache-Control` and `Vary` on `.etag` routes. | Synapse sets them. |
| 22 | Request entity tags use a strict Synapse parser of RFC 9110 §8.8.3: `etagc` = `%x21 / %x23-7E / %x80-FF`, optional whitespace around list members, `*` only alone. Spring's `ETag.parse` is **not** used for requests: a probe against spring-web 7.0.10 showed it turns `"5"x` into `["5"]` and `5` into `[]`. | Strict parsing is the decision (unquoted → 400). |
| 23 | Bound-header violations use the component name, as other header bindings do (R26):<br>• a list (several tags, or several header lines) → `{version, "must be a single entity tag"}`;<br>• unquoted, malformed or empty → `{version, "must be an entity tag"}`;<br>• `*` together with other tags → "must be an entity tag";<br>• a tag value that doesn't convert → `{version, "is not a valid value"}`. | One field per component. |
| 24 | **A bound `If-None-Match` on an unsafe method** is parsed like `If-Match`, but a weak tag binds its opaque value instead of answering 412: RFC 9110 §13.1.2 uses weak comparison, so a weak tag can match. The `STALE_VERSION` → 412 rule applies to `If-Match` only. **This refines decision D8, which listed both headers for the weak → 412 rule; confirmed by the maintainer 2026-11-04 (weak → 412 applies to `If-Match` only).** `If-None-Match: *` binds `null`, so create-only semantics need `.from(...)` (documented). | RFC correctness; the decision text may not have meant If-None-Match. |
| 25 | Preconditions apply to POST, PUT and DELETE. On GET/HEAD an unbound `If-Match` is ignored. Order in the handler: the Accept check → binding (400) → cursor unwrap (400) → preconditions (unbound header or weak tag → 412, then `.requireIfMatch` → 428) → execute → the `STALE_VERSION` translation → the response. | RFC 9110 §13.2.1: preconditions after the normal request checks, just before the action. |
| 26 | The translation: a `SynapseException` whose `details()` is `SynapseErrors.STALE_VERSION`, on a route that binds `If-Match`, when the request carried `If-Match` (including `*`), is rethrown as `new SynapseException(SynapseErrors.PRECONDITION_FAILED, cause)`. That gives 412, code `PRECONDITION_FAILED`, overridable through `errors.status.[PRECONDITION_FAILED]`. Otherwise `STALE_VERSION` maps normally: 409 by category, overridable per code. The 412s the edge raises itself use `PRECONDITION_FAILED`. | One code a client checks for every failed precondition. The pipeline has already logged `STALE_VERSION` once. |
| 27 | New codes and messages:<br>• `SynapseErrors.STALE_VERSION`: "The resource was changed by another request" (`CONFLICT`);<br>• `RestErrors.PRECONDITION_REQUIRED`: "The request must be conditional" (category `PRECONDITION_FAILED`, status 428).<br>The 428 problem carries the violation `{If-Match, "is required; send the ETag from a GET of this resource"}`, so the "how to retry" (RFC 6585 §3) survives production, where `detail` is hidden. | Templates take no arguments (core's `SynapseErrorsTest` rule). |
| 28 | `.requireIfMatch()` is allowed only on POST, PUT and DELETE. The route must bind a header named `If-Match`, declared or by convention (startup failure otherwise). `If-Match: *` satisfies it. | Decision 428. |
| 29 | Filter and route interactions:<br>• no new servlet filter; edge filter orders are unchanged (R46: `ClientErrorFilter` at +5);<br>• all 2b behaviour runs in `OperationHandler`/`ResponseWriter` of Synapse routes only;<br>• escape-hatch controllers, the 405/OPTIONS fallback (R30, R43) and error dispatches are unaffected;<br>• the implicit HEAD of a GET route gets the same `Link`, `ETag` and 304 as GET;<br>• new 400/412/428 failures are `SynapseException`s, so the shared classifier (R44) writes them with no ERROR log. | Smallest surface. |
| 30 | `CursorCodec` bean `synapseCursorCodec`: `@ConditionalOnBean(RestBindings.class)` and `@ConditionalOnMissingBean(CursorCodec.class)`. | Like the other route beans (R31). |
| 31 | Properties validation:<br>• parameter names are non-blank, pairwise distinct and made of RFC 3986 unreserved characters;<br>• `max-offset` ≥ 0;<br>• `max-age` positive when set;<br>• violations are an `IllegalArgumentException` naming the key (the existing style). | Fail at startup. |
| 32 | The plan-2c OpenAPI module is named `synapse-api-rest-openapi`, in `api/`, and is recorded in BUILD §2 now. | Follows `synapse-<area>-<tech>` naming. |
| 33 | The startup convertibility check (folded in) runs in `RequestBinder.checkBindable`. It uses `mvcConversionService.canConvert` from `String` (or `List<String>` for list components) for path, query, `QueryRecord` sub-components, header and cookie sources. For `If-Match`/`If-None-Match` sources the target is the component type. | `checkBindable` already runs at startup (R27) and holds the conversion service. |

## Review Focus

1. **A tampered cursor or a cross-route replay.** A cursor whose ciphertext, tag, key id or version byte was changed, or that is replayed on another route, with another sort or with other filters, must be 400 `VALIDATION_FAILED` with the one `cursor` violation, and the operation must not run. Pinned in Task 6: `cursorFromAnotherRouteSortOrFilterIs400`.
2. **`If-Match` on a write: stale versus race.**
   - A stale `If-Match` must be 412 with the resource unchanged.
   - When the operation's read matched but a concurrent write won before its compare-and-set, the answer must also be 412 with `If-Match` and 409 without it, never 200 and never a lost update.

   Pinned in Task 10: `staleIfMatchIs412AndNothingChanges`, `raceLostAfterTheReadIs412WithIfMatchAnd409Without`.
3. **The raw cursor leaking.** The operation's `nextCursor` string must appear nowhere in the response (body, `Link`, any header), on GET, HEAD and POST. Pinned in Task 6: `rawCursorNeverReachesTheClient`.
4. **An offset cap bypass.** `offset` above `max-offset` must be 400 whether it is sent plainly, signed (`+1001`), as an overflow, repeated, on POST, or with the parameter renamed through `paging.parameters.offset`. A `Link` must never offer an offset above the cap. Pinned in Task 4: `offsetCapCannotBeBypassed`; in Task 7: `offsetLinkStopsAtMaxOffset`.
5. **304 on HEAD.** `HEAD` with a matching `If-None-Match` must be 304 with `ETag`, `Cache-Control: no-cache` and `Vary: Accept`, no body and no `Content-Length` of the GET body; a non-matching one must be 200 with the GET's headers. Pinned in Task 8: `headWithMatchingIfNoneMatchIs304WithValidators`.

---

## File structure

```
core/synapse-core/src/main/java/io/americanexpress/synapse/core/
  error/                SynapseErrors (STALE_VERSION)
api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/
  RouteSpec (sortable, etag, requireIfMatch), RestErrors (PRECONDITION_REQUIRED)
  paging/               CursorCodec, CursorScope, InvalidCursorException, package-info   (public SPI, @Incubating)
  internal/binding/     RouteDeclaration (+Sortable, ETag, RequireIfMatch), InputSource (+Page), RouteDefinition
                        (+paging, conditional), ResponseRules (+etag), PagingRules, ConditionalRules, BindingValidator,
                        RequestBinder
  internal/paging/      PagingSettings, SortKeys, PageQueryReader, CursorKeys, AesGcmCursorCodec, InputDigest,
                        PageCursors, PageBody, PageLinks, package-info
  internal/conditional/ EntityTags, ETagValues, Preconditions, package-info
  internal/route/       OperationHandler, ResponseWriter, RouteCompiler (modified)
  internal/aot/         SynapseRestRuntimeHints (PageBody)
  autoconfigure/        SynapseRestProperties (+Paging), SynapseRestAutoConfiguration (codec bean, wiring)
api/synapse-api-rest/src/main/resources/META-INF/additional-spring-configuration-metadata.json
samples/rest-books/     (Task 12)
docs/modules/ROOT/pages/ rest.adoc, core.adoc
```

**Plan-2a APIs these tasks consume.** Each was checked in the code at a263109a.
- `RouteSpec` declarations are recorded as `RouteDeclaration` records. `RouteSpec.definition()` builds `RouteDefinition.declared(method, path, operation, declarations, response)`.
- `BindingValidator.validate(List<RouteDefinition>, List<RestConventions>)` returns the resolved routes (R4). `RouteResolver` holds the per-route checks; `requireSingleOutput` is R22; `RESERVED_HEADERS` and `isSimpleHeaderValue` are R34.
- `RouteDefinition(HttpMethod, String path, ServiceOperation<?, ?>, String operationName, Class<?> inputType, Class<?> outputType, List<InputSource> sources, ResponseRules response, List<RouteDeclaration> declarations)`, with `outputValueType()` (unwraps `Optional` and `List`) and `pathVariables()`.
- `RequestBinder(JsonMapper, ConversionService)`, `void checkBindable(RouteDefinition)` (called by `RouteCompiler` at startup) and `@Nullable Object bind(RouteDefinition, ServerRequest)`. The query string is parsed from `getQueryString()` (R24). Header values come from `request.headers().header(name)`.
- `ResponseWriter(JsonMapper, ConversionService)` with `ServerResponse write(RouteDefinition, @Nullable Object result, ServerRequest)`. HEAD writes headers without a body; 304 and 204 are written without a body.
- `OperationHandler(RouteDefinition, RequestBinder, ResponseWriter)`: Accept check, bind, execute, write.
- `RouteCompiler(BindingValidator, RequestBinder, ResponseWriter)`: specificity order (R45), implicit HEAD (R30), `NOT_ERROR_DISPATCH` (R43).
- `RestErrorStatuses.statusFor(ErrorDetails, @Nullable String operation)` takes a `RestErrors` code's own status before the category table. `RestExceptionClassifier.errorFor` passes a `SynapseException` through unlogged (R44).
- Core:
  - `PageQuery(@Nullable String cursor, @Nullable Long offset, int size, List<SortOrder> sort)` with `violations(int maxSize)`;
  - `PageResult<T>(List<T> items, @Nullable String nextCursor, boolean hasNext, @Nullable Long total)`;
  - `SortOrder(String property, Direction direction)`;
  - `SynapsePagingProperties(int defaultSize, int maxSize)` (bean);
  - `SynapseEnvironment.isProduction()`;
  - the `Clock` bean;
  - `SynapseException(ErrorDetails, Throwable, Object...)` and `ofViolations(ErrorDetails, List<Violation>)`;
  - `OperationPipeline.validate` reports `PageQuery` component violations as `<component>.<field>` (the edge rejects first, so no double report);
  - `@Incubating` (`io.americanexpress.synapse.core.Incubating`).
- Core's `OperationTypesAotProcessor` already registers `Book` for `PageResult<Book>` (Javadoc, checked).

**Library facts this plan relies on** (verified this session against the local jars, unless marked):
- spring-web 7.0.10 (`javap`):
  - `org.springframework.http.ETag` is `record ETag(String tag, boolean weak)` with `compare(ETag, boolean strong)`, `formattedTag()` and `isWildcard()`;
  - `HttpHeaders.ETAG`, `IF_MATCH`, `IF_NONE_MATCH`, `CACHE_CONTROL`, `VARY` and `LINK` exist;
  - `CacheControl.noCache().getHeaderValue()` exists.
- Behaviour, from a JDK 25 probe: `ETag.parse` is lenient (`"5"x` → `["5"]`, `5` → `[]`, `""` → `[]`). Weak comparison of `"5"` and `W/"5"` is true; strong is false.
- spring-webmvc 7.0.10 (`javap`): `ServerRequest.headers().header(String)` returns `List<String>`; `ServerResponse.status(int)` and `HeadersBuilder.header(String, String...)` exist. `ServerRequest.checkNotModified(String)` exists but is **not used**: it adds no `Cache-Control`/`Vary` and evaluates `If-Match` differently.
- jackson-databind 3.1.7 (`javap`): `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS`, `ObjectMapper.writer()`, `ObjectWriter.with(SerializationFeature)` and `writeValueAsBytes`. jackson-databind depends on `jackson-annotations` (`com.fasterxml.jackson.annotation.JsonProperty`, `JsonInclude`).
- JDK 21.0.12 probe:
  - `Cipher.getInstance("AES/GCM/NoPadding")` with a 32-byte `SecretKeySpec` and `GCMParameterSpec(128, nonce12)` round-trips;
  - `updateAAD` with a different AAD fails with `AEADBadTagException`;
  - re-initializing the same `Cipher` for encryption with the same key and nonce throws `InvalidAlgorithmParameterException`;
  - `Base64.getUrlDecoder()` rejects `+` and `/`.
- NIST SP 800-38D §8.3 (2^32 random-nonce invocations per key): taken from `research-paging.md`, not re-read here.

---

### Task 1: Error codes and paging properties

**Files:**
- Modify:
  - `core/synapse-core/src/main/java/io/americanexpress/synapse/core/error/SynapseErrors.java`;
  - `api/synapse-api-rest/src/main/java/io/americanexpress/synapse/api/rest/RestErrors.java`;
  - `.../api/rest/autoconfigure/SynapseRestProperties.java`;
  - `src/main/resources/META-INF/additional-spring-configuration-metadata.json` (descriptions for `paging.parameters.*` if the processor doesn't take them from Javadoc).
- Test:
  - `core/.../core/error/SynapseErrorsTest.java`;
  - `api/.../api/rest/RestErrorsTest.java`;
  - `.../autoconfigure/{SynapseRestPropertiesTest,SynapseRestAutoConfigurationTest}.java`.

**Interfaces:**
- Produces:
  - `SynapseErrors.STALE_VERSION("The resource was changed by another request", ErrorCategory.CONFLICT)`, placed after `CONFLICT`.
  - `RestErrors.PRECONDITION_REQUIRED("The request must be conditional", PRECONDITION_FAILED, 428)`.
  - `SynapseRestProperties` gains a component `@DefaultValue Paging paging`:

```java
public record Paging(@DefaultValue Parameters parameters, @DefaultValue("1000") long maxOffset,
        @DefaultValue Cursor cursor) {
    public record Parameters(@DefaultValue("cursor") String cursor, @DefaultValue("offset") String offset,
            @DefaultValue("size") String size, @DefaultValue("sort") String sort) {}
    public record Cursor(@Nullable String key, List<String> previousKeys, @Nullable Duration maxAge) {
        @Override public String toString();   // "Cursor[key=<set|unset>, previousKeys=<n>, maxAge=...]"
    }
}
```

  - Compact-constructor rules: *Defaults* #31. `previousKeys` null → `List.of()`.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseErrorsTest
@Test void everyCodeHasItsCategory()              // EXPECTED gains STALE_VERSION -> CONFLICT; values() size 12
@Test void templatesAreExact()                    // STALE_VERSION "The resource was changed by another request"
// RestErrorsTest
@Test void statusesAndCategoriesMatchTheSpec()    // + PRECONDITION_REQUIRED 428 / PRECONDITION_FAILED
// SynapseRestPropertiesTest
@Test void pagingDefaultsMatchTheSpec()           // parameters cursor/offset/size/sort, maxOffset 1000, key null, previousKeys [], maxAge null
@Test void rejectsDuplicateOrInvalidParameterNames() // size=offset → IAE naming "synapse.api.rest.paging.parameters"; "pa ge" → IAE
@Test void rejectsNegativeMaxOffsetAndNonPositiveMaxAge() // -1 → IAE "synapse.api.rest.paging.max-offset"; max-age=0s → IAE "...cursor.max-age"
@Test void cursorToStringNeverShowsKeys()         // key "c2VjcmV0..." → toString() contains "set" and not the value; previousKeys count only
// SynapseRestAutoConfigurationTest
@Test void bindsPagingProperties()                // paging.parameters.size=page_size, max-offset=500, cursor.max-age=1d, cursor.previous-keys[0]=… → read back
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl core/synapse-core,api/synapse-api-rest -am test -Dtest='SynapseErrorsTest,RestErrorsTest,SynapseRestPropertiesTest,SynapseRestAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: the new assertions fail.
- [ ] **Step 3: Implement** the code and properties above, with Javadoc on every component. The metadata must describe every new key: the docs build (Task 13) checks that.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core -am verify` and `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat: add STALE_VERSION, PRECONDITION_REQUIRED and REST paging properties`.

### Task 2: Paging declarations and startup checks

**Files:**
- Create: `.../api/rest/internal/binding/PagingRules.java`, `.../api/rest/internal/paging/{SortKeys,PagingSettings,package-info}.java`
- Modify:
  - `.../api/rest/RouteSpec.java` (`sortable`);
  - `.../internal/binding/{RouteDeclaration,InputSource,RouteDefinition,BindingValidator}.java`;
  - `SynapseRestAutoConfiguration` (a `PagingSettings` bean, `@ConditionalOnBean(RestBindings.class)` and `@ConditionalOnMissingBean`; the `bindingValidator` bean takes it);
  - every test and fixture that constructs `RouteDefinition` or `BindingValidator` directly.
- Test: `.../internal/binding/BindingValidatorTest.java`, `.../internal/paging/SortKeysTest.java`, `.../api/rest/RestBindingsTest.java`

**Interfaces:**
- Consumes: `SynapseRestProperties.Paging.Parameters` (Task 1), `ComponentRefs`, R22 `requireSingleOutput`.
- Produces:
  - `RouteSpec`:
    - `public <E extends Enum<E>> RouteSpec<I, O> sortable(Class<E> keys)`;
    - `public RouteSpec<I, O> sortable(String... keys)`.
  - `RouteDeclaration.Sortable(@Nullable Class<? extends Enum<?>> keys, List<String> names)`.
  - `InputSource.Page(ResolvedComponent component)`, which is added to the sealed `permits`. Each `describe`/`location` switch gains "the paging parameters".
  - `record PagingRules(@Nullable ResolvedComponent component, @Nullable SortKeys sortKeys)`. A `null` component means `PageQuery` is the whole input.
  - `RouteDefinition` gains `@Nullable PagingRules paging` (last component). `declared(...)` sets `null`; `resolved(...)` gains a parameter.
  - `RouteDefinition.outputValueType()` also unwraps `PageResult`.
  - `boolean pagedOutput()`, true when the raw output type is `PageResult`.
  - `SortKeys`:
    - `static SortKeys of(Class<? extends Enum<?>> keys)`;
    - `static SortKeys of(List<String> names)`;
    - `Map<String, String> wireToProperty()` (ordered);
    - `@Nullable String property(String wireName)`;
    - `String allowed()` (wire names, comma-separated).

    Wire names follow *Defaults* #5: `keys.getField(constant.name()).getAnnotation(JsonProperty.class)`.
  - `record PagingSettings(String cursor, String offset, String size, String sort, long maxOffset, int defaultSize, int maxSize)`:
    - `static PagingSettings of(SynapseRestProperties.Paging, SynapsePagingProperties)`;
    - `static PagingSettings defaults()` (standard names, 20/100/1000; for tests and fixtures);
    - `Set<String> names()`.
  - `BindingValidator(PagingSettings settings)` replaces the no-argument constructor.
  - New startup failures, each through the existing `fail(route, problem, action)`:
    1. two `PageQuery` components in one input;
    2. a declared source (any `RouteDeclaration` naming a `PageQuery` component) or a convention source for a `PageQuery` component;
    3. on a route that binds `PageQuery`: a query source (implicit `Query`, declared `Query`, or a `QueryRecord` sub-component name) whose wire name is a paging parameter name;
    4. `.sortable` on a route without `PageQuery`, declared twice, with no keys, with an invalid wire name, or with two keys of one wire name (*Defaults* #6);
    5. output `.header`/`.body`/`.alsoInBody` on a `PageResult` route (R22 extended: "…needs a single value, but the output is a PageResult"); convention response headers are skipped for `PageResult`;
    6. `PageResult` nested in `Optional` or `List`.
  - Resolution:
    - a `PageQuery` component gets `InputSource.Page` before conventions and defaults (verbs: all);
    - `inputType == PageQuery.class` resolves to no sources and `PagingRules(null, sortKeys)`.

- [ ] **Step 1: Write the failing tests**

```java
// BindingValidatorTest (fixtures in the @NullMarked fixture package: SearchBooksRequest(@Nullable String author, PageQuery page),
//   enum SortKey { TITLE, @JsonProperty("year") PUBLISHED_YEAR, AUTHOR_NAME }, SearchAllOperation implements QueryOperation<PageQuery, PageResult<Book>>)
@Test void pageQueryComponentBindsFromPagingParametersOnGetAndPost() // GET and POST /books → sources contain InputSource.Page(page); POST has Body for author
@Test void pageQueryCanBeTheWholeInput()          // get("/all", searchAll) → sources empty, paging().component() null
@Test void twoPageQueryComponentsFail()           // record(PageQuery a, PageQuery b) → "more than one PageQuery"
@Test void declaredSourceForPageQueryFails()      // .query(SearchBooksRequest::page) → "binds from the paging parameters"
@Test void queryComponentWithPagingNameFails()    // record(@Nullable String size, PageQuery page) on GET → "size" and "paging parameter"
@Test void pagingNameClashIgnoredWithoutPageQuery() // record(@Nullable String size) on GET → valid
@Test void renamedPagingParameterIsChecked()      // PagingSettings size "page_size"; component pageSize declared .query("page_size", …) → fails
@Test void sortableNeedsPageQuery()               // get("/books/{id}", getBook).sortable("title") → "sortable" and "PageQuery"
@Test void sortableTwiceFails()
@Test void pageResultRejectsOutputHeaderAndBody() // .header("X-Total", Book::title) on PageResult route → "the output is a PageResult"
@Test void pageResultInsideOptionalFails()
// SortKeysTest
@Test void enumWireNamesUseJsonPropertyElseLowerCamel() // SortKey → {title=TITLE, year=PUBLISHED_YEAR, authorName=AUTHOR_NAME}
@Test void namesMapToThemselves()                 // of(List.of("title","year")) → {title=title, year=year}
@Test void duplicateWireNameFails()               // enum with @JsonProperty("title") on two constants → SynapseConfigurationException
// RestBindingsTest
@Test void sortableIsRecordedAsADeclaration()     // .sortable(SortKey.class) → declarations contain Sortable(SortKey.class, [])
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Keep `RouteDefinition`'s existing component order and append the new component, so diffs in callers stay mechanical. The plan's file list names the fixtures to update (`RouteFixtures`, `BindingFixtures`, every test that calls the canonical constructor).
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): declare and check paging bindings and sort allow-lists`.

### Task 3: Conditional declarations, startup checks and convertibility

**Files:**
- Create: `.../api/rest/internal/binding/ConditionalRules.java`
- Modify:
  - `RouteSpec` (`etag`, `requireIfMatch`);
  - `RouteDeclaration` (`ETag`, `RequireIfMatch`);
  - `RouteDefinition` (gains `ConditionalRules conditional`; the ETag component lives there, not in `ResponseRules`);
  - `BindingValidator`;
  - `RequestBinder.checkBindable`.
- Test: `.../internal/binding/{BindingValidatorTest,RequestBinderTest}.java`

**Interfaces:**
- Produces:
  - `RouteSpec`:
    - `public <T> RouteSpec<I, O> etag(ComponentRef<T, ?> component)`;
    - `public RouteSpec<I, O> requireIfMatch()`.
  - `RouteDeclaration.ETag(ComponentRef<?, ?> component)` and `RouteDeclaration.RequireIfMatch()`.
  - `record ConditionalRules(@Nullable ResolvedComponent etag, boolean requireIfMatch, @Nullable InputSource.Header ifMatch, @Nullable InputSource.Header ifNoneMatch)`, with `static ConditionalRules NONE`. The header sources are the resolved input sources whose name equals `If-Match`/`If-None-Match` (case-insensitive), declared or by convention.
  - Startup failures:
    1. `.etag` per *Defaults* #18/#19: the method; a List/`PageResult` output; not a simple type; twice; a component not in the output value record;
    2. `.etag` makes `Cache-Control`/`Vary` reserved on that route (*Defaults* #21); `ETag` joins `RESERVED_HEADERS`;
    3. `.requireIfMatch()` per *Defaults* #28;
    4. a component bound from `If-Match` or `If-None-Match` that is not `@Nullable` ("`*` binds null; declare UpdateBookRequest.version @Nullable").
  - `RequestBinder.checkBindable(route)` also fails (`SynapseConfigurationException`, "REST route GET /x (op): the component R.c (type T) can't be converted from a string") when `conversionService.canConvert(STRING or STRINGS, target)` is false. It covers `Path`, `Query`, `QueryRecord` sub-components, `Header` and `Cookie` (*Defaults* #33).

- [ ] **Step 1: Write the failing tests**

```java
// BindingValidatorTest
@Test void etagOnGetResolves()                    // get("/books/{id}", getBook).etag(Book::version) → conditional().etag().name() == "version"
@Test void etagOnPutOrDeleteFails()               // put(...).etag(Book::version) → "ETag" and "PUT" and "RFC 9110"
@Test void etagOnPostNeedsCreated()               // post(...).etag(Book::version) without .created → fails; with .created → valid
@Test void etagOnListOrPageResultFails()
@Test void etagNeedsSimpleType()                  // .etag(Book::author) where author is a record → "simple type"
@Test void etagHeaderIsReserved()                 // .header("ETag", Book::version) → "reserved"; on an .etag route .header("Vary", …) → "reserved"
@Test void ifMatchComponentMustBeNullable()       // .header("If-Match", UpdateBookRequest::version) with long version → "@Nullable"
@Test void ifMatchHeaderIsRecognisedCaseInsensitively() // .header("if-match", …) → conditional().ifMatch() present
@Test void requireIfMatchNeedsAnIfMatchBinding()  // put(...).requireIfMatch() without If-Match → "requireIfMatch" and "If-Match"
@Test void requireIfMatchOnGetFails()
// RequestBinderTest
@Test void checkBindableRejectsUnconvertibleSources() // .query("filter", R::filter) where filter is record Filter(int a, int b) → SynapseConfigurationException naming "filter";
                                                      // a component record Isbn(String value) → accepted if canConvert is true (assert the actual result and keep it)
@Test void checkBindableAcceptsSimpleAndListTypes()   // UUID path, List<String> query, Long header → no failure
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Before relying on it, verify what `mvcConversionService.canConvert(String, record)` answers for a record with one `String` component (Spring's `ObjectToObjectConverter` uses a `String` constructor). Record the observed answer in the test comment. Reject only what can't convert.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): declare and check ETags and required preconditions`.

### Task 4: Binding `PageQuery` from the query string

**Files:**
- Create: `.../api/rest/internal/paging/PageQueryReader.java`
- Modify: `.../internal/binding/RequestBinder.java` (constructor and `InputSource.Page`), `SynapseRestAutoConfiguration` (the `requestBinder` bean takes `PagingSettings`)
- Test: `.../internal/paging/PageQueryReaderTest.java`, `.../internal/binding/RequestBinderTest.java`

**Interfaces:**
- Consumes: `PagingSettings`, `SortKeys`, `PagingRules` (Task 2); the parsed query map (R24).
- Produces:
  - `PageQueryReader`:
    - constructor `PageQueryReader(PagingSettings settings)`;
    - `PageQuery read(MultiValueMap<String, String> query, @Nullable SortKeys sortKeys, List<Violation> violations)`. It appends violations (*Defaults* #2, #3) and returns a `PageQuery` with `size` defaulted. The cursor is left as the client's token; Task 6 unwraps it.
  - Sort parsing (*Defaults* #2/#3/#5): split on `,`, trim nothing (a space is part of the key and so unknown), a leading `-` → `DESC`, and map wire → property through `SortKeys`.
  - `RequestBinder(JsonMapper, ConversionService, PagingSettings)`:
    - an `InputSource.Page` component binds through the reader;
    - `inputType == PageQuery.class` returns the reader's `PageQuery` directly;
    - violations join the binder's list in component order, and the binder throws one `VALIDATION_FAILED` as today.

- [ ] **Step 1: Write the failing tests**

```java
// PageQueryReaderTest (PagingSettings.defaults(): default 20, max 100, max-offset 1000)
@Test void defaultsSizeAndLeavesSortEmpty()       // {} → PageQuery(null, null, 20, [])
@Test void readsCursorOffsetSizeAndSort()         // size=5&offset=10&sort=title,-year → (null, 10, 5, [TITLE ASC, PUBLISHED_YEAR DESC])
@Test void sizeOverTheCapIsAWireNameViolation()   // acceptance 11: size=101 → [{size, "must be less than or equal to 100"}]
@Test void offsetCapCannotBeBypassed()            // Review Focus 4: offset=1001, +1001, 99999999999999999999, offset=1&offset=2000,
                                                  // and with parameters.offset=skip: skip=1001 (and offset=1001 then ignored as unknown),
                                                  // and the same values on a POST route → each yields exactly one violation on the
                                                  // offset wire name; offset=1000 → accepted
@Test void cursorWithOffsetIsAViolation()         // cursor=x&offset=0 → {cursor, "must not be combined with offset"}
@Test void sortWithoutSortableIsAViolation()      // sortKeys null, sort=title → {sort, "is not supported by this route"}
@Test void unknownSortKeyIsAViolation()           // acceptance 12: sort=price → {sort, "has a key that is not allowed (allowed: title, year, authorName)"}
@Test void malformedOrRepeatedSortKeys()          // "title,,year", "-", "title,-title" → the texts of Defaults #3
@Test void repeatedSortParametersAreJoined()      // sort=title&sort=-year == sort=title,-year
@Test void repeatedSizeIsAViolation()             // size=1&size=2 → {size, "must have a single value"}
@Test void usesConfiguredParameterNames()         // parameters.size=page_size → page_size=200 → violation field "page_size"; size=200 ignored
// RequestBinderTest
@Test void bindsPageQueryComponentOnGetAndPost()  // acceptance 12: GET /books?size=5 and POST /books?size=5 {"author":"a"} → page.size() 5, author "a"
@Test void bindsPageQueryAsWholeInput()
@Test void pagingViolationsJoinOtherViolations()  // GET ?limit=abc&size=0 → violations [limit…, size…] in component order, one exception
@Test void pagingParametersNeverComeFromTheBody() // POST body {"size":5} → size 20
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Integers are parsed with `Long.parseLong` (`size` within `int`). An overflow or a non-digit value is "is not a valid value". The core `PageQuery.violations` stays as it is: the edge's checks are a superset under wire names.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): bind PageQuery from paging parameters with edge caps`.

### Task 5: `CursorCodec` and the AES-256-GCM default

**Files:**
- Create: `.../api/rest/paging/{CursorCodec,CursorScope,InvalidCursorException,package-info}.java`, `.../internal/paging/{CursorKeys,AesGcmCursorCodec}.java`
- Modify: `SynapseRestAutoConfiguration` (bean `synapseCursorCodec`)
- Test: `.../internal/paging/{AesGcmCursorCodecTest,CursorKeysTest}.java`, `.../autoconfigure/SynapseRestAutoConfigurationTest.java`

**Interfaces:**
- Produces (public, `@Incubating`):

```java
public interface CursorCodec {
    String encode(String cursor, CursorScope scope);
    String decode(String token, CursorScope scope);   // throws InvalidCursorException for every rejected token
}
public record CursorScope(String operation, List<SortOrder> sort, String inputDigest) {
    public String normalizedSort();                   // "TITLE:ASC,PUBLISHED_YEAR:DESC"; "" when empty
}
public final class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String reason);     // the reason is for DEBUG logs, never for the client
}
```

- Internal:
  - `record CursorKeys(Key current, List<Key> previous, boolean generated)`:
    - `record Key(byte[] id, SecretKey secret)` with `id` 4 bytes;
    - `static CursorKeys from(SynapseRestProperties.Paging.Cursor cursor, SecureRandom random)` (*Defaults* #10/#11);
    - `@Nullable Key find(byte[] id)`.
  - `AesGcmCursorCodec implements CursorCodec`:
    - constructor `AesGcmCursorCodec(CursorKeys keys, Clock clock, @Nullable Duration maxAge, SecureRandom random)`;
    - `boolean keyGenerated()`;
    - format, limits and logging per *Defaults* #9 and #13.
  - Bean: `@Bean @ConditionalOnBean(RestBindings.class) @ConditionalOnMissingBean(CursorCodec.class) CursorCodec synapseCursorCodec(SynapseRestProperties, Clock)`. It uses `new SecureRandom()` (never `getInstanceStrong()`, which can block).

- [ ] **Step 1: Write the failing tests**

```java
// AesGcmCursorCodecTest (fixed key bytes, MutableClock.at("2026-11-04T00:00:00Z"), scope = new CursorScope("search-books", [TITLE ASC], "ab12…"))
@Test void roundTrips()                           // decode(encode("raw-7", scope), scope) == "raw-7"
@Test void tokenIsOpaqueAndUrlSafe()              // token matches [A-Za-z0-9_-]+; base64url-decoded bytes don't contain "raw-7"; two encodes differ (nonce)
@Test void flippedBitIsRejected()                 // flip each byte position of the decoded token in turn → InvalidCursorException for every one
@Test void otherOperationSortOrDigestIsRejected() // decode with operation "other", sort [TITLE DESC], digest "cd34…" → InvalidCursorException
@Test void previousKeyDecryptsAndCurrentKeyEncrypts() // codec A(key1) encodes; codec B(current key2, previous [key1]) decodes it; B's tokens carry key2's id
@Test void unknownKeyIdIsRejected()               // token of key1 into codec(key2, no previous) → InvalidCursorException; DEBUG output mentions "unknown key id", not the token
@Test void expiredTokenIsRejectedOnlyWithMaxAge() // max-age 1h: clock +2h → rejected; without max-age → accepted
@Test void malformedTokensAreRejected()           // "", "!!", "AA", 1025 chars, wrong version byte → InvalidCursorException
@Test void oversizedRawCursorFailsEncode()        // 513-byte raw → IllegalStateException
// CursorKeysTest
@Test void decodesStandardAndUrlSafeBase64()      // same 32 bytes both alphabets, with and without padding → same key id
@Test void rejectsWrongLengthWithoutEchoingTheKey() // 16-byte key → SynapseConfigurationException naming "synapse.api.rest.paging.cursor.key", message has no key text
@Test void rejectsDuplicateKeyIds()               // previous-keys contains the current key → failure naming "previous-keys"
@Test void generatesAKeyWhenUnset()               // key null → generated() true, 32-byte key
// SynapseRestAutoConfigurationTest
@Test void cursorCodecActivatesWithBindings()     // with a RestBindings bean → CursorCodec is AesGcmCursorCodec; without → absent
@Test void cursorCodecBacksOffToUserBean()        // user CursorCodec bean → it is the only one
@Test void invalidCursorKeyFailsStartup()         // cursor.key=short → context fails; failure analysis names the property
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
  - The decode order: length cap → base64url → version byte → key id lookup → `iat`/AAD rebuild → `Cipher.doFinal` (`AEADBadTagException` → reject) → expiry check.
  - Every rejection is an `InvalidCursorException` with a short reason.
  - Grep the module for `javax.crypto.Mac` and `Hmac`: there must be none.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): add CursorCodec with an AES-256-GCM default`.

### Task 6: Cursors at the edge and the page body

**Files:**
- Create: `.../internal/paging/{InputDigest,PageCursors,PageBody}.java`
- Modify:
  - `.../internal/route/{OperationHandler,ResponseWriter,RouteCompiler}.java`;
  - `SynapseRestAutoConfiguration` (`InputDigest` and `PageCursors` beans, each `@ConditionalOnBean(RestBindings.class)` and `@ConditionalOnMissingBean`; new constructor arguments; `routeCompiler` gets `SynapseEnvironment`);
  - tests that construct these classes.
- Test:
  - `.../internal/paging/{InputDigestTest,PageCursorsTest}.java`;
  - `.../internal/route/{ResponseWriterTest,OperationHandlerTest,RouteCompilerTest}.java`;
  - new `.../internal/paging/RestPagingIT.java` (first part).

**Interfaces:**
- Consumes: `CursorCodec` (Task 5), `PagingRules`, `PagingSettings` (Task 2), and the binder output (Task 4).
- Produces:
  - `InputDigest`:
    - constructor `InputDigest(JsonMapper mapper)`;
    - `String of(RouteDefinition route, @Nullable Object input)` (*Defaults* #12; SHA-256 via `MessageDigest`, `HexFormat.of()`).
  - `PageCursors`:
    - constructor `PageCursors(CursorCodec codec, InputDigest digest, PagingSettings settings)`;
    - `@Nullable Object unwrap(RouteDefinition route, @Nullable Object input)`. When the bound `PageQuery` has a cursor, it decodes it with `CursorScope(route.operationName(), page.sort(), digest)` and returns the input rebuilt through the record's canonical constructor with `new PageQuery(raw, null, size, sort)`. `InvalidCursorException` becomes `SynapseException.ofViolations(VALIDATION_FAILED, [{<cursor name>, "is not valid for this request"}])`.
    - `@Nullable String wrap(RouteDefinition route, @Nullable Object input, @Nullable String rawCursor)`;
    - `void reportGeneratedKey(List<RouteDefinition> routes, SynapseEnvironment environment)` (*Defaults* #11).
  - `record PageBody(List<?> items, @JsonInclude(NON_NULL) @Nullable String nextCursor, boolean hasNext, @JsonInclude(NON_NULL) @Nullable Long total)`.
  - `ResponseWriter`:
    - constructor `ResponseWriter(JsonMapper, ConversionService, PageCursors)`;
    - `write(RouteDefinition, @Nullable Object result, ServerRequest, @Nullable Object input)` replaces the 3-argument form; update every caller.
    - A `PageResult` result is always written as a `PageBody` with the wrapped cursor. The `PageResult` record itself is never handed to the mapper.
  - `OperationHandler`:
    - constructor `OperationHandler(RouteDefinition, RequestBinder, ResponseWriter, PageCursors)`;
    - order: Accept → bind → `unwrap` → execute → `write(…, input)`. The unwrapped input is passed, and the digest excludes the `PageQuery`, so the scope is the same.
  - `RouteCompiler`:
    - constructor `RouteCompiler(BindingValidator, RequestBinder, ResponseWriter, PageCursors, SynapseEnvironment)`;
    - calls `reportGeneratedKey` once after validation.

- [ ] **Step 1: Write the failing tests**

```java
// InputDigestTest
@Test void ignoresThePageQueryComponent()         // same author, different page → same digest
@Test void changesWithFilters()                   // author "a" vs "b" → different digests
@Test void isStableForMapComponents()             // Map components with insertion orders a,b and b,a → same digest
@Test void excludesPreconditionHeaders()          // a component bound from If-Match changes → same digest
// PageCursorsTest (real AesGcmCursorCodec)
@Test void unwrapReplacesTokenWithRawCursor()     // input page.cursor = wrap(route, input, "raw-3") → unwrapped page.cursor "raw-3", other components equal
@Test void invalidTokenIsCursorViolation()        // → VALIDATION_FAILED, violations [{cursor, "is not valid for this request"}]
@Test void generatedKeyWarnsInProductionOnlyForPagedRoutes() // production + PageResult route → one WARN with the Defaults #11 text; no paged route → nothing; non-production → INFO
// ResponseWriterTest
@Test void pageResultIsAPageObject()              // PageResult([b1], "raw", true, 42) → {"items":[…],"nextCursor":"<token>","hasNext":true,"total":42}; token != "raw"
@Test void nullMembersAreOmitted()                // PageResult.of([], false) → exactly {"items":[],"hasNext":false}
@Test void pageMemberNamesFollowTheNamingStrategy() // SNAKE_CASE mapper → "next_cursor", "has_next"
// RestPagingIT (RANDOM_PORT; fixtures: GET /books (SearchBooksRequest(author, page), sortable SortKey, cursor-based in-memory op recording its inputs),
//   POST /books/search (same op type, body filter), GET /authors (PageQuery whole input), GET /letters (another cursor op))
@Test @Tag("smoke") void firstPageThenCursorPage() // acceptance 12: GET /books?size=2 → 200 page with nextCursor; GET /books?size=2&cursor=<it> → second page;
                                                    // the operation received the raw cursor it issued
@Test void pageQueryBindsOnPost()                 // acceptance 12: POST /books/search?size=2 {"author":"guin"} → page; then POST …?size=2&cursor=<token> same body → next page
@Test void sizeOverTheCapIs400()                  // acceptance 11: size=101 → 400 VALIDATION_FAILED, violations [{size,…}]; operation count 0
@Test void unknownSortKeyIs400()                  // acceptance 12: sort=price → 400, violation field "sort"
@Test void tamperedCursorIs400()                  // acceptance 12: one character of the token changed → 400 [{cursor, "is not valid for this request"}]; operation count 0
@Test void cursorFromAnotherRouteSortOrFilterIs400() // Review Focus 1: token from /books on /letters; token with sort=title used with sort=-title;
                                                      // token for author=guin used with author=herbert → each 400 cursor violation; operation count 0
@Test void rawCursorNeverReachesTheClient()       // Review Focus 3: operation's raw cursors ("RAW-…" prefix) never appear in any response header or body on GET, HEAD and POST
@Test void cursorFromAnotherInstanceIs400()       // two contexts with generated keys: a token of one is 400 on the other
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** the signatures above.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): wrap operation cursors and write PageResult as a page object`.

### Task 7: The `Link: rel="next"` header

**Files:**
- Create: `.../internal/paging/PageLinks.java`
- Modify: `ResponseWriter`
- Test: `.../internal/paging/PageLinksTest.java`, `RestPagingIT` (second part)

**Interfaces:**
- Produces:
  - `PageLinks`:
    - `static @Nullable String next(HttpServletRequest request, PagingSettings settings, PageQuery query, PageResult<?> result, @Nullable String token)`;
    - returns the header value `<target>; rel="next"` or `null` (*Defaults* #4/#15).
  - `ResponseWriter` adds it on GET and HEAD only. `Link` is already a reserved output header (R34), so nothing else writes it.

- [ ] **Step 1: Write the failing tests**

```java
// PageLinksTest (MockHttpServletRequest with requestURI "/api/books" and a raw query string)
@Test void cursorLinkKeepsOtherParametersInOrder()   // "author=le%20guin&size=2&cursor=old&sort=title" + token T → "</api/books?author=le%20guin&size=2&sort=title&cursor=T>; rel=\"next\""
@Test void offsetLinkAddsSizeToOffset()              // "offset=4&size=2", hasNext, no cursor → "</api/books?size=2&offset=6>; rel=\"next\""
@Test void firstOffsetPageLinksToTheNextOffset()     // "" (default size 20), hasNext → "...?offset=20"
@Test void offsetLinkStopsAtMaxOffset()              // Review Focus 4: offset=990&size=20 (max 1000) → null; offset=980&size=20 → "offset=1000"
@Test void noLinkOnTheLastPage()                     // hasNext false → null
@Test void noOffsetLinkForACursorRequest()           // request had cursor, result hasNext without nextCursor → null
@Test void usesConfiguredParameterNames()            // parameters.cursor=page_token, offset=skip → "page_token=T" / "skip=6"; old values dropped
// RestPagingIT
@Test void getAndHeadSendLinkAndPostDoesNot()        // GET and HEAD /books?size=2 → Link with cursor; POST /books/search → no Link
@Test void followingLinksReachesTheLastPage()        // follow Link from the first page until absent → every item once, in order; last page hasNext false
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Split the raw query on `&`. Decode each name only to compare it with the paging names, and keep the raw pair otherwise. The token is base64url, so it needs no encoding.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): add Link rel=next to paged GET responses`.

### Task 8: ETags on reads and 304

**Files:**
- Create: `.../internal/conditional/{EntityTags,ETagValues,package-info}.java`
- Modify: `ResponseWriter`, `SynapseRestAutoConfiguration` (`ETagValues` bean with `mvcConversionService`; the `responseWriter` bean takes it)
- Test: `.../internal/conditional/{EntityTagsTest,ETagValuesTest}.java`, `.../internal/route/ResponseWriterTest.java`, new `.../internal/conditional/RestConditionalIT.java` (first part)

**Interfaces:**
- Produces:
  - `EntityTags`:
    - `record Parsed(boolean wildcard, List<ETag> tags)`;
    - `static Parsed parse(List<String> fieldValues)`, which throws `IllegalArgumentException` for any grammar error (*Defaults* #22). Several header lines are one list.
  - `ETagValues`:
    - constructor `ETagValues(ConversionService conversionService)`;
    - `@Nullable ETag of(RouteDefinition route, Object output)`, which formats the component and validates `etagc` (*Defaults* #18);
    - `static String header(ETag tag)` returns `"\"" + tag.tag() + "\""`.
  - `ResponseWriter` (constructor gains `ETagValues`):
    - On GET/HEAD with status 200 and a tag: if `If-None-Match` parses and some tag (or `*`) matches by `compare(…, false)`, it returns 304 with `ETag`, `Cache-Control: no-cache`, `Vary: Accept` and nothing else.
    - Otherwise it adds `ETag`, `Cache-Control: no-cache` and `Vary: Accept` to the 200.
    - On POST with 201 it adds `ETag` only.
    - A malformed `If-None-Match` is ignored (*Defaults* #20).

- [ ] **Step 1: Write the failing tests**

```java
// EntityTagsTest
@Test void parsesStrongWeakListsAndWildcard()     // "\"5\"" → [5]; "W/\"5\"" → [W/5]; "\"5\", W/\"6\"" → [5, W/6]; "*" → wildcard
@Test void rejectsWhatSpringAccepts()             // "\"5\"x", "5", "\"a b\"", "\"\"\"", "*, \"5\"", "" , "\"5\",," → IllegalArgumentException
@Test void joinsSeveralHeaderLines()              // ["\"1\"", "\"2\""] → [1, 2]
@Test void acceptsEmptyQuotedTag()                // "\"\"" → [""] (RFC allows *etagc)
// ETagValuesTest
@Test void formatsSimpleComponents()              // long 7 → "7"; Instant → conversion-service text; null → null
@Test void rejectsCharactersOutsideEtagc()        // String "a b", "a\"b", "a\u0001" → IllegalStateException without the value in the message
// ResponseWriterTest
@Test void etagRouteAddsValidators()              // GET → ETag "\"7\"", Cache-Control "no-cache", Vary "Accept"; body still has "version":7
@Test void matchingIfNoneMatchIs304()             // If-None-Match "\"7\"" → 304, ETag, Cache-Control, Vary; no body, no Content-Type, no output headers
@Test void weakAndWildcardIfNoneMatchMatch()      // W/"7" → 304; * → 304; "\"8\"" → 200
@Test void malformedIfNoneMatchIsIgnored()        // "7" (unquoted) → 200 with ETag
@Test void postCreatedSendsETagOnly()             // 201 with ETag "\"1\""; no Cache-Control, no Vary
@Test void nullTagMeansNoValidators()             // version null → 200 without ETag, Cache-Control, Vary
// RestConditionalIT (RANDOM_PORT; fixture: versioned in-memory store with compare-and-set; GET /books/{id} .etag(Book::version), POST /books .created .etag,
//   PUT /books/{id} .header("If-Match", UpdateBookRequest::version), DELETE /books/{id} .header("If-Match", DeleteBookRequest::version).requireIfMatch(),
//   PUT /notes/{id} (no If-Match binding); operation invocation counters)
@Test @Tag("smoke") void ifNoneMatchYields304WithETag() // acceptance 12: GET → ETag "\"1\""; GET with If-None-Match "\"1\"" → 304 with ETag, Cache-Control no-cache, Vary Accept, empty body
@Test void headWithMatchingIfNoneMatchIs304WithValidators() // Review Focus 5: HEAD + matching → 304, same three headers, no body, no Content-Length;
                                                            // HEAD + non-matching → 200 with ETag and the GET's Content-Length, no body
@Test void notFoundBeatsIfNoneMatch()             // GET missing id with If-None-Match * → 404 BOOK_NOT_FOUND
@Test void postReturnsETag()                      // 201 + Location + ETag "\"1\""
@Test void putAndDeleteNeverSendETag()            // 200 PUT and 204 DELETE responses have no ETag header
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** the signatures above. The 304 is built with `ServerResponse.status(304).headers(...)`. The existing writer already sends no body for 304 and HEAD.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): emit ETags and answer If-None-Match with 304`.

### Task 9: Binding `If-Match` and `If-None-Match` as entity tags

**Files:**
- Modify: `.../internal/binding/RequestBinder.java`
- Test: `.../internal/binding/RequestBinderTest.java`

**Interfaces:**
- Consumes: `EntityTags.parse` (Task 8), `ConditionalRules` (Task 3).
- Produces: for an `InputSource.Header` whose name is `If-Match` or `If-None-Match` (case-insensitive), the binder:
  - binds `*` as `null`;
  - converts one tag's opaque value (strong, or weak) to the component type;
  - adds the violations of *Defaults* #23 for a list or a malformed value.

  The binder doesn't judge weakness; Task 10's preconditions do, after binding (*Defaults* #25). An absent header leaves the component `null`: it is `@Nullable` by the Task 3 check.

- [ ] **Step 1: Write the failing tests**

```java
// RequestBinderTest (UpdateBookRequest(UUID id, @Nullable Long version, String title) with .header("If-Match", UpdateBookRequest::version))
@Test void strongTagBindsUnquotedAndConverted()   // acceptance 12: If-Match "\"5\"" → version 5
@Test void wildcardBindsNull()                    // If-Match * → version null
@Test void absentHeaderBindsNull()
@Test void weakTagBindsItsValue()                 // If-Match W/"5" → version 5 (the precondition step answers 412; Task 10)
@Test void listIsAViolation()                     // "\"5\", \"6\"" and two If-Match lines → {version, "must be a single entity tag"}
@Test void unquotedOrMalformedIsAViolation()      // "5", "\"5", "*, \"5\"" → {version, "must be an entity tag"}
@Test void unconvertibleTagIsAViolation()         // "\"abc\"" → {version, "is not a valid value"}
@Test void ifNoneMatchBindsTheSameWay()           // .header("If-None-Match", R::etag) "\"x\"" → "x"; * → null; W/"x" → "x" (Defaults #24)
@Test void otherHeadersAreNotParsedAsTags()       // .header("X-Version", …) "\"5\"" for a String component → "\"5\"" verbatim
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement** in the binder's `value(InputSource)` switch.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): parse If-Match and If-None-Match as entity tags`.

### Task 10: Preconditions on writes: 412, 428 and `STALE_VERSION`

**Files:**
- Create: `.../internal/conditional/Preconditions.java`
- Modify: `.../internal/route/OperationHandler.java`
- Test: `.../internal/conditional/PreconditionsTest.java`, `.../internal/route/OperationHandlerTest.java`, `RestConditionalIT` (second part)

**Interfaces:**
- Produces:
  - `Preconditions`:
    - `static void check(RouteDefinition route, ServerRequest request)`. On POST, PUT and DELETE it throws, in order:
      - `SynapseException(PRECONDITION_FAILED)` for an unbound `If-Match` or `If-None-Match` that is present;
      - the same for a bound `If-Match` whose single tag is weak;
      - `SynapseException.ofViolations(RestErrors.PRECONDITION_REQUIRED, [{If-Match, "is required; send the ETag from a GET of this resource"}])` when `.requireIfMatch()` is set and `If-Match` is absent.

      On GET/HEAD it does nothing (*Defaults* #25).
    - `static SynapseException translate(RouteDefinition route, ServerRequest request, SynapseException failure)` (*Defaults* #26).
  - `OperationHandler`: Accept → bind → `unwrap` → `Preconditions.check` → execute in `try { … } catch (SynapseException e) { throw Preconditions.translate(route, request, e); }` → write.

- [ ] **Step 1: Write the failing tests**

```java
// PreconditionsTest
@Test void unboundIfMatchOrIfNoneMatchOnUnsafeIs412()    // PUT /notes/1 with If-Match "\"1\"" or If-None-Match * → PRECONDITION_FAILED
@Test void unboundIfMatchOnGetIsIgnored()
@Test void weakBoundIfMatchIs412()                        // W/"5" → PRECONDITION_FAILED
@Test void requireIfMatchWithoutHeaderIs428()             // DELETE without If-Match → code PRECONDITION_REQUIRED, violation {If-Match, …}; If-Match * → passes
@Test void staleVersionWithIfMatchBecomesPreconditionFailed() // translate(STALE_VERSION) with If-Match "\"5\"" (or *) → PRECONDITION_FAILED with the cause; without If-Match → same instance
@Test void otherConflictsAreNotTranslated()               // DUPLICATE_ISBN (CONFLICT) with If-Match → same instance
// OperationHandlerTest
@Test void preconditionsRunAfterBindingAndBeforeExecute() // bad body + unbound If-Match → 400 (not 412); valid body + weak If-Match → 412 and operation count 0
// RestConditionalIT
@Test void versionComesFromIfMatch()                      // acceptance 12: PUT If-Match "\"1\"" → 200 body version 2; the operation saw version 1
@Test void staleIfMatchIs412AndNothingChanges()           // Review Focus 2, acceptance 12: PUT If-Match "\"0\"" → 412 PRECONDITION_FAILED; GET → still version 1, old title
@Test void raceLostAfterTheReadIs412WithIfMatchAnd409Without() // Review Focus 2: a store hook bumps the version between the operation's read and its compare-and-set →
                                                          // with If-Match "\"1\"": 412 PRECONDITION_FAILED; without If-Match: 409 STALE_VERSION; the concurrent write's title survives in both
@Test void weakIfMatchIs412WithoutCallingTheOperation()   // W/"1" → 412; operation count 0
@Test void ifMatchListOrUnquotedIs400()                   // "\"1\", \"2\"" and 1 → 400 VALIDATION_FAILED, violation field "version"
@Test void wildcardOnMissingResourceIs404()               // PUT /books/missing If-Match * → 404 BOOK_NOT_FOUND
@Test void requireIfMatchIs428()                          // DELETE without If-Match → 428 PRECONDITION_REQUIRED, violations [{If-Match, …}]; with "\"1\"" → 204
@Test void unboundPreconditionIs412()                     // PUT /notes/1 with If-Match → 412; operation count 0
@Test void staleVersionStatusCanBeOverridden()            // synapse.api.rest.errors.status.[STALE_VERSION]=422 → race without If-Match is 422
@Test void ifUnmodifiedSinceIsIgnored()                   // PUT with If-Unmodified-Since in the past → 200
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
  - The race fixture is a test `BookStore` whose `compareAndSet` can be armed to apply a competing write first.
  - The fixture operation does what *Defaults* #26 assumes: a fast-path `STALE_VERSION` when `request.version()` differs from the read version, then compare-and-set with the read version, throwing `STALE_VERSION` on failure.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl api/synapse-api-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): enforce write preconditions with 412, 428 and STALE_VERSION`.

### Task 11: AOT hints and the test slice

**Files:**
- Modify: `.../internal/aot/SynapseRestRuntimeHints.java`
- Test:
  - `.../internal/aot/{SynapseRestRuntimeHintsTest,RestAotProcessingTest}.java`;
  - `api/synapse-test-rest/src/test/java/io/americanexpress/synapse/test/rest/slice/{SynapseRestTestIT,BooksRestConfiguration,SearchBooksOperation,BookSearch}.java` (extend the existing paged fixture).

**Interfaces:**
- Produces:
  - `SynapseRestRuntimeHints` registers binding reflection for `PageBody` (Jackson serializes it reflectively; the item types come from core's `OperationTypesAotProcessor`).
  - No hint is registered for sort-key enums (unknown at build time; see *Covered by later plans*).
  - `RestBindingsAotProcessor` is unchanged: `.etag`/`.sortable` method references are `ComponentRef` lambdas in the same configuration class.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseRestRuntimeHintsTest
@Test void registersPageBodyBinding()              // RuntimeHintsPredicates.reflection().onType(PageBody.class)
// RestAotProcessingTest
@Test void generatedContextServesPagedAndConditionalRoutes() // AOT-generated context (as in 2a): GET paged route → page object with nextCursor;
                                                             // GET .etag route with If-None-Match → 304
// SynapseRestTestIT (synapse-test-rest)
@Test void pagedRouteWorksInTheSlice()             // @MockitoBean SearchBooksOperation returns PageResult.ofCursor([book], "raw") → 200, $.nextCursor present and != "raw", Link header present
@Test void conditionalRouteWorksInTheSlice()       // GET with If-None-Match → 304
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -pl api/synapse-api-rest,api/synapse-test-rest -am verify`.
- [ ] **Step 5: Commit**: `feat(rest): add AOT hints and slice coverage for paging and ETags`.

### Task 12: Extend `samples/rest-books`

**Files:**
- Modify (under `samples/rest-books/src/main/java/io/americanexpress/synapse/samples/books/`):
  - `model/{Book,SearchBooksRequest,UpdateBookRequest,DeleteBookRequest}.java`;
  - `data/{StoredBook,InMemoryBookStore,WriteOutcome}.java`;
  - `business/{ReviseBook,RemoveBook,AddBook}.java`, `business/model/{BookRevision,CatalogBook}.java`;
  - `operation/{SearchBooksOperation,UpdateBookOperation,DeleteBookOperation,BookMapping}.java`, `business/CatalogBookMapping.java`;
  - `api/rest/BooksRestConfiguration.java`;
  - `src/main/resources/application.yaml`;
  - `README.md`.
- Test (under `samples/rest-books/src/test/java/…/books/`): `BooksApplicationIT.java`, `api/rest/BooksRestTest.java`, `data/InMemoryBookStoreTest.java`, `operation/BookOperationsTest.java`, `business/ReviseBookTest.java`

**What it shows:**
- **Versions.**
  - `StoredBook`, `CatalogBook` and `Book` gain `long version`, starting at 1 and incremented by every write.
  - `InMemoryBookStore.replace(StoredBook, long expectedVersion)` and `remove(UUID, @Nullable Long expectedVersion)` compare and set. `WriteOutcome` gains `STALE`.
  - `ReviseBook`/`RemoveBook` throw `SynapseErrors.STALE_VERSION` on a fast-path mismatch or on `STALE`. The expected version is the request's version, or the version just read when the request has none.
- **Paged, sortable search.**
  - `SearchBooksRequest(@Nullable String author, PageQuery page)` with `enum SortKey { TITLE, YEAR }`, sortable as `title` and `year`.
  - `SearchBooksOperation` returns `PageResult<Book>`, with a cursor that is the next index as a string (it is opaque to the client), and supports offsets. The default sort is by title.
- **Routes in `BooksRestConfiguration`:**
  - `GET /books` gets `.sortable(SearchBooksRequest.SortKey.class)`;
  - `GET /books/{id}` gets `.etag(Book::version)`;
  - `POST /books` gets `.etag(Book::version)`;
  - `PUT /books/{id}` gets `.header("If-Match", UpdateBookRequest::version)` (optional);
  - `DELETE /books/{id}` gets `.header("If-Match", DeleteBookRequest::version).requireIfMatch()`.

  Comments explain each line, as the existing ones do.
- **`application.yaml`:** a commented `synapse.api.rest.paging` block (`max-offset`, `cursor.key: ${BOOKS_CURSOR_KEY}` with the `openssl rand -base64 32` hint).

- [ ] **Step 1: Write the failing tests**

```java
// InMemoryBookStoreTest
@Test void replaceIsACompareAndSet()              // expected 1 → WRITTEN, version 2; expected 1 again → STALE, book unchanged
@Test void removeChecksTheVersionWhenGiven()      // remove(id, 5) → STALE; remove(id, null) → removed
// BooksApplicationIT (real server, no profile)
@Test void searchPagesWithCursorAndLink()         // GET /books?sort=title&size=2 → 2 items, nextCursor, Link; following Link → last item, hasNext false, no Link
@Test void searchRejectsUnknownSortAndTamperedCursor() // sort=price → 400 sort; altered cursor → 400 cursor
@Test void searchByOffset()                       // offset=2&size=2 → third book; offset=1001 → 400 offset
@Test void getSendsETagAnd304()                   // GET → ETag "\"1\""; If-None-Match "\"1\"" → 304; HEAD with it → 304
@Test void putWithIfMatchUpdatesAndStaleIs412()   // PUT If-Match "\"1\"" → 200 version 2; again with "\"1\"" → 412 PRECONDITION_FAILED
@Test void concurrentUpdateWithoutIfMatchIs409()  // @MockitoSpyBean InMemoryBookStore: find returns a copy with version 0 → PUT without If-Match → 409 STALE_VERSION
@Test void deleteRequiresIfMatch()                // DELETE without If-Match → 428 PRECONDITION_REQUIRED; If-Match "\"1\"" → 204; If-Match * on a missing book → 404
@Test void postSendsETag()                        // 201 + Location + ETag "\"1\""
// BooksRestTest (@SynapseRestTest): searchReturnsAPageObject, getAnswers304
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -pl samples/rest-books -am verify`.
- [ ] **Step 3: Implement** the sample changes. Keep the layering (`ArchitectureTest` must still pass). `STALE_VERSION` comes from core, so `contract` needs no new code.
- [ ] **Step 4: Run** `./mvnw -pl samples/rest-books -am verify` (all sample tests, the smoke IT included).
- [ ] **Step 5: Update `README.md` and verify every example by running the app.**
  1. Build with `./mvnw -pl samples/rest-books -am package -DskipTests` (only to produce the jar for the manual run; Step 4 is the passing build).
  2. Start `java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar --spring.profiles.active=dev`.
  3. In Git Bash, against a fresh start, run **every** `curl` block of the README in document order, the existing ones included. Paste the real output; trim only volatile values (ids, correlation ids, dates, tokens), and say so where they appear.
  4. New sections:
     - *Pages and sorting*: `curl -s -i "http://localhost:8080/books?sort=title&size=2"`; following the token with `NEXT=$(curl -s "…" | sed -E 's/.*"nextCursor":"([^"]+)".*/\1/')`; an offset page; `sort=price` → 400; a changed cursor → 400.
     - *Conditional requests*:
       - `GET` showing `ETag`, `Cache-Control` and `Vary`;
       - `If-None-Match` → 304 on GET and with `curl -I`;
       - `PUT` with `If-Match: "1"` → 200 version 2;
       - the same again → 412;
       - `If-Match: W/"2"` → 412;
       - `DELETE` without `If-Match` → 428, then with `If-Match: "2"` → 204;
       - the 409 race explained in text, with a pointer to `BooksApplicationIT.concurrentUpdateWithoutIfMatchIs409` (it can't be produced reliably with curl).
  5. Update the examples that change:
     - search now returns a page object;
     - *Delete a book* and the *Errors* example "Deleting the same book again" now send `If-Match` (`*` on the second call, which shows the 404);
     - the *Endpoints* table gets the new inputs and answers.
  6. Restart without a profile and run the *Production mode* blocks. Paste the cursor-key WARN line from the startup log, and show starting with `--synapse.api.rest.paging.cursor.key=$(openssl rand -base64 32)` silencing it.
  7. Replace *Not here yet* with: API versioning, CORS and OpenAPI (plan 2c).

  An example whose output differs from the README is fixed in the README. If it reveals a library or sample bug, stop and report it; don't paper over it.
- [ ] **Step 6: Commit**: `feat(samples): show paging and conditional requests in rest-books`.

### Task 13: Docs, spec status and the full build

**Files:**
- Modify:
  - `docs/modules/ROOT/pages/rest.adoc`;
  - `docs/modules/ROOT/pages/core.adoc` (`STALE_VERSION` in the codes table; the *Paging* section names the REST edge checks);
  - `specs/v2/BUILD.md` §15 P1 (a "Done (plan 2b)" note).

- [ ] **Step 1: Write the docs.** `rest.adoc`:
  - Change the L40-42 NOTE so that only API versioning, CORS and OpenAPI are "not available yet (plan 2c)", and drop the sentence about non-nullable `PageQuery` components.
  - A new *Paging* section:
    - parameters and their properties;
    - the defaults and caps, with the violation table of *Defaults* #3;
    - `.sortable` with enum and names, the wire-name rule and `SortOrder.property`;
    - `PageQuery` as a component or the whole input; POST paging via the query string;
    - the page object and `Link`, with an offset example and the max-offset cutoff;
    - cursors: what is bound, including that context components bind a cursor to the caller, and that a per-request value breaks cursors;
    - key setup and rotation with `previous-keys`, `max-age`, the per-start key and its WARN;
    - the 2^32-tokens-per-key note (NIST SP 800-38D, rotate well before);
    - replacing `CursorCodec`;
    - the escape-hatch warning of *Defaults* #17.
  - A new *Conditional requests* section:
    - `.etag`: types, methods, the headers on 200 and 304, HEAD;
    - `If-Match` binding: the table of cases from DESIGN 4.1, including weak, list, unquoted, `*`, missing resource;
    - the 412/409 rule with an operation example (fast path plus compare-and-set; JPA's `@Version` → `STALE_VERSION` translator arrives in plan 4, and until then a JPA operation should throw `STALE_VERSION` itself);
    - `If-None-Match` on writes (*Defaults* #24);
    - unbound preconditions → 412;
    - `.requireIfMatch()` and 428;
    - `If-Unmodified-Since` ignored;
    - the warning that an operation which ignores the bound version turns `If-Match` into a no-op.
  - *Response mapping*: the `PageResult` row.
  - *Errors*: `PRECONDITION_REQUIRED` 428 in the `RestErrors` table; `STALE_VERSION` in the status explanation.
  - *Configuration*: mention the paging keys.
- [ ] **Step 2: Build the docs** (`cd docs && npx antora antora-playbook.yml`, as CI runs it; see the 2a ledger, Task 14). Check that the property table lists every `synapse.api.rest.paging.*` key with a description.
- [ ] **Step 3: Update BUILD.md §15 P1** with "Done (plan 2b, REST paging and conditional requests)":
  - covered: acceptance 11 (REST: page size over the cap), 12 (`PageQuery` on GET and POST, unknown sort key, tampered and replayed cursors, `version` from `If-Match`, 304 also on HEAD, 412, 409, weak `If-Match`, 428), 10 (REST: new beans' context-runner and AOT hint tests);
  - plan 2c covers 7 (OpenAPI), versioning and CORS.
- [ ] **Step 4: Run the full build on JDK 25:** `./mvnw clean verify`. Expected: BUILD SUCCESS (all modules, the sample, invoker tests, coverage gates). Run it in the background; it takes several minutes.
- [ ] **Step 5: Run the JDK 21 smoke leg:** `./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke`. Expected: BUILD SUCCESS. Report the test counts per module for both runs.
- [ ] **Step 6: Commit**: `docs: document REST paging and conditional requests`.

---

## Covered by later plans

| Spec item | Plan |
|---|---|
| API versioning: `.version("2")`, path segment with a predicate, Boot `spring.mvc.apiversion.*`, error codes | 2c |
| CORS: `synapse.api.rest.cors.*`, `CorsConfigurationSource`, problem+json rejections, CORS for the 405/OPTIONS fallback mapping (parked 2a item), `Link`/`ETag`/correlation header exposed by default | 2c |
| OpenAPI 3.1 in `synapse-api-rest-openapi`: springdoc 3.x (excluding `spring-boot-starter-logging`), a Synapse customizer from the resolved routes, Synapse's own schema generator on Jackson 3, Swagger UI off and API docs on at lowest precedence. It must describe paging parameters, `Link`, `ETag`/`If-Match`, 304/412/428 and `.requireIfMatch()` as a required header (acceptance 7) | 2c |
| gRPC page tokens (AIP-158) and whether `CursorCodec` moves to core for them; `STALE_VERSION` → ABORTED | 3 |
| The `@Version` → `STALE_VERSION` data translator (BUILD §8.2 as amended) and acceptance 8's `@Version` conflict | 4 |
| Native verification: JCA AES/GCM in a native image, the reflective `@JsonProperty` lookup on sort-key enums (no hint is registered), the lambda hints of `.etag`/`.sortable` references (R40) | 5 (native nightly) |
| ETag representation suffix (XML codec), PATCH preconditions | P4 |
| A declared pre-read `.precondition(...)`, `Last-Modified`/`If-Modified-Since`, `prev`/`first` links, an opt-in total | Later, on request (DESIGN 4.1 rows of 2026-11-04) |
