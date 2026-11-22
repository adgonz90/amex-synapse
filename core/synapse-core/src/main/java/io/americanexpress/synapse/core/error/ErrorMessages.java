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
package io.americanexpress.synapse.core.error;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;

/**
 * Resolves the caller-facing message (detail) of a {@link SynapseException}. A {@link MessageSource} entry keyed by
 * the error code overrides the error's message template; without one, the exception's own message is used. A
 * {@code MessageSource} answer that is exactly the code (which Spring returns with
 * {@code spring.messages.use-code-as-default-message=true}) counts as no entry.
 *
 * <p>
 * Template arguments are converted with {@link String#valueOf(Object)} before formatting, so numbers and dates print
 * exactly as given (an id {@code 12345} never becomes {@code 12,345}), as they do in the template itself.
 */
public final class ErrorMessages {

    private final MessageSource source;

    /**
     * Creates the resolver.
     *
     * @param source the message source whose entries, keyed by error code, override the templates
     */
    public ErrorMessages(MessageSource source) {
        this.source = source;
    }

    /**
     * Returns the message of the error for the caller.
     *
     * @param e the error
     * @param locale the caller's locale, or {@code null} for {@link Locale#ROOT}
     * @return the {@code MessageSource} entry for {@code e.code()} formatted with the error's arguments, or the
     *         exception's message when there is no entry
     */
    public String detail(SynapseException e, @Nullable Locale locale) {
        List<@Nullable Object> args = e.args();
        String[] text = new String[args.size()];
        for (int i = 0; i < text.length; i++) {
            text[i] = String.valueOf(args.get(i));
        }
        // No default message here: MessageSource would run the already-formatted message through MessageFormat
        // again, dropping apostrophes and re-reading braces that came from the arguments.
        String message = source.getMessage(e.code(), text, null, locale == null ? Locale.ROOT : locale);
        // Exactly the code means "no entry" when the source uses the code as its default message
        // (spring.messages.use-code-as-default-message=true).
        if (message != null && !message.equals(e.code())) {
            return message;
        }
        String fallback = e.getMessage();
        return fallback == null ? e.code() : fallback;
    }
}
