package com.yinyangeq.qudelixprobe;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class GaiaProbe {
    static final String EDITOR_FIRMWARE = "3.2.9";
    static final UUID SERIAL_PORT = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private GaiaProbe() {}

    static String readInitialStateReply(InputStream input, Consumer<String> trace) throws IOException {
        return parseDevice(new Reader(input, trace).receive("initial device state", 0x8101), trace).describe();
    }

    private static Device parseDevice(byte[] data, Consumer<String> trace) throws ProtocolException {
        if (data.length < 7) throw new ProtocolException("The initial state reply ends before the firmware field.");
        if (data[2] != 3) {
            throw new ProtocolException("The 5K declined the initial state request (result "
                    + Byte.toUnsignedInt(data[2]) + ").");
        }
        int model = Byte.toUnsignedInt(data[0]) << 8 | Byte.toUnsignedInt(data[1]);
        Device device = new Device(model, Arrays.copyOfRange(data, 3, 7));
        trace.accept("Device model " + model + " · firmware " + device.version + " (" + device.firmwareBytes + ")");
        return device;
    }

    static final class Device {
        final int model;
        final String version;
        final String firmwareBytes;
        final byte[] firmwareField;

        Device(int model, byte[] field) {
            this.model = model;
            firmwareField = field.clone();
            int versionBits = Byte.toUnsignedInt(field[0]) | Byte.toUnsignedInt(field[1]) << 8;
            version = (versionBits & 63) + "." + ((versionBits >> 6) & 63) + "." + (versionBits >> 12);
            firmwareBytes = String.format(Locale.ROOT, "%02X %02X %02X %02X",
                    Byte.toUnsignedInt(field[0]), Byte.toUnsignedInt(field[1]),
                    Byte.toUnsignedInt(field[2]), Byte.toUnsignedInt(field[3]));
        }

        String describe() {
            return "5K responded. Firmware " + version + " · bytes " + firmwareBytes + ".";
        }
    }

    private enum Request {
        INITIAL_STATE("initial device state", 0x0100, 0, 0, 4),
        EQ_CONFIGURATION("EQ configuration", 0x0120, 0x80),
        USR_PRESET("USR preset", 0x0123, 1),
        SPK_PRESET("SPK preset", 0x0123, 2);

        final String name;
        final int command;
        final int[] body;

        Request(String name, int command, int... body) {
            this.name = name;
            this.command = command;
            this.body = body;
        }
    }

    // The caller applies the socket deadline and closes the transport on every exit.
    static final class Connection implements ReversibleEqTest.Link {
        private final OutputStream output;
        private final Reader reader;
        private final Consumer<String> trace;
        private final Closeable transport;
        private Device device;

        Connection(InputStream input, OutputStream output, Consumer<String> log) {
            this(input, output, log, () -> {
                try {
                    input.close();
                } finally {
                    output.close();
                }
            });
        }

        Connection(InputStream input, OutputStream output, Consumer<String> log, Closeable transport) {
            this.output = output;
            this.transport = transport;
            long start = System.nanoTime();
            trace = message -> log.accept("+" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    + " ms · " + message);
            reader = new Reader(input, trace);
        }

        Device readDevice() throws IOException {
            if (device == null) {
                send(Request.INITIAL_STATE);
                device = parseDevice(reader.receive(Request.INITIAL_STATE.name, 0x8101), trace);
            }
            return device;
        }

        byte[] readEqConfiguration() throws IOException {
            send(Request.EQ_CONFIGURATION);
            return reader.receive(Request.EQ_CONFIGURATION.name, 0x8121);
        }

        void requestPreset(EqSnapshot.Group group) throws IOException {
            send(group == EqSnapshot.Group.USR ? Request.USR_PRESET : Request.SPK_PRESET);
        }

        byte[] readUsrName(int slot) throws IOException {
            return readSlotName(0, slot);
        }

        byte[] readSlotName(int group,int slot) throws IOException {
            send("slot name", 0x070b, new byte[]{(byte)group, (byte) slot});
            return reader.receive("USR slot name", 0x870c);
        }

        byte[] readPresetSegment() throws IOException {
            return reader.receive("preset", 0x8124, 0x8125, 0x8128);
        }

        @Override public EqSnapshot read() throws IOException {
            return EqSnapshot.read(this);
        }

        @Override public void editTestPeak(ReversibleEqTest.Band band, boolean restore) throws IOException {
            int gain = restore ? band.gain : band.gain - 10;
            byte[] body = ByteBuffer.allocate(10)
                    .put((byte) 0).put((byte) 1).put((byte) band.index).put((byte) 5)
                    .putShort((short) band.frequency).putShort((short) gain).putShort((short) band.q).array();
            send(restore ? "original USR peak gain" : "temporary USR peak cut", 0x070f, body);
        }

        @Override public void loadUsrPreset(int selector) throws IOException {
            ReversibleEqTest.requireCustomPreset(selector);
            // On original 5K firmware 3.x, the load payload is EQ group followed by the raw preset selector.
            send("saved USR preset", 0x0709, new byte[]{(byte) EqSnapshot.Group.USR.id, (byte) selector});
        }

        void requireEditorFirmware() throws IOException {
            Device d=readDevice();
            if (d.model!=1 || !d.version.equals(EDITOR_FIRMWARE))
                throw new ProtocolException("These controls support the original 5K on firmware "+EDITOR_FIRMWARE+".");
        }

        private byte[] settingsBlock(int mask, int size) throws IOException {
            send("USB and microphone settings", 0x0120, new byte[]{(byte)mask});
            byte[] data = reader.receive("device configuration", 0x8121);
            if (data.length != size + 1 || (data[0] & 255) != mask)
                throw new ProtocolException("Unexpected 5K configuration block.");
            return Arrays.copyOfRange(data, 1, data.length);
        }

        UsbAudioSettings usbAudio() throws IOException {
            requireEditorFirmware();
            return readUsbAudio();
        }

        UsbAudioSettings readUsbAudio() throws IOException {
            return UsbAudioSettings.decode(settingsBlock(1,12), settingsBlock(64,32), settingsBlock(16,4));
        }

        void setUsbPlatform(int platform) throws IOException {
            requireEditorFirmware();
            if (!UsbMode.supported(platform)) throw new IOException("Unknown USB mode.");
            send("USB platform",0x0301,new byte[]{(byte)platform});
        }

        void checkUsbRestart() throws IOException {
            byte[] eq = settingsBlock(128,18);
            if ((eq[2] & 255) == 255 || (eq[11] & 255) == 255)
                throw new IOException("Save or restore the temporary device EQ before changing the USB profile.");
        }

        void usbField(UsbAudioSettings current, UsbAudioSettings.Field field, int value) throws IOException {
            requireEditorFirmware();
            current.with(field, value);
            send("USB or microphone setting", field.command, new byte[]{(byte)value});
        }

        boolean channelsSwapped() throws IOException {
            requireEditorFirmware();
            return readChannelRouting();
        }

        boolean readChannelRouting() throws IOException {
            return readDacSettings().swapped;
        }

        DacSettings dacSettings() throws IOException {
            requireEditorFirmware();
            return readDacSettings();
        }

        byte[] outputSpecifications() throws IOException {
            return settingsBlock(64,32);
        }

        private DacSettings readDacSettings() throws IOException {
            send("DAC configuration", 0x0120, new byte[]{8});
            return new DacSettings(reader.receive("DAC configuration", 0x8121));
        }

        DacSettings setDacSetting(DacSettings shown, DacSettings.Field field, int target) throws IOException {
            DacSettings before = dacSettings();
            if (target < 0 || target > 1 || !before.supported() || !before.sameReading(shown))
                throw new IOException("DAC settings changed. Read the 5K and try again.");
            if (before.value(field) == target) return before;
            String name = field == DacSettings.Field.PROFILE ? "DAC profile" : "Amplifier gain";
            send(name, field.command, new byte[]{(byte) target});
            for (int i = 0; i < 3; i++) {
                ReversibleEqTest.settle();
                DacSettings checked = dacSettings();
                if (checked.confirms(before, field, target)) return checked;
            }
            throw new IOException("The 5K did not confirm " + name + ". Read the current settings.");
        }

        void routeChannels(boolean swapped) throws IOException {
            requireEditorFirmware();
            send("DAC channel swap", 0x050a, new byte[]{(byte)(swapped ? 1 : 0)});
        }

        DeviceLevels levels() throws IOException {
            requireEditorFirmware();
            return readLevels();
        }

        DeviceLevels readLevels() throws IOException {
            send("audio, battery and RMS",0x0110,new byte[]{27});
            byte[] state=reader.receive("audio and battery",0x8111);
            send("charger and hardware volume",0x0120,new byte[]{3});
            return new DeviceLevels(state,reader.receive("volume configuration",0x8121));
        }

        DeviceLevels setInputPriority(DeviceLevels before, int expected, int choice) throws IOException {
            if (choice < 0 || choice >= 3 || before.priority < 0 || before.priority != expected || before.call)
                throw new IOException("Input priority or call state changed. Refresh the 5K and try again.");
            if (before.priority == choice) return before;
            send("input priority",0x0203,new byte[]{(byte)choice});
            for (int attempt=0; attempt<3; attempt++) {
                ReversibleEqTest.settle();
                DeviceLevels checked=levels();
                if (checked.priority == choice) return checked;
            }
            throw new IOException("The 5K did not confirm the new input priority.");
        }

        DeviceLevels setCheckedVolume(DeviceLevels shown, DeviceLevels now, int target) throws IOException {
            if (!now.adjustable() || now.volume != shown.volume || now.limit != shown.limit
                    || target > now.limit || target < now.limit - 3600)
                throw new IOException("Hardware volume or its limit changed. Refresh before adjusting.");
            volume(target);
            DeviceLevels after = levels();
            if (after.volume == now.volume && after.adjustable() && after.limit == now.limit && after.volume != target) {
                ReversibleEqTest.settle();
                after = levels();
            }
            if (after.volume != target || !after.adjustable() || after.limit != now.limit)
                throw new IOException("The 5K did not confirm that volume. Refresh to read it.");
            return after;
        }

        void volume(int raw) throws IOException {
            send("hardware volume",0x0200,ByteBuffer.allocate(3).put((byte)1).putShort((short)raw).array());
        }

        void editBand(Peq p,int ch,int band) throws IOException {
            send("runtime PEQ filter",0x070f,ByteBuffer.allocate(10).put((byte)p.group).put((byte)(1<<ch))
                .put((byte)band).put((byte)p.type(ch,band)).putShort((short)p.hz(ch,band))
                .putShort((short)p.gain(ch,band)).putShort((short)p.q(ch,band)).array());
        }

        void preamp(Peq p,int ch) throws IOException {
            send("runtime PEQ preamp",0x0703,ByteBuffer.allocate(5).put((byte)p.group).put((byte)(1<<ch))
                .put((byte)0).putShort((short)p.pre(ch)).array());
        }

        void metadata(Peq p) throws IOException {
            if(p.group==0) send("runtime crossfeed",0x070d,new byte[]{0,1,0,0,(byte)p.xfeed()});
            else send("receiver profile",0x0216,ByteBuffer.allocate(5).put((byte)2)
                     .putShort((short)p.impedance()).putShort((short)p.sensitivity()).array());
        }

        void enableEq(int group,boolean enabled) throws IOException {
            if(group<0 || group>1) throw new ProtocolException("Invalid EQ stage.");
            send("EQ power",0x0700,new byte[]{(byte)group,(byte)(enabled?1:0)});
        }

        void nameSlot(int group,int slot,String name) throws IOException {
            byte[] text=name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(slot<0 || slot>=20 || text.length>32) throw new ProtocolException("Invalid slot name.");
            send("custom slot name",0x070a,ByteBuffer.allocate(3+text.length)
                    .put((byte)group).put((byte)slot).put((byte)text.length).put(text).array());
        }

        void preset(int group,int selector,boolean save) throws IOException {
            if(selector<0 || selector>=42 || (save && selector<22)) throw new ProtocolException("Invalid preset destination.");
            send(save?"save custom preset":"reload saved preset",save?0x0708:0x0709,new byte[]{(byte)group,(byte)selector});
        }

        @Override public void close() throws IOException {
            transport.close();
        }

        private void send(Request request) throws IOException {
            byte[] body = new byte[request.body.length];
            for (int i = 0; i < body.length; i++) body[i] = (byte) request.body[i];
            send(request.name, request.command, body);
        }

        private void send(String name, int command, byte[] body) throws IOException {
            byte[] frame = ByteBuffer.allocate(8 + body.length)
                    .put((byte) 0xff).put((byte) 1).put((byte) 0).put((byte) body.length)
                    .putShort((short) 0xf001).putShort((short) command).put(body).array();
            trace.accept(String.format(Locale.ROOT, "Sending %s request F001/%04X", name, command));
            try {
                output.write(frame);
                output.flush();
            } catch (IOException error) {
                throw new IOException("GAIA could not send the " + name + " request.", error);
            }
            trace.accept("Sent " + name + " request");
        }
    }

    private static final class Reader {
        private final DataInputStream stream;
        private final Consumer<String> trace;

        Reader(InputStream input, Consumer<String> trace) {
            stream = new DataInputStream(input);
            this.trace = trace;
        }

        byte[] receive(String reply, int... commands) throws IOException {
            try {
                return readFrame(reply, commands);
            } catch (EOFException error) {
                trace.accept("The incoming stream ended before the reply.");
                throw error;
            } catch (ProtocolException error) {
                trace.accept(error.getMessage());
                throw error;
            } catch (IOException error) {
                trace.accept("Android reported an error reading the control socket.");
                throw new IOException("GAIA did not return a complete " + reply + " reply.", error);
            }
        }

        private byte[] readFrame(String reply, int[] commands) throws IOException {
            for (int count = 0; count < 16; count++) {
                int start = stream.readUnsignedByte();
                int version = stream.readUnsignedByte();
                int flags = stream.readUnsignedByte();
                if (start != 0xff || version != 1 || flags != 0) {
                    throw new ProtocolException("Unsupported GAIA frame header.");
                }
                int length = stream.readUnsignedByte();
                int vendor = stream.readUnsignedShort();
                int command = stream.readUnsignedShort();
                byte[] payload = new byte[length];
                stream.readFully(payload);
                trace.accept(String.format(Locale.ROOT, "Received %04X/%04X (%d bytes)", vendor, command, length));

                if (vendor != 0xf001) continue;
                if (command == 0xa002 && length >= 2 && payload[0] == 0 && payload[1] == 3) {
                    throw new ProtocolException("The 5K reports that another controller holds the session.");
                }
                for (int expected : commands) {
                    if (command != expected) continue;
                    if (length == 0) throw new ProtocolException("The " + reply + " reply has no status byte.");
                    if (payload[0] != 0) {
                        throw new ProtocolException("The " + reply + " request declined with status "
                                + Byte.toUnsignedInt(payload[0]) + ".");
                    }
                    return Arrays.copyOfRange(payload, 1, length);
                }
            }
            throw new ProtocolException("No " + reply + " reply in the first 16 frames.");
        }
    }
}
