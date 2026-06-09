package com.textview.reader.archive;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Aggregates per-entry RAR backend decisions for fixture reports and diagnostics. */
final class RarBackendRouteSummary {
    private final EnumMap<RarBackendRoute.Kind, Integer> routeCounts;
    final int routedEntryCount;
    final int firstPartyAllowedCount;
    final int libarchiveOwnedCount;
    final int cleanUnsupportedCount;

    private RarBackendRouteSummary(@NonNull EnumMap<RarBackendRoute.Kind, Integer> routeCounts,
                                   int routedEntryCount,
                                   int firstPartyAllowedCount,
                                   int libarchiveOwnedCount,
                                   int cleanUnsupportedCount) {
        this.routeCounts = new EnumMap<>(routeCounts);
        this.routedEntryCount = routedEntryCount;
        this.firstPartyAllowedCount = firstPartyAllowedCount;
        this.libarchiveOwnedCount = libarchiveOwnedCount;
        this.cleanUnsupportedCount = cleanUnsupportedCount;
    }

    @NonNull
    static RarBackendRouteSummary empty() {
        return new RarBackendRouteSummary(new EnumMap<RarBackendRoute.Kind, Integer>(RarBackendRoute.Kind.class),
                0,
                0,
                0,
                0);
    }

    @NonNull
    static RarBackendRouteSummary fromEntries(@NonNull List<RarArchiveReader.RarEntry> entries) {
        EnumMap<RarBackendRoute.Kind, Integer> counts = new EnumMap<>(RarBackendRoute.Kind.class);
        int routed = 0;
        int firstParty = 0;
        int libarchive = 0;
        int unsupported = 0;
        for (RarArchiveReader.RarEntry entry : entries) {
            if (entry == null) continue;
            RarBackendDecision decision = RarBackendRouter.decideEntry(entry);
            increment(counts, decision.route);
            routed++;
            if (decision.firstPartyAllowed) firstParty++;
            if (decision.libarchiveOwned) libarchive++;
            if (!decision.firstPartyAllowed && !decision.libarchiveOwned) unsupported++;
        }
        return new RarBackendRouteSummary(counts, routed, firstParty, libarchive, unsupported);
    }

    @NonNull
    Map<RarBackendRoute.Kind, Integer> routeCounts() {
        return Collections.unmodifiableMap(routeCounts);
    }

    int count(@NonNull RarBackendRoute.Kind route) {
        Integer count = routeCounts.get(route);
        return count == null ? 0 : count;
    }

    boolean contains(@NonNull RarBackendRoute.Kind route) {
        return count(route) > 0;
    }

    @NonNull
    String routesLabel() {
        if (routeCounts.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        for (RarBackendRoute.Kind route : RarBackendRoute.Kind.values()) {
            Integer count = routeCounts.get(route);
            if (count == null || count <= 0) continue;
            parts.add(count == 1 ? route.name() : route.name() + " x" + count);
        }
        return join(parts, ", ");
    }

    @NonNull
    String countsLabel() {
        if (routedEntryCount <= 0) return "-";
        return "first-party=" + firstPartyAllowedCount
                + ", libarchive-owned=" + libarchiveOwnedCount
                + ", clean-unsupported=" + cleanUnsupportedCount;
    }

    @NonNull
    String toMarkdownLine() {
        if (routedEntryCount <= 0) return "-";
        return routesLabel() + " (" + countsLabel() + ")";
    }

    @NonNull
    static String routeCountsLabel(@NonNull List<RarBackendRouteSummary> summaries) {
        EnumMap<RarBackendRoute.Kind, Integer> total = new EnumMap<>(RarBackendRoute.Kind.class);
        for (RarBackendRouteSummary summary : summaries) {
            if (summary == null) continue;
            for (Map.Entry<RarBackendRoute.Kind, Integer> entry : summary.routeCounts.entrySet()) {
                increment(total, entry.getKey(), entry.getValue());
            }
        }
        if (total.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        for (RarBackendRoute.Kind route : RarBackendRoute.Kind.values()) {
            Integer count = total.get(route);
            if (count == null || count <= 0) continue;
            parts.add(route.name() + "=" + count);
        }
        return join(parts, ", ");
    }

    private static void increment(@NonNull EnumMap<RarBackendRoute.Kind, Integer> counts,
                                  @NonNull RarBackendRoute.Kind route) {
        increment(counts, route, 1);
    }

    private static void increment(@NonNull EnumMap<RarBackendRoute.Kind, Integer> counts,
                                  @NonNull RarBackendRoute.Kind route,
                                  int amount) {
        Integer current = counts.get(route);
        counts.put(route, (current == null ? 0 : current) + amount);
    }

    @NonNull
    private static String join(@NonNull List<String> parts, @NonNull String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
