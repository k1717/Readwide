// Execute the actual installed/show JavaScript with explicit synthetic DOM ranges.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const out=process.argv[2],install=fs.readFileSync(path.join(out,'install.js'),'utf8');
const cases=vm.runInNewContext(fs.readFileSync(path.join(out,'cases.js'),'utf8'),{}, {timeout:1000});
const results=[];
for(const test of cases){
 try{
  const spans=[],ranges=[],scrolls=[];
  const parent={nodeName:'P',insertBefore(){},removeChild(span){spans.splice(spans.indexOf(span),1);},normalize(){}};
  // Split into multiple text nodes to exercise maps across inline/ruby boundaries.
  const middle=Math.floor(test.dom.length/2);
  const nodes=[{nodeValue:test.dom.slice(0,middle),base:0,parentNode:parent},
               {nodeValue:test.dom.slice(middle),base:middle,parentNode:parent}];
  const document={body:{},getElementsByClassName:()=>spans,
   createTreeWalker(root,show,filter){let i=0;return {nextNode(){while(i<nodes.length){const n=nodes[i++];if(filter.acceptNode(n)===1)return n;}return null;}};},
   createRange(){let n,s,end;return {setStart(node,offset){n=node;s=offset;},setEnd(node,offset){end=offset;},surroundContents(span){ranges.push({start:n.base+s,end:n.base+end});span.parentNode=parent;spans.push(span);}};},
   createElement:()=>({style:{setProperty(){}},firstChild:null,getBoundingClientRect:()=>test.rect,scrollIntoView:options=>scrolls.push(options)})};
  const window={innerWidth:800,innerHeight:600,visualViewport:{width:800,height:600,offsetLeft:test.offsetLeft,offsetTop:test.offsetTop}};
  const context=vm.createContext({window,document,NodeFilter:{SHOW_TEXT:4,FILTER_REJECT:2,FILTER_ACCEPT:1}});
  assert.equal(vm.runInContext(install,context,{timeout:1000}),true);
  vm.runInContext(test.js,context,{timeout:1000});
  const selected=ranges.length?ranges[0].start:-1;
  assert.equal(selected,test.expectedStart,'wrong sentence occurrence');
  assert.equal(scrolls.length>0,test.expectScroll,'wrong viewport follow behavior');
  // Replay should address precisely the same source range, without cursor drift.
  const previous=ranges.map(r=>({...r}));ranges.length=0;
  vm.runInContext(test.js,context,{timeout:1000});
  assert.deepEqual(ranges,previous,'page-load replay moved to a different occurrence');
  results.push({name:test.name,passed:true});
 }catch(e){results.push({name:test.name,passed:false,error:e.message});}
}
fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2)+'\n');
for(const r of results)console.log(`${r.passed?'PASS':'FAIL'} ${r.name}${r.error?': '+r.error:''}`);
const passed=results.filter(r=>r.passed).length,failed=results.length-passed;
console.log(`TOTAL: ${passed} passed; ${failed} failed`);process.exitCode=failed?1:0;
