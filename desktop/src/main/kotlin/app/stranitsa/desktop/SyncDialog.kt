package app.stranitsa.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.stranitsa.core.NearbyPeer
import app.stranitsa.core.SyncBrowser
import app.stranitsa.core.SyncHost
import app.stranitsa.core.SyncSession
import app.stranitsa.core.qrMatrix
import app.stranitsa.core.syncConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private sealed interface Step {
    data object Host : Step
    data object Nearby : Step
    data class Confirm(val session: SyncSession, val name: String, val code: String) : Step
    data object Working : Step
    data class Result(val text: String) : Step
}

/**
 * Синхронизация без облака. Пока окно открыто, компьютер виден в сети и показывает QR;
 * телефон сканирует его. Либо «Найти рядом» — и сверка шестизначного кода.
 */
@Composable
fun SyncDialog(st: AppState) {
    val c = Ui.c
    val name = remember { deviceName() }
    var step by remember { mutableStateOf<Step>(Step.Host) }
    var host by remember { mutableStateOf<SyncHost?>(null) }
    var hostError by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf(st.core.syncPeers()) }
    val close = { st.dialog = Dialog.None }

    fun finish(s: SyncSession, ok: Boolean) {
        step = Step.Working
        Thread {
            val r = runCatching { s.finish(ok, System.currentTimeMillis()) }
            st.refresh(); peers = st.core.syncPeers()
            step = Step.Result(r.fold({ S.syncDone(it.peerName, it.newEntries) }, { S.syncFail(it.message ?: "") }))
        }.start()
    }

    fun incoming(s: SyncSession) {
        val p = s.peer()
        if (p.needsConfirmation) step = Step.Confirm(s, p.name, p.code) else finish(s, true)
    }

    // Видимость в сети — только пока открыт этот экран.
    DisposableEffect(Unit) {
        val h = runCatching { SyncHost.start(st.core, name, true) }.getOrNull()
        host = h; hostError = h == null
        onDispose { runCatching { h?.stop() } }
    }
    LaunchedEffect(host) {
        val h = host ?: return@LaunchedEffect
        // Ожидание — в фоновом потоке, обработка — снова в потоке интерфейса (контекст LaunchedEffect).
        while (isActive) {
            val s = withContext(Dispatchers.IO) { runCatching { h.accept(500) }.getOrNull() }
            if (s != null && (step == Step.Host || step == Step.Nearby)) incoming(s)
        }
    }

    Modal(close, width = 640.dp) {
        Txt(S.sync, Type.title)
        Spacer(h = 6.dp)
        when (val s = step) {
            Step.Host -> {
                Txt(S.syncLead, Type.body, c.muted)
                Row(Modifier.padding(top = 16.dp)) {
                    val qr = host?.qrText()
                    Box(Modifier.size(244.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(12.dp)) {
                        if (qr != null) {
                            val m = remember(qr) { qrMatrix(qr) }
                            Canvas(Modifier.size(220.dp)) {
                                val cell = size.width / m.size
                                for (y in 0 until m.size) for (x in 0 until m.size) if (m.cells[y * m.size + x])
                                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                            }
                        }
                    }
                    Spacer(w = 20.dp)
                    Column(Modifier.weight(1f)) {
                        Txt(S.syncScanHint, Type.ui.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                        Txt(S.syncNearbyHint(name), Type.body, c.muted, Modifier.padding(top = 6.dp))
                        Txt(if (hostError) S.syncStartFail else S.syncWaiting, Type.ui, c.ink, Modifier.padding(top = 12.dp))
                        if (peers.isNotEmpty()) {
                            Txt(S.syncKnown, Type.label, c.muted, Modifier.padding(top = 18.dp, bottom = 4.dp))
                            peers.forEach { p ->
                                Hairline()
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Txt(p.name, Type.ui)
                                        if (p.lastSync > 0) Txt(S.syncLast(S.dayTime(Instant.ofEpochMilli(p.lastSync).atZone(ZoneId.systemDefault()))), Type.meta, c.muted)
                                    }
                                    Btn(S.forget, { st.core.forgetPeer(p.deviceId); peers = st.core.syncPeers() })
                                }
                            }
                        }
                    }
                }
                DialogButtons {
                    Btn(S.syncNearby, { step = Step.Nearby }, Kind.Outline, height = 32.dp)
                    Btn(S.close, close, Kind.Filled, "Esc", height = 32.dp)
                }
            }
            Step.Nearby -> NearbyStep(st, name, onSession = { incoming(it) }, onError = { step = Step.Result(S.syncFail(it)) }, onBack = { step = Step.Host })
            is Step.Confirm -> {
                Txt(S.syncCodeLead(s.name), Type.body, c.muted)
                Txt(s.code.chunked(3).joinToString(" "), Type.code, c.ink, Modifier.padding(vertical = 18.dp))
                DialogButtons {
                    Btn(S.codeNo, { finish(s.session, false) }, Kind.Outline, height = 32.dp)
                    Btn(S.codeYes, { finish(s.session, true) }, Kind.Filled, height = 32.dp)
                }
            }
            Step.Working -> Txt(S.syncWaiting, Type.ui, c.muted, Modifier.padding(vertical = 16.dp))
            is Step.Result -> {
                Txt(s.text, Type.ui, c.ink, Modifier.padding(vertical = 12.dp))
                DialogButtons {
                    Btn(S.back, { step = Step.Host }, Kind.Outline, height = 32.dp)
                    Btn(S.close, close, Kind.Filled, "Esc", height = 32.dp)
                }
            }
        }
    }
}

@Composable
private fun NearbyStep(st: AppState, name: String, onSession: (SyncSession) -> Unit, onError: (String) -> Unit, onBack: () -> Unit) {
    val c = Ui.c
    var found by remember { mutableStateOf(emptyList<NearbyPeer>()) }
    var connecting by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val b = SyncBrowser.start(st.core)
        val t = Thread {
            while (!Thread.currentThread().isInterrupted) {
                found = runCatching { b.peers() }.getOrDefault(emptyList())
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        onDispose { t.interrupt(); runCatching { b.stop() } }
    }
    Txt(if (found.isEmpty()) S.syncSearching else S.syncNearby.trimEnd('…'), Type.body, c.muted)
    Column(Modifier.padding(top = 10.dp)) {
        found.forEach { p ->
            Hairline()
            HoverRow({
                if (!connecting) {
                    connecting = true
                    Thread {
                        runCatching { syncConnect(st.core, p, name) }
                            .onSuccess { onSession(it) }.onFailure { onError(it.message ?: "") }
                        connecting = false
                    }.start()
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(p.name, Type.ui, c.ink, Modifier.weight(1f))
                    if (p.known) Txt(S.syncKnownMark, Type.meta, c.muted, Modifier.padding(end = 10.dp))
                    GlyphIcon(Glyph.Next, c.muted, 16.dp)
                }
            }
        }
    }
    DialogButtons { Btn(S.back, onBack, Kind.Outline, "Esc", height = 32.dp) }
}
