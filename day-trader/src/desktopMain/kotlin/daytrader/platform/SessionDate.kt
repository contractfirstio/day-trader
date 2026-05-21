package daytrader.platform

import java.time.LocalDate

actual fun currentSessionDateIso(): String = LocalDate.now().toString()
