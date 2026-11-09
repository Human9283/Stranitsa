const sw = (n,c,t='#fff') => `<div style="width:92px"><div style="height:56px;border-radius:8px;background:${c};box-shadow:inset 0 0 0 1px rgba(0,0,0,.12)"></div><div style="font:500 12px/16px var(--sans);margin-top:4px">${n}</div><div style="font:400 12px/16px var(--sans);color:#6b6962">${c}</div></div>`;
const pal = (name, cols) => `<div style="margin-bottom:18px"><div style="font:500 14px var(--sans);margin-bottom:8px">${name}</div><div style="display:flex;gap:10px;flex-wrap:wrap">${cols.map(c=>sw(...c)).join('')}</div></div>`;
const rulesHTML = `<div style="width:1480px;background:#F7F5EF;border-radius:20px;padding:36px 40px;display:grid;grid-template-columns:1fr 1fr;gap:40px;color:#1C1B18;font-family:var(--sans)">
<div>
 <div style="font:600 34px/40px var(--serif)">Страница — оформление 2.0</div>
 <div style="font:400 16px/24px var(--sans);color:#5E5C55;margin:8px 0 24px">Идея: бумажная тетрадь, а не сайт. Задачи — крупным шрифтом с засечками, всё остальное — тихо и по делу. Один акцент — синие чернила.</div>
 <div style="font:600 20px var(--serif);margin-bottom:10px">Цвета</div>
 ${pal('Светлая',[['бумага','#F7F5EF'],['текст','#1C1B18'],['второй текст','#5E5C55'],['линия','#E1DED3'],['акцент','#1F3FBF'],['заливка','#E8EBF7']])}
 ${pal('Тёмная / AMOLED',[['фон','#151513'],['AMOLED','#000000'],['текст','#ECEAE3'],['второй текст','#A6A399'],['акцент','#9DB4FF'],['заливка','#1F2335']])}
 ${pal('Monet (пример для зелёных обоев)',[['фон','#F4F8F1'],['акцент','#2C6A4F'],['заливка','#DDEBDF'],['фон, тёмная','#101411'],['акцент','#9BD4B5'],['заливка','#1B2A20']])}
 <div style="font:400 14px/21px var(--sans);color:#5E5C55">Контраст основного текста — выше 12:1, второго — не ниже 6:1. Акцентом отмечено только то, что можно нажать, и текущая страница.</div>
</div>
<div>
 <div style="font:600 20px var(--serif);margin-bottom:10px">Шрифты — два, а не три</div>
 <div style="border-top:1px solid #E1DED3">
  <div style="display:flex;justify-content:space-between;align-items:baseline;padding:8px 0;border-bottom:1px solid #E1DED3"><span style="font:400 48px/52px var(--serif)">03</span><span style="font:400 13px var(--sans);color:#5E5C55">Source Serif 4 · 48 · номер страницы</span></div>
  <div style="display:flex;justify-content:space-between;align-items:baseline;padding:8px 0;border-bottom:1px solid #E1DED3"><span style="font:600 30px/36px var(--serif)">Настройки</span><span style="font:400 13px var(--sans);color:#5E5C55">Serif · 30 semibold · заголовок</span></div>
  <div style="display:flex;justify-content:space-between;align-items:baseline;padding:8px 0;border-bottom:1px solid #E1DED3"><span style="font:400 19px/28px var(--serif)">Записаться к стоматологу</span><span style="font:400 13px var(--sans);color:#5E5C55">Serif · 19/28 · задача</span></div>
  <div style="display:flex;justify-content:space-between;align-items:baseline;padding:8px 0;border-bottom:1px solid #E1DED3"><span style="font:500 16px var(--sans)">Сделано · Не закончено</span><span style="font:400 13px var(--sans);color:#5E5C55">IBM Plex Sans · 16 medium · действия</span></div>
  <div style="display:flex;justify-content:space-between;align-items:baseline;padding:8px 0;border-bottom:1px solid #E1DED3"><span style="font:400 14px var(--sans)">Активных 83 · сегодня сделано 2</span><span style="font:400 13px var(--sans);color:#5E5C55">Sans · 14 · пояснения, метки (не меньше 13)</span></div>
 </div>
 <div style="font:400 14px/21px var(--sans);color:#5E5C55;margin:8px 0 22px">Моноширинный шрифт и мелкие заглавные метки с разрядкой убраны. Цифры — табличные.</div>
 <div style="font:600 20px var(--serif);margin-bottom:10px">Форма</div>
 <div style="font:400 15px/23px var(--sans);margin-bottom:22px">Страница — без карточек, теней и скруглений: только строки и тонкие линии. Скругление 10 — у кнопок, полей и плиток; 28 — у нижних листов и виджета (радиус системы). Таблеток и градиентов нет. Иконки — одна линейная гарнитура, штрих 1,75.</div>
 <div style="font:600 20px var(--serif);margin-bottom:10px">Что было не так в 1.x — и что исправлено</div>
 <div style="font:400 15px/23px var(--sans)">
  <p style="margin-bottom:6px"><b>Мелкий текст.</b> Было 11–12 px моно и серым. Стало: не мельче 13, пояснения 14, остальное 16–19.</p>
  <p style="margin-bottom:6px"><b>Цветная полоса сбоку</b> у выбранной строки — признак «шаблонного» ИИ-интерфейса. Теперь заливка на всю ширину.</p>
  <p style="margin-bottom:6px"><b>Заглавные метки с разрядкой</b> («СТРАНИЦА», «ВНЕШНИЙ ВИД»). Теперь обычные заголовки разделов.</p>
  <p style="margin-bottom:6px"><b>Флажки вместо выбора темы.</b> Теперь переключатель из трёх вариантов.</p>
  <p style="margin-bottom:6px"><b>Навигация сверху.</b> Стрелки страниц перенесены вниз, куда достаёт большой палец.</p>
  <p><b>Равное внимание всему.</b> Теперь на экране одна заполненная кнопка — главное действие.</p>
 </div>
</div></div>`;
