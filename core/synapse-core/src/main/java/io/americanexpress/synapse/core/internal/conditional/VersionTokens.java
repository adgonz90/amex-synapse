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
package io.americanexpress.synapse.core.internal.conditional;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.util.ClassUtils;

/**
 * The one codec of version values and their entity tag tokens (the opaque value inside {@code "..."}), shared by every
 * protocol: REST's {@code ETag}, {@code If-Match} and {@code If-None-Match}, and gRPC's {@code etag} and
 * {@code if_none_match} fields. So a token one protocol writes, the other reads as the same version.
 *
 * <p>
 * It never uses the locale, Spring MVC's or any other conversion service, or formatting annotations:
 * <ul>
 * <li>a {@code String} (or {@code CharSequence}) is itself, and must be a non-empty run of RFC 9110 {@code etagc};</li>
 * <li>an integral number ({@code byte}, {@code short}, {@code int}, {@code long}, boxed or not, and
 * {@code BigInteger}) is plain decimal: an optional {@code -} and digits, without a leading {@code +} or zero;</li>
 * <li>a {@code java.time} value ({@code Instant}, {@code LocalDate}, {@code LocalDateTime}, {@code LocalTime},
 * {@code OffsetDateTime}, {@code OffsetTime}, {@code ZonedDateTime}, {@code Year}, {@code YearMonth}) is ISO-8601, as
 * its {@code toString} writes it and its {@code parse} reads it.</li>
 * </ul>
 * Nothing else is a version type, and the API plugins check that at startup. No message repeats the value.
 */
public final class VersionTokens {

    /** The version types, as messages name them. */
    public static final String TYPES = "a String, an integral number (byte, short, int, long or BigInteger) or a "
            + "java.time value (Instant, LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, "
            + "ZonedDateTime, Year or YearMonth)";

    /** Plain decimal: zero, or an optional minus and digits without a leading zero. */
    private static final Pattern DECIMAL = Pattern.compile("0|-?[1-9][0-9]*");

    private static final Map<Class<?>, Function<String, Object>> INTEGRAL = Map
            .of(Byte.class, Byte::valueOf, Short.class, Short::valueOf, Integer.class, Integer::valueOf, Long.class,
                    Long::valueOf, BigInteger.class, BigInteger::new);

    private static final Map<Class<?>, Function<String, Object>> TIME = Map
            .of(Instant.class, Instant::parse, LocalDate.class, LocalDate::parse, LocalDateTime.class,
                    LocalDateTime::parse, LocalTime.class, LocalTime::parse, OffsetDateTime.class,
                    OffsetDateTime::parse, OffsetTime.class, OffsetTime::parse, ZonedDateTime.class,
                    ZonedDateTime::parse, Year.class, Year::parse, YearMonth.class, YearMonth::parse);

    private VersionTokens() {
    }

    /**
     * Tells whether a type is a version type.
     *
     * @param type the type, primitive or not
     * @return whether {@link #format} and {@link #parse} take it
     */
    public static boolean supports(Class<?> type) {
        Class<?> boxed = ClassUtils.resolvePrimitiveIfNecessary(type);
        return boxed == String.class || boxed == CharSequence.class || INTEGRAL.containsKey(boxed)
                || TIME.containsKey(boxed);
    }

    /**
     * Returns the token of a version value: the opaque value of its strong entity tag.
     *
     * @param value the version
     * @return the token, a non-empty run of {@code etagc}
     * @throws IllegalArgumentException when the value is not of a version type, or is a string that is empty or has a
     *         character an entity tag can't carry
     */
    public static String format(Object value) {
        Class<?> type = value.getClass();
        if (value instanceof CharSequence text) {
            return checked(text.toString());
        }
        if (!supports(type)) {
            throw notAVersionType(type);
        }
        // Integral numbers print in plain decimal and java.time values in ISO-8601, whatever the locale.
        return checked(value.toString());
    }

    /**
     * Returns the version a token names.
     *
     * @param token the opaque value of an entity tag
     * @param type the version type, primitive or not
     * @return the version, never {@code null}
     * @throws IllegalArgumentException when the type is not a version type, or the token doesn't name a version of it
     *         (empty, not plain decimal, not ISO-8601, or out of range)
     */
    public static Object parse(String token, Class<?> type) {
        Class<?> boxed = ClassUtils.resolvePrimitiveIfNecessary(type);
        if (!supports(boxed)) {
            throw notAVersionType(boxed);
        }
        if (token.isEmpty()) {
            throw new IllegalArgumentException("The version token is empty");
        }
        if (boxed == String.class || boxed == CharSequence.class) {
            return checked(token);
        }
        Function<String, Object> integral = INTEGRAL.get(boxed);
        if (integral != null) {
            if (!DECIMAL.matcher(token).matches()) {
                throw new IllegalArgumentException("The version token is not a plain decimal number");
            }
            // NumberFormatException, for a value out of range, is an IllegalArgumentException.
            return integral.apply(token);
        }
        Function<String, Object> time = Objects.requireNonNull(TIME.get(boxed));
        try {
            return time.apply(token);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("The version token is not an ISO-8601 " + boxed.getSimpleName(), e);
        }
    }

    private static String checked(String token) {
        if (token.isEmpty()) {
            throw new IllegalArgumentException("The version token is empty");
        }
        for (int i = 0; i < token.length(); i++) {
            if (!EntityTags.isEtagc(token.charAt(i))) {
                throw new IllegalArgumentException("The version token has a character an entity tag can't carry (a "
                        + "double quote, a space, a control character, DEL or a character above U+00FF)");
            }
        }
        return token;
    }

    private static IllegalArgumentException notAVersionType(Class<?> type) {
        return new IllegalArgumentException(type.getSimpleName() + " is not a version type: a version is " + TYPES);
    }
}
