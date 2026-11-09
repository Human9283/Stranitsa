package app.stranitsa.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stranitsa.R
import app.stranitsa.core.StCore
import app.stranitsa.core.KnownPeer
import app.stranitsa.core.NearbyPeer
import app.stranitsa.core.SyncBrowser
import app.stranitsa.core.SyncException
import app.stranitsa.core.SyncHost
import app.stranitsa.core.SyncSession
import app.stranitsa.core.decodeQr
import app.stranitsa.core.qrMatrix
import app.stranitsa.core.syncConnect
import app.stranitsa.core.syncConnectQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Шаги экрана синхронизации. */
sealed interface SyncStep {
    data object Home : SyncStep
    data class Host(val qr: String, val name: String) : SyncStep
    data object Scan : SyncStep
    data class Nearby(val peers: List<NearbyPeer>) : SyncStep
    data class Connecting(val name: String) : SyncStep
    data class Confirm(val name: String, val code: String, val waiting: Boolean) : SyncStep
    data class Done(val name: String, val newEntries: Int) : SyncStep
    data class Failed(val message: String) : SyncStep
}

/** Имя этого устройства для другой стороны: имя из настроек телефона, иначе модель. */
fun deviceName(ctx: Context): String =
    android.provider.Settings.Global.getString(ctx.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

/**
 * Синхронизация без облака: показать QR, сканировать QR, найти рядом, знакомые устройства.
 * Сеть — в ядре; здесь только сценарий и экраны. Все сетевые вызовы — в фоновом потоке.
 */
@Composable
fun SyncScreen(core: StCore, onSynced: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val res = ctx.resources
    val scope = rememberCoroutineScope()
    val myName = remember { deviceName(ctx) }
    var step by remember { mutableStateOf<SyncStep>(SyncStep.Home) }
    var peers by remember { mutableStateOf(core.syncPeers()) }
    var host by remember { mutableStateOf<SyncHost?>(null) }
    var browser by remember { mutableStateOf<SyncBrowser?>(null) }
    var pending by remember { mutableStateOf<SyncSession?>(null) }
    // mDNS на Android работает только с «multicast lock» — берём его, пока экран ищет или виден.
    val multicast = remember {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("stranitsa-sync")?.apply { setReferenceCounted(false) }
    }

    fun stopAll() {
        host?.stop(); host = null
        browser?.stop(); browser = null
        pending = null
        runCatching { multicast?.release() }
    }

    DisposableEffect(Unit) { onDispose { stopAll() } }

    fun message(e: Throwable): String = when (e) {
        is SyncException.Rejected -> res.getString(R.string.sync_err_rejected)
        is SyncException.Unreachable -> res.getString(R.string.sync_err_unreachable)
        is SyncException.BadQr -> res.getString(R.string.sync_err_qr)
        else -> res.getString(R.string.sync_err_other, e.message ?: e.javaClass.simpleName)
    }

    fun finish(s: SyncSession, ok: Boolean) {
        val p = s.peer()
        step = if (p.needsConfirmation) SyncStep.Confirm(p.name, p.code, waiting = true) else SyncStep.Connecting(p.name)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { s.finish(ok, System.currentTimeMillis()) } }
            stopAll()
            r.onSuccess {
                onSynced()
                peers = core.syncPeers()
                step = SyncStep.Done(it.peerName, it.newEntries)
            }.onFailure { step = SyncStep.Failed(message(it)) }
        }
    }

    /** Соединение установлено: сверка кода или сразу обмен. */
    fun handle(s: SyncSession) {
        val p = s.peer()
        if (p.needsConfirmation) {
            pending = s
            step = SyncStep.Confirm(p.name, p.code, waiting = false)
        } else {
            finish(s, true)
        }
    }

    fun connectWith(name: String, connect: () -> SyncSession) {
        step = SyncStep.Connecting(name)
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(connect) }
                .onSuccess { handle(it) }
                .onFailure { stopAll(); step = SyncStep.Failed(message(it)) }
        }
    }

    fun startHost() {
        stopAll()
        runCatching { multicast?.acquire() }
        scope.launch {
            val h = withContext(Dispatchers.IO) { runCatching { SyncHost.start(core, myName, true) } }
                .getOrElse { step = SyncStep.Failed(message(it)); return@launch }
            host = h
            step = SyncStep.Host(h.qrText(), myName)
            while (isActive && host === h) {
                val s = withContext(Dispatchers.IO) { runCatching { h.accept(700) } }.getOrNull() ?: continue
                handle(s)
                break
            }
        }
    }

    fun startNearby(target: KnownPeer? = null) {
        stopAll()
        runCatching { multicast?.acquire() }
        val b = SyncBrowser.start(core)
        browser = b
        step = if (target == null) SyncStep.Nearby(emptyList()) else SyncStep.Connecting(target.name)
        scope.launch {
            val started = System.currentTimeMillis()
            while (isActive && browser === b) {
                val found = withContext(Dispatchers.IO) { b.peers() }
                if (target != null) {
                    val p = found.firstOrNull { it.deviceId == target.deviceId }
                    if (p != null) {
                        connectWith(p.name) { syncConnect(core, p, myName) }
                        return@launch
                    }
                    if (System.currentTimeMillis() - started > 10_000) {
                        stopAll()
                        step = SyncStep.Failed(res.getString(R.string.sync_not_found, target.name))
                        return@launch
                    }
                } else if (step is SyncStep.Nearby) {
                    step = SyncStep.Nearby(found)
                }
                delay(700)
            }
        }
    }

    val home = { stopAll(); step = SyncStep.Home }
    BackHandler(enabled = step != SyncStep.Home) {
        pending?.let { s -> scope.launch(Dispatchers.IO) { runCatching { s.finish(false, 0) } } }
        home()
    }

    when (val s = step) {
        SyncStep.Home -> SyncHomeView(
            peers = peers,
            onShowQr = ::startHost,
            onScan = { stopAll(); step = SyncStep.Scan },
            onNearby = { startNearby() },
            onSyncPeer = { startNearby(it) },
            onForget = { core.forgetPeer(it.deviceId); peers = core.syncPeers() },
            onBack = onBack,
        )
        is SyncStep.Host -> SyncQrView(s.qr, s.name, onBack = home)
        SyncStep.Scan -> SyncScanView(
            onCode = { text -> connectWith("") { syncConnectQr(core, text, myName) } },
            onBack = home,
        )
        is SyncStep.Nearby -> SyncNearbyView(s.peers, onPick = { p ->
            connectWith(p.name) { syncConnect(core, p, myName) }
        }, onBack = home)
        is SyncStep.Connecting -> SyncProgressView(
            if (s.name.isBlank()) stringResource(R.string.sync_waiting) else stringResource(R.string.sync_connecting, s.name),
            onBack = home,
        )
        is SyncStep.Confirm -> SyncConfirmView(
            s.name, s.code, s.waiting,
            onYes = { pending?.let { finish(it, true) } },
            onNo = { pending?.let { finish(it, false) } },
            onBack = home,
        )
        is SyncStep.Done -> SyncResultView(
            stringResource(R.string.sync_done_title),
            res.getQuantityString(R.plurals.sync_done, s.newEntries, s.newEntries, s.name),
            onDone = home,
        )
        is SyncStep.Failed -> SyncResultView(stringResource(R.string.sync_failed_title), s.message, onDone = home)
    }
}

// ---------- экраны (без логики — их можно снимать скриншот-тестами) ----------

@Composable
private fun ActionRow(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Txt(title, Ui.t.setting)
            Txt(hint, Ui.t.stats, Ui.c.muted)
        }
        GlyphIcon(Glyph.Next, Ui.c.muted)
    }
}

@Composable
fun SyncHomeView(
    peers: List<KnownPeer>,
    onShowQr: () -> Unit,
    onScan: () -> Unit,
    onNearby: () -> Unit,
    onSyncPeer: (KnownPeer) -> Unit,
    onForget: (KnownPeer) -> Unit,
    onBack: () -> Unit,
) {
    val c = Ui.c
    val t = Ui.t
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) { DateTimeFormatter.ofPattern("d MMM, HH:mm", locale) }
    SubScreen(stringResource(R.string.menu_sync), onBack) {
        Txt(stringResource(R.string.sync_lead), t.body, c.muted, Modifier.padding(start = 20.dp, end = 28.dp, top = 8.dp, bottom = 18.dp))
        Hairline()
        Column(Modifier.padding(horizontal = 20.dp)) {
            ActionRow(stringResource(R.string.sync_show_qr), stringResource(R.string.sync_show_qr_hint), onShowQr)
            Hairline()
            ActionRow(stringResource(R.string.sync_scan), stringResource(R.string.sync_scan_hint), onScan)
            Hairline()
            ActionRow(stringResource(R.string.sync_nearby), stringResource(R.string.sync_nearby_hint), onNearby)
            Hairline()
        }
        if (peers.isNotEmpty()) {
            SectionLabel(stringResource(R.string.sync_known))
            Column(Modifier.padding(start = 20.dp, end = 14.dp)) {
                peers.forEach { p ->
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                    Column {
                        Txt(p.name, t.setting)
                        Txt(
                            if (p.lastSync > 0) stringResource(
                                R.string.sync_last,
                                fmt.format(Instant.ofEpochMilli(p.lastSync).atZone(ZoneId.systemDefault())),
                            ) else stringResource(R.string.sync_never),
                            t.stats, c.muted, Modifier.padding(top = 2.dp),
                        )
                    }
                    Row(Modifier.offset(x = (-12).dp)) {
                        TextAction(stringResource(R.string.sync_now), { onSyncPeer(p) }, color = c.accent)
                        TextAction(stringResource(R.string.sync_forget), { onForget(p) }, color = c.ink)
                    }
                    }
                    Hairline()
                }
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

/** QR — всегда тёмным по белому, с полями: так его надёжно читает любая камера, и в тёмной теме тоже. */
@Composable
fun SyncQrView(qr: String, name: String, onBack: () -> Unit) {
    val m = remember(qr) { qrMatrix(qr) }
    val label = stringResource(R.string.sync_show_qr)
    SubScreen(label, onBack) {
        Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.86f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(14.dp)
                    .semantics { contentDescription = label }
            ) {
                val cell = size.width / m.size
                for (y in 0 until m.size) for (x in 0 until m.size) {
                    if (m.cells[y * m.size + x]) {
                        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
        Txt(stringResource(R.string.sync_visible, name), Ui.t.body, Ui.c.ink, Modifier.padding(start = 20.dp, end = 28.dp, top = 20.dp))
        Txt(stringResource(R.string.sync_waiting), Ui.t.action, Ui.c.ink, Modifier.padding(start = 20.dp, top = 14.dp))
    }
}

@Composable
fun SyncScanView(onCode: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok; denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    SubScreen(stringResource(R.string.sync_scan), onBack) {
        when {
            granted -> {
                QrCamera(onCode, Modifier.fillMaxWidth().aspectRatio(1f).padding(horizontal = 20.dp, vertical = 12.dp))
                Txt(stringResource(R.string.sync_scan_hint_frame), Ui.t.body, Ui.c.muted, Modifier.padding(horizontal = 20.dp))
            }
            denied -> Txt(stringResource(R.string.sync_camera_denied), Ui.t.body, Ui.c.ink, Modifier.padding(20.dp))
        }
    }
}

/** Камера: каждый кадр (яркость) отдаём ядру, оно ищет в нём QR «Страницы». */
@Composable
private fun QrCamera(onCode: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val callback by rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            val view = PreviewView(c)
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { img ->
                    try {
                        if (!done.get()) {
                            val plane = img.planes[0]
                            val buf = plane.buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            val text = decodeQr(img.width, img.height, bytes, plane.rowStride)
                            if (text != null && done.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(c).execute { callback(text) }
                            }
                        }
                    } finally {
                        img.close()
                    }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(c))
            view
        },
    )
}

@Composable
fun SyncNearbyView(peers: List<NearbyPeer>, onPick: (NearbyPeer) -> Unit, onBack: () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    SubScreen(stringResource(R.string.sync_nearby), onBack) {
        Txt(
            stringResource(if (peers.isEmpty()) R.string.sync_searching else R.string.sync_nearby_hint),
            t.body, c.muted, Modifier.padding(start = 20.dp, end = 28.dp, top = 8.dp, bottom = 18.dp),
        )
        Hairline()
        Column(Modifier.padding(horizontal = 20.dp)) {
            peers.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onPick(p) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(p.name, t.setting, modifier = Modifier.weight(1f))
                    if (p.known) Txt(stringResource(R.string.sync_known_mark), t.stats, c.muted, Modifier.padding(end = 12.dp))
                    GlyphIcon(Glyph.Next, c.muted)
                }
                Hairline()
            }
            if (peers.isEmpty()) Txt(stringResource(R.string.sync_nobody), t.stats, c.muted, Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
fun SyncProgressView(text: String, onBack: () -> Unit) {
    SubScreen(stringResource(R.string.menu_sync), onBack) {
        Txt(text, Ui.t.body, Ui.c.ink, Modifier.padding(start = 20.dp, end = 28.dp, top = 16.dp))
    }
}

/** Крупный код из двух групп по три цифры: «482 913». */
@Composable
fun SyncConfirmView(name: String, code: String, waiting: Boolean, onYes: () -> Unit, onNo: () -> Unit, onBack: () -> Unit) {
    val c = Ui.c
    val t = Ui.t
    SubScreen(stringResource(R.string.sync_code_title), onBack) {
        Txt(stringResource(R.string.sync_code_lead, name), t.body, c.muted, Modifier.padding(start = 20.dp, end = 28.dp, top = 8.dp))
        Txt(
            code.chunked(3).joinToString(" "),
            t.code,
            c.ink,
            Modifier.padding(start = 20.dp, top = 32.dp, bottom = 24.dp).semantics { contentDescription = code.toList().joinToString(" ") },
        )
        if (waiting) {
            Txt(stringResource(R.string.sync_code_waiting), t.action, c.muted, Modifier.padding(20.dp))
        } else {
            Column(Modifier.padding(horizontal = 20.dp)) {
                FilledAction(stringResource(R.string.sync_code_yes), onYes, Modifier.fillMaxWidth())
                TextAction(stringResource(R.string.sync_code_no), onNo, Modifier.fillMaxWidth().padding(top = 4.dp), color = c.ink)
            }
        }
    }
}

@Composable
fun SyncResultView(title: String, message: String, onDone: () -> Unit) {
    SubScreen(title, onDone) {
        Txt(message, Ui.t.body, Ui.c.ink, Modifier.padding(start = 20.dp, end = 28.dp, top = 12.dp))
        FilledAction(stringResource(R.string.sync_ok), onDone, Modifier.padding(start = 20.dp, top = 20.dp))
    }
}
