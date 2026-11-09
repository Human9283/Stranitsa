//! Общее ядро «Страницы» (метод Autofocus 1, Mark Forster) для Android, iOS и ПК.
//!
//! * [`model`] — данные и формат файла;
//! * [`rules`] — правила AF1 над одним списком;
//! * [`library`] — несколько списков, слияние копий с разных устройств, отмена;
//! * [`text`] — поиск и разбор текста из «Поделиться»;
//! * [`store`] — файл: чтение с миграцией старых форматов, атомарная запись;
//! * [`core`] — объект для приложений (через UniFFI: Kotlin, Swift);
//! * [`diag`] — журнал служебных событий и файл о сбое для отчёта об ошибке.

uniffi::setup_scaffolding!();

pub mod rules;
pub mod core;
pub mod diag;
pub mod library;
pub mod model;
pub mod store;
pub mod sync;
pub mod text;

pub use crate::core::{StCore, NotebookInfo, Snapshot};
pub use crate::model::{Entry, Settings, Status, ThemeMode};
pub use crate::store::CoreError;
pub use crate::text::{join_shared_text, split_shared_text, HitRange, SearchHit};
pub use crate::sync::{KnownPeer, NearbyPeer, PeerInfo, SyncBrowser, SyncError, SyncHost, SyncReport, SyncSession};

/// Границы размера страницы — чтобы интерфейсы не дублировали числа.
#[uniffi::export]
pub fn page_size_limits() -> Vec<i32> {
    vec![model::MIN_PAGE_SIZE, model::DEFAULT_PAGE_SIZE, model::MAX_PAGE_SIZE]
}
