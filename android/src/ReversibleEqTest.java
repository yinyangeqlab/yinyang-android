package com.yinyangeq.qudelixprobe;

import java.io.Closeable;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

final class ReversibleEqTest {
    private static final int SELECTOR = 3;
    private static final int FIRST_CUSTOM_PRESET = PresetDirectory.FIRST_CUSTOM;
    private static final int LAST_CUSTOM_PRESET = FIRST_CUSTOM_PRESET + PresetDirectory.COUNT - 1;
    private static final int UNSAVED = 255;
    private static final ReentrantLock TEST_LOCK = new ReentrantLock();

    private ReversibleEqTest() {}

    interface Connector {
        Link open() throws IOException;
    }

    interface Link extends Closeable {
        EqSnapshot read() throws IOException;
        void editTestPeak(Band band, boolean restore) throws IOException;
        void loadUsrPreset(int selector) throws IOException;
    }

    static String run(Connector connector, EqTestJournal journal, String address, Consumer<String> trace) throws IOException {
        lock();
        try {
            if (journal.exists()) throw new ProtocolException("Check the pending EQ restoration before starting another test.");
            try (Link link = connector.open()) {
                EqSnapshot baseline = link.read();
                Band band = Band.choose(baseline);
                requireCustomPreset(selector(baseline));
                EqTestJournal.Entry entry = new EqTestJournal.Entry(address, baseline, false);
                journal.save(entry);
                trace.accept("Saved the pre-test EQ snapshot before the first write.");
                try {
                    link.editTestPeak(band, false);
                } catch (IOException error) {
                    trace.accept("The temporary write was interrupted. Checking the current EQ before recovery.");
                }
                settle();
                restore(link, journal, entry, band, trace);
            }
            return verifyReconnected(connector, journal, trace);
        } finally {
            TEST_LOCK.unlock();
        }
    }

    static String check(Connector connector, EqTestJournal journal, String address, Consumer<String> trace) throws IOException {
        lock();
        try {
            if (!journal.exists()) throw new ProtocolException("There is no pending EQ test to check.");
            EqTestJournal.Entry entry = journal.read();
            if (!entry.address.equalsIgnoreCase(address)) {
                throw new ProtocolException("Select the same paired 5K used for the EQ test.");
            }
            boolean wrote;
            try (Link link = connector.open()) {
                wrote = restore(link, journal, entry, Band.choose(entry.original), trace);
            }
            return wrote ? verifyReconnected(connector, journal, trace) : finishVerified(journal, entry, trace);
        } finally {
            TEST_LOCK.unlock();
        }
    }

    private static void lock() throws ProtocolException {
        if (!TEST_LOCK.tryLock()) throw new ProtocolException("The previous EQ operation is still closing. Try again in a moment.");
    }

    private static boolean restore(Link link, EqTestJournal journal, EqTestJournal.Entry entry, Band band,
                                    Consumer<String> trace) throws IOException {
        EqSnapshot original = entry.original;
        EqSnapshot actual = link.read();
        requireSameDevice(original, actual);
        boolean cutPresent = matchingConfiguration(original, actual)
                && Arrays.equals(original.spk, actual.spk) && Arrays.equals(band.withCut(original.usr), actual.usr);
        if (cutPresent) {
            trace.accept("Verified the 1 dB cut in the USR block and matched every other EQ byte except the allowed selector change.");
            if (!entry.cutVerified) {
                try {
                    journal.save(entry.withVerifiedCut());
                } catch (IOException error) {
                    trace.accept("Could not record the cut confirmation. Continuing with gain restoration.");
                }
            }
            link.editTestPeak(band, true);
            settle();
            actual = link.read();
            requireSameDevice(original, actual);
        }

        if (!matchingConfiguration(original, actual) || !Arrays.equals(original.usr, actual.usr)
                || !Arrays.equals(original.spk, actual.spk)) {
            throw new ProtocolException("The EQ differs from the recovery snapshot. Export the debug log if the difference persists.");
        }
        trace.accept("Verified the original gain and exact USR and SPK blocks.");
        if (selector(actual) == selector(original)) return cutPresent;

        // Older recovery records can contain a selector outside the supported custom slots.
        if (!isCustomPreset(selector(original))) {
            throw new ProtocolException("The gain is restored. Reselect the original preset from the earlier test, then close that controller.");
        }
        link.loadUsrPreset(selector(original));
        settle();
        return true;
    }

    private static String verifyReconnected(Connector connector, EqTestJournal journal, Consumer<String> trace) throws IOException {
        EqTestJournal.Entry entry = journal.read();
        trace.accept("Reconnecting for read-only verification of the original preset.");
        settle();
        try (Link link = connector.open()) {
            EqSnapshot actual = link.read();
            requireSameDevice(entry.original, actual);
            if (selector(actual) != selector(entry.original)) {
                throw new ProtocolException("The original USR preset selection could not be verified after reconnecting.");
            }
            if (!Arrays.equals(entry.original.configuration, actual.configuration)
                    || !Arrays.equals(entry.original.usr, actual.usr) || !Arrays.equals(entry.original.spk, actual.spk)) {
                throw new ProtocolException("The EQ differs from the recovery snapshot after reconnecting. Export the debug log if the difference persists.");
            }
        }
        return finishVerified(journal, entry, trace);
    }

    private static String finishVerified(EqTestJournal journal, EqTestJournal.Entry entry, Consumer<String> trace) throws IOException {
        trace.accept("Verified the original preset selection, configuration, and both EQ blocks on a fresh connection.");
        journal.clear();
        if (entry.cutVerified) {
            return "EQ test passed. Verified the 1 dB cut, then verified the original gain, preset selection, and both EQ blocks after reconnecting.";
        }
        return "The original gain, preset selection, and both EQ blocks are verified. The temporary cut could not be confirmed.";
    }

    private static boolean matchingConfiguration(EqSnapshot before, EqSnapshot actual) {
        byte[] comparison = before.configuration.clone();
        if (selector(actual) == UNSAVED) comparison[SELECTOR] = (byte) UNSAVED;
        return Arrays.equals(comparison, actual.configuration);
    }

    private static int selector(EqSnapshot snapshot) {
        return Byte.toUnsignedInt(snapshot.configuration[SELECTOR]);
    }

    private static boolean isCustomPreset(int selector) {
        return selector >= FIRST_CUSTOM_PRESET && selector <= LAST_CUSTOM_PRESET;
    }

    static void requireCustomPreset(int selector) throws ProtocolException {
        if (!isCustomPreset(selector)) {
            throw new ProtocolException("Select a saved disposable USR preset from Custom #1–#20 before running the test.");
        }
    }

    private static void requireSameDevice(EqSnapshot before, EqSnapshot actual) throws ProtocolException {
        if (actual.device.model != before.device.model || !Arrays.equals(actual.device.firmwareField, before.device.firmwareField)) {
            throw new ProtocolException("Device firmware differs from the saved EQ recovery snapshot.");
        }
    }

    static void settle() throws IOException {
        try {
            Thread.sleep(200);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("The EQ test was interrupted.", error);
        }
    }

    static final class Band {
        private static final int FIRST_WORD = 48;
        private static final int GAIN_MASK = 1023;

        final int index;
        final int frequency;
        final int gain;
        final int q;

        private Band(int index, int frequency, int gain, int q) {
            this.index = index;
            this.frequency = frequency;
            this.gain = gain;
            this.q = q;
        }

        static Band choose(EqSnapshot original) throws ProtocolException {
            if (original.device.model != 1 || !original.device.version.equals("3.2.9")) {
                throw new ProtocolException("The live EQ test requires an original 5K on firmware 3.2.9.");
            }
            if (selector(original) >= 254
                    || (original.configuration[4] & 1) == 0 || (original.usr[0] & 1) == 0) {
                throw new ProtocolException("Load a saved, enabled, disposable USR PEQ preset first.");
            }
            ByteBuffer data = ByteBuffer.wrap(original.usr).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < 10; index++) {
                int word = data.getInt(FIRST_WORD + 4 * index);
                int frequency = Short.toUnsignedInt(data.getShort(8 + 2 * index));
                int gain = (word << 18) >> 22;
                int q = (word >>> 14) & 16383;
                if ((word & 15) == 5 && (word >>> 28) == 0 && frequency >= 20 && frequency <= 20000
                        && gain >= -110 && gain <= 120 && q >= 103 && q <= 10240) {
                    return new Band(index, frequency, gain, q);
                }
            }
            throw new ProtocolException("The disposable USR preset needs an eligible peaking filter for the 1 dB cut.");
        }

        byte[] withCut(byte[] original) {
            byte[] result = original.clone();
            ByteBuffer data = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
            int offset = FIRST_WORD + 4 * index;
            int word = data.getInt(offset) & ~(GAIN_MASK << 4);
            data.putInt(offset, word | (((gain - 10) & GAIN_MASK) << 4));
            return result;
        }
    }
}
