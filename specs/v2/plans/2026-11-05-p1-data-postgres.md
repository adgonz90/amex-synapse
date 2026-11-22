# P1 plan 4: data stores and PostgreSQL — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Synapse's named data stores (BUILD §8.1–8.2) with PostgreSQL as the first vendor. Every store, including a single one, is declared with `@SynapseDataStore("<name>")`, configured under `synapse.data.stores.<name>` and built by Synapse with Boot's builders and binding:
- a Hikari `DataSource` and JDBC templates;
- an EntityManagerFactory (JPA) or Spring Data JDBC infrastructure, with a transaction manager and repositories;
- Flyway migrations from `db/migration/<store>`, then schema validation, in `SmartLifecycle.start()`;
- a health contributor, with readiness opt-in, and metrics.

The plan also adds error translation, UUIDv7 ids, auditing, the JPA entity bases, the `ddl-auto` guard, `@SynapseDataTest` and per-store test connections.

**Architecture:**
- `@SynapseDataStore` imports a Synapse `ImportBeanDefinitionRegistrar`. For each store it registers factory-method bean definitions on internal factory beans, which the data auto-configurations define. The beans are named after the store and qualified with its name.
- Because Synapse's `DataSource`, `JdbcOperations`, `EntityManagerFactory`, `TransactionManager` and `Flyway` beans exist before Boot's auto-configurations are evaluated, Boot's single-DataSource, JDBC, JPA and Flyway auto-configurations back off. Three Boot defaults are set at lowest precedence: the Spring Data repository switches and `spring.jpa.open-in-view`.
- A per-store `SmartLifecycle` in the `DATA_STORES` phase runs the migrations, then the schema action. Nothing touches the database while beans are created: Hibernate gets the dialect and the database version from the vendor module, with JDBC metadata access turned off.

**Modules (new, under `data/`):**
- `synapse-data-core`: stores, JDBC, Flyway, health, translation, ids, auditing support, the vendor SPI.
- `synapse-data-jpa` and `synapse-data-jdbc`: store access.
- `synapse-data-postgres`: the vendor.
- `synapse-test-data`: the slice.

**Tech Stack:**
- Java 21 (`--release 21`, built on JDK 25).
- Spring Boot 4.1.2, which manages every library below. No version is added or overridden:
  - `spring-boot-jdbc`, `-hibernate`, `-jpa`, `-health`, `-transaction`;
  - HikariCP 7.0.2;
  - Hibernate ORM 7.4.13.Final, with Jakarta Persistence 3.2.0;
  - Spring Data 2026.0.2 (JPA 4.1.2, JDBC 4.1.2, Commons 4.1.2);
  - Flyway 12.4.0 (`flyway-core`, `flyway-database-postgresql`);
  - PostgreSQL JDBC 42.7.14;
  - Testcontainers 2.0.5 (`testcontainers-postgresql`);
  - H2 2.4.240 (test scope only).
- Spring Framework 7.0.10 (`spring-orm`, `spring-tx`, `spring-jdbc`).
- Test image `postgres:18.6-alpine`. The PostgreSQL versioning policy (https://www.postgresql.org/support/versioning/) lists 18.6 as the current minor; the 18.6 release notes (https://www.postgresql.org/docs/release/18.6/) say it came out 2026-08-13 and needs no dump or restore.
- Latest Boot GA re-checked (BUILD §1): 4.1.2. 4.2.0-RC2 came out 2026-11-05 (https://github.com/spring-projects/spring-boot/releases). The plan stays on 4.1.2.

**Spec:** [specs/v2/BUILD.md](../BUILD.md) §2, §3.1–3.3, §3.6 (*Keys without defaults*), §4.1, §5.1 (`SynapseErrors`), §5.9, §5.10, §8.1–8.2, §12 (`@SynapseDataTest`), §14 gate 1, §15 P1 acceptance 8, 10, 15, 17, 19 and 20. The reasons are in the [DESIGN.md](../DESIGN.md) §4.1 rows marked *proposed 2026-11-05, P1 plan 4*, and the Liquibase item is under *Open questions*. Parked items from earlier ledgers that this plan owns are listed in *Carried in*.

## Global Constraints

**Branch, JDK and language**
- Branch: `feature/v2-p1-data-postgres`, from `main` at 208065f2. The spec commit is its first commit. **Never push.** Merge to `main` locally only when the maintainer says so (squash, as for the earlier plans).
- JDK: run `export JAVA_HOME="$HOME/.jdks/jdk-25.0.5+8"; export PATH="$JAVA_HOME/bin:$PATH"` before any `./mvnw`. JDK 21 runs use `-Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java"`. Never use a global `mvn`.
- Compile with `--release 21`: no Java 22+ APIs. Prefer records for data carriers.
- Every package has a `package-info.java` annotated `@NullMarked`, and nullable types are `@Nullable`. Error Prone and NullAway must pass. A test package with the same name as a main package has no `package-info.java` of its own.
- Formatting: run the build, and Spotless applies the profile locally. Don't hand-format. Every file carries the license header (SQL and XML resources too, if Spotless checks them; follow what the build does).
- Don't edit `.vscode/` or a pom while a Maven build runs (VS Code shares `target/`).

**Behaviour fixed by the spec (copy exactly)**
- Every store is named and configured under `synapse.data.stores.<name>.*`, including a single one. `spring.datasource.*` is not used, and startup warns if it is set.
- `url`, `username` and `password` have no default. Credentials come from environment variables (`SYNAPSE_DATA_STORES_<NAME>_PASSWORD`).
- With one store, it is the primary. With several, one may set `primary=true`; others are selected with `@Transactional("<store>")`. An unqualified `@Transactional` with several stores and no primary fails at startup. Cross-store transactions are not atomic (documented).
- Migrations: Flyway, per store, from `db/migration/<store>`, in `SmartLifecycle.start()`, followed by schema validation, before any inbound plugin starts.
- When a migration tool is present, `ddl-auto` defaults to `validate`. `create`, `create-drop` and `update` fail startup unless the store sets `synapse.data.stores.<name>.allow-ddl-auto=true`. This guard is safe everywhere: no environment name is ever checked.
- No remote I/O during bean creation (acceptance 17). The EntityManagerFactory is built without JDBC metadata access.
- Readiness reflects a store only with `synapse.data.stores.<name>.health.readiness=true`, using its own short timeout.
- Error translation: optimistic lock → `STALE_VERSION`; unique constraint → `ALREADY_EXISTS`; query timeout → `DEADLINE_EXCEEDED`; connection failure → `UNAVAILABLE`; other data access failures → `INTERNAL` (the pipeline's default).
- UUIDv7 per RFC 9562, monotonic within a millisecond, using the `Clock`. Auditing fills timestamps from the `Clock` and the user from the `RequestContext` principal, else `synapse.data.auditing.system-user` (default `system`).
- Startup logs the connection budget (stores × pool size).

**Library code**
- Spring Boot first: build with `DataSourceProperties.initializeDataSourceBuilder()`, `EntityManagerFactoryBuilder`, `JpaProperties`, `HibernateProperties`, `HibernateJpaVendorAdapter`, `TransactionManagerCustomizers` and Boot's `Binder`. Never alias a Boot key. Boot defaults change only at lowest precedence, with an `EnvironmentPostProcessor` that adds to `defaultProperties` (the pattern of `Log4j2DefaultsEnvironmentPostProcessor`).
- No `@ComponentScan`, no `@Import` of framework packages (`@SynapseDataStore` imports Synapse's own registrar), no `application.*` and no `META-INF/synapse/` files in Synapse jars.
- SLF4J API only. **Never log, or put in a message, a URL, user name, password or any property value.** A URL is shown only as its scheme (`jdbc:postgresql`).
- Code outside `..internal..` packages is public API. New public SPI types are `@Incubating`.
- Configuration failures throw `SynapseConfigurationException(description, action)`. Copy the texts from *Messages*.
- Registrar bean definitions name a factory bean and a factory method, pass the store name as a constructor argument, and **set `setTargetType(...)`**. Boot's `@ConditionalOnMissingBean` predicts the type before the factory bean's definition exists, and AOT generates code for factory methods (never instance-supplier lambdas).

**Tests**
- Unit tests are `*Test` (surefire); integration tests are `*IT` (failsafe). Every module with ITs tags at least one fast IT `@Tag("smoke")`.
- Every auto-configuration gets `ApplicationContextRunner` tests: it activates, backs off to a user bean, binds its properties, and is absent when its condition fails. For store beans, "backs off" means a bean with the store bean name in the `@SynapseDataStore` class.
- Store behaviour is tested on H2 in `synapse-data-core`, `-jpa` and `-jdbc`, through a test-only `H2TestVendor` (`jdbc:h2:`, `org.hibernate.dialect.H2Dialect`, product name `H2`, version `2.4`, `JdbcH2Dialect`). PostgreSQL behaviour is tested in `synapse-data-postgres` with Testcontainers.
- **Docker is required for the PostgreSQL ITs.** Never `disabledWithoutDocker`, never skip. If Docker is missing, the implementer stops and reports it (this machine has none today; see the report to the maintainer).
- Every Spring Boot test runs as `test`, with `src/test/resources/application-test.yaml` in each module. Context runners need neither.
- JaCoCo floor per module: 80% line / 70% branch; 90% of changed lines covered. `-DskipTests` is never a passing build.
- Assert outcomes: bean names and types, resolved values, exact message texts, rows in the database, ids and timestamps.

**Commits**
- Conventional Commits. Every message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Stage by explicit path (`git add <path> …`), never `git add -A` or `git add .`. Never stash, amend or push.
- Spring Boot stays on 4.1.2.

**Commands.**
- Unit test: `./mvnw -q -pl data/<module> -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`.
- IT: `./mvnw -q -pl data/<module> -am verify -Dit.test=ClassNameIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`.

Neither counts as a passing build. Every task ends with `./mvnw -pl <module> -am verify` for each module it touched.

## Spec changes this plan records

Committed with this plan (`docs: plan data stores and Postgres (P1 plan 4)`), before Task 1:
- **BUILD.md:**
  - §2: new rows `synapse-data-core` and `synapse-test-data`, and the `synapse-data-jpa`/`-jdbc` purpose.
  - §3.3: the Hibernate metadata-access check is recorded as verified; schema actions run in `start()`.
  - §5.1: `ALREADY_EXISTS`.
  - §8.1: bean names and qualifiers, `access`, the key list, Liquibase waiting on the maintainer, the `none` default without a migration tool, `migrations.enabled`, `open-in-view`.
  - §8.2: base class names, `isNew` for the id-only bases, the auditor source, the translation codes.
  - §12: the `@SynapseDataTest` row with `@StoreConnection`.
  - §15: the plan 4 entry in the delivery list.
- **DESIGN.md:** the history line; 4.1 rows *Data modules*, *Store registration*, *Per-store configuration*, *Schema actions*, *Test connections*, *Data error codes*, *Ids and auditing* and *Open-in-view* (all marked *proposed*); the Liquibase open question; sources.
- **AGENTS.md:** no edit now. Task 1 adds the `data/` row to the layout table when the modules exist (as plan 2b did for `api/`).

## Defaults this plan sets (confirm at review)

| # | Default | Why |
|---|---|---|
| 1 | New artifact `synapse-data-core` under `-jpa`/`-jdbc`, plus `synapse-test-data`. Modules live in a new top-level `data/` folder. | DESIGN *Data modules (proposed)*. |
| 2 | Store names match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`. The bean prefix is the lowerCamel form (`legacy-orders` → `legacyOrders`). The qualifier and the persistence unit name are the name as written. | Kebab names fit property keys and env variables (`SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD`); camel bean names are Java-friendly. |
| 3 | Beans per store `<p>` (all default candidates, qualifier `<name>`): `<p>DataSource` (`HikariDataSource`), `<p>JdbcTemplate`, `<p>NamedParameterJdbcTemplate`, `<p>JdbcClient`, `<p>Flyway`, `<p>StoreLifecycle`. JPA adds `<p>PersistenceManagedTypes`, `<p>EntityManagerFactory`, `<p>TransactionManager` (`JpaTransactionManager`), `<p>SchemaAction` and repositories. JDBC adds `<p>TransactionManager` (`JdbcTransactionManager`), `<p>JdbcDialect`, `<p>RelationalManagedTypes`, `<p>JdbcMappingContext`, `<p>JdbcConverter`, `<p>DataAccessStrategy`, `<p>JdbcAggregateTemplate` and repositories. *Amended:* JDBC also has `<p>JdbcCustomConversions` (Task 11). A bean of the store class that replaces one of these gets the store's qualifier (unless it names its own) and primary flag (interim review I3). | One naming rule; `@Transactional("books")` matches the qualifier. |
| 4 | Primary: the registrar sets `primary` from the store's own `synapse.data.stores.<name>.primary` (bound at registration, so Boot's `@ConditionalOnSingleCandidate` sees it). A `BeanDefinitionRegistryPostProcessor` then marks the store primary when it is the only one, and fails when two set it. *Amended in Task 15:* under AOT the post-processor runs again at startup, and fails when a store's bound `primary` differs from the one its definition was generated with. | Conditions are evaluated before post-processors run; a single store is a single candidate either way. |
| 5 | Access: `@SynapseDataStore(value, access = DataAccess.AUTO)`. AUTO is the one access module present. With none or both, startup fails (*Messages*). | Both modules may coexist in one service. |
| 6 | Connection: a `JdbcConnectionDetails` bean qualified with the store name wins over `url`/`username`/`password`. Otherwise all three must be set; `password` may be set to an empty string. *Amended (Tony to confirm):* a `url` with credentials in it (userinfo, or a parameter named like a user or secret) fails startup (Task 5); an empty password reaches the pool as `""`, not `null`, so a driver never reads a password file of its own (Task 14; since final review 2 also a `null` password from qualified details); the vendor's URL check runs on the qualified details too. | Tests and container tooling supply qualified details. Keys without defaults fail at startup. |
| 7 | Hikari settings bind onto the `HikariDataSource` at `synapse.data.stores.<name>.hikari`, after Synapse sets `poolName` to the store name (so metrics carry `pool=books`). No other Synapse pool defaults: Hikari's own defaults are the safe ones. *Amended in Tasks 2, 3 and the interim review (Tony to confirm):* `hikari.jdbc-url`, `username`, `password`, `data-source-class-name`, `data-source-jndi`, `schema`, `catalog`, `connection-init-sql` (final review 2), `connection-test-query` (final re-review) and `data-source-properties.user`, `username`, `password`, `url` (any case) fail startup naming the key, never the value; `credentials-provider-class-name` stays allowed. Hikari's timeouts take milliseconds (F7). A store key that writes the store name in brackets fails startup. | Boot's binding pattern (`spring.datasource.hikari.*`); one source of truth. Changes the `pool-size` example in `environments.adoc` (Task 15). |
| 8 | JPA: Boot's `JpaProperties` bound at `<prefix>.jpa` and `HibernateProperties` at `<prefix>.jpa.hibernate`, then turned into vendor properties with `HibernateProperties.determineHibernateProperties`. Synapse adds `jakarta.persistence.database-product-name` (the vendor's `databaseProductName()`: `H2`, `PostgreSQL`) unless `jpa.database-platform` or `jpa.properties.hibernate.dialect` is set, so Hibernate resolves the dialect from the name and version itself, `jakarta.persistence.database-product-version` and the major and minor versions (`database-version`, else the vendor default), `hibernate.boot.allow_jdbc_metadata_access=false` and `hibernate.integrator_provider` (Task 8). *Amended in Task 6 (Tony to confirm):* the product name replaces `hibernate.dialect`, which made Hibernate log the deprecation WARN HHH90000025 on every startup; it is still connection-free. | Boot's own classes; no Boot key aliased. |
| 9 | JDBC templates: Boot's `JdbcProperties.Template` bound at `<prefix>.jdbc.template` and applied as `JdbcTemplateConfiguration` does (fetch size, max rows, query timeout, ignore warnings, skip results processing, skip undeclared results, results map case-insensitive). | Same settings as `spring.jdbc.template.*`. |
| 10 | Migrations: `migrations.enabled` (default `true`), `migrations.locations` (default `classpath:db/migration/<name>`), `migrations.flyway.*` (a `Map<String, String>` of Flyway's own keys without the `flyway.` prefix, for example `baseline-on-migrate`; passed through `FluentConfiguration.configuration(Map)` with `flyway.` added and the key turned into Flyway's camelCase). A `Flyway` bean exists for every store whenever `flyway-core` is present, so Boot's Flyway always backs off; the lifecycle skips `migrate()` when disabled. *Amended in Task 5 (Tony to confirm):* `flyway-core` is optional (F13; the lifecycle takes a `StoreMigration`); `migrations.flyway.url`, `user`, `password`, `locations` and (final review 2) `init-sql` fail startup naming the key; an unknown Flyway key, a value Flyway rejects and two spellings of one key fail without showing the value; `migrations.locations` or `migrations.flyway.*` set without a migration tool fails. | Boot's Flyway mapping is private; Flyway's own names avoid copying it. Boot's `flyway` Actuator endpoint then lists each store. |
| 11 | Boot defaults at lowest precedence (`DataDefaultsEnvironmentPostProcessor` in data-core, `JpaDefaultsEnvironmentPostProcessor` in data-jpa, `JdbcDefaultsEnvironmentPostProcessor` in data-jdbc): `spring.liquibase.enabled=false`; `spring.jpa.open-in-view=false` and `spring.data.jpa.repositories.enabled=false`; `spring.data.jdbc.repositories.enabled=false`. *Amended in Task 2 (Tony to confirm):* also `spring.sql.init.mode=never`, since Boot's SQL initialization would run scripts against the primary store at bean creation. | Boot's Spring Data auto-configurations would scan the application packages for repositories (a JPA store without repositories doesn't make them back off), and Boot's JDBC one resolves a dialect over a connection. Liquibase isn't supported yet and Boot's would migrate the primary at bean creation. |
| 12 | `ddl-auto` values: Boot's five (`none`, `validate`, `update`, `create`, `create-drop`). Default `validate` when `flyway-core` is present (even with `migrations.enabled=false`), else `none`. Raw `hibernate.hbm2ddl.auto` or `jakarta.persistence.schema-generation.*` keys in `jpa.properties` fail startup. Hibernate always gets `none`; Synapse runs the action in `start()` (DESIGN *Schema actions*). *Amended in Tasks 8 and 9 (Tony to confirm the global `none` exception):* every `hibernate.hbm2ddl.*` key and every `jakarta.`/`javax.persistence.schema-generation.*` key fails, in `jpa.properties`, in Hibernate's global settings (except the value `none` for `hibernate.hbm2ddl.auto[.<contributor>]` and the root `database.action`/`scripts.action`) and in a customizer's output; `jpa.generate-ddl=true` fails; Hibernate gets `none` for the root jakarta actions too; schema actions never create or drop namespaces; an allowed action other than `none`/`validate` sets `hibernate.hbm2ddl.halt_on_error=true`; `update` fails at bean creation for a store whose class defines its own EntityManagerFactory. | Closes the guard's bypass; keeps acceptance 17 for every setting. |
| 13 | Schema validation failure fails startup with the store name and Hibernate's message (table and column names; server log only). | A mismatch must not start serving. |
| 14 | `ALREADY_EXISTS` (`CONFLICT`, template "The resource already exists"). Query timeouts use `DEADLINE_EXCEEDED`. Connection failures include `CannotCreateTransactionException` (the database is down when the transaction begins). Translators walk the cause chain to depth 10. | DESIGN *Data error codes*. `@Transactional` sits inside the Synapse advisor (BUILD §5.2), so begin and commit failures reach the translator. |
| 15 | UUIDv7: the algorithm in Task 1. `EntityIdGenerator` bean `synapseEntityIdGenerator` (`@ConditionalOnMissingBean(EntityIdGenerator.class)`), built on the `Clock` and one `SecureRandom`. | RFC 9562 §6.2 method 1. |
| 16 | Bases: `IdEntity`, `VersionedEntity`, `AuditedEntity` (UUID, assigned in `@PrePersist` when null) and `LongIdEntity`, `LongVersionedEntity`, `LongAuditedEntity` (`@GeneratedValue(strategy = SEQUENCE)`). `isNew()` is `version == null`, or `id == null` for the id-only bases. `equals` compares the id and the unproxied class (`Hibernate.getClass`); `hashCode` is the unproxied class's hash. Columns: `id`, `version`, `created_at`, `created_by`, `modified_at`, `modified_by`. | BUILD's `<ID>` is carried by the `Identifiable<ID>` capability; a mapped superclass can't choose its generator by type. |
| 17 | Auditing: JPA uses a Synapse default entity listener (`EntityCallbacks`, from mapping resource `io/americanexpress/synapse/data/jpa/synapse-orm.xml` added to every store's persistence unit). `@PrePersist` assigns the id and stamps created and modified; `@PreUpdate` stamps modified. JDBC uses a `BeforeConvertCallback`: created when `getCreatedAt()` is null, and modified always. The user comes from `AuditorAware<String>` bean `synapseAuditorAware` (`@ConditionalOnMissingBean(AuditorAware.class)`). *Amended in Task 10 (Tony to confirm):* `synapseAuditorAware` is `@Bean(defaultCandidate = false)` with `@ConditionalOnMissingBean(name = "synapseAuditorAware")`, and both callbacks resolve an application `AuditorAware<String>` first (one data-core `AuditStamps` resolver), so an `AuditorAware<Long>` or `@EnableJpaAuditing` no longer breaks the stores; `@EnableJpaAuditing` without an application auditor gets none of Synapse's, and two application `AuditorAware<String>` beans fail startup. Stamps are truncated to microseconds. JPA's `@PrePersist` stamps created only when `getCreatedAt()` is null, as JDBC does. | DESIGN *Ids and auditing*. |
| 18 | The undeclared-store check (`synapse.data.stores.<x>` set with no `@SynapseDataStore("x")`) is skipped when the bean definition `synapseDataTestSlice` exists (`@SynapseDataTest` registers it), because the slice leaves other stores out on purpose. | The slice must start with the service's full configuration. |
| 19 | Store packages must not overlap: startup fails when one store's package is the other's or lies inside it. | Otherwise one store would scan the other's entities and repositories. |
| 20 | Health: Synapse defines `dbHealthContributor` (Boot's `DataSourceHealthContributorAutoConfiguration` backs off by name), a composite of one indicator per store named after the store (`db/books`). Indicator: when the pool has connections and none idle, it reports `UP` with detail `pool: busy` without borrowing; otherwise it borrows on a virtual thread and waits at most `health.timeout` (default `1s`), checking `Connection.isValid(1)`. A borrow that returns late is closed. Readiness: a `HealthEndpointGroupsPostProcessor` makes `db/<name>` a member of the `readiness` group for stores with `health.readiness=true`. *Amended in Task 12 (Tony to confirm):* the borrow runs on a platform daemon thread, not a virtual one (on JDK 21 a never-started Hikari pool pins the carrier), with at most one borrow in flight per store; a check that finds one waits for it up to its own timeout, so an outage never queues borrows. Every store is in the contributor, whatever its `DataSource` (the busy check needs Hikari). `health.timeout` must be positive. | BUILD §5.10: a busy pool doesn't flip readiness; a short timeout. |
| 21 | Startup report (one `ApplicationStartedEvent` listener, logger `io.americanexpress.synapse.data`): an INFO budget line and, when set, one WARN per ignored prefix (`spring.datasource`, `spring.flyway`), naming keys only. Keys are read from enumerable sources other than `defaultProperties`. *Amended (interim review):* one data-core report; the access modules add their keys: `spring.jdbc.template`, `spring.jpa` (with its own `spring.jpa.open-in-view` WARN, quiet for `false`), `spring.data.jdbc.dialect` and `spring.dao.exceptiontranslation.enabled`. | BUILD §8.1. |
| 22 | The unqualified `@Transactional` check runs only with ≥2 stores and no primary. It inspects every bean's user class with an `AnnotationTransactionAttributeSource`, skips `org.springframework.*` classes and Spring Data repository interfaces, and fails listing up to 5 `Class#method`s (then `and <n> more`). `TransactionTemplate` use can't be checked (documented). *Amended in Task 9:* it honours what Spring resolves: a primary transaction manager, a `TransactionManagementConfigurer` bean, or a type-level `@Qualifier` that names a transaction manager. | BUILD §8.1. |
| 23 | `@SynapseDataTest("<store>")` is `@Transactional` on that store (rolled back), like `@DataJpaTest`. Its `value` is an `@AliasFor` of `Transactional.transactionManager`. | The qualifier is the store name. |
| 24 | `@StoreConnection("<store>")` goes on static `JdbcDatabaseContainer<?>` fields of the test class (and its superclasses). The container is started if it isn't running. The registered `JdbcConnectionDetails` bean is `<p>StoreConnectionDetails`, qualified with the store name, with `defaultCandidate=false`. `@Bean`-method containers are not supported in P1. *Amended in Task 13:* the details bean uses an instance supplier, so AOT test contexts are not supported; a subclass or `@Nested` class can't replace a connection for the same store. | DESIGN *Test connections*. |
| 25 | `SynapseContainers.postgres()` and `postgres(String key)`: one `PostgreSQLContainer` (`org.testcontainers.postgresql`) per key per JVM, image `postgres:18.6-alpine`, started lazily, never stopped (Ryuk cleans up). | Shared across test classes (BUILD §12); two keys give two databases (acceptance 8). |
| 26 | Postgres vendor: matches URLs that start with `jdbc:postgresql:`; dialect `org.hibernate.dialect.PostgreSQLDialect`; JDBC dialect `org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect`; database product name `PostgreSQL`; default `database-version` `15` (Hibernate 7.4's `PostgreSQLDialect` supports 14 and later; a store on a 14 server sets `database-version: 14`). Services set their real version. *Amended in Task 14 (Tony to confirm the default 15 and these rules):* the URL must name a host and a database (`jdbc:postgresql://<host>[:<port>][,...]/<database>`); the URL parameters `host`, `port`, `dbname`, `PGHOST`, `PGPORT`, `PGDBNAME` and `service` fail startup; the `hikari.data-source-properties` `PGHOST`, `PGPORT`, `PGDBNAME`, `host`, `port`, `dbname`, `user`, `password`, `sslpassword`, `socketFactory`, `socketFactoryArg`, `options`, `currentSchema` and `service` fail startup (any case); a class-path `org/postgresql/driverconfig.properties` fails startup; every `PGProperty` of PgJDBC 42.7.14 is classified blocked or allowed by a test. | PostgreSQL 14 reaches end of life on 2026-11-12, so the ruling made 15, the oldest supported release after that, the default. The checks keep a store on the host, database and credentials its settings name. |
| 27 | `rest-books` stays on its in-memory store. The first Postgres sample is `samples/reference-bookstore` in plan 5. | BUILD §15 assigns the reference app to plan 5. |
| 28 | Liquibase: not built (DESIGN *Open questions*). Migration runs through an internal `StoreMigration` seam with one Flyway implementation. | License decision pending. |

*Amended* notes in the table record what implementation changed (Task 15 collected them from the ledger). The ones marked *Tony to confirm* are defaults the maintainer hasn't confirmed yet. Each is listed with the provisional choice the branch implements:
- #6: a `url` with credentials fails startup; an empty password reaches the pool as `""` for **every** vendor, not only PostgreSQL (no driver password file is read; a future vendor that needs a `null` password would need an SPI opt-out).
- #7: the rejected Hikari keys; a bracketed store key fails startup. Since final review 2 (I2) also `hikari.schema` and `hikari.catalog` (the schema goes in the URL), and `hikari.connection-init-sql` with `migrations.flyway.init-sql` (session or role changes bypass the isolation rules): an application that needs an init statement has no way to set one until a Synapse key exists. Since the final re-review (N2) also `hikari.connection-test-query`, which runs on each connection check.
- #8: `jakarta.persistence.database-product-name` instead of `hibernate.dialect`.
- #10: `flyway-core` optional; the rejected Flyway keys.
- #11: `spring.sql.init.mode=never`.
- #12: in Hibernate's global settings, the value `none` is allowed for `hibernate.hbm2ddl.auto[.<contributor>]` and the root `database.action`/`scripts.action`; every other schema key there fails startup.
- #17: the auditor back-off (`defaultCandidate = false`, an application `AuditorAware<String>` wins).
- #20: a platform daemon thread, one borrow in flight per store.
- #26: the PostgreSQL default `database-version` 15 (14 reaches end of life on 2026-11-12), and the isolation rules: host and database required in the URL, the blocked URL parameters and `data-source-properties`, a class-path `driverconfig.properties` fails, the SSL and Kerberos keys allowed.
- JDBC qualifier rules (Task 11 rulings): a store's own `<p>NamingStrategy`/`<p>JdbcCustomConversions`, else an application bean qualified with the store, else an unqualified one; `@Primary` among several; a bean with another qualifier is never used. One unqualified `JdbcCustomConversions` replaces the dialect's store conversions for every JDBC store, and fails startup when those stores' dialects differ (interim parked 3).
- JPA customization (interim review I4, provisional): application `PhysicalNamingStrategy`/`ImplicitNamingStrategy` beans with the same single-bean qualifier rules; every applicable `HibernatePropertiesCustomizer`, run unqualified, then store-qualified, then the store's own, each group in `@Order`, so the most specific wins; the guards run again on the result.

Open questions, with what the branch does meanwhile:
- Should lock, serialization and deadlock failures (55P03, 40001, 40P01) map to a retryable code? Meanwhile they are `INTERNAL`, pinned by tests.
- Should migration scripts in `db/migration/<store>` alone count as "migrations configured" when no migration tool is on the class path? Meanwhile only explicit `migrations.locations` or `migrations.flyway.*` keys fail startup; scripts alone are not looked for (no class-path scan).
- Optional follow-up (task-14 review): a `SynapseOperations.with().translators(ApplicationContext)` helper, so a `@SynapseDataTest` doesn't wire the data translators one by one. Not built; the docs show `translator(...)`.

The interim whole-branch review's list of public API that is hard to change after 2.0 (its recommendation 4: the store keys, bean names and qualifier rule, `SynapseDataStoreProperties`, the entity bases' columns and semantics, `Auditable.setCreated`, `DataStoreVendor`, `SynapseContainers.postgres()` in the generic slice, `@SynapseDataTest.value`, `ALREADY_EXISTS`) is the other set to decide before the release.

## Messages (copy exactly; `<…>` filled in)

| Case | Description | Action |
|---|---|---|
| Invalid name | `'<name>' is not a valid store name (@SynapseDataStore on <class>).` | `Use lowercase letters, digits and dashes, starting with a letter, such as books or legacy-orders.` |
| Declared twice | `The store '<name>' is declared twice: <class A> and <class B>.` | `Declare each store once.` |
| Packages overlap | `The packages of the stores '<a>' (<package a>) and '<b>' (<package b>) overlap.` | `Give each store's @SynapseDataStore class, entities and repositories their own package, outside every other store's package.` |
| Undeclared | `synapse.data.stores.<name> is configured, but no @SynapseDataStore("<name>") declares the store.` | `Add the data module that declares it, or remove the settings.` |
| Two primaries | `More than one store sets primary: <a>, <b>.` | `Set synapse.data.stores.<name>.primary: true on one store only.` |
| Access ambiguous | `The store '<name>' must name its access: both synapse-data-jpa and synapse-data-jdbc are present.` | `Use @SynapseDataStore(value = "<name>", access = DataAccess.JPA) or DataAccess.JDBC.` |
| Access missing | `The store '<name>' uses <JPA\|JDBC>, but <synapse-data-jpa\|synapse-data-jdbc> is not on the class path.` | `Add <module>.` (AUTO with neither module: `<JPA\|JDBC>` reads `JPA or JDBC`, the module `synapse-data-jpa or synapse-data-jdbc`.) |
| No key | `The store '<name>' has no <url\|username\|password>.` | url: `Set synapse.data.stores.<name>.url in the data module's config-<env>.yaml. Keys that say where to connect have no default.` username/password: `Set synapse.data.stores.<name>.<key>, from an environment variable such as SYNAPSE_DATA_STORES_<NAME>_<KEY>.` (`<NAME>`: upper case, dashes removed; `<KEY>` is `USERNAME` or `PASSWORD`, so the username's action names `SYNAPSE_DATA_STORES_BOOKS_USERNAME`; F24b) |
| No vendor | `No Synapse vendor module supports the store '<name>' (<scheme>).` | `Add the vendor module for this database, such as synapse-data-postgres.` |
| ddl-auto not allowed | `The store '<name>' sets ddl-auto to <value>, which changes the schema at startup.` | `Use migrations in db/migration/<name>, or allow it for one environment with synapse.data.stores.<name>.allow-ddl-auto: true in application-local.yaml or a test file.` |
| ddl-auto unknown | `The store '<name>' sets ddl-auto to '<value>', which is not one of none, validate, update, create, create-drop.` | `Use one of these values.` |
| Raw schema key | `The store '<name>' sets <key> in synapse.data.stores.<name>.jpa.properties.` | `Use synapse.data.stores.<name>.jpa.hibernate.ddl-auto instead.` |
| Schema mismatch | `The schema of the store '<name>' does not match its entities: <Hibernate's message>` | `Add a migration in <locations> that brings the schema in line, or fix the mapping.` |
| Unqualified `@Transactional` | `There are <n> stores and none is primary, so an unqualified @Transactional can't choose a transaction manager: <Class#method>, ….` | `Name the store (@Transactional("<store>")), or set synapse.data.stores.<name>.primary: true on one store.` |
| WARN ignored keys | `<prefix>.* is set (<key>, <key>), but Synapse data stores don't use it. Configure each store under synapse.data.stores.<name>.` | |
| INFO budget | `Synapse data stores: books (pool 10), legacy (pool 5); at most 15 connections per instance.` | |

`<scheme>` is the URL up to its second `:` (`jdbc:postgresql`). *Amended (interim review I2):* it is `jdbc:` plus the subprotocol (cut at the first character that isn't a letter, digit, `+ - . _`), else `no scheme`, so a URL that isn't JDBC-shaped never prints a credential.

### Messages added in implementation

Collected from the code at Task 15 (`SynapseConfigurationException` unless noted). `<key>` is always a key name, never a value.

| Case | Description | Action |
|---|---|---|
| Key replaces a connection setting (`hikari.*` incl. `hikari.schema` and `hikari.catalog`, `hikari.data-source-properties.*`, `migrations.flyway.*`, `jpa.properties.*`) | `The store '<name>' sets <key>, which would replace its <url\|username\|password\|migrations.locations>.` | `Remove <key> and set synapse.data.stores.<name>.<store key> instead.` |
| SQL on each new connection (`hikari.connection-init-sql`, `migrations.flyway.init-sql`; final review 2, I2, Tony to confirm) | `The store '<name>' sets <key>, SQL that runs on each new connection and could change the role or schema the store uses.` | `Remove <key>. Name the schema in synapse.data.stores.<name>.url.` |
| SQL on each connection check (`hikari.connection-test-query`; final re-review N2, Tony to confirm) | `The store '<name>' sets <key>, SQL that runs on each connection check and could change the role or schema the store uses.` | `Remove <key>. Name the schema in synapse.data.stores.<name>.url.` |
| Credentials in the url | `The store '<name>' has credentials in synapse.data.stores.<name>.url, and drivers and pools log the URL.` | `Remove them from the URL and set synapse.data.stores.<name>.username and synapse.data.stores.<name>.password instead (from environment variables).` |
| Bracketed store key | `The key <key> names its store in brackets, but store keys use the dotted form, so it would be ignored.` | `Write it as <dotted key>.` |
| Primary changed after registration (AOT build, or a later `@PropertySource`) | `The store '<name>' sets synapse.data.stores.<name>.primary differently from when its beans were registered (in AOT mode, at build time).` | `Set synapse.data.stores.<name>.primary in config.yaml or application.yaml, the same in every environment and not in a @PropertySource, and in AOT mode build again.` |
| Migrations without a tool | `The store '<name>' configures migrations, but no migration tool is on the class path.` | `Add org.flywaydb:flyway-core, or set synapse.data.stores.<name>.migrations.enabled: false.` |
| Flyway key twice | `The store '<name>' sets <key A> and <key B>, which are the same Flyway setting.` | `Remove one of them.` |
| Unknown Flyway key | `The store '<name>' sets <key>, which is not a Flyway setting.` | `Write Flyway settings with Flyway's own names, such as baseline-on-migrate, in a config file. Environment variable forms of Flyway keys are not supported.` |
| Flyway rejects a value | `The store '<name>' sets <key> to a value Flyway rejects.` | `Fix the value of <key>. Flyway's message isn't shown, since it may contain the value.` |
| Flyway rejects the settings | `Flyway rejects the migrations.flyway settings of the store '<name>': <key>, <key>.` | `Check these settings against Flyway's documentation. Flyway's message isn't shown, since it may contain a value.` |
| Health timeout not positive | `The store '<name>' sets synapse.data.stores.<name>.health.timeout to a duration that is not positive, so its health check could never pass.` | `Set synapse.data.stores.<name>.health.timeout to a positive duration, or remove it to wait 1s.` |
| Several application beans for a store (naming strategies, `JdbcCustomConversions`) | `The store '<name>' finds more than one <Type> bean: <a>, <b>.` | `Mark one of them @Primary, or define <p><Type> in the store's @SynapseDataStore class.` |
| Vendor URL problem | `The store '<name>' has a url that <problem>.` PostgreSQL problems: `names no host`, `names no database`, `has the parameter <host\|port\|dbname\|PGHOST\|PGPORT\|PGDBNAME>, which would replace the host, port or database it names`, `has the parameter service, which reads where and as whom to connect from a pg_service.conf file` | `Set synapse.data.stores.<name>.url in the form <form>.` (PostgreSQL: `jdbc:postgresql://<host>[:<port>][,<host>[:<port>]...]/<database>[?<parameters>]`), or `Fix synapse.data.stores.<name>.url.` for a vendor without a form |
| Blocked driver property | `The store '<name>' sets synapse.data.stores.<name>.hikari.data-source-properties.<key>, a driver property that would change where, as whom or in which schema the store connects, or that holds a secret.` | `Remove it, name the host, port, database and schema in synapse.data.stores.<name>.url, and set synapse.data.stores.<name>.username and synapse.data.stores.<name>.password.` |
| Forbidden class-path resource | `The class path has <resource> (<location>), which the <vendor> driver reads behind the settings of the store '<name>'.` | `Remove it, and configure the store under synapse.data.stores.<name>.` |
| Own EntityManagerFactory with `update` | `The store '<name>' sets ddl-auto to update, which Synapse runs only on the EntityManagerFactory it builds, and the application defines <p>EntityManagerFactory.` | `Remove the application's <p>EntityManagerFactory bean, or change the schema with a migration in <locations>.` |
| `jpa.generate-ddl` | `The store '<name>' sets synapse.data.stores.<name>.jpa.generate-ddl, which Synapse doesn't use to change the schema.` | `Use synapse.data.stores.<name>.jpa.hibernate.ddl-auto instead.` |
| Global schema key | `Hibernate's global settings (system properties or hibernate.properties) set <key>, which would change the schema of the store '<name>' behind its ddl-auto.` | `Remove <key> from the JVM's system properties and hibernate.properties, and use synapse.data.stores.<name>.jpa.hibernate.ddl-auto instead.` |
| Global connection key | `Hibernate's global settings (system properties or hibernate.properties) set <key>, which would replace the <url\|username\|password> of the store '<name>'.` | `Remove <key> from the JVM's system properties and hibernate.properties, and set synapse.data.stores.<name>.<store key> instead.` |
| Customizer sets a schema key | `A HibernatePropertiesCustomizer sets <key> for the store '<name>', which would change its schema behind its ddl-auto.` | `Remove <key> from the customizer, and use synapse.data.stores.<name>.jpa.hibernate.ddl-auto instead.` |
| Customizer sets a connection key | `A HibernatePropertiesCustomizer sets <key> for the store '<name>', which would replace its <store key>.` | `Remove <key> from the customizer, and set synapse.data.stores.<name>.<store key> instead.` |
| Per-store open-in-view | `The store '<name>' sets synapse.data.stores.<name>.jpa.open-in-view, but open-in-view is not a store setting.` | `Remove it. Boot's spring.jpa.open-in-view applies to a single or primary store, and Synapse turns it off by default.` |
| Per-store bootstrap | `The store '<name>' sets synapse.data.stores.<name>.jpa.bootstrap, but Synapse builds each store's EntityManagerFactory itself, before the store starts.` | `Remove it.` |
| One `JdbcCustomConversions` for several dialects | `The JdbcCustomConversions bean <bean> would apply to JDBC stores whose dialects differ: <store> (<dialect>), <store> (<dialect>).` | `Qualify it with the store it is for, such as @Qualifier("<store>"), or define each store's <prefix>JdbcCustomConversions in its @SynapseDataStore class.` |
| `@SynapseDataTest` with an invalid name | `'<value>' is not a valid store name (@SynapseDataTest("<value>")).` | `Use lowercase letters, digits and dashes, starting with a letter, such as books or legacy-orders.` |
| `@SynapseDataTest` with an unknown store | `@SynapseDataTest("<value>") names a store that no @SynapseDataStore("<value>") declares. Declared stores: <names\|none>. Scanned packages: <packages\|none>.` | `Name one of the declared stores, such as @SynapseDataTest("<first>").`, or with none declared `Declare the store with @SynapseDataStore in the application's packages.` |
| `@StoreConnection` misuse (`IllegalStateException`) | `The store '<name>' has more than one @StoreConnection: <field> and <field>.`; `@StoreConnection <field> must be static.`; `@StoreConnection <field> names '<value>', which is not a valid store name.`; `@StoreConnection <field> must be a org.testcontainers.containers.JdbcDatabaseContainer.` (`<field>` reads `field '<name>' in <class>`) | |
| WARN `spring.jpa.open-in-view` (quiet when `false`) | `spring.jpa.open-in-view is set. Synapse turns open-in-view off by default, since operations are the transaction boundary: with a single store, or a primary one, Boot applies the key to it and can keep an EntityManager open for a whole web request.` | |
| WARN `spring.dao.exceptiontranslation.enabled` | `spring.dao.exceptiontranslation.enabled is set, but Synapse ignores it: each JDBC store's transaction manager is a JdbcTransactionManager, which translates exceptions. To change it, define <prefix>TransactionManager in the store's @SynapseDataStore class.` | |
| WARN `spring.data.jdbc.dialect` | `spring.data.jdbc.dialect is set, but Synapse ignores it: each JDBC store takes its dialect from its vendor. To change a store's dialect, define <prefix>JdbcDialect in its @SynapseDataStore class.` | |
| INFO budget with a store's own DataSource | `Synapse data stores: books (pool 10), legacy (own DataSource); at most 10 connections per instance, not counting legacy.` | |
| Health details (fixed texts) | `UP` with `pool: busy`; `DOWN` with `error`: `The connection failed.`, `The connection is not valid.`, `No connection within <n> ms.`, `The check was interrupted.`, `The check could not start.` | |

## Review Focus

1. **A URL carrying credentials** (`jdbc:postgresql://app:s3cr3t@db/books` or `?password=s3cr3t`). No message, log line or health detail may contain it. Pinned in Task 3: `messagesAndReportsNeverContainTheUrl`.
2. **A dashed store name** (`legacy-orders`). The beans are `legacyOrdersDataSource` and so on, the qualifier is `legacy-orders`, `@Transactional("legacy-orders")` works, and `SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD` binds. Pinned in Task 2 (`dashedStoreNamesWork`) and Task 6 (`dashedStoreTransactions`).
3. **A guard bypass through raw Hibernate keys** (`jpa.properties.hibernate.hbm2ddl.auto: update`). It must fail like `ddl-auto: update`, never reach Hibernate. Pinned in Task 8: `rawSchemaKeysAreRejected`.
4. **A unique or optimistic-lock failure wrapped at commit** (`JpaSystemException`/`TransactionSystemException` around a Hibernate exception, or the raw `jakarta.persistence.OptimisticLockException`). It must still become `ALREADY_EXISTS`/`STALE_VERSION`, not `INTERNAL`. Pinned in Task 4 (`translatesThroughTheCauseChain`) and Task 14 (`duplicateAtCommitIsDuplicate`).
5. **A second store inside the first store's package** (`com.acme.data` and `com.acme.data.legacy`). Startup must fail, not silently give both stores the same entities. Pinned in Task 3: `overlappingStorePackagesFail`.

---

## Library facts this plan relies on (verified against the 4.1.2 / 7.4.13 sources and docs)

- Boot's how-to *Data Access* (4.1.2, https://docs.spring.io/spring-boot/how-to/data-access.html) documents a custom DataSource through `DataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class)` with `@ConfigurationProperties("….configuration")` for Hikari. It documents additional DataSources with `@Bean(defaultCandidate = false)` and `@Qualifier`, so that the auto-configured one stays. It documents multiple EntityManagerFactories with `new EntityManagerFactoryBuilder(vendorAdapter, dataSource -> properties, null)`, `JpaProperties` and `@EnableJpaRepositories(entityManagerFactoryRef = …)`, plus a `JpaTransactionManager` per factory. Package names: `org.springframework.boot.jdbc.DataSourceBuilder`, `org.springframework.boot.jdbc.autoconfigure.DataSourceProperties`, `org.springframework.boot.jpa.EntityManagerFactoryBuilder`, `org.springframework.boot.jpa.autoconfigure.JpaProperties`.
- Boot's how-to *Database Initialization* (https://docs.spring.io/spring-boot/how-to/data-initialization.html): Flyway uses the (`@Primary`) DataSource; default location `classpath:db/migration`. `ddl-auto` defaults to `create-drop` only for an embedded database without Flyway or Liquibase, else `none`. Flyway and EntityManagerFactory beans are detected initializers.
- Back-off conditions (sources):
  - `DataSourceAutoConfiguration` pools: `@ConditionalOnMissingBean({DataSource, XADataSource})`;
  - `JdbcTemplateAutoConfiguration` and `DataSourceInitializationAutoConfiguration`: `@ConditionalOnSingleCandidate(DataSource)`; `JdbcTemplateConfiguration`: `@ConditionalOnMissingBean(JdbcOperations)`; `JdbcClientAutoConfiguration`: `@ConditionalOnMissingBean(JdbcClient)`;
  - `DataSourceTransactionManagerAutoConfiguration`: single candidate plus `@ConditionalOnMissingBean(TransactionManager)`;
  - `HibernateJpaConfiguration`: `@ConditionalOnSingleCandidate(DataSource)`; its EMF is `@ConditionalOnMissingBean({LocalContainerEntityManagerFactoryBean, EntityManagerFactory})`; its TM is `@ConditionalOnMissingBean(TransactionManager)`;
  - `FlywayAutoConfiguration.FlywayConfiguration`: `@ConditionalOnMissingBean(Flyway)`;
  - `DataJpaRepositoriesAutoConfiguration`: `@ConditionalOnMissingBean({JpaRepositoryFactoryBean, JpaRepositoryConfigExtension})` and `spring.data.jpa.repositories.enabled`;
  - `DataJdbcRepositoriesAutoConfiguration`: `spring.data.jdbc.repositories.enabled`; its `SpringBootJdbcConfiguration` resolves a dialect from a connection;
  - `DataSourceHealthContributorAutoConfiguration`: `@ConditionalOnMissingBean(name = {"dbHealthIndicator", "dbHealthContributor"})`.
- `@ConditionalOnMissingBean`/`@ConditionalOnBean` ignore beans that are not default candidates (Javadoc in 4.1.2).
- Boot's `HikariDataSourceMeterBinder` binds every `DataSource` bean (`ObjectProvider.UNFILTERED`), so store pools get `hikaricp.*` metrics with their pool name.
- Boot's `ConnectionDetailsRegistrar` skips registering a `ConnectionDetails` type that already has a bean ("Skipping registration … due to existing beans"), so `@ServiceConnection` yields one `JdbcConnectionDetails` per context.
- Hibernate 7.4.13 `JdbcEnvironmentInitiator`: when `hibernate.boot.allow_jdbc_metadata_access` is `DISALLOW` (`false`) and `hibernate.dialect` is set, it builds the environment from explicit settings, with the version from `jakarta.persistence.database-product-version` (or major/minor), and opens no connection. *Amended in Task 6:* the same holds with `jakarta.persistence.database-product-name` instead of `hibernate.dialect`: the dialect resolver builds the dialect from the name and version, with no connection and no HHH90000025 WARN (verified in the 7.4.13 sources). `PostgreSQLDialect.MINIMUM_VERSION` is 14.
- JPA 3.2 `EntityManagerFactory.getSchemaManager()` → `jakarta.persistence.SchemaManager` with `create(boolean)`, `drop(boolean)`, `validate()` (throws `SchemaValidationException`), `truncate()`. Hibernate's `SchemaManagerImpl` runs them through `SchemaManagementToolCoordinator.process(metadata, serviceRegistry, properties, delayedDropRegistry)` with an `Action`; `Action.UPDATE` goes through the same coordinator.
- Spring ORM 7.0.10 `HibernateJpaVendorAdapter` installs `SpringBeanContainer` when given a `ConfigurableListableBeanFactory`, so Hibernate creates entity listeners as Spring beans. *Corrected (F8):* the adapter gets the bean factory only as a bean (`BeanFactoryAware`), not through `LocalContainerEntityManagerFactoryBean`, so `JpaStoreFactory` calls `adapter.setBeanFactory(...)` per store. `HibernateExceptionTranslator` maps `QueryTimeoutException` to Spring's `QueryTimeoutException` and `JDBCConnectionException` to `DataAccessResourceFailureException`. *Corrected (Task 6):* it maps every Hibernate `ConstraintViolationException`, unique ones included, to `DataIntegrityViolationException`, not `DuplicateKeyException`; `JpaExceptionTranslator` finds the unique ones (`ConstraintKind.UNIQUE`) in the cause chain. A native query through the `EntityManager` throws a plain `PersistenceException` around Hibernate's `QueryTimeoutException`, which Spring never translates (Task 14).
- *Added in Task 14:* Spring 7's `JdbcTemplate` translates with `SQLExceptionSubclassTranslator`, which falls back to SQLState classes unless the application ships its own `sql-error-codes.xml` (spring-jdbc 7.0.10 `JdbcAccessor`, `SQLStateSQLExceptionTranslator`). Class 40 is pessimistic locking, so PostgreSQL's 40001 becomes `CannotAcquireLockException` and 40P01 `PessimisticLockingFailureException`; class 55 is in no set, so 55P03 (lock timeout) becomes `UncategorizedSQLException`. The bundled `sql-error-codes.xml` entries for PostgreSQL are not consulted. On JPA, Hibernate maps 55P03 to `LockTimeoutException`, which reaches the pipeline inside JPA's `PessimisticLockException`.
- Spring Data JDBC 4.1.2 `JdbcConfiguration` has public static `createCustomConversions`, `createMappingContext`, `createConverter`, `createDataAccessStrategy` and `scanForEntities`. `@EnableJdbcRepositories` has `jdbcOperationsRef`, `dataAccessStrategyRef`, `transactionManagerRef` and `jdbcAggregateOperationsRef`. `@EnableJpaRepositories` has `entityManagerFactoryRef` and `transactionManagerRef`.
- Flyway 12.4.0 `FluentConfiguration` has `dataSource(DataSource)`, `locations(String...)`, `configuration(Map<String, String>)` and `load()`; `Flyway.migrate()`.
- Boot 4.1.2 `HealthEndpointGroupsPostProcessor` and `HealthEndpointGroup.isMember(String)` exist in `spring-boot-health`.
- Spring Framework's `DefaultLifecycleProcessor` exits for `-Dspring.context.exit=onRefresh` before starting lifecycle beans, so acceptance 17 checks bean creation only.
- Not verified when the plan was written, so each was a Step 1 check in the owning task. All three are settled:
  - that a `@MappedSuperclass` subclass's `@PrePersist` default listener from a mapping resource reaches entities that don't declare it (Task 10). *Verified* in Task 10 by test: the listener reaches an entity that declares nothing, built by Spring with the context's `MutableClock` (`JpaAuditingIT.theCallbacksUseTheContextsClock`);
  - that `HibernateJpaVendorAdapter` gets the bean factory through `LocalContainerEntityManagerFactoryBean` when built by `EntityManagerFactoryBuilder` (Task 10). *Refuted* by the pre-flight scan of the Spring ORM 7.0.10 sources (F8): it doesn't, so `JpaStoreFactory` calls `adapter.setBeanFactory(...)` (see the corrected fact above);
  - that `HealthEndpointGroup.isMember("db/books")` is called with that path form (Task 12). *Verified* by the pre-flight scan of the Boot 4.1.2 sources (`HealthEndpointSupport.getAggregateDescriptor` builds `prefix + "/" + child`) and pinned by `StoreReadinessIT`.

## Carried in (parked items that BUILD and the ledgers assign to plan 4)

- From the P1 core ledger (`2026-11-01-p1-core/task-15-brief.md`, *Covered by later P1 plans*): "Store readiness opt-in (§5.10), `@SynapseDataTest`, Spring Data translators, transactions with a real store | 4 (data)". These are Tasks 12, 13, 4/14 and 6/14.
- From the P1 core ledger (`global-constraints.md`): `SynapseLifecyclePhases.DATA_STORES = DEFAULT_PHASE - 10240`, so stores start first and stop last. Task 5 uses it.
- From the P1 core ledger (Task 13 report): the ArchUnit stand-in `io.americanexpress.synapse.data.archfixture.DataCapability` (test sources of `synapse-test`) marks Synapse data types by package. The real capability types (Task 1) live in `io.americanexpress.synapse.data`, so the existing rule and the REST `BindingValidator` check apply to them unchanged. Task 1 adds a test that pins this.
- From plan 2b (`task-13-brief.md` and `rest.adoc` L605): "JPA's `@Version` → `STALE_VERSION` translator arrives in plan 4". Task 4 builds it; Task 15 updates `rest.adoc`.
- From the environments plan (*Covered by later plans*): the name `allow-ddl-auto` (confirmed here: `synapse.data.stores.<name>.allow-ddl-auto`); store `url`/`username`/`password` without defaults (Task 2); data modules' `config*.yaml` (Task 14's fixture data module ships `META-INF/synapse/config.yaml` and `config-test.yaml`; Task 15 documents it); `environments.adoc` L273 promises the `ddl-auto` switch (Task 15).
- Parked items that are **not** plan 4's: the payload-logger burst-filter choice (M4, core), the REST cross-parameter field name and pipeline-proxy check (2a), the R46 Tomcat ERROR line (2a), the R24 type-walk memoization (2b), the PATCH revisit (2c/P4).

---

## File structure

```
data/synapse-data-core/      pom.xml
  src/main/java/io/americanexpress/synapse/data/
    Identifiable, Versioned, Auditable, EntityIdGenerator, SynapseDataStore, DataAccess, package-info
    vendor/                  DataStoreVendor, package-info
    autoconfigure/           SynapseDataAutoConfiguration, SynapseDataHealthAutoConfiguration, SynapseDataProperties,
                             SynapseDataStoreProperties, DataDefaultsEnvironmentPostProcessor, package-info
    internal/store/          StoreRegistrar, StoreDefinition, StoreNames, StoreDefinitionsPostProcessor, StoreFactory,
                             StoreConnection, StoreInfrastructure, StoreLifecycle, StoreStartStep, DataStartupReport,
                             UnqualifiedTransactionCheck, StoreRepositoryConfigurationSource, package-info
    internal/migration/      StoreMigration, FlywayStoreMigration, package-info
    internal/id/             UuidV7Generator, package-info
    internal/error/          DataExceptionTranslator, package-info
    internal/health/         StoreHealthIndicator, StoreReadinessGroups, package-info
    internal/aot/            SynapseDataRuntimeHints, package-info
  src/main/resources/META-INF/  spring.factories, spring/…AutoConfiguration.imports, spring/aot.factories,
                             additional-spring-configuration-metadata.json
data/synapse-data-jpa/       …/data/jpa/ IdEntity, VersionedEntity, AuditedEntity, LongIdEntity, LongVersionedEntity,
                             LongAuditedEntity, EntityCallbacks (package-private), synapse-orm.xml (resource)
                             …/data/jpa/autoconfigure/ SynapseDataJpaAutoConfiguration, JpaDefaultsEnvironmentPostProcessor
                             …/data/jpa/internal/ JpaStoreInfrastructure, JpaStoreFactory, JpaSchemaAction,
                             MetadataKeeper, JpaExceptionTranslator, JpaRuntimeHints
data/synapse-data-jdbc/      …/data/jdbc/autoconfigure/ SynapseDataJdbcAutoConfiguration, JdbcDefaultsEnvironmentPostProcessor
                             …/data/jdbc/internal/ JdbcStoreInfrastructure, JdbcStoreFactory, JdbcAuditingCallback,
                             JdbcRuntimeHints
data/synapse-data-postgres/  …/data/postgres/ PostgresVendor; …/data/postgres/autoconfigure/ SynapsePostgresAutoConfiguration
data/synapse-test-data/      …/test/data/ SynapseDataTest, AutoConfigureSynapseData, StoreConnection, SynapseContainers,
                             SynapseDataTypeExcludeFilter, StoreConnectionContextCustomizerFactory,
                             SynapseDataTestAutoConfiguration
core/synapse-core/           error/SynapseErrors (ALREADY_EXISTS)
pom.xml, bom/synapse-bom/pom.xml, parent/synapse-parent/pom.xml (only if it lists Synapse artifacts), AGENTS.md
docs/modules/ROOT/           nav.adoc, pages/data.adoc (new), testing.adoc, environments.adoc, rest.adoc, core.adoc
```

---

### Task 1: `synapse-data-core`, capabilities, UUIDv7 and `ALREADY_EXISTS`

**Files:**
- Create:
  - `data/synapse-data-core/pom.xml`. Dependencies: `synapse-core`, `spring-boot-jdbc`, `com.zaxxer:HikariCP`, `spring-data-commons`, `spring-boot-transaction`, `spring-tx`, `flyway-core`, `jspecify`, `slf4j-api`; optional `spring-boot-health`. Test scope: `synapse-test`, `spring-boot-starter-test`, `com.h2database:h2`.
  - `.../data/{Identifiable,Versioned,Auditable,EntityIdGenerator,package-info}.java`;
  - `.../data/internal/id/{UuidV7Generator,package-info}.java`;
  - `src/test/resources/application-test.yaml`.
- Modify:
  - `pom.xml` (module `data/synapse-data-core`; `dependencyManagement` entries for all five new artifacts, added as their modules appear);
  - `bom/synapse-bom/pom.xml` (the five artifacts; `synapse-test-data` as a test-support artifact like `synapse-test-rest`);
  - `core/synapse-core/.../error/SynapseErrors.java` (`ALREADY_EXISTS` after `STALE_VERSION`);
  - `AGENTS.md` layout table (`| data/ | Data modules and their test slice (synapse-data-core, -jpa, -jdbc, -postgres, synapse-test-data) |`).
- Test: `.../data/internal/id/UuidV7GeneratorTest.java`; `core/.../error/SynapseErrorsTest.java` (extend); `core/synapse-test/.../architecture/SynapseArchitectureTest.java` (one case; fixture under `com.example.arch.bad.datatype`).

**Interfaces:**
- Produces:

```java
public interface Identifiable<ID> { @Nullable ID getId(); }
public interface Versioned { @Nullable Long getVersion(); }
public interface Auditable {
    @Nullable Instant getCreatedAt(); @Nullable String getCreatedBy();
    @Nullable Instant getModifiedAt(); @Nullable String getModifiedBy();
    void setCreated(Instant at, String by);
    void setModified(Instant at, String by);
}
@FunctionalInterface public interface EntityIdGenerator { UUID nextId(); }
public final class UuidV7Generator implements EntityIdGenerator {   // internal.id
    public UuidV7Generator(Clock clock);                              // new SecureRandom()
    UuidV7Generator(Clock clock, RandomGenerator random);             // tests
}
// SynapseErrors
ALREADY_EXISTS("The resource already exists", ErrorCategory.CONFLICT),
```

Algorithm (RFC 9562 §6.2 method 1; `nextId` is `synchronized`):

```
now = clock.millis()
if now > lastMillis: lastMillis = now; counter = random.nextInt(0x800)        // 11 random bits: room to count
else: counter += 1; if counter > 0xFFF: lastMillis += 1; counter = random.nextInt(0x800)   // also when the clock goes back
msb = (lastMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000 | counter
lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L
```

- [ ] **Step 1: Write the failing tests**

```java
// UuidV7GeneratorTest (MutableClock at 2026-01-01T00:00:00Z; a seeded SplittableRandom)
@Test void versionAndVariantBits()                // uuid.version() == 7, uuid.variant() == 2
@Test void timestampIsTheClockMillis()            // uuid.getMostSignificantBits() >>> 16 == clock.millis()
@Test void monotonicWithinAMillisecond()          // 4096 ids at one instant: strictly increasing (compare as unsigned msb, then lsb)
@Test void counterOverflowMovesToTheNextMillisecond()  // random always 0x7FF: the 2050th id has timestamp clock.millis() + 1
@Test void clockGoingBackStaysMonotonic()          // id at t, clock set to t - 5 ms, next id > previous
@Test void newMillisecondRestartsTheCounter()      // advance 1 ms: timestamp +1, counter == the seeded draw
// SynapseErrorsTest
@Test void duplicateIsAConflict()                  // ALREADY_EXISTS.category() == CONFLICT, code "ALREADY_EXISTS", template as above
// SynapseArchitectureTest
@Test void synapseDataCapabilitiesMarkEntities()   // an operation returning a record that implements io.americanexpress.synapse.data.Identifiable is reported by NO_ENTITIES_IN_API (synapse-test gets a test-scope dependency on synapse-data-core)
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest=UuidV7GeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors.
- [ ] **Step 3: Implement** the module, the types and the code. The REST error-status tests still pass: `ALREADY_EXISTS` maps through its category (409). Check that `docs`' error table generator, if any, picks up the new code (Task 15 documents it).
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl core/synapse-core,core/synapse-test,data/synapse-data-core -am verify`.
- [ ] **Step 5: Commit**: `feat(data): add synapse-data-core with capability interfaces, UUIDv7 ids and ALREADY_EXISTS`.

### Task 2: Declaring a store builds its DataSource and JDBC templates

**Files:**
- Create:
  - `.../data/{SynapseDataStore,DataAccess}.java`;
  - `.../data/vendor/{DataStoreVendor,package-info}.java`;
  - `.../data/autoconfigure/{SynapseDataAutoConfiguration,SynapseDataProperties,SynapseDataStoreProperties,DataDefaultsEnvironmentPostProcessor,package-info}.java`;
  - `.../data/internal/store/{StoreRegistrar,StoreDefinition,StoreNames,StoreDefinitionsPostProcessor,StoreFactory,StoreConnection,StoreInfrastructure,package-info}.java`;
  - `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `META-INF/spring.factories` (the EPP), `META-INF/additional-spring-configuration-metadata.json` (every `synapse.data.stores.*.…` key with its description and default).
- Test: `.../data/autoconfigure/SynapseDataAutoConfigurationTest.java`, `DataDefaultsEnvironmentPostProcessorTest.java`; `.../data/internal/store/StoreNamesTest.java`; test fixtures `H2TestVendor`, `fixture/books/BooksStore`, `fixture/legacy/LegacyStore` (H2 `jdbc:h2:mem:<name>;DB_CLOSE_DELAY=-1`).

**Interfaces:**
- Produces:

```java
@Target(TYPE) @Retention(RUNTIME) @Documented @Import(StoreRegistrar.class)
public @interface SynapseDataStore { String value(); DataAccess access() default DataAccess.AUTO; }
public enum DataAccess { AUTO, JPA, JDBC }
@Incubating public interface DataStoreVendor {
    String name(); boolean supports(String jdbcUrl); String defaultDatabaseVersion();
    String hibernateDialect(); String jdbcDialect();       // class names; the JDBC dialect has a public static INSTANCE
}
@ConfigurationProperties("synapse.data")
public record SynapseDataProperties(@DefaultValue Auditing auditing) {
    public record Auditing(@DefaultValue("system") String systemUser) {}
}
public record SynapseDataStoreProperties(                 // bound per store by StoreFactory, never a bean
    @Nullable String url, @Nullable String username, @Nullable String password, @Nullable String driverClassName,
    @DefaultValue("false") boolean primary, @Nullable String databaseVersion,
    @DefaultValue("false") boolean allowDdlAuto,
    @DefaultValue Migrations migrations, @DefaultValue Health health) {
    public record Migrations(@DefaultValue("true") boolean enabled, @Nullable List<String> locations,
                             @DefaultValue Map<String, String> flyway) {}
    public record Health(@DefaultValue("false") boolean readiness, @DefaultValue("1s") Duration timeout) {}
    public static SynapseDataStoreProperties bind(Environment environment, String store);
}
public record StoreDefinition(String name, String beanPrefix, String basePackage, DataAccess access,
                              boolean primary, String declaredBy) {
    public String beanName(String suffix);                // beanPrefix + suffix, e.g. "booksDataSource"
}
public final class StoreNames {
    public static boolean isValid(String name); public static String beanPrefix(String name);
    public static String environmentVariable(String name, String key); // ("legacy-orders","password") → SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD
}
public interface StoreInfrastructure {                    // internal SPI, listed in spring.factories
    DataAccess access();
    void register(StoreDefinition store, BeanDefinitionRegistry registry, Environment environment, ResourceLoader resourceLoader);
}
public final class StoreFactory {                         // bean "synapseDataStoreFactory"
    public HikariDataSource dataSource(String store);
    public JdbcTemplate jdbcTemplate(String store); public NamedParameterJdbcTemplate namedParameterJdbcTemplate(String store);
    public JdbcClient jdbcClient(String store);
    public DataStoreVendor vendor(String store);          // used by later tasks
    public SynapseDataStoreProperties properties(String store);
    public StoreDefinition definition(String store);
}
record StoreConnection(String url, String username, String password, @Nullable String driverClassName) {}
```

- `StoreRegistrar` (`ImportBeanDefinitionRegistrar`, `EnvironmentAware`, `ResourceLoaderAware`):
  1. validates the name;
  2. resolves the access (`StoreInfrastructure` implementations from `SpringFactoriesLoader`);
  3. registers a `StoreDefinition` bean definition `<p>StoreDefinition` (instance-free: bean class `StoreDefinition`, constructor arguments). `StoreFactory` and the checks read it;
  4. registers the data-core beans of *Defaults* #3, skipping a name the registry already has. Each gets the qualifier, `primary` from the store's own property and `setTargetType`;
  5. calls the access's `StoreInfrastructure.register`.
- `StoreDefinitionsPostProcessor` (registered by the registrar once, as `synapseStoreDefinitionsPostProcessor`): the single-store primary rule (*Defaults* #4). Task 3 adds the checks.
- `StoreFactory.dataSource`: connection from a qualified `JdbcConnectionDetails` or the properties (*Defaults* #6); `DataSourceProperties` (class loader, url, username, password, driver class, name = store; `afterPropertiesSet()`); `initializeDataSourceBuilder().type(HikariDataSource.class).build()`; `poolName` = store; then bind `<prefix>.hikari` onto it with `Binder` and `Bindable.ofInstance`.
- `SynapseDataAutoConfiguration`: `@AutoConfiguration`, `@ConditionalOnClass(HikariDataSource.class)`, `@EnableConfigurationProperties(SynapseDataProperties.class)`. Bean `synapseDataStoreFactory` (`@ConditionalOnMissingBean`) with `ObjectProvider<DataStoreVendor>` and `ObjectProvider<JdbcConnectionDetails>`.
- `DataDefaultsEnvironmentPostProcessor`: `spring.liquibase.enabled=false` at lowest precedence (*Defaults* #11).

- [ ] **Step 1: Write the failing tests**

```java
// SynapseDataAutoConfigurationTest: ApplicationContextRunner with SynapseDataAutoConfiguration and Boot's
// DataSourceAutoConfiguration, JdbcTemplateAutoConfiguration, JdbcClientAutoConfiguration,
// DataSourceTransactionManagerAutoConfiguration, DataSourceInitializationAutoConfiguration, TransactionAutoConfiguration;
// user config H2TestVendor + BooksStore; properties synapse.data.stores.books.{url,username,password}
@Test void aSingleStoreGetsItsBeans()          // beans booksDataSource (HikariDataSource, pool name "books"), booksJdbcTemplate,
                                               // booksNamedParameterJdbcTemplate, booksJdbcClient; all primary; qualifier "books"
@Test void bootsSingleDataSourceBacksOff()     // no bean "dataSource"; getBeansOfType(DataSource).keySet() == {booksDataSource};
                                               // no "jdbcTemplate", no "jdbcClient" bean
@Test void twoStoresWithoutPrimary()           // Books + Legacy: neither primary; getBean(DataSource.class) throws NoUniqueBeanDefinitionException;
                                               // @Qualifier("legacy") injection point gets legacyDataSource
@Test void primaryStoreIsPrimary()             // legacy.primary=true → getBean(DataSource.class) == legacyDataSource
@Test void bindsHikariSettings()               // books.hikari.maximum-pool-size=7, books.hikari.connection-timeout=2s → 7, 2000
@Test void hikariPoolNameCanBeOverridden()     // books.hikari.pool-name=books-main → "books-main"
@Test void qualifiedConnectionDetailsWin()     // a JdbcConnectionDetails bean @Qualifier("books") with another H2 url; no books.url set → that url
@Test void aBeanInTheStoreClassReplacesSynapses() // BooksStoreWithOwnDataSource defines @Bean booksDataSource → that instance, no failure
@Test void noStoreNoBeans()                    // no @SynapseDataStore class → no DataSource from Synapse; synapseDataStoreFactory exists
@Test void absentWithoutHikari()               // FilteredClassLoader(HikariDataSource.class) → no synapseDataStoreFactory
@Test void dashedStoreNamesWork()              // Review Focus 2: @SynapseDataStore("legacy-orders") → legacyOrdersDataSource, qualifier "legacy-orders";
                                               // env SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD (a SystemEnvironmentPropertySource) → password bound
@Test void dataSourceCreationOpensNoConnection() // url jdbc:h2:tcp://127.0.0.1:1/none (nothing listens): the context starts; pool not started
                                               // (HikariDataSource.getHikariPoolMXBean() == null)
// StoreNamesTest
@ParameterizedTest void validNames()           // books, legacy-orders, b2 → valid; Books, 2b, legacy_orders, -x, x-, a--b → invalid
@Test void beanPrefixIsLowerCamel()            // legacy-orders → legacyOrders
// DataDefaultsEnvironmentPostProcessorTest
@Test void liquibaseIsOffAtLowestPrecedence()  // defaultProperties has spring.liquibase.enabled=false; an application property true wins
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest='SynapseDataAutoConfigurationTest,StoreNamesTest,DataDefaultsEnvironmentPostProcessorTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: compilation errors.
- [ ] **Step 3: Implement** as described under *Interfaces*. For this task, a test-only `StoreInfrastructure` for `JDBC` registers nothing, so AUTO resolves. The JDBC module (Task 11) replaces it.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core -am verify`.
- [ ] **Step 5: Commit**: `feat(data): build named stores' DataSources and JDBC templates with Boot's builders`.

### Task 3: Store checks and the startup report

**Files:**
- Create: `.../data/internal/store/DataStartupReport.java`.
- Modify: `.../internal/store/{StoreRegistrar,StoreDefinitionsPostProcessor,StoreFactory}.java`, `.../autoconfigure/SynapseDataAutoConfiguration.java` (the report bean).
- Test: `.../internal/store/StoreChecksTest.java`, `DataStartupReportTest.java`.

**Interfaces:**
- Consumes: Task 2.
- Produces: the *Messages* rows *Invalid name*, *Declared twice*, *Packages overlap*, *Undeclared*, *Two primaries*, *Access ambiguous*, *Access missing*, *No key*, *No vendor*, WARN and INFO. `DataStartupReport implements ApplicationListener<ApplicationStartedEvent>` (*Defaults* #21).
  - The registrar raises *Invalid name* and the access messages.
  - The post-processor raises *Declared twice* (it keeps the first `declaredBy` per name), *Packages overlap*, *Undeclared* (configured names from `Binder` over `synapse.data.stores` as a `Map<String, Object>`, skipped per *Defaults* #18) and *Two primaries*.
  - `StoreFactory` raises *No key* and *No vendor* (at bean creation; no I/O).

- [ ] **Step 1: Write the failing tests**

```java
// StoreChecksTest (context runner; assert the startup failure's root cause is SynapseConfigurationException
// with exactly the description and action from *Messages*)
@Test void invalidNameFails()                  // @SynapseDataStore("Books")
@Test void aStoreDeclaredTwiceFails()          // two classes with "books"
@Test void overlappingStorePackagesFail()      // Review Focus 5: fixture.books.BooksStore + fixture.books.legacy.NestedLegacyStore
@Test void sameNamePrefixIsNotOverlap()        // packages fixture.book and fixture.books → starts
@Test void configuredButUndeclaredFails()      // synapse.data.stores.bokks.url set, only BooksStore declared
@Test void sliceSkipsTheUndeclaredCheck()      // with bean definition "synapseDataTestSlice" → starts
@Test void twoPrimariesFail()
@Test void missingUrlFails()                   // and missingUsernameFails, missingPasswordFails (env variable name in the action)
@Test void emptyPasswordIsAllowed()            // password: "" → starts
@Test void unsupportedVendorFails()            // url jdbc:sqlserver://h;databaseName=x → description ends "(jdbc:sqlserver)."
@Test void jdbcAccessWithoutTheModuleFails()   // access = JDBC, no JDBC StoreInfrastructure → *Access missing*
@Test void messagesAndReportsNeverContainTheUrl()   // Review Focus 1: url "jdbc:sqlserver://app:s3cr3t@db/books?password=s3cr3t"
                                               // → the message contains neither "s3cr3t" nor "db/books"; same for the report's lines
// DataStartupReportTest (OutputCaptureExtension; context runner publishing ApplicationStartedEvent)
@Test void logsTheConnectionBudget()           // books max 10, legacy max 5 → INFO exactly "Synapse data stores: books (pool 10), legacy (pool 5); at most 15 connections per instance."
@Test void warnsAboutSpringDatasourceKeys()    // spring.datasource.url and .username set → one WARN "spring.datasource.* is set (spring.datasource.url, spring.datasource.username), but …"; no value printed
@Test void warnsAboutSpringFlywayKeys()
@Test void noStoresNoReport()
```

- [ ] **Step 2: Run to verify failure:** `./mvnw -q -pl data/synapse-data-core -am test -Dtest='StoreChecksTest,DataStartupReportTest' -Dsurefire.failIfNoSpecifiedTests=false`. Expected: the new tests fail.
- [ ] **Step 3: Implement** the checks and the report.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core -am verify`.
- [ ] **Step 5: Commit**: `feat(data): check store declarations and report the connection budget`.

### Task 4: Data error translation

**Files:**
- Create: `.../data/internal/error/{DataExceptionTranslator,package-info}.java`.
- Modify: `.../autoconfigure/SynapseDataAutoConfiguration.java` (bean `synapseDataExceptionTranslator`, `@ConditionalOnMissingBean(name = …)`).
- Test: `.../internal/error/DataExceptionTranslatorTest.java`, `SynapseDataAutoConfigurationTest` (bean present and backs off), and `.../internal/error/DataTranslationIT.java` (`@Tag("smoke")`; H2, a real pipeline from `SynapseOperations.invoke`).

**Interfaces:**
- Produces: `public final class DataExceptionTranslator implements ExceptionTranslator`. It walks the cause chain to depth 10. First match: `OptimisticLockingFailureException` → `STALE_VERSION`; `DuplicateKeyException` → `ALREADY_EXISTS`; `org.springframework.dao.QueryTimeoutException` → `DEADLINE_EXCEEDED`; `DataAccessResourceFailureException`, `TransientDataAccessResourceException`, `CannotCreateTransactionException` → `UNAVAILABLE`; else `null` (the pipeline's `INTERNAL`). The original exception is the `SynapseException` cause.

- [ ] **Step 1: Write the failing tests**

```java
// DataExceptionTranslatorTest
@ParameterizedTest void mapsSpringDataAccessExceptions()   // ObjectOptimisticLockingFailureException → STALE_VERSION; DuplicateKeyException → ALREADY_EXISTS;
                                                           // QueryTimeoutException → DEADLINE_EXCEEDED; CannotGetJdbcConnectionException → UNAVAILABLE;
                                                           // CannotCreateTransactionException → UNAVAILABLE
@Test void translatesThroughTheCauseChain()                // Review Focus 4: TransactionSystemException(cause DuplicateKeyException) → ALREADY_EXISTS
@Test void otherDataAccessFailuresAreLeftToThePipeline()   // BadSqlGrammarException → null; IllegalStateException → null
@Test void keepsTheCause()                                 // translated.getCause() is the thrown exception
@Test void stopsAtDepthTen()                               // a DuplicateKeyException 11 causes deep → null
// DataTranslationIT (H2 store with a unique column; an operation doing two inserts through booksJdbcClient)
@Test void duplicateInsertIsDuplicateThroughThePipeline()  // assertThatSynapseException(...).hasCode("ALREADY_EXISTS").hasCategory(CONFLICT)
@Test void unreachableStoreIsUnavailable()                 // store url jdbc:h2:tcp://127.0.0.1:1/none, hikari.connection-timeout=250ms → UNAVAILABLE
```

- [ ] **Step 2: Run to verify failure** (unit test, then IT). Expected: compilation errors.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core -am verify`.
- [ ] **Step 5: Commit**: `feat(data): translate data access failures into Synapse errors`.

### Task 5: Migrations per store in `SmartLifecycle.start()`

**Files:**
- Create: `.../data/internal/migration/{StoreMigration,FlywayStoreMigration,package-info}.java`; `.../internal/store/{StoreLifecycle,StoreStartStep}.java`.
- Modify: `.../internal/store/{StoreRegistrar,StoreFactory}.java` (`<p>Flyway` and `<p>StoreLifecycle` definitions, Flyway only when `org.flywaydb.core.Flyway` is present).
- Test: `.../internal/migration/FlywayStoreMigrationTest.java`, `.../internal/store/StoreLifecycleTest.java`, `.../internal/store/StoreMigrationsIT.java` (H2; `src/test/resources/db/migration/books/V1__books.sql`, `db/migration/legacy/V1__legacy.sql`, `db/migration/other/V1__other.sql`).

**Interfaces:**
- Produces:

```java
public interface StoreMigration { String store(); void migrate(); }                 // internal
public final class FlywayStoreMigration implements StoreMigration {
    public static Flyway flyway(StoreDefinition store, DataSource dataSource, SynapseDataStoreProperties properties, ClassLoader classLoader);
    // locations default classpath:db/migration/<name>; migrations.flyway entries → "flyway." + camelCase(key)
}
public interface StoreStartStep { String store(); void start(); default void stop() {} }   // internal; JPA adds one (Task 8)
public final class StoreLifecycle implements SmartLifecycle {                       // phase SynapseLifecyclePhases.DATA_STORES
    public StoreLifecycle(StoreDefinition store, @Nullable Flyway flyway, boolean migrationsEnabled, ObjectProvider<StoreStartStep> steps);
    // start(): flyway.migrate() when enabled and present, then each step whose store() matches, in order; stop(): steps' stop() in reverse
}
// StoreFactory
public Flyway flyway(String store); public StoreLifecycle lifecycle(String store);
```

- [ ] **Step 1: Write the failing tests**

```java
// FlywayStoreMigrationTest
@Test void defaultLocationIsTheStoresFolder()       // configuration.getLocations() == [classpath:db/migration/books]
@Test void configuredLocationsWin()
@Test void flywayKeysPassThrough()                   // migrations.flyway.baseline-on-migrate=true → isBaselineOnMigrate(); table=books_history → getTable()
@Test void loadingOpensNoConnection()                // unreachable H2 tcp url: Flyway built without exception
// StoreLifecycleTest
@Test void phaseIsDataStores()                       // getPhase() == SynapseLifecyclePhases.DATA_STORES; isAutoStartup()
@Test void migratesThenRunsTheStoresSteps()          // recording steps for books and legacy → books lifecycle runs migrate, then books's step only
@Test void disabledMigrationsSkipMigrate()
@Test void stopRunsStepsInReverse()
// StoreMigrationsIT (@Tag("smoke"); @SpringBootTest with Books + Legacy on two H2 databases)
@Test void eachStoreRunsItsOwnMigrations()           // books has table book and flyway_schema_history with V1; legacy has table legacy_item; neither has "other"
@Test void bootsFlywayBacksOff()                     // with FlywayAutoConfiguration in the context: getBeansOfType(Flyway).keySet() == {booksFlyway, legacyFlyway}; no flywayInitializer
@Test void aFailingMigrationFailsStartup()           // db/migration/broken/V1__broken.sql with invalid SQL → refresh fails; message names the store's migration
@Test void storesStartBeforeInboundPhases()          // a recording SmartLifecycle in SynapseLifecyclePhases.INBOUND starts after both stores migrated
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core -am verify`.
- [ ] **Step 5: Commit**: `feat(data): run Flyway migrations per store before inbound plugins start`.

### Task 6: `synapse-data-jpa`: an EntityManagerFactory and transaction manager per store

**Files:**
- Create:
  - `data/synapse-data-jpa/pom.xml`. Dependencies: `synapse-data-core`, `spring-boot-hibernate`, `spring-data-jpa`. Test: `synapse-test`, `spring-boot-starter-test`, `h2`.
  - `.../data/jpa/autoconfigure/{SynapseDataJpaAutoConfiguration,JpaDefaultsEnvironmentPostProcessor,package-info}.java`;
  - `.../data/jpa/internal/{JpaStoreInfrastructure,JpaStoreFactory,package-info}.java`;
  - `META-INF/spring.factories` (`StoreInfrastructure`, the EPP), the imports file;
  - `src/test/resources/application-test.yaml`.
- Modify: `pom.xml` (module).
- Test: `.../jpa/autoconfigure/SynapseDataJpaAutoConfigurationTest.java`, `JpaDefaultsEnvironmentPostProcessorTest.java`; `.../jpa/internal/JpaTransactionsIT.java`; fixtures `fixture/books/{BooksStore,Book}` (a plain `@Entity`) and `fixture/legacy/{LegacyStore,LegacyItem}`, H2.

**Interfaces:**
- Consumes: `StoreInfrastructure`, `StoreDefinition`, `StoreFactory` (Task 2).
- Produces:

```java
public final class JpaStoreFactory {        // bean "synapseJpaStoreFactory"
    public PersistenceManagedTypes managedTypes(String store);                  // PersistenceManagedTypesScanner over the store's package
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(String store);
    public JpaTransactionManager transactionManager(String store);              // + TransactionManagerCustomizers if present
    public Map<String, Object> vendorProperties(String store);                  // what Hibernate receives (tests and Task 8)
}
```

  - `entityManagerFactory`: *Defaults* #8; `HibernateJpaVendorAdapter` with `showSql`, `generateDdl=false`, `databasePlatform` from `JpaProperties`. Build with `new EntityManagerFactoryBuilder(adapter, ds -> vendorProperties, null)` → `.dataSource(<p>DataSource).managedTypes(<p>PersistenceManagedTypes).persistenceUnit(name).build()`. Task 10 adds the mapping resource.
  - From this task on, Hibernate always receives `hibernate.hbm2ddl.auto=none`, so it never touches the schema while the factory is built. Task 8 adds the guard and runs the action in `start()`.
  - `JpaStoreInfrastructure.register` adds the `<p>PersistenceManagedTypes`, `<p>EntityManagerFactory` (target type `LocalContainerEntityManagerFactoryBean`) and `<p>TransactionManager` definitions.
  - `JpaDefaultsEnvironmentPostProcessor`: `spring.jpa.open-in-view=false` and `spring.data.jpa.repositories.enabled=false` at lowest precedence.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseDataJpaAutoConfigurationTest (runner adds HibernateJpaAutoConfiguration, DataJpaRepositoriesAutoConfiguration, TransactionAutoConfiguration)
@Test void aStoreGetsAnEntityManagerFactoryAndTransactionManager()  // booksEntityManagerFactory (unit "books", managed class Book), booksTransactionManager (JpaTransactionManager), primary
@Test void bootsJpaBacksOff()                       // no "entityManagerFactory", no "transactionManager" bean
@Test void hibernateNeverReadsMetadata()            // vendorProperties: hibernate.boot.allow_jdbc_metadata_access=false, hibernate.dialect=H2Dialect,
                                                    // jakarta.persistence.database-product-version=2.4
@Test void databaseVersionIsConfigurable()          // books.database-version=2.3.232 → that value
@Test void bindsJpaAndHibernateProperties()         // books.jpa.show-sql=true, books.jpa.properties.hibernate.jdbc.batch_size=20,
                                                    // books.jpa.hibernate.naming.physical-strategy=…CamelCaseToUnderscores… → present in vendorProperties
@Test void transactionManagerCustomizersApply()     // spring.transaction.default-timeout=7s → booksTransactionManager.getDefaultTimeout() == 7
@Test void startsWithTheDatabaseUnreachable()       // unreachable H2 tcp url: the context refreshes (EMF built) — acceptance 17 at the JPA level
@Test void absentWithoutHibernate()                 // FilteredClassLoader(SessionImplementor) → no synapseJpaStoreFactory
// JpaDefaultsEnvironmentPostProcessorTest
@Test void openInViewAndBootRepositoriesOffAtLowestPrecedence()
// JpaTransactionsIT (@Tag("smoke"); Books + Legacy on H2, no primary; schema from migrations db/migration/books and db/migration/legacy)
@Test void transactionalWithAStoreNameUsesThatStore()  // a @Transactional("legacy") bean method saves through the legacy EntityManager (@PersistenceContext(unitName="legacy")); the row is in legacy only
@Test void rollbackOnException()                     // @Transactional("books") method that persists then throws → no row
@Test void dashedStoreTransactions()                 // Review Focus 2: store "legacy-orders", @Transactional("legacy-orders") commits
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.** Order `SynapseDataJpaAutoConfiguration` `before = HibernateJpaAutoConfiguration`.
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-jpa -am verify`.
- [ ] **Step 5: Commit**: `feat(data): build a JPA EntityManagerFactory and transaction manager per store`.

### Task 7: JPA repositories per store

**Files:**
- Create: `data/synapse-data-core/.../internal/store/StoreRepositoryConfigurationSource.java`.
- Modify: `.../jpa/internal/JpaStoreInfrastructure.java`.
- Test: `.../jpa/internal/JpaRepositoriesTest.java` (runner), `.../jpa/internal/JpaRepositoriesIT.java`; fixtures `BookRepository extends JpaRepository<Book, Long>`, `LegacyItemRepository`, and a custom fragment `BookRepositoryCustom`/`BookRepositoryCustomImpl`.

**Interfaces:**
- Produces:

```java
public final class StoreRepositoryConfigurationSource extends RepositoryConfigurationSourceSupport {
    public StoreRepositoryConfigurationSource(StoreDefinition store, Map<String, String> references,
            Environment environment, ResourceLoader resourceLoader, BeanDefinitionRegistry registry);
    // base package = store.basePackage(); getAttribute(name) answers from `references`
    // (e.g. entityManagerFactoryRef=booksEntityManagerFactory, transactionManagerRef=booksTransactionManager);
    // defaults otherwise as @EnableJpaRepositories/@EnableJdbcRepositories declare them
}
```

  `JpaStoreInfrastructure.register` runs `new RepositoryConfigurationDelegate(source, resourceLoader, environment).registerRepositoriesIn(registry, new JpaRepositoryConfigExtension())`.

- [ ] **Step 1: Write the failing tests**

```java
// JpaRepositoriesTest
@Test void repositoriesBindToTheirStore()             // bookRepository's factory bean has entityManager from booksEntityManagerFactory and TM booksTransactionManager
@Test void bootsRepositoriesBackOff()                 // DataJpaRepositoriesAutoConfiguration in the runner, spring.data.jpa.repositories.enabled=true explicitly:
                                                      // still one bookRepository bean (no duplicate registered by Boot)
@Test void aStoreWithoutRepositoriesStarts()          // LegacyStore with no repository interfaces → starts
@Test void customFragmentsWork()                      // bookRepository.customMethod() reaches BookRepositoryCustomImpl
// JpaRepositoriesIT (two H2 stores)
@Test void saveAndFindPerStore()                      // bookRepository.save in books; legacyItemRepository.findAll() in legacy is empty
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core,data/synapse-data-jpa -am verify`.
- [ ] **Step 5: Commit**: `feat(data): register Spring Data JPA repositories per store`.

### Task 8: Schema actions and the `ddl-auto` guard

**Files:**
- Create: `.../jpa/internal/{JpaSchemaAction,MetadataKeeper}.java`.
- Modify: `.../jpa/internal/{JpaStoreFactory,JpaStoreInfrastructure}.java` (`<p>SchemaAction` definition, a `StoreStartStep`).
- Test: `.../jpa/internal/DdlAutoGuardTest.java`, `JpaSchemaActionIT.java`.

**Interfaces:**
- Consumes: `StoreStartStep` (Task 5), `JpaStoreFactory.vendorProperties` (Task 6).
- Produces:

```java
public final class JpaSchemaAction implements StoreStartStep {
    public JpaSchemaAction(String store, String action, EntityManagerFactory emf, MetadataKeeper metadata);
    // start(): validate → emf.getSchemaManager().validate() (SchemaValidationException → *Schema mismatch*);
    //          create → create(true); create-drop → drop(true) then create(true); update → SchemaManagementToolCoordinator
    //          with Action.UPDATE over metadata.metadata(); none → nothing. stop(): create-drop → drop(true)
}
public final class MetadataKeeper implements IntegratorProvider { /* keeps the Metadata and SessionFactory from Integrator.integrate */ }
public static String schemaAction(String store, SynapseDataStoreProperties p, JpaProperties jpa, HibernateProperties h, boolean flywayPresent);
// in JpaStoreFactory: the guard (*Defaults* #12, *Messages*), then vendorProperties gets hibernate.hbm2ddl.auto=none
```

- [ ] **Step 1: Write the failing tests**

```java
// DdlAutoGuardTest (context runner; root cause messages exactly as *Messages*)
@ParameterizedTest @ValueSource(strings = {"create", "create-drop", "update"})
void schemaChangingValuesFailWithoutAllowDdlAuto(String value)
@ParameterizedTest @ValueSource(strings = {"create", "create-drop", "update"})
void allowDdlAutoPermitsThem(String value)            // allow-ddl-auto=true → starts; vendorProperties hibernate.hbm2ddl.auto == none
@Test void defaultIsValidateWithFlyway()              // schemaAction(...) == "validate"
@Test void defaultIsNoneWithoutFlyway()               // FilteredClassLoader(Flyway) → "none"
@Test void unknownValueFails()                        // ddl-auto=drop → *ddl-auto unknown*
@ParameterizedTest @ValueSource(strings = {"hibernate.hbm2ddl.auto", "jakarta.persistence.schema-generation.database.action"})
void rawSchemaKeysAreRejected(String key)             // Review Focus 3: books.jpa.properties.<key>=update → *Raw schema key*
@Test void noEnvironmentNameIsConsulted()             // spring.profiles.active=local alone doesn't allow update (fails)
// JpaSchemaActionIT (H2)
@Test void validatePassesAfterMigrations()            // V1 creates the book table → starts
@Test void validateFailsOnAMismatch()                 // entity with a column the migration lacks → *Schema mismatch* naming the store
@Test void createBuildsTheSchemaInStart()             // allow-ddl-auto, create, migrations.enabled=false → table exists after start, not before (checked in a StoreStartStep ordered before it)
@Test void updateAddsAColumn()                        // allow-ddl-auto, update, V1 lacks a column → column exists after start
@Test void createDropDropsOnStop()                    // after context.close(), the table is gone (H2 DB_CLOSE_DELAY=-1, checked through a separate DataSource)
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-jpa -am verify`.
- [ ] **Step 5: Commit**: `feat(data): validate the schema after migrations and guard ddl-auto per store`.

### Task 9: An unqualified `@Transactional` with several stores and no primary fails at startup

**Files:**
- Create: `data/synapse-data-core/.../internal/store/UnqualifiedTransactionCheck.java`.
- Modify: `.../autoconfigure/SynapseDataAutoConfiguration.java` (bean `synapseUnqualifiedTransactionCheck`, `@ConditionalOnClass(TransactionAttributeSource.class)`, `@ConditionalOnMissingBean(name = …)`).
- Test: `.../internal/store/UnqualifiedTransactionCheckTest.java`.

**Interfaces:**
- Consumes: the `StoreDefinition` beans (Task 2).
- Produces: `public final class UnqualifiedTransactionCheck implements SmartInitializingSingleton` (*Defaults* #22, *Messages*).

- [ ] **Step 1: Write the failing tests**

```java
// UnqualifiedTransactionCheckTest (runner: Books + Legacy on H2, a PlatformTransactionManager stub per store qualified by name)
@Test void unqualifiedMethodFails()            // bean class Ledger with @Transactional void post() → description lists "Ledger#post"
@Test void unqualifiedClassLevelFails()        // @Transactional on the class
@Test void qualifiedPasses()                   // @Transactional("books")
@Test void primaryMakesUnqualifiedValid()      // legacy.primary=true → starts
@Test void oneStoreNeverChecks()
@Test void listsAtMostFiveMethods()            // 7 methods → five names then "and 2 more"
@Test void repositoriesAndSpringClassesAreSkipped()   // a Spring Data repository and TransactionTemplate bean → starts
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core -am verify`.
- [ ] **Step 5: Commit**: `feat(data): fail startup on an unqualified @Transactional when no store is primary`.

### Task 10: JPA entity bases, ids and auditing

**Files:**
- Create:
  - `.../data/jpa/{IdEntity,VersionedEntity,AuditedEntity,LongIdEntity,LongVersionedEntity,LongAuditedEntity,EntityCallbacks,package-info}.java`;
  - `src/main/resources/io/americanexpress/synapse/data/jpa/synapse-orm.xml` (persistence-unit default entity listener `io.americanexpress.synapse.data.jpa.EntityCallbacks`, schema `https://jakarta.ee/xml/ns/persistence/orm` version 3.2);
  - `.../jpa/internal/{JpaExceptionTranslator,JpaRuntimeHints}.java`; `META-INF/spring/aot.factories`.
- Modify:
  - `.../jpa/internal/JpaStoreFactory.java` (`.mappingResources("io/americanexpress/synapse/data/jpa/synapse-orm.xml")`);
  - `data/synapse-data-core/.../autoconfigure/SynapseDataAutoConfiguration.java` (beans `synapseEntityIdGenerator`, `synapseAuditorAware`);
  - `.../jpa/autoconfigure/SynapseDataJpaAutoConfiguration.java` (bean `synapseJpaExceptionTranslator`).
- Test: `.../jpa/EntityBasesTest.java`, `.../jpa/JpaAuditingIT.java`, `.../jpa/internal/JpaExceptionTranslatorTest.java`, `.../jpa/internal/JpaRuntimeHintsTest.java`; core `SynapseDataAutoConfigurationTest` (the two beans: present, back off, `system-user` bound).

**Interfaces:**
- Consumes: `EntityIdGenerator`, `Auditable` (Task 1).
- Produces:

```java
@MappedSuperclass public abstract class IdEntity implements Identifiable<UUID>, Persistable<UUID> {
    public @Nullable UUID getId(); public boolean isNew();            // id == null
    void assignId(UUID id);                                           // package-private, EntityCallbacks only
}
@MappedSuperclass public abstract class VersionedEntity extends IdEntity implements Versioned { /* @Version Long version; isNew: version == null */ }
@MappedSuperclass public abstract class AuditedEntity extends VersionedEntity implements Auditable { /* created_at, created_by (updatable=false), modified_at, modified_by */ }
// LongIdEntity / LongVersionedEntity / LongAuditedEntity: same shape, Long id, @GeneratedValue(strategy = SEQUENCE)
final class EntityCallbacks {                                         // created by Hibernate through SpringBeanContainer
    EntityCallbacks(EntityIdGenerator ids, Clock clock, AuditorAware<String> auditor, SynapseDataProperties properties);
    @PrePersist void prePersist(Object entity);  @PreUpdate void preUpdate(Object entity);
}
public final class JpaExceptionTranslator implements ExceptionTranslator {
    // cause chain (depth 10): jakarta.persistence.OptimisticLockException, org.hibernate.StaleStateException → STALE_VERSION;
    // org.hibernate.exception.ConstraintViolationException with kind UNIQUE → ALREADY_EXISTS; else null
}
// synapseAuditorAware: RequestContext.find().flatMap(RequestContext::principal).map(Principal::getName), else system-user
```

- [ ] **Step 1: Verify the two unverified facts (*Library facts*)** with a throwaway test before writing the listener:
  - a default listener from a mapping resource reaches an entity that extends a `@MappedSuperclass` and declares nothing;
  - its constructor dependencies come from the store's context (the `Clock` bean is the test's `MutableClock`).

  If either fails, stop and report: the fallback (an `Integrator`-registered Hibernate event listener) changes *Defaults* #17.
- [ ] **Step 2: Write the failing tests**

```java
// EntityBasesTest (plain unit)
@Test void idOnlyBaseIsNewUntilItHasAnId()
@Test void versionedBaseIsNewUntilItHasAVersion()       // id set, version null → isNew()
@Test void equalityIsByIdAndUnproxiedClass()            // two instances, same id → equal; different class same id → not equal; null id → only identity
@Test void hashCodeIsStableAcrossPersist()              // hashCode before and after assignId is equal
// JpaAuditingIT (H2; MutableClock at 2026-01-01T00:00:00Z; Book extends AuditedEntity; LegacyNote implements Auditable with custom columns note_created/…)
@Test void persistAssignsAUuidV7FromTheClock()          // id.version()==7; id msb>>>16 == clock millis — acceptance 20
@Test void auditingFillsCreatedAndModified()            // createdAt == modifiedAt == clock instant; createdBy == "system"
@Test void updateMovesModifiedOnly()                    // clock +1h, change, flush → modifiedAt +1h, createdAt unchanged
@Test void principalFromTheRequestContextIsTheAuditor() // RequestContexts.with(principal "ana").run(save) → createdBy "ana"
@Test void systemUserIsConfigurable()                   // synapse.data.auditing.system-user=batch → "batch"
@Test void customColumnsAreAudited()                    // LegacyNote gets note_created etc.
@Test void longIdBasesUseTheSequence()                  // LongAuditedEntity subclass gets id 1 then 2 (migration creates the sequence)
@Test void saveOfANewEntityPersistsOnce()               // repository.save(new Book) → one INSERT, no SELECT (Persistable.isNew), via Hibernate statistics
@Test void versionConflictIsStaleVersion()              // two copies, both saved → second fails; through SynapseOperations.invoke → STALE_VERSION
// JpaExceptionTranslatorTest: optimistic lock and stale state → STALE_VERSION; unique ConstraintViolationException → ALREADY_EXISTS; other kinds → null
// JpaRuntimeHintsTest: resource synapse-orm.xml; EntityCallbacks declared constructors (RuntimeHintsPredicates)
```

- [ ] **Step 3: Run to verify failure.**
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run to verify pass**, then `./mvnw -pl data/synapse-data-core,data/synapse-data-jpa -am verify`.
- [ ] **Step 6: Commit**: `feat(data): add JPA entity bases with UUIDv7 ids, Persistable and auditing`.

### Task 11: `synapse-data-jdbc`: Spring Data JDBC stores

**Files:**
- Create:
  - `data/synapse-data-jdbc/pom.xml` (`synapse-data-core`, `spring-data-jdbc`; test as Task 6);
  - `.../data/jdbc/autoconfigure/{SynapseDataJdbcAutoConfiguration,JdbcDefaultsEnvironmentPostProcessor,package-info}.java`;
  - `.../data/jdbc/internal/{JdbcStoreInfrastructure,JdbcStoreFactory,JdbcAuditingCallback,JdbcRuntimeHints,package-info}.java`;
  - `META-INF` registrations; `application-test.yaml`.
- Modify: `pom.xml` (module); data-core: remove the test-only JDBC `StoreInfrastructure` from Task 2.
- Test: `.../jdbc/autoconfigure/SynapseDataJdbcAutoConfigurationTest.java`, `JdbcDefaultsEnvironmentPostProcessorTest.java`; `.../jdbc/internal/JdbcStoresIT.java` (`@Tag("smoke")`), `JdbcRuntimeHintsTest.java`; and in data-jpa: `MixedAccessIT.java` (data-jpa gets synapse-data-jdbc in test scope).

**Interfaces:**
- Consumes: `StoreRepositoryConfigurationSource` (Task 7), `StoreFactory.vendor` (Task 2), `Auditable`, `AuditorAware` (Task 10).
- Produces:

```java
public final class JdbcStoreFactory {     // bean "synapseJdbcStoreFactory"
    public JdbcTransactionManager transactionManager(String store);
    public JdbcDialect dialect(String store);                  // vendor.jdbcDialect() class's static INSTANCE
    public RelationalManagedTypes managedTypes(String store);  // fromSupplier(() -> JdbcConfiguration.scanForEntities(package))
    public JdbcMappingContext mappingContext(String store); public JdbcConverter converter(String store);
    public DataAccessStrategy dataAccessStrategy(String store); public JdbcAggregateTemplate aggregateTemplate(String store);
}
final class JdbcAuditingCallback implements BeforeConvertCallback<Object>, Ordered { }   // *Defaults* #17
// JdbcDefaultsEnvironmentPostProcessor: spring.data.jdbc.repositories.enabled=false at lowest precedence
```

  Repositories use the references `jdbcOperationsRef=<p>NamedParameterJdbcTemplate`, `dataAccessStrategyRef`, `transactionManagerRef` and `jdbcAggregateOperationsRef`.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseDataJdbcAutoConfigurationTest (runner adds DataJdbcRepositoriesAutoConfiguration)
@Test void aJdbcStoreGetsItsInfrastructure()          // booksTransactionManager (JdbcTransactionManager), booksJdbcDialect == JdbcH2Dialect.INSTANCE, mapping context, aggregate template
@Test void bootsJdbcRepositoriesBackOff()             // no "jdbcAggregateTemplate", no "jdbcDialect" bean
@Test void dialectComesFromTheVendor()                // unreachable url → context starts (no dialect resolution over a connection)
@Test void absentWithoutSpringDataJdbc()
// JdbcStoresIT (@Tag("smoke"); two H2 JDBC stores; aggregates with @Id Long, @Version, implementing Auditable)
@Test void repositoriesWorkPerStore()
@Test void auditingStampsAggregates()                 // created and modified from the clock; second save moves modified only
@Test void versionConflictIsStaleVersion()            // through SynapseOperations.invoke
@Test void transactionalWithAStoreName()
// MixedAccessIT (data-jpa module, both modules present)
@Test void autoAccessIsAmbiguous()                    // *Access ambiguous*
@Test void eachStoreNamesItsAccess()                  // books JPA, legacy JDBC → both work
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-core,data/synapse-data-jdbc,data/synapse-data-jpa -am verify`.
- [ ] **Step 5: Commit**: `feat(data): add Spring Data JDBC stores`.

### Task 12: Store health and readiness opt-in

**Files:**
- Create: `data/synapse-data-core/.../autoconfigure/SynapseDataHealthAutoConfiguration.java`; `.../internal/health/{StoreHealthIndicator,StoreReadinessGroups,package-info}.java`.
- Modify: the imports file.
- Test: `.../internal/health/StoreHealthIndicatorTest.java`, `.../autoconfigure/SynapseDataHealthAutoConfigurationTest.java`, `.../internal/health/StoreReadinessIT.java` (data-core gets `spring-boot-health` in test scope as well as optional at compile).

**Interfaces:**
- Produces: *Defaults* #20.
  - `SynapseDataHealthAutoConfiguration`: `@AutoConfiguration(before = DataSourceHealthContributorAutoConfiguration.class)` (by name, `beforeName`), `@ConditionalOnClass(HealthContributor.class)`, `@ConditionalOnEnabledHealthIndicator("db")`.
  - Beans: `dbHealthContributor` (`@ConditionalOnMissingBean(name = {"dbHealthIndicator", "dbHealthContributor"})`), a composite with one child per store, and `synapseStoreReadinessGroups` (`HealthEndpointGroupsPostProcessor`).
  - `public StoreHealthIndicator(HikariDataSource dataSource, Duration timeout)`.

- [ ] **Step 1: Verify** that the readiness group is asked `isMember("db/books")` for a composite child (*Library facts*), with a throwaway runner test. Adjust the member form if Boot passes another one.
- [ ] **Step 2: Write the failing tests**

```java
// StoreHealthIndicatorTest
@Test void upWhenAConnectionValidates()          // H2 → UP
@Test void downWhenTheDatabaseIsUnreachable()    // unreachable tcp url, timeout 300ms → DOWN within 1s
@Test void aBusyPoolStaysUp()                    // maximum-pool-size 1, the one connection held → UP with detail pool=busy, no wait
@Test void aLateBorrowIsClosed()                 // borrow that completes after the timeout is closed (active count returns to 0)
@Test void detailsNeverContainTheUrl()
// SynapseDataHealthAutoConfigurationTest
@Test void replacesBootsDbContributorWithOnePerStore()   // dbHealthContributor composite has children books and legacy
@Test void backsOffToAUserDbHealthContributor()
@Test void absentWithoutHealth()                 // FilteredClassLoader(HealthContributor)
@Test void bindsHealthTimeout()                  // books.health.timeout=200ms
// StoreReadinessIT (HealthEndpoint bean, management.endpoint.health.probes.enabled=true; books H2, legacy unreachable)
@Test void readinessIgnoresAStoreThatDidNotOptIn()   // legacy down → healthForPath("readiness") UP; healthForPath("db","legacy") DOWN
@Test void readinessIncludesAnOptedInStore()         // legacy.health.readiness=true → readiness DOWN
```

- [ ] **Step 3: Run to verify failure.**
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run to verify pass**, then `./mvnw -pl data/synapse-data-core -am verify`.
- [ ] **Step 6: Commit**: `feat(data): add per-store health with opt-in readiness`.

### Task 13: `synapse-test-data`: `@SynapseDataTest` and `@StoreConnection`

**Files:**
- Create:
  - `data/synapse-test-data/pom.xml`:
    - property `<synapse.enforcer.allowTestLibraries>true</synapse.enforcer.allowTestLibraries>` (as `synapse-test-rest`);
    - dependencies: `synapse-test`, `synapse-data-core`, `spring-boot-test-autoconfigure`, `org.testcontainers:testcontainers-jdbc`, `org.testcontainers:testcontainers-postgresql`, `org.postgresql:postgresql`;
    - test scope: `synapse-data-jpa`, `h2`, `spring-boot-starter-test`. The slice's own tests run on H2; only `SynapseContainersIT` starts a container. PostgreSQL slice use is tested in Task 14.
  - `.../test/data/{SynapseDataTest,AutoConfigureSynapseData,StoreConnection,SynapseContainers,SynapseDataTypeExcludeFilter,StoreConnectionContextCustomizerFactory,SynapseDataTestAutoConfiguration,package-info}.java`;
  - `META-INF/spring.factories` (`ContextCustomizerFactory`); `META-INF/spring/io.americanexpress.synapse.test.data.AutoConfigureSynapseData.imports`:
    - `SynapseCoreAutoConfiguration`, `SynapseTestClockAutoConfiguration`;
    - `SynapseDataAutoConfiguration`, `optional:` JPA, JDBC and Postgres auto-configurations;
    - Boot's `TransactionAutoConfiguration` and `TransactionManagerCustomizationAutoConfiguration`;
    - `SynapseDataTestAutoConfiguration`.
- Modify: `pom.xml` (module).
- Test: `.../test/data/SynapseDataTestIT.java` (`@Tag("smoke")`, H2), `StoreConnectionTest.java`, `SynapseContainersIT.java` (Docker), `SynapseDataTypeExcludeFilterTest.java`.

**Interfaces:**
- Consumes: `StoreDefinitionsPostProcessor`'s slice rule (*Defaults* #18), qualified `JdbcConnectionDetails` (Task 2).
- Produces:

```java
@Target(TYPE) @Retention(RUNTIME) @Documented @Inherited
@BootstrapWith(SpringBootTestContextBootstrapper.class) @ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false) @TypeExcludeFilters(SynapseDataTypeExcludeFilter.class)
@AutoConfigureSynapseData @Transactional @ActiveProfiles("test")
public @interface SynapseDataTest {
    @AliasFor(annotation = Transactional.class, attribute = "transactionManager") String value();
}
@Target(FIELD) @Retention(RUNTIME) @Documented public @interface StoreConnection { String value(); }
public final class SynapseContainers {
    public static final String POSTGRES_IMAGE = "postgres:18.6-alpine";
    public static PostgreSQLContainer postgres(); public static PostgreSQLContainer postgres(String key);
}
// SynapseDataTestAutoConfiguration registers bean "synapseDataTestSlice" (marker)
// SynapseDataTypeExcludeFilter: includes only classes annotated @SynapseDataStore whose value equals the test's store
```

- [ ] **Step 1: Write the failing tests**

```java
// SynapseDataTestIT (H2 stores books + legacy configured in application-test.yaml; slice for "books")
@Test void startsOnlyThatStore()             // booksDataSource and bookRepository present; no legacyDataSource; no ServiceOperation beans
@Test void migrationsRanForTheStore()        // book table exists
@Test void eachTestRollsBack()               // test A saves; test B (ordered) sees no rows
@Test void clockIsTheMutableTestClock()      // auditing uses 2026-01-01T00:00:00Z
@Test void runsAsTest()                      // active profiles == [test]
// StoreConnectionTest
@Test void registersQualifiedConnectionDetails()   // a stub JdbcDatabaseContainer (isRunning true) → bean booksStoreConnectionDetails, qualifier books, not a default candidate
@Test void sameFieldsShareTheCachedContext()       // two test classes with the same fields → equal ContextCustomizers
@Test void fieldMustBeAJdbcDatabaseContainer()     // @StoreConnection on a String field → IllegalStateException naming the field
// SynapseContainersIT (Docker)
@Test void sharedPerKey()                    // postgres() same instance twice; postgres("legacy") a different running container; image postgres:18.6-alpine
// SynapseDataTypeExcludeFilterTest: BooksStore included, LegacyStore and an @Component excluded
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-test-data -am verify` (needs Docker).
- [ ] **Step 5: Commit**: `feat(test): add @SynapseDataTest and per-store test connections`.

### Task 14: `synapse-data-postgres` and the PostgreSQL ITs

**Files:**
- Create:
  - `data/synapse-data-postgres/pom.xml` (`synapse-data-core`, `org.postgresql:postgresql`, `org.flywaydb:flyway-database-postgresql`; test: `synapse-test-data`, `synapse-data-jpa`, `synapse-data-jdbc`, `spring-boot-starter-test`, `spring-boot-health`);
  - `.../data/postgres/{PostgresVendor,package-info}.java`, `.../postgres/autoconfigure/{SynapsePostgresAutoConfiguration,package-info}.java`, `.../postgres/internal/aot/PostgresRuntimeHints.java`, META-INF registrations.
  - A test fixture "data module" `src/test/java/.../postgres/fixture/books/` (`BooksStore`, `Book extends AuditedEntity` with a unique `isbn`, `BookRepository`), `.../fixture/legacy/` (a JDBC store: `LegacyStore` with `access = JDBC`, `LegacyItem`, repository), `src/test/resources/db/migration/{books,legacy}/V1__*.sql`, `src/test/resources/META-INF/synapse/config.yaml` and `config-test.yaml` (pool and migration settings, which show the module-file layout; see the environments plan's *Defaults* #9: put no root `application.*` beside them), `application-test.yaml`.
- Modify: `pom.xml` (module).
- Test: `.../postgres/autoconfigure/SynapsePostgresAutoConfigurationTest.java`, `PostgresRuntimeHintsTest.java`, `.../postgres/PostgresStoreIT.java` (`@Tag("smoke")`), `PostgresTwoStoresIT.java`, `PostgresExitOnRefreshIT.java`.

**Interfaces:**
- Produces: `public final class PostgresVendor implements DataStoreVendor` (*Defaults* #26). `SynapsePostgresAutoConfiguration`: `@ConditionalOnClass(org.postgresql.Driver.class)`, bean `postgresDataStoreVendor` (`@ConditionalOnMissingBean(name = "postgresDataStoreVendor")`). Hints: the dialect classes (constructors; the JDBC dialect's `INSTANCE` field).

- [ ] **Step 1: Write the failing tests**

```java
// SynapsePostgresAutoConfigurationTest
@Test void contributesTheVendor()             // supports("jdbc:postgresql://h/db"); not "jdbc:h2:mem:x"; name "postgres"; version "15" (F22); product name "PostgreSQL"; dialect names
@Test void backsOffToAUserVendor()            // user bean named postgresDataStoreVendor
@Test void absentWithoutTheDriver()           // FilteredClassLoader(org.postgresql.Driver)
@Test void storeOnPostgresGetsTheDialectAndVersion()  // with synapse-data-jpa: product name PostgreSQL, product version 15, no hibernate.dialect; the resolved dialect is PostgreSQLDialect at 15; database-version=17 → 17 (amended: F22 and the Task 6 carry)
// PostgresStoreIT (@Tag("smoke"); @SynapseDataTest("books"), @StoreConnection("books") static PostgreSQLContainer books = SynapseContainers.postgres())
@Test void migratesAndValidates()             // flyway_schema_history has V1; context started (validate passed)
@Test void idsAuditingAndVersion()            // UUIDv7 from the clock; created/modified; version 0 after insert
@Test void duplicateAtCommitIsDuplicate()     // Review Focus 4: two books with one isbn, flushed at commit through SynapseOperations.invoke → ALREADY_EXISTS
@Test void staleWriteIsStaleVersion()         // acceptance 8: @Version conflict → STALE_VERSION (CONFLICT)
// PostgresTwoStoresIT (@SpringBootTest of a fixture app; books (JPA) on postgres("books"), legacy (JDBC) on postgres("legacy"); no primary)
@Test void eachStoreHasItsOwnInfrastructure() // acceptance 8: DataSource, EntityManagerFactory or JDBC context, TM, repositories, db/books and db/legacy health, flyway history per database
@Test void transactionalLegacyTargetsLegacy() // a @Transactional("legacy") bean writes only to the legacy container
@Test void unqualifiedTransactionalFails()    // a nested context adding an unqualified @Transactional bean fails with *Unqualified @Transactional*
@Test void springDatasourceUrlWarns()         // spring.datasource.url set → the WARN line (OutputCaptureExtension)
@Test void ddlAutoUpdateFailsUnlessAllowed()  // books.jpa.hibernate.ddl-auto=update → *ddl-auto not allowed*; with allow-ddl-auto → starts — acceptance 8
@Test void moduleConfigFilesConfigureTheStore()   // META-INF/synapse/config.yaml's books.hikari.maximum-pool-size reaches the pool
// PostgresExitOnRefreshIT (no Docker; launches a JVM as ContextExitOnRefreshIT in synapse-api-rest does)
@Test void startsWithNoDatabaseReachable()    // acceptance 17: books url jdbc:postgresql://127.0.0.1:1/books, -Dspring.context.exit=onRefresh, ENV/profile test → exit code 0
// PostgresRuntimeHintsTest: the two dialect classes are registered
```

- [ ] **Step 2: Run to verify failure.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify pass**, then `./mvnw -pl data/synapse-data-postgres,data/synapse-test-data -am verify` (Docker). Then the JDK 21 smoke leg for the data modules: `./mvnw -pl data/synapse-data-postgres -am verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke`.
- [ ] **Step 5: Commit**: `feat(data): add the PostgreSQL vendor module with Testcontainers integration tests`.

### Task 15: AOT check, docs, spec status and the full build

**Files:**
- Create:
  - `data/synapse-data-core/.../internal/aot/{SynapseDataRuntimeHints,package-info}.java` (resource patterns `db/migration/*/**`) and its `aot.factories` entry;
  - `tests/synapse-data-mixed-tests/src/test/.../StoreAotProcessingTest.java` (H2 fixtures: books JPA, legacy JDBC). *Amended (F3):* data-jpa doesn't have synapse-data-jdbc in test scope, so the test is in the mixed-tests module;
  - `docs/modules/ROOT/pages/data.adoc`.
- Modify:
  - `docs/modules/ROOT/nav.adoc` (*Data* after *REST*);
  - `pages/testing.adoc` (an `@SynapseDataTest` section);
  - `pages/environments.adoc`: L211/L231/L247 `pool-size` → `hikari.maximum-pool-size`; L273 the `allow-ddl-auto` sentence; L252 and L280 now that the store modules exist;
  - `pages/rest.adoc` L605 (the translator exists);
  - `pages/core.adoc` (the `ALREADY_EXISTS` row in the errors table);
  - `specs/v2/BUILD.md` §15 (*Done (plan 4)* note; the delivery-list bullet says "Done").
  - *Added (interim review, parked item 8):* `StoreDefinitionsPostProcessor` fails startup when a store's `primary` differs from the one its definition was registered with. Under AOT the post-processor runs again at startup, with the run-time environment, so a `primary` changed after the build fails, naming the key. The AOT test showed the other store checks (undeclared, bracketed keys, two primaries, packages) run again at startup too, so a store added only at run time (an environment variable included) fails as undeclared. Core's `AotEnvironmentCheck` is unchanged.

**Interfaces:**
- Consumes: the whole feature.

- [ ] **Step 1: Write the failing tests**

```java
// SynapseDataRuntimeHintsTest: RuntimeHintsPredicates.resource().forResource("db/migration/books/V1__books.sql") matches
// StoreAotProcessingTest (as CoreAotProcessingTest does): AOT-process a context with books (JPA) and legacy (JDBC) on H2,
// compile the generated code with TestCompiler, then refresh a GenericApplicationContext from the generated initializer:
@Test void storeBeansAreGeneratedAheadOfTime()   // generated code registers booksDataSource, booksEntityManagerFactory, legacyJdbcAggregateTemplate, bookRepository
@Test void generatedContextCreatesTheBeans()      // the AOT context's getBeansOfType(DataSource).keySet() == {booksDataSource, legacyDataSource}
```

- [ ] **Step 2: Run to verify failure**, **Step 3: implement** the hints and any AOT fix the test finds, **Step 4: run to verify pass.**
- [ ] **Step 5: Write `data.adoc`:**
  - modules and which one to add;
  - declaring a store; bean names and qualifiers; replacing a bean;
  - the key table (generated property reference link, plus the additional metadata);
  - keys without defaults and credentials from environment variables, with a `data-books` module example of `config.yaml`/`config-<env>.yaml`;
  - one store, several stores, `primary`, `@Transactional("<store>")`, cross-store transactions not atomic, the unqualified check and its `TransactionTemplate` limit;
  - migrations (locations, `migrations.flyway.*`, separate-job teams);
  - `ddl-auto`, its defaults and `allow-ddl-auto` in `application-local.yaml`;
  - schema actions in `start()`; open-in-view off;
  - entity bases, ids (UUIDv7, not a security capability), `Persistable`, auditing (system user, principal);
  - JDBC stores; error translation table; health and readiness; metrics (`hikaricp.*` with `pool=<store>`); the connection budget;
  - vendors (PostgreSQL defaults, `database-version`); Liquibase not yet; native: best effort.
- [ ] **Step 6: Build the docs** (`cd docs && npx antora antora-playbook.yml`, as CI does). Check that every `synapse.data.*` key has a description in the property table.
- [ ] **Step 7: Update BUILD.md §15** with *Done (plan 4)*. It covers acceptance:
  - 8;
  - 10 (data modules);
  - 15 (the store part);
  - 17 (stores);
  - 19 (`@SynapseDataTest` provided);
  - 20 (auditing timestamps and UUIDv7 ids).
- [ ] **Step 8: Run the full build on JDK 25:** `./mvnw clean verify` (in the background). Expected: BUILD SUCCESS, including the invoker tests and coverage gates.
- [ ] **Step 9: Run the JDK 21 smoke leg:** `./mvnw verify -Djvm="$HOME/.jdks/jdk-21.0.12.1+1/bin/java" -Dsynapse.it.groups=smoke`. Report the test counts per module for both runs.
- [ ] **Step 10: Commit**: `docs: document data stores and the PostgreSQL module`.

---

## Covered by later plans

| Spec item | Plan |
|---|---|
| gRPC status for `ALREADY_EXISTS` (ALREADY_EXISTS) and for `STALE_VERSION` (ABORTED) | 3 |
| `samples/reference-bookstore` on Postgres with Flyway, using `@SynapseDataTest`; acceptance 18 and 19; the archetype's data module (`META-INF/synapse/config*.yaml`, `allow-ddl-auto` in `application-local.yaml`); native verification of per-store Flyway resource scanning | 5 |
| Liquibase support | After the maintainer's license decision (DESIGN *Open questions*) |
| `@StoreConnection` on `@Bean`-method containers; Testcontainers definitions for other vendors | P5 |
| Vendor SPI shape for the next vendors (final review 2, Minor 8; `DataStoreVendor` is `@Incubating`): an allow-list mode for driver properties (a deny list is brittle for drivers such as MySQL Connector/J with about 300 properties); a classification test per vendor, like `PostgresVendorTest`'s for every `PGProperty`; checks for JVM-global and file-system sources (Oracle's `oracle.net.tns_admin`/`TNS_ADMIN`, `ojdbc.properties`, wallets) and pool keys (MySQL's `hikari.catalog` is now rejected for every store); N1 (the English `urlProblem` fragment) and N2 (`urlForm` and `urlProblem` as one record) from the Task 14 review | P5 |
| A build test that runs the data modules without `flyway-core` on the class path (interim review Minor 12: today only `FilteredClassLoader` covers it, which wouldn't catch a linkage regression in `StoreFactory` or `SynapseDataAutoConfiguration`), as a small never-published module or an invoker project | Follow-up before 2.0.0-M1 (not assigned to a plan yet) |
| Read replicas, multi-tenancy | 2.x |
