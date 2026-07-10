package daytrader.domain

/** Minutes after RTH open when auto liquidity flush may run (US/HK 09:46, LSE 08:16). */
const val AUTO_LIQUIDITY_FLUSH_MINUTES_AFTER_OPEN = 16

const val AUTO_LIQUIDITY_FLUSH_OFFSET_MS = AUTO_LIQUIDITY_FLUSH_MINUTES_AFTER_OPEN * 60_000L

/** Maximum win-rate redistribution passes per flush event. */
const val AUTO_LIQUIDITY_FLUSH_MAX_LOOPS = 3
