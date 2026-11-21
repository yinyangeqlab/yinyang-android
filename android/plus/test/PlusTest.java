package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PlusTest {
    private static void check(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Rejected without changing the draft.
        }
    }

    private static PlusPreset.Channel channel(int count, int offset, int level) {
        List<PlusPreset.Band> bands = new ArrayList<>();
        for (int i = 0; i < count; i++)
            bands.add(new PlusPreset.Band(5, offset + 73 * i, -5, 1024));
        return new PlusPreset.Channel(level, bands);
    }

    private static void presets() {
        PlusPreset.Channel shared = channel(20, 100, -20);
        PlusPreset.Channel left = channel(10, 2300, -10), right = channel(10, 3300, -20);
        PlusPreset.Banks layered = PlusPreset.banks(PlusPreset.Mode.LAYERED, shared, left, right, true);
        check(layered.user.bands.size() == 10, "Only shared bands 1–10 in USR");
        for (int ear = 0; ear < 2; ear++)
            check(layered.match[ear].bands.size() == 20 &&
                layered.match[ear].bands.get(0) == shared.bands.get(10) &&
                layered.match[ear].bands.get(10) == (ear == 0 ? left : right).bands.get(0), "SPK overlap and per-side ordering");
        check(layered.match[0].preamp - layered.match[1].preamp == 10, "Preserve preamp difference");
        PlusPreset.Channel[] imported = PlusPreset.parse(PlusPreset.file(layered.match, 20), true, 20);
        PlusPreset.Channel[] cleared={imported[0].clearFilters(),imported[1].clearFilters()};
        PlusPreset.Channel[] reloaded=PlusPreset.parse(PlusPreset.file(cleared,20),true,20);
        check(reloaded[0].bands.isEmpty() && reloaded[1].bands.isEmpty()
                && reloaded[0].preamp==imported[0].preamp && reloaded[1].preamp==imported[1].preamp,
                "Clearing Channel Match removes both filter sets and keeps their preamps");
        check(imported[1].bands.get(10).hz == 3300, "Native stereo export round-trip");
        PlusPreset.Banks userOnly = PlusPreset.banks(PlusPreset.Mode.SHARED, channel(30, 100, -20), left, right, true);
        String userFile = PlusPreset.file(new PlusPreset.Channel[]{userOnly.user}, 30);
        check(userOnly.match == null && PlusPreset.parse(userFile, false, 30)[0].bands.size() == 30, "30 shared without SPK");
        rejects(() -> PlusPreset.banks(PlusPreset.Mode.LAYERED, userOnly.user, left, right, false), "Reject 30 plus match");
        for (int l = 0; l <= 20; l++) for (int r = 0; r <= 20; r++) {
            PlusPreset.Channel a = channel(l, 100, 0), b = channel(r, 300, 0);
            int capacity = PlusPreset.sharedCapacity(a, b);
            PlusPreset.Channel full = channel(capacity, 500, 0);
            PlusPreset.Banks allocated = PlusPreset.banks(PlusPreset.Mode.LAYERED, full, a, b, false);
            check(allocated.user.bands.size() == 10 && allocated.match[0].bands.size() == capacity - 10 + l
                && allocated.match[1].bands.size() == capacity - 10 + r, "Asymmetric allocation");
            rejects(() -> PlusPreset.banks(PlusPreset.Mode.LAYERED, channel(capacity + 1, 500, 0), a, b, false), "Refuse overflow");
        }
        rejects(() -> PlusPreset.parse("CH: 0\nPreamp: 0 dB", true, 10), "Reject incomplete stereo file");
        rejects(() -> PlusPreset.parse("Filter 1: ON PK Fc 100 Hz Gain 0 dB", false, 10), "Require actual Q");
        check(PlusPreset.parse("Filter 1: ON PK Fc 100 Hz Gain -3 dB Q 1.414", false, 10)[0].bands.get(0).q == 1448,
            "Parse and quantize the real Q field");
        check(PlusPreset.parse("Filter 1: ON LS Fc 100 Hz Gain -3 dB", false, 10)[0].bands.get(0).type == 3,
            "AutoEQ shelf with standard Q");
        PlusPreset.Channel precise = new PlusPreset.Channel(0, Arrays.asList(new PlusPreset.Band(5, 1500, 30, 64)));
        check(PlusPreset.parse(PlusPreset.file(new PlusPreset.Channel[]{precise}, 10), false, 10)[0].bands.get(0).q == 64,
            "Keep hardware Q precision in saved files");
        PlusPreset.Channel boost = new PlusPreset.Channel(0, Arrays.asList(new PlusPreset.Band(5, 1000, 100, 1024)));
        PlusPreset.Banks protectedEq = PlusPreset.banks(PlusPreset.Mode.SHARED, boost, left, right, true);
        check(protectedEq.userReduction >= 115 && Math.abs(PlusPreset.response(protectedEq.user, 48000).db(1000) + 1.5) <= .11,
            "Modeled preamp leaves 1.5 dB peak margin");
        PlusPreset.Channel excessive = new PlusPreset.Channel(0, Arrays.asList(new PlusPreset.Band(5, 1000, 500, 1024)));
        rejects(() -> PlusPreset.banks(PlusPreset.Mode.SHARED, excessive, left, right, true), "Never clip required attenuation silently");
    }

    private static byte[] elements(int... data) {
        byte[] bytes = new byte[data.length];
        for (int i = 0; i < data.length; i++) bytes[i] = (byte) data[i];
        return bytes;
    }

    private static byte[] packet(int command, int header, byte[] payload) {
        byte[] report = new byte[128];
        report[0] = 9;
        report[1] = (byte) (payload.length + 3);
        report[2] = (byte) (command >> 8);
        report[3] = (byte) command;
        report[4] = (byte) header;
        System.arraycopy(payload, 0, report, 5, payload.length);
        return report;
    }

    private static void usb() throws IOException {
        check(PlusUsbProtocol.known(0x0a12, 0x5004) && !PlusUsbProtocol.known(0x0a12, 0x4007), "Match Plus USB IDs only");
        ArrayDeque<byte[]> replies = new ArrayDeque<>();
        replies.add(packet(0x9000, 0x80, elements(1, 1, 2, 0, 0, 0, 2, 1, 2, 0, 0, 120,
            3, 2, 1, 0, 3, 0, 0, 0, 0, 0)));
        byte[] battery = new byte[16];
        battery[2] = 89;
        battery[8] = 1 << 2;
        byte[] audio = new byte[16];
        audio[0] = (3 << 3) | 1;
        audio[1] = (byte) (24 << 3);
        audio[4] = (byte) 0x80;
        audio[5] = (byte) 0xbb;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(elements(0x80, 1, 0x01, 0, 0, 0, 0x61, 4));
        data.write(battery);
        data.write(elements(0x51, 4));
        data.write(audio);
        byte[] response = data.toByteArray();
        replies.add(packet(0x9003, 0x81, Arrays.copyOfRange(response, 12, response.length)));
        replies.add(packet(0x9003, 0, Arrays.copyOf(response, 12)));
        PlusUsbProtocol.Status status = PlusUsbProtocol.status(new PlusUsbProtocol.Transport() {
            int sends;
            @Override public void send(byte[] report) {
                check(report.length == 128 && report[0] == 8 && report[2] == (byte) 0x80, "USB request layout");
                byte[] actual = Arrays.copyOfRange(report, 3, 2 + (report[1] & 255));
                check(Arrays.equals(actual, sends++ == 0 ? elements(1, 0, 0x20, 0, 4)
                    : elements(0x90, 2, 0x80, 0x80, 0, 0x61, 0, 0x51, 0)), "Only initialization and status requests");
            }
            @Override public byte[] receive(int timeout) {
                return replies.remove();
            }
        });
        check(status.mode == PlusPreset.Mode.LAYERED && status.firmware.equals("1.0.3"), "Decode Plus mode and firmware");
        check(status.percent == 89 && status.charging && status.sampleRate == 48000 && status.bits == 24
            && status.inputLabel().equals("USB DAC"), "Decode battery and playback");
        try {
            PlusUsbProtocol.elements(elements(0x80, 2, 0, 0, 0, 0));
            throw new AssertionError("Reject malformed TLV data");
        } catch (ProtocolException expected) {
            // Good.
        }
    }

    public static void main(String[] args) throws IOException {
        presets();
        usb();
        ApplyTest.run();
        ChannelSwapTest.run();
        BlindPlusTest.run();
        BlindLevelTest.run();
        CompanionTest.run();
        System.out.println("PASS 5K Plus preset routing, text round-trips, headroom, and USB status fixtures.");
    }
}
