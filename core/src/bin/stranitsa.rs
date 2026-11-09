//! `stranitsa` — «Страница» в терминале (Windows, macOS, Linux) на том же ядре, что и Android.
//! Пока — для проверки синхронизации и как основа ПК-версии.
//!
//! ```text
//! stranitsa list                 показать рабочую страницу текущего списка
//! stranitsa add "текст"          записать задачу в конец
//! stranitsa host                 стать видимым: показать QR и ждать телефон
//! stranitsa connect "<stranitsa:1?…>"  подключиться по тексту из QR
//! stranitsa nearby               найти устройства рядом и синхронизироваться
//! stranitsa peers                знакомые устройства
//! ```
//! Ключи: `--data <файл>` (по умолчанию `stranitsa-data/stranitsa.json`), `--name <имя>`, `--yes` (подтверждать
//! сверку кода автоматически — только для проверок), `--once` (host: выйти после первой синхронизации).

use stranitsa_core::sync::{decode_qr, sync_connect, sync_connect_qr, NearbyPeer, SyncBrowser, SyncHost, SyncSession};
use stranitsa_core::{StCore, Status};
use std::io::{BufRead, Write};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

struct Opts {
    data: String,
    name: String,
    yes: bool,
    once: bool,
    args: Vec<String>,
}

fn parse() -> Opts {
    let mut o = Opts {
        data: "stranitsa-data/stranitsa.json".into(),
        name: std::env::var("HOSTNAME").or_else(|_| std::env::var("COMPUTERNAME")).unwrap_or_else(|_| "PC".into()),
        yes: false,
        once: false,
        args: Vec::new(),
    };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--data" => o.data = it.next().expect("--data <файл>"),
            "--name" => o.name = it.next().expect("--name <имя>"),
            "--yes" => o.yes = true,
            "--once" => o.once = true,
            _ => o.args.push(a),
        }
    }
    o
}

fn ask(question: &str) -> bool {
    print!("{question} [y/N] ");
    std::io::stdout().flush().ok();
    let mut line = String::new();
    std::io::stdin().lock().read_line(&mut line).ok();
    matches!(line.trim(), "y" | "Y" | "д" | "Д" | "yes" | "да")
}

/// Сверка кода (если нужна) и обмен.
fn complete(s: Arc<SyncSession>, yes: bool) {
    let p = s.peer();
    let ok = if p.needs_confirmation {
        println!("\nПодключается «{}». Код: {}", p.name, p.code);
        yes || ask("Код совпадает с кодом на другом устройстве?")
    } else {
        println!("\nПодключается «{}» ({})", p.name, if p.via_qr { "по QR" } else { "знакомое устройство" });
        true
    };
    match s.finish(ok, now()) {
        Ok(r) => println!("Синхронизировано с «{}»: новых строк {}", r.peer_name, r.new_entries),
        Err(e) => println!("Не получилось: {e}"),
    }
}

fn main() {
    let o = parse();
    if let Some(dir) = std::path::Path::new(&o.data).parent() {
        std::fs::create_dir_all(dir).ok();
    }
    let core = StCore::new(o.data.clone(), now());
    let cmd = o.args.first().map(String::as_str).unwrap_or("list");
    match cmd {
        "list" => {
            let s = core.snapshot();
            let name = if s.name.is_empty() { "Мой список" } else { &s.name };
            println!("{name} · стр. {} из {} · активных {}", s.current_page + 1, s.last_page + 1, s.active_total);
            let from = (s.current_page * s.page_size) as usize;
            for (i, e) in s.entries.iter().enumerate().skip(from).take(s.page_size as usize) {
                let mark = match e.status {
                    Status::Active => "  ",
                    Status::Done => "✓ ",
                    Status::Reentered => "→ ",
                    Status::Dismissed => "~ ",
                    Status::Cancelled => "× ",
                };
                println!("{:>4} {mark}{}", i + 1, e.text);
            }
        }
        "add" => {
            let text = o.args[1..].join(" ");
            let s = core.add(text, now());
            println!("Записано. Активных: {}", s.active_total);
        }
        "host" => {
            let host = SyncHost::start(core.clone(), o.name.clone(), true).expect("не удалось открыть порт");
            let qr = qrcode::QrCode::new(host.qr_text().as_bytes()).unwrap();
            println!(
                "{}",
                qr.render::<qrcode::render::unicode::Dense1x2>()
                    .dark_color(qrcode::render::unicode::Dense1x2::Light)
                    .light_color(qrcode::render::unicode::Dense1x2::Dark)
                    .build()
            );
            println!("Отсканируйте QR в «Странице»: Меню → Синхронизация → Сканировать QR.");
            println!("Или выберите «{}» в «Найти рядом». Порт {}.", o.name, host.port());
            println!("QR-текст: {}", host.qr_text());
            loop {
                match host.accept(1000) {
                    Ok(Some(s)) => {
                        complete(s, o.yes);
                        if o.once {
                            break;
                        }
                    }
                    Ok(None) => {}
                    Err(e) => println!("Отклонено подключение: {e}"),
                }
            }
        }
        "connect" => {
            let qr = o.args.get(1).expect("connect \"stranitsa:1?…\"").clone();
            match sync_connect_qr(core.clone(), qr, o.name.clone()) {
                Ok(s) => complete(s, o.yes),
                Err(e) => println!("Не подключились: {e}"),
            }
        }
        "nearby" => {
            let b = SyncBrowser::start(core.clone());
            println!("Ищу устройства рядом…");
            std::thread::sleep(Duration::from_secs(4));
            let peers = b.peers();
            if peers.is_empty() {
                println!("Никого. На другом устройстве откройте Меню → Синхронизация.");
                return;
            }
            for (i, p) in peers.iter().enumerate() {
                println!("{}. {}{}", i + 1, p.name, if p.known { " (знакомое)" } else { "" });
            }
            let pick = if peers.len() == 1 || o.yes { 0 } else {
                print!("Номер: ");
                std::io::stdout().flush().ok();
                let mut l = String::new();
                std::io::stdin().lock().read_line(&mut l).ok();
                l.trim().parse::<usize>().unwrap_or(1).saturating_sub(1)
            };
            match sync_connect(core.clone(), peers[pick.min(peers.len() - 1)].clone(), o.name.clone()) {
                Ok(s) => complete(s, o.yes),
                Err(e) => println!("Не подключились: {e}"),
            }
        }
        // Служебное: распознать QR на картинке (скриншот) тем же декодером, что и камера телефона.
        "scan-image" => {
            let path = o.args.get(1).expect("scan-image <файл.png>");
            let dec = png::Decoder::new(std::fs::File::open(path).expect("файл"));
            let mut reader = dec.read_info().expect("png");
            let mut buf = vec![0; reader.output_buffer_size()];
            let info = reader.next_frame(&mut buf).expect("кадр");
            let ch = info.color_type.samples();
            let luma: Vec<u8> = buf[..info.buffer_size()]
                .chunks(ch)
                .map(|p| if ch >= 3 { ((p[0] as u32 * 299 + p[1] as u32 * 587 + p[2] as u32 * 114) / 1000) as u8 } else { p[0] })
                .collect();
            match decode_qr(info.width as i32, info.height as i32, luma, info.width as i32) {
                Some(t) => println!("{t}"),
                None => println!("QR не найден"),
            }
        }
        // Служебное: подключиться напрямую как к устройству «рядом» (без mDNS), со сверкой кода.
        "connect-direct" => {
            let (ip, port, id) = (o.args[1].clone(), o.args[2].parse::<i32>().expect("порт"), o.args[3].clone());
            let peer = NearbyPeer { device_id: id, name: String::new(), addresses: vec![ip], port, known: false };
            match sync_connect(core.clone(), peer, o.name.clone()) {
                Ok(s) => complete(s, o.yes),
                Err(e) => println!("Не подключились: {e}"),
            }
        }
        "peers" => {
            for p in core.sync_peers() {
                println!("{} · последняя синхронизация {}", p.name, p.last_sync);
            }
        }
        other => println!("Неизвестная команда «{other}». Есть: list, add, host, connect, nearby, peers."),
    }
}
