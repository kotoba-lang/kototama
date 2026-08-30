# Claude project rules

Follow `AGENTS.md`. Kototama Q9 work moves complete components and must build
without a JVM through native `kotoba` and Amu `--jvm-free`. Decision-only
`.cljk` files and JVM-backed acceptance are forbidden; block on missing
tooling instead of falling back.
