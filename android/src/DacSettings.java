package com.yinyangeq.qudelixprobe;

import java.net.ProtocolException;
import java.util.Arrays;

/** Original 5K v3 DAC configuration. The leading byte is the presence mask. */
final class DacSettings {
    enum Field {
        PROFILE(0x0508), GAIN(0x0505);

        final int command;
        Field(int command) { this.command = command; }
    }

    final int profile, effectiveProfile, gain;
    final boolean swapped;
    private final byte[] bytes;

    DacSettings(byte[] config) throws ProtocolException {
        if (config.length != 5 || config[0] != 8)
            throw new ProtocolException("Unrecognized 5K DAC configuration.");
        bytes = config.clone();
        profile = config[4] & 3;
        effectiveProfile = (config[4] >>> 2) & 3;
        gain = (config[1] >>> 5) & 1;
        swapped = (config[4] & 0x10) != 0;
    }

    String filterName() {
        String[] names={"Linear phase fast roll-off","Linear phase slow roll-off","Linear phase super slow roll-off",
                "Minimum phase fast roll-off","Minimum phase slow roll-off","Minimum phase super slow roll-off","Hybrid fast roll-off","NOS"};
        int filter=(bytes[3]>>>2)&15;
        return filter<names.length?names[filter]:"Filter unreported";
    }
    double nominalMillivolts(DeviceLevels levels, byte[] sys2) {
        if(levels==null || levels.call || sys2==null) return Double.NaN;
        int selection=(bytes[1]>>>2)&7;
        int route=selection==0?bytes[1]&3:selection-1;
        if(route!=0 && route!=1) return Double.NaN;
        double volts=route==0 && UsbAudioSettings.bits(sys2,155,2)==0 ? 2 : 1;
        return 1000*volts*Math.pow(10,(levels.sourceDb+levels.volume/60.0)/20);
    }
    boolean supported() { return profile <= 1 && effectiveProfile <= 1; }
    int value(Field field) { return field == Field.PROFILE ? profile : gain; }

    boolean sameReading(DacSettings other) {
        return other != null && Arrays.equals(bytes, other.bytes);
    }

    boolean confirms(DacSettings previous, Field field, int target) {
        Field untouched = field == Field.PROFILE ? Field.GAIN : Field.PROFILE;
        return supported() && value(field) == target && value(untouched) == previous.value(untouched)
                && swapped == previous.swapped;
    }
}
