package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.util.*;

final class BlindPlusTest {
    private static final String OWNER="plus-001122334455";
    private static class Store implements PlusBlindRound.Journal {
        byte[] record; boolean fail;
        public void save(byte[] bytes)throws IOException {if(fail)throw new IOException("Storage full");record=bytes.clone();}
        public void clear(){record=null;}
        PlusBlindRound read()throws IOException{return PlusBlindRound.read(record);}
    }
    private interface Attempt {void run() throws IOException;}
    private static void rejects(Attempt a) {
        try{a.run();throw new AssertionError("Expected operation to fail");}catch(IOException expected){}
    }
    private static PlusApply hardware(ApplyTest.Device d)throws IOException{
        return new PlusApply(d,new PlusUsbProtocol.Status("1.0.3",PlusPreset.Mode.LAYERED,null,null,120));
    }
    private static PlusBlindRound.Choice preset(String name,PlusPreset.Mode layout,int hz) {
        PlusPreset.Channel a=new PlusPreset.Channel(-20,Collections.singletonList(new PlusPreset.Band(5,hz,20,1100)));
        PlusPreset.Channel empty=PlusPreset.Channel.flat();
        return new PlusBlindRound.Choice(name,layout,PlusPreset.banks(layout,a,empty,empty,false));
    }
    private static void original(ApplyTest.Device d, Map<Integer,byte[]> baseline){
        for(int id:PlusApply.WORKING)ApplyTest.expect(Arrays.equals(d.state.get(id),baseline.get(id)));
    }
    static void run()throws IOException{
        PlusBlindRound.Choice first=preset("Warm",PlusPreset.Mode.LAYERED,250),
                second=preset("Bright",PlusPreset.Mode.SHARED,5000);
        ApplyTest.Device d=new ApplyTest.Device(); PlusApply hw=hardware(d); Store disk=new Store();
        Map<Integer,byte[]> initial=hw.read(PlusApply.WORKING);
        rejects(()->PlusBlindRound.start(hw,OWNER,first,first,false,disk));
        disk.fail=true;rejects(()->PlusBlindRound.start(hw,OWNER,first,second,false,disk));ApplyTest.expect(d.writes==0);
        disk.fail=false;PlusBlindRound.start(hw,OWNER,first,second,false,disk);
        ApplyTest.expect(d.writes==0 && disk.read().round.hidden());
        rejects(()->disk.read().choose(0,disk));
        rejects(()->disk.read().hear(0,hw,"plus-001122334456",disk));ApplyTest.expect(d.writes==0);
        disk.fail=true;rejects(()->disk.read().hear(0,hw,OWNER,disk));ApplyTest.expect(d.writes==0);
        disk.fail=false;disk.read().hear(0,hw,OWNER,disk);ApplyTest.expect(disk.read().round.heardMask==1);
        rejects(()->disk.read().choose(0,disk));
        boolean hiddenAssignment=disk.read().round.swapped;
        disk.read().hear(1,hw,OWNER,disk);disk.read().choose(1,disk);
        PlusBlindRound restored=disk.read();
        ApplyTest.expect(restored.round.swapped==hiddenAssignment && restored.round.favorite==1 && !restored.round.hidden());
        rejects(()->disk.read().end(hw,"plus-001122334456",disk));
        restored.end(hw,OWNER,disk);ApplyTest.expect(disk.record==null);original(d,initial);

        // A partial write can be resumed by another process over either route on the same DAC.
        for(int failAt=1; failAt<=4; failAt++){
            ApplyTest.Device partial=new ApplyTest.Device();PlusApply transport=hardware(partial);Store journal=new Store();
            Map<Integer,byte[]> saved=transport.read(PlusApply.WORKING);
            PlusBlindRound pending=PlusBlindRound.start(transport,OWNER,PlusBlindRound.Choice.device(),first,false,journal);
            int audition=pending.round.source(0)==1?0:1;
            partial.failAt=failAt;
            rejects(()->journal.read().hear(audition,transport,OWNER,journal));
            ApplyTest.expect(journal.read().round.heardMask==0 && journal.read().interrupted());
            partial.lost=false;
            rejects(()->journal.read().hear(audition,transport,OWNER,journal));
            journal.read().end(hardware(partial),OWNER,journal);original(partial,saved);ApplyTest.expect(journal.record==null);
        }
        PlusBlindRound round=PlusBlindRound.start(hw,OWNER,first,second,false,disk);
        round.hear(1,hw,OWNER,disk);
        byte[] wanted=d.state.get(0xa3).clone();d.state.get(0xa3)[9]^=3;
        int writes=d.writes;rejects(()->disk.read().end(hw,OWNER,disk));ApplyTest.expect(d.writes==writes&&disk.record!=null);
        d.state.put(0xa3,wanted);
        d.failAt=d.writes+1;rejects(()->disk.read().end(hw,OWNER,disk));
        ApplyTest.expect(disk.read().interrupted());d.lost=false;
        disk.read().end(hw,OWNER,disk);original(d,initial);
        d.state.get(0x80)[0]=(byte)0xf0;rejects(()->PlusBlindRound.start(hw,OWNER,first,second,false,disk));
        rejects(()->new PlusApply(d,new PlusUsbProtocol.Status("1.0.4",PlusPreset.Mode.LAYERED,null,null,120)));
        System.out.println("PASS Plus blind round: device binding, randomized persistent labels, both verified sides before reveal, mixed layouts, journal failures, interrupted auditions/restores and external-edit protection.");
    }
}
