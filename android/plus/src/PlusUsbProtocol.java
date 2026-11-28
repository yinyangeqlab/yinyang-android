package com.yinyangeq.qudelixprobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

final class PlusUsbProtocol {
    static final int VID = 0x0a12;
    static final int REPORT_SIZE = 128;
    private static final int DEVICE_CONFIG = 0x80;
    private static final int INIT = 0x0100, INIT_REPLY = 0x9000, READ = 0x9002, READ_REPLY = 0x9003;
    private static final int BATTERY = 0x61, AUDIO = 0x51;

    interface Transport {
        void send(byte[] report) throws IOException;
        byte[] receive(int timeoutMillis) throws IOException;
    }

    static final class Status {
        final String firmware;
        final PlusPreset.Mode mode;
        final int percent, sampleRate, bits, input, packetSize;
        final boolean charging, playing;
        final String output;

        Status(String firmware, PlusPreset.Mode mode, byte[] battery, byte[] audio, int packetSize) {
            this.packetSize = packetSize;
            this.firmware = firmware;
            this.mode = mode;
            percent = battery == null || battery.length != 16 || u8(battery[2]) > 100 ? -1 : u8(battery[2]);
            charging = percent >= 0 && bits(battery, 66, 3) == 1;
            playing = audio != null && audio.length == 16 && bits(audio, 0, 1) == 1;
            input = playing ? bits(audio, 3, 4) : 0;
            bits = playing ? bits(audio, 11, 6) : 0;
            sampleRate = playing ? bits(audio, 32, 24) : 0;
            int route = audio != null && audio.length == 16 ? bits(audio, 7, 4) : -1;
            output = route == 0 ? "4.4 mm balanced" : route == 1 ? "3.5 mm single-ended" : "";
        }

        String inputLabel() {
            if (!playing) return "Idle";
            if (input == 3) return "USB DAC";
            if (input == 1 || input == 2) return "Bluetooth " + input;
            if (input >= 5 && input <= 7) return "LE Audio " + (input - 4);
            if (input == 8) return "LE Audio broadcast";
            return "Active";
        }
    }

    static boolean known(int vendor, int product) {
        return vendor == VID && ((product >= 0x5000 && product <= 0x5004) ||
            (product >= 0x5010 && product <= 0x5014) || product == 0x5020 || product == 0x5021 ||
            product == 0x5030 || product == 0x5031);
    }

    static Status status(Transport io) throws IOException {
        Map<Integer, byte[]> initial = exchange(io, INIT, INIT_REPLY, new byte[]{0x20, 0, 4});
        byte[] model = required(initial, 1, 4);
        byte[] version = required(initial, 2, 4);
        byte[] firmware = required(initial, 3, 8);
        if (model[0] != 2 || model[1] != 0 || version[0] != 2)
            throw new ProtocolException("This device did not confirm the 5K Plus protocol.");
        Map<Integer, byte[]> result = exchange(io, READ, READ_REPLY,
            new byte[]{(byte) 0x80, (byte) DEVICE_CONFIG, 0, BATTERY, 0, AUDIO, 0});
        byte[] configuration = required(result, DEVICE_CONFIG, 4);
        int mode = u8(configuration[0]) >> 4;
        if (mode > 1) throw new ProtocolException("The device returned an unknown EQ mode.");
        String display = u8(firmware[0]) + "." + u8(firmware[1]) + "." + u8(firmware[2]);
        return new Status(display, PlusPreset.Mode.values()[mode], result.get(BATTERY), result.get(AUDIO), u8(version[3]));
    }

    private static Map<Integer, byte[]> exchange(Transport io, int command, int reply, byte[] data) throws IOException {
        send(io, command, data);
        return elements(receive(io, reply));
    }

    static Map<Integer, byte[]> read(Transport io, int... ids) throws IOException {
        byte[] request = new byte[1 + 2 * ids.length];
        request[0] = (byte) 0x80;
        for (int i = 0; i < ids.length; i++) request[1 + 2 * i] = (byte) ids[i];
        return exchange(io, READ, READ_REPLY, request);
    }

    static byte[] slotName(Transport io,int group,int slot) throws IOException {
        int tag = 0x94 + 16 * group;
        Map<Integer,byte[]> reply = exchange(io,READ,READ_REPLY,new byte[]{(byte)128,(byte)tag,1,(byte)group,(byte)slot,0,0});
        byte[] value = reply.get(tag);
        if (value == null || value.length < 4 || value[0] != group || value[1] != slot || (value[2]&255)>32 ||
                (value[2]&255)>value.length-4) throw new IOException("The Plus returned an invalid preset name.");
        return Arrays.copyOfRange(value,4,4+(value[2]&255));
    }

    static void stopPairing(Transport link) throws IOException {
        // UI event 3 closes the current window. It leaves all stored bonds intact.
        send(link,0x9006,new byte[]{(byte)128,(byte)0xd0,1,3,0,0,0});
        for(int i=0;i<4;i++){
            byte[] status=required(read(link,0x31),0x31,48);
            if((status[0]&2)==0)return;
            try{Thread.sleep(75);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
        }
        throw new IOException("The Plus still reports an open pairing window.");
    }

    static void automaticPairing(Transport link,int packetSize,boolean enabled) throws IOException {
        byte[] current=required(read(link,0x30),0x30,8);
        byte[] wanted=current.clone();
        wanted[2]=(byte)(enabled?current[2]|128:current[2]&127);
        if(!Arrays.equals(current,wanted))set(link,packetSize,0x30,wanted);
        if(!Arrays.equals(required(read(link,0x30),0x30,8),wanted))
            throw new IOException("The Plus did not confirm its automatic pairing setting.");
        if(!enabled && (required(read(link,0x31),0x31,48)[0]&2)!=0)stopPairing(link);
    }

    static void saveSlot(Transport io,int group,int slot) throws IOException {
        presetAction(io,0x30,group,slot);
    }

    static void deleteSlot(Transport io,int group,int slot) throws IOException {
        presetAction(io,0x31,group,slot);
    }

    private static void presetAction(Transport io,int action,int group,int slot) throws IOException {
        if(group<0 || group>2 || slot<0 || slot>=20)throw new ProtocolException("Invalid preset slot.");
        send(io,0x9006,new byte[]{(byte)128,(byte)action,1,(byte)group,(byte)slot,0,0});
    }

    static void set(Transport io, int packetSize, int id, byte[] value) throws IOException {
        if (packetSize < 40 || packetSize > 120 || value.length % 4 != 0)
            throw new ProtocolException("Unsupported Plus write format.");
        byte[] data = new byte[value.length + 2];
        data[0] = (byte) id;
        data[1] = (byte) (value.length / 4);
        System.arraycopy(value, 0, data, 2, value.length);
        int offset = 0, index = 0;
        while (offset < data.length) {
            int count = Math.min(packetSize - 1, data.length - offset);
            boolean last = offset + count == data.length;
            byte[] fragment = new byte[count + 1];
            fragment[0] = (byte) (index | (last ? 128 : 0));
            System.arraycopy(data, offset, fragment, 1, count);
            send(io, 0x9004, fragment);
            if (!last) {
                byte[] ack = receive(io, 0x9001);
                if (ack.length != 2 || u8(ack[0]) != index || ack[1] != 0)
                    throw new ProtocolException("The 5K Plus declined an EQ fragment.");
            }
            offset += count;
            index++;
        }
    }

    // Internal command envelope follows the USB report layout. The Bluetooth adapter
    // translates only the command and payload into the negotiated GAIA framing.
    private static void send(Transport io, int command, byte[] data) throws IOException {
        byte[] outgoing = new byte[REPORT_SIZE];
        outgoing[0] = 8;
        outgoing[1] = (byte) (data.length + 3);
        outgoing[2] = (byte) 0x80;
        outgoing[3] = (byte) (command >> 8);
        outgoing[4] = (byte) command;
        System.arraycopy(data, 0, outgoing, 5, data.length);
        io.send(outgoing);
    }

    private static byte[] receive(Transport io, int reply) throws IOException {
        Fragments fragments = new Fragments();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            int remaining = Math.max(1, (int) ((deadline - System.nanoTime()) / 1_000_000));
            byte[] report = io.receive(remaining);
            if (report.length < 5 || report[0] != 9 || ((u8(report[2]) << 8) | u8(report[3])) != reply) continue;
            byte[] payload = fragments.append(report);
            if (payload != null) return payload;
        }
        throw new ProtocolException("The 5K Plus did not answer the control request.");
    }

    static final class Fragments {
        private final byte[][] items = new byte[32][];
        private int last = -1, count, size;

        byte[] append(byte[] report) throws IOException {
            int end = u8(report[1]) + 2, header = u8(report[4]), index = header & 31;
            if (end > report.length || end < 5 || (header & 0x60) != 0 || items[index] != null ||
                (last >= 0 && (index > last || (header & 128) != 0)))
                throw new ProtocolException("Invalid 5K Plus USB report.");
            if ((header & 128) != 0) {
                last = index;
                for (int i = index + 1; i < items.length; i++)
                    if (items[i] != null) throw new ProtocolException("Invalid 5K Plus fragment order.");
            }
            items[index] = Arrays.copyOfRange(report, 5, end);
            size += end - 5;
            count++;
            if (size > 1022) throw new ProtocolException("The 5K Plus reply is too large.");
            if (last == -1 || count != last + 1) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
            for (int i = 0; i <= last; i++) bytes.write(items[i], 0, items[i].length);
            return bytes.toByteArray();
        }
    }

    static Map<Integer, byte[]> elements(byte[] payload) throws IOException {
        Map<Integer, byte[]> response = new HashMap<>();
        for (int start = 0; start < payload.length;) {
            if (start + 2 > payload.length) throw new ProtocolException("Incomplete 5K Plus response header.");
            int id = u8(payload[start]), end = start + 2 + u8(payload[start + 1]) * 4;
            if (end > payload.length || response.containsKey(id)) throw new ProtocolException("Invalid 5K Plus response data.");
            response.put(id, Arrays.copyOfRange(payload, start + 2, end));
            start = end;
        }
        return response;
    }

    static byte[] required(Map<Integer, byte[]> reply, int id, int length) throws IOException {
        byte[] value = reply.get(id);
        if (value == null || value.length != length) throw new ProtocolException("The 5K Plus response has an unexpected layout.");
        return value;
    }

    private static int u8(byte b) {
        return b & 255;
    }

    private static int bits(byte[] bytes, int start, int count) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            int offset = start + i;
            result |= ((bytes[offset / 8] >>> (offset % 8)) & 1) << i;
        }
        return result;
    }
}
