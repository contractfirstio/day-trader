# Replay ↔ Paper Parity Fix — Handoff Document

Use this document after reverting the branch that mixed **product fixes** with **test-stabilization rabbit-hole changes**. It describes only the replay consistency work: making interactive replay, batch replay, and headless backtest reproduce the same outcome as the original paper session when config and captured quotes are identical.

---

## Problem statement

### Invariant (target behavior)

> Captured IB ticks are published in timestamp order. After each tick, virtual time (`ReplayClock`) equals that tick's `epochMs`, and the Touch Turn engine evaluates liquidity, fills, and stop rules at that moment — the same way paper did when the ticks arrived live.

Paper (`paper-live-ib`) and replay (`REPLAY` broker) both use the **broker emulator** for fills. Replay is not expected to match a real exchange; it **is** expected to match paper when replaying the same capture with the same rules.

### Bug 1 — OPEN_DEADLINE flatten at wrong time/price (symbol 1810)

**Paper session:** `session-951b53756234d9c8`  
**Replay session:** `session-b3cfa94e8d55a55a`  
**Deployment:** `inst-8c88e7538a4243fc`

| | Paper | Replay (broken) |
|---|-------|-----------------|
| Entry | SELL 4200 @ 22.92 | Same |
| OPEN_DEADLINE flatten | ~11:00 virtual, ~22.94 ask | ~11:07 virtual, ~22.76 ask |
| Recorded PnL | **−84 HKD** | **+672 HKD** |

Engine/UI stayed aligned (`touch_turn_state_sync` mismatchCount = 0). The gap was **replay publishing quotes past the deadline before stop rules ran**, so flatten saw a different book than paper.

**Root cause:** Interactive quote drip advanced `ReplayClock` on every tick, but `PollStopRules` ran on a **30s wall-clock timer** (only max-speed path polled per quote). Virtual time crossed 11:00 while stop checks lagged; later ticks (e.g. ask 22.76) were ingested before OPEN_DEADLINE fired.

### Bug 2 — Entry fill before `ordersPlacedAt` (symbol 00148 HK)

**Paper session:** `session-732253989f3166ba`  
**Replay session:** `session-6d7386e30850303d`

| | Paper | Replay (broken) |
|---|-------|-----------------|
| Entry fill | SELL 500 @ **135.2** | SELL 500 @ **135.3** |
| Exit | BUY 500 @ 137.7 | Same |
| PnL | **−1250 HKD** | **−1200 HKD** |

Paper had ticks with bid 135.3 **before** `ordersPlacedAt`; replay played them while the entry stop was working, causing an early fill. Paper never saw those ticks during bracket working time.

### Bug 3 — Recorded PnL vs emulator on session stop

On OPEN_DEADLINE stop, flatten happened in the emulator but `session_closed` sometimes captured **pre-flatten** fills/PnL (entry-only, wrong realized PnL). Paper and batch comparisons need post-flatten gateway fills.

---

## Fix map (product changes only)

Implement in this order. Each tier is independently valuable; **Tier 1** fixes the 1810 case.

### Tier 1 — Per-quote stop evaluation + halt drip (Fixes A, B)

**Files:** `ReplayPlaybackOrchestrator.kt`, `MultiSymbolQuoteFeeder.kt`

1. Replace `onQuotePublished` callback with **`onAfterQuotePublished: suspend (symbol) -> Boolean`**.
2. After each published quote (interactive drip **and** max-speed backtest path):
   - `engine.dispatchAndAwait(PollStopRules)` (+ drain).
   - Return **`false`** when no `RUNNING` deployment remains for that symbol → feeder calls **`disableDripForSymbol`** and stops ingesting more quotes.
3. Keep liquidity nudge every N quotes as today; do **not** move stop polling back to a wall-clock timer.

**Why:** OPEN_DEADLINE must fire when virtual clock crosses the deadline, not when a background timer fires.

### Tier 2 — OPEN_DEADLINE quote cap + pre-flatten book sync (Fixes C, H)

**New file:** `ReplayQuoteStopSync.kt`  
**Files:** `MultiSymbolQuoteFeeder.kt`, `ReplayPlaybackOrchestrator.kt`

1. Add `ReplayQuoteStopSync.openDeadlineEpochMs(deployment, sessionDate)`:
   - RTH open + `rules.stopAfterOpenMinutes` when `openDeadline` enabled.
2. Wire `quoteFeeder.resolveStopDeadlineEpochMs` from orchestrator (min deadline across running deployments for symbol).
3. In `prepareQuotePublish`, when next quote `epochMs > deadline`:
   - Publish only through `deadline` (`publishThroughDeadline`).
   - Advance clock to `deadline`.
   - Run `onAfterQuotePublished` (stop poll).
   - **Do not** publish the “trap” quote past deadline until stop has run (then drip should halt per Tier 1).
4. On replay session stop, before flatten: **`quoteFeeder.publishUpTo(symbol, clock.nowEpochMillis())`** so emulator sees the same book as at decision time.

**Regression test:** `MultiSymbolQuoteFeederOpenDeadlineTest` — proves drip stops at deadline and leaves later quotes unpublished.

### Tier 3 — Post-flatten fill capture (Fixes F, G)

**Files:** `TouchTurnEngine.kt`, `ReplayHybridRuntime.kt`, `TouchTurnManualStopHandler.kt` (already has `flattenOnBroker` flag on main)

In `handleStopSession`, before building `session_closed`:

1. Add optional hooks on `TouchTurnEngine`:
   - `replayPrepareSessionStop: (symbol) -> Unit` → publish quotes up to virtual now.
   - `replayDrainBroker: suspend () -> Unit` → drain emulator/gateway pipeline.
2. When flattening on stop:
   - Call `replayPrepareSessionStop(symbol)`.
   - `gateway.flattenSymbolForSymbol(symbol)`.
   - `replayDrainBroker()`.
   - Read **`gateway.fills`** for session trade capture (not stale pre-flatten list).
   - Pass `flattenOnBroker = false` into `TouchTurnManualStopHandler` (flatten already done).
3. Wire hooks in `ReplayHybridRuntime` when constructing the engine for replay.

**Why:** Recorded PnL and `sessionTrades` must reflect the flatten fill the emulator actually produced.

### Tier 4 — Entry fill anchor after bracket (HK 00148 fix)

**New file:** `ReplayQuoteFillAnchor.kt`  
**Files:** `ReplayPlaybackOrchestrator.kt`, `ReplaySessionController.kt`

1. Read `ordersPlacedAt` from bundle timeline milestones.
2. **`alignAfterBracketPlaced`**: if next pending quote `epochMs <= ordersPlacedAt`, seek feeder to **first quote strictly after** that anchor; advance clock to that quote if needed.
3. Call from:
   - Interactive replay: after bracket placed / in `onAfterQuotePublished` when `ordersPlacedForSession`.
   - Headless backtest: in `runBacktestReplay` before driving quotes to completion.

**Why:** Prevents replay from filling on ticks paper never saw while the entry stop was working.

**Tests:** `ReplayQuoteFillAnchorTest`, fixture `ReplaySessionFixtures.entryFillParityContents()`, integration test `runBacktestReplay_entryFillParity_matchesPaperEntryPriceAndPnl` (expects entry 135.2, PnL −1250).

### Tier 5 — Virtual clock alignment (supporting)

**File:** `ReplaySessionTiming.kt`

- `alignClockToSessionOpen(clock, deployment, sessionDate)` — reset replay clock to RTH open for the session date, not wall clock when user clicks Start.
- Used by `ReplaySessionController.alignBacktestClock` and interactive playback bootstrap.

### Tier 6 — Batch replay honestly re-simulates (Fix D)

**Files:** `ReplayBacktestOptions.kt`, `ReplaySessionController.kt`, `BatchReplayRunner.kt`

1. Add `applyGroundTruthFills: Boolean = false` to `ReplayBacktestOptions`.
2. Default batch/background replay: **`applyGroundTruthFills = false`** — PnL comes from emulator re-simulation only.
3. Opt-in ground truth overlay (`ReplayGroundTruthApplier`) only for verify-capture / CI regression when rules match capture.

**Why:** Batch must not “match” paper by copying fills while tick replay is still wrong.

### Tier 7 — Capture instrument / board lot parity (supporting)

**File:** `ReplaySessionController.kt` companion

- `ensureDeploymentForCapture` / `syncInstrumentFromCapture` / `resolveCaptureInstrument`
- Apply manifest `instrument` or paper entry fill quantity so bracket sizing (e.g. HK 500-lot) matches paper.

### Tier 8 — Headless inbound event ordering (batch only, small)

**Files:** `QueuedBrokerGateway.kt`, `GatewayEvent.kt`, `ReplayHybridRuntime.kt`

For headless backtest when quotes are ingested synchronously:

1. `setPauseInboundProcessing(true)` during backtest — pause IO inbound consumer.
2. `applyInboundEvent(event)` on caller thread after quote ingest.
3. `drainAllPendingInboundEvents()` after bracket placement and before stop.
4. `InboundShutdown` + `shutdownInboundConsumer()` on harness shutdown (clean test teardown).

**Scope:** Needed so batch replay sees fill events in order; **not** a substitute for Tiers 1–4.

### Tier 9 — Regression tests (Fix I)

| Test | Asserts |
|------|---------|
| `ReplayQuoteStopSyncTest` | Deadline epoch = RTH open + stopAfterOpenMinutes |
| `MultiSymbolQuoteFeederOpenDeadlineTest` | Drip stops at deadline; trap quote unpublished |
| `ReplayQuoteFillAnchorTest` | Seek skips pre-`ordersPlacedAt` bid 135.3 |
| `ReplaySessionTimingTest` | Clock aligns to session open |
| `ReplayBacktestTest.runBacktestReplay_entryFillParity_*` | End-to-end HK entry 135.2, PnL −1250 |

Optional follow-up: full **1810 OPEN_DEADLINE** fixture from production capture (late entry, flatten ~22.94, PnL ≈ −84) — planned as Fix I but may not be fully committed; add when re-applying.

---

## Explicitly OUT OF SCOPE for the first re-apply PR

These were added while chasing flaky tests and **should not** be part of the initial parity re-apply. Add only if a specific test proves a real product bug after Tiers 1–8 pass.

| Change | Why skip initially |
|--------|-------------------|
| `TouchTurnEngine.setBacktestSyncCommands` / synchronous liquidity watch / pausing `stopRulesPollJob` | Test harness concurrency band-aids |
| `awaitBacktestBootstrapWork` and related bootstrap wait loops | Broke trade path (timeout before fast-forward) |
| Synchronous `runClosedBarRefetch` during backtest | Duplicate-eval workaround; fix drip/stop first |
| JUnit default timeout 10s, `SlowIntegrationTest` removal, `forkEvery` tweaks | Test infra, not product |
| Mass `ReplaySessionController.driveSessionToCompletion` yield/spin tuning | Stabilization only |
| `BatchReplayContractTest` / `MainDispatcherContractTest` harness refactors | Isolation for flaky suite |

**Rule of thumb:** If the change is “make the test finish faster” rather than “make replay evaluate at the same virtual instant as paper,” leave it out.

---

## Files touched by the product fix (reference)

| File | Role |
|------|------|
| `replay/ReplayQuoteStopSync.kt` | **NEW** — OPEN_DEADLINE epoch helper |
| `replay/ReplayQuoteFillAnchor.kt` | **NEW** — post-bracket quote seek |
| `replay/ReplaySessionTiming.kt` | **NEW** — clock align to RTH open |
| `replay/MultiSymbolQuoteFeeder.kt` | Per-quote stop callback, deadline cap, drip halt |
| `replay/ReplayPlaybackOrchestrator.kt` | `onAfterQuotePublished`, stop deadline wiring, fill anchor |
| `replay/ReplayHybridRuntime.kt` | Stop hooks, inbound drain, shutdown poison pills |
| `replay/ReplaySessionController.kt` | Fill anchor in backtest, clock align, capture instrument, ground-truth opt-in |
| `replay/ReplayBacktestOptions.kt` | `applyGroundTruthFills` flag |
| `replay/BatchReplayRunner.kt` | `applyGroundTruthFills = false` |
| `engine/touchturn/TouchTurnEngine.kt` | `replayPrepareSessionStop` / `replayDrainBroker` in `handleStopSession` |
| `gateway/QueuedBrokerGateway.kt` | Pause inbound consumer, `applyInboundEvent`, shutdown |
| `gateway/GatewayEvent.kt` | `InboundShutdown` |
| `replay/support/ReplaySessionFixtures.kt` | `entryFillParityContents()` test fixture |
| Tests listed in Tier 9 | Regression coverage |

---

## Verification checklist

After re-applying Tiers 1–8:

1. **Unit:** `./gradlew :day-trader:desktopTest --tests "daytrader.replay.MultiSymbolQuoteFeederOpenDeadlineTest" --tests "daytrader.replay.ReplayQuoteFillAnchorTest" --tests "daytrader.replay.ReplayQuoteStopSyncTest" --tests "daytrader.replay.ReplaySessionTimingTest"`
2. **Integration:** `./gradlew :day-trader:desktopTest --tests "daytrader.replay.ReplayBacktestSessionTest.runBacktestReplay_entryFillParity_matchesPaperEntryPriceAndPnl"`
3. **Manual:** Re-run replay on capture `inst-8c88e7538a4243fc` / 1810 — PnL should be ≈ **−84 HKD**, flatten near **22.94**, not +672 @ 22.76.
4. **Full suite:** `./gradlew :day-trader:desktopTest` — fix any failures without re-introducing sync-command hacks.

---

## Agent prompt (copy-paste for a fresh session)

```
You are re-applying the replay ↔ paper parity fix to day-trader after a branch revert.

Read first: docs/replay-paper-parity-fix-handoff.md

Goal: With identical Touch Turn rules and the same captured prices.jsonl, interactive replay,
batch replay, and headless backtest must reproduce the paper session outcome (fills, stop
trigger, realized PnL) — not merely match via ReplayGroundTruthApplier.

Implement ONLY the product tiers in the handoff doc (Tiers 1–8). Do NOT re-introduce the
test rabbit-hole changes listed under "OUT OF SCOPE" (backtestSyncCommands, awaitBacktestBootstrapWork,
JUnit timeout band-aids, etc.) unless a failing test proves a separate product bug.

Work in this order:
1. Tier 1 (A+B): MultiSymbolQuoteFeeder.onAfterQuotePublished + PollStopRules per quote + stop drip when session ends.
2. Tier 2 (C+H): ReplayQuoteStopSync + deadline cap in prepareQuotePublish + publishUpTo before flatten.
3. Tier 3 (F+G): TouchTurnEngine replayPrepareSessionStop / replayDrainBroker + post-flatten gateway fills.
4. Tier 4: ReplayQuoteFillAnchor + wire in orchestrator and ReplaySessionController.runBacktestReplay.
5. Tier 5–7: ReplaySessionTiming, applyGroundTruthFills default false, ensureDeploymentForCapture.
6. Tier 8: QueuedBrokerGateway inbound pause/drain for headless batch only.
7. Tier 9 tests: MultiSymbolQuoteFeederOpenDeadlineTest, ReplayQuoteFillAnchorTest, entry fill parity test.

Invariant to preserve: after each published quote, virtual clock == quote epochMs and stop rules
have been evaluated before any later quote is published.

Reference the original diagnosis:
- 1810: paper −84 vs replay +672 — OPEN_DEADLINE saw ask 22.76 because stop polled on wall clock.
- 00148: paper entry 135.2 vs replay 135.3 — pre-ordersPlacedAt ticks caused early fill.

Run the verification checklist in the doc before finishing. Keep diffs minimal and match
existing code style. Do not commit unless asked.
```

---

## Original diagnosis sessions (for manual replay)

| Label | Session ID | Path under `~/Library/Application Support/Day Trader/` |
|-------|------------|--------------------------------------------------------|
| Paper 1810 | `session-951b53756234d9c8` | `paper-live-ib/sessions/inst-8c88e7538a4243fc/` |
| Replay 1810 (bad) | `session-b3cfa94e8d55a55a` | `replay/sessions/inst-8c88e7538a4243fc/` |
| Paper HK entry | `session-732253989f3166ba` | `paper-live-ib/sessions/inst-238a23ac1f5e40cc/` |
| Replay HK entry (bad) | `session-6d7386e30850303d` | `replay/sessions/inst-238a23ac1f5e40cc/` |

Use the Day Trader log diagnosis workflow in README: correlate `application.jsonl`, `prices.jsonl`, and `emulator-engine.jsonl` by `epochMs` + symbol.
