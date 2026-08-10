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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Screen 6 — Products: photo, stock stepper, Live/Hidden, written straight to the website. */
@Composable
fun ProductsScreen(s: AppState) {
    val low = s.products.count { it.stock in 1..3 }
    val out = s.products.count { it.stock <= 0 }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = s.productQuery,
            onValueChange = { s.productQuery = it },
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
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

        if (low > 0 || out > 0) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (out > 0) Pill(out.toString() + " out of stock", T.warn)
                if (low > 0) Pill(low.toString() + " running low", T.gold)
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(s.visibleProducts) { p ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .background(T.surface).border(1.dp, T.line).padding(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        Thumb(s.abs(p.image), 76, 96)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = T.text, style = head(15), maxLines = 2)
                            Spacer(Modifier.height(3.dp))
                            Kicker(p.fabric.ifBlank { "—" }, T.ghost)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    rs(p.sale ?: p.price),
                                    color = T.goldSoft,
                                    style = head(16, FontWeight.Bold),
                                )
                                if (p.sale != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(rs(p.price), color = T.ghost, style = body(12))
                                }
                            }
                        }
                        Box(
                            Modifier
                                .background(if (p.active) T.goldTint else Color.Transparent)
                                .border(1.dp, if (p.active) T.gold else T.line)
                                .clickable { s.toggleLive(p) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            Kicker(
                                if (p.active) "live" else "hidden",
                                if (p.active) T.goldSoft else T.faint,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stepper("−") { s.bumpStock(p, -1) }
                        Box(
                            Modifier.height(44.dp).width(64.dp)
                                .border(1.dp, if (p.stock <= 3) T.warn.copy(alpha = 0.6f) else T.line),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                p.stock.toString(),
                                color = if (p.stock <= 3) T.warn else T.text,
                                style = head(16, FontWeight.Bold),
                            )
                        }
                        Stepper("+") { s.bumpStock(p, 1) }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (p.stock <= 0) "out of stock" else "in stock",
                            color = if (p.stock <= 0) T.warn else T.ghost,
                            style = body(12),
                        )
                    }
                }
            }
            item {
                if (s.products.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("No products loaded yet.", color = T.faint, style = body(13))
                }
                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

@Composable
fun Thumb(url: String, w: Int, h: Int) {
    Box(
        Modifier.width(w.dp).height(h.dp).background(T.elev).border(1.dp, T.line),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isBlank()) {
            Text("—", color = T.ghost, style = mono(12))
        } else {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(h.dp),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun Pill(label: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.5f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) { Kicker(label, color) }
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
