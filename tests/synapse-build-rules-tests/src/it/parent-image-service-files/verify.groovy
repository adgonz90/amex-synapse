// The image profile's check (execution image-build-environment) treats a module with application.yml or
// application.properties as a service, as one with application.yaml (parent-image-build-file), and skips a module
// that sets synapse.image.notService=true, the documented opt-out, even when it has an application.yaml.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('BUILD SUCCESS') : log

// The output of one execution: from its header ("--- <plugin>:<version>:<goal> (<id>) @ <module> ---") to the next.
def execution = { String module ->
    def header = log =~ /--- [^\n]*\(image-build-environment\) @ ${module} ---/
    assert header.find() : "the check didn't run in ${module}"
    def start = header.end()
    def next = log.indexOf('--- ', start)
    return log.substring(start, next < 0 ? log.length() : next)
}
['svc-yml', 'svc-properties'].each { module ->
    assert !execution(module).contains('Skipping Rule Enforcement.') : "the check skipped ${module}, a service"
}
['parent-image-service-files', 'fixtures'].each { module ->
    assert execution(module).contains('Skipping Rule Enforcement.') : "the check didn't skip ${module}: ${execution(module)}"
}
// The invoker fails a build whose post-build script returns anything but true or null.
true
