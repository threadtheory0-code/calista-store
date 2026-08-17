package pk.calista.merchant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val TYPES = listOf(
    Triple("percentage_off_order", "% off", "Takes a percentage off the whole order"),
    Triple("fixed_off_order", "Rs off", "Takes a fixed amount off the whole order"),
    Triple("buy_x_get_y", "Buy X get Y", "Cheapest items in each group are discounted"),
)

/** Screen — the offers running on the website right now. */
@Composable
fun DiscountsScreen(s: AppState) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.discounts.count { it.active }.toString() + " running",
                    color = T.text, style = head(16, FontWeight.Bold),
                )
                Text(
                    s.discounts.count { !it.active }.toString() + " paused",
                    color = T.ghost, style = mono(11),
                )
            }
            Box(
                Modifier.background(T.gold).clickable { s.newDiscount() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) { Kicker("+ new offer", T.bg) }
        }

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(s.discounts) { d ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .background(T.surface).border(1.dp, T.line)
                        .clickable { s.editDiscount(d) }.padding(13.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, color = T.text, style = head(15))
                            Spacer(Modifier.height(3.dp))
                            Text(d.summary, color = T.goldSoft, style = body(12))
                        }
                        Box(
                            Modifier
                                .background(if (d.active) T.goldTint else Color.Transparent)
                                .border(1.dp, if (d.active) T.gold else T.line)
                                .clickable { s.toggleDiscount(d) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) { Kicker(if (d.active) "on" else "off", if (d.active) T.goldSoft else T.faint) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (d.code.isNotBlank()) Tag(d.code, T.gold) else Tag("automatic", T.ship)
                        if (d.minCart != null) Tag("min " + rs(d.minCart), T.faint)
                        Tag("used " + d.usedCount + (d.usageLimit?.let { " / " + it } ?: ""), T.faint)
                        if (d.endDate.isNotBlank()) Tag("ends " + d.endDate, T.faint)
                    }
                }
            }
            item {
                if (s.discounts.isEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text("No offers yet. Tap + new offer.", color = T.faint, style = body(13))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Screen — build or change one offer. */
@Composable
fun DiscountEditScreen(s: AppState) {
    val isNew = s.xId == 0L
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Kicker(if (isNew) "New offer" else "Editing offer")
        Spacer(Modifier.height(12.dp))

        Field("What is the offer called?", s.xTitle, "Eid Sale 20% off") { s.xTitle = it }

        Spacer(Modifier.height(16.dp))
        Kicker("Kind of offer", T.faint)
        Spacer(Modifier.height(7.dp))
        Column(Modifier.fillMaxWidth()) {
            for ((code, label, note) in TYPES) {
                val on = s.xType == code
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (on) T.goldTint else Color.Transparent)
                        .border(1.dp, if (on) T.gold else T.line)
                        .clickable { s.xType = code }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(16.dp).height(16.dp)
                            .border(1.dp, if (on) T.gold else T.line)
                            .background(if (on) T.gold else Color.Transparent),
                    )
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(label, color = if (on) T.goldSoft else T.text, style = head(14))
                        Text(note, color = T.ghost, style = body(11))
                    }
                }
                Spacer(Modifier.height(7.dp))
            }
        }

        Spacer(Modifier.height(10.dp))
        when (s.xType) {
            "percentage_off_order" ->
                Field("Percentage off", s.xValue, "20", number = true) { s.xValue = it }
            "fixed_off_order" ->
                Field("Rupees off", s.xValue, "500", number = true) { s.xValue = it }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Buy", s.xBuy, "2", Modifier.weight(1f), number = true) { s.xBuy = it }
                Field("Get", s.xGet, "1", Modifier.weight(1f), number = true) { s.xGet = it }
                Field("at % off", s.xGetPercent, "100", Modifier.weight(1f), number = true) {
                    s.xGetPercent = it
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Field("Code the customer types", s.xCode, "leave empty to apply automatically") {
            s.xCode = it
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Minimum cart", s.xMinCart, "optional", Modifier.weight(1f), number = true) {
                s.xMinCart = it
            }
            Field("Times usable", s.xLimit, "unlimited", Modifier.weight(1f), number = true) {
                s.xLimit = it
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Starts", s.xStart, "2026-08-20", Modifier.weight(1f)) { s.xStart = it }
            Field("Ends", s.xEnd, "2026-08-31", Modifier.weight(1f)) { s.xEnd = it }
        }
        Spacer(Modifier.height(5.dp))
        Text("Dates as year-month-day. Leave empty for no limit.", color = T.ghost, style = body(11))

        Spacer(Modifier.height(18.dp))
        ActiveToggle(
            s.xActive, "running", "paused",
            if (s.xActive) "Customers can use this now." else "Saved, but not applied at checkout.",
        ) { s.xActive = !s.xActive }

        Spacer(Modifier.height(22.dp))
        GoldButton(
            if (s.saving) "Saving…" else if (isNew) "Create offer" else "Save offer",
            Modifier.fillMaxWidth(), enabled = !s.saving,
        ) { s.saveDiscount() }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton("Cancel", Modifier.weight(1f)) { s.go(Screen.Discounts) }
            if (!isNew) {
                Box(
                    Modifier.weight(1f).height(46.dp).border(1.dp, T.warn.copy(alpha = 0.5f))
                        .clickable { s.deleteDiscount() },
                    contentAlignment = Alignment.Center,
                ) { Kicker("delete", T.warn) }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Tag(label: String, color: Color) {
    Box(
        Modifier.border(1.dp, color.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
    ) { Kicker(label, color) }
}
