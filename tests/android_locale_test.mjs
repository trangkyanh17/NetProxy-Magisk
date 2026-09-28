import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..');
const basePath = path.join(repoRoot, 'src/android/app/src/main/res/values/strings.xml');
const viPath = path.join(repoRoot, 'src/android/app/src/main/res/values-vi/strings.xml');
const localeConfigPath = path.join(repoRoot, 'src/android/app/src/main/res/xml/locales_config.xml');
const manifestPath = path.join(repoRoot, 'src/android/app/src/main/AndroidManifest.xml');
const settingsScreenPath = path.join(repoRoot, 'src/android/app/src/main/java/com/fanjv/netproxy/feature/settings/presentation/SettingsScreen.kt');
const localeControllerPath = path.join(repoRoot, 'src/android/app/src/main/java/com/fanjv/netproxy/core/locale/AppLocaleController.kt');

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

const proxyNodeKeys = [...vi.keys()].filter((key) => key.includes('node'));
const literalNodeKeys = proxyNodeKeys.filter((key) => /\bnút\b/iu.test(vi.get(key)));
if (literalNodeKeys.length) {
  fail(`proxy terminology must use "node", not "nút": ${literalNodeKeys.join(', ')}`);
}

if (!fs.existsSync(localeConfigPath)) fail('Android per-app locale config is missing');
const localeConfig = fs.readFileSync(localeConfigPath, 'utf8');
for (const tag of ['zh-CN', 'vi']) {
  if (!localeConfig.includes(`android:name="${tag}"`)) fail(`locale config is missing ${tag}`);
}

const manifest = fs.readFileSync(manifestPath, 'utf8');
if (!manifest.includes('android:localeConfig="@xml/locales_config"')) {
  fail('AndroidManifest does not expose per-app language settings');
}

if (!fs.existsSync(localeControllerPath)) fail('AppLocaleController is missing');
const localeController = fs.readFileSync(localeControllerPath, 'utf8');
for (const token of ['LocaleManager', 'applicationLocales', 'createConfigurationContext']) {
  if (!localeController.includes(token)) fail(`AppLocaleController is missing ${token}`);
}

const settingsScreen = fs.readFileSync(settingsScreenPath, 'utf8');
for (const token of ['R.string.settings_language', 'OverlayDropdownPreference', 'AppLocaleController.setLanguage']) {
  if (!settingsScreen.includes(token)) fail(`Settings language selector is missing ${token}`);
}

console.log(`PASS: Vietnamese locale covers all ${base.size} string resources, preserves placeholders, and exposes app language selection.`);
