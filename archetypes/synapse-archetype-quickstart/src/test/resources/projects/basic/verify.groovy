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

// Runs after the generated project (com.example:greetings) built with verify. maven-archetype-plugin passes the
// generated project as context.projectDir; basedir is this script's directory. This file is filtered with @ delimiters:
// the token below is the reactor version.

def reactorVersion = '@project.version@'
def project = context.projectDir as File

// The generated files are hello-rest's, with the package path replaced, plus .gitattributes and the Maven Wrapper
// (expected-files.txt). The build's own output (target/) and the IT's build.log are left out.
def expected = new File(basedir as File, 'expected-files.txt').readLines()*.trim().findAll { it }.sort()
def actual = []
project.eachFileRecurse(groovy.io.FileType.FILES) { file ->
    def path = project.toPath().relativize(file.toPath()).toString().replace('\\', '/')
    if (!path.startsWith('target/') && path != 'build.log') {
        actual << path
    }
}
actual.sort()
assert actual == expected: "Generated files differ from expected-files.txt.\nMissing: ${expected - actual}\nExtra: ${actual - expected}"

// The environment comes from ENV, as in hello-rest (Velocity leaves the undefined ${ENV} as it is).
def applicationYaml = new File(project, 'src/main/resources/application.yaml').text
assert applicationYaml =~ /(?m)^    active: \$\{ENV\}$/: "application.yaml doesn't map spring.profiles.active to \${ENV}:\n${applicationYaml}"
assert applicationYaml =~ /(?m)^    name: greetings$/: "application.yaml doesn't name the application greetings:\n${applicationYaml}"

// Velocity drops lines that start with ##, so the template escapes the README's headings; they must survive.
def readme = new File(project, 'README.md').text
def headings = readme.readLines().findAll { it.startsWith('## ') }
assert headings == [
    '## What it shows',
    '## Run it',
    '## Try it',
    '## Tests',
    '## Spring AOT and container images',
    '## Layout',
    '## Make it yours',
]: "README headings: ${headings}"
assert !readme.contains('#[[') && !readme.contains(']]#'): 'README still holds Velocity escapes'

// Git keeps the project on LF, whatever line separator the generator used, except the wrapper's Windows script.
assert new File(project, '.gitattributes').readLines()*.trim() == ['* text=auto eol=lf', '*.cmd text eol=crlf', 'mvnw text eol=lf']

// The parent is synapse-parent at the version of the reactor that built this archetype, from a repository.
def pom = new groovy.xml.XmlSlurper().parse(new File(project, 'pom.xml'))
assert pom.parent.groupId.text() == 'io.americanexpress.synapse'
assert pom.parent.artifactId.text() == 'synapse-parent'
assert pom.parent.version.text() == reactorVersion: "parent version ${pom.parent.version.text()}, reactor ${reactorVersion}"
// An empty <relativePath/>: the parent comes from a repository, never from a directory above the generated project.
assert pom.parent.relativePath.size() == 1 && pom.parent.relativePath.text() == '': "parent relativePath: '${pom.parent.relativePath.text()}'"
assert pom.groupId.text() == 'com.example'
assert pom.artifactId.text() == 'greetings'
assert pom.version.text() == '0.1.0-SNAPSHOT'

// The Maven Wrapper is the repository's, copied as is (an unfiltered fileSet), byte for byte: mvnw on LF and mvnw.cmd
// on CRLF. basedir is archetypes/<archetype>/target/it/projects/<name>, so the repository root is six levels up. The
// post-generation script sets mvnw executable; only a POSIX file system (Linux CI) can show it.
def root = new File(basedir as File, '../../../../../..').canonicalFile
['mvnw', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties'].each {
    assert new File(project, it).isFile(): "no ${it}"
    assert new File(project, it).bytes == new File(root, it).bytes: "${it} differs from the repository's (${root})"
}
def mvnw = new File(project, 'mvnw')
assert !mvnw.getText('UTF-8').contains('\r'): 'mvnw has a carriage return'
def mvnwCmd = new File(project, 'mvnw.cmd').getText('UTF-8')
assert mvnwCmd.contains('\r\n') && !(mvnwCmd =~ /(?<!\r)\n/): 'mvnw.cmd is not on CRLF'
if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains('posix')) {
    assert java.nio.file.Files.isExecutable(mvnw.toPath()): 'mvnw is not executable'
}

// The wrapper runs: it downloads Maven 3.9.16 into ~/.m2/wrapper (once) and prints its version. On Windows the script
// is named with its directory, .\mvnw.cmd: with NoDefaultCurrentDirectoryInExePath set, cmd doesn't look for a bare
// mvnw.cmd in the current directory.
def windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).startsWith('windows')
def versionLog = new File(basedir as File, 'mvnw-version.log')
// Its stdin is the null device, so nothing it starts can wait for input. A timeout destroys the whole process tree:
// on Windows, destroying cmd alone would leave its powershell or java running, holding the log open.
def wrapper = new ProcessBuilder(windows ? ['cmd', '/c', '.\\mvnw.cmd', '--version'] : ['./mvnw', '--version'])
        .directory(project).redirectErrorStream(true).redirectOutput(versionLog)
        .redirectInput(ProcessBuilder.Redirect.from(new File(windows ? 'NUL' : '/dev/null'))).start()
if (!wrapper.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
    wrapper.toHandle().descendants().forEach { it.destroyForcibly() }
    wrapper.destroyForcibly()
    assert false: "mvnw --version didn't finish in 10 minutes:\n${versionLog.text}"
}
def versionOutput = versionLog.text
assert wrapper.exitValue() == 0: "mvnw --version exited with ${wrapper.exitValue()}:\n${versionOutput}"
assert versionOutput.contains('Apache Maven 3.9.16'): "mvnw --version didn't run Maven 3.9.16:\n${versionOutput}"

true
