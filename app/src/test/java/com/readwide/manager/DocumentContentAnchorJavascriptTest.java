package com.readwide.manager;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DocumentContentAnchorJavascriptTest {

    @Test
    public void verticalPageMetadataForcesVerticalSentenceCapture() {
        String install = DocumentContentAnchorJavascript.installScript();
        String capture = DocumentContentAnchorJavascript.captureExpression(24, 36, 720, true);

        assertTrue(install.contains("__rwDocForceVerticalWriting===true"));
        assertTrue(install.contains("__rwDocVerticalAnchorIsVisible"));
        assertTrue(capture.contains("__rwDocForceVerticalWriting=true"));
        assertTrue(capture.contains("writingMode:window.__rwDocForceVerticalWriting?'vertical-rl'"));
    }

    @Test
    public void verticalCaptureUsesOneViewportProbeForCaretAndSentenceElement() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("window.__rwDocVerticalProbe=function()"));
        assertTrue(install.contains("window.__rwDocStableSentenceRoot(caret&&caret.node)"));
        assertTrue(install.contains("window.__rwDocGlyphForCaret(root,caret,"));
        assertTrue(install.contains("x+viewport.visual.left,y+viewport.visual.top"));
        assertTrue(install.contains("window.__rwDocGlyphFullyInside(glyph.rect,viewport)"));
        assertTrue(install.contains("caretMatched:true"));
        assertFalse(install.contains("seen.indexOf(root)>=0"));
    }

    @Test
    public void verticalCaptureRejectsClippedEdgeGlyphs() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("window.__rwDocGlyphFullyInside=function(rect,viewport)"));
        assertTrue(install.contains("rect.left>=viewport.left+guard"));
        assertTrue(install.contains("rect.right<=viewport.right-guard"));
        assertTrue(install.contains("rect.top>=viewport.top+guard"));
        assertTrue(install.contains("rect.bottom<=viewport.bottom-guard"));
        assertTrue(install.contains(
                "if(!window.__rwDocGlyphFullyInside(glyph.rect,viewport))continue"));
    }

    @Test
    public void verticalCaretSnapChecksBothAdjacentGlyphsWithTightDistance() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("if(insertion>0)offsets.push(insertion-1)"));
        assertTrue(install.contains("offsets.push(insertion)"));
        assertTrue(install.contains(
                "if(insertion+1<map.text.length)offsets.push(insertion+1)"));
        assertTrue(install.contains("Math.max(6,best.rect.width*0.75)"));
        assertTrue(install.contains("Math.max(6,best.rect.height*0.75)"));
        assertFalse(install.contains("best.rect.width*1.35"));
        assertFalse(install.contains("best.rect.height*1.35"));
    }

    @Test
    public void unmatchedCaretIsNotSilentlyReportedAsAnExactZeroOffset() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("if(!root||!caret)return -1"));
        assertTrue(install.contains("prefix.selectNodeContents(root)"));
        assertTrue(install.contains("prefix.cloneContents()"));
        assertTrue(install.contains("querySelectorAll('script,style,rt,rp')"));
        assertTrue(install.contains("caretParent.closest('rt,rp')"));
        assertTrue(install.contains("if(!glyph)continue"));
        assertFalse(install.contains("if(localOffset<0)localOffset=0"));
    }

    @Test
    public void textNodeBoundarySelectsNextRealGlyphInsteadOfCollapsedPreviousRange() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("if(offset<start+length)"));
        assertTrue(install.contains("tail.setStart(lastNode,lastLength-1)"));
        assertFalse(install.contains("if(offset<=start+length)"));
        assertTrue(install.contains("r.width<0.5||r.height<0.5"));
    }

    @Test
    public void verticalCaptureReadsScrollingElementAndRecordsViewport() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("document.scrollingElement||document.documentElement"));
        assertTrue(install.contains("viewportWidth:viewport.width||0"));
        assertTrue(install.contains("viewportHeight:viewport.height||0"));
        assertTrue(install.contains("focusRatioX:viewport.width>0"));
        assertTrue(install.contains("focusRatioY:usableViewport.height>0"));
        assertTrue(install.contains("var edgeInset=Math.min(48,Math.max(24,viewport.width*0.06))"));
        assertTrue(install.contains("var focusX=best.glyphRect.left+best.glyphRect.width/2"));
        assertTrue(install.contains("var focusY=best.glyphRect.top+best.glyphRect.height/2"));
        assertTrue(install.contains("var focusCenterX=focusRect.left+Math.max(0,focusRect.width)/2"));
        assertTrue(install.contains("var focusCenterY=focusRect.top+Math.max(0,focusRect.height)/2"));
        assertTrue(install.contains("window.scrollBy(focusCenterX-desiredX,focusCenterY-desiredY)"));
        assertFalse(install.contains("window.scrollTo(window.scrollX,anchor.scrollY);\n                        return true"));
    }

    @Test
    public void sameViewportRestoreUsesExactDomScrollAndVerificationUsesExactGlyph() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("window.__rwDocSameVisualGeometry(anchor)"));
        assertTrue(install.contains("viewportBasis:'visual-v1'"));
        assertTrue(install.contains("window.scrollTo(anchor.scrollX"));
        assertTrue(install.contains("var range=window.__rwDocRangeAtOffset(target,anchor.charOffset||0)"));
        assertTrue(install.contains("var glyph=window.__rwDocRangeRect(range);if(!glyph)return false"));
        assertTrue(install.contains("glyph.top < viewport.top-edgeTolerance"));
        assertTrue(install.contains("glyph.bottom > viewport.bottom+edgeTolerance"));
        assertTrue(install.contains("samePositionViewport"));
        assertTrue(install.contains("currentMaxX*ratioX"));
    }

    @Test
    public void idlessRestorePrefersSavedBlockAndContextAmongDuplicateSentences() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("blockIndex===expectedBlock"));
        assertTrue(install.contains("Math.abs(blockIndex-expectedBlock)"));
        assertTrue(install.contains("textBefore"));
        assertTrue(install.contains("textAfter"));
    }

    @Test
    public void bookmarkPreviewBeginsAtCapturedGlyphInsteadOfEarlierHiddenText() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("var focusStart=Math.max(start,offset)"));
        assertFalse(install.contains("offset-24"));
    }

    @Test
    public void verticalBookmarkLabelScansToStartOfSelectedPhysicalColumn() {
        String install = DocumentContentAnchorJavascript.installScript();
        String bookmarkCapture = DocumentContentAnchorJavascript.installAndCaptureExpression(
                0, 0, 720, true, true);
        String stateCapture = DocumentContentAnchorJavascript.installAndCaptureExpression(
                0, 0, 720, true);

        assertTrue(install.contains("window.__rwDocVerticalColumnStart=function(selected)"));
        assertTrue(install.contains("window.__rwDocCaptureColumnStart===true?"));
        assertTrue(install.contains("var targetX=selected.glyphRect.left+selected.glyphRect.width/2"));
        assertTrue(install.contains("for(var y=startY;y<=endY;y+=step)consider(y)"));
        assertTrue(install.contains(
                "Math.max(targetWidth,Math.max(1,glyph.rect.width))*0.65"));
        assertFalse(install.contains(
                "(targetWidth+Math.max(1,glyph.rect.width))*0.55"));
        assertTrue(install.contains("Math.abs(centerX-targetX)>sameColumnTolerance"));
        assertTrue(install.contains("columnStartText:columnStartText"));
        assertTrue(bookmarkCapture.contains("window.__rwDocCaptureColumnStart=true"));
        assertTrue(stateCapture.contains("window.__rwDocCaptureColumnStart=false"));
    }

    @Test
    public void japaneseSentenceBoundariesUseEncodingStableEscapes() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("\\u3002"));
        assertTrue(install.contains("\\uFF01"));
        assertTrue(install.contains("\\uFF1F"));
    }

    @Test
    public void horizontalPageDoesNotForceVerticalWriting() {
        String capture = DocumentContentAnchorJavascript.captureExpression(0, 0, 720, false);

        assertTrue(capture.contains("__rwDocForceVerticalWriting=false"));
        assertFalse(capture.contains("__rwDocForceVerticalWriting=true"));
    }

    @Test
    public void missingOrFailedScriptIsReportedInsteadOfPageStartFallback() {
        String capture = DocumentContentAnchorJavascript.captureExpression(0, 0, 720, true);

        assertTrue(capture.contains("anchorMode:'script-missing'"));
        assertTrue(capture.contains("anchorMode:'capture-error'"));
        assertFalse(capture.contains("catch(e){return {anchorMode:'block-top'"));
    }

    @Test
    public void physicalChromeInsetsAreScaledIntoCssViewportCoordinates() {
        String assignment = DocumentContentAnchorJavascript.viewportInsetAssignment(
                144, 216, 1080);

        assertTrue(assignment.contains("__rwCssHeight/__rwPhysicalHeight"));
        assertTrue(assignment.contains("window.visualViewport"));
        assertTrue(assignment.contains("window.__rwDocAnchorTopInset=144*__rwPhysicalToCss"));
        assertTrue(assignment.contains("window.__rwDocAnchorBottomInset=216*__rwPhysicalToCss"));
        assertFalse(assignment.contains("window.__rwDocAnchorTopInset=144;"));
    }

    @Test
    public void mobileEpubUsesVisualViewportInsteadOfWideLayoutViewport() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("window.__rwDocViewport=function()"));
        assertTrue(install.contains("vv&&Number(vv.width)>0?Number(vv.width)"));
        assertTrue(install.contains("vv&&Number(vv.height)>0?Number(vv.height)"));
        assertTrue(install.contains("Number(vv.offsetLeft)"));
        assertTrue(install.contains("Number(vv.offsetTop)"));
        assertTrue(install.contains("Math.min(viewport.right,r.right)"));
        assertTrue(install.contains("glyph.right > viewport.right+edgeTolerance"));
        assertTrue(install.contains("window.__rwDocLegacyLayoutMatches(anchor)"));
        assertFalse(install.contains("var rr=Math.min(window.innerWidth,r.right)"));
    }

    @Test
    public void installAndCaptureStayInOneJavascriptEvaluation() {
        String combined = DocumentContentAnchorJavascript.installAndCaptureExpression(
                24, 36, 720, true);

        int installAt = combined.indexOf("window.__rwDocAnchorAtTop=function()");
        int captureAt = combined.lastIndexOf("window.__rwDocAnchorAtTop?");
        assertTrue(installAt >= 0);
        assertTrue(captureAt > installAt);
        assertTrue(combined.contains("__rwDocForceVerticalWriting=true"));
    }

    @Test
    public void namespacedEpubTypeDoesNotUseAnInvalidCssSelectorEscape() {
        String install = DocumentContentAnchorJavascript.installScript();

        assertTrue(install.contains("hasAttribute('epub:type')"));
        assertFalse(install.contains("[epub\\\\:type]"));
    }
}
