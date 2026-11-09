//! Файл данных: чтение (с миграцией старых форматов), атомарная запись.
//!
//! Форматы:
//! * v0 — Android 1.0–1.6: один список, настройки в корне, числовые id;
//! * v1 — Android 1.7: несколько списков, числовые id;
//! * v2 — общее ядро: UUID, время изменений и надгробия для синхронизации.

use crate::model::{
    new_id, Entry, Library, Notebook, Settings, Status, ThemeMode, DEFAULT_NOTEBOOK_ID, DEFAULT_PAGE_SIZE,
    FORMAT_VERSION, MAX_PAGE_SIZE, MIN_PAGE_SIZE,
};
use serde::Deserialize;
use serde_json::Value;
use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::Path;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("not a Stranitsa file: {msg}")]
    BadFormat { msg: String },
    #[error("file is from a newer app version ({version})")]
    TooNew { version: u32 },
    #[error("i/o error: {msg}")]
    Io { msg: String },
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        CoreError::Io { msg: e.to_string() }
    }
}

fn bad(msg: impl ToString) -> CoreError {
    CoreError::BadFormat { msg: msg.to_string() }
}

pub fn encode(lib: &Library) -> String {
    serde_json::to_string(lib).expect("Library всегда сериализуется")
}

/// Читает любой поддерживаемый формат. `now` — для новых идентификаторов при миграции.
pub fn decode(text: &str, now: i64) -> Result<Library, CoreError> {
    let root: Value = serde_json::from_str(text).map_err(bad)?;
    let obj = root.as_object().ok_or_else(|| bad("root is not an object"))?;
    let lib = if let Some(v) = obj.get("version") {
        let version = v.as_u64().ok_or_else(|| bad("version"))? as u32;
        if version > FORMAT_VERSION {
            return Err(CoreError::TooNew { version });
        }
        let mut lib = serde_json::from_value::<Library>(root).map_err(bad)?;
        if version < 3 {
            adopt_default_id(&mut lib);
        }
        lib
    } else if obj.contains_key("notebooks") {
        migrate_v1(serde_json::from_value(root).map_err(bad)?, now)
    } else if obj.contains_key("entries") || obj.contains_key("pageSize") {
        migrate_v0(serde_json::from_value(root).map_err(bad)?, now)
    } else {
        return Err(bad("unknown format"));
    };
    Ok(repair(lib))
}

/// Первый список файла старой версии становится общим списком по умолчанию.
/// Безопасно: синхронизации до версии 3 не было, id нигде больше не встречался.
fn adopt_default_id(lib: &mut Library) {
    if lib.notebooks.iter().any(|n| n.id == DEFAULT_NOTEBOOK_ID) || lib.notebooks.is_empty() {
        return;
    }
    let old = std::mem::replace(&mut lib.notebooks[0].id, DEFAULT_NOTEBOOK_ID.to_string());
    if lib.current_id == old {
        lib.current_id = DEFAULT_NOTEBOOK_ID.to_string();
    }
}

/// Приводит значения в допустимые пределы.
fn repair(mut lib: Library) -> Library {
    lib.version = FORMAT_VERSION;
    if lib.device_id.is_empty() {
        lib.device_id = new_id();
    }
    if lib.notebooks.is_empty() {
        lib.notebooks.push(Notebook::new(new_id(), String::new(), 0, DEFAULT_PAGE_SIZE));
    }
    for nb in &mut lib.notebooks {
        nb.page_size = nb.page_size.clamp(MIN_PAGE_SIZE, MAX_PAGE_SIZE);
        nb.sort_entries();
        nb.clamp_page();
    }
    if !lib.notebooks.iter().any(|n| n.id == lib.current_id) {
        lib.current_id = lib.notebooks[0].id.clone();
    }
    lib
}

// ---------- старые форматы ----------

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LegacyEntry {
    text: String,
    #[serde(default = "active")]
    status: Status,
    #[serde(default)]
    created_at: i64,
    #[serde(default)]
    closed_at: Option<i64>,
    #[serde(default)]
    reentries: i32,
    #[serde(default)]
    reviewed: bool,
}

fn active() -> Status {
    Status::Active
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct LegacyState {
    entries: Vec<LegacyEntry>,
    page_size: Option<i32>,
    current_page: i32,
    acted_on_visit: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct V0 {
    #[serde(flatten)]
    state: LegacyState,
    #[serde(default = "yes")]
    show_hints: bool,
    #[serde(default)]
    theme: ThemeMode,
    #[serde(default)]
    pure_black: bool,
    #[serde(default)]
    dynamic_color: bool,
    #[serde(default)]
    system_backup: bool,
}

fn yes() -> bool {
    true
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct V1Notebook {
    id: Value,
    #[serde(default)]
    name: String,
    #[serde(default)]
    state: LegacyState,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct V1 {
    notebooks: Vec<V1Notebook>,
    #[serde(default)]
    current_id: Value,
    #[serde(default)]
    settings: Settings,
}

/// Строки старого формата: новые UUID; порядок сохраняется (время записи делаем строго возрастающим).
fn migrate_state(s: LegacyState, name: String, now: i64) -> Notebook {
    let mut nb = Notebook::new(new_id(), name, now, s.page_size.unwrap_or(DEFAULT_PAGE_SIZE));
    nb.name_updated_at = 0; // имя из старой версии не должно перебивать переименование на другом устройстве
    nb.updated_at = 0;
    let mut last = i64::MIN;
    for e in s.entries {
        let created_at = if e.created_at > last { e.created_at } else { last + 1 };
        last = created_at;
        nb.entries.push(Entry {
            id: new_id(),
            text: e.text,
            status: e.status,
            created_at,
            closed_at: e.closed_at,
            reentries: e.reentries,
            reviewed: e.reviewed,
            updated_at: e.closed_at.unwrap_or(created_at),
        });
    }
    nb.current_page = s.current_page;
    nb.acted_on_visit = s.acted_on_visit;
    nb
}

fn migrate_v0(v: V0, now: i64) -> Library {
    let mut nb = migrate_state(v.state, String::new(), now);
    nb.id = DEFAULT_NOTEBOOK_ID.into();
    Library {
        version: FORMAT_VERSION,
        device_id: new_id(),
        current_id: nb.id.clone(),
        notebooks: vec![nb],
        deleted_notebooks: BTreeMap::new(),
        settings: Settings {
            show_hints: v.show_hints,
            theme: v.theme,
            pure_black: v.pure_black,
            dynamic_color: v.dynamic_color,
            system_backup: v.system_backup,
            ..Settings::default()
        },
    }
}

fn migrate_v1(v: V1, now: i64) -> Library {
    let mut current_id = String::new();
    let mut notebooks = Vec::new();
    for old in v.notebooks {
        let is_current = old.id == v.current_id;
        let nb = migrate_state(old.state, old.name, now);
        if is_current {
            current_id = nb.id.clone();
        }
        notebooks.push(nb);
    }
    let mut lib = Library {
        version: FORMAT_VERSION,
        device_id: new_id(),
        current_id,
        notebooks,
        deleted_notebooks: BTreeMap::new(),
        settings: v.settings,
    };
    adopt_default_id(&mut lib);
    lib
}

// ---------- файл ----------

/// Загрузка. Нет файла — пустая библиотека. Испорченный файл откладывается рядом (`.broken-…`), не затирается.
pub fn load(path: &Path, now: i64) -> Library {
    let Ok(text) = fs::read_to_string(path) else {
        return Library::new(now);
    };
    match decode(&text, now) {
        Ok(lib) => lib,
        Err(_) => {
            let aside = path.with_file_name(format!(
                "{}.broken-{now}",
                path.file_name().and_then(|n| n.to_str()).unwrap_or("stranitsa.json")
            ));
            let _ = fs::copy(path, aside);
            Library::new(now)
        }
    }
}

/// Пишет во временный файл, сбрасывает на диск и атомарно подменяет основной.
/// Основной файл не удаляется заранее — сбой посреди записи его не портит.
pub fn save(path: &Path, lib: &Library) -> Result<(), CoreError> {
    save_text(path, &encode(lib))
}

/// То же для уже готового текста файла.
pub fn save_text(path: &Path, text: &str) -> Result<(), CoreError> {
    let tmp = path.with_extension("json.tmp");
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(text.as_bytes())?;
        f.sync_all()?;
    }
    fs::rename(&tmp, path)?;
    Ok(())
}

/// Запись файла в отдельном потоке: действие не ждёт `fsync` (на телефонах это единицы–десятки
/// миллисекунд). Если записей накопилось несколько, на диск уходит только последняя. Каждая запись
/// по-прежнему атомарна (временный файл → `fsync` → подмена), так что файл всегда целый.
pub struct DiskWriter {
    tx: Option<std::sync::mpsc::Sender<WriterMsg>>,
    handle: Option<std::thread::JoinHandle<()>>,
    error: std::sync::Arc<std::sync::Mutex<Option<String>>>,
}

enum WriterMsg {
    Write(String),
    Flush(std::sync::mpsc::Sender<()>),
}

impl DiskWriter {
    pub fn new(path: std::path::PathBuf) -> Self {
        let (tx, rx) = std::sync::mpsc::channel::<WriterMsg>();
        let error = std::sync::Arc::new(std::sync::Mutex::new(None));
        let err = error.clone();
        let handle = std::thread::Builder::new()
            .name("stranitsa-writer".into())
            .spawn(move || {
                while let Ok(first) = rx.recv() {
                    let mut text = None;
                    let mut acks = Vec::new();
                    let mut take = |m: WriterMsg| match m {
                        WriterMsg::Write(t) => text = Some(t),
                        WriterMsg::Flush(a) => acks.push(a),
                    };
                    take(first);
                    while let Ok(next) = rx.try_recv() {
                        take(next);
                    }
                    if let Some(t) = text {
                        *err.lock().unwrap() = save_text(&path, &t).err().map(|e| e.to_string());
                    }
                    for a in acks {
                        let _ = a.send(());
                    }
                }
            })
            .expect("поток записи");
        DiskWriter { tx: Some(tx), handle: Some(handle), error }
    }

    /// Поставить текст в очередь на запись.
    pub fn write(&self, text: String) {
        if let Some(tx) = &self.tx {
            let _ = tx.send(WriterMsg::Write(text));
        }
    }

    /// Дождаться, пока всё поставленное в очередь окажется на диске.
    pub fn flush(&self) {
        if let Some(tx) = &self.tx {
            let (ack, done) = std::sync::mpsc::channel();
            if tx.send(WriterMsg::Flush(ack)).is_ok() {
                let _ = done.recv();
            }
        }
    }

    pub fn last_error(&self) -> Option<String> {
        self.error.lock().unwrap().clone()
    }
}

impl Drop for DiskWriter {
    fn drop(&mut self) {
        self.flush();
        self.tx = None; // поток видит закрытый канал и завершается
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_v2() {
        let mut l = Library::new(1);
        l.current_mut().add("a", 5);
        l.add_notebook("Работа", 6);
        l.settings.pure_black = true;
        assert_eq!(decode(&encode(&l), 7).unwrap(), l);
    }

    /// Файл Android 1.0–1.6.
    #[test]
    fn migrates_v0_single_list() {
        let legacy = r#"{"entries":[{"id":1,"text":"старое","createdAt":5},{"id":2,"text":"ещё","status":"DONE","createdAt":5,"closedAt":7}],
            "pageSize":12,"currentPage":0,"actedOnVisit":true,"nextId":3,"showHints":false,"theme":"DARK","pureBlack":true,"systemBackup":true}"#;
        let l = decode(legacy, 100).unwrap();
        assert_eq!(l.notebooks.len(), 1);
        let nb = l.current();
        assert_eq!(nb.entries.iter().map(|e| e.text.as_str()).collect::<Vec<_>>(), ["старое", "ещё"]);
        assert_eq!(nb.entries[1].status, Status::Done);
        assert!(nb.entries[0].created_at < nb.entries[1].created_at, "порядок сохраняется при равном времени");
        assert_eq!(nb.entries[1].updated_at, 7);
        assert_eq!(nb.page_size, 12);
        assert!(nb.acted_on_visit);
        assert!(!l.settings.show_hints && l.settings.pure_black && l.settings.system_backup);
        assert_eq!(l.settings.theme, ThemeMode::Dark);
        // id стали уникальными строками
        assert_ne!(nb.entries[0].id, nb.entries[1].id);
        assert!(nb.entries[0].id.len() >= 32);
        // и обратно — уже в новом формате
        assert_eq!(decode(&encode(&l), 200).unwrap(), l);
    }

    /// Файл Android 1.7.
    #[test]
    fn migrates_v1_lists() {
        let v1 = r#"{"notebooks":[
              {"id":1,"name":"","state":{"entries":[{"id":1,"text":"дом","createdAt":1}],"pageSize":25,"currentPage":0,"actedOnVisit":false,"nextId":2}},
              {"id":2,"name":"Работа","state":{"entries":[{"id":1,"text":"отчёт","createdAt":2,"reentries":2}],"pageSize":30,"currentPage":0,"actedOnVisit":false,"nextId":2}}],
            "currentId":2,"nextNotebookId":3,"settings":{"showHints":true,"theme":"LIGHT","pureBlack":false,"dynamicColor":true,"systemBackup":false,"lastSeen":42}}"#;
        let l = decode(v1, 100).unwrap();
        assert_eq!(l.notebooks.len(), 2);
        assert_eq!(l.notebooks[0].id, DEFAULT_NOTEBOOK_ID);
        assert_eq!(l.current().name, "Работа");
        assert_eq!(l.current().entries[0].reentries, 2);
        assert_eq!(l.current().page_size, 30);
        assert_eq!(l.settings.last_seen, 42);
        assert!(l.settings.dynamic_color);
    }

    #[test]
    fn version2_file_gets_shared_default_id() {
        let mut l = Library::new(1);
        l.notebooks[0].id = "random-uuid-from-1.8".into();
        l.current_id = l.notebooks[0].id.clone();
        l.version = 2;
        let mut v: Value = serde_json::from_str(&encode(&l)).unwrap();
        v["version"] = 2.into();
        let back = decode(&v.to_string(), 2).unwrap();
        assert_eq!(back.notebooks[0].id, DEFAULT_NOTEBOOK_ID);
        assert_eq!(back.current_id, DEFAULT_NOTEBOOK_ID);
        assert_eq!(back.version, FORMAT_VERSION);
    }

    #[test]
    fn repairs_broken_values() {
        let s = r#"{"entries":[{"id":7,"text":"x","createdAt":0}],"pageSize":999,"currentPage":50}"#;
        let l = decode(s, 1).unwrap();
        assert_eq!(l.current().page_size, MAX_PAGE_SIZE);
        assert_eq!(l.current().current_page, 0);
    }

    #[test]
    fn rejects_garbage_and_newer_versions() {
        assert!(matches!(decode("{не json", 0), Err(CoreError::BadFormat { .. })));
        assert!(matches!(decode(r#"{"hello":1}"#, 0), Err(CoreError::BadFormat { .. })));
        assert!(matches!(decode(r#"{"version":99}"#, 0), Err(CoreError::TooNew { version: 99 })));
    }

    #[test]
    fn save_load_and_broken_file_kept_aside() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("stranitsa.json");
        assert_eq!(load(&p, 1).notebooks.len(), 1); // нет файла — пустая библиотека
        let mut l = Library::new(1);
        l.current_mut().add("задача", 2);
        save(&p, &l).unwrap();
        save(&p, &l).unwrap(); // перезапись поверх существующего
        assert_eq!(load(&p, 3), l);
        assert!(!dir.path().join("stranitsa.json.tmp").exists());
        fs::write(&p, "{сломано").unwrap();
        let fresh = load(&p, 4);
        assert!(fresh.current().entries.is_empty());
        assert!(fs::read_dir(dir.path()).unwrap().any(|e| e.unwrap().file_name().to_string_lossy().starts_with("stranitsa.json.broken-")));
    }
}
