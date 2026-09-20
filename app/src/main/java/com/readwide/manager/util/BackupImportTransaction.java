package com.readwide.manager.util;

import java.util.List;

/** Rolls back reported commit failures, including a partially applied failing step.
 * This is not a cross-file power-loss journal. Each file still uses AtomicFile.
 */
final class BackupImportTransaction {
    interface Step {
        void apply() throws Exception;
        void rollback() throws Exception;
    }
    interface Action { void run() throws Exception; }
    static Step step(Action apply, Action rollback) {
        return new Step() {
            @Override public void apply() throws Exception { apply.run(); }
            @Override public void rollback() throws Exception { rollback.run(); }
        };
    }
    private BackupImportTransaction() { }

    static void run(List<Step> steps) throws Exception {
        int started = -1;
        try {
            for (int i = 0; i < steps.size(); i++) { started = i; steps.get(i).apply(); }
        } catch (Exception failure) {
            boolean rollbackFailed = false;
            for (int i = started; i >= 0; i--) {
                try { steps.get(i).rollback(); }
                catch (Exception rollback) {
                    rollbackFailed = true;
                    if (rollback != failure) failure.addSuppressed(rollback);
                }
            }
            if (rollbackFailed) {
                throw new java.io.IOException("Backup import failed; restoring previous data also failed. Keep the original backup.", failure);
            }
            throw failure;
        }
    }
}
