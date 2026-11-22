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
package io.americanexpress.synapse.core.autoconfigure;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.Ordered;

/**
 * Explains Boot's failure when {@code spring.profiles.active} is a placeholder, such as {@code ${ENV}}, whose
 * variable has no value: Boot rejects the unresolved text as a profile name before Synapse sees it.
 * <p>
 * Boot validates the names in {@code spring.profiles.include}, {@code spring.profiles.default} and profile groups the
 * same way. Those are not the environment, so the analyzer answers only when the failure names
 * {@code spring.profiles.active}, and leaves every other failure to Boot's own analyzers.
 */
final class UnresolvedEnvironmentFailureAnalyzer implements FailureAnalyzer, Ordered {

    private static final String ACTIVE = "spring.profiles.active";

    /** Boot's {@code ProfilesValidator} message for a profile name that is an unresolved placeholder. */
    private static final Pattern UNRESOLVED = Pattern.compile("^Profile '(\\$\\{([^}]+)\\})' must");

    /** The message of the exception {@code ProfilesValidator} wraps a validation failure in. */
    private static final Pattern VALIDATED_PROPERTY = Pattern
            .compile("^Invalid profile property value found in Environment under '([^']+)'$");

    /** {@code spring.profiles.active} or one of its indexed elements, such as {@code spring.profiles.active[0]}. */
    private static final Pattern ACTIVE_PROPERTY = Pattern.compile("^" + Pattern.quote(ACTIVE) + "(?:\\[\\d+\\])?$");

    @Override
    public @Nullable FailureAnalysis analyze(Throwable failure) {
        // The property whose value failed: a BindException names it (as in a run of Boot 4.1.2), and so does the
        // wrapper of ProfilesValidator. The innermost one is closest to the failing value.
        String property = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof BindException bind) {
                property = bind.getName().toString();
            } else if (cause instanceof IllegalStateException && message != null) {
                Matcher validated = VALIDATED_PROPERTY.matcher(message);
                if (validated.matches()) {
                    property = validated.group(1);
                    continue;
                }
                Matcher matcher = UNRESOLVED.matcher(message);
                if (matcher.find()) {
                    return property != null && ACTIVE_PROPERTY.matcher(property).matches()
                            ? analysis(matcher.group(1), matcher.group(2), cause)
                            : null;
                }
            }
        }
        return null;
    }

    private static FailureAnalysis analysis(String placeholder, String variable, Throwable cause) {
        return new FailureAnalysis(
                "No environment is set: " + ACTIVE + " is '" + placeholder + "', and " + variable + " has no value.",
                "Set " + variable + " to the name of the environment (the service has an application-<env>.yaml for "
                        + "each). Run locally with -Dspring-boot.run.profiles=local or an IDE run configuration; "
                        + "Synapse's test slices activate test; other Spring Boot "
                        + "tests declare @ActiveProfiles(\"test\").",
                cause);
    }

    /**
     * Runs before Boot's analyzers. Boot's {@code BindFailureAnalyzer} also answers this failure, and it would answer
     * first whenever spring-boot comes before synapse-core on the classpath, as it does in an executable jar.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
