// A local run on docker compose and the tests use one PostgreSQL image: every compose file of the samples and the
// service archetype pins the digest of SynapseContainers.POSTGRES_IMAGE (synapse-test-data), so a change of the pin in
// one place without the others fails here. basedir is tests/synapse-build-rules-tests/target/it/postgres-image-pin,
// so the repository root is five levels up.
def root = new File(basedir, '../../../../..').canonicalFile

def containers = new File(root,
        'data/synapse-test-data/src/main/java/io/americanexpress/synapse/test/data/SynapseContainers.java')
def declaration = containers.getText('UTF-8') =~ /POSTGRES_IMAGE\s*=\s*"postgres@sha256:"\s*\+\s*"([0-9a-f]{64})"/
assert declaration.find() : "${containers} doesn't declare POSTGRES_IMAGE as \"postgres@sha256:\" + \"<digest>\""
def pinned = declaration.group(1)

def composeFiles = []
['samples', 'archetypes'].each { dir ->
    new File(root, dir).eachFileRecurse(groovy.io.FileType.FILES) { file ->
        if (file.name ==~ /(docker-)?compose(\.[\w-]+)?\.ya?ml/ && !file.path.replace(File.separatorChar, '/' as char).contains('/target/')) {
            composeFiles << file
        }
    }
}
def mismatches = []
def checked = 0
composeFiles.each { file ->
    file.readLines('UTF-8').eachWithIndex { line, index ->
        def image = line =~ /^\s*image:\s*(postgres\S*)/
        if (image.find()) {
            checked++
            def digest = image.group(1) =~ /@sha256:([0-9a-f]{64})$/
            if (!digest.find() || digest.group(1) != pinned) {
                mismatches << "${root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char)}:${index + 1}: ${image.group(1)}".toString()
            }
        }
    }
}
assert checked > 0 : 'no compose file names a postgres image; the check is reading the wrong directories'
assert mismatches.isEmpty() : "these compose images don't pin SynapseContainers.POSTGRES_IMAGE's digest " +
        "(sha256:${pinned}):\n" + mismatches.join('\n')
true
