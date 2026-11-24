const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const code=fs.readFileSync(require('node:path').join(__dirname, '../../vega/src/main/assets/ui/app.js'),'utf8');
const fields=new Map(),field=id=>{if(!fields.has(id))fields.set(id,{value:'',style:{},dataset:{},setAttribute(){},addEventListener(){},focus(){},classList:{add(){},toggle(){}}});return fields.get(id)};
const mock={home:{buttons:[]},send:{error:'chyba'},runTool:{error:'chyba'}};
const calls=[];
const ctx=vm.createContext({document:{body:{classList:{toggle(){},add(){},remove(){}},appendChild(){}},querySelector:field,querySelectorAll:()=>[],addEventListener(){}},matchMedia:()=>({matches:false}),innerHeight:850,window:{VEGA:new Proxy({},{get(_,k){return (...a)=>{calls.push([k,...a]);return JSON.stringify(mock[k]??true)}}})}});
let run=s=>vm.runInContext(s,ctx);
// Inicializaci skutečného WebView je třeba ověřit na telefonu. Zde se testují obslužné funkce.
run(code.slice(0,code.indexOf('S.busy = J(B.isBusy()')));
run('toast=()=>{}; renderChips=()=>{}; render=()=>{}; autosize=()=>{};');
assert.equal((run('emptyState()').match(/btnchip ex/g)||[]).length,2);
mock.home.airgap=true;assert.match(run('emptyState()'),/Bez sítě · místní nástroje/);
run("window.vega.on('files',{files:[{id:'a'},{id:'b',evicted:['a']}]})");assert.equal(run("S.files.map(f=>f.id).join()"),'b');
field('#input').value='návrh';run("window.vega.on('shared',{text:'sdíleno'})");assert.equal(field('#input').value,'návrh\nsdíleno');
run('send()');assert.equal(field('#input').value,'návrh\nsdíleno');assert.equal(run('S.busy || S.files.length !== 1'),false);
mock.send=true;run('send()');assert.equal(field('#input').value,'');assert.equal(run('S.busy && S.files.length===0'),true);
field('[data-tool-text]').value='text';run("S.busy=false; S.tool={id:'x'};runTool()");assert.equal(run("S.tool.id==='x' && !S.busy"),true);assert.equal(field('[data-tool-text]').value,'text');
run("S.space={kind:'temp'}; window.vega.on('files',{files:[{id:'c'}]})");assert.equal(run('S.files.length'),0);
// draft podle konverzace: přepnutí chatu nepřenese text ani přílohy k jinému adresátovi
run("S.drafts={}; S.req=null; S.files=[]; S.space={kind:'chat',id:'1'}");field('#input').value='rozdělaná zpráva pro chat 1';
run("setSpace({kind:'chat',id:'2'})");assert.equal(field('#input').value,'');
run("setSpace({kind:'chat',id:'1'})");assert.equal(field('#input').value,'rozdělaná zpráva pro chat 1');
// vyhozená příloha zmizí i z draftu jiné konverzace
run("S.drafts={'chat:9':{text:'x',files:[{id:'old'},{id:'keep'}]}}; S.space={kind:'chat',id:'1'}; S.files=[]; window.vega.on('files',{files:[{id:'new',evicted:['old']}]})");
assert.equal(run("S.drafts['chat:9'].files.map(f=>f.id).join()"),'keep');
// zjednodušené UI (22. 11.): úvod bez „Zeptat se“ a bez duplicitního tlačítka modelu (jen stav Bez sítě); menu bez „Vylepšení“
mock.home={buttons:[],engine:true,model:'Gemini'};run("S.space={kind:'chat',id:'1'}");
let h=run('emptyState()');assert.ok(!/data-act="ask"/.test(h)&&!/pickModel/.test(h),'úvod má být bez Zeptat se a bez tlačítka modelu');
mock.home={buttons:[],engine:true,airgap:true};assert.ok(/pickModel/.test(run('emptyState()')),'v Bez sítě má být stav vidět');
mock.chats=[];run('closeSheet=()=>{}; drawer()');assert.ok(!/Vylepšení/.test(field('#layer').innerHTML),'menu bez Vylepšení');
// „Nová konverzace“ v prázdném chatu nezakládá další prázdný chat
let created=0;mock.messages=[];Object.defineProperty(mock,'newChat',{configurable:true,get(){created++;return 'x'}});
run("S.space={kind:'chat',id:'e1'}");run("act('new',{})");assert.equal(created,0,'prázdný chat se má znovu použít');
// kontrola, že počítadlo funguje: z neprázdného chatu bez prázdného v seznamu se nová konverzace založí
mock.messages=[{role:'user',text:'x'}];mock.chats=[{id:'e1'}];run("S.space={kind:'chat',id:'e1'}");run("act('new',{})");assert.equal(created,1);
// tlačítko z hlášky „Vypnout Bez sítě“ / „Zapnout veřejný web“ opravdu přepne nastavení
run("renderTop=()=>{};");calls.length=0;
run("act('netOn',{what:'airgap'}); act('netOn',{what:'web'})");assert.deepEqual(calls.filter(c=>c[0]==='setSetting').map(c=>c.slice(1).join('=')),['airgap=false','webAllowed=true']);
// vadná karta (stará verze, chybí pole) nesmaže celou konverzaci – jen se nahradí poznámkou
assert.match(run("safeCard({type:'diff'},true)"),/nejde zobrazit/);
// noční kolo N10 (snímek z telefonu): „Odpověď se nedokončila… klepni na Pokračovat“, uživatel pak napsal další zprávu → tlačítko zmizelo
assert.match(run("safeCard({type:'resume',req:'r1',text:'Proč se země točí?'},true)"),/data-act="retry" data-req="r1">Pokračovat/);
assert.match(run("safeCard({type:'resume',req:'r1',text:'Proč se země točí?'},false)"),/data-act="retry" data-req="r1"[^>]*>Pokračovat/,'Pokračovat i u starší zprávy');
// noční kolo N13: čekání na NFC štítek z chatu jde zrušit (dřív jen minutový limit)
assert.match(run("S.nfc=true, safeCard({type:'nfcWait'},true)"),/data-act="nfcCancel">Zrušit/);
// nezobrazitelný dotaz na souhlas se odmítne (nativní strana nečeká 5 minut, nic neodejde)
run("console.error=()=>{}; toast=()=>{};");calls.length=0;run("window.vega.on('consent',{req:'r9'})");
assert.ok(calls.some(c=>c[0]==='answer'&&c[1]==='r9'&&/false/.test(c[2])),'souhlas má být odmítnut');
// markdown z modelu: odrážky „*   “, tabulky, kurzíva; násobení „2 * 3 * 4“ zůstane
assert.match(run("md('*   **Cena:** 250 Kč')"),/^• <b>Cena:<\/b>/);
assert.match(run("md('| A | B |\\n|---|---|\\n| 1 | <x> |')"),/<table><tr><th>A<\/th><th>B<\/th><\/tr><tr><td>1<\/td><td>&lt;x&gt;<\/td><\/tr><\/table>/);
assert.equal(run("md('2 * 3 * 4 a *slovo*')"),'2 * 3 * 4 a <i>slovo</i>');
// Kopírovat: čistý text bez markdownu jde do nativní schránky
assert.equal(run("plainText('## Návrh\\n**Dobrý den,**\\n*   bod\\n| A | B |\\n|---|---|\\n| 1 | 2 |\\nviz [web](https://x.cz)')"),'Návrh\nDobrý den,\n• bod\nA\tB\n1\t2\nviz web (https://x.cz)');
mock.messages=[{role:'vega',text:'**Ahoj**, tady je dlouhý e-mail pro šéfa, který si chci zkopírovat do Gmailu.'}];calls.length=0;
run("S.mc=null; act('copyMsg',{i:'0'})");assert.deepEqual(calls.filter(c=>c[0]==='copyText').map(c=>c[1]),['Ahoj, tady je dlouhý e-mail pro šéfa, který si chci zkopírovat do Gmailu.']);
// C1: Upravit vrátí text do pole, Znovu volá nativní regenerate, kód má vlastní Kopírovat
mock.messages=[{role:'user',text:'napiš básničku'},{role:'vega',text:'Tady je básnička o podzimu a padajícím listí, která má čtyři verše.'}];
run("S.space={kind:'chat',id:'c1'}; S.busy=false; S.mc=null; render()");
run('renderStream()');assert.ok(/data-act="editLast"/.test(field('#stream').innerHTML)&&/data-act="regen"/.test(field('#stream').innerHTML),'Upravit a Znovu mají být vidět');
mock.editLast='napiš básničku';calls.length=0;run("act('editLast',{})");assert.equal(field('#input').value,'napiš básničku');
calls.length=0;mock.regenerate=true;run("S.busy=false; act('regen',{})");assert.ok(calls.some(c=>c[0]==='regenerate'));run("S.busy=false");
mock.messages=[{role:'vega',text:'Kód:\n```js\nconsole.log(1)\n```'}];run("S.mc=null; renderStream()");assert.match(field('#stream').innerHTML,/data-code="console.log\(1\)\n"/);calls.length=0;run("act('copyCode',{code:'console.log(1)'})");
assert.deepEqual(calls.filter(c=>c[0]==='copyText').map(c=>c[1].trim()),['console.log(1)']);
// C6: sdílení celé konverzace jako čistý text
assert.equal(run("chatPlain([{role:'user',text:'kolik je 2+2'},{role:'vega',text:'**4**\\n\\n_(spočítáno přesně v telefonu, bez modelu)_'}],'Počty')"),'Počty\n\nJá: kolik je 2+2\n\nVEGA: 4');
// feature freeze: úvod max 6 akcí; Hardware/WiFi/Projekty nejsou v „+“ ani v menu
mock.home={buttons:[1,2,3,4,5,6,7].map(i=>({id:'b'+i,name:'Tlačítko '+i,inputKind:'BYTES'})),engine:true,project:{name:'P'}};run("S.space={kind:'chat',id:'c0'}");
assert.ok((run('emptyState()').match(/data-act="(runBtn|say|pick|findDocs|freeSetup|pickModel)"/g)||[]).length<=6,'úvod má mít nejvýš 6 akcí');
mock.home={buttons:[],engine:false};assert.ok((run('emptyState()').match(/data-act="(runBtn|say|pick|findDocs|freeSetup|pickModel)"/g)||[]).length<=6);
run("closeSheet=()=>{}; plusSheet()");assert.ok(!/data-act="(hw|wifi|folder)"/.test(field('#layer').innerHTML),'„+“ bez Hardware/WiFi/projektu');
mock.chats=[];run("drawer()");assert.ok(!/data-tab="projects"/.test(field('#layer').innerHTML),'menu bez Projektů');
// ranní přehled: sekce v Nastavení – zdroje, stav, Vyzkoušet teď; bez přesného času tlačítko Povolit
mock.newsExact={exact:false};const nh=run("newsSettingsHtml({enabled:true,sources:[{id:'novinky',name:'Novinky.cz',on:true},{id:'ct24',name:'ČT24',on:false}],last:'3 témat: <b>x</b>'})");
assert.ok(/data-news-src="novinky" checked/.test(nh)&&/data-news-src="ct24" >/.test(nh)&&/data-act="newsNow"/.test(nh)&&/data-act="newsExact"/.test(nh)&&/&lt;b&gt;/.test(nh));
assert.ok(!/data-news-src/.test(run("newsSettingsHtml({enabled:false})")),'vypnuto = jen přepínač');
// den 24. 11., kolo 17: výpis v PDF/Excelu → první návrh „Zpracovat výpis“ (ne „Shrnout“ do modelu); po výpisu návrhy dotazů bez modelu
run("S.tool=null; S.busy=false; S.space={kind:'chat',id:'s1'}");mock.procedures=[];
run("S.files=[{id:'f',name:'vypis-listopad.pdf'}]");assert.equal(run("chips()[0].t"),'Zpracovat výpis');
run("S.files=[{id:'f',name:'Pohyby_na_uctu.xlsx'}]");assert.equal(run("chips()[0].t"),'Zpracovat výpis');
run("S.files=[{id:'f',name:'smlouva.pdf'}]");assert.equal(run("chips()[0].t"),'Shrnout');
run("S.files=[]; S.savedRuns=new Set(); S.lastCard={type:'run',run:'r1',value:{kind:'TABLE',headers:['Měsíc','Příjmy','Výdaje','Saldo','Pohybů'],rows:[]}}");
const sc=run("chips()");assert.equal(JSON.stringify(sc.map(c=>c.t)),JSON.stringify(['Za co utrácím nejvíc?','Pravidelné platby','Uložit pohyby do Excelu']));
assert.ok(sc.every(c=>/^say:/.test(c.a)));
run("S.lastCard={type:'run',run:'r2',save:{x:1},value:{kind:'TABLE',headers:['Měsíc','Příjmy','Výdaje','Saldo','Pohybů'],rows:[]}}");
assert.equal(JSON.stringify(run("chips()").map(c=>c.t)),JSON.stringify(['Uložit jako nástroj','Za co utrácím nejvíc?','Pravidelné platby']));
run("S.lastCard={type:'run',run:'r3',value:{kind:'TABLE',headers:['A','B'],rows:[]}}");assert.equal(run("chips().length"),0);
// kolo 18: „↻ Znovu“ jen u odpovědi modelu – výpočet z výpisu („bez modelu“) by vyšel stejně
mock.messages=[{role:'user',text:'jaká byla největší platba?'},{role:'vega',text:'Největší platby:\n• −12 500,00 Kč – Nájem byt (5. 11.)\n_Z tabulky výpisu, bez modelu._'}];
run("S.space={kind:'chat',id:'c2'}; S.busy=false; S.mc=null; render()");run('renderStream()');
assert.ok(!/data-act="regen"/.test(field('#stream').innerHTML)&&/data-act="copyMsg"/.test(field('#stream').innerHTML),'bez Znovu, Kopírovat ano');
// kolo 19: návrh na už položenou otázku (po kartě výpisu) zmizí, ostatní zůstanou; nová karta = znovu všechny
const stc={type:'run',run:'r9',value:{kind:'TABLE',headers:['Měsíc','Příjmy','Výdaje','Saldo','Pohybů'],rows:[]}};
mock.messages=[{role:'user',text:'Zpracuj výpis z banky.'},{role:'vega',text:'Výpis zpracován.',card:stc},{role:'user',text:'za co utrácím nejvíc'},{role:'vega',text:'Nejvíc utrácíš za: **Bydlení**.\n_bez modelu_'}];
run("S.space={kind:'chat',id:'c3'}; S.busy=false; S.files=[]; S.mc=null; renderChips=()=>{}; render()");run('renderStream()');
assert.equal(JSON.stringify(run("chips()").map(c=>c.t)),JSON.stringify(['Pravidelné platby','Uložit pohyby do Excelu']));
mock.messages=[{role:'user',text:'za co utrácím nejvíc'},{role:'vega',text:'Výpis zpracován.',card:stc}];run("S.mc=null");run('renderStream()');
assert.equal(run("chips().length"),3,'otázka PŘED kartou návrh neskrývá');
console.log('36 UI logic scenarios passed.');
