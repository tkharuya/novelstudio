/* Local UI integration checks. Native calls are explicitly mocked; no real account is used. */
'use strict';
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const os = require('node:os');
const assert = require('node:assert/strict');
const assets = path.resolve(__dirname, '../app/src/main/assets');
const output = process.env.NOVELSTUDIO_QA_DIR || fs.mkdtempSync(path.join(os.tmpdir(), 'novelstudio-qa-'));
fs.mkdirSync(output, { recursive: true });
const results = [];
const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, 'http://127.0.0.1').pathname;
  if (/^\/illustrations\/[a-f0-9-]{36}\.png$/.test(pathname)) {
    // A local one-pixel fixture; real PNG storage/decoding is tested in the native layer.
    res.setHeader('Content-Type','image/png');res.end(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=','base64'));return;
  }
  const name = pathname === '/' ? 'index.html' : pathname.slice(1);
  if (!/^[a-zA-Z0-9_.-]+$/.test(name)) { res.writeHead(403); res.end(); return; }
  const file = path.join(assets, name);
  if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type', name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html; charset=utf-8');
  fs.createReadStream(file).pipe(res);
});
async function runCheck(name, body) {
  try { await body(); results.push({ name, passed: true }); console.log('PASS '+name); }
  catch (error) { results.push({ name, passed: false, message: error.message }); throw error; }
}
function installNativeMock() {
  const KEY = 'novelstudio.native-test-state',RKEY='novelstudio.runtime-test-state',IKEY='novelstudio.image-test-status';
  window.__nativeCalls = [];
  window.__exports = [];
  window.__copied = '';
  window.__saveFails = false;
  window.__imageState = JSON.parse(localStorage.getItem(IKEY)||'null')||{configured:false,busy:false,model:'gpt-image-2.5-flare',message:''};
  window.__acks=[];window.__ackFails=false;window.__snapshotFails=false;
  window.__runtimeSnapshot=()=>JSON.parse(localStorage.getItem(RKEY)||'{"records":[],"activeTextId":"","activeImageId":"","notificationsEnabled":false,"backgroundReady":true}');
  window.__setRuntimeSnapshot=(value,emit=false)=>{localStorage.setItem(RKEY,JSON.stringify(value));if(emit)window.onNativeEvent('runtimeSnapshot',value);};
  const auth = { signedIn: true, busy: false, authenticating: false, generating: false, waitingForApp: false, exchanging: false, planEnabled: true, email: 'local-test@example.invalid', message: '', diagnosticCode: '', diagnosticStage: '' };
  const modelData = { models: [{ id: 'mock-model', name: 'テスト用モデル（実API未接続）' }] };
  window.NativeBridge = {
    loadState: () => localStorage.getItem(KEY) || '{}',
    saveState: (value) => { if (window.__saveFails) return false; localStorage.setItem(KEY, value); return true; },
    getStatus: () => JSON.stringify(auth),
    getRuntimeSnapshot: () => {if(window.__snapshotFails)throw Error('snapshot unavailable');return JSON.stringify(window.__runtimeSnapshot());},
    acknowledgeGeneration: (requestId,seq) => {
      const saved=JSON.parse(localStorage.getItem(KEY)||'{}'),receipt=saved.runtimeReceipts&&saved.runtimeReceipts[requestId];
      const durable=!!(receipt&&receipt.terminal&&receipt.seq===seq);
      window.__acks.push({requestId,seq,durable,saved});
      if(!durable)throw Error('ACK before successful persistence');
      if(window.__ackFails)return false;
      const snapshot=window.__runtimeSnapshot(),record=snapshot.records.find(r=>r.requestId===requestId);
      if(record&&(record.seq!==seq||record.phase==='generating'))return false;
      snapshot.records=snapshot.records.filter(r=>r.requestId!==requestId);
      if(snapshot.activeTextId===requestId)snapshot.activeTextId='';if(snapshot.activeImageId===requestId)snapshot.activeImageId='';
      window.__setRuntimeSnapshot(snapshot);return true;
    },
    requestGenerationNotifications: () => {window.__nativeCalls.push(['requestGenerationNotifications']);const snapshot=window.__runtimeSnapshot();snapshot.notificationsEnabled=true;window.__setRuntimeSnapshot(snapshot,true);},
    openBackgroundSettings: () => window.__nativeCalls.push(['openBackgroundSettings']),

    getAppInfo: () => JSON.stringify({ version: '1.3.0', platform: 'android-test-double' }),
    signIn: () => { window.__nativeCalls.push(['signIn']); setTimeout(() => window.onNativeEvent('auth', auth), 0); },
    signOut: () => { auth.signedIn=false; window.onNativeEvent('auth', auth); },
    refreshModels: () => setTimeout(() => window.onNativeEvent('models', modelData), 0),
    generate: (value) => { window.__lastRequest=JSON.parse(value); window.__nativeCalls.push(['generate', window.__lastRequest]); },
    cancelGeneration: () => {window.__nativeCalls.push(['cancelGeneration']);if(window.__runtimeSnapshot().batch&&window.__runtimeSnapshot().batch.status==='running'){const snap=window.__runtimeSnapshot();snap.batch.status='paused';window.__setRuntimeSnapshot(snap,true);return;}const snapshot=window.__runtimeSnapshot(),record=snapshot.records.find(r=>r.requestId===snapshot.activeTextId);if(record){record.phase='cancelled';record.seq++;record.message='テストで中止';snapshot.activeTextId='';window.__setRuntimeSnapshot(snapshot);window.onNativeEvent('generation',record);}else if(window.__lastRequest)window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'cancelled',message:'テストで中止'});},
    startBatchGeneration: value => {
      const input=JSON.parse(value);window.__lastBatch=input;window.__nativeCalls.push(['startBatchGeneration',input]);
      const b=window.__savedState().books.find(b=>b.id===input.bookId),snap=window.__runtimeSnapshot();
      snap.batch={id:'batch_ui_fixture',bookId:b.id,bookTitle:b.title,completed:0,status:'running',message:'1話ずつ生成しています。',createdAt:Date.now(),items:input.items.map((x,i)=>({id:x.id,title:b.chapters.find(c=>c.id===x.id).title,text:'',revision:i===0?1:0,requestId:i===0?'batch_request_0':'',status:i===0?'generating':'queued'}))};
      window.__setRuntimeSnapshot(snap,true);
    },
    resumeBatchGeneration: () => {window.__nativeCalls.push(['resumeBatchGeneration']);const snap=window.__runtimeSnapshot();snap.batch.status='running';const item=snap.batch.items[snap.batch.completed];item.status='generating';item.requestId='batch_resumed_'+snap.batch.completed;item.revision++;window.__setRuntimeSnapshot(snap,true);},
    closeBatchGeneration: () => {window.__nativeCalls.push(['closeBatchGeneration']);const snap=window.__runtimeSnapshot();snap.batch.status='closed';window.__setRuntimeSnapshot(snap,true);},
    openSite: (value) => { window.__lastSite=JSON.parse(value); window.__nativeCalls.push(['openSite', window.__lastSite]); },
    copyText: (value) => { window.__copied=value; },
    exportFile: (name,mime,text) => { window.__exports.push({name,mime,text}); },
    getImageStatus: () => JSON.stringify(window.__imageState),
    configureImageApi: () => {window.__nativeCalls.push(['configureImageApi']);window.__imageState.configured=true;localStorage.setItem(IKEY,JSON.stringify(window.__imageState));window.onNativeEvent('imageStatus',window.__imageState);},
    clearImageApi: () => {window.__nativeCalls.push(['clearImageApi']);window.__imageState.configured=false;localStorage.setItem(IKEY,JSON.stringify(window.__imageState));window.onNativeEvent('imageStatus',window.__imageState);},
    generateIllustration: value => {window.__lastImageRequest=JSON.parse(value);window.__nativeCalls.push(['generateIllustration',window.__lastImageRequest]);window.__imageState.busy=true;window.onNativeEvent('imageStatus',window.__imageState);},
    cancelIllustration: () => {window.__nativeCalls.push(['cancelIllustration']);window.__imageState.busy=false;window.onNativeEvent('imageStatus',window.__imageState);if(window.__lastImageRequest)window.onNativeEvent('illustration',{...window.__lastImageRequest,phase:'cancelled',message:'待機を中止しました。'});},
    importIllustration: value => {window.__lastImageRequest=JSON.parse(value);window.__nativeCalls.push(['importIllustration',window.__lastImageRequest]);},
    exportIllustration: id => window.__nativeCalls.push(['exportIllustration',id]),
    exportBackup: text => window.__exports.push({name:'backup.zip',mime:'application/zip',text}),
    importBackup: () => { window.__nativeCalls.push(['importBackup']); },
    openExternal: (url) => { window.__nativeCalls.push(['openExternal',url]); }
  };
  window.__savedState=() => JSON.parse(localStorage.getItem(KEY)||'{}');
}

async function main() {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({headless:true,executablePath:process.env.CHROME_BIN, args:['--no-sandbox','--disable-dev-shm-usage']});
  const context=await browser.newContext({viewport:{width:393,height:851},deviceScaleFactor:1});
  await context.addInitScript(installNativeMock);
  const page=await context.newPage();
  const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  page.on('dialog',dialog=>dialog.accept());
  const action=async name => page.locator(`[data-action="${name}"]:visible`).first().click();
  try {
    await page.goto(url);
    await page.waitForSelector('[data-action="new-book"]');
    await runCheck('initial render and empty shelf',async()=>{
      assert.match(await page.locator('body').innerText(),/最初の作品/);
      assert.equal(await page.locator('body').evaluate(el=>el.scrollWidth<=window.innerWidth),true);
      await page.screenshot({path:path.join(output,'01-empty-phone.png'),fullPage:true});
    });
    await runCheck('sample is explicitly labelled and readable',async()=>{
      await action('sample');
      await page.getByTestId('reader-sheet').waitFor();
      assert.match(await page.getByTestId('reader-sheet').innerText(),/サンプル・未生成/);
      assert.equal((await page.evaluate(()=>window.__savedState())).books.length,1);
      await page.screenshot({path:path.join(output,'02-sample-phone.png'),fullPage:true});
    });
    await runCheck('create work, escape HTML and save manuscript',async()=>{
      await action('new-book');
      await page.getByTestId('new-book-title').fill('<img src=x onerror=alert(1)>検証作品');
      await page.getByTestId('create-book').click();
      await page.getByTestId('chapter-body').waitFor();
      await page.locator('#work-world').fill('港町。魔法は存在しない。');
      await page.locator('#work-synopsis').fill('書店で見つかった手紙の差出人を探す。');
      await page.getByTestId('chapter-title').fill('第1話　出発');
      await page.getByTestId('chapter-body').fill('第1話の本文。登場人物は結衣。\n雨の日に港へ向かった。');
      await page.evaluate(()=>window.flushAppState());
      const saved=await page.evaluate(()=>window.__savedState());
      assert.equal(saved.books.length,2);
      assert.equal(saved.books[1].chapters[0].body,'第1話の本文。登場人物は結衣。\n雨の日に港へ向かった。');
      assert.equal(await page.locator('#app img').count(),0);
      await page.getByTestId('nav-shelf').click();
      assert.match(await page.locator('body').innerText(),/<img src=x onerror=alert\(1\)>検証作品/);
      assert.equal(await page.locator('#app img').count(),0);
      await page.getByTestId('nav-editor').click();
    });
    await runCheck('new chapter and prompt include settings and previous chapter',async()=>{
      await page.getByTestId('new-chapter').click();
      await page.getByTestId('chapter-title').fill('第2話　手紙');
      await page.getByTestId('chapter-body').fill('ここは編集済みの元の原稿。');
      await page.locator('#generation-hint').fill('青年と初めて話す場面。');
      await action('preview-prompt');
      const promptText=await page.locator('[role=dialog]').innerText();
      assert.match(promptText,/魔法は存在しない/);
      assert.match(promptText,/第1話の本文/);
      assert.match(promptText,/青年と初めて話す/);
      await action('close-modal');
      await page.getByTestId('generate').click();
      const req=await page.evaluate(()=>window.__lastRequest);
      assert.equal(req.model,'mock-model');
      assert.match(req.prompt,/第1話の本文/);
      assert.match(req.instructions,/小説本文だけ/);
    });
    await runCheck('stream failure preserves candidate and original; stale events ignored',async()=>{
      await page.evaluate(()=>{
        window.onNativeEvent('generation',{requestId:'stale-request',phase:'delta',delta:'混ざってはいけない'});
        window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'delta',delta:'途中の文章。'});
        window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'error',message:'通信中断の試験'});
      });
      assert.equal(await page.getByTestId('chapter-body').inputValue(),'ここは編集済みの元の原稿。');
      assert.equal(await page.locator('#candidate-text').innerText(),'途中の文章。');
      const c=(await page.evaluate(()=>window.__savedState())).books[1].chapters[1];
      assert.equal(c.candidate.status,'error');
      assert.equal(c.candidate.text,'途中の文章。');
      await page.reload();
      await page.getByTestId('nav-editor').click();
      assert.equal(await page.locator('#candidate-text').innerText(),'途中の文章。');
      assert.equal(await page.getByTestId('chapter-body').inputValue(),'ここは編集済みの元の原稿。');
    });
    await runCheck('continuation adoption and undo preserve the original',async()=>{
      await page.locator('[data-action="mode"][data-mode="continue"]').click();
      await page.getByTestId('generate').click();
      await page.evaluate(()=>{
        const requestId=window.__lastRequest.requestId;
        window.onNativeEvent('generation',{requestId,phase:'delta',delta:'追加される続き。'});
        window.onNativeEvent('generation',{requestId,phase:'complete',text:'追加される続き。'});
      });
      assert.equal(await page.getByTestId('chapter-body').inputValue(),'ここは編集済みの元の原稿。');
      await page.getByTestId('adopt-candidate').click();
      assert.equal(await page.getByTestId('chapter-body').inputValue(),'ここは編集済みの元の原稿。\n追加される続き。');
      await action('undo-adopt');
      assert.equal(await page.getByTestId('chapter-body').inputValue(),'ここは編集済みの元の原稿。');
    });
    await runCheck('interrupted generation survives reload without being marked complete',async()=>{
      await page.getByTestId('generate').click();
      await page.evaluate(()=>{
        window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'delta',delta:'再起動しても残る文章。'});
        window.flushAppState();
      });
      await page.reload();
      await page.getByTestId('nav-editor').click();
      assert.equal(await page.locator('#candidate-text').innerText(),'再起動しても残る文章。');
      assert.match(await page.getByTestId('candidate').innerText(),/中断した生成候補/);
      await action('discard');
    });
    await runCheck('multiple title candidates keep their work target and only change the title on adoption',async()=>{
      await page.locator('#work-details summary').first().click();
      await page.locator('#work-characters').fill('結衣。26歳。黒髪。港町の書店を経営している。');
      await page.locator('#title-count').selectOption('10');
      const before=await page.evaluate(()=>window.__savedState());
      const workId=before.books[1].id;
      const original=await page.locator('#work-title').inputValue();
      const originalBody=await page.getByTestId('chapter-body').inputValue();
      await page.getByTestId('generate-titles').click();
      const request=await page.evaluate(()=>window.__lastRequest);
      assert.match(request.prompt,/10個提案/);
      assert.match(request.prompt,/港町/);
      assert.match(request.prompt,/結衣/);
      assert.match(request.instructions,/"titles"/);
      assert.equal(await page.locator('#work-title').inputValue(),original);
      assert.equal(await page.getByTestId('generate-synopsis').isDisabled(),true);
      await page.evaluate(()=>window.onNativeEvent('notice',{message:'ファイル選択を開けませんでした（局所的なエラーの試験）。'}));
      assert.equal((await page.evaluate(()=>window.__savedState())).books[1].titleCandidate.status,'generating');
      assert.equal(await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='cancelGeneration').length),0);
      assert.equal(await page.getByTestId('generate-synopsis').isDisabled(),true);
      await page.getByTestId('book-selector').selectOption(before.books[0].id);
      await page.evaluate(()=>{
        const titles=['<img src=x onerror=alert(1)>という題名',...Array.from({length:9},(_,i)=>'港の手紙 '+(i+2))];
        window.onNativeEvent('generation',{requestId:'stale-metadata',phase:'complete',text:'混ざってはいけない'});
        window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'delta',delta:'{"titles":['});
        window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'complete',text:JSON.stringify({titles})});
      });
      const saved=await page.evaluate(()=>window.__savedState());
      assert.equal(saved.books[0].titleCandidate,null);
      assert.equal(saved.books[1].title,original);
      assert.equal(saved.books[1].titleCandidate.items.length,10);
      await page.getByTestId('book-selector').selectOption(workId);
      assert.equal(await page.locator('.title-option').count(),10);
      assert.equal(await page.locator('#app img').count(),0);
      await page.locator('#title-option-1').fill('港で止まった時計');
      await page.getByTestId('adopt-title-1').click();
      assert.equal(await page.locator('#work-title').inputValue(),'港で止まった時計');
      assert.equal(await page.getByTestId('chapter-body').inputValue(),before.books[1].chapters[0].body);
      // Chapter selection resets when changing works. The generation never altered either manuscript.
      const adopted=await page.evaluate(()=>window.__savedState());
      assert.equal(adopted.books[1].chapters[1].body,originalBody);
      assert.equal(adopted.books[1].titleCandidate.items[1],'港で止まった時計');
      await page.locator('[data-action="copy-meta-title"][data-index="0"]').click();
      assert.equal(await page.evaluate(()=>window.__copied),'<img src=x onerror=alert(1)>という題名');
      await page.reload();await page.getByTestId('nav-editor').click();
      await page.locator('#work-details summary').first().click();
      assert.equal(await page.locator('.title-option').count(),10);
      assert.equal(await page.locator('#work-title').inputValue(),'港で止まった時計');
      assert.equal(await page.locator('#title-count').inputValue(),'10');
      await page.screenshot({path:path.join(output,'titles-candidates-phone.png'),fullPage:true});
    });
    await runCheck('title count choices and malformed output remain editable and recoverable',async()=>{
      await page.locator('#title-count').selectOption('20');
      await page.getByTestId('generate-titles').click();
      assert.match((await page.evaluate(()=>window.__lastRequest)).prompt,/20個提案/);
      await page.evaluate(()=>window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'error',text:'1. 港の手紙\n2. 止まった時計\n2. 止まった時計',message:'テストで中断'}));
      assert.equal(await page.locator('.title-option').count(),2);
      assert.equal(await page.locator('#work-title').inputValue(),'港で止まった時計');
      await page.locator('#title-count').selectOption('5');
      await page.getByTestId('generate-titles').click();
      assert.match((await page.evaluate(()=>window.__lastRequest)).prompt,/5個提案/);
      await page.evaluate(()=>window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'complete',text:'{"unexpected":"形式が異なる受信結果"}'}));
      assert.equal(await page.locator('.title-option').count(),0);
      assert.match(await page.locator('#title-result-raw').inputValue(),/形式が異なる/);
      await page.locator('#title-result-raw').fill('1. 港の記録\n2. 書店の手紙');
      await action('parse-titles');
      assert.equal(await page.locator('.title-option').count(),2);
      await page.locator('[data-action="copy-metadata"][data-kind="titles"]').click();
      assert.equal(await page.evaluate(()=>window.__copied),'1. 港の記録\n2. 書店の手紙');
      assert.equal(await page.locator('#work-title').inputValue(),'港で止まった時計');
    });
    await runCheck('synopsis adoption is explicit, reversible and incomplete output survives reload',async()=>{
      const original=await page.locator('#work-synopsis').inputValue();
      await page.getByTestId('generate-synopsis').click();
      assert.match((await page.evaluate(()=>window.__lastRequest)).prompt,/400〜600字/);
      assert.match((await page.evaluate(()=>window.__lastRequest)).prompt,/結末や終盤の秘密は明かさない/);
      await page.evaluate(()=>window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'complete',text:'港町で書店を営む結衣は、宛名のない手紙を見つける。時計に残された時刻を手掛かりに、差出人を探し始める。'}));
      assert.equal(await page.locator('#work-synopsis').inputValue(),original);
      await page.locator('#synopsis-candidate-text').fill('編集して採用する紹介文。港町の書店で見つけた手紙の差出人を探す。');
      await page.getByTestId('adopt-synopsis').click();
      assert.match(await page.locator('#work-synopsis').inputValue(),/編集して採用する/);
      await action('undo-metadata');
      assert.equal(await page.locator('#work-synopsis').inputValue(),original);
      await page.getByTestId('generate-synopsis').click();
      await page.evaluate(()=>{window.onNativeEvent('generation',{requestId:window.__lastRequest.requestId,phase:'delta',delta:'中断しても残す紹介文。'});window.flushAppState();});
      await page.reload();await page.getByTestId('nav-editor').click();
      await page.locator('#work-details summary').first().click();
      assert.equal(await page.locator('#synopsis-candidate-text').inputValue(),'中断しても残す紹介文。');
      assert.match(await page.getByTestId('synopsis-candidate').innerText(),/中断/);
      assert.equal(await page.locator('#work-synopsis').inputValue(),original);
      await page.locator('#synopsis-candidate-text').fill('再開後に編集した紹介文。');
      await page.getByTestId('adopt-synopsis').click();
      assert.equal(await page.locator('#work-synopsis').inputValue(),'再開後に編集した紹介文。');
      await page.locator('#work-details summary').first().click();
      const state=await page.evaluate(()=>window.__savedState());
      await page.getByTestId('chapter-selector').selectOption(state.books[1].chapters[1].id);
    });
    await runCheck('ChatGPT illustration handoff copies bounded context and does not claim automatic generation',async()=>{
      await page.getByTestId('chapter-body').fill('結衣が港で手紙を読む場面。'+('長い原稿。'.repeat(5000))+'本文末尾の目印');
      await page.locator('.illustration-options summary').click();
      await page.locator('#work-illustrationStyle').fill('青を基調としたカラーイラスト。人物の服は紺色。');
      await page.getByTestId('chatgpt-illustration').first().click();
      const payload=await page.evaluate(()=>window.__lastSite);
      assert.equal(payload.site,'chatgpt');assert.equal(payload.purpose,'illustration');
      assert.equal(payload.url,'https://chatgpt.com/');
      assert.match(payload.body,/青を基調/);assert.match(payload.body,/黒髪/);
      assert.match(payload.body,/結衣が港で手紙を読む場面/);
      assert.match(payload.body,/以降は省略/);assert.doesNotMatch(payload.body,/本文末尾の目印/);
      assert.ok(payload.body.length<=32000);
      assert.equal(await page.evaluate(()=>window.__copied),payload.body);
      assert.equal(await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generateIllustration').length),0);
      assert.equal((await page.evaluate(()=>window.__savedState())).books[1].chapters[1].illustrations.length,0);
      assert.match(await page.getByTestId('illustration-panel').innerText(),/貼り付けて送信/);
      assert.match(await page.getByTestId('illustration-panel').innerText(),/冒頭最大18,000字/);
      await action('preview-illustration');
      assert.match(await page.locator('[role=dialog]').innerText(),/本文/);
      await action('close-modal');
      await page.getByTestId('configure-image-api').click();
      assert.equal(await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='configureImageApi').length),1);
      assert.match(await page.getByTestId('illustration-panel').innerText(),/ChatGPTとは別料金/);
    });
    await runCheck('one-tap API illustration attaches once to its original chapter and survives reload',async()=>{
      const before=await page.evaluate(()=>window.__savedState());
      const target=before.books[1].chapters[1];
      await page.getByTestId('generate-illustration').click();
      const request=await page.evaluate(()=>window.__lastImageRequest);
      assert.equal(request.bookId,before.books[1].id);assert.equal(request.chapterId,target.id);
      assert.equal(await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generateIllustration').length),1);
      assert.equal((await page.evaluate(()=>window.__savedState())).books[1].chapters[1].illustrations.length,0);
      assert.equal(await page.getByTestId('generate-illustration').isDisabled(),true);
      await page.getByTestId('new-chapter').click();
      await page.evaluate(()=>{
        const data={...window.__lastImageRequest,phase:'complete',image:{id:'11111111-1111-4111-8111-111111111111',mime:'image/png',width:1024,height:1536,createdAt:Date.now(),source:'api',prompt:window.__lastImageRequest.prompt}};
        window.__imageState.busy=false;window.onNativeEvent('imageStatus',window.__imageState);
        window.onNativeEvent('illustration',data);window.onNativeEvent('illustration',data);
      });
      assert.equal(await page.locator('.illustration-gallery img').count(),0);
      const saved=await page.evaluate(()=>window.__savedState());
      assert.equal(saved.books[1].chapters[1].illustrations.length,1);
      assert.equal(saved.books[1].chapters[2].illustrations.length,0);
      assert.equal(saved.books[1].chapters[1].body,target.body);
      await page.getByTestId('chapter-selector').selectOption(target.id);
      assert.equal(await page.locator('.illustration-gallery img').count(),1);
      await page.locator('.illustration-gallery img').scrollIntoViewIfNeeded();
      await page.waitForFunction(()=>document.querySelector('.illustration-gallery img').naturalWidth>0);
      await page.locator('.illustration-gallery [data-action="export-illustration"]').click();
      assert.deepEqual(await page.evaluate(()=>window.__nativeCalls.at(-1)),['exportIllustration','11111111-1111-4111-8111-111111111111']);
      await page.locator('.illustration-panel').screenshot({path:path.join(output,'illustration-panel-phone.png')});
      await page.getByTestId('nav-reader').click();
      assert.equal(await page.locator('.reader-gallery img').count(),1);
      await page.reload();await page.getByTestId('nav-editor').click();
      assert.equal(await page.locator('.illustration-gallery img').count(),1);
      assert.equal((await page.evaluate(()=>window.__savedState())).books[1].chapters[1].illustrations[0].mime,'image/png');
    });
    await runCheck('image import cancellation and invalid image references preserve existing illustrations',async()=>{
      await page.getByTestId('import-illustration').click();
      await page.evaluate(()=>window.onNativeEvent('illustration',{...window.__lastImageRequest,phase:'cancelled',message:'画像の選択を中止しました。'}));
      assert.equal(await page.getByTestId('import-illustration').isEnabled(),true);
      assert.equal(await page.locator('.illustration-gallery img').count(),1);
      await page.getByTestId('import-illustration').click();
      await page.evaluate(()=>window.onNativeEvent('illustration',{...window.__lastImageRequest,phase:'complete',image:{id:'../../private-key',mime:'image/png',width:10,height:10,createdAt:Date.now(),source:'import',prompt:''}}));
      assert.match(await page.locator('#toast').innerText(),/参照形式/);
      assert.equal(await page.locator('.illustration-gallery img').count(),1);
      await page.getByTestId('import-illustration').click();
      await page.evaluate(()=>window.onNativeEvent('illustration',{...window.__lastImageRequest,phase:'complete',image:{id:'22222222-2222-4222-8222-222222222222',mime:'image/png',width:100,height:100,createdAt:Date.now(),source:'import',prompt:''}}));
      assert.equal(await page.locator('.illustration-gallery img').count(),2);
      await page.locator('[data-action="remove-illustration"][data-id="22222222-2222-4222-8222-222222222222"]').click();
      assert.equal(await page.locator('.illustration-gallery img').count(),1);
      // File errors are visible without injecting an inline event handler into trusted HTML.
      await page.locator('.illustration-gallery img').evaluate(img=>img.dispatchEvent(new Event('error')));
      assert.equal(await page.locator('.image-missing .image-unavailable').isVisible(),true);
      assert.match(await page.locator('.image-missing .image-unavailable').innerText(),/画像が見つかりません/);
      await page.getByTestId('nav-reader').click();await page.getByTestId('nav-editor').click();
    });
    await runCheck('active image requests block restore and deletion; wait cancellation clears the job',async()=>{
      await page.locator('.illustration-options summary').click();
      await page.getByTestId('configure-image-api').click();
      const before=await page.evaluate(()=>window.__savedState());
      await page.getByTestId('generate-illustration').click();
      await action('delete-chapter');
      assert.equal((await page.evaluate(()=>window.__savedState())).books[1].chapters.length,before.books[1].chapters.length);
      await page.getByTestId('settings').click();
      const imports=await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='importBackup').length);
      await page.getByTestId('restore').click();
      assert.equal(await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='importBackup').length),imports);
      await page.evaluate(()=>{const next=window.__savedState();next.books[1].title='実行中に復元してはいけない';window.onNativeEvent('import',{text:JSON.stringify(next)});});
      assert.notEqual((await page.evaluate(()=>window.__savedState())).books[1].title,'実行中に復元してはいけない');
      await action('close-modal');
      await action('cancel-illustration');
      assert.equal(await page.getByTestId('generate-illustration').isEnabled(),true);
      assert.equal(await page.locator('.illustration-gallery img').count(),1);
      assert.equal((await page.evaluate(()=>window.__savedState())).books[1].chapters[1].illustrationRequest,null);
    });
    await runCheck('reader settings and bookmark survive navigation',async()=>{
      await page.getByTestId('chapter-body').fill(('長い原稿を読むための検証用文章。\n\n').repeat(100));
      await page.getByTestId('nav-reader').click();
      await action('font-larger');
      await page.locator('[data-action="reader-theme"][data-theme="night"]').click();
      await page.evaluate(()=>window.scrollTo(0,1500));
      await page.waitForTimeout(300);
      await page.evaluate(()=>window.flushAppState());
      const saved=await page.evaluate(()=>window.__savedState());
      assert.equal(saved.prefs.fontSize,20);
      assert.equal(saved.prefs.readerTheme,'night');
      assert.ok(saved.bookmarks[saved.activeChapterId].ratio>0);
      await page.getByTestId('nav-editor').click();
      await page.getByTestId('nav-reader').click();
      await page.waitForTimeout(150);
      assert.ok(await page.evaluate(()=>window.scrollY)>1000);
      await page.screenshot({path:path.join(output,'03-reader-night.png')});
    });
    await runCheck('posting payload stays separate from publication success',async()=>{
      await page.getByTestId('nav-publish').click();
      await page.locator('[data-action="reviewed"]').check();
      await page.getByTestId('open-kakuyomu').click();
      const payload=await page.evaluate(()=>window.__lastSite);
      assert.equal(payload.site,'kakuyomu');
      assert.equal(payload.url,'https://kakuyomu.jp/my/works');
      assert.equal(payload.tags,'AI本文利用');
      assert.ok(payload.body.length>1000);
      assert.match(await page.locator('body').innerText(),/公開は公式サイトで確定/);
      assert.equal((await page.evaluate(()=>window.__savedState())).books[1].chapters[1].published,undefined);
    });
    await runCheck('backup export and failed restore protect existing manuscripts',async()=>{
      await page.getByTestId('settings').click();
      await page.getByTestId('backup').click();
      const exported=await page.evaluate(()=>window.__exports.at(-1));
      assert.equal(exported.mime,'application/zip');
      const parsed=JSON.parse(exported.text);
      assert.equal(parsed.books.length,2);
      assert.ok(parsed.books[1].titleCandidate);assert.ok(parsed.books[1].synopsisCandidate);
      assert.equal(parsed.books[1].chapters[1].illustrations.length,1);
      assert.equal(exported.text.includes('apiKey'),false);assert.equal(exported.text.includes('b64_json'),false);
      assert.equal(exported.text.includes('local-test@example.invalid'),false);
      assert.equal(exported.text.includes('access_token'),false);
      await page.evaluate(()=>window.onNativeEvent('import',{text:'{"bad":"backup"}'}));
      assert.equal((await page.evaluate(()=>window.__savedState())).books.length,2);
      await page.evaluate(()=>{
        window.__saveFails=true;
        const valid=window.__savedState();valid.books[1].title='保存失敗で反映してはいけない';
        window.onNativeEvent('import',{text:JSON.stringify(valid)});
        window.__saveFails=false;
      });
      assert.notEqual((await page.evaluate(()=>window.__savedState())).books[1].title,'保存失敗で反映してはいけない');
      assert.match(await page.locator('#toast').innerText(),/現在の原稿を維持/);
      await page.evaluate(()=>window.onNativeEvent('import',{text:window.__exports.at(-1).text}));
      assert.equal((await page.evaluate(()=>window.__savedState())).books.length,2);
    });
    await runCheck('connection failure is readable and diagnostics omit private fields',async()=>{
      await page.getByTestId('nav-shelf').click();
      await page.setViewportSize({width:360,height:900});
      await page.evaluate(()=>window.onNativeEvent('status',{
        signedIn:false,busy:true,authenticating:true,generating:false,planEnabled:false,
        message:'ブラウザでChatGPTにログインしてください。',diagnosticCode:'',diagnosticStage:''
      }));
      assert.match(await page.getByTestId('auth-message').innerText(),/ブラウザでChatGPT/);
      await page.getByTestId('settings').click();
      await page.evaluate(()=>window.onNativeEvent('auth',{
        signedIn:false,busy:false,authenticating:false,generating:false,planEnabled:false,
        email:'private-email@example.invalid',message:'認証情報の受信：通信が時間切れになりました。',
        diagnosticCode:'AUTH_TOKEN_TIMEOUT',diagnosticStage:'認証情報の受信',
        access_token:'DO_NOT_COPY_ACCESS',refresh_token:'DO_NOT_COPY_REFRESH',
        callback:'http://127.0.0.1/auth/callback?code=DO_NOT_COPY_CODE'
      }));
      const modal=page.locator('[role=dialog]');
      assert.match(await modal.getByTestId('auth-message').innerText(),/通信が時間切れ/);
      assert.equal(await modal.getByTestId('auth-retry').isEnabled(),true);
      await modal.getByTestId('copy-diagnostics').click();
      assert.equal(await page.evaluate(()=>window.__copied),'小説工房 1.3.0\n処理段階: 認証情報の受信\n診断コード: AUTH_TOKEN_TIMEOUT');
      await modal.getByTestId('auth-diagnostics').locator('summary').click();
      assert.equal(await modal.getByTestId('auth-diagnostic-code').innerText(),'AUTH_TOKEN_TIMEOUT');
      await page.screenshot({path:path.join(output,'auth-failure-phone.png'),fullPage:true});
      await action('close-modal');
      await page.getByTestId('nav-editor').click();
      assert.match(await page.getByTestId('auth-message').innerText(),/通信が時間切れ/);
      await page.getByTestId('nav-shelf').click();
      await page.evaluate(()=>window.onNativeEvent('status',{diagnosticCode:'AUTH_KEYS_'+('A'.repeat(70))}));
      await page.getByTestId('auth-diagnostics').locator('summary').click();
      assert.equal(await page.locator('body').evaluate(el=>el.scrollWidth<=window.innerWidth+1),true);
    });
    await runCheck('connection retry clears failure and retains manuscripts',async()=>{
      const before=(await page.evaluate(()=>window.__savedState())).books;
      await page.getByTestId('auth-retry').click();
      const confirm=page.locator('[data-action="confirm-signin"]:visible');
      if(await confirm.count()) await confirm.click();
      await page.waitForFunction(()=>window.__nativeCalls.some(c=>c[0]==='signIn'));
      await page.waitForFunction(()=>!document.querySelector('[data-testid="auth-retry"]'));
      assert.match(await page.locator('body').innerText(),/ChatGPTに接続済み/);
      assert.deepEqual((await page.evaluate(()=>window.__savedState())).books,before);
    });
    await runCheck('callback receipt waits for app return without a false login success or duplicate sign-in',async()=>{
      await page.getByTestId('nav-shelf').click();
      const before=await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='signIn').length);
      await page.evaluate(()=>window.onNativeEvent('status',{
        signedIn:false,busy:true,authenticating:true,generating:false,waitingForApp:true,exchanging:false,
        message:'ログイン結果を受信しました。小説工房へ戻ると接続を完了します。',diagnosticCode:'',diagnosticStage:''
      }));
      assert.match(await page.getByTestId('auth-message').innerText(),/小説工房へ戻ると接続を完了/);
      assert.doesNotMatch(await page.locator('body').innerText(),/ChatGPTに接続済み/);
      assert.equal(await page.locator('.connection-banner [data-action="signin"]').isDisabled(),true);
      await page.locator('.connection-pill').click();
      assert.equal(await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='signIn').length),before);
      await page.evaluate(()=>window.onNativeEvent('status',{
        waitingForApp:false,exchanging:true,message:'ChatGPTの接続を完了しています。この画面を開いたままお待ちください。'
      }));
      assert.equal(await page.locator('.connection-banner [data-action="signin"]').isDisabled(),true);
      await page.evaluate(()=>window.onNativeEvent('auth',{
        signedIn:true,busy:false,authenticating:false,waitingForApp:false,exchanging:false,planEnabled:true,
        message:'ChatGPTプランで接続しました。',diagnosticCode:'',diagnosticStage:''
      }));
      assert.match(await page.locator('body').innerText(),/ChatGPTに接続済み/);
      assert.equal(await page.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='signIn').length),before);
    });
    await runCheck('phone and fold layouts keep controls inside the viewport',async()=>{
      for(const width of [360,768]) {
        await page.setViewportSize({width,height:900});
        for(const tab of ['shelf','editor','reader','publish']) {
          await page.getByTestId('nav-'+tab).click();
          await page.waitForTimeout(60);
          await page.evaluate(()=>{const t=document.getElementById('toast');t.classList.remove('visible');window.scrollTo(0,0);});
          await page.screenshot({path:path.join(output,`layout-${tab}-${width}.png`),fullPage:tab!=='reader'});
          const fits=await page.locator('body').evaluate(el=>el.scrollWidth<=window.innerWidth+1);
          if(!fits) fs.writeFileSync(path.join(output,'overflow.json'),JSON.stringify(await page.locator('body *').evaluateAll(nodes=>nodes.filter(el=>{const r=el.getBoundingClientRect();return r.right>window.innerWidth+1||r.left< -1;}).map(el=>({tag:el.tagName,id:el.id,class:el.className,text:el.innerText?.slice(0,100),right:el.getBoundingClientRect().right,left:el.getBoundingClientRect().left}))),null,2));
          assert.equal(fits,true,`${tab} overflows at ${width}`);
        }
      }
    });
    await runCheck('production posting-input script on synthetic editor fixtures',async()=>{
      const source=fs.readFileSync(path.resolve(__dirname,'../app/src/main/java/jp/haruya/novelstudio/PublishingBrowser.java'),'utf8');
      const attr=source.match(/TARGET_ATTRIBUTE\s*=\s*"([^"]+)"/)[1];
      const method=source.split('private static String inputScript(JSONObject args) {')[1].split('private static String jsQuote')[0];
      const terms=method.match(/"(?:[^"\\]|\\.)*"|jsQuote\(args\.toString\(\)\)|TARGET_ATTRIBUTE/g);
      const script=args=>terms.map(term=>term.startsWith('"')?JSON.parse(term):term==='TARGET_ATTRIBUTE'?attr:JSON.stringify(JSON.stringify(args)).replace(/\u2028/g,'\\u2028').replace(/\u2029/g,'\\u2029')).join('');
      const fixture=await browser.newContext({viewport:{width:393,height:851}});
      await fixture.route('**/*',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:'<textarea id="body" aria-label="本文" style="width:300px;height:150px"></textarea><textarea id="search" aria-label="検索"></textarea><textarea id="other"></textarea><div id="editable" contenteditable="true" aria-label="本文" style="width:300px;height:120px;border:1px solid"></div>'}));
      const editorPage=await fixture.newPage();
      const fixtureUrl='https://syosetu.com/usernovel/edit/fixture';
      await editorPage.goto(fixtureUrl);
      const body='一行目\n二行目「引用」\\テスト <script>文字列</script>';
      const args={mode:'inspect',site:'narou',kind:'body',text:body,token:'ns_fixture_1',url:fixtureUrl,hash:'',length:0};
      const evaluate=async a=>JSON.parse(await editorPage.evaluate(script(a)));
      await editorPage.locator('#body').focus();
      let inspected=await evaluate(args);assert.equal(inspected.ok,true);
      let inserted=await evaluate({...args,mode:'insert',hash:inspected.hash,length:inspected.length});assert.equal(inserted.ok,true);
      assert.equal(await editorPage.locator('#body').inputValue(),body);
      assert.equal(await editorPage.locator('script').count(),0);
      await editorPage.locator('#body').fill('変更前');await editorPage.locator('#body').focus();
      inspected=await evaluate(args);await editorPage.locator('#body').fill('別の内容へ変更');
      inserted=await evaluate({...args,mode:'insert',hash:inspected.hash,length:inspected.length});assert.equal(inserted.ok,false);
      assert.equal(await editorPage.locator('#body').inputValue(),'別の内容へ変更');
      await editorPage.locator('#body').evaluate(el=>el.maxLength=2);await editorPage.locator('#body').focus();
      assert.equal((await evaluate(args)).ok,false);
      for(const selector of ['#search','#other']){await editorPage.locator(selector).focus();assert.equal((await evaluate(args)).ok,false);}
      await editorPage.locator('#editable').focus();inspected=await evaluate(args);assert.equal(inspected.ok,true);
      inserted=await evaluate({...args,mode:'insert',hash:inspected.hash,length:inspected.length});assert.equal(inserted.ok,true);
      assert.equal((await editorPage.locator('#editable').innerText()).replace(/\r\n?/g,'\n'),body);
      await fixture.close();
    });
    // Runtime replay uses an independent UI session with a deterministic native journal double.
    const runtimeContext=await browser.newContext({viewport:{width:393,height:851},deviceScaleFactor:1});
    await runtimeContext.addInitScript(installNativeMock);
    const runtimePage=await runtimeContext.newPage(),runtimeDialogs=[];
    runtimePage.on('pageerror',error=>errors.push('runtime: '+error.message));
    runtimePage.on('dialog',dialog=>{runtimeDialogs.push(dialog.message());dialog.accept();});
    await runtimePage.goto(url);
    const baseRuntimeState=()=>({app:'NovelStudio',schemaVersion:1,books:[
      {id:'book_runtime_a',title:'作品A',synopsis:'研究者が未知の構造を調べる。',world:'港町と私設研究室。',characters:'玲司。黒髪の研究者。',style:'自然な日本語。',genre:'ファンタジー',chapters:[{id:'chapter_runtime_a1',title:'第1話',body:'元の本文A。編集済みの語句を保持する。'},{id:'chapter_runtime_a2',title:'第2話',body:'元の本文B。'}]},
      {id:'book_runtime_b',title:'作品B',synopsis:'別の作品。',world:'別の町。',characters:'結衣。',style:'自然な日本語。',genre:'現代',chapters:[{id:'chapter_runtime_b1',title:'別作品の第1話',body:'別作品の本文。'}]}
    ],activeBookId:'book_runtime_a',activeChapterId:'chapter_runtime_a1',prefs:{modelId:'mock-model',authNoticeSeen:true},bookmarks:{},runtimeReceipts:{}});
    const runtimeSnapshot=(records,activeTextId='',activeImageId='')=>({records,activeTextId,activeImageId,notificationsEnabled:false,backgroundReady:true});
    const textRecord=(request,seq,phase,text)=>({type:'text',requestId:request.requestId,bookId:request.bookId,chapterId:request.chapterId,purpose:request.purpose,kind:request.kind||'',mode:request.mode||'new',sourceBody:request.sourceBody,sourceValue:request.sourceValue,createdAt:request.createdAt||Date.now(),updatedAt:Date.now(),seq,phase,text,message:''});
    const publishRuntime=async (snapshot,emit=true)=>runtimePage.evaluate(({snapshot,emit})=>window.__setRuntimeSnapshot(snapshot,emit),{snapshot,emit});
    async function resetRuntimeFixture(fixture=baseRuntimeState()){
      await runtimePage.evaluate(fixture=>{
        // The old WebView has gone away; its unload save must not replace this new native fixture.
        window.__saveFails=true;localStorage.setItem('novelstudio.native-test-state',JSON.stringify(fixture));
        localStorage.removeItem('novelstudio.runtime-test-state');localStorage.removeItem('novelstudio.image-test-status');
      },fixture);
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      await runtimePage.waitForFunction(()=>document.getElementById('model-selector')&&document.getElementById('model-selector').value==='mock-model');
    }
    await runCheck('background text snapshot restores a live job, preserves another draft and deduplicates queued deltas',async()=>{
      await resetRuntimeFixture();await runtimePage.getByTestId('generate').click();
      const request=await runtimePage.evaluate(()=>window.__lastRequest);
      assert.equal(request.bookId,'book_runtime_a');assert.equal(request.chapterId,'chapter_runtime_a1');assert.equal(request.purpose,'manuscript');assert.equal(request.mode,'new');
      assert.equal(request.sourceBody,'元の本文A。編集済みの語句を保持する。');assert.ok(request.createdAt>0);
      await runtimePage.getByTestId('book-selector').selectOption('book_runtime_b');
      await runtimePage.getByTestId('chapter-body').evaluate(el=>{el.focus();el.value='編集中の別作品。未確定の入力も保持する。';el.setSelectionRange(4,4);});
      await publishRuntime(runtimeSnapshot([textRecord(request,2,'generating','甲乙')],request.requestId));
      assert.equal(await runtimePage.getByTestId('book-selector').inputValue(),'book_runtime_b');
      assert.equal(await runtimePage.getByTestId('chapter-body').inputValue(),'編集中の別作品。未確定の入力も保持する。');
      assert.equal(await runtimePage.evaluate(()=>document.activeElement.id),'chapter-body');
      await runtimePage.evaluate(request=>{
        window.onNativeEvent('generation',{...request,seq:1,phase:'delta',delta:'甲'});
        window.onNativeEvent('generation',{...request,seq:2,phase:'delta',delta:'乙'});
      },request);
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters[0].candidate.text,'甲乙');
      // A missing delta is recovered from the complete native snapshot before appending anything else.
      await publishRuntime(runtimeSnapshot([textRecord(request,4,'generating','甲乙丙丁')],request.requestId),false);
      await runtimePage.evaluate(request=>window.onNativeEvent('generation',{...request,seq:4,phase:'delta',delta:'丁'}),request);
      await runtimePage.evaluate(request=>{window.onNativeEvent('generation',{...request,seq:5,phase:'delta',delta:'戊'});window.onNativeEvent('generation',{...request,seq:5,phase:'delta',delta:'戊'});window.flushAppState();},request);
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters[0].candidate.text,'甲乙丙丁戊');
      await publishRuntime(runtimeSnapshot([textRecord(request,6,'generating','甲乙丙丁戊己')],request.requestId),false);
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      const resumed=await runtimePage.evaluate(()=>window.__savedState());
      assert.equal(resumed.activeBookId,'book_runtime_b');assert.equal(resumed.books[1].chapters[0].body,'編集中の別作品。未確定の入力も保持する。');
      assert.equal(resumed.books[0].chapters[0].candidate.status,'generating');assert.equal(resumed.books[0].chapters[0].candidate.text,'甲乙丙丁戊己');
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generate').length),0);
      await runtimePage.getByTestId('book-selector').selectOption('book_runtime_a');
      assert.match(await runtimePage.getByTestId('candidate').innerText(),/生成中/);assert.doesNotMatch(await runtimePage.getByTestId('candidate').innerText(),/中断した/);
      assert.equal(await runtimePage.getByTestId('chapter-body').inputValue(),request.sourceBody);
    });
    await runCheck('journal-merged terminal text is acknowledged only after save and never reappears after adoption',async()=>{
      await resetRuntimeFixture();await runtimePage.getByTestId('generate').click();
      const request=await runtimePage.evaluate(()=>window.__lastRequest),record=textRecord(request,10,'complete','バックグラウンドで完成した本文。');
      await runtimePage.evaluate(({record,snapshot})=>{
        const saved=window.__savedState();Object.assign(saved.books[0].chapters[0].candidate,{status:'complete',seq:record.seq,text:record.text});
        window.__saveFails=true;localStorage.setItem('novelstudio.native-test-state',JSON.stringify(saved));window.__setRuntimeSnapshot(snapshot);
      },{record,snapshot:runtimeSnapshot([record])});
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      const acks=await runtimePage.evaluate(()=>window.__acks);
      assert.equal(acks.length,1);assert.equal(acks[0].durable,true);assert.equal(acks[0].seq,10);
      assert.equal(acks[0].saved.books[0].chapters[0].candidate.text,record.text);assert.equal(acks[0].saved.runtimeReceipts[request.requestId].terminal,true);
      assert.equal(await runtimePage.getByTestId('chapter-body').inputValue(),request.sourceBody);
      await runtimePage.getByTestId('adopt-candidate').click();
      assert.equal(await runtimePage.getByTestId('chapter-body').inputValue(),record.text);
      await runtimePage.getByTestId('chapter-body').fill('採用後に自分で編集した本文。');
      await publishRuntime(runtimeSnapshot([record]));
      await runtimePage.evaluate(request=>window.onNativeEvent('generation',{...request,seq:9,phase:'delta',delta:'重複させてはいけない'}),request);
      const saved=await runtimePage.evaluate(()=>window.__savedState());
      assert.equal(saved.books[0].chapters[0].candidate,null);assert.equal(saved.books[0].chapters[0].body,'採用後に自分で編集した本文。');
      assert.equal(await runtimePage.locator('[data-testid="candidate"]').count(),0);
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generate').length),0);
    });
    await runCheck('background metadata reconstructs its original work and preserves edits in the visible work',async()=>{
      await resetRuntimeFixture();await runtimePage.locator('#work-details summary').first().click();
      await runtimePage.getByTestId('generate-titles').click();
      const request=await runtimePage.evaluate(()=>window.__lastRequest);
      assert.equal(request.purpose,'metadata');assert.equal(request.kind,'titles');assert.equal(request.sourceValue,'作品A');assert.equal(request.bookId,'book_runtime_a');assert.equal(request.chapterId,'chapter_runtime_a1');
      await runtimePage.getByTestId('book-selector').selectOption('book_runtime_b');
      await runtimePage.evaluate(({request,snapshot})=>{
        const saved=window.__savedState();saved.books[0].titleCandidate=null;window.__saveFails=true;
        localStorage.setItem('novelstudio.native-test-state',JSON.stringify(saved));window.__setRuntimeSnapshot(snapshot);
      },{request,snapshot:runtimeSnapshot([textRecord(request,2,'generating','{"titles":[')],request.requestId)});
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();await runtimePage.locator('#work-details summary').first().click();
      assert.equal(await runtimePage.getByTestId('book-selector').inputValue(),'book_runtime_b');
      await runtimePage.locator('#work-title').evaluate(el=>{el.focus();el.value='別作品の編集を保持';});
      const complete=textRecord(request,4,'complete','{"titles":["構造を育てる研究者","神性の設計者"]}');
      await publishRuntime(runtimeSnapshot([complete]));
      assert.equal(await runtimePage.locator('#work-title').inputValue(),'別作品の編集を保持');
      const saved=await runtimePage.evaluate(()=>window.__savedState());
      assert.equal(saved.books[0].title,'作品A');assert.equal(saved.books[0].titleCandidate.status,'complete');assert.deepEqual(saved.books[0].titleCandidate.items,['構造を育てる研究者','神性の設計者']);
      assert.equal(saved.books[1].titleCandidate,null);assert.equal(saved.books[1].title,'別作品の編集を保持');
      assert.ok((await runtimePage.evaluate(()=>window.__acks)).every(a=>a.durable));
      await runtimePage.getByTestId('book-selector').selectOption('book_runtime_a');await runtimePage.getByTestId('adopt-title-1').click();
      assert.equal(await runtimePage.locator('#work-title').inputValue(),'神性の設計者');
    });
    await runCheck('interrupted process results are restored without resubmission and missing source requires confirmation',async()=>{
      await resetRuntimeFixture();
      const record={type:'text',requestId:'req_runtime_interrupted',bookId:'book_runtime_a',chapterId:'chapter_runtime_a1',purpose:'manuscript',mode:'rewrite',phase:'interrupted',seq:3,text:'保存されていた途中の候補。',message:'処理が中断されました。自動で再送信はしません。',createdAt:Date.now()-2000,sourceUnavailable:true};
      await publishRuntime(runtimeSnapshot([record]));
      const saved=await runtimePage.evaluate(()=>window.__savedState());
      assert.equal(saved.books[0].chapters[0].candidate.status,'interrupted');assert.equal(saved.books[0].chapters[0].candidate.sourceUnavailable,true);
      assert.equal(saved.books[0].chapters[0].candidate.sourceBody,'');assert.equal(saved.books[0].chapters[0].body,'元の本文A。編集済みの語句を保持する。');
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generate').length),0);
      const count=runtimeDialogs.length;await runtimePage.getByTestId('adopt-candidate').click();
      assert.ok(runtimeDialogs.slice(count).some(m=>m.includes('生成開始時の本文を確認できません')));
      assert.equal(await runtimePage.getByTestId('chapter-body').inputValue(),record.text);
      assert.ok((await runtimePage.evaluate(()=>window.__acks)).every(a=>a.durable));
    });
    await runCheck('background image replay restores active progress and deduplicates already merged image metadata',async()=>{
      await resetRuntimeFixture();await runtimePage.locator('.illustration-options summary').click();await runtimePage.getByTestId('configure-image-api').click();await runtimePage.getByTestId('generate-illustration').click();
      const request=await runtimePage.evaluate(()=>window.__lastImageRequest),record={type:'image',requestId:request.requestId,bookId:request.bookId,chapterId:request.chapterId,purpose:'manuscript',kind:'api',seq:0,phase:'generating',createdAt:request.createdAt,message:''};
      await publishRuntime(runtimeSnapshot([record],'',request.requestId),false);
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      assert.equal(await runtimePage.getByTestId('generate-illustration').isDisabled(),true);assert.match(await runtimePage.getByTestId('illustration-panel').innerText(),/挿絵を生成しています/);
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generateIllustration').length),0);
      const image={id:'33333333-3333-4333-8333-333333333333',mime:'image/png',width:1024,height:1536,createdAt:Date.now(),source:'api',prompt:'テスト用の保存済みプロンプト。'};
      const complete={...record,phase:'complete',seq:2,image};
      await runtimePage.evaluate(({complete,snapshot})=>{
        const saved=window.__savedState(),c=saved.books[0].chapters[0];c.illustrations=[complete.image];c.illustrationRequest={requestId:complete.requestId,kind:'api',status:'complete',seq:complete.seq,createdAt:complete.createdAt};
        window.__saveFails=true;localStorage.setItem('novelstudio.native-test-state',JSON.stringify(saved));window.__setRuntimeSnapshot(snapshot);
      },{complete,snapshot:runtimeSnapshot([complete])});
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      let saved=await runtimePage.evaluate(()=>window.__savedState());
      assert.equal(saved.books[0].chapters[0].illustrations.length,1);assert.equal(saved.books[0].chapters[0].illustrationRequest,null);assert.equal(saved.books[0].chapters[0].body,'元の本文A。編集済みの語句を保持する。');
      assert.equal((await runtimePage.evaluate(()=>window.__acks))[0].durable,true);
      await runtimePage.evaluate(complete=>window.onNativeEvent('illustration',complete),complete);
      saved=await runtimePage.evaluate(()=>window.__savedState());assert.equal(saved.books[0].chapters[0].illustrations.length,1);
      await runtimePage.getByTestId('nav-reader').click();assert.equal(await runtimePage.locator('.reader-gallery img').count(),1);
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generateIllustration').length),0);
    });
    await runCheck('terminal acknowledgement waits for durable UI save and retries native acknowledgement failure',async()=>{
      await resetRuntimeFixture();await runtimePage.getByTestId('generate').click();
      const request=await runtimePage.evaluate(()=>window.__lastRequest),complete=textRecord(request,5,'complete','保存失敗から回復する生成結果。');
      await runtimePage.evaluate(()=>{window.__saveFails=true;window.__ackFails=true;});
      await publishRuntime(runtimeSnapshot([complete]));
      assert.equal(await runtimePage.evaluate(()=>window.__acks.length),0);
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters[0].candidate.status,'generating');
      assert.equal(await runtimePage.locator('#candidate-text').innerText(),complete.text);
      await runtimePage.evaluate(()=>{window.__saveFails=false;window.flushAppState();});
      assert.equal(await runtimePage.evaluate(()=>window.__acks.length),1);assert.equal((await runtimePage.evaluate(()=>window.__acks))[0].durable,true);
      assert.equal((await runtimePage.evaluate(()=>window.__runtimeSnapshot())).records.length,1);
      await runtimePage.evaluate(()=>{window.__ackFails=false;window.flushAppState();});
      assert.equal(await runtimePage.evaluate(()=>window.__acks.length),2);assert.equal((await runtimePage.evaluate(()=>window.__runtimeSnapshot())).records.length,0);
      await runtimePage.evaluate(()=>window.flushAppState());assert.equal(await runtimePage.evaluate(()=>window.__acks.length),2);
      assert.ok((await runtimePage.evaluate(()=>window.__acks)).every(a=>a.durable));
    });
    await runCheck('conflicting candidates and a full image collection retain unacknowledged native results',async()=>{
      const fixture=baseRuntimeState();fixture.books[0].titleCandidate={requestId:'meta_current_kept',kind:'titles',text:'{"titles":["手元の候補"]}',items:['手元の候補'],sourceValue:'作品A',status:'complete',seq:1,createdAt:Date.now()};
      await resetRuntimeFixture(fixture);
      const conflicting={type:'text',requestId:'meta_older_pending',bookId:'book_runtime_a',chapterId:'chapter_runtime_a1',purpose:'metadata',kind:'titles',mode:'new',sourceValue:'作品A',text:'{"titles":["上書きしてはいけない"]}',phase:'complete',seq:4,createdAt:Date.now()};
      await publishRuntime(runtimeSnapshot([conflicting]));
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].titleCandidate.requestId,'meta_current_kept');assert.equal(await runtimePage.evaluate(()=>window.__acks.length),0);assert.equal((await runtimePage.evaluate(()=>window.__runtimeSnapshot())).records.length,1);
      const imageFixture=baseRuntimeState();imageFixture.books[0].chapters[0].illustrations=Array.from({length:50},(_,i)=>({id:'40000000-0000-4000-8000-'+String(i).padStart(12,'0'),mime:'image/png',width:100,height:100,createdAt:Date.now(),source:'import',prompt:''}));
      await resetRuntimeFixture(imageFixture);
      const blocked={type:'image',requestId:'image_pending_full',bookId:'book_runtime_a',chapterId:'chapter_runtime_a1',kind:'api',phase:'complete',seq:2,createdAt:Date.now(),image:{id:'55555555-5555-4555-8555-555555555555',mime:'image/png',width:100,height:100,createdAt:Date.now(),source:'api',prompt:''}};
      await publishRuntime(runtimeSnapshot([blocked]));
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters[0].illustrations.length,50);assert.equal(await runtimePage.evaluate(()=>window.__acks.length),0);
      const existing={...blocked,requestId:'image_already_present',image:imageFixture.books[0].chapters[0].illustrations[0]};
      await publishRuntime(runtimeSnapshot([blocked,existing]));
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters[0].illustrations.length,50);assert.deepEqual((await runtimePage.evaluate(()=>window.__acks)).map(a=>a.requestId),['image_already_present']);
      assert.deepEqual((await runtimePage.evaluate(()=>window.__runtimeSnapshot())).records.map(r=>r.requestId),['image_pending_full']);
    });
    await runCheck('background settings expose notification and battery actions without resending active generation',async()=>{
      await resetRuntimeFixture();await runtimePage.getByTestId('generate').click();const request=await runtimePage.evaluate(()=>window.__lastRequest);
      const active=textRecord(request,1,'generating','通知を許可する前から生成している。');await publishRuntime(runtimeSnapshot([active],request.requestId));
      await runtimePage.getByTestId('settings').click();assert.match(await runtimePage.getByTestId('background-status').innerText(),/生成中/);assert.match(await runtimePage.getByTestId('notification-status').innerText(),/無効/);
      await runtimePage.evaluate(()=>window.onNativeEvent('notice',{message:'ファイル操作だけの通知。'}));
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters[0].candidate.status,'generating');assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='cancelGeneration').length),0);
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='openBackgroundSettings').length),0);
      await runtimePage.getByTestId('generation-notifications').click();assert.match(await runtimePage.getByTestId('notification-status').innerText(),/有効/);
      await runtimePage.getByTestId('background-battery-settings').click();assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='openBackgroundSettings').length),1);
      await runtimePage.getByTestId('refresh-runtime').click();assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(c=>c[0]==='generate').length),1);
      await runtimePage.setViewportSize({width:360,height:900});assert.equal(await runtimePage.locator('body').evaluate(el=>el.scrollWidth<=window.innerWidth+1),true);
      await runtimePage.screenshot({path:path.join(output,'background-settings-phone.png'),fullPage:true});
      const done=textRecord(request,2,'complete','通知設定の変更後に完成した本文。');await publishRuntime({...runtimeSnapshot([done]),notificationsEnabled:true});
      assert.match(await runtimePage.getByTestId('background-status').innerText(),/バックグラウンドで生成できます/);assert.ok((await runtimePage.evaluate(()=>window.__acks)).every(a=>a.durable));
    });

    await runCheck('single partial resume retains original prompt through reload and tab changes',async()=>{
      await resetRuntimeFixture();await runtimePage.getByTestId('generate').click();
      const req=await runtimePage.evaluate(()=>window.__lastRequest);
      await runtimePage.evaluate(req=>window.onNativeEvent('generation',{...req,phase:'error',text:'文章の途中',message:'通信切断'}),req);
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      await runtimePage.getByTestId('resume-candidate').click();
      const resume=await runtimePage.evaluate(()=>window.__lastRequest);
      assert.equal(resume.resumePrefix,'文章の途中');assert.equal(resume.prompt,req.prompt);assert.notEqual(resume.requestId,req.requestId);
      await runtimePage.getByTestId('nav-reader').click();
      await runtimePage.evaluate(req=>window.onNativeEvent('generation',{...req,phase:'complete',text:'文章の途中から続いた。'}),resume);
      await runtimePage.getByTestId('nav-editor').click();
      assert.equal(await runtimePage.locator('#candidate-text').innerText(),'文章の途中から続いた。');
      await runtimePage.getByTestId('adopt-candidate').click();
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters[0].body,'文章の途中から続いた。');
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(x=>x[0]==='cancelGeneration').length),0);
    });
    await runCheck('100 episode UI reserves only new chapters and continues across all tabs',async()=>{
      await resetRuntimeFixture();
      await runtimePage.locator('#batch-count').fill('101');await runtimePage.getByTestId('start-batch').click();
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(x=>x[0]==='startBatchGeneration').length),0);
      await runtimePage.locator('#batch-count').fill('100');await runtimePage.locator('#batch-plan').fill('1〜10話で神性を解析。以後は身体へ融合し強化。');
      await runtimePage.getByTestId('start-batch').click();
      const input=await runtimePage.evaluate(()=>window.__lastBatch),saved=await runtimePage.evaluate(()=>window.__savedState());
      assert.equal(input.items.length,100);assert.match(input.plan,/神性/);assert.equal(saved.books[0].chapters.length,102);assert.equal(saved.books[0].chapters[0].body,'元の本文A。編集済みの語句を保持する。');
      assert.match(await runtimePage.getByTestId('batch-progress').innerText(),/0 \/ 100/);
      for(const tab of ['reader','publish','shelf','editor'])await runtimePage.getByTestId('nav-'+tab).click();
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(x=>x[0]==='cancelGeneration').length),0);
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(x=>x[0]==='startBatchGeneration').length),1);
      await runtimePage.getByTestId('chapter-selector').selectOption(input.items[0].id);
      assert.equal(await runtimePage.getByTestId('chapter-body').getAttribute('readonly'),'');
      await runtimePage.setViewportSize({width:360,height:900});assert.equal(await runtimePage.locator('body').evaluate(el=>el.scrollWidth<=window.innerWidth+1),true);
      await runtimePage.screenshot({path:path.join(output,'batch-running-phone.png'),fullPage:true});
    });
    await runCheck('batch stream replay and WebView reload do not duplicate or lose received text',async()=>{
      const send=async (seq,delta,text)=>runtimePage.evaluate(({seq,delta,text})=>{
        const snap=window.__runtimeSnapshot(),b=snap.batch,item=b.items[0];item.text=text;item.streamSeq=seq;item.revision=seq+2;window.__setRuntimeSnapshot(snap,false);
        window.onNativeEvent('batchDelta',{batchId:b.id,bookId:b.bookId,chapterId:item.id,requestId:item.requestId,phase:'delta',seq,delta});
      },{seq,delta,text});
      await send(1,'一','一');assert.equal(await runtimePage.locator('#candidate-text').innerText(),'一');
      await runtimePage.evaluate(()=>window.__setRuntimeSnapshot(window.__runtimeSnapshot(),true));
      await send(2,'二','一二');assert.equal(await runtimePage.locator('#candidate-text').innerText(),'一二');
      await runtimePage.evaluate(()=>window.__setRuntimeSnapshot(window.__runtimeSnapshot(),true));
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      await send(3,'三','一二三');assert.equal(await runtimePage.locator('#candidate-text').innerText(),'一二三');
      await send(3,'三','一二三');assert.equal(await runtimePage.locator('#candidate-text').innerText(),'一二三');
    });
    await runCheck('batch pause/reload/resume retains partial and completed chapters without resubmission',async()=>{
      await runtimePage.evaluate(()=>{
        const snap=window.__runtimeSnapshot(),b=snap.batch;b.completed=1;b.status='paused';b.message='通信が中断しました。保存した位置から再開できます。';
        Object.assign(b.items[0],{status:'complete',text:'一括生成1話目の本文。',revision:100});
        Object.assign(b.items[1],{status:'paused',text:'2話目の途中',requestId:'batch_request_1',revision:100});window.__setRuntimeSnapshot(snap,true);
      });
      let saved=await runtimePage.evaluate(()=>window.__savedState());assert.equal(saved.books[0].chapters[2].body,'一括生成1話目の本文。');assert.equal(saved.books[0].chapters[3].candidate.text,'2話目の途中');
      await runtimePage.reload();await runtimePage.getByTestId('nav-editor').click();
      assert.match(await runtimePage.getByTestId('batch-progress').innerText(),/1 \/ 100/);assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(x=>x[0]==='startBatchGeneration'||x[0]==='resumeBatchGeneration').length),0);
      await runtimePage.getByTestId('chapter-selector').selectOption(saved.books[0].chapters[3].id);
      assert.equal(await runtimePage.locator('#candidate-text').innerText(),'2話目の途中');
      await runtimePage.screenshot({path:path.join(output,'batch-paused-phone.png'),fullPage:true});
      await runtimePage.getByTestId('resume-batch').click();
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(x=>x[0]==='resumeBatchGeneration').length),1);
      saved=await runtimePage.evaluate(()=>window.__savedState());assert.equal(saved.books[0].chapters[2].body,'一括生成1話目の本文。');assert.equal(saved.books[0].chapters[3].candidate.text,'2話目の途中');
    });
    await runCheck('100 completed chapters merge once and preserve subsequent manual edits',async()=>{
      await runtimePage.evaluate(()=>{const snap=window.__runtimeSnapshot(),b=snap.batch;b.completed=100;b.status='complete';b.message='100話の生成を完了しました。';b.items.forEach((x,i)=>Object.assign(x,{status:'complete',text:i===0?'一括生成1話目の本文。':'一括生成'+(i+1)+'話目の完成本文。',revision:i===0?100:200}));window.__setRuntimeSnapshot(snap,true);});
      let saved=await runtimePage.evaluate(()=>window.__savedState());assert.equal(saved.books[0].chapters.length,102);
      for(let i=2;i<102;i++)assert.ok(saved.books[0].chapters[i].body.length>0);
      assert.match(await runtimePage.getByTestId('batch-progress').innerText(),/100 \/ 100/);
      await runtimePage.getByTestId('chapter-body').fill('完成後に手動で修正した本文。');await runtimePage.evaluate(()=>window.flushAppState());
      await runtimePage.evaluate(()=>window.__setRuntimeSnapshot(window.__runtimeSnapshot(),true));assert.equal(await runtimePage.getByTestId('chapter-body').inputValue(),'完成後に手動で修正した本文。');
      await runtimePage.getByTestId('close-batch').click();await runtimePage.getByTestId('batch-count').waitFor();
      await runtimePage.setViewportSize({width:700,height:900});assert.equal(await runtimePage.locator('body').evaluate(el=>el.scrollWidth<=window.innerWidth+1),true);
      await runtimePage.screenshot({path:path.join(output,'batch-controls-fold.png'),fullPage:true});
    });
    await runCheck('empty work reuses the initial blank chapter and failed save prevents batch start',async()=>{
      const fixture=baseRuntimeState();fixture.books[0].chapters=[{id:'chapter_runtime_a1',title:'第1話',body:''}];await resetRuntimeFixture(fixture);
      await runtimePage.evaluate(()=>window.__saveFails=true);await runtimePage.getByTestId('start-batch').click();
      assert.equal(await runtimePage.evaluate(()=>window.__nativeCalls.filter(x=>x[0]==='startBatchGeneration').length),0);
      await runtimePage.evaluate(()=>window.__saveFails=false);await runtimePage.getByTestId('start-batch').click();
      assert.equal((await runtimePage.evaluate(()=>window.__savedState())).books[0].chapters.length,100);
      assert.equal((await runtimePage.evaluate(()=>window.__lastBatch)).items[0].id,'chapter_runtime_a1');
    });
    await runtimeContext.close();
    assert.deepEqual(errors,[]);
  } finally {
    fs.writeFileSync(path.join(output,'ui-results.json'),JSON.stringify({scope:'Local HTML UI with a mocked native bridge; not real Android/OAuth/posting verification',results,errors},null,2));
    await browser.close();
    await new Promise(resolve=>server.close(resolve));
  }
  console.log('QA output: '+output);
}
main().catch(error=>{console.error(error.stack);server.close();process.exitCode=1;});
