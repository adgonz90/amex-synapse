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

// Runs after the project is generated (maven-archetype-plugin binds the request and each property, such as
// serviceName). maven-archetype-plugin checks archetype-metadata.xml's validationRegex only for a value typed at the
// prompt, not for -DserviceName=..., so this script applies the same rule to every value: serviceName is part of a
// Java package (data.<serviceName>) and of environment variable names (SYNAPSE_DATA_STORES_<SERVICENAME>_URL), so it
// is a lowercase Java identifier without a hyphen, and not a Java keyword. Keep the rule in step with the
// validationRegex in archetype-metadata.xml. A failure here fails the generation; the files are already written.

def keywords = ['abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const',
        'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'false', 'final', 'finally', 'float', 'for',
        'goto', 'if', 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new', 'null',
        'package', 'private', 'protected', 'public', 'return', 'short', 'static', 'strictfp', 'super', 'switch',
        'synchronized', 'this', 'throw', 'throws', 'transient', 'true', 'try', 'void', 'volatile', 'while'] as Set

def name = request.properties.getProperty('serviceName') ?: ''
if (!(name ==~ /[a-z][a-z0-9]*/) || keywords.contains(name)) {
    def suggestion = name.toLowerCase(Locale.ROOT).replaceAll(/[^a-z0-9]/, '').replaceFirst(/^[0-9]+/, '')
    if (!suggestion || keywords.contains(suggestion)) {
        suggestion = 'orders'
    }
    def project = new File(request.outputDirectory as String, request.artifactId as String)
    throw new IllegalArgumentException("serviceName '${name}' is not valid: it must be a lowercase letter followed by "
            + "lowercase letters and digits, with no hyphen, and not a Java keyword, because it names the Java package "
            + "data.<serviceName> and the environment variables SYNAPSE_DATA_STORES_<SERVICENAME>_*. The project in "
            + "${project} was written but doesn't build: delete it and generate again with, for example, "
            + "-DserviceName=${suggestion}.")
}
