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

// Runs after the project is generated (maven-archetype-plugin binds the request). The generator writes the Maven
// Wrapper's mvnw without its executable bit, so this sets it for everyone, as the repository's mvnw has it. On a file
// system without POSIX permissions (Windows) there is nothing to set.

def project = new File(request.outputDirectory as String, request.artifactId as String)
def mvnw = new File(project, 'mvnw')
// The generation still succeeds when the bit can't be set, but on a POSIX file system it says so.
if (!mvnw.setExecutable(true, false)
        && java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains('posix')) {
    println "[WARNING] Could not make ${mvnw} executable: run chmod +x mvnw in ${project} before ./mvnw."
}
