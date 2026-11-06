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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StringUtils;

/**
 * Names configuration sources for people: the label says where a value was set without printing the value.
 */
public final class ConfigSources {

    /** The start of the name of every source Boot's config data loaded from a file. */
    static final String CONFIG_RESOURCE_PREFIX = "Config resource ";

    /**
     * Boot's, spring-test's and Synapse's fixed source names. {@code synapseBaggageFields} is the source Synapse's
     * baggage post-processor adds first, so it outranks the command line.
     */
    private static final Map<String, String> LABELS = Map
            .of(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME, "command line arguments",
                    StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, "Java system properties",
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, "environment variables",
                    DefaultPropertiesPropertySource.NAME, "defaults", "active-test-profiles", "@ActiveProfiles",
                    "Inlined Test Properties", "test properties", "synapseBaggageFields", "Synapse baggage fields");

    /**
     * A source Boot's config data loaded, such as
     * {@code Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'}. Boot
     * numbers every document of a multi-document file, from {@code  (document #0)}; a single-document file has no
     * number.
     */
    private static final Pattern CONFIG_RESOURCE = Pattern
            .compile("^" + Pattern.quote(CONFIG_RESOURCE_PREFIX)
                    + "'[^\\[]*\\[(?<file>.*)\\]' via location .*?(?<document> \\(document #\\d+\\))?$");

    /**
     * A module source, such as {@code Synapse module config [config-e2.yaml] client-accounts-1.4.0.jar}. Like Boot's
     * sources, every document of a multi-document file is numbered, from {@code  (document #0)}.
     */
    private static final Pattern MODULE_SOURCE = Pattern
            .compile("^" + Pattern.quote(ModuleConfigFiles.SOURCE_PREFIX)
                    + "\\[(?<file>[^\\]]*)\\] (?<origin>.*?)(?<document> \\(document #\\d+\\))?$");

    /**
     * A URL-shaped part of a text: one or more schemes (such as {@code jar:https:}) followed by {@code //}, up to
     * whitespace, a quote or a bracket, the delimiters of Boot's source names.
     */
    private static final Pattern URL_SHAPED = Pattern.compile("(?:[A-Za-z][A-Za-z0-9+.\\-]*:)+//[^\\s'\\[\\]]*");

    /** A text that is a URL as a whole, such as the file of a {@code Config resource} source loaded from a URL. */
    private static final Pattern WHOLE_URL = Pattern.compile("(?:[A-Za-z][A-Za-z0-9+.\\-]*:)+//.*", Pattern.DOTALL);

    /** The scheme and the location (authority without user info, and path) of a URL that {@link URI} can't parse. */
    private static final Pattern HIERARCHICAL_URL = Pattern
            .compile("^(?<scheme>[A-Za-z][A-Za-z0-9+.\\-]*://)(?:[^/?#]*@)?(?<location>[^?#]*)");

    private static final String JAR_PREFIX = "jar:";

    private static final String JAR_SEPARATOR = "!/";

    private static final String FILE_PREFIX = "file:";

    private static final String CLASSES = "/target/classes";

    private static final String TEST_CLASSES = "/target/test-classes";

    /** The URL protocols of local files and archives: files and Boot's nested jar entries. */
    private static final Set<String> LOCAL_PROTOCOLS = Set.of("file", "nested");

    private ConfigSources() {
    }

    /**
     * Tells whether a URL is local, so Synapse may read it: a file, one of Boot's nested jar entries, or an entry of
     * a jar that is itself local. A {@code jar:} URL over a remote jar, such as
     * {@code jar:https://host/x.jar!/application.yaml}, is not: opening it would download the jar.
     *
     * @param url the URL
     * @return whether reading it does no remote I/O
     */
    static boolean isLocal(URL url) {
        String location = url.toExternalForm();
        while (location.regionMatches(true, 0, JAR_PREFIX, 0, JAR_PREFIX.length())) {
            location = location.substring(JAR_PREFIX.length());
        }
        int colon = location.indexOf(':');
        return colon > 0 && LOCAL_PROTOCOLS.contains(location.substring(0, colon).toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the label of a source: a readable name for Boot's and spring-test's fixed sources, the file of a
     * {@code Config resource} source or of a Synapse module source (with its document number, when it has one), and
     * the name of any other source.
     *
     * @param source the source
     * @return its label; a URL in it keeps no user info, query or fragment
     */
    public static String label(PropertySource<?> source) {
        String name = source.getName();
        String label = LABELS.get(name);
        if (label != null) {
            return label;
        }
        for (Pattern pattern : List.of(CONFIG_RESOURCE, MODULE_SOURCE)) {
            Matcher matcher = pattern.matcher(name);
            if (matcher.matches()) {
                String document = matcher.group("document");
                return redact(matcher.group("file")) + (document != null ? document : "");
            }
        }
        return redact(name);
    }

    /**
     * Removes what a URL can carry besides its location: the user info (credentials), the query (tokens, presigned
     * signatures) and the fragment of every URL-shaped part of a text, one with a scheme and {@code ://}, also inside
     * a {@code jar:} or {@code nested:} wrapper. The text is still percent-encoded, so an encoded delimiter can't hide
     * a credential. Local {@code file:/…}, {@code jar:file:/…} and {@code nested:/…} locations have no {@code ://} and
     * stay as they are.
     *
     * @param text a source name, a file or a location
     * @return the text with every URL's user info, query and fragment removed
     */
    static String redact(String text) {
        if (!text.contains("://")) {
            return text;
        }
        if (WHOLE_URL.matcher(text).matches()) {
            // A file or location that is a URL may hold a space, so the whole text is one URL.
            return redactUrl(text);
        }
        Matcher matcher = URL_SHAPED.matcher(text);
        StringBuilder redacted = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(redacted, Matcher.quoteReplacement(redactUrl(matcher.group())));
        }
        matcher.appendTail(redacted);
        return redacted.toString();
    }

    /**
     * Redacts one URL: wrapper schemes such as {@code jar:} are kept, the innermost {@code scheme://authority/path}
     * loses its user info, query and fragment, and a jar entry after {@code !/} loses its query and fragment.
     */
    private static String redactUrl(String url) {
        int separator = url.indexOf("://");
        int schemeStart = url.lastIndexOf(':', separator - 1) + 1;
        String wrappers = url.substring(0, schemeStart);
        String inner = url.substring(schemeStart);
        String entry = "";
        int entryStart = inner.indexOf(JAR_SEPARATOR, inner.indexOf("://") + 3);
        if (entryStart >= 0) {
            entry = withoutQueryOrFragment(inner.substring(entryStart));
            inner = inner.substring(0, entryStart);
        }
        return wrappers + redactHierarchical(inner) + entry;
    }

    /** {@code scheme://authority/path} without user info, query or fragment, parsed with {@link URI} when it can be. */
    private static String redactHierarchical(String url) {
        try {
            URI uri = new URI(url);
            String authority = uri.getRawAuthority();
            if (uri.getScheme() != null && authority != null) {
                String path = uri.getRawPath();
                return uri.getScheme() + "://" + authority.substring(authority.lastIndexOf('@') + 1)
                        + (path != null ? path : "");
            }
        } catch (URISyntaxException ex) {
            // Not a valid URI, such as a path with a space: the pattern below still finds the parts.
        }
        Matcher matcher = HIERARCHICAL_URL.matcher(url);
        return matcher.find() ? matcher.group("scheme") + matcher.group("location") : withoutQueryOrFragment(url);
    }

    private static String withoutQueryOrFragment(String value) {
        int end = value.length();
        for (char delimiter : new char[]{'?', '#'}) {
            int index = value.indexOf(delimiter);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return value.substring(0, end);
    }

    /**
     * Returns where the file behind a source comes from: for a {@code Config resource} source, the classpath root or
     * directory of the resource it was loaded from (found through the {@link TextResourceOrigin} of its keys), named
     * as {@link #origin(URL, String)} names it; for a Synapse module source, the origin in its name.
     *
     * @param source the source
     * @return the origin, or {@code null} for any other source and for a {@code Config resource} source whose
     *         resource is unknown or has no URL
     */
    public static @Nullable String origin(PropertySource<?> source) {
        String name = source.getName();
        Matcher module = MODULE_SOURCE.matcher(name);
        if (module.matches()) {
            return redact(module.group("origin"));
        }
        if (!name.startsWith(CONFIG_RESOURCE_PREFIX)) {
            return null;
        }
        Resource resource = resource(source);
        if (resource == null) {
            return null;
        }
        String path = resource instanceof ClassPathResource classPath ? classPath.getPath() : resource.getFilename();
        if (path == null) {
            return null;
        }
        try {
            return origin(resource.getURL(), path);
        } catch (IOException ex) {
            return null;
        }
    }

    /**
     * Returns the resource a {@code Config resource} source was loaded from, through the {@link TextResourceOrigin} of
     * any of its keys.
     *
     * @param source the source
     * @return the resource, or {@code null} when the source is not enumerable or no key has a resource origin
     */
    static @Nullable Resource resource(PropertySource<?> source) {
        if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
            return null;
        }
        for (String key : enumerable.getPropertyNames()) {
            for (Origin origin = OriginLookup.getOrigin(source, key); origin != null; origin = origin.getParent()) {
                if (origin instanceof TextResourceOrigin text && text.getResource() != null) {
                    return text.getResource();
                }
            }
        }
        return null;
    }

    /**
     * Returns where a classpath resource comes from, named by the classpath root that holds it: a jar by its file name
     * (the innermost jar of a Boot nested-jar URL, or the fat jar itself for its {@code BOOT-INF/classes/}), a Maven
     * module's {@code target/classes} directory by the module's name, its {@code target/test-classes} directory by
     * the module's name and {@code (test)}, and any other location by its path.
     *
     * @param url the resource's URL, as a class loader returns it
     * @param resourcePath the resource's path under the classpath root, such as
     *        {@code META-INF/synapse/config.yaml}
     * @return the origin, without the URL's user info, query or fragment; percent-encoded characters are decoded
     *         after those are removed
     */
    public static String origin(URL url, String resourcePath) {
        String location = root(redact(url.toExternalForm()), resourcePath);
        if (location.startsWith(JAR_PREFIX)) {
            return archiveName(location.substring(JAR_PREFIX.length()));
        }
        if (location.startsWith(FILE_PREFIX)) {
            return directoryName(location.substring(FILE_PREFIX.length()));
        }
        return decode(stripTrailingSlash(location));
    }

    /**
     * Returns the classpath root of a resource: its URL without the resource path, such as
     * {@code jar:file:/r/client-accounts-1.4.0.jar!/} or {@code file:/w/client-accounts/target/classes/}.
     *
     * @param url the resource's URL
     * @param resourcePath the resource's path under the classpath root
     * @return the root, or the whole URL when it doesn't end with the path
     */
    static String root(URL url, String resourcePath) {
        return root(url.toExternalForm(), resourcePath);
    }

    private static String root(String location, String resourcePath) {
        return location.endsWith(resourcePath)
                ? location.substring(0, location.length() - resourcePath.length())
                : location;
    }

    /**
     * Returns the full location of a classpath root, for when its origin is not unique: the root without its
     * {@code jar:} and {@code file:} prefixes and its trailing {@code !/} or {@code /}, such as
     * {@code /w/a/client/target/classes}, {@code /r/a/client.jar} or
     * {@code nested:/app/books.jar/!BOOT-INF/lib/client.jar}, percent-encoded characters decoded.
     *
     * @param root a root, as {@link #root(URL, String)} returns it
     * @return its location
     */
    static String location(String root) {
        String location = redact(root);
        for (String prefix : List.of(JAR_PREFIX, FILE_PREFIX)) {
            if (location.startsWith(prefix)) {
                location = location.substring(prefix.length());
            }
        }
        if (location.endsWith(JAR_SEPARATOR)) {
            location = location.substring(0, location.length() - JAR_SEPARATOR.length());
        }
        return decode(stripTrailingSlash(location));
    }

    /**
     * The name of the innermost jar in a jar URL without its {@code jar:} prefix, such as
     * {@code file:/r/client-accounts-1.4.0.jar!/} or
     * {@code nested:/app/books.jar/!BOOT-INF/lib/client-accounts-1.4.0.jar!/}; an entry that is not a jar, such as
     * {@code BOOT-INF/classes/}, belongs to the jar that holds it.
     */
    private static String archiveName(String location) {
        String archive = location.endsWith(JAR_SEPARATOR)
                ? location.substring(0, location.length() - JAR_SEPARATOR.length())
                : location;
        String[] parts = StringUtils.delimitedListToStringArray(archive, "!");
        String name = parts[0];
        for (int i = parts.length - 1; i > 0; i--) {
            String part = stripTrailingSlash(parts[i]);
            if (part.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                name = part;
                break;
            }
        }
        name = stripTrailingSlash(name);
        return decode(name.substring(name.lastIndexOf('/') + 1));
    }

    /** The name of a classpath directory, from the path of a {@code file:} URL. */
    private static String directoryName(String location) {
        String path = location;
        while (path.startsWith("//")) {
            path = path.substring(1);
        }
        path = decode(stripTrailingSlash(path));
        if (path.endsWith(CLASSES)) {
            return lastSegment(path.substring(0, path.length() - CLASSES.length()), path);
        }
        if (path.endsWith(TEST_CLASSES)) {
            return lastSegment(path.substring(0, path.length() - TEST_CLASSES.length()), path) + " (test)";
        }
        return path;
    }

    private static String lastSegment(String module, String path) {
        String name = module.substring(module.lastIndexOf('/') + 1);
        return name.isEmpty() ? path : name;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") && value.length() > 1 ? value.substring(0, value.length() - 1) : value;
    }

    private static String decode(String value) {
        return StringUtils.uriDecode(value, StandardCharsets.UTF_8);
    }
}
