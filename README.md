# day-trader

Compose Multiplatform desktop UI for viewing and sorting trading positions.

## Run the UI

```bash
./gradlew :day-trader:run
```

## Project layout

- `day-trader/` — Kotlin Multiplatform module (desktop JVM target)
- `day-trader/src/commonMain/` — shared UI (`App.kt`)
- `day-trader/src/desktopMain/` — desktop entry point (`main.kt`)
