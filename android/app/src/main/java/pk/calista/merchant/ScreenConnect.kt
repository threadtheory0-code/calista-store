package pk.calista.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Screen 1 — Connect store. Runs once; the values live in SharedPreferences after that. */
@Composable
fun ConnectScreen(s: AppState) {
    var url by remember { mutableStateOf(if (s.storeUrl.isBlank()) "https://calista.pk" else s.storeUrl) }
    var token by remember { mutableStateOf(s.adminToken) }

    Column(
        Modifier.fillMaxSize().background(T.bg).verticalScroll(rememberScrollState()).padding(22.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        Kicker("Calista · merchant", T.gold)
        Spacer(Modifier.height(10.dp))
        Text("Connect store", color = T.text, style = head(34, FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        Text(
            "The app reads and writes the same Cloudflare D1 database as your website. " +
                "Nothing is stored anywhere else.",
            color = T.faint, style = body(13),
        )
        Spacer(Modifier.height(26.dp))

        Field("Store address", url, { url = it }, "https://calista.pk")
        Spacer(Modifier.height(14.dp))
        Field("Admin token", token, { token = it }, "the ADMIN_TOKEN worker secret", password = true)

        Spacer(Modifier.height(24.dp))
        GoldButton(
            "Connect",
            Modifier.fillMaxWidth(),
            enabled = url.isNotBlank() && token.isNotBlank() && !s.loading,
        ) { s.connect(url, token) }

        if (s.toast.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Blueprint(borderColor = T.warn) {
                Kicker("Could not connect", T.warn)
                Spacer(Modifier.height(6.dp))
                Text(s.toast, color = T.muted, style = body(13))
            }
        }

        Spacer(Modifier.height(28.dp))
        Blueprint {
            Kicker("Checklist")
            Spacer(Modifier.height(8.dp))
            Text(
                "1. /api/admin/* endpoints deployed on the Worker\n" +
                    "2. ADMIN_TOKEN set as a Worker secret\n" +
                    "3. POSTEX_TOKEN set, cities cached\n\n" +
                    "See SYNC-SETUP.md — every step has a curl check.",
                color = T.faint, style = body(12),
            )
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    password: Boolean = false,
) {
    Column {
        Kicker(label)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            singleLine = true,
            placeholder = { Text(hint, color = T.ghost, style = body(13)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = T.gold,
                unfocusedBorderColor = T.line,
                focusedTextColor = T.text,
                unfocusedTextColor = T.text,
                cursorColor = T.gold,
                focusedContainerColor = T.surface,
                unfocusedContainerColor = T.surface,
            ),
        )
    }
}
