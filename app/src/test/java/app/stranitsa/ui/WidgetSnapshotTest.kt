package app.stranitsa.ui

import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import app.stranitsa.StWidget
import app.stranitsa.core.StCore
import app.stranitsa.core.Settings
import app.stranitsa.core.Snapshot
import app.stranitsa.core.ThemeMode
import org.junit.Rule
import org.junit.Test

/** Вид виджета: светлая, тёмная, AMOLED, цвета обоев; высокий и низкий; пустой список. */
class WidgetSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ru"))

    private val now = 1_750_000_000_000L

    private fun core(): Snapshot {
        val c = StCore.inMemory(now)
        listOf(
            "Разобрать почту", "Позвонить в банк", "Купить продукты", "Отчёт по проекту X — собрать цифры за квартал и отправить Маше",
            "Записаться к стоматологу", "Прочитать главу книги", "Починить кран", "Ответить Маше", "Оплатить интернет",
        ).forEachIndexed { i, t -> c.add(t, now + i) }
        c.done(c.snapshot().entries[1].id, now + 20)
        return c.snapshot()
    }

    private fun shot(snap: Snapshot, night: Boolean, heightDp: Int, widthDp: Int = 340) {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_5.copy(locale = "ru", nightMode = if (night) NightMode.NIGHT else NightMode.NOTNIGHT)
        )
        val ctx = paparazzi.context
        val rv = StWidget.build(ctx, snap, widthDp, heightDp, night)
        val parent = FrameLayout(ctx)
        val v = rv.apply(ctx, parent)
        val d = ctx.resources.displayMetrics.density
        paparazzi.snapshot(
            FrameLayout(ctx).apply {
                setBackgroundColor(if (night) 0xFF2A2A2A.toInt() else 0xFFCFCFCF.toInt())
                setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                addView(v, FrameLayout.LayoutParams((widthDp * d).toInt(), (heightDp * d).toInt()))
            }
        )
    }

    private fun with(s: Snapshot, theme: ThemeMode, black: Boolean = false) =
        s.copy(settings = Settings(true, theme, black, false, false, 0))

    @Test fun light_tall() = shot(with(core(), ThemeMode.LIGHT), false, 170)
    @Test fun dark_tall() = shot(with(core(), ThemeMode.DARK), true, 170)
    @Test fun amoled_tall() = shot(with(core(), ThemeMode.DARK, black = true), true, 170)
    @Test fun light_short() = shot(with(core(), ThemeMode.LIGHT), false, 110)
    @Test fun small_light() = shot(with(core(), ThemeMode.LIGHT), false, 170, 170)
    @Test fun small_dark() = shot(with(core(), ThemeMode.DARK), true, 170, 170)
    @Test fun tall_4x4() = shot(with(core(), ThemeMode.LIGHT), false, 340)
    @Test fun empty() = shot(with(StCore.inMemory(now).snapshot(), ThemeMode.LIGHT), false, 110)
}
