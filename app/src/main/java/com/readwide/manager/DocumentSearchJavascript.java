package com.readwide.manager;

/** Search placement in CSS coordinates, including EPUB vertical writing. */
final class DocumentSearchJavascript {
    private DocumentSearchJavascript() {}

    static String revealCurrentMatch(int safeBottomPx, int webViewHeightPx) {
        return "(function(){try{var safeBottomPx=" + safeBottomPx
                + ";var viewHeightPx=" + webViewHeightPx + ";"
                + """
                function verticalHit(e){
                  var mode=String(window.getComputedStyle(e).writingMode||'').toLowerCase();
                  return mode.indexOf('vertical-')===0||mode.indexOf('sideways-')===0;
                }
                function ensureSpacers(vertical){
                  var ids=['rw-document-search-top-spacer','rw-document-search-bottom-spacer'];
                  if(vertical){
                    for(var i=0;i<ids.length;i++){
                      var old=document.getElementById(ids[i]);
                      if(old&&old.parentNode)old.parentNode.removeChild(old);
                    }
                    return;
                  }
                  if(!document.body)return;
                  if(!document.getElementById('rw-document-search-spacer-style')){
                    var style=document.createElement('style');style.id='rw-document-search-spacer-style';
                    style.textContent='#rw-document-search-top-spacer{display:block!important;height:12vh!important;min-height:72px!important;pointer-events:none!important;}#rw-document-search-bottom-spacer{display:block!important;height:88vh!important;min-height:420px!important;pointer-events:none!important;}';
                    (document.head||document.documentElement).appendChild(style);
                  }
                  if(!document.getElementById(ids[0])){
                    var top=document.createElement('div');top.id=ids[0];top.setAttribute('aria-hidden','true');
                    document.body.insertBefore(top,document.body.firstChild);
                  }
                  if(!document.getElementById(ids[1])){
                    var bottom=document.createElement('div');bottom.id=ids[1];bottom.setAttribute('aria-hidden','true');
                    document.body.appendChild(bottom);
                  }
                }
                function placeHit(){
                  var e=document.getElementById('rw-document-search-current');
                  if(!e)return false;
                  var vertical=verticalHit(e);
                  ensureSpacers(vertical);
                  if(vertical){
                    // Logical block alignment handles both signed vertical-rl
                    // offsets and vertical-lr, including nested scroll boxes.
                    try{e.scrollIntoView({block:'center',inline:'start',behavior:'auto'});}
                    catch(ignore){e.scrollIntoView(true);}
                  }
                  var h=window.visualViewport&&window.visualViewport.height
                    ||window.innerHeight||document.documentElement.clientHeight||0;
                  var safeBottom=safeBottomPx>0&&viewHeightPx>0?safeBottomPx*h/viewHeightPx:-1;
                  var r=e.getBoundingClientRect();
                  var targetTop=Math.max(54,Math.round(h*0.12));
                  if(safeBottom>0)targetTop=Math.min(targetTop,Math.max(8,safeBottom-r.height-8));
                  var current=window.scrollY||document.documentElement.scrollTop||0;
                  var y=Math.max(0,current+r.top-targetTop);
                  var x=window.scrollX||document.documentElement.scrollLeft||0;
                  if(Math.abs(y-current)>3)window.scrollTo(x,y);
                  if(safeBottom>0){
                    var rr=e.getBoundingClientRect();
                    if(rr.bottom>safeBottom){
                      var cy=window.scrollY||document.documentElement.scrollTop||0;
                      window.scrollTo(window.scrollX||document.documentElement.scrollLeft||0,
                        Math.max(0,cy+rr.bottom-safeBottom+12));
                    }
                  }
                  return true;
                }
                var ok=placeHit();setTimeout(placeHit,60);setTimeout(placeHit,160);
                return ok;}catch(ex){return false;}})()
                """;
    }
}
