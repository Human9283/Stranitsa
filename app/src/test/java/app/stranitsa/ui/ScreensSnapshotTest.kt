package app.stranitsa.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import app.stranitsa.StActions
import app.stranitsa.ShareSheet
import app.stranitsa.core.StCore
import app.stranitsa.core.KnownPeer
import app.stranitsa.core.NearbyPeer
import app.stranitsa.core.SearchHit
import app.stranitsa.core.Settings
import app.stranitsa.core.Snapshot
import app.stranitsa.core.Status
import app.stranitsa.core.ThemeMode
import org.junit.Rule
import org.junit.Test

private object NoActions : StActions {
    override fun undo() {}
    override fun add(text: String) {}
    override fun done(id: String) {}
    override fun reenter(id: String) {}
    override fun cancel(id: String) {}
    override fun edit(id: String, text: String) {}
    override fun advance(dismiss: Boolean) {}
    override fun revive(id: String, text: String) {}
    override fun forget(id: String) {}
    override fun setPageSize(size: Int) {}
    override fun setShowHints(show: Boolean) {}
    override fun setTheme(mode: ThemeMode) {}
    override fun setPureBlack(on: Boolean) {}
    override fun setDynamicColor(on: Boolean) {}
    override fun setSystemBackup(on: Boolean) {}
    override fun switchList(id: String) {}
    override fun addList(name: String) {}
    override fun renameList(id: String, name: String) {}
    override fun deleteList(id: String) {}
    override fun search(query: String, includeClosed: Boolean) = emptyList<SearchHit>()
    override fun doneSince(since: Long) = 2
    override fun exportJson() = ""
    override fun importJson(text: String) {}
}

private const val DAY = 86_400_000L
private val NOW = System.currentTimeMillis()

private fun StCore.idOf(text: String, fromIndex: Int = 50): String =
    snapshot().entries.drop(fromIndex).first { it.text == text && it.status == Status.ACTIVE }.id

/** Наполнить ядро: страницы 1–2 закрыты, 3-я рабочая (два дня записей), 4–7 впереди. */
private fun fill(core: StCore, tasks: List<String>, more: Int, done: List<String>, moved: String) {
    repeat(50) { core.add(tasks[it % tasks.size], NOW - 3 * DAY) }
    core.snapshot().entries.take(50).forEach { core.done(it.id, NOW - DAY) }
    tasks.forEachIndexed { i, t -> core.add(t, if (i < 8) NOW - DAY else NOW) }
    repeat(more) { core.add(tasks[(it * 7) % tasks.size], NOW) }
    core.goTo(2)
    core.done(core.idOf(done[0]), NOW)
    core.reenter(core.idOf(moved), NOW)
    core.done(core.idOf(done[1]), NOW)
}

/** Снова «первый проход» на рабочей странице: уйти и вернуться. */
private fun StCore.freshVisit(): Snapshot { goTo(3); return goTo(2) }

private val ruTasks = listOf(
    "Разобрать почту", "Позвонить в банк", "Купить продукты", "Отчёт по проекту X",
    "Записаться к стоматологу", "Прочитать главу книги", "Починить кран", "Ответить Маше",
    "Оплатить интернет", "Разобрать стол", "Спланировать отпуск", "Сделать бэкап телефона",
    "Выучить 10 слов", "Пробежка 20 минут", "Обновить резюме", "Полить цветы",
    "Сортировать фото", "Позвонить маме", "Корм и лоток для кота", "Отдать книги в библиотеку",
    "Записать идеи для статьи", "Проверить страховку", "Заказать фильтр для воды",
    "Посмотреть лекцию", "Сдать куртку в химчистку",
)

private fun ruCore() = StCore.inMemory(NOW).also {
    fill(it, ruTasks, 105, listOf("Разобрать почту", "Оплатить интернет"), "Отчёт по проекту X")
}

private fun enCore() = StCore.inMemory(NOW).also {
    fill(
        it,
        listOf(
            "Clear the inbox", "Call the bank", "Buy groceries", "Project X report", "Book the dentist",
            "Read a chapter", "Fix the tap", "Reply to Maria", "Pay the internet bill", "Tidy the desk",
            "Plan the holiday", "Back up the phone", "Learn 10 words", "20-minute run", "Update CV",
            "Water the plants", "Sort photos", "Call mum", "Feed the cat", "Return library books",
            "Draft article ideas", "Check insurance", "Order a water filter", "Watch a lecture", "Dry cleaning",
        ),
        60, listOf("Clear the inbox", "Pay the internet bill"), "Project X report",
    )
}

class ScreensSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ru"))

    private fun shot(
        dark: Boolean = false,
        locale: String = "ru",
        pureBlack: Boolean = false,
        dynamic: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_5.copy(locale = locale, nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT)
        )
        paparazzi.snapshot {
            StTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, pureBlack = pureBlack, dynamic = dynamic) { content() }
        }
    }

    /** Высокий «экран», чтобы длинные настройки попали в снимок целиком. */
    private fun shotTall(locale: String = "ru", content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(locale = locale, screenHeight = 3400))
        paparazzi.snapshot { StTheme(mode = ThemeMode.LIGHT) { content() } }
    }

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
            ) {}
        }
    }

    // ---------- главный экран ----------

    @Test fun main_page() = shot { MainScreen(ruCore().snapshot(), NoActions, onOpen = {}) }

    @Test fun main_firstPass() = shot { MainScreen(ruCore().freshVisit(), NoActions, onOpen = {}) }

    @Test fun main_selected() {
        val core = ruCore()
        shot { MainScreen(core.snapshot(), NoActions, onOpen = {}, initialSelected = core.idOf("Записаться к стоматологу")) }
    }

    @Test fun main_confirmDismiss() =
        shot { MainScreen(ruCore().freshVisit(), NoActions, onOpen = {}, initialConfirming = true) }

    @Test fun main_afterDismiss() {
        val core = ruCore()
        core.freshVisit()
        core.advance(true, NOW)
        shot { MainScreen(core.goTo(2), NoActions, onOpen = {}) }
    }

    @Test fun main_dark() {
        val core = ruCore()
        shot(dark = true) { MainScreen(core.snapshot(), NoActions, onOpen = {}, initialSelected = core.idOf("Записаться к стоматологу")) }
    }

    @Test fun main_jumpFromSearch() {
        val core = ruCore()
        val target = core.snapshot().entries.last { it.text == "Ответить Маше" }.id
        shot { MainScreen(core.snapshot(), NoActions, onOpen = {}, jumpTo = target) }
    }

    @Test fun main_multilingual() {
        val core = StCore.inMemory(NOW)
        listOf(
            "Call Zoë about the Ødegaard report", "Zażółć gęślą jaźń — list do Łukasza",
            "Příliš žluťoučký kůň", "Çalışma planını gözden geçir", "Größe der Straße prüfen",
            "Tiếng Việt: học từ mới", "Ґанок полагодити, Їжака нагодувати", "Қазақ тілі сабағы",
            "Œuvre complète — à lire", "Купить хлеб",
        ).forEach { core.add(it, NOW) }
        val s = core.snapshot()
        core.done(s.entries[1].id, NOW)
        core.reenter(s.entries[4].id, NOW)
        shot { MainScreen(core.snapshot(), NoActions, onOpen = {}) }
    }

    @Test fun firstRun() = shot { MainScreen(StCore.inMemory(NOW).snapshot(), NoActions, onOpen = {}) }

    // ---------- оформление ----------

    @Test fun theme_amoled() {
        val core = ruCore()
        shot(dark = true, pureBlack = true) {
            MainScreen(core.snapshot(), NoActions, onOpen = {}, initialSelected = core.idOf("Записаться к стоматологу"))
        }
    }

    @Test fun theme_monet_light() {
        val core = ruCore()
        shot(dynamic = true) { MainScreen(core.snapshot(), NoActions, onOpen = {}, initialSelected = core.idOf("Записаться к стоматологу")) }
    }

    @Test fun theme_monet_dark() {
        val core = ruCore()
        shot(dark = true, dynamic = true) {
            MainScreen(core.snapshot(), NoActions, onOpen = {}, initialSelected = core.idOf("Записаться к стоматологу"))
        }
    }

    // ---------- другие экраны ----------

    @Test fun dismissed() {
        val core = ruCore()
        core.freshVisit()
        shot { DismissedScreen(core.advance(true, NOW), NoActions, onBack = {}) }
    }

    @Test fun settings() = shotTall {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            SettingsScreen(ruCore().snapshot(), Settings(true, ThemeMode.SYSTEM, true, false, false, 0), NoActions, onBack = {})
        }
    }

    @Test fun search() {
        val core = ruCore()
        shot { SearchScreen(core.snapshot(), { q, c -> core.search(q, c) }, onBack = {}, onOpenEntry = {}, initialQuery = "отчет") }
    }

    @Test fun share() = shot {
        ShareSheet(
            "Список покупок:\n- молоко\n- хлеб\n- кофе", onCancel = {}, onAdd = { _, _ -> },
            lists = listOf("1" to "Дом", "2" to "Работа"), initialList = "1",
        )
    }

    private fun twoLists(): Snapshot {
        val core = ruCore()
        val home = core.snapshot().currentId
        core.renameList(home, "Дом", NOW)
        core.addList("Работа", NOW)
        listOf("Отчёт за квартал", "Созвон с командой", "Обновить договор").forEach { core.add(it, NOW) }
        return core.switchList(home)
    }

    @Test fun lists() = shot { ListsScreen(twoLists(), NoActions, onBack = {}, onPicked = {}) }

    @Test fun main_twoLists_break() = shot {
        MainScreen(ruCore().snapshot(), NoActions, onOpen = {}, listName = "Дом", breakDays = 5)
    }

    @Test fun review() = shot { ReviewScreen(ruCore().snapshot(), onBack = {}, onOpenEntry = {}) }

    @Test fun about() = shot { AboutScreen(onBack = {}) }

    // ---------- синхронизация ----------

    private val peers = listOf(
        KnownPeer("pc", "MacBook Air", "k1", NOW - 3_600_000),
        KnownPeer("tab", "Pixel Tablet", "k2", NOW - 4 * DAY),
    )

    @Test fun sync_home() = shot { SyncHomeView(peers, {}, {}, {}, {}, {}, onBack = {}) }

    @Test fun sync_qr() = shot {
        SyncQrView(
            "stranitsa:1?h=192.168.1.23&p=48213&i=3f2c9a1e-0000-4000-8000-000000000000&k=7Qy6m2CqQ0Zfh3a4wqKfN1hU0fF1q7wH3r9cZp2Yx1A&s=U2VjcmV0U2VjcmV0U2VjcmV0U2VjcmV0U2VjcmV0MDE&n=UGl4ZWwgOA",
            "Pixel 8", onBack = {},
        )
    }

    @Test fun sync_nearby() = shot {
        SyncNearbyView(
            listOf(NearbyPeer("pc", "MacBook Air", listOf("192.168.1.10"), 5000, true), NearbyPeer("x", "DESKTOP-7K2", listOf("192.168.1.11"), 5001, false)),
            onPick = {}, onBack = {},
        )
    }

    @Test fun sync_confirm() = shot { SyncConfirmView("DESKTOP-7K2", "482913", waiting = false, onYes = {}, onNo = {}, onBack = {}) }

    @Test fun sync_qr_dark() = shot(dark = true) { SyncQrView("stranitsa:1?h=10.0.0.5&p=1&i=a&k=b&s=c&n=ZA", "Pixel 8", onBack = {}) }

    @Test fun en_sync_home() = shot(locale = "en") { SyncHomeView(peers, {}, {}, {}, {}, {}, onBack = {}) }

    @Test fun en_sync_confirm() = shot(locale = "en") { SyncConfirmView("DESKTOP-7K2", "482913", waiting = false, onYes = {}, onNo = {}, onBack = {}) }

    // ---------- English ----------

    @Test fun en_main_selected() {
        val core = enCore()
        shot(locale = "en") { MainScreen(core.snapshot(), NoActions, onOpen = {}, initialSelected = core.idOf("Book the dentist")) }
    }

    @Test fun en_confirmDismiss() =
        shot(locale = "en") { MainScreen(enCore().freshVisit(), NoActions, onOpen = {}, initialConfirming = true) }

    @Test fun en_firstRun() = shot(locale = "en") { MainScreen(StCore.inMemory(NOW).snapshot(), NoActions, onOpen = {}) }

    @Test fun en_dismissed() {
        val core = enCore()
        core.freshVisit()
        shot(locale = "en") { DismissedScreen(core.advance(true, NOW), NoActions, onBack = {}) }
    }

    @Test fun en_search() {
        val core = enCore()
        shot(locale = "en") { SearchScreen(core.snapshot(), { q, c -> core.search(q, c) }, onBack = {}, onOpenEntry = {}, initialQuery = "the") }
    }

    @Test fun en_share() = shot(locale = "en") {
        ShareSheet("How to sleep better — https://example.com/sleep", onCancel = {}, onAdd = { _, _ -> })
    }

    @Test fun en_settings() = shotTall(locale = "en") {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            SettingsScreen(enCore().snapshot(), Settings(true, ThemeMode.DARK, false, true, false, 0), NoActions, onBack = {})
        }
    }

    @Test fun en_main_break() = shot(locale = "en") {
        MainScreen(enCore().snapshot(), NoActions, onOpen = {}, listName = "Home", breakDays = 1)
    }

    @Test fun en_about() = shot(locale = "en") { AboutScreen(onBack = {}) }
}

/** Крупный системный шрифт: 130 % и 200 % — главные экраны не должны ломаться. */
class FontScaleSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ru"))

    private fun shot(scale: Float, tall: Boolean = false, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_5.copy(locale = "ru", fontScale = scale, screenHeight = if (tall) 3400 else DeviceConfig.PIXEL_5.screenHeight)
        )
        paparazzi.snapshot { StTheme(mode = ThemeMode.LIGHT) { content() } }
    }

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
            ) {}
        }
    }

    private fun selected(): Pair<Snapshot, String> { val c = ruCore(); return c.snapshot() to c.idOf("Записаться к стоматологу") }

    @Test fun main_x130() { val (s, id) = selected(); shot(1.3f) { MainScreen(s, NoActions, onOpen = {}, initialSelected = id, listName = "Дом", breakDays = 5) } }
    @Test fun main_x200() { val (s, id) = selected(); shot(2.0f) { MainScreen(s, NoActions, onOpen = {}, initialSelected = id) } }
    /** Как на телефоне пользователя: одна страница, кнопка внизу неактивна («Других страниц нет»). */
    @Test fun onePage_x130() {
        val c = StCore.inMemory(NOW)
        ruTasks.take(13).forEach { c.add(it, NOW) }
        shot(1.3f) { MainScreen(c.snapshot(), NoActions, onOpen = {}) }
    }
    @Test fun confirm_x200() = shot(2.0f) { MainScreen(ruCore().freshVisit(), NoActions, onOpen = {}, initialConfirming = true) }
    @Test fun settings_x200() = shot(2.0f, tall = true) {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            SettingsScreen(ruCore().snapshot(), Settings(true, ThemeMode.SYSTEM, true, false, false, 0), NoActions, onBack = {})
        }
    }
    @Test fun share_x200() = shot(2.0f) {
        ShareSheet(
            "Список покупок:\n- молоко\n- хлеб\n- кофе", onCancel = {}, onAdd = { _, _ -> },
            lists = listOf("1" to "Дом", "2" to "Работа", "3" to "Дача"), initialList = "1",
        )
    }
}
