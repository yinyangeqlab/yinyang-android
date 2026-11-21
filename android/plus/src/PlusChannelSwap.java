package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Matches the DAC output order and the complete working SPK bank on a linked 5K Plus. */
final class PlusChannelSwap {
    static final int DAC = 0x40;
    private static final int OUTPUT_SWAP_BIT = 1 << 5;
    private static final int MODE = 0x80, MATCH_SELECTOR = 0xa0, MATCH = 0xa3;
    static final int[] STATE = {DAC, MODE, 0x90, 0x93, MATCH_SELECTOR, MATCH, 0xb0, 0xb3};
    private static final int SLOTS = 20, PREAMPS = 12, FREQUENCIES = 16, PARAMETERS = 16 + 4 * SLOTS;

    interface Journal {
        byte[] load() throws IOException;
        void save(byte[] bytes) throws IOException;
    }

    static boolean swapped(byte[] dac) {
        return (dac[0] & OUTPUT_SWAP_BIT) != 0;
    }

    static Map<Integer, byte[]> target(Map<Integer, byte[]> before) throws IOException {
        int mode = (before.get(MODE)[0] & 255) >>> 4;
        if (mode > 1) throw new IOException("Unknown 5K Plus EQ layout.");

        Map<Integer, byte[]> after = new LinkedHashMap<>(before);
        byte[] dac = before.get(DAC).clone();
        dac[0] ^= OUTPUT_SWAP_BIT;
        after.put(DAC, dac);
        // The 30-band layout has a single shared bank. Its inactive SPK bank is left intact.
        if (mode == 1) return after;

        byte[] original = before.get(MATCH);
        // PEQ and GEQ share this layout. Carry the stored filter words as-is.
        byte[] match = original.clone();
        exchange(match, PREAMPS, 2);
        exchange(match, FREQUENCIES, 2 * SLOTS);
        exchange(match, PARAMETERS, 4 * SLOTS);
        if (!Arrays.equals(original, match)) {
            byte[] selector = before.get(MATCH_SELECTOR).clone();
            selector[3] = (byte) 255;
            after.put(MATCH_SELECTOR, selector);
            after.put(MATCH, match);
        }
        return after;
    }

    private static void exchange(byte[] block, int start, int length) {
        byte[] left = Arrays.copyOfRange(block, start, start + length);
        System.arraycopy(block, start + length, block, start, length);
        System.arraycopy(left, 0, block, start + length, length);
    }

    static final class Result {
        final boolean swapped, layered;
        Result(Map<Integer, byte[]> state) {
            swapped = swapped(state.get(DAC));
            layered = ((state.get(MODE)[0] & 255) >>> 4) == 0;
        }
    }

    /** Leaves the journal until the caller has committed the companion's corresponding draft. */
    static Result run(PlusApply device, String identity, boolean shownSwapped, Journal journal) throws IOException {
        byte[] saved = journal.load();
        Map<Integer, byte[]> live = device.read(STATE);
        Map<Integer, byte[]> before = saved == null ? live : decode(saved, identity);
        Map<Integer, byte[]> after = target(before);
        if (saved == null && swapped(live.get(DAC)) != shownSwapped)
            throw new IOException("Channel routing changed on the Plus. Check the current setting again.");

        for (int tag : STATE) {
            byte[] actual = live.get(tag);
            if (!Arrays.equals(actual, before.get(tag)) && !Arrays.equals(actual, after.get(tag)))
                throw new IOException("Settings on the 5K Plus changed outside this swap. Review the current device settings.");
        }
        device.verify(live, STATE);
        if (saved == null) journal.save(encode(identity, before));
        for (int tag : new int[]{MATCH_SELECTOR, MATCH, DAC}) {
            if (!Arrays.equals(live.get(tag), after.get(tag))) device.write(tag, after.get(tag));
        }
        device.verify(after, STATE);
        return new Result(after);
    }

    private static byte[] encode(String identity, Map<Integer, byte[]> before) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(identity);
            for (int tag : STATE) out.write(before.get(tag));
        }
        return bytes.toByteArray();
    }

    private static Map<Integer, byte[]> decode(byte[] bytes, String identity) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (!identity.equals(in.readUTF())) throw new IOException("Reconnect the same 5K Plus to finish its channel swap.");
            Map<Integer, byte[]> snapshot = new LinkedHashMap<>();
            for (int tag : STATE) {
                byte[] entry = new byte[PlusApply.length(tag)];
                in.readFully(entry);
                snapshot.put(tag, entry);
            }
            if (in.read() != -1) throw new IOException("Invalid channel swap recovery record.");
            return snapshot;
        }
    }
}
