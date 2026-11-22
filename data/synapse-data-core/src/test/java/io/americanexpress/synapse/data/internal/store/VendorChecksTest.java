/*
 * Copyright 2026 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.americanexpress.synapse.data.internal.store;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.NestedExceptionUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks a vendor adds to its stores: the driver properties it blocks in {@code hikari.data-source-properties}
 * (in any case), what it finds wrong with a URL, and the class-path resources its driver would read behind the store's
 * settings. Each failure names the key or the resource, never a value. An empty password reaches the pool as
 * {@code ""}, so the driver never falls back to a password source of its own.
 */
@ExtendWith(OutputCaptureExtension.class)
class VendorChecksTest {

    private static final String PREFIX = "synapse.data.stores.books";

    /** A resource the checked vendor forbids, which is on the test class path. */
    static final String PRESENT_RESOURCE = "io/americanexpress/synapse/data/internal/store/forbidden-driver.properties";

    /** The books store, without a vendor. */
    private final ApplicationContextRunner store = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues(PREFIX + ".url=jdbc:h2:mem:vendor-checks;DB_CLOSE_DELAY=-1", PREFIX + ".username=sa",
                    PREFIX + ".password=", PREFIX + ".migrations.enabled=false");

    /** The books store on the checked vendor, which forbids a resource that is absent. */
    private final ApplicationContextRunner runner = store
            .withBean("checkedVendor", CheckedVendor.class,
                    () -> new CheckedVendor(List.of("absent/driver.properties")));

    @ParameterizedTest
    @ValueSource(strings = {"targetHost", "TARGETHOST", "targethost", "TargetHost", "secretKey"})
    void aBlockedDriverPropertyFailsInAnyCase(String key, CapturedOutput output) {
        runner
                .withPropertyValues(PREFIX + ".hikari.data-source-properties." + key + "=elsewhere-s3cr3t")
                .run(context -> {
                    SynapseConfigurationException failure = configurationFailure(context);
                    assertThat(failure)
                            .hasMessage("The store 'books' sets " + PREFIX + ".hikari.data-source-properties." + key
                                    + ", a driver property that would change where, as whom or in which schema the store connects, "
                                    + "or that holds a secret.");
                    assertThat(failure.action())
                            .isEqualTo("Remove it, name the host, port, database and schema in " + PREFIX
                                    + ".url, and set " + PREFIX + ".username and " + PREFIX + ".password.");
                });
        assertThat(output).doesNotContain("elsewhere-s3cr3t");
    }

    @Test
    void otherDriverPropertiesPass() {
        runner.withPropertyValues(PREFIX + ".hikari.data-source-properties.cacheSize=10").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("booksDataSource", HikariDataSource.class).getDataSourceProperties())
                    .containsEntry("cacheSize", "10");
        });
    }

    @Test
    void aUrlTheVendorRejectsFails(CapturedOutput output) {
        runner.withPropertyValues(PREFIX + ".url=jdbc:h2:mem:vendor-checks;NOHOST=s3cr3t").run(context -> {
            SynapseConfigurationException failure = configurationFailure(context);
            assertThat(failure).hasMessage("The store 'books' has a url that names no host.");
            assertThat(failure.action()).isEqualTo("Set " + PREFIX + ".url in the form jdbc:h2:<host>/<database>.");
        });
        assertThat(output).doesNotContain("s3cr3t");
    }

    @Test
    void aVendorWithoutAUrlFormGetsAPlainAction() {
        store
                .withBean("checkedVendor", CheckedVendor.class, () -> new CheckedVendor(List.of(), null))
                .withPropertyValues(PREFIX + ".url=jdbc:h2:mem:vendor-checks;NOHOST=1")
                .run(context -> assertThat(configurationFailure(context).action())
                        .isEqualTo("Fix " + PREFIX + ".url."));
    }

    @Test
    void aForbiddenResourceOnTheClassPathFails() {
        store
                .withBean("checkedVendor", CheckedVendor.class,
                        () -> new CheckedVendor(List.of("absent/driver.properties", PRESENT_RESOURCE)))
                .run(context -> {
                    SynapseConfigurationException failure = configurationFailure(context);
                    String location = Objects
                            .requireNonNull(getClass().getClassLoader().getResource(PRESENT_RESOURCE))
                            .toString();
                    assertThat(failure)
                            .hasMessage("The class path has " + PRESENT_RESOURCE + " (" + location
                                    + "), which the checked driver reads behind the settings of the store 'books'.");
                    assertThat(failure.action()).isEqualTo("Remove it, and configure the store under " + PREFIX + ".");
                    assertThat(failure.getMessage()).doesNotContain("s3cr3t");
                });
    }

    /**
     * Boot gives a non-embedded database an empty password as {@code null}, and a driver then looks for a password of
     * its own (PostgreSQL's {@code .pgpass}). The store's empty password reaches the pool as {@code ""}.
     */
    @Test
    void anEmptyPasswordReachesThePoolAsEmpty() {
        runner.withPropertyValues(PREFIX + ".url=jdbc:h2:tcp://127.0.0.1:1/none").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("booksDataSource", HikariDataSource.class).getPassword()).isEmpty();
        });
    }

    @Test
    void aPasswordReachesThePool() {
        runner
                .withPropertyValues(PREFIX + ".url=jdbc:h2:tcp://127.0.0.1:1/none", PREFIX + ".password=from-env")
                .run(context -> assertThat(context.getBean("booksDataSource", HikariDataSource.class).getPassword())
                        .isEqualTo("from-env"));
    }

    private static SynapseConfigurationException configurationFailure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable cause = NestedExceptionUtils
                .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure(), "the startup failure"));
        assertThat(cause).isInstanceOf(SynapseConfigurationException.class);
        return (SynapseConfigurationException) cause;
    }

    /**
     * An H2 vendor with checks: it blocks the driver properties {@code targetHost} and {@code secretKey}, rejects a URL
     * with {@code ;NOHOST=}, and forbids the given resources.
     */
    static class CheckedVendor extends H2TestVendor {

        private final List<String> forbidden;

        private final @Nullable String urlForm;

        CheckedVendor(List<String> forbidden) {
            this(forbidden, "jdbc:h2:<host>/<database>");
        }

        CheckedVendor(List<String> forbidden, @Nullable String urlForm) {
            this.forbidden = forbidden;
            this.urlForm = urlForm;
        }

        @Override
        public String name() {
            return "checked";
        }

        @Override
        public Set<String> blockedDriverProperties() {
            return Set.of("targetHost", "SecretKey");
        }

        @Override
        public @Nullable String urlProblem(String jdbcUrl) {
            return jdbcUrl.contains(";NOHOST=") ? "names no host" : null;
        }

        @Override
        public @Nullable String urlForm() {
            return urlForm;
        }

        @Override
        public List<String> forbiddenResources() {
            return forbidden;
        }
    }
}
