const assert = require('node:assert/strict');
const test = require('node:test');
const katex = require('katex');
const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');

const expression = String.raw`\href{javascript:__beacon_probe=1}{X}`;
const render = () => katex.renderToString(expression, {throwOnError: false, displayMode: true, output: 'html'});
const originalTrust = Object.getOwnPropertyDescriptor(Object.prototype, 'trust');

test('ordinary untrusted math cannot emit a javascript link', () => {
  assert.equal(/href=["']javascript:/i.test(render()), false);
});

test('inherited trust cannot authorize a javascript link', () => {
  const original = Object.getOwnPropertyDescriptor(Object.prototype, 'trust');
  Object.defineProperty(Object.prototype, 'trust', {value: true, configurable: true, writable: true});
  try {
    assert.equal(/href=["']javascript:/i.test(render()), false);
  } finally {
    if (original) Object.defineProperty(Object.prototype, 'trust', original);
    else delete Object.prototype.trust;
  }
});

test('prototype fixture is restored and ordinary math remains untrusted', () => {
  assert.deepEqual(Object.getOwnPropertyDescriptor(Object.prototype, 'trust'), originalTrust);
  assert.equal(/href=["']javascript:/i.test(render()), false);
});

test('explicitly trusted ordinary links still render', () => {
  const html = katex.renderToString(String.raw`\href{https://example.test/math}{X}`, {trust: true, output: 'html'});
  assert.match(html, /href="https:\/\/example\.test\/math"/);
});

test('native MathML keeps fractions and superscripts without HTML font classes', () => {
  const html = katex.renderToString(String.raw`\frac{a}{b}+x^2`, {throwOnError: true, displayMode: true, output: 'mathml'});
  assert.match(html, /<mfrac>/);
  assert.match(html, /<msup>/);
  assert.match(html, /<math[^>]+display="block"/);
  assert.doesNotMatch(html, /katex-html|katex-mathml/);
  assert.throws(() => katex.renderToString(String.raw`\notARealMacro`, {throwOnError: true}), katex.ParseError);
});

test('shipped bundle matches the locked reproducible build, checksum and KaTeX notice', () => {
  const rich = path.join(__dirname, '../../app/src/main/assets/rich');
  const bundle = fs.readFileSync(path.join(rich, 'mermaid-11.17.2.min.js'));
  const rebuilt = require('esbuild').buildSync({absWorkingDir: __dirname, entryPoints: ['entry.mjs'], bundle: true,
    format: 'iife', platform: 'browser', target: 'chrome80', minify: true, legalComments: 'inline', write: false}).outputFiles[0].contents;
  assert.deepEqual(bundle, Buffer.from(rebuilt));
  const manifest = require('./package.json');
  const lock = require('./package-lock.json');
  assert.equal(katex.version, manifest.overrides.katex);
  assert.equal(lock.packages['node_modules/katex'].version, katex.version);
  assert.equal(lock.packages['node_modules/mermaid'].version, manifest.dependencies.mermaid);
  assert.equal(lock.packages['node_modules/dompurify'].version, manifest.overrides.dompurify);
  assert.match(bundle.toString(), /0\.18\.2/);
  assert.doesNotMatch(bundle.toString(), /0\.16\.47/);
  const digest = createHash('sha256').update(bundle).digest('hex');
  assert.ok(fs.readFileSync(path.join(rich, 'README.md'), 'utf8').includes(digest));
  assert.equal(fs.readFileSync(path.join(rich, 'KATEX-LICENSE'), 'utf8').replace(/\r\n/g, '\n'), fs.readFileSync(path.join(__dirname, 'node_modules/katex/LICENSE'), 'utf8').replace(/\r\n/g, '\n'));
});
