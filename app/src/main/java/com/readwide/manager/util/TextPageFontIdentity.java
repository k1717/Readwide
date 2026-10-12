package com.readwide.manager.util;

import android.graphics.Typeface;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.UUID;

/** Cache identity of the font actually measured, not the current bytes at a font path. */
public final class TextPageFontIdentity {
    private static final String SESSION = UUID.randomUUID().toString();
    private static final ArrayList<FaceToken> FACES = new ArrayList<>();
    private static final String[] READER_SYSTEM_FAMILIES = {
            "sans-serif", "sans-serif-medium", "sans-serif-condensed", "sans-serif-light"
    };
    private static long nextToken;

    private TextPageFontIdentity() {}

    public static synchronized String key(Typeface face) {
        if (face == null || Typeface.DEFAULT.equals(face)) return "builtin:default";
        if (Typeface.SERIF.equals(face)) return "builtin:serif";
        if (Typeface.MONOSPACE.equals(face)) return "builtin:monospace";
        // These are actual system faces offered by ReaderFontDialogController,
        // not imported files with similar names. Their environment is also part
        // of the disk key. Do not give them a fresh UUID after every process restart.
        for (String family : READER_SYSTEM_FAMILIES) {
            if (face.equals(Typeface.create(family, Typeface.NORMAL))) return "builtin:family:" + family;
        }
        // FontManager may still hold the old Typeface after its file is replaced.
        // A file hash would identify the new bytes, not that already-loaded face.
        // Non-built-in fonts therefore reuse indexes only within this process and
        // for this exact object. Do not pin a font in memory just to retain its ID.
        FaceToken match = null;
        for (int i = FACES.size() - 1; i >= 0; i--) {
            FaceToken token = FACES.get(i);
            Typeface existing = token.face.get();
            if (existing == null) FACES.remove(i);
            else if (existing == face) match = token;
        }
        if (match == null) {
            match = new FaceToken(face, ++nextToken);
            FACES.add(match);
        }
        return "session:" + SESSION + ":face:" + match.id;
    }

    private static final class FaceToken {
        final WeakReference<Typeface> face;
        final long id;

        FaceToken(Typeface face, long id) {
            this.face = new WeakReference<>(face);
            this.id = id;
        }
    }
}
