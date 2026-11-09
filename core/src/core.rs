//! Объект, который видят приложения (Kotlin, Swift, ПК): одно состояние, атомарная запись после
//! каждого изменения, отмена и слияние с другим устройством.
//!
//! Время передаётся параметром `now` (мс) — ядро не читает часы само: так оно детерминировано и тестируемо.

use crate::model::{Entry, Library, Settings};
use crate::store::{self, CoreError};
use crate::sync::{Identity, KnownPeer};
use crate::text::SearchHit;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

/// Краткие сведения о списке — для экрана «Списки» и выбора в «Поделиться».
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct NotebookInfo {
    pub id: String,
    /// Пустое — список по умолчанию (подпись на языке интерфейса).
    pub name: String,
    pub active_total: i32,
    pub current_page: i32,
    pub last_page: i32,
}

/// Всё, что нужно экрану, одним значением: текущий список уже разобран на страницы.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Snapshot {
    pub settings: Settings,
    pub notebooks: Vec<NotebookInfo>,
    pub current_id: String,
    pub name: String,
    /// Все строки текущего списка по порядку; страница `p` — это `entries[p*page_size ..]`.
    pub entries: Vec<Entry>,
    pub page_size: i32,
    /// Рабочая страница.
    pub current_page: i32,
    pub acted_on_visit: bool,
    pub last_page: i32,
    /// Куда ведёт «Дальше».
    pub next_page: i32,
    /// Предлагать ли отбросить рабочую страницу при уходе с неё.
    pub dismiss_applies: bool,
    pub active_total: i32,
    /// Сколько активных задач на каждой странице.
    pub page_active_count: Vec<i32>,
    /// Отброшенные, ещё не разобранные; новые сверху.
    pub dismissed_for_review: Vec<Entry>,
    pub can_undo: bool,
}

struct Inner {
    lib: Library,
    /// Запись файла в фоне; None — без файла (превью и тесты).
    writer: Option<store::DiskWriter>,
    undo: Vec<Library>,
    /// Ключ устройства и знакомые устройства — отдельный файл `stranitsa-sync.json` рядом с данными.
    identity: Identity,
    identity_path: Option<PathBuf>,
    /// Файл данных — для размера в отчёте об ошибке.
    data_path: Option<PathBuf>,
}

fn load_identity(path: &Option<PathBuf>) -> Identity {
    if let Some(p) = path {
        if let Ok(text) = std::fs::read_to_string(p) {
            if let Ok(id) = serde_json::from_str::<Identity>(&text) {
                return id;
            }
            crate::diag::event("sync keys: file damaged, new device key generated");
        }
        let id = Identity::generate();
        let _ = write_identity(p, &id);
        return id;
    }
    Identity::generate()
}

fn write_identity(p: &PathBuf, id: &Identity) -> std::io::Result<()> {
    let tmp = p.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec(id).expect("identity"))?;
    std::fs::rename(&tmp, p).inspect_err(|e| crate::diag::event(format!("sync keys: save failed: {e}")))
}

const UNDO_DEPTH: usize = 50;

#[derive(uniffi::Object)]
pub struct StCore {
    inner: Mutex<Inner>,
}

impl StCore {
    fn make_snapshot(i: &Inner) -> Snapshot {
        let lib = &i.lib;
        let nb = lib.current();
        Snapshot {
            settings: lib.settings.clone(),
            notebooks: lib
                .notebooks
                .iter()
                .map(|n| NotebookInfo {
                    id: n.id.clone(),
                    name: n.name.clone(),
                    active_total: n.active_total(),
                    current_page: n.current_page,
                    last_page: n.last_page(),
                })
                .collect(),
            current_id: nb.id.clone(),
            name: nb.name.clone(),
            entries: nb.entries.clone(),
            page_size: nb.page_size,
            current_page: nb.current_page,
            acted_on_visit: nb.acted_on_visit,
            last_page: nb.last_page(),
            next_page: nb.next_page(),
            dismiss_applies: nb.dismiss_applies(),
            active_total: nb.active_total(),
            page_active_count: (0..=nb.last_page()).map(|p| nb.active_count_on_page(p)).collect(),
            dismissed_for_review: nb.dismissed_for_review(),
            can_undo: !i.undo.is_empty(),
        }
    }

    /// Поставить текущее состояние в очередь на запись (сам `fsync` идёт в фоновом потоке).
    fn persist(i: &mut Inner) {
        if let Some(w) = &i.writer {
            w.write(store::encode(&i.lib));
        }
    }

    /// Применить изменение; если что-то поменялось — запомнить для отмены (если `record`) и записать.
    fn mutate(&self, record: bool, f: impl FnOnce(&mut Library)) -> Snapshot {
        let mut i = self.inner.lock().unwrap();
        let before = i.lib.clone();
        f(&mut i.lib);
        if i.lib != before {
            if record {
                i.undo.push(before);
                if i.undo.len() > UNDO_DEPTH {
                    i.undo.remove(0);
                }
            }
            Self::persist(&mut i);
        }
        Self::make_snapshot(&i)
    }

    fn list(&self, record: bool, f: impl FnOnce(&mut crate::model::Notebook)) -> Snapshot {
        self.mutate(record, |l| f(l.current_mut()))
    }
}

#[uniffi::export]
impl StCore {
    /// Открыть файл данных (создаётся при первой записи). Старые форматы переводятся автоматически.
    #[uniffi::constructor]
    pub fn new(path: String, now: i64) -> Arc<Self> {
        let p = PathBuf::from(path);
        crate::diag::init(p.parent().map(|d| d.to_path_buf()));
        let lib = store::load(&p, now);
        let identity_path = Some(p.with_file_name("stranitsa-sync.json"));
        let identity = load_identity(&identity_path);
        Arc::new(StCore {
            inner: Mutex::new(Inner {
                lib,
                writer: Some(store::DiskWriter::new(p.clone())),
                undo: Vec::new(),
                identity,
                identity_path,
                data_path: Some(p),
            }),
        })
    }

    /// Без файла — для превью и тестов.
    #[uniffi::constructor]
    pub fn in_memory(now: i64) -> Arc<Self> {
        Arc::new(StCore {
            inner: Mutex::new(Inner {
                lib: Library::new(now),
                writer: None,
                undo: Vec::new(),
                identity: Identity::generate(),
                identity_path: None,
                data_path: None,
            }),
        })
    }

    pub fn snapshot(&self) -> Snapshot {
        Self::make_snapshot(&self.inner.lock().unwrap())
    }

    pub fn device_id(&self) -> String {
        self.inner.lock().unwrap().lib.device_id.clone()
    }

    /// Ошибка последней записи на диск (запись идёт в фоне, поэтому о ней узнаём здесь).
    pub fn last_save_error(&self) -> Option<String> {
        self.inner.lock().unwrap().writer.as_ref().and_then(|w| w.last_error())
    }

    /// Дождаться записи на диск. Вызывать, когда приложение уходит с экрана, и перед тем,
    /// как кто-то другой читает файл напрямую.
    pub fn flush(&self) {
        if let Some(w) = &self.inner.lock().unwrap().writer {
            w.flush();
        }
    }

    /// Сведения для отчёта об ошибке: только числа и настройки, без текстов задач,
    /// названий списков и имён устройств.
    pub fn diagnostics(&self) -> Vec<String> {
        use crate::model::Status;
        let i = self.inner.lock().unwrap();
        let lib = &i.lib;
        let mut count = [0usize; 5];
        let mut pending_review = 0;
        for n in &lib.notebooks {
            for e in &n.entries {
                count[match e.status {
                    Status::Active => 0,
                    Status::Done => 1,
                    Status::Reentered => 2,
                    Status::Dismissed => 3,
                    Status::Cancelled => 4,
                }] += 1;
            }
            pending_review += n.dismissed_for_review().len();
        }
        let nb = lib.current();
        let set = &lib.settings;
        let tomb_entries: usize = lib.notebooks.iter().map(|n| n.deleted_entries.len()).sum();
        let size = i.data_path.as_ref().and_then(|p| std::fs::metadata(p).ok()).map(|m| m.len());
        let on = |b: bool| if b { "on" } else { "off" };
        vec![
            format!("format: v{}", lib.version),
            format!(
                "lists: {} (current is #{}), lines: {} (active {}, done {}, re-entered {}, dismissed {}, deleted {})",
                lib.notebooks.len(),
                lib.notebooks.iter().position(|n| n.id == nb.id).map(|p| p + 1).unwrap_or(0),
                count.iter().sum::<usize>(),
                count[0], count[1], count[2], count[3], count[4],
            ),
            format!("dismissed waiting for review: {pending_review}"),
            format!(
                "current list: page {} of {}, page size {}, acted on visit {}",
                nb.current_page + 1,
                nb.last_page() + 1,
                nb.page_size,
                on(nb.acted_on_visit)
            ),
            format!("removal marks: lines {}, lists {}", tomb_entries, lib.deleted_notebooks.len()),
            format!(
                "settings: theme {:?}, pure black {}, wallpaper colors {}, hints {}, system backup {}, auto sync {} min, update check {}, cleanup {}",
                set.theme,
                on(set.pure_black),
                on(set.dynamic_color),
                on(set.show_hints),
                on(set.system_backup),
                set.auto_sync_minutes,
                on(set.check_updates),
                on(set.cleanup_enabled)
            ),
            format!("known devices: {}", i.identity.peers.len()),
            format!("undo steps: {}", i.undo.len()),
            format!("data file: {}", size.map(|b| format!("{} KB", b.div_ceil(1024))).unwrap_or_else(|| "none".into())),
            format!(
                "last save error: {}",
                i.writer.as_ref().and_then(|w| w.last_error()).unwrap_or_else(|| "none".into())
            ),
        ]
    }

    // ---------- задачи ----------

    pub fn add(&self, text: String, now: i64) -> Snapshot {
        self.list(true, |n| {
            n.add(&text, now);
        })
    }

    /// Несколько задач в конец указанного списка (например, из «Поделиться»).
    pub fn add_to(&self, notebook_id: String, texts: Vec<String>, now: i64) -> Snapshot {
        self.mutate(true, |l| {
            if let Some(n) = l.notebook_mut(&notebook_id) {
                for t in &texts {
                    n.add(t, now);
                }
            }
        })
    }

    pub fn done(&self, id: String, now: i64) -> Snapshot {
        self.list(true, |n| {
            n.done(&id, now);
        })
    }

    pub fn reenter(&self, id: String, now: i64) -> Snapshot {
        self.list(true, |n| {
            n.reenter(&id, now);
        })
    }

    pub fn cancel(&self, id: String, now: i64) -> Snapshot {
        self.list(true, |n| {
            n.cancel(&id, now);
        })
    }

    pub fn edit(&self, id: String, text: String, now: i64) -> Snapshot {
        self.list(true, |n| {
            n.edit(&id, &text, now);
        })
    }

    pub fn advance(&self, dismiss: bool, now: i64) -> Snapshot {
        self.list(true, |n| n.advance(dismiss, now))
    }

    pub fn revive(&self, id: String, text: String, now: i64) -> Snapshot {
        self.list(true, |n| {
            n.revive(&id, &text, now);
        })
    }

    pub fn forget(&self, id: String, now: i64) -> Snapshot {
        self.list(true, |n| {
            n.forget(&id, now);
        })
    }

    /// Только для тестов и превью: сделать страницу рабочей.
    pub fn go_to(&self, page: i32) -> Snapshot {
        self.list(false, |n| n.go_to(page))
    }

    pub fn set_page_size(&self, size: i32) -> Snapshot {
        self.list(false, |n| n.set_page_size(size))
    }

    pub fn search(&self, query: String, include_closed: bool) -> Vec<SearchHit> {
        self.inner.lock().unwrap().lib.current().search(&query, include_closed)
    }

    /// Сколько задач текущего списка закрыто как «Сделано» начиная с `since`.
    pub fn done_since(&self, since: i64) -> i32 {
        self.inner.lock().unwrap().lib.current().done_since(since)
    }

    // ---------- списки ----------

    pub fn switch_list(&self, id: String) -> Snapshot {
        self.mutate(false, |l| {
            l.switch_to(&id);
        })
    }

    pub fn add_list(&self, name: String, now: i64) -> Snapshot {
        self.mutate(true, |l| {
            l.add_notebook(&name, now);
        })
    }

    pub fn rename_list(&self, id: String, name: String, now: i64) -> Snapshot {
        self.mutate(false, |l| {
            l.rename_notebook(&id, &name, now);
        })
    }

    pub fn delete_list(&self, id: String, now: i64) -> Snapshot {
        self.mutate(true, |l| {
            l.delete_notebook(&id, now);
        })
    }

    // ---------- очистка ----------

    /// Сколько закрытых строк убрала бы очистка (закрытых больше `days` суток назад) во всех списках.
    pub fn cleanup_preview(&self, days: i32, now: i64) -> i32 {
        let before = now - days.max(0) as i64 * 86_400_000;
        let i = self.inner.lock().unwrap();
        i.lib.notebooks.iter().map(|n| n.cleanup_count(before) as i32).sum()
    }

    /// Убрать закрытые строки старше `days` суток во всех списках. Можно отменить.
    pub fn cleanup(&self, days: i32, now: i64) -> Snapshot {
        if !self.inner.lock().unwrap().lib.settings.cleanup_enabled {
            // Выключено в настройках: ничего не убираем, что бы ни вызвало.
            crate::diag::event("cleanup: refused, turned off in settings");
            return self.snapshot();
        }
        let before = now - days.max(0) as i64 * 86_400_000;
        let mut removed = 0;
        let snap = self.mutate(true, |l| {
            for n in &mut l.notebooks {
                removed += n.cleanup_count(before);
                n.cleanup(before, now);
            }
        });
        crate::diag::event(format!("cleanup: {days} days, {removed} lines removed"));
        snap
    }

    // ---------- настройки ----------

    pub fn set_settings(&self, settings: Settings) -> Snapshot {
        self.mutate(false, |l| l.settings = settings)
    }

    // ---------- отмена ----------

    pub fn undo(&self, now: i64) -> Snapshot {
        let mut i = self.inner.lock().unwrap();
        if let Some(prev) = i.undo.pop() {
            i.lib.restore_from(&prev, now);
            Self::persist(&mut i);
        }
        Self::make_snapshot(&i)
    }

    // ---------- знакомые устройства ----------

    /// Устройства, с которыми уже синхронизировались; последние — первыми.
    pub fn sync_peers(&self) -> Vec<KnownPeer> {
        let mut v = self.inner.lock().unwrap().identity.peers.clone();
        v.sort_by_key(|p| std::cmp::Reverse(p.last_sync));
        v
    }

    /// Забыть устройство: в следующий раз снова понадобится QR или сверка кода.
    pub fn forget_peer(&self, device_id: String) {
        let mut i = self.inner.lock().unwrap();
        i.identity.peers.retain(|p| p.device_id != device_id);
        if let Some(p) = i.identity_path.clone() {
            let _ = write_identity(&p, &i.identity);
        }
    }

    // ---------- копии и синхронизация ----------

    /// Полная копия в формате файла (для «Сохранить копию» и для передачи на другое устройство).
    pub fn export_json(&self) -> String {
        store::encode(&self.inner.lock().unwrap().lib)
    }

    /// Восстановить из копии: заменяет все списки. Настройки и id устройства остаются свои. Можно отменить.
    pub fn import_json(&self, text: String, now: i64) -> Result<Snapshot, CoreError> {
        let other = store::decode(&text, now).inspect_err(|e| crate::diag::event(format!("import: rejected ({})", e.kind())))?;
        crate::diag::event("import: replaced all lists from a copy");
        Ok(self.mutate(true, move |l| {
            l.notebooks = other.notebooks;
            l.deleted_notebooks = other.deleted_notebooks;
            if l.notebooks.iter().any(|n| n.id == other.current_id) {
                l.current_id = other.current_id;
            } else {
                l.current_id = l.notebooks[0].id.clone();
            }
        }))
    }

    /// Влить копию с другого устройства (синхронизация). Своё — настройки, текущий список,
    /// рабочие страницы — не меняется. После слияния история отмены сбрасывается.
    pub fn merge_json(&self, text: String, now: i64) -> Result<Snapshot, CoreError> {
        let other = store::decode(&text, now).inspect_err(|e| crate::diag::event(format!("merge: rejected ({})", e.kind())))?;
        let mut i = self.inner.lock().unwrap();
        let before = i.lib.clone();
        i.lib.merge(&other);
        if i.lib != before {
            i.undo.clear();
            Self::persist(&mut i);
        }
        Ok(Self::make_snapshot(&i))
    }
}

impl StCore {
    pub(crate) fn sync_identity(&self) -> Identity {
        self.inner.lock().unwrap().identity.clone()
    }

    pub(crate) fn remember_peer(&self, device_id: &str, name: &str, public_key: &[u8], now: i64) {
        let mut i = self.inner.lock().unwrap();
        i.identity.remember(device_id, name, public_key, now);
        if let Some(p) = i.identity_path.clone() {
            let _ = write_identity(&p, &i.identity);
        }
    }

    /// Сколько строк во всех списках — чтобы сказать, сколько пришло при синхронизации.
    pub(crate) fn total_entries(&self) -> i32 {
        self.inner.lock().unwrap().lib.notebooks.iter().map(|n| n.entries.len() as i32).sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Status;

    #[test]
    fn diagnostics_and_journal_never_contain_user_text() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("stranitsa.json").to_string_lossy().to_string();
        let core = StCore::new(p, 1);
        core.add("Секретная задача про Машу".into(), 10);
        core.done(core.snapshot().entries[0].id.clone(), 15);
        core.add_list("Тайный проект".into(), 20);
        let _ = core.import_json("{\"version\":3,\"notebooks\":[{\"text\":\"Секретная цитата\"}]}".into(), 40);
        let _ = core.merge_json("{\"entries\":\"Секретная цитата\"}".into(), 50);
        core.cleanup(0, 60);
        core.flush();
        let all = [core.diagnostics(), crate::diag::events()].concat().join("\n");
        for word in ["Секрет", "Маш", "Тайн", "цитат"] {
            assert!(!all.contains(word), "{word} leaked into:\n{all}");
        }
        assert!(all.contains("lists: 2"), "{all}");
        assert!(all.contains("import: rejected (bad format)"), "{all}");
    }

    #[test]
    fn unreadable_data_file_is_set_aside_not_overwritten() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("stranitsa.json");
        let original = [0xffu8, 0xfe, b'{', b'x'];
        std::fs::write(&path, original).unwrap();
        let core = StCore::new(path.to_string_lossy().to_string(), 1);
        core.add("новая".into(), 2);
        core.flush();
        let aside: Vec<_> = std::fs::read_dir(dir.path())
            .unwrap()
            .map(|e| e.unwrap().path())
            .filter(|p| p.to_string_lossy().contains(".broken-"))
            .collect();
        assert_eq!(aside.len(), 1);
        assert_eq!(std::fs::read(&aside[0]).unwrap(), original);
    }

    #[test]
    fn cleanup_is_off_by_default_and_does_nothing_until_enabled() {
        assert!(!Settings::default().cleanup_enabled);
        // Файл старой версии: в настройках нет нового поля.
        let mut v: serde_json::Value = serde_json::from_str(&StCore::in_memory(1).export_json()).unwrap();
        assert!(v["settings"].as_object_mut().unwrap().remove("cleanupEnabled").is_some());
        let lib = crate::store::decode(&v.to_string(), 1).unwrap();
        assert!(!lib.settings.cleanup_enabled);

        let day = 86_400_000i64;
        let core = StCore::in_memory(0);
        core.add("старое".into(), 10);
        let id = core.snapshot().entries[0].id.clone();
        core.done(id, 100);
        assert_eq!(core.cleanup_preview(30, 100 * day), 1);
        assert_eq!(core.cleanup(30, 100 * day).entries.len(), 1, "выключено — ничего не убирается");
        let mut set = core.snapshot().settings;
        set.cleanup_enabled = true;
        core.set_settings(set);
        assert_eq!(core.cleanup(30, 100 * day).entries.len(), 0);
    }

    #[test]
    fn actions_persist_and_reload() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("stranitsa.json").to_string_lossy().to_string();
        let core = StCore::new(p.clone(), 1);
        core.add("первая".into(), 10);
        let s = core.add("вторая".into(), 20);
        let id = s.entries[0].id.clone();
        core.done(id, 30);
        core.flush();
        let again = StCore::new(p, 40).snapshot();
        assert_eq!(again.entries.len(), 2);
        assert_eq!(again.entries[0].status, Status::Done);
    }

    #[test]
    fn background_writes_end_with_the_latest_state_and_drop_flushes() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("stranitsa.json").to_string_lossy().to_string();
        {
            let core = StCore::new(p.clone(), 1);
            for k in 0..200 {
                core.add(format!("t{k}"), 10 + k);
            }
            // без явного flush: запись при закрытии ядра дописывает последнее состояние
        }
        let again = StCore::new(p.clone(), 999).snapshot();
        assert_eq!(again.entries.len(), 200);
        assert_eq!(again.entries[199].text, "t199");
        assert!(!dir.path().join("stranitsa.json.tmp").exists(), "временный файл не остаётся");
    }

    #[test]
    fn save_error_is_reported_after_flush() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("нет такой папки").join("stranitsa.json").to_string_lossy().to_string();
        let core = StCore::new(p, 1);
        core.add("a".into(), 2);
        core.flush();
        assert!(core.last_save_error().is_some());
    }

    #[test]
    fn cleanup_is_undoable_and_does_not_come_back_through_sync() {
        let day = 86_400_000i64;
        let phone = StCore::in_memory(0);
        let pc = StCore::in_memory(0);
        let mut on = phone.snapshot().settings;
        on.cleanup_enabled = true;
        phone.set_settings(on);
        phone.add("старое".into(), 10);
        phone.add("новое".into(), 20);
        let old = phone.snapshot().entries[0].id.clone();
        phone.done(old, 100);
        // компьютер получает копию
        pc.merge_json(phone.export_json(), 200).unwrap();
        assert_eq!(pc.snapshot().entries.len(), 2);
        let now = 100 * day;
        assert_eq!(phone.cleanup_preview(30, now), 1);
        let s = phone.cleanup(30, now);
        assert_eq!(s.entries.len(), 1);
        // слияние с компьютером, у которого строка ещё есть, её не возвращает
        phone.merge_json(pc.export_json(), now + 1).unwrap();
        assert_eq!(phone.snapshot().entries.len(), 1);
        // и убирает её на компьютере
        pc.merge_json(phone.export_json(), now + 2).unwrap();
        assert_eq!(pc.snapshot().entries.len(), 1);
        // отмена возвращает строку, и уже насовсем
        let s = phone.undo(now + 3);
        assert_eq!(s.entries.len(), 2);
        pc.merge_json(phone.export_json(), now + 4).unwrap();
        assert_eq!(pc.snapshot().entries.len(), 2);
    }

    #[test]
    fn snapshot_has_page_data() {
        let core = StCore::in_memory(0);
        core.set_page_size(10);
        for k in 0..23 {
            core.add(format!("t{k}"), k);
        }
        let s = core.snapshot();
        assert_eq!(s.last_page, 2);
        assert_eq!(s.page_active_count, [10, 10, 3]);
        assert!(s.dismiss_applies);
        assert_eq!(s.next_page, 1);
    }

    #[test]
    fn undo_keeps_settings_and_is_reported() {
        let core = StCore::in_memory(0);
        assert!(!core.snapshot().can_undo);
        assert!(core.add("a".into(), 1).can_undo);
        let mut st = core.snapshot().settings;
        st.pure_black = true;
        core.set_settings(st);
        let id = core.snapshot().entries[0].id.clone();
        core.done(id, 2);
        let s = core.undo(3);
        assert_eq!(s.entries[0].status, Status::Active);
        assert!(s.settings.pure_black);
    }

    #[test]
    fn share_into_other_list() {
        let core = StCore::in_memory(0);
        let home = core.snapshot().current_id;
        core.add_list("Работа".into(), 1);
        let s = core.add_to(home.clone(), vec!["молоко".into(), "хлеб".into()], 2);
        assert!(s.entries.is_empty(), "текущий — «Работа», туда ничего не попало");
        let s = core.switch_list(home);
        assert_eq!(s.entries.iter().map(|e| e.text.as_str()).collect::<Vec<_>>(), ["молоко", "хлеб"]);
    }

    #[test]
    fn merge_two_cores() {
        let phone = StCore::in_memory(0);
        phone.add("с телефона".into(), 10);
        let pc = StCore::in_memory(0);
        pc.import_json(phone.export_json(), 11).unwrap(); // первое знакомство
        pc.add("с ПК".into(), 20);
        phone.add("ещё с телефона".into(), 15);
        phone.merge_json(pc.export_json(), 30).unwrap();
        pc.merge_json(phone.export_json(), 31).unwrap();
        let a: Vec<_> = phone.snapshot().entries.iter().map(|e| e.text.clone()).collect();
        let b: Vec<_> = pc.snapshot().entries.iter().map(|e| e.text.clone()).collect();
        assert_eq!(a, ["с телефона", "ещё с телефона", "с ПК"]);
        assert_eq!(a, b);
        assert_ne!(phone.device_id(), pc.device_id());
    }

    #[test]
    fn import_rejects_bad_file_without_changes() {
        let core = StCore::in_memory(0);
        core.add("a".into(), 1);
        assert!(core.import_json("{nope".into(), 2).is_err());
        assert_eq!(core.snapshot().entries.len(), 1);
    }
}
