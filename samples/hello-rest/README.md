# Sample: hello-rest, the quick start

The smallest Synapse 2 service: one greeting over REST. Read it in five minutes, then copy it. It is what a real
service looks like, with no extra features: an operation, its two models, one route, and the tests.

It uses `synapse-api-rest`, `synapse-logging-log4j2` and the Synapse test modules. For a larger service (several
routes, business rules, paging, versions, CORS and OpenAPI) see [`rest-books`](../rest-books/README.md).

## What it shows

Code paths are under `src/main/java/io/americanexpress/synapse/samples/hello/`.

| Feature | Where |
|---|---|
| A `QueryOperation<GetGreetingRequest, Greeting>`: a use case that is plain Java, with no protocol in it. Its name is its class name (`GetGreetingOperation` is `get-greeting`) | `operation/GetGreetingOperation.java` |
| Records with Jakarta Validation: the pipeline checks `@NotBlank @Size(max = 40)` before the operation runs | `model/` |
| One `RestBindings` bean, which is all the REST configuration: `GET /greetings/{name}` fills `GetGreetingRequest.name` from the path | `api/rest/HelloRestConfiguration.java` |
| Problem responses (RFC 9457 `application/problem+json`) with `code`, `correlationId` and `violations`; `detail` only where `synapse.errors.include-detail` is on (local) | [Try it](#try-it) |
| The correlation id: read from `X-Correlation-Id` or generated, echoed on every response, written to the logs of the request | [Try it](#try-it) |
| The environment: `spring.profiles.active: ${ENV}` in `application.yaml`, and one file per environment | `src/main/resources/` |
| `GET /actuator/health` | `pom.xml` (`spring-boot-starter-actuator`) |
| ECS JSON logs on the console; a failing operation logs one line, a successful one logs nothing | [Try it](#try-it) |
| The pom of a real service: `synapse-parent` as the parent, dependencies without versions | `pom.xml` |
| Tests at every level, and the layering rules | [Tests](#tests) |

The packages follow Synapse's layering: `api.rest` calls `operation`, and nothing calls `api`. `ArchitectureTest`
checks it with `SynapseArchitecture.forApplication(HelloApplication.class)`.

## Run it

You need JDK 25 to build (see [Before you start](../README.md#before-you-start)). Then, from the repository root,
in Git Bash on Windows or any POSIX shell:

```bash
./mvnw -pl samples/hello-rest -am package -DskipTests
ENV=local java -jar samples/hello-rest/target/hello-rest-2.0.0-SNAPSHOT.jar
```

The first command builds the sample and the Synapse modules it uses, without running the tests (`./mvnw verify` runs
them). The second runs the `local` environment: `application.yaml` maps `spring.profiles.active: ${ENV}`, and
`application-local.yaml` turns on `detail` in problem responses (`synapse.errors.include-detail: true`). Without `ENV`
the service doesn't start: it needs exactly one environment.

You can also run it with `spring-boot:run`, which runs the `local` environment too (the pom sets
`spring-boot.run.profiles`; `-Dspring-boot.run.profiles=<env>` picks another). It runs the sample alone, so it takes
the Synapse modules from your local Maven repository: install them first (again after you change them):

```bash
./mvnw -pl samples/hello-rest -am install -DskipTests
./mvnw -pl samples/hello-rest spring-boot:run
```

`install` puts the Synapse modules in `~/.m2`, replacing any Synapse snapshot you have there.

The service starts on port 8080 and logs ECS JSON, one object per line. These are the lines that matter (the
`@timestamp`, `process` and `service` fields are left out here):

```
"message":"The following 1 profile is active: \"local\""
"message":"Tomcat started on port 8080 (http) with context path '/'"
"message":"Synapse REST exposes GET /greetings/{name} -> get-greeting on port 8080"
"message":"Started HelloApplication in 2.355 seconds (process running for 2.896)"
"message":"Synapse environment: local (spring.profiles.active=${ENV}, application.yaml)\nConfiguration, highest priority first: ..."
```

The last line lists the configuration sources of the running service, which is the first thing to read when a value
isn't what you expect. Stop the service with Ctrl+C.

## Try it

A greeting:

```bash
curl -si localhost:8080/greetings/Ada
```

```
HTTP/1.1 200 
X-Correlation-Id: 9294ffa2-3359-470c-bf2a-a992668d592d
Content-Type: application/json
Content-Length: 25
Date: Sat, 07 Nov 2026 17:49:42 GMT

{"message":"Hello, Ada!"}
```

Every response, errors included, carries `X-Correlation-Id`. Send your own (`-H 'X-Correlation-Id: my-id'`) and the
service uses it, and writes it to the logs of that request as `correlationId`.

A name longer than 40 characters fails the operation's validation. The answer is a problem response, and the
`violations` say which field:

```bash
curl -si localhost:8080/greetings/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
```

```
HTTP/1.1 400 
X-Correlation-Id: 1b1f2895-8ccf-4e1b-b35e-9af4569c34cb
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 17:49:42 GMT
Connection: close

{"type":"about:blank","title":"Bad Request","status":400,"detail":"The request is invalid","code":"VALIDATION_FAILED","correlationId":"1b1f2895-8ccf-4e1b-b35e-9af4569c34cb","violations":[{"field":"name","message":"size must be between 0 and 40"}]}
```

`detail` is there because the `local` environment turns it on; in every other environment it is absent unless the
environment's file turns it on. The failed operation logged one line (the same `correlationId`):

```
"log":{"level":"WARN","logger":"io.americanexpress.synapse.operation"} ... "message":"Operation get-greeting failed with VALIDATION_FAILED (INVALID_INPUT): The request is invalid","correlationId":"1b1f2895-8ccf-4e1b-b35e-9af4569c34cb","errorCategory":"INVALID_INPUT","errorCode":"VALIDATION_FAILED","operation":"get-greeting"
```

A path no route matches is a problem response too, with its own code:

```bash
curl -si localhost:8080/nope
```

```
HTTP/1.1 404 
X-Correlation-Id: b7c1e8d4-70c2-4aff-bf42-6f855fc52e3d
Content-Type: application/problem+json;charset=UTF-8
Transfer-Encoding: chunked
Date: Sat, 07 Nov 2026 17:49:42 GMT

{"type":"about:blank","title":"Not Found","status":404,"detail":"No route matches the request","code":"ROUTE_NOT_FOUND","correlationId":"b7c1e8d4-70c2-4aff-bf42-6f855fc52e3d"}
```

Health:

```bash
curl -s localhost:8080/actuator/health
```

```
{"groups":["liveness","readiness"],"status":"UP"}
```

(The ids and the date change on every run.)

## Tests

| Test | What it checks | Kind |
|---|---|---|
| `operation/GetGreetingOperationTest` | The operation with the real pipeline and no Spring: the greeting, and that a blank name is `INVALID_INPUT` with a violation on `name` (`SynapseOperations.invoke`) | unit |
| `api/rest/HelloRestTest` | The route on MockMvc with the operation mocked (`@SynapseRestTest`): the path variable reaches the operation's input, and the output is the JSON body | unit |
| `ArchitectureTest` | The layering rules | unit |
| `HelloApplicationIT` | The whole service on a real server in the `test` environment: the greeting and its correlation id, a too-long name as a problem response with no `detail`, an unknown route, and health | integration |

```bash
./mvnw -pl samples/hello-rest -am verify
```

runs them with everything the sample depends on (`*Test` in the `test` phase, `*IT` in `verify`). To run one unit
test class, or the integration test alone:

```bash
./mvnw -pl samples/hello-rest -am test -Dtest=GetGreetingOperationTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -pl samples/hello-rest -am verify -Dit.test=HelloApplicationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

`-Djacoco.skip=true` turns off the coverage gate, which needs the tests of every module the build includes.

The sample's pom has no formatter or license check of its own, as a service of yours wouldn't: the repository root
formats and checks it ([Before you start](../README.md#before-you-start)).

`src/test/resources/application-test.yaml` sets nothing, so `HelloApplicationIT` checks Synapse's defaults: a problem
response carries no `detail`.

## Layout

```
samples/hello-rest/
  pom.xml                             parent synapse-parent; the dependencies of a REST service, and two for tests
  src/main/java/.../hello/
    HelloApplication.java             @SpringBootApplication
    operation/GetGreetingOperation    QueryOperation<GetGreetingRequest, Greeting>
    model/GetGreetingRequest          (@NotBlank @Size(max = 40) String name)
    model/Greeting                    (String message)
    api/rest/HelloRestConfiguration   RestBindings: GET /greetings/{name}
  src/main/resources/
    application.yaml                  spring.profiles.active: ${ENV}, the application name, virtual threads, OTLP
                                      metrics push off (no collector runs locally)
    application-local.yaml            local conveniences: detail in problem responses
  src/test/java/.../hello/            the tests above
  src/test/resources/
    application-test.yaml             the test environment (empty: the tests run with the defaults)
```

## Make it yours

Copy the directory and change these:

* `pom.xml`: the `groupId`, `artifactId` and `description`; the parent's `relativePath` (drop it when the parent comes
  from a repository); and remove the four `maven.*.skip` properties, which only keep this sample from being published.
* The package `io.americanexpress.synapse.samples.hello` and `HelloApplication`.
* `spring.application.name` in `application.yaml`.
