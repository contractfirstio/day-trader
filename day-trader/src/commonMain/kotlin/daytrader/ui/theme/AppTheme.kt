package daytrader.ui.theme

import androidx.compose.ui.graphics.Color

val DarkBackground = Color(0xFF121318)
val SurfaceDark = Color(0xFF1C1D24)
val TableHeaderBg = Color(0xFF252730)
val BrandRed = Color(0xFFD32F2F)
val GainGreen = Color(0xFF00C853)
/** P&L / loss text — orange-red for emphasis on dark backgrounds. */
val LossRed = Color(0xFFFF3D00)
/** Bearish candle body and wick (true red, not [LossRed]). */
val CandleRed = BrandRed
val CandleGreen = GainGreen
val TextSecondary = Color(0xFF9AA0A6)

/** Open market / running instance accent (matches market status bar). */
val MarketOpenBorder = Color(0xFF00E676)
val MarketOpenSurface = Color(0xFF0A1A12)
val MarketOpenGlow = Color(0xFF003D20)

/** In the money (pulse) and win (solid). */
val TradeBlueBorder = Color(0xFF42A5F5)
val TradeBlueSurface = Color(0xFF0A121F)
val TradeBlueGlow = Color(0xFF102440)

/** Out of the money (pulse) and loss (solid). */
val TradeRedBorder = Color(0xFFEF5350)
val TradeRedSurface = Color(0xFF1A0A0A)
val TradeRedGlow = Color(0xFF3D1010)

/** Breakeven session (pulse). */
val TradeNeutralBorder = Color(0xFFB0BEC5)
val TradeNeutralSurface = Color(0xFF181A1C)
val TradeNeutralGlow = Color(0xFF2A2E32)

/** Instance error state. */
val SessionErrorBorder = Color(0xFFB388FF)
val SessionErrorSurface = Color(0xFF151020)
val SessionErrorGlow = Color(0xFF2A1840)
