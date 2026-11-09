//! Правила Autofocus 1 (Mark Forster) над одним списком.
//!
//! * Один длинный список, новые задачи — в конец.
//! * Работаем постранично: быстро читаем страницу, затем медленно, пока что-то не «выделится».
//! * Работаем над задачей сколько хочется; не закончили — вычёркиваем и переписываем в конец.
//! * Кружим по странице, пока проход не даст ни одной задачи, затем — следующая страница.
//! * Если при первом проходе по странице ничего не выделилось, все её оставшиеся задачи
//!   отбрасываются (кроме последней страницы, на которой ещё пишем).
//! * После последней страницы — снова первая активная.

use crate::model::{new_id, Entry, Notebook, Status, MAX_PAGE_SIZE, MIN_PAGE_SIZE};

impl Notebook {
    // ---------- страницы ----------

    pub fn page_count(&self) -> i32 {
        if self.entries.is_empty() {
            1
        } else {
            (self.entries.len() as i32 + self.page_size - 1) / self.page_size
        }
    }

    pub fn last_page(&self) -> i32 {
        self.page_count() - 1
    }

    pub fn page_of_index(&self, index: usize) -> i32 {
        index as i32 / self.page_size
    }

    pub fn page_entries(&self, page: i32) -> &[Entry] {
        if page < 0 {
            return &[];
        }
        let from = (page * self.page_size) as usize;
        if from >= self.entries.len() {
            return &[];
        }
        let to = (from + self.page_size as usize).min(self.entries.len());
        &self.entries[from..to]
    }

    pub fn active_count_on_page(&self, page: i32) -> i32 {
        self.page_entries(page).iter().filter(|e| e.status == Status::Active).count() as i32
    }

    pub fn is_page_active(&self, page: i32) -> bool {
        self.active_count_on_page(page) > 0
    }

    pub fn first_active_page(&self) -> i32 {
        (0..=self.last_page()).find(|&p| self.is_page_active(p)).unwrap_or(self.last_page())
    }

    /// Следующая активная страница после рабочей; если таких нет — по кругу к первой активной.
    pub fn next_page(&self) -> i32 {
        for p in self.current_page + 1..=self.last_page() {
            if self.is_page_active(p) {
                return p;
            }
        }
        self.first_active_page()
    }

    /// Применимо ли правило отбрасывания при уходе с рабочей страницы.
    pub fn dismiss_applies(&self) -> bool {
        !self.acted_on_visit && self.current_page != self.last_page() && self.is_page_active(self.current_page)
    }

    pub fn active_total(&self) -> i32 {
        self.entries.iter().filter(|e| e.status == Status::Active).count() as i32
    }

    /// Сколько закрыто как «Сделано» начиная с `since`. Перенесённые в конец не считаются.
    pub fn done_since(&self, since: i64) -> i32 {
        self.entries
            .iter()
            .filter(|e| e.status == Status::Done && e.closed_at.unwrap_or(0) >= since)
            .count() as i32
    }

    /// Отброшенные, ещё не разобранные; новые сверху.
    pub fn dismissed_for_review(&self) -> Vec<Entry> {
        self.entries
            .iter()
            .rev()
            .filter(|e| e.status == Status::Dismissed && !e.reviewed)
            .cloned()
            .collect()
    }

    // ---------- действия ----------

    /// Время записи новой строки: строго больше предыдущей — так локальный порядок
    /// совпадает с порядком сортировки при слиянии, даже если часы откатились.
    fn next_created_at(&self, now: i64) -> i64 {
        match self.entries.last() {
            Some(last) if last.created_at >= now => last.created_at + 1,
            _ => now,
        }
    }

    fn push(&mut self, text: String, now: i64, reentries: i32) {
        let created_at = self.next_created_at(now);
        self.entries.push(Entry {
            id: new_id(),
            text,
            status: Status::Active,
            created_at,
            closed_at: None,
            reentries,
            reviewed: false,
            updated_at: now,
        });
    }

    /// Новая задача в конец. Пустой текст игнорируется. Возвращает, добавлено ли.
    pub fn add(&mut self, text: &str, now: i64) -> bool {
        let t = text.trim();
        if t.is_empty() {
            return false;
        }
        let had_active = self.entries.iter().any(|e| e.status == Status::Active);
        self.push(t.to_string(), now, 0);
        if !had_active {
            // Список был пуст — начинаем работать со страницы новой задачи.
            self.current_page = self.last_page();
            self.acted_on_visit = false;
        }
        true
    }

    fn index_of_active(&self, id: &str) -> Option<usize> {
        self.entries.iter().position(|e| e.id == id && e.status == Status::Active)
    }

    fn close(&mut self, id: &str, status: Status, now: i64, counts_as_action: bool) -> bool {
        let Some(i) = self.index_of_active(id) else { return false };
        let on_current = self.page_of_index(i) == self.current_page;
        let e = &mut self.entries[i];
        e.status = status;
        e.closed_at = Some(now);
        e.updated_at = now;
        if counts_as_action && on_current {
            self.acted_on_visit = true;
        }
        true
    }

    pub fn done(&mut self, id: &str, now: i64) -> bool {
        self.close(id, Status::Done, now, true)
    }

    /// Поработали, но не закончили: вычеркнуть и переписать в конец.
    pub fn reenter(&mut self, id: &str, now: i64) -> bool {
        let Some(i) = self.index_of_active(id) else { return false };
        let (text, reentries) = (self.entries[i].text.clone(), self.entries[i].reentries);
        self.close(id, Status::Reentered, now, true);
        self.push(text, now, reentries + 1);
        true
    }

    pub fn cancel(&mut self, id: &str, now: i64) -> bool {
        self.close(id, Status::Cancelled, now, false)
    }

    pub fn edit(&mut self, id: &str, text: &str, now: i64) -> bool {
        let t = text.trim();
        if t.is_empty() {
            return false;
        }
        match self.entries.iter_mut().find(|e| e.id == id) {
            Some(e) if e.text != t => {
                e.text = t.to_string();
                e.updated_at = now;
                true
            }
            _ => false,
        }
    }

    /// Уйти с рабочей страницы. Если `dismiss` и правило применимо — оставшиеся задачи отбрасываются.
    pub fn advance(&mut self, dismiss: bool, now: i64) {
        if dismiss && self.dismiss_applies() {
            let from = (self.current_page * self.page_size) as usize;
            let to = (from + self.page_size as usize).min(self.entries.len());
            for e in &mut self.entries[from..to] {
                if e.status == Status::Active {
                    e.status = Status::Dismissed;
                    e.closed_at = Some(now);
                    e.updated_at = now;
                }
            }
        }
        self.current_page = self.next_page();
        self.acted_on_visit = false;
    }

    pub fn go_to(&mut self, page: i32) {
        let p = page.clamp(0, self.last_page());
        if p != self.current_page {
            self.current_page = p;
            self.acted_on_visit = false;
        }
    }

    /// Новый размер страницы; рабочая страница пересчитывается так, чтобы её первая строка осталась на ней.
    pub fn set_page_size(&mut self, size: i32) {
        let n = size.clamp(MIN_PAGE_SIZE, MAX_PAGE_SIZE);
        if n == self.page_size {
            return;
        }
        let first = ((self.current_page * self.page_size) as usize).min(self.entries.len().saturating_sub(1));
        self.page_size = n;
        self.current_page = self.page_of_index(first);
        self.acted_on_visit = false;
    }

    fn mark_reviewed(&mut self, id: &str, now: i64) -> bool {
        match self.entries.iter_mut().find(|e| e.id == id) {
            Some(e) if !e.reviewed => {
                e.reviewed = true;
                e.updated_at = now;
                true
            }
            _ => false,
        }
    }

    /// Вернуть отброшенную задачу в конец (возможно, переформулировав).
    pub fn revive(&mut self, id: &str, text: &str, now: i64) -> bool {
        if text.trim().is_empty() {
            return false;
        }
        self.mark_reviewed(id, now);
        self.add(text, now)
    }

    /// Окончательно забыть отброшенную задачу.
    pub fn forget(&mut self, id: &str, now: i64) -> bool {
        self.mark_reviewed(id, now)
    }

    fn removable(e: &Entry, before: i64) -> bool {
        let closed_before = e.closed_at.is_some_and(|t| t < before);
        match e.status {
            Status::Active => false,
            // Отброшенное, которое ещё не разобрано, нужно показать в «Отброшенных» — не трогаем.
            Status::Dismissed => e.reviewed && closed_before,
            _ => closed_before,
        }
    }

    /// Сколько закрытых строк `cleanup(before, …)` убрал бы.
    pub fn cleanup_count(&self, before: i64) -> usize {
        self.entries.iter().filter(|e| Self::removable(e, before)).count()
    }

    /// Убрать закрытые строки, закрытые раньше `before` (выполненные, перенесённые, удалённые и
    /// уже разобранные отброшенные). Активные не трогаются никогда. Нумерация страниц сдвигается,
    /// поэтому рабочая страница пересчитывается так, чтобы её первая оставшаяся строка осталась на ней.
    /// Для каждой убранной строки остаётся «надгробие» — слияние с другим устройством её не вернёт.
    pub fn cleanup(&mut self, before: i64, now: i64) -> usize {
        let from = ((self.current_page * self.page_size) as usize).min(self.entries.len());
        let anchor: Option<String> =
            self.entries.iter().skip(from).find(|e| !Self::removable(e, before)).map(|e| e.id.clone());
        let mut removed = 0;
        let mut kept = Vec::with_capacity(self.entries.len());
        for e in std::mem::take(&mut self.entries) {
            if Self::removable(&e, before) {
                self.deleted_entries.insert(e.id.clone(), now.max(e.updated_at + 1));
                removed += 1;
            } else {
                kept.push(e);
            }
        }
        self.entries = kept;
        if removed > 0 {
            let old = self.current_page;
            self.current_page = match anchor.and_then(|id| self.entries.iter().position(|e| e.id == id)) {
                Some(i) => self.page_of_index(i),
                None => self.last_page(),
            };
            if self.current_page != old {
                self.acted_on_visit = false;
            }
        }
        removed
    }

    /// Держит рабочую страницу в допустимых пределах (после слияния строк могло стать больше или меньше).
    pub fn clamp_page(&mut self) {
        self.current_page = self.current_page.clamp(0, self.last_page());
    }

    /// Строки по возрастанию времени записи; при равенстве — по id (одинаково на всех устройствах).
    pub fn sort_entries(&mut self) {
        self.entries.sort_by(|a, b| a.created_at.cmp(&b.created_at).then_with(|| a.id.cmp(&b.id)));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::DEFAULT_PAGE_SIZE;

    fn nb(n: usize, page_size: i32) -> Notebook {
        let mut s = Notebook::new("nb".into(), String::new(), 0, page_size);
        for i in 0..n {
            s.add(&format!("t{}", i + 1), 0);
        }
        s
    }

    fn id(s: &Notebook, text: &str) -> String {
        s.entries.iter().find(|e| e.text == text && e.status == Status::Active).unwrap().id.clone()
    }

    fn texts(es: &[Entry]) -> Vec<&str> {
        es.iter().map(|e| e.text.as_str()).collect()
    }

    #[test]
    fn pages_split_by_page_size() {
        let s = nb(12, 5);
        assert_eq!(s.page_count(), 3);
        assert_eq!(texts(s.page_entries(1)), ["t6", "t7", "t8", "t9", "t10"]);
        assert_eq!(s.page_entries(2).len(), 2);
        assert_eq!(s.current_page, 0);
    }

    #[test]
    fn created_at_strictly_increases_even_with_same_clock() {
        let s = nb(5, 5);
        for w in s.entries.windows(2) {
            assert!(w[0].created_at < w[1].created_at);
        }
    }

    #[test]
    fn blank_task_ignored() {
        let mut s = nb(1, 5);
        assert!(!s.add("   ", 0));
        assert_eq!(s.entries.len(), 1);
    }

    #[test]
    fn reenter_crosses_off_and_appends() {
        let mut s = nb(3, 5);
        let i = id(&s, "t1");
        s.reenter(&i, 10);
        assert_eq!(s.entries[0].status, Status::Reentered);
        let last = s.entries.last().unwrap();
        assert_eq!(last.text, "t1");
        assert_eq!(last.reentries, 1);
        assert_eq!(last.status, Status::Active);
        assert_ne!(last.id, i);
        assert!(s.acted_on_visit);
    }

    #[test]
    fn dismissal_when_nothing_stands_out_on_first_pass() {
        let mut s = nb(12, 5);
        assert!(s.dismiss_applies());
        s.advance(true, 5);
        assert!(s.page_entries(0).iter().all(|e| e.status == Status::Dismissed));
        assert_eq!(s.current_page, 1);
        assert_eq!(s.dismissed_for_review().len(), 5);
    }

    #[test]
    fn no_dismissal_after_working_on_page() {
        let mut s = nb(12, 5);
        let i = id(&s, "t2");
        s.done(&i, 1);
        assert!(!s.dismiss_applies());
        s.advance(true, 2);
        assert_eq!(s.page_entries(0).iter().filter(|e| e.status == Status::Dismissed).count(), 0);
        assert_eq!(s.current_page, 1);
        assert!(!s.acted_on_visit);
    }

    #[test]
    fn last_page_is_never_dismissed() {
        let mut s = nb(12, 5);
        s.go_to(2);
        assert!(!s.dismiss_applies());
    }

    #[test]
    fn acting_on_other_page_does_not_count() {
        let mut s = nb(12, 5);
        let i = id(&s, "t7");
        s.done(&i, 1);
        assert!(s.dismiss_applies());
    }

    #[test]
    fn wraps_from_last_page_to_first_active() {
        let mut s = nb(12, 5);
        s.advance(true, 1);
        let i = id(&s, "t6");
        s.done(&i, 2);
        s.advance(false, 3);
        assert_eq!(s.current_page, 2);
        s.advance(false, 4);
        assert_eq!(s.current_page, 1);
    }

    #[test]
    fn skips_closed_pages() {
        let mut s = nb(15, 5);
        for t in ["t6", "t7", "t8", "t9", "t10", "t1"] {
            let i = id(&s, t);
            s.done(&i, 1);
        }
        s.advance(false, 2);
        assert_eq!(s.current_page, 2);
    }

    #[test]
    fn adding_to_empty_list_jumps_to_new_page() {
        let mut s = nb(5, 5);
        for k in 1..=5 {
            let i = id(&s, &format!("t{k}"));
            s.done(&i, 1);
        }
        s.add("new", 2);
        assert_eq!(s.current_page, 1);
    }

    #[test]
    fn revive_marks_reviewed_and_appends() {
        let mut s = nb(12, 5);
        s.advance(true, 1);
        let d = s.dismissed_for_review().into_iter().find(|e| e.text == "t3").unwrap();
        s.revive(&d.id, "t3 — первый шаг", 2);
        assert_eq!(s.dismissed_for_review().len(), 4);
        let last = s.entries.last().unwrap();
        assert_eq!(last.text, "t3 — первый шаг");
        assert_eq!(last.status, Status::Active);
    }

    #[test]
    fn page_size_change_keeps_position() {
        let mut s = nb(30, 10);
        s.go_to(2);
        s.set_page_size(25);
        assert_eq!(s.current_page, 0);
        s.set_page_size(10);
        assert_eq!(s.current_page, 0);
        s.set_page_size(999);
        assert_eq!(s.page_size, MAX_PAGE_SIZE);
    }

    #[test]
    fn done_since_counts_only_done() {
        let mut s = nb(4, DEFAULT_PAGE_SIZE);
        let (a, b, c, d) = (id(&s, "t1"), id(&s, "t2"), id(&s, "t3"), id(&s, "t4"));
        s.done(&a, 100);
        s.reenter(&b, 200);
        s.cancel(&c, 300);
        s.done(&d, 400);
        assert_eq!(s.done_since(50), 2);
        assert_eq!(s.done_since(150), 1);
    }

    #[test]
    fn edits_bump_updated_at() {
        let mut s = nb(1, 5);
        let i = id(&s, "t1");
        assert!(s.edit(&i, "новый текст", 77));
        assert_eq!(s.entries[0].updated_at, 77);
        assert!(!s.edit(&i, "новый текст", 78)); // без изменений — без записи
    }

    // ---------- сверка с правилами AF1 ----------

    #[test]
    fn full_cycle_follows_the_method() {
        // 3 страницы по 5; 12 задач. Страница 1: «выделилась» t2 — делаем, t4 — не закончили.
        let mut s = nb(12, 5);
        let (t2, t4) = (id(&s, "t2"), id(&s, "t4"));
        s.done(&t2, 1);
        s.reenter(&t4, 2); // вычеркнуто и переписано в КОНЕЦ списка
        assert_eq!(s.entries.last().unwrap().text, "t4");
        assert_eq!(s.entries.len(), 13);
        // Уходим: на странице что-то выделилось — остаток НЕ отбрасывается.
        assert!(!s.dismiss_applies());
        s.advance(true, 3);
        assert_eq!(s.current_page, 1);
        assert_eq!(s.entries.iter().filter(|e| e.status == Status::Dismissed).count(), 0);
        // Страница 2: ничего не выделилось на первом проходе — отбрасывается целиком.
        assert!(s.dismiss_applies());
        s.advance(true, 4);
        assert!(s.page_entries(1).iter().all(|e| e.status == Status::Dismissed));
        assert_eq!(s.current_page, 2);
        // Последняя страница (там же пишем) никогда не отбрасывается.
        assert!(!s.dismiss_applies());
        s.advance(true, 5);
        assert!(s.page_entries(2).iter().all(|e| e.status == Status::Active));
        // По кругу — к первой странице, где ещё есть активные задачи.
        assert_eq!(s.current_page, 0);
    }

    #[test]
    fn dismissed_items_are_not_active_and_never_reorder() {
        let mut s = nb(12, 5);
        let before: Vec<String> = s.entries.iter().map(|e| e.id.clone()).collect();
        s.advance(true, 1);
        let after: Vec<String> = s.entries.iter().map(|e| e.id.clone()).collect();
        assert_eq!(before, after); // порядок строк системой не меняется
        assert_eq!(s.active_total(), 7);
    }

    #[test]
    fn reentry_does_not_trigger_dismissal_when_page_fills_up() {
        // Работаем на последней странице; переписанная в конец строка открывает новую страницу.
        let mut s = nb(10, 5);
        s.go_to(1);
        let i = id(&s, "t6");
        s.reenter(&i, 1);
        assert_eq!(s.last_page(), 2);
        // Работали на странице — её остаток нельзя отбросить, хотя она уже не последняя.
        assert!(!s.dismiss_applies());
    }

    #[test]
    fn cancel_is_not_work() {
        // Удаление ошибочной строки — не «выделилась»: страницу ещё можно отбросить.
        let mut s = nb(12, 5);
        let i = id(&s, "t1");
        s.cancel(&i, 1);
        assert!(s.dismiss_applies());
    }

    #[test]
    fn cleanup_removes_only_old_closed_lines_and_keeps_the_working_page() {
        let mut s = nb(30, 10);
        // t1..t12 закрыты давно, t13 закрыта недавно, t14 отброшена, но не разобрана
        for k in 1..=12 { let i = id(&s, &format!("t{k}")); s.done(&i, 100); }
        let i = id(&s, "t13"); s.done(&i, 900);
        s.go_to(2); // рабочая страница 3: строки t21..t30
        let before_first = s.page_entries(2)[0].text.clone();
        assert_eq!(s.cleanup_count(500), 12);
        assert_eq!(s.cleanup(500, 1000), 12);
        assert_eq!(s.entries.len(), 18);
        assert!(s.entries.iter().all(|e| e.status == Status::Active || e.text == "t13"));
        // первая строка рабочей страницы осталась на рабочей странице
        assert!(s.page_entries(s.current_page).iter().any(|e| e.text == before_first));
        assert_eq!(s.deleted_entries.len(), 12);
        assert_eq!(s.cleanup(500, 1001), 0);
    }

    #[test]
    fn cleanup_keeps_unreviewed_dismissed() {
        let mut s = nb(12, 5);
        s.advance(true, 10); // страница 1 отброшена
        assert_eq!(s.cleanup(1_000, 2_000), 0);
        let ids: Vec<String> = s.entries.iter().take(5).map(|e| e.id.clone()).collect();
        for i in &ids { s.forget(i, 20); }
        assert_eq!(s.cleanup(1_000, 2_000), 5);
    }

    #[test]
    fn reentry_counter_grows() {
        let mut s = nb(2, 5);
        for n in 1..=3 {
            let i = s.entries.iter().rev().find(|e| e.text == "t1" && e.status == Status::Active).unwrap().id.clone();
            s.reenter(&i, n);
        }
        assert_eq!(s.entries.last().unwrap().reentries, 3);
    }
}
