const I = {
  search:'<path d="M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15zM16 16l5 5"/>',
  menu:'<path d="M4 7h16M4 12h16M4 17h16"/>',
  back:'<path d="M19 12H5M11 6l-6 6 6 6"/>',
  prev:'<path d="M15 6l-6 6 6 6"/>',
  next:'<path d="M9 6l6 6-6 6"/>',
  plus:'<path d="M12 5v14M5 12h14"/>',
  minus:'<path d="M5 12h14"/>',
  check:'<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  more:'<circle cx="12" cy="5" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="12" cy="19" r="1.2"/>',
  close:'<path d="M6 6l12 12M18 6L6 18"/>',
};
const ic = (n, s=24) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24">${I[n]}</svg>`;
const ib = n => `<div class="ib">${ic(n)}</div>`;
const status = t => `<div class="sb"><span>${t||'9:41'}</span><span>LTE ▮▮▮ 86%</span></div>`;
const phone = (theme, inner, cls='') => `<div class="phone ${theme} ${cls}">${status()}${inner}<div class="nav"><i></i></div></div>`;
const wrap = (cap, sub, html) => `<div><div class="cap">${cap}<small>${sub||''}</small></div>${html}</div>`;

const tasks = [
 [51,'Разобрать почту','done'],[52,'Позвонить в банк'],[53,'Купить продукты'],[54,'Отчёт по проекту X','done','×2'],
 [55,'Записаться к стоматологу'],[56,'Прочитать главу книги'],[57,'Починить кран'],[58,'Ответить Маше'],
 [59,'Оплатить интернет','done'],[60,'Разобрать стол'],[61,'Спланировать отпуск'],[62,'Сделать бэкап телефона'],[63,'Выучить 10 слов'],[64,'Пробежка 20 минут']
];
function row(r, sel){ const [n,t,st,m]=r; return `<div class="row ${st==='done'?'done':''} ${sel?'sel':''}"><div class="n">${n}</div><div class="t">${t}</div>${m?`<div class="m">${m}</div>`:''}</div>`; }
function mainScreen(theme, o={}){
  const rows = tasks.slice(0,o.rows||10).map((r,i)=>{
    const out=[]; if(i===9) out.push(`<div class="day">Вс, 8 нояб.</div>`);
    out.push(row(r, o.sel===r[0]));
    if(o.sel===r[0]) out.push(`<div class="acts"><div class="btn fill">Сделано</div><div class="btn">Не закончено</div><div class="btn ink">Изменить</div></div>`);
    return out.join('');
  }).join('');
  const day = `<div class="day">Сб, 7 нояб.</div>`;
  const foot = o.next ? `<div class="foot"><div class="ib">${ic('prev')}</div><div class="go p"><span>Страница пройдена — дальше</span><span>04 ›</span></div></div>`
    : `<div class="foot"><div class="ib">${ic('prev')}</div><div class="go"><span>Дальше</span><span style="color:var(--ink2)">04 ›</span></div></div>`;
  return phone(theme, `
   <div class="head"><div class="pn"><b>03</b><span>из 06</span></div>${ib('search')}${ib('menu')}</div>
   <div class="stats">Активных 83 · сегодня сделано 2</div>
   ${o.hint===false?'':`<div class="hint">${o.hint||'Просмотрите страницу, затем читайте медленно, пока что-то не «выделится».'}</div>`}
   <div class="list">${day}${rows}<div class="add"><div class="n">${ic('plus',22)}</div>Новая задача</div></div>${foot}
   ${o.sheet||''}`);
}
const dismissSheet = `<div class="scrim"></div><div class="bsheet"><div class="grab"></div>
 <h3>Ничего не выделилось?</h3><p>Отбросить 22 задачи этой страницы. Сами они не вернутся, но их можно просмотреть в разделе «Отброшенные».</p>
 <div class="btnrow"><div class="btn fill">Отбросить 22</div><div class="btn">Оставить</div><div class="btn ink">Отмена</div></div></div>`;

const topBar = (title) => `<div class="top">${ib('back')}</div>${title?`<div class="title">${title}</div>`:''}`;
const itemRow = (a,b,right='') => `<div class="item"><div class="g"><div class="a">${a}</div>${b?`<div class="b">${b}</div>`:''}</div>${right}</div>`;
const swRow = (a,b,on,dis) => itemRow(a,b,`<div class="sw ${on?'on':''} ${dis?'dis':''}"></div>`);

const lists = phone('t-light', `${topBar('Списки')}<div class="lead">Forster советует отдельный список для каждого места: «Дом», «Работа». У каждого свои страницы.</div>
 <div class="item cur"><div class="g"><div class="a" style="font:600 20px/26px var(--serif)">Дом</div><div class="b">Активных 83 · стр. 03 из 06</div></div><div style="color:var(--accent)">${ic('check')}</div>${ib('more')}</div>
 ${itemRow('<span style="font:400 20px/26px var(--serif)">Работа</span>','Активных 3 · стр. 01 из 01',ib('more'))}
 ${itemRow('<span style="font:400 20px/26px var(--serif)">Дача</span>','Пусто',ib('more'))}
 <div class="item" style="color:var(--accent);gap:16px">${ic('plus')}<div class="a">Новый список</div></div>`);

const search = phone('t-light', `${topBar()}<div class="field">пла<span style="border-left:2px solid var(--accent)"></span></div>
 <div style="display:flex;justify-content:space-between;align-items:center;padding:8px 20px 8px;font:400 14px var(--sans);color:var(--ink2)"><span>Найдено 4</span><span style="color:var(--accent);font-weight:500">+ закрытые 3</span></div>
 <div class="hit"><div class="t">С<mark>пла</mark>нировать отпуск</div><div class="b">стр. 03 · строка 61</div></div>
 <div class="hit"><div class="t">О<mark>пла</mark>тить интернет</div><div class="b">стр. 03 · строка 59 · сделано</div></div>
 <div class="hit"><div class="t">С<mark>пла</mark>нировать ремонт</div><div class="b">стр. 05 · строка 112</div></div>
 <div style="flex:1"></div>
 <div class="kb"><div><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div><div><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div><div><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div><div class="w"><i style="max-width:60px"></i><i style="flex:5"></i><i style="max-width:60px"></i></div></div>`);

const review = phone('t-light', `${topBar()}<div class="title">С возвращением</div><div class="lead">Вас не было 5 дней. Прочтите список целиком — так вы снова увидите, что важно.</div>
 <div style="border-top:1px solid var(--rule);flex:1;overflow:hidden"><div class="day">Страница 03</div>${tasks.slice(4,12).map(r=>row(r)).join('')}</div>
 <div class="foot" style="justify-content:space-between;padding:0 20px"><div class="btn ink" style="padding:0">Пропустить</div><div class="btn fill">Начать со стр. 03</div></div>`);

const dismissed = phone('t-light', `${topBar('Отброшенные')}<div class="lead">Ничего не потеряно: верните в конец списка то, что ещё нужно.</div>
 <div style="border-top:1px solid var(--rule)"></div>
 ${['Сделать бэкап телефона','Выучить 10 слов','Пробежка 20 минут','Обновить резюме'].map(t=>`<div style="padding:12px 20px 4px;border-bottom:1px solid var(--rule)"><div style="font:400 19px/28px var(--serif)">${t}</div><div style="font:400 13px var(--sans);color:var(--ink2)">стр. 03 · отброшено 6 нояб.</div><div style="display:flex;margin-left:-12px"><div class="btn">Вернуть в конец</div><div class="btn ink">Забыть</div></div></div>`).join('')}`);

const firstrun = phone('t-light', `<div class="head"><div class="pn"></div>${ib('menu')}</div>
 <div style="padding:20px 20px 0"><div style="font:600 30px/36px var(--serif)">Запишите первую задачу</div><div class="lead" style="padding:12px 0 0">Всё, что приходит в голову — по одной строке, в конец списка. Остальное сделает метод: страницы по 25 строк, затем «что выделилось?».</div></div>
 <div style="flex:1"></div>
 <div style="margin:0 20px 12px;border-bottom:2px solid var(--accent);font:400 19px/28px var(--serif);padding-bottom:8px;display:flex;justify-content:space-between;align-items:center"><span style="color:var(--ink2)">Новая задача</span><span style="color:var(--accent)">${ic('plus')}</span></div>
 <div class="kb"><div><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div><div><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div><div><i></i><i></i><i></i><i></i><i></i><i></i><i></i></div><div class="w"><i style="max-width:60px"></i><i style="flex:5"></i><i style="max-width:60px"></i></div></div>`);

const share = phone('t-dark', `<div style="flex:1;opacity:.5;padding:30px 20px;font:400 15px/22px var(--sans)">Страница в браузере…</div><div class="scrim"></div>
 <div class="bsheet" style="bottom:22px"><div class="grab"></div><div style="font:500 14px var(--sans);color:var(--ink2);margin-bottom:6px">Записать в конец списка</div>
 <div style="font:400 20px/28px var(--serif);margin-bottom:12px">Как работает Страница — Mark Forster https://markforster.net</div>
 <div style="font:500 14px var(--sans);color:var(--ink2);margin-bottom:6px">Список</div>
 <div class="seg" style="margin:0 0 4px"><div class="on">Дом</div><div>Работа</div><div>Дача</div></div>
 <div class="btnrow" style="justify-content:flex-end"><div class="btn ink">Отмена</div><div class="btn fill">Записать</div></div></div>`);

const settings = phone('t-light', `${topBar('Настройки')}<div style="overflow:hidden;flex:1">
 <div class="sec" style="padding-top:8px">Страница</div>
 ${itemRow('Строк на странице','Forster советует 25–35',`<div class="step"><i>${ic('minus',20)}</i><b>25</b><i>${ic('plus',20)}</i></div>`)}
 ${swRow('Подсказки на странице','Короткая строка о том, что делать сейчас',true)}
 <div class="sec">Оформление</div>
 <div class="seg"><div>Как в системе</div><div>Светлая</div><div class="on">Тёмная</div></div>
 ${swRow('Чёрный для AMOLED','Только в тёмной теме',false,true)}
 ${swRow('Цвета обоев','Акцент и оттенки по обоям (Material You)',true)}
 <div class="sec">Данные</div>
 ${itemRow('Синхронизация','Телефон, ПК, планшет — без облака',ic('next'))}
 ${itemRow('Сохранить копию в файл','Список хранится только на этом телефоне',ic('next'))}
 ${swRow('Копия средствами Android','Выключено: список не покидает телефон',false)}
 </div>`);

const syncHome = phone('t-light', `${topBar('Синхронизация')}<div class="lead">Устройства в одной сети обмениваются списками напрямую, без облака. Всё шифруется.</div>
 ${itemRow('Показать QR-код','Другое устройство его отсканирует',ic('next'))}
 ${itemRow('Сканировать QR-код','На компьютере: stranitsa host',ic('next'))}
 ${itemRow('Найти рядом','Без QR: сверите шестизначный код',ic('next'))}
 <div class="sec" style="padding-top:26px">Знакомые устройства</div>
 ${itemRow('MacBook Air','8 нояб., 19:41',`<div class="btn" style="padding:0 8px">Синхр.</div>${ib('more')}`)}
 ${itemRow('Pixel Tablet','4 нояб., 20:14',`<div class="btn" style="padding:0 8px">Синхр.</div>${ib('more')}`)}`);

const syncQr = phone('t-light', `${topBar('Показать QR-код')}<div class="lead">Откройте «Синхронизация → Сканировать QR» на другом устройстве и наведите камеру.</div>
 <div class="tile"><svg width="220" height="220" viewBox="0 0 21 21" style="fill:#111;stroke:none">${(()=>{let s='';const f=(x,y)=>{for(let i=0;i<7;i++)for(let j=0;j<7;j++){if(i==0||i==6||j==0||j==6||(i>1&&i<5&&j>1&&j<5))s+=`<rect x="${x+j}" y="${y+i}" width="1" height="1"/>`}};f(0,0);f(14,0);f(0,14);let r=7;for(let i=0;i<21;i++)for(let j=0;j<21;j++){if((i<8&&j<8)||(i<8&&j>12)||(i>12&&j<8))continue;r=(r*1103515245+12345)&0x7fffffff;if((r>>8)%2)s+=`<rect x="${j}" y="${i}" width="1" height="1"/>`}return s})()}</svg></div>
 <div class="center" style="font:400 14px/20px var(--sans);color:var(--ink2);margin-top:10px">Этот телефон видят только пока открыт этот экран</div>
 <div class="center" style="font:500 16px var(--sans);margin-top:22px">Ждём подключения…</div>
 <div style="flex:1"></div><div class="btnrow" style="justify-content:center;margin:0 0 20px"><div class="btn ink">Отмена</div></div>`);

const syncCode = phone('t-light', `${topBar('Сверьте код')}<div class="lead">«MacBook Air» подключается. Код должен совпасть с кодом на другом экране.</div>
 <div class="big" style="margin-top:36px">482 917</div>
 <div style="flex:1"></div><div class="btnrow" style="flex-direction:column;margin:0 20px 20px"><div class="btn fill" style="width:100%">Совпадает</div><div class="btn ink" style="width:100%">Не совпадает — отмена</div></div>`);

const about = phone('t-light', `${topBar()}<div class="title">Страница</div><div class="lead">Версия 1.10. Метод AF1 Марка Форстера; приложение неофициальное.</div>
 <div style="padding:8px 20px;font:400 17px/26px var(--serif)"><p style="margin-bottom:14px">Один длинный список. Новые задачи — в конец. Читайте страницу и берите то, что «выделилось».</p><p style="margin-bottom:14px">Не закончили — вычеркните и перепишите в конец. Если на первом проходе ничего не выделилось, страница отбрасывается.</p><p style="color:var(--ink2)">Приложение ничего не оценивает и не переставляет — это нарушило бы метод.</p></div>
 <div style="border-top:1px solid var(--rule);margin-top:12px">${itemRow('Лицензии шрифтов','',ic('next'))}${itemRow('Исходный код','',ic('next'))}</div>`);

// ---------- виджеты ----------
function widget(theme, size, o={}){
  const th = theme;
  const W={ '2x2':[168,168], '4x2':[352,168], '4x4':[352,352] }[size];
  const lines = tasks.filter(t=>!t[2]).slice(0,{ '2x2':0,'4x2':3,'4x4':8 }[size]);
  const plus = `<div style="width:40px;height:40px;border-radius:12px;background:var(--accent);color:var(--on);display:flex;align-items:center;justify-content:center;flex:none">${ic('plus')}</div>`;
  const head = size==='2x2' ? '' : `<div style="display:flex;align-items:center;gap:8px"><div style="font:400 30px/34px var(--serif);font-feature-settings:'tnum'">03</div><div style="font:400 14px/18px var(--sans);color:var(--ink2);flex:1">из 06 · Дом</div>${plus}</div>`;
  let body='';
  if(size==='2x2') body = `<div style="display:flex;flex-direction:column;height:100%;justify-content:space-between"><div><div style="font:400 14px/18px var(--sans);color:var(--ink2)">Дом · страница</div><div style="font:400 56px/60px var(--serif);font-feature-settings:'tnum'">03</div><div style="font:400 14px/18px var(--sans);color:var(--ink2)">из 06 · активных 83</div></div><div style="display:flex;justify-content:flex-end">${plus}</div></div>`;
  else body = head + `<div style="margin-top:6px">${lines.map(t=>`<div style="display:flex;gap:10px;align-items:baseline;padding:5px 0"><span style="width:20px;font:400 12px var(--sans);color:var(--ink2);text-align:right;font-feature-settings:'tnum'">${t[0]}</span><span style="font:400 16px/22px var(--serif);white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${t[1]}</span></div>`).join('')}${size==='4x4'?`<div style="font:400 13px var(--sans);color:var(--ink2);padding:8px 0 0 30px">ещё 14 на этой странице</div>`:''}</div>`;
  const bgmap={'t-light':'#F7F5EF','t-dark':'#1B1B18','t-amoled':'#000','t-monetL':'#E6EFE3','t-monetD':'#1A231D'};
  return `<div class="${th}" style="width:${W[0]}px;height:${W[1]}px;border-radius:28px;background:${bgmap[th]};color:var(--ink);padding:14px 16px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.25)">${body}</div>`;
}
function wallpaper(theme, inner, h){
  const g={'t-light':'linear-gradient(160deg,#E7B6C6,#B9A5D6 60%,#8FA7DD)','t-dark':'linear-gradient(160deg,#2a2335,#1a2438 60%,#10202c)','t-amoled':'#050505','t-monetL':'linear-gradient(160deg,#BFD9B5,#9EC9B8 60%,#86B7C4)','t-monetD':'linear-gradient(160deg,#1c2e22,#173229 60%,#143036)'}[theme];
  return `<div style="width:390px;border-radius:28px;background:${g};padding:20px 19px;display:flex;flex-direction:column;gap:14px;align-items:center;height:${h}px">${inner}</div>`;
}
