package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

final class ApplyTest {
    static void expect(boolean ok) {
        if (!ok) throw new AssertionError();
    }

    static class Device implements PlusUsbProtocol.Transport {
        final Map<Integer, byte[]> state = new HashMap<>();
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        int index, writes, failAt, badAckAt, fragmentCount;
        boolean mismatch, lost;
        Device() {
            for (int pair[] : new int[][]{{0x80,4},{0x90,4},{0xa0,4},{0xb0,4},{0x93,76},{0xa3,256},{0xb3,196}}) {
                byte[] bytes = new byte[pair[1]];
                new Random(pair[0]).nextBytes(bytes);
                if (pair[1] == 4) bytes[0] = 1;
                state.put(pair[0], bytes);
            }
        }
        void respond(int command, int header, byte[] data) {
            byte[] report = new byte[data.length + 5];
            report[0] = 9; report[1] = (byte) (data.length + 3);
            report[2] = (byte) (command >> 8); report[3] = (byte) command; report[4] = (byte) header;
            System.arraycopy(data, 0, report, 5, data.length);
            queue.add(report);
        }
        public void send(byte[] report) throws IOException {
            if (lost) throw new IOException("Disconnected");
            int cmd = (report[3] & 255) << 8 | (report[4] & 255), end = (report[1] & 255) + 2;
            if (cmd == 0x9002) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                for (int i = 6; i < end; i += 2) {
                    int id = report[i] & 255;
                    byte[] value = state.get(id).clone();
                    if (mismatch && writes >= 4) { value[0] ^= 1; mismatch = false; }
                    bytes.write(id); bytes.write(value.length / 4); bytes.write(value);
                }
                byte[] data = bytes.toByteArray();
                for (int i = 0; i < data.length; i += 70) {
                    int next = Math.min(data.length, i + 70);
                    respond(0x9003, i / 70 | (next == data.length ? 128 : 0), Arrays.copyOfRange(data, i, next));
                }
            } else {
                expect(cmd == 0x9004); // No flash save, reset, volume, or firmware operations.
                int h = report[5] & 255;
                expect((h & 31) == index++);
                collected.write(report, 6, end - 6);
                if ((h & 128) == 0) {
                    respond(0x9001, 128, new byte[]{(byte) h, (byte) (++fragmentCount == badAckAt ? 1 : 0)});
                    if (fragmentCount == badAckAt) { index = 0; collected.reset(); }
                } else {
                    byte[] data = collected.toByteArray();
                    expect(data.length == 2 + (data[1] & 255) * 4);
                    state.put(data[0] & 255, Arrays.copyOfRange(data, 2, data.length));
                    collected.reset(); index = 0;
                    if (++writes == failAt) { lost = true; throw new IOException("Cable removed"); }
                }
            }
        }
        public byte[] receive(int timeout) throws IOException {
            if (queue.isEmpty()) throw new IOException("No reply");
            return queue.remove();
        }
    }

    private static PlusUsbProtocol.Status status(String firmware, int size) {
        return new PlusUsbProtocol.Status(firmware, PlusPreset.Mode.LAYERED, null, null, size);
    }

    static void run() throws IOException {
        PlusPreset.Channel common = new PlusPreset.Channel(-30, Collections.nCopies(23, new PlusPreset.Band(3, 250, -27, 1522)));
        PlusPreset.Channel left = new PlusPreset.Channel(-12, Collections.nCopies(4, new PlusPreset.Band(1, 13000, 0, 721)));
        PlusPreset.Channel right = new PlusPreset.Channel(-19, Collections.nCopies(7, new PlusPreset.Band(5, 6800, -4, 2921)));
        PlusPreset.Banks plan = PlusPreset.banks(PlusPreset.Mode.LAYERED, common, left, right, false);
        for (int packetSize : new int[]{40,120}) {
            Device d = new Device();
            byte[] attrs = Arrays.copyOf(d.state.get(0xa3), 12); attrs[0] |= 1;
            PlusApply apply = new PlusApply(d, status("1.0.3", packetSize));
            apply.apply(PlusPreset.Mode.LAYERED, plan);
            byte[] spk = d.state.get(0xa3);
            ByteBuffer bytes = ByteBuffer.wrap(spk).order(ByteOrder.LITTLE_ENDIAN);
            expect(Arrays.equals(attrs, Arrays.copyOf(spk, 12)));
            expect(bytes.getShort(12) == -12 && bytes.getShort(14) == -19);
            expect(bytes.getShort(16+2*13) == 13000 && bytes.getShort(16+2*33) == 6800);
            expect(bytes.getInt(96+4*19) == (1024 << 14));
            expect(bytes.getInt(96+4*39) == (5 | (1020 << 4) | (2921 << 14)));
            expect(bytes.getInt(96) == (3 | (997 << 4) | (1522 << 14)));
            apply.apply(PlusPreset.Mode.LAYERED, plan);
            byte[] preserved = spk.clone();
            PlusPreset.Banks single = PlusPreset.banks(PlusPreset.Mode.SHARED, common, left, right, false);
            apply.apply(PlusPreset.Mode.SHARED, single);
            expect(d.state.get(0x80)[0] == 17 && Arrays.equals(preserved, d.state.get(0xa3)));
        }
        for (int failure = 0; failure < 3; failure++) {
            Device d = new Device();
            Map<Integer, byte[]> before = new HashMap<>(d.state);
            if (failure == 0) d.mismatch = true;
            else if (failure == 1) d.badAckAt = 1;
            else d.failAt = 2;
            try {
                new PlusApply(d,status("1.0.3",120)).apply(PlusPreset.Mode.LAYERED, plan);
                throw new AssertionError("False success on incomplete apply");
            } catch (IOException expected) {
                if (failure == 2) expect(expected.getMessage().contains("partially"));
                else {
                    expect(expected.getMessage().contains("restored and verified"));
                    for (int id : before.keySet()) expect(Arrays.equals(before.get(id), d.state.get(id)));
                }
            }
        }
        Device d = new Device();
        try { new PlusApply(d, status("2.0.0",120)); throw new AssertionError(); }
        catch (IOException expected) { expect(d.writes == 0); }
        byte[] usb = new byte[]{8,6,(byte)128,1,0,32,0,4};
        expect(Arrays.equals(PlusGaia.encode(usb, PlusGaia.V3), new byte[]{(byte)240,3,1,0,32,0,4}));
        byte[] normalized = {9,5,(byte)144,1,(byte)128,1,0};
        expect(Arrays.equals(normalized, PlusGaia.decode(new byte[]{(byte)240,3,(byte)144,1,(byte)128,1,0}, PlusGaia.V3)));
        expect(Arrays.equals(normalized, PlusGaia.decode(new byte[]{(byte)240,1,(byte)144,1,0,(byte)128,1,0}, PlusGaia.LEGACY)));
        expect(PlusGaia.decode(new byte[]{0,1,0,0},PlusGaia.V3) == null);
        System.out.println("PASS live-bank encoding, fragmented transfer, readback, restore, disconnect, firmware gate and Bluetooth envelopes.");
    }
}
