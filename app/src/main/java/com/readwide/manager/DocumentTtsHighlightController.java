package com.readwide.manager;

import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Highlights the currently spoken read-aloud sentence in the document viewer's
 * WebView (EPUB / Word-family / HWP/HWPX / Markdown). The read-aloud text buffer
 * is plain text (HTML flattened by {@code Html.fromHtml}), so exact character
 * offsets do not map onto the rendered DOM; instead this searches the DOM for
 * the spoken sentence's text and wraps the matching range in a highlight span.
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

    /**
     * Installs the highlight helper into the current page. Safe to call on every
     * page load; it redefines the helper and clears any prior highlight. If a
     * sentence was requested while the page was still loading, it is replayed
     * now.
     */
    void installScript() {
        if (activity.webView == null) return;
        evaluate(HIGHLIGHT_SCRIPT, null);
        if (pendingSentence != null) {
            evaluate(showJs(pendingSentence, pendingAllowScroll), null);
        }
    }

    /**
     * Highlights the given spoken sentence. The text is matched against the DOM
     * with whitespace collapsed and case ignored; if it can't be found (heavily
     * marked-up passage, or text that spans structural boundaries) nothing is
     * highlighted and the previous highlight is cleared.
     */
    void highlight(@Nullable String sentence, boolean allowScroll) {
        if (activity.webView == null) return;
        String normalized = DocumentTtsHighlightMath.normalizeForDomSearch(sentence);
        if (normalized.isEmpty()) {
            clear();
            return;
        }
        // Latest-wins: kept for the page-load replay above. A sentence from the
        // previous page simply fails the DOM search on the new page, so a stale
        // replay paints nothing rather than something wrong.
        pendingSentence = normalized;
        pendingAllowScroll = allowScroll;
        evaluate(showJs(normalized, allowScroll), null);
    }

    private static String showJs(@NonNull String normalized, boolean allowScroll) {
        return "(function(){try{return window.__rwTtsHl&&window.__rwTtsHl.show("
                + DocumentTtsHighlightMath.toJsStringLiteral(normalized)
                + "," + (allowScroll ? "true" : "false")
                + ");}catch(e){return false;}})()";
    }

    /** Removes any current highlight. */
    void clear() {
        pendingSentence = null;
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
     * The injected highlight helper. Walks visible text nodes, concatenates their
     * text with single-space collapsing while remembering each node/offset, finds
     * the target sentence in that concatenation, then wraps the corresponding DOM
     * range in a `<span>` with a highlight background and scrolls it into view.
     * A single reused span id keeps only one highlight at a time.
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
            + "window.__rwTtsHl={"
            + "show:function(target,allowScroll){clearSpan();"
            + "var tq=squeeze(target||'');if(!tq)return false;"
            + "var b=build();var idx=b.text.indexOf(tq);var matchLen=tq.length;"
            + "if(idx<0&&tq.length>40){idx=b.text.indexOf(tq.substring(0,40));"
            // Prefix fallback: highlight ONLY the found prefix; extending by the
            // full target length would bleed into unrelated following text.
            + "matchLen=40;}"
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
            + "if(allowScroll&&rc&&(rc.top<0||rc.bottom>window.innerHeight-180)){span.scrollIntoView({block:'center'});}"
            + "return true;},"
            + "clear:function(){clearSpan();}};"
            + "return true;}catch(e){return false;}})()";
}
