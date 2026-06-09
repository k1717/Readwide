package com.textview.reader;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MainPendingArchiveCreation {
    @NonNull final ArrayList<File> sources;
    @NonNull final File destination;
    @NonNull final File parent;

    MainPendingArchiveCreation(@NonNull List<File> sources,
                               @NonNull File destination,
                               @NonNull File parent) {
        this.sources = new ArrayList<>(sources);
        this.destination = destination;
        this.parent = parent;
    }

    @NonNull
    List<File> immutableSources() {
        return Collections.unmodifiableList(sources);
    }

    @NonNull
    String displayName() {
        return destination.getName();
    }
}
