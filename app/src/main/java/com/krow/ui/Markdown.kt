package com.krow.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface Md {
    data class Para(val t: String) : Md
    data class Code(val lang: String, val code: String) : Md
    data class Head(val level: Int, val t: String) : Md
    data class Item(val marker: String, val t: String, val indent: Int) : Md
    data class Quote(val t: String) : Md
    data object Rule : Md
}

private val listRe = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
private val headRe = Regex("""^(#{1,4})\s+(.*)$""")
private val ruleRe = Regex("""^(-{3,}|\*{3,}|_{3,})$""")

fun parseMd(src: String): List<Md> {
    val out = ArrayList<Md>()
    val lines = src.split("\n")
    val para = StringBuilder()
    fun flush() { if (para.isNotEmpty()) { out += Md.Para(para.toString().trimEnd()); para.setLength(0) } }
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        if (l.trimStart().startsWith("```")) {
            flush()
            val lang = l.trim().removePrefix("```").trim()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
            out += Md.Code(lang, code.toString().trimEnd('\n'))
            i++
            continue
        }
        val h = headRe.find(l)
        val li = listRe.find(l)
        when {
            l.isBlank() -> flush()
            ruleRe.matches(l.trim()) -> { flush(); out += Md.Rule }
            h != null -> { flush(); out += Md.Head(h.groupValues[1].length, h.groupValues[2]) }
            li != null -> { flush(); out += Md.Item(li.groupValues[2].let { if (it[0].isDigit()) it else "•" }, li.groupValues[3], li.groupValues[1].length / 2) }
            l.startsWith(">") -> { flush(); out += Md.Quote(l.removePrefix(">").trim()) }
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
        }
        i++
    }
    flush()
    return out
}

@Composable
fun inlineMd(s: String): AnnotatedString {
    val p = LocalPal.current
    val codeStyle = SpanStyle(fontFamily = Mono, fontSize = 14.sp, color = p.codeInk, background = p.codeBg)
    val linkStyle = TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val e = s.indexOf('`', i + 1)
                if (e > i) { withStyle(codeStyle) { append(s.substring(i + 1, e)) }; i = e + 1; continue }
            }
            if (s.startsWith("**", i)) {
                val e = s.indexOf("**", i + 2)
                if (e > i + 2) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, e)) }; i = e + 2; continue }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                val e = s.indexOf(c, i + 1)
                if (e > i + 1 && !s[e - 1].isWhitespace()) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, e)) }; i = e + 1; continue }
            }
            if (c == '[') {
                val mid = s.indexOf("](", i)
                val end = if (mid > i) s.indexOf(')', mid) else -1
                if (mid > i && end > mid) {
                    val url = s.substring(mid + 2, end)
                    if (url.startsWith("http")) { withLink(LinkAnnotation.Url(url, linkStyle)) { append(s.substring(i + 1, mid)) }; i = end + 1; continue }
                }
            }
            if ((c == 'h') && (s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                var e = i
                while (e < s.length && !s[e].isWhitespace() && s[e] != ')' && s[e] != '>' && s[e] != '"') e++
                while (e > i && s[e - 1] in ".,;:!?*_'") e--
                val url = s.substring(i, e)
                if (url.length > 9) { withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }; i = e; continue }
            }
            append(c); i++
        }
    }
}

@Composable
fun Markdown(text: String) {
    val p = LocalPal.current
    val blocks = remember(text) { parseMd(text) }
    SelectionContainer {
        Column {
            blocks.forEachIndexed { i, b ->
                val prev = blocks.getOrNull(i - 1)
                val gap = when {
                    i == 0 -> 0.dp
                    prev is Md.Item && b is Md.Item -> 7.dp
                    b is Md.Head -> 22.dp
                    prev is Md.Head -> 8.dp
                    else -> 16.dp
                }
                Box(Modifier.padding(top = gap)) {
                    when (b) {
                        is Md.Para -> Text(inlineMd(b.t), color = p.ink, fontFamily = Read, fontSize = 16.sp, lineHeight = 26.sp)
                        is Md.Head -> Text(
                            inlineMd(b.t), color = p.ink, fontFamily = Read, fontWeight = FontWeight.SemiBold,
                            fontSize = (23 - b.level * 2).sp, lineHeight = 30.sp,
                        )
                        is Md.Item -> Row(Modifier.padding(start = (b.indent * 18).dp)) {
                            if (b.marker == "•") Box(Modifier.width(22.dp).height(26.dp), Alignment.TopStart) {
                                Box(Modifier.padding(top = 11.dp, start = 4.dp).size(5.dp).clip(CircleShape).background(p.ink))
                            } else Text(b.marker, color = p.ink, fontFamily = Read, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.width(26.dp))
                            Text(inlineMd(b.t), color = p.ink, fontFamily = Read, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.weight(1f))
                        }
                        is Md.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(p.line2))
                            Text(inlineMd(b.t), Modifier.padding(start = 12.dp), color = p.muted, fontFamily = Read, fontSize = 16.sp, lineHeight = 26.sp)
                        }
                        is Md.Code -> CodeBlock(b.lang, b.code)
                        is Md.Rule -> ThreadBreak("")
                    }
                }
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String) {
    val p = LocalPal.current
    val clip = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, p.line, RoundedCornerShape(14.dp))
            .background(p.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().background(p.sunk).padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(lang.ifBlank { "code" }, 12, p.muted, family = Mono, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { clip.setText(AnnotatedString(code)) }.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Ic(Icons.Outlined.ContentCopy, 13)
                T("Copy", 12, p.muted)
            }
        }
        HLine()
        Text(
            code,
            Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            color = p.ink, fontFamily = Mono, fontSize = 13.sp, lineHeight = 20.sp, softWrap = false,
        )
    }
}
