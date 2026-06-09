package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.textview.reader.util.FileOperationProgress;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.crypto.Cipher;

final class RarSplitStoredExtractor {
    private RarSplitStoredExtractor() {}

    static void extract(@NonNull RarArchiveReader.RarEntry first,
                        @NonNull File outFile,
                        @Nullable char[] password,
                        @NonNull List<RarArchiveReader.RarEntry> allEntries,
                        @Nullable FileOperationProgress progress) throws IOException {
        RarSplitStoredPlan plan = null;
        try {
            plan = RarSplitStoredPlan.fromFirstEntry(first, allEntries);
            try (RarOutputFileGuard guard = RarOutputFileGuard.forTarget(outFile)) {
                if (plan.encrypted()) {
                    extractEncrypted(plan, outFile, password, progress);
                } else {
                    RarStoredPayloadIO.copySegmentsToFile(plan.payloadSegments(), outFile, progress);
                }
                RarStoredPayloadIO.verifyCrc(plan.crcEntry(), outFile);
                guard.commit();
            }
        } catch (IOException e) {
            throw RarSplitStoredFailure.wrap(first, plan, e);
        }
    }

    private static void extractEncrypted(@NonNull RarSplitStoredPlan plan,
                                         @NonNull File outFile,
                                         @Nullable char[] password,
                                         @Nullable FileOperationProgress progress) throws IOException {
        if (password == null || password.length == 0) throw new ArchiveSupport.PasswordRequiredException();
        RarArchiveReader.RarEntry first = plan.chain().get(0);
        RarArchiveReader.EncryptionInfo encryption = first.encryption;
        if (encryption == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Encrypted split RAR payload is missing encryption metadata");
        }

        Cipher cipher;
        String errorMessage;
        if (plan.kind() == RarSplitStoredPlan.Kind.RAR4_AES_STORED) {
            cipher = Rar3Crypto.createAesCbcDecryptCipher(password, encryption.salt);
            errorMessage = "RAR3/RAR4 AES split decrypt failed";
        } else if (plan.kind() == RarSplitStoredPlan.Kind.RAR5_AES_STORED) {
            Rar5Crypto.Secrets secrets = Rar5Crypto.deriveSecrets(password, encryption.kdfCount, encryption.salt);
            if (!Rar5Crypto.passwordMatches(secrets, encryption.check)) {
                throw new ArchiveSupport.PasswordRequiredException();
            }
            cipher = Rar5Crypto.createAesCbcDecryptCipher(secrets, encryption.iv);
            errorMessage = "RAR5 AES split decrypt failed";
        } else {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "Encrypted split RAR payload is not supported yet");
        }

        RarCryptoStreams.decryptSegmentsToFile(
                plan.payloadSegments(),
                plan.unpackedSize(),
                cipher,
                outFile,
                errorMessage,
                progress,
                true);
    }
}
