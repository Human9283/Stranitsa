const { chromium } = require('playwright');
(async()=>{
  const b = await chromium.launch(); const p = await b.newPage({viewport:{width:2200,height:1000},deviceScaleFactor:1});
  for (const s of ['rules','main','more','more2','themes','widgets']) {
    await p.goto('file://'+__dirname+'/index.html#'+s); await p.reload();
    await p.waitForSelector('body[data-ready]',{state:'attached'}); await p.waitForTimeout(400);
    const el = await p.$('#root > *'); await el.screenshot({path:__dirname+`/${s}.png`});
  }
  await b.close();
})();
