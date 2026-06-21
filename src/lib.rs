//! # kototama — clojure-wasm-runtime
//!
//! One canonical **Clojure/EDN-subset → WebAssembly** runtime, unifying the two
//! compilers that grew up apart:
//!
//! - **kotoba-clj** — the general-purpose core compiler (kotoba-edn reader + wasm
//!   codegen). `compile_clj`.
//! - **kami-engine-clj** — the game layer on top: `GAME_PRELUDE` (vec/map/timer/vec3
//!   helpers) + the `kami:engine` host ABI. `compile_game`.
//!
//! Both read Clojure source *as EDN* (kotoba-edn is the single source-of-truth
//! reader) and emit real wasm bytes. kototama is the seam they share, plus the
//! **browser path**: this crate compiles to wasm itself, so a browser can compile
//! Clojure → wasm with no server, then run the result via `WebAssembly.instantiate`.
//! That is what lights up "edit CLJ → live game" (CodePen for CLJ games) on
//! network-isekai / isekai.network.
//!
//! See `90-docs/adr/0001-kototama-clojure-wasm-runtime.md`.

pub use kami_engine_clj;
pub use kotoba_clj;

/// The compiler error type, re-exported so hosts depend only on kototama.
pub use kami_engine_clj::CljError;

/// Compile a general Clojure/EDN-subset program to a wasm module (no game prelude).
pub fn compile_clj(src: &str) -> Result<Vec<u8>, String> {
    kotoba_clj::compile_str(src).map_err(|e| e.to_string())
}

/// Compile a kami **game** (`logic.clj`): the `GAME_PRELUDE` is prepended and the
/// `kami:engine` host ABI is targeted, so the module is drivable by a kami host
/// (native `kami-script-runtime`, or a browser host over the same imports).
pub fn compile_game(src: &str) -> Result<Vec<u8>, String> {
    compile_game_typed(src).map_err(|e| e.to_string())
}

/// Native: compile a game keeping the typed [`CljError`], so hosts get `?`-friendly
/// errors (kami-script-runtime). Same output as [`compile_game`].
pub fn compile_game_typed(src: &str) -> Result<Vec<u8>, CljError> {
    kami_engine_clj::compile_str_with_prelude(src)
}

/// The game prelude source (helpers written in the language itself).
pub fn game_prelude() -> &'static str {
    kami_engine_clj::game_prelude()
}

/// Browser API: the compiler runs *in the page* (kototama compiled to wasm). Returns
/// wasm bytes the browser instantiates directly — no native runtime needed.
#[cfg(target_arch = "wasm32")]
mod web {
    use wasm_bindgen::prelude::*;

    /// Compile general Clojure source → wasm bytes (Uint8Array in JS).
    #[wasm_bindgen]
    pub fn compile(src: &str) -> Result<Vec<u8>, JsValue> {
        super::compile_clj(src).map_err(|e| JsValue::from_str(&e))
    }

    /// Compile a kami game `logic.clj` → wasm bytes (GAME_PRELUDE + kami:engine ABI).
    #[wasm_bindgen]
    pub fn compile_game(src: &str) -> Result<Vec<u8>, JsValue> {
        super::compile_game(src).map_err(|e| JsValue::from_str(&e))
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn compiles_general_clj_to_wasm() {
        let w = super::compile_clj("(defn fact [n] (if (< n 2) 1 (* n (fact (- n 1)))))").unwrap();
        assert_eq!(&w[0..4], b"\0asm"); // real wasm magic
    }

    #[test]
    fn compiles_game_logic_to_wasm() {
        let w = super::compile_game("(defsystem tick [dt] (+ dt 1))").unwrap();
        assert_eq!(&w[0..4], b"\0asm");
    }
}
