package daytrader.data

import daytrader.domain.Position

fun mockPositions(): List<Position> = listOf(
    Position("AAPL", "Apple Inc.", 150, 175.20, 181.10, 0.85, 885.00, "USD"),
    Position("TSLA", "Tesla Inc.", 80, 210.50, 198.30, -2.40, -976.00, "USD"),
    Position("NVDA", "NVIDIA Corp.", 65, 450.00, 485.25, 3.12, 2291.25, "USD"),
    Position("MSFT", "Microsoft Corp.", 110, 380.10, 389.50, 0.15, 1034.00, "USD"),
    Position("AMD", "Advanced Micro Devices", 120, 112.00, 108.40, -1.10, -432.00, "USD"),
    Position("AMZN", "Amazon.com Inc.", 200, 145.00, 151.20, 1.05, 1240.00, "USD")
)
