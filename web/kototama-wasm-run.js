// kototama-wasm-run: minimal browser-native WASM AOT host, exposed as a
// custom element (WebComponent). No JVM, no com.dylibso.chicory, no
// wasmtime process — the module is already an AOT-compiled binary (emitted
// by `kotoba wasm emit` in kotoba-lang/kotoba) and the browser's own
// WebAssembly engine (V8/JSC/SpiderMonkey) instantiates + runs it directly.
//
// R0 scope: zero-import modules only (e.g. src/demo.kotoba in kotoba-lang/kotoba,
// exports `main() -> i32`). Host-import ABI (kgraph/kse/auth/... — the same
// wire contract kotoba.wasm-exec implements on the JVM side) has no browser
// implementation yet; that is follow-up, not this element's job.
export class KototamaWasmRun extends HTMLElement {
  async connectedCallback() {
    const shadow = this.attachShadow({ mode: 'open' });
    const pre = document.createElement('pre');
    shadow.appendChild(pre);

    const src = this.getAttribute('src');
    const exportName = this.getAttribute('export') || 'main';

    try {
      const response = await fetch(src);
      const { instance } = await WebAssembly.instantiateStreaming(response, {});
      const fn = instance.exports[exportName];
      if (typeof fn !== 'function') {
        throw new Error(`module has no export "${exportName}"`);
      }
      const result = fn();
      pre.textContent = `kototama-wasm-run: ${src} ${exportName}() => ${result}`;
      this.dispatchEvent(new CustomEvent('kototama-wasm-run:done', { detail: { result } }));
    } catch (err) {
      pre.textContent = `kototama-wasm-run ERROR: ${err.message}`;
      this.dispatchEvent(new CustomEvent('kototama-wasm-run:error', { detail: { error: err.message } }));
    }
  }
}

customElements.define('kototama-wasm-run', KototamaWasmRun);
