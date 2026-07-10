# Agent Brief: Auto Liquidity Flush (T+16 Win-Rate Redistribution)

**Purpose:** Handoff for a future coding session. Implement automatic redistribution of no-trade liquidity into open Touch Turn entry brackets at a fixed time after market open — without changing initial bracket placement.

**Status:** Design locked from operator discussion (2026-07-10). Implement in slices below; run tests per [agent-regression-tests.mdc](../.cursor/rules/agent-regression-tests.mdc).

**Related (do not conflate):**

- **Liquidity gate** — opening-bar ATR filter (`NO_TRADE_NOT_LIQUIDITY`). Unchanged.
- **Manual Liquidity Allocator** — existing UI (`LiquidityAllocatorScreen`). Stays available when auto flush is off or for leftover pool balance.

---

## Mission

When enabled via a **global switch**, unused `maxDollars` from no-trade Touch Turn sessions accumulates in a **single synchronized pool per market session** (currency + session date). At **16 minutes after RTH open** for each market zone, flush that pool into **already-placed**, unfilled entry brackets using the **Bayesian win-rate formula**, with per-order price guards and up to **3 redistribution loops**. Anything still unallocated after 3 loops remains in the pool.

**Hard constraint:** Initial order placement is **untouched**. Brackets are still sized from `maxDollars` only at liquidity evaluation; the pool is never read during `requestBracketAfterLiquidityEvaluation`.

---

## Design decisions (locked in — do not relitigate)

### 1. Global switch

| Property | Value |
|----------|--------|
| Field | `StrategiesAppState.autoLiquidityFlushEnabled` (name negotiable; keep obvious) |
| Default | **`false`** (opt-in) |
| Persistence | `strategies-screen.json` via `StrategiesAppStatePersistence` |
| Wiring | `AppDependencies` → flush scheduler / coordinator (mirror `globalAutoStartEnabled` → `TouchTurnEngine`) |
| UI | Toggle on **Strategies screen** near global auto-start; optional status line on Liquidity Allocator screen |

**When off:** credits still land in bucket (today’s behaviour); manual allocator works; **no** scheduled flush, no auto resize.

**When on:** scheduled flush runs per market zone as described below.

### 2. Pool model (unchanged credits, synchronized consumption)

- **Credit (existing):** On session stop, `TouchTurnEngine.handleStopSession` → `liquidityBucketRepository.creditNoTradeSession` when `LiquidityBucketLogic.isNoTradeCreditEligible` (any `NO_TRADE*`, no orders placed).
- **Pool key:** `(sessionDate, currencyCode)` — existing `LiquidityCurrencyBucket` in `LiquidityBucketRepository`.
- **One writer for flush:** All auto debits and resizes go through a new **`LiquidityFlushCoordinator`** (see §5). Manual allocator must use the same coordinator for debits/resizes to avoid double-spend (refactor in PR 1).

Credits that arrive **after** the T+16 flush remain in the pool until the operator clears them manually or a future enhancement adds another flush — **not in scope** for v1.

### 3. Initial placement — do not disrupt

The following paths must **not** gain pool reads, upsize logic, or new hooks:

- `TouchTurnEngine.handlePollLiquidity` / `evaluateLiquidityAfterClosedBar`
- `withLiquidityEvaluatedIfClosed`
- `requestBracketAfterLiquidityEvaluation`
- `TouchTurnOrderPlanner.buildOrderPlan` / `sizeQuantity` at first bracket submit

Auto liquidity is **only** `resizeTouchTurnBracket` on existing working entries, triggered by the flush coordinator after open + 16 min.

### 4. Flush timing

| Market | Zone | RTH open | Flush at (local) |
|--------|------|----------|------------------|
| US | `America/New_York` | 09:30 | **09:46** |
| HK | `Asia/Hong_Kong` | 09:30 | **09:46** |
| UK (LSE) | `Europe/London` | 08:00 | **08:16** |

- Offset constant: **`AUTO_LIQUIDITY_FLUSH_MINUTES_AFTER_OPEN = 16`** (domain constant, testable).
- Compute fire time with existing `TouchTurnLogic.marketOpenEpochMillis(sessionDate, zoneId, …)` + offset.
- **One flush attempt per `(zoneId, sessionDate)` per app process day** — idempotent guard (in-memory + persisted “last flush epoch” optional) so restarts don’t double-flush.
- Schedule from engine or dedicated watcher that already knows running deployments / market zones (prefer hook near existing market-open / auto-start polling in `TouchTurnEngine`).

US note: 09:46 is ~1 minute after the 15m opening bar close (09:45), so most brackets exist and early no-trades have credited.

### 5. Flush algorithm (3 loops, win-rate, price guard)

**Eligible row** (reuse / extract from `LiquidityAllocatorMapper.toRow`):

- Touch Turn, `RUNNING`
- Same currency as pool
- `ordersPlacedForSession == true`
- Entry order working, `filled == 0`, no position opened
- Has planned bracket + resolvable bracket order ids

**Per flush event** for one `(currencyCode, sessionDate)`:

```text
if !autoLiquidityFlushEnabled → return
if pool.available <= 0 → return

repeat at most 3 times (loopIndex 1..3):
  rows ← eligible rows with quotes loaded
  if rows.isEmpty() → break

  distribution ← distributeLiquidityByBayesianWinRate(
    rows = rows.map { deploymentId to (winDays, lossDays) },
    available = pool.available
  )
  // existing: LiquidityAllocationLogic.distributeLiquidityByBayesianWinRate

  for each (deploymentId, dollarWeight) in distribution (deterministic order: deploymentId asc):
    row ← build row with allocationDollars = dollarWeight
    additionalQty ← suggestedQuantity(dollarWeight, entry, orderSizeRules) or 0

    if additionalQty <= 0:
      // HK board lot: budget can't buy a whole increment — skip, no debit
      continue

    if priceTooCloseToEntry(row, quote):
      // do NOT resize; do NOT debit — allocation stays in pool for next loop
      continue

    effectiveNotional ← additionalQty * entryPrice
    under coordinator lock:
      debit effectiveNotional from pool
      resizeTouchTurnBracket(currentQty + additionalQty)
      on resize failure → refund effectiveNotional
      on success → update deployment plannedQuantity

  // end for — skipped rows never debited; pool unchanged for them

// after 3 loops: leftover pool.available remains (manual clear / allocator)
```

**Win-rate formula (existing):**

```kotlin
(winDays + 1) / (winDays + lossDays + 2)
```

via `bayesianWinRateWeight` + `distributeLiquidityByWeight` in `LiquidityAllocationLogic.kt`.

**Price guard (“too close to entry”):**

- Evaluate **immediately before** each resize, not once at flush start.
- **Skip resize** (leave allocation in pool) when entry is imminently touchable / at risk of immediate fill.
- **Proposed rule (implement + test):** reuse `TouchTurnLogic.liveEntryTouchable(setup, bid, ask, invertTradeSide) == true` → **skip** (price has reached the entry band; upsizing would disrupt the working order).
- Alternative/complement: skip when `entryFillGap` ≤ small threshold (e.g. 0.1% of entry or 1 tick) — pick one rule in PR 2 and document in tests.
- Operator intent: avoid changing size when price is **very close** to entry.

**Debit amount:** Always **`additionalQty × entryPrice`**, never the raw win-rate dollar slice. Prevents HK / board-lot “debit with zero size change” bug in manual path too.

### 6. Synchronization (capital safety)

**Invariant for a flush:**

```text
Σ debits in this flush ≤ pool.available at flush start
Σ effective notional deployed ≤ Σ credits for that currency/date (no double-spend)
```

Requirements:

1. **`LiquidityFlushCoordinator`** — single entry point for flush + manual apply.
2. **`Mutex` per `(sessionDate, currencyCode)`** — no concurrent flush and manual apply.
3. **Single-flight flush** — if flush already running, do not start a second.
4. **Debit immediately before resize** under lock; refund on broker failure (existing pattern in `LiquidityAllocatorViewModel.applyInternal`).
5. **Re-read** open orders / quotes immediately before each row’s price check and resize (entry may have filled during loop).
6. If entry no longer eligible (partial fill, stopped) → skip, no debit.

Do **not** implement credit-triggered or debounced multi-pass flushes in v1 — only T+16 + internal 3 loops.

### 7. HK / board lots

- Use `deployment.instrument.orderSizeRules()` for `suggestedQuantity`.
- If dollar weight cannot buy ≥1 increment → **skip, no debit** (money stays in pool for next loop or remains after loop 3).
- Do not assume US unit lot for HK symbols.
- Tests must include `minOrderSize = 1000`, `orderSizeIncrement = 1000`.

### 8. Out of scope (v1)

- Second flush for credits after T+16
- Per-deployment toggle
- Even distribution mode (win-rate only)
- Changing liquidity gate or opening-bar logic
- Auto clear of leftover pool

---

## Architecture (target)

```text
┌─────────────────────────────────────────────────────────────────┐
│ TouchTurnEngine (unchanged bracket path)                        │
│   PollLiquidity → requestBracketAfterLiquidityEvaluation        │
│   handleStopSession → creditNoTradeSession                      │
└───────────────────────────┬─────────────────────────────────────┘
                            │ credits
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│ LiquidityBucketRepository (existing)                            │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│ AutoLiquidityFlushScheduler                                     │
│   watches clock + autoLiquidityFlushEnabled                     │
│   at marketOpen + 16min → coordinator.flush(currency, date)     │
└───────────────────────────┬─────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│ LiquidityFlushCoordinator                                       │
│   mutex per (date, currency)                                    │
│   3-loop win-rate distribute + price guard + debit/resize       │
└───────────────────────────┬─────────────────────────────────────┘
                            │
         ┌──────────────────┼──────────────────┐
         ▼                  ▼                  ▼
 LiquidityAllocationLogic  ExecutionManager   LiquidityAllocatorViewModel
 (distribute + win rate)   resizeTouchTurn    (manual → delegates here)
```

### New / moved types (suggested paths)

| Type | Package | Role |
|------|---------|------|
| `LiquidityFlushCoordinator` | `daytrader.domain` or `daytrader.engine.liquidity` | Locked flush + shared apply |
| `LiquidityFlushPlan` | same | One loop’s allocations + skip reasons |
| `AutoLiquidityFlushScheduler` | `daytrader.engine` or `daytrader.data` | T+16 scheduling per zone |
| `LiquidityEntryProximityGuard` | `daytrader.domain` | `priceTooCloseToEntry` pure function |
| `LiquidityAllocationApplier` | extract from ViewModel | debit + resize + refund + plannedQty |

---

## Implementation slices (PR order)

### PR 1 — Safe apply extraction (no behaviour change except debit fix)

**Goal:** Coordinator/applier usable by manual UI; fix effective-notional debit.

1. Extract `LiquidityAllocationApplier` from `LiquidityAllocatorViewModel.applyInternal`.
2. Skip apply when `additionalQty <= 0` or `previewQuantity <= currentQuantity`.
3. Debit `effectiveNotional`, not raw allocation dollars.
4. `LiquidityAllocatorViewModel` delegates to applier/coordinator.
5. Unit tests for applier; extend `E2ELiquidityAllocatorIntegrationTest`.

**Files touched:** `LiquidityAllocatorViewModel.kt`, new applier/coordinator, tests.

### PR 2 — Domain flush logic (pure, no scheduler)

**Goal:** Testable 3-loop flush without engine wiring.

1. `LiquidityEntryProximityGuard` + tests (touchable / gap threshold — document chosen rule).
2. `LiquidityFlushCoordinator.flush(...)` — pure inputs: pool snapshot, deployments, orders, quotes.
3. Uses `distributeLiquidityByBayesianWinRate` each loop.
4. Returns audit: `{ loop, debited, skippedProximity, skippedLot, failedResize, remainingPool }`.
5. `LiquidityFlushCoordinatorTest` + HK board-lot scenarios.

### PR 3 — Global switch + UI

1. `StrategiesAppState.autoLiquidityFlushEnabled` + persistence migration (default false).
2. `StrategiesViewModel` toggle handler.
3. UI toggle on `StrategiesScreen` / `App.kt` (same area as global auto-start).
4. Persistence round-trip test.

### PR 4 — T+16 scheduler + engine wiring

1. `AutoLiquidityFlushScheduler` — per `RthMarketSessions.all`, compute next flush instant; fire once per zone per session date.
2. Wire in `AppDependencies` / `TouchTurnEngine.start()` (or existing timer scope).
3. Read `autoLiquidityFlushEnabled` from `StrategiesAppStateRepository`.
4. On fire: group pool by currency for deployments in that zone; call `coordinator.flush` per active currency bucket.
5. Log / `SessionTrace` event: `auto_liquidity_flush_completed` with audit summary.

### PR 5 — E2E + regression

1. `E2EAutoLiquidityFlushTest` (emulator): credit pool from no-trade, place bracket on second symbol, advance clock to T+16, assert resize + bucket debited.
2. Test: proximity skip → no debit, pool unchanged for that slice.
3. Test: 3 loops exhaust proximity skips; remainder left in pool.
4. Test: global switch off → no flush at T+16.
5. `./gradlew unitTest` then `./gradlew e2eEmulator` then `./gradlew allTestsParallel`.

---

## Test-first checklist (Tier 1 examples)

Write failing tests **before** production wiring in each PR.

| Test | Assert |
|------|--------|
| `flush_distributesByBayesianWinRate` | Higher win-rate symbol gets larger effective notional |
| `flush_skipsWhenPriceTooCloseToEntry` | No debit, no resize, pool unchanged |
| `flush_skipsWhenBelowBoardLot` | HK 1000-lot; small slice skipped |
| `flush_loopsMaxThreeTimes` | Fourth pass not invoked; remainder > 0 ok |
| `flush_refundsOnResizeFailure` | Pool restored |
| `flush_doesNotRunWhenSwitchOff` | Scheduler no-op |
| `applier_debitsEffectiveNotionalNotWeight` | Debit == additionalQty × entry |
| `initialBracketPlacement_unchanged` | No imports/calls from flush in `requestBracketAfterLiquidityEvaluation` |
| `concurrentManualAndFlush_serialized` | Mutex: total debits ≤ starting available |

---

## Key file references (existing)

| Concern | Location |
|---------|----------|
| Pool credit on stop | `TouchTurnEngine.handleStopSession` (~705) |
| Credit eligibility | `LiquidityBucketLogic.isNoTradeCreditEligible` |
| Win-rate distribute | `LiquidityAllocationLogic.kt` |
| Eligible rows | `LiquidityAllocatorMapper.toRow` |
| Manual apply | `LiquidityAllocatorViewModel.applyInternal` |
| Resize broker | `ExecutionManager.resizeTouchTurnBracket` |
| Entry touchable | `TouchTurnLogic.liveEntryTouchable` |
| Fill gap | `LiquidityAllocatorMapper.entryFillGap` |
| Market open time | `TouchTurnLogic.marketOpenEpochMillis`, `RthMarketSessions` |
| Global settings pattern | `StrategiesAppState.globalAutoStartEnabled` |
| Order sizing / lots | `TouchTurnOrderPlanner.suggestedQuantity`, `InstrumentOrderSizeRules` |

---

## Agent checklist (definition of done)

1. [ ] Global switch persists; default **off**.
2. [ ] Initial bracket placement path unchanged (grep: no pool access in liquidity eval / bracket submit).
3. [ ] Flush fires at **open + 16 min** per market zone, once per session date.
4. [ ] Distribution uses **Bayesian win-rate** only.
5. [ ] Up to **3 loops**; leftover stays in pool.
6. [ ] **Price check** before each resize; too close → skip, no debit.
7. [ ] **Effective-notional** debits; HK board-lot skips handled.
8. [ ] **Coordinator mutex**; manual allocator shares same apply path.
9. [ ] Tests red→green per PR; `allTestsParallel` green before handoff.

---

## Open questions (resolve during PR 2 if needed)

1. **Proximity rule:** `liveEntryTouchable == true` vs fixed % gap — implement one, leave constant tunable.
2. **EUR market:** Include in scheduler via `RthMarketSessions.EUR` (08:16 London) if EUR deployments exist.
3. **Flush audit UI:** Log-only v1 vs toast on Liquidity screen — log-only is enough for v1.
4. **Process restart at 09:45:** Persist `flushedZoneDates: Set<String>` (`"${zoneId}:${sessionDate}"`) to avoid duplicate flush after restart — recommended in PR 4.
