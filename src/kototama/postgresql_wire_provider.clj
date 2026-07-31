(ns kototama.postgresql-wire-provider
  "T8.4 fail-closed inject surface for component-link PostgreSQL wire imports.

  Real wire sessions live behind component-linked Wasm guests + SCRAM (see
  docs/postgresql-qualification.edn). This provider only supplies ABI-matched
  HostFunctions that always fail closed, so host-parity live runners can prove
  actor:host linkage without a live database."
  (:require [kototama.tender :as tender])
  (:import (com.dylibso.chicory.wasm.types ValType)))

(defn- i32s [n]
  (vec (repeat n ValType/I32)))

(defn fail-closed-inject-provider
  "Return `:host-functions` map for wire imports, each deny-closed.

  - `:pg-open` returns handle 0 (i64)
  - other i32-result imports return -1"
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
         deny-i32)
        prepare-fn
        (tender/host-fn
         "pg_prepare"
         (into [ValType/I64] (i32s 8))
         ValType/I32
         deny-i32)
        session-reset-fn
        (tender/host-fn
         "pg_session_reset"
         [ValType/I64 ValType/I32 ValType/I32 ValType/I32 ValType/I32]
         ValType/I32
         deny-i32)
        close-stmt-fn
        (tender/host-fn
         "pg_close_statement"
         (into [ValType/I64] (i32s 6))
         ValType/I32
         deny-i32)]
    {:host-functions
     {:pg-open open-fn
      :pg-query query-fn
      :pg-simple-query simple-fn
      :pg-prepare prepare-fn
      :pg-session-reset session-reset-fn
      :pg-close-statement close-stmt-fn}
     :close! (fn [])}))
