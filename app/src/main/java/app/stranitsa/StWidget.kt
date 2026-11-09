package app.stranitsa

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.stranitsa.ui.AmoledColors
import app.stranitsa.ui.StColors
import app.stranitsa.ui.DarkColors
import app.stranitsa.ui.LightColors
import app.stranitsa.ui.dynamicColors
import app.stranitsa.ui.pad2
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.stranitsa.ui.pageEntries
import app.stranitsa.ui.supportsDynamicColor
import app.stranitsa.core.Snapshot
import app.stranitsa.core.Status
import app.stranitsa.core.ThemeMode

/**
 * Виджет на главном экране: рабочая страница текущего списка (только активные строки).
 * Читать и работать со списком по-прежнему нужно в приложении — виджет ничего не оценивает и не
 * переставляет; он напоминает, где вы остановились, и даёт записать мысль в конец списка («+»).
 * Цвета берутся из настроек темы приложения: светлая, тёмная, AMOLED, цвета обоев (Monet).
 */
class StWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snap = context.repository.state.value
        ids.forEach { render(context, manager, it, snap) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(context, manager, id, context.repository.state.value)
    }

    companion object {
        const val ACTION_QUICK_ADD = "app.stranitsa.QUICK_ADD"
        private const val MAX_ROWS = 8
        private const val ROW_DP = 30
        private const val MORE_DP = 22

        /** Перерисовать все виджеты (вызывается при любом изменении данных или настроек). */
        fun updateAll(context: Context, snap: Snapshot) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StWidget::class.java))
            ids.forEach { render(context, manager, it, snap) }
        }

        private fun palette(ctx: Context, snap: Snapshot, dark: Boolean): StColors {
            val s = snap.settings
            return when {
                s.dynamicColor && supportsDynamicColor -> dynamicColors(ctx, dark, s.pureBlack && dark)
                dark && s.pureBlack -> AmoledColors
                dark -> DarkColors
                else -> LightColors
            }
        }

        private fun render(ctx: Context, manager: AppWidgetManager, id: Int, snap: Snapshot) {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            // Размер виджета: в портрете — MIN_WIDTH × MAX_HEIGHT, в альбомной ориентации — наоборот.
            val o = manager.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
            val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val rv = build(ctx, snap, w, h, night)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
            val add = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, ShareActivity::class.java).setAction(ACTION_QUICK_ADD), flags,
            )
            rv.setOnClickPendingIntent(R.id.root, open)
            if (w >= SMALL_MAX_WIDTH) rv.setOnClickPendingIntent(R.id.body, open)
            rv.setOnClickPendingIntent(R.id.add, add)
            manager.updateAppWidget(id, rv)
        }

        /** Уже́ этого (dp) — компактный вид 2×2: только номер страницы и «+». */
        internal const val SMALL_MAX_WIDTH = 220

        /** Содержимое виджета без нажатий. [night] — что показать на устройствах до Android 14. */
        internal fun build(ctx: Context, snap: Snapshot, widthDp: Int, heightDp: Int, night: Boolean): RemoteViews {
            val (lightDark, darkDark) = when (snap.settings.theme) {
                ThemeMode.LIGHT -> false to false
                ThemeMode.DARK -> true to true
                ThemeMode.SYSTEM -> false to true
            }
            val lc = palette(ctx, snap, lightDark)
            val dc = palette(ctx, snap, darkDark)
            val small = widthDp < SMALL_MAX_WIDTH
            val rv = RemoteViews(ctx.packageName, if (small) R.layout.widget_small else R.layout.widget)

            // Цвет в светлой и тёмной теме сразу (Android 14+ переключает сам, без обновления виджета).
            fun color(view: Int, method: String, pick: (StColors) -> Color) {
                val l = pick(lc).toArgb()
                val d = pick(dc).toArgb()
                if (Build.VERSION.SDK_INT >= 34) rv.setColorInt(view, method, l, d)
                else rv.setInt(view, method, if (night) d else l)
            }
            color(R.id.bg, "setColorFilter") { it.widgetBg() }
            color(R.id.addBg, "setColorFilter") { it.accent }
            color(R.id.addIcon, "setColorFilter") { it.onAccent }

            val page = snap.currentPage
            val total = pad2(snap.lastPage + 1)
            val name = snap.name.ifBlank { ctx.getString(R.string.list_default) }
            rv.setTextViewText(R.id.pageNo, pad2(page + 1))
            color(R.id.pageNo, "setTextColor") { it.ink }
            color(R.id.pageOf, "setTextColor") { it.muted }

            if (small) {
                rv.setTextViewText(R.id.listName, ctx.getString(R.string.widget_small_label, name))
                rv.setTextViewText(R.id.pageOf, ctx.getString(R.string.widget_small_meta, total, snap.activeTotal))
                color(R.id.listName, "setTextColor") { it.muted }
                return rv
            }

            rv.setTextViewText(R.id.pageOf, ctx.getString(R.string.widget_header, total, name))
            color(R.id.more, "setTextColor") { it.muted }
            color(R.id.empty, "setTextColor") { it.muted }

            val avail = heightDp - 66 // минус шапка и отступы
            val from = page * snap.pageSize
            val active = snap.entries.drop(from).take(snap.pageSize).withIndex()
                .filter { it.value.status == Status.ACTIVE }
                .map { (from + it.index + 1) to it.value.text }
            val numIds = intArrayOf(R.id.num1, R.id.num2, R.id.num3, R.id.num4, R.id.num5, R.id.num6, R.id.num7, R.id.num8)
            val txtIds = intArrayOf(R.id.txt1, R.id.txt2, R.id.txt3, R.id.txt4, R.id.txt5, R.id.txt6, R.id.txt7, R.id.txt8)
            val rowIds = intArrayOf(R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6, R.id.row7, R.id.row8)
            // Если не всё помещается — последнюю строку отдаём под «ещё N»; строки не режутся посередине.
            val fitAll = minOf(avail / ROW_DP, MAX_ROWS)
            val showMore = active.size > fitAll
            val shown = if (showMore) ((avail - MORE_DP) / ROW_DP).coerceIn(0, MAX_ROWS) else active.size
            for (i in 0 until MAX_ROWS) {
                val visible = i < shown && i < active.size
                rv.setViewVisibility(rowIds[i], if (visible) View.VISIBLE else View.GONE)
                if (visible) {
                    rv.setTextViewText(numIds[i], active[i].first.toString())
                    rv.setTextViewText(txtIds[i], active[i].second.replace('\n', ' '))
                    color(numIds[i], "setTextColor") { it.muted }
                    color(txtIds[i], "setTextColor") { it.ink }
                }
            }
            rv.setViewVisibility(R.id.more, if (showMore) View.VISIBLE else View.GONE)
            if (showMore) rv.setTextViewText(R.id.more, ctx.getString(R.string.widget_more, active.size - shown))
            val empty = active.isEmpty()
            rv.setViewVisibility(R.id.empty, if (empty) View.VISIBLE else View.GONE)
            if (empty) {
                rv.setTextViewText(R.id.empty, ctx.getString(if (snap.entries.isEmpty()) R.string.widget_empty_new else R.string.widget_empty_page))
            }
            return rv
        }

        /** Подложка виджета. В обычной тёмной теме чуть светлее фона приложения — иначе сливается с тёмными обоями. */
        private fun StColors.widgetBg(): Color = if (this == DarkColors) Color(0xFF1B1B18) else bg
    }
}
