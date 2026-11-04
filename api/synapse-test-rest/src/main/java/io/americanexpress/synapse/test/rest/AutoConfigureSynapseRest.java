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
package io.americanexpress.synapse.test.rest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

/**
 * Imports the auto-configurations the REST slice needs: Boot's AOP, validation, message source, Jackson, HTTP message
 * converter, HTTP encoding, Spring MVC (with its error handling and observation), observation, tracing and
 * task-execution support; Synapse core's and the REST plugin's auto-configurations; the test clock; and the check that
 * each bound operation has a test double. The list is in
 * {@code META-INF/spring/io.americanexpress.synapse.test.rest.AutoConfigureSynapseRest.imports}.
 *
 * @see SynapseRestTest
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ImportAutoConfiguration
public @interface AutoConfigureSynapseRest {
}
