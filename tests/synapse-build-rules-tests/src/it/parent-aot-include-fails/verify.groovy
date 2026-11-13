// The build failed in process-aot, with Synapse's message, and generated nothing.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert log.contains('BUILD FAILURE') : log
assert log.contains("During AOT processing the environment is the default profile 'build', but these profiles are active: kafka.") : log
assert log.contains('Move feature profiles from spring.profiles.include to each environment') : log
assert log =~ /Failed to execute goal org\.springframework\.boot:spring-boot-maven-plugin:[^:]+:process-aot \(process-aot\)/ : log
assert !new File(basedir, 'target/spring-aot/main/sources').exists() : 'process-aot generated sources'
