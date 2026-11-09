//! Синхронизация без облака: два устройства в одной сети обмениваются копиями и сливают их.
//!
//! Канал — Noise (`XX_25519_ChaChaPoly_BLAKE2s`), как в WireGuard: всё зашифровано, и каждая
//! сторона знает постоянный ключ другой. Как устройства убеждаются, что говорят друг с другом:
//!
//! * **QR.** Одно устройство показывает QR (адрес, его ключ и одноразовый секрет), другое сканирует.
//!   Секрет входит в рукопожатие (`XXpsk3`) — подключиться может только тот, кто видел экран.
//! * **Рядом, без QR.** Устройства находят друг друга через mDNS. Обе стороны показывают 6-значный код,
//!   выведенный из рукопожатия; пользователь подтверждает, что коды совпадают (как при Bluetooth).
//! * **Повторно.** После первого раза устройства помнят ключи друг друга — без кодов.
//!
//! Обмен: инициатор присылает свою копию, ответчик сливает и присылает результат, инициатор сливает его.
//! Слияние коммутативно и идемпотентно, поэтому после обмена у обоих одинаковые данные.

use crate::core::StCore;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const NOISE_XX: &str = "Noise_XX_25519_ChaChaPoly_BLAKE2s";
const NOISE_XX_PSK: &str = "Noise_XXpsk3_25519_ChaChaPoly_BLAKE2s";
const PROTOCOL: u8 = 1;
const SERVICE: &str = "_stranitsa._tcp.local.";
const MODE_NEARBY: u8 = 0;
const MODE_QR: u8 = 1;
/// Сколько ждать ответа другой стороны (в том числе пока человек сверяет код).
const IO_TIMEOUT: Duration = Duration::from_secs(120);
const CHUNK: usize = 60_000;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum SyncError {
    #[error("network: {msg}")]
    Network { msg: String },
    #[error("secure channel failed: {msg}")]
    Handshake { msg: String },
    #[error("not a Stranitsa pairing code")]
    BadQr,
    #[error("the other device declined or the codes did not match")]
    Rejected,
    #[error("the other device is not reachable")]
    Unreachable,
    #[error("data: {msg}")]
    Data { msg: String },
}

fn net(e: impl ToString) -> SyncError {
    SyncError::Network { msg: e.to_string() }
}
fn hs(e: impl ToString) -> SyncError {
    SyncError::Handshake { msg: e.to_string() }
}

// ---------- кто я и кого знаю ----------

/// Устройство, с которым уже синхронизировались.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct KnownPeer {
    pub device_id: String,
    pub name: String,
    pub public_key: String,
    pub last_sync: i64,
}

/// Постоянный ключ устройства и список знакомых. Хранится отдельно от списка задач
/// (не попадает ни в резервную копию, ни в синхронизацию).
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    pub private_key: String,
    pub public_key: String,
    #[serde(default)]
    pub peers: Vec<KnownPeer>,
}

impl Identity {
    pub fn generate() -> Identity {
        let kp = snow::Builder::new(NOISE_XX.parse().unwrap()).generate_keypair().expect("генерация ключей");
        Identity { private_key: B64.encode(kp.private), public_key: B64.encode(kp.public), peers: Vec::new() }
    }

    fn private(&self) -> Vec<u8> {
        B64.decode(&self.private_key).unwrap_or_default()
    }

    pub fn trusts(&self, public_key: &[u8]) -> bool {
        let k = B64.encode(public_key);
        self.peers.iter().any(|p| p.public_key == k)
    }

    pub fn remember(&mut self, device_id: &str, name: &str, public_key: &[u8], now: i64) {
        let k = B64.encode(public_key);
        self.peers.retain(|p| p.device_id != device_id && p.public_key != k);
        self.peers.push(KnownPeer { device_id: device_id.into(), name: name.into(), public_key: k, last_sync: now });
    }
}

// ---------- QR ----------

/// Содержимое QR: `stranitsa:1?h=<адреса>&p=<порт>&i=<id>&k=<ключ>&s=<секрет>&n=<имя>`.
#[derive(Clone, Debug, PartialEq)]
pub struct QrInvite {
    pub hosts: Vec<String>,
    pub port: u16,
    pub device_id: String,
    pub public_key: Vec<u8>,
    pub secret: Vec<u8>,
    pub name: String,
}

impl QrInvite {
    pub fn encode(&self) -> String {
        format!(
            "stranitsa:1?h={}&p={}&i={}&k={}&s={}&n={}",
            self.hosts.join(","),
            self.port,
            self.device_id,
            B64.encode(&self.public_key),
            B64.encode(&self.secret),
            B64.encode(self.name.as_bytes())
        )
    }

    pub fn parse(text: &str) -> Result<QrInvite, SyncError> {
        let rest = text.trim().strip_prefix("stranitsa:1?").ok_or(SyncError::BadQr)?;
        let mut m: HashMap<&str, &str> = HashMap::new();
        for kv in rest.split('&') {
            let (k, v) = kv.split_once('=').ok_or(SyncError::BadQr)?;
            m.insert(k, v);
        }
        let get = |k: &str| m.get(k).copied().ok_or(SyncError::BadQr);
        let b = |k: &str| -> Result<Vec<u8>, SyncError> { B64.decode(get(k)?).map_err(|_| SyncError::BadQr) };
        let inv = QrInvite {
            hosts: get("h")?.split(',').filter(|s| !s.is_empty()).map(String::from).collect(),
            port: get("p")?.parse().map_err(|_| SyncError::BadQr)?,
            device_id: get("i")?.to_string(),
            public_key: b("k")?,
            secret: b("s")?,
            name: String::from_utf8(b("n")?).map_err(|_| SyncError::BadQr)?,
        };
        if inv.hosts.is_empty() || inv.secret.len() != 32 || inv.public_key.len() != 32 {
            return Err(SyncError::BadQr);
        }
        Ok(inv)
    }
}

/// Квадратная матрица QR для рисования: `cells[y * size + x]` — тёмная клетка.
#[derive(Clone, Debug, uniffi::Record)]
pub struct QrMatrix {
    pub size: i32,
    pub cells: Vec<bool>,
}

#[uniffi::export]
pub fn qr_matrix(text: String) -> QrMatrix {
    let code = qrcode::QrCode::with_error_correction_level(text.as_bytes(), qrcode::EcLevel::M)
        .expect("текст QR помещается");
    let size = code.width() as i32;
    let cells = code.to_colors().into_iter().map(|c| c == qrcode::Color::Dark).collect();
    QrMatrix { size, cells }
}

/// Распознать QR в кадре камеры (яркость, по байту на пиксель). Возвращает текст, только если это
/// приглашение «Страницы».
#[uniffi::export]
pub fn decode_qr(width: i32, height: i32, luma: Vec<u8>, row_stride: i32) -> Option<String> {
    let (w, h, stride) = (width as usize, height as usize, row_stride.max(width) as usize);
    if luma.len() < stride * h.saturating_sub(1) + w {
        return None;
    }
    let mut img = rqrr::PreparedImage::prepare_from_greyscale(w, h, |x, y| luma[y * stride + x]);
    for grid in img.detect_grids() {
        if let Ok((_, text)) = grid.decode() {
            if QrInvite::parse(&text).is_ok() {
                return Some(text);
            }
        }
    }
    None
}

// ---------- канал ----------

struct Channel {
    stream: TcpStream,
    noise: snow::TransportState,
}

fn write_frame16(s: &mut TcpStream, data: &[u8]) -> Result<(), SyncError> {
    s.write_all(&(data.len() as u16).to_be_bytes()).map_err(net)?;
    s.write_all(data).map_err(net)
}

fn read_frame16(s: &mut TcpStream) -> Result<Vec<u8>, SyncError> {
    let mut len = [0u8; 2];
    s.read_exact(&mut len).map_err(net)?;
    let mut buf = vec![0u8; u16::from_be_bytes(len) as usize];
    s.read_exact(&mut buf).map_err(net)?;
    Ok(buf)
}

impl Channel {
    fn send_frame(&mut self, plain: &[u8]) -> Result<(), SyncError> {
        let mut out = vec![0u8; plain.len() + 64];
        let n = self.noise.write_message(plain, &mut out).map_err(hs)?;
        write_frame16(&mut self.stream, &out[..n])
    }

    fn recv_frame(&mut self) -> Result<Vec<u8>, SyncError> {
        let frame = read_frame16(&mut self.stream)?;
        let mut out = vec![0u8; frame.len()];
        let n = self.noise.read_message(&frame, &mut out).map_err(hs)?;
        out.truncate(n);
        Ok(out)
    }

    /// Сообщение любой длины: сначала длина, затем куски.
    fn send(&mut self, data: &[u8]) -> Result<(), SyncError> {
        self.send_frame(&(data.len() as u64).to_be_bytes())?;
        for chunk in data.chunks(CHUNK) {
            self.send_frame(chunk)?;
        }
        Ok(())
    }

    fn recv(&mut self) -> Result<Vec<u8>, SyncError> {
        let head = self.recv_frame()?;
        let total = u64::from_be_bytes(head.try_into().map_err(|_| hs("bad header"))?) as usize;
        if total > 256 * 1024 * 1024 {
            return Err(hs("message too large"));
        }
        let mut data = Vec::with_capacity(total);
        while data.len() < total {
            data.extend(self.recv_frame()?);
        }
        Ok(data)
    }

    fn send_json<T: Serialize>(&mut self, v: &T) -> Result<(), SyncError> {
        self.send(&serde_json::to_vec(v).map_err(|e| SyncError::Data { msg: e.to_string() })?)
    }

    fn recv_json<T: for<'de> Deserialize<'de>>(&mut self) -> Result<T, SyncError> {
        serde_json::from_slice(&self.recv()?).map_err(|e| SyncError::Data { msg: e.to_string() })
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Hello {
    protocol: u8,
    device_id: String,
    name: String,
    /// Знаю ли я твой ключ (были ли уже знакомы).
    trusts_you: bool,
}

#[derive(Serialize, Deserialize)]
struct Verdict {
    ok: bool,
}

/// 6-значный код из хеша рукопожатия: одинаков у обеих сторон, только если посередине никого нет.
fn short_code(handshake_hash: &[u8]) -> String {
    let n = u32::from_be_bytes([handshake_hash[0], handshake_hash[1], handshake_hash[2], handshake_hash[3]]);
    format!("{:06}", n % 1_000_000)
}

fn configure(stream: &TcpStream) -> Result<(), SyncError> {
    stream.set_nodelay(true).map_err(net)?;
    stream.set_read_timeout(Some(IO_TIMEOUT)).map_err(net)?;
    stream.set_write_timeout(Some(IO_TIMEOUT)).map_err(net)
}

// ---------- сеанс ----------

/// Сведения о только что подключённом устройстве — показать пользователю перед обменом.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PeerInfo {
    pub device_id: String,
    pub name: String,
    /// Код для сверки (если `needs_confirmation`).
    pub code: String,
    /// Нужно ли сверить код на обоих экранах (первое знакомство без QR).
    pub needs_confirmation: bool,
    pub via_qr: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SyncReport {
    pub peer_name: String,
    /// Сколько строк прибавилось в списках этого устройства.
    pub new_entries: i32,
}

/// Установленное защищённое соединение. Дальше — `finish(true)` (обмен) или `finish(false)` (отказ).
#[derive(uniffi::Object)]
pub struct SyncSession {
    core: Arc<StCore>,
    initiator: bool,
    info: PeerInfo,
    peer_key: Vec<u8>,
    channel: Mutex<Option<Channel>>,
}

#[uniffi::export]
impl SyncSession {
    pub fn peer(&self) -> PeerInfo {
        self.info.clone()
    }

    /// Подтвердить (или отклонить) и обменяться данными. Если нужна сверка кода — ждёт решения
    /// другой стороны. После успеха устройство запоминается как знакомое.
    pub fn finish(&self, confirmed: bool, now: i64) -> Result<SyncReport, SyncError> {
        let mut guard = self.channel.lock().unwrap();
        let ch = guard.as_mut().ok_or(SyncError::Rejected)?;
        if self.info.needs_confirmation {
            ch.send_json(&Verdict { ok: confirmed })?;
            let theirs: Verdict = ch.recv_json()?;
            if !confirmed || !theirs.ok {
                *guard = None;
                return Err(SyncError::Rejected);
            }
        } else if !confirmed {
            *guard = None;
            return Err(SyncError::Rejected);
        }
        let before = self.core.total_entries();
        if self.initiator {
            ch.send(self.core.export_json().as_bytes())?;
            let merged = String::from_utf8(ch.recv()?).map_err(|e| SyncError::Data { msg: e.to_string() })?;
            self.core.merge_json(merged, now).map_err(|e| SyncError::Data { msg: e.to_string() })?;
        } else {
            let theirs = String::from_utf8(ch.recv()?).map_err(|e| SyncError::Data { msg: e.to_string() })?;
            self.core.merge_json(theirs, now).map_err(|e| SyncError::Data { msg: e.to_string() })?;
            ch.send(self.core.export_json().as_bytes())?;
        }
        self.core.remember_peer(&self.info.device_id, &self.info.name, &self.peer_key, now);
        *guard = None;
        Ok(SyncReport { peer_name: self.info.name.clone(), new_entries: self.core.total_entries() - before })
    }
}

/// Общая часть рукопожатия для обеих сторон. `psk` — секрет из QR.
fn handshake(
    core: &Arc<StCore>,
    mut stream: TcpStream,
    initiator: bool,
    psk: Option<&[u8]>,
    my_name: &str,
) -> Result<Arc<SyncSession>, SyncError> {
    configure(&stream)?;
    let identity = core.sync_identity();
    let params = if psk.is_some() { NOISE_XX_PSK } else { NOISE_XX };
    let private = identity.private();
    let mut b = snow::Builder::new(params.parse().unwrap()).local_private_key(&private);
    if let Some(k) = psk {
        b = b.psk(3, k);
    }
    let mut state = if initiator { b.build_initiator() } else { b.build_responder() }.map_err(hs)?;
    let mut buf = vec![0u8; 1024];
    if initiator {
        let n = state.write_message(&[], &mut buf).map_err(hs)?;
        write_frame16(&mut stream, &buf[..n])?;
        let m = read_frame16(&mut stream)?;
        state.read_message(&m, &mut buf).map_err(hs)?;
        let n = state.write_message(&[], &mut buf).map_err(hs)?;
        write_frame16(&mut stream, &buf[..n])?;
    } else {
        let m = read_frame16(&mut stream)?;
        state.read_message(&m, &mut buf).map_err(hs)?;
        let n = state.write_message(&[], &mut buf).map_err(hs)?;
        write_frame16(&mut stream, &buf[..n])?;
        let m = read_frame16(&mut stream)?;
        state.read_message(&m, &mut buf).map_err(hs)?;
    }
    let peer_key = state.get_remote_static().ok_or_else(|| hs("no remote key"))?.to_vec();
    let code = short_code(state.get_handshake_hash());
    let noise = state.into_transport_mode().map_err(hs)?;
    let mut ch = Channel { stream, noise };

    let i_trust = identity.trusts(&peer_key);
    let mine = Hello { protocol: PROTOCOL, device_id: core.device_id(), name: my_name.into(), trusts_you: i_trust };
    let theirs: Hello = if initiator {
        ch.send_json(&mine)?;
        ch.recv_json()?
    } else {
        let t = ch.recv_json()?;
        ch.send_json(&mine)?;
        t
    };
    if theirs.protocol != PROTOCOL {
        return Err(hs(format!("protocol {}", theirs.protocol)));
    }
    if theirs.device_id == core.device_id() {
        return Err(hs("connected to itself"));
    }
    let via_qr = psk.is_some();
    // Сверка нужна, если это не QR и хотя бы одна сторона ещё не знает другую. Обе считают одинаково.
    let needs_confirmation = !via_qr && !(i_trust && theirs.trusts_you);
    Ok(Arc::new(SyncSession {
        core: core.clone(),
        initiator,
        info: PeerInfo { device_id: theirs.device_id, name: theirs.name, code, needs_confirmation, via_qr },
        peer_key,
        channel: Mutex::new(Some(ch)),
    }))
}

// ---------- ожидающая сторона ----------

fn local_ipv4() -> Vec<String> {
    let mut v: Vec<String> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        .filter_map(|i| match i.ip() {
            IpAddr::V4(a) if !a.is_link_local() => Some(a.to_string()),
            _ => None,
        })
        .collect();
    v.sort();
    v.dedup();
    v
}

/// Устройство «видно»: принимает подключения по QR и от тех, кто нашёл его рядом.
#[derive(uniffi::Object)]
pub struct SyncHost {
    core: Arc<StCore>,
    name: String,
    listener: TcpListener,
    secret: Vec<u8>,
    qr: String,
    stopped: AtomicBool,
    mdns: Mutex<Option<(mdns_sd::ServiceDaemon, String)>>,
}

#[uniffi::export]
impl SyncHost {
    /// Открыть порт и объявить себя в сети. `advertise = false` — без mDNS (только QR).
    #[uniffi::constructor]
    pub fn start(core: Arc<StCore>, device_name: String, advertise: bool) -> Result<Arc<Self>, SyncError> {
        let listener = TcpListener::bind("0.0.0.0:0").map_err(net)?;
        listener.set_nonblocking(true).map_err(net)?;
        let port = listener.local_addr().map_err(net)?.port();
        let mut secret = vec![0u8; 32];
        getrandom::fill(&mut secret).map_err(net)?;
        let identity = core.sync_identity();
        let hosts = local_ipv4();
        let qr = QrInvite {
            hosts: if hosts.is_empty() { vec!["127.0.0.1".into()] } else { hosts.clone() },
            port,
            device_id: core.device_id(),
            public_key: B64.decode(&identity.public_key).unwrap_or_default(),
            secret: secret.clone(),
            name: device_name.clone(),
        }
        .encode();
        let mdns = if advertise { advertise_mdns(&core.device_id(), &device_name, &hosts, port).ok() } else { None };
        Ok(Arc::new(SyncHost {
            core,
            name: device_name,
            listener,
            secret,
            qr,
            stopped: AtomicBool::new(false),
            mdns: Mutex::new(mdns),
        }))
    }

    pub fn qr_text(&self) -> String {
        self.qr.clone()
    }

    pub fn port(&self) -> i32 {
        self.listener.local_addr().map(|a| a.port() as i32).unwrap_or(0)
    }

    /// Ждать подключения до `timeout_ms`. `None` — никто не подключился (или остановлено).
    /// Неудачное рукопожатие (чужой, неверный секрет) возвращается ошибкой — можно ждать дальше.
    /// Фоновый приём для автосинхронизации: знакомое устройство — обмен без вопросов,
    /// незнакомое — отказ (знакомство требует кода или QR на экране).
    pub fn accept_known(&self, timeout_ms: i64) -> Option<SyncReport> {
        let s = self.accept(timeout_ms).ok()??;
        if s.peer().needs_confirmation {
            let _ = s.finish(false, now_ms());
            return None;
        }
        s.finish(true, now_ms()).ok()
    }

    pub fn accept(&self, timeout_ms: i64) -> Result<Option<Arc<SyncSession>>, SyncError> {
        let deadline = Instant::now() + Duration::from_millis(timeout_ms.max(0) as u64);
        loop {
            if self.stopped.load(Ordering::SeqCst) {
                return Ok(None);
            }
            match self.listener.accept() {
                Ok((mut stream, _)) => {
                    stream.set_nonblocking(false).map_err(net)?;
                    stream.set_read_timeout(Some(Duration::from_secs(15))).map_err(net)?;
                    let mut pre = [0u8; 4];
                    stream.read_exact(&mut pre).map_err(net)?;
                    if &pre[..2] != b"ST" || pre[2] != PROTOCOL {
                        return Err(hs("not a Stranitsa device"));
                    }
                    let psk = if pre[3] == MODE_QR { Some(self.secret.as_slice()) } else { None };
                    return handshake(&self.core, stream, false, psk, &self.name).map(Some);
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    if Instant::now() >= deadline {
                        return Ok(None);
                    }
                    std::thread::sleep(Duration::from_millis(50));
                }
                Err(e) => return Err(net(e)),
            }
        }
    }

    /// Перестать быть видимым.
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        if let Some((d, full)) = self.mdns.lock().unwrap().take() {
            let _ = d.unregister(&full);
            let _ = d.shutdown();
        }
    }
}

impl Drop for SyncHost {
    fn drop(&mut self) {
        self.stop();
    }
}

fn advertise_mdns(
    device_id: &str,
    name: &str,
    hosts: &[String],
    port: u16,
) -> Result<(mdns_sd::ServiceDaemon, String), mdns_sd::Error> {
    let daemon = mdns_sd::ServiceDaemon::new()?;
    let instance = format!("st-{}", &device_id[..8.min(device_id.len())]);
    let host = format!("{instance}.local.");
    let props = [("id", device_id), ("n", name), ("v", "1")];
    let ips: Vec<IpAddr> = hosts.iter().filter_map(|h| h.parse().ok()).collect();
    let info = mdns_sd::ServiceInfo::new(SERVICE, &instance, &host, &ips[..], port, &props[..])?;
    let full = info.get_fullname().to_string();
    daemon.register(info)?;
    Ok((daemon, full))
}

// ---------- подключающаяся сторона ----------

fn connect_any(hosts: &[String], port: u16) -> Result<TcpStream, SyncError> {
    for h in hosts {
        let Ok(ip) = h.parse::<IpAddr>() else { continue };
        if let Ok(s) = TcpStream::connect_timeout(&SocketAddr::new(ip, port), Duration::from_secs(4)) {
            return Ok(s);
        }
    }
    Err(SyncError::Unreachable)
}

fn open(core: &Arc<StCore>, mut stream: TcpStream, mode: u8, psk: Option<&[u8]>, name: &str) -> Result<Arc<SyncSession>, SyncError> {
    stream.write_all(&[b'S', b'T', PROTOCOL, mode]).map_err(net)?;
    handshake(core, stream, true, psk, name)
}

/// Подключиться по отсканированному QR.
#[uniffi::export]
pub fn sync_connect_qr(core: Arc<StCore>, qr_text: String, device_name: String) -> Result<Arc<SyncSession>, SyncError> {
    let inv = QrInvite::parse(&qr_text)?;
    let stream = connect_any(&inv.hosts, inv.port)?;
    let s = open(&core, stream, MODE_QR, Some(&inv.secret), &device_name)?;
    if s.peer_key != inv.public_key || s.info.device_id != inv.device_id {
        return Err(hs("the device does not match the QR code"));
    }
    Ok(s)
}

/// Подключиться к устройству, найденному рядом.
#[uniffi::export]
pub fn sync_connect(core: Arc<StCore>, peer: NearbyPeer, device_name: String) -> Result<Arc<SyncSession>, SyncError> {
    let stream = connect_any(&peer.addresses, peer.port as u16)?;
    let s = open(&core, stream, MODE_NEARBY, None, &device_name)?;
    if s.info.device_id != peer.device_id {
        return Err(hs("a different device answered"));
    }
    Ok(s)
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Автосинхронизация: в течение `wait_ms` ищет знакомые устройства рядом (у которых открыт
/// приём) и обменивается с каждым без кодов. Незнакомые не трогает.
#[uniffi::export]
pub fn sync_known_nearby(core: Arc<StCore>, device_name: String, wait_ms: i64) -> Vec<SyncReport> {
    let browser = SyncBrowser::start(core.clone());
    std::thread::sleep(Duration::from_millis(wait_ms.clamp(0, 30_000) as u64));
    let peers: Vec<NearbyPeer> = browser.peers().into_iter().filter(|p| p.known).collect();
    browser.stop();
    let mut out = Vec::new();
    for p in peers {
        if let Ok(s) = sync_connect(core.clone(), p, device_name.clone()) {
            if s.peer().needs_confirmation {
                let _ = s.finish(false, now_ms());
            } else if let Ok(r) = s.finish(true, now_ms()) {
                out.push(r);
            }
        }
    }
    out
}

// ---------- поиск рядом ----------

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct NearbyPeer {
    pub device_id: String,
    pub name: String,
    pub addresses: Vec<String>,
    pub port: i32,
    /// Уже знакомы — синхронизация без кода.
    pub known: bool,
}

/// Ищет «видимые» устройства в сети, пока не остановлен.
#[derive(uniffi::Object)]
pub struct SyncBrowser {
    core: Arc<StCore>,
    daemon: Option<mdns_sd::ServiceDaemon>,
    found: Arc<Mutex<HashMap<String, NearbyPeer>>>,
}

#[uniffi::export]
impl SyncBrowser {
    #[uniffi::constructor]
    pub fn start(core: Arc<StCore>) -> Arc<Self> {
        let found: Arc<Mutex<HashMap<String, NearbyPeer>>> = Arc::new(Mutex::new(HashMap::new()));
        let daemon = mdns_sd::ServiceDaemon::new().ok();
        if let Some(d) = &daemon {
            if let Ok(rx) = d.browse(SERVICE) {
                let found = found.clone();
                std::thread::spawn(move || {
                    while let Ok(ev) = rx.recv() {
                        match ev {
                            mdns_sd::ServiceEvent::ServiceResolved(info) => {
                                let id = info.get_property_val_str("id").unwrap_or_default().to_string();
                                if id.is_empty() {
                                    continue;
                                }
                                let mut addrs: Vec<String> =
                                    info.get_addresses().iter().filter(|a| a.is_ipv4()).map(|a| a.to_string()).collect();
                                addrs.sort();
                                found.lock().unwrap().insert(
                                    info.get_fullname().to_string(),
                                    NearbyPeer {
                                        device_id: id,
                                        name: info.get_property_val_str("n").unwrap_or_default().to_string(),
                                        addresses: addrs,
                                        port: info.get_port() as i32,
                                        known: false,
                                    },
                                );
                            }
                            mdns_sd::ServiceEvent::ServiceRemoved(_, full) => {
                                found.lock().unwrap().remove(&full);
                            }
                            mdns_sd::ServiceEvent::SearchStopped(_) => break,
                            _ => {}
                        }
                    }
                });
            }
        }
        Arc::new(SyncBrowser { core, daemon, found })
    }

    /// Найденные сейчас устройства (кроме себя), знакомые — первыми.
    pub fn peers(&self) -> Vec<NearbyPeer> {
        let me = self.core.device_id();
        let identity = self.core.sync_identity();
        let mut v: Vec<NearbyPeer> = self
            .found
            .lock()
            .unwrap()
            .values()
            .filter(|p| p.device_id != me)
            .map(|p| {
                let mut p = p.clone();
                p.known = identity.peers.iter().any(|k| k.device_id == p.device_id);
                p
            })
            .collect();
        v.sort_by(|a, b| b.known.cmp(&a.known).then_with(|| a.name.cmp(&b.name)));
        v
    }

    pub fn stop(&self) {
        if let Some(d) = &self.daemon {
            let _ = d.stop_browse(SERVICE);
            let _ = d.shutdown();
        }
    }
}

impl Drop for SyncBrowser {
    fn drop(&mut self) {
        self.stop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Status;
    use std::thread;

    fn texts(core: &StCore) -> Vec<String> {
        core.snapshot().entries.iter().map(|e| e.text.clone()).collect()
    }

    /// Хост ждёт в отдельном потоке и подтверждает с решением `host_ok`.
    fn serve(host: Arc<SyncHost>, host_ok: bool) -> thread::JoinHandle<Result<(PeerInfo, SyncReport), SyncError>> {
        thread::spawn(move || {
            let s = host.accept(10_000)?.expect("подключились");
            let info = s.peer();
            let r = s.finish(host_ok, 500)?;
            Ok((info, r))
        })
    }

    fn local(host: &SyncHost) -> NearbyPeer {
        NearbyPeer {
            device_id: host.core.device_id(),
            name: host.name.clone(),
            addresses: vec!["127.0.0.1".into()],
            port: host.port(),
            known: false,
        }
    }

    #[test]
    fn qr_roundtrip_and_rejects_garbage() {
        let inv = QrInvite {
            hosts: vec!["192.168.1.5".into(), "10.0.0.2".into()],
            port: 50123,
            device_id: "abc".into(),
            public_key: vec![7; 32],
            secret: vec![9; 32],
            name: "Мой ПК".into(),
        };
        assert_eq!(QrInvite::parse(&inv.encode()).unwrap(), inv);
        assert!(QrInvite::parse("https://example.com").is_err());
        assert!(QrInvite::parse("stranitsa:1?h=1.2.3.4&p=1").is_err());
    }

    #[test]
    fn qr_matrix_decodes_back() {
        let text = "stranitsa:1?h=127.0.0.1&p=1234&i=dev&k=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&s=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&n=UEM";
        let m = qr_matrix(text.into());
        // нарисовать как кадр камеры: 6 пикселей на клетку, поля 4 клетки
        let scale = 6usize;
        let side = (m.size as usize + 8) * scale;
        let mut img = vec![255u8; side * side];
        for y in 0..m.size as usize {
            for x in 0..m.size as usize {
                if m.cells[y * m.size as usize + x] {
                    for dy in 0..scale {
                        for dx in 0..scale {
                            img[((y + 4) * scale + dy) * side + (x + 4) * scale + dx] = 0;
                        }
                    }
                }
            }
        }
        assert_eq!(decode_qr(side as i32, side as i32, img, side as i32).as_deref(), Some(text));
    }

    #[test]
    fn sync_by_qr_merges_both_ways_and_remembers() {
        let pc = StCore::in_memory(0);
        pc.add("с ПК".into(), 10);
        let phone = StCore::in_memory(0);
        phone.add("с телефона".into(), 20);

        let host = SyncHost::start(pc.clone(), "ПК".into(), false).unwrap();
        let qr = host.qr_text().replace(&local_ipv4().join(","), "127.0.0.1");
        let t = serve(host.clone(), true);
        let s = sync_connect_qr(phone.clone(), qr, "Телефон".into()).unwrap();
        assert!(s.peer().via_qr && !s.peer().needs_confirmation);
        let r = s.finish(true, 600).unwrap();
        let (info, hr) = t.join().unwrap().unwrap();
        assert_eq!(info.name, "Телефон");
        assert_eq!(r.peer_name, "ПК");
        assert_eq!((r.new_entries, hr.new_entries), (1, 1));
        assert_eq!(texts(&pc), ["с ПК", "с телефона"]);
        assert_eq!(texts(&phone), texts(&pc));
        assert_eq!(phone.sync_peers()[0].name, "ПК");
        assert_eq!(pc.sync_peers()[0].name, "Телефон");
    }

    #[test]
    fn wrong_qr_secret_is_refused() {
        let pc = StCore::in_memory(0);
        let phone = StCore::in_memory(0);
        let host = SyncHost::start(pc.clone(), "ПК".into(), false).unwrap();
        let mut inv = QrInvite::parse(&host.qr_text()).unwrap();
        inv.hosts = vec!["127.0.0.1".into()];
        inv.secret = vec![1; 32]; // подсмотрели адрес, но не секрет
        let h = host.clone();
        let t = thread::spawn(move || h.accept(10_000).map(|s| s.is_some()));
        assert!(sync_connect_qr(phone, inv.encode(), "чужой".into()).is_err());
        assert!(t.join().unwrap().is_err(), "хост отверг рукопожатие");
        assert!(pc.sync_peers().is_empty());
    }

    #[test]
    fn nearby_needs_matching_codes_then_trusted_resync_needs_none() {
        let pc = StCore::in_memory(0);
        let phone = StCore::in_memory(0);
        phone.add("задача".into(), 1);
        let host = SyncHost::start(pc.clone(), "ПК".into(), false).unwrap();

        // первое знакомство: коды совпадают у обоих, оба подтверждают
        let t = serve(host.clone(), true);
        let s = sync_connect(phone.clone(), local(&host), "Телефон".into()).unwrap();
        assert!(s.peer().needs_confirmation);
        let (host_info, _) = {
            s.finish(true, 2).unwrap();
            t.join().unwrap().unwrap()
        };
        assert_eq!(host_info.code, s.peer().code, "одинаковый код на обоих экранах");
        assert_eq!(texts(&pc), ["задача"]);

        // повторно: уже знакомы — без кода
        phone.add("ещё".into(), 3);
        let t = serve(host.clone(), true);
        let s = sync_connect(phone.clone(), local(&host), "Телефон".into()).unwrap();
        assert!(!s.peer().needs_confirmation);
        s.finish(true, 4).unwrap();
        t.join().unwrap().unwrap();
        assert_eq!(texts(&pc), ["задача", "ещё"]);
    }

    #[test]
    fn declined_on_one_side_means_nothing_is_exchanged() {
        let pc = StCore::in_memory(0);
        let phone = StCore::in_memory(0);
        phone.add("секрет".into(), 1);
        let host = SyncHost::start(pc.clone(), "ПК".into(), false).unwrap();
        let t = serve(host.clone(), false); // на ПК нажали «Не совпадает»
        let s = sync_connect(phone.clone(), local(&host), "Телефон".into()).unwrap();
        assert!(matches!(s.finish(true, 2), Err(SyncError::Rejected)));
        assert!(matches!(t.join().unwrap(), Err(SyncError::Rejected)));
        assert!(texts(&pc).is_empty());
        assert!(pc.sync_peers().is_empty() && phone.sync_peers().is_empty());
    }

    #[test]
    fn status_changes_flow_both_ways() {
        let pc = StCore::in_memory(0);
        let phone = StCore::in_memory(0);
        phone.add("общая".into(), 1);
        let host = SyncHost::start(pc.clone(), "ПК".into(), false).unwrap();
        let t = serve(host.clone(), true);
        sync_connect(phone.clone(), local(&host), "Т".into()).unwrap().finish(true, 2).unwrap();
        t.join().unwrap().unwrap();
        // на ПК отметили сделанной
        let id = pc.snapshot().entries[0].id.clone();
        pc.done(id, 100);
        let t = serve(host.clone(), true);
        sync_connect(phone.clone(), local(&host), "Т".into()).unwrap().finish(true, 200).unwrap();
        t.join().unwrap().unwrap();
        assert_eq!(phone.snapshot().entries[0].status, Status::Done);
    }

    /// Поиск через mDNS. Нужна сеть с multicast — в контейнерах CI её часто нет, поэтому тест
    /// не запускается по умолчанию: `cargo test -- --ignored mdns`.
    #[test]
    #[ignore]
    fn mdns_finds_visible_device() {
        let pc = StCore::in_memory(0);
        let phone = StCore::in_memory(0);
        let host = SyncHost::start(pc.clone(), "ПК".into(), true).unwrap();
        let browser = SyncBrowser::start(phone.clone());
        let deadline = Instant::now() + Duration::from_secs(10);
        let found = loop {
            if let Some(p) = browser.peers().into_iter().find(|p| p.device_id == pc.device_id()) {
                break p;
            }
            assert!(Instant::now() < deadline, "не нашли за 10 с");
            thread::sleep(Duration::from_millis(200));
        };
        assert_eq!(found.name, "ПК");
        assert_eq!(found.port, host.port());
        let t = serve(host.clone(), true);
        sync_connect(phone.clone(), found, "Т".into()).unwrap().finish(true, 1).unwrap();
        t.join().unwrap().unwrap();
        browser.stop();
    }

    #[test]
    fn large_list_goes_through_in_chunks() {
        let pc = StCore::in_memory(0);
        let phone = StCore::in_memory(0);
        let long = "x".repeat(500);
        for i in 0..400 {
            phone.add(format!("{i} {long}"), i);
        }
        let host = SyncHost::start(pc.clone(), "ПК".into(), false).unwrap();
        let t = serve(host.clone(), true);
        sync_connect(phone.clone(), local(&host), "Т".into()).unwrap().finish(true, 1000).unwrap();
        t.join().unwrap().unwrap();
        assert_eq!(pc.snapshot().entries.len(), 400);
    }

    #[test]
    fn host_accept_known_syncs_known_and_refuses_strangers() {
        let a = StCore::in_memory(1);
        let b = StCore::in_memory(1);
        let c = StCore::in_memory(1);
        a.add("на компьютере".into(), 2);
        b.add("на телефоне".into(), 3);
        // знакомство по QR
        let host = SyncHost::start(a.clone(), "ПК".into(), false).unwrap();
        let h = host.clone();
        let t = thread::spawn(move || { let s = h.accept(5000).unwrap().unwrap(); s.finish(true, 10).unwrap() });
        sync_connect_qr(b.clone(), host.qr_text(), "Телефон".into()).unwrap().finish(true, 10).unwrap();
        t.join().unwrap();
        // повторно — в фоне, без кода
        b.add("ещё с телефона".into(), 20);
        let h = host.clone();
        let t = thread::spawn(move || h.accept_known(5000));
        let peer = NearbyPeer { device_id: a.device_id(), name: "ПК".into(), addresses: vec!["127.0.0.1".into()], port: host.port(), known: true };
        sync_connect(b.clone(), peer.clone(), "Телефон".into()).unwrap().finish(true, 30).unwrap();
        assert!(t.join().unwrap().is_some());
        assert!(texts(&a).contains(&"ещё с телефона".to_string()));
        // незнакомый — отказ без обмена
        let h = host.clone();
        let t = thread::spawn(move || h.accept_known(5000));
        let r = sync_connect(c.clone(), peer, "Чужой".into()).and_then(|s| s.finish(true, 40));
        assert!(t.join().unwrap().is_none());
        assert!(r.is_err() || !texts(&a).iter().any(|x| x == "чужое"));
        assert!(!texts(&c).contains(&"на компьютере".to_string()));
        host.stop();
    }
}
