package daytrader.platform

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext

object CrashLogging {
    /**
     * Installs a default uncaught exception handler that prints full stack traces.
     *
     * This complements [CoroutineExceptionHandler] because not all failures happen inside coroutines.
     */
    fun installDefaultHandlers() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                System.err.println(
                    "[uncaught] thread=${thread.name} " +
                        "exception=${throwable.javaClass.name}: ${throwable.message}"
                )
                throwable.printStackTrace(System.err)
            } finally {
                existing?.uncaughtException(thread, throwable)
            }
        }
    }

    fun coroutineExceptionHandler(tag: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { ctx, throwable ->
            System.err.println(buildCoroutineHeader(tag, ctx, throwable))
            throwable.printStackTrace(System.err)
        }

    private fun buildCoroutineHeader(
        tag: String,
        ctx: CoroutineContext,
        throwable: Throwable
    ): String {
        val thread = Thread.currentThread()
        val coroutineName = ctx[CoroutineName]?.name
        return buildString {
            append("[coroutine-uncaught] tag=").append(tag)
            append(" thread=").append(thread.name)
            if (coroutineName != null) append(" coroutine=").append(coroutineName)
            append(" exception=").append(throwable.javaClass.name)
            val msg = throwable.message
            if (!msg.isNullOrBlank()) append(": ").append(msg)
        }
    }
}
