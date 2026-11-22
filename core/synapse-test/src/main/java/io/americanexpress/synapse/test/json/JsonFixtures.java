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
package io.americanexpress.synapse.test.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.util.ClassUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads JSON fixtures from the test classpath into objects.
 */
public final class JsonFixtures {

    private final JsonMapper mapper;

    private JsonFixtures(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Returns fixtures read with a default Jackson 3 {@link JsonMapper}.
     *
     * @return the fixtures
     */
    public static JsonFixtures standard() {
        return new JsonFixtures(JsonMapper.builder().build());
    }

    /**
     * Returns fixtures read with the given mapper.
     *
     * @param mapper the mapper
     * @return the fixtures
     */
    public static JsonFixtures of(JsonMapper mapper) {
        return new JsonFixtures(Objects.requireNonNull(mapper, "mapper"));
    }

    /**
     * Reads a fixture into a type.
     *
     * @param <T> the type
     * @param resource the classpath resource; a leading {@code /} is accepted
     * @param type the type
     * @return the object
     * @throws IllegalArgumentException if the resource does not exist
     */
    public <T> T read(String resource, Class<T> type) {
        try (InputStream in = open(resource)) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JSON fixture " + resource, e);
        }
    }

    /**
     * Reads a fixture into a generic type, for example a list.
     *
     * @param <T> the type
     * @param resource the classpath resource; a leading {@code /} is accepted
     * @param type the type
     * @return the object
     * @throws IllegalArgumentException if the resource does not exist
     */
    public <T> T read(String resource, TypeReference<T> type) {
        try (InputStream in = open(resource)) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JSON fixture " + resource, e);
        }
    }

    /**
     * Returns the fixture's text (UTF-8).
     *
     * @param resource the classpath resource; a leading {@code /} is accepted
     * @return the text
     * @throws IllegalArgumentException if the resource does not exist
     */
    public String text(String resource) {
        try (InputStream in = open(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read JSON fixture " + resource, e);
        }
    }

    private static InputStream open(String resource) {
        String path = resource.startsWith("/") ? resource.substring(1) : resource;
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        InputStream in = loader == null ? null : loader.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalArgumentException("JSON fixture not found on the classpath: " + resource);
        }
        return in;
    }
}
