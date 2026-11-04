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

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessagesTest {

    enum LibraryErrors implements ErrorDetails {

        BOOK_NOT_FOUND("Book {0} was not found"),

        QUOTED("Can''t lend {0}");

        private final String template;

        LibraryErrors(String template) {
            this.template = template;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return template;
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.NOT_FOUND;
        }
    }

    private final StaticMessageSource source = new StaticMessageSource();

    private final ErrorMessages messages = new ErrorMessages(source);

    @Test
    void usesTemplateByDefault() {
        SynapseException e = new SynapseException(LibraryErrors.BOOK_NOT_FOUND, 12345);

        assertThat(messages.detail(e, Locale.US)).isEqualTo("Book 12345 was not found");
        assertThat(messages.detail(e, null)).isEqualTo("Book 12345 was not found");
    }

    @Test
    void defaultMessageIsNotFormattedTwice() {
        SynapseException e = new SynapseException(LibraryErrors.QUOTED, "{0} isn't here");

        assertThat(e.getMessage()).isEqualTo("Can't lend {0} isn't here");
        assertThat(messages.detail(e, Locale.US)).isEqualTo("Can't lend {0} isn't here");
    }

    @Test
    void messageSourceOverridesByCode() {
        source.addMessage("BOOK_NOT_FOUND", Locale.US, "No book {0}");
        source.addMessage("BOOK_NOT_FOUND", Locale.FRENCH, "Aucun livre {0}");
        source.addMessage("BOOK_NOT_FOUND", Locale.ROOT, "Book {0} missing");
        SynapseException e = new SynapseException(LibraryErrors.BOOK_NOT_FOUND, "b-1");

        assertThat(messages.detail(e, Locale.US)).isEqualTo("No book b-1");
        assertThat(messages.detail(e, Locale.FRENCH)).isEqualTo("Aucun livre b-1");
        assertThat(messages.detail(e, null)).isEqualTo("Book b-1 missing");
    }

    @Test
    void aBareCodeFromTheMessageSourceFallsBackToTheTemplate() {
        // What Spring returns with spring.messages.use-code-as-default-message=true and no entry for the code.
        source.setUseCodeAsDefaultMessage(true);
        SynapseException e = new SynapseException(LibraryErrors.BOOK_NOT_FOUND, 12345);

        assertThat(messages.detail(e, Locale.US)).isEqualTo("Book 12345 was not found");
    }

    @Test
    void argsAreNotLocaleFormatted() {
        source.addMessage("BOOK_NOT_FOUND", Locale.US, "No book {0} ({1})");
        SynapseException e = new SynapseException(LibraryErrors.BOOK_NOT_FOUND, 12345, null);

        assertThat(messages.detail(e, Locale.US)).isEqualTo("No book 12345 (null)");
    }
}
