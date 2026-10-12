package com.readwide.manager.document.doc;

import com.readwide.manager.document.render.ParagraphStyle;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class DocParagraphPropertiesTest {
    @Test public void spacingIsUnsignedAndExplicitZeroIndentsOverrideEarlierValues() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        word(bytes, 0); // PAPX style index
        for (int opcode : new int[] {0x845E, 0x845D, 0x8460}) {
            word(bytes, opcode); word(bytes, 720);
            word(bytes, opcode); word(bytes, 0);
        }
        word(bytes, 0xA413); word(bytes, 240);
        word(bytes, 0xA414); word(bytes, 40000);
        ParagraphStyle style = decode(bytes.toByteArray()).toParagraphStyle();
        assertEquals(0f, style.marginLeftPt, 0f);
        assertEquals(0f, style.marginRightPt, 0f);
        assertEquals(0f, style.textIndentPt, 0f);
        assertEquals(12f, style.marginTopPt, 0f);
        assertEquals(2000f, style.marginBottomPt, 0f);
    }

    @Test public void absentSpacingStaysUnsetAndZeroSpacingIsPreserved() throws Exception {
        assertNull(decode(new byte[] {0, 0}).toParagraphStyle().marginBottomPt);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        word(bytes, 0);
        word(bytes, 0xA414); word(bytes, 240);
        word(bytes, 0xA414); word(bytes, 0);
        assertEquals(0f, decode(bytes.toByteArray()).toParagraphStyle().marginBottomPt, 0f);
        // A truncated final operand must not consume bytes outside the PAPX.
        bytes.write(0x13); bytes.write(0xA4); bytes.write(1);
        ParagraphStyle style = decode(bytes.toByteArray()).toParagraphStyle();
        assertNull(style.marginTopPt);
        assertEquals(0f, style.marginBottomPt, 0f);
    }

    private static DocParagraphProperties.ParaStyle decode(byte[] bytes) throws Exception {
        Method method = DocParagraphProperties.class.getDeclaredMethod("decodePapx", byte[].class, int.class, int.class);
        method.setAccessible(true);
        return (DocParagraphProperties.ParaStyle) method.invoke(null, bytes, 0, bytes.length);
    }

    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
}
