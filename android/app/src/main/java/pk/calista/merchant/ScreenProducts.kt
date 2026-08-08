package pk.calista.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/** Screen 6 — Products: stock steppers and Live/Hidden, written straight to the website. */
@Composable
fun ProductsScreen(s: AppState) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = s.productQuery,
            onValueChange = { s.productQuery = it },
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            shape = RectangleShape,
            singleLine = true,
            placeholder = { Text("Search products", color = T.ghost, style = body(13)) },
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

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(s.visibleProducts) { p ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = T.text, style = head(15))
                            Text(
                                p.fabric + " · " + rs(p.sale ?: p.price) +
                                    (if (p.sale != null) "  was " + rs(p.price) else ""),
                                color = T.faint, style = body(12),
                            )
                        }
                        Box(
                            Modifier
                                .background(if (p.active) T.goldTint else Color.Transparent)
                                .border(1.dp, if (p.active) T.gold else T.line)
                                .clickable { s.toggleLive(p) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) { Kicker(if (p.active) "live" else "hidden", if (p.active) T.goldSoft else T.faint) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stepper("-") { s.bumpStock(p, -1) }
                        Box(
                            Modifier.height(44.dp).width(64.dp).border(1.dp, T.line),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                p.stock.toString(),
                                color = if (p.stock <= 3) T.warn else T.text,
                                style = head(16),
                            )
                        }
                        Stepper("+") { s.bumpStock(p, 1) }
                        Spacer(Modifier.width(12.dp))
                        Text("in stock", color = T.ghost, style = body(12))
                    }
                    Spacer(Modifier.height(12.dp))
                    RowDivider()
                }
            }
            item {
                if (s.products.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "No products loaded. This screen needs GET /api/admin/products on the " +
                            "Worker — see SYNC-SETUP.md step 3.",
                        color = T.faint, style = body(13),
                    )
                }
                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).border(1.dp, T.line).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = T.gold, style = head(18))
    }
}
