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

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUrlsTest {

    /** URLs that carry a credential, or a parameter that may be one; shared with {@code StoreChecksTest}. */
    static final String[] WITH_CREDENTIALS = {"jdbc:postgresql://app:s3cr3t@db:5432/books",
            "jdbc:postgresql://app@db/books", "jdbc:postgresql://db/books?user=app",
            "jdbc:postgresql://db/books?ssl=true&Password=s3cr3t", "jdbc:sqlserver://db;databaseName=books;user=app",
            "jdbc:h2:mem:books;USERNAME=app", "jdbc:oracle:thin:app/s3cr3t@db:1521:books",
            "JDBC:ORACLE:THIN:app@db:1521:books", "jdbc:postgresql://db/books?sslpassword=s3cr3t",
            "jdbc:postgresql://db/books?ssl=true&sslPassword=s3cr3t", "jdbc:postgresql://db/books? password = s3cr3t",
            "jdbc:postgresql://db/books?pass%77ord=s3cr3t", "jdbc:postgresql:books?password=s3cr3t",
            "jdbc:postgresql://db/books?PWD=s3cr3t", "jdbc:postgresql://db/books?uid=app",
            "jdbc:h2:mem:books;FILE_PASSWORD=s3cr3t", "jdbc:sqlserver://db;trustStorePassword=s3cr3t",
            "jdbc:sqlserver://db;keyStoreSecret=s3cr3t",
            "jdbc:mysql://address=(host=db)(user=app)(password=s3cr3t)/books",
            "jdbc:mysql://(host=db,user=app,password=s3cr3t)/books", "jdbc:oracle:oci:scott/s3cr3t@db",
            "jdbc:x://db;a=u:s3cr3t@h/books", "jdbc:postgresql://db/books?password=100%",
            // Malformed escapes don't stop the decoding of the valid ones.
            "jdbc:postgresql://db/books?pass%77ord=s3cr3t&q=%zz", "jdbc:postgresql://db/books?pass%77ord=s3cr3t&q=100%",
            "jdbc:postgresql://db/books?q=%zz&ssl%70assword=s3cr3t", "jdbc:postgresql://app%3As3cr3t%40db/books?q=%zz",
            // Decoded twice, and with + read as a space.
            "jdbc:postgresql://db/books?pass%2577ord=s3cr3t", "jdbc:postgresql://db/books?ssl=true+password=s3cr3t",
            // Credential-like names.
            "jdbc:postgresql://db/books?password1=s3cr3t", "jdbc:postgresql://db/books?PASSWORD2=s3cr3t",
            "jdbc:postgresql://db/books?accessToken=s3cr3t", "jdbc:postgresql://db/books?sessionToken=s3cr3t",
            "jdbc:postgresql://db/books?authToken=s3cr3t", "jdbc:postgresql://db/books?passphrase=s3cr3t",
            "jdbc:postgresql://db/books?passcode=s3cr3t", "jdbc:postgresql://db/books?secretKey=s3cr3t",
            "jdbc:postgresql://db/books?client_secret=s3cr3t", "jdbc:postgresql://db/books?oauthCredential=s3cr3t",
            "jdbc:postgresql://db/books?apikey=s3cr3t", "jdbc:postgresql://db/books?api_key=s3cr3t",
            "jdbc:postgresql://db/books?API-KEY=s3cr3t", "jdbc:postgresql://db/books?user_name=app",
            "jdbc:postgresql://db/books?userid=app", "jdbc:postgresql://db/books?user.id=app",
            "jdbc:postgresql://db/books?login=app", "jdbc:postgresql://db/books?ssl$password=s3cr3t",
            "jdbc:h2:mem:books;AUTHZPWD=s3cr3t",
            // PgJDBC's forms: user information before a host list or an IPv6 host, the parameters of a host list
            // or of the host-less forms.
            "jdbc:postgresql://app:s3cr3t@h1:5432,h2:5432/books?targetServerType=primary",
            "jdbc:postgresql://h1:5432,app:s3cr3t@h2:5432/books", "jdbc:postgresql://app:s3cr3t@[::1]:5432/books",
            "jdbc:postgresql://h1,h2/books?loadBalanceHosts=true&password=s3cr3t",
            "jdbc:postgresql://db/books?user=app&password=s3cr3t", "jdbc:postgresql:///books?password=s3cr3t",
            "jdbc:postgresql:books?user=app", "jdbc:postgresql://db/books?sslmode=verify-full&sslpassword=s3cr3t"};

    static Stream<String> withCredentials() {
        return Stream.of(WITH_CREDENTIALS);
    }

    @ParameterizedTest
    @MethodSource("withCredentials")
    void credentialsAreFound(String url) {
        assertThat(JdbcUrls.hasCredentials(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://db:5432/books",
            "jdbc:postgresql://h1:5432,h2:5432/books?sslmode=require&ApplicationName=svc",
            "jdbc:postgresql://db/books?passwordCharacterEncoding=x", "jdbc:postgresql://db/books?applicationUser=x",
            "jdbc:oracle:thin:@db:1521/books", "jdbc:oracle:thin:@//db:1521/books", "jdbc:oracle:thin:/@alias",
            "jdbc:h2:mem:books;DB_CLOSE_DELAY=-1", "jdbc:postgresql://db/books?options=-c%20search_path=a@b",
            "jdbc:postgresql://db/books?q=%zz", "jdbc:postgresql://db/books?q=100%",
            "jdbc:postgresql://db/books?sslpasswordcallback=com.example.Callback",
            "jdbc:postgresql://db/books?sslkey=/k.pk8", "jdbc:postgresql://db/books?pemKeyAlgorithm=RSA",
            "jdbc:postgresql://db/books?gssUseDefaultCreds=true",
            "jdbc:postgresql://db/books?authenticationPluginClassName=com.example.Plugin",
            "jdbc:postgresql://db/books?jaasLogin=false", "jdbc:postgresql://db/books?requireAuth=scram-sha-256",
            "jdbc:postgresql://db/books?loginTimeout=5", "jdbc:postgresql://db/books?ApplicationName=svc",
            "jdbc:h2:mem:books;PASSWORD_HASH=TRUE;AUTHREALM=r;DB_CLOSE_DELAY=-1",
            // PgJDBC's host lists, IPv6 hosts and socket factories.
            "jdbc:postgresql://h1:5432,h2:5432/books?targetServerType=primary",
            "jdbc:postgresql://h1,h2,h3/books?targetServerType=preferSecondary&loadBalanceHosts=true",
            "jdbc:postgresql://[::1]:5432/books", "jdbc:postgresql://[2001:db8::1],[2001:db8::2]:5433/books",
            "jdbc:postgresql://localhost/books?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
                    + "&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432",
            "jdbc:postgresql://db/books?sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&sslmode=verify-full",
            "jdbc:postgresql:books", "jdbc:postgresql:///books?ApplicationName=svc"})
    void urlsWithoutCredentialsPass(String url) {
        assertThat(JdbcUrls.hasCredentials(url)).isFalse();
    }

    /**
     * Review Focus 1 and I2: the scheme is the subprotocol after {@code jdbc:}, cut at the first character that can't
     * be part of one, so a malformed URL never shows what follows it, such as a user name and password.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"jdbc:postgresql://db:5432/books|jdbc:postgresql",
            "JDBC:ORACLE:THIN:@db:1521:books|JDBC:ORACLE", "jdbc:h2:mem:books;DB_CLOSE_DELAY=-1|jdbc:h2",
            "jdbc:sqlserver://db;databaseName=books|jdbc:sqlserver", "jdbc:admin:s3cr3t@db|jdbc:admin",
            "jdbc:postgresql//app:s3cr3t@db/books|jdbc:postgresql", "jdbc:h2@s3cr3t:mem|jdbc:h2",
            "jdbc:x?password=s3cr3t:y|jdbc:x", "jdbc:x;password=s3cr3t:y|jdbc:x",
            "jdbc:password=s3cr3t:y|jdbc:password", "jdbc:x s3cr3t:y|jdbc:x",
            "admin:s3cr3t@db:5432/books|no scheme", "s3:cr3t:et|no scheme", "s3cr3t|no scheme", "jdbc:|no scheme",
            "jdbc::s3cr3t:x|no scheme", "jdbc:@s3cr3t:x|no scheme"})
    void schemeIsTheSubprotocolOnly(String url, String scheme) {
        assertThat(JdbcUrls.scheme(url)).isEqualTo(scheme).doesNotContain("s3cr3t", "cr3t", "app");
    }
}
