import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..');
const basePath = path.join(repoRoot, 'src/android/app/src/main/res/values/strings.xml');
const viPath = path.join(repoRoot, 'src/android/app/src/main/res/values-vi/strings.xml');

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

function resources(file) {
  const xml = fs.readFileSync(file, 'utf8');
  const map = new Map();
  const re = /<string\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g;
  let match;
  while ((match = re.exec(xml)) !== null) map.set(match[1], match[2]);
  return map;
}

function placeholders(value) {
  return [...value.matchAll(/%(?:\d+\$)?(?:[-#+ 0,(]*\d*(?:\.\d+)?)?[a-zA-Z%]/g)]
    .map((match) => match[0])
    .sort();
}

if (!fs.existsSync(viPath)) fail('Vietnamese resource file is missing');

const base = resources(basePath);
const vi = resources(viPath);

const missing = [...base.keys()].filter((key) => !vi.has(key));
const extra = [...vi.keys()].filter((key) => !base.has(key));
if (missing.length) fail(`missing Vietnamese keys: ${missing.join(', ')}`);
if (extra.length) fail(`unexpected Vietnamese keys: ${extra.join(', ')}`);
if (base.size !== vi.size) fail(`resource count mismatch: base=${base.size}, vi=${vi.size}`);

for (const [key, value] of base) {
  const expected = placeholders(value);
  const actual = placeholders(vi.get(key));
  if (JSON.stringify(expected) !== JSON.stringify(actual)) {
    fail(`placeholder mismatch for ${key}: ${expected.join(' ')} != ${actual.join(' ')}`);
  }
}

console.log(`PASS: Vietnamese locale covers all ${base.size} string resources with matching placeholders.`);
