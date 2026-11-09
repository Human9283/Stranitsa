//! Журнал для отчёта об ошибке.
//!
//! Сюда пишутся только служебные события: «синхронизация не удалась: сеть», «файл повреждён».
//! Тексты задач, названия списков, имена и адреса устройств сюда не попадают — вызывающий код
//! передаёт только вид события, а [`scrub`] на всякий случай прячет домашнюю папку и IP-адреса.
//!
//! Журнал живёт в памяти (последние [`CAP`] событий). При сбое — панике в ядре или исключении
//! в приложении — он вместе с описанием сбоя сохраняется в файл рядом с данными, чтобы при
//! следующем запуске можно было отправить отчёт.

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::{Mutex, Once};

pub const CAP: usize = 200;
const MAX_LINE: usize = 300;
const MAX_CRASH: usize = 16_000;
pub const CRASH_FILE: &str = "stranitsa-crash.txt";

static LOG: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static DIR: Mutex<Option<PathBuf>> = Mutex::new(None);
static HOOK: Once = Once::new();

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Время UTC вида `2026-11-09 14:03:27Z` — без сторонних библиотек.
pub fn format_utc(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let (days, sod) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    // Алгоритм Говарда Хиннанта: дни от 1970-01-01 → дата.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}Z", sod / 3600, sod % 3600 / 60, sod % 60)
}

fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

/// Спрятать то, что может указать на человека: домашнюю папку (в путях есть имя пользователя)
/// и IP-адреса. Длинные строки обрезаются.
pub fn scrub(s: &str) -> String {
    let mut out = s.replace(['\r', '\n'], " ");
    for var in ["HOME", "USERPROFILE"] {
        if let Ok(home) = std::env::var(var) {
            if home.len() > 3 {
                out = out.replace(&home, "~");
            }
        }
    }
    out = mask_ipv4(&out);
    if out.chars().count() > MAX_LINE {
        out = out.chars().take(MAX_LINE).collect::<String>() + "…";
    }
    out
}

/// `192.168.1.20` → `x.x.x.x`. Версии вида `1.2.3` не трогает: нужны ровно четыре числа.
fn mask_ipv4(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < b.len() {
        let boundary = i == 0 || !(b[i - 1].is_ascii_alphanumeric() || b[i - 1] == b'.');
        if boundary && b[i].is_ascii_digit() {
            let mut j = i;
            let mut groups = 0;
            loop {
                let start = j;
                while j < b.len() && b[j].is_ascii_digit() && j - start < 3 {
                    j += 1;
                }
                if j == start {
                    break;
                }
                groups += 1;
                if groups == 4 || j >= b.len() || b[j] != b'.' {
                    break;
                }
                j += 1;
            }
            let end_ok = j >= b.len() || !(b[j].is_ascii_alphanumeric() || b[j] == b'.');
            if groups == 4 && end_ok {
                out.push_str("x.x.x.x");
                i = j;
                continue;
            }
        }
        let ch = s[i..].chars().next().unwrap();
        out.push(ch);
        i += ch.len_utf8();
    }
    out
}

/// Записать событие. `msg` — вид события, без пользовательских данных.
pub fn event(msg: impl AsRef<str>) {
    let line = format!("{} {}", format_utc(now_ms()), scrub(msg.as_ref()));
    let mut l = lock(&LOG);
    if l.len() >= CAP {
        l.pop_front();
    }
    l.push_back(line);
}

pub fn events() -> Vec<String> {
    lock(&LOG).iter().cloned().collect()
}

/// Папка для файла о сбое (папка с данными) и перехват паники. Вызывается при открытии ядра.
pub fn init(dir: Option<PathBuf>) {
    if let Some(d) = dir {
        *lock(&DIR) = Some(d);
    }
    HOOK.call_once(|| {
        let prev = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            // В выпускной сборке паника завершает процесс сразу (panic = "abort"),
            // поэтому записываем файл прямо здесь.
            let place = info.location().map(|l| format!("{}:{}", l.file(), l.line())).unwrap_or_default();
            let what = info
                .payload()
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| info.payload().downcast_ref::<String>().cloned())
                .unwrap_or_default();
            let thread = std::thread::current().name().unwrap_or("?").to_string();
            let text = format!("Rust panic in thread '{thread}' at {place}\n{}", scrub(&what));
            event(format!("panic at {place}"));
            record_crash(&text);
            prev(info);
        }));
    });
}

fn crash_path() -> Option<PathBuf> {
    lock(&DIR).as_ref().map(|d| d.join(CRASH_FILE))
}

/// Сохранить описание сбоя вместе с журналом. Повторный сбой до отправки дописывается в конец.
pub fn record_crash(description: &str) {
    if let Some(p) = crash_path() {
        write_crash(&p, description);
    }
}

fn write_crash(p: &std::path::Path, description: &str) {
    let mut text = std::fs::read_to_string(p).unwrap_or_default();
    if !text.is_empty() {
        text.push_str("\n\n");
    }
    text.push_str(&format!("== {} ==\n", format_utc(now_ms())));
    for line in description.lines() {
        text.push_str(&scrub_keep_long(line));
        text.push('\n');
    }
    text.push_str("-- events --\n");
    for e in events().iter().rev().take(60).collect::<Vec<_>>().into_iter().rev() {
        text.push_str(e);
        text.push('\n');
    }
    if text.len() > MAX_CRASH {
        let cut = text.len() - MAX_CRASH;
        let cut = (cut..text.len()).find(|&i| text.is_char_boundary(i)).unwrap_or(text.len());
        text = text[cut..].to_string();
    }
    let _ = std::fs::write(p, text);
}

/// Как [`scrub`], но без обрезки: строки стека бывают длинными, а смысл у них в конце.
fn scrub_keep_long(s: &str) -> String {
    let mut out = s.replace('\r', "");
    for var in ["HOME", "USERPROFILE"] {
        if let Ok(home) = std::env::var(var) {
            if home.len() > 3 {
                out = out.replace(&home, "~");
            }
        }
    }
    mask_ipv4(&out)
}

pub fn pending_crash() -> Option<String> {
    let p = crash_path()?;
    std::fs::read_to_string(p).ok().filter(|t| !t.trim().is_empty())
}

pub fn clear_crash() {
    if let Some(p) = crash_path() {
        let _ = std::fs::remove_file(p);
    }
}

// ---------- для приложений ----------

/// Папка с данными: сюда пишется файл о сбое. Вызывать как можно раньше при запуске —
/// до открытия ядра, чтобы поймать и самые ранние сбои.
#[uniffi::export]
pub fn diag_init(data_dir: String) {
    init(Some(PathBuf::from(data_dir)));
}

/// Добавить событие в журнал (из приложения). Передавайте только вид события, без текстов задач.
#[uniffi::export]
pub fn diag_log(message: String) {
    event(message);
}

/// Последние события журнала, старые сверху.
#[uniffi::export]
pub fn diag_events() -> Vec<String> {
    events()
}

/// Приложение упало: сохранить описание (класс исключения, стек) и журнал в файл рядом с данными.
#[uniffi::export]
pub fn diag_record_crash(description: String) {
    event("app crash");
    record_crash(&description);
}

/// Описание сбоя прошлого запуска, если он был и отчёт ещё не отправлен или не отклонён.
#[uniffi::export]
pub fn diag_pending_crash() -> Option<String> {
    pending_crash()
}

#[uniffi::export]
pub fn diag_clear_crash() {
    clear_crash()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utc_format() {
        assert_eq!(format_utc(0), "1970-01-01 00:00:00Z");
        assert_eq!(format_utc(1_762_696_407_000), "2025-11-09 13:53:27Z");
        assert_eq!(format_utc(951_782_400_000), "2000-02-29 00:00:00Z");
    }

    #[test]
    fn ip_addresses_are_masked_but_versions_are_not() {
        assert_eq!(mask_ipv4("connect 192.168.1.20:4040 failed"), "connect x.x.x.x:4040 failed");
        assert_eq!(mask_ipv4("app 1.2.3 ok"), "app 1.2.3 ok");
        assert_eq!(mask_ipv4("v10.0.0.1"), "v10.0.0.1");
        assert_eq!(mask_ipv4("10.0.0.1"), "x.x.x.x");
        assert_eq!(mask_ipv4("ошибка 8.8.8.8, ещё"), "ошибка x.x.x.x, ещё");
    }

    #[test]
    fn crash_file_collects_description_and_events_and_appends() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join(CRASH_FILE);
        event("before crash marker");
        write_crash(&p, "java.lang.IllegalStateException: boom\n\tat app.X.y(X.kt:1)");
        write_crash(&p, "second");
        let t = std::fs::read_to_string(&p).unwrap();
        assert!(t.contains("IllegalStateException: boom"));
        assert!(t.contains("\tat app.X.y(X.kt:1)"));
        assert!(t.contains("before crash marker"));
        assert!(t.contains("second"));
        assert_eq!(t.matches("== ").count(), 2);
    }

    #[test]
    fn scrub_hides_home_and_cuts_long_lines() {
        if let Ok(home) = std::env::var("HOME") {
            if home.len() > 3 {
                assert_eq!(scrub(&format!("{home}/x")), "~/x");
            }
        }
        let long = "a".repeat(1000);
        assert!(scrub(&long).chars().count() <= MAX_LINE + 1);
        assert_eq!(scrub("a\nb"), "a b");
    }
}
