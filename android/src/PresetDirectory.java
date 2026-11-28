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
    static boolean named(EqSnapshot state,int group,int slot){
        return (state.configuration[6+9*group+slot/8]&(1<<(slot%8)))!=0;
    }

    static EqSnapshot name(GaiaProbe.Connection link,EqSnapshot before,int group,int slot,String title) throws IOException {
        link.nameSlot(group,slot,title);
        ReversibleEqTest.settle();
        EqSnapshot after=link.read();
        byte[] expected=before.configuration.clone();
        int index=6+9*group+slot/8, mask=1<<(slot%8);
        expected[index]=(byte)(title.isEmpty()?expected[index]&~mask:expected[index]|mask);
        if(!Arrays.equals(expected,after.configuration) || !Arrays.equals(before.usr,after.usr)
                || !Arrays.equals(before.spk,after.spk) || !title.isEmpty() && !title.equals(readName(link,group,slot)))
            throw new IOException("The slot name could not be verified. Refresh the device presets.");
        return after;
    }

    static EqSnapshot select(GaiaProbe.Connection link,EqSnapshot before,int group,int raw) throws IOException {
        if(PeqSession.selector(before,group)==raw)return before;
        link.preset(group,raw,false);
        ReversibleEqTest.settle();
        EqSnapshot after=link.read();
        byte[] expected=before.configuration.clone();
        expected[3+9*group]=(byte)raw;
        if(!Arrays.equals(expected,after.configuration) || !PeqSession.block(before,1-group).same(PeqSession.block(after,1-group)))
            throw new IOException("The requested slot did not verify.");
        return after;
    }

    /** Verify the flash copy independently after reloading the slot. */
    static EqSnapshot copyWorking(GaiaProbe.Connection link,EqSnapshot before,int group,int slot) throws IOException {
        int raw=22+slot;
        link.preset(group,raw,true);
        ReversibleEqTest.settle();
        EqSnapshot saved=link.read();
        byte[] expected=before.configuration.clone();
        expected[3+9*group]=(byte)raw;
        int index=6+9*group+slot/8;
        int bit=1<<(slot%8);
        if(!named(before,group,slot))expected[index]=(byte)((expected[index]&~bit)|(saved.configuration[index]&bit));
        if(!Arrays.equals(expected,saved.configuration) || !Arrays.equals(before.usr,saved.usr) || !Arrays.equals(before.spk,saved.spk))
            throw new IOException("The working EQ could not be verified in the destination slot.");
        link.preset(group,raw,false);
        ReversibleEqTest.settle();
        EqSnapshot reloaded=link.read();
        if(!Arrays.equals(saved.configuration,reloaded.configuration) || !Arrays.equals(saved.usr,reloaded.usr)
                || !Arrays.equals(saved.spk,reloaded.spk))
            throw new IOException("The destination filters differed after reload.");
        return reloaded;
    }

    /** Move first copies and verifies the destination; the source is flattened only after that. */
    static EqSnapshot organize(GaiaProbe.Connection link,EqSnapshot before,int group,int slot,int destination,
                               String expectedName,boolean remove) throws IOException {
        int start=PeqSession.selector(before,group);
        if(start>=42)throw new IOException("Save or reload the working EQ before managing device slots.");
        if(!named(before,group,slot) || !expectedName.equals(readName(link,group,slot))
                || destination>=0 && named(before,group,destination))
            throw new IOException("The preset list changed. Refresh before continuing.");
        EqSnapshot current=before;
        try {
            if(destination>=0){
                current=select(link,current,group,22+slot);
                current=copyWorking(link,current,group,destination);
                current=name(link,current,group,destination,expectedName);
            }
            current=select(link,current,group,0);
            current=copyWorking(link,current,group,slot);
            if(remove){
                current=select(link,current,group,0);
                current=name(link,current,group,slot,"");
            }
            int restore=start!=22+slot?start:destination>=0?22+destination:remove?0:start;
            return select(link,current,group,restore);
        } catch(IOException error) {
            throw new IOException("Slot operation stopped. Refresh both slots before retrying. "+error.getMessage(),error);
        }
    }
}
