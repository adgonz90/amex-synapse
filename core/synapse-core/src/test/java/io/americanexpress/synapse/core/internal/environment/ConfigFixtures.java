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
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Builds real {@link SpringApplication} runs over fixture jars and directories, for tests of the environment
 * post-processor. Fixture runs never use the environment name {@code test}: the fixture class loader delegates to the
 * test class loader first, where the module's own {@code application-test.yaml} would answer.
 */
public final class ConfigFixtures {

    private ConfigFixtures() {
    }

    /**
     * Writes a jar.
     *
     * @param dir the directory to write it in
     * @param fileName the jar's file name
     * @param entries entry path to UTF-8 content
     * @return the jar
     */
    public static Path jar(Path dir, String fileName, Map<String, String> entries) {
        Path jar = dir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jarOut.putNextEntry(new JarEntry(entry.getKey()));
                jarOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return jar;
    }

    /**
     * Writes a directory of files, such as a module's or a service's {@code target/classes}.
     *
     * @param dir the directory to create it in
     * @param name the directory's name
     * @param entries file path (relative to the new directory) to UTF-8 content
     * @return the directory
     */
    public static Path directory(Path dir, String name, Map<String, String> entries) {
        Path root = dir.resolve(name);
        try {
            Files.createDirectories(root);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                Path file = root.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return root;
    }

    /**
     * Creates a class loader over the given jars and directories, whose parent is the test class loader.
     *
     * @param roots the jars and directories, in classpath order
     * @return the class loader; the caller closes it
     */
    public static URLClassLoader classLoader(Path... roots) {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            try {
                urls[i] = roots[i].toUri().toURL();
            } catch (MalformedURLException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return new URLClassLoader(urls, ConfigFixtures.class.getClassLoader());
    }

    /**
     * Creates an application that loads its resources through the given class loader and sees only the given
     * environment variables, never the machine's.
     *
     * @param classLoader the class loader for resources and {@code spring.factories}
     * @param environmentVariables the environment variables
     * @return a non-web application with an empty configuration, no banner, and no logging listener
     */
    public static SpringApplication application(ClassLoader classLoader, Map<String, Object> environmentVariables) {
        SpringApplication application = new SpringApplication(new DefaultResourceLoader(classLoader),
                EmptyConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        // Boot's logging listener would reconfigure the test JVM's logging for every later test, such as the ones
        // that capture key-value pairs in the output; fixture runs leave logging as it is.
        application
                .setListeners(application
                        .getListeners()
                        .stream()
                        .filter(listener -> !(listener instanceof LoggingApplicationListener))
                        .toList());
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                new LinkedHashMap<>(environmentVariables)));
        application.setEnvironment(environment);
        return application;
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
