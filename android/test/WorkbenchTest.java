package com.yinyangeq.qudelixprobe;

import java.io.*;
import java.nio.*;
import java.util.*;

public final class WorkbenchTest {
    static int checks;
    static void check(boolean valid) {
        if (!valid)
            throw new AssertionError("Check " + (checks + 1));
        checks++;
    }
    interface Job {
        void run() throws Exception;
    }
    static void rejects(Job job) throws Exception {
        try {
            job.run();
        } catch (IllegalArgumentException | IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Expected rejection");
    }
    static void usbSettings() throws Exception {
        byte[] sys = new byte[12], sys2 = new byte[32], mic = new byte[4];
        sys[4] = 2 << 5;
        mic[0] = (byte)((22 << 2) | 2);
        mic[2] = 1;
        sys2[20] = (byte)((6 << 3) | 5);
        for (int platform = 0; platform < 3; platform++) {
            sys2[19] = (byte)(platform << 5);
            UsbAudioSettings original = UsbAudioSettings.decode(sys,sys2,mic);
            check(original.platform == platform && original.rate == new int[]{2,5,6}[platform]);
            check(original.source == 0 && original.gain == 22 && original.music == 1 && original.voice == 1);
            check(UsbAudioSettings.unpack(original.packed()).packed() == original.packed());
            UsbAudioSettings target = original.measurement();
            check(target.gain == original.gain && target.rate == 5 && target.platform == platform);
            List<String> writes = new ArrayList<>();
            original.applyMicChanges(target,(f,v)->writes.add(f.name()+v));
            check(writes.equals(Arrays.asList("MUSIC0","VOICE0","SOURCE1")));
            writes.clear();
            target.applyMicChanges(original,(f,v)->writes.add(f.name()+v));
            check(writes.equals(Arrays.asList("SOURCE0","VOICE1","MUSIC1")));
            writes.clear();
            target.applyMicChanges(target,(f,v)->writes.add(f.name()));
            check(writes.isEmpty());
            rejects(()->target.with(UsbAudioSettings.Field.GAIN,23));
            if (platform != 0) rejects(()->target.with(UsbAudioSettings.Field.RATE,4));
        }
        rejects(()->UsbAudioSettings.decode(new byte[11],sys2,mic));
        rejects(()->UsbAudioSettings.unpack(-1));
        sys2[19] = (byte)(3 << 5);
        rejects(()->UsbAudioSettings.decode(sys,sys2,mic));
    }

    private static void editHistory() {
        Peq original = Peq.flat(1);
        Peq changed = original.copy();
        changed.pre(1, -30);
        EqEditHistory history = new EqEditHistory();
        history.record(original, original);
        check(!history.canUndo() && !history.canRedo());
        history.record(original, changed);
        check(history.first().same(original));
        check(history.canUndo() && history.undo(changed).same(original));
        EqEditHistory opened = new EqEditHistory();
        opened.reload(1, history.undoBlocks(), history.redoBlocks());
        check(opened.canRedo() && opened.redo(original).same(changed));
        history.record(original, changed);
        check(!history.canRedo() && history.undo(changed).same(original));
        history.reload(1, "not-valid-base64", "");
        check(!history.canUndo() && !history.canRedo());
    }

    private static void dacCommands() throws Exception {
        Wire device = new Wire();
        device.swapped = true;
        try (GaiaProbe.Connection link = device.connect()) {
            DacSettings original = link.dacSettings();
            check(original.profile == 0 && original.gain == 0 && original.swapped);
            DacSettings performance = link.setDacSetting(original, DacSettings.Field.PROFILE, 1);
            check(performance.profile == 1 && performance.effectiveProfile == 1 && performance.gain == 0);
            rejects(() -> link.setDacSetting(original, DacSettings.Field.GAIN, 1));
            check(device.gainWrites == 0);
            DacSettings high = link.setDacSetting(performance, DacSettings.Field.GAIN, 1);
            check(high.gain == 1 && high.profile == 1 && high.swapped);
            DacSettings normal = link.setDacSetting(high, DacSettings.Field.GAIN, 0);
            check(normal.gain == 0 && device.profileWrites == 1 && device.gainWrites == 2 && device.saves == 0);
        }
        device.firmwareByte = (byte)0x80;
        try (GaiaProbe.Connection link = device.connect()) {
            rejects(link::dacSettings);
        }
    }

    private static void batteryEstimate() {
        long minute = 60000;
        BatteryEstimate estimate = BatteryEstimate.forLink("first-link");
        for (int i = 0; i < 14; i++)
            check(estimate.update(i < 7 ? 13 : 12, null, i * minute).endsWith("Estimating"));
        check(estimate.update(11, null, 14 * minute).equals("Time ETA · 1:17"));
        check(BatteryEstimate.forLink("first-link").update(11, null, 15 * minute).endsWith("1:16"));
        check(estimate.update(10, null, 17 * minute).endsWith("Estimating"));
        check(estimate.update(10, "Charging", 18 * minute).endsWith("Charging"));
        check(estimate.update(-1, null, 19 * minute).equals(BatteryEstimate.UNAVAILABLE));
        check(BatteryEstimate.forLink("second-link").update(11, null, 19 * minute).endsWith("Estimating"));

        BatteryEstimate earlyDrop = new BatteryEstimate();
        int[] samples = {52, 52, 51, 50, 50};
        for (int i = 0; i < samples.length; i++)
            check(earlyDrop.update(samples[i], null, i * minute).endsWith("Estimating"));
        check(earlyDrop.update(50, null, 5 * minute).equals("Time ETA · 2:05"));
        check(earlyDrop.update(50, null, 6 * minute).equals("Time ETA · 2:04"));
    }

    private static void powerAndPresets() throws IOException {
        Wire wire=new Wire();
        wire.block[0].pre(0,-23);
        wire.block[1].pre(1,-7);
        wire.slotNames[0][2]="Original";
        wire.slots[0][2]=wire.block[0].copy();
        try(GaiaProbe.Connection link=wire.connect()){
            EqSnapshot start=link.read();
            EqSnapshot mainOnly=EqSnapshot.setPower(link,start,1);
            check(mainOnly.enabledStages()==1 && wire.powerWrites==1);
            check(Arrays.equals(start.usr,mainOnly.usr)&&Arrays.equals(start.spk,mainOnly.spk));
            wire.rejectPower=1;
            try{
                EqSnapshot.setPower(link,mainOnly,2);
                throw new AssertionError("Unverified power state succeeded");
            }catch(IOException expected){check(expected.getMessage().contains("restored"));}
            check(link.read().enabledStages()==1 && wire.saves==0);
            wire.rejectPower=-1;
            EqSnapshot disabled=EqSnapshot.setPower(link,link.read(),0);
            check(disabled.enabledStages()==0);
            EqSnapshot moved=PresetDirectory.organize(link,disabled,0,2,6,"Original",true);
            check(moved.enabledStages()==0 && PeqSession.selector(moved,0)==28 && PeqSession.selector(moved,1)==24);
            check(PeqSession.block(moved,0).pre(0)==-23 && PeqSession.block(moved,1).pre(1)==-7);
            check(!PresetDirectory.named(moved,0,2) && PresetDirectory.named(moved,0,6));
            check(wire.slots[0][2].same(Peq.flat(0)));
            EqSnapshot renamed=PresetDirectory.name(link,moved,0,6,"Renamed");
            EqSnapshot reset=PresetDirectory.organize(link,renamed,0,6,-1,"Renamed",false);
            check(PresetDirectory.named(reset,0,6) && PeqSession.block(reset,0).same(Peq.flat(0)));
            EqSnapshot removed=PresetDirectory.organize(link,reset,0,6,-1,"Renamed",true);
            check(!PresetDirectory.named(removed,0,6) && PeqSession.selector(removed,0)==0);
        }
    }
    public static void main(String[] args) throws Exception {
        batteryEstimate();
        powerAndPresets();
        for(int deleted:new int[]{0,4,9}){
            Peq ears=Peq.flat(1);ears.pre(0,-15);ears.pre(1,7);
            for(int i=0;i<10;i++)for(int ear=0;ear<2;ear++)ears.band(ear,i,5,120+10*i+ear,i,1024+i);
            Peq before=ears.copy();ears.removeBand(0,deleted);
            for(int i=0;i<10;i++){
                check(ears.hz(1,i)==before.hz(1,i) && ears.word(1,i)==before.word(1,i));
                if(i<9){int source=i<deleted?i:i+1;check(ears.hz(0,i)==before.hz(0,source)&&ears.word(0,i)==before.word(0,source));}
            }
            check(ears.type(0,9)==0 && ears.pre(0)==-15 && ears.pre(1)==7);
        }
        check(SignalReading.state(-6.01)==SignalReading.State.NORMAL);
        check(SignalReading.state(-6)==SignalReading.State.LOW_HEADROOM);
        check(SignalReading.state(-1)==SignalReading.State.NEAR_FULL_SCALE);
        check(SignalReading.state(0)==SignalReading.State.CLIPPING_RISK && SignalReading.state(1)==SignalReading.State.CLIPPING_RISK);
        check(SignalReading.state(Double.NaN)==SignalReading.State.UNAVAILABLE);
        check(Math.abs(SignalReading.spl(-20,1000,1000,100)-80)<.00001);
        check(Double.isNaN(SignalReading.spl(0,1000,0,100)));
        usbSettings();
        dacCommands();
        editHistory();
        check(ConsolePresence.target(ConsolePresence.State.AWAKE,false,true)==1);
        check(ConsolePresence.target(ConsolePresence.State.STANDBY,false,true)==0);
        check(ConsolePresence.target(ConsolePresence.State.AWAKE,true,true)==0);
        check(ConsolePresence.target(ConsolePresence.State.UNKNOWN,false,true)==-1);
        check(ConsolePresence.target(ConsolePresence.State.AWAKE,false,false)==-1);
        check(ConsolePresence.parse("HTTP/1.1 200 Ok\r\ndevice-discovery-protocol-version:00030010\r\n")==ConsolePresence.State.AWAKE);
        check(ConsolePresence.parse("HTTP/1.1 620 Server Standby\ndevice-discovery-protocol-version:00030010\n")==ConsolePresence.State.STANDBY);
        check(ConsolePresence.parse("HTTP/1.1 200 Ok\ndevice-discovery-protocol-version:00020020\n")==ConsolePresence.State.UNKNOWN);
        rejects(()->ConsolePresence.localAddress("192.168.1.255"));
        rejects(()->ConsolePresence.localAddress("8.8.8.8"));
        check(ConsolePresence.localAddress("192.168.1.2").isSiteLocalAddress());
        Peq stereo = Peq.flat(0), spk = Peq.flat(1);
        spk.edit(1, 9, 4, "9333", "-3.4", "4.209");
        spk.pre(1, -73);
        spk.metadata(0, 325, 978);
        check(spk.importText(spk.exportText()).same(spk));
        spk.band(0,0,5,500,10,1024);
        Peq cleared=spk.clearFilters();
        check(!cleared.hasFilters() && spk.hasFilters() && cleared.pre(1)==-73
                && cleared.impedance()==325 && cleared.sensitivity()==978);
        stereo.edit(0, 0, 5, "100", "2", "1.2");
        stereo.metadata(11, 0, 0);
        check(stereo.importText(stereo.exportText()).same(stereo));
        Peq chart=Peq.flat(1);
        check(Math.abs(new PeqResponse(chart,0).db(3000))<0.00001);
        chart.pre(1,-20);chart.band(1,0,5,1000,60,1024);
        check(Math.abs(new PeqResponse(chart,1).db(1000)-4)<0.00001);
        check(Math.abs(new PeqResponse(chart,0).db(1000))<0.00001);
        chart.band(1,0,3,1000,-60,724);
        check(Math.abs(new PeqResponse(chart,1).db(20)+8)<0.01);
        chart.band(1,0,4,1000,-60,724);
        check(Math.abs(new PeqResponse(chart,1).db(20000)+8)<0.01);
        String sparse =
                "# YinYang EQ\nPreamp: -3.7 dB\nFilter 3: ON LSC Fc 150 Hz Gain -2.137890 dB Q 0.707100";
        Peq p = stereo.importText(sparse);
        check(p.type(0, 0) == 0 && p.type(0, 2) == 3 && p.pre(0) == -37 && p.gain(0, 2) == -21);
        String guide =
                "Engine: test\nPreference Tuning Lab · Qudelix 5K entry guide\n1 bands\nSet PRE GAIN to -1.3 "
                + "dB. Use a PEQ mode.\n\nBand\tType\tFrequency (Hz)\tGain (dB)\tQ\n1\tHSC\t3000\t1.4\t0.7\n";
        Peq entry = stereo.importText(guide);
        check(entry.type(0, 0) == 4 && entry.pre(0) == -13 && entry.gain(0, 0) == 14);
        rejects(() -> spk.importText(sparse));
        rejects(() -> stereo.importText(spk.exportText()));
        rejects(() -> stereo.importText(sparse + "\nFilter 3: ON PK Fc 110 Hz Gain 1 dB Q 1"));
        rejects(() -> stereo.importText(sparse.replace("Filter 3", "Filter 11")));
        rejects(() -> stereo.importText(sparse.replace("0.707100", "NaN")));
        rejects(() -> stereo.importText("TYPE: GEQ"));
        rejects(() -> spk.importText("Channel: L\n" + sparse));
        check(spk.type(1, 9) == 4 && spk.type(0, 9) == 0);
        byte[] status = new byte[17], config = new byte[17];
        status[0] = 11;
        config[0] = 2;
        status[5] = 3;
        status[6] = (byte) (91 << 1);
        ByteBuffer state = ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN);
        state.putInt(1, 2 | (6 << 5) | (3 << 9) | (2 << 12) | (3 << 16));
        state.putInt(13, (660 << 16) | 2);
        ByteBuffer.wrap(config).order(ByteOrder.LITTLE_ENDIAN).putShort(1, (short) -1200);
        DeviceLevels levels = new DeviceLevels(status, config);
        check(levels.battery == 91 && levels.charging.equals("Charging") && levels.volume == -1200
                && levels.adjustable());
        check(levels.stream.contains("Bluetooth 2") && levels.stream.contains("LDAC")
                && levels.stream.contains("44.1 kHz") && levels.stream.contains("660.0 kb/s"));
        check(levels.playingBluetooth() && levels.playingInput(2) && !levels.playingInput(1)
                && levels.sampleRate==44100 && levels.bitDepth==24);
        state.putInt(1, 2 | (1 << 9) | (2 << 12) | (4 << 16) | (1 << 22));
        status[5] = 0;
        levels = new DeviceLevels(status, config);
        check(levels.charging.equals("Discharging") && levels.stream.contains("PCM")
                && levels.stream.contains("2304.0 kb/s"));
        check(levels.playingInput(0) && !levels.playingBluetooth());
        state.putInt(1, 0);
        status[5] = 1;
        levels = new DeviceLevels(status, config);
        check(levels.charging.contains("idle") && levels.stream.equals("No audio playing"));
        byte[] metered = java.util.Arrays.copyOf(status,33);
        metered[0]=27;
        java.nio.ByteBuffer frames=java.nio.ByteBuffer.wrap(metered).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        frames.putInt(1,2 | (1<<9)); frames.putInt(17,1<<27); frames.putInt(21,0);
        DeviceLevels telemetry=new DeviceLevels(metered,config);
        check(Math.abs(telemetry.leftRms+12.0824)<.001 && telemetry.rightRms==Double.NEGATIVE_INFINITY);
        rejects(() -> new DeviceLevels(new byte[12], config));
        ByteArrayOutputStream commands = new ByteArrayOutputStream();
        try (GaiaProbe.Connection radio =
                        new GaiaProbe.Connection(new ByteArrayInputStream(new byte[0]), commands, t -> {})) {
            radio.volume(-390);
            check(Arrays.equals(commands.toByteArray(),
                    new byte[] {-1, 1, 0, 3, (byte) 0xf0, 1, 2, 0, 1, (byte) 0xfe, 0x7a}));
        }
        for (boolean swapped : new boolean[]{false,true}) {
            BlindRound round=new BlindRound(swapped,0,-1);
            check(round.hidden() && round.source(0)!=round.source(1));
            round.verified(1);
            try {round.pick(1);throw new AssertionError();} catch(IllegalStateException expected){checks++;}
            round.verified(0);
            BlindRound resumed=new BlindRound(round.swapped,round.heardMask,round.favorite);
            resumed.pick(1);
            check(!resumed.hidden() && resumed.source(resumed.favorite)==(swapped?0:1));
        }
        byte[] combined=new byte[29];
        combined[0]=3; combined[1]=48;
        System.arraycopy(config,1,combined,13,16);
        check(new DeviceLevels(status,combined).charging.contains("80%"));
        combined[1]=16;
        check(new DeviceLevels(status,combined).charging.contains("100%"));
        combined[1]=0;
        check(new DeviceLevels(status,combined).charging.contains("disabled"));
        status[5]=3;
        check(new DeviceLevels(status,combined).charging.equals("Charging"));
        check(new DeviceLevels(status,combined).volume == -1200);
        routeFlow();
        volumeFlow();
        inputPriorityFlow();
        usbModeFlow();
        DeviceProfile plus=DeviceProfile.plus(new byte[]{0x22,0x11,0x33,0x66,0x55,0x44});
        check(plus.bluetoothMacLabel().equals("Bluetooth MAC · 11:22:33:44:55:66"));
        check(plus.address.equals("11:22:33:44:55:66"));
        check(!plus.matches(DeviceProfile.original(1,plus.address)));
        check(!plus.matches(DeviceProfile.plus(new byte[]{0x22,0x11,0x33,0x67,0x55,0x44})));
        check(plus.matches(new DeviceProfile(DeviceProfile.Model.PLUS,plus.address)));
        rejects(()->DeviceProfile.original(2,plus.address));
        rejects(()->DeviceProfile.plus(new byte[6]));
        rejects(()->DeviceProfile.plus(new byte[]{-1,-1,-1,-1,-1,-1}));
        rejects(()->DeviceProfile.plus(new byte[4]));
        presetHistoryDiff();
        firmwareCompatibility();
        comparison();
        for (int group = 0; group < 2; group++) hardwareFlow(group);
        System.out.println(checks + " editor, hardware transaction and telemetry checks passed.");
    }
    private static void routeFlow() throws Exception {
        Wire w = new Wire();
        Peq first = w.block[1];
        first.pre(0,-25);
        first.pre(1,-40);
        for (int i=0;i<10;i++) {
            first.band(0,i,i%6,110+i,-30+i,990+i);
            first.band(1,i,(i+3)%6,1540+i,20-i,802+i);
        }
        w.stored[1] = first.copy();
        Peq expected = first.swapChannels();
        check(!first.same(expected) && first.same(expected.swapChannels()));
        check(expected.pre(1)==-25 && expected.pre(0)==-40 && expected.q(0,8)==810 && expected.hz(1,6)==116);
        PeqSession session = new PeqSession(1,w.selected[1],first);
        EqSnapshot baseline;
        try (GaiaProbe.Connection l=w.connect()) {
            baseline=l.read();
            w.failBand=true;
            rejects(()->ChannelRouting.apply(l,baseline,baseline,session,true,s->{}));
            check(!w.swapped);
        }
        w.failBand=false;
        try (GaiaProbe.Connection l=w.connect()) {
            ChannelRouting.apply(l,baseline,l.read(),session,true,s->{});
            ChannelRouting.apply(l,baseline,l.read(),session,true,s->{});
            check(w.routeWrites==1);
        }
        EqSnapshot reversed;
        try (GaiaProbe.Connection l=w.connect()) {
            reversed=l.read();
            ChannelRouting.verify(l,baseline,reversed,session,true);
            check(w.block[1].same(expected) && w.block[0].same(w.stored[0]));
            ChannelRouting.apply(l,reversed,reversed,session,false,s->{});
        }
        try (GaiaProbe.Connection l=w.connect()) {
            ChannelRouting.verify(l,reversed,l.read(),session,false);
            check(w.selected[1]==24 && w.block[1].same(w.stored[1]) && !w.swapped && w.saves==0);
        }
    }
    private static void volumeFlow() throws Exception {
        Wire w=new Wire();
        try(GaiaProbe.Connection link=w.connect()) {
            DeviceLevels now=link.levels();
            check(link.setCheckedVolume(now,now,now.volume-30).volume==now.volume-30 && w.volumeWrites==1);
            rejects(()->link.setCheckedVolume(now,link.levels(),now.volume-60));
            check(w.volumeWrites==1);
            DeviceLevels lower=link.levels();
            check(link.setCheckedVolume(lower,lower,now.volume).volume==now.volume);
            w.ignoreVolume=true;
            rejects(()->link.setCheckedVolume(now,now,now.volume-30));
            check(w.volumeWrites==3);
        }
    }
    private static void usbModeFlow() throws Exception {
        Wire wire=new Wire();
        try(GaiaProbe.Connection link=wire.connect()){
            for(int target:new int[]{1,2,0}){
                link.setUsbPlatform(target);
                check(link.usbAudio().platform==target);
            }
            rejects(()->link.setUsbPlatform(3));
            rejects(()->link.setUsbPlatform(-1));
            check(wire.usbWrites==3);
        }
    }
    private static void inputPriorityFlow() throws Exception {
        Wire wire = new Wire();
        try (GaiaProbe.Connection link = wire.connect()) {
            DeviceLevels before = link.levels();
            check(before.priority == 0 && !before.playing);
            DeviceLevels changed = link.setInputPriority(before,0,2);
            check(changed.priority == 2 && changed.source == 0 && !changed.playing && wire.priorityWrites == 1);
            check(!before.sameReading(changed));
            rejects(() -> link.setInputPriority(changed,0,1));
            rejects(() -> link.setInputPriority(changed,2,3));
            check(link.setInputPriority(changed,2,2).priority == 2 && wire.priorityWrites == 1);
            wire.ignorePriority = true;
            rejects(() -> link.setInputPriority(link.levels(),2,1));
            check(wire.priorityWrites == 2 && link.levels().priority == 2);
            wire.callActive = true;
            rejects(() -> link.setInputPriority(link.levels(),2,1));
            check(wire.priorityWrites == 2);
        }
    }
    private static void presetHistoryDiff() {
        Peq live = Peq.flat(1);
        live.bytes[48+3] = (byte)0xa0;
        Peq archived = Peq.flat(1);
        archived.pre(0,-20);
        archived.pre(1,-40);
        archived.band(1,2,5,1800,-10,1024);
        archived.metadata(0,240,1080);
        Peq restored = PresetDiff.restore(live,archived,true);
        check(restored.pre(0)==-40 && restored.pre(1)==-20 && restored.gain(0,2)==-10 && restored.type(0,2)==5);
        check(restored.impedance()==240 && restored.sensitivity()==1080);
        check((restored.bytes[51]&0xf0)==0xa0 && live.pre(0)==0 && archived.pre(1)==-40);
        String diff=PresetDiff.describe(live,restored);
        check(diff.contains("Left Filter 3") && diff.contains("1800 Hz"));
        check(diff.contains("Left Preamp") && diff.contains("Right Preamp") && diff.contains("Sensitivity"));
        check(PresetDiff.describe(restored,restored).startsWith("No filter"));
    }

    private static void firmwareCompatibility() throws Exception {
        Wire wire = new Wire();
        wire.firmwareByte = (byte)0xa0;
        wire.namedSlots = true;
        wire.readsOnly = true;
        String report = FirmwareCompatibility.run(wire::connect,()->false);
        check(report.contains("Firmware read from device · 3.2.10") && report.contains("8 read passed"));
        check(report.contains("1 saved slot name read.") && !report.contains("Private slot name"));
        check(report.contains("Still to verify on hardware") && report.contains("does not unlock controls"));
        try (GaiaProbe.Connection link = wire.connect()) {
            rejects(link::levels);
            rejects(link::usbAudio);
        }
        wire.badUsb = true;
        wire.namedSlots = false;
        report = FirmwareCompatibility.run(wire::connect,()->false);
        check(report.contains("NEEDS REVIEW · USB sample-rate profiles") && report.contains("READ PASS · SPK active EQ"));
        check(report.contains("NOT TESTED · USR saved preset names") && report.contains("2 not tested"));
        int[] stage = {0};
        report=FirmwareCompatibility.run(()->{
            if (stage[0]++ == 1) throw new IOException("Socket closed for 00:12:34:56:AB:CD");
            return wire.connect();
        },()->false);
        check(report.contains("COULD NOT READ · Audio") && report.contains("[device]") && !report.contains("34:56:AB"));
    }
    private static void comparison() throws Exception {
        Peq a = Peq.flat(0), b = a.copy(), original = a.copy(), fixed = Peq.flat(1);
        a.metadata(5,0,0);
        original.metadata(2,0,0);
        b.pre(0,-60);
        PeqComparison raw = new PeqComparison(a,b,original,null,false);
        check(raw.side(0).pre(0)==0 && raw.side(1).pre(0)==-60 && raw.side(0).xfeed()==2);
        PeqComparison match = new PeqComparison(a,b,original,null,true);
        check(match.attenuation[0]==60 && match.attenuation[1]==0);
        check(match.side(0).pre(0)==-60 && match.side(1).pre(0)==-60 && a.pre(0)==0 && b.pre(0)==-60);
        fixed.pre(1,100);
        PeqComparison cascade = new PeqComparison(a,b,original,fixed,true);
        check(cascade.side(0).pre(0)==-110 && cascade.side(1).pre(0)==-110);
        Peq leftRight = Peq.flat(1);
        leftRight.pre(1,-30);
        PeqComparison stereo = new PeqComparison(leftRight,leftRight,leftRight,null,true);
        check(stereo.side(0).pre(0)==-10 && stereo.side(1).pre(1)==-40);
        rejects(() -> new PeqComparison(a,leftRight,original,null,true));
        fixed.pre(1,240);
        rejects(() -> new PeqComparison(a,b,original,fixed,true));
        Peq peak = Peq.flat(0);
        peak.band(0,0,5,3333,60,15*1024);
        PeqComparison spiky = new PeqComparison(peak,b,original,null,true);
        check(new PeqResponse(spiky.side(0),0).db(3333) < -.7);
        check(spiky.attenuation[1] >= 0 && spiky.attenuation[0] > spiky.attenuation[1]);
        Wire w = new Wire();
        PeqSession session = new PeqSession(0,24,w.block[0]);
        try (GaiaProbe.Connection link = w.connect()) {
            for (int i : new int[]{0,1,0}) {
                Peq target = spiky.side(i);
                session.preview(link,link.read(),target, journal -> {});
                check(w.block[0].same(target) && w.saves==0);
            }
            session.restore(link,link.read());
        }
        try (GaiaProbe.Connection fresh = w.connect()) {
            EqSnapshot after = fresh.read();
            check(PeqSession.selector(after,0)==24 && session.original.same(PeqSession.block(after,0)) && w.saves==0);
        }
    }
    private static void hardwareFlow(int group) throws Exception {
        Wire w = new Wire();
        PeqSession s = new PeqSession(group, 24, w.block[group]);
        Peq goal = s.original.copy();
        int ch = group;
        goal.pre(ch, -30);
        for (int b = 2; b < 10; b++) goal.band(ch, b, 3, 1000 + 10 * b, -20, 750);
        goal.band(ch, 1, 5, 1600, -10, 1250);
        if (group == 1)
            goal.metadata(0, 320, 1020);
        else
            goal.metadata(7, 0, 0);
        try (GaiaProbe.Connection link = w.connect()) {
            EqSnapshot before = link.read();
            s.preview(link, before, goal, record -> {});
            check(w.block[group].same(goal) && w.selected[group] == 255 && w.saves == 0);
            check(w.stored[group].same(s.original) && w.block[1 - group].same(Peq.flat(1 - group)));
            s.restore(link, link.read());
        }
        try (GaiaProbe.Connection link = w.connect()) {
            EqSnapshot after = link.read();
            check(PeqSession.selector(after, group) == 24 && PeqSession.block(after, group).same(s.original));
            // A restore can be retried if the link dropped after reloading the slot.
            s.restore(link, after);
            s = new PeqSession(group, 24, w.block[group]);
            Peq invalid = s.original.copy();
            invalid.pre(ch, 50);
            w.block[group] = invalid;
            PeqSession current = s;
            rejects(() -> current.preview(link, link.read(), goal, rec -> {}));
            w.block[group] = s.original.copy();
            w.failBand = true;
            rejects(() -> current.preview(link, link.read(), goal, rec -> {}));
            check(s.pending != null && PeqSession.legalIntermediate(s.confirmed, goal, w.block[group]));
            w.failBand = false;
            s.preview(link, link.read(), goal, rec -> {});
            link.preset(group, 24, true);
            check(w.saves == 1);
        }
        try (GaiaProbe.Connection link = w.connect()) {
            link.preset(group, 24, false);
            EqSnapshot after = link.read();
            check(PeqSession.block(after, group).same(goal));
            check(PeqSession.selector(after, group) == 24);
        }
    }
    /** A simulated device that owns working and saved blocks independently across radio connections. */
    static final class Wire {
        Peq[] block = {Peq.flat(0), Peq.flat(1)}, stored = {Peq.flat(0), Peq.flat(1)};
        int[] selected = {24, 24};
        int enabled=3, powerWrites, rejectPower=-1;
        final String[][] slotNames=new String[2][20];
        final Peq[][] slots=new Peq[2][20];
        int saves, volume=-1560, volumeWrites, priority, priorityWrites, usbMode, usbWrites;
        boolean ignorePriority, callActive;
        boolean ignoreVolume, swapped;
        int routeWrites, profile, gain, profileWrites, gainWrites;
        boolean failBand;
        byte firmwareByte = (byte)0x90;
        boolean readsOnly, namedSlots, badUsb;
        GaiaProbe.Connection connect() {
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            InputStream in = new InputStream() {
                public int read() {
                    return queue.isEmpty() ? -1 : queue.remove();
                }
            };
            OutputStream out = new OutputStream() {
                public void write(int b) {
                    throw new AssertionError();
                }
                public void write(byte[] data, int off, int len) throws IOException {
                    ByteBuffer wire = ByteBuffer.wrap(data);
                    int cmd = wire.getShort(6) & 65535;
                    wire.position(8);
                    int g = wire.get() & 255;
                    if (readsOnly && cmd != 0x100 && cmd != 0x110 && cmd != 0x120 && cmd != 0x123 && cmd != 0x70b)
                        throw new AssertionError("The compatibility check sent a non-read command");
                    switch (cmd) {
                        case 0x100:
                            reply(queue, 0x8101, new byte[] {0, 1, 3, (byte) 0x83, firmwareByte, 0, 0});
                            break;
                        case 0x110:
                            byte[] status=new byte[17];status[0]=11;
                            if (callActive) status[1]=4;
                            reply(queue,0x8111,status);break;
                        case 0x200:
                            ++volumeWrites;if(!ignoreVolume)volume=wire.getShort();break;
                        case 0x203:
                            if (g > 2 || wire.hasRemaining()) throw new AssertionError("Invalid input priority");
                            ++priorityWrites;if(!ignorePriority)priority=g;break;
                        case 0x301:
                            if(g>2 || wire.hasRemaining())throw new AssertionError("Invalid USB platform command");
                            usbMode=g;usbWrites++;break;
                        case 0x505:
                            if (g > 1 || wire.hasRemaining()) throw new AssertionError("Invalid output gain");
                            gain = g;
                            gainWrites++;
                            break;
                        case 0x508:
                            if (g > 1 || wire.hasRemaining()) throw new AssertionError("Invalid DAC profile");
                            profile = g;
                            profileWrites++;
                            break;
                        case 0x50a:
                            if (g > 1 || wire.hasRemaining()) throw new AssertionError("Unexpected DAC command");
                            swapped = g == 1;
                            routeWrites++;
                            break;
                        case 0x120:
                            if (g == 8) {
                                reply(queue, 0x8121, new byte[]{8, (byte)(gain << 5), 0, 0, (byte)(profile | profile << 2 | (swapped ? 0x10 : 0))});
                                break;
                            }
                            if(g==3){byte[] both=new byte[29];both[0]=3;both[2]=(byte)(priority<<2);ByteBuffer.wrap(both).order(ByteOrder.LITTLE_ENDIAN).putShort(13,(short)volume);reply(queue,0x8121,both);break;}
                            if (g == 1 || g == 64 || g == 16) {
                                byte[] settings = new byte[g==1 ? 13 : g==64 ? (badUsb ? 8 : 33) : 5];
                                settings[0] = (byte)g;
                                if(g==64 && !badUsb){
                                    settings[20]=(byte)(usbMode<<5);
                                    settings[21]=(byte)(2 | 2<<3);
                                }
                                reply(queue,0x8121,settings);
                                break;
                            }
                            byte[] cfg = new byte[19];
                            cfg[0] = (byte) 128;
                            cfg[10] = 1;
                            cfg[4] = (byte)(enabled&1);
                            cfg[13] = (byte)((enabled>>1)&1);
                            cfg[3] = (byte) selected[0];
                            cfg[12] = (byte) selected[1];
                            if (namedSlots) { cfg[6]=1; cfg[15]=2; }
                            for(int bank=0;bank<2;bank++)for(int slot=0;slot<20;slot++)
                                if(slotNames[bank][slot]!=null)cfg[6+9*bank+slot/8]|=1<<(slot%8);
                            reply(queue, 0x8121, cfg);
                            break;
                        case 0x700:
                            int state=wire.get();
                            check((g==0 || g==1) && (state==0 || state==1) && !wire.hasRemaining());
                            powerWrites++;
                            if(rejectPower!=g)enabled=(enabled&~(1<<g))|(state<<g);
                            break;
                        case 0x70a:
                            int renamed=wire.get()&255;
                            byte[] title=new byte[wire.get()&255];wire.get(title);
                            slotNames[g][renamed]=title.length==0?null:new String(title,java.nio.charset.StandardCharsets.UTF_8);
                            break;
                        case 0x70b:
                            int requested=wire.get()&255;
                            String value=slotNames[g][requested]==null?"Private slot name":slotNames[g][requested];
                            byte[] label = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            ByteBuffer name = ByteBuffer.allocate(3+label.length)
                                    .put((byte)g).put((byte)requested).put((byte)label.length).put(label);
                            reply(queue,0x870c,name.array());
                            break;
                        case 0x123:
                            int group = g == 1 ? 0 : 1;
                            byte[] bytes = block[group].bytes;
                            for (int i = 0; i * 50 < bytes.length; i++) {
                                int offset = i * 50, n = Math.min(50, bytes.length - offset);
                                ByteBuffer chunk = ByteBuffer.allocate(n + 6)
                                                           .put((byte) group)
                                                           .put((byte) (((bytes.length - 1) / 50 << 4) | i))
                                                           .putShort((short) n)
                                                           .putShort((short) offset);
                                chunk.put(bytes, offset, n);
                                reply(queue, 0x8128, chunk.array());
                            }
                            break;
                        case 0x70f:
                            if (failBand)
                                throw new IOException("simulated link loss");
                            int ch = wire.get() == 1 ? 0 : 1, b = wire.get();
                            block[g].band(ch, b, wire.get(), wire.getShort(), wire.getShort(), wire.getShort());
                            selected[g] = 255;
                            break;
                        case 0x703:
                            int channel = wire.get() == 1 ? 0 : 1;
                            wire.get();
                            block[g].pre(channel, wire.getShort());
                            selected[g] = 255;
                            break;
                        case 0x70d:
                            wire.get();
                            wire.get();
                            block[g].metadata(wire.getShort(), 0, 0);
                            selected[g] = 255;
                            break;
                        case 0x216:
                            check(g == 2);
                            block[1].metadata(0, wire.getShort(), wire.getShort());
                            selected[1] = 255;
                            break;
                        case 0x708:
                            selected[g] = wire.get();
                            stored[g] = block[g].copy();
                            slots[g][selected[g]-22]=stored[g].copy();
                            saves++;
                            break;
                        case 0x709:
                            selected[g] = wire.get();
                            if(selected[g]==0)block[g]=Peq.flat(g);
                            else if(selected[g]>=22 && slots[g][selected[g]-22]!=null)
                                block[g]=slots[g][selected[g]-22].copy();
                            else block[g]=stored[g].copy();
                            break;
                        default:
                            throw new AssertionError("Unexpected command " + cmd);
                    }
                }
            };
            return new GaiaProbe.Connection(in, out, t -> {});
        }
        void reply(ArrayDeque<Integer> q, int id, byte[] payload) {
            byte[] bytes = ByteBuffer.allocate(9 + payload.length)
                                   .put((byte) 255)
                                   .put((byte) 1)
                                   .put((byte) 0)
                                   .put((byte) (payload.length + 1))
                                   .putShort((short) 0xf001)
                                   .putShort((short) id)
                                   .put((byte) 0)
                                   .put(payload)
                                   .array();
            for (byte b : bytes) q.add(b & 255);
        }
    }
}
