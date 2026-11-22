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

import java.util.Arrays;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Prints what verify.groovy checks when it runs the AOT-processed jar: the active profiles and two settings. */
@Component
class ProfilesReport implements CommandLineRunner {

    private final Environment environment;

    ProfilesReport(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        System.out.println("PROFILES-REPORT active=" + Arrays.toString(environment.getActiveProfiles()) + " greeting="
                + environment.getProperty("greeting") + " build-only=" + environment.getProperty("build-only"));
    }
}
