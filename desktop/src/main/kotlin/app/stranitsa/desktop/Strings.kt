package app.stranitsa.desktop

import java.util.Locale

/** Строки интерфейса: русский и английский, по языку системы. */
class Str(private val ru: Boolean) {
    private fun t(r: String, e: String) = if (ru) r else e

    val appName = t("Страница", "Stranitsa")
    val lists = t("Списки", "Lists")
    val defaultList = t("Мой список", "My list")
    val newList = t("Новый список", "New list")
    val dismissed = t("Отброшенные", "Dismissed")
    val sync = t("Синхронизация", "Sync")
    val settings = t("Настройки", "Settings")
    val search = t("Поиск", "Search")
    val newTask = t("Новая задача", "New task")
    val firstTask = t("Первая задача", "First task")
    fun pageOf(n: String) = t("из $n", "of $n")
    fun stats(active: Int, today: Int) = t("активных $active · сделано сегодня $today", "active $active · done today $today")
    val hintFirst = t("Пробегите страницу, затем читайте медленно, пока что-то не выделится.", "Skim the page, then read slowly until something stands out.")
    val hintCircle = t("Кружите по странице. Пустой проход: дальше.", "Circle the page. An empty pass: move on.")
    val hintClosed = t("На этой странице всё закрыто.", "Everything on this page is closed.")
    fun hintViewing(p: String, w: String) = t("Вы смотрите страницу $p. Рабочая: $w.", "You are viewing page $p. Working page: $w.")
    fun breakBanner(days: Int) = t(
        "Вы не открывали список ${plRu(days, "день", "дня", "дней")}. Форстер советует сначала прочитать все невыполненные задачи.",
        "You haven’t opened the list for $days ${if (days == 1) "day" else "days"}. Forster suggests reading all open tasks first.",
    )
    val readAll = t("Прочитать всё", "Read all")
    val later = t("Не сейчас", "Not now")
    val done = t("Сделано", "Done")
    val notFinished = t("Не закончил", "Not finished")
    val edit = t("Изменить", "Edit")
    val delete = t("Удалить", "Delete")
    val save = t("Сохранить", "Save")
    val cancel = t("Отмена", "Cancel")
    val close = t("Закрыть", "Close")
    val undo = t("Отменить", "Undo")
    val toEnd = t("→ в конец", "→ to end")
    val nextPassed = t("Пройдена, дальше", "Page done, next")
    val nextNothing = t("Ничего не выделилось", "Nothing stood out")
    val nextPlain = t("Следующая страница", "Next page")
    fun nextBack(p: String) = t("К странице $p", "Back to page $p")
    val noOther = t("Других страниц нет", "No other pages")
    val allClosed = t("Все задачи закрыты", "All tasks are closed")
    val prevPage = t("Предыдущая страница", "Previous page")
    val nextPage = t("Следующая страница", "Next page")
    val confirmTitle = t("Ничего не выделилось?", "Nothing stood out?")
    fun confirmBody(n: Int, p: String) = t(
        "Отбросить ${plRu(n, "задачу", "задачи", "задач")} страницы $p. Сами они не вернутся; их можно просмотреть в «Отброшенных».",
        "Dismiss $n ${if (n == 1) "task" else "tasks"} on page $p. They won’t come back on their own; you can review them under “Dismissed”.",
    )
    fun dismissN(n: Int) = t("Отбросить $n", "Dismiss $n")
    val keep = t("Не отбрасывать", "Keep")
    fun barDone(s: String) = t("Сделано: $s", "Done: $s")
    fun barReentered(s: String) = t("В конец списка: $s", "Moved to the end: $s")
    fun barDeleted(s: String) = t("Удалено: $s", "Deleted: $s")
    fun barAdded(p: String) = t("Записано в конец · стр. $p", "Added to the end · p. $p")
    fun barDismissed(n: Int) = t("Отброшено: $n", "Dismissed: $n")
    fun found(n: Int) = t("Найдено $n", "Found $n")
    val nothingFound = t("Ничего не найдено", "Nothing found")
    fun showClosed(n: Int) = t("показать закрытые ($n)", "show closed ($n)")
    val hideClosed = t("скрыть закрытые", "hide closed")
    fun where(p: String, line: Int) = t("стр. $p · строка $line", "p. $p · line $line")
    val enterOpens = t("Enter: открыть страницу задачи", "Enter: open the task’s page")
    val dismissedLead = t("Система отсеяла эти задачи. Верните в конец списка то, что ещё нужно; можно переформулировать.", "The system filtered these out. Return what still matters to the end of the list; you may reword it.")
    val dismissedEmpty = t("Разбирать нечего.", "Nothing to review.")
    val revive = t("Вернуть в конец", "Return to end")
    val forget = t("Забыть", "Forget")
    val reviewTitle = t("Все невыполненные", "All open tasks")
    val reviewLead = t("Просто прочитайте: делать ничего не нужно. Нажмите на задачу, чтобы открыть её страницу.", "Just read: no action needed. Click a task to open its page.")
    fun pageN(p: String) = t("Страница $p", "Page $p")
    val back = t("Назад", "Back")
    // настройки
    val linesPerPage = t("Строк на странице", "Lines per page")
    val linesHint = t("Форстер советует 25–35", "Forster suggests 25–35")
    val hints = t("Подсказки на странице", "Hints on the page")
    val theme = t("Тема", "Theme")
    val themeSystem = t("Как в системе", "System")
    val themeLight = t("Светлая", "Light")
    val themeDark = t("Тёмная", "Dark")
    val pureBlack = t("Чёрный фон в тёмной теме", "Pure black in dark theme")
    val dataFile = t("Файл с данными", "Data file")
    val openFolder = t("Открыть папку", "Open folder")
    val exportCopy = t("Сохранить копию в файл…", "Save a copy to a file…")
    val importCopy = t("Восстановить из файла…", "Restore from a file…")
    val importConfirm = t("Заменить все списки содержимым файла?", "Replace all lists with the file’s contents?")
    val replace = t("Заменить", "Replace")
    val exportOk = t("Копия сохранена.", "Copy saved.")
    val importOk = t("Восстановлено.", "Restored.")
    val importBad = t("Файл не похож на копию Страницы.", "This file doesn’t look like a Stranitsa copy.")
    val ok = t("Готово", "Done")
    // списки
    val rename = t("Переименовать", "Rename")
    fun deleteListQ(n: String) = t("Удалить «$n» со всеми задачами?", "Delete “$n” with all its tasks?")
    // синхронизация
    val syncLead = t("Телефон и этот компьютер в одной сети обмениваются списками напрямую, без облака. Всё шифруется.", "Your phone and this computer on the same network exchange lists directly, without a cloud. Everything is encrypted.")
    val syncScanHint = t("На телефоне: Меню → Синхронизация → Сканировать QR-код", "On the phone: Menu → Sync → Scan QR code")
    fun syncNearbyHint(name: String) = t("Или выберите «$name» в «Найти рядом» и сверьте шестизначный код.", "Or pick “$name” in “Find nearby” and compare the six-digit code.")
    val syncWaiting = t("Ждём подключения…", "Waiting for a device…")
    val syncKnown = t("Знакомые устройства", "Known devices")
    fun syncLast(d: String) = t("последняя синхронизация $d", "last synced $d")
    val syncNearby = t("Найти рядом…", "Find nearby…")
    val syncSearching = t("Ищем устройства с открытым экраном синхронизации…", "Looking for devices with the sync screen open…")
    val syncKnownMark = t("знакомое", "known")
    val syncCodeTitle = t("Сверьте код", "Compare the code")
    fun syncCodeLead(n: String) = t("«$n» хочет синхронизироваться. Убедитесь, что на обоих устройствах один и тот же код.", "“$n” wants to sync. Make sure both devices show the same code.")
    val codeYes = t("Коды совпадают", "Codes match")
    val codeNo = t("Не совпадают", "They don’t match")
    fun syncDone(n: String, k: Int) = t("Синхронизировано с «$n». Новых строк: $k.", "Synced with “$n”. New lines: $k.")
    fun syncFail(e: String) = t("Не получилось: $e", "Didn’t work: $e")
    val syncStartFail = t("Не удалось открыть сетевой порт.", "Couldn’t open a network port.")
    val syncNowMenu = t("Синхронизировать сейчас", "Sync now")
    val autoSync = t("Автосинхронизация", "Automatic sync")
    val autoSyncHint = t("Пока программа запущена, знакомые устройства в этой сети синхронизируются сами. Новое устройство сначала подключите через «Синхронизацию».", "While the app is running, known devices on this network sync by themselves. Pair a new device once via “Sync”.")
    val off = t("Выкл", "Off")
    fun minutes(n: Int) = t("$n мин", "$n min")
    fun lastSync(time: String, who: String) = t("Последняя: $time · $who", "Last: $time · $who")
    val checkUpdates = t("Проверять обновления", "Check for updates")
    val checkUpdatesHint = t("При запуске спрашивать GitHub, не вышла ли новая версия", "Ask GitHub at startup whether a new version is out")
    fun updateAvailable(v: String) = t("Доступна версия $v", "Version $v is available")
    val updateDo = t("Обновить", "Update")
    val updateOpenPage = t("Открыть страницу", "Open page")
    fun updateDownloading(v: String) = t("Загрузка версии $v…", "Downloading version $v…")
    fun updateReady(v: String) = t("Версия $v скачана и проверена.", "Version $v is downloaded and checked.")
    val updateInstall = t("Установить", "Install")
    val updateInstallWin = t("Установить и закрыть программу", "Install and close the app")
    fun updateFailed(m: String) = t("Не удалось скачать обновление: $m", "Could not download the update: $m")
    val retry = t("Повторить", "Try again")
    val updateNoOpen = t("Не удалось открыть установщик. Открыта папка с файлом: запустите его вручную.", "Could not open the installer. The folder with the file is open: run it yourself.")
    fun cleanupUndoBar(n: Int) = t("Убрано строк: $n", "Removed lines: $n")
    val cleanup = t("Очистка", "Cleanup")
    val cleanupHint = t("Выполненные, перенесённые и удалённые строки только зачёркиваются. Здесь можно убрать старые; нумерация страниц сдвинется. Невыполненные не трогаются. Можно отменить.", "Done, moved and deleted lines are only crossed out. This removes the old ones; page numbers shift. Open tasks are never touched. Can be undone.")
    fun days(n: Int) = t("$n дн.", "$n d")
    fun cleanupCount(n: Int) = t("Старше этого срока: $n", "Older than that: $n")
    fun cleanupConfirm(n: Int) = t("Убрать закрытых строк: $n? Нумерация страниц сдвинется.", "Remove $n closed lines? Page numbers will shift.")
    fun cleanupDone(n: Int) = t("Убрано строк: $n", "Removed lines: $n")
    val remove = t("Убрать", "Remove")
    // клавиши, справка
    val keys = t("Клавиши", "Keyboard shortcuts")
    val howTo = t("Как работает метод", "How the method works")
    val about = t("О программе", "About")
    fun aboutText(v: String) = t("Страница $v для компьютера. Метод Autofocus 1 Марка Форстера; приложение неофициальное и с автором метода не связано. Шрифты: Source Serif 4, IBM Plex Sans (SIL OFL).", "Stranitsa $v for desktop. Based on Mark Forster’s Autofocus 1 method; unofficial and not affiliated with the author. Fonts: Source Serif 4, IBM Plex Sans (SIL OFL).")
    val report = t("Сообщить об ошибке", "Report a problem")
    val reportLead = t(
        "В отчёте версия, система, настройки, число списков и строк и технический журнал. Ваших задач, названий списков и имён устройств в нём нет. Прочитайте его перед отправкой. Для отправки на GitHub нужен аккаунт GitHub.",
        "The report has the app version, system, settings, counts of lists and lines, and a technical log. It does not have your tasks, list names or device names. Read it before sending. Sending on GitHub needs a GitHub account.",
    )
    val reportOpen = t("Открыть на GitHub", "Open on GitHub")
    val copy = t("Копировать", "Copy")
    val reportCopied = t("Отчёт скопирован.", "Report copied.")
    fun reportSaved(f: String) = t("Сохранено: $f", "Saved: $f")
    val reportPaste = t("Отчёт слишком длинный для ссылки, поэтому он скопирован: вставьте его в текст обращения.", "The report is too long for a link, so it is copied: paste it into the issue text.")
    val reportIntro = t("**Что случилось?**\n\n(Опишите, что вы делали и что пошло не так.)", "**What happened?**\n\n(Describe what you were doing and what went wrong.)")
    val crashBanner = t("В прошлый раз программа закрылась с ошибкой. Отправить отчёт?", "The app closed with an error last time. Send a report?")
    val crashFatal = t("В программе произошла ошибка, и она закроется. Данные сохранены. При следующем запуске можно будет отправить отчёт.", "The app hit an error and will close. Your data is saved. Next time you start it you can send a report.")
    val reportFile = "stranitsa-report.txt"
    val rules = listOf(
        t("Один длинный список всего, что нужно сделать. Новое всегда в конец.", "One long list of everything to do. New items always go at the end."),
        t("Работайте постранично. Сначала быстро прочитайте страницу, ничего не делая.", "Work page by page. First read the page quickly without acting."),
        t("Затем читайте медленно, пока одна задача не «выделится». Не расставляйте приоритеты в уме.", "Then read slowly until one task stands out. Don’t prioritise in your head."),
        t("Работайте над ней сколько хочется. Закончили: «Сделано». Нет: «Не закончил», задача уйдёт в конец.", "Work on it as long as you like. Finished: “Done”. Not finished: it goes to the end."),
        t("Если на первом проходе ничего не выделилось, страница отбрасывается (кроме последней).", "If nothing stands out on the first pass, the page is dismissed (except the last one)."),
        t("Дела к точному времени держите в календаре, а не здесь.", "Keep fixed-time items in a calendar, not here."),
    )
    // меню
    val menuFile = t("Файл", "File"); val menuEdit = t("Правка", "Edit"); val menuView = t("Вид", "View"); val menuHelp = t("Справка", "Help")
    val sidebar = t("Боковая панель", "Sidebar")
    val quit = t("Выход", "Quit")
    val keyRows = listOf(
        t("Выбрать задачу", "Select a task") to "↑ ↓",
        done to "Ctrl+Enter",
        t("Не закончил: в конец списка", "Not finished: to the end") to "Ctrl+→",
        edit to "F2",
        t("Удалить ошибочную", "Delete a mistaken one") to "Delete",
        newTask to "Ctrl+N",
        t("Страница пройдена, дальше", "Page done, next") to "Ctrl+Shift+→",
        t("Листать страницы", "Browse pages") to "PgUp  PgDn",
        search to "Ctrl+F",
        undo to "Ctrl+Z",
        sidebar to "F9",
        settings to "Ctrl+,",
        keys to "F1",
        quit to "Ctrl+Q",
    )

    private fun plRu(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val w = when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
        return "$n $w"
    }
}

val S = Str(Locale.getDefault().language == "ru")
