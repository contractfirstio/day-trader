# Agent Brief: Local Reporting Analytics (SQLite + ETL + Saved Queries + In-App UI)

**Purpose:** Handoff prompt for a new coding session. Implement a **read-side reporting layer** for Day Trader that enables flexible adhoc SQL (e.g. “How did this deployment / algo config perform on Tuesdays in HK?”) without changing any existing JSON persistence.

**Out of scope for this brief:**

- Touch Turn config refactor (GateSet / DeploymentConfig library) — see [touch-turn-config-refactor-agent-brief.md](./touch-turn-config-refactor-agent-brief.md). When that lands, add nullable columns for library entity IDs; do not block this work.
- Liquidity bucket analytics tables — defer unless explicitly requested in a follow-up.
- Remote/cloud Postgres, multi-user dashboards, or hosted Metabase.
- Changing how `deployments.json` is written, read, or migrated by the trading path.

---

## Mission

Add a **derived, disposable SQLite database** (`reporting.db`) that projects session performance data from existing JSON sources into relational tables optimized for adhoc queries. Wire **async sync** on app launch and after each session closes. Surface **sync errors as in-app notifications**. Provide an in-app **Reporting** screen for saved queries plus support external tools (Metabase, DBeaver) against the same DB file.

**Behaviour constraints (non-negotiable):**

1. **Existing JSON persistence is unchanged** — `deployments.json` remains the source of truth for trading.
2. **Reporting sync must never block trading** — no synchronous SQLite writes on the session-stop critical path; failures must not propagate to session lifecycle or JSON flush.
3. **Reporting DB is rebuildable** — a full ETL pass from JSON must be able to recreate the entire database from scratch.
4. **Default reporting filters production-like broker scopes** — `interactive-brokers` and `paper-live-ib` only; emulator and replay are ingested but excluded from default views/queries.

---

## Locked design decisions (do not relitigate)

These were confirmed with the product owner. Implement exactly as stated.

| # | Topic | Decision |
|---|--------|----------|
| 1 | **Hosting** | **Local only.** SQLite file on the same machine as Day Trader. Query via in-app UI, CLI, DBeaver, or Metabase on `localhost`. No remote server. |
| 2 | **Production broker scopes** | **`interactive-brokers` + `paper-live-ib`** are production-like (`is_production_like = 1`). **`emulator` + `replay`** are ingested but filtered out of default saved queries and the in-app Reporting tab default filter. |
| 3 | **Calendar / day-of-week** | Use **RTH session date** — `StrategySession.date` / `SessionHistoryRecord.date` (ISO `YYYY-MM-DD` in the market’s session calendar). **Do not** derive day-of-week from `startedAt` or `stoppedAt`. Precompute `day_of_week_iso` (ISO 8601: Monday=1 … Sunday=7) at ingest. |
| 4 | **`fact_session` shape** | **Maximum flatten** — typed columns for essentially all of `TouchTurnRunRecord` and session-level fields. Minimize `json_extract()` in queries. Accept occasional schema migrations when new persisted Touch Turn fields are added. |
| 5 | **Sync cadence** | **Full reconcile on app launch** + **incremental upsert after each session closes**. All sync work is **async** (background coroutine/worker). **Sync errors appear as in-app notifications** (not silent log-only failures). |
| 6 | **Query UI** | **Both:** in-app Reporting tab (saved queries, parameters, results grid) **and** external SQL clients / Metabase pointed at the same `reporting.db`. |
| 7 | **Saved queries** | Store in **`saved_query` table** inside `reporting.db` **plus** version-controlled seed files under `docs/reporting/queries/*.sql` loaded on schema init/migrate. |

---

## Current system context (read before coding)

### Tech stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin 2.x, KMP `jvm("desktop")` only |
| UI | Compose Multiplatform, Material 3 |
| App persistence | kotlinx.serialization JSON via `JsonFileStore` (debounced ~400ms, atomic replace) |
| Concurrency | Kotlin Coroutines, `StateFlow` |
| Build | Gradle, single module `:day-trader` today |

### Architecture today

```text
UI (Compose) → ViewModel → Repository → Domain
                              ↓
                    JSON persistence (broker-scoped)
```

### Data root layout

Override: environment variable **`DAY_TRADER_DATA_DIR`**.

Default macOS: `~/Library/Application Support/Day Trader/`

```text
{applicationDataRoot}/                    ← AppFileSystem.applicationDataRoot()
  ib-gateway.json                         ← global, not broker-scoped
  reporting/
    reporting.db                          ← NEW — not broker-scoped (aggregates all scopes)
  interactive-brokers/
    deployments.json                        ← PRIMARY ETL SOURCE per scope
    strategies-screen.json
    watchlists.json
    liquidity-buckets.json
    sessions/{deploymentId}/{sessionId}/...
  paper-live-ib/
    deployments.json
    ...
  emulator/
    deployments.json
    ...
  replay/
    deployments.json
    replay-settings.json
    ...
```

**Important:** `deployments.json` is **broker-scoped**. The reporting ETL must iterate **all four** `BrokerKind.dataDirectorySegment` directories under the application data root, not only the currently active broker scope from `AppFileSystem.configureDataScope`.

Broker directory segments (`BrokerKind.kt`):

| `BrokerKind` | `dataDirectorySegment` | `is_production_like` |
|--------------|------------------------|----------------------|
| `INTERACTIVE_BROKERS` | `interactive-brokers` | 1 |
| `EMULATOR_LIVE_IB_MARKET_DATA` | `paper-live-ib` | 1 |
| `EMULATOR` | `emulator` | 0 |
| `REPLAY` | `replay` | 0 |

### Authoritative reporting source

**Primary:** `{applicationDataRoot}/{broker-scope}/deployments.json` → `DeploymentRecord.sessionHistory[]`

Each `SessionHistoryRecord` contains:

- Session identity: `id`, `date`, `startedAt`, `stoppedAt`, `status`
- Performance: `pnl`, `trades`, `maxAtRisk`
- Touch Turn funnel: `hadLiquidityCandle`, `ordersPlacedForCandle`, `positionOpened`
- Fills: `sessionTrades[]`
- Frozen algo outcome: `touchTurnRunRecord` (`TouchTurnRunRecordRecord`)
- Config version: `configurationFingerprint` (e.g. `cfg-v1:abc123def456`)

**Secondary (do not use for Phase 1 ETL):** `sessions/.../application.jsonl` `session_closed` events — richer forensics but duplicates persisted session history. Only use if a gap is discovered in JSON session rows.

**Not persisted for reporting:** live IB positions, in-flight broker state (except what is captured at session stop into session history).

### Key domain types and files

| Concern | Path |
|---------|------|
| Serialized JSON records | `day-trader/src/commonMain/kotlin/daytrader/data/persistence/DeploymentRecords.kt` |
| Domain session model | `day-trader/src/commonMain/kotlin/daytrader/domain/StrategySession.kt` |
| Session stop + rollups | `day-trader/src/commonMain/kotlin/daytrader/domain/StrategySessionLogic.kt` |
| Touch Turn run record | `day-trader/src/commonMain/kotlin/daytrader/domain/TouchTurnRunRecord.kt` |
| Config fingerprint | `day-trader/src/commonMain/kotlin/daytrader/domain/StrategyConfigurationSnapshot.kt` |
| Session close trace hook | `day-trader/src/commonMain/kotlin/daytrader/diagnostics/SessionTrace.kt` |
| Deployment repository | `day-trader/src/commonMain/kotlin/daytrader/data/FileStrategyDeploymentRepository.kt` |
| App wiring | `day-trader/src/commonMain/kotlin/daytrader/ui/AppDependencies.kt` |
| File system / data root | `day-trader/src/desktopMain/kotlin/daytrader/platform/AppFileSystem.kt` |
| Navigation screens | `day-trader/src/commonMain/kotlin/daytrader/presentation/navigation/AppScreen.kt` |
| UI fault pattern (reference for notifications) | `day-trader/src/commonMain/kotlin/daytrader/presentation/ui/UiFault.kt` |

### Session close flow (integration point)

When a session stops, `StrategyDeployment.onSessionStopped()` in `StrategySessionLogic.kt`:

1. Builds `TouchTurnRunRecord` (Touch Turn only)
2. Closes the session with `configurationFingerprint`
3. Calls **`SessionTrace.sessionClosed(...)`**
4. Returns updated deployment; caller persists JSON via **`repository.flushPersistenceBlocking()`**

**Recommended hook order for reporting sync:**

```text
1. JSON flush completes (flushPersistenceBlocking)
2. Enqueue async reporting projection job with:
   - brokerScope (from BrokerKind.dataDirectorySegment — NOT only current AppFileSystem scope)
   - DeploymentRecord / domain StrategyDeployment snapshot
   - closed StrategySession
```

Also enqueue **full reconcile** once on app startup (after repositories load), on a background dispatcher.

For **batch replay** merges (`BatchReplayOutcomeApplier.applyAll`), enqueue a full reconcile or per-deployment upsert after flush — replay writes closed sessions into `deployments.json`.

---

## SQLite database location and access

| Property | Value |
|----------|-------|
| Path | `{applicationDataRoot}/reporting/reporting.db` |
| Created by | ETL / schema migrator on first sync |
| WAL mode | Enable `PRAGMA journal_mode=WAL` for concurrent read (Metabase) while app writes |
| Foreign keys | `PRAGMA foreign_keys=ON` |

External tools: document in `docs/reporting/README.md` that Metabase/DBeaver should open this path read-only when possible.

---

## Schema (implement as `docs/reporting/schema.sql` and apply programmatically)

Apply schema via versioned migrations (`reporting_schema_version` table). Initial version = 1.

### ETL bookkeeping

```sql
CREATE TABLE reporting_schema_version (
    version     INTEGER PRIMARY KEY,
    applied_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE etl_run (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at          TEXT NOT NULL DEFAULT (datetime('now')),
    finished_at         TEXT,
    trigger             TEXT NOT NULL
        CHECK (trigger IN ('app_launch', 'session_closed', 'manual', 'reconcile')),
    broker_scope        TEXT,              -- NULL = all scopes
    source_path         TEXT,
    deployments_seen    INTEGER NOT NULL DEFAULT 0,
    sessions_upserted   INTEGER NOT NULL DEFAULT 0,
    fills_upserted      INTEGER NOT NULL DEFAULT 0,
    configs_upserted    INTEGER NOT NULL DEFAULT 0,
    status              TEXT NOT NULL DEFAULT 'running'
        CHECK (status IN ('running', 'success', 'failed')),
    error_message       TEXT
);

CREATE TABLE etl_source_watermark (
    broker_scope        TEXT PRIMARY KEY,
    source_path         TEXT NOT NULL,
    source_mtime_epoch  INTEGER NOT NULL,
    source_size_bytes   INTEGER NOT NULL,
    last_etl_run_id     INTEGER REFERENCES etl_run(id),
    last_success_at     TEXT
);

-- Sync errors surfaced as in-app notifications (also queryable for Reporting tab)
CREATE TABLE etl_sync_error (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at         TEXT NOT NULL DEFAULT (datetime('now')),
    broker_scope        TEXT NOT NULL,
    deployment_id       TEXT,
    session_id          TEXT,
    trigger             TEXT NOT NULL,
    message             TEXT NOT NULL,
    detail              TEXT,              -- stack trace or parse context
    acknowledged_at     TEXT,              -- set when user dismisses in UI
    retry_count         INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_etl_sync_error_unacked
    ON etl_sync_error (acknowledged_at, occurred_at DESC);
```

### Reference: broker scope

```sql
CREATE TABLE broker_scope (
    code                TEXT PRIMARY KEY,
    display_name        TEXT NOT NULL,
    is_production_like  INTEGER NOT NULL DEFAULT 0
);

INSERT INTO broker_scope (code, display_name, is_production_like) VALUES
    ('interactive-brokers', 'Interactive Brokers', 1),
    ('paper-live-ib',       'Paper Live IB',       1),
    ('emulator',            'Emulator',            0),
    ('replay',              'Replay',              0);
```

### Dimension: deployment (current config snapshot)

Updated on every ETL pass when a deployment is seen. Historical session rows retain their own denormalized columns + `config_fingerprint`; changing deployment config today does not rewrite old session rows in JSON — same rule applies in SQLite.

```sql
CREATE TABLE dim_deployment (
    broker_scope                    TEXT NOT NULL,
    deployment_id                   TEXT NOT NULL,

    strategy_type                   TEXT NOT NULL,
    status                          TEXT NOT NULL,

    symbol                          TEXT NOT NULL,
    company_name                    TEXT,
    market_zone_id                  TEXT,
    currency_code                   TEXT NOT NULL DEFAULT 'USD',
    market_source                   TEXT,
    max_at_risk                     INTEGER NOT NULL,

    auto_start_on_market_open       INTEGER NOT NULL DEFAULT 0,
    last_auto_start_session_date    TEXT,

    current_config_fingerprint      TEXT,

    -- Current Touch Turn rules (from ConfigurationRecord.touchTurnRules)
    rule_atr_liquidity_ratio        REAL,
    rule_daily_atr_lookback         INTEGER,
    rule_entry_inward_offset        REAL,
    rule_tp_fib_green               REAL,
    rule_tp_fib_red                 REAL,
    rule_tp_to_sl_ratio             REAL,
    rule_closed_bar_settle_ms       INTEGER,
    rule_stop_after_open_minutes    INTEGER,
    rule_trailing_trigger_fraction  REAL,
    rule_trailing_arm_fraction      REAL,
    rule_enable_liquidity_daily_atr INTEGER,
    rule_enable_open_deadline       INTEGER,
    rule_enable_adjustable_trailing INTEGER,
    rule_invert_trade_side          INTEGER,

    first_seen_at                   TEXT NOT NULL DEFAULT (datetime('now')),
    last_seen_at                    TEXT NOT NULL DEFAULT (datetime('now')),

    PRIMARY KEY (broker_scope, deployment_id)
);

CREATE INDEX idx_dim_deployment_market ON dim_deployment (market_zone_id, symbol);
```

### Dimension: config fingerprint (“this algo”)

One row per distinct `configurationFingerprint` / resolved fingerprint. Populate from:

1. `SessionHistoryRecord.configurationFingerprint` when present
2. Else compute using `StrategySession.resolvedConfigurationFingerprint(deployment)` logic
3. Rule params from **`touchTurnRunRecord.rules`** when present (frozen at session close — preferred for historical accuracy)

Also store `StrategyConfigurationSnapshot.canonicalString()` in `canonical_string` when available.

```sql
CREATE TABLE dim_config (
    config_fingerprint              TEXT PRIMARY KEY,

    strategy_type                   TEXT NOT NULL,
    max_dollars                     INTEGER,

    rule_atr_liquidity_ratio        REAL,
    rule_daily_atr_lookback         INTEGER,
    rule_entry_inward_offset        REAL,
    rule_tp_fib_green               REAL,
    rule_tp_fib_red                 REAL,
    rule_tp_to_sl_ratio             REAL,
    rule_closed_bar_settle_ms       INTEGER,
    rule_stop_after_open_minutes    INTEGER,
    rule_trailing_trigger_fraction  REAL,
    rule_trailing_arm_fraction      REAL,
    rule_enable_liquidity_daily_atr INTEGER,
    rule_enable_open_deadline       INTEGER,
    rule_enable_adjustable_trailing INTEGER,
    rule_invert_trade_side          INTEGER,

    canonical_string              TEXT,
    first_seen_at                   TEXT NOT NULL DEFAULT (datetime('now'))
);
```

### Fact: session (primary analytics table — maximum flatten)

**Grain:** one row per `(broker_scope, session_id)`.

Include **Quick Flip** sessions with Touch Turn columns NULL / default where `touchTurnRunRecord` is absent.

```sql
CREATE TABLE fact_session (
    broker_scope                    TEXT NOT NULL,
    session_id                      TEXT NOT NULL,
    deployment_id                   TEXT NOT NULL,

    -- Session identity & calendar (from SessionHistoryRecord)
    session_date                    TEXT NOT NULL,       -- YYYY-MM-DD RTH session date
    started_at                      TEXT,
    stopped_at                      TEXT,
    day_of_week_iso                 INTEGER NOT NULL     -- ISO 8601 Mon=1..Sun=7
        CHECK (day_of_week_iso BETWEEN 1 AND 7),
    week_iso                        TEXT,                -- strftime('%Y-W%W', session_date)
    month                           TEXT,                -- YYYY-MM

    -- Denormalized deployment dimensions
    strategy_type                   TEXT NOT NULL,
    symbol                          TEXT NOT NULL,
    company_name                    TEXT,
    market_zone_id                  TEXT NOT NULL,
    currency_code                   TEXT NOT NULL DEFAULT 'USD',

    -- Core performance
    status                          TEXT NOT NULL,
    pnl                             REAL NOT NULL,
    trades                          INTEGER NOT NULL,
    max_at_risk                     INTEGER NOT NULL,

    -- Touch Turn funnel flags (session-level)
    had_liquidity_candle            INTEGER,
    orders_placed_for_candle        INTEGER,
    position_opened                 INTEGER,

    -- Config frozen at session close
    config_fingerprint              TEXT REFERENCES dim_config(config_fingerprint),

    -- runContext (TouchTurnRunContext)
    ctx_max_dollars                 INTEGER,
    ctx_started_by                  TEXT,                -- MANUAL, AUTO_MARKET_OPEN
    ctx_broker_id                   TEXT,
    ctx_broker_kind                 TEXT,
    ctx_invert_trade_side           INTEGER,

    -- prepareSnapshot (nullable; flatten scalar fields, JSON for checks list)
    prepare_prepared_at_epoch_ms    INTEGER,
    prepare_overall_status          TEXT,
    prepare_bootstrap_reused        INTEGER,
    prepare_atr14                   REAL,
    prepare_volume_sma20             REAL,
    prepare_opening_bar_pending     INTEGER,
    prepare_checks_json             TEXT,

    -- marketInputs (TouchTurnRunMarketInputs)
    mi_opening_bar_open             REAL,
    mi_opening_bar_high             REAL,
    mi_opening_bar_low              REAL,
    mi_opening_bar_close            REAL,
    mi_opening_bar_volume           REAL,
    mi_opening_bar_time             TEXT,
    mi_adr14                        REAL,
    mi_atr14                        REAL,
    mi_daily_atr14                  REAL,
    mi_volume_sma20                 REAL,
    mi_currency_code                TEXT,
    mi_market_zone_id               TEXT,
    mi_data_error_message           TEXT,
    mi_opening_bar_range            REAL,                -- high - low when both set
    mi_range_to_daily_atr_ratio     REAL,                -- range / daily_atr14 when both set

    -- decision (TouchTurnSessionDecision)
    decision_outcome                TEXT,
    decision_planned_quantity       INTEGER,
    decision_planned_side           TEXT,
    decision_planned_entry          REAL,
    decision_planned_stop_loss      REAL,
    decision_planned_take_profit    REAL,
    decision_planned_trail_trigger  REAL,
    decision_executed_legs          TEXT,                -- comma-separated TouchTurnOrderRole names

    -- stopEvent (TouchTurnStopEvent)
    stop_trigger                    TEXT,
    stop_error_message              TEXT,
    stop_broker_unrealized_pnl      REAL,

    -- milestones (TouchTurnMilestoneTimestamps) — all nine
    ms_starting_session_at          TEXT,
    ms_data_ready_at                TEXT,
    ms_data_failed_at               TEXT,
    ms_bar_closed_at                TEXT,
    ms_liquidity_evaluated_at       TEXT,
    ms_close_confirmed_at           TEXT,
    ms_orders_placed_at             TEXT,
    ms_position_opened_at           TEXT,
    ms_closing_session_at           TEXT,

    -- Derived reporting flags (match StrategySessionLogic rollups / hadPosition)
    had_position                    INTEGER NOT NULL DEFAULT 0,
    is_win                          INTEGER NOT NULL DEFAULT 0,
    is_loss                         INTEGER NOT NULL DEFAULT 0,
    is_no_trade_day                 INTEGER NOT NULL DEFAULT 0,
    is_outcome_no_trade             INTEGER NOT NULL DEFAULT 0,

    source_record_hash              TEXT,
    ingested_at                     TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at                      TEXT NOT NULL DEFAULT (datetime('now')),

    PRIMARY KEY (broker_scope, session_id),
    FOREIGN KEY (broker_scope, deployment_id)
        REFERENCES dim_deployment (broker_scope, deployment_id)
);

CREATE INDEX idx_fact_session_market_dow
    ON fact_session (market_zone_id, day_of_week_iso, session_date);

CREATE INDEX idx_fact_session_deployment_date
    ON fact_session (broker_scope, deployment_id, session_date);

CREATE INDEX idx_fact_session_config_date
    ON fact_session (config_fingerprint, session_date);

CREATE INDEX idx_fact_session_outcome
    ON fact_session (decision_outcome, market_zone_id);

CREATE INDEX idx_fact_session_symbol
    ON fact_session (symbol, session_date);

CREATE INDEX idx_fact_session_production_date
    ON fact_session (session_date, broker_scope);
```

### Fact: session fills

```sql
CREATE TABLE fact_session_fill (
    broker_scope        TEXT NOT NULL,
    exec_id             TEXT NOT NULL,
    session_id          TEXT NOT NULL,

    order_id            INTEGER NOT NULL,
    perm_id             INTEGER NOT NULL,
    parent_order_id     INTEGER NOT NULL DEFAULT 0,
    side                TEXT NOT NULL,
    quantity            INTEGER NOT NULL,
    price               REAL NOT NULL,
    fill_time           TEXT NOT NULL,
    currency            TEXT NOT NULL DEFAULT 'USD',
    commission          REAL,
    realized_pnl        REAL,

    ingested_at         TEXT NOT NULL DEFAULT (datetime('now')),

    PRIMARY KEY (broker_scope, exec_id),
    FOREIGN KEY (broker_scope, session_id)
        REFERENCES fact_session (broker_scope, session_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_fact_session_fill_session
    ON fact_session_fill (broker_scope, session_id);
```

### Saved queries

```sql
CREATE TABLE saved_query (
    query_id            TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    description         TEXT,
    sql_text            TEXT NOT NULL,
    parameters_json     TEXT,
    category            TEXT,
    tags_json           TEXT,
    origin              TEXT NOT NULL DEFAULT 'user'
        CHECK (origin IN ('system', 'user')),
    is_favorite         INTEGER NOT NULL DEFAULT 0,
    created_at          TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at          TEXT NOT NULL DEFAULT (datetime('now')),
    last_run_at         TEXT,
    run_count           INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE saved_query_run (
    run_id              INTEGER PRIMARY KEY AUTOINCREMENT,
    query_id            TEXT NOT NULL REFERENCES saved_query(query_id),
    ran_at              TEXT NOT NULL DEFAULT (datetime('now')),
    parameters_json     TEXT,
    row_count           INTEGER,
    duration_ms         INTEGER,
    error_message       TEXT
);
```

### Views (ship in schema.sql)

```sql
CREATE VIEW v_session_reporting AS
SELECT
    s.*,
    d.auto_start_on_market_open,
    d.current_config_fingerprint AS deployment_current_config,
    c.rule_atr_liquidity_ratio,
    c.rule_invert_trade_side AS config_invert_trade_side,
    b.display_name AS broker_scope_name,
    b.is_production_like
FROM fact_session s
JOIN dim_deployment d
  ON d.broker_scope = s.broker_scope AND d.deployment_id = s.deployment_id
LEFT JOIN dim_config c ON c.config_fingerprint = s.config_fingerprint
JOIN broker_scope b ON b.code = s.broker_scope;

-- Default filter for production-like scopes
CREATE VIEW v_session_production AS
SELECT * FROM v_session_reporting WHERE is_production_like = 1;
```

---

## Derived field computation (must match app semantics)

Implement in the projector (`SessionReportingProjector` or similar) using the same rules as the app.

### `day_of_week_iso` from `session_date`

```kotlin
// session_date is ISO local date YYYY-MM-DD
// ISO weekday: Mon=1 .. Sun=7
fun dayOfWeekIso(sessionDate: String): Int {
    val dow = java.time.LocalDate.parse(sessionDate).dayOfWeek.value
    return dow // java.time DayOfWeek already ISO
}
```

### `had_position`

Port **`StrategySession.hadPosition()`** from `StrategySessionLogic.kt` exactly — do not invent simpler heuristics.

### Rollup-aligned flags (closed sessions only)

For `status == CLOSED`:

| Column | Rule |
|--------|------|
| `had_position` | `StrategySession.hadPosition()` |
| `is_win` | `had_position && pnl > 0` |
| `is_loss` | `had_position && pnl <= 0` |
| `is_no_trade_day` | `!had_position` |
| `is_outcome_no_trade` | `decision_outcome.startsWith("NO_TRADE")` when outcome present |

### `config_fingerprint`

Use `session.configurationFingerprint` when set; else compute via `StrategyConfigurationSnapshot` / `resolvedConfigurationFingerprint(deployment)` — reuse existing domain functions, do not reimplement hash logic.

### `market_zone_id` on fact row

Prefer, in order:

1. `touchTurnRunRecord.marketInputs.marketZoneId`
2. `DeploymentRecord.configuration.marketZoneId`
3. Fallback `"America/New_York"` only when truly absent (log warning in ETL)

### Opening bar derived metrics

```text
mi_opening_bar_range = high - low  (when both non-null)
mi_range_to_daily_atr_ratio = range / mi_daily_atr14  (when daily_atr14 > 0)
```

---

## ETL / projection specification

### Package layout (recommended)

```text
daytrader.reporting/
  ReportingDatabase.kt          -- open SQLite, migrate schema, WAL
  ReportingSchemaMigrator.kt
  SessionReportingProjector.kt  -- DeploymentRecord + SessionHistoryRecord → SQL upserts
  ReportingSyncCoordinator.kt   -- queue, launch + session-close triggers
  ReportingSyncErrorStore.kt    -- etl_sync_error CRUD + StateFlow for UI
  SavedQueryRepository.kt
  SavedQuerySeeder.kt           -- load docs/reporting/queries/*.sql
  ReportingPaths.kt             -- reporting.db path under applicationDataRoot
```

Place JDBC/SQLite code in **`desktopMain`** (`daytrader.reporting` desktop actual or `desktopMain`-only classes). Keep interfaces in `commonMain` where the app triggers sync without JDBC.

**Dependency:** add SQLite JDBC to `desktopMain` (e.g. `org.xerial:sqlite-jdbc` — use a current stable version).

### Triggers

| Trigger | Behaviour |
|---------|-----------|
| **`app_launch`** | After `AppDependencies` repositories load, enqueue full reconcile of all broker scopes (read each `deployments.json` under application data root). |
| **`session_closed`** | After successful `flushPersistenceBlocking()` for the deployment that closed a session, enqueue incremental upsert for that one session. Pass domain objects or re-read JSON — prefer domain objects already in memory to avoid race with file read. |
| **`manual`** | Gradle/run config CLI task or menu action for developer backfill (optional but useful). |
| **`reconcile`** | Same as app_launch; used after batch replay applier finishes. |

### Async execution requirements

```text
- Use a dedicated CoroutineScope(SupervisorJob() + Dispatchers.IO) owned by ReportingSyncCoordinator
- NEVER call SQLite from Dispatchers.Main
- NEVER block session stop waiting for sync
- Serialize writes with a Mutex or single-thread executor to avoid SQLITE_BUSY
- Wrap each job in try/catch; on failure insert etl_sync_error and emit notification
- Successful session sync should NOT clear unrelated errors (user dismisses individually)
```

### Upsert algorithm (incremental session)

```text
1. UPSERT dim_config from fingerprint + rules
2. UPSERT dim_deployment from parent DeploymentRecord
3. UPSERT fact_session (ON CONFLICT UPDATE all columns, refresh updated_at)
4. DELETE FROM fact_session_fill WHERE broker_scope=? AND session_id=?
5. INSERT fills from sessionTrades
6. UPDATE saved_query.run_count untouched
```

### Full reconcile algorithm

```text
For each broker_scope in [interactive-brokers, paper-live-ib, emulator, replay]:
  If deployments.json missing → skip
  Parse DeploymentsDocument
  For each deployment:
    UPSERT dim_deployment
    For each session in sessionHistory:
      UPSERT dim_config + fact_session + fills (same as incremental)
  UPDATE etl_source_watermark (mtime, size)
```

**Optional integrity pass:** session IDs present in DB for scope but absent from JSON → delete orphaned fact rows (only on full reconcile, not incremental).

### Idempotency

- Primary keys prevent duplicates.
- `source_record_hash` = stable hash of canonical JSON for the session record (or key fields) to skip no-op updates in incremental path.

---

## Saved query seed files

Create directory: `docs/reporting/queries/`

Each seed file starts with metadata comments parsed by `SavedQuerySeeder`:

```sql
-- @query_id: hk-tuesday-by-deployment
-- @name: HK Tuesdays by deployment
-- @description: Session performance for Hong Kong market on Tuesdays, production scopes only.
-- @category: performance
-- @tags: hk,tuesday,deployment
-- @parameters: [{"name":"day_of_week_iso","type":"integer","label":"Day (Mon=1)","default":2},{"name":"market_zone_id","type":"text","label":"Market zone","default":"Asia/Hong_Kong"},{"name":"deployment_id","type":"text","label":"Deployment ID","required":false}]

SELECT
    deployment_id,
    symbol,
    config_fingerprint,
    COUNT(*) AS sessions,
    SUM(pnl) AS total_pnl,
    ROUND(AVG(pnl), 2) AS avg_pnl,
    SUM(is_win) AS wins,
    SUM(is_loss) AS losses,
    SUM(is_no_trade_day) AS no_trade_days
FROM v_session_production
WHERE market_zone_id = :market_zone_id
  AND day_of_week_iso = :day_of_week_iso
  AND status = 'CLOSED'
  AND (:deployment_id IS NULL OR deployment_id = :deployment_id)
GROUP BY deployment_id, symbol, config_fingerprint
ORDER BY total_pnl DESC;
```

**Seed at least these system queries (`origin = 'system'`):**

1. `hk-tuesday-by-deployment` (above)
2. `win-rate-by-config-30d` — win rate grouped by `config_fingerprint`, last 30 session dates
3. `no-trade-outcome-breakdown` — counts by `decision_outcome` and `market_zone_id`
4. `pnl-by-symbol-and-dow` — heatmap-friendly grouping
5. `sessions-negative-pnl-with-position` — data quality / slippage review

Seeder rules:

- Upsert by `query_id`
- Do **not** overwrite `origin='user'` rows
- For `origin='system'`, update SQL when seed file content hash changes

### Parameter binding in app/CLI

Support `:param` placeholders. Bind types: `text`, `integer`, `real`, `boolean` (0/1). Optional params: allow `NULL` checks as in seed SQL.

---

## In-app UI specification

### Navigation

Add **`AppScreen.REPORTING`** with label **“Reporting”** in `AppScreen.kt` and the navigation rail (`App.kt`).

### Reporting screen (Phase 1 minimum)

| Section | Behaviour |
|---------|-----------|
| **Sync status** | Last successful reconcile time; rows in `fact_session`; unacknowledged error count |
| **Sync errors** | List from `etl_sync_error` where `acknowledged_at IS NULL` — show broker scope, deployment/session id, message, timestamp; **Dismiss** sets `acknowledged_at` |
| **Saved queries** | List from `saved_query` ordered by `is_favorite DESC, name`; filter to favorites toggle |
| **Query runner** | Select query → render parameter form from `parameters_json` → Run → display results in scrollable table |
| **Production filter toggle** | Default ON — queries run against `v_session_production` or auto-inject `is_production_like = 1` |

### Global notifications for sync errors

When a new row is inserted into `etl_sync_error`:

1. Persist to SQLite ( durable across restarts until dismissed )
2. Emit on **`ReportingSyncNotificationBus`** (`StateFlow<List<ReportingSyncNotification>>`)
3. Show a **non-blocking banner** or indicator in the app shell (top bar or nav rail badge) — follow patterns from `UiFaultBus` / `UiFaultIndicator.kt`
4. Clicking opens Reporting screen filtered to errors

**Do not** use modal dialogs for every sync failure (too intrusive during trading). Use dismissible banner + Reporting tab detail.

### ViewModel

`ReportingViewModel` in `daytrader.presentation.reporting`:

- Loads saved queries
- Executes SQL read-only (no user-supplied raw SQL in Phase 1 — only vetted saved queries)
- Tracks selected query + parameter state + result grid
- Acknowledges sync errors

---

## External tooling (document, minimal code)

Add `docs/reporting/README.md` with:

1. Path to `reporting.db`
2. How to install/run Metabase locally and add SQLite file data source
3. Note WAL mode + read-only connections
4. List of seed queries and example HK/Tuesday question

---

## Testing requirements

| Area | Tests |
|------|-------|
| **Projector unit tests** | Map fixture `SessionHistoryRecord` + `DeploymentRecord` → expected `fact_session` columns including `day_of_week_iso`, `had_position`, flags |
| **Day-of-week** | HK session date on known Tuesday → `day_of_week_iso = 2` |
| **had_position** | Port existing `StrategySession.hadPosition` test cases if any; add cases for `positionOpened`, milestones, trades |
| **ETL idempotency** | Run projector twice → same row count, updated `updated_at` |
| **Sync error** | Failing projector inserts `etl_sync_error` without throwing to caller |
| **Saved query seeder** | Parses header comments; upserts system queries |
| **Schema migration** | Fresh DB applies v1; second run no-op |

Use in-memory SQLite (`jdbc:sqlite::memory:`) in JVM tests.

**Do not** require Metabase in CI.

---

## Implementation phases (execute in order)

### Phase 1 — Schema + projector + manual ETL

- [ ] Add `docs/reporting/schema.sql` and `ReportingSchemaMigrator`
- [ ] Implement `SessionReportingProjector` with full flatten mapping
- [ ] Manual entry point: run full reconcile from `main` test harness or Gradle task
- [ ] Unit tests for projector + derived fields
- [ ] Validate row counts against UI session history for a sample `deployments.json`

**Acceptance:** Running manual ETL against real data produces sensible `fact_session` rows; HK Tuesday query returns expected sessions.

### Phase 2 — Async sync + notifications

- [ ] `ReportingSyncCoordinator` hooked after `flushPersistenceBlocking()` on session stop paths (`TouchTurnEngine`, `RunningSessionShutdown`, `BatchReplayOutcomeApplier`, etc.)
- [ ] App launch full reconcile
- [ ] `etl_sync_error` + `ReportingSyncNotificationBus` + banner indicator
- [ ] Tests: sync failure does not fail session stop coroutine

**Acceptance:** Close a session → within seconds fact row appears; induced parse error → notification visible and dismissible.

### Phase 3 — Saved queries + Reporting UI

- [ ] Seed files + `SavedQuerySeeder`
- [ ] `AppScreen.REPORTING` + `ReportingViewModel` + results table
- [ ] Run seed query “HK Tuesdays by deployment” from UI

**Acceptance:** User can run saved parameterized query from app; external Metabase can open same DB.

### Phase 4 — Polish (if time)

- [ ] Manual “Sync now” button on Reporting screen
- [ ] Favorite queries
- [ ] Export query results CSV
- [ ] Orphan cleanup on full reconcile

---

## Critical “do not” list

- **Do not** write to `deployments.json` from reporting code.
- **Do not** replace `FileStrategyDeploymentRepository` or add SQLite to the trading repository layer.
- **Do not** run SQLite writes on `Dispatchers.Main` or inside `flushPersistenceBlocking`.
- **Do not** block session stop on reporting success/failure.
- **Do not** default dashboards to emulator/replay data.
- **Do not** use `startedAt` for day-of-week reporting dimensions.
- **Do not** allow arbitrary user-typed SQL in Phase 1 UI (saved queries only — prevents accidental destructive statements).

---

## Example acceptance query (manual verification)

After ETL, this should answer the original product question:

```sql
SELECT
    deployment_id,
    symbol,
    config_fingerprint,
    COUNT(*) AS sessions,
    SUM(pnl) AS total_pnl,
    SUM(is_win) AS wins,
    SUM(is_no_trade_day) AS no_trade_days
FROM v_session_production
WHERE market_zone_id = 'Asia/Hong_Kong'
  AND day_of_week_iso = 2
  AND status = 'CLOSED'
GROUP BY deployment_id, symbol, config_fingerprint;
```

Compare totals to Strategies screen 30d rollups for a single deployment filtered to HK — they should be consistent for the same date range and config fingerprint filter.

---

## Reference: JSON → SQL column map (session row)

| SQLite | Source |
|--------|--------|
| `broker_scope` | Directory name under application data root |
| `session_id` | `SessionHistoryRecord.id` |
| `deployment_id` | Parent `DeploymentRecord.id` |
| `session_date` | `SessionHistoryRecord.date` |
| `started_at` / `stopped_at` | Same record |
| `pnl`, `trades`, `max_at_risk`, `status` | Same record |
| Funnel flags | `hadLiquidityCandle`, etc. |
| `config_fingerprint` | `configurationFingerprint` |
| `ctx_*` | `touchTurnRunRecord.runContext` |
| `prepare_*` | `touchTurnRunRecord.runContext.prepareSnapshot` |
| `mi_*` | `touchTurnRunRecord.marketInputs` + opening bar OHLC |
| `decision_*` | `touchTurnRunRecord.decision` |
| `stop_*` | `touchTurnRunRecord.stopEvent` |
| `ms_*` | `touchTurnRunRecord.milestones` (fallback to session `touchTurnMilestones` if run record null) |
| `strategy_type`, `symbol`, etc. | Parent deployment `configuration` + `strategy` |
| Fills | `sessionTrades[]` → `fact_session_fill` |

---

## Future extensions (do not implement now)

- Columns for Touch Turn library IDs (`gate_set_id`, `placement_strategy_id`, `deployment_config_id`) when config refactor ships
- `fact_liquidity_event` from `liquidity-buckets.json`
- Read-only replica or Postgres export
- Arbitrary SQL editor in app with statement whitelist

---

**End of brief.** Start with Phase 1 schema + projector tests before wiring UI or app lifecycle hooks.
