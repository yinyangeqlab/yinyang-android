package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public final class ProbeTest {
    private static int passed;

    public static void main(String[] args) throws IOException {
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(Simulation.REPLY)) {
            @Override public int read(byte[] data, int offset, int length) throws IOException {
                return super.read(data, offset, Math.min(1, length));
            }
        };
        check(GaiaProbe.readInitialStateReply(fragmented, message -> {}).contains("bytes 01 02 03 04"),
                "fragmented replies identify device firmware");
        checkSession();
        checkEqSnapshot();

        byte[] notification = frame(0xf001, 0xa000, 0, 4);
        check(read(join(notification, Simulation.REPLY)).contains("bytes 01 02 03 04"), "skip unrelated complete frame");
        expectError(() -> read(frame(0xf001, 0xa002, 0, 3)), "another controller");
        expectError(() -> read(frame(0xf001, 0x8101, 7)), "status 7");
        expectError(() -> read(frame(0xf001, 0x8101)), "no status byte");
        expectError(() -> read(Arrays.copyOf(Simulation.REPLY, Simulation.REPLY.length - 1)), null);
        expectError(() -> read(frame(0xf001, 0x8101, 0, 0, 1, 3)), "ends before the firmware field");
        expectError(() -> read(frame(0xf001, 0x8101, 0, 0, 1, 2, 1, 2, 3, 4)), "result 2");
        byte[] checksum = Simulation.REPLY.clone();
        checksum[2] = 1;
        expectError(() -> read(checksum), "Unsupported GAIA frame header");
        byte[] unrelated = new byte[0];
        for (int i = 0; i < 16; i++) unrelated = join(unrelated, notification);
        byte[] frameLimit = join(unrelated, Simulation.REPLY);
        expectError(() -> read(frameLimit), "first 16 frames");
        String successful = Simulation.run();
        check(successful.contains("Read back · Peak 1,000 Hz, -1.0 dB"), "read-back shows the observed test gain");
        check(successful.contains("Restored and verified · Peak 1,000 Hz, +0.0 dB"), "full offline scenario reports restoration");
        String interrupted = Simulation.runInterrupted();
        check(interrupted.contains("Read after reconnect · Peak 1,000 Hz, -1.0 dB"), "recovery reports the state after reconnect");
        check(interrupted.contains("Restored and verified · Peak 1,000 Hz, +0.0 dB"), "recovery scenario reports restoration");

        Simulation.Device device = new Simulation.Device();
        Simulation.Preset prior = device.read();
        Simulation.exercise(device, () -> {});
        check(prior.equals(device.read()), "verified rollback on success");

        try {
            Simulation.exercise(device, () -> { throw new IllegalStateException("Simulated verification failure."); });
            throw new AssertionError("Expected injected verification failure.");
        } catch (IllegalStateException expected) {
            check(prior.equals(device.read()), "verified rollback after failure");
        }

        expectError(() -> Simulation.exercise(device, device::disconnect), "connection lost");
        device.reconnect();
        check(!prior.equals(device.read()), "interrupted write needs reconciliation");
        Simulation.restore(device, prior);
        check(prior.equals(device.read()), "restored complete saved state after reconnect");
        checkLog();
        System.out.println(passed + " probe and simulator checks passed.");
    }

    private static String read(byte[] bytes) throws IOException {
        return GaiaProbe.readInitialStateReply(new ByteArrayInputStream(bytes), message -> {});
    }

    private static void checkSession() throws IOException {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        List<String> trace = new ArrayList<>();
        check(new GaiaProbe.Connection(new ByteArrayInputStream(Simulation.REPLY), sent, trace::add)
                .readDevice().describe().contains("bytes 01 02 03 04"), "initial state reply is enough to complete the probe");
        byte[] request = frame(0xf001, 0x0100, 0, 0, 4);
        check(Arrays.equals(sent.toByteArray(), request), "exactly one initial state request, without API version or registration");
        check(trace.stream().anyMatch(line -> line.contains("Received F001/8101 (8 bytes)")), "initial state reply metadata is logged");
        ByteArrayOutputStream failed = new ByteArrayOutputStream();
        expectError(() -> new GaiaProbe.Connection(new ByteArrayInputStream(new byte[0]), failed, message -> {}).readDevice(), null);
        check(Arrays.equals(failed.toByteArray(), request), "early disconnect stops further sends");
        OutputStream closed = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("closed"); }
        };
        expectError(() -> new GaiaProbe.Connection(new ByteArrayInputStream(Simulation.REPLY), closed, message -> {}).readDevice(),
                "could not send the initial device state request");
    }

    private static void checkEqSnapshot() throws IOException {
        byte[] config = new byte[19];
        config[0] = (byte) 0x80;
        config[10] = 1;
        byte[] eqConfigReply = reply(0x8121, config);
        byte[] usr = new byte[88];
        byte[] spk = new byte[128];
        for (int i = 0; i < usr.length; i++) usr[i] = (byte) (i + 50);
        for (int i = 0; i < spk.length; i++) spk[i] = (byte) (i * 2);
        byte[] start = join(frame(0xf001, 0x8101, 0, 0, 1, 3, 0x83, 0x90, 0x9a, 0x65), eqConfigReply);
        byte[] replyStream = start;
        replyStream = join(replyStream, segment(0, 0x10, 0, usr, 50));
        replyStream = join(replyStream, segment(0, 0x11, 50, usr, 38));
        replyStream = join(replyStream, segment(1, 0x20, 0, spk, 50));
        replyStream = join(replyStream, segment(1, 0x21, 50, spk, 50));
        replyStream = join(replyStream, segment(1, 0x22, 100, spk, 28));

        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        byte[] snapshotReplies = join(replyStream, eqConfigReply);
        GaiaProbe.Connection connection = new GaiaProbe.Connection(new ByteArrayInputStream(join(snapshotReplies,
                Arrays.copyOfRange(snapshotReplies, Simulation.REPLY.length, snapshotReplies.length))), sent, message -> {});
        EqSnapshot snapshot = connection.read();
        String json = snapshot.toJson();
        Base64.Encoder base64 = Base64.getEncoder();
        check(json.contains("\"firmware\": \"3.2.9\""), "snapshot decodes the firmware version");
        check(json.contains("\"verifiedConfigurationBase64\": \"" + base64.encodeToString(config) + "\""), "exact configuration preserved");
        check(json.contains("\"usrPresetBase64\": \"" + base64.encodeToString(usr) + "\""), "exact USR bytes preserved across segments");
        check(json.contains("\"spkPresetBase64\": \"" + base64.encodeToString(spk) + "\""), "exact SPK bytes preserved across segments");
        byte[] requestConfig = frame(0xf001, 0x0120, 0x80);
        byte[] expected = join(frame(0xf001, 0x0100, 0, 0, 4), requestConfig);
        expected = join(expected, frame(0xf001, 0x0123, 1));
        expected = join(expected, frame(0xf001, 0x0123, 2));
        byte[] captureRequests = join(expected, requestConfig);
        check(Arrays.equals(sent.toByteArray(), captureRequests), "capture sends only the requested reads");
        connection.read();
        check(Arrays.equals(sent.toByteArray(), join(captureRequests,
                Arrays.copyOfRange(captureRequests, frame(0xf001, 0x0100, 0, 0, 4).length, captureRequests.length))),
                "a fresh EQ read on the same socket reuses the initial device identity");

        byte[] changed = config.clone();
        changed[6] = 1;
        byte[] inconsistent = join(replyStream, reply(0x8121, changed));
        expectError(() -> capture(inconsistent), "configuration changed during the read");
        byte[] overlapping = join(start, join(segment(0, 0x10, 0, usr, 50), segment(0, 0x11, 40, usr, 38)));
        expectError(() -> capture(overlapping), "did not form a complete block");
        byte[] missing = join(start, segment(0, 0x00, 0, usr, 50));
        expectError(() -> capture(missing), "did not form a complete block");
        byte[] wrongGroup = join(start, segment(1, 0x10, 0, spk, 50));
        expectError(() -> capture(wrongGroup), "did not form a complete block");
        byte[] differentLayout = join(Simulation.REPLY, frame(0xf001, 0x8121, 0, 0x80, 2));
        expectError(() -> capture(differentLayout), "requires the verified USR/SPK configuration layout");
        expectError(() -> capture(frame(0xf001, 0x8101, 0, 0, 2, 3, 1, 2, 3, 4)), "requires an original Qudelix-5K");
    }

    private static EqSnapshot capture(byte[] replies) throws IOException {
        return EqSnapshot.read(new GaiaProbe.Connection(new ByteArrayInputStream(replies),
                new ByteArrayOutputStream(), message -> {}));
    }

    private static byte[] segment(int group, int position, int offset, byte[] preset, int length) {
        byte[] header = {(byte) group, (byte) position, 0, 0, (byte) (offset >> 8), (byte) offset};
        return reply(0x8128, join(header, Arrays.copyOfRange(preset, offset, offset + length)));
    }

    private static byte[] reply(int command, byte[] data) {
        int[] body = new int[data.length + 1];
        for (int i = 0; i < data.length; i++) body[i + 1] = Byte.toUnsignedInt(data[i]);
        return frame(0xf001, command, body);
    }

    private static void checkLog() throws IOException {
        Path root = Files.createTempDirectory("probe-log-test");
        try {
            ProbeLog log = new ProbeLog(root.toFile());
            log.record("initial probe started");
            check(new ProbeLog(root.toFile()).snapshot(null).contains("initial probe started"), "diagnostics survive an app restart");
            String filler = new String(new char[4000]).replace('\0', 'x');
            for (int i = 0; i < 16; i++) log.record(filler);
            log.record("fresh probe result");
            String exported = log.snapshot(null);
            check(exported.indexOf("initial probe started") < exported.indexOf("fresh probe result"), "rotation preserves export order");
            for (int i = 0; i < 35; i++) log.record(filler);
            String rotated = log.snapshot(null);
            check(rotated.length() <= 128 * 1024 && !rotated.contains("initial probe started"), "diagnostic storage is bounded");
            String report = "Checked at 2026-11-18T20:18:43Z\n\nREAD PASS · Audio\nStatus decoded.\n\n"
                    + "NEEDS REVIEW · EQ\n" + filler + "\nFull error detail.";
            String savedOnly = log.snapshot(report);
            check(savedOnly.startsWith(rotated) && savedOnly.contains(report), "previously saved report is included");
            log.recordCompatibilityReport(report);
            String combined = new ProbeLog(root.toFile()).snapshot(report);
            check(combined.contains("===== Firmware compatibility report =====\n\n" + report
                    + "\n\n===== End firmware compatibility report ====="), "report keeps formatting and complete result");
            check(combined.indexOf(report) == combined.lastIndexOf(report), "latest report appears once");
            for (int i = 0; i < 35; i++) log.record(filler);
            check(!log.snapshot(null).contains(report) && log.snapshot(report).contains(report),
                    "latest report is still exportable after log rotation");
        } finally {
            Files.deleteIfExists(root.resolve("probe-debug.log"));
            Files.deleteIfExists(root.resolve("probe-debug.previous.log"));
            Files.delete(root);
        }
    }

    private static byte[] frame(int vendor, int command, int... payload) {
        byte[] bytes = {(byte) 0xff, 1, 0, (byte) payload.length,
                (byte) (vendor >> 8), (byte) vendor, (byte) (command >> 8), (byte) command};
        byte[] data = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) data[i] = (byte) payload[i];
        return join(bytes, data);
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed++;
    }

    private static void expectError(ProbeAction action, String message) throws IOException {
        try {
            action.run();
            throw new AssertionError("Expected IOException: " + message);
        } catch (IOException error) {
            if (message == null && !(error instanceof java.io.EOFException)) throw error;
            if (message != null && !error.getMessage().contains(message)) throw error;
            passed++;
        }
    }

    private interface ProbeAction {
        void run() throws IOException;
    }
}
