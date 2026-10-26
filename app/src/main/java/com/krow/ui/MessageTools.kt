package com.krow.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Opens a link in the user's browser. */
fun openUrl(ctx: Context, url: String) {
    val u = if (url.contains("://")) url else "https://$url"
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** System share sheet for plain text / links. */
fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** True when the whole string is a single address the user probably wants to open directly (like a browser omnibox). */
fun looksLikeUrl(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty() || t.contains(' ')) return false
    if (t.startsWith("http://") || t.startsWith("https://")) return true
    return Regex("""^([a-z0-9-]+\.)+[a-z]{2,}(:\d+)?(/\S*)?$""", RegexOption.IGNORE_CASE).matches(t)
}

/** Full-text view where any part of the message can be selected, copied or shared. */
@Composable
fun SelectTextDialog(text: String, onClose: () -> Unit) {
    val p = LocalPal.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose, containerColor = p.raised,
        title = { T("Select text", 16, weight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { T(text, 15, lh = 1.5f) }
            }
        },
        confirmButton = { Pill("Copy all", filled = true) { clip.setText(AnnotatedString(text)); onClose() } },
        dismissButton = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("Share") { shareText(ctx, text); onClose() }; Pill("Close", onClick = onClose) } },
    )
}
