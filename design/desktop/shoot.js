const { chromium } = require('playwright');
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:1240,height:900}});
for (const s of 'abcdefgh'){await p.goto('file://'+__dirname+'/mockups.html#'+s);await p.reload();await p.waitForSelector('body[data-ready]',{state:'attached'});await p.waitForTimeout(300);
const el=await p.$('#r');await el.screenshot({path:__dirname+`/${s}.png`});}
await b.close();})();
