//! Данные. Что синхронизируется между устройствами, а что остаётся на устройстве:
//!
//! * общее (сливается при синхронизации): списки, их имена, задачи и их состояния, «надгробия» удалённого;
//! * своё у каждого устройства: настройки, текущий список, рабочая страница, «первый проход»,
//!   размер страницы (на ПК удобно больше строк, чем на телефоне).

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Состояние строки в тетради.
#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Eq, Debug, Hash, uniffi::Enum)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Status {
    /// Ждёт, пока «выделится».
    Active,
    /// Выполнена и вычеркнута.
    Done,
    /// Поработали, не закончили: вычеркнута здесь и переписана в конец.
    Reentered,
    /// Отброшена по правилу AF1.
    Dismissed,
    /// Удалена вручную.
    Cancelled,
}

impl Status {
    /// Для детерминированного выбора при полном совпадении времени изменения.
    pub(crate) fn rank(self) -> u8 {
        match self {
            Status::Active => 0,
            Status::Reentered => 1,
            Status::Dismissed => 2,
            Status::Cancelled => 3,
            Status::Done => 4,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Eq, Debug, Default, uniffi::Enum)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ThemeMode {
    #[default]
    System,
    Light,
    Dark,
}

/// Строка списка.
#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Entry {
    /// Глобально уникальный идентификатор (UUID): задачи с разных устройств не путаются.
    pub id: String,
    pub text: String,
    pub status: Status,
    /// Время записи в список, мс. Внутри списка строго возрастает — это и есть порядок строк.
    pub created_at: i64,
    #[serde(default)]
    pub closed_at: Option<i64>,
    /// Сколько раз задача уже переписывалась в конец.
    #[serde(default)]
    pub reentries: i32,
    /// Для отброшенных: уже разобрана в «Отброшенных».
    #[serde(default)]
    pub reviewed: bool,
    /// Время последнего изменения, мс. При слиянии побеждает более позднее.
    pub updated_at: i64,
}

/// Общие настройки устройства.
#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug, uniffi::Record)]
#[serde(rename_all = "camelCase", default)]
pub struct Settings {
    pub show_hints: bool,
    pub theme: ThemeMode,
    /// AMOLED: чистый чёрный фон в тёмной теме.
    pub pure_black: bool,
    /// Цвета обоев (Material You).
    pub dynamic_color: bool,
    /// Системная резервная копия. По умолчанию выключена — пользователь включает сам.
    pub system_backup: bool,
    /// Когда приложение в последний раз было открыто, мс.
    pub last_seen: i64,
    /// Автосинхронизация со знакомыми устройствами рядом: раз в столько минут; 0 — выключена.
    #[uniffi(default = 0)]
    pub auto_sync_minutes: i32,
    /// Проверять, не вышла ли новая версия (GitHub Releases).
    #[uniffi(default = true)]
    pub check_updates: bool,
    /// После очистки показывать на главном экране строку «Отменить». По умолчанию выключено:
    /// отменить можно и в настройках сразу после очистки.
    #[uniffi(default = false)]
    pub show_cleanup_undo: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            show_hints: true,
            theme: ThemeMode::System,
            pure_black: false,
            dynamic_color: false,
            system_backup: false,
            last_seen: 0,
            auto_sync_minutes: 0,
            check_updates: true,
            show_cleanup_undo: false,
        }
    }
}

pub const DEFAULT_PAGE_SIZE: i32 = 25;
pub const MIN_PAGE_SIZE: i32 = 10;
pub const MAX_PAGE_SIZE: i32 = 40;

/// Отдельная «тетрадь» — Форстер советует свой список для каждого места.
#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Notebook {
    pub id: String,
    /// Пустое имя — список по умолчанию; подпись берётся из ресурсов на языке интерфейса.
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub name_updated_at: i64,
    /// Когда список создан или восстановлен; сравнивается с «надгробием» при слиянии.
    #[serde(default)]
    pub updated_at: i64,
    /// Видимые строки по порядку (по возрастанию `created_at`, затем `id`).
    #[serde(default)]
    pub entries: Vec<Entry>,
    /// Удалённые строки (отмена добавления): id → когда удалена. Нужны, чтобы слияние их не воскресило.
    #[serde(default)]
    pub deleted_entries: BTreeMap<String, i64>,

    // --- своё у каждого устройства ---
    #[serde(default = "default_page_size")]
    pub page_size: i32,
    #[serde(default)]
    pub current_page: i32,
    #[serde(default)]
    pub acted_on_visit: bool,
}

fn default_page_size() -> i32 {
    DEFAULT_PAGE_SIZE
}

impl Notebook {
    pub fn new(id: String, name: String, now: i64, page_size: i32) -> Self {
        Notebook {
            id,
            name,
            name_updated_at: now,
            updated_at: now,
            entries: Vec::new(),
            deleted_entries: BTreeMap::new(),
            page_size,
            current_page: 0,
            acted_on_visit: false,
        }
    }
}

/// Всё, что хранит приложение.
#[derive(Serialize, Deserialize, Clone, PartialEq, Eq, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Library {
    /// Версия формата файла.
    pub version: u32,
    /// Идентификатор этого устройства (для синхронизации).
    pub device_id: String,
    pub notebooks: Vec<Notebook>,
    /// Удалённые списки: id → когда удалён.
    #[serde(default)]
    pub deleted_notebooks: BTreeMap<String, i64>,

    // --- своё у каждого устройства ---
    pub current_id: String,
    #[serde(default)]
    pub settings: Settings,
}

/// v3: у списка по умолчанию общий id на всех устройствах.
pub const FORMAT_VERSION: u32 = 3;

/// Список по умолчанию — один и тот же на всех устройствах, чтобы при первой синхронизации
/// «Мой список» с телефона и с ПК слились в один, а не стали двумя.
pub const DEFAULT_NOTEBOOK_ID: &str = "default";

pub fn new_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

impl Library {
    pub fn new(now: i64) -> Self {
        let mut nb = Notebook::new(DEFAULT_NOTEBOOK_ID.into(), String::new(), now, DEFAULT_PAGE_SIZE);
        // имя никто не задавал — переименование на любом устройстве должно победить
        nb.name_updated_at = 0;
        Library {
            version: FORMAT_VERSION,
            device_id: new_id(),
            current_id: nb.id.clone(),
            notebooks: vec![nb],
            deleted_notebooks: BTreeMap::new(),
            settings: Settings::default(),
        }
    }

    pub fn current_index(&self) -> usize {
        self.notebooks.iter().position(|n| n.id == self.current_id).unwrap_or(0)
    }

    pub fn current(&self) -> &Notebook {
        &self.notebooks[self.current_index()]
    }

    pub fn current_mut(&mut self) -> &mut Notebook {
        let i = self.current_index();
        &mut self.notebooks[i]
    }

    pub fn notebook_mut(&mut self, id: &str) -> Option<&mut Notebook> {
        self.notebooks.iter_mut().find(|n| n.id == id)
    }
}
