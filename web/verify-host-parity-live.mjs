// T8.4 Node/browser live host runner for host-parity critical imports.
//
// Runs the same class of proofs as kototama.host-parity-live (JVM) against
// wasm-webcomponent's actor-host.js under Node's WebAssembly engine.
// Case ids align with lang/host-parity.edn where listed.
//
// Run: node web/verify-host-parity-live.mjs
// Requires: wasm-tools on PATH; network to fetch pinned actor-host sources.
import { readFile, mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import { writeFileSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { tmpdir } from 'node:os';
import path from 'node:path';

// Prefer sibling checkout (superproject west layout) so actor-host surface
// matches local wasm-webcomponent tip; fall back to pinned CDN commit.
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
// web/ -> kototama-t84 -> kotoba-lang siblings
const siblingActorHost = path.resolve(here, '../../wasm-webcomponent/src/actor-host.js');
// also try when this repo is named kototama (not kototama-t84)
const siblingActorHostAlt = path.resolve(here, '../../../orgs/kotoba-lang/wasm-webcomponent/src/actor-host.js');
// Includes actor-host `random-bytes` + `kagi-sign` inject (2026-07-31).
const WASM_WEBCOMPONENT_COMMIT = '68805af90604a389ecb8056c1f5be7170e2ff282';
const SRC_FILES = [
  'src/actor-host.js',
  'src/vendor/curves/ed25519.js',
  'src/vendor/curves/utils.js',
  'src/vendor/curves/abstract/curve.js',
  'src/vendor/curves/abstract/edwards.js',
  'src/vendor/curves/abstract/hash-to-curve.js',
  'src/vendor/curves/abstract/modular.js',
  'src/vendor/curves/abstract/montgomery.js',
  'src/vendor/hashes/_md.js',
  'src/vendor/hashes/_u64.js',
  'src/vendor/hashes/crypto.js',
  'src/vendor/hashes/sha2.js',
  'src/vendor/hashes/utils.js',
];

function watToWasm(wat, workDir) {
  const watPath = path.join(workDir, 'guest.wat');
  const wasmPath = path.join(workDir, 'guest.wasm');
  writeFileSync(watPath, wat);
  const r = spawnSync('wasm-tools', ['parse', watPath, '-o', wasmPath], { encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error(`wasm-tools parse failed: ${r.stderr || r.stdout}`);
  }
  return readFileSync(wasmPath);
}

const corpus = [
  {
    id: 'sha256-hex-all-available',
    imports: ['sha256-hex'],
    check: (n) => Number(n) === 64,
    wat: `(module
      (import "kotoba" "sha256_hex" (func $sha256_hex (param i32 i32 i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 0) "hello")
      (func (export "main") (result i64)
        (i64.extend_i32_s (call $sha256_hex (i32.const 0) (i32.const 5) (i32.const 100) (i32.const 64)))))`,
  },
  {
    id: 'clock-monotonic-all',
    imports: ['clock-monotonic'],
    check: (n) => Number(n) > 0,
    wat: `(module
      (import "kotoba" "clock_monotonic" (func $clock_monotonic (result i64)))
      (func (export "main") (result i64) (call $clock_monotonic)))`,
  },
  {
    id: 'log-write-all-available',
    imports: ['log-write'],
    check: (n) => Number(n) >= 0,
    wat: `(module
      (import "kotoba" "log_write" (func $log_write (param i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 0) "ok")
      (func (export "main") (result i64)
        (i64.extend_i32_s (call $log_write (i32.const 0) (i32.const 2)))))`,
  },
  {
    id: 'log-read-all-available',
    imports: ['log-read'],
    check: (n) => Number(n) >= 0,
    wat: `(module
      (import "kotoba" "log_read" (func $log_read (param i32 i32) (result i32)))
      (memory (export "memory") 1)
      (func (export "main") (result i64)
        (i64.extend_i32_s (call $log_read (i32.const 0) (i32.const 256)))))`,
  },
  {
    id: 'gen-keypair-all-available',
    imports: ['gen-keypair'],
    check: (n) => Number(n) === 64,
    wat: `(module
      (import "kotoba" "gen_keypair" (func $gen_keypair (param i32 i32) (result i32)))
      (memory (export "memory") 1)
      (func (export "main") (result i64)
        (i64.extend_i32_s (call $gen_keypair (i32.const 0) (i32.const 64)))))`,
  },
  {
    id: 'sign-all-available',
    imports: ['gen-keypair', 'sign'],
    check: (n) => Number(n) === 64,
    wat: `(module
      (import "kotoba" "gen_keypair" (func $gen_keypair (param i32 i32) (result i32)))
      (import "kotoba" "sign" (func $sign (param i32 i32 i32 i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 64) "ok")
      (func (export "main") (result i64)
        (drop (call $gen_keypair (i32.const 0) (i32.const 64)))
        (i64.extend_i32_s
          (call $sign (i32.const 0) (i32.const 64) (i32.const 2) (i32.const 128) (i32.const 64)))))`,
  },
  {
    id: 'verify-all-available',
    imports: ['gen-keypair', 'sign', 'verify'],
    check: (n) => Number(n) === 1,
    wat: `(module
      (import "kotoba" "gen_keypair" (func $gen_keypair (param i32 i32) (result i32)))
      (import "kotoba" "sign" (func $sign (param i32 i32 i32 i32 i32) (result i32)))
      (import "kotoba" "verify" (func $verify (param i32 i32 i32 i32 i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 64) "ok")
      (func (export "main") (result i64)
        (drop (call $gen_keypair (i32.const 0) (i32.const 64)))
        (drop (call $sign (i32.const 0) (i32.const 64) (i32.const 2) (i32.const 128) (i32.const 64)))
        (i64.extend_i32_s
          (call $verify (i32.const 32) (i32.const 32) (i32.const 64) (i32.const 2)
                        (i32.const 128) (i32.const 64)))))`,
  },
  {
    id: 'random-bytes-all-available',
    imports: ['random-bytes'],
    check: (n) => Number(n) === 16,
    wat: `(module
      (import "kotoba" "random_bytes" (func $random_bytes (param i32 i32) (result i32)))
      (memory (export "memory") 1)
      (func (export "main") (result i64)
        (i64.extend_i32_s (call $random_bytes (i32.const 0) (i32.const 16)))))`,
  },
  {
    id: 'http-post-node-inject-available',
    imports: ['http-post'],
    // Injected host callback — no real network; proves link + inject path.
    // URL must match https allowlist entry (exact host + path prefix).
    limits: {
      maxHttpPosts: 8,
      httpUrlAllowlist: ['https://example.test/'],
    },
    inject: { httpPost: () => new TextEncoder().encode('pong') },
    check: (n) => Number(n) === 4,
    wat: `(module
      (import "kotoba" "http_post" (func $http_post (param i32 i32 i32 i32 i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 0) "https://example.test/x")
      (data (i32.const 50) "body")
      (func (export "main") (result i64)
        (i64.extend_i32_s
          (call $http_post (i32.const 0) (i32.const 22)
                           (i32.const 50) (i32.const 4)
                           (i32.const 200) (i32.const 256)))))`,
  },
  {
    id: 'llm-infer-node-available',
    imports: ['llm-infer'],
    limits: { maxLlmInfers: 2 },
    inject: { llmInfer: () => 'pong' },
    check: (n) => Number(n) === 4,
    wat: `(module
      (import "kotoba" "llm_infer" (func $llm_infer (param i32 i32 i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 0) "hi")
      (func (export "main") (result i64)
        (i64.extend_i32_s
          (call $llm_infer (i32.const 0) (i32.const 2) (i32.const 100) (i32.const 64)))))`,
  },
  {
    id: 'kagi-sign-node-inject-available',
    imports: ['kagi-sign'],
    limits: { maxKagiSigns: 1 },
    inject: {
      kagiDecisions: [{ ref: 'kagi://ops/key', purpose: 'release' }],
      kagiSigner: {
        authorizedSign(_decisions, _ref, _message) {
          return new Uint8Array(64).fill(7);
        },
      },
    },
    check: (n) => Number(n) === 64,
    wat: `(module
      (import "kotoba" "kagi_sign" (func $kagi_sign (param i32 i32 i32 i32 i32 i32) (result i32)))
      (memory (export "memory") 1)
      (data (i32.const 0) "kagi://ops/key")
      (data (i32.const 32) "msg")
      (func (export "main") (result i64)
        (i64.extend_i32_s
          (call $kagi_sign (i32.const 0) (i32.const 14)
                           (i32.const 32) (i32.const 3)
                           (i32.const 64) (i32.const 64)))))`,
  },
];

const tmpRoot = await mkdtemp(path.join(tmpdir(), 'kototama-host-parity-live-'));
const buildDir = path.join(tmpRoot, 'build');
await mkdir(buildDir, { recursive: true });

try {
  let actorHostUrl;
  let sourceNote;
  const localHost = [siblingActorHost, siblingActorHostAlt].find((p) => existsSync(p));
  if (localHost) {
    actorHostUrl = pathToFileURL(localHost).href;
    sourceNote = `sibling:${localHost}`;
  } else {
    for (const filePath of SRC_FILES) {
      const url = `https://cdn.jsdelivr.net/gh/kotoba-lang/wasm-webcomponent@${WASM_WEBCOMPONENT_COMMIT}/${filePath}`;
      const src = await (await fetch(url)).text();
      const dest = path.join(tmpRoot, filePath);
      await mkdir(path.dirname(dest), { recursive: true });
      await writeFile(dest, src);
    }
    actorHostUrl = pathToFileURL(path.join(tmpRoot, 'src', 'actor-host.js')).href;
    sourceNote = `cdn:${WASM_WEBCOMPONENT_COMMIT}`;
  }

  const {
    actorHostImports,
    hostCaps,
    inMemoryStore,
  } = await import(actorHostUrl);

  const results = [];
  let failed = 0;

  for (const entry of corpus) {
    try {
      const wasm = watToWasm(entry.wat, buildDir);
      const memoryBox = {};
      const store = inMemoryStore();
      const caps = hostCaps({
        grants: entry.imports,
        limits: {
          allowWriteImports: true,
          allowSecretImports: true,
          maxRandomBytes: 65536,
          maxLogWriteBytes: 65536,
          maxLogReadBytes: 65536,
          maxHttpPosts: 8,
          maxHttpGets: 8,
          maxLlmInfers: 2,
          ...(entry.limits || {}),
        },
      });
      const importObject = {
        kotoba: actorHostImports(entry.imports, caps, memoryBox, {
          store,
          runtime: 'node',
          ...(entry.inject || {}),
        }),
      };
      const { instance } = await WebAssembly.instantiate(wasm, importObject);
      memoryBox.memory = instance.exports.memory;
      const result = instance.exports.main();
      const ok = entry.check(result);
      results.push({
        ok,
        id: entry.id,
        host: 'node',
        imports: entry.imports,
        result: typeof result === 'bigint' ? result.toString() : result,
        live: true,
      });
      if (ok) console.log(`OK: ${entry.id}`);
      else {
        failed += 1;
        console.error(`FAIL: ${entry.id} result=${result}`);
      }
    } catch (e) {
      failed += 1;
      results.push({
        ok: false,
        id: entry.id,
        host: 'node',
        imports: entry.imports,
        live: true,
        error: e.message,
      });
      console.error(`FAIL: ${entry.id} ${e.message}`);
    }
  }

  const report = {
    ok: failed === 0,
    host: 'node',
    total: corpus.length,
    passed: corpus.length - failed,
    failed: results.filter((r) => !r.ok),
    results,
    case_ids: corpus.map((c) => c.id),
    wasm_webcomponent_source: sourceNote,
    wasm_webcomponent_commit: WASM_WEBCOMPONENT_COMMIT,
    note: 'T8.4 Node/browser live runner via wasm-webcomponent actor-host.js under Node WebAssembly',
  };

  // Machine-readable line for Clojure shell integration.
  console.log(`HOST_PARITY_LIVE_JSON:${JSON.stringify(report)}`);

  if (failed !== 0) {
    console.error(`host-parity-live Node runner FAILED (${failed}/${corpus.length})`);
    process.exit(1);
  }
  console.log(`OK: Node live host runner proved ${corpus.length} host-parity cases`);
} finally {
  await rm(tmpRoot, { recursive: true, force: true });
}
