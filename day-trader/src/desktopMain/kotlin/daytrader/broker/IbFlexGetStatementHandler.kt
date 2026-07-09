package daytrader.broker

import org.w3c.dom.Element

internal object IbFlexGetStatementHandler {
    sealed interface Outcome {
        data class Ready(val xml: String) : Outcome
        data object InProgress : Outcome
        data class Failed(val code: String?, val message: String?, val raw: String) : Outcome
    }

    fun classify(document: Element, xml: String): Outcome {
        if (document.nodeName == "FlexQueryResponse" || document.getElementsByTagName("Trade").length > 0) {
            return Outcome.Ready(xml)
        }
        return when (document.getElementsByTagName("Status").item(0)?.textContent) {
            "Success" -> Outcome.Ready(xml)
            "Fail" -> {
                val code = document.getElementsByTagName("ErrorCode").item(0)?.textContent
                if (code == STATEMENT_IN_PROGRESS || code == RATE_LIMITED) {
                    Outcome.InProgress
                } else {
                    val message = document.getElementsByTagName("ErrorMessage").item(0)?.textContent
                    Outcome.Failed(code, message, xml)
                }
            }
            else -> Outcome.Failed(code = null, message = "Unexpected Flex GetStatement response", raw = xml.take(200))
        }
    }

    private const val STATEMENT_IN_PROGRESS = "1019"
    private const val RATE_LIMITED = "1018"
}
