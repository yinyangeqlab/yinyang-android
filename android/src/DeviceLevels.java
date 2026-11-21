package com.yinyangeq.qudelixprobe;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class DeviceLevels {
    final int battery, volume, limit, source, priority, sampleRate, bitDepth;
    final double leftRms, rightRms, sourceDb;
    final boolean playing, call, usbEnumerated;
    final String charging, stream;
    private static final int[] RATES = {8000, 16000, 32000, 44100, 48000, 88200, 96000};
    private static final String[] SOURCES = {
            "No active input", "USB DAC", "Bluetooth 1", "Bluetooth 2", "Bluetooth 1 call", "Bluetooth 2 call"};
    static String[] inputNames() { return new String[] {SOURCES[1], SOURCES[2], SOURCES[3]}; }
    private static final String[] CODECS = {
            "Unreported", "SBC", "AAC", "aptX", "aptX HD", "aptX Adaptive", "LDAC"};
    DeviceLevels(byte[] status, byte[] config) throws ProtocolException {
        boolean hasSystem = config.length == 29 && config[0] == 3;
        boolean hasRms = status.length == 33 && status[0] == 27;
        if ((!hasRms && (status.length != 17 || status[0] != 11)) || (!hasSystem && (config.length != 17 || config[0] != 2)))
            throw new ProtocolException("Unexpected 5K status layout.");
        ByteBuffer s = ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN);
        int audio = s.getInt(1), power = status[5] & 255;
        battery = (status[6] & 255) >>> 1;
        boolean usb = (power & 1) != 0;
        int cap = hasSystem && (config[1] & 32) != 0 ? 80 : 100;
        if ((power & 2) != 0) charging = "Charging";
        else if (!usb || (status[12] & 32) != 0) charging = "Discharging";
        else if (!hasSystem) charging = "USB power · charger idle";
        else if ((config[1] & 16) == 0) charging = "USB power · charging disabled";
        else charging = "Charge complete · " + cap + "% limit";
        int offset = hasSystem ? 12 : 0;
        int configuredPriority = hasSystem ? (config[2] >>> 2) & 7 : -1;
        priority = configuredPriority >= 0 && configuredPriority < 3 ? configuredPriority : -1;
        ByteBuffer c = ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN);
        usbEnumerated = (audio & (1 << 25)) != 0;
        playing = (audio & 2) != 0;
        source = (audio >>> 9) & 7;
        call = (audio & 4) != 0 || source == 4 || source == 5;
        leftRms = hasRms && playing && !call ? inputRms(s.getInt(17)) : Double.NaN;
        rightRms = hasRms && playing && !call ? inputRms(s.getInt(21)) : Double.NaN;
        boolean ignoreHostGain = source == 1 && hasSystem && (config[6] & 64) != 0;
        sourceDb = ignoreHostGain ? 0 : c.getShort(offset+13)/60.0;
        volume = c.getShort(offset + (call ? 5 : 1));
        limit = Math.min(c.getShort(offset + 3), (audio & (1 << 15)) != 0 ? 360 : 0);
        int bits = (audio >>> 12) & 3, rate = (audio >>> 16) & 15, codec = (audio >>> 5) & 15,
            hfp = (audio >>> 3) & 3;
        bitDepth = bits == 1 ? 16 : bits == 2 ? 24 : 0;
        sampleRate = rate < RATES.length ? RATES[rate] : 0;
        String encoding = source == 1        ? "PCM"
                : source == 2 || source == 3 ? lookup(CODECS, codec)
                : hfp == 1                   ? "Narrowband voice"
                : hfp == 2                   ? "Wideband voice"
                                             : "Unreported";
        long raw = Integer.toUnsignedLong(s.getInt(13));
        String bitrate = "Bitrate unreported";
        if ((source == 2 || source == 3) && raw != 0 && codec > 0 && codec < CODECS.length) {
            double kbps = codec == 6 ? Math.min(raw >>> 16, 990)
                    : codec == 5     ? (raw & 0xffffff) / 1000.0
                                     : raw / 1000.0;
            if (kbps > 0)
                bitrate = Peq.fmt("%.1f kb/s", kbps);
        } else if (source == 1 && bitDepth > 0 && sampleRate > 0 && (audio & (1 << 22)) != 0) {
            bitrate =
                    Peq.fmt("%.1f kb/s (stereo PCM payload)", sampleRate * bitDepth * 2 / 1000.0);
        }
        stream = !playing ? "No audio playing"
                          : lookup(SOURCES, source) + " · " + encoding + "\n"
                        + (bitDepth > 0 ? bitDepth + "-bit" : "Bit depth unreported")
                        + " · "
                        + (sampleRate > 0 ? Peq.fmt("%.1f kHz", sampleRate / 1000.0).replace(".0 kHz"," kHz")
                                               : "Sample rate unreported")
                        + " · " + bitrate;
    }
    String activeInput() { return playing ? lookup(SOURCES, source) : SOURCES[0]; }
    boolean playingInput(int choice) { return playing && !call && source == choice+1; }
    boolean playingBluetooth() { return playingInput(1) || playingInput(2); }
    private static double inputRms(int raw) {
        return raw < 0 ? Double.NaN : raw == 0 ? Double.NEGATIVE_INFINITY : 20*Math.log10(raw/2147483648.0)+12;
    }
    private static String lookup(String[] a, int n) {
        return n < a.length ? a[n] : "Unreported";
    }
    boolean sameReading(DeviceLevels other){
        return other!=null && volume==other.volume && limit==other.limit && battery==other.battery &&
               call==other.call && priority==other.priority && charging.equals(other.charging) && stream.equals(other.stream);
    }
    boolean adjustable() {
        return !call && limit >= -3600 && limit <= 360 && volume >= limit - 3600 && volume <= limit;
    }
}
