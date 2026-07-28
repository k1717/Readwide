package com.readwide.manager;

import java.util.List;

/** Builds injection-safe JavaScript for resolving a parsed EPUB CFI in WebView. */
final class EpubCfiJavascript {
    private EpubCfiJavascript() {}

    /**
     * Installs a DOM resolver for the point-CFI subset parsed by {@link EpubCfi}.
     * Android/WebView values are not interpolated into this script.
     */
    static String installScript() {
        return """
                (function(){try{
                  window.__rwEpubCfiTextGroup=function(parent,gap){
                    var nodes=[],elements=0,children=parent&&parent.childNodes?parent.childNodes:[];
                    for(var i=0;i<children.length;i++){
                      var n=children[i];
                      if(n.nodeType===1){elements++;continue;}
                      if((n.nodeType===3||n.nodeType===4)&&elements===gap)nodes.push(n);
                    }
                    return nodes;
                  };
                  window.__rwEpubCfiPointInGroup=function(nodes,declared,before,after){
                    var text='',i;
                    for(i=0;i<nodes.length;i++)text+=String(nodes[i].nodeValue||'');
                    var wanted=Math.max(0,parseInt(declared,10)||0),exact=wanted<=text.length;
                    var at=Math.min(wanted,text.length),pre=String(before||''),post=String(after||'');
                    var matches=function(pos){
                      return (!pre||text.substring(Math.max(0,pos-pre.length),pos)===pre)&&
                             (!post||text.substring(pos,pos+post.length)===post);
                    };
                    if((pre||post)&&!matches(at)){
                      var needle=pre+post,best=-1,bestDistance=Number.MAX_SAFE_INTEGER,from=0,found;
                      if(needle){
                        while((found=text.indexOf(needle,from))>=0){
                          var candidate=found+pre.length,distance=Math.abs(candidate-wanted);
                          if(distance<bestDistance){best=candidate;bestDistance=distance;}
                          from=found+Math.max(1,needle.length);
                        }
                      }else if(pre){var p=text.lastIndexOf(pre,Math.min(wanted,text.length));if(p>=0)best=p+pre.length;}
                      else if(post){best=text.indexOf(post);}
                      if(best>=0){at=best;exact=true;}else exact=false;
                    }
                    var remaining=at;
                    for(i=0;i<nodes.length;i++){
                      var len=String(nodes[i].nodeValue||'').length;
                      if(remaining<=len)return {node:nodes[i],offset:remaining,exact:exact};
                      remaining-=len;
                    }
                    return nodes.length?{node:nodes[nodes.length-1],offset:String(nodes[nodes.length-1].nodeValue||'').length,exact:false}:null;
                  };
                  window.__rwEpubCfiResolve=function(target){
                    target=target||{};var steps=target.steps||[],node=document.documentElement,lastElement=node;
                    if(!node||!steps.length)return null;
                    for(var i=0;i<steps.length;i++){
                      var step=steps[i]||{},n=parseInt(step.n,10)||0;
                      if(n<=0)return {element:lastElement,range:null,exact:false};
                      if((n&1)===0){
                        var index=(n/2)-1,children=node.children||[],next=children[index]||null,id=String(step.id||'');
                        if(id&&(!next||String(next.id||'')!==id)){
                          next=null;
                          for(var c=0;c<children.length;c++)if(String(children[c].id||'')===id){next=children[c];break;}
                          if(!next){var asserted=document.getElementById(id);if(asserted&&node.contains(asserted))next=asserted;}
                        }
                        if(!next)return {element:lastElement,range:null,exact:false};
                        node=next;lastElement=next;
                      }else{
                        if(i!==steps.length-1)return {element:lastElement,range:null,exact:false};
                        var group=window.__rwEpubCfiTextGroup(node,(n-1)/2);
                        var point=window.__rwEpubCfiPointInGroup(group,target.offset,target.before,target.after);
                        if(!point)return {element:lastElement,range:null,exact:false};
                        var range=document.createRange(),len=String(point.node.nodeValue||'').length;
                        range.setStart(point.node,Math.max(0,Math.min(len,point.offset)));
                        range.setEnd(point.node,Math.max(0,Math.min(len,point.offset<len?point.offset+1:point.offset)));
                        return {element:lastElement,range:range,exact:!!point.exact};
                      }
                    }
                    return {element:lastElement,range:null,exact:true};
                  };
                  window.__rwEpubCfiScroll=function(target){
                    var resolved=window.__rwEpubCfiResolve(target);if(!resolved)return false;
                    var element=resolved.element,rect=null;
                    if(resolved.range){var rects=resolved.range.getClientRects();rect=rects&&rects.length?rects[0]:resolved.range.getBoundingClientRect();}
                    if(!rect&&element){
                      try{element.scrollIntoView({block:'center',inline:'center',behavior:'auto'});}catch(e){element.scrollIntoView(true);}
                      rect=element.getBoundingClientRect();
                    }
                    if(rect){
                      var top=Math.max(0,Number(window.__rwDocAnchorTopInset)||0),
                          bottom=Math.max(top+1,window.innerHeight-Math.max(0,Number(window.__rwDocAnchorBottomInset)||0)),
                          dx=0,dy=0;
                      if(rect.left<0||rect.right>window.innerWidth)dx=rect.left-window.innerWidth*0.5;
                      if(rect.top<top||rect.bottom>bottom)dy=rect.top-(top+bottom)*0.5;
                      if(dx||dy)window.scrollBy(dx,dy);
                    }
                    return !!resolved.exact;
                  };
                  return true;
                }catch(e){return false;}})()
                """;
    }

    /** Safe JSON payload consumed by {@link #installScript()}. */
    static String targetJson(EpubCfi cfi) {
        if (cfi == null) return "null";
        StringBuilder out = new StringBuilder(192);
        out.append("{\"steps\":[");
        List<EpubCfi.Step> steps = cfi.contentSteps();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) out.append(',');
            EpubCfi.Step step = steps.get(i);
            out.append("{\"n\":").append(step.number())
                    .append(",\"id\":").append(quoteJson(step.idAssertion()))
                    .append('}');
        }
        out.append("],\"offset\":").append(Math.max(0, cfi.characterOffset()))
                .append(",\"before\":").append(quoteJson(cfi.textBefore()))
                .append(",\"after\":").append(quoteJson(cfi.textAfter()))
                .append('}');
        return out.toString();
    }

    /** Expression to run after {@link #installScript()} on the loaded page. */
    static String scrollExpression(EpubCfi cfi) {
        return "(function(){try{return !!(window.__rwEpubCfiScroll&&"
                + "window.__rwEpubCfiScroll(" + targetJson(cfi)
                + "));}catch(e){return false;}})()";
    }

    /** Installs the resolver and immediately scrolls to the supplied target. */
    static String installAndScrollExpression(EpubCfi cfi) {
        return installScript() + ";" + scrollExpression(cfi);
    }

    private static String quoteJson(String value) {
        String s = value != null ? value : "";
        StringBuilder out = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '<': out.append("\\u003c"); break;
                case '>': out.append("\\u003e"); break;
                case '&': out.append("\\u0026"); break;
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                default:
                    if (c < 0x20) {
                        out.append("\\u00");
                        final char[] hex = "0123456789abcdef".toCharArray();
                        out.append(hex[(c >>> 4) & 0x0f]).append(hex[c & 0x0f]);
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }
}
