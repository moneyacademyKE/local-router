# Local Router Runtime Gap Analysis

**Date:** 2026-11-16 EAT
**Question:** Would replacing the local Wrangler/workerd door with Babashka, Swift, or Gleam solve the observed instability?

## Executive verdict

**No immediate rewrite is justified.** The evidence points to three different failure classes, and only one is plausibly related to the local process runtime:

1. **Process lifecycle:** launchd kept a Wrangler/Node process alive while requests hung. A health-aware supervisor would help.
2. **macOS file policy/building:** Wrangler reported `Cannot read directory "src": operation not permitted` while watching `/Users/moe/Desktop/infered`. A different language does not bypass macOS TCC; moving the checkout or granting the correct file access does.
3. **Application contract:** failed routes attempted to write `NULL` into `routing_decisions.selected_model`, which is `NOT NULL`. Rewriting the server would not fix this data-contract bug unless the new implementation deliberately fixes it.

**Recommendation:** keep Wrangler/workerd as the local execution truth, move its persisted state and preferably the checkout out of Desktop, pin Wrangler, and add a small Babashka health supervisor. If a native local daemon is still wanted after that experiment, choose **Swift**, not Gleam. Do not rewrite the router in Babashka or Gleam.

## Evidence observed

| Observation | What it proves | What it does not prove |
|---|---|---|
| `wrangler dev` runs the Worker in local `workerd`; local D1 is persisted under `.wrangler/state/v3/d1` | The local door is a Cloudflare-compatible simulation, not a generic Node server | That Wrangler's watcher/process lifecycle is reliable on this Mac |
| launchd had one Wrangler process running for about 37 minutes; `/v1/health` currently returned HTTP 200 in 8 ms | The current process is healthy and the port is live | That a stale process cannot wedge again |
| Prior log: `Cannot read directory "src": operation not permitted` and unresolved `src/worker.js` | The failed rebuild was a filesystem/watch/build access failure | That JavaScript, D1, or the router algorithm caused it |
| Prior log: repeated `NOT NULL constraint failed: routing_decisions.selected_model` | Failure telemetry is losing rows when no candidate serves the request | That the serving path itself caused those failures |
| Prior log: `upstream stream died mid-content` | Some failures are upstream stream/lifecycle events | That a local language rewrite can make an upstream model healthy |
| Current wrapper applies migrations before `wrangler dev` | Bootstrap ordering is explicit now | That launchd will restart a wedged process; `KeepAlive` reacts to exit, not health |

The repository's `src/dev_server.clj` is also not a Babashka implementation of the Worker: it shells out to Node and imports `src/worker.js`. Switching that file alone would change nothing about the runtime under test.

## Capability gap

| Requirement | Wrangler/workerd | Babashka | Swift | Gleam |
|---|---|---|---|---|
| Cloudflare Worker parity | **Best**: same `fetch`, Web Streams, bindings model, and local D1 simulation | Poor: must reimplement the Worker boundary and D1 adapter | Poor: separate server and storage semantics | Poor: separate server and storage semantics |
| SSE relay, back-pressure, cancellation | Existing `TransformStream`/`ReadableStream` implementation is already tested | Possible with `http-kit`, but a new SSE relay and cancellation contract is required | Strong with SwiftNIO async channels and structured concurrency | Possible on BEAM, but HTTP/SSE libraries and interop become new risk |
| D1 behavior and migrations | **Exactest local analogue**; Wrangler applies the repo migrations | SQLite/pod or another DB, not D1; migration and locking semantics diverge | SQLite package/driver and a new adapter | SQLite/DB driver and a new adapter |
| Process supervision | launchd + Wrangler; current KeepAlive is coarse | Excellent as a small watchdog/launcher; not a replacement for Worker semantics | Good native service lifecycle and graceful shutdown options | **Best built-in model** with OTP supervisors and restartable actors |
| macOS Desktop/TCC failure | Exposed because the watcher reads the Desktop checkout | Still exposed if it reads the same path | Still exposed if it reads the same path | Still exposed if it reads the same path |
| Cloud deploy parity | **Direct**: deploy the same Worker | None without retaining a JS Worker | None without retaining a JS Worker | None without retaining a JS Worker |
| Rewrite surface | None | Large: routing, streams, catalog, D1, tests | Large: all of the above plus Cloudflare boundary replacement | Large: all of the above plus BEAM deployment |

## Runtime-specific assessment

### Babashka

Babashka is the best **supporting tool**, not the best replacement. It starts quickly, has HTTP client/server options, process control, and is already the project's scripting runtime. It can provide:

- a bounded health loop that curls `/v1/health`;
- a restart threshold for a wedged process;
- log classification and a restart counter;
- migration-first startup and a stable `--persist-to` directory.

It cannot execute the existing Worker as a Cloudflare Worker. A direct Babashka port would duplicate the HTTP/SSE protocol, ranking state, D1 interactions, and tests while losing deployment parity. That is more moving parts, not fewer.

### Swift

Swift is the strongest candidate for a **separate native local daemon**. SwiftNIO's async APIs provide back-pressure-aware streams, and Swift service-lifecycle patterns provide structured cancellation and graceful shutdown. A compiled binary would remove `bunx`, Node, Wrangler's watcher, and esbuild from the long-lived local process.

That only addresses the process/build layer. It does not grant Desktop access, preserve D1 semantics automatically, or repair the `selected_model` contract. It also creates a second router implementation that can drift from the deployed Worker. Swift becomes rational only if the local door must be a first-class native gateway and the hardening experiment below still records runtime failures.

### Gleam

Gleam/OTP offers the best supervision story: a crashed child can be restarted without taking down the whole application. That is valuable for a genuinely BEAM-shaped service with independent workers and long-lived state.

This router is currently a small edge state machine around a Cloudflare Worker, not an OTP application. Gleam would require new HTTP/SSE, D1/SQLite, Cloudflare API, packaging, and deployment choices. It would solve neither TCC nor upstream stream deaths, and it would create the largest deployment-parity gap. It is the wrong first move.

## Lowest-complexity remediation path

1. **Pin Wrangler instead of resolving `@latest` at boot.** Version drift in a launchd process is needless variability.
2. **Put local persistence outside the checkout**, for example under `~/.opencrabs/state/infered-local/d1`, and pass the same `--persist-to` path to both migrations and `wrangler dev`. This preserves D1 history if the Desktop checkout is moved or rebuilt.
3. **Remove Desktop from the long-lived watch path.** The cleanest fix is a stable checkout outside protected Desktop folders. Granting TCC access is a machine-policy decision, not a router rewrite.
4. **Add a health-aware Babashka supervisor.** `KeepAlive` only notices process exit; it cannot detect a process that owns port 8790 but no longer answers. The supervisor should restart after bounded health failures, record the reason, and cap restart loops.
5. **Fix the telemetry contract in the existing Worker.** Failed requests with no serving candidate need an explicit sentinel such as `unserved` (or a schema migration that makes the field nullable). They must not silently lose the analytics row.
6. **Run a soak before considering a rewrite.** Measure the current door after the above changes, not just a single smoke request.

## Falsifiable decision gate

Run the hardened Wrangler door for 72 hours with one health probe per minute and a small scheduled SSE probe. Record:

- health availability and p95 response time;
- launchd/supervisor restarts and restart causes;
- Wrangler build/TCC errors;
- D1 migration failures and `selected_model` write failures;
- completion HTTP 5xx rate, stream `[DONE]` integrity, and upstream pre/post-content failures;
- RSS and CPU for the long-lived process.

**Keep Wrangler + Babashka supervisor** if there are zero TCC/build failures, zero telemetry constraint failures, no unexplained wedged-port event, and stream behavior remains within the existing upstream-failure baseline.

**Prototype Swift only** if the hardening pass still shows local process/build instability while the Worker itself remains correct. The Swift prototype should be a local gateway with contract tests against the Worker, not an immediate replacement of the deployed Worker.

**Reject Gleam and direct Babashka rewrites** unless the requirement changes to a separately deployable, non-Cloudflare router. That would be a product/platform decision, not a fix for this incident.

## Sources

- Cloudflare local development: https://developers.cloudflare.com/workers/local-development/
- Cloudflare local D1 development: https://developers.cloudflare.com/d1/best-practices/local-development/
- Cloudflare Wrangler `dev` and `--persist-to`: https://developers.cloudflare.com/workers/wrangler/commands/workers/
- Cloudflare Wrangler bundling and `--no-bundle`: https://developers.cloudflare.com/workers/wrangler/bundling/
- Babashka features/toolbox: https://babashka.org/ and https://babashka.org/toolbox/
- SwiftNIO async channels: https://github.com/apple/swift-nio/blob/main/docs/public-async-nio-apis.md
- Swift HTTP server project: https://github.com/swift-server/swift-http-server
- Gleam OTP and supervision: https://gleam.run/news/gleam-v0.12-and-gleam-otp-v0.1-released/
- macOS file and folder access: https://support.apple.com/guide/mac-help/control-access-to-files-and-folders-on-mac-mchld5a35146/mac

**Rich Hickey recommendation:** do not complect a runtime rewrite with a recoverable supervisor/TCC/data-contract problem. Keep the deployed Worker as the single routing model, and add only the smallest local supervision needed to make that model boring.
