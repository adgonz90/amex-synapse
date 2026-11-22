// -Pimage on a reactor: the enforcer check runs in every module but checks only the service, which has
// application-build.yaml; build-image-no-fork is bound in the service only (it declares spring-boot-maven-plugin), after
// repackage. The image itself was skipped by -Dspring-boot.build-image.skip=true.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('BUILD SUCCESS') : log
// No warning at all in a -Pimage build either (the image profile's service check included). Like parent-build-config's
// check, it rejects every [WARNING] line, a third-party one too, so read the lines it prints before blaming the parent.
def warnings = log.readLines().findAll { it.startsWith('[WARNING]') }
assert warnings.isEmpty() : "the build printed ${warnings.size()} warnings, the first: ${warnings.take(3)}"

// The output of one execution: from its header ("--- <plugin>:<version>:<goal> (<id>) @ <module> ---") to the next.
def execution = { String id, String module ->
    def header = log =~ /--- [^\n]*\(${id}\) @ ${module} ---/
    if (!header.find()) {
        return null
    }
    def start = header.end()
    def next = log.indexOf('--- ', start)
    return log.substring(start, next < 0 ? log.length() : next)
}

def modules = ['parent-image-build-file', 'lib', 'service']
modules.each { module ->
    assert execution('image-build-environment', module) != null : "the check didn't run in ${module}"
}
// The aggregator and the library aren't services, so the check skips them; the service passes it.
['parent-image-build-file', 'lib'].each { module ->
    assert execution('image-build-environment', module).contains('Skipping Rule Enforcement.') :
            "the check didn't skip ${module}: ${execution('image-build-environment', module)}"
}
assert !execution('image-build-environment', 'service').contains('Skipping Rule Enforcement.') : 'the check skipped the service'

// build-image-no-fork only where spring-boot-maven-plugin is declared, after repackage.
assert execution('build-image', 'lib') == null : 'an image step is bound in the library'
assert execution('build-image', 'parent-image-build-file') == null : 'an image step is bound in the aggregator'
assert log.contains('build-image-no-fork (build-image) @ service ---') : 'build-image-no-fork is not bound in the service'
assert log.indexOf('(repackage) @ service ---') < log.indexOf('(build-image) @ service ---') : 'build-image ran before repackage'
assert new File(basedir, 'service/target/service-1.0.jar').isFile()
