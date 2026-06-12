package com.textview.reader.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Logical successor-context registry for the evolving first-party RAR3/RAR4 PPMd model.
 *
 * <p>RAR PPMd states point at successor contexts that are owned by the model allocator. Earlier
 * passes kept only state arrays and suffix links. This table introduces an explicit context-node
 * lifetime so a decoded state can acquire a successor context without confusing that node pointer
 * with the state's backing array pointer. The live RAR PPMd decoder is still gated elsewhere; this
 * registry is a validated scaffold for context creation/update/rescale work in later passes.</p>
 */
final class RarPpmdSuccessorTable {
    @NonNull private final Map<Integer, RarPpmdContext> contextsByPointer = new HashMap<>();
    private int allocatedSuccessorContexts;

    void registerRoot(@NonNull RarPpmdContext rootContext) throws IOException {
        int pointer = rootContext.contextPointer();
        if (pointer == RarPpmdContext.NO_CONTEXT) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd root context must own a context node before successor registration");
        }
        if (contextsByPointer.containsKey(pointer)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd root context pointer is already registered: " + pointer);
        }
        contextsByPointer.put(pointer, rootContext);
    }

    @NonNull
    RarPpmdContext allocateSuccessorContext(@NonNull RarPpmdSubAllocator allocator,
                                            int suffixPointer) throws IOException {
        RarPpmdContext.validatePointerOrNoneForModel(suffixPointer, "successor suffix");
        int contextPointer = allocator.allocUnits(1);
        if (contextsByPointer.containsKey(contextPointer)) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd successor context pointer collision: " + contextPointer);
        }
        RarPpmdContext context = new RarPpmdContext();
        context.attachContextPointer(contextPointer);
        context.setSuffixPointer(suffixPointer);
        context.allocateStateArray(allocator);
        contextsByPointer.put(contextPointer, context);
        allocatedSuccessorContexts++;
        return context;
    }

    @Nullable
    RarPpmdContext contextForPointer(int pointer) throws IOException {
        RarPpmdContext.validatePointerOrNoneForModel(pointer, "successor lookup");
        if (pointer == RarPpmdContext.NO_CONTEXT) return null;
        return contextsByPointer.get(pointer);
    }

    @NonNull
    RarPpmdContext requireContextForPointer(int pointer) throws IOException {
        RarPpmdContext context = contextForPointer(pointer);
        if (context == null) {
            throw new RarArchiveReader.UnsupportedRarFeatureException(
                    "RAR3/RAR4 PPMd successor context pointer is not registered: " + pointer);
        }
        return context;
    }

    int registeredContextCount() {
        return contextsByPointer.size();
    }

    int allocatedSuccessorContextCount() {
        return allocatedSuccessorContexts;
    }
}
