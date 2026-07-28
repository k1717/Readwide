package com.readwide.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EpubCfiJavascriptTest {
    @Test
    public void serializesGeorgiaStepsOffsetAndAssertions() {
        EpubCfi cfi = EpubCfi.parse(
                "epubcfi(/6/4[ct]!/4/2[d10e42]/12[d10e85]/6[d10e93]/1:1552[Bryan,%20and])");
        assertNotNull(cfi);

        String json = EpubCfiJavascript.targetJson(cfi);
        assertTrue(json.contains("\"n\":4"));
        assertTrue(json.contains("\"id\":\"d10e93\""));
        assertTrue(json.contains("\"n\":1"));
        assertTrue(json.contains("\"offset\":1552"));
        assertTrue(json.contains("\"before\":\"Bryan\""));
        assertTrue(json.contains("\"after\":\" and\""));
    }

    @Test
    public void dangerousAssertionCharactersStayInsideEscapedJsonStrings() {
        EpubCfi cfi = EpubCfi.parse(
                "epubcfi(/6/2[item\"ref]!/4/2[p\"id]/1:0[\";window.bad=1;//,</script>&])");
        assertNotNull(cfi);

        String json = EpubCfiJavascript.targetJson(cfi);
        assertTrue(json.contains("p\\\"id"));
        assertTrue(json.contains("\\u003c/script\\u003e\\u0026"));
        assertFalse(json.contains("</script>"));

        String expression = EpubCfiJavascript.scrollExpression(cfi);
        assertTrue(expression.contains("window.__rwEpubCfiScroll"));
        assertFalse(expression.contains("</script>"));
    }

    @Test
    public void installScriptUsesElementChildrenAndLogicalTextGaps() {
        String script = EpubCfiJavascript.installScript();
        assertTrue(script.contains("node.children"));
        assertTrue(script.contains("n.nodeType===3||n.nodeType===4"));
        assertTrue(script.contains("elements===gap"));
        assertTrue(script.contains("document.getElementById"));
        assertTrue(script.contains("document.createRange"));
        assertTrue(script.contains("window.__rwDocAnchorTopInset"));
        assertTrue(script.contains("window.__rwDocAnchorBottomInset"));
    }

    @Test
    public void installAndScrollDoesNotEmbedRawCfiSource() {
        EpubCfi cfi = EpubCfi.parse(
                "package.opf#epubcfi(/6/4[ct]!/4/2[d10e42]/18[d10e150]/4[d10e155]/1:35)");
        assertNotNull(cfi);
        String script = EpubCfiJavascript.installAndScrollExpression(cfi);
        assertTrue(script.contains("window.__rwEpubCfiResolve"));
        assertTrue(script.contains("\"offset\":35"));
        assertFalse(script.contains("package.opf#epubcfi"));
    }
}
