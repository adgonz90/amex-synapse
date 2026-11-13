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
package io.americanexpress.synapse.core.autoconfigure;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitchCheck;
import io.americanexpress.synapse.core.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.core.internal.paging.CursorKeys;
import io.americanexpress.synapse.core.internal.paging.InputDigest;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.paging.CursorCodec;

/**
 * Auto-configuration for the Synapse core: its properties, the application {@link Clock}, the {@link LogMasking},
 * the paging cursors every API plugin shares (the {@link CursorCodec}, the input digest and the page cursors), and the
 * check of the build-time switches under AOT.
 * The cursor beans are present whatever the plugins, since core can't see their bindings; each plugin reports a
 * generated cursor key once it has a paged route or method. Registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@EnableConfigurationProperties({SynapseErrorProperties.class, SynapsePagingProperties.class,
        SynapseObservabilityProperties.class, SynapseOperationsProperties.class})
public final class SynapseCoreAutoConfiguration {

    /**
     * The clock Synapse reads the time from, in UTC. Tests replace it with a fixed or mutable clock.
     *
     * @return the system clock in UTC
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Writes payloads for logs with {@code @Masked} members masked. It follows Boot's {@link JsonMapper} (naming
     * strategy and other {@code spring.jackson.*} settings) when there is a single or primary one, without changing
     * that mapper; otherwise it uses its own standard mapper.
     *
     * @param jsonMapper Boot's mapper, if there is a single or primary one
     * @return the log masker
     */
    @Bean
    @ConditionalOnMissingBean
    LogMasking logMasking(ObjectProvider<JsonMapper> jsonMapper) {
        JsonMapper base = jsonMapper.getIfUnique();
        return base != null ? new LogMasking(base) : LogMasking.standard();
    }

    /**
     * The codec of the opaque paging cursors: AES-256-GCM with the keys and maximum age of
     * {@code synapse.paging.cursor}. Without {@code cursor.key}, the key is generated at startup. An invalid key fails
     * startup. Define a {@link CursorCodec} bean to replace it.
     *
     * @param properties the paging properties, for the cursor settings
     * @param clock the application clock, for the issue time and the expiry check
     * @return the codec
     */
    @Bean
    @ConditionalOnMissingBean(CursorCodec.class)
    CursorCodec synapseCursorCodec(SynapsePagingProperties properties, Clock clock) {
        // Never SecureRandom.getInstanceStrong(), which can block.
        SecureRandom random = new SecureRandom();
        SynapsePagingProperties.Cursor cursor = properties.cursor();
        return new AesGcmCursorCodec(CursorKeys.from(cursor, random), clock, cursor.maxAge(), random);
    }

    /**
     * Digests the filters of a request, which its cursor tokens are bound to. It writes them with its own canonical
     * mapper, never the application's, so the application's Jackson configuration can't make two requests' filters
     * look alike.
     *
     * @return the digest
     */
    @Bean
    @ConditionalOnMissingBean
    InputDigest synapseInputDigest() {
        return new InputDigest();
    }

    /**
     * Converts between the operations' raw cursors and the tokens callers see, for every API plugin.
     *
     * @param codec the cursor codec
     * @param digest digests the request filters
     * @return the cursors
     */
    @Bean
    @ConditionalOnMissingBean
    PageCursors synapsePageCursors(CursorCodec codec, InputDigest digest) {
        return new PageCursors(codec, digest);
    }

    /**
     * Fails startup in AOT mode when a property that is fixed at build time differs from the build, naming every such
     * property in one failure. The properties are the {@link BuildTimeSwitch} beans the modules define (for example
     * {@code springdoc.api-docs.enabled} and {@code spring.grpc.server.reflection.enabled}). Outside AOT mode it does
     * nothing. Define a bean with the same name to replace it.
     *
     * @param switches every build-time switch bean
     * @return the check, run once all singletons exist
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseBuildTimeSwitchCheck")
    static BuildTimeSwitchCheck synapseBuildTimeSwitchCheck(ObjectProvider<BuildTimeSwitch> switches) {
        return new BuildTimeSwitchCheck(switches, AotDetector::useGeneratedArtifacts);
    }
}
