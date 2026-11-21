package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

final class CompanionTest {
    private static final class Device extends ApplyTest.Device {
        final byte[][][] saved=new byte[3][20][];
        final byte[][][] names=new byte[3][20][];
        Device() {
            for (int group=0;group<3;group++) state.put(PlusLibrary.base(group)+1,new byte[12]);
            for(int[] row:new int[][]{{0xa2,12},{0xb2,12},{0x20,4},{0x21,4},{0x30,8},{0x31,48},{0x50,16},{0x40,8},{0x41,4},{0x51,16},{0x52,16},{0x70,28},{0x34,12},{0x61,16}})
                state.put(row[0],new byte[row[1]]);
        }
        @Override public void send(byte[] report) throws IOException {
            int cmd=report[4]&255, tag=report[6]&255;
            if(cmd==2 && (tag&15)==4 && tag>=0x94) {
                int group=report[8], slot=report[9];
                byte[] name=names[group][slot];
                byte[] value=new byte[2+name.length];
                value[0]=(byte)tag;value[1]=(byte)(name.length/4);
                System.arraycopy(name,0,value,2,name.length);respond(0x9003,128,value);return;
            }
            if(cmd==6 && tag==0xd0){
                ApplyTest.expect(report[5]==(byte)128 && report[7]==1 && report[8]==3 && report[9]==0 && report[10]==0 && report[11]==0);
                state.get(0x31)[0]&=~2;return;
            }
            if(cmd==6) {
                int group=report[8], slot=report[9], base=PlusLibrary.base(group);
                ApplyTest.expect(tag==0x30 && saved[group][slot]==null);
                saved[group][slot]=state.get(base+3).clone();
                names[group][slot]=state.get(base+4).clone();
                ByteBuffer flags=ByteBuffer.wrap(state.get(base+1)).order(ByteOrder.LITTLE_ENDIAN);
                flags.putInt(0,flags.getInt(0)|(1<<slot)); flags.putInt(8,flags.getInt(8)|(1<<slot));return;
            }
            super.send(report);
            if(cmd==4 && report[5]==(byte)128 && (tag==0x90 || tag==0xa0 || tag==0xb0)) {
                int i=(state.get(tag)[3]&255)-22;
                if(i>=0 && i<20) state.put(tag+3,saved[(tag-0x90)/16][i].clone());
            }
            if(cmd==4 && tag==0x72) System.arraycopy(state.get(tag),2,state.get(0x70),4,2);
        }
    }
    private static void usbModes() throws IOException {
        Device dev=new Device();
        dev.state.put(0x20,new byte[]{3,7,8,9});
        for(int target:new int[]{1,2,0}){
            PlusLevels shown=PlusLevels.read(dev);
            dev.state.get(0x20)[1]++;
            byte[] expected=dev.state.get(0x20).clone();
            expected[0]=(byte)((target<<4)|3);
            shown.setUsbMode(dev,120,target);
            ApplyTest.expect(Arrays.equals(dev.state.get(0x20),expected) && PlusLevels.read(dev).usbMode==target);
        }
        PlusLevels normal=PlusLevels.read(dev);
        normal.setUsbMode(dev,120,0);
        dev.state.get(0x20)[0]=0x13;
        try{normal.setUsbMode(dev,120,2);throw new AssertionError("Stale USB mode accepted");}catch(IOException expected){}
        try{normal.setUsbMode(dev,120,3);throw new AssertionError("Unknown USB mode accepted");}catch(IOException expected){}
        ApplyTest.expect(dev.writes==3 && PlusLevels.read(dev).usbMode==1);
        dev.failAt=4;
        try{PlusLevels.read(dev).setUsbMode(dev,120,2);throw new AssertionError("Disconnected mode reported verified");}catch(IOException expected){}
        dev.lost=false;
        ApplyTest.expect(dev.writes==4 && PlusLevels.read(dev).usbMode==2);
    }
    private static void inputs() throws IOException {
        Device dev=new Device();
        byte[] peers=dev.state.get(0x31);
        peers[4]=2; // An app-only control connection must not appear as an audio input.
        peers[8]=0x55;
        peers[12]=65; // Classic, source 1.
        peers[16]=0x12;peers[17]=0x78;peers[18]=0x56;peers[19]=0x34;
        peers[20]=69;peers[21]=1; // LE, source 5.
        peers[24]=(byte)0x92;peers[25]=0x66;peers[26]=0x77;peers[27]=(byte)0x88;
        byte[] config=dev.state.get(0x50);
        config[1]=(byte)0xe3;config[10]=71;
        byte[] audio=dev.state.get(0x51);
        audio[0]=9;
        PlusLevels initial=PlusLevels.read(dev);
        ApplyTest.expect(initial.canSelectInput(1) && initial.canSelectInput(2)
                && !initial.canSelectInput(3) && initial.playingSelectedInput(initial,1));
        ApplyTest.expect(initial.activePhonePeer(0)==initial.bluetoothInputPeer(1) && initial.activePhonePeer(1)==0);

        initial.selectInput(dev,120,initial,2);
        byte[] expected=config.clone();
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putInt(4,0x92887766);
        expected[1]=(byte)0xd5;
        ApplyTest.expect(Arrays.equals(dev.state.get(0x50),expected));
        PlusLevels selected=PlusLevels.read(dev);
        ApplyTest.expect(selected.preferredInput(2) && !selected.playingSelectedInput(initial,2));
        audio[0]=41;audio[1]=(byte)(24<<3);
        ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).putInt(4,48000);
        PlusLevels playing=PlusLevels.read(dev);
        PlusUsbProtocol.Status status=playing.status(new PlusUsbProtocol.Status("",PlusPreset.Mode.LAYERED,null,null,120));
        ApplyTest.expect(playing.playingBluetooth() && playing.playingSelectedInput(initial,2)
                && status.sampleRate==48000 && status.bits==24 && status.inputLabel().equals("LE Audio 1"));
        ApplyTest.expect(playing.activePhonePeer(1)==initial.bluetoothInputPeer(2) && playing.activePhonePeer(0)==0);
        peers[28]=(byte)133;peers[29]=1;peers[32]=0x51; // A second LE transmitter prevents attribution.
        PlusLevels ambiguous=PlusLevels.read(dev);
        ApplyTest.expect(ambiguous.activePhonePeer(1)==0 && ambiguous.phonePeer(1)==0);
        peers[28]=0;

        peers[24]=0x45;
        try{PlusLevels.read(dev).selectInput(dev,120,initial,2);throw new AssertionError("Stale Bluetooth peer selected");}catch(IOException expectedFailure){}
        ApplyTest.expect(dev.writes==1);
        peers[24]=(byte)0x92;

        playing.selectInput(dev,120,playing,1);
        expected[1]=(byte)0xc1;
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putInt(4,0x12345678);
        ApplyTest.expect(Arrays.equals(dev.state.get(0x50),expected) && PlusLevels.read(dev).preferredInput(1));
        dev.state.get(0x21)[0]=4;
        PlusLevels usb=PlusLevels.read(dev);
        usb.selectInput(dev,120,usb,3);
        ApplyTest.expect(Arrays.equals(dev.state.get(0x50),config) && PlusLevels.read(dev).preferredInput(3));
        audio[0]=25;
        PlusLevels activeUsb=PlusLevels.read(dev);
        ApplyTest.expect(activeUsb.playingSelectedInput(usb,3) && !activeUsb.playingBluetooth()
                && activeUsb.activePhonePeer(1)==0 && activeUsb.phonePeer(1)==initial.bluetoothInputPeer(2));
    }
    static void run() throws IOException {
        usbModes();
        inputs();
        Device dev=new Device();
        byte[] bluetooth={(byte)0x21,42,(byte)0xd5,(byte)0xb1,9,8,7,6};
        dev.state.put(0x30,bluetooth.clone());dev.state.get(0x31)[0]=3;
        PlusUsbProtocol.automaticPairing(dev,120,false);
        ApplyTest.expect(dev.state.get(0x31)[0]==1 && dev.state.get(0x30)[2]==0x55);
        PlusUsbProtocol.automaticPairing(dev,120,true);
        ApplyTest.expect(Arrays.equals(bluetooth,dev.state.get(0x30)) && dev.state.get(0x31)[0]==1);
        PlusPreset.Band a=new PlusPreset.Band(5,150,-37,1400), b=new PlusPreset.Band(3,200,25,707);
        PlusPreset.Channel basic=new PlusPreset.Channel(-13,Arrays.asList(a,b));
        PlusPreset.Channel[][] input={{basic},{basic,PlusPreset.Channel.flat()},{basic}};
        for(int choice:new int[]{3,0,1,2}) {
            int slot=PlusLibrary.create(dev,120,choice,"new preset",input);
            PlusPreset.Channel[][] loaded=PlusLibrary.load(dev,120,choice,new int[]{slot,slot,slot});
            for(int g:PlusLibrary.groups(choice)) {
                ApplyTest.expect("new preset".equals(PlusLibrary.read(dev).names[g][slot]));
                ApplyTest.expect(loaded[g][0].bands.get(0).gain==-37 && loaded[g][0].bands.get(1).hz==200);
                ApplyTest.expect(loaded[g][0].preamp==-13);
            }
            if(choice==3) ApplyTest.expect(PlusLibrary.pairKey(input).equals(PlusLibrary.pairKey(loaded)));
        }
        ApplyTest.expect(PlusLibrary.read(dev).free(3)==2);
        dev.state.get(0x51)[0]=21;
        dev.state.get(0x34)[4]=45;
        ByteBuffer.wrap(dev.state.get(0x70)).order(ByteOrder.LITTLE_ENDIAN).putShort(4,(short)-1800).putShort(24,(short)23);
        ByteBuffer.wrap(dev.state.get(0x50)).order(ByteOrder.LITTLE_ENDIAN).putInt(12,320 | 990<<14);
        dev.state.get(0x80)[0]=0; // Layered mode uses the SPK load.
        ByteBuffer.wrap(dev.state.get(0xa2)).order(ByteOrder.LITTLE_ENDIAN).putInt(0,640<<1);
        PlusLevels levels=PlusLevels.read(dev);
        ApplyTest.expect(levels.headphoneOhms==64 && levels.sensitivity==99);
        ApplyTest.expect(levels.adjustable() && levels.millivolts==23 && levels.bluetoothAudioMs==45);
        byte[] original=dev.state.get(0x40).clone();
        levels.setDac(dev,120,true,1);
        ApplyTest.expect(dev.state.get(0x40)[2]==4);
        PlusLevels.read(dev).setDac(dev,120,true,0);
        PlusLevels.read(dev).setDac(dev,120,false,1);
        ApplyTest.expect(dev.state.get(0x40)[1]==16);
        PlusLevels.read(dev).setDac(dev,120,false,0);
        ApplyTest.expect(Arrays.equals(original,dev.state.get(0x40)));
        levels.setGain(dev,120,levels,-1830);
        ApplyTest.expect(PlusLevels.read(dev).gain==-1830);
        try { levels.setGain(dev,120,levels,30); throw new AssertionError(); } catch(IOException expected) {}
        PlusLevels.Stream stream=new PlusLevels.Stream(dev);
        byte[] noti=new byte[18]; noti[0]=0x52; noti[1]=4; noti[2]=2;
        ByteBuffer.wrap(noti).order(ByteOrder.LITTLE_ENDIAN).putInt(10,1<<28).putInt(14,1<<27);
        dev.respond(0x9005,0,Arrays.copyOfRange(noti,0,9));
        dev.respond(0x9005,129,Arrays.copyOfRange(noti,9,18));
        levels=PlusLevels.read(stream);
        ApplyTest.expect(Math.abs(levels.left+6.0618)<.001 && Math.abs(levels.right+12.0824)<.001);
        stream.resetMeter(); ApplyTest.expect(!PlusLevels.read(stream).meterReady);
        System.out.println("PASS slot discovery, saves/loads, Bluetooth and USB input selection, DAC switches, volume, latency and RMS.");
    }
}
