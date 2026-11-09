//! Работа с текстом: поиск и разбор текста из «Поделиться».

use crate::model::{Entry, Notebook, Status};

/// Найденный фрагмент. Позиции — в единицах UTF-16 (так считают строки Kotlin, Java и Swift NSString).
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct HitRange {
    pub start: i32,
    pub end: i32,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SearchHit {
    /// Номер строки в списке (с нуля).
    pub index: i32,
    pub entry: Entry,
    pub ranges: Vec<HitRange>,
}

/// Регистр и «ё» = «е». Каждый символ даёт ровно один символ — позиции сохраняются.
fn normalize(s: &str) -> Vec<char> {
    s.chars()
        .map(|c| {
            let l = c.to_lowercase().next().unwrap_or(c);
            if l == 'ё' {
                'е'
            } else {
                l
            }
        })
        .collect()
}

fn find(hay: &[char], needle: &[char]) -> Option<usize> {
    if needle.is_empty() || needle.len() > hay.len() {
        return None;
    }
    (0..=hay.len() - needle.len()).find(|&i| hay[i..i + needle.len()] == *needle)
}

fn utf16_offset(chars: &[char], char_index: usize) -> i32 {
    chars[..char_index].iter().map(|c| c.len_utf16() as i32).sum()
}

impl Notebook {
    /// Все слова запроса должны встретиться (в любом порядке). Сначала активные, затем закрытые.
    pub fn search(&self, query: &str, include_closed: bool) -> Vec<SearchHit> {
        let words: Vec<Vec<char>> = query
            .split_whitespace()
            .map(normalize)
            .filter(|w| !w.is_empty())
            .collect();
        if words.is_empty() {
            return Vec::new();
        }
        let mut hits: Vec<SearchHit> = self
            .entries
            .iter()
            .enumerate()
            .filter(|(_, e)| include_closed || e.status == Status::Active)
            .filter_map(|(i, e)| {
                let original: Vec<char> = e.text.chars().collect();
                let text = normalize(&e.text);
                let mut ranges = Vec::new();
                for w in &words {
                    let at = find(&text, w)?;
                    ranges.push(HitRange {
                        start: utf16_offset(&original, at),
                        end: utf16_offset(&original, at + w.len()),
                    });
                }
                Some(SearchHit { index: i as i32, entry: e.clone(), ranges })
            })
            .collect();
        hits.sort_by_key(|h| if h.entry.status == Status::Active { 0 } else { 1 });
        hits
    }
}

/// Строки, которые станут отдельными задачами: без пустых строк и маркеров списка.
/// Заголовок вида «Список покупок:» в первой строке задачей не считается.
#[uniffi::export]
pub fn split_shared_text(text: String) -> Vec<String> {
    let lines: Vec<String> = text
        .lines()
        .map(|l| l.trim().trim_start_matches(['-', '•', '*', '–', '—']).trim().to_string())
        .filter(|l| !l.is_empty())
        .collect();
    if lines.len() > 1 && lines[0].ends_with(':') {
        lines[1..].to_vec()
    } else {
        lines
    }
}

/// Одна задача: переводы строк превращаются в пробелы.
#[uniffi::export]
pub fn join_shared_text(text: String) -> String {
    text.lines().map(str::trim).filter(|l| !l.is_empty()).collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn nb(items: &[&str]) -> Notebook {
        let mut n = Notebook::new("n".into(), String::new(), 0, 10);
        for t in items {
            n.add(t, 0);
        }
        n
    }

    fn found(hits: &[SearchHit]) -> Vec<&str> {
        hits.iter().map(|h| h.entry.text.as_str()).collect()
    }

    #[test]
    fn ignores_case_and_yo() {
        let n = nb(&["Купить ёлку", "Позвонить в банк", "ЕЛКА: гирлянда", "Банк — выписка"]);
        assert_eq!(found(&n.search("елк", false)), ["Купить ёлку", "ЕЛКА: гирлянда"]);
        assert_eq!(found(&n.search("выписка БАНК", false)), ["Банк — выписка"]);
        assert!(n.search("   ", false).is_empty());
    }

    #[test]
    fn closed_only_when_asked_and_after_active() {
        let mut n = nb(&["отчёт 1", "отчёт 2"]);
        let id = n.entries[0].id.clone();
        n.done(&id, 1);
        assert_eq!(found(&n.search("отчет", false)), ["отчёт 2"]);
        let all = n.search("отчет", true);
        assert_eq!(found(&all), ["отчёт 2", "отчёт 1"]);
        assert_eq!(all[0].ranges, [HitRange { start: 0, end: 5 }]);
    }

    #[test]
    fn ranges_are_utf16() {
        // эмодзи занимает 2 единицы UTF-16 — позиция найденного слова сдвигается на 2
        let n = nb(&["🙂 Банк"]);
        let h = &n.search("банк", false)[0];
        assert_eq!(h.ranges, [HitRange { start: 3, end: 7 }]);
    }

    #[test]
    fn share_split_drops_bullets_and_heading() {
        assert_eq!(split_shared_text("- молоко\n\n• хлеб\n  * Call Bob  ".into()), ["молоко", "хлеб", "Call Bob"]);
        assert_eq!(split_shared_text("Список покупок:\n- молоко\n- хлеб".into()), ["молоко", "хлеб"]);
        assert_eq!(split_shared_text("Позвонить: Ивану".into()), ["Позвонить: Ивану"]);
    }

    #[test]
    fn share_join() {
        assert_eq!(join_shared_text("Статья о сне\n\nhttps://example.com\n".into()), "Статья о сне https://example.com");
    }
}
