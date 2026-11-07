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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one part of a JDBC URL Synapse ever shows. A URL may carry a user name or password, so messages, logs and
 * {@code toString()} show only its scheme.
 */
public final class JdbcUrls {

    /** A parameter's name: what precedes an {@code =} at the start of a URL, a parameter list or an element of one. */
    private static final Pattern PARAMETER_NAME = Pattern.compile("(^|[?&;(),:\\s])\\s*([^?&;(),:=\\s]+)\\s*=");

    /**
     * A credential-like parameter name, once normalized (lower case, without {@code _}, {@code -} and {@code .}): a
     * user name ({@code user}, {@code username}, {@code userid}, {@code uid}, {@code login}) as the whole name, a
     * password word ({@code password}, {@code passwd}, {@code pwd}, optionally followed by digits) at its end, or
     * {@code secret}, {@code token}, {@code passphrase}, {@code passcode}, {@code apikey} or {@code credential}
     * anywhere in it.
     */
    private static final Pattern CREDENTIAL_NAME = Pattern
            .compile("^(user|username|userid|uid|login)$|(password|passwd|pwd)\\d*$|secret|token|passphrase|passcode"
                    + "|apikey|credential");

    private static final String ORACLE = "jdbc:oracle:";

    private static final String JDBC = "jdbc:";

    private static final String NO_SCHEME = "no scheme";

    private JdbcUrls() {
    }

    /**
     * Returns the scheme of a JDBC URL: {@code jdbc:} and the subprotocol after it, which is cut at the first
     * character that is not a letter, digit, {@code +}, {@code -}, {@code .} or {@code _} (such as the next {@code :},
     * {@code /}, {@code @}, {@code ?}, {@code ;} or {@code =}). A malformed URL never shows what follows, such as a
     * user name and password: one that doesn't start with {@code jdbc:} and a letter has no scheme.
     *
     * @param url a JDBC URL
     * @return the scheme, such as {@code jdbc:postgresql}, or {@code no scheme}
     */
    public static String scheme(String url) {
        if (!url.regionMatches(true, 0, JDBC, 0, JDBC.length()) || url.length() == JDBC.length()
                || !isLetter(url.charAt(JDBC.length()))) {
            return NO_SCHEME;
        }
        int end = JDBC.length() + 1;
        while (end < url.length() && isSubprotocolCharacter(url.charAt(end))) {
            end++;
        }
        return url.substring(0, end);
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isSubprotocolCharacter(char c) {
        return isLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.' || c == '_';
    }

    /**
     * Tells whether a JDBC URL carries credentials, or may: it fails closed, since drivers and pools log the URL. The
     * URL is checked as written, with {@code +} read as a space, and each of these percent-decoded once and twice
     * (leniently: each run of valid {@code %XX} escapes is decoded as UTF-8, and a malformed escape such as
     * {@code %zz} or a trailing {@code %} stays as written). Any form with one of these carries credentials:
     * <ul>
     * <li>a parameter with a credential-like name, after {@code ?}, {@code &},
     * {@code ;}, {@code (}, {@code )}, {@code ,}, {@code :} or white space, in any case; names such as
     * {@code passwordCharacterEncoding}, {@code applicationUser} or {@code loginTimeout} don't count. Normalized (lower
     * case, without {@code _}, {@code -} and {@code .}), a name is credential-like when it is {@code user},
     * {@code username}, {@code userid}, {@code uid} or {@code login}, ends in {@code password}, {@code passwd} or
     * {@code pwd} (optionally followed by digits), or contains {@code secret}, {@code token}, {@code passphrase},
     * {@code passcode}, {@code apikey} or {@code credential};</li>
     * <li>user information: an {@code @} between the first {@code //} and the first {@code /}, {@code ?} or
     * {@code #};</li>
     * <li>Oracle's {@code user/password@}: text between {@code jdbc:oracle:<subprotocol>:} and the first {@code @},
     * other than the wallet form's {@code /} ({@code jdbc:oracle:thin:/@alias}).</li>
     * </ul>
     *
     * @param url a JDBC URL
     * @return {@code true} when the URL carries, or may carry, a credential
     */
    public static boolean hasCredentials(String url) {
        Set<String> forms = new LinkedHashSet<>();
        for (String written : List.of(url, url.replace('+', ' '))) {
            String once = decode(written);
            String twice = decode(once);
            forms.addAll(List.of(written, once, twice, once.replace('+', ' '), twice.replace('+', ' ')));
        }
        return forms.stream().anyMatch(JdbcUrls::carriesCredentials);
    }

    private static boolean carriesCredentials(String url) {
        return hasCredentialParameter(url) || hasUserInfo(url) || hasOracleUser(url);
    }

    private static boolean hasCredentialParameter(String url) {
        Matcher names = PARAMETER_NAME.matcher(url);
        while (names.find()) {
            String written = names.group(2);
            if (written == null) {
                continue;
            }
            String name = written.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
            if (CREDENTIAL_NAME.matcher(name).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes each run of valid {@code %XX} escapes as UTF-8 (malformed bytes become U+FFFD), and keeps everything
     * else, including a malformed escape, as written.
     */
    private static String decode(String url) {
        StringBuilder decoded = new StringBuilder(url.length());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = 0;
        while (i < url.length()) {
            if (isEscape(url, i)) {
                bytes.reset();
                while (isEscape(url, i)) {
                    bytes.write(Integer.parseInt(url.substring(i + 1, i + 3), 16));
                    i += 3;
                }
                decoded.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            } else {
                decoded.append(url.charAt(i));
                i++;
            }
        }
        return decoded.toString();
    }

    private static boolean isEscape(String url, int index) {
        return index + 2 < url.length() && url.charAt(index) == '%'
                && Character.digit(url.charAt(index + 1), 16) >= 0 && Character.digit(url.charAt(index + 2), 16) >= 0;
    }

    private static boolean hasUserInfo(String url) {
        int authority = url.indexOf("//");
        if (authority < 0) {
            return false;
        }
        for (int i = authority + 2; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                return false;
            }
            if (c == '@') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOracleUser(String url) {
        if (!url.toLowerCase(Locale.ROOT).startsWith(ORACLE)) {
            return false;
        }
        int colon = url.indexOf(':', ORACLE.length());
        int at = url.indexOf('@');
        if (colon < 0 || at <= colon) {
            return false;
        }
        String user = url.substring(colon + 1, at);
        return !user.isEmpty() && !"/".equals(user);
    }
}
