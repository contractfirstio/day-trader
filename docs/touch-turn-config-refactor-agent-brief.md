# Agent Brief: Touch Turn Config Refactor (Gates + Placement + Deployment Config)

**Purpose:** Handoff prompt for a new coding session. Implement **Phase 1** of the Touch Turn configuration architecture. Refactor tests (including E2E) **incrementally per step** — do not leave test updates to the end.

**Related research (do not implement yet):** [touch-turn-bar-shape-mode-selection.md](./touch-turn-bar-shape-mode-selection.md) — bar-shape routing is **out of scope** for this refactor; only lay foundations (gate type extensibility, run-record fields).

---

## Mission

Replace the monolithic per-deployment `TouchTurnRuleConfig` blob with **three first-class, named, shareable configuration layers**:

1. **Gates** — global, strategy-agnostic predicates (what must be true to trade).
2. **Order Placement Strategies** — Touch Turn–specific (how to build brackets once gates pass).
3. **Deployment Config** — Touch Turn–specific composition linking one gate set to one placement strategy.

Then wire **Strategy Deployments** to reference a Deployment Config by id instead of embedding full rules inline.

**Phase 1 scope only:** one gate set → one placement strategy per Deployment Config. No multi-rule routing yet.

**Behaviour parity:** After migration, existing deployments must trade identically to today until the operator edits library entities.

---

## Design decisions (locked in — do not relitigate)

### 1. Three object types and sharing rules

| Entity | Scope | Shareable across |
|--------|--------|------------------|
| **Gates / GateSet** | App-wide library | All strategy types (Touch Turn, Quick Flip, future) |
| **Order Placement Strategy** | Per `StrategyType` | Touch Turn deployments only (start with Touch Turn) |
| **Deployment Config** | Per `StrategyType` | Touch Turn deployments only — **not** across Quick Flip |

- **Strategy Deployment** (per symbol, max risk, auto-start) holds `touchTurnDeploymentConfigId` (or equivalent), not inline gate/placement fields.
- Each library entity has a **friendly name** (e.g. `"HK Liquidity 25%"`, `"Inverse Continuation"`, `"HK Inverse Default"`).
- **Operator configures all thresholds** — nothing hardcoded except safe defaults for *new* entities. Defaults may mirror `TouchTurnDefaults` but behaviour must come from persisted config.

### 2. Gates vs placement vs guards (evaluation order)

```text
1. Bootstrap        — opening 15m bar + daily ATR available
2. Gate evaluation  — ALL gates in the linked GateSet must pass (AND)
3. Resolve placement — fixed strategy from Deployment Config (Phase 1)
4. Build bracket     — computeBracketSetup / TP-SL / entry offset
5. Placement guards  — invert-stop block, live quote checks (NOT gates)
```

- **Gates** answer: “Should we trade?”
- **Placement strategy** answers: “Reversal or inverse? What bracket params?”
- **Guards** answer: “Is this bracket safe to submit right now?” (keep existing `invertPlacementBlockOutcome` etc.)

### 3. Phase 1 gate types

Implement **one** gate type only:

- **`ATR_LIQUIDITY`** — equivalent to today’s `liquidityRangeDailyAtr` + `atrLiquidityRatio` + `dailyAtrLookbackPeriods`.
  - Params: `enabled`, `atrLiquidityRatio`, `dailyAtrLookbackPeriods` (or reference existing defaults).
  - Failure outcome: `NO_TRADE_NOT_LIQUIDITY` (same as today).

Do **not** implement bar-shape gates in this refactor.

### 4. Phase 1 placement strategies

Map to today’s `TouchTurnRuleConfig` placement fields:

- **`invertTradeSide`** — `false` = Touch Turn (reversal), `true` = Inverse (continuation).
- Bracket sizing: `takeProfitFibRatioGreen/Red`, `takeProfitToStopLossRatio`, `entryInwardOffsetRatioOfRange`.
- Trailing: `enables.adjustableTrailingStop`, `trailingStopTriggerFractionOfEntryToTp`, `trailingStopArmFractionOfEntryToStop`.
- Session: `enables.openDeadline`, `stopAfterOpenMinutes`, `closedBarRefetchSettleMs` (keep on placement or deployment config — prefer placement strategy for bracket/session behaviour; document choice in PR).

Friendly names examples: `"Touch Turn Reversal"`, `"Inverse Continuation"`.

### 5. Deployment Config (Phase 1 shape)

```text
TouchTurnDeploymentConfig
  id, name
  gateSetId: String          // ref to global GateSet
  placementStrategyId: String // ref to Touch Turn placement library
  strategyType: TOUCH_AND_TURN_SCALPER  // always, for now
```

Single link only — no `rules[]` array until Phase 2.

### 6. Adapter strategy (minimize blast radius)

**Recommended for Phase 1:** Resolve libraries into the existing `TouchTurnRuleConfig` at session start / liquidity evaluation so `TouchTurnLogic`, `TouchTurnEngine`, and IB paths change minimally.

```text
resolve(deploymentConfigId) → TouchTurnRuleConfig
```

Add new types alongside; do not delete `TouchTurnRuleConfig` in Phase 1. Mark resolver as the single place that maps GateSet + PlacementStrategy → legacy config.

### 7. Persistence layout (proposed)

Follow `AppDataFiles`, `BrokerKind.dataDirectorySegment`, and existing `JsonFileStore` backup patterns.

**App data root** (macOS: `~/Library/Application Support/Day Trader/`):

```text
gates.json                                    # global GateSet library (all broker modes)
```

**Per broker scope** (migrate **all three** below; **skip `replay/`**):

| Broker mode | `BrokerKind` | Subdirectory |
|-------------|--------------|--------------|
| Interactive Brokers | `INTERACTIVE_BROKERS` | `interactive-brokers/` |
| Broker Emulator | `EMULATOR` | `emulator/` |
| Paper + live IB (hybrid) | `EMULATOR_LIVE_IB_MARKET_DATA` | `paper-live-ib/` |
| Session Replay | `REPLAY` | `replay/` — **no migration** |

```text
{broker-scope}/
  deployments.json                            # each Touch Turn deployment: touchTurnDeploymentConfigId
  deployments.json.bak                        # existing backup pattern — must be written before migrate
  touch-turn/
    placement-strategies.json                 # TouchTurnPlacementStrategy library (this scope)
    placement-strategies.json.bak
    deployment-configs.json                   # TouchTurnDeploymentConfig library (this scope)
    deployment-configs.json.bak
```

- **Gates** are global (`gates.json` at app root) so the same GateSet can be reused across IB, emulator, and hybrid.
- **Placement strategies** and **deployment configs** are **broker-scoped** because `deployments.json` is already per mode and rules often differ (e.g. hybrid enables liquidity gates by default).
- **`replay/`** is excluded: replay loads captured sessions; it does not need library migration or new config files on disk. Tests may use in-memory fixtures only.

Use the same atomic-write + `.bak` patterns as `deployments.json` / `watchlists.json` (see `JsonFileStore.backupIfPresent`).

### 8. Session / replay audit trail

On session start and in `touchTurnRunRecord`, snapshot:

- `gateSetId`, `placementStrategyId`, `deploymentConfigId`
- Resolved gate pass/fail per gate (Phase 1: one ATR gate)
- Resolved `invertTradeSide` and key thresholds

So editing a shared library later does not rewrite history. Follow patterns in `SessionTrace`, `TouchTurnRunRecord`, `session_started` payload.

### 9. UI (Phase 1 — pragmatic)

Minimum viable:

- Deployment editor: **pick** a Deployment Config by friendly name (dropdown/list).
- Ability to **create/edit** GateSets, Placement Strategies, and Deployment Configs (dialog or simple screen — mirror `TouchTurnRulesConfigDialog.kt` patterns).

Full polish can follow; do not block engine work on perfect UI. **Do** remove or redirect the old inline rules dialog so deployments are not configured two ways.

### 10. Explicitly out of scope

- Bar-shape gate types and runtime mode routing (see research doc).
- Multiple gate-set → strategy rules in one Deployment Config (Phase 2).
- Quick Flip placement libraries (only define extension point: gates are global).
- Changing trading logic, liquidity math, or invert-stop behaviour.
- Git commits unless the user asks.

---

## Data backup and migration (mandatory)

### Before the operator upgrades (manual backup — document in README)

Instruct the operator to **copy the entire app data folder** before first launch on the new build:

| OS | Path |
|----|------|
| macOS | `~/Library/Application Support/Day Trader/` |
| Windows | `%APPDATA%\Day Trader\` |
| Linux | `~/.local/share/day-trader/` |

Example (macOS):

```bash
cp -a ~/Library/Application\ Support/Day\ Trader \
      ~/Library/Application\ Support/Day\ Trader.backup-$(date +%Y%m%d)
```

Minimum files to preserve per **non-replay** broker scope if doing a selective backup:

- `{broker-scope}/deployments.json`
- `{broker-scope}/deployments.json.bak` (if present)
- `{broker-scope}/watchlists.json` (optional but recommended)

After upgrade, new files appear at:

- `gates.json` (app root)
- `{broker-scope}/touch-turn/placement-strategies.json`
- `{broker-scope}/touch-turn/deployment-configs.json`

**Do not back up or migrate `replay/`** for this refactor.

### Automatic backup on migration (implement in code)

Before writing migrated data for a broker scope, the migrator **must**:

1. Call `backupIfPresent` (or equivalent) for:
   - `deployments.json` → `deployments.json.bak` (already standard)
   - `touch-turn/placement-strategies.json` → `.bak` (on first create or overwrite)
   - `touch-turn/deployment-configs.json` → `.bak`
2. For **global** `gates.json` at app root: backup to `gates.json.bak` before first write.
3. If migration fails mid-flight, **do not** leave a partial `deployments.json` without a valid `.bak` — follow the same fail-safe approach as `JsonFileStore`.

Add constants to `AppDataFiles` for new paths and backup filenames.

### When migration runs

- **On first load** of deployments for each broker scope (`INTERACTIVE_BROKERS`, `EMULATOR`, `EMULATOR_LIVE_IB_MARKET_DATA`) after upgrade.
- **Not** on `REPLAY` — `ReplaySessionController` / replay startup must not invoke the deployment migrator for disk under `replay/`.
- Migration is **idempotent**: if `touchTurnDeploymentConfigId` is already set and library refs resolve, skip that deployment.
- **One-time flag** (optional): persist `touchTurnConfigMigrationVersion: 1` in a small marker file per scope (e.g. `{broker-scope}/touch-turn/.migration-version`) to avoid re-running dedupe logic every launch — only if needed for performance.

### Migration algorithm (per broker scope, except replay)

For each deployment in `deployments.json` where `strategy == TOUCH_AND_TURN_SCALPER`:

1. If `touchTurnDeploymentConfigId` is non-null and resolves → **skip**.
2. Read legacy `configuration.touchTurnRules` (and legacy top-level `invertTradeSide` if present — see `DeploymentPersistence` / `TouchTurnRuleConfigPersistence`).
3. **GateSet:** Map liquidity fields to an `ATR_LIQUIDITY` gate. **Dedupe** against existing entries in global `gates.json` (same type + same params → reuse id). Otherwise create named entity, e.g. `"Migrated ATR gate ({broker-scope})"` or include ratio in name: `"ATR 25% liquidity"`.
4. **PlacementStrategy:** Map remaining `TouchTurnRuleConfig` fields to `TouchTurnPlacementStrategy`. **Dedupe** within `{broker-scope}/touch-turn/placement-strategies.json` by content equality. Name e.g. `"Migrated placement — {symbol}"` or `"Migrated placement (shared)"` when deduped.
5. **DeploymentConfig:** Create `{ gateSetId, placementStrategyId, name }`. **Dedupe** within scope: deployments with **identical** resolved legacy rules should share **one** DeploymentConfig id (important for many HK hybrid symbols on the same rules).
6. Set `touchTurnDeploymentConfigId` on the deployment record.
7. **Phase 1 retention:** Keep inline `touchTurnRules` on disk **until migration is verified** (read fallback), or clear them only after successful save of all library files — document choice in PR. Prefer: keep inline copy read-only for one release, resolve via config id when present.

### Global gates dedupe across broker modes

When migrating `interactive-brokers/`, then `emulator/`, then `paper-live-ib/`:

- Identical ATR gate params should produce **one** GateSet in root `gates.json`, not three duplicates.
- Placement and deployment configs remain **per scope** (hybrid 40% ATR vs 25% may differ).

### Migration tests (required)

- [ ] Legacy `deployments.json` fixture per broker kind → expected library files + config ids.
- [ ] **Dedupe:** three deployments with identical `touchTurnRules` → one shared `TouchTurnDeploymentConfig`.
- [ ] **Cross-scope gates:** same ATR params in emulator + hybrid → one GateSet in root `gates.json`.
- [ ] **Replay skipped:** loading replay scope does not create `replay/touch-turn/` or mutate `replay/deployments.json` for migration.
- [ ] **Backup:** after migrate, `.bak` files exist and primary files parse.
- [ ] **Parity:** `TouchTurnConfigResolver.resolve(...)` equals legacy `touchTurnRules` for every migrated fixture.
- [ ] **Rollback story:** document that operator can restore `deployments.json.bak` and remove new library files to revert (manual).

### Operator-facing note (short README subsection)

After implementation, add a brief README note:

1. Back up `Day Trader` app data folder before upgrading.
2. On first launch, IB / Emulator / Hybrid modes auto-migrate Touch Turn rules into named libraries.
3. Replay mode is unchanged.
4. To roll back, restore the backup folder.

---

## Codebase map (start here)

| Area | Path |
|------|------|
| Rules blob today | `day-trader/.../domain/TouchTurnRuleConfig.kt` |
| Deployment field | `day-trader/.../domain/StrategyDeployment.kt` (`touchTurnRules`) |
| Persistence records | `day-trader/.../data/persistence/DeploymentRecords.kt` (`TouchTurnRuleConfigRecord`) |
| Load/save | `day-trader/.../data/persistence/DeploymentPersistence.kt` |
| Liquidity eval | `day-trader/.../domain/TouchTurnDeploymentExtensions.kt` (`withLiquidityEvaluatedIfClosed`) |
| Engine | `day-trader/.../engine/touchturn/TouchTurnEngine.kt` |
| Bracket setup | `day-trader/.../domain/TouchTurnLogic.kt` (`computeBracketSetup`, `evaluateEntryGate`) |
| Rules UI | `day-trader/.../ui/TouchTurnRulesConfigDialog.kt` |
| Strategies UI/VM | `day-trader/.../ui/StrategiesScreen.kt`, `.../StrategiesViewModel.kt` |
| E2E helpers | `day-trader/src/commonTest/kotlin/daytrader/e2e/support/` |
| Key E2E tests | `E2EEngineLiquidityEvaluationTest`, `E2EFullTradeLifecycleTest`, `E2EDomainSmokeTest`, `E2ENoTradeEdgeCasesTest`, `DeploymentPersistenceTest` |

~78 Kotlin files reference `TouchTurnRuleConfig` / `touchTurnRules` — many changes are mechanical once resolver exists.

---

## Step-by-step implementation plan

Complete **one step per PR-sized chunk**. After **each** step: run relevant unit tests + Touch Turn E2E subset; update fixtures; keep CI green.

### Step 1 — Domain models + repositories (no behaviour change)

- [ ] Add domain types: `Gate`, `GateSet`, `GateType` (enum with `ATR_LIQUIDITY`), `TouchTurnPlacementStrategy`, `TouchTurnDeploymentConfig`.
- [ ] Add `GateEvaluationContext` (opening bar, dailyAtr14, marketZoneId, sessionDate, etc.).
- [ ] Add `GateEvaluator` interface + `AtrLiquidityGateEvaluator` (extract logic from `TouchTurnLogic` / existing liquidity checks — single source of truth).
- [ ] Add `TouchTurnConfigResolver`: `(GateSet, TouchTurnPlacementStrategy) → TouchTurnRuleConfig`.
- [ ] Add file repositories + persistence records for the three JSON files.
- [ ] **Tests:** unit tests for evaluator, resolver, repository round-trip (mirror `DeploymentPersistenceTest`).

### Step 2 — Backup, migration + deployment wiring

- [ ] Add `AppDataFiles` constants for `gates.json`, `gates.json.bak`, `{scope}/touch-turn/placement-strategies.json`, `deployment-configs.json`, and their `.bak` files.
- [ ] Implement `TouchTurnConfigMigrator` (or equivalent) per **§ Data backup and migration** — run for `INTERACTIVE_BROKERS`, `EMULATOR`, `EMULATOR_LIVE_IB_MARKET_DATA` only; **exclude `REPLAY`**.
- [ ] Wire migrator into deployment load path (`JsonFileStore` / `FileStrategyDeploymentRepository`) **after** backup, **before** normalizing deployments.
- [ ] Add `touchTurnDeploymentConfigId` to `StrategyDeployment` / `ConfigurationRecord`.
- [ ] `effectiveTouchTurnRules()` resolves via config id → libraries → `TouchTurnRuleConfig` (fallback to inline `touchTurnRules` if config id missing).
- [ ] **Tests:** all migration tests in § Data backup and migration; extend `DeploymentLoadNormalizerTest`, `DeploymentPersistenceTest` with per-`BrokerKind` fixtures.

### Step 3 — Engine integration + run record

- [ ] At liquidity evaluation (`withLiquidityEvaluatedIfClosed` or engine hook): evaluate GateSet explicitly; log per-gate results; then resolve placement.
- [ ] Extend `TouchTurnRunRecord` / `session_started` / `SessionTrace` with config ids + gate outcomes.
- [ ] **Tests:** `TouchTurnLogicTest` (liquidity paths), `TouchTurnRunRecordTest`, `E2EDomainSmokeTest`, `E2EEngineLiquidityEvaluationTest`.

### Step 4 — UI: libraries + deployment picker

- [ ] UI to list/create/edit GateSets, Placement Strategies, Deployment Configs (friendly names).
- [ ] Deployment create/edit: select Deployment Config instead of editing full rules inline.
- [ ] Deprecate or redirect `TouchTurnRulesConfigDialog` to edit libraries or selected config’s components.
- [ ] **Tests:** `TouchTurnPipelineDetailUiMapperTest`, `E2EStrategiesViewModelIntegrationTest`, `SimulatedBrokerTouchTurnRulesTest` — update to use config ids / resolver fixtures.

### Step 5 — E2E hardening + cleanup

- [ ] Update E2E helpers: `E2EEngineLiquidityHelper`, `E2EBracketHelper`, `E2EBracketExitHelper`, `ReplaySessionFixtures` to build gate/placement/config libraries instead of mutating `touchTurnRules` directly.
- [ ] Run full Touch Turn E2E suite: `E2EFullTradeLifecycleTest`, `E2ENoTradeEdgeCasesTest`, `E2EBrokerFaultInjectionTest`, `E2EHkMarketSessionTest`, replay tests touching rules.
- [ ] Remove dead inline-rules UI paths if migration complete; document JSON file locations in root `README.md` (short subsection only).
- [ ] Update this brief: check off steps, note any deviations.

---

## Testing instructions (mandatory)

1. **Per step:** run tests affected by that step before proceeding. Prefer:
   ```bash
   ./gradlew :day-trader:desktopTest --tests "daytrader.data.persistence.DeploymentPersistenceTest"
   ./gradlew :day-trader:desktopTest --tests "daytrader.e2e.E2EEngineLiquidityEvaluationTest"
   ```
   (Adjust module/task names to match project conventions if different.)

2. **Unit tests first** for new evaluators/resolver/persistence; **E2E second** once resolver is wired.

3. **Do not** duplicate liquidity math in tests — assert against gate evaluator outcomes.

4. **E2E pattern:** Create named GateSet + PlacementStrategy + DeploymentConfig in test setup; assign config id to deployment; avoid copying full `TouchTurnRuleConfig` onto deployment unless testing legacy migration.

5. **Migration test:** Load synthetic legacy `deployments.json` per broker scope (`interactive-brokers`, `emulator`, `paper-live-ib`); assert library files, `.bak` backups, deduped config ids, and identical resolved `TouchTurnRuleConfig`. **No migration tests for `replay/`.**

6. **Parity test:** Same deployment pre/post migration produces same `entryOrdersPermitted` / `invertTradeSide` / bracket geometry for a fixed OHLC fixture.

7. **Backup test:** Corrupt new `deployments.json` after migrate; verify `deployments.json.bak` restores prior state (mirror `JsonDocumentReaderTest` patterns).

---

## Phase 2 preview (do not implement now)

Document in code comments or a short “Future” section in PR description only:

- `TouchTurnDeploymentConfig.rules: List<{ gateSetId, placementStrategyId, priority }>` — first match wins.
- New gate types: `BAR_SHAPE_CAUTION_RED`, etc. (see research doc).
- Gate type registry with `requiredContext` flags (`OPENING_BAR_15M`, `DAILY_ATR`).
- Optional Quick Flip placement library under `quick-flip/`.

---

## Success criteria for Phase 1

- [ ] Operator can define named GateSet (ATR gate), Placement Strategy (Touch Turn vs Inverse + bracket params), and Deployment Config linking them.
- [ ] Multiple Touch Turn deployments can share the same Deployment Config id.
- [ ] Gates library is global (file path not under `touch-turn/` only — usable by future strategies).
- [ ] Legacy deployments auto-migrate for **IB, Emulator, and Hybrid**; **`replay/` excluded**; trading behaviour unchanged without operator edits.
- [ ] Automatic `.bak` written before migration; operator manual full-folder backup documented in README.
- [ ] Session logs record which config ids and gate results were used.
- [ ] All Touch Turn unit + E2E tests updated and passing.
- [ ] No bar-shape routing or multi-rule config in this phase.

---

## Agent execution notes

- Read `TouchTurnRuleConfig.kt` and `TouchTurnDeploymentExtensions.kt` before editing.
- Prefer **small, focused diffs** — match existing Kotlin style and persistence patterns (`JsonFileStore`, `DeploymentPersistence`).
- Do **not** commit unless the user explicitly asks.
- If a design ambiguity arises (e.g. whether `openDeadline` belongs on gate vs placement), choose the option that preserves today's behaviour, document in PR/commit message, and align with the adapter mapping in `TouchTurnConfigResolver`.
- When unsure, preserve behaviour parity over API elegance.
- Implement **§ Data backup and migration** in Step 2 before enabling config-id resolution in the engine.
- Never run disk migration against `BrokerKind.REPLAY` / `replay/` data directory.

---

*End of agent brief. Start with Step 1.*
