package com.readwide.manager.archive;

import java.io.IOException;
import java.util.Arrays;

/** Metadata-only, linear-time planning for the supported one-output-coder 7z graphs. */
final class SevenZDecodePlan {
    static final class Coder {
        private final byte[] id, properties;
        final int inputs, outputs;
        Coder(byte[] id, byte[] properties, int inputs, int outputs) {
            this.id = id == null ? null : id.clone();
            this.properties = properties == null ? null : properties.clone();
            this.inputs = inputs; this.outputs = outputs;
        }
    }

    static final class Step {
        final int coderIndex;
        final SevenZCoderRegistry.Prepared decoder;
        private final int[] sources;
        private Step(int coderIndex, SevenZCoderRegistry.Prepared decoder, int[] sources) {
            this.coderIndex = coderIndex; this.decoder = decoder; this.sources = sources;
        }
        int inputCount() { return sources.length; }
        /** Nonnegative is a coder index; negative is -(packed-stream ordinal + 1). */
        int source(int slot) { return sources[slot]; }
    }

    private final Step[] steps;
    final int finalCoder, packedStreamCount;
    final long outputSize;
    private SevenZDecodePlan(Step[] steps, int finalCoder, int packedStreamCount, long outputSize) {
        this.steps = steps; this.finalCoder = finalCoder;
        this.packedStreamCount = packedStreamCount; this.outputSize = outputSize;
    }
    int stepCount() { return steps.length; }
    Step step(int index) { return steps[index]; }

    static SevenZDecodePlan compile(Coder[] coders, long[] outputSizes,
            int[] bindInputs, int[] bindOutputs, int[] packedInputs) throws IOException {
        if (coders == null || coders.length == 0 || outputSizes == null
                || outputSizes.length != coders.length || bindInputs == null
                || bindOutputs == null || bindInputs.length != bindOutputs.length
                || bindInputs.length != coders.length - 1 || packedInputs == null) {
            throw new IOException("Invalid 7z decode graph dimensions");
        }
        int count = coders.length;
        int[] inputBases = new int[count];
        SevenZCoderRegistry.Prepared[] decoders = new SevenZCoderRegistry.Prepared[count];
        int inputCount = 0;
        for (int i = 0; i < count; i++) {
            checkCancelled();
            Coder coder = coders[i];
            if (coder == null) throw new IOException("Missing 7z coder");
            SevenZCoderRegistry.Entry entry = SevenZCoderRegistry.require(coder.id);
            if (coder.inputs != entry.inputCount || coder.outputs != 1) {
                throw new ArchiveSupport.UnsupportedArchiveFeatureException("Unsupported 7z coder stream arity: " + entry.name);
            }
            if (coder.inputs > Integer.MAX_VALUE - inputCount) throw new IOException("7z input count overflow");
            inputBases[i] = inputCount;
            inputCount += coder.inputs;
            decoders[i] = entry.prepare(coder.properties, outputSizes[i]);
        }
        if (inputCount - bindInputs.length != packedInputs.length) throw new IOException("Invalid 7z packed input count");

        int[] owners = new int[inputCount];
        int[] sources = new int[inputCount];
        Arrays.fill(sources, Integer.MIN_VALUE);
        for (int i = 0; i < count; i++) Arrays.fill(owners, inputBases[i], inputBases[i] + coders[i].inputs, i);
        int[] consumers = new int[count];
        Arrays.fill(consumers, -1);
        int[] dependencies = new int[count];
        for (int i = 0; i < bindInputs.length; i++) {
            int input = bindInputs[i], output = bindOutputs[i];
            if (input < 0 || input >= inputCount || output < 0 || output >= count
                    || sources[input] != Integer.MIN_VALUE || consumers[output] != -1) {
                throw new IOException("Invalid or duplicate 7z coder binding");
            }
            sources[input] = output;
            consumers[output] = owners[input];
            dependencies[owners[input]]++;
        }
        for (int i = 0; i < packedInputs.length; i++) {
            int input = packedInputs[i];
            if (input < 0 || input >= inputCount || sources[input] != Integer.MIN_VALUE) {
                throw new IOException("Invalid or duplicate 7z packed input");
            }
            sources[input] = -i - 1;
        }
        for (int source : sources) if (source == Integer.MIN_VALUE) throw new IOException("Unbound 7z coder input");
        int finalCoder = -1;
        for (int i = 0; i < count; i++) {
            if (consumers[i] != -1) continue;
            if (finalCoder != -1) throw new IOException("Disconnected 7z coder graph");
            finalCoder = i;
        }
        if (finalCoder < 0) throw new IOException("7z graph has no final output");

        // Kahn's algorithm: no recursion or repeated global binding scans.
        int[] ready = new int[count];
        int head = 0, tail = 0;
        for (int i = 0; i < count; i++) if (dependencies[i] == 0) ready[tail++] = i;
        Step[] steps = new Step[count];
        int completed = 0;
        while (head < tail) {
            checkCancelled();
            int coder = ready[head++];
            steps[completed++] = new Step(coder, decoders[coder],
                    Arrays.copyOfRange(sources, inputBases[coder], inputBases[coder] + coders[coder].inputs));
            int consumer = consumers[coder];
            if (consumer >= 0 && --dependencies[consumer] == 0) ready[tail++] = consumer;
        }
        if (completed != count) throw new IOException("Cyclic or disconnected 7z coder graph");
        return new SevenZDecodePlan(steps, finalCoder, packedInputs.length, outputSizes[finalCoder]);
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("7z extraction cancelled");
    }
}
