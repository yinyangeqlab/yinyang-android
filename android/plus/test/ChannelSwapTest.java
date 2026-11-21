package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ChannelSwapTest {
    private static final String ID = "plus-1234567890A2";
    private static final int DAC = PlusChannelSwap.DAC, SPK = 0xa3, SELECTOR = 0xa0;

    private static final class Recovery implements PlusChannelSwap.Journal {
        byte[] bytes;
        boolean fail;
        public byte[] load() { return bytes; }
        public void save(byte[] value) throws IOException {
            if (fail) throw new IOException("Storage unavailable");
            bytes = value;
        }
    }

    private static PlusPreset.Channel channel(int count, int offset, int preamp) {
        List<PlusPreset.Band> filters = new ArrayList<>();
        for (int i=0;i<count;i++)
            filters.add(new PlusPreset.Band(5,offset+i*41,2-i,1000+i*11));
        return new PlusPreset.Channel(preamp,filters);
    }

    private static ApplyTest.Device fixture(int main, int left, int right, boolean layered) throws IOException {
        ApplyTest.Device device = new ApplyTest.Device();
        PlusPreset.Channel shared = channel(main,100,-33);
        PlusPreset.Channel l = channel(left,1750,-12), r = channel(right,4000,-27);
        PlusPreset.Mode layout = layered ? PlusPreset.Mode.LAYERED : PlusPreset.Mode.SHARED;
        PlusPreset.Banks banks = PlusPreset.banks(layout,shared,l,r,false);
        device.state.get(0x80)[0] = (byte)(layered ? 1 : 17);
        device.state.put(DAC,new byte[]{5,0x30,4,0x21,(byte)128,0,0x24,1});
        device.state.put(0x93,PlusApply.preset(device.state.get(0x93),
                new PlusPreset.Channel[]{layered ? banks.user : channel(8,600,-35)},10));
        device.state.put(SPK,PlusApply.preset(device.state.get(SPK),layered ? banks.match : new PlusPreset.Channel[]{l,r},20));
        device.state.put(0xb3,PlusApply.preset(device.state.get(0xb3),
                new PlusPreset.Channel[]{layered ? channel(30,500,-48) : banks.user},30));
        device.state.get(SELECTOR)[3]=9;
        return device;
    }

    private static PlusApply driver(ApplyTest.Device device, String firmware, int packetSize) throws IOException {
        return new PlusApply(device, new PlusUsbProtocol.Status(firmware,PlusPreset.Mode.LAYERED,null,null,packetSize));
    }

    private static void same(byte[] a, byte[] b, String context) {
        if (!Arrays.equals(a,b)) throw new AssertionError(context);
    }

    private static void checkChannels(byte[] actual, byte[] original) {
        same(Arrays.copyOfRange(actual,0,12),Arrays.copyOfRange(original,0,12),"SPK attributes");
        for (int[] fields : new int[][]{{12,2},{16,40},{96,80}}) {
            int from=fields[0], lanes=fields[1];
            same(Arrays.copyOfRange(actual,from,from+lanes),
                    Arrays.copyOfRange(original,from+lanes,from+2*lanes),"Old R is new L");
            same(Arrays.copyOfRange(actual,from+lanes,from+2*lanes),
                    Arrays.copyOfRange(original,from,from+lanes),"Old L is new R");
        }
    }

    private static void exercise(int main, int left, int right, boolean layered) throws IOException {
        ApplyTest.Device device = fixture(main,left,right,layered);
        Map<Integer,byte[]> before = new HashMap<>(device.state);
        PlusApply driver = driver(device,"1.0.3",left%2==0?40:120);
        Recovery saved = new Recovery();
        PlusChannelSwap.Result result = PlusChannelSwap.run(driver,ID,false,saved);
        ApplyTest.expect(result.swapped && result.layered==layered && saved.bytes!=null);
        byte[] routing = before.get(DAC).clone();
        routing[0] ^= 0x20;
        same(device.state.get(DAC),routing,"Only Plus routing bit changes");
        if (layered) {
            checkChannels(device.state.get(SPK),before.get(SPK));
            ApplyTest.expect(device.state.get(SELECTOR)[3]==(byte)255);
        }
        for (int tag : PlusChannelSwap.STATE) {
            if (tag==DAC || layered && (tag==SPK || tag==SELECTOR)) continue;
            same(before.get(tag),device.state.get(tag),"Unrelated state remains exact");
        }
        int writes=device.writes;
        ApplyTest.expect(PlusChannelSwap.run(driver,ID,false,saved).swapped && device.writes==writes);
        ApplyTest.expect(!PlusChannelSwap.run(driver,ID,true,new Recovery()).swapped);
        for (int tag : PlusChannelSwap.STATE) {
            if (layered && tag==SELECTOR) continue; // The modified working SPK is an unsaved preset.
            same(before.get(tag),device.state.get(tag),"Reversing the swap restores working values");
        }
    }

    private static void interrupts() throws IOException {
        for (int write=1;write<=3;write++) {
            ApplyTest.Device device = fixture(12,0,18,true);
            Map<Integer,byte[]> original = new HashMap<>(device.state);
            Recovery pending = new Recovery();
            device.failAt=write;
            try { PlusChannelSwap.run(driver(device,"1.0.3",40),ID,false,pending); throw new AssertionError("Interrupted write reported success"); }
            catch (IOException expected) { ApplyTest.expect(pending.bytes!=null); }
            device.lost=false;
            try { PlusChannelSwap.run(driver(device,"1.0.3",40),"plus-1234567890AA",false,pending); throw new AssertionError("Wrong device accepted"); }
            catch (IOException expected) { ApplyTest.expect(device.writes==write); }
            ApplyTest.expect(PlusChannelSwap.run(driver(device,"1.0.3",40),ID,false,pending).swapped);
            checkChannels(device.state.get(SPK),original.get(SPK));
            same(original.get(0x93),device.state.get(0x93),"Recovery leaves USR intact");
        }
        ApplyTest.Device device=fixture(10,10,20,true);
        Recovery pending = new Recovery();
        device.failAt=1;
        try { PlusChannelSwap.run(driver(device,"1.0.3",120),ID,false,pending); throw new AssertionError(); }
        catch(IOException expected) { ApplyTest.expect(pending.bytes!=null); }
        device.lost=false;
        device.state.get(0x93)[34]^=1;
        try { PlusChannelSwap.run(driver(device,"1.0.3",120),ID,false,pending); throw new AssertionError("External edit accepted"); }
        catch(IOException expected) { ApplyTest.expect(device.writes==1); }
    }

    private static void bluetooth() throws IOException {
        for (int vendor : new int[]{PlusGaia.V3,PlusGaia.LEGACY}) {
            ApplyTest.Device device = fixture(10,20,20,true);
            PlusUsbProtocol.Transport bt = new PlusUsbProtocol.Transport() {
                public void send(byte[] report) throws IOException {
                    byte[] frame = PlusGaia.encode(report,vendor);
                    ApplyTest.expect((frame[0] & 255)==0xf0 && (frame[1] & 255)==(vendor & 255));
                    byte[] raw = new byte[128];
                    raw[0] = 8;
                    raw[1] = (byte)(frame.length - 1);
                    raw[2] = (byte)128;
                    System.arraycopy(frame,2,raw,3,frame.length-2);
                    device.send(raw);
                }
                public byte[] receive(int timeout) throws IOException {
                    byte[] report = device.receive(timeout);
                    int prefix = vendor == PlusGaia.LEGACY ? 5 : 4;
                    byte[] frame = new byte[prefix + report.length - 4];
                    frame[0] = (byte)(vendor >> 8);
                    frame[1] = (byte)vendor;
                    frame[2] = report[2];
                    frame[3] = report[3];
                    System.arraycopy(report,4,frame,prefix,report.length-4);
                    return PlusGaia.decode(frame,vendor);
                }
            };
            PlusApply driver = new PlusApply(bt,new PlusUsbProtocol.Status("1.0.3",PlusPreset.Mode.LAYERED,null,null,80));
            byte[] initial = device.state.get(SPK);
            ApplyTest.expect(PlusChannelSwap.run(driver,ID,false,new Recovery()).swapped);
            checkChannels(device.state.get(SPK),initial);
            ApplyTest.expect(!PlusChannelSwap.run(driver,ID,true,new Recovery()).swapped);
            same(device.state.get(SPK),initial,"Bluetooth swap reversed");
        }
    }

    static void run() throws IOException {
        int count=0;
        for (int l=0;l<=20;l++) for (int r=0;r<=20;r++) {
            for (int main=0;main<=30-Math.max(l,r);main++) {
                exercise(main,l,r,true);
                count++;
            }
        }
        for (int main=0;main<=30;main++) exercise(main,20,0,false);
        interrupts();
        bluetooth();
        ApplyTest.Device d=fixture(0,0,0,true);
        Recovery storage=new Recovery();
        storage.fail=true;
        try { PlusChannelSwap.run(driver(d,"1.0.3",120),ID,false,storage); throw new AssertionError(); }
        catch (IOException expected) { ApplyTest.expect(d.writes==0); }
        try { PlusChannelSwap.run(driver(d,"1.0.3",120),ID,true,new Recovery()); throw new AssertionError(); }
        catch (IOException expected) { ApplyTest.expect(d.writes==0); }
        ApplyTest.Device flat=fixture(0,0,0,true);
        PlusPreset.Channel neutral=PlusPreset.Channel.flat();
        flat.state.put(SPK,PlusApply.preset(flat.state.get(SPK),new PlusPreset.Channel[]{neutral,neutral},20));
        byte[] empty=flat.state.get(SPK);
        ApplyTest.expect(PlusChannelSwap.run(driver(flat,"1.0.3",120),ID,false,new Recovery()).swapped);
        ApplyTest.expect(flat.writes==1 && flat.state.get(SELECTOR)[3]==9);
        same(flat.state.get(SPK),empty,"Empty symmetric SPK remains untouched");
        ApplyTest.Device geq=fixture(10,0,20,true);
        byte[] initial=geq.state.get(SPK);
        initial[0]&=~1;
        ApplyTest.expect(PlusChannelSwap.run(driver(geq,"1.0.3",120),ID,false,new Recovery()).swapped);
        checkChannels(geq.state.get(SPK),initial);
        System.out.println("PASS Plus output/SPK swap across "+count+" layered allocations and all 31 shared counts, USB and both Bluetooth frames, and recovery failures.");
    }
}
