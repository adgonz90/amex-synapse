import groovy.xml.XmlSlurper

// The JDK 21 pull request leg (ci.yml) runs only the integration tests tagged @Tag("smoke") (-Dsynapse.it.groups=smoke)
// of the modules that inherit the root build. A module whose ITs have no smoke tag runs none of them on that leg, and
// the leg stays green, so AGENTS.md requires at least one per module. This check fails when a module of the root
// build (followed through <modules>) has an IT, by Failsafe's default names, in a source directory other than
// src/main/java, and none of its source files carries @Tag("smoke"). Modules that inherit synapse-parent (the samples)
// don't read that filter; jdk21-pr-excludes checks them. basedir is
// tests/synapse-build-rules-tests/target/it/smoke-its, so the repository root is five levels up.
def root = new File(basedir, '../../../../..').canonicalFile

def rootBuild = { File pom ->
    def parent = new XmlSlurper().parse(pom).parent
    parent.size() > 0 && parent.groupId.text().trim() == 'io.americanexpress.synapse' && parent.artifactId.text().trim() == 'synapse'
}

def modules = []
def collect
collect = { File dir ->
    def pom = new File(dir, 'pom.xml')
    assert pom.isFile() : "${pom} is missing"
    new XmlSlurper().parse(pom).modules.module.each { module ->
        def child = new File(dir, module.text().trim()).canonicalFile
        modules << child
        collect(child)
    }
}
collect(root)
assert modules : 'the root pom lists no module; the check is reading the wrong file'

def smoke = ~/@Tag\(\s*"smoke"\s*\)/
def checked = 0
def untagged = []
modules.findAll { rootBuild(new File(it, 'pom.xml')) }.each { module ->
    def src = new File(module, 'src')
    if (!src.isDirectory()) {
        return
    }
    def its = []
    def tagged = false
    src.eachDir { kind ->
        def java = new File(kind, 'java')
        if (kind.name == 'main' || !java.isDirectory()) {
            return
        }
        java.eachFileRecurse(groovy.io.FileType.FILES) { file ->
            if (!file.name.endsWith('.java')) {
                return
            }
            // Failsafe's default includes: IT*.java, *IT.java and *ITCase.java.
            if (file.name ==~ /(IT.*|.*IT|.*ITCase)\.java/) {
                its << file
            }
            if (!tagged && smoke.matcher(file.getText('UTF-8')).find()) {
                tagged = true
            }
        }
    }
    if (its) {
        checked++
        if (!tagged) {
            untagged << "${root.toPath().relativize(module.toPath()).toString().replace(File.separatorChar, '/' as char)} (${its.size()} ITs)".toString()
        }
    }
}
assert checked > 0 : 'no module of the root build has an IT; the check is reading the wrong directories'
assert untagged.isEmpty() : 'these modules have integration tests but none tagged @Tag("smoke"), so the JDK 21 pull ' +
        'request leg (-Dsynapse.it.groups=smoke) would run none of them; tag at least one fast IT of each:\n' +
        untagged.join('\n')
true
