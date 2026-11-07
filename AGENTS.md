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
CI=true ./mvnw verify                          # exactly what CI runs (formatting is checked, not applied)
./mvnw verify -Djvm=/path/to/jdk-21/bin/java   # run tests on a Java 21 runtime
./mvnw -pl core/synapse-core -am verify        # one module and what it depends on
./mvnw -Pmutation verify                       # also run PIT mutation testing (nightly in CI)
```

Single test: `-Dtest=ClassName#method -Dsurefire.failIfNoSpecifiedTests=false` (integration tests: `-Dit.test=...`).

## Quality gates (CI fails on any of these)
- **Formatting:** Spotless with the Synapse Eclipse profile in `.config/synapse-java-format.xml`, plus import order, LF line endings and the license header. Local builds format files automatically; CI only checks.
  - Don't hand-format against the profile.
  - The formatter engine version (`eclipse-formatter.version`) is pinned. Bump it only in its own commit.
- **Static analysis:** Error Prone and NullAway (JSpecify mode). Every package has a `package-info.java` annotated `@NullMarked`, and nullable types are marked `@Nullable`. A test package with the same name as a main package has no `package-info.java` of its own: the main one covers it, and a second copy is a duplicate type in IDEs that compile main and test sources together.
- **Dependencies:** the Maven Enforcer bans some dependencies (see the root `pom.xml`) and requires dependency convergence. Test libraries must stay in test scope (except `synapse-test` and the `synapse-test-<plugin>` slices).
- **Tests:**
  - Unit tests (`*Test`, surefire) and integration tests (`*IT`, failsafe) are never skipped. Pull requests run all of them on JDK 25, and unit tests plus each module's smoke integration tests on JDK 21; the full JDK 21 run is nightly. Oracle and SQL Server integration tests run nightly, on release, and on PRs that change those modules.
  - Every module with integration tests tags at least one of them `@Tag("smoke")` (keep them fast); those are the ones the JDK 21 pull request leg runs (`-Dsynapse.it.groups=smoke`).
  - `-DskipTests` also skips the coverage gate (there is no execution data), so a build with it is not a passing build.
  - Per-module JaCoCo floor: 80% line / 70% branch.
  - Pull requests must cover at least 90% of the lines they change.
- **Security:**
  - CodeQL, secret scanning and Dependabot.
  - Dependency review, which fails on new high-severity dependencies.
  - OSV-Scanner, which fails a PR only on vulnerabilities that PR introduces.
  - actionlint and zizmor on workflow changes.
  - OpenSSF Scorecard.

## Engineering rules
- **Spring Boot first.** Never re-implement what Spring Boot auto-configures (DataSource, Jackson mapper, Kafka, gRPC server, observability exporters). Synapse configures only what Synapse builds (clients from bindings, and named data stores under `synapse.data.stores.<name>`, including a single store), and builds those with Boot's builders and property binding, never by hand. Synapse never adds an alias for a Boot property. Customize it through auto-configuration: `@AutoConfiguration`, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, with `@ConditionalOn…` back-off so users can replace every bean, and properties under `synapse.*` via `@ConfigurationProperties`.
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
| `samples/` | Runnable samples; never published |
| `docs/` | Documentation site (Antora, AsciiDoc); property tables are generated from configuration metadata |
| `tests/` | Tests of the build itself (maven-invoker projects for the Enforcer rules); never published |
| `specs/` | Design specifications |
| `.config/` | Formatter profile and license header |

## Commits and pull requests
- [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `build:`, `chore:`, `docs:`, `test:`; `!` marks breaking changes). Release notes and versions are generated from them.
- Note CVE or advisory IDs in commits that bump dependencies for security.
- Contributors must sign the American Express [Contributor License Agreement](https://cla-assistant.io/americanexpress/synapse).
