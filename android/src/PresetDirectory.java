package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A single verified listing of both preset banks on an original 5K. */
final class PresetDirectory {
    static final int FIRST_CUSTOM = 22, COUNT = 20;
    private final GaiaProbe.Device device;
    private final byte[] config;
    private final String[][] names;

    private PresetDirectory(GaiaProbe.Device device, byte[] config, String[][] names) {
        this.device = device;
        this.config = config;
        this.names = names;
    }

    static PresetDirectory read(GaiaProbe.Connection link) throws IOException {
        return read(link,link.readDevice());
    }

    static PresetDirectory read(GaiaProbe.Connection link,GaiaProbe.Device device) throws IOException {
        if (device.model != 1 || !device.version.equals(GaiaProbe.EDITOR_FIRMWARE))
            throw new ProtocolException("The preset library supports the original 5K on firmware "+GaiaProbe.EDITOR_FIRMWARE+".");
        byte[] config = link.readEqConfiguration();
        EqSnapshot.checkConfiguration(config);
        String[][] names = {readBank(link,config,0),readBank(link,config,1)};
        if (!Arrays.equals(config, link.readEqConfiguration()))
            throw new ProtocolException("The 5K configuration changed during the preset read. Refresh the list.");
        return new PresetDirectory(device, config, names);
    }

    static String[] readBank(GaiaProbe.Connection link,byte[] config,int group) throws IOException {
        String[] names = new String[COUNT];
        for (int i = 0; i < COUNT; i++) {
            if ((config[6 + 9 * group + i / 8] & (1 << (i % 8))) != 0)
                names[i] = readName(link, group, i);
        }
        return names;
    }

    static String readName(GaiaProbe.Connection link, int group, int slot) throws IOException {
        byte[] data = link.readSlotName(group, slot);
        if (data.length < 3 || data[0] != group || (data[1] & 255) != slot)
            throw new ProtocolException("The 5K returned a name for an unexpected slot. Refresh the list.");
        int length = data[2] & 255;
        if (length > 32 || length > data.length - 3) throw new ProtocolException("Incomplete 5K preset name.");
        int end = 0;
        while (end < length && data[3 + end] != 0) end++;
        return new String(data, 3, end, StandardCharsets.UTF_8);
    }

    String name(int group, int slot) { return names[group][slot]; }
    int selector(int group) { return config[3 + 9 * group] & 255; }
    boolean enabled(int group) { return (config[4 + 9 * group] & 1) != 0; }
    String activeName(int group) {
        int selector = selector(group), slot = selector - FIRST_CUSTOM;
        if (slot >= 0 && slot < COUNT) {
            String name = name(group, slot);
            return name == null || name.isEmpty() ? "Custom #" + (slot + 1) : name;
        }
        if (selector == 255) return "Unsaved EQ";
        return group == 0 && selector < FACTORY_NAMES.length ? FACTORY_NAMES[selector] : "Factory #" + selector;
    }

    static final String[] FACTORY_NAMES = {"Flat", "Acoustic", "Bass Booster", "Bass Reducer",
            "Classical", "Dance", "Deep", "Electronic", "Hip Hop", "Jazz", "Latin", "Loudness", "Lounge",
            "Piano", "Pop", "R&B", "Rock", "Small Speaker", "Spoken Word", "Treble Booster", "Treble Reducer", "Vocal"};

    interface Connector { GaiaProbe.Connection open() throws IOException; }

    PresetDirectory switchTo(Connector connector, int group, int slot) throws IOException {
        if (group < 0 || group > 1 || slot < 0 || slot >= COUNT || name(group,slot) == null)
            throw new ProtocolException("Choose a saved preset.");
        int target = FIRST_CUSTOM + slot;
        byte[] expected;
        try (GaiaProbe.Connection link = connector.open()) {
            PresetDirectory current = read(link);
            requireSameSlot(current, group, slot);
            if (current.selector(group) == target) return current;
            if (current.selector(group) != selector(group))
                throw new ProtocolException("The active preset changed. Refresh before switching.");
            expected = current.config.clone();
            expected[3 + 9 * group] = (byte)target;
            link.preset(group, target, false);
            ReversibleEqTest.settle();
        }
        ReversibleEqTest.settle();
        try (GaiaProbe.Connection link = connector.open()) {
            PresetDirectory verified = read(link);
            requireSameSlot(verified, group, slot);
            if (!Arrays.equals(expected, verified.config))
                throw new ProtocolException("The 5K reported a different active preset or configuration after switching. Refresh to check it.");
            return verified;
        }
    }

    private void requireSameSlot(PresetDirectory other, int group, int slot) throws ProtocolException {
        if (!Arrays.equals(device.firmwareField, other.device.firmwareField) || !name(group, slot).equals(other.name(group, slot)))
            throw new ProtocolException("The saved preset list changed. Refresh before switching.");
    }
}
