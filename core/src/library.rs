//! Набор списков: создание, переименование, удаление, слияние двух копий и отмена.
//!
//! Слияние (синхронизация без сервера) — «последнее изменение побеждает» на уровне строки:
//! * строки объединяются по id; если строку меняли на обоих устройствах, берётся версия с большим `updated_at`;
//! * порядок — по времени записи (`created_at`, затем id), поэтому он одинаков на всех устройствах;
//! * удаления хранятся «надгробиями» и побеждают всё, что изменено не позже момента удаления;
//! * настройки, текущий список, рабочая страница и размер страницы — свои у каждого устройства и не сливаются.

use crate::model::{new_id, Entry, Library, Notebook};
use std::collections::{BTreeMap, HashMap};

impl Library {
    // ---------- списки ----------

    pub fn switch_to(&mut self, id: &str) -> bool {
        if self.notebooks.iter().any(|n| n.id == id) && self.current_id != id {
            self.current_id = id.to_string();
            true
        } else {
            false
        }
    }

    /// Новый список; сразу становится текущим. Размер страницы — как у текущего.
    pub fn add_notebook(&mut self, name: &str, now: i64) -> bool {
        let n = name.trim();
        if n.is_empty() {
            return false;
        }
        let nb = Notebook::new(new_id(), n.to_string(), now, self.current().page_size);
        self.current_id = nb.id.clone();
        self.notebooks.push(nb);
        true
    }

    pub fn rename_notebook(&mut self, id: &str, name: &str, now: i64) -> bool {
        let n = name.trim();
        if n.is_empty() {
            return false;
        }
        match self.notebook_mut(id) {
            Some(nb) if nb.name != n => {
                nb.name = n.to_string();
                nb.name_updated_at = now;
                true
            }
            _ => false,
        }
    }

    /// Последний список удалить нельзя. Если удаляем текущий — переходим на первый оставшийся.
    pub fn delete_notebook(&mut self, id: &str, now: i64) -> bool {
        if self.notebooks.len() <= 1 || !self.notebooks.iter().any(|n| n.id == id) {
            return false;
        }
        self.notebooks.retain(|n| n.id != id);
        self.deleted_notebooks.insert(id.to_string(), now);
        if self.current_id == id {
            self.current_id = self.notebooks[0].id.clone();
        }
        true
    }

    // ---------- слияние ----------

    /// Влить копию с другого устройства. Своё (настройки, текущий список, страницы) не меняется.
    pub fn merge(&mut self, other: &Library) {
        // надгробия списков
        let mut deleted_nb = self.deleted_notebooks.clone();
        for (id, t) in &other.deleted_notebooks {
            let e = deleted_nb.entry(id.clone()).or_insert(*t);
            *e = (*e).max(*t);
        }

        let mut result: Vec<Notebook> = Vec::new();
        let mut seen: Vec<String> = Vec::new();
        let ids = self.notebooks.iter().map(|n| n.id.clone()).chain(other.notebooks.iter().map(|n| n.id.clone()));
        for id in ids {
            if seen.contains(&id) {
                continue;
            }
            seen.push(id.clone());
            let mine = self.notebooks.iter().find(|n| n.id == id);
            let theirs = other.notebooks.iter().find(|n| n.id == id);
            let merged = match (mine, theirs) {
                (Some(a), Some(b)) => merge_notebook(a, b),
                (Some(a), None) => a.clone(),
                (None, Some(b)) => {
                    // Новый для нас список: страницы — с нуля, размер страницы — как у нашего текущего.
                    let mut nb = b.clone();
                    nb.page_size = self.current().page_size;
                    nb.current_page = 0;
                    nb.acted_on_visit = false;
                    nb
                }
                (None, None) => unreachable!(),
            };
            // список жив, если его меняли позже, чем удалили
            let alive = match deleted_nb.get(&id) {
                Some(&t) => merged.updated_at.max(merged.name_updated_at) > t,
                None => true,
            };
            if alive {
                result.push(merged);
            }
        }
        if result.is_empty() {
            // Все списки удалены на разных устройствах — оставляем один пустой.
            result.push(Notebook::new(new_id(), String::new(), 0, self.current().page_size));
        }
        for nb in &mut result {
            nb.clamp_page();
        }
        self.notebooks = result;
        self.deleted_notebooks = deleted_nb;
        if !self.notebooks.iter().any(|n| n.id == self.current_id) {
            self.current_id = self.notebooks[0].id.clone();
        }
    }

    // ---------- отмена ----------

    /// Вернуть список к прежнему снимку так, чтобы синхронизация этого не перекрыла:
    /// всё, что отмена меняет, получает время `now` (иначе другое устройство со «свежей» версией
    /// победило бы при слиянии). Строки, которых в снимке не было, удаляются надгробием.
    /// Настройки и размеры страниц не трогаются.
    pub fn restore_from(&mut self, prev: &Library, now: i64) {
        let mut out: Vec<Notebook> = Vec::new();
        for p in &prev.notebooks {
            let mut nb = p.clone();
            match self.notebooks.iter().find(|n| n.id == p.id) {
                Some(cur) => {
                    let cur_by_id: HashMap<&str, &Entry> = cur.entries.iter().map(|e| (e.id.as_str(), e)).collect();
                    for e in &mut nb.entries {
                        if cur_by_id.get(e.id.as_str()).is_none_or(|c| *c != e) {
                            e.updated_at = now;
                        }
                    }
                    let prev_ids: Vec<&str> = p.entries.iter().map(|e| e.id.as_str()).collect();
                    let mut deleted = cur.deleted_entries.clone();
                    for e in &cur.entries {
                        if !prev_ids.contains(&e.id.as_str()) {
                            deleted.insert(e.id.clone(), now);
                        }
                    }
                    nb.deleted_entries = deleted;
                    if nb.name != cur.name {
                        nb.name_updated_at = now;
                    }
                    nb.page_size = cur.page_size;
                    let size = cur.page_size;
                    if size != p.page_size {
                        // рабочая страница из снимка — в единицах старого размера
                        let first = (p.current_page * p.page_size) as usize;
                        nb.current_page = (first.min(nb.entries.len().saturating_sub(1)) as i32) / size;
                    }
                }
                None => {
                    // список был удалён — восстанавливаем, он «новее» своего надгробия
                    nb.updated_at = now;
                    for e in &mut nb.entries {
                        e.updated_at = now;
                    }
                }
            }
            nb.clamp_page();
            out.push(nb);
        }
        let mut deleted_nb: BTreeMap<String, i64> = self.deleted_notebooks.clone();
        for p in &prev.notebooks {
            deleted_nb.remove(&p.id);
        }
        for cur in &self.notebooks {
            if !prev.notebooks.iter().any(|p| p.id == cur.id) {
                deleted_nb.insert(cur.id.clone(), now);
            }
        }
        self.notebooks = out;
        self.deleted_notebooks = deleted_nb;
        self.current_id = prev.current_id.clone();
        if !self.notebooks.iter().any(|n| n.id == self.current_id) {
            self.current_id = self.notebooks[0].id.clone();
        }
    }
}

fn newer(a: &Entry, b: &Entry) -> bool {
    // true, если `a` новее `b`; при равенстве — детерминированно, одинаково на всех устройствах
    (a.updated_at, a.status.rank(), &a.text, a.reentries, a.reviewed)
        > (b.updated_at, b.status.rank(), &b.text, b.reentries, b.reviewed)
}

fn merge_notebook(a: &Notebook, b: &Notebook) -> Notebook {
    let mut deleted = a.deleted_entries.clone();
    for (id, t) in &b.deleted_entries {
        let e = deleted.entry(id.clone()).or_insert(*t);
        *e = (*e).max(*t);
    }
    let mut by_id: HashMap<String, Entry> = HashMap::new();
    for e in a.entries.iter().chain(b.entries.iter()) {
        match by_id.get(&e.id) {
            Some(x) if !newer(e, x) => {}
            _ => {
                by_id.insert(e.id.clone(), e.clone());
            }
        }
    }
    let mut entries: Vec<Entry> = by_id
        .into_values()
        .filter(|e| deleted.get(&e.id).is_none_or(|&t| e.updated_at > t))
        .collect();
    entries.sort_by(|x, y| x.created_at.cmp(&y.created_at).then_with(|| x.id.cmp(&y.id)));

    let (name, name_updated_at) = if b.name_updated_at > a.name_updated_at
        || (b.name_updated_at == a.name_updated_at && b.name > a.name)
    {
        (b.name.clone(), b.name_updated_at)
    } else {
        (a.name.clone(), a.name_updated_at)
    };
    Notebook {
        id: a.id.clone(),
        name,
        name_updated_at,
        updated_at: a.updated_at.max(b.updated_at),
        entries,
        deleted_entries: deleted,
        // своё — от нашего устройства
        page_size: a.page_size,
        current_page: a.current_page,
        acted_on_visit: a.acted_on_visit,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Status;

    fn texts(nb: &Notebook) -> Vec<String> {
        nb.entries.iter().map(|e| e.text.clone()).collect()
    }

    /// Два «устройства» с общим началом: B получает копию A, как после первой синхронизации.
    fn pair() -> (Library, Library) {
        let mut a = Library::new(0);
        a.current_mut().add("общая 1", 10);
        a.current_mut().add("общая 2", 20);
        let mut b = a.clone();
        b.device_id = new_id();
        (a, b)
    }

    #[test]
    fn new_tasks_from_both_devices_interleave_by_time() {
        let (mut a, mut b) = pair();
        a.current_mut().add("с телефона", 100);
        b.current_mut().add("с ПК", 50);
        b.current_mut().add("с ПК 2", 150);
        let mut a2 = a.clone();
        a2.merge(&b);
        b.merge(&a);
        assert_eq!(texts(a2.current()), ["общая 1", "общая 2", "с ПК", "с телефона", "с ПК 2"]);
        assert_eq!(a2.current().entries, b.current().entries, "оба устройства видят один порядок");
    }

    #[test]
    fn last_change_wins_per_task() {
        let (mut a, mut b) = pair();
        let id = a.current().entries[0].id.clone();
        a.current_mut().done(&id, 100);
        b.current_mut().edit(&id, "переименовано позже", 200);
        a.merge(&b);
        let e = &a.current().entries[0];
        assert_eq!(e.text, "переименовано позже");
        assert_eq!(e.status, Status::Active, "у более позднего изменения задача ещё активна");
    }

    #[test]
    fn merge_is_idempotent_and_keeps_local_settings() {
        let (mut a, mut b) = pair();
        a.settings.pure_black = true;
        a.current_mut().page_size = 12;
        b.current_mut().add("x", 300);
        a.merge(&b);
        let once = a.clone();
        a.merge(&b);
        assert_eq!(a, once);
        assert!(a.settings.pure_black);
        assert_eq!(a.current().page_size, 12);
    }

    #[test]
    fn stale_copy_does_not_resurrect_deleted_list() {
        let (mut a, mut b) = pair();
        a.add_notebook("Работа", 100);
        let stale = a.clone(); // копия до удаления (например, третье устройство давно не синхронизировалось)
        let work = a.current().id.clone();
        a.delete_notebook(&work, 200);
        b.merge(&a);
        b.merge(&stale);
        assert!(b.notebooks.iter().all(|n| n.id != work));
        a.merge(&stale);
        assert!(a.notebooks.iter().all(|n| n.id != work));
    }

    #[test]
    fn undo_survives_sync() {
        let (mut a, mut b) = pair();
        let id = a.current().entries[0].id.clone();
        let before = a.clone();
        a.current_mut().done(&id, 100);
        b.merge(&a); // B узнал, что задача сделана
        a.restore_from(&before, 200); // отмена на A
        b.merge(&a);
        assert_eq!(b.current().entries[0].status, Status::Active, "отмена новее и побеждает");
    }

    #[test]
    fn undo_of_add_removes_task_everywhere() {
        let (mut a, mut b) = pair();
        let before = a.clone();
        a.current_mut().add("по ошибке", 100);
        b.merge(&a);
        assert_eq!(b.current().entries.len(), 3);
        a.restore_from(&before, 200);
        assert_eq!(a.current().entries.len(), 2);
        b.merge(&a);
        assert_eq!(texts(b.current()), ["общая 1", "общая 2"]);
    }

    #[test]
    fn undo_of_delete_list_brings_it_back_through_sync() {
        let (mut a, mut b) = pair();
        a.add_notebook("Работа", 100);
        a.current_mut().add("задача", 110);
        b.merge(&a);
        let before = a.clone();
        let work = a.current().id.clone();
        a.delete_notebook(&work, 200);
        b.merge(&a);
        assert!(b.notebooks.iter().all(|n| n.id != work));
        a.restore_from(&before, 300);
        b.merge(&a);
        let nb = b.notebooks.iter().find(|n| n.id == work).expect("список вернулся");
        assert_eq!(texts(nb), ["задача"]);
    }

    #[test]
    fn lists_add_rename_switch_delete() {
        let mut l = Library::new(0);
        l.current_mut().add("дом 1", 0);
        let home = l.current().id.clone();
        assert!(l.add_notebook("Работа", 1));
        assert_eq!(l.current().name, "Работа");
        l.current_mut().add("работа 1", 2);
        assert_eq!(texts(&l.notebooks[0]), ["дом 1"]);
        l.rename_notebook(&home, "Дом", 3);
        l.switch_to(&home);
        assert_eq!(l.current().name, "Дом");
        assert!(l.delete_notebook(&home, 4));
        assert_eq!(l.current().name, "Работа");
        let only = l.current().id.clone();
        assert!(!l.delete_notebook(&only, 5), "последний не удаляется");
        assert!(!l.add_notebook("  ", 6));
    }

    /// Два новых устройства, каждое со своим «Мой список», — после синхронизации один список.
    #[test]
    fn default_lists_of_two_new_devices_become_one() {
        let mut phone = Library::new(0);
        phone.current_mut().add("с телефона", 10);
        let mut pc = Library::new(50);
        pc.current_mut().add("с ПК", 60);
        phone.merge(&pc);
        assert_eq!(phone.notebooks.len(), 1);
        assert_eq!(texts(phone.current()), ["с телефона", "с ПК"]);
    }

    /// Переименование на телефоне не перебивается именем по умолчанию с нового ПК.
    #[test]
    fn rename_beats_default_name_of_new_device() {
        let mut phone = Library::new(0);
        let id = phone.current().id.clone();
        phone.rename_notebook(&id, "Дом", 100);
        let pc = Library::new(1_000); // ПК появился позже
        phone.merge(&pc);
        assert_eq!(phone.current().name, "Дом");
    }

    #[test]
    fn rename_last_wins() {
        let (mut a, mut b) = pair();
        let id = a.current().id.clone();
        a.rename_notebook(&id, "Дом", 100);
        b.rename_notebook(&id, "Квартира", 200);
        a.merge(&b);
        assert_eq!(a.current().name, "Квартира");
    }
}
