const assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const code=fs.readFileSync(require('node:path').join(__dirname, '../../vega/src/main/assets/ui/app.js'),'utf8');
const fields=new Map(),field=id=>{if(!fields.has(id))fields.set(id,{value:'',style:{},dataset:{},setAttribute(){},focus(){},classList:{add(){},toggle(){}}});return fields.get(id)};
const mock={home:{buttons:[]},send:{error:'chyba'},runTool:{error:'chyba'}};
const ctx=vm.createContext({document:{querySelector:field,querySelectorAll:()=>[],addEventListener(){}},matchMedia:()=>({matches:false}),innerHeight:850,window:{VEGA:new Proxy({},{get(_,k){return ()=>JSON.stringify(mock[k]??true)}})}});
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
console.log('9 UI logic scenarios passed.');
