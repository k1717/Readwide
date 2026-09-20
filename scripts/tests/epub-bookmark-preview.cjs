// Behavior of the actual Java-generated script with synthetic DOM/caret/range
// collaborators. This does not emulate Android rendering or validate a device.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
if (process.argv.length !== 4) {
  throw new Error('Usage: epub-bookmark-preview.cjs INSTALL_SCRIPT OUTPUT_JSON');
}
const source = fs.readFileSync(process.argv[2], 'utf8');

function capture({name, top = 0, inset = 0, ruby = false}) {
  const text = ruby ? '「春夏秋冬。次の文章です。' : '春夏秋冬。次の文章です。';
  const width = 400, height = 220, glyphHeight = 16;
  const glyph = (offset) => ({left:368,right:384,top:top+offset*glyphHeight,
    bottom:top+(offset+1)*glyphHeight,width:16,height:glyphHeight});
  let root;
  const rootRect = {left:368,right:384,top,bottom:top+text.length*glyphHeight,
    width:16,height:text.length*glyphHeight};
  root = {
    nodeType:1,id:'sentence-1',parentElement:null,textContent:text,innerText:text,
    closest(selector) { return /script|style|rt|rp/.test(selector) ? null : root; },
    contains(node) { return node === root || nodes.includes(node); },
    cloneNode() { return {textContent:text,innerText:text,querySelectorAll:()=>[]}; },
    getClientRects:()=>[rootRect],getBoundingClientRect:()=>rootRect,
    hasAttribute:()=>false,
  };
  const annotation = {closest(selector) { return /script|style|rt|rp/.test(selector) ? annotation : root; }};
  const nodes = ruby ? [
    {nodeType:3,nodeValue:text.slice(0,2),parentElement:root,base:0},
    {nodeType:3,nodeValue:'はる',parentElement:annotation,base:null},
    {nodeType:3,nodeValue:text.slice(2),parentElement:root,base:2},
  ] : [{nodeType:3,nodeValue:text,parentElement:root,base:0}];
  const baseNodes = nodes.filter(n=>n.base !== null);
  const nodeAt = (offset) => baseNodes.find(n=>offset>=n.base && offset<n.base+n.nodeValue.length) || baseNodes[baseNodes.length-1];
  const document = {
    body:{scrollWidth:width,scrollHeight:height},
    documentElement:{scrollWidth:width,scrollHeight:height,scrollLeft:0,scrollTop:0},
    querySelectorAll:()=>[root],elementFromPoint:()=>root,
    createTreeWalker(){let i=0;return {nextNode:()=>nodes[i++]||null};},
    caretRangeFromPoint(x,y) {
      const offset = Math.max(0,Math.min(text.length-1,Math.floor((y-top)/glyphHeight)));
      const node = nodeAt(offset);
      return {startContainer:node,startOffset:offset-node.base};
    },
    createRange(){let node,offset;return {
      setStart(n,o){node=n;offset=o;},setEnd(){},
      getClientRects:()=>[glyph(node.base+offset)],
    };},
  };
  const window = {innerWidth:width,innerHeight:height,
    visualViewport:{width,height,scale:1,offsetLeft:0,offsetTop:0},
    scrollX:0,scrollY:0,__rwDocAnchorTopInset:inset,__rwDocAnchorBottomInset:0,
    __rwDocForceVerticalWriting:true,__rwDocCaptureColumnStart:true,
    getComputedStyle:()=>({writingMode:'vertical-rl'})};
  const context = vm.createContext({window,document,NodeFilter:{SHOW_TEXT:4}});
  assert.equal(vm.runInContext(source, context, {timeout:1000}), true, 'script installation failed');
  // Run through the VM too so unexpected loops in the production helpers are bounded.
  const captured = vm.runInContext(`(function(){
    var selected=window.__rwDocVerticalProbe();
    var column=window.__rwDocVerticalColumnStart(selected);
    var withPreview=window.__rwDocAnchorAtTop();
    window.__rwDocCaptureColumnStart=false;
    var withoutPreview=window.__rwDocAnchorAtTop();
    return JSON.stringify({withPreview:withPreview,withoutPreview:withoutPreview,
      columnOffset:column?column.localOffset:null});
  })()`, context, {timeout:1000});
  return {text,...JSON.parse(captured)};
}

const cases = [
  {name:'exact visual top',top:0,saveOffset:1},
  {name:'one pixel inside',top:1,saveOffset:1},
  {name:'two pixels inside control',top:2,saveOffset:0},
  {name:'one pixel clipped above visual viewport',top:-1,saveOffset:1},
  {name:'toolbar top inset 40',top:8,inset:40,saveOffset:5},
  {name:'toolbar top inset 60',top:8,inset:60,saveOffset:4},
  {name:'quote split text nodes and ruby under toolbar',top:8,inset:40,ruby:true,saveOffset:5},
];
const results = cases.map(c=>{
  const result = {case:c.name,firstGlyphTop:c.top,usableTop:c.inset||0,passed:false};
  try {
    const actual = capture(c);
    result.columnOffset = actual.columnOffset;
    result.preview = actual.withPreview.columnStartText;
    result.saveOffset = actual.withPreview.charOffset;
    const withPreview = {...actual.withPreview}, withoutPreview = {...actual.withoutPreview};
    delete withPreview.columnStartText;delete withoutPreview.columnStartText;
    assert.deepEqual(withPreview,withoutPreview,'preview capture changed a restoration field');
    result.allNonPreviewAnchorFieldsUnchanged = true;
    assert.equal(actual.withPreview.anchorMode,'visible-sentence');
    assert.equal(actual.withPreview.charOffset,c.saveOffset,'save-probe offset changed');
    const expectedOffset = c.top < 0 ? 1 : 0;
    assert.equal(actual.columnOffset,expectedOffset,'wrong physical column starting glyph');
    assert.equal(actual.withPreview.columnStartText,
      actual.text.slice(expectedOffset,actual.text.indexOf('。')+1),
      'preview omitted column text or included ruby annotation');
    result.passed = true;
  } catch(error) {
    result.error = error.message;
  }
  console.log(`${result.passed?'PASS':'FAIL'} ${c.name}${result.error?': '+result.error:''}`);
  return result;
});
const passed = results.filter(r=>r.passed).length, failed = results.length-passed;
fs.writeFileSync(process.argv[3], JSON.stringify({
  boundary:'Actual Java-generated JavaScript in Node VM with synthetic DOM/range/caret collaborators. No Android/WebView/device rendering claim.',
  passed,failed,results},null,2)+'\n');
console.log(`${passed} passed; ${failed} failed; ${results.length} cases.`);
process.exitCode = failed ? 1 : 0;
