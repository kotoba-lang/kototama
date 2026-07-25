//! Native micro-TCB Component host.
//!
//! The stdin/stdout protocol is deliberately tiny:
//!   1. Clojure sends one run envelope.
//!   2. each imported WIT function yields one provider-call envelope;
//!      Clojure validates and invokes the admitted provider, then responds.
//!   3. the host emits one terminal result or error envelope.
//!
//! No WASI linker is installed.  Therefore a Component receives only the
//! separately named aiueos imports carried in its admitted envelope.

mod v2_bindings;

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::{self, BufRead, BufReader, BufWriter, Write};
use std::sync::{Arc, Mutex};
use wasmtime::component::{Component, Linker, Resource, ResourceTable, Val};
use wasmtime::{Config, Engine, Store, StoreLimits, StoreLimitsBuilder};

#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
struct Ability {
    target: String,
    operation: String,
    #[serde(rename = "max-bytes")]
    max_bytes: u64,
    #[serde(rename = "max-items")]
    max_items: u64,
    #[serde(rename = "deadline-ms")]
    deadline_ms: u64,
    #[serde(rename = "audit-id")]
    audit_id: String,
}

#[derive(Debug, Deserialize)]
struct Import {
    name: String,
    ability: Ability,
}

#[derive(Debug, Deserialize)]
struct Run {
    #[serde(rename = "type")]
    kind: String,
    component: String,
    imports: Vec<Import>,
    fuel: u64,
    #[serde(rename = "memory-pages")]
    memory_pages: u64,
}

struct Protocol {
    input: BufReader<io::Stdin>,
    output: BufWriter<io::Stdout>,
}

struct State {
    protocol: Arc<Mutex<Protocol>>,
    limits: StoreLimits,
    // This is the host representation backing WIT v2 `own<grant>` /
    // `borrow<grant>`. A guest sees only an opaque component resource handle.
    grants: ResourceTable,
    // Per-import accounting lives in the native host, not the provider
    // process.  A compromised or buggy provider therefore cannot turn a
    // bounded grant into an unbounded sequence of guest calls.
    calls: BTreeMap<String, u64>,
}

#[derive(Debug, Clone)]
struct Grant {
    import: String,
    ability: Ability,
}

fn issue_grant(state: &mut State, name: &str, ability: &Ability) -> Result<Resource<Grant>> {
    validate_ability(name, ability)?;
    state.grants.push(Grant {
        import: name.to_owned(),
        ability: ability.clone(),
    }).map_err(|error| anyhow!("cannot issue Component grant resource: {error}"))
}

fn authorize_grant(state: &State, grant: &Resource<Grant>, name: &str, ability: &Ability) -> Result<()> {
    // Borrowed resources are looked up in a host-only table. A forged handle,
    // wrong resource type, or a handle issued for another import is denied
    // before provider protocol I/O begins.
    let issued = state.grants.get(grant)
        .map_err(|error| anyhow!("invalid Component grant resource: {error}"))?;
    if issued.import != name || issued.ability != *ability {
        bail!("Component grant resource does not authorize import {name}");
    }
    Ok(())
}

fn allowed_operation(name: &str) -> Option<&'static str> {
    match name {
        "aiueos-identity-sign" => Some("identity/sign"),
        "aiueos-identity-verify" => Some("identity/verify"),
        "aiueos-hash-sha256" => Some("hash/sha256"),
        "aiueos-http-post" => Some("http/post"),
        "aiueos-log-read" => Some("log/read"),
        "aiueos-clock-now" => Some("clock/now"),
        "aiueos-log-append" => Some("log/append"),
        _ => None,
    }
}

fn validate_ability(name: &str, ability: &Ability) -> Result<()> {
    let Some(expected_operation) = allowed_operation(name) else {
        bail!("unrecognized aiueos Component import: {name}");
    };
    if ability.operation != expected_operation {
        bail!("import {name} is bound to an invalid operation");
    }
    if ability.target.is_empty() || ability.audit_id.is_empty()
        || ability.max_bytes == 0 || ability.max_items == 0 || ability.deadline_ms == 0
    {
        bail!("import {name} has an unbounded or incomplete ability");
    }
    Ok(())
}

fn send(protocol: &mut Protocol, value: &Value) -> Result<()> {
    serde_json::to_writer(&mut protocol.output, value)?;
    protocol.output.write_all(b"\n")?;
    protocol.output.flush()?;
    Ok(())
}

fn consume_item_quota(calls: &mut BTreeMap<String, u64>, name: &str, ability: &Ability) -> Result<()> {
    let calls = calls.entry(name.to_owned()).or_insert(0);
    *calls = calls.checked_add(1).ok_or_else(|| anyhow!("provider call counter overflow"))?;
    if *calls > ability.max_items {
        bail!("import {name} exceeded its admitted max-items quota");
    }
    Ok(())
}

fn provider_call(state: &mut State, name: &str, ability: &Ability, value: i64) -> Result<i64> {
    // The descriptor is captured while linking, never supplied by the guest.
    validate_ability(name, ability)?;
    consume_item_quota(&mut state.calls, name, ability)?;
    let mut protocol = state.protocol.lock().map_err(|_| anyhow!("protocol lock poisoned"))?;
    send(&mut protocol, &json!({
        "type": "provider-call",
        "import": name,
        "ability": ability,
        "payload": { "value": value }
    }))?;
    let mut response = String::new();
    if protocol.input.read_line(&mut response)? == 0 {
        bail!("provider closed protocol before responding");
    }
    let response: Value = serde_json::from_str(&response).context("invalid provider response")?;
    if response.get("type") != Some(&Value::String("provider-result".into())) {
        bail!("expected provider-result response");
    }
    if response.get("import") != Some(&Value::String(name.into())) {
        bail!("provider response import does not match request");
    }
    response.get("value")
        .and_then(Value::as_i64)
        .ok_or_else(|| anyhow!("provider response must contain an i64 value"))
}

// With Wasmtime's deliberately minimal feature set its error type does not
// implement `std::error::Error`. Keep that boundary explicit rather than
// enabling unrelated Wasmtime features merely to use `anyhow` conversion.
fn wasmtime_result<T>(result: std::result::Result<T, wasmtime::Error>, context: &str) -> Result<T> {
    result.map_err(|error| anyhow!("{context}: {error}"))
}

fn run(request: Run, protocol: Arc<Mutex<Protocol>>) -> Result<i64> {
    if request.kind != "run" {
        bail!("first protocol envelope must be a run request");
    }
    let mut imports = BTreeMap::new();
    for import in request.imports {
        validate_ability(&import.name, &import.ability)?;
        if imports.insert(import.name, import.ability).is_some() {
            bail!("duplicate Component import");
        }
    }

    let mut config = Config::new();
    config.wasm_component_model(true);
    config.consume_fuel(true);
    let engine = wasmtime_result(Engine::new(&config), "cannot create Component engine")?;
    let component = wasmtime_result(Component::from_file(&engine, &request.component),
                                    "cannot compile admitted Component")?;
    let limits = StoreLimitsBuilder::new()
        .memory_size(request.memory_pages.saturating_mul(65536) as usize)
        .build();
    let mut store = Store::new(&engine, State {
        protocol,
        limits,
        grants: ResourceTable::new(),
        calls: BTreeMap::new(),
    });
    store.limiter(|state| &mut state.limits);
    wasmtime_result(store.set_fuel(request.fuel), "cannot set Component fuel")?;

    let mut linker = Linker::<State>::new(&engine);
    for (name, ability) in imports {
        let import_name = name.clone();
        wasmtime_result(linker.root().func_wrap(&name, move |mut cx, (value,): (i64,)| {
            provider_call(cx.data_mut(), &import_name, &ability, value)
                .map(|result| (result,))
                .map_err(|error| wasmtime::Error::msg(error.to_string()))
        }), "cannot bind admitted Component import")?;
    }
    let instance = wasmtime_result(linker.instantiate(&mut store, &component),
                                   "Component imports did not match the admitted bindings")?;
    let function = instance.get_func(&mut store, "main")
        .ok_or_else(|| anyhow!("Component does not export main"))?;
    let mut results = [Val::S64(0)];
    wasmtime_result(function.call(&mut store, &[], &mut results), "Component main failed")?;
    match results.into_iter().next() {
        Some(Val::S64(value)) => Ok(value),
        _ => bail!("Component main must return s64"),
    }
}

fn main() {
    let protocol = Arc::new(Mutex::new(Protocol {
        input: BufReader::new(io::stdin()),
        output: BufWriter::new(io::stdout()),
    }));
    let outcome = (|| -> Result<i64> {
        let mut line = String::new();
        {
            let mut locked = protocol.lock().map_err(|_| anyhow!("protocol lock poisoned"))?;
            if locked.input.read_line(&mut line)? == 0 {
                bail!("missing run envelope");
            }
        }
        run(serde_json::from_str(&line).context("invalid run envelope")?, protocol.clone())
    })();
    let terminal = match outcome {
        Ok(value) => json!({ "type": "result", "value": value }),
        Err(error) => json!({ "type": "error", "message": format!("{error:#}") }),
    };
    if let Ok(mut locked) = protocol.lock() {
        let _ = send(&mut locked, &terminal);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bounded_ability() -> Ability {
        Ability {
            target: "clock://monotonic".into(),
            operation: "clock/now".into(),
            max_bytes: 1,
            max_items: 1,
            deadline_ms: 1,
            audit_id: "native-quota-test".into(),
        }
    }

    #[test]
    fn native_host_rejects_calls_after_the_admitted_item_quota() {
        let mut calls = BTreeMap::new();
        let ability = bounded_ability();
        consume_item_quota(&mut calls, "aiueos-clock-now", &ability).unwrap();
        assert!(consume_item_quota(&mut calls, "aiueos-clock-now", &ability).is_err());
    }

    #[test]
    fn host_only_grant_resource_cannot_cross_named_imports() {
        let protocol = Arc::new(Mutex::new(Protocol {
            input: BufReader::new(io::stdin()),
            output: BufWriter::new(io::stdout()),
        }));
        let mut state = State {
            protocol,
            limits: StoreLimitsBuilder::new().build(),
            grants: ResourceTable::new(),
            calls: BTreeMap::new(),
        };
        let ability = bounded_ability();
        let grant = issue_grant(&mut state, "aiueos-clock-now", &ability).unwrap();
        assert!(authorize_grant(&state, &grant, "aiueos-clock-now", &ability).is_ok());
        assert!(authorize_grant(&state, &grant, "aiueos-log-append", &ability).is_err());
    }
}
