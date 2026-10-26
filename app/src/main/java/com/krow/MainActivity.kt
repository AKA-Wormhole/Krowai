package com.krow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.krow.ui.ChatScreen
import com.krow.ui.FilesScreen
import com.krow.ui.GitHubSection
import com.krow.ui.KrowTheme
import com.krow.ui.LocalPal
import com.krow.ui.SearchScreen
import com.krow.ui.SettingsScreen
import com.krow.ui.Sidebar
import com.krow.ui.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as KrowApp).core
        handleIntent(intent, core)
        setContent { Root(core) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, (application as KrowApp).core)
    }

    /** Text / links / images shared into Krow from other apps. */
    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?, core: Core) {
        if (i == null) return
        val mime = i.type.orEmpty()
        when (i.action) {
            Intent.ACTION_SEND -> {
                i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { core.incomingText.value = it }
            }
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { core.incomingText.value = it }
        }
        i.action = null
    }
}

@Composable
fun Root(core: Core) {
    val s by core.state.collectAsState()
    KrowTheme(s.settings.theme, s.settings.accent) {
        val p = LocalPal.current
        val scope = rememberCoroutineScope()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var screen by rememberSaveable { mutableStateOf("chat") }
        var settingsPage by rememberSaveable { mutableStateOf("home") }
        val notice by core.notice.collectAsState()
        LaunchedEffect(notice) { if (notice != null) { delay(4000); core.notice.value = null } }
        BackHandler(enabled = screen != "chat") { screen = "chat" }
        val sharedText by core.incomingText.collectAsState()
        LaunchedEffect(sharedText) { if (sharedText != null) screen = "chat" }

        Box(Modifier.fillMaxSize().background(p.bg)) {
            when (screen) {
                "settings" -> SettingsScreen(core, startPage = settingsPage, onBack = { screen = "chat" }, onSearch = { screen = "search" }, extra = { GitHubSection(core) })
                "search" -> SearchScreen(core, onBack = { screen = "chat" }, onAsk = { prompt -> core.newChat(); screen = "chat"; core.send(prompt) })
                "files" -> {
                    val ch = s.chats.firstOrNull { it.id == s.activeChat }
                    FilesScreen(core, core.workProject(ch?.projectId ?: s.activeProject.ifEmpty { s.projects.first().id }), onBack = { screen = "chat" })
                }
                else -> ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = p.side,
                            drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                            modifier = Modifier.width(330.dp),
                        ) {
                            Sidebar(core, close = { scope.launch { drawer.close() } }, openSettings = { pg -> scope.launch { drawer.close() }; settingsPage = pg; screen = "settings" }, openFiles = { screen = "files" })
                        }
                    },
                ) {
                    ChatScreen(core, onMenu = { scope.launch { drawer.open() } }, onSettings = { pg -> settingsPage = pg; screen = "settings" }, onFiles = { screen = "files" })
                }
            }
            notice?.let { n ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(p.raised)
                        .border(1.dp, p.line2, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    T(n, 13)
                }
            }
        }
    }
}
