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

/** Screen — the homepage banners, in the order they appear on the site. */
@Composable
fun BannersScreen(s: AppState) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Homepage banners", color = T.text, style = head(16, FontWeight.Bold))
                Text(
                    s.banners.count { it.active }.toString() + " showing · " +
                        s.banners.count { !it.active } + " hidden",
                    color = T.ghost, style = mono(11),
                )
            }
            Box(
                Modifier.background(T.gold).clickable { s.newBanner() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) { Kicker("+ new", T.bg) }
        }

        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            items(s.banners) { b ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .background(T.surface).border(1.dp, T.line)
                        .clickable { s.editBanner(b) },
                ) {
                    Box(Modifier.fillMaxWidth().height(112.dp)) {
                        WideThumb(s.abs(b.image))
                        Column(
                            Modifier.align(Alignment.BottomStart).padding(13.dp),
                        ) {
                            if (b.eyebrow.isNotBlank()) Kicker(b.eyebrow, T.goldSoft)
                            Text(b.heading, color = T.text, style = head(19, FontWeight.Bold))
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(9.dp)
                                .background(if (b.active) T.goldTint else T.bg.copy(alpha = 0.8f))
                                .border(1.dp, if (b.active) T.gold else T.line)
                                .clickable { s.toggleBanner(b) }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Kicker(
                                if (b.active) "showing" else "hidden",
                                if (b.active) T.goldSoft else T.faint,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            b.subheading.ifBlank { "no subheading" },
                            color = T.ghost, style = body(12),
                            modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        if (b.buttonText.isNotBlank()) {
                            Kicker(b.buttonText, T.goldSoft)
                        }
                    }
                }
            }
            item {
                if (s.banners.isEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text("No banners yet. Tap + new.", color = T.faint, style = body(13))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Screen — write one banner and photograph its background. */
@Composable
fun BannerEditScreen(s: AppState) {
    val isNew = s.bId == 0L
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {

        Kicker(if (isNew) "New banner" else "Editing banner")
        Spacer(Modifier.height(12.dp))

        PhotoField(s, s.bImage) { s.bImage = it }

        Spacer(Modifier.height(18.dp))
        Field("Small line above", s.bEyebrow, "New arrivals") { s.bEyebrow = it }
        Spacer(Modifier.height(12.dp))
        Field("Heading", s.bHeading, "Eid Collection 2026") { s.bHeading = it }
        Spacer(Modifier.height(12.dp))
        Field("Line underneath", s.bSub, "Embroidered lawn, ready to ship", lines = 2) {
            s.bSub = it
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Button words", s.bBtnText, "Shop now", Modifier.weight(1f)) { s.bBtnText = it }
            Field("Button goes to", s.bBtnLink, "/collection.html", Modifier.weight(1f)) {
                s.bBtnLink = it
            }
        }

        Spacer(Modifier.height(18.dp))
        Kicker("How it will look", T.faint)
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(150.dp).border(1.dp, T.line)) {
            WideThumb(s.abs(s.bImage))
            Column(Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                if (s.bEyebrow.isNotBlank()) Kicker(s.bEyebrow, T.goldSoft)
                Spacer(Modifier.height(4.dp))
                Text(
                    s.bHeading.ifBlank { "Heading goes here" },
                    color = T.text, style = head(24, FontWeight.Bold),
                )
                if (s.bSub.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(s.bSub, color = T.muted, style = body(12))
                }
                if (s.bBtnText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.background(T.gold).padding(horizontal = 13.dp, vertical = 8.dp)) {
                        Kicker(s.bBtnText, T.bg)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        ActiveToggle(
            s.bActive, "showing", "hidden",
            if (s.bActive) "Visible on the homepage." else "Saved, but not on the site.",
        ) { s.bActive = !s.bActive }

        Spacer(Modifier.height(22.dp))
        GoldButton(
            if (s.saving) "Saving…" else if (isNew) "Add banner" else "Save banner",
            Modifier.fillMaxWidth(), enabled = !s.saving && !s.uploading,
        ) { s.saveBanner() }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton("Cancel", Modifier.weight(1f)) { s.go(Screen.Banners) }
            if (!isNew) {
                Box(
                    Modifier.weight(1f).height(46.dp).border(1.dp, T.warn.copy(alpha = 0.5f))
                        .clickable { s.deleteBanner() },
                    contentAlignment = Alignment.Center,
                ) { Kicker("delete", T.warn) }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** A wide, cropped image fill used behind banner text. */
@Composable
fun WideThumb(url: String) {
    Box(Modifier.fillMaxWidth().height(150.dp).background(T.elev)) {
        if (url.isNotBlank()) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Box(Modifier.fillMaxWidth().height(150.dp).background(T.bg.copy(alpha = 0.45f)))
        }
    }
}
