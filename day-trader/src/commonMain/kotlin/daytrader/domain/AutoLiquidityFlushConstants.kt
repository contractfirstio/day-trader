package daytrader.domain

/** Minutes after RTH open when auto liquidity flush may run (US/HK 09:46, LSE 08:16). */
const val AUTO_LIQUIDITY_FLUSH_MINUTES_AFTER_OPEN = 16

const val AUTO_LIQUIDITY_FLUSH_OFFSET_MS = AUTO_LIQUIDITY_FLUSH_MINUTES_AFTER_OPEN * 60_000L

/**
 * Safety bound for auto-flush drain loops. Each pass redistributes remaining pool liquidity
 * onto eligible working brackets (uncapped by per-deployment maxAtRisk). Stops early when the
 * pool no longer decreases (residual below one board lot, or no eligible rows).
 */
const val AUTO_LIQUIDITY_FLUSH_MAX_LOOPS = 50
