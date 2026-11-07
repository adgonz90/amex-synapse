// synapse-bom must manage guava (a CVE pin consumers need) at exactly the version the root pom pins.
// basedir is tests/synapse-build-rules-tests/target/it/bom-guava-pin, so the repository root is five levels up.
def root = new File(basedir, '../../../../..').canonicalFile
def guavaVersion = { File pom ->
    def text = pom.getText('UTF-8').replaceAll(/(?s)<!--.*?-->/, '')
    def matcher = text =~ /(?s)<dependency>\s*<groupId>com\.google\.guava<\/groupId>\s*<artifactId>guava<\/artifactId>\s*<version>([^<]+)<\/version>/
    assert matcher.count == 1 : "${pom} must manage com.google.guava:guava exactly once, found ${matcher.count}"
    matcher[0][1]
}
def rootVersion = guavaVersion(new File(root, 'pom.xml'))
def bomVersion = guavaVersion(new File(root, 'bom/synapse-bom/pom.xml'))
assert bomVersion == rootVersion : "synapse-bom manages guava ${bomVersion} but the root pom pins ${rootVersion}"
