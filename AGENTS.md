# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository. Tool-specific files, such as a local
`CLAUDE.md`, should import or reference this file rather than duplicate it.

## Project
Synapse is a set of Spring Boot starters for building enterprise services with prescriptive conventions. Services are
written as protocol-agnostic operations and exposed over REST, gRPC, GraphQL or cloud functions by adding API plugin
starters. Clients, data access and messaging follow the same conventions.

**Design decisions, scope and the delivery plan live in [specs/v2/SPEC.md](specs/v2/SPEC.md).** Read it before
designing anything. If a question is listed under its *Open questions*, ask the maintainers instead of deciding.

## Branches
| Branch | Line | Rules |
|---|---|---|
| `main` | Synapse 2 (in development). Spring Boot 4.1, built with JDK 25, `--release 21` | All new work. Short-lived `feature/*` / `fix/*` branches, merged by pull request. |
| `develop` | Synapse 0.4.x (v1), Spring Boot 3.5, JDK 21 | Maintenance only: security (CVE) fixes. CircleCI. |

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
- **Static analysis:** Error Prone and NullAway (JSpecify mode). Every package has a `package-info.java` annotated `@NullMarked`, and nullable types are marked `@Nullable`.
- **Dependencies:** the Maven Enforcer bans some dependencies (see the root `pom.xml`) and requires dependency convergence. Test libraries must stay in test scope.
- **Tests:**
  - Unit tests (`*Test`, surefire) and integration tests (`*IT`, failsafe) both run; integration tests are never skipped.
  - Per-module JaCoCo floor: 80% line / 70% branch.
  - Pull requests must cover at least 90% of the lines they change.
- **Security:** CodeQL, dependency review (fails on high severity), Dependabot.

## Engineering rules
- **Spring Boot first.** Never re-implement what Spring Boot auto-configures (DataSource, Jackson mapper, Kafka, gRPC server, observability exporters). Customize it through auto-configuration: `@AutoConfiguration`, registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, with `@ConditionalOn…` back-off so users can replace every bean, and properties under `synapse.*` via `@ConfigurationProperties`.
- **No hidden wiring.**
  - No `@ComponentScan` or `@Import` of framework packages.
  - No `@EnableWebMvc` / `@EnableWebFlux`.
  - No `application.properties` or logging configuration shipped inside library jars.
- **Every auto-configuration has `ApplicationContextRunner` tests:** it activates under its conditions, backs off when the user defines the bean, binds its properties, and stays absent when its condition isn't met.
- **Layering:**
  - Service modules (operations) never depend on protocol code (servlet, gRPC, GraphQL, Synapse API plugins).
  - Protocol concerns, such as HTTP status codes, live only in API plugins.
- **Logging:** SLF4J API only. Library code never chooses a logging backend.
- **Errors:** never expose stack traces, exception class names or internal messages to callers. Error detail is hidden unless `synapse.errors.include-detail=true`.
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
| `specs/` | Design specifications |
| `.config/` | Formatter profile and license header |

## Commits and pull requests
- [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `build:`, `chore:`, `docs:`, `test:`; `!` marks breaking changes). Release notes and versions are generated from them.
- Note CVE or advisory IDs in commits that bump dependencies for security.
- Contributors must sign the American Express [Contributor License Agreement](https://cla-assistant.io/americanexpress/synapse).
