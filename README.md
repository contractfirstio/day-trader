# Day Trader

Day Trader is a desktop trading workstation for monitoring open positions and managing automated strategy deployments. It is built with **Kotlin Multiplatform** and **Compose Multiplatform** (desktop JVM), using a dark trading-terminal aesthetic and a layered architecture that separates domain logic, persistence, presentation, and UI.

> **Note:** The **positions blotter** loads live holdings from **IB Gateway** when connected. Strategy execution still uses **mock/demo data**.

## Features

### Positions

- Sortable **positions blotter** (company, symbol, unrealized PnL)
- Displays quantity, average price, market price, daily change, and PnL per line
- Data is loaded from IB Gateway when connected (`reqPositions` + live price ticks for PnL)

### Strategies

Manage one or more **deployments** per strategy template:

| Strategy                   | Description                                                           | Default max at risk |
|----------------------------|-----------------------------------------------------------------------|---------------------|
| **Touch and Turn Scalper** | Scalps reversals when price touches prior session high/low and turns  | $500                |
| **Quick Flip Scalper**     | Rapid in-and-out trades on short-term momentum flips with tight stops | $250                |

For each deployment you can:

- **Deploy** a strategy (symbol + max dollars at risk)
- **Start / end** sessions (tracks session history rows)
- **Filter** by active/stopped, strategy type, and search text
- **Duplicate** or **delete** the selected deployment
- View three detail tabs:
  - **Config** — symbol (fixed after create) and max at risk (editable when stopped)
  - **Trading** — demo fill with entry/stop/target, risk/upside/unrealized stats, stop adjustment, exit position
  - **Session history** — sortable closed sessions with 7d/30d PnL rollups and win rate

Deployment list cards show total PnL, 7d/30d rollups, win rate, trades today, and a live-trade summary when active.

## Architecture

The `:day-trader` module follows a simple unidirectional flow:

```
UI (Compose) → ViewModel → Repository → Domain
                              ↓
                         JSON persistence (desktop)
```

| Layer            | Package                      | Responsibility                                                                 |
|------------------|------------------------------|--------------------------------------------------------------------------------|
| **UI**           | `daytrader.ui`               | Compose screens, navigation rail, blotter components, theme                    |
| **Presentation** | `daytrader.presentation`     | `ViewModel`s, UI state, formatters, mappers (`*UiMapper`)                      |
| **Data**         | `daytrader.data`             | Repositories, `StrategyCatalog`, demo execution helpers                        |
| **Persistence**  | `daytrader.data.persistence` | JSON documents, debounced writes, legacy migration                             |
| **Domain**       | `daytrader.domain`           | `StrategyDeployment`, `StrategySession`, `ActiveExecution`, session lifecycle, risk math |
| **Platform**     | `daytrader.platform`         | `expect`/`actual` file I/O and session date                                    |

**Repositories wired at startup** (`AppDependencies`):

- `FileStrategyDeploymentRepository` — deployments, session history, and live execution state
- `FileStrategiesAppStateRepository` — strategies screen UI state (selection, detail tab, global auto-start)
- `GatewayPositionRepository` (desktop) — live positions from IB Gateway via `reqPositions` + market data ticks

**Domain highlights:**

- Session-scoped history keyed by ISO date (`currentSessionDateIso()`)
- `StrategySessionLogic` — start/end session, close position, update in-progress session stats
- `ExecutionRisk` — risk dollars, upside, unrealized PnL, % of max at risk from stop/target
- `StrategyCatalog` — display names, descriptions, default sizing, reward multiples for targets

Starting a session seeds a **demo filled position** (`DemoActiveExecution`) so the Trading tab is exercisable without a real execution backend.

## Persistence

On first launch, deployments are loaded from `deployments.json` when present; otherwise legacy files (`strategy-instances.json`, `instances.json`) are migrated once and written to the new format. A missing file yields an empty deployment list (add deployments from the Strategies screen). UI preferences persist separately.

| File                     | Contents                                                             |
|--------------------------|----------------------------------------------------------------------|
| `deployments.json`       | All deployments (config, live execution, session history)            |
| `strategies-screen.json` | Selected deployment, detail tab, global auto-start                   |

**Legacy migration:** Older files (`instances.json`, `strategy-instances.json`, `app-state.json`) are read once, migrated to the new format, and removed.

**Write behavior:**

- Debounced saves (~400 ms) to avoid excessive disk I/O during rapid edits
- Atomic writes via a temp file + replace

### Data directory

Persistence is scoped by broker so IB and emulator state never share the same JSON files. After you choose a broker on the startup screen, data is written under a broker-specific subdirectory.

Deployments and UI state stay in the broker-scoped directory so they persist across launches. Only raw IB tick disk logs (`ib-prices/`) are isolated per app launch under `runs/` (UTC timestamp + process id).

Default base locations (set `DAY_TRADER_DATA_DIR` to override with a fixed path):

| OS      | Base path                                   |
|---------|---------------------------------------------|
| macOS   | `~/Library/Application Support/Day Trader/` |
| Windows | `%APPDATA%\Day Trader\`                     |
| Linux   | `~/.local/share/day-trader/`                |

Effective default path pattern for session logs (under `{broker-scope}/`):

| OS      | Pattern |
|---------|---------|
| macOS   | `~/Library/Application Support/Day Trader/{broker}/sessions/{deploymentId}/{sessionId}/` |
| Windows | `%APPDATA%\Day Trader\{broker}\sessions\{deploymentId}\{sessionId}\` |
| Linux   | `~/.local/share/day-trader/{broker}/sessions/{deploymentId}/{sessionId}/` |

Raw IB tick capture (optional, for high-fidelity replay) lives under `runs/run-YYYYMMDD-HHMMSS-PID/{broker}/ib-prices/` when `DAY_TRADER_IB_PRICE_DISK_LOGS=true`.

**Touch Turn diagnosis** (correlate by `epochMs` + symbol):

| Log | Path (under `{broker-scope}/`) | Purpose |
|-----|--------------------------------|---------|
| Session application | `sessions/{deploymentId}/{sessionId}/application.jsonl` | Lifecycle, `bracket_submit_requested`, `bracket_acknowledged`, `broker_open_orders`, `touch_turn_state_sync` |
| Session prices | `sessions/.../prices.jsonl` | IB quotes (bid/ask/last, tick volume) |
| Session historical | `sessions/.../historical.jsonl` | Touch Turn bootstrap + closed-bar refetch payloads |
| Session manifest | `sessions/.../manifest.json` | Session metadata and milestone timeline for replay |
| Session emulator | `sessions/.../emulator-engine.jsonl` | Brackets and fills while a session is active |
| Emulator engine | `emulator/engine.jsonl` | Global fallback when no session is bound |
| Execution gateway | `execution/gateway.jsonl` | Global `open_orders_snapshot`, `touch_turn_bracket_placed` |
| IB raw ticks | `runs/.../ib-prices/{SYMBOL}.jsonl` | Per-field IB ticks when disk logging enabled |

Disable noisy logs: `DAY_TRADER_TOUCH_TURN_STATE_SYNC_LOG=false`, `DAY_TRADER_EMULATOR_LOGS=false`, `DAY_TRADER_EXECUTION_GATEWAY_LOG=false`, `DAY_TRADER_SESSION_HISTORICAL_LOGS=false`, `DAY_TRADER_SESSION_MANIFEST=false`.

**Session replay (dev):** load a captured session with `SessionBundleDirectoryReader.loadFromDirectory(...)`, or run Tier A regression with `ReplaySessionRunner(bundle, repository, scope).run()` against a parsed `SessionBundle`. Optional high-fidelity quotes: pass the path to `runs/.../ib-prices/{SYMBOL}.jsonl` when loading the bundle.

**Desktop session replay:** choose **Session Replay** on startup, pick a captured **hybrid (paper-live-ib)** session folder (or browse manually), then press **Run Replay** in the control bar. Offline emulator and replay-mode captures are excluded. Set `DAY_TRADER_BROKER=replay` to pre-select replay mode.

**Hybrid session capture for replay** (recommended when running paper-live):

```bash
export DAY_TRADER_BROKER=hybrid
export DAY_TRADER_IB_PRICE_DISK_LOGS=true
./gradlew :day-trader:run
```

| Broker               | Subdirectory            | Example (macOS)                                                                      |
|----------------------|-------------------------|--------------------------------------------------------------------------------------|
| Interactive Brokers  | `interactive-brokers/`  | `~/Library/Application Support/Day Trader/interactive-brokers/deployments.json`      |
| Broker Emulator      | `emulator/`             | `~/Library/Application Support/Day Trader/emulator/deployments.json`                   |

When using a fixed data directory (for example via `DAY_TRADER_DATA_DIR`), any legacy JSON files that still live at the base directory (from before the broker split) are moved into `interactive-brokers/` automatically on first IB launch.

Example:

```bash
export DAY_TRADER_DATA_DIR=/tmp/day-trader-dev
./gradlew :day-trader:run
```

## Requirements

- **JDK 17+** (recommended for Kotlin 2.0 and Compose Desktop)
- No Android SDK required — desktop JVM target only

## Broker selection

On launch, the app shows a **broker selection** screen: **Interactive Brokers** or **Broker Emulator**. Click **Continue** to connect with your choice.

Optionally pre-select a card with `DAY_TRADER_BROKER`:

| Value | Backend |
|-------|---------|
| `ib` *(default pre-selection)* | Interactive Brokers via IB Gateway / TWS |
| `emulator`, `sim`, or `mock` | In-memory broker emulator (no Gateway required) |
| `hybrid`, `paper-live`, `emulator-live-ib` | Paper execution (emulator) with **live IB** bid/ask/last for charts and fills |

Example:

```bash
export DAY_TRADER_BROKER=emulator
./gradlew :day-trader:run
```

The emulator starts with an empty positions blotter and seeded working orders (including SPY bracket legs). Order fills can open positions over time. It streams synthetic market ticks every ~2s and answers Touch Turn historical requests (first 15-minute candle and 14-day ADR).

For Touch Turn, the first 15-minute bar is **time-shifted** so it closes after a short wall-clock delay (default **10 seconds**), while the app still treats it as a normal 15-minute bar. That lets you see “candle forming” → “closed” → liquidity → entry window in under a minute. Override with:

| Variable | Default | Meaning |
|----------|---------|---------|
| `DAY_TRADER_EMULATOR_CANDLE_CLOSE_SEC` | `10` | Seconds until the synthetic first 15m bar closes |
| `off` or `0` | — | Use today’s 09:30 bar instead (often already closed) |
| `DAY_TRADER_EMULATOR_FIRST_CANDLE_COLOR` | `auto` | `red` / `long` = red bar (long entry); `green` / `short` = green bar (short); `auto` = hash by symbol/day |
| `DAY_TRADER_EMULATOR_FIRST_CANDLE_ALTERNATE` | `true` | When `auto`, flip green/red on each new session’s first-candle fetch (2nd session = long, 3rd = short, …) |
| `DAY_TRADER_EMULATOR_ENTRY_FILL_IMMEDIATELY` | `false` | `true` = entry limit fills as soon as bracket is placed (legacy) |
| `DAY_TRADER_EMULATOR_ENTRY_NEVER_FILL_PROB` | `0.25` | Chance price drifts away and the entry limit is never touched (otherwise price approaches over several ticks) |

When a Touch Turn liquidity bracket is logged inside the entry window, the emulator **places working orders** and sets the market to the entry price. Every ~2s price tick can fill the entry limit and **open a blotter position** (TP/STOP legs activate after entry fills). Once the entry is filled, the emulator **walks price randomly between stop and take-profit** until one leg fills (the other is cancelled). IB still logs only — no live `placeOrder`.

## IB Gateway connection

When `DAY_TRADER_BROKER` is `ib` (default), the desktop app connects to IB Gateway (or TWS) in the background. Status appears in the top bar:

- **Connecting…** — socket open, waiting for API handshake (`nextValidId`)
- **Connected (next order id N)** — positions requested automatically; blotter updates as ticks arrive
- **Error** — use **Reconnect**; check Gateway is running and API clients are enabled

Enrichment requests (`reqContractDetails`, streaming `reqMktData`) are **paced** (default **25 msg/sec**, **50 ms** min gap; override with `DAY_TRADER_IB_MAX_MSG_PER_SEC` / `DAY_TRADER_IB_MIN_INTERVAL_MS`) to stay under IB’s **50 messages/sec** limit. On error **100**, pacing backs off automatically for 20s. Shared `reqMktData` per contract avoids duplicate lines for positions + hybrid streaming. Positions publish as they arrive from IB. Reconnect waits 1.5s before resubscribing; position refresh only cancels market data for closed lines.

**Hybrid paper mode** (`DAY_TRADER_BROKER=hybrid`): Touch Turn **order matching** is emulated (positions, brackets, fills), but **pricing is decoupled**: fill triggers use live **bid**, **ask**, and **last** from IB (`EmulatorPricingSource.LIVE_EXCHANGE`), not synthetic walks. The chart uses the IB session gateway; the emulator only consumes the same ticks via `ingestExternalQuote`. Full emulator mode (`emulator`) uses `EmulatorPricingSource.SYNTHETIC` instead. Limit/stop rules are identical (buy at ask, sell at bid); live mode waits for both bid and ask before evaluating fills.

Market data uses **live** streaming by default (`reqMarketDataType` live). If you lack a subscription for a symbol, IB may fall back to delayed data (informational log **10167**). Use the subtle chart icon in the connection bar to test feeds or switch to delayed / delayed-frozen. **Market price for P&L** uses, in order: streaming ticks → bid/ask mid → **latest daily historical close** (`reqHistoricalData`, requested ~4s after load if no live price) → portfolio (only if distinct from avg cost) → avg cost fallback. Log codes **2119** (data farm connecting) and **10167** (no live subscription — delayed data) are informational.

Default endpoint: `127.0.0.1:4001` (Gateway paper). Override with environment variables:

| Variable | Default |
|----------|---------|
| `DAY_TRADER_IB_HOST` | `127.0.0.1` |
| `DAY_TRADER_IB_PORT` | `4001` |
| `DAY_TRADER_IB_CLIENT_ID` | `1` |
| `DAY_TRADER_IB_ACCOUNT` | *(empty = all accounts)* |
| `DAY_TRADER_IB_LOGS` | `false` — set to `true` for connection, positions, ticks, and `[IB]` diagnostics |
| `DAY_TRADER_IB_DEBUG` | `false` — extra stack traces on errors (requires `DAY_TRADER_IB_LOGS=true`) |
| `DAY_TRADER_IB_REDACT_LOGS` | `false` — when IB logs are on, set to `true` to hide prices/qty/symbols |

By default, console output is limited to **`[TouchTurn]`** planned bracket orders (log-only, not sent to IB). Set `DAY_TRADER_IB_LOGS=true` to restore `[IB]` connection and position logging.

`TwsApi.jar` lives in `day-trader/libs/` (copied from the IB API package).

## Getting started

Clone the repository, then from the project root:

```bash
./gradlew :day-trader:run
```

This opens the Day Trader window (navigation: **Positions** | **Strategies**). Start IB Gateway before or after launch; use **Reconnect** if needed.

### Other Gradle tasks

```bash
# Run all tests (unit + desktop + Cucumber E2E). Force execution if Gradle serves FROM-CACHE:
./gradlew :day-trader:desktopTest --rerun-tasks

# Shorthand alias (same as desktopTest):
./gradlew :day-trader:test

# Cucumber E2E only (emulator / hybrid / IB feature files):
./gradlew :day-trader:desktopTest --tests "daytrader.e2e.CucumberTestSuite" --rerun-tasks

# HTML report:
# day-trader/build/reports/tests/desktopTest/index.html

# Package a native installer for the current OS
./gradlew :day-trader:packageDistributionForCurrentOS
```

Note: `./gradlew test` at the repo root without `:day-trader:` fails — there is no root `test` task. Tests live in the `:day-trader` module as `desktopTest`.

Native distribution targets (see `day-trader/build.gradle.kts`): DMG (macOS), MSI (Windows), Deb (Linux). Package name: `DayTrader`, version `1.0.0`.

## Project layout

```
day-trader/                          # Gradle root (Kotlin Multiplatform)
├── settings.gradle.kts              # includes :day-trader
├── gradle/
│   └── libs.versions.toml           # Kotlin, Compose, coroutines, serialization versions
└── day-trader/                      # Application module
    ├── build.gradle.kts
    └── src/
        ├── commonMain/kotlin/
        │   ├── daytrader/
        │   │   ├── domain/          # Models + run/execution logic
        │   │   ├── data/            # Repositories, catalog
        │   │   ├── data/persistence/ # JSON store, records, migration
        │   │   ├── presentation/    # ViewModels, UI state, mappers
        │   │   ├── ui/              # App, screens, blotter, theme
        │   │   └── platform/        # expect APIs (AppFileSystem, SessionDate)
        └── desktopMain/kotlin/
            ├── main.kt              # Window entry (title: "Day Trader")
            └── daytrader/platform/  # actual file system + session date
```

## Tech stack

|               |                                                     |
|---------------|-----------------------------------------------------|
| Language      | Kotlin 2.0.21                                       |
| UI            | Compose Multiplatform 1.8.1, Material 3             |
| Concurrency   | Kotlin Coroutines (`StateFlow` in ViewModels)       |
| Serialization | kotlinx.serialization (JSON persistence)            |
| Build         | Gradle 9.5.1 (wrapper), configuration cache enabled |

## Roadmap / limitations

The codebase is structured for future integration (real positions feed, broker execution, market data), but today:

- Positions load from IB when connected; blotter is empty when Gateway is down
- Positions never persist to disk
- Strategy runs and fills are **demo/simulated** when you start an instance
- No authentication, order routing, or backtesting engine

Contributions that add real data sources should extend `PositionRepository` and replace demo execution hooks in the data layer while keeping domain rules in `daytrader.domain`.
