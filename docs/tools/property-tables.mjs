// Generates the configuration property reference from each module's spring-configuration-metadata.json.
// Run by the Antora Collector extension (see docs/antora.yml) from the repository root. If no module has been
// compiled yet, it compiles them first. Output: docs/build/generated-docs/modules/ROOT/partials/properties/all.adoc
// Exits non-zero (failing the docs build) if a synapse.* property has no description.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const root = process.cwd();
const metadataPath = join('target', 'classes', 'META-INF', 'spring-configuration-metadata.json');
const outputDir = join(root, 'docs', 'build', 'generated-docs', 'modules', 'ROOT', 'partials', 'properties');

// Module directories are the ones with a pom.xml; target, node_modules and hidden directories are skipped.
function findModules(dir) {
  const modules = existsSync(join(dir, 'pom.xml')) ? [dir] : [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory() && !entry.name.startsWith('.') && !['target', 'node_modules', 'docs'].includes(entry.name)) {
      modules.push(...findModules(join(dir, entry.name)));
    }
  }
  return modules;
}

function compiledMetadata() {
  return findModules(root)
    .map((dir) => ({ module: dir.split(sep).pop(), file: join(dir, metadataPath) }))
    .filter(({ file }) => existsSync(file));
}

let found = compiledMetadata();
if (found.length === 0) {
  const mvnw = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw';
  console.log('No configuration metadata found; compiling modules.');
  execFileSync(mvnw, ['--batch-mode', '--no-transfer-progress', '--quiet', '-DskipTests', 'process-classes'], {
    cwd: root,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  found = compiledMetadata();
}

// Quality gate: every synapse.* property must have a description (taken from the Javadoc of the property).
const undocumented = found.flatMap(({ module, file }) =>
  (JSON.parse(readFileSync(file, 'utf8')).properties ?? [])
    .filter((p) => p.name.startsWith('synapse.') && !p.deprecation && !(p.description ?? '').trim())
    .map((p) => `${module}: ${p.name}`),
);
if (undocumented.length > 0) {
  console.error('Configuration properties without a description (add Javadoc to the property):');
  undocumented.forEach((name) => console.error(`  ${name}`));
  process.exit(1);
}

const cell = (text) => String(text).replaceAll('|', '\\|');
const code = (text) => '`+' + String(text) + '+`';
// Upper-cases the first letter, unless the second is already upper case, so a name such as gRPC stays as written.
const sentence = (text) => {
  if (!text) {
    return '';
  }
  return /^.\p{Lu}/u.test(text) ? text : text.charAt(0).toUpperCase() + text.slice(1);
};
const simpleType = (type) => (type ?? '').replace(/^java\.lang\./, '');

const sections = found
  .map(({ module, file }) => ({ module, properties: JSON.parse(readFileSync(file, 'utf8')).properties ?? [] }))
  .filter(({ properties }) => properties.some((p) => p.name.startsWith('synapse.')))
  .sort((a, b) => a.module.localeCompare(b.module))
  .map(({ module, properties }) => {
    const rows = properties
      .filter((p) => p.name.startsWith('synapse.') && !p.deprecation)
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((p) =>
        [
          `|${code(p.name)}`,
          `|${p.type ? code(simpleType(p.type)) : ''}`,
          `|${p.defaultValue !== undefined ? code(JSON.stringify(p.defaultValue).replace(/^"|"$/g, '')) : ''}`,
          `|${cell(sentence(p.description ?? ''))}`,
        ].join('\n'),
      );
    return [
      `== ${module}`,
      '',
      '[cols="3,1,1,4",options="header"]',
      '|===',
      '|Property |Type |Default |Description',
      '',
      rows.join('\n\n'),
      '|===',
    ].join('\n');
  });

rmSync(join(root, 'docs', 'build', 'generated-docs'), { recursive: true, force: true });
mkdirSync(outputDir, { recursive: true });
const body = sections.length > 0 ? sections.join('\n\n') : 'No modules define `synapse.*` properties yet.';
writeFileSync(join(outputDir, 'all.adoc'), body + '\n');
console.log(`Property tables: ${sections.length} module(s) -> ${relative(root, outputDir)}`);
