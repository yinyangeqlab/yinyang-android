package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.util.Locale;

/** Hardware identity shared by discovery, routing, and device-local storage. */
final class DeviceProfile {
    enum Model { ORIGINAL("Qudelix-5K"), PLUS("Qudelix-5K Plus");
        final String label;
        Model(String label){this.label=label;}
    }
    final Model model;
    final String address;
    final String key;
    DeviceProfile(Model model,String address) throws IOException {
        if(model==null || address==null || !address.matches("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
                || address.equals("00:00:00:00:00:00") || address.equalsIgnoreCase("ff:ff:ff:ff:ff:ff"))
            throw new IOException("The device did not report a usable identity.");
        this.model=model;this.address=address.toUpperCase(Locale.ROOT);
        key=(model==Model.PLUS?"plus-":"5k-")+this.address.replace(":","");
    }
    static DeviceProfile original(int reportedModel,String bondedAddress) throws IOException {
        if(reportedModel!=1)throw new IOException("The device did not identify as an original 5K.");
        return new DeviceProfile(Model.ORIGINAL,bondedAddress);
    }
    static DeviceProfile plus(byte[] identity) throws IOException {
        if(identity==null || identity.length!=6)throw new IOException("Incomplete Plus identity.");
        // System status packs Bluetooth NAP, UAP, and LAP as three little-endian fields.
        String address=String.format(Locale.ROOT,"%02X:%02X:%02X:%02X:%02X:%02X",
                identity[1]&255,identity[0]&255,identity[2]&255,identity[5]&255,identity[4]&255,identity[3]&255);
        return new DeviceProfile(Model.PLUS,address);
    }
    boolean matches(DeviceProfile other){return other!=null && key.equals(other.key);}
    String title(){return model.label+" · "+address.substring(12);}
    String bluetoothMacLabel(){return "Bluetooth MAC · "+address;}
}
