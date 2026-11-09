package app.stranitsa

import android.content.Context
import android.net.wifi.WifiManager
import app.stranitsa.core.StCore
import app.stranitsa.core.SyncReport
import app.stranitsa.core.syncKnownNearby
import app.stranitsa.ui.deviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Автосинхронизация: найти знакомые устройства в этой сети (компьютер со «Страницей»,
 * у которого включена автосинхронизация) и обменяться без кодов. Незнакомые не трогаются.
 */
object AutoSync {
    suspend fun runOnce(ctx: Context, core: StCore): List<SyncReport> = withContext(Dispatchers.IO) {
        // Без этого «замка» Android отбрасывает пакеты mDNS, и устройства рядом не находятся.
        val lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("stranitsa-autosync")?.apply { setReferenceCounted(false); acquire() }
        try {
            syncKnownNearby(core, deviceName(ctx), 3000)
        } finally {
            runCatching { lock?.release() }
        }
    }
}
