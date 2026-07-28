package com.readwide.manager;

/**
 * DOM helpers used by the rendered document bookmark/state anchor path.
 *
 * <p>The horizontal path intentionally preserves the original v1 block/Y
 * behavior. Vertical-writing EPUB pages add a v2 visible-sentence anchor: the
 * first actually visible sentence in logical vertical-rl reading order is
 * identified by a stable element id when available, then by sentence text and
 * block position.</p>
 */
final class DocumentContentAnchorJavascript {
    private DocumentContentAnchorJavascript() {}

    static String installScript() {
        return """
                (function(){try{
                  window.__rwDocNorm=function(t){return String(t||'').replace(/\\s+/g,' ').trim();};
                  window.__rwDocViewport=function(){
                    var vv=window.visualViewport;
                    var width=vv&&Number(vv.width)>0?Number(vv.width):Math.max(0,Number(window.innerWidth)||0);
                    var height=vv&&Number(vv.height)>0?Number(vv.height):Math.max(0,Number(window.innerHeight)||0);
                    var offsetLeft=vv&&isFinite(Number(vv.offsetLeft))?Number(vv.offsetLeft):0;
                    var offsetTop=vv&&isFinite(Number(vv.offsetTop))?Number(vv.offsetTop):0;
                    var scale=vv&&Number(vv.scale)>0?Number(vv.scale):1;
                    // DOMRect and caret/elementFromPoint coordinates in Android
                    // WebView remain client coordinates rooted at (0, 0), even
                    // when visualViewport reports a transient layout offset.
                    return {left:0,top:0,right:width,bottom:height,
                            width:width,height:height,scale:scale,
                            offsetLeft:offsetLeft,offsetTop:offsetTop};
                  };
                  window.__rwDocUsableViewport=function(){
                    var viewport=window.__rwDocViewport();
                    var topInset=Math.max(0,Number(window.__rwDocAnchorTopInset)||0);
                    var bottomInset=Math.max(0,Number(window.__rwDocAnchorBottomInset)||0);
                    var top=Math.min(viewport.bottom,viewport.top+topInset);
                    var bottom=Math.max(top+2,viewport.bottom-bottomInset);
                    if(bottom>viewport.bottom)bottom=viewport.bottom;
                    return {left:viewport.left,top:top,right:viewport.right,bottom:bottom,
                            width:viewport.width,height:Math.max(0,bottom-top),
                            visual:viewport,topInset:topInset,bottomInset:bottomInset};
                  };
                  window.__rwDocSameVisualGeometry=function(anchor){
                    anchor=anchor||{};
                    if(String(anchor.viewportBasis||'')!=='visual-v1')return false;
                    var viewport=window.__rwDocViewport();
                    var usable=window.__rwDocUsableViewport();
                    var close=function(a,b,t){return isFinite(Number(a))&&Math.abs(Number(a)-Number(b))<=t;};
                    return close(anchor.viewportWidth,viewport.width,1)&&
                      close(anchor.viewportHeight,viewport.height,1)&&
                      close(anchor.viewportScale,viewport.scale,0.01)&&
                      close(anchor.layoutWidth,window.innerWidth,1)&&
                      close(anchor.layoutHeight,window.innerHeight,1)&&
                      close(anchor.anchorTopInset,usable.topInset,1)&&
                      close(anchor.anchorBottomInset,usable.bottomInset,1);
                  };
                  window.__rwDocLegacyLayoutMatches=function(anchor){
                    anchor=anchor||{};
                    if(String(anchor.viewportBasis||'')==='visual-v1')return false;
                    var width=Number(anchor.viewportWidth)||0,height=Number(anchor.viewportHeight)||0;
                    return width>0&&height>0&&Math.abs(width-window.innerWidth)<=1&&
                      Math.abs(height-window.innerHeight)<=1;
                  };
                  window.__rwDocScrollExtent=function(){
                    var viewport=window.__rwDocViewport();
                    return {
                      maxX:Math.max(0,Math.max(document.documentElement.scrollWidth,
                        document.body?document.body.scrollWidth:0)-viewport.width),
                      maxY:Math.max(0,Math.max(document.documentElement.scrollHeight,
                        document.body?document.body.scrollHeight:0)-viewport.height)
                    };
                  };
                  window.__rwDocCleanText=function(el){
                    if(!el)return '';
                    var c=el.cloneNode(true);
                    var drop=c.querySelectorAll('script,style,rt,rp');
                    for(var i=0;i<drop.length;i++){if(drop[i].parentNode)drop[i].parentNode.removeChild(drop[i]);}
                    return window.__rwDocNorm(c.innerText||c.textContent||'');
                  };
                  window.__rwDocBlocks=function(){
                    var raw=Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,th,table'));
                    return raw.filter(function(e){
                      var t=window.__rwDocCleanText(e),r=e.getBoundingClientRect();
                      return t.length>0&&r.height>0&&r.width>0;
                    });
                  };
                  window.__rwDocWritingMode=function(){
                    if(window.__rwDocForceVerticalWriting===true)return 'vertical-rl';
                    var candidates=[];
                    var viewport=window.__rwDocViewport();
                    var probe=document.elementFromPoint(Math.max(1,viewport.width-12),Math.min(Math.max(12,viewport.height*0.18),Math.max(12,viewport.height-2)));
                    if(probe)candidates.push(probe);
                    if(document.body)candidates.push(document.body);
                    if(document.documentElement)candidates.push(document.documentElement);
                    var blocks=window.__rwDocBlocks();
                    for(var i=0;i<Math.min(8,blocks.length);i++)candidates.push(blocks[i]);
                    for(var j=0;j<candidates.length;j++){
                      try{
                        var wm=String(window.getComputedStyle(candidates[j]).writingMode||'').toLowerCase();
                        if(wm.indexOf('vertical-')===0)return wm;
                      }catch(ignore){}
                    }
                    return 'horizontal-tb';
                  };
                  window.__rwDocVisibleRect=function(el,vertical,relaxed){
                    if(!el)return null;
                    var rects=el.getClientRects(),best=null;
                    var viewport=window.__rwDocUsableViewport();
                    for(var i=0;i<rects.length;i++){
                      var r=rects[i];
                      var l=Math.max(viewport.left,r.left),t=Math.max(viewport.top,r.top);
                      var rr=Math.min(viewport.right,r.right),bb=Math.min(viewport.bottom,r.bottom);
                      var vw=rr-l,vh=bb-t;
                      if(relaxed){if(vw<0.5||vh<0.5)continue;}
                      else{
                        var needW=Math.min(6,Math.max(2,r.width*0.25));
                        var needH=Math.min(18,Math.max(4,r.height*0.12));
                        if(vw<needW||vh<needH)continue;
                      }
                      var candidate={left:r.left,top:r.top,right:r.right,bottom:r.bottom,
                                     visibleLeft:l,visibleTop:t,visibleRight:rr,visibleBottom:bb,
                                     visibleWidth:vw,visibleHeight:vh};
                      if(!best){best=candidate;continue;}
                      if(vertical){
                        if(candidate.visibleRight>best.visibleRight+1||
                           (Math.abs(candidate.visibleRight-best.visibleRight)<=1&&candidate.visibleTop<best.visibleTop))best=candidate;
                      }else if(candidate.visibleTop<best.visibleTop-1||
                                (Math.abs(candidate.visibleTop-best.visibleTop)<=1&&candidate.visibleLeft<best.visibleLeft))best=candidate;
                    }
                    return best;
                  };
                  window.__rwDocStableSentenceCandidates=function(){
                    var raw=Array.prototype.slice.call(document.querySelectorAll('span[id],p[id],li[id],blockquote[id]'));
                    var semantic=Array.prototype.slice.call(document.querySelectorAll('[id]'));
                    for(var si=0;si<semantic.length;si++){
                      var se=semantic[si];
                      if(se.hasAttribute&&se.hasAttribute('epub:type')&&raw.indexOf(se)<0)raw.push(se);
                    }
                    return raw.filter(function(el){
                      if(el.closest&&el.closest('script,style,rt,rp'))return false;
                      return window.__rwDocCleanText(el).length>0;
                    });
                  };
                  window.__rwDocStableSentenceRoot=function(node){
                    var el=node&&node.nodeType===3?node.parentElement:node;
                    if(!el||!el.closest)return null;
                    var root=el.closest('span[id],p[id],li[id],blockquote[id]');
                    if(!root){
                      for(var parent=el;parent;parent=parent.parentElement){
                        if(parent.id&&parent.hasAttribute&&parent.hasAttribute('epub:type')){root=parent;break;}
                      }
                    }
                    if(!root)root=el.closest('p,li,blockquote,h1,h2,h3,h4,h5,h6,pre,td,th');
                    if(!root||root.closest('script,style,rt,rp'))return null;
                    return window.__rwDocCleanText(root).length>0?root:null;
                  };
                  window.__rwDocBestVisible=function(elements,vertical,relaxed){
                    var best=null;
                    for(var i=0;i<elements.length;i++){
                      var rect=window.__rwDocVisibleRect(elements[i],vertical,relaxed);
                      if(!rect)continue;
                      var candidate={el:elements[i],rect:rect,textLength:window.__rwDocCleanText(elements[i]).length};
                      if(!best){best=candidate;continue;}
                      if(vertical){
                        if(rect.visibleRight>best.rect.visibleRight+1||
                           (Math.abs(rect.visibleRight-best.rect.visibleRight)<=1&&
                            (rect.visibleTop<best.rect.visibleTop-1||
                             (Math.abs(rect.visibleTop-best.rect.visibleTop)<=1&&candidate.textLength<best.textLength))))best=candidate;
                      }else if(rect.visibleTop<best.rect.visibleTop-1||
                                (Math.abs(rect.visibleTop-best.rect.visibleTop)<=1&&
                                 (rect.visibleLeft<best.rect.visibleLeft-1||
                                  (Math.abs(rect.visibleLeft-best.rect.visibleLeft)<=1&&candidate.textLength<best.textLength))))best=candidate;
                    }
                    return best;
                  };
                  window.__rwDocTextMap=function(root){
                    var text='',nodes=[];
                    if(!root)return {text:text,nodes:nodes};
                    var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null);
                    var n;
                    while((n=walker.nextNode())){
                      var p=n.parentElement;
                      if(p&&p.closest&&p.closest('script,style,rt,rp'))continue;
                      nodes.push({node:n,start:text.length});
                      text+=String(n.nodeValue||'');
                    }
                    return {text:text,nodes:nodes};
                  };
                  window.__rwDocCaretForRect=function(rect){
                    if(!rect)return null;
                    var viewport=window.__rwDocUsableViewport();
                    var x=Math.max(viewport.left+1,Math.min(viewport.right-2,rect.visibleRight-2));
                    var y=Math.max(viewport.top+1,Math.min(viewport.bottom-1,
                      rect.visibleTop+Math.min(8,Math.max(1,rect.visibleHeight/2))));
                    return window.__rwDocCaretAtPoint(
                      x-viewport.visual.left,y-viewport.visual.top);
                  };
                  window.__rwDocCaretAtPoint=function(x,y){
                    try{
                      if(document.caretRangeFromPoint){var r=document.caretRangeFromPoint(x,y);if(r)return {node:r.startContainer,offset:r.startOffset};}
                      if(document.caretPositionFromPoint){var p=document.caretPositionFromPoint(x,y);if(p)return {node:p.offsetNode,offset:p.offset};}
                    }catch(ignore){}
                    return null;
                  };
                  window.__rwDocGlyphFullyInside=function(rect,viewport){
                    if(!rect||!viewport)return false;
                    // Reject clipped edge columns when choosing a SAVE anchor.
                    // The usable viewport already excludes app chrome; this
                    // small extra guard keeps anti-aliased edge glyphs from
                    // winning merely because vertical-rl orders them farthest
                    // to the right.
                    var guard=Math.min(2,Math.max(0,
                      Math.min(viewport.width,viewport.height)/8));
                    return rect.left>=viewport.left+guard&&
                      rect.right<=viewport.right-guard&&
                      rect.top>=viewport.top+guard&&
                      rect.bottom<=viewport.bottom-guard;
                  };
                  window.__rwDocVerticalProbe=function(){
                    var viewport=window.__rwDocUsableViewport();
                    var usableHeight=Math.max(2,viewport.height);
                    var edgeInset=Math.min(48,Math.max(24,viewport.width*0.06));
                    var xs=[viewport.width-edgeInset,viewport.width*0.82,viewport.width*0.62,
                            viewport.width*0.42,viewport.width*0.22,edgeInset];
                    var usableTop=viewport.top-viewport.visual.top;
                    var usableBottom=viewport.bottom-viewport.visual.top;
                    var ys=[usableTop+Math.min(18,usableHeight*0.08),usableTop+usableHeight*0.28,
                            usableTop+usableHeight*0.50,usableTop+usableHeight*0.72,
                            usableBottom-Math.min(18,usableHeight*0.08)];
                    var best=null;
                    for(var xi=0;xi<xs.length;xi++)for(var yi=0;yi<ys.length;yi++){
                      var x=Math.max(1,Math.min(viewport.width-2,xs[xi]));
                      var y=Math.max(usableTop+1,Math.min(usableBottom-1,ys[yi]));
                      var caret=window.__rwDocCaretAtPoint(x,y);
                      var root=window.__rwDocStableSentenceRoot(caret&&caret.node);
                      if(!root)continue;
                      var rect=window.__rwDocVisibleRect(root,true,true);if(!rect)continue;
                      var glyph=window.__rwDocGlyphForCaret(root,caret,
                        x+viewport.visual.left,y+viewport.visual.top);if(!glyph)continue;
                      if(!window.__rwDocGlyphFullyInside(glyph.rect,viewport))continue;
                      var candidate={el:root,rect:rect,caret:caret,probeX:x,probeY:y,
                                     localOffset:glyph.offset,glyphRect:glyph.rect,
                                     textLength:window.__rwDocCleanText(root).length};
                      if(!best||glyph.rect.right>best.glyphRect.right+1||
                         (Math.abs(glyph.rect.right-best.glyphRect.right)<=1&&
                          (glyph.rect.top<best.glyphRect.top-1||
                           (Math.abs(glyph.rect.top-best.glyphRect.top)<=1&&candidate.textLength<best.textLength))))best=candidate;
                    }
                    return best;
                  };
                  window.__rwDocOffsetIn=function(root,caret){
                    if(!root||!caret)return -1;
                    var caretParent=caret.node&&caret.node.nodeType===3?caret.node.parentElement:caret.node;
                    if(caretParent&&caretParent.closest&&caretParent.closest('rt,rp'))return -1;
                    var map=window.__rwDocTextMap(root);
                    for(var i=0;i<map.nodes.length;i++){
                      if(map.nodes[i].node===caret.node)return Math.max(0,map.nodes[i].start+(parseInt(caret.offset,10)||0));
                    }
                    try{
                      if(root.contains(caret.node)){
                        var prefix=document.createRange();
                        prefix.selectNodeContents(root);
                        prefix.setEnd(caret.node,Math.max(0,parseInt(caret.offset,10)||0));
                        var fragment=prefix.cloneContents();
                        var ignored=fragment.querySelectorAll?fragment.querySelectorAll('script,style,rt,rp'):[];
                        for(var j=0;j<ignored.length;j++)if(ignored[j].parentNode)ignored[j].parentNode.removeChild(ignored[j]);
                        return Math.max(0,String(fragment.textContent||'').length);
                      }
                    }catch(ignore){}
                    return -1;
                  };
                  window.__rwDocRangeAtOffset=function(root,offset){
                    if(!root)return null;
                    var map=window.__rwDocTextMap(root);
                    offset=Math.max(0,Math.min(map.text.length,parseInt(offset,10)||0));
                    var lastNode=null,lastLength=0;
                    for(var i=0;i<map.nodes.length;i++){
                      var start=map.nodes[i].start;
                      var length=String(map.nodes[i].node.nodeValue||'').length;
                      if(length<=0)continue;
                      lastNode=map.nodes[i].node;lastLength=length;
                      if(offset<start+length){
                        var local=Math.max(0,Math.min(length-1,offset-start));
                        var range=document.createRange();
                        range.setStart(map.nodes[i].node,local);
                        range.setEnd(map.nodes[i].node,local+1);
                        return range;
                      }
                    }
                    if(lastNode&&lastLength>0){
                      var tail=document.createRange();
                      tail.setStart(lastNode,lastLength-1);tail.setEnd(lastNode,lastLength);
                      return tail;
                    }
                    return null;
                  };
                  window.__rwDocRangeRect=function(range){
                    if(!range||!range.getClientRects)return null;
                    var rects=range.getClientRects(),best=null;
                    for(var i=0;i<rects.length;i++){
                      var r=rects[i];
                      if(!r||r.width<0.5||r.height<0.5)continue;
                      if(!best||r.right>best.right+1||
                         (Math.abs(r.right-best.right)<=1&&r.top<best.top))best=r;
                    }
                    return best;
                  };
                  window.__rwDocGlyphForCaret=function(root,caret,probeX,probeY){
                    if(!root||!caret||!caret.node||!root.contains(caret.node))return null;
                    var caretParent=caret.node.nodeType===3?caret.node.parentElement:caret.node;
                    if(caretParent&&caretParent.closest&&caretParent.closest('rt,rp'))return null;
                    var insertion=window.__rwDocOffsetIn(root,caret);if(insertion<0)return null;
                    var map=window.__rwDocTextMap(root),offsets=[];
                    if(insertion>0)offsets.push(insertion-1);
                    offsets.push(insertion);
                    if(insertion+1<map.text.length)offsets.push(insertion+1);
                    var best=null,seen={};
                    for(var oi=0;oi<offsets.length;oi++){
                      var off=Math.max(0,Math.min(Math.max(0,map.text.length-1),offsets[oi]));
                      if(seen[off])continue;seen[off]=true;
                      var range=window.__rwDocRangeAtOffset(root,off);
                      var rect=window.__rwDocRangeRect(range);if(!rect)continue;
                      var cx=rect.left+rect.width/2,cy=rect.top+rect.height/2;
                      var dx=Math.abs(cx-probeX),dy=Math.abs(cy-probeY);
                      var score=dx*dx+dy*dy;
                      if(!best||score<best.score)best={offset:off,rect:rect,score:score,dx:dx,dy:dy};
                    }
                    if(!best)return null;
                    // A caret API may snap to a nearby column. Accept normal
                    // insertion-edge ambiguity, but not a glyph more than
                    // roughly three quarters of its own cell from the probe.
                    var maxDx=Math.max(6,best.rect.width*0.75);
                    var maxDy=Math.max(6,best.rect.height*0.75);
                    return best.dx<=maxDx&&best.dy<=maxDy?best:null;
                  };
                  window.__rwDocVerticalColumnStart=function(selected){
                    if(!selected||!selected.glyphRect)return null;
                    var viewport=window.__rwDocUsableViewport();
                    var targetX=selected.glyphRect.left+selected.glyphRect.width/2;
                    var targetWidth=Math.max(1,selected.glyphRect.width);
                    var startY=viewport.top+2,endY=viewport.bottom-2;
                    var step=Math.max(5,Math.min(10,
                      Math.max(1,selected.glyphRect.height)*0.5));
                    var best=null;
                    var consider=function(clientY){
                      var caret=window.__rwDocCaretAtPoint(
                        targetX-viewport.visual.left,
                        clientY-viewport.visual.top);
                      var root=window.__rwDocStableSentenceRoot(caret&&caret.node);
                      if(!root)return;
                      var glyph=window.__rwDocGlyphForCaret(root,caret,targetX,clientY);
                      if(!glyph||!window.__rwDocGlyphFullyInside(glyph.rect,viewport))return;
                      var centerX=glyph.rect.left+glyph.rect.width/2;
                      // Allow normal punctuation/ruby cell-width variation, but
                      // stay well below one full glyph advance so an adjacent
                      // vertical column cannot supply the bookmark label.
                      var sameColumnTolerance=Math.max(3,
                        Math.max(targetWidth,Math.max(1,glyph.rect.width))*0.65);
                      if(Math.abs(centerX-targetX)>sameColumnTolerance)return;
                      var candidate={el:root,localOffset:glyph.offset,glyphRect:glyph.rect};
                      if(!best||glyph.rect.top<best.glyphRect.top-1||
                         (Math.abs(glyph.rect.top-best.glyphRect.top)<=1&&
                          glyph.rect.right>best.glyphRect.right))best=candidate;
                    };
                    // Walk down the selected physical column instead of relying
                    // on a handful of fixed Y probes. Blank space, ruby, and
                    // sentence boundaries can otherwise make a middle glyph
                    // become the bookmark label even though the column begins
                    // near the top of the visible reading area.
                    for(var y=startY;y<=endY;y+=step)consider(y);
                    consider(selected.glyphRect.top+selected.glyphRect.height/2);
                    return best;
                  };
                  window.__rwDocSentenceAt=function(raw,offset){
                    raw=String(raw||'');offset=Math.max(0,Math.min(raw.length,parseInt(offset,10)||0));
                    var boundary=/[\\u3002\\uFF01\\uFF1F!?\\uFF0E.\\n\\r]/,start=0,end=raw.length;
                    for(var i=offset-1;i>=0;i--){if(boundary.test(raw.charAt(i))){start=i+1;break;}}
                    for(var j=offset;j<raw.length;j++){if(boundary.test(raw.charAt(j))){end=j+1;break;}}
                    var sentence=window.__rwDocNorm(raw.substring(start,end));
                    if(!sentence)sentence=window.__rwDocNorm(raw);
                    // The bookmark row should begin at the captured glyph, not
                    // up to one vertical column before it. The surrounding
                    // before/after fields already carry matching context.
                    var focusStart=Math.max(start,offset),focusEnd=Math.min(end,offset+72);
                    var focus=window.__rwDocNorm(raw.substring(focusStart,focusEnd));
                    if(sentence.length>180)sentence=sentence.substring(0,180);
                    return {text:sentence,sentenceOffset:Math.max(0,offset-start),
                            focusText:focus,
                            before:window.__rwDocNorm(raw.substring(Math.max(0,start-48),start)),
                            after:window.__rwDocNorm(raw.substring(end,Math.min(raw.length,end+48)))};
                  };
                  window.__rwDocAnchorAtTop=function(){
                    var blocks=window.__rwDocBlocks();
                    var viewport=window.__rwDocViewport();
                    var usableViewport=window.__rwDocUsableViewport();
                    var extent=window.__rwDocScrollExtent();
                    var maxY=extent.maxY,maxX=extent.maxX;
                    var writingMode=window.__rwDocWritingMode();
                    var vertical=writingMode.indexOf('vertical-')===0;
                    if(vertical){
                      var probe=window.__rwDocVerticalProbe();
                      var best=probe;
                      if(best){
                        var el=best.el;
                        var block=el.closest?el.closest('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,th,table'):null;
                        if(!block)block=el;
                        var blockIndex=blocks.indexOf(block);if(blockIndex<0)blockIndex=0;
                        var localOffset=Math.max(0,best.localOffset);
                        var map=window.__rwDocTextMap(el);
                        var sentence=window.__rwDocSentenceAt(map.text,localOffset);
                        var columnStart=window.__rwDocCaptureColumnStart===true?
                          window.__rwDocVerticalColumnStart(best):null;
                        var columnStartText='';
                        if(columnStart){
                          var columnMap=window.__rwDocTextMap(columnStart.el);
                          var columnSentence=window.__rwDocSentenceAt(
                            columnMap.text,Math.max(0,columnStart.localOffset));
                          columnStartText=columnSentence.focusText||columnSentence.text||'';
                        }
                        var focusX=best.glyphRect.left+best.glyphRect.width/2;
                        var focusY=best.glyphRect.top+best.glyphRect.height/2;
                        return {anchorMode:'visible-sentence',writingMode:writingMode,
                                elementId:el.id||'',blockIndex:blockIndex,charOffset:localOffset,
                                caretMatched:true,
                                sentenceOffset:sentence.sentenceOffset,text:sentence.text,
                                focusText:sentence.focusText,
                                columnStartText:columnStartText,
                                textBefore:sentence.before,textAfter:sentence.after,
                                focusRatioX:viewport.width>0?Math.max(0,Math.min(1,
                                  (focusX-viewport.left)/viewport.width)):0.5,
                                focusRatioY:usableViewport.height>0?
                                  Math.max(0,Math.min(1,(focusY-usableViewport.top)/usableViewport.height)):0.25,
                                scrollX:(window.scrollX||((document.scrollingElement||document.documentElement).scrollLeft)||0),
                                scrollY:(window.scrollY||((document.scrollingElement||document.documentElement).scrollTop)||0),
                                viewportBasis:'visual-v1',
                                viewportWidth:viewport.width||0,viewportHeight:viewport.height||0,
                                viewportScale:viewport.scale||1,
                                layoutWidth:window.innerWidth||0,layoutHeight:window.innerHeight||0,
                                anchorTopInset:usableViewport.topInset||0,
                                anchorBottomInset:usableViewport.bottomInset||0,
                                maxScrollX:maxX,maxScrollY:maxY};
                      }
                      return {anchorMode:'vertical-position',writingMode:writingMode,blockIndex:0,
                              scrollX:(window.scrollX||((document.scrollingElement||document.documentElement).scrollLeft)||0),
                              scrollY:(window.scrollY||((document.scrollingElement||document.documentElement).scrollTop)||0),
                              viewportBasis:'visual-v1',
                              viewportWidth:viewport.width||0,viewportHeight:viewport.height||0,
                              viewportScale:viewport.scale||1,
                              layoutWidth:window.innerWidth||0,layoutHeight:window.innerHeight||0,
                              anchorTopInset:usableViewport.topInset||0,
                              anchorBottomInset:usableViewport.bottomInset||0,
                              maxScrollX:maxX,maxScrollY:maxY,text:''};
                    }
                    if(!blocks.length)return {anchorMode:'block-top',writingMode:writingMode,blockIndex:0,
                      scrollX:window.scrollX||0,scrollY:window.scrollY||0,maxScrollX:maxX,maxScrollY:maxY,text:''};
                    var bestBlock=blocks[0],bestIndex=0,threshold=8;
                    for(var k=0;k<blocks.length;k++){
                      var br=blocks[k].getBoundingClientRect();
                      if(br.bottom>=threshold){bestBlock=blocks[k];bestIndex=k;break;}
                      if(br.top<=threshold){bestBlock=blocks[k];bestIndex=k;}
                    }
                    var text=window.__rwDocCleanText(bestBlock);if(text.length>180)text=text.substring(0,180);
                    return {anchorMode:'block-top',writingMode:writingMode,blockIndex:bestIndex,
                            scrollX:window.scrollX||0,scrollY:window.scrollY||0,
                            maxScrollX:maxX,maxScrollY:maxY,text:text};
                  };
                  window.__rwDocFindVerticalTarget=function(anchor){
                    anchor=anchor||{};
                    var id=String(anchor.elementId||'');
                    var text=window.__rwDocNorm(anchor.text||anchor.sentenceText||'');
                    var needle=text.length>80?text.substring(0,80):text;
                    if(id){
                      var byId=document.getElementById(id);
                      if(byId&&(!needle||window.__rwDocCleanText(byId).indexOf(needle)>=0))return byId;
                    }
                    var blocks=window.__rwDocBlocks();
                    var expectedBlock=parseInt(anchor.blockIndex,10);
                    if(!isFinite(expectedBlock))expectedBlock=-1;
                    if(text.length&&id){
                      var candidates=window.__rwDocStableSentenceCandidates();
                      var before=window.__rwDocNorm(anchor.textBefore||'');
                      var after=window.__rwDocNorm(anchor.textAfter||'');
                      var bestCandidate=null,bestCandidateScore=-1000000;
                      for(var i=0;i<candidates.length;i++){
                        var candidate=candidates[i],candidateText=window.__rwDocCleanText(candidate);
                        if(candidateText.indexOf(needle)<0)continue;
                        var block=candidate.closest?candidate.closest('h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,td,th,table'):null;
                        if(!block)block=candidate;
                        var blockIndex=blocks.indexOf(block),blockText=window.__rwDocCleanText(block);
                        var at=blockText.indexOf(needle),score=0;
                        if(expectedBlock>=0&&blockIndex>=0){
                          score-=Math.min(1000,Math.abs(blockIndex-expectedBlock))*4;
                          if(blockIndex===expectedBlock)score+=20;
                        }
                        if(before&&at>=0&&blockText.substring(0,at).indexOf(before.substring(Math.max(0,before.length-24)))>=0)score+=6;
                        if(after&&at>=0&&blockText.substring(at+needle.length).indexOf(after.substring(0,24))>=0)score+=6;
                        if(score>bestCandidateScore){bestCandidateScore=score;bestCandidate=candidate;}
                      }
                      if(bestCandidate)return bestCandidate;
                    }
                    if(text.length){
                      var blockNeedle=text.length>64?text.substring(0,64):text;
                      var before=window.__rwDocNorm(anchor.textBefore||'');
                      var after=window.__rwDocNorm(anchor.textAfter||'');
                      var firstMatch=null,bestMatch=null,bestScore=-1000000;
                      for(var j=0;j<blocks.length;j++){
                        var blockText=window.__rwDocCleanText(blocks[j]);
                        var at=blockText.indexOf(blockNeedle);if(at<0)continue;
                        if(!firstMatch)firstMatch=blocks[j];
                        var score=expectedBlock>=0?-Math.abs(j-expectedBlock)*4:0;
                        if(j===expectedBlock)score+=20;
                        if(before&&blockText.substring(0,at).indexOf(before.substring(Math.max(0,before.length-24)))>=0)score++;
                        if(after&&blockText.substring(at+blockNeedle.length).indexOf(after.substring(0,24))>=0)score++;
                        if(score>bestScore){bestScore=score;bestMatch=blocks[j];}
                      }
                      if(bestMatch||firstMatch)return bestMatch||firstMatch;
                    }
                    if(blocks.length){var idx=parseInt(anchor.blockIndex||0,10)||0;return blocks[Math.max(0,Math.min(blocks.length-1,idx))];}
                    return null;
                  };
                  window.__rwDocVerticalAnchorIsVisible=function(anchor){
                    anchor=anchor||{};
                    if(String(anchor.viewportBasis||'')!=='visual-v1'){
                      if(typeof anchor.scrollX!=='number')return false;
                      var currentX=window.scrollX||((document.scrollingElement||document.documentElement).scrollLeft)||0;
                      var currentY=window.scrollY||((document.scrollingElement||document.documentElement).scrollTop)||0;
                      return Math.abs(currentX-anchor.scrollX)<=1&&
                        Math.abs(currentY-(Number(anchor.scrollY)||0))<=1;
                    }
                    var viewport=window.__rwDocUsableViewport();
                    var target=window.__rwDocFindVerticalTarget(anchor);
                    if(!target)return false;
                    var expectedId=String(anchor.elementId||'');
                    if(expectedId&&String(target.id||'')!==expectedId)return false;
                    var expectedText=window.__rwDocNorm(anchor.text||anchor.sentenceText||'');
                    if(expectedText){
                      var targetText=window.__rwDocCleanText(target);
                      var needle=expectedText.length>80?expectedText.substring(0,80):expectedText;
                      if(targetText.indexOf(needle)<0)return false;
                    }
                    var range=window.__rwDocRangeAtOffset(target,anchor.charOffset||0);
                    var glyph=window.__rwDocRangeRect(range);if(!glyph)return false;
                    var cx=glyph.left+glyph.width/2,cy=glyph.top+glyph.height/2;
                    var edgeTolerance=0.5;
                    if(glyph.left < viewport.left-edgeTolerance||glyph.right > viewport.right+edgeTolerance||
                       glyph.top < viewport.top-edgeTolerance||glyph.bottom > viewport.bottom+edgeTolerance)return false;
                    if(typeof anchor.focusRatioX==='number'&&typeof anchor.focusRatioY==='number'){
                      var desiredX=viewport.left+viewport.width*Math.max(0,Math.min(1,anchor.focusRatioX));
                      var desiredY=viewport.top+viewport.height*
                        Math.max(0,Math.min(1,anchor.focusRatioY));
                      var toleranceX=Math.max(2,glyph.width*0.55);
                      var toleranceY=Math.max(2,glyph.height*0.55);
                      if(Math.abs(cx-desiredX)>toleranceX||Math.abs(cy-desiredY)>toleranceY)return false;
                    }
                    return true;
                  };
                  window.__rwDocScrollToAnchor=function(anchor,forceSemantic){
                    anchor=anchor||{};
                    var viewport=window.__rwDocViewport();
                    var usableViewport=window.__rwDocUsableViewport();
                    var vertical=String(anchor.writingMode||'').indexOf('vertical-')===0||anchor.anchorMode==='visible-sentence';
                    if(vertical){
                      if(anchor.anchorMode==='vertical-position'&&typeof anchor.scrollX==='number'){
                        var samePositionViewport=window.__rwDocSameVisualGeometry(anchor)||
                          window.__rwDocLegacyLayoutMatches(anchor);
                        if(samePositionViewport){
                          window.scrollTo(anchor.scrollX,typeof anchor.scrollY==='number'?anchor.scrollY:0);
                          return true;
                        }
                        var currentExtent=window.__rwDocScrollExtent();
                        var currentMaxX=currentExtent.maxX;
                        var currentMaxY=currentExtent.maxY;
                        var ratioX=Number(anchor.scrollRatioX);
                        if(!isFinite(ratioX)){
                          var savedMaxX=Number(anchor.maxScrollX)||0;
                          if(savedMaxX>0)ratioX=Math.abs(Number(anchor.scrollX)||0)/savedMaxX;
                        }
                        var ratioY=Number(anchor.scrollRatio);
                        if(!isFinite(ratioY)){
                          var savedMaxY=Number(anchor.maxScrollY)||0;
                          if(savedMaxY>0)ratioY=Math.max(0,Number(anchor.scrollY)||0)/savedMaxY;
                        }
                        if(isFinite(ratioX)){
                          ratioX=Math.max(0,Math.min(1,ratioX));
                          ratioY=isFinite(ratioY)?Math.max(0,Math.min(1,ratioY)):0;
                          var direction=Number(anchor.scrollX)<0?-1:1;
                          window.scrollTo(direction*Math.round(currentMaxX*ratioX),Math.round(currentMaxY*ratioY));
                          return true;
                        }
                        return false;
                      }
                      if(!forceSemantic&&String(anchor.viewportBasis||'')!=='visual-v1'&&
                         window.__rwDocLegacyLayoutMatches(anchor)&&typeof anchor.scrollX==='number'){
                        window.scrollTo(anchor.scrollX,typeof anchor.scrollY==='number'?anchor.scrollY:0);
                        return true;
                      }
                      var target=window.__rwDocFindVerticalTarget(anchor);
                      if(target){
                        var sameViewport=!forceSemantic&&window.__rwDocSameVisualGeometry(anchor);
                        if(sameViewport&&typeof anchor.scrollX==='number'){
                          window.scrollTo(anchor.scrollX,typeof anchor.scrollY==='number'?anchor.scrollY:0);
                          return true;
                        }
                        try{target.scrollIntoView({block:'start',inline:'start',behavior:'auto'});}catch(e){target.scrollIntoView(true);}
                        var focusRange=window.__rwDocRangeAtOffset(target,anchor.charOffset||0);
                        var focusRect=window.__rwDocRangeRect(focusRange);
                        if(focusRect&&typeof anchor.focusRatioX==='number'&&typeof anchor.focusRatioY==='number'){
                          var desiredX=Math.max(usableViewport.left+1,Math.min(usableViewport.right-2,
                            usableViewport.left+usableViewport.width*
                              Math.max(0,Math.min(1,anchor.focusRatioX))));
                          var desiredY=usableViewport.top+usableViewport.height*
                            Math.max(0,Math.min(1,anchor.focusRatioY));
                          var focusCenterX=focusRect.left+Math.max(0,focusRect.width)/2;
                          var focusCenterY=focusRect.top+Math.max(0,focusRect.height)/2;
                          window.scrollBy(focusCenterX-desiredX,focusCenterY-desiredY);
                        }else{
                          var rect=window.__rwDocVisibleRect(target,true,true);
                          if(rect)window.scrollBy(rect.right-(usableViewport.right-10),0);
                          if(typeof anchor.scrollY==='number')window.scrollTo(window.scrollX,anchor.scrollY);
                        }
                        return true;
                      }
                      if(typeof anchor.scrollX==='number'){
                        window.scrollTo(anchor.scrollX,typeof anchor.scrollY==='number'?anchor.scrollY:0);return true;
                      }
                    }
                    var blocks=window.__rwDocBlocks();
                    if(!blocks.length){if(typeof anchor.scrollY==='number')window.scrollTo(0,anchor.scrollY);return false;}
                    var oldText=window.__rwDocNorm(anchor.text||'');
                    var oldIdx=parseInt(anchor.blockIndex||0,10)||0,targetBlock=null;
                    if(oldText.length>=12){
                      var oldNeedle=oldText.length>80?oldText.substring(0,80):oldText;
                      for(var b=0;b<blocks.length;b++)if(window.__rwDocCleanText(blocks[b]).indexOf(oldNeedle)>=0){targetBlock=blocks[b];break;}
                    }
                    if(!targetBlock){oldIdx=Math.max(0,Math.min(blocks.length-1,oldIdx));targetBlock=blocks[oldIdx];}
                    if(targetBlock){targetBlock.scrollIntoView(true);return true;}
                    return false;
                  };
                  return true;
                }catch(e){return false;}})()
                """;
    }

    /**
     * Converts Android physical-pixel chrome overlap into the CSS-pixel
     * coordinate system used by DOMRects and the visual viewport. A mobile
     * EPUB may expose a much wider layout viewport through
     * {@code window.innerWidth} than the portion actually visible on screen.
     * Passing raw Android pixels here makes the usable DOM viewport collapse on
     * high-density devices (notably Samsung phones), causing every visible
     * sentence candidate to be rejected.
     */
    static String viewportInsetAssignment(int topOcclusionPx,
                                          int bottomOcclusionPx,
                                          int webViewHeightPx) {
        int safeTop = Math.max(0, topOcclusionPx);
        int safeBottom = Math.max(0, bottomOcclusionPx);
        int safeHeight = Math.max(1, webViewHeightPx);
        return "(function(){var __rwPhysicalHeight=" + safeHeight
                + ";var __rwVisualViewport=window.visualViewport;"
                + "var __rwCssHeight=__rwVisualViewport&&Number(__rwVisualViewport.height)>0"
                + "?Number(__rwVisualViewport.height):Math.max(0,Number(window.innerHeight)||0);"
                + "var __rwPhysicalToCss=__rwCssHeight/__rwPhysicalHeight;"
                + "window.__rwDocAnchorTopInset=" + safeTop + "*__rwPhysicalToCss;"
                + "window.__rwDocAnchorBottomInset=" + safeBottom + "*__rwPhysicalToCss;})();";
    }

    static String captureExpression(int topOcclusionPx,
                                    int bottomOcclusionPx,
                                    int webViewHeightPx,
                                    boolean forceVerticalWriting) {
        return captureExpression(topOcclusionPx, bottomOcclusionPx,
                webViewHeightPx, forceVerticalWriting, false);
    }

    static String captureExpression(int topOcclusionPx,
                                    int bottomOcclusionPx,
                                    int webViewHeightPx,
                                    boolean forceVerticalWriting,
                                    boolean captureColumnStart) {
        return viewportInsetAssignment(topOcclusionPx, bottomOcclusionPx, webViewHeightPx)
                + "window.__rwDocForceVerticalWriting=" + forceVerticalWriting
                + ";window.__rwDocCaptureColumnStart=" + captureColumnStart
                + ";" + """
                (function(){try{
                  return window.__rwDocAnchorAtTop?window.__rwDocAnchorAtTop():
                    {anchorMode:'script-missing',writingMode:window.__rwDocForceVerticalWriting?'vertical-rl':'horizontal-tb',blockIndex:0,
                     scrollX:window.scrollX||0,scrollY:window.scrollY||0,
                     maxScrollX:Math.max(0,document.documentElement.scrollWidth-
                       (window.visualViewport&&window.visualViewport.width>0?window.visualViewport.width:window.innerWidth)),
                     maxScrollY:Math.max(0,document.documentElement.scrollHeight-
                       (window.visualViewport&&window.visualViewport.height>0?window.visualViewport.height:window.innerHeight)),text:''};
                }catch(e){return {anchorMode:'capture-error',writingMode:window.__rwDocForceVerticalWriting?'vertical-rl':'horizontal-tb',blockIndex:0,
                  scrollX:window.scrollX||0,scrollY:window.scrollY||0,maxScrollX:0,maxScrollY:0,text:''};}})()
                """;
    }

    /**
     * Installs the helper functions and captures in one WebView evaluation.
     *
     * <p>Most reflowable EPUB pages normally keep JavaScript disabled. A
     * separate install evaluation therefore allowed the JavaScript policy to
     * be restored before the capture evaluation started, losing the helper
     * functions on some WebView builds. Keeping both operations in the same
     * evaluation makes explicit bookmark capture deterministic.</p>
     */
    static String installAndCaptureExpression(int topOcclusionPx,
                                              int bottomOcclusionPx,
                                              int webViewHeightPx,
                                              boolean forceVerticalWriting) {
        return installAndCaptureExpression(topOcclusionPx, bottomOcclusionPx,
                webViewHeightPx, forceVerticalWriting, false);
    }

    static String installAndCaptureExpression(int topOcclusionPx,
                                              int bottomOcclusionPx,
                                              int webViewHeightPx,
                                              boolean forceVerticalWriting,
                                              boolean captureColumnStart) {
        return installScript() + ";"
                + captureExpression(topOcclusionPx, bottomOcclusionPx,
                webViewHeightPx, forceVerticalWriting, captureColumnStart);
    }
}
