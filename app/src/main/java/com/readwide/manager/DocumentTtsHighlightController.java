package com.readwide.manager;

import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Highlights the currently spoken read-aloud sentence in the document viewer's
 * WebView (EPUB / Word-family / HWP/HWPX / Markdown). The read-aloud text buffer
 * is plain text (HTML flattened by {@code Html.fromHtml}). Matching uses the
 * known source page and character offset after both source and DOM whitespace
 * are removed. If the normalized page differs, only a unique full sentence
 * with matching surrounding context is accepted.
 *
 * <p>All state lives in an injected script (`window.__rwTtsHl`) so it survives
 * across highlight calls without Java holding DOM references. The script is
 * reinstalled on every page load. JavaScript is toggled on only for the
 * evaluate call and restored afterwards, matching the Markdown anchor script's
 * handling.</p>
 */
final class DocumentTtsHighlightController {

    private final DocumentPageActivity activity;

    DocumentTtsHighlightController(@NonNull DocumentPageActivity activity) {
        this.activity = activity;
    }

    /**
     * The most recently requested sentence, replayed after a page load. During
     * automatic page turns the next page's first segment starts speaking while
     * the WebView is still loading, so the highlight call arrives before the
     * helper script exists and silently does nothing; replaying it once the
     * page finishes loading makes the first sentence on the new page light up.
     */
    @Nullable private String pendingSentence;
    private boolean pendingAllowScroll;
    private int pendingPage = -1;
    @Nullable private DocumentTtsHighlightMath.TextLocation pendingLocation;

    /**
     * Installs the highlight helper into the current page. Safe to call on every
     * page load; it redefines the helper and clears any prior highlight. If a
     * sentence was requested while the page was still loading, it is replayed
     * now.
     */
    void installScript() {
        if (activity.webView == null) return;
        evaluate(HIGHLIGHT_SCRIPT, null);
        if (pendingSentence != null && pendingPage == activity.currentPage && pendingLocation != null) {
            evaluate(showJs(pendingSentence, pendingAllowScroll, pendingLocation), null);
        }
    }

    /**
     * Highlights the given source occurrence. Whitespace is removed and simple
     * character case is normalized on both sides. If its known position and
     * context cannot identify a match, the previous highlight is cleared.
     */
    void highlight(@Nullable String sentence, boolean allowScroll, int page,
                   @NonNull DocumentTtsHighlightMath.TextLocation location) {
        if (activity.webView == null) return;
        String normalized = DocumentTtsHighlightMath.normalizeForDomSearch(sentence);
        if (normalized.isEmpty()) {
            clear();
            return;
        }
        // Keep the source page/position for replay; the same sentence can occur
        // more than once within a chapter or on an unrelated page.
        pendingSentence = normalized;
        pendingAllowScroll = allowScroll;
        pendingPage = page;
        pendingLocation = location;
        if (page == activity.currentPage) evaluate(showJs(normalized, allowScroll, location), null);
    }

    private static String showJs(@NonNull String normalized, boolean allowScroll,
                                 @NonNull DocumentTtsHighlightMath.TextLocation location) {
        return "(function(){try{return window.__rwTtsHl&&window.__rwTtsHl.show("
                + DocumentTtsHighlightMath.toJsStringLiteral(normalized)
                + "," + (allowScroll ? "true" : "false") + "," + location.toJavascript()
                + ");}catch(e){return false;}})()";
    }

    /** Removes any current highlight. */
    void clear() {
        pendingSentence = null;
        pendingLocation = null;
        pendingPage = -1;
        if (activity.webView == null) return;
        evaluate("(function(){try{if(window.__rwTtsHl)window.__rwTtsHl.clear();}catch(e){}})()", null);
    }

    private void evaluate(@NonNull String js, @Nullable android.webkit.ValueCallback<String> cb) {
        WebView webView = activity.webView;
        if (webView == null) {
            if (cb != null) cb.onReceiveValue(null);
            return;
        }
        final int targetPage = activity.currentPage;
        WebSettings settings = webView.getSettings();
        boolean restoreJavascriptOff = !settings.getJavaScriptEnabled();
        if (restoreJavascriptOff) settings.setJavaScriptEnabled(true);
        webView.evaluateJavascript(js, value -> {
            activity.restoreDocumentJavaScriptPolicy(
                    webView, targetPage, restoreJavascriptOff);
            if (cb != null) cb.onReceiveValue(value);
        });
    }

    /**
     * The injected helper maps normalized text positions back to DOM ranges.
     * It retains node/offset mappings while removing whitespace, verifies the
     * source position against the page fingerprint, and falls back to unique
     * surrounding context if rendering changed the text. Highlight spans share
     * a class so the previous spoken range can be removed before the next one.
     */
    private static final String HIGHLIGHT_SCRIPT =
            "(function(){try{"
            + "if(window.__rwTtsHl)return true;"
            + "var HL='__rwTtsHlSpan';"
            + "function clearSpan(){var es=document.getElementsByClassName(HL);"
            + "while(es.length){var e=es[0];var p=e.parentNode;while(e.firstChild)p.insertBefore(e.firstChild,e);p.removeChild(e);p.normalize();}}"
            // Whitespace-SQUEEZED matching: both the DOM text and the target drop
            // ALL whitespace before comparing, so paragraph boundaries (which the
            // buffer renders as newlines but the DOM may not render at all) and
            // any other spacing differences cannot break or misalign the match.
            + "function lower1(c){var l=c.toLowerCase();return l.length===1?l:c;}"
            + "function build(){"
            + "var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){"
            + "if(!n.nodeValue||!n.nodeValue.trim())return NodeFilter.FILTER_REJECT;"
            + "var p=n.parentNode;if(p){var t=p.nodeName.toUpperCase();if(t==='SCRIPT'||t==='STYLE')return NodeFilter.FILTER_REJECT;}"
            + "return NodeFilter.FILTER_ACCEPT;}},false);"
            + "var text='',map=[],node;"
            + "while((node=walker.nextNode())){var raw=node.nodeValue;"
            + "for(var i=0;i<raw.length;i++){var c=raw.charAt(i);"
            + "if(!/\\s/.test(c)){text+=lower1(c);map.push({node:node,offset:i});}}}"
            + "return {text:text,map:map};}"
            + "function squeeze(s){var o='';for(var i=0;i<s.length;i++){var c=s.charAt(i);"
            + "if(!/\\s/.test(c))o+=lower1(c);}return o;}"
            + "function hash(s){var h=0;for(var i=0;i<s.length;i++)h=(Math.imul(h,31)+s.charCodeAt(i))|0;return h;}"
            + "function locate(text,target,location){if(!location)return -1;"
            + "var at=location.start;"
            + "if(text.length===location.pageLength&&hash(text)===location.pageHash"
            + "&&text.substring(at,at+target.length)===target)return at;"
            // When rendered text differs (e.g. an image replacement character),
            // accept only one exact surrounding-context match. Never guess the
            // first occurrence or a short prefix of an unrelated sentence.
            + "var before=location.before||'',after=location.after||'',found=-1;"
            + "for(var i=text.indexOf(target);i>=0;i=text.indexOf(target,i+1)){"
            + "var end=i+target.length;"
            + "var left=before?i>=before.length&&text.substring(i-before.length,i)===before:i===0;"
            + "var right=after?text.substring(end,end+after.length)===after:end===text.length;"
            + "if(left&&right){if(found>=0)return -1;found=i;}}return found;}"
            + "window.__rwTtsHl={"
            + "show:function(target,allowScroll,location){clearSpan();"
            + "var tq=squeeze(target||'');if(!tq)return false;"
            + "var b=build();var idx=locate(b.text,tq,location);var matchLen=tq.length;"
            + "if(idx<0)return false;"
            + "var startM=b.map[idx];var endIdx=idx+matchLen-1;if(endIdx>=b.map.length)endIdx=b.map.length-1;"
            + "var endM=b.map[endIdx];if(!startM||!endM)return false;"
            + "var spans=[];var gi=idx;"
            + "while(gi<=endIdx){var n=b.map[gi].node;var so=b.map[gi].offset;var eo=so;"
            + "while(gi+1<=endIdx&&b.map[gi+1].node===n){gi++;eo=b.map[gi].offset;}"
            + "try{var r=document.createRange();r.setStart(n,so);r.setEnd(n,eo+1);"
            + "var sp=document.createElement('span');sp.className=HL;"
            + "sp.style.setProperty('background-color','rgba(255,214,0,0.42)','important');"
            + "sp.style.setProperty('border-radius','2px','important');"
            + "sp.style.setProperty('box-shadow','0 0 0 2px rgba(255,214,0,0.42)','important');"
            + "r.surroundContents(sp);spans.push(sp);}catch(e){}"
            + "gi++;}"
            + "if(!spans.length)return false;"
            + "var span=spans[spans.length-1];"
            + "var rc=span?span.getBoundingClientRect():null;"
            // Recenter when the sentence leaves the comfortably visible area: fully
            // above, or entering the bottom band (~180px) that the toolbars and the
            // floating card cover. Requiring it to be FULLY off-screen stalled the
            // follow at the bottom of long pages - partially visible (or chrome-
            // covered) sentences never triggered a scroll. At the true end of a
            // page the browser clamps the scroll, so this is harmless there.
            + "var vv=window.visualViewport;"
            + "var vw=vv&&Number(vv.width)>0&&isFinite(Number(vv.width))?Number(vv.width):window.innerWidth;"
            + "var vh=vv&&Number(vv.height)>0&&isFinite(Number(vv.height))?Number(vv.height):window.innerHeight;"
            + "var vl=vv&&isFinite(Number(vv.offsetLeft))?Number(vv.offsetLeft):0;"
            + "var vt=vv&&isFinite(Number(vv.offsetTop))?Number(vv.offsetTop):0;"
            + "if(allowScroll&&rc&&(rc.left<vl||rc.right>vl+vw||rc.top<vt||rc.bottom>vt+vh-180)){"
            + "span.scrollIntoView({block:'center',inline:'nearest',behavior:'auto'});}"
            + "return true;},"
            + "clear:function(){clearSpan();}};"
            + "return true;}catch(e){return false;}})()";
}
