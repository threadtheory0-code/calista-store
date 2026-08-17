package pk.calista.merchant

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

/** One labelled text field, styled once so every editor screen matches. */
@Composable
fun Field(
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    lines: Int = 1,
    onChange: (String) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Kicker(label, T.faint)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            singleLine = lines == 1,
            minLines = lines,
            textStyle = body(14, FontWeight.Medium).copy(color = T.text),
            placeholder = { Text(hint, color = T.ghost, style = body(13)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
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

/** A live/hidden switch — the wording changes with what is being switched. */
@Composable
fun ActiveToggle(on: Boolean, onLabel: String, offLabel: String, note: String, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .background(if (on) T.goldTint else Color.Transparent)
                .border(1.dp, if (on) T.gold else T.line)
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) { Kicker(if (on) onLabel else offLabel, if (on) T.goldSoft else T.faint) }
        Spacer(Modifier.width(12.dp))
        Text(note, color = T.ghost, style = body(12))
    }
}

/**
 * Photo block: shows the current picture, and offers Camera / Gallery / Clear.
 * The chosen shot is shrunk and uploaded to the store's image bucket; the returned
 * path is handed back through onUploaded.
 */
@Composable
fun PhotoField(
    s: AppState,
    url: String,
    onUploaded: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val target = remember { Photo.cameraTarget(ctx) }
    val cameraUri = remember {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", target)
    }

    val pickGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? -> if (uri != null) s.uploadPhoto(uri) { onUploaded(it) } }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok -> if (ok) s.uploadCameraFile(target) { onUploaded(it) } }

    Column(Modifier.fillMaxWidth()) {
        Kicker("Photo", T.faint)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.Top) {
            Box {
                Thumb(s.abs(url), 100, 126)
                if (s.uploading) {
                    Box(
                        Modifier.width(100.dp).height(126.dp).background(T.bg.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center,
                    ) { Kicker("uploading", T.gold) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (url.isBlank()) {
                        "Photograph the outfit, or pick a picture already on this phone. " +
                            "It uploads to your store and appears on the website."
                    } else {
                        "Saved on your store at " + url
                    },
                    color = T.faint, style = body(12),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Chip("camera", T.gold, !s.uploading) { takePhoto.launch(cameraUri) }
                    Chip("gallery", T.muted, !s.uploading) { pickGallery.launch("image/*") }
                    if (url.isNotBlank()) Chip("clear", T.warn, !s.uploading) { onUploaded("") }
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, if (enabled) color.copy(alpha = 0.55f) else T.line)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) { Kicker(label, if (enabled) color else T.ghost) }
}
