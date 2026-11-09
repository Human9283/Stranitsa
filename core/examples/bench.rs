//! Замер: сколько стоит одно действие ядра при разном размере списка.
//! `cargo run --release --example bench`
use stranitsa_core::StCore;
use std::time::Instant;

fn main() {
    for n in std::env::var("N").ok().map(|v| v.split(',').filter_map(|x| x.parse().ok()).collect::<Vec<usize>>()).unwrap_or(vec![100usize, 1_000, 5_000, 20_000]) {
        let dir = std::env::var("TMPDIR").map(std::path::PathBuf::from).unwrap_or_else(|_| std::env::temp_dir()).join(format!("stranitsa-bench-{n}"));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("stranitsa.json");
        let _ = std::fs::remove_file(&path);
        let core = StCore::new(path.to_string_lossy().into(), 1_000);
        let t = Instant::now();
        for i in 0..n {
            core.add(format!("Задача номер {i}: купить молоко и позвонить в банк"), 1_000 + i as i64);
        }
        let fill = t.elapsed();
        core.flush();
        let size = std::fs::metadata(&path).unwrap().len();
        // одно действие: добавить (мутация + запись файла + снимок)
        let reps = 30;
        let t = Instant::now();
        for i in 0..reps { core.add(format!("новая {i}"), 9_000_000 + i); }
        let add = t.elapsed() / reps as u32; // сколько ждёт вызывающий
        core.flush();
        let _to_disk = t.elapsed() / reps as u32;
        // как было раньше: действие ждёт запись на диск (add + flush подряд)
        let t = Instant::now();
        for i in 0..reps { core.add(format!("синхронно {i}"), 9_100_000 + i); core.flush(); }
        let sync_old = t.elapsed() / reps as u32;
        // только снимок (копия списка)
        let t = Instant::now();
        for _ in 0..reps { let _ = core.snapshot(); }
        let snap = t.elapsed() / reps as u32;
        let id = core.snapshot().entries.iter().find(|e| e.status == stranitsa_core::Status::Active).unwrap().id.clone();
        let t = Instant::now();
        core.done(id, 9_999_999);
        let done = t.elapsed();
        // из чего складывается: только запись файла такого же размера, с fsync и без
        let blob = vec![b'x'; size as usize];
        let tmp = dir.join("blob.tmp");
        let t = Instant::now();
        for _ in 0..reps { std::fs::write(&tmp, &blob).unwrap(); }
        let plain = t.elapsed() / reps as u32;
        let t = Instant::now();
        for _ in 0..reps { use std::io::Write; let mut f = std::fs::File::create(&tmp).unwrap(); f.write_all(&blob).unwrap(); f.sync_all().unwrap(); }
        let synced = t.elapsed() / reps as u32;
        println!("        запись файла без fsync {plain:>7.2?} · с fsync {synced:>7.2?}");
        println!("{n:>6} строк · файл {:>6} КБ · добавить {:>7.2?} (раньше, с ожиданием диска {:>7.2?}) · снимок {:>7.2?} · «сделано» {:>7.2?} · заполнение {:?}", size / 1024, add, sync_old, snap, done, fill);
    }
}
