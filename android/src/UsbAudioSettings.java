package com.yinyangeq.qudelixprobe;

import java.io.IOException;

/** USB profile selected for the active platform and controls of the 3.5 mm mic path. */
final class UsbAudioSettings {
    static final String[] RATES = {"96 kHz", "88.2 kHz", "48 kHz", "44.1 kHz", "All playback rates", "48 kHz with mic", "44.1 kHz with mic"};
    static final int MEASUREMENT_RATE = 5;
    enum Field {
        RATE(0x0209), SOURCE(0x0601), GAIN(0x0600), VOICE(0x0602), MUSIC(0x0603);
        final int command;
        Field(int command) { this.command = command; }
    }
    final int platform, rate, source, gain, voice, music;

    UsbAudioSettings(int platform, int rate, int source, int gain, int voice, int music) throws IOException {
        if (!UsbMode.supported(platform) || !validRate(platform, rate) || gain < 0 || gain > 22
                || (source & ~1) != 0 || (voice & ~1) != 0 || (music & ~1) != 0)
            throw new IOException("Unrecognized USB or microphone settings on the 5K.");
        this.platform = platform;
        this.rate = rate;
        this.source = source;
        this.gain = gain;
        this.voice = voice;
        this.music = music;
    }

    static boolean validRate(int platform, int value) {
        return value >= 0 && value < RATES.length && (platform == 0 || value == 2 || value == 3 || value == 5 || value == 6);
    }
    static int bits(byte[] block, int offset, int width) {
        int value = 0;
        for (int n = 0; n < width; n++) {
            int bit = offset + n;
            value |= ((block[bit / 8] >>> (bit % 8)) & 1) << n;
        }
        return value;
    }
    static int platform(byte[] sys2){
        return sys2==null || sys2.length!=32?-1:bits(sys2,157,3);
    }
    static UsbAudioSettings decode(byte[] sys, byte[] sys2, byte[] mic) throws IOException {
        if (sys.length != 12 || sys2.length != 32 || mic.length != 4) throw new IOException("Incomplete USB or mic settings.");
        int platform = platform(sys2);
        int rate = platform == 0 ? bits(sys,37,3) : bits(sys2,platform == 1 ? 160 : 163,3);
        return new UsbAudioSettings(platform, rate, bits(mic,0,1), bits(mic,2,6), bits(mic,1,1), bits(mic,16,1));
    }
    int value(Field field) {
        switch (field) {
            case RATE: return rate;
            case SOURCE: return source;
            case GAIN: return gain;
            case VOICE: return voice;
            case MUSIC: return music;
            default: throw new AssertionError(field);
        }
    }
    UsbAudioSettings with(Field field, int value) throws IOException {
        return new UsbAudioSettings(platform, field == Field.RATE ? value : rate, field == Field.SOURCE ? value : source,
                field == Field.GAIN ? value : gain, field == Field.VOICE ? value : voice, field == Field.MUSIC ? value : music);
    }
    UsbAudioSettings measurement() throws IOException {
        return new UsbAudioSettings(platform, MEASUREMENT_RATE, 1, gain, 0, 0);
    }
    interface MicWriter { void set(Field field, int value) throws IOException; }

    void applyMicChanges(UsbAudioSettings target, MicWriter writer) throws IOException {
        if (music == 1 && target.music == 0) writer.set(Field.MUSIC, 0);
        if (voice == 1 && target.voice == 0) writer.set(Field.VOICE, 0);
        if (source != target.source) writer.set(Field.SOURCE, target.source);
        if (gain != target.gain) writer.set(Field.GAIN, target.gain);
        if (voice == 0 && target.voice == 1) writer.set(Field.VOICE, 1);
        if (music == 0 && target.music == 1) writer.set(Field.MUSIC, 1);
    }
    int packed() {
        return platform | rate << 2 | source << 5 | gain << 6 | voice << 11 | music << 12;
    }
    static UsbAudioSettings unpack(int value) throws IOException {
        if ((value & ~8191) != 0) throw new IOException("Invalid saved USB profile.");
        return new UsbAudioSettings(value & 3, (value >> 2) & 7, (value >> 5) & 1,
                (value >> 6) & 31, (value >> 11) & 1, (value >> 12) & 1);
    }
}
