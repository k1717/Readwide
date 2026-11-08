package com.readwide.manager;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import androidx.core.os.BundleCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.readwide.manager.model.SafDocumentEntry;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class SafDocumentOperationsInstrumentedTest {
    private static final String AUTHORITY = "readwide.operations.fixture";
    private static final Uri SOURCE_TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "source-root");
    private static final Uri TARGET_TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "target-root");
    private static final Uri SOURCE_PARENT = document(SOURCE_TREE, "source-root");
    private static final Uri TARGET_PARENT = document(TARGET_TREE, "target-root");
    private File root;
    private Provider provider;
    private ContentResolver resolver;

    @Before public void setUp() throws Exception {
        root = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "saf-operation-fixture-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        provider = new Provider(root);
        resolver = ContentResolver.wrap(provider);
        provider.addDirectory("source-root", null, "Source");
        provider.addDirectory("target-root", null, "Target");
    }

    @After public void tearDown() {
        Thread.interrupted();
        if (root != null) removeFixture(root);
    }

    @Test public void crossTreeCopyPreservesSourceAndExactBytes() throws Exception {
        provider.addFile("book", "source-root", "책.txt", "original bytes");
        assertEquals(SafDocumentOperations.Result.DONE, transfer("book", false));
        assertEquals("original bytes", provider.text("book"));
        assertEquals("original bytes", provider.text(provider.child("target-root", "책.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void crossTreeMoveDeletesOnlyAfterVerifiedDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "original bytes");
        assertEquals(SafDocumentOperations.Result.DONE, transfer("book", true));
        assertNull(provider.nodes.get("book"));
        assertEquals("original bytes", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(1, provider.sourceDeletes);
    }

    @Test public void conflictNeverOverwritesExistingTarget() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addFile("existing", "target-root", "BOOK.txt", "existing");
        assertEquals(SafDocumentOperations.Result.CONFLICT, transfer("book", true));
        assertEquals("existing", provider.text("existing"));
        assertEquals("source", provider.text("book"));
        assertEquals(0, provider.creates);
    }

    @Test public void changedSelectionIsRejectedBeforeCreatingDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        SafDocumentEntry selected = selected("book");
        provider.nodes.get("book").modified++;
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer(selected, false));
        assertEquals(0, provider.creates);
    }

    @Test public void truncatedSourceKeepsSourceAndReportsUnverifiablePartialTarget() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "short");
        provider.nodes.get("book").declaredSize = 100L;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("book", true));
        assertEquals("short", provider.text("book"));
        assertNotNull(provider.child("target-root", "book.txt"));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void sourceDeleteFailureRetainsVerifiedDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "original bytes");
        provider.failSourceDelete = true;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("original bytes", provider.text("book"));
        assertEquals("original bytes", provider.text(provider.child("target-root", "book.txt").id));
    }

    @Test public void fallbackFolderMoveKeepsWholeSourceAfterVerifiedCopy() throws Exception {
        provider.addDirectory("folder", "source-root", "Folder");
        provider.addFile("book", "folder", "book.txt", "source");
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("folder", true));
        Node copiedFolder = provider.child("target-root", "Folder");
        assertNotNull(copiedFolder);
        assertEquals("source", provider.text(provider.child(copiedFolder.id, "book.txt").id));
        assertEquals("source", provider.text("book"));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void fallbackMoveOfMultiParentDocumentNeverDeletesGlobalSource() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void sourceMovedAfterSelectionCannotStartCopyOrMove() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addDirectory("elsewhere", "source-root", "Elsewhere");
        SafDocumentEntry entry = selected("book");
        provider.nodes.get("book").parent = "elsewhere";
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer(entry, false));
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer(entry, true));
        assertEquals("source", provider.text("book"));
        assertEquals(0, provider.creates);
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void sourceRelocatedDuringCollisionCheckCannotCreateDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addDirectory("elsewhere", "source-root", "Elsewhere");
        provider.relocateSourceAtCollisionCheck = true;
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer("book", true));
        assertNotNull(provider.child("elsewhere", "book.txt"));
        assertEquals(0, provider.creates);
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void nativeMoveRechecksSourceParentAfterCollisionCheck() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addDirectory("elsewhere", "source-root", "Elsewhere");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        provider.relocateSourceAtCollisionCheck = true;
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer("book", true));
        assertNotNull(provider.child("elsewhere", "book.txt"));
        assertEquals(0, provider.nativeMoves);
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void nativeMoveRejectsPartialSourceAfterCollisionCheck() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        provider.sourceFlagsAtCollisionCheck = DocumentsContract.Document.FLAG_PARTIAL;
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer("book", true));
        assertNotNull(provider.child("source-root", "book.txt"));
        assertEquals(0, provider.nativeMoves);
        assertEquals(0, provider.creates);
    }

    @Test public void nativeMoveRechecksParentAfterItsSourceMetadataRead() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addDirectory("elsewhere", "source-root", "Elsewhere");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        provider.relocateSourceAtReadyQuery = true;
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer("book", true));
        assertNotNull(provider.child("elsewhere", "book.txt"));
        assertEquals(0, provider.nativeMoves);
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void sourceParentWithWrongTreeScopeCannotCreateDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        assertEquals(SafDocumentOperations.Result.FAILED,
                SafDocumentOperations.transfer(resolver, SOURCE_TREE, document(TARGET_TREE, "source-root"),
                        selected("book"), TARGET_TREE, TARGET_PARENT, true));
        assertEquals(0, provider.creates);
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void removeCapabilityAddedAfterSelectionKeepsGlobalSource() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        SafDocumentEntry entry = selected("book");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer(entry, true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void removeCapabilityAddedAfterCopyKeepsBothFiles() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.sourceFlagsAfterCopy = DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void partialSourceAfterCopyKeepsBothFiles() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.sourceFlagsAfterCopy = DocumentsContract.Document.FLAG_PARTIAL;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void virtualSourceAfterCopyKeepsBothFiles() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.sourceFlagsAfterCopy = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void sourceRelocatedAfterCopyKeepsOriginalAndVerifiedDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addDirectory("elsewhere", "source-root", "Elsewhere");
        provider.relocateSourceAfterCopy = true;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text(provider.child("elsewhere", "book.txt").id));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void sourceRelocatedDuringFinalDestinationCheckIsNotDeleted() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addDirectory("elsewhere", "source-root", "Elsewhere");
        provider.relocateSourceAtFinalTargetQuery = true;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text(provider.child("elsewhere", "book.txt").id));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void incompleteSourceParentAfterCopyKeepsOriginalAndDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.incompleteSourceParentAfterCopy = true;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void removeCapabilityInFinalDeletionQueryKeepsOriginalAndDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.sourceFlagsAtDeletionQuery = DocumentsContract.Document.FLAG_SUPPORTS_REMOVE;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void partialFlagInFinalDeletionQueryKeepsOriginalAndDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.sourceFlagsAtDeletionQuery = DocumentsContract.Document.FLAG_PARTIAL;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void virtualFlagInFinalDeletionQueryKeepsOriginalAndDestination() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.sourceFlagsAtDeletionQuery = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void nativeMoveDoesNotRequireGlobalDeleteCapability() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        Node source = provider.nodes.get("book");
        source.flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        source.flags &= ~DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
        assertEquals(SafDocumentOperations.Result.DONE, transfer("book", true));
        assertNull(provider.child("source-root", "book.txt"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(1, provider.nativeMoves);
        assertEquals(0, provider.sourceDeletes);
        assertEquals(0, provider.creates);
    }

    @Test public void nativeMoveCannotReportSuccessWhenOldParentStillContainsSource() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        provider.copyInsteadOfNativeMove = true;
        assertEquals(SafDocumentOperations.Result.SOURCE_REMAINS, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void newSourceMemberPreventsFolderMoveCleanup() throws Exception {
        provider.addDirectory("folder", "source-root", "Folder");
        provider.addFile("book", "folder", "book.txt", "source");
        provider.addSourceMemberOnWrite = true;
        assertEquals(SafDocumentOperations.Result.FAILED, transfer("folder", true));
        assertNotNull(provider.nodes.get("folder"));
        assertNotNull(provider.nodes.get("late-source"));
        assertEquals(0, provider.sourceDeletes);
        assertNull(provider.child("target-root", "Folder"));
    }

    @Test public void cancellationCleansOwnedDestinationAndPreservesInterrupt() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.interruptOnWrite = true;
        assertEquals(SafDocumentOperations.Result.FAILED, transfer("book", true));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        assertEquals("source", provider.text("book"));
        assertNull(provider.child("target-root", "book.txt"));
    }

    @Test public void mismatchedSourceTreeUriCannotMutateEitherTree() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        SafDocumentEntry wrong = provider.entry(provider.nodes.get("book"), TARGET_TREE);
        assertEquals(SafDocumentOperations.Result.FAILED, transfer(wrong, true));
        assertEquals(0, provider.creates);
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void mismatchedDestinationScopeCannotCreate() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        assertEquals(SafDocumentOperations.Result.FAILED,
                SafDocumentOperations.transfer(resolver, SOURCE_TREE, SOURCE_PARENT, selected("book"),
                        TARGET_TREE, document(SOURCE_TREE, "target-root"), false));
        assertEquals(0, provider.creates);
    }

    @Test public void partialDocumentIsNotCopiedOrMoved() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "unfinished");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_PARTIAL;
        assertEquals(SafDocumentOperations.Result.FAILED, transfer("book", true));
        assertEquals(0, provider.creates);
    }

    @Test public void loadingChildListCannotBeAcceptedAsCompleteInventory() throws Exception {
        provider.addDirectory("folder", "source-root", "Folder");
        provider.addFile("book", "folder", "book.txt", "source");
        provider.loadingChildren = "folder";
        assertEquals(SafDocumentOperations.Result.FAILED, transfer("folder", true));
        assertEquals(0, provider.creates);
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void foreignDestinationMemberIsNeverDeletedDuringFailureCleanup() throws Exception {
        provider.addDirectory("folder", "source-root", "Folder");
        provider.addFile("book", "folder", "book.txt", "source");
        provider.addForeignTargetMemberAndFailWrite = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("folder", true));
        assertNotNull(provider.nodes.get("foreign-target"));
        assertEquals("another client", provider.text("foreign-target"));
        assertNotNull(provider.nodes.get("folder"));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void invalidNamesNeverCreateFolders() throws Exception {
        assertEquals(SafDocumentOperations.Result.FAILED,
                SafDocumentOperations.createFolder(resolver, TARGET_TREE, TARGET_PARENT, "../outside"));
        assertEquals(0, provider.creates);
    }

    @Test public void createdFolderWithFailedConfirmationIsUnconfirmed() throws Exception {
        provider.failQueryAfterMutation = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.createFolder(resolver, TARGET_TREE, TARGET_PARENT, "Created"));
        assertNotNull(provider.child("target-root", "Created"));
    }

    @Test public void renamedFileWithFailedConfirmationIsUnconfirmed() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        SafDocumentEntry entry = selected("book");
        provider.failQueryAfterMutation = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, entry, "new.txt"));
        assertNotNull(provider.child("source-root", "new.txt"));
        assertEquals("original", provider.text("book"));
    }

    @Test public void cancellationReplacesOnlyGenericFailureMessages() {
        assertEquals(R.string.file_operation_cancelled,
                SafOperationViewModel.cancellationMessage(R.string.file_operation_failed, true));
        assertEquals(R.string.file_operation_cancelled,
                SafOperationViewModel.cancellationMessage(R.string.delete_failed, true));
        assertEquals(R.string.file_operation_failed,
                SafOperationViewModel.cancellationMessage(R.string.file_operation_failed, false));
    }

    @Test public void extensionRenameAcceptsUpdatedMimeAndDocumentId() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        SafDocumentEntry entry = selected("book");
        provider.renameMime = "application/octet-stream";
        provider.renameChangesId = true;
        assertEquals(SafDocumentOperations.Result.DONE,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, entry, "new.bin"));
        assertNull(provider.nodes.get("book"));
        assertEquals("original", provider.text("renamed-book"));
        assertEquals("application/octet-stream", provider.nodes.get("renamed-book").mime);
    }

    @Test public void directoryRenameAcceptsNewDocumentId() throws Exception {
        provider.addDirectory("folder", "source-root", "Old");
        provider.addFile("child", "folder", "book.txt", "original");
        SafDocumentEntry entry = selected("folder");
        provider.renameChangesId = true;
        assertEquals(SafDocumentOperations.Result.DONE,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, entry, "New"));
        assertNotNull(provider.child("renamed-folder", "book.txt"));
        assertEquals("original", provider.text("child"));
    }

    @Test public void fileBecomingDirectoryAfterRenameIsUnconfirmed() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        provider.renameMime = DocumentsContract.Document.MIME_TYPE_DIR;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, selected("book"), "New"));
        assertEquals("original", provider.text("book"));
    }

    @Test public void directoryBecomingFileAfterRenameIsUnconfirmed() throws Exception {
        provider.addDirectory("folder", "source-root", "Old");
        provider.renameMime = "application/octet-stream";
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, selected("folder"), "New"));
        assertNotNull(provider.nodes.get("folder"));
    }

    @Test public void changedSizeAfterRenameIsUnconfirmed() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        provider.editDuringRename = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, selected("book"), "new.txt"));
        assertEquals("edited", provider.text("book"));
    }

    @Test public void lostKnownSizeAfterRenameIsUnconfirmed() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        provider.loseSizeDuringRename = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, selected("book"), "new.txt"));
        assertEquals("original", provider.text("book"));
    }

    @Test public void createWithFailedReplyDoesNotClaimNothingChanged() throws Exception {
        provider.failMutationReply = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.createFolder(resolver, TARGET_TREE, TARGET_PARENT, "Created"));
        assertNotNull(provider.child("target-root", "Created"));
    }

    @Test public void renameWithFailedReplyDoesNotClaimNothingChanged() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        SafDocumentEntry entry = selected("book");
        provider.failMutationReply = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, entry, "new.txt"));
        assertNotNull(provider.child("source-root", "new.txt"));
    }

    @Test public void copyWithLostCreatedUriPreservesSourceAndReportsUnconfirmedOutput() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "original");
        provider.failMutationReply = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("book", true));
        assertEquals("original", provider.text("book"));
        assertNotNull(provider.child("target-root", "book.txt"));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void nullCreatedUriLeavesUnknownOutputForInspection() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "original");
        provider.omitMutationUri = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("book", true));
        assertNotNull(provider.child("target-root", "book.txt"));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void loadingDocumentMetadataCannotStartCopy() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "original");
        SafDocumentEntry entry = selected("book");
        provider.loadingDocument = "book";
        assertEquals(SafDocumentOperations.Result.FAILED, transfer(entry, false));
        assertEquals(0, provider.creates);
    }

    @Test public void errorDocumentMetadataCannotStartCopy() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "original");
        SafDocumentEntry entry = selected("book");
        provider.errorDocument = "book";
        assertEquals(SafDocumentOperations.Result.FAILED, transfer(entry, false));
        assertEquals(0, provider.creates);
    }

    @Test public void sourceEditedDuringRenameLookupIsRejected() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        SafDocumentEntry entry = selected("book");
        provider.editSourceAtRenameLookup = true;
        assertEquals(SafDocumentOperations.Result.CHANGED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, entry, "new.txt"));
        assertNotNull(provider.child("source-root", "old.txt"));
    }

    @Test public void selectedDocumentMovedToAnotherParentIsNotRenamed() throws Exception {
        provider.addFile("book", "source-root", "old.txt", "original");
        SafDocumentEntry entry = selected("book");
        provider.nodes.get("book").parent = "target-root";
        assertEquals(SafDocumentOperations.Result.CHANGED,
                SafDocumentOperations.rename(resolver, SOURCE_TREE, SOURCE_PARENT, entry, "new.txt"));
        assertNotNull(provider.child("target-root", "old.txt"));
    }

    @Test public void lateCancellationKeepsCommittedAndUncertainOutcomes() {
        int[] messages = {R.string.deleted, R.string.saf_operation_done,
                R.string.saf_source_remaining, R.string.saf_cleanup_required,
                R.string.saf_delete_unconfirmed, R.string.saf_delete_changed};
        for (int message : messages) {
            assertEquals(message, SafOperationViewModel.cancellationMessage(message, true));
        }
    }

    @Test public void targetEditDuringVerificationCannotBeAdoptedAsVerifiedCopy() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.editTargetAtVerificationQuery = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("edited", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void targetEditDuringFinalSourceCheckKeepsOriginal() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.editTargetAtSourceCheck = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("edited", provider.text(provider.child("target-root", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void relocatedCopyIsNotDeletedDuringFailureCleanup() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.addDirectory("other-target", "target-root", "Other");
        provider.relocateTargetAtSourceCheck = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("book", true));
        assertEquals("source", provider.text("book"));
        assertEquals("source", provider.text(provider.child("other-target", "book.txt").id));
        assertEquals(0, provider.sourceDeletes);
    }

    @Test public void earlierCopiedChildIsRecheckedBeforeFolderCompletion() throws Exception {
        provider.addDirectory("folder", "source-root", "Folder");
        provider.addFile("first", "folder", "first.txt", "source");
        provider.addFile("second", "folder", "second.txt", "second");
        provider.editFirstTargetOnSecondWrite = true;
        assertEquals(SafDocumentOperations.Result.CLEANUP_REQUIRED, transfer("folder", false));
        assertEquals("source", provider.text("first"));
        Node copiedFolder = provider.child("target-root", "Folder");
        assertEquals("edited", provider.text(provider.child(copiedFolder.id, "first.txt").id));
    }

    @Test public void ownChildCreationMayUpdateDestinationDirectoryMetadata() throws Exception {
        provider.addDirectory("folder", "source-root", "Folder");
        provider.addFile("book", "folder", "book.txt", "source");
        provider.updateParentOnCreate = true;
        assertEquals(SafDocumentOperations.Result.DONE, transfer("folder", false));
        assertEquals("source", provider.text(provider.child(provider.child("target-root", "Folder").id, "book.txt").id));
    }

    @Test public void changedNativeMoveSourceAfterCollisionCheckIsRejected() throws Exception {
        provider.addFile("book", "source-root", "book.txt", "source");
        provider.nodes.get("book").flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        provider.editSourceAtCollisionCheck = true;
        assertEquals(SafDocumentOperations.Result.CHANGED, transfer("book", true));
        assertEquals(0, provider.nativeMoves);
        assertNotNull(provider.child("source-root", "book.txt"));
    }

    private SafDocumentOperations.Result transfer(String id, boolean move) throws Exception {
        return transfer(selected(id), move);
    }

    private SafDocumentOperations.Result transfer(SafDocumentEntry entry, boolean move) {
        return SafDocumentOperations.transfer(resolver, SOURCE_TREE, SOURCE_PARENT, entry,
                TARGET_TREE, TARGET_PARENT, move);
    }

    private SafDocumentEntry selected(String id) throws IOException {
        return SafDocumentOperations.read(resolver, SOURCE_TREE, document(SOURCE_TREE, id));
    }

    private static Uri document(Uri tree, String id) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    private static void removeFixture(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeFixture(child);
        assertTrue(file.delete());
    }

    private static final class Node {
        String id, mime;
        String parent;
        final File file;
        String name;
        long modified = 100L;
        Long declaredSize;
        int flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        Node(String id, String parent, String name, String mime, File file) {
            this.id = id; this.parent = parent; this.name = name; this.mime = mime; this.file = file;
            flags |= file == null ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        }
        long size() { return declaredSize != null ? declaredSize : file == null ? 0L : file.length(); }
    }

    private static final class Provider extends ContentProvider {
        final File root;
        final Map<String, Node> nodes = new LinkedHashMap<>();
        int creates, sourceDeletes, sequence, nativeMoves;
        boolean failSourceDelete, addSourceMemberOnWrite, interruptOnWrite;
        boolean addForeignTargetMemberAndFailWrite;
        boolean copyInsteadOfNativeMove;
        boolean failQueryAfterMutation;
        boolean failMutationReply, omitMutationUri, editSourceAtRenameLookup;
        boolean renameChangesId, editDuringRename, loseSizeDuringRename;
        String renameMime;
        String loadingDocument, errorDocument;
        boolean editTargetAtVerificationQuery, editTargetAtSourceCheck, relocateTargetAtSourceCheck;
        boolean editFirstTargetOnSecondWrite, updateParentOnCreate, editSourceAtCollisionCheck;
        boolean relocateSourceAtCollisionCheck, relocateSourceAfterCopy, incompleteSourceParentAfterCopy;
        boolean relocateSourceAtReadyQuery, collisionChecked;
        boolean relocateSourceAtFinalTargetQuery, sourceParentCheckedAfterCopy;
        int sourceFlagsAtCollisionCheck, sourceFlagsAfterCopy, sourceFlagsAtDeletionQuery;
        String verificationOpenedId, firstWrittenId;
        String unavailableDocument;
        String loadingChildren;

        Provider(File root) { this.root = root; }

        void addDirectory(String id, String parent, String name) {
            nodes.put(id, new Node(id, parent, name, DocumentsContract.Document.MIME_TYPE_DIR, null));
        }

        void addFile(String id, String parent, String name, String text) throws IOException {
            File file = new File(root, "data-" + (++sequence));
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
            }
            nodes.put(id, new Node(id, parent, name, "text/plain", file));
        }

        String text(String id) throws IOException {
            return new String(Files.readAllBytes(nodes.get(id).file.toPath()), StandardCharsets.UTF_8);
        }

        void edit(String id) {
            Node node = nodes.get(id);
            try (FileOutputStream output = new FileOutputStream(node.file)) {
                output.write("edited".getBytes(StandardCharsets.UTF_8));
                node.modified++;
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }

        Node child(String parent, String name) {
            for (Node node : nodes.values()) if (parent.equals(node.parent) && name.equals(node.name)) return node;
            return null;
        }

        SafDocumentEntry entry(Node node, Uri tree) {
            return new SafDocumentEntry(document(tree, node.id), node.id, node.name, node.mime,
                    node.file == null, node.size(), node.modified, node.flags);
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            String id = DocumentsContract.getDocumentId(uri);
            boolean children = "children".equals(uri.getLastPathSegment());
            if (children && id.equals("source-root") && verificationOpenedId != null) {
                sourceParentCheckedAfterCopy = true;
            }
            if (!children && id.equals(verificationOpenedId) && sourceParentCheckedAfterCopy
                    && relocateSourceAtFinalTargetQuery) {
                relocateSourceAtFinalTargetQuery = false;
                nodes.get("book").parent = "elsewhere";
            }
            if (!children && id.equals("book") && projection.length > 3
                    && DocumentsContract.Document.COLUMN_FLAGS.equals(projection[3])) {
                nodes.get("book").flags |= sourceFlagsAtDeletionQuery;
                sourceFlagsAtDeletionQuery = 0;
            }
            if (children && id.equals("source-root") && editSourceAtRenameLookup) {
                editSourceAtRenameLookup = false;
                edit("book");
            }
            if (children && id.equals("target-root") && editSourceAtCollisionCheck) {
                editSourceAtCollisionCheck = false;
                edit("book");
            }
            if (children && id.equals("target-root") && relocateSourceAtCollisionCheck) {
                relocateSourceAtCollisionCheck = false;
                nodes.get("book").parent = "elsewhere";
            }
            if (children && id.equals("target-root") && sourceFlagsAtCollisionCheck != 0) {
                nodes.get("book").flags |= sourceFlagsAtCollisionCheck;
                sourceFlagsAtCollisionCheck = 0;
            }
            if (children && id.equals("target-root")) collisionChecked = true;
            if (!children && id.equals("book") && collisionChecked && relocateSourceAtReadyQuery) {
                relocateSourceAtReadyQuery = false;
                nodes.get("book").parent = "elsewhere";
            }
            if (!children && id.equals(verificationOpenedId) && editTargetAtVerificationQuery) {
                editTargetAtVerificationQuery = false;
                edit(id);
            }
            if (!children && id.equals("book") && verificationOpenedId != null) {
                nodes.get("book").flags |= sourceFlagsAfterCopy;
                sourceFlagsAfterCopy = 0;
                if (relocateSourceAfterCopy) {
                    relocateSourceAfterCopy = false;
                    nodes.get("book").parent = "elsewhere";
                }
                if (incompleteSourceParentAfterCopy) {
                    incompleteSourceParentAfterCopy = false;
                    loadingChildren = "source-root";
                }
                if (editTargetAtSourceCheck) {
                    editTargetAtSourceCheck = false;
                    edit(verificationOpenedId);
                }
                if (relocateTargetAtSourceCheck) {
                    relocateTargetAtSourceCheck = false;
                    nodes.get(verificationOpenedId).parent = "other-target";
                }
            }
            if (!children && id.equals(unavailableDocument)) {
                throw new IllegalStateException("Confirmation unavailable after mutation");
            }
            if (children) {
                for (Node node : nodes.values()) if (id.equals(node.parent)) addRow(cursor, projection, node);
                if (id.equals(loadingChildren)) {
                    Bundle extras = new Bundle();
                    extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                    cursor.setExtras(extras);
                }
            } else if (nodes.containsKey(id)) {
                addRow(cursor, projection, nodes.get(id));
            }
            if (!children && (id.equals(loadingDocument) || id.equals(errorDocument))) {
                Bundle extras = new Bundle();
                if (id.equals(loadingDocument)) extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                if (id.equals(errorDocument)) extras.putString(DocumentsContract.EXTRA_ERROR, "Unavailable");
                cursor.setExtras(extras);
            }
            return cursor;
        }

        private void addRow(MatrixCursor cursor, String[] projection, Node node) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : projection) {
                switch (column) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(node.id); break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add(node.name); break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: row.add(node.mime); break;
                    case DocumentsContract.Document.COLUMN_SIZE: row.add(node.size()); break;
                    case DocumentsContract.Document.COLUMN_LAST_MODIFIED: row.add(node.modified); break;
                    case DocumentsContract.Document.COLUMN_FLAGS: row.add(node.flags); break;
                    default: row.add(null);
                }
            }
        }

        @Override public Bundle call(String method, String arg, Bundle extras) {
            Uri uri = BundleCompat.getParcelable(extras, "uri", Uri.class);
            String id = DocumentsContract.getDocumentId(uri);
            if ("android:createDocument".equals(method)) {
                String created = "new-" + (++creates);
                String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                String mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
                try {
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) addDirectory(created, id, name);
                    else addFile(created, id, name, "");
                } catch (IOException failed) { throw new IllegalStateException(failed); }
                Bundle result = new Bundle();
                result.putParcelable("uri", document(uri, created));
                if (failQueryAfterMutation) unavailableDocument = created;
                if (updateParentOnCreate) nodes.get(id).modified++;
                if (failMutationReply) throw new IllegalStateException("Reply failed after creation");
                if (omitMutationUri) result.remove("uri");
                return result;
            }
            if ("android:deleteDocument".equals(method)) {
                if (!id.startsWith("new-")) {
                    sourceDeletes++;
                    if (failSourceDelete) throw new SecurityException("Read only source");
                }
                deleteNode(id);
                return new Bundle();
            }
            if ("android:renameDocument".equals(method)) {
                Node node = nodes.get(id);
                node.name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (renameMime != null) node.mime = renameMime;
                if (editDuringRename) edit(id);
                if (loseSizeDuringRename) node.declaredSize = -1L;
                if (renameChangesId) {
                    nodes.remove(id);
                    node.id = "renamed-" + id;
                    nodes.put(node.id, node);
                    for (Node child : nodes.values()) {
                        if (id.equals(child.parent)) child.parent = node.id;
                    }
                }
                if (failQueryAfterMutation) unavailableDocument = node.id;
                Bundle result = new Bundle();
                result.putParcelable("uri", document(uri, node.id));
                if (failMutationReply) throw new IllegalStateException("Reply failed after rename");
                return result;
            }
            if ("android:moveDocument".equals(method)) {
                nativeMoves++;
                Uri target = null;
                for (String key : extras.keySet()) {
                    if (!"uri".equals(key) && !"parentUri".equals(key)) {
                        target = BundleCompat.getParcelable(extras, key, Uri.class);
                    }
                }
                assertNotNull(target);
                String parent = DocumentsContract.getDocumentId(target);
                String movedId = id;
                if (copyInsteadOfNativeMove) {
                    movedId = "native-copy";
                    Node original = nodes.get(id);
                    try { addFile(movedId, parent, original.name, text(id)); }
                    catch (IOException failed) { throw new IllegalStateException(failed); }
                } else {
                    nodes.get(id).parent = parent;
                }
                Bundle result = new Bundle();
                result.putParcelable("uri", document(target, movedId));
                return result;
            }
            throw new AssertionError(method);
        }

        private void deleteNode(String id) {
            List<String> children = new ArrayList<>();
            for (Node node : nodes.values()) if (id.equals(node.parent)) children.add(node.id);
            for (String child : children) deleteNode(child);
            Node removed = nodes.remove(id);
            if (removed != null && removed.file != null) assertTrue(removed.file.delete());
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            Node node = nodes.get(DocumentsContract.getDocumentId(uri));
            if (node == null || node.file == null) throw new FileNotFoundException();
            if (mode.contains("w") && node.id.startsWith("new-")) {
                if (firstWrittenId == null) firstWrittenId = node.id;
                else if (editFirstTargetOnSecondWrite) {
                    editFirstTargetOnSecondWrite = false;
                    edit(firstWrittenId);
                }
                try {
                    if (addSourceMemberOnWrite) {
                        addSourceMemberOnWrite = false;
                        addFile("late-source", "folder", "late.txt", "late original");
                    }
                    if (addForeignTargetMemberAndFailWrite) {
                        addForeignTargetMemberAndFailWrite = false;
                        addFile("foreign-target", node.parent, "foreign.txt", "another client");
                        throw new FileNotFoundException("Write failed after concurrent edit");
                    }
                } catch (IOException failed) {
                    throw new FileNotFoundException(failed.getMessage());
                }
                if (interruptOnWrite) Thread.currentThread().interrupt();
            }
            if (!mode.contains("w") && node.id.startsWith("new-")) verificationOpenedId = node.id;
            return ParcelFileDescriptor.open(node.file, mode.contains("w")
                    ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_TRUNCATE
                    : ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new AssertionError(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            throw new UnsupportedOperationException();
        }
    }
}
