package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight diagnostic trace for the narrow RAR3/RAR4 classic-LZ first-party path.
 *
 * <p>The trace is collected only by diagnostic/probe runs. It does not change extraction routing
 * and must not be used as a user-facing support claim for compressed-solid RAR.</p>
 */
final class Rar3ClassicLzStateTrace {
    private static final int MAX_BLOCKS = 64;
    private static final int MAX_MATCH_SAMPLES = 32;

    private final List<BlockTrace> blocks = new ArrayList<>();
    private final List<String> matchSamples = new ArrayList<>();
    private BlockTrace current;
    private int literalCount;
    private int longMatchCount;
    private int shortMatchCount;
    private int oldDistanceMatchCount;
    private int repeatLastMatchCount;
    private int highDistanceSlotCount;
    private int lowDistanceDecodeCount;
    private int lowDistanceRepeatUseCount;
    private int keepOldTableBlocks;
    private int resetTableBlocks;
    private int vmFilterCount;
    private int endBlockCount;
    private int maxDistance;
    private int maxLength;
    private int reusedTableBlocks;
    private int changedTableBlocks;
    private int previousTableFingerprint = Integer.MIN_VALUE;
    private int suspiciousTransitionCount;
    private String failureDetail = "";

    void beginBlock(int blockNumber, @NonNull int[] oldTableLengths, long bitsBefore, long writtenBefore) {
        current = new BlockTrace(blockNumber, bitsBefore, writtenBefore, fingerprint(oldTableLengths));
    }

    void afterTables(@NonNull Rar3HuffmanTables tables, long bitsAfterTables) {
        if (current == null) return;
        int tableFingerprint = fingerprint(tables.tableLengths);
        current.bitsAfterTables = bitsAfterTables;
        current.tableFingerprint = tableFingerprint;
        current.mainSymbolCount = countNonZero(tables.tableLengths, 0, Rar3HuffmanTables.NC);
        current.distanceSymbolCount = countNonZero(tables.tableLengths,
                Rar3HuffmanTables.NC,
                Rar3HuffmanTables.NC + Rar3HuffmanTables.DC);
        current.lowDistanceSymbolCount = countNonZero(tables.tableLengths,
                Rar3HuffmanTables.NC + Rar3HuffmanTables.DC,
                Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC);
        current.repeatSymbolCount = countNonZero(tables.tableLengths,
                Rar3HuffmanTables.NC + Rar3HuffmanTables.DC + Rar3HuffmanTables.LDC,
                tables.tableLengths.length);
        current.keepOldTable = tables.keepOldTable;
        if (tables.keepOldTable) {
            keepOldTableBlocks++;
        } else {
            resetTableBlocks++;
        }
        if (previousTableFingerprint == Integer.MIN_VALUE) {
            current.tableRelation = "first";
        } else if (previousTableFingerprint == tableFingerprint) {
            current.tableRelation = "same-as-previous";
            reusedTableBlocks++;
        } else {
            current.tableRelation = "changed";
            changedTableBlocks++;
        }
        previousTableFingerprint = tableFingerprint;
    }

    void recordLiteral() {
        literalCount++;
        if (current != null) current.literalCount++;
    }

    void recordMatch(@NonNull Rar3DecodeAction.Type actionType,
                     int distance,
                     int decodedLength,
                     int emittedLength,
                     int oldDistanceSlot,
                     long writtenBefore,
                     long writtenAfter,
                     int beforeOld0,
                     int beforeOld1,
                     int beforeOld2,
                     int beforeOld3,
                     int beforeLastDistance,
                     int beforeLastLength,
                     int old0,
                     int old1,
                     int old2,
                     int old3,
                     int lastDistance,
                     int lastLength,
                     int lengthSlot,
                     int distanceSlot,
                     int distanceExtraBits,
                     int lowDistance,
                     boolean lowDistanceRepeated,
                     @NonNull Rar3ClassicLzStateTransitionCheck.Result transition) {
        switch (actionType) {
            case LONG_MATCH:
                longMatchCount++;
                if (current != null) current.longMatchCount++;
                break;
            case SHORT_DISTANCE_MATCH:
                shortMatchCount++;
                if (current != null) current.shortMatchCount++;
                break;
            case OLD_DISTANCE_MATCH:
                oldDistanceMatchCount++;
                if (current != null) current.oldDistanceMatchCount++;
                break;
            case REPEAT_LAST_MATCH:
                repeatLastMatchCount++;
                if (current != null) current.repeatLastMatchCount++;
                break;
            default:
                break;
        }
        if (distance > maxDistance) maxDistance = distance;
        if (decodedLength > maxLength) maxLength = decodedLength;
        if (distanceSlot > 9) {
            highDistanceSlotCount++;
            if (current != null) current.highDistanceSlotCount++;
        }
        if (lowDistance >= 0) {
            lowDistanceDecodeCount++;
            if (current != null) current.lowDistanceDecodeCount++;
        }
        if (lowDistanceRepeated) {
            lowDistanceRepeatUseCount++;
            if (current != null) current.lowDistanceRepeatUseCount++;
        }
        if (!transition.ok) {
            suspiciousTransitionCount++;
            if (current != null) current.suspiciousTransitionCount++;
        }
        if (matchSamples.size() < MAX_MATCH_SAMPLES) {
            matchSamples.add(actionType
                    + " dist=" + distance
                    + " decodedLen=" + decodedLength
                    + " emittedLen=" + emittedLength
                    + " oldSlot=" + oldDistanceSlot
                    + " lenSlot=" + lengthSlot
                    + " distSlot=" + distanceSlot
                    + " distBits=" + distanceExtraBits
                    + " low=" + lowDistance
                    + (lowDistanceRepeated ? " lowRepeat" : "")
                    + " wrote=" + (writtenAfter - writtenBefore)
                    + " oldBefore=[" + beforeOld0 + ',' + beforeOld1 + ',' + beforeOld2 + ',' + beforeOld3 + ']'
                    + " lastBefore=" + beforeLastDistance + '/' + beforeLastLength
                    + " oldAfter=[" + old0 + ',' + old1 + ',' + old2 + ',' + old3 + ']'
                    + " lastAfter=" + lastDistance + '/' + lastLength
                    + (transition.ok ? "" : " transition=" + transition.detail));
        }
    }

    void recordEndBlock(long bitsAfter, long writtenAfter) {
        endBlockCount++;
        if (current != null) {
            current.ended = true;
            current.bitsAfter = bitsAfter;
            current.writtenAfter = writtenAfter;
            finishCurrent();
        }
    }

    void recordVmFilter() {
        vmFilterCount++;
        if (current != null) current.vmFilterCount++;
    }

    void recordBlockLimitReached(long bitsAfter, long writtenAfter) {
        if (current != null) {
            current.limitReachedWithoutEndBlock = true;
            current.bitsAfter = bitsAfter;
            current.writtenAfter = writtenAfter;
            finishCurrent();
        }
    }

    void recordFailure(@Nullable String detail, long bitsAfter, long writtenAfter) {
        failureDetail = detail == null ? "" : detail;
        if (current != null) {
            current.failureDetail = failureDetail;
            current.bitsAfter = bitsAfter;
            current.writtenAfter = writtenAfter;
            finishCurrent();
        }
    }

    @NonNull
    Snapshot snapshot() {
        if (current != null && !blocks.contains(current)) finishCurrent();
        return new Snapshot(blocks,
                matchSamples,
                literalCount,
                longMatchCount,
                shortMatchCount,
                oldDistanceMatchCount,
                repeatLastMatchCount,
                vmFilterCount,
                endBlockCount,
                maxDistance,
                maxLength,
                highDistanceSlotCount,
                lowDistanceDecodeCount,
                lowDistanceRepeatUseCount,
                keepOldTableBlocks,
                resetTableBlocks,
                reusedTableBlocks,
                changedTableBlocks,
                suspiciousTransitionCount,
                failureDetail);
    }

    private void finishCurrent() {
        if (current == null) return;
        if (!blocks.contains(current) && blocks.size() < MAX_BLOCKS) blocks.add(current);
        current = null;
    }

    private static int countNonZero(@NonNull int[] values, int start, int end) {
        int count = 0;
        int safeEnd = Math.min(values.length, end);
        for (int i = Math.max(0, start); i < safeEnd; i++) {
            if (values[i] != 0) count++;
        }
        return count;
    }

    private static int fingerprint(@NonNull int[] values) {
        int result = 1;
        for (int value : values) result = 31 * result + value;
        return result;
    }

    static final class BlockTrace {
        final int blockNumber;
        final long bitsBefore;
        long bitsAfterTables = -1L;
        long bitsAfter = -1L;
        final long writtenBefore;
        long writtenAfter = -1L;
        final int oldTableFingerprint;
        int tableFingerprint;
        int mainSymbolCount;
        int distanceSymbolCount;
        int lowDistanceSymbolCount;
        int repeatSymbolCount;
        int literalCount;
        int longMatchCount;
        int shortMatchCount;
        int oldDistanceMatchCount;
        int repeatLastMatchCount;
        int highDistanceSlotCount;
        int lowDistanceDecodeCount;
        int lowDistanceRepeatUseCount;
        boolean keepOldTable;
        int vmFilterCount;
        int suspiciousTransitionCount;
        boolean ended;
        boolean limitReachedWithoutEndBlock;
        String tableRelation = "unknown";
        String failureDetail = "";

        BlockTrace(int blockNumber, long bitsBefore, long writtenBefore, int oldTableFingerprint) {
            this.blockNumber = blockNumber;
            this.bitsBefore = bitsBefore;
            this.writtenBefore = writtenBefore;
            this.oldTableFingerprint = oldTableFingerprint;
        }

        @NonNull
        String markdownRow() {
            return "| " + blockNumber
                    + " | " + tableRelation
                    + " | " + hex(tableFingerprint)
                    + " | " + mainSymbolCount
                    + " | " + distanceSymbolCount
                    + " | " + lowDistanceSymbolCount
                    + " | " + repeatSymbolCount
                    + " | " + literalCount
                    + " | " + longMatchCount
                    + " | " + shortMatchCount
                    + " | " + oldDistanceMatchCount
                    + " | " + repeatLastMatchCount
                    + " | " + highDistanceSlotCount
                    + " | " + lowDistanceDecodeCount
                    + " | " + lowDistanceRepeatUseCount
                    + " | " + keepOldTable
                    + " | " + vmFilterCount
                    + " | " + suspiciousTransitionCount
                    + " | " + bitsBefore
                    + " | " + bitsAfterTables
                    + " | " + bitsAfter
                    + " | " + writtenBefore
                    + " | " + writtenAfter
                    + " | " + ended
                    + " | " + limitReachedWithoutEndBlock
                    + " | " + escape(failureDetail)
                    + " |";
        }
    }

    static final class Snapshot {
        @NonNull private final List<BlockTrace> blocks;
        @NonNull private final List<String> matchSamples;
        final int literalCount;
        final int longMatchCount;
        final int shortMatchCount;
        final int oldDistanceMatchCount;
        final int repeatLastMatchCount;
        final int vmFilterCount;
        final int endBlockCount;
        final int maxDistance;
        final int maxLength;
        final int highDistanceSlotCount;
        final int lowDistanceDecodeCount;
        final int lowDistanceRepeatUseCount;
        final int keepOldTableBlocks;
        final int resetTableBlocks;
        final int reusedTableBlocks;
        final int changedTableBlocks;
        final int suspiciousTransitionCount;
        @NonNull final String failureDetail;

        Snapshot(@NonNull List<BlockTrace> blocks,
                 @NonNull List<String> matchSamples,
                 int literalCount,
                 int longMatchCount,
                 int shortMatchCount,
                 int oldDistanceMatchCount,
                 int repeatLastMatchCount,
                 int vmFilterCount,
                 int endBlockCount,
                 int maxDistance,
                 int maxLength,
                 int highDistanceSlotCount,
                 int lowDistanceDecodeCount,
                 int lowDistanceRepeatUseCount,
                 int keepOldTableBlocks,
                 int resetTableBlocks,
                 int reusedTableBlocks,
                 int changedTableBlocks,
                 int suspiciousTransitionCount,
                 @NonNull String failureDetail) {
            this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
            this.matchSamples = Collections.unmodifiableList(new ArrayList<>(matchSamples));
            this.literalCount = literalCount;
            this.longMatchCount = longMatchCount;
            this.shortMatchCount = shortMatchCount;
            this.oldDistanceMatchCount = oldDistanceMatchCount;
            this.repeatLastMatchCount = repeatLastMatchCount;
            this.vmFilterCount = vmFilterCount;
            this.endBlockCount = endBlockCount;
            this.maxDistance = maxDistance;
            this.maxLength = maxLength;
            this.highDistanceSlotCount = highDistanceSlotCount;
            this.lowDistanceDecodeCount = lowDistanceDecodeCount;
            this.lowDistanceRepeatUseCount = lowDistanceRepeatUseCount;
            this.keepOldTableBlocks = keepOldTableBlocks;
            this.resetTableBlocks = resetTableBlocks;
            this.reusedTableBlocks = reusedTableBlocks;
            this.changedTableBlocks = changedTableBlocks;
            this.suspiciousTransitionCount = suspiciousTransitionCount;
            this.failureDetail = failureDetail;
        }

        @NonNull
        List<BlockTrace> blocks() {
            return blocks;
        }

        @NonNull
        String compact() {
            return "classicLzTrace blocks=" + blocks.size()
                    + "; literals=" + literalCount
                    + "; long=" + longMatchCount
                    + "; short=" + shortMatchCount
                    + "; old=" + oldDistanceMatchCount
                    + "; repeatLast=" + repeatLastMatchCount
                    + "; vm=" + vmFilterCount
                    + "; maxDist=" + maxDistance
                    + "; maxLen=" + maxLength
                    + "; highDistSlots=" + highDistanceSlotCount
                    + "; lowDist=" + lowDistanceDecodeCount
                    + "; lowDistRepeat=" + lowDistanceRepeatUseCount
                    + "; tableKeep=" + keepOldTableBlocks
                    + "; tableReset=" + resetTableBlocks
                    + "; tableSame=" + reusedTableBlocks
                    + "; tableChanged=" + changedTableBlocks
                    + "; transitionSuspect=" + suspiciousTransitionCount
                    + (failureDetail.length() == 0 ? "" : "; failure=" + failureDetail);
        }

        @NonNull
        String markdownStatus() {
            return "blocks=" + blocks.size()
                    + ", long=" + longMatchCount
                    + ", short=" + shortMatchCount
                    + ", old=" + oldDistanceMatchCount
                    + ", repeat=" + repeatLastMatchCount
                    + ", maxDist=" + maxDistance
                    + ", maxLen=" + maxLength
                    + ", highDistSlots=" + highDistanceSlotCount
                    + ", lowDist=" + lowDistanceDecodeCount
                    + ", lowDistRepeat=" + lowDistanceRepeatUseCount
                    + ", tableKeep=" + keepOldTableBlocks
                    + ", tableReset=" + resetTableBlocks
                    + ", tableSame=" + reusedTableBlocks
                    + ", tableChanged=" + changedTableBlocks
                    + ", transitionSuspect=" + suspiciousTransitionCount;
        }

        @NonNull
        String matchesMarkdown() {
            if (matchSamples.isEmpty()) return "-";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < matchSamples.size(); i++) {
                if (i > 0) sb.append("<br>");
                sb.append(escape(matchSamples.get(i)));
            }
            return sb.toString();
        }

        @NonNull
        String blocksMarkdown() {
            if (blocks.isEmpty()) return "-";
            StringBuilder sb = new StringBuilder();
            sb.append("| Block | Table relation | Table fp | Main | Dist | LowDist | Repeat | Literal | Long | Short | Old | RepeatLast | HighDist | LowDistUse | LowDistRepeat | Keep old | VM | Transition suspect | Bits before | Bits after tables | Bits after | Written before | Written after | Ended | Limit stop | Detail |\n");
            sb.append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
            for (BlockTrace block : blocks) sb.append(block.markdownRow()).append('\n');
            return sb.toString();
        }
    }

    @NonNull
    private static String hex(int value) {
        return String.format(Locale.US, "0x%08x", value);
    }

    @NonNull
    private static String escape(@Nullable String text) {
        if (text == null || text.length() == 0) return "-";
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
