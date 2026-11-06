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
package io.americanexpress.synapse.core.internal.environment;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * The cause Synapse keeps when it can't read or parse a configuration file. A parser's message quotes the file, and a
 * read failure's message can name a path, so the cause keeps only the failure's type, the position of a YAML parse
 * error and the stack trace: never a message, a snippet of the file or the original cause chain. Boot logs the cause
 * chain at {@code DEBUG}, and any code may print it.
 */
final class ConfigFileFailures {

    /** SnakeYAML's exception with a position. It is named, not referenced, until such an exception exists. */
    private static final String MARKED_YAML_EXCEPTION = "org.yaml.snakeyaml.error.MarkedYAMLException";

    private ConfigFileFailures() {
    }

    /**
     * Returns the cause to keep for a file's read or parse failure: an exception whose message is the failure's simple
     * class name, followed by {@code at line <L>, column <C>} (1-based) when a SnakeYAML parse error with a position is
     * in its cause chain. It has the failure's stack trace and no cause.
     *
     * @param failure the read or parse failure
     * @return the cause to keep
     */
    static RuntimeException sanitized(Throwable failure) {
        SanitizedFailure cause = new SanitizedFailure(failure.getClass().getSimpleName() + position(failure));
        cause.setStackTrace(failure.getStackTrace());
        return cause;
    }

    /** The position of the first SnakeYAML parse error in the cause chain, or nothing. */
    private static String position(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (isMarkedYamlException(current.getClass())) {
                String position = Yaml.position(current);
                if (position != null) {
                    return position;
                }
            }
        }
        return "";
    }

    /**
     * Whether the type is SnakeYAML's {@code MarkedYAMLException}, checked by name so that SnakeYAML's classes are
     * loaded only once such an exception exists. SnakeYAML is an optional dependency of this module.
     */
    private static boolean isMarkedYamlException(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(MARKED_YAML_EXCEPTION)) {
                return true;
            }
        }
        return false;
    }

    /** Reads SnakeYAML's types. It is loaded only after a SnakeYAML exception was found, so SnakeYAML is present. */
    private static final class Yaml {

        private Yaml() {
        }

        /** The problem's position, 1-based ({@link Mark} counts lines and columns from 0), or {@code null}. */
        static @Nullable String position(Throwable failure) {
            if (failure instanceof MarkedYAMLException marked) {
                Mark mark = marked.getProblemMark();
                if (mark != null) {
                    return " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
                }
            }
            return null;
        }
    }

    /** A file failure reduced to its type, position and stack trace. */
    private static final class SanitizedFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SanitizedFailure(String message) {
            super(message, null, false, true);
        }
    }
}
