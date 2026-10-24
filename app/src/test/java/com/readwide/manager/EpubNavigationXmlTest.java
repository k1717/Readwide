package com.readwide.manager;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class EpubNavigationXmlTest {
    @Test public void convertsHtml4NamesToNumericReferencesInTextAndAttributes() throws Exception {
        assertEquals("<p title='&#8220;note&#8221;'>&#160;&#8212;&#945;&#977;&#8364;&#198;</p>",
                EpubNavigationXml.prepare("<p title='&ldquo;note&rdquo;'>&nbsp;&mdash;&alpha;&thetasym;&euro;&AElig;</p>"));
    }

    @Test public void preservesXmlReferencesAndDoesNotDecodeTwiceOrGuessNames() throws Exception {
        String xml = "<p>&amp;nbsp;&lt;script&gt;&quot;&apos;&#160;&#xA0;&unknown;&NBSP;</p>";
        assertEquals(xml, EpubNavigationXml.prepare(xml));
    }

    @Test public void preservesCommentsCdataAndProcessingInstructionsExactly() throws Exception {
        String literal = "<?note &nbsp;?><![CDATA[<!DOCTYPE html [ignored]> &mdash;]]>"
                + "<!-- <!DOCTYPE html [ignored]> &alpha; -->";
        assertEquals("<p>" + literal + "&#160;</p>",
                EpubNavigationXml.prepare("<p>" + literal + "&nbsp;</p>"));
    }

    @Test public void skipsCommentDoctypeThenRemovesOnlyActualExternalDeclaration() throws Exception {
        String comment = "<!-- <!DOCTYPE html SYSTEM 'not-a-declaration'> -->";
        assertEquals(comment + "<html>&#8212;</html>", EpubNavigationXml.prepare(comment
                + "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Strict//EN' 'https://invalid.example/[unused].dtd'>"
                + "<html>&mdash;</html>"));
    }

    @Test public void rejectsInternalSubsetsIncompleteAndMultipleDeclarations() {
        for (String xml : new String[] {
                "<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///private'>]><html>&x;</html>",
                "<!DOCTYPE html SYSTEM 'unfinished'",
                "<!DOCTYPE html><!DOCTYPE html><html/>"}) {
            assertThrows(IOException.class, () -> EpubNavigationXml.prepare(xml));
        }
    }

    @Test public void doesNotInterpretDoctypeLikeTextInsideAttributeAsDeclaration() throws Exception {
        String malformed = "<p title='<!DOCTYPE html [no]>'>&nbsp;</p>";
        assertEquals("<p title='<!DOCTYPE html [no]>'>&#160;</p>", EpubNavigationXml.prepare(malformed));
        // The XML parser, not this lexical normalization, rejects the raw '<'.
    }
}
