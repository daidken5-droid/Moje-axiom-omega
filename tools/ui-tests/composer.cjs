const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const code=fs.readFileSync(require('node:path').join(__dirname, '../../vega/src/main/assets/ui/app.js'),'utf8');
const fields=new Map(),field=id=>{if(!fields.has(id))fields.set(id,{value:'',style:{},dataset:{},setAttribute(){},focus(){},classList:{add(){},toggle(){}}});return fields.get(id)};
const mock={home:{buttons:[]},send:{error:'chyba'},runTool:{error:'chyba'}};
const calls=[];
const ctx=vm.createContext({document:{querySelector:field,querySelectorAll:()=>[],addEventListener(){}},matchMedia:()=>({matches:false}),innerHeight:850,window:{VEGA:new Proxy({},{get(_,k){return (...a)=>{calls.push([k,...a]);return JSON.stringify(mock[k]??true)}}})}});
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
console.log('22 UI logic scenarios passed.');
