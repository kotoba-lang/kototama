// kototama-wasm-kgraph-demo: like <kototama-wasm-run> (kototama-wasm-run.js)
// but wires up the kgraph-* host-import ABI (web/kgraph.js) instead of
// assuming a zero-import module. Demonstrates the same browser-native WASM
// AOT execution model — no JVM, no com.dylibso.chicory, no wasmtime — this
// time with a real (browser-side) host boundary a module can call into, not
// just a self-contained computation.
import { kgraphHostImports, writeEdn } from './kgraph.js';

export class KototamaWasmKgraphDemo extends HTMLElement {
  async connectedCallback() {
    const shadow = this.attachShadow({ mode: 'open' });
    const pre = document.createElement('pre');
    shadow.appendChild(pre);

    const src = this.getAttribute('src');

    try {
      const store = [];
      const memoryBox = {};
      const importObject = { kotoba: kgraphHostImports(store, memoryBox) };

      const response = await fetch(src);
      const { instance } = await WebAssembly.instantiateStreaming(response, importObject);
      memoryBox.memory = instance.exports.memory;

      const written = instance.exports.main();
      const heapBase = Number(this.getAttribute('heap-base') || 2048);
      const resultBytes = new Uint8Array(memoryBox.memory.buffer, heapBase, written);
      const resultText = new TextDecoder('utf-8').decode(resultBytes);

      pre.textContent =
        `kototama-wasm-kgraph-demo: ${src}\n` +
        `  store (after kgraph_assert!): ${writeEdn(store)}\n` +
        `  kgraph_query result:          ${resultText}`;
      this.dispatchEvent(new CustomEvent('kototama-wasm-kgraph-demo:done', {
        detail: { store, resultText },
      }));
    } catch (err) {
      pre.textContent = `kototama-wasm-kgraph-demo ERROR: ${err.message}`;
      this.dispatchEvent(new CustomEvent('kototama-wasm-kgraph-demo:error', {
        detail: { error: err.message },
      }));
    }
  }
}

customElements.define('kototama-wasm-kgraph-demo', KototamaWasmKgraphDemo);
