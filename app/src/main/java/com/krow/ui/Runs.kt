package com.krow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krow.*

/** Settings → Activity: recent commands and file changes Krow performed, newest first. */
@Composable
fun RunsSection(core: Core, onOpen: () -> Unit) {
    val p = LocalPal.current
    val s by core.state.collectAsState()
    val runs = remember(s.chats) { s.chats.flatMap { c -> c.msgs.filter { it.role == "tool" }.map { c to it } }.sortedByDescending { it.second.at }.take(60) }
    if (runs.isEmpty()) {
        T("No activity yet. Commands and file changes Krow performs show up here.", 14, p.muted)
        return
    }
    runs.forEach { (c, m) ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { core.openChat(c.id); onOpen() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(p.sunk), Alignment.Center) { Ic(kindIcon(m.kind), 16, p.accent) }
            Column(Modifier.weight(1f)) {
                T(m.title, 14, weight = FontWeight.Medium, maxLines = 1)
                T(m.cmd.ifBlank { c.title }, 12, p.muted, family = Mono, maxLines = 1)
            }
            when (m.status) {
                "done" -> Ic(Icons.Outlined.Check, 16, p.ok)
                "fail", "denied" -> Ic(Icons.Outlined.Close, 16, p.bad)
                else -> Ic(Icons.Outlined.Remove, 16, p.faint)
            }
        }
    }
}
