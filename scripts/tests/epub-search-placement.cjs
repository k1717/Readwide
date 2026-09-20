// Actual generated production JS; explicit synthetic scrolling/DOM, not a browser.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const out=process.argv[2],results=[];
function test(name,fn){try{fn();results.push({name,passed:true});}catch(e){results.push({name,passed:false,error:e.message});}}
function page({mode='horizontal-tb',x=0,y=0,hitX=100,hitY=1500,panel=false,missing=false,oldSpacers=false}={}){
 const nodes={},queue=[],calls=[];
 const window={innerWidth:800,innerHeight:600,visualViewport:{height:600},scrollX:x,scrollY:y,
   getComputedStyle:()=>({writingMode:mode}),scrollTo(nx,ny){calls.push([nx,ny]);this.scrollX=nx;this.scrollY=Math.max(0,ny);}};
 const parent={firstChild:null,appendChild(el){nodes[el.id]=el;el.parentNode=this;},insertBefore(el){this.appendChild(el);},removeChild(el){delete nodes[el.id];},offsetHeight:600};
 const hit={id:'rw-document-search-current',getBoundingClientRect(){return {left:hitX-window.scrollX,right:hitX-window.scrollX+32,top:hitY-window.scrollY,bottom:hitY-window.scrollY+32,width:32,height:32};},
   scrollIntoView(options){assert.equal(options.block,'center');assert.equal(options.inline,'start');window.scrollX=hitX-384;window.scrollY=hitY;}};
 if(!missing)nodes[hit.id]=hit;
 if(oldSpacers)for(const id of ['rw-document-search-top-spacer','rw-document-search-bottom-spacer'])parent.appendChild({id});
 const document={body:parent,head:parent,documentElement:{clientHeight:600,scrollLeft:0,scrollTop:0},
   getElementById:id=>nodes[id]||null,createElement:()=>({setAttribute(){}})};
 const ctx=vm.createContext({window,document,setTimeout:fn=>queue.push(fn)});
 const value=vm.runInContext(fs.readFileSync(path.join(out,panel?'panel.js':'plain.js'),'utf8'),ctx,{timeout:1000});
 for(const fn of queue)fn();
 return {value,window,nodes,hit,calls};
}
for(const [name,opts] of [
 ['vertical-rl reveals leftward columns',{mode:'vertical-rl',hitX:-900}],
 ['vertical-lr reveals rightward columns',{mode:'vertical-lr',hitX:1500}],
 ['vertical-rl retains signed horizontal position',{mode:'vertical-rl',x:-800,hitX:-1400}],
])test(name,()=>{const p=page(opts);assert.equal(p.value,true);const r=p.hit.getBoundingClientRect();assert.ok(r.left>=0&&r.right<=800,'hit remains outside horizontal viewport');assert.ok(r.top>=0&&r.bottom<=600);assert.equal(p.nodes['rw-document-search-top-spacer'],undefined,'vertical layout got a horizontal spacer');});
test('horizontal reading retains existing X offset',()=>{const p=page({x:120,hitX:220});assert.equal(p.window.scrollX,120);assert.equal(p.hit.getBoundingClientRect().top,72);assert.ok(p.nodes['rw-document-search-bottom-spacer']);});
test('horizontal ordinary search placement stays below header',()=>{const p=page();assert.equal(p.value,true);assert.equal(p.hit.getBoundingClientRect().top,72);});
test('search panel physical pixels map to CSS pixels',()=>{const p=page({panel:true});const r=p.hit.getBoundingClientRect();assert.ok(r.top>=0&&r.bottom<=80,'hit hidden by panel at CSS Y=80');});
test('vertical search removes stale horizontal spacers',()=>{const p=page({mode:'vertical-lr',oldSpacers:true});assert.equal(p.nodes['rw-document-search-top-spacer'],undefined);assert.equal(p.nodes['rw-document-search-bottom-spacer'],undefined);});
test('missing selected hit does not move page',()=>{const p=page({missing:true,x:-200,y:100});assert.equal(p.value,false);assert.deepEqual(p.calls,[]);assert.equal(p.window.scrollX,-200);});

// This fixture checks stable root/candidate selection against temporary search
// wrappers. All text nodes remain in their source order before/after unwrapping.
function anchorFixture({stable=true,semantic=false}={}){
 let hit,paragraph;
 const full='春の文章です。春の続きです。';
 const clean=text=>({textContent:text,innerText:text,querySelectorAll:()=>[]});
 paragraph={id:stable?'publisher-sentence':'',nodeType:1,parentElement:null,textContent:full,innerText:full,
   classList:{contains:()=>false},hasAttribute:n=>semantic&&n==='epub:type',cloneNode:()=>clean(full),
   getBoundingClientRect:()=>({left:500,right:520,top:0,bottom:300,width:20,height:300}),
   closest(selector){if(/script|style|rt,rp/.test(selector))return null;if(selector==='span[id],p[id],li[id],blockquote[id]')return stable&&!semantic?this:null;return this;}};
 hit={id:'rw-document-search-current',nodeType:1,parentElement:paragraph,textContent:'春',innerText:'春',
   classList:{contains:n=>n==='rw-document-search-hit'},hasAttribute:()=>false,cloneNode:()=>clean('春'),
   getBoundingClientRect:()=>({left:500,right:520,top:0,bottom:20,width:20,height:20}),
   closest:selector=>/script|style|rt,rp/.test(selector)?null:(selector.startsWith('span[id]')?hit:paragraph)};
 const text={nodeType:3,nodeValue:'春',parentElement:hit};
 const offset=full.indexOf('春',1);
 const nodes=[{nodeType:3,nodeValue:full.slice(0,offset),parentElement:paragraph},text,
   {nodeType:3,nodeValue:full.slice(offset+1),parentElement:paragraph}];
 const document={querySelectorAll:selector=>selector==='[id]'?[hit,paragraph]:[hit,paragraph],body:{},documentElement:{},
   createTreeWalker(root){const list=root===hit?[text]:nodes;let i=0;return {nextNode:()=>list[i++]||null};}};
 const window={};const ctx=vm.createContext({window,document,NodeFilter:{SHOW_TEXT:4}});
 assert.equal(vm.runInContext(fs.readFileSync(path.join(out,'anchor.js'),'utf8'),ctx,{timeout:1000}),true);
 return {window,text,hit,paragraph,offset,unwrap(){text.parentElement=paragraph;}};
}
for(const [name,opts] of [
 ['publisher sentence id survives search highlight cleanup',{}],
 ['unidentified paragraph remains the anchor root',{stable:false}],
 ['semantic EPUB sentence survives search highlight cleanup',{semantic:true}],
])test(name,()=>{const f=anchorFixture(opts);const before=f.window.__rwDocStableSentenceRoot(f.text);assert.equal(before,f.paragraph,'temporary highlighted span became saved sentence');const offset=f.window.__rwDocOffsetIn(before,{node:f.text,offset:0});assert.equal(offset,f.offset,'wrapped text shifted the stored character offset');f.unwrap();assert.equal(f.window.__rwDocStableSentenceRoot(f.text),before);assert.equal(f.window.__rwDocOffsetIn(before,{node:f.text,offset:0}),offset,'removing search wrapper shifted character offset');});
test('temporary selected hit excluded from stable candidates',()=>{const f=anchorFixture();const values=f.window.__rwDocStableSentenceCandidates();assert.ok(!values.includes(f.hit));assert.ok(values.includes(f.paragraph));});
fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2)+'\n');
for(const r of results)console.log(`${r.passed?'PASS':'FAIL'} ${r.name}${r.error?': '+r.error:''}`);
const passed=results.filter(r=>r.passed).length,failed=results.length-passed;
console.log(`TOTAL: ${passed} passed; ${failed} failed`);process.exitCode=failed?1:0;
