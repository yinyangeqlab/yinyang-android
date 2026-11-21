package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ReversibleEqTestTest {
    private static final String DEVICE = "02:00:00:00:00:01";
    private static int passed;

    public static void main(String[] args) throws IOException {
        checkPackets();
        scenario(ReversibleEqTestTest::normalRoundTrip);
        scenario(ReversibleEqTestTest::droppedAfterCut);
        scenario(ReversibleEqTestTest::droppedAfterRestore);
        scenario(ReversibleEqTestTest::droppedAfterLoad);
        scenario(ReversibleEqTestTest::failedReconnect);
        scenario(ReversibleEqTestTest::failedVerification);
        scenario(ReversibleEqTestTest::legacyRecovery);
        scenario(ReversibleEqTestTest::unchangedDevice);
        scenario(ReversibleEqTestTest::unexpectedChanges);
        scenario(ReversibleEqTestTest::unsupportedInputs);
        System.out.println(passed + " reversible EQ checks passed.");
    }

    private static void checkPackets() throws IOException {
        ReversibleEqTest.Band band = ReversibleEqTest.Band.choose(baseline());
        check(band.index == 2 && band.frequency == 1234 && band.gain == -25 && band.q == 1536,
                "packed peak fields decode exactly, including signed gain");
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        GaiaProbe.Connection link = new GaiaProbe.Connection(new ByteArrayInputStream(new byte[0]), wire, text -> {});
        link.editTestPeak(band, false);
        check(Arrays.equals(wire.toByteArray(), hex("FF01000AF001070F0001020504D2FFDD0600")),
                "only the USR peak is sent with its gain reduced by 1 dB");
        wire.reset();
        link.editTestPeak(band, true);
        check(Arrays.equals(wire.toByteArray(), hex("FF01000AF001070F0001020504D2FFE70600")),
                "rollback sends the original band parameters exactly");
        wire.reset();
        link.loadUsrPreset(25);
        check(Arrays.equals(wire.toByteArray(), hex("FF010002F00107090019")),
                "preset load sends group USR and the exact selector, with no save command");
        wire.reset();
        link.loadUsrPreset(22);
        link.loadUsrPreset(41);
        check(Arrays.equals(wire.toByteArray(), hex("FF010002F00107090016FF010002F00107090029")),
                "both custom slot range boundaries keep their original selector");
        wire.reset();
        reject(() -> link.loadUsrPreset(21), "Custom #1");
        reject(() -> link.loadUsrPreset(42), "Custom #1");
        check(wire.size() == 0, "unverified preset selectors send no load request");
    }

    private static void normalRoundTrip(Path directory) throws IOException {
        EqSnapshot original = baseline();
        EqTestJournal journal = journal(directory);
        Device device = new Device(original);
        device.requireSavedBaseline = journal;
        String result = ReversibleEqTest.run(device, journal, DEVICE, text -> {});
        check(result.startsWith("EQ test passed") && result.contains("original gain, preset selection"),
                "a single action verifies the gain and exact original selection");
        check(device.writes == 3 && device.loads == 1, "one cut, one gain rollback, and one preset reload");
        check(device.opens == 2 && device.session.closed && device.session.writes == 0,
                "verification opens a new connection, performs no writes, and closes it");
        check(!journal.exists(), "only the fresh read clears the recovery record");
    }

    private static void droppedAfterCut(Path directory) throws IOException {
        EqSnapshot original = baseline();
        EqTestJournal journal = journal(directory);
        Device interrupted = new Device(original);
        interrupted.dropOnWrite = 1;
        reject(() -> ReversibleEqTest.run(interrupted, journal, DEVICE, text -> {}), "Simulated connection lost");
        check(journal.exists() && !journal.read().cutVerified && interrupted.writes == 1,
                "a drop after the cut keeps the pre-write snapshot");
        reject(() -> ReversibleEqTest.run(interrupted, journal, DEVICE, text -> {}), "pending EQ restoration");
        check(interrupted.writes == 1 && interrupted.opens == 1, "a pending journal prevents another cut");
        String result = ReversibleEqTest.check(interrupted, journal(directory), DEVICE, text -> {});
        check(result.startsWith("EQ test passed") && interrupted.writes == 3 && interrupted.loads == 1,
                "recovery after restart verifies the cut, restores gain, and reloads the original slot");
        check(interrupted.opens == 3 && interrupted.session.writes == 0 && !journal.exists(),
                "a separate read-only link verifies the recovered selection");
    }

    private static void droppedAfterRestore(Path directory) throws IOException {
        EqSnapshot original = baseline();
        EqTestJournal journal = journal(directory);
        Device interrupted = new Device(original);
        interrupted.dropOnWrite = 2;
        reject(() -> ReversibleEqTest.run(interrupted, journal, DEVICE, text -> {}), "Simulated connection lost");
        check(journal.read().cutVerified && interrupted.writes == 2, "proof of the cut survives an interrupted rollback reply");
        check(ReversibleEqTest.check(interrupted, journal(directory), DEVICE, text -> {}).startsWith("EQ test passed")
                        && interrupted.writes == 3 && interrupted.loads == 1 && !journal.exists(),
                "already-restored gain needs only a single preset reload and fresh verification");
    }

    private static void droppedAfterLoad(Path directory) throws IOException {
        EqTestJournal journal = journal(directory);
        Device device = new Device(baseline());
        device.dropOnWrite = 3;
        reject(() -> ReversibleEqTest.run(device, journal, DEVICE, text -> {}), "Simulated connection lost");
        check(journal.read().cutVerified && device.loads == 1, "journal survives a lost link after preset reload");
        check(ReversibleEqTest.check(device, journal(directory), DEVICE, text -> {}).startsWith("EQ test passed")
                        && device.opens == 2 && device.session.writes == 0 && !journal.exists(),
                "a check reads the already-restored selection and sends no duplicate writes");
    }

    private static void failedReconnect(Path directory) throws IOException {
        EqTestJournal journal = journal(directory);
        Device device = new Device(baseline());
        device.failOpen = 2;
        reject(() -> ReversibleEqTest.run(device, journal, DEVICE, text -> {}), "Simulated connect failure");
        check(journal.read().cutVerified && device.session.closed, "failed verification connect leaves proof and closes the original session");
        check(ReversibleEqTest.check(device, journal, DEVICE, text -> {}).startsWith("EQ test passed")
                        && device.writes == 3 && device.session.writes == 0 && !journal.exists(),
                "retry verifies the restored EQ without repeating any writes");
    }

    private static void failedVerification(Path directory) throws IOException {
        EqTestJournal journal = journal(directory);
        Device device = new Device(baseline());
        device.ignoreLoad = true;
        reject(() -> ReversibleEqTest.run(device, journal, DEVICE, text -> {}), "preset selection could not be verified");
        check(journal.read().cutVerified && device.session.writes == 0 && device.writes == 3,
                "matching EQ with an unsaved selector retains the journal without retrying on the verification connection");
        device.ignoreLoad = false;
        device.changeSpkOnLoad = true;
        reject(() -> ReversibleEqTest.check(device, journal, DEVICE, text -> {}), "differs from the recovery snapshot after reconnecting");
        check(journal.exists() && device.writes == 4 && device.session.writes == 0,
                "matching selector with a changed EQ block fails independent verification");
    }

    private static void legacyRecovery(Path directory) throws IOException {
        EqSnapshot original = withSelector(baseline(), 21);
        EqTestJournal journal = journal(directory);
        journal.save(new EqTestJournal.Entry(DEVICE, original, true));
        Device device = new Device(original);
        device.state = withSelector(original, 255);
        reject(() -> ReversibleEqTest.check(device, journal, DEVICE, text -> {}), "Reselect the original preset from the earlier test");
        check(journal.exists() && device.writes == 0, "existing journal for an unsupported slot stays recoverable");
        device.state = original;
        check(ReversibleEqTest.check(device, journal, DEVICE, text -> {}).startsWith("EQ test passed") && !journal.exists(),
                "a legacy test with the original preset selected can still be verified");
    }

    private static void unchangedDevice(Path directory) throws IOException {
        EqSnapshot original = baseline();
        EqTestJournal journal = journal(directory);
        Device unchanged = new Device(original);
        unchanged.ignoreCut = true;
        String result = ReversibleEqTest.run(unchanged, journal, DEVICE, text -> {});
        check(unchanged.opens == 2 && unchanged.session.writes == 0, "even matching state is verified on a new connection");
        check(result.contains("cut could not be confirmed") && !journal.exists() && unchanged.writes == 1,
                "original state is verified without claiming a successful cut");
    }

    private static void unexpectedChanges(Path directory) throws IOException {
        EqSnapshot original = baseline();
        EqTestJournal journal = journal(directory);
        journal.save(new EqTestJournal.Entry(DEVICE, original, true));
        Device otherDevice = new Device(original);
        reject(() -> ReversibleEqTest.check(otherDevice, journal, "02:00:00:00:00:02", text -> {}), "same paired 5K");
        check(otherDevice.opens == 0 && otherDevice.writes == 0, "recovery is bound to the original paired address");

        byte[] otherSlot = original.configuration.clone();
        otherSlot[3] = 26;
        Device wrongSelection = new Device(new EqSnapshot(original.device, otherSlot, original.usr, original.spk));
        reject(() -> ReversibleEqTest.check(wrongSelection, journal, DEVICE, text -> {}), "differs from the recovery snapshot");
        check(wrongSelection.writes == 0 && journal.exists(), "identical EQ in the wrong slot is insufficient");

        byte[] changedSpeaker = original.spk.clone();
        changedSpeaker[5] ^= 1;
        byte[] unsaved = original.configuration.clone();
        unsaved[3] = (byte) 255;
        Device changed = new Device(new EqSnapshot(original.device, unsaved, ReversibleEqTest.Band.choose(original).withCut(original.usr), changedSpeaker));
        reject(() -> ReversibleEqTest.check(changed, journal, DEVICE, text -> {}), "differs from the recovery snapshot");
        check(changed.writes == 0 && journal.exists(), "unexpected SPK changes block automatic writes");

        Device differentFirmware = new Device(new EqSnapshot(new GaiaProbe.Device(1, hex("83909A66")), original.configuration, original.usr, original.spk));
        reject(() -> ReversibleEqTest.check(differentFirmware, journal, DEVICE, text -> {}), "firmware differs");
        check(differentFirmware.writes == 0 && journal.exists(), "a different firmware field blocks recovery");
    }

    private static void unsupportedInputs(Path directory) throws IOException {
        EqSnapshot original = baseline();
        EqTestJournal journal = journal(directory);
        byte[] unsaved = original.configuration.clone();
        unsaved[3] = (byte) 255;
        Device unavailableSlot = new Device(new EqSnapshot(original.device, unsaved, original.usr, original.spk));
        reject(() -> ReversibleEqTest.run(unavailableSlot, journal, DEVICE, text -> {}), "saved, enabled, disposable");
        check(unavailableSlot.writes == 0 && !journal.exists(), "the test begins with a saved selector");

        Device builtIn = new Device(withSelector(original, 21));
        reject(() -> ReversibleEqTest.run(builtIn, journal, DEVICE, text -> {}), "Custom #1");
        check(builtIn.writes == 0 && !journal.exists(), "the test requires a verified custom slot before its first write");

        Device wrongFirmware = new Device(new EqSnapshot(new GaiaProbe.Device(1, hex("83919A65")), original.configuration, original.usr, original.spk));
        reject(() -> ReversibleEqTest.run(wrongFirmware, journal, DEVICE, text -> {}), "firmware 3.2.9");
        check(wrongFirmware.writes == 0, "untested firmware stays read-only");

        byte[] noPeak = original.usr.clone();
        noPeak[56] &= (byte) 0xf0;
        Device noBand = new Device(new EqSnapshot(original.device, original.configuration, noPeak, original.spk));
        reject(() -> ReversibleEqTest.run(noBand, journal, DEVICE, text -> {}), "eligible peaking filter");
        check(noBand.writes == 0, "the app neither creates nor enables a band");

        Device withoutStorage = new Device(original);
        reject(() -> ReversibleEqTest.run(withoutStorage, journal(directory.resolve("absent")), DEVICE, text -> {}), null);
        check(withoutStorage.writes == 0, "a journal write failure blocks the hardware write");

        Files.write(directory.resolve(EqTestJournal.FILE_NAME), new byte[]{0});
        Device corrupt = new Device(original);
        reject(() -> ReversibleEqTest.check(corrupt, journal, DEVICE, text -> {}), null);
        check(corrupt.writes == 0 && journal.exists(), "corrupt recovery data is retained and fails closed");
    }

    private static EqSnapshot withSelector(EqSnapshot snapshot, int selector) throws IOException {
        byte[] config = snapshot.configuration.clone();
        config[3] = (byte) selector;
        return new EqSnapshot(snapshot.device, config, snapshot.usr, snapshot.spk);
    }

    private static EqSnapshot baseline() throws IOException {
        byte[] config = new byte[19];
        config[0] = (byte) 0x80;
        config[3] = 25;
        config[4] = 1;
        config[10] = 1;
        byte[] usr = new byte[88];
        usr[0] = 1;
        ByteBuffer.wrap(usr).order(ByteOrder.LITTLE_ENDIAN).putShort(12, (short) 1234)
                .putInt(56, 5 | ((-25 & 1023) << 4) | (1536 << 14));
        byte[] spk = new byte[128];
        Arrays.fill(spk, (byte) 0x38);
        return new EqSnapshot(new GaiaProbe.Device(1, hex("83909A65")), config, usr, spk);
    }

    private static EqTestJournal journal(Path directory) {
        return new EqTestJournal(directory.toFile());
    }

    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    private static void scenario(Work work) throws IOException {
        Path directory = Files.createTempDirectory("reversible-eq-test");
        try {
            work.run(directory);
        } finally {
            Files.deleteIfExists(directory.resolve(EqTestJournal.FILE_NAME));
            Files.delete(directory);
        }
    }

    private static void reject(Action action, String expected) throws IOException {
        try {
            action.run();
            throw new AssertionError("Expected an error: " + expected);
        } catch (IOException error) {
            if (expected != null && (error.getMessage() == null || !error.getMessage().contains(expected))) throw error;
            passed++;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }

    private static final class Device implements ReversibleEqTest.Connector {
        private final EqSnapshot original;
        private EqSnapshot state;
        private Session session;
        private int opens;
        private int writes;
        private int loads;
        private boolean ignoreCut;
        private boolean ignoreLoad;
        private boolean changeSpkOnLoad;
        private int dropOnWrite;
        private int failOpen;
        private EqTestJournal requireSavedBaseline;

        Device(EqSnapshot initial) {
            original = initial;
            state = initial;
        }

        @Override public ReversibleEqTest.Link open() throws IOException {
            if (session != null && !session.closed) throw new AssertionError("The original connection is still open");
            if (++opens == failOpen) throw new IOException("Simulated connect failure");
            session = new Session();
            return session;
        }

        private final class Session implements ReversibleEqTest.Link {
            private int writes;
            private boolean closed;

            @Override public EqSnapshot read() throws IOException {
                if (closed) throw new EOFException("Simulated connection lost");
                return state;
            }

            @Override public void editTestPeak(ReversibleEqTest.Band band, boolean restore) throws IOException {
                read();
                writes++;
                Device.this.writes++;
                if (ignoreCut && !restore) return;
                if (requireSavedBaseline != null && Device.this.writes == 1) {
                    check(Arrays.equals(requireSavedBaseline.read().original.usr, state.usr), "snapshot is durable before transmit");
                }
                byte[] usr = state.usr.clone();
                ByteBuffer data = ByteBuffer.wrap(usr).order(ByteOrder.LITTLE_ENDIAN);
                int offset = 48 + 4 * band.index;
                int word = data.getInt(offset);
                int gain = restore ? band.gain : band.gain - 10;
                data.putInt(offset, (word & 0xffffc00f) | ((gain & 0x3ff) << 4));
                state = withSelector(new EqSnapshot(state.device, state.configuration, usr, state.spk), 255);
                interruptWrite();
            }

            @Override public void loadUsrPreset(int selector) throws IOException {
                read();
                if (selector != Byte.toUnsignedInt(original.configuration[3]) || !Arrays.equals(original.usr, state.usr)) {
                    throw new AssertionError("The load must target the original selector after restoring the gain");
                }
                writes++;
                Device.this.writes++;
                loads++;
                if (!ignoreLoad) state = original;
                if (changeSpkOnLoad) {
                    byte[] spk = original.spk.clone();
                    spk[5] ^= 1;
                    state = new EqSnapshot(original.device, original.configuration, original.usr, spk);
                }
                interruptWrite();
            }

            private void interruptWrite() throws EOFException {
                if (Device.this.writes != dropOnWrite) return;
                closed = true;
                throw new EOFException("Simulated connection lost");
            }

            @Override public void close() {
                closed = true;
            }
        }
    }

    private interface Action { void run() throws IOException; }
    private interface Work { void run(Path directory) throws IOException; }
}
