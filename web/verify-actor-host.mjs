// Dependency-free smoke test for actor-host-demo.wasm against
// kotoba-lang/wasm-webcomponent's actor-host.js host-import port (this repo
// carries the compiled `.wasm` only -- see index.html and README.md).
// Mirrors verify-kgraph.mjs's structure. Run: `node web/verify-actor-host.mjs`
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Node has no flag-free `import()` of an https: URL; fetch the source and
// hand it to a `data:` URL instead. Pinned to the same commit index.html
// loads from jsdelivr -- bump both together, don't float on @main.
const WASM_WEBCOMPONENT_COMMIT = '042d3b46a2eacf802a00efc64ad8c8ac54ca5eb3';
async function importFromJsdelivr(filePath) {
  const url = `https://cdn.jsdelivr.net/gh/kotoba-lang/wasm-webcomponent@${WASM_WEBCOMPONENT_COMMIT}/${filePath}`;
  const src = await (await fetch(url)).text();
  return import(`data:text/javascript;base64,${Buffer.from(src).toString('base64')}`);
}

const { actorHostImports, hostCaps, inMemoryStore } = await importFromJsdelivr('src/actor-host.js');

const here = path.dirname(fileURLToPath(import.meta.url));
const bytes = await readFile(path.join(here, 'actor-host-demo.wasm'));

const store = inMemoryStore();
const memoryBox = {};
const caps = hostCaps({
  grants: ['now', 'sha256-hex', 'log-append!'],
  limits: { allowWriteImports: true },
});
const importObject = { kotoba: actorHostImports(['now', 'sha256-hex', 'log-append!'], caps, memoryBox, { store }) };

const { instance } = await WebAssembly.instantiate(bytes, importObject);
memoryBox.memory = instance.exports.memory;

const written = Number(instance.exports.main());
const resultText = new TextDecoder('utf-8').decode(new Uint8Array(memoryBox.memory.buffer, 100, written));
const logged = new TextDecoder('utf-8').decode(store.read());

let failed = false;
function check(cond, message) {
  if (!cond) {
    failed = true;
    console.error(`FAIL: ${message}`);
  } else {
    console.log(`OK: ${message}`);
  }
}

check(written === 64, `main() wrote a 64-char sha256 hex digest (got ${written})`);
check(
  resultText === '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824',
  `guest-computed sha256("hello") matches the known digest (got ${resultText})`
);
check(logged === 'hello', `log_append! recorded the guest's 5-byte payload (got ${JSON.stringify(logged)})`);

if (failed) {
  console.error('actor-host browser host-import verification FAILED');
  process.exit(1);
}
console.log('OK: browser-native WebAssembly engine ran now/log_append/sha256_hex through kotoba-lang/wasm-webcomponent\'s actor-host.js (no JVM/Chicory/wasmtime)');
