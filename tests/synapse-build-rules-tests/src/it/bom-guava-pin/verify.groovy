import groovy.xml.XmlSlurper

// The root pom.xml, synapse-bom and synapse-parent manage the same third-party entries (a CVE pin, or a library Spring
// Boot doesn't manage) at the same versions: the root and synapse-parent repeat the BOM's entries on purpose, and an
// entry added to one file only is reported too. ${...} properties are resolved within each file. A mismatch is
// reported as one line per entry: "<g:a>: bom <v>, root <v>, parent <v>". Where synapse-parent lists its entries
// against its synapse-bom import doesn't matter: Maven adds an imported entry only when the POM doesn't manage it.
// basedir is tests/synapse-build-rules-tests/target/it/bom-guava-pin, so the repository root is five levels up.
def root = new File(basedir, '../../../../..').canonicalFile

def managedVersions = { File pom ->
    def project = new XmlSlurper().parse(pom)
    def properties = [:]
    project.properties.children().each { properties[it.name()] = it.text().trim() }
    def resolve = { String value ->
        def resolved = value
        10.times { resolved = resolved.replaceAll(/\$\{([^}]+)}/) { all, key -> properties.containsKey(key) ? properties[key] : all } }
        resolved
    }
    def versions = [:]
    project.dependencyManagement.dependencies.dependency.each { dep ->
        def groupId = dep.groupId.text().trim()
        def version = dep.version.text().trim()
        if (groupId != 'io.americanexpress.synapse' && dep.scope.text().trim() != 'import' && version) {
            def key = "${groupId}:${dep.artifactId.text().trim()}".toString()
            assert !versions.containsKey(key) : "${pom} manages ${key} more than once"
            versions[key] = resolve(version)
        }
    }
    versions
}

def bom = managedVersions(new File(root, 'bom/synapse-bom/pom.xml'))
def rootPom = managedVersions(new File(root, 'pom.xml'))
def parentFile = new File(root, 'parent/synapse-parent/pom.xml')
def parent = managedVersions(parentFile)

assert bom : 'synapse-bom manages no third-party entry; the check is reading the wrong file'
def keys = new TreeSet(bom.keySet() + rootPom.keySet() + parent.keySet())
def mismatches = keys.findAll { key -> bom.get(key) == null || rootPom.get(key) != bom.get(key) || parent.get(key) != bom.get(key) }
        .collect { key -> "${key}: bom ${bom.get(key) ?: '<missing>'}, root ${rootPom.get(key) ?: '<missing>'}, parent ${parent.get(key) ?: '<missing>'}".toString() }
assert mismatches.isEmpty() : "the root pom, synapse-bom and synapse-parent disagree:\n" + mismatches.join('\n')
