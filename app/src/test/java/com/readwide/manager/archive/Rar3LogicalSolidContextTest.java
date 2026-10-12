package com.readwide.manager.archive;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Rar3LogicalSolidContextTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void freshPrimerUsesSharedStateAndRequiresFileBoundary() throws Exception {
        RarArchiveReader.RarEntry head = entry(false);
        Rar3SolidState state = new Rar3SolidState();
        try (Rar3PackedPayload payload = payload(head)) {
            Rar3UnpackContext context = Rar3UnpackContext.forLogicalSolidPayload(head, payload, state);
            assertTrue(context.solid);
            assertTrue(context.requiresFileBoundary());
            assertSame(state.oldTableLengths(), context.oldTableLengths());
            assertSame(state.mixedPpmd(), context.mixedPpmd());
            assertSame(state.vmFilterState(), context.vmFilterState());
            assertEquals(head.dataCrc, context.expectedCrc());
        }
    }

    @Test public void continuationKeepsRetainedTablesAndSingleUseLogicalStream() throws Exception {
        RarArchiveReader.RarEntry head = entry(true);
        Rar3SolidState state = new Rar3SolidState();
        state.updateWritePosition(3);
        state.oldTableLengths()[0] = 7;
        state.setReuseClassicTables(true);
        try (Rar3PackedPayload payload = payload(head)) {
            Rar3UnpackContext context = Rar3UnpackContext.forLogicalSolidPayload(head, payload, state);
            context.resetWindow();
            assertEquals(3, context.writePosition());
            assertEquals(7, context.oldTableLengths()[0]);
            assertTrue(context.reuseClassicTables());
            assertSame(payload.input, context.openPackedPayload(null));
            assertThrows(IOException.class, () -> context.openPackedPayload(null));
        }
    }

    @Test public void missingPrimerAndUnresetNewRunAreRejected() throws Exception {
        RarArchiveReader.RarEntry continuation = entry(true);
        try (Rar3PackedPayload payload = payload(continuation)) {
            assertThrows(IOException.class, () -> Rar3UnpackContext.forLogicalSolidPayload(
                    continuation, payload, new Rar3SolidState()));
        }
        RarArchiveReader.RarEntry reset = entry(false);
        Rar3SolidState old = new Rar3SolidState();
        old.updateWritePosition(1);
        try (Rar3PackedPayload payload = payload(reset)) {
            assertThrows(IOException.class, () -> Rar3UnpackContext.forLogicalSolidPayload(reset, payload, old));
        }
    }

    @Test public void invalidatedStateCannotReenterThroughLogicalBridge() throws Exception {
        RarArchiveReader.RarEntry head = entry(true);
        Rar3SolidState state = new Rar3SolidState();
        state.updateWritePosition(1);
        state.invalidate();
        try (Rar3PackedPayload payload = payload(head)) {
            assertThrows(IOException.class, () -> Rar3UnpackContext.forLogicalSolidPayload(head, payload, state));
        }
    }

    @Test public void independentFactoryStillRejectsSolidInput() throws Exception {
        RarArchiveReader.RarEntry head = entry(true);
        try (Rar3PackedPayload payload = payload(head)) {
            assertThrows(IOException.class, () -> Rar3UnpackContext.forLogicalPayload(head, payload));
        }
    }

    private Rar3PackedPayload payload(RarArchiveReader.RarEntry entry) throws Exception {
        return Rar3PackedPayload.open(entry, Collections.singletonList(entry), null, null);
    }

    private RarArchiveReader.RarEntry entry(boolean solid) {
        RarArchiveReader.RarEntry entry = new RarArchiveReader.RarEntry("entry.bin", false,
                8, 16, 0, 4, 0x33, solid, false, false, null, 123, 0, 0);
        entry.sourceArchive = new File(temporary.getRoot(), "unopened.rar");
        return entry;
    }
}
