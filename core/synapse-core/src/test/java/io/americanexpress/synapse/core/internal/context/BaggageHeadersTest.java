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
package io.americanexpress.synapse.core.internal.context;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaggageHeadersTest {

    @Test
    void parsesMembersWithPropertiesAndWhitespace() {
        assertThat(BaggageHeaders.parse("a=1, b = x%20y ;p=1"))
                .containsExactly(
                        new BaggageHeaders.Entry("a", "1", ""), new BaggageHeaders.Entry("b", "x y", ";p=1"));
    }

    @Test
    void skipsMalformedMembers() {
        assertThat(BaggageHeaders.parse("a=1,=x,novalue,b=2"))
                .extracting(BaggageHeaders.Entry::key)
                .containsExactly("a", "b");
    }

    @Test
    void skipsMembersThatFailPercentDecoding() {
        assertThat(BaggageHeaders.parse("a=%zz,b=2")).extracting(BaggageHeaders.Entry::key).containsExactly("b");
    }

    @Test
    void parsesNullAndBlankAsEmpty() {
        assertThat(BaggageHeaders.parse(null)).isEmpty();
        assertThat(BaggageHeaders.parse("  ")).isEmpty();
    }

    @Test
    void retainKeepsOnlyListedKeys() {
        assertThat(BaggageHeaders.retain("correlationId=c1,tenant=t,secret=s", Set.of("correlationId", "tenant")))
                .isEqualTo("correlationId=c1,tenant=t");
    }

    @Test
    void retainReturnsNullWhenNothingKept() {
        assertThat(BaggageHeaders.retain("secret=s", Set.of("tenant"))).isNull();
        assertThat(BaggageHeaders.retain(null, Set.of("tenant"))).isNull();
    }

    @Test
    void removeDropsListedKeys() {
        assertThat(BaggageHeaders.remove("correlationId=evil,partner=p", Set.of("correlationId")))
                .isEqualTo("partner=p");
    }

    @Test
    void removeReturnsNullWhenNothingLeft() {
        assertThat(BaggageHeaders.remove("a=1", Set.of("a"))).isNull();
    }

    @Test
    void formatPercentEncodesValues() {
        assertThat(BaggageHeaders.format(List.of(new BaggageHeaders.Entry("k", "a,b;c", ""))))
                .isEqualTo("k=a%2Cb%3Bc");
    }

    @Test
    void formatKeepsPropertiesAndEncodesNonAscii() {
        assertThat(BaggageHeaders
                .format(List
                        .of(new BaggageHeaders.Entry("k", "é y", ";p=1"),
                                new BaggageHeaders.Entry("j", "2", ""))))
                .isEqualTo("k=%C3%A9%20y;p=1,j=2");
    }

    @Test
    void literalPlusStaysPlus() {
        assertThat(BaggageHeaders.parse("a=x+y")).containsExactly(new BaggageHeaders.Entry("a", "x+y", ""));
    }

    @Test
    void skipsTrailingLonePercentAndTruncatedEscape() {
        assertThat(BaggageHeaders.parse("a=%,b=%2,c=3")).extracting(BaggageHeaders.Entry::key).containsExactly("c");
    }

    @Test
    void keepsAMemberWithAnEmptyValue() {
        assertThat(BaggageHeaders.parse("a=")).containsExactly(new BaggageHeaders.Entry("a", "", ""));
    }

    @Test
    void skipsEmptyMembers() {
        assertThat(BaggageHeaders.parse("a=1,,b=2")).extracting(BaggageHeaders.Entry::key).containsExactly("a", "b");
    }

    @Test
    void keepsDuplicateKeysInOrder() {
        assertThat(BaggageHeaders.parse("a=1,a=2"))
                .containsExactly(new BaggageHeaders.Entry("a", "1", ""),
                        new BaggageHeaders.Entry("a", "2", ""));
        assertThat(BaggageHeaders.retain("a=1,b=x,a=2", Set.of("a"))).isEqualTo("a=1,a=2");
    }
}
