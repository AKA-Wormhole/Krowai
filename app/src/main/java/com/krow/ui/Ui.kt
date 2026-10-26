package com.krow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun T(
    text: String, size: Int = 14, color: Color = LocalPal.current.ink, weight: FontWeight = FontWeight.Normal,
    family: FontFamily = Sans, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier, italic: Boolean = false, lh: Float = 1.45f,
) = Text(
    text, modifier, color = color, fontSize = size.sp, fontWeight = weight, fontFamily = family, maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, lineHeight = (size * lh).sp,
)

@Composable
fun Ic(v: ImageVector, size: Int = 16, tint: Color = LocalPal.current.muted, modifier: Modifier = Modifier) =
    Icon(v, null, modifier.size(size.dp), tint = tint)

@Composable
fun Mark(size: Int = 28) {
    val p = LocalPal.current
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * .3f).dp)).background(p.brand), Alignment.Center) {
        T("K", (size * .6f).toInt(), p.accentInk, FontWeight.Medium, Read, lh = 1f)
    }
}

@Composable
fun IconBtn(
    v: ImageVector, desc: String = "", size: Int = 36, tint: Color = LocalPal.current.muted,
    filled: Boolean = false, onClick: () -> Unit,
) {
    val p = LocalPal.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, krowSpring(), label = "iconBtn")
    Box(
        Modifier
            .size(size.dp)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) p.accent else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        Alignment.Center,
    ) { Ic(v, 18, if (filled) p.accentInk else tint) }
}

@Composable
fun Pill(
    label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false, filled: Boolean = false,
    mono: Boolean = false, small: Boolean = false, color: Color? = null, onClick: (() -> Unit)? = null,
) {
    val p = LocalPal.current
    val bg = when { filled -> p.accent; selected -> p.accentSoft; else -> Color.Transparent }
    val fg = color ?: when { filled -> p.accentInk; selected -> p.accent; else -> p.muted }
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected || filled) Color.Transparent else p.line, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (small) 9.dp else 12.dp, vertical = if (small) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Ic(icon, 14, fg)
        T(label, if (small) 12 else 13, fg, FontWeight.Medium, if (mono) Mono else Sans, maxLines = 1)
    }
}

/** Soft elevated surface — warm shadow only, never glow. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, elevation: Dp = 6.dp, radius: Dp = 16.dp, borderColor: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .shadow(elevation, shape, clip = false, ambientColor = p.shadow, spotColor = p.shadow)
            .clip(shape)
            .background(p.surface)
            .border(1.dp, borderColor ?: p.line2, shape),
        content = content,
    )
}

fun kindIcon(kind: String): ImageVector = when (kind) {
    "search" -> Icons.Outlined.Search
    "memory" -> Icons.Outlined.Bookmark
    "link" -> Icons.Outlined.Link
    "file" -> Icons.Outlined.Description
    "edit" -> Icons.Outlined.Edit
    "folder" -> Icons.Outlined.Folder
    "zip" -> Icons.Outlined.Archive
    "git" -> Icons.Outlined.AccountTree
    "actions" -> Icons.Outlined.PlayArrow
    "artifact" -> Icons.Outlined.Inventory2
    else -> Icons.Outlined.Terminal
}

fun modeLabel(mode: String) = when (mode) {
    "auto" -> "Auto-run"
    "read" -> "Read only"
    else -> "Ask first"
}

fun modeIcon(mode: String) = when (mode) {
    "auto" -> Icons.Outlined.Bolt
    "read" -> Icons.Outlined.Visibility
    else -> Icons.Outlined.Shield
}

fun modeHint(mode: String) = when (mode) {
    "auto" -> "Run tools without asking"
    "read" -> "Search and read only"
    else -> "Approve each change"
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, placeholder: String = "", modifier: Modifier = Modifier,
    mono: Boolean = false, password: Boolean = false, singleLine: Boolean = true,
) {
    val p = LocalPal.current
    BasicTextField(
        value, onChange,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.sunk).border(1.dp, p.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        textStyle = TextStyle(color = p.ink, fontSize = 14.sp, fontFamily = if (mono) Mono else Sans),
        cursorBrush = SolidColor(p.accent), singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (password) KeyboardType.Password else KeyboardType.Text),
        decorationBox = { inner -> Box { if (value.isEmpty()) T(placeholder, 14, p.faint, family = if (mono) Mono else Sans); inner() } },
    )
}

@Composable
fun TextDialog(title: String, initial: String, hint: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val p = LocalPal.current
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.raised,
        title = { T(title, 17, weight = FontWeight.SemiBold) },
        text = { Field(v, { v = it }, hint) },
        confirmButton = { Pill(confirm, filled = true, onClick = { onOk(v) }) },
        dismissButton = { Pill("Cancel", onClick = onDismiss) },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    val p = LocalPal.current
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.raised,
        title = { T(title, 17, weight = FontWeight.SemiBold) },
        text = { T(body, 14, p.muted) },
        confirmButton = { Pill(confirm, filled = true, onClick = onOk) },
        dismissButton = { Pill("Cancel", onClick = onDismiss) },
    )
}

@Composable
fun HLine() { Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPal.current.line)) }

@Composable
fun ThreadBreak(label: String) {
    val p = LocalPal.current
    if (label.isBlank()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(p.line))
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(p.line))
        T(label, 11, p.faint, FontWeight.Medium, Mono)
        Box(Modifier.weight(1f).height(1.dp).background(p.line))
    }
}
