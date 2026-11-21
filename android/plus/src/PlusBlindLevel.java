package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Level matching on the physical program, after the editor's per-bank headroom and overflow routing. */
final class PlusBlindLevel {
    private final Map<Integer,byte[]> input;
    private final int mainGroup;
    private final PlusPreset.Channel[] main, match;
    final PeqResponse.ModeledLevel level;

    PlusBlindLevel(Map<Integer,byte[]> input)throws IOException{
        this.input=input;
        int mode=(input.get(0x80)[0]&255)>>>4;
        if(mode>1)throw new IOException("Unknown EQ layout for loudness matching.");
        mainGroup=mode==0?0:2;
        main=enabled(mainGroup);
        match=mode==0?enabled(1):null;
        PeqResponse[] shared=new PeqResponse[2],ears=new PeqResponse[2];
        if(main!=null)shared[0]=shared[1]=PlusPreset.response(main[0],PeqResponse.RATE);
        if(match!=null)for(int ear=0;ear<2;ear++)ears[ear]=PlusPreset.response(match[ear],PeqResponse.RATE);
        level=PeqResponse.stereoLevel(shared,ears);
    }

    private PlusPreset.Channel[] enabled(int group)throws IOException{
        int selector=PlusLibrary.base(group);
        if((input.get(selector)[0]&16)==0)return null;
        byte[] preset=input.get(selector+3);
        if((preset[0]&1)==0)throw new IOException("Loudness match requires PEQ in every enabled EQ stage. Choose PEQs or turn off Loudness match.");
        return PlusLibrary.decode(group,preset);
    }

    private static int available(PlusPreset.Channel[] channels){
        if(channels==null)return 0;
        int room=480;
        for(PlusPreset.Channel ch:channels)room=Math.min(room,ch.preamp+240);
        return room;
    }

    Map<Integer,byte[]> trimmed(int tenths)throws IOException{
        int mainCut=Math.min(tenths,available(main)), matchCut=tenths-mainCut;
        if(tenths<0 || matchCut>available(match))throw new IOException("The enabled preamps cannot supply enough attenuation. Adjust the presets or turn off Loudness match.");
        Map<Integer,byte[]> out=new LinkedHashMap<>(input);
        apply(out,mainGroup,main,mainCut);
        apply(out,1,match,matchCut);
        return out;
    }

    private static void apply(Map<Integer,byte[]> out,int group,PlusPreset.Channel[] channels,int cut){
        if(cut==0)return;
        int reg=PlusLibrary.base(group);
        byte[] data=out.get(reg+3).clone(), selector=out.get(reg).clone();
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for(int ch=0;ch<channels.length;ch++)b.putShort(12+2*ch,(short)(channels[ch].preamp-cut));
        selector[3]=(byte)255;
        out.put(reg,selector);out.put(reg+3,data);
    }
}
