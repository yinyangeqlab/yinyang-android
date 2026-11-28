package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Firmware 1.0.3 exposes three separate slot masks, twenty slots per bank. */
final class PlusLibrary {
    static final String[] GROUPS = {"Main EQ · USR","Channel Match · SPK","30-band EQ"};
    static final int PAIR = 3;
    static final String[] FACTORY = {"Flat","Acoustic","Bass Booster","Bass Reducer","Classical","Dance","Deep","Electronic",
        "Hip Hop","Jazz","Latin","Loudness","Lounge","Piano","Pop","R&B","Rock","Small Speaker","Spoken Word",
        "Treble Booster","Treble Reducer","Vocal"};
    // Nonnegative selections are custom slots. -1 means unselected and -2 starts the factory bank.
    static int factory(int index) { return -2-index; }
    static int wireIndex(int slot) { return slot < -1 ? -slot-2 : 22+slot; }
    String title(int group,int slot) { return slot == -1 ? "" : slot < -1 ? FACTORY[wireIndex(slot)] : names[group][slot]; }
    final String[][] names = new String[3][20];
    static int base(int group) { return 0x90 + group*16; }
    static int bands(int group) { return group==0 ? 10 : group==1 ? 20 : 30; }
    static int size(int group) { return 16 + 6*bands(group)*(group==1 ? 2 : 1); }
    static int[] groups(int choice) { return choice == PAIR ? new int[]{0,1} : new int[]{choice}; }
    private static ByteBuffer bytes(byte[] data) { return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
    static String pairKey(PlusPreset.Channel[][] banks) {
        try {
            String common = PlusPreset.file(banks[0],10);
            String match = PlusPreset.file(banks[1],20);
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((common+"\n"+match).getBytes(StandardCharsets.UTF_8));
            return "storedPair:"+Base64.getEncoder().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static PlusLibrary read(PlusUsbProtocol.Transport link) throws IOException {
        PlusLibrary library = new PlusLibrary();
        Map<Integer,byte[]> blocks = PlusUsbProtocol.read(link,0x91,0xa1,0xb1);
        for (int g=0;g<3;g++) {
            ByteBuffer data = bytes(PlusUsbProtocol.required(blocks,base(g)+1,12));
            int mask=data.getInt(0), named=data.getInt(8);
            for(int i=0;i<20;i++) if ((mask & (1<<i))!=0) {
                String name = (named & (1<<i))==0 ? "" : new String(PlusUsbProtocol.slotName(link,g,i),StandardCharsets.UTF_8);
                library.names[g][i] = name.isEmpty() ? "Custom " + (i+1) : name;
            }
        }
        return library;
    }
    static String activeName(PlusUsbProtocol.Transport link,int group,byte[] selector) throws IOException {
        int raw=selector[3]&255;
        if(raw<FACTORY.length)return FACTORY[raw];
        return raw<42?slotTitle(link,group,raw-22):"Unsaved device EQ";
    }

    private static String slotTitle(PlusUsbProtocol.Transport link,int group,int slot) throws IOException {
        byte[] flags=block(link,base(group)+1,12);
        if((bytes(flags).getInt(8)&(1<<slot))==0)return "Custom "+(slot+1);
        String title=new String(PlusUsbProtocol.slotName(link,group,slot),StandardCharsets.UTF_8);
        return title.isEmpty()?"Custom "+(slot+1):title;
    }

    int free(int choice) throws IOException {
        for (int i=0;i<20;i++) {
            boolean empty = true;
            for (int g:groups(choice)) empty &= names[g][i]==null;
            if (empty) return i;
        }
        throw new IOException("No empty slot is available for this selection.");
    }
    static PlusPreset.Channel[] decode(int group,byte[] raw) throws IOException {
        if(raw.length!=size(group)) throw new IOException("Unexpected bank length.");
        ByteBuffer b=bytes(raw);
        int slots=bands(group), count=group==1 ? 2 : 1;
        PlusPreset.Channel[] result=new PlusPreset.Channel[count];
        try {
            for(int ear=0;ear<count;ear++) {
                List<PlusPreset.Band> filters=new ArrayList<>();
                for(int i=0;i<slots;i++) {
                    int n=ear*slots+i, params=b.getInt(16+2*slots*count+4*n), type=params&15;
                    if(type==0) continue;
                    filters.add(new PlusPreset.Band(type,b.getShort(16+2*n)&65535,(params<<18)>>22,(params>>>14)&65535));
                }
                result[ear]=new PlusPreset.Channel(b.getShort(12+2*ear),filters);
            }
        } catch(IllegalArgumentException e) { throw new IOException("Preset values exceed the editor range. "+e.getMessage(),e); }
        return result;
    }
    private static byte[] block(PlusUsbProtocol.Transport link,int id,int size) throws IOException {
        return PlusUsbProtocol.required(PlusUsbProtocol.read(link,id),id,size);
    }
    private static void mode(PlusUsbProtocol.Transport link,int packet,int group) throws IOException {
        byte[] mode=block(link,0x80,4);
        int required=group==2 ? 1 : 0;
        if ((mode[0]&255)>>4 == required) return;
        mode[0]=(byte)((mode[0]&15) | (required<<4));
        put(link,packet,0x80,mode);
    }
    private static void put(PlusUsbProtocol.Transport link,int packet,int id,byte[] value) throws IOException {
        PlusUsbProtocol.set(link,packet,id,value);
        if(!Arrays.equals(value,block(link,id,value.length))) throw new IOException("The Plus did not confirm the bank update.");
    }
    static PlusPreset.Channel[][] load(PlusUsbProtocol.Transport link,int packet,int choice,int[] slots) throws IOException {
        PlusLibrary library=read(link);
        for(int g:groups(choice)) {
            int slot=slots[g];
            if (slot == -1 || slot>=20 || (slot>=0 && library.names[g][slot]==null) ||
                    (slot < -1 && wireIndex(slot)>=(g==1 ? 1 : FACTORY.length)))
                throw new IOException("This preset is unavailable. Open the browser again.");
        }
        int[] ids = choice==2 ? new int[]{128,176,179} : new int[]{128,144,147,160,163};
        Map<Integer,byte[]> before=PlusUsbProtocol.read(link,ids);
        for (int id:ids) PlusUsbProtocol.required(before,id,(id & 15)==3 ? size((id-0x93)/16) : 4);
        try {
            mode(link,packet,choice);
            for(int g:groups(choice)) {
                byte[] selector=before.get(base(g)).clone();
                selector[3]=(byte)wireIndex(slots[g]);
                put(link,packet,base(g),selector);
            }
            return current(link,choice);
        } catch(IOException error) {
            try {
                for(int i=1;i<ids.length;i+=2) {
                    PlusUsbProtocol.set(link,packet,ids[i+1],before.get(ids[i+1]));
                    PlusUsbProtocol.set(link,packet,ids[i],before.get(ids[i]));
                }
                PlusUsbProtocol.set(link,packet,128,before.get(128));
                Map<Integer,byte[]> restored=PlusUsbProtocol.read(link,ids);
                for (int id:ids) if (!Arrays.equals(restored.get(id),before.get(id))) throw new IOException("Bank restoration differed.");
            } catch(IOException recovery) { throw new IOException("Preset load interrupted. Working EQ recovery could not be verified.",recovery); }
            throw new IOException("The preset could not be loaded. The previous working EQ was restored. "+error.getMessage(),error);
        }
    }
    static PlusPreset.Channel[][] current(PlusUsbProtocol.Transport link,int choice) throws IOException {
        PlusPreset.Channel[][] result=new PlusPreset.Channel[3][];
        for(int g : choice==2 ? new int[]{2} : new int[]{0,1})
            result[g]=decode(g,block(link,base(g)+3,size(g)));
        return result;
    }

    private static int occupied(PlusUsbProtocol.Transport link,int tag) throws IOException {
        return bytes(block(link,tag+1,12)).getInt();
    }

    private static byte[] titleBytes(String name) throws IOException {
        byte[] title=name.getBytes(StandardCharsets.UTF_8);
        if(title.length==0 || title.length>32)throw new IOException("Use a name between 1 and 32 UTF-8 bytes.");
        return title;
    }

    private static void writeName(PlusUsbProtocol.Transport link,int packet,int group,int slot,byte[] title) throws IOException {
        byte[] named=new byte[(4+title.length+3)/4*4];
        named[0]=(byte)group;named[1]=(byte)slot;named[2]=(byte)title.length;
        System.arraycopy(title,0,named,4,title.length);
        PlusUsbProtocol.set(link,packet,base(group)+4,named);
    }

    private static void verifySlot(PlusUsbProtocol.Transport link,int group,int slot,String expected) throws IOException {
        boolean present=(occupied(link,base(group))&(1<<slot))!=0;
        if(present!=(expected!=null) || present && !expected.equals(slotTitle(link,group,slot)))
            throw new IOException("The device preset list changed. Refresh before continuing.");
    }

    static void rename(PlusUsbProtocol.Transport link,int packet,int group,int slot,String from,String to) throws IOException {
        verifySlot(link,group,slot,from);
        int tag=base(group);
        byte[] selector=block(link,tag,4), working=block(link,tag+3,size(group));
        writeName(link,packet,group,slot,titleBytes(to));
        verifySlot(link,group,slot,to);
        if(!Arrays.equals(selector,block(link,tag,4)) || !Arrays.equals(working,block(link,tag+3,size(group))))
            throw new IOException("The active EQ changed while verifying the rename.");
    }

    private static byte[] select(PlusUsbProtocol.Transport link,int packet,int tag,int raw) throws IOException {
        byte[] selector=block(link,tag,4);
        if((selector[3]&255)!=raw){
            selector[3]=(byte)raw;
            put(link,packet,tag,selector);
        }
        return selector;
    }

    private static void saveWorking(PlusUsbProtocol.Transport link,int packet,int group,int slot,byte[] preset,String name)
            throws IOException {
        int tag=base(group);
        select(link,packet,tag,255);
        put(link,packet,tag+3,preset);
        writeName(link,packet,group,slot,titleBytes(name));
        PlusUsbProtocol.saveSlot(link,group,slot);
        verifySlot(link,group,slot,name);
        // Select another slot first to ensure readback came from flash, even if Save selected the destination.
        select(link,packet,tag,0);
        select(link,packet,tag,22+slot);
        if(!Arrays.equals(preset,block(link,tag+3,size(group))))throw new IOException("Saved filters did not verify.");
    }

    static void organize(PlusUsbProtocol.Transport link,int packet,int group,int slot,int destination,String name,boolean remove)
            throws IOException {
        int tag=base(group);
        byte[] layout=block(link,0x80,4);
        if((layout[0]&255)>>>4 != (group==2?1:0))throw new IOException("Select this EQ mode on the device before managing its slots.");
        int original=block(link,tag,4)[3]&255;
        if(original>=42)throw new IOException("Save or reload the working EQ before managing device slots.");
        verifySlot(link,group,slot,name);
        if(destination>=0)verifySlot(link,group,destination,null);
        try{
            if(destination>=0){
                select(link,packet,tag,22+slot);
                byte[] source=block(link,tag+3,size(group));
                saveWorking(link,packet,group,destination,source,name);
            }
            select(link,packet,tag,0);
            if(remove){
                PlusUsbProtocol.deleteSlot(link,group,slot);
                verifySlot(link,group,slot,null);
            }else{
                byte[] flat=block(link,tag+3,size(group));
                saveWorking(link,packet,group,slot,flat,name);
            }
            int restore=original!=22+slot?original:destination>=0?22+destination:remove?0:original;
            select(link,packet,tag,restore);
            if(!Arrays.equals(layout,block(link,0x80,4)))throw new IOException("The active EQ layout changed.");
        }catch(IOException failure){
            throw new IOException("Slot operation stopped. Refresh the device presets before retrying. "+failure.getMessage(),failure);
        }
    }

    static int create(PlusUsbProtocol.Transport link,int packet,int choice,String name,PlusPreset.Channel[][] banks) throws IOException {
        return save(link,packet,choice,name,banks,-1,null);
    }

    static int save(PlusUsbProtocol.Transport link,int packet,int choice,String name,PlusPreset.Channel[][] banks,
                    int target,String previousName) throws IOException {
        titleBytes(name);
        int slot=target<0?read(link).free(choice):target;
        mode(link,packet,choice);
        int completed=0;
        try {
            for(int g:groups(choice)) {
                int tag=base(g);
                verifySlot(link,g,slot,previousName);
                byte[] preset=PlusApply.preset(block(link,tag+3,size(g)),banks[g],bands(g));
                saveWorking(link,packet,g,slot,preset,name);
                completed++;
            }
        } catch(IOException failure) {
            throw new IOException("Save interrupted at slot "+(slot+1)+". "+completed+" bank(s) verified. Reopen device presets before retrying. "+failure.getMessage(),failure);
        }
        return slot;
    }
}
