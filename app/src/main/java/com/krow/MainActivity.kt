package com.krow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RectangleShape
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
import com.krow.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as KrowApp).core
        setContent { Root(core) }
    }
}

@Composable
fun Root(core: Core) {
    val s by core.state.collectAsState()
    KrowTheme(s.settings.theme) {
        val p = LocalPal.current
        val scope = rememberCoroutineScope()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var screen by rememberSaveable { mutableStateOf("chat") }
        val notice by core.notice.collectAsState()
        LaunchedEffect(notice) { if (notice != null) { delay(4000); core.notice.value = null } }
        BackHandler(enabled = screen != "chat") { screen = "chat" }

        Box(Modifier.fillMaxSize().background(p.bg)) {
            when (screen) {
                "settings" -> SettingsScreen(core, onBack = { screen = "chat" }, extra = { GitHubSection(core) })
                "files" -> {
                    val ch = s.chats.firstOrNull { it.id == s.activeChat }
                    FilesScreen(core, core.project(ch?.projectId ?: s.activeProject.ifEmpty { s.projects.first().id }), onBack = { screen = "chat" })
                }
                else -> ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(drawerContainerColor = p.side, drawerShape = RectangleShape, modifier = Modifier.width(310.dp)) {
                            Sidebar(core, close = { scope.launch { drawer.close() } }, openSettings = { scope.launch { drawer.close() }; screen = "settings" })
                        }
                    },
                ) {
                    ChatScreen(core, onMenu = { scope.launch { drawer.open() } }, onSettings = { screen = "settings" }, onFiles = { screen = "files" })
                }
            }
            notice?.let { n ->
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 16.dp, end = 16.dp).clip(RoundedCornerShape(12.dp)).background(p.raised).border(1.dp, p.line2, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    T(n, 13)
                }
            }
        }
    }
}
