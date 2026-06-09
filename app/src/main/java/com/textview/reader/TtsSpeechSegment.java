package com.textview.reader;

import androidx.annotation.NonNull;

final class TtsSpeechSegment {
    final int startChar;
    final int endChar;
    final String speechText;

    TtsSpeechSegment(int startChar, int endChar, @NonNull String speechText) {
        this.startChar = Math.max(0, startChar);
        this.endChar = Math.max(this.startChar, endChar);
        this.speechText = speechText;
    }
}
