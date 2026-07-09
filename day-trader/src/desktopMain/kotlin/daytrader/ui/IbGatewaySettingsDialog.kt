package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import daytrader.broker.IbGatewayConfig
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
fun IbGatewaySettingsDialog(
    initial: IbGatewayConfig,
    onDismiss: () -> Unit,
    onSave: (IbGatewayConfig) -> Unit
) {
    var host by remember(initial) { mutableStateOf(initial.host) }
    var port by remember(initial) { mutableStateOf(initial.port.toString()) }
    var clientId by remember(initial) { mutableStateOf(initial.clientId.toString()) }
    var accountCode by remember(initial) { mutableStateOf(initial.accountCode) }
    var flexToken by remember(initial) { mutableStateOf(initial.flexToken) }
    var flexTradesQueryId by remember(initial) { mutableStateOf(initial.flexTradesQueryId) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 420.dp, max = 480.dp),
            color = SurfaceDark
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Interactive Brokers",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "Gateway / TWS connection. Environment variables override saved values. " +
                        "Changes apply on the next broker connection.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                IbSettingsField("Host", host, onValueChange = { host = it })
                IbSettingsField("Port", port, onValueChange = { port = it })
                IbSettingsField("Client ID", clientId, onValueChange = { clientId = it })
                IbSettingsField(
                    label = "Account (optional)",
                    value = accountCode,
                    onValueChange = { accountCode = it },
                    supportingText = "Leave blank to subscribe to all accounts."
                )
                Text(
                    "Settled trade history (Flex Web Service)",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    "In IB Client Portal (not TWS): Menu → Reporting → Flex Queries. " +
                        "Create an Activity Flex Query with the Trades section, then open the gear icon " +
                        "→ Flex Web Service Configuration to enable and copy the token. " +
                        "Live accounts only — paper accounts cannot use Flex.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                IbSettingsField(
                    label = "Flex token",
                    value = flexToken,
                    onValueChange = { flexToken = it },
                    supportingText = "From Flex Web Service Configuration in Client Portal."
                )
                IbSettingsField(
                    label = "Trades query ID",
                    value = flexTradesQueryId,
                    onValueChange = { flexTradesQueryId = it },
                    supportingText = "Numeric ID beside query name. Query must include Trade Date and cover your history (e.g. Last 3 Days)."
                )
                errorMessage?.let { message ->
                    Text(message, color = BrandRed, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Button(
                        onClick = {
                            val parsed = parseIbGatewaySettings(
                                host = host,
                                port = port,
                                clientId = clientId,
                                accountCode = accountCode,
                                flexToken = flexToken,
                                flexTradesQueryId = flexTradesQueryId,
                            )
                            if (parsed == null) {
                                errorMessage = "Enter a valid host, port (1–65535), and client ID."
                            } else {
                                errorMessage = null
                                onSave(parsed)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun IbSettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supportingText: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = BrandRed,
                unfocusedBorderColor = TextSecondary,
                focusedLabelColor = TextSecondary,
                unfocusedLabelColor = TextSecondary,
                cursorColor = BrandRed
            )
        )
        supportingText?.let {
            Text(it, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

internal fun parseIbGatewaySettings(
    host: String,
    port: String,
    clientId: String,
    accountCode: String,
    flexToken: String = "",
    flexTradesQueryId: String = "",
): IbGatewayConfig? {
    val trimmedHost = host.trim()
    if (trimmedHost.isEmpty()) return null
    val parsedPort = port.trim().toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
    val parsedClientId = clientId.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
    return IbGatewayConfig(
        host = trimmedHost,
        port = parsedPort,
        clientId = parsedClientId,
        accountCode = accountCode.trim(),
        flexToken = flexToken.trim(),
        flexTradesQueryId = flexTradesQueryId.trim(),
    )
}
