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
package example;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Beans that exist only in one environment; AOT processing generates code only for the environment it ran in, and for
 * the settings of its file (application-build.yaml).
 */
@Configuration(proxyBeanMethods = false)
class BuildOnlyConfiguration {

    @Bean
    @Profile("build")
    BuildMarker buildMarker() {
        return new BuildMarker();
    }

    @Bean
    @Profile("e3")
    E3Marker e3Marker() {
        return new E3Marker();
    }

    @Bean
    @ConditionalOnProperty("build-only")
    BuildFileMarker buildFileMarker() {
        return new BuildFileMarker();
    }

    /** Present only when the build environment is active. */
    static class BuildMarker {}

    /** Present only when the e3 environment is active. */
    static class E3Marker {}

    /** Present only when application-build.yaml was read. */
    static class BuildFileMarker {}
}
