package app.stranitsa

import android.app.UiModeManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.stranitsa.core.Snapshot
import app.stranitsa.core.ThemeMode
import app.stranitsa.ui.AboutScreen
import app.stranitsa.ui.StTheme
import app.stranitsa.ui.DismissedScreen
import app.stranitsa.ui.ListsScreen
import app.stranitsa.ui.MainScreen
import app.stranitsa.ui.ReviewScreen
import app.stranitsa.ui.SearchScreen
import app.stranitsa.ui.SettingsScreen
import app.stranitsa.ui.SyncScreen
import app.stranitsa.ui.isDark

enum class Screen { Main, Dismissed, Settings, About, Search, Lists, Review, Sync }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: StViewModel = viewModel()
            val lib by vm.snapshot.collectAsStateWithLifecycle()
            val settings = lib.settings
            val dark = isDark(settings.theme)

            // Перерыв замечаем при каждом возвращении в приложение, а не только при запуске процесса.
            LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onAppStart() }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.markSeen(); vm.onAppStop(); vm.flush() }

            // Значки строки состояния и навигации — по теме приложения, а не системы.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // Android 12+: сообщаем системе выбор темы — тогда и фон окна при запуске
            // (до отрисовки Compose) будет правильного цвета, без вспышки.
            LaunchedEffect(settings.theme) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                        when (settings.theme) {
                            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                        }
                    )
                }
            }

            StTheme(mode = settings.theme, pureBlack = settings.pureBlack, dynamic = settings.dynamicColor) {
                StApp(vm, lib)
            }
        }
    }
}

@Composable
fun StApp(vm: StViewModel, lib: Snapshot) {
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    var jumpTo by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }
    val back = { screen = Screen.Main }
    val state = lib
    val defaultName = stringResource(R.string.list_default)
    // Имя списка показываем, только когда их несколько.
    val listName = if (lib.notebooks.size > 1) lib.name.ifBlank { defaultName } else null
    val open: (String) -> Unit = { jumpTo = it; screen = Screen.Main }

    when (screen) {
        Screen.Main -> MainScreen(
            state, vm, onOpen = { screen = it },
            showHints = lib.settings.showHints,
            listName = listName,
            breakDays = vm.breakDays,
            onDismissBreak = vm::dismissBreak,
            update = vm.update,
            onDismissUpdate = vm::dismissUpdate,
            jumpTo = jumpTo, onJumped = { jumpTo = null },
        )
        Screen.Search -> SearchScreen(state, vm::search, onBack = back, onOpenEntry = open)
        Screen.Review -> ReviewScreen(state, onBack = { vm.dismissBreak(); back() }, onOpenEntry = { vm.dismissBreak(); open(it) })
        Screen.Lists -> ListsScreen(lib, vm, onBack = back, onPicked = back)
        Screen.Dismissed -> DismissedScreen(state, vm, onBack = back)
        Screen.Settings -> SettingsScreen(state, lib.settings, vm, onBack = back, lastAutoSync = vm.lastAutoSync)
        Screen.About -> AboutScreen(onBack = back)
        Screen.Sync -> SyncScreen(vm.core, onSynced = vm::refresh, onBack = back)
    }
}
