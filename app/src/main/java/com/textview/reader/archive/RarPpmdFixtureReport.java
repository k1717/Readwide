package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

/** Summarizes visible RAR3/RAR4 compressed-block flags for PPMd decoder work. */
final class RarPpmdFixtureReport {
    private final int compressedRar3Or4;
    private final int ppmd;
    private final int classicLz;
    private final int unknown;
    @NonNull private final String firstDiagnostic;

    private RarPpmdFixtureReport(int compressedRar3Or4,
                                 int ppmd,
                                 int classicLz,
                                 int unknown,
                                 @NonNull String firstDiagnostic) {
        this.compressedRar3Or4 = compressedRar3Or4;
        this.ppmd = ppmd;
        this.classicLz = classicLz;
        this.unknown = unknown;
        this.firstDiagnostic = firstDiagnostic;
    }

    @NonNull
    static RarPpmdFixtureReport empty() {
        return fromEntries(Collections.<RarArchiveReader.RarEntry>emptyList());
    }

    @NonNull
    static RarPpmdFixtureReport fromEntries(@NonNull List<RarArchiveReader.RarEntry> entries) {
        int compressed = 0;
        int ppmd = 0;
        int classic = 0;
        int unknown = 0;
        String first = "no RAR3/RAR4 compressed payloads";
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null || entry.directory || entry.rarVersion >= 5) continue;
            if (entry.method == 0 || entry.method == 0x30) continue;
            compressed++;
            Rar3PpmdBlockProbe.Result probe = Rar3PpmdBlockProbe.probe(entry);
            if (compressed == 1) first = probe.diagnostic();
            if (probe.isPpmd()) ppmd++;
            else if (probe.isClassicLz()) classic++;
            else unknown++;
        }
        return new RarPpmdFixtureReport(compressed, ppmd, classic, unknown, first);
    }

    int compressedRar3Or4Count() { return compressedRar3Or4; }
    int ppmdCount() { return ppmd; }
    int classicLzCount() { return classicLz; }
    int unknownCount() { return unknown; }

    @NonNull
    String oneLineSummary() {
        if (compressedRar3Or4 == 0) return "no RAR3/RAR4 compressed payloads";
        return "compressed=" + compressedRar3Or4
                + ", ppmd=" + ppmd
                + ", classicLz=" + classicLz
                + ", unknown=" + unknown
                + ", first=" + firstDiagnostic;
    }

    @NonNull
    static String countsLabel(@NonNull List<RarPpmdFixtureReport> reports) {
        int compressed = 0;
        int ppmd = 0;
        int classic = 0;
        int unknown = 0;
        for (RarPpmdFixtureReport report : reports) {
            compressed += report.compressedRar3Or4;
            ppmd += report.ppmd;
            classic += report.classicLz;
            unknown += report.unknown;
        }
        return "compressed=" + compressed
                + ", ppmd=" + ppmd
                + ", classicLz=" + classic
                + ", unknown=" + unknown;
    }
}
