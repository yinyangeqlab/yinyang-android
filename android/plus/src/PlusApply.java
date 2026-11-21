package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Live EQ editing only. The device's separate named-preset save command is never issued. */
final class PlusApply {
    private static final int MODE = 0x80;
    private final PlusUsbProtocol.Transport io;
    private final int packetSize;

    PlusApply(PlusUsbProtocol.Transport io, PlusUsbProtocol.Status status) throws IOException {
        this.io = io;
        packetSize = status.packetSize;
        if (!"1.0.3".equals(status.firmware) || packetSize < 40 || packetSize > 120)
            throw new ProtocolException("This preview supports live EQ on 5K Plus firmware 1.0.3.");
    }

    static byte[] preset(byte[] previous, PlusPreset.Channel[] channels, int slots) throws IOException {
        int count = slots * channels.length;
        if (previous.length != 16 + 6 * count) throw new ProtocolException("Unexpected preset size.");
        byte[] next = previous.clone();
        next[0] |= 1; // PEQ. Preserve impedance, sensitivity, spatial settings and reserved fields.
        ByteBuffer data = ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN);
        for (int ear = 0; ear < channels.length; ear++) {
            PlusPreset.Channel channel = channels[ear];
            if (channel.bands.size() > slots) throw new ProtocolException("The EQ exceeds this bank.");
            data.putShort(12 + 2 * ear, (short) channel.preamp);
            for (int slot = 0; slot < slots; slot++) {
                PlusPreset.Band b = slot < channel.bands.size() ? channel.bands.get(slot) : null;
                int i = ear * slots + slot;
                int params = b == null ? 1024 << 14 : b.type | ((b.gain & 1023) << 4) | (b.q << 14);
                data.putShort(16 + 2 * i, (short) (b == null ? 1000 : b.hz));
                data.putInt(16 + 2 * count + 4 * i, params);
            }
        }
        return next;
    }

    static int length(int id) {
        switch (id) {
            case PlusChannelSwap.DAC: return 8;
            case 0x93: return 76;
            case 0xa3: return 256;
            case 0xb3: return 196;
            default: return 4;
        }
    }

    Map<Integer, byte[]> read(int... ids) throws IOException {
        Map<Integer, byte[]> values = PlusUsbProtocol.read(io, ids);
        for (int id : ids) PlusUsbProtocol.required(values, id, length(id));
        return values;
    }

    void write(int id, byte[] value) throws IOException {
        PlusUsbProtocol.set(io, packetSize, id, value);
    }

    void verify(Map<Integer, byte[]> expected, int... ids) throws IOException {
        Map<Integer, byte[]> actual = read(ids);
        for (int id : ids) if (!Arrays.equals(expected.get(id), actual.get(id)))
            throw new ProtocolException("EQ readback differed for bank 0x" + Integer.toHexString(id) + ".");
    }

    static final int[] WORKING = {MODE, 0x90, 0x93, 0xa0, 0xa3, 0xb0, 0xb3};

    private static int[] active(PlusPreset.Mode mode) {
        return mode == PlusPreset.Mode.LAYERED ? new int[]{MODE, 0x90, 0x93, 0xa0, 0xa3} : new int[]{MODE, 0xb0, 0xb3};
    }

    static Map<Integer,byte[]> plan(Map<Integer,byte[]> before, PlusPreset.Mode mode, PlusPreset.Banks banks) throws IOException {
        boolean layered = mode == PlusPreset.Mode.LAYERED;
        int[] ids = active(mode);
        Map<Integer, byte[]> after = new LinkedHashMap<>();
        for (Map.Entry<Integer,byte[]> entry : before.entrySet()) after.put(entry.getKey(), entry.getValue().clone());
        after.get(MODE)[0] = (byte) ((before.get(MODE)[0] & 15) | (mode.ordinal() << 4));
        for (int i = 1; i < ids.length; i += 2) {
            int group = ids[i], presetId = ids[i + 1];
            after.get(group)[0] |= 16;
            after.get(group)[3] = (byte) 255; // Working edits, outside the named preset slots.
            PlusPreset.Channel[] channels = group == 0xa0 ? banks.match : new PlusPreset.Channel[]{banks.user};
            after.put(presetId, preset(before.get(presetId), channels, group == 0xa0 ? 20 : layered ? 10 : 30));
        }
        return after;
    }

    /** Apply only working selectors, blocks and mode. No named-slot write is used. */
    void moveWorking(Map<Integer,byte[]> from, Map<Integer,byte[]> to, boolean restore) throws IOException {
        verify(from,WORKING);
        boolean modeChanged=!Arrays.equals(from.get(MODE),to.get(MODE));
        if (modeChanged && !restore) write(MODE,to.get(MODE));
        for (int i=1;i<WORKING.length;i+=2) {
            int selector=WORKING[i], data=WORKING[i+1];
            if (Arrays.equals(from.get(selector),to.get(selector)) && Arrays.equals(from.get(data),to.get(data))) continue;
            // Work with an unsaved selector before the payload. Restore a named selector last.
            if ((to.get(selector)[3]&255)==255) {
                write(selector,to.get(selector)); write(data,to.get(data));
            } else {
                write(data,to.get(data)); write(selector,to.get(selector));
            }
        }
        if (modeChanged && restore) write(MODE,to.get(MODE));
        verify(to,WORKING);
    }

    void apply(PlusPreset.Mode mode, PlusPreset.Banks banks) throws IOException {
        int[] ids=active(mode);
        Map<Integer,byte[]> before = read(ids);
        Map<Integer,byte[]> after = plan(before,mode,banks);
        // Confirm the initial read immediately before the first write.
        verify(before, ids);
        boolean identical = true;
        for (int id : ids) identical &= Arrays.equals(before.get(id), after.get(id));
        if (identical) return;
        boolean changedMode = !Arrays.equals(before.get(MODE), after.get(MODE));
        try {
            if (changedMode) write(MODE, after.get(MODE));
            for (int i = 1; i < ids.length; i++) write(ids[i], after.get(ids[i]));
            verify(after, ids);
        } catch (IOException failure) {
            try {
                // Restore the working data and selectors, including a previously unsaved selection.
                for (int i = 1; i < ids.length; i += 2) {
                    write(ids[i+1], before.get(ids[i+1]));
                    write(ids[i], before.get(ids[i]));
                }
                if (changedMode) write(MODE, before.get(MODE));
                verify(before, ids);
            } catch (IOException recovery) {
                throw new IOException("Apply interrupted. The current EQ could be partially changed. Reconnect and apply the complete phone preset again. " + failure.getMessage(), recovery);
            }
            throw new IOException("Apply failed. The previous EQ was restored and verified. " + failure.getMessage(), failure);
        }
    }
}
