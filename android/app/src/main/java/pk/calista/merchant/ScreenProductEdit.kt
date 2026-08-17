package pk.calista.merchant

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Screen — add or edit a product, written straight to the website. */
@Composable
fun ProductEditScreen(s: AppState) {
    val isNew = s.draftId == 0L
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Kicker(if (isNew) "New product" else "Editing #" + s.draftId)
        Spacer(Modifier.height(12.dp))

        PhotoField(s, s.dImage) { s.dImage = it }

        Spacer(Modifier.height(18.dp))
        Field("Product name", s.dName, "Embroidered Lawn 3-Piece") { s.dName = it }

        Spacer(Modifier.height(14.dp))
        Field("Fabric", s.dFabric, "Lawn") { s.dFabric = it }
        if (s.fabrics.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                for (f in s.fabrics) {
                    val on = s.dFabric.equals(f, true)
                    Box(
                        Modifier
                            .background(if (on) T.goldTint else Color.Transparent)
                            .border(1.dp, if (on) T.gold else T.line)
                            .clickable { s.dFabric = f }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) { Kicker(f, if (on) T.goldSoft else T.muted) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Price", s.dPrice, "4500", Modifier.weight(1f), number = true) { s.dPrice = it }
            Field("Sale price", s.dSale, "optional", Modifier.weight(1f), number = true) {
                s.dSale = it
            }
        }
        Spacer(Modifier.height(12.dp))
        Field("Stock", s.dStock, "0", number = true) { s.dStock = it }

        Spacer(Modifier.height(18.dp))
        ActiveToggle(
            s.dActive, "live on website", "hidden",
            if (s.dActive) "Customers can see and buy this." else "Saved, but not shown in the shop.",
        ) { s.dActive = !s.dActive }

        Spacer(Modifier.height(22.dp))
        GoldButton(
            if (s.saving) "Saving…" else if (isNew) "Add product" else "Save changes",
            Modifier.fillMaxWidth(),
            enabled = !s.saving && !s.uploading,
        ) { s.saveDraft() }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton("Cancel", Modifier.weight(1f)) { s.go(Screen.Products) }
            if (!isNew) {
                Box(
                    Modifier.weight(1f).height(46.dp).border(1.dp, T.warn.copy(alpha = 0.5f))
                        .clickable { s.deleteDraft() },
                    contentAlignment = Alignment.Center,
                ) { Kicker("delete", T.warn) }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
