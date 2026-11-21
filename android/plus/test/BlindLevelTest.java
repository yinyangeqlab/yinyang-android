package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.nio.*;
import java.util.*;

final class BlindLevelTest {
    private static PlusPreset.Channel channel(int pre,PlusPreset.Band... bands){return new PlusPreset.Channel(pre,Arrays.asList(bands));}
    private static Map<Integer,byte[]> source(Map<Integer,byte[]> base,PlusPreset.Mode mode,PlusPreset.Channel main,
                                               PlusPreset.Channel left,PlusPreset.Channel right,boolean headroom)throws IOException{
        return PlusApply.plan(base,mode,PlusPreset.banks(mode,main,left,right,headroom));
    }
    private static void close(double actual,double expected){
        if(Math.abs(actual-expected)>1e-6)throw new AssertionError(actual+" differs from "+expected);
    }
    private static int pre(Map<Integer,byte[]> state,int group,int ear) {
        return ByteBuffer.wrap(state.get(PlusLibrary.base(group)+3)).order(ByteOrder.LITTLE_ENDIAN).getShort(12+ear*2);
    }
    static void run()throws IOException{
        ApplyTest.Device fixture=new ApplyTest.Device();
        PlusApply io=new PlusApply(fixture,new PlusUsbProtocol.Status("1.0.3",PlusPreset.Mode.LAYERED,null,null,120));
        Map<Integer,byte[]> baseline=io.read(PlusApply.WORKING);
        PlusPreset.Channel flat=channel(0);
        Map<Integer,byte[]> a=source(baseline,PlusPreset.Mode.SHARED,flat,flat,flat,false);
        Map<Integer,byte[]> b=source(baseline,PlusPreset.Mode.SHARED,channel(-60),flat,flat,false);
        PlusBlindLevel louder=new PlusBlindLevel(a), softer=new PlusBlindLevel(b);
        int[] cuts=PeqResponse.matchAttenuation(louder.level,softer.level);
        ApplyTest.expect(Arrays.equals(cuts,new int[]{60,0}));
        close(new PlusBlindLevel(louder.trimmed(60)).level.stereoPowerDb,softer.level.stereoPowerDb);
        ApplyTest.expect(pre(a,2,0)==0); // Never modify the recovery snapshot, even for the device-source choice.
        ApplyTest.expect(Arrays.equals(PeqResponse.matchAttenuation(louder.level,louder.level),new int[]{10,10}));
        Map<Integer,byte[]> stereo=source(baseline,PlusPreset.Mode.LAYERED,channel(-30),flat,channel(-60),false);
        PlusBlindLevel asymmetric=new PlusBlindLevel(stereo);
        close(asymmetric.level.stereoPowerDb, -3+10*Math.log10((1+Math.pow(10,-.6))/2));
        close(asymmetric.level.peakDb,-3);
        // A common attenuation can be split across stages, equally on both match preamps.
        Map<Integer,byte[]> tight=source(baseline,PlusPreset.Mode.LAYERED,channel(-235),channel(10),channel(-10),false);
        Map<Integer,byte[]> trim=new PlusBlindLevel(tight).trimmed(50);
        ApplyTest.expect(pre(trim,0,0)==-240 && pre(trim,1,0)==-35 && pre(trim,1,1)==-55);
        close(new PlusBlindLevel(tight).level.stereoPowerDb-new PlusBlindLevel(trim).level.stereoPowerDb,5);
        try{new PlusBlindLevel(tight).trimmed(300);throw new AssertionError();}catch(IOException expected){}
        // Reconstruct the actual physical split, including the stage headroom and the shared spill into L/R.
        List<PlusPreset.Band> bands=new ArrayList<>();
        for(int i=0;i<12;i++)bands.add(new PlusPreset.Band(5,250+i*170,8+i,1300));
        PlusPreset.Channel main=new PlusPreset.Channel(-10,bands),right=channel(-25,new PlusPreset.Band(4,4100,-30,1270));
        PlusPreset.Banks plan=PlusPreset.banks(PlusPreset.Mode.LAYERED,main,flat,right,true);
        PlusBlindLevel level=new PlusBlindLevel(PlusApply.plan(baseline,PlusPreset.Mode.LAYERED,plan));
        PeqResponse m=PlusPreset.response(main,48000), l=PlusPreset.response(flat,48000), r=PlusPreset.response(right,48000);
        PeqResponse.ModeledLevel unsplit=PeqResponse.stereoLevel(new PeqResponse[]{m,m},new PeqResponse[]{l,r});
        double headroomDb=(main.preamp-plan.user.preamp + flat.preamp-plan.match[0].preamp)/10.;
        ApplyTest.expect(plan.user.bands.size()==10 && plan.match[0].bands.size()==2 && headroomDb>0);
        close(level.level.stereoPowerDb,unsplit.stereoPowerDb-headroomDb);
        close(level.level.peakDb,unsplit.peakDb-headroomDb);
        // A disabled stage is silent in the model even if its stored filter encoding is unsupported.
        stereo.get(0xa0)[0]&=~16;stereo.get(0xa3)[0]&=~1;
        close(new PlusBlindLevel(stereo).level.stereoPowerDb,-3);
        stereo.get(0xa0)[0]|=16;
        try{new PlusBlindLevel(stereo);throw new AssertionError();}catch(IOException expected){}
        stereo.get(0xa3)[0]|=1;
        ByteBuffer.wrap(stereo.get(0xa3)).order(ByteOrder.LITTLE_ENDIAN).putInt(96,7|1024<<14);
        try{new PlusBlindLevel(stereo);throw new AssertionError();}catch(IOException expected){}
        // A matched round persists the choices and applied attenuation, then restores all seven exact blocks.
        class Journal implements PlusBlindRound.Journal{
            byte[] bytes;
            public void save(byte[] next){bytes=next.clone();}
            public void clear(){bytes=null;}
        }
        Journal journal=new Journal();
        PlusBlindRound start=PlusBlindRound.start(io,"unit",
                new PlusBlindRound.Choice("Flat",PlusPreset.Mode.SHARED,PlusPreset.banks(PlusPreset.Mode.SHARED,flat,flat,flat,false)),
                new PlusBlindRound.Choice("Quiet",PlusPreset.Mode.SHARED,PlusPreset.banks(PlusPreset.Mode.SHARED,channel(-60),flat,flat,false)),true,journal);
        ApplyTest.expect(start.levelMatched && Arrays.equals(start.attenuation,cuts) && fixture.writes==0);
        PlusBlindRound.read(journal.bytes).hear(0,io,"unit",journal);
        PlusBlindRound.read(journal.bytes).hear(1,io,"unit",journal);
        PlusBlindRound restored=PlusBlindRound.read(journal.bytes);
        ApplyTest.expect(restored.levelMatched && restored.round.heardMask==3 && restored.round.hidden());
        restored.choose(0,journal);
        PlusBlindRound.read(journal.bytes).end(io,"unit",journal);
        for(int reg:PlusApply.WORKING)ApplyTest.expect(Arrays.equals(baseline.get(reg),fixture.state.get(reg)));
        System.out.println("PASS modeled 5K Plus loudness matching: known trims, asymmetric stereo power, headroom, shared spill, 30-band mode, matched journal/restore, disabled and unsupported stages, and attenuation limits.");
    }
}
