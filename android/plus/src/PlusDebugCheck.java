package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;

/** Journal one quiet preamp edit before touching the working bank. No named-slot commands. */
final class PlusDebugCheck {
    private final AtomicFile journal;
    PlusDebugCheck(Context c) { journal = new AtomicFile(new File(c.getFilesDir(),"plus-eq-check-v1")); }
    boolean pending() { return journal.getBaseFile().isFile() || new File(journal.getBaseFile()+".bak").isFile(); }
    private static byte[] read(PlusUsbProtocol.Transport link,int tag,int length) throws IOException {
        return PlusUsbProtocol.required(PlusUsbProtocol.read(link,tag),tag,length);
    }
    static byte[] identity(PlusUsbProtocol.Transport link) throws IOException {
        byte[] id=Arrays.copyOfRange(read(link,0x11,8),2,8);
        if(Arrays.equals(id,new byte[6])) throw new IOException("Device identity unavailable.");
        return id;
    }
    String run(PlusUsbProtocol.Transport link,PlusUsbProtocol.Status state) throws IOException {
        if(!"1.0.3".equals(state.firmware)) throw new IOException("This check requires Plus firmware 1.0.3.");
        byte[] identity=identity(link);
        byte[] audio=read(link,0x51,16);
        int route=((audio[0]&255)|(audio[1]&255)<<8)>>7&15;
        if(route!=3) throw new IOException("Unplug headphones before the reversible EQ check.");
        if(pending()) return restore(link,state,identity);
        int mode=(read(link,0x80,4)[0]&255)>>4;
        if(mode>1) throw new IOException("Unknown EQ layout.");
        int group=mode==1 ? 2 : 0, base=PlusLibrary.base(group);
        Map<Integer,byte[]> snapshot=PlusUsbProtocol.read(link,base,base+3);
        byte[] selector=PlusUsbProtocol.required(snapshot,base,4), before=PlusUsbProtocol.required(snapshot,base+3,PlusLibrary.size(group));
        byte[] after=edited(before);
        FileOutputStream file=journal.startWrite();
        try {
            DataOutputStream out=new DataOutputStream(file);
            out.write(identity);out.writeByte(group);out.write(selector);out.write(before);out.flush();
            journal.finishWrite(file);
        } catch(IOException e){ journal.failWrite(file); throw e; }
        byte[] temp=selector.clone();temp[3]=(byte)255;
        IOException failed=null;
        try {
            PlusUsbProtocol.set(link,state.packetSize,base,temp);
            PlusUsbProtocol.set(link,state.packetSize,base+3,after);
            if(!Arrays.equals(read(link,base+3,after.length),after))throw new IOException("Temporary preamp did not verify.");
        } catch(IOException e){ failed=e; }
        String restored=restore(link,state,identity);
        if(failed!=null) throw new IOException(failed.getMessage()+" "+restored,failed);
        return "Temporary working-bank edit verified. "+restored;
    }
    private static byte[] edited(byte[] before) {
        byte[] after=before.clone();
        ByteBuffer b=ByteBuffer.wrap(after).order(ByteOrder.LITTLE_ENDIAN);
        short v=b.getShort(12);
        b.putShort(12,(short)(v<=-240 ? v+1 : v-1));
        return after;
    }
    private String restore(PlusUsbProtocol.Transport link,PlusUsbProtocol.Status state,byte[] id)throws IOException {
        try(DataInputStream in=new DataInputStream(journal.openRead())) {
            byte[] expected=new byte[6];in.readFully(expected);
            if(!Arrays.equals(id,expected)) throw new IOException("Reconnect the same Plus to restore this check.");
            int group=in.readUnsignedByte();
            if(group!=0 && group!=2)throw new IOException("Unrecognized saved bank.");
            int base=PlusLibrary.base(group);byte[] selector=new byte[4],before=new byte[PlusLibrary.size(group)];
            in.readFully(selector);in.readFully(before);
            byte[] current=read(link,base+3,before.length), active=read(link,base,4),temp=selector.clone();temp[3]=(byte)255;
            if((!Arrays.equals(current,before)&&!Arrays.equals(current,edited(before))) ||
                    (!Arrays.equals(active,selector)&&!Arrays.equals(active,temp)))
                throw new IOException("Hardware edits changed after the test. Recovery is paused to protect them.");
            if(!Arrays.equals(current,before)) PlusUsbProtocol.set(link,state.packetSize,base+3,before);
            if(!Arrays.equals(active,selector)) PlusUsbProtocol.set(link,state.packetSize,base,selector);
            if(!Arrays.equals(before,read(link,base+3,before.length)) || !Arrays.equals(selector,read(link,base,4)))
                throw new IOException("Restore readback did not match. Reconnect and check restoration.");
        }
        journal.delete();return "Original working EQ and selector restored and verified.";
    }
}
