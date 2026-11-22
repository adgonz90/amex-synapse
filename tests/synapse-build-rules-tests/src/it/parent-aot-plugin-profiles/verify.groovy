// Built with the parent's aot profile (-Paot), the plugin-level <profiles> (for spring-boot:run) did not reach
// process-aot: AOT processing ran with no active profile and the build environment as the default profile, and the
// generated code activates no profile.
def log = new File(basedir, 'build.log').getText('UTF-8')
assert !log.contains('The requested profile "aot" could not be activated') : 'synapse-parent has no aot profile'
assert log.contains('No active profile set, falling back to 1 default profile: "build"') : 'AOT processing did not run in the build environment'
def aotDir = new File(basedir, 'target/spring-aot/main/sources')
assert aotDir.isDirectory() : 'spring-boot:process-aot did not write target/spring-aot/main/sources'
def text = []
aotDir.eachFileRecurse(groovy.io.FileType.FILES) { if (it.name.endsWith('.java')) { text << it.getText('UTF-8') } }
assert !text.isEmpty()
assert !text.any { it.contains('addActiveProfile') } : 'the generated code activates a profile at run time'
