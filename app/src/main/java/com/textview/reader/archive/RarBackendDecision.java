package com.textview.reader.archive;

import androidx.annotation.NonNull;

/** Immutable routing result for a RAR payload candidate. */
final class RarBackendDecision {
    final RarBackendRoute.Kind route;
    final String reason;
    final boolean firstPartyAllowed;
    final boolean libarchiveOwned;

    private RarBackendDecision(@NonNull RarBackendRoute.Kind route,
                               @NonNull String reason,
                               boolean firstPartyAllowed,
                               boolean libarchiveOwned) {
        this.route = route;
        this.reason = reason;
        this.firstPartyAllowed = firstPartyAllowed;
        this.libarchiveOwned = libarchiveOwned;
    }

    @NonNull
    static RarBackendDecision firstParty(@NonNull RarBackendRoute.Kind route,
                                         @NonNull String reason) {
        return new RarBackendDecision(route, reason, true, false);
    }

    @NonNull
    static RarBackendDecision libarchive(@NonNull String reason) {
        return new RarBackendDecision(RarBackendRoute.Kind.TRY_LIBARCHIVE, reason, false, true);
    }

    @NonNull
    static RarBackendDecision unsupported(@NonNull RarBackendRoute.Kind route,
                                          @NonNull String reason) {
        return new RarBackendDecision(route, reason, false, false);
    }

    boolean is(@NonNull RarBackendRoute.Kind kind) {
        return route == kind;
    }

    @NonNull
    String diagnostic() {
        return route.name() + ": " + reason;
    }

    @Override
    public String toString() {
        return diagnostic();
    }
}
