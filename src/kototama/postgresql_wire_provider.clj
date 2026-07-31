(ns kototama.postgresql-wire-provider
  "T8.4 fail-closed inject surface for component-link PostgreSQL wire imports.

  Real wire sessions live behind component-linked Wasm guests + SCRAM (see
  docs/postgresql-qualification.edn). This provider only supplies ABI-matched
  HostFunctions that always fail closed, so host-parity live runners can prove
  actor:host linkage without a live database."
  (:require [kototama.tender :as tender])
  (:import (com.dylibso.chicory.wasm.types ValType)))

(defn fail-closed-inject-provider
  "Return `:host-functions` map for core wire imports, each deny-closed.

  - `:pg-open` returns handle 0 (i64)
  - `:pg-query` / `:pg-simple-query` return -1 (i32)"
  []
  (let [deny-i32 (fn [_ _] -1)
        deny-i64 (fn [_ _] 0)
        open-fn
        (tender/host-fn
         "pg_open"
         [ValType/I32 ValType/I32 ValType/I32 ValType/I32
          ValType/I32 ValType/I32 ValType/I32]
         ValType/I64
         deny-i64)
        query-fn
        (tender/host-fn
         "pg_query"
         [ValType/I64 ValType/I32 ValType/I32 ValType/I32 ValType/I32]
         ValType/I32
         deny-i32)
        simple-fn
        (tender/host-fn
         "pg_simple_query"
         [ValType/I32 ValType/I32 ValType/I32 ValType/I32
          ValType/I32 ValType/I32 ValType/I32]
         ValType/I32
         deny-i32)]
    {:host-functions
     {:pg-open open-fn
      :pg-query query-fn
      :pg-simple-query simple-fn}
     :close! (fn [])}))
