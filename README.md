# Day Trader

Day Trader is a desktop trading workstation for monitoring open positions and managing automated strategy instances. It is built with **Kotlin Multiplatform** and **Compose Multiplatform** (desktop JVM), using a dark trading-terminal aesthetic and a layered architecture that separates domain logic, persistence, presentation, and UI.

> **Note:** The app currently uses **mock position data** and **simulated strategy execution** for development and UI work. It does not connect to a live broker or market data feed.

## Features

### Positions

- Sortable **positions blotter** (company, symbol, unrealized PnL)
- Displays quantity, average price, market price, daily change, and PnL per line
- Data is loaded from an in-memory repository seeded with sample equities (AAPL, TSLA, NVDA, etc.)

### Strategies

Manage one or more **strategy instances** per template:

| Strategy                   | Description                                                           | Default max at risk |
|----------------------------|-----------------------------------------------------------------------|---------------------|
| **Touch and Turn Scalper** | Scalps reversals when price touches prior session high/low and turns  | $500                |
| **Quick Flip Scalper**     | Rapid in-and-out trades on short-term momentum flips with tight stops | $250                |

For each instance you can:

- **Create** instances (symbol + max dollars at risk)
- **Start / stop** runs (tracks session-day performance rows)
- **Filter** by running/stopped, strategy type, and search text
- **Duplicate** or **delete** the selected instance
- View three detail tabs:
  - **Configuration** — symbol (fixed after create) and max at risk (editable when stopped)
  - **Live** — demo fill with entry/stop/target, risk/upside/unrealized stats, stop adjustment, close position
  - **Performance** — sortable closed run history with 7d/30d PnL rollups and win rate

Instance list cards show total PnL, 7d/30d rollups, win rate, trades today, and a live-trade summary when running.

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
| **Data**         | `daytrader.data`             | Repositories, `StrategyCatalog`, mock seeds, demo execution helpers            |
| **Persistence**  | `daytrader.data.persistence` | JSON documents, debounced writes, legacy migration                             |
| **Domain**       | `daytrader.domain`           | `StrategyInstance`, `StrategyRun`, `ActiveExecution`, run lifecycle, risk math |
| **Platform**     | `daytrader.platform`         | `expect`/`actual` file I/O and session date                                    |

**Repositories wired at startup** (`AppDependencies`):

- `FileStrategyInstanceRepository` — instances + performance + live execution state
- `FileStrategiesAppStateRepository` — strategies screen UI state (selection, filters, active tab)
- `InMemoryPositionRepository` — mock positions only

**Domain highlights:**

- Session-scoped runs keyed by ISO date (`currentSessionDateIso()`)
- `StrategyRunLogic` — start/stop run, close position, update in-progress day stats
- `ExecutionRisk` — risk dollars, upside, unrealized PnL, % of max at risk from stop/target
- `StrategyCatalog` — display names, descriptions, default sizing, reward multiples for targets

Starting a run seeds a **demo filled position** (`DemoActiveExecution`) so the Live tab is exercisable without a real execution backend.

## Persistence

On first launch, strategy instances are loaded from disk or seeded from `mockStrategyInstances()` and written out. UI preferences persist separately.

| File                     | Contents                                                             |
|--------------------------|----------------------------------------------------------------------|
| `instances.json`         | All strategy instances (config, live execution, performance history) |
| `strategies-screen.json` | Selected instance, search, filters, detail tab                       |

**Legacy migration:** Older filenames (`strategy-instances.json`, `app-state.json`) are read once, migrated to the new format, and removed.

**Write behavior:**

- Debounced saves (~400 ms) to avoid excessive disk I/O during rapid edits
- Atomic writes via a temp file + replace

### Data directory

Default locations (override with `DAY_TRADER_DATA_DIR`):

| OS      | Path                                        |
|---------|---------------------------------------------|
| macOS   | `~/Library/Application Support/Day Trader/` |
| Windows | `%APPDATA%\Day Trader\`                     |
| Linux   | `~/.local/share/day-trader/`                |

Example:

```bash
export DAY_TRADER_DATA_DIR=/tmp/day-trader-dev
./gradlew :day-trader:run
```

## Requirements

- **JDK 17+** (recommended for Kotlin 2.0 and Compose Desktop)
- No Android SDK required — desktop JVM target only

## Getting started

Clone the repository, then from the project root:

```bash
./gradlew :day-trader:run
```

This opens the Day Trader window (navigation: **Positions** | **Strategies**).

### Other Gradle tasks

```bash
# Run unit/UI tests (when present under commonTest)
./gradlew :day-trader:desktopTest

# Package a native installer for the current OS
./gradlew :day-trader:packageDistributionForCurrentOS
```

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
        │   │   ├── data/            # Repositories, mocks, catalog
        │   │   ├── data/persistence/ # JSON store, records, migration
        │   │   ├── presentation/    # ViewModels, UI state, mappers
        │   │   ├── ui/              # App, screens, blotter, theme
        │   │   └── platform/        # expect APIs (AppFileSystem, SessionDate)
        │   └── Platform.kt          # expect Platform metadata
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

- Positions never persist and are not updated live
- Strategy runs and fills are **demo/simulated** when you start an instance
- No authentication, order routing, or backtesting engine

Contributions that add real data sources should extend `PositionRepository` and replace demo execution hooks in the data layer while keeping domain rules in `daytrader.domain`.
