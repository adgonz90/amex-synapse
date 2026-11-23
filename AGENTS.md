# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. Tool-specific files, such as a local
`CLAUDE.md`, should import or reference this file rather than duplicate it.

## Project
Synapse is a set of Spring Boot starters for building enterprise services with prescriptive conventions. Services are
written as protocol-agnostic operations and exposed over REST, gRPC, GraphQL or cloud functions by adding API plugin
starters. Clients, data access and messaging follow the same conventions.

**What to build, and in which phase, is in [specs/v2/BUILD.md](specs/v2/BUILD.md).** Read it before designing
anything. The reasons behind each decision, the 2.x roadmap and the open questions are in
[specs/v2/DESIGN.md](specs/v2/DESIGN.md). If a question is listed under its *Open questions*, ask the maintainers
instead of deciding.

## Branches
| Branch | Line | Rules |
|---|---|---|
| `main` | Synapse 2 (in development). Spring Boot 4.1, built with JDK 25, `--release 21` | All new work. Short-lived `feature/*` / `fix/*` branches, merged by pull request. |
| `develop` | Synapse 0.4.x (v1), Spring Boot 3.5, JDK 21 | Maintenance only: security (CVE) fixes. CircleCI. |

Synapse 2 work happens only on `main`. Don't change `develop`: its security fixes are handled separately.

Check which branch you are on before editing. Everything below describes `main`.

## Build and test
Requires JDK 25 to build (enforced). The Maven Wrapper is included; never use a global `mvn`.

```bash
./mvnw verify                                  # unit + integration tests and all quality gates
CI=true ./mvnw verify                          # CI's quality gates (formatting is checked, not applied)
CI=true ./mvnw -T 2 -Dmaven.test.redirectTestOutputToFile=true verify javadoc:javadoc   # CI's Verify gates (main runs install)
./mvnw verify -Djvm=/path/to/jdk-21/bin/java   # run tests on a Java 21 runtime
./mvnw -pl core/synapse-core -am verify        # one module and what it depends on
./mvnw -Pmutation verify                       # also run PIT mutation testing (nightly in CI)
./mvnw -Paot -pl samples/reference-bookstore/service-bookstore -am verify   # Spring AOT (nightly in CI)
```

Verify, Smoke and the nightly full, AOT and image jobs build two modules at a time (`-T 2`; `MAVEN_THREADS` in
`ci.yml` and `nightly.yml`), and CI writes each test
class's output to `target/surefire-reports/` or `target/failsafe-reports/` (`<class>-output.txt`) instead of the
console; failures are still summarised in the log. The other jobs' flags are in `.github/workflows/`.

On pull requests, Verify (JDK 25) is split across runners by the module groups in `.github/verify-groups.txt`. Each
group's job installs what its modules depend on without tests, then runs the command above with `-pl <its modules>`;
the `Verify (JDK 25)` check passes only when every group passed, and runs the changed-lines gate on all their coverage
reports. Smoke (tests on JDK 21) is split by the same groups, and its check likewise passes only when every group
passed. Every module is in exactly one group (the pull request fails otherwise), so a new module needs a line there.
Locally, a group's commands need `-Dmaven.repo.local=<scratch dir>`, since they install.

`-Paot` (synapse-parent's opt-in Spring AOT processing) works only for the reference bookstore, as above. The other
samples have no build environment file (`application-build.yaml`) by design, so `./mvnw -Paot verify` at the root fails
in them (`The environment 'build' has no file in the service`).

Single test: `-Dtest=ClassName#method -Dsurefire.failIfNoSpecifiedTests=false`. A single integration test with `-am`:
`-Dit.test=ClassNameIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
-Djacoco.skip=true` (the modules `-am` adds have no such IT, and the coverage gate needs every test of the module).
Neither is a passing build.

The samples inherit `synapse-parent`, not the root build, so only a build that includes the root formats them and
checks their license headers. `-pl samples/<name> -am` includes it (`-am` adds the root as the parent of the library
modules); `./mvnw -N process-sources` does it alone.

## Quality gates (CI fails on any of these)
- **Formatting:** Spotless with the Synapse Eclipse profile in `.config/synapse-java-format.xml`, plus import order, LF line endings and the license header. Local builds format files automatically; CI only checks.
  - Don't hand-format against the profile.
  - The formatter engine version (`eclipse-formatter.version`) is pinned. Bump it only in its own commit.
- **Static analysis:** Error Prone and NullAway (JSpecify mode). Every package has a `package-info.java` annotated `@NullMarked`, and nullable types are marked `@Nullable`. A test package with the same name as a main package has no `package-info.java` of its own: the main one covers it, and a second copy is a duplicate type in IDEs that compile main and test sources together.
- **Dependencies:** the Maven Enforcer bans some dependencies (see the root `pom.xml`) and requires dependency convergence. Test libraries must stay in test scope (except `synapse-test` and the `synapse-test-<plugin>` slices).
- **Tests:**
  - Unit tests (`*Test`, surefire) and integration tests (`*IT`, failsafe) are never skipped. Pull requests run all of them on JDK 25, and unit tests plus each module's smoke integration tests on JDK 21; the full JDK 21 run is nightly. The samples inherit `synapse-parent`, which doesn't read the smoke filter, so the JDK 21 pull request leg leaves out the ITs of the samples listed in `.config/jdk21-pr-excluded-its.txt` (Failsafe's `excludesFile`); they run on JDK 25 on every pull request and on JDK 21 nightly. The exception is the reference bookstore's `BookstoreAotIT`, which needs `-Paot` and runs only in the nightly `aot-reference` job (JDK 25 and 21). Oracle and SQL Server integration tests run nightly, on release, and on PRs that change those modules.
  - Every module with integration tests tags at least one of them `@Tag("smoke")` (keep them fast); those are the ones the JDK 21 pull request leg runs (`-Dsynapse.it.groups=smoke`).
  - `-DskipTests` also skips the coverage gate (there is no execution data), so a build with it is not a passing build.
  - Per-module JaCoCo floor: 80% line / 70% branch.
  - Pull requests must cover at least 90% of the lines they change.
- **Proto contracts:** `buf lint` (STANDARD minus the three AIP RPC-naming rules) and `buf breaking` (FILE) on every pull request to `main` (`.github/workflows/buf.yml`, configured by `buf.yaml`). Renumbering or removing a field, or moving or renaming a `.proto`, is a breaking change; a deliberate break needs the `buf skip breaking` label. The service archetype's copies of `buf.yml` and `buf.lock` must equal the repository's byte for byte.
- **Security:**
  - CodeQL, secret scanning and Dependabot.
  - Dependency review, which fails on new high-severity dependencies.
  - OSV-Scanner, which fails a PR only on vulnerabilities that PR introduces (known gap: the modules that depend on
    reactor SNAPSHOT artifacts fail transitive resolution and aren't scanned, yet the job passes; synapse-bom scans 0
    packages too, so a regressed CVE pin in the BOM wouldn't show there either, and only the `bom-guava-pin` invoker
    test guards the guava pin; see
    [BUILD.md §14](specs/v2/BUILD.md), gate 8).
  - actionlint and zizmor on workflow changes.
  - OpenSSF Scorecard.

## Engineering rules
- **Spring Boot first.** Never re-implement what Spring Boot auto-configures (DataSource, Jackson mapper, Kafka, gRPC server, observability exporters). Synapse configures only what Synapse builds (clients from bindings, and named data stores under `synapse.data.stores.<name>`, including a single store), and builds those with Boot's builders and property binding, never by hand. Synapse never adds an alias for a Boot property. Customize it through auto-configuration: `@AutoConfiguration`, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, with `@ConditionalOn…` back-off on every bean (a public extension point backs off by its public type, or by a documented `synapse…` name where each plugin has its own bean of one type; an internal bean backs off by its `synapse…` name only; BUILD.md 3.5), and properties under `synapse.*` via `@ConfigurationProperties`.
- **No hidden wiring.**
  - No `@ComponentScan` or `@Import` of framework packages.
  - No `@EnableWebMvc` / `@EnableWebFlux`.
  - No `application.properties`, `META-INF/synapse/` files or logging configuration shipped inside Synapse's own library jars. (A consumer's library modules ship their settings in `META-INF/synapse/config.yaml` and `config-<env>.yaml`; see BUILD.md 3.6.)
- **Every auto-configuration has `ApplicationContextRunner` tests:** it activates under its conditions, backs off when the user defines the bean, binds its properties, and stays absent when its condition isn't met.
- **Layering:**
  - Operation and business code (`ServiceOperation`/`BusinessOperation` beans and their models) never imports protocol code (servlet, gRPC, GraphQL, Synapse API plugins). In consumer projects, protocol bindings live in the service module's `api.<protocol>` packages. Business code never calls operations and never uses the service's own models, except its events and the contract models of the services it calls (see BUILD.md, *Layering rules*).
  - Protocol concerns, such as HTTP status codes, live only in API plugins.
- **Logging:** SLF4J API only. Library code never chooses a logging backend. The prescriptive default, synchronous Log4j2 (`synapse-logging-log4j2`), is managed by `synapse-parent` and declared only by consumers' bootable `service-*` modules.
- **Errors:** never expose stack traces, exception class names or internal messages to callers. Error detail is hidden unless `synapse.errors.include-detail` is true.
- **Environments:** the environment is the one active Spring profile (BUILD.md 3.6). Synapse has no production concept: every safety setting defaults to its safe value in every environment, and an environment file turns a convenience on. Never branch on the environment's name or use it in a `@Conditional`.
- **Java:** the code must compile with `--release 21`, so don't use Java 22+ APIs. Prefer records for data carriers.
- **Bug fixes** start with a failing test that reproduces the bug.
- **Dependency versions** are managed in the root `pom.xml`. Overriding a version that Spring Boot manages is allowed only as a documented CVE pin, with a removal condition and the advisory ID.
- **GitHub Actions** are pinned to a full commit SHA with the version in a comment, and workflows declare least-privilege `permissions:`.
- Don't commit IDE-specific settings (`.vscode/`, `.idea/`) or secrets.

## Repository layout
| Path | Contents |
|---|---|
| `pom.xml` | Root aggregator and internal build parent (versions, gates, profiles) |
| `bom/synapse-bom` | Bill of materials for consumers (does not import Spring Boot's BOM) |
| `parent/synapse-parent` | Parent POM for consumer applications (extends `spring-boot-starter-parent`) |
| `core/synapse-core` | Protocol-agnostic core |
| `core/synapse-test` | Test support: `SynapseOperations`, `@SynapseOperationTest`, architecture rules, AOT assertions |
| `core/synapse-logging-log4j2` | Prescriptive Log4j2 logging: ECS JSON default, optional failure-log burst limit |
| `api/` | API plugins, their test slices and add-ons (synapse-api-rest, synapse-test-rest, synapse-api-rest-openapi, synapse-api-grpc, synapse-test-grpc) |
| `data/` | Data modules and their test slice (synapse-data-core, -jpa, -jdbc, -postgres, synapse-test-data) |
| `samples/` | Runnable samples (hello-rest, rest-books, grpc-inventory, multi-store, reference-bookstore), each on `synapse-parent` with a README run against the app; never published |
| `archetypes/` | Maven archetypes (synapse-archetype-quickstart, shaped like hello-rest; synapse-archetype-service, shaped like the reference bookstore), each with an IT that generates a project and builds it; javadoc-placeholder (the javadoc jar they attach on release) |
| `docs/` | Documentation site (Antora, AsciiDoc); property tables are generated from configuration metadata |
| `tests/` | Tests of the build itself (maven-invoker projects for the Enforcer rules) and of modules that only meet in an application (synapse-data-mixed-tests: JPA and JDBC stores together; synapse-logging-logback-tests: Synapse's log lines on Logback in Boot's structured formats); never published |
| `specs/` | Design specifications |
| `.config/` | Formatter profile and license header |

## Commits and pull requests
- [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `build:`, `chore:`, `docs:`, `test:`; `!` marks breaking changes). Release notes and versions are generated from them.
- Note CVE or advisory IDs in commits that bump dependencies for security.
- Contributors must sign the American Express [Contributor License Agreement](https://cla-assistant.io/americanexpress/synapse).
