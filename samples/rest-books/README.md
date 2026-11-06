# Sample: a book catalog over REST

A small Synapse 2 service you can run and call: a catalog of books served over REST. It uses only what Synapse has
today (`synapse-core`, `synapse-api-rest`, `synapse-logging-log4j2` and the test modules). The data lives in memory,
starts with three books, and is lost when the service stops.

## What it shows

Code paths are under `src/main/java/io/americanexpress/synapse/samples/books/`.

| Feature | Where |
|---|---|
| Operations: `QueryOperation` for reads, `CommandOperation` for writes, named after their class (`GetBookOperation` is `get-book`) | `operation/` |
| Business rules in `BusinessOperation` beans that operations call (`AddBook`, `ReviseBook`, `RemoveBook`): one book per ISBN, and no lost updates | `business/` |
| Error codes callers can match (`BOOK_NOT_FOUND`, `DUPLICATE_ISBN`) in an `ErrorDetails` enum; `STALE_VERSION` comes from core's `SynapseErrors` | `contract/BookErrors.java` |
| Records with Jakarta Validation and JSpecify nullness; the pipeline validates each request before the operation runs | `model/` |
| One `RestBindings` bean for the whole API: path variables, query parameters, JSON body, a value from the request context, `201` + `Location`, `204` on `DELETE`, `.notFound(...)`, an output header, a per-route body limit, paging, ETags and `If-Match` | `api/rest/BooksRestConfiguration.java` |
| Paging: a `PageQuery` in the input, a `.sortable(...)` allow-list, a `PageResult` output written as a page object with `Link: rel="next"`, and cursors the edge encrypts | `model/SearchBooksRequest.java`, `operation/SearchBooksOperation.java`, [Pages and sorting](#pages-and-sorting) |
| Versions and conditional requests: `.etag(...)` (`ETag`, 304), `If-Match` bound to a `version` component (412), `.requireIfMatch()` (428), and `STALE_VERSION` from a compare-and-set in the store (409 without `If-Match`) | `data/InMemoryBookStore.java`, `business/ReviseBook.java`, [Conditional requests](#conditional-requests) |
| A stand-in data store (thread-safe, in memory) with its own model | `data/` |
| Problem responses (RFC 9457 `application/problem+json`) with `code`, `correlationId` and `violations`; `detail` only when `include-detail` is on (local) | [Errors](#errors) |
| The correlation id: read from `X-Correlation-Id` or generated, echoed on every response, written to the logs of the request | [Correlation id](#correlation-id) |
| ECS JSON logs; a failing operation logs one line, a successful one logs nothing | [Logs](#what-to-look-for-in-the-logs) |
| Tests: the REST slice (`@SynapseRestTest`), the operation slice (`@SynapseOperationTest`), `SynapseOperations.invoke`, an end-to-end test on a real server, and the layering rules | `src/test/java` |

The packages follow Synapse's layering (specs/v2/BUILD.md section 4.3): `api.rest` calls `operation`; operations call
`business` (only through `BusinessOperation.execute`) and `data`; business code calls `data`; nobody calls `api`.
`ArchitectureTest` checks it with `SynapseArchitecture.forApplication(BooksApplication.class)`.

Two choices worth knowing when you read the code:

* The reads (`GetBookOperation`, `SearchBooksOperation`, `GetCatalogStatsOperation`) have no business rule, so they
  read the store directly, which the layering allows. The writes go through business code, where the ISBN and version
  rules live.
* `BookErrors` is in `contract`, not `business`: the REST binding uses it (`.notFound(BookErrors.BOOK_NOT_FOUND)`), and
  the `api` layer may not depend on `business`. Every layer may use `contract`. In a multi-module service this enum
  would be in a `contract-books` module.

Every layer owns its model, and the caller maps: `model.Book` (what the API returns), `business.model.NewBook`,
`BookRevision` and `BookRemoval` (what the business operations take), `business.model.CatalogBook` (what they return),
and `data.StoredBook` (what the store keeps). The business operations map `StoredBook` to `CatalogBook`; the
operations map `CatalogBook` to `Book`. The reads, which call the store directly, map `StoredBook` to `Book`
themselves (the operation to data edge is allowed).

Every book has a `version`: 1 when it is added, plus 1 on every change. The store writes only if the book is still at
the version the writer read (a compare-and-set, as `UPDATE ... WHERE version = ?` would do in a database), so two
concurrent updates can't both win. Over REST the version is the book's `ETag`, and a client sends it back in
`If-Match`.

## Run it

You need JDK 25 to build (Synapse's build requires it; the service itself runs on Java 21 or later): point
`JAVA_HOME` at a JDK 25 (`export JAVA_HOME=<path to a JDK 25>` and put `$JAVA_HOME/bin` first on `PATH`), so that
`java -version` reports 25. Then, from the repository root, in Git Bash on Windows or any POSIX shell:

```bash
./mvnw -pl samples/rest-books -am package -DskipTests
ENV=local java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar
```

The first command builds the sample and the Synapse modules it uses, without running the tests (`./mvnw verify` runs
them). The second runs the `local` environment: `application.yaml` maps `spring.profiles.active: ${ENV}`, and
`application-local.yaml` turns on `detail` in problem responses (`synapse.errors.include-detail: true`).
`spring-boot:run` runs this module as `local` too: the pom sets `spring-boot.run.profiles` to `local`, and
`-Dspring-boot.run.profiles=<env>` picks another. It runs the sample alone, so it takes the Synapse modules from your
local Maven repository: install them first (again after you change them):

```bash
./mvnw -pl samples/rest-books -am install -DskipTests
./mvnw -pl samples/rest-books spring-boot:run
```

`-DskipTests` only saves time on the install step; it is not a test run.

The service listens on port 8080; stop it with Ctrl+C. At startup it logs which environment it runs and where its
configuration comes from (see [What to look for in the logs](#what-to-look-for-in-the-logs)), and the REST plugin logs
one line per route, for example:

```text
{"@timestamp":"2026-11-04T20:51:44.689895800Z","log":{"level":"INFO","logger":"io.americanexpress.synapse.api.rest"},"process":{"pid":5868,"thread":{"name":"main"}},"service":{"name":"rest-books","node":{}},"message":"Synapse REST exposes GET /books/{id} -> get-book on port 8080","ecs":{"version":"8.11"}}
```

It also logs a WARN that `synapse.api.rest.paging.cursor.key` is not set (see [Safe defaults](#safe-defaults)).

The service needs an environment. Started without `ENV`, it stops before anything else runs:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

No environment is set: spring.profiles.active is '${ENV}', and ENV has no value.

Action:

Set ENV to the name of the environment (the service has an application-<env>.yaml for each). Run locally with -Dspring-boot.run.profiles=local or an IDE run configuration; Synapse's test slices activate test; other Spring Boot tests declare @ActiveProfiles("test").
```

An environment without its own file (here `ENV=e9`; the sample ships only `application-local.yaml`) stops it too:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

The environment 'e9' has no file in the service: none of application-e9.yaml, application-e9.yml, application-e9.properties was loaded or found on the classpath. Environment names are case-sensitive.

Action:

Add application-e9.yaml to the service (it may be empty), or set the environment to the name of an existing file.
```

## Endpoints

| Method | Path | Operation | Input | Answers |
|---|---|---|---|---|
| `GET`, `HEAD` | `/books/{id}` | `get-book` | `id` from the path | 200 with `ETag`, `Cache-Control: no-cache` and `Vary: Accept`; 304 when `If-None-Match` matches; 404 `BOOK_NOT_FOUND` |
| `GET` | `/books` | `search-books` | `author` from the query string (optional; part of the name, any case); `size` (default 20, at most 100); `cursor` or `offset` (at most 1000); `sort` (`title`, `year`, `-` for descending; by title when absent) | 200 with a page `{items, nextCursor, hasNext, total}` and `Link: rel="next"` when there is a next page; 400 |
| `GET`, `HEAD` | `/books/stats` | `get-catalog-stats` | none | 200 with `X-Books-Total` |
| `POST` | `/books` | `create-book` | JSON body; `channel` from the `X-Channel` header | 201 + `Location` + `ETag`; 400; 409 `DUPLICATE_ISBN`; 413 over 16 KB |
| `PUT` | `/books/{id}` | `update-book` | `id` from the path, `version` from `If-Match` (optional), the rest from the JSON body | 200 with the new version (no `ETag`); 400; 404 `BOOK_NOT_FOUND`; 409 `DUPLICATE_ISBN`, or `STALE_VERSION` without `If-Match`; 412 `PRECONDITION_FAILED` |
| `DELETE` | `/books/{id}` | `delete-book` | `id` from the path, `version` from `If-Match` (required; `*` for any version) | 204; 404 `BOOK_NOT_FOUND`; 412 `PRECONDITION_FAILED`; 428 `PRECONDITION_REQUIRED` |
| `GET` | `/actuator/health` | Spring Boot Actuator | | 200 |

Any other method on these paths is 405 with `Allow`, and `OPTIONS` lists the methods. The seeded books, all at
version 1:

| id | Title | Author |
|---|---|---|
| `00000000-0000-0000-0000-000000000001` | Dune | Frank Herbert |
| `00000000-0000-0000-0000-000000000002` | The Left Hand of Darkness | Ursula K. Le Guin |
| `00000000-0000-0000-0000-000000000003` | A Wizard of Earthsea | Ursula K. Le Guin |

## Try it

Run these in a second terminal, in order, against a freshly started service (some change the catalog). The responses
below are real. Values the service generates differ on each run: the new book's id, correlation ids, dates and paging
tokens (`nextCursor` and the `cursor` in `Link`, which are also new on every response).

### Health

```bash
curl -s http://localhost:8080/actuator/health
```

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

### Read a book

The path variable `{id}` fills `GetBookRequest.id`.

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```json
{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}
```

### Search

On `GET`, simple components of the input record come from the query string of the same name. The output is a
`PageResult`, so the answer is a page object: `items`, `hasNext`, `total`, and `nextCursor` when there is a next page.

```bash
curl -s "http://localhost:8080/books?author=guin"
```

```json
{"items":[{"id":"00000000-0000-0000-0000-000000000003","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","year":1968,"addedVia":null,"version":1},{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969,"addedVia":null,"version":1}],"hasNext":false,"total":2}
```

### Pages and sorting

`SearchBooksRequest.page` is a `PageQuery`, which the plugin fills from the `size`, `cursor`, `offset` and `sort`
query parameters. `.sortable(SearchBooksRequest.SortKey.class)` allows `sort=title` and `sort=year` (`-year` for
descending, `year,title` for both). The operation pages through the books and returns its own cursor, the index of the
next book as a string; the plugin encrypts it (AES-256-GCM) before the client sees it, and adds a relative
`Link: rel="next"` header on `GET` and `HEAD`.

```bash
curl -s -i "http://localhost:8080/books?sort=title&size=2"
```

```text
HTTP/1.1 200 
X-Correlation-Id: a284078f-bcaf-406f-b057-c0537d8dbb86
Link: </books?sort=title&size=2&cursor=AdBO4qUFIR4mf5sIkyAQ6cjvMVBFmsxdG2AEp1DoT-ujWMnZ7DI-vjOx>; rel="next"
Content-Type: application/json
Content-Length: 428
Date: Wed, 04 Nov 2026 20:52:03 GMT

{"items":[{"id":"00000000-0000-0000-0000-000000000003","isbn":"978-0547773742","title":"A Wizard of Earthsea","author":"Ursula K. Le Guin","year":1968,"addedVia":null,"version":1},{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}],"nextCursor":"AdBO4qUFIR4mf5sIkyAQ6cjvMVBFmsxdG2AEp1DoT-ujWMnZ7DI-vjOx","hasNext":true,"total":3}
```

Follow the token, either from `nextCursor` or from the `Link` target. The next page is the last one: no `nextCursor`,
`hasNext` is false, and no `Link`.

```bash
NEXT=$(curl -s "http://localhost:8080/books?sort=title&size=2" | sed -E 's/.*"nextCursor":"([^"]+)".*/\1/')
curl -s "http://localhost:8080/books?sort=title&size=2&cursor=$NEXT"
```

```json
{"items":[{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969,"addedVia":null,"version":1}],"hasNext":false,"total":3}
```

A page can also start at an offset, up to `synapse.api.rest.paging.max-offset` (1000); beyond that, callers page by
cursor.

```bash
curl -s "http://localhost:8080/books?offset=2&size=2"
```

```json
{"items":[{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969,"addedVia":null,"version":1}],"hasNext":false,"total":3}
```

The plugin checks the paging parameters before the operation runs. A sort key that isn't allowed:

```bash
curl -s "http://localhost:8080/books?sort=price"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"bfef29cb-65b9-4e96-837c-3b2d98cd50c0","violations":[{"field":"sort","message":"has a key that is not allowed (allowed: title, year)"}]}
```

A changed token (here its first character):

```bash
curl -s "http://localhost:8080/books?sort=title&size=2&cursor=$(echo "$NEXT" | sed 's/^A/B/')"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"e9a540fd-8b85-42b7-a2cd-8142f19a3b32","violations":[{"field":"cursor","message":"is not valid for this request"}]}
```

A token is bound to the operation, the sort and the other inputs (such as `author`), so the same token with another
sort is rejected the same way. Every reason gets the same message.

```bash
curl -s "http://localhost:8080/books?sort=-year&size=2&cursor=$NEXT"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"fd999c06-8f54-4536-b408-a56c928ce480","violations":[{"field":"cursor","message":"is not valid for this request"}]}
```

An offset over the cap:

```bash
curl -s "http://localhost:8080/books?offset=1001"
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"af9880ba-f68b-437e-975b-9a1dd50d6f89","violations":[{"field":"offset","message":"must be less than or equal to 1000"}]}
```

### An output header

`.header("X-Books-Total", CatalogStats::books)` writes that component to a response header. `HEAD` answers the
headers of the `GET` without the body:

```bash
curl -s -I http://localhost:8080/books/stats
```

```text
HTTP/1.1 200 
X-Correlation-Id: 6edef225-c080-4616-b6da-bb2fdea3a3dc
X-Books-Total: 3
Content-Type: application/json
Content-Length: 23
Date: Wed, 04 Nov 2026 20:52:03 GMT
```

### Add a book

The JSON body fills the request record. `CreateBookRequest.channel` comes from the request context instead, which
`application.yaml` fills from the `X-Channel` header (`synapse.api.rest.context.headers.channel`). The answer is 201
with the new book's `Location`, and its `ETag` (`.etag(Book::version)`): the new book is at version 1.

```bash
curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'X-Channel: mobile' \
  -d '{"isbn":"978-0261103573","title":"The Fellowship of the Ring","author":"J. R. R. Tolkien","year":1954}'
```

```text
HTTP/1.1 201 
X-Correlation-Id: 3e3e9a6a-7d01-4c78-8c10-119a2027fa70
Location: /books/007b0e77-67b4-4b5d-bf51-6417b426e652
ETag: "1"
Content-Type: application/json
Content-Length: 178
Date: Wed, 04 Nov 2026 20:52:04 GMT

{"id":"007b0e77-67b4-4b5d-bf51-6417b426e652","isbn":"978-0261103573","title":"The Fellowship of the Ring","author":"J. R. R. Tolkien","year":1954,"addedVia":"mobile","version":1}
```

### Update a book

The `id` comes from the path and the rest from the body. A body `"id"` that differs from the path is a 400. Without
`If-Match`, the update applies to the version the business operation reads, and the version goes up by 1 (see
[Conditional requests](#conditional-requests) for `If-Match`).

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000002 \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019}'
```

```json
{"id":"00000000-0000-0000-0000-000000000002","isbn":"978-0441478125","title":"The Left Hand of Darkness (50th anniversary edition)","author":"Ursula K. Le Guin","year":2019,"addedVia":null,"version":2}
```

### Delete a book

`DELETE` requires `If-Match` with the version to delete (`.requireIfMatch()`), and answers 204 with no body.

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000003 -H 'If-Match: "1"'
```

```text
HTTP/1.1 204 
X-Correlation-Id: 6384557f-5b86-444b-9b9e-5dbdbdf3eadd
Date: Wed, 04 Nov 2026 20:52:04 GMT
```

### Errors

Every error is `application/problem+json` with the `code` callers match and the `correlationId`.

Deleting the same book again, with `If-Match: *` (any version): the business operation throws
`SynapseException(BookErrors.BOOK_NOT_FOUND, id)`, and the message template `Book {0} was not found` is filled in.

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000003 -H 'If-Match: *'
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000003 was not found","code":"BOOK_NOT_FOUND","correlationId":"1690dca6-94c2-4d5e-8ffd-28c6d623b84a"}
```

Reading a book that doesn't exist: the operation returns an empty `Optional`, and `.notFound(BookErrors.BOOK_NOT_FOUND)`
on the route answers it. The route's path values (here `{id}`) fill the template's placeholders, in template order.

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"detail":"Book 00000000-0000-0000-0000-000000000099 was not found","code":"BOOK_NOT_FOUND","correlationId":"328381c3-7151-4137-b14c-b6c431bd1f05"}
```

A validation error: the pipeline runs Jakarta Validation on the request record before the operation and reports
every violation.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"","title":" ","author":"Nobody","year":-1}'
```

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"3c2126b0-e770-4e90-b1d0-5a41f30692e9","violations":[{"field":"isbn","message":"must not be blank"},{"field":"title","message":"must not be blank"},{"field":"year","message":"must be greater than 0"}]}
```

A business rule: the ISBN of Dune is taken.

```bash
curl -s -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert"}'
```

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"A book with ISBN 978-0441172719 already exists","code":"DUPLICATE_ISBN","correlationId":"8aafdf58-e1ba-47c1-94a1-3ae9670861d3"}
```

A method the path doesn't serve is 405 with `Allow`:

```bash
curl -s -i -X PATCH http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 405 
X-Correlation-Id: 0aa94d93-f68a-4eb5-b5cb-7a1551c14b2f
Allow: GET, HEAD, PUT, DELETE, OPTIONS
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 04 Nov 2026 20:52:04 GMT

{"type":"about:blank","title":"Method Not Allowed","status":405,"detail":"The method is not allowed for this route","code":"METHOD_NOT_ALLOWED","correlationId":"0aa94d93-f68a-4eb5-b5cb-7a1551c14b2f"}
```

A body over the limit is 413 before the body is read. `POST /books` allows 16 KB (`.maxRequestSize(...)`); this sends
20,000 bytes. (`-H 'Expect:'` stops curl from waiting for a `100 Continue` first.)

```bash
head -c 20000 /dev/zero | tr '\0' x | curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'Expect:' --data-binary @-
```

```text
HTTP/1.1 413 
X-Correlation-Id: 99d78307-673b-4e4d-b6ab-980fd8cb79b2
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 04 Nov 2026 20:52:04 GMT
Connection: close

{"type":"about:blank","title":"Content Too Large","status":413,"detail":"The request body is too large","code":"PAYLOAD_TOO_LARGE","correlationId":"99d78307-673b-4e4d-b6ab-980fd8cb79b2"}
```

### Correlation id

Send your own id, and it comes back in the header, in the problem body and in the log line of the failure:

```bash
curl -s -i -X POST http://localhost:8080/books \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: demo-123' -H 'X-Channel: web' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert"}'
```

```text
HTTP/1.1 409 
X-Correlation-Id: demo-123
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 04 Nov 2026 20:52:04 GMT

{"type":"about:blank","title":"Conflict","status":409,"detail":"A book with ISBN 978-0441172719 already exists","code":"DUPLICATE_ISBN","correlationId":"demo-123"}
```

Without the header, the service generates a UUID. An id that isn't 1 to 128 characters of `[A-Za-z0-9._:-]` is
replaced.

### Conditional requests

`GET /books/{id}` sends the book's version as a strong `ETag` (`.etag(Book::version)`), with `Cache-Control: no-cache`
(a cache must check with the service before reusing it) and `Vary: Accept`:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 200 
X-Correlation-Id: 675f77b6-6591-46a9-a8c7-79ed82d22886
Cache-Control: no-cache
Vary: Accept
ETag: "1"
Content-Type: application/json
Content-Length: 149
Date: Wed, 04 Nov 2026 20:52:04 GMT

{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":1}
```

A client that has version 1 sends it in `If-None-Match`, and gets 304 without a body while the book is unchanged:

```bash
curl -s -i http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-None-Match: "1"'
```

```text
HTTP/1.1 304 
X-Correlation-Id: ccf0706e-2f13-4ba9-8113-3c3f1da64258
ETag: "1"
Cache-Control: no-cache
Vary: Accept
Date: Wed, 04 Nov 2026 20:52:04 GMT
```

`HEAD` answers the same way:

```bash
curl -s -I http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-None-Match: "1"'
```

```text
HTTP/1.1 304 
X-Correlation-Id: ff56c137-3131-49df-9f72-b0ac0dd92fdd
ETag: "1"
Cache-Control: no-cache
Vary: Accept
Date: Wed, 04 Nov 2026 20:52:04 GMT
```

On `PUT`, `.header("If-Match", UpdateBookRequest::version)` binds the tag's value to the `version` component, and the
update succeeds only on that version. The answer carries the new version in the body, and no `ETag` (RFC 9110 section
9.3.4):

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000001 \
  -H 'Content-Type: application/json' -H 'If-Match: "1"' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965}'
```

```json
{"id":"00000000-0000-0000-0000-000000000001","isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965,"addedVia":null,"version":2}
```

The same request again is based on a version that is no longer current. `ReviseBook` throws
`SynapseErrors.STALE_VERSION`, and because the request carried `If-Match` the plugin answers 412
`PRECONDITION_FAILED`; the book is unchanged:

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000001 \
  -H 'Content-Type: application/json' -H 'If-Match: "1"' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965}'
```

```json
{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"a64722ed-6e89-4d69-a522-fe02a987d8ad"}
```

A weak tag can never match a write's precondition, so the plugin answers 412 before the operation runs:

```bash
curl -s -X PUT http://localhost:8080/books/00000000-0000-0000-0000-000000000001 \
  -H 'Content-Type: application/json' -H 'If-Match: W/"2"' \
  -d '{"isbn":"978-0441172719","title":"Dune","author":"Frank Herbert","year":1965}'
```

```json
{"type":"about:blank","title":"Precondition Failed","status":412,"detail":"A precondition failed","code":"PRECONDITION_FAILED","correlationId":"1b980117-1d87-49aa-80d8-ae6a80f822e2"}
```

`If-Match` is optional on `PUT`. Without it, `ReviseBook` checks the version it has just read, so a concurrent change
that wins between that read and the store's compare-and-set still fails: the store returns `STALE`, and the answer is
409 `STALE_VERSION` (412 if the request had carried `If-Match`). Two curl calls can't reliably hit that window;
`BooksApplicationIT.concurrentUpdateWithoutIfMatchIs409` produces it with a spy on the store whose `find` returns the
book at an older version.

`DELETE` requires `If-Match` (`.requireIfMatch()`). Without it the answer is 428, and the violation says how to retry,
with `detail` off too:

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000001
```

```text
HTTP/1.1 428 
X-Correlation-Id: 99fbc750-738b-45e3-bc7f-d31939525fa4
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Wed, 04 Nov 2026 20:52:04 GMT

{"type":"about:blank","title":"Precondition Required","status":428,"detail":"The request must be conditional","code":"PRECONDITION_REQUIRED","correlationId":"99fbc750-738b-45e3-bc7f-d31939525fa4","violations":[{"field":"If-Match","message":"is required; send the ETag from a GET of this resource"}]}
```

With the current version (2, after the update above), the book is deleted:

```bash
curl -s -i -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000001 -H 'If-Match: "2"'
```

```text
HTTP/1.1 204 
X-Correlation-Id: 63bca881-4b82-4676-87d3-7c7c00ee4b41
Date: Wed, 04 Nov 2026 20:52:04 GMT
```

## What to look for in the logs

The console shows one JSON object per line, in the Elastic Common Schema (from `synapse-logging-log4j2`).

* At startup, one INFO event on `io.americanexpress.synapse.environment` names the environment and where it was set,
  then every configuration source, highest priority first, with the module or jar it comes from. It never prints a
  value. Its `message`, with `ENV=local`:

  ```text
  Synapse environment: local (spring.profiles.active=${ENV}, application.yaml)
  Configuration, highest priority first:
    1. server.ports
    2. Synapse baggage fields
    3. Java system properties
    4. environment variables
    5. application-local.yaml  synapse-sample-rest-books-2.0.0-SNAPSHOT.jar
    6. application.yaml        synapse-sample-rest-books-2.0.0-SNAPSHOT.jar
    7. logCorrelation
    8. applicationInfo
    9. defaults
    10. Management Server
  ```

* Successful calls log nothing.
* A failing operation logs one WARN line on `io.americanexpress.synapse.operation` (ERROR with the stack trace for an
  internal error), with `operation`, `error.code`, `error.category`, `correlationId` and the trace and span ids, plus
  the context attribute `channel` when the request sent `X-Channel`. The correlation id example above logs:

  ```json
  {"@timestamp":"2026-11-04T20:52:04.512351500Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"},"process":{"pid":5868,"thread":{"name":"tomcat-handler-38"}},"service":{"name":"rest-books","node":{}},"message":"Operation create-book failed with DUPLICATE_ISBN (CONFLICT): A book with ISBN 978-0441172719 already exists","channel":"web","correlationId":"demo-123","error":{"category":"CONFLICT","code":"DUPLICATE_ISBN"},"operation":"create-book","spanId":"0fa4d3b04897ea54","traceId":"b523ce9f8094e8eb97a26e811e8e8284","ecs":{"version":"8.11"}}
  ```

* A stale `If-Match` logs the operation's failure as `STALE_VERSION`, although the client gets 412
  `PRECONDITION_FAILED`: `Operation update-book failed with STALE_VERSION (CONFLICT): The resource was changed by
  another request`, with the request's `correlationId`.
* Requests turned away before an operation runs are not logged: an unknown book on `GET`, 405, 413, a request that
  doesn't bind, a sort key, offset or cursor the plugin rejects, a weak `If-Match` (412), and a `DELETE` without
  `If-Match` (428).

## Safe defaults

Synapse's defaults are the same in every environment; only `application-local.yaml` turns `detail` on. Stop the
service and start it with that setting turned off again:

```bash
ENV=local java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar \
  --synapse.errors.include-detail=false
```

No cursor key is set, so the plugin generates one at startup and warns, at every start, that tokens won't survive a
restart or work on another instance:

```text
{"@timestamp":"2026-11-04T20:52:28.497836700Z","log":{"level":"WARN","logger":"io.americanexpress.synapse.api.rest"},"process":{"pid":776,"thread":{"name":"main"}},"service":{"name":"rest-books","node":{}},"message":"synapse.api.rest.paging.cursor.key is not set, so paging cursors are encrypted with a key generated at startup: a cursor fails with 400 after a restart or on another instance. Set the same base64 256-bit key on every instance (for example: openssl rand -base64 32).","ecs":{"version":"8.11"}}
```

Problem responses leave out `detail`:

```bash
curl -s http://localhost:8080/books/00000000-0000-0000-0000-000000000099
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"f2c58f13-f82c-4c5e-98de-194fd74f8f7b"}
```

Deleting a book that doesn't exist (with `If-Match: *`, so the route's 428 doesn't answer first) fails in the business
operation:

```bash
curl -s -X DELETE http://localhost:8080/books/00000000-0000-0000-0000-000000000099 -H 'If-Match: *'
```

```json
{"type":"about:blank","title":"Not Found","status":404,"code":"BOOK_NOT_FOUND","correlationId":"268f7557-4d79-40cb-b0b5-e041d8d2fe51"}
```

The log line of the failure still carries the message (`Book 00000000-0000-0000-0000-000000000099 was not found`), so
the `correlationId` a caller reports leads to it.

To silence the WARN, give the service a key. Stop it and start it with one (a real deployment sets the same key on
every instance, from the environment or a secret store; see `application.yaml`):

```bash
ENV=local java -jar samples/rest-books/target/synapse-sample-rest-books-2.0.0-SNAPSHOT.jar \
  --synapse.api.rest.paging.cursor.key=$(openssl rand -base64 32)
```

The startup log then has no line about `synapse.api.rest.paging.cursor.key`, and no WARN at all.

## Configuration

`src/main/resources/application.yaml` lists the settings worth changing while you try the service, with comments: the
correlation header, error detail, the context header mapping, the deadline (`Request-Timeout`), the body limit, the
concurrency limit, and (commented out) the paging settings: `max-offset` and the cursor key, read from
`BOOKS_CURSOR_KEY`. Boot's OTLP metrics export is turned off there, because no OpenTelemetry collector runs with the
sample.

## Tests

`./mvnw -pl samples/rest-books -am verify` builds the modules and runs every test:

| Test | Shows |
|---|---|
| `api/rest/BooksRestTest` | `@SynapseRestTest`: the bindings, edge filters and problem responses on MockMvc, with `@MockitoBean` operations: the page object and `Link`, 304, `If-Match` binding, 428 |
| `operation/BookOperationsTest` | `@SynapseOperationTest`: operations and business beans with the real pipeline (validation, error codes, versions, paging and sorting) |
| `business/ReviseBookTest` | `SynapseOperations.invoke`: business operations through their pipeline, without Spring, including the races that end in `STALE_VERSION` |
| `data/InMemoryBookStoreTest` | The store, as a plain unit test: the compare-and-set |
| `BooksApplicationIT` | The whole service on a real port, as `test` with `synapse.errors.include-detail=false` (the safe defaults: no `detail`): cursors and `Link`, offsets, 304, 412, 409 with a `@MockitoSpyBean` store, 428; tagged `smoke` |
| `ArchitectureTest` | The layering rules |

## Not here yet

API versioning, CORS and OpenAPI (`/v3/api-docs`) come with Synapse's next REST plan (plan 2c) and will extend this
sample. The `RestConventions` bean (app-wide binding rules by type) isn't used: this API has no component type shared
by several routes.

This module inherits the repository's build parent so it passes the same gates as the library. Your own service
inherits `io.americanexpress.synapse:synapse-parent` and declares the same dependencies as this `pom.xml`.
