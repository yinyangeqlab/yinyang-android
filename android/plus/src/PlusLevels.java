package com.yinyangeq.qudelixprobe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class PlusLevels {
    static final int USB_INPUT=3;

    private static final class AudioPeer {
        final int key, transport, source;
        AudioPeer(byte[] status,int offset) {
            int flags=little(status).getShort(offset)&65535;
            transport=(flags >>> 2)&3;
            source=(flags >>> 6)&15;
            key=((status[offset+4]&255)<<24)
                    | ((status[offset+7]&255)<<16)
                    | ((status[offset+6]&255)<<8)
                    | (status[offset+5]&255);
        }
        boolean matches(AudioPeer other) {
            return other!=null && key==other.key && transport==other.transport;
        }
        long identity() {
            return key!=0 && transport<=1 ? (Integer.toUnsignedLong(key)<<2)|transport : 0;
        }
    }

    /** Runtime RMS is pushed by firmware. A solicited 0x52 read has a zero mask even during playback. */
    static final class Stream implements PlusUsbProtocol.Transport {
        private final PlusUsbProtocol.Transport transport;
        private PlusUsbProtocol.Fragments fragments = new PlusUsbProtocol.Fragments();
        private byte[] latest;
        private long received;
        Stream(PlusUsbProtocol.Transport transport) { this.transport = transport; }
        public void send(byte[] report) throws IOException { transport.send(report); }
        public byte[] receive(int timeout) throws IOException {
            byte[] packet = transport.receive(timeout);
            if (packet.length >= 5 && packet[0] == 9 && (packet[2] & 255) == 0x90 && packet[3] == 5) {
                try {
                    byte[] payload = fragments.append(packet);
                    if (payload != null) {
                        fragments = new PlusUsbProtocol.Fragments();
                        byte[] sample = PlusUsbProtocol.elements(payload).get(0x52);
                        if (sample != null && sample.length == 16 && (sample[0] & 2) != 0) {
                            latest = sample; received = System.nanoTime();
                        }
                    }
                } catch (IOException invalidNotification) { fragments = new PlusUsbProtocol.Fragments(); }
            }
            return packet;
        }
        void resetMeter() { latest = null; }
        byte[] sample() { return System.nanoTime() - received < 1500000000L ? latest : null; }
    }

    final int gain, ceiling, millivolts, source;
    final int profile, gainMode, effectiveProfile, bluetoothAudioMs;
    final int codec, filter;
    final double headphoneOhms, sensitivity;
    final boolean lossless;
    final int usbMode;
    final boolean leEnabled,automaticPairing,pairing,hasPairingStatus;
    final java.util.Set<Long> connectedPeers=new java.util.HashSet<>();
    private final List<AudioPeer> audioPeers=new ArrayList<>();
    private final int activeSource;
    private final boolean usbEnumerated;
    private final byte[] dacConfig, inputConfig;
    double readMillis;
    final boolean playing, voice, muted, meterReady, channelsSwapped;
    final double left, right;
    private final byte[] audioBlock, battery, config;

    PlusLevels(Map<Integer,byte[]> blocks) throws IOException {
        byte[] usb=blocks.get(0x20);
        usbMode=usb!=null && usb.length==4?(usb[0]&255)>>>4:-1;
        byte[] audio = PlusUsbProtocol.required(blocks, 0x51, 16);
        audioBlock = audio; battery = blocks.get(0x61); config = blocks.get(0x80);
        byte[] dac = PlusUsbProtocol.required(blocks, 0x41, 4);
        inputConfig = PlusUsbProtocol.required(blocks,0x50,16);
        byte[] usbStatus=blocks.get(0x21);
        usbEnumerated=usbStatus!=null && usbStatus.length==4 && (usbStatus[0]&4)!=0;
        byte[] bluetooth=PlusUsbProtocol.required(blocks,0x30,8);
        leEnabled=(bluetooth[3]&128)!=0;automaticPairing=(bluetooth[2]&128)!=0;
        byte[] peers=blocks.get(0x31);hasPairingStatus=peers!=null && peers.length==48;
        pairing=hasPairingStatus && (peers[0]&2)!=0;
        if(hasPairingStatus)for(int offset=4;offset<36;offset+=8){
            long id=0;
            for(int pos=offset+2;pos<offset+8;pos++)id=(id<<8)|(peers[pos]&255);
            if(id!=0)connectedPeers.add(id);
            if((peers[offset]&1)!=0)audioPeers.add(new AudioPeer(peers,offset));
        }
        int load = little(inputConfig).getInt(12);
        int mode = config!=null && config.length==4 ? (config[0]&255)>>>4 : -1;
        byte[] attr = mode==0?blocks.get(0xa2):mode==1?blocks.get(0xb2):null;
        int presetLoad = attr!=null && attr.length==12 ? little(attr).getInt(0) : 0;
        int presetOhms=(presetLoad>>>1)&16383, presetSensitivity=(presetLoad>>>15)&2047;
        headphoneOhms = (presetOhms>0 ? presetOhms : load & 16383) / 10.0;
        sensitivity = (presetSensitivity>0 ? presetSensitivity : (load>>>14)&2047) / 10.0;
        filter = (little(dac).getShort(0) >>> 7) & 15;
        lossless = (audio[2] & 128) != 0;
        codec = ((audio[2]&255)>>1)&15;
        dacConfig = PlusUsbProtocol.required(blocks, 0x40, 8);
        channelsSwapped = PlusChannelSwap.swapped(dacConfig);
        profile = (dacConfig[1] >> 4) & 3;
        gainMode = (dacConfig[2] >> 2) & 1;
        effectiveProfile = (dac[0] >> 5) & 3;
        byte[] latency = blocks.get(0x34);
        activeSource=(audio[0] >>> 3)&15;
        ByteBuffer volume = little(PlusUsbProtocol.required(blocks, 0x70, 28));
        ByteBuffer runtime = little(PlusUsbProtocol.required(blocks, 0x52, 16));
        playing = (audio[0] & 1) != 0;
        voice = playing && ((audio[0] >> 1) & 3) == 1;
        bluetoothAudioMs=playing && (activeSource==1 || activeSource==2) && latency!=null && latency.length==12
                ? little(latency).getShort(4)&65535 : -1;
        source = volume.getShort(0);
        gain = volume.getShort(4);
        ceiling = Math.min(volume.getShort(6), (dac[1] & 8) != 0 ? 360 : 0);
        muted = (volume.get(10) & 1) != 0;
        millivolts = volume.getShort(24) & 65535;
        meterReady = playing && !voice && (runtime.get(0) & 2) != 0;
        left = rms(runtime.getInt(8));
        right = rms(runtime.getInt(12));
    }

    private static ByteBuffer little(byte[] value) { return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN); }
    private double rms(int raw) {
        if (!meterReady || raw < 0) return Double.NaN;
        return raw == 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(raw / 2147483648.0) + 12;
    }
    String streamTitle(PlusUsbProtocol.Status status) {
        if (!playing) return "No audio playing";
        String[] codecs = status.input >= 5 ? new String[]{"", "LC3", "aptX Lite", "aptX Adaptive", "aptX Adaptive R4"}
                : new String[]{"","SBC","AAC","aptX","aptX HD","aptX Adaptive","LDAC","LHDC"};
        String format = voice ? "Voice" : status.input == 3 ? "PCM" : codec < codecs.length ? codecs[codec] : "";
        return status.inputLabel() + (format.isEmpty() ? "" : " · "+format) + (lossless ? " · Lossless" : "")
                + (status.sampleRate > 0 ? Peq.fmt(" · %s kHz",Double.toString(status.sampleRate/1000.0).replaceFirst("\\.0$","")) : "")
                + (status.bits > 0 ? " · "+status.bits+"-bit" : "");
    }
    String filterName() {
        String[] names={"Linear phase fast roll-off","Linear phase slow roll-off","Linear phase super slow roll-off",
                "Minimum phase fast roll-off","Minimum phase slow roll-off","Minimum phase super slow roll-off","Hybrid fast roll-off"};
        return filter < names.length ? names[filter] : "Filter unreported";
    }
    int floor() { return ceiling - 3600; }
    boolean adjustable() { return !voice && !muted && ceiling >= -3600 && gain >= floor() && gain <= ceiling; }
    static PlusLevels read(PlusUsbProtocol.Transport link) throws IOException {
        long start = System.nanoTime();
        Map<Integer,byte[]> blocks = PlusUsbProtocol.read(link, 0x41, 0x51, 0x52, 0x70, 0x61, 0x80, 0x40, 0x34, 0x50, 0x30, 0x31, 0xa2, 0xb2, 0x20, 0x21);
        if (link instanceof Stream) {
            byte[] sample = ((Stream)link).sample();
            if (sample != null) blocks.put(0x52, sample);
        }
        PlusLevels result = new PlusLevels(blocks);
        result.readMillis = (System.nanoTime()-start)/1e6;
        return result;
    }
    PlusUsbProtocol.Status status(PlusUsbProtocol.Status previous) {
        int mode = config != null && config.length == 4 ? (config[0] & 255) >> 4 : -1;
        return new PlusUsbProtocol.Status(previous.firmware, mode >= 0 && mode < 2 ? PlusPreset.Mode.values()[mode] : previous.mode,
                battery, audioBlock, previous.packetSize);
    }
    static void meter(PlusUsbProtocol.Transport link, int packet, boolean enabled) throws IOException {
        // Runtime telemetry subscription. The device accepts a bit mask followed by milliseconds, little endian.
        PlusUsbProtocol.set(link, packet, 0x53, enabled ? new byte[]{2, (byte)250, 0, 0} : new byte[4]);
    }
    void setUsbMode(PlusUsbProtocol.Transport link,int packet,int target) throws IOException {
        if(!UsbMode.supported(target) || !UsbMode.supported(usbMode))throw new IOException("Unknown Plus USB mode.");
        byte[] config=PlusUsbProtocol.required(PlusUsbProtocol.read(link,0x20),0x20,4);
        if(((config[0]&255)>>>4)!=usbMode)throw new IOException("USB mode changed. Select again using the current setting.");
        if(target==usbMode)return;
        config[0]=(byte)((config[0]&15)|(target<<4));
        // USB may re-enumerate during this write. Never replay it on another session.
        PlusUsbProtocol.set(link,packet,0x20,config);
        byte[] actual=PlusUsbProtocol.required(PlusUsbProtocol.read(link,0x20),0x20,4);
        if(((actual[0]&255)>>>4)!=target)throw new IOException("The Plus has not confirmed the selected USB mode.");
    }
    private AudioPeer peer(int input) {
        return input>=1 && input<=2 && input<=audioPeers.size() ? audioPeers.get(input-1) : null;
    }
    boolean canSelectInput(int input) {
        if(voice)return false;
        if(input==USB_INPUT)return usbEnumerated || playing && activeSource==USB_INPUT;
        AudioPeer peer=peer(input);
        return peer!=null && peer.key!=0 && peer.transport<=1;
    }
    boolean preferredInput(int input) {
        int key=little(inputConfig).getInt(4);
        if(input==USB_INPUT)return key==0 && (inputConfig[1]&15)==USB_INPUT;
        AudioPeer peer=peer(input);
        return peer!=null && key!=0 && key==peer.key && ((inputConfig[1] >>> 4)&3)==peer.transport;
    }
    boolean playingBluetooth() {
        return playing && !voice && (activeSource==1 || activeSource==2 || activeSource>=5 && activeSource<=8);
    }
    long bluetoothInputPeer(int input) {
        AudioPeer peer=peer(input);
        return peer==null ? 0 : peer.identity();
    }
    private AudioPeer soleBluetoothPeer(int transport) {
        if(transport<0 || transport>1)return null;
        AudioPeer candidate=null;
        for(AudioPeer peer:audioPeers) {
            if(peer.transport!=transport)continue;
            if(candidate!=null)return null;
            candidate=peer;
        }
        return candidate;
    }
    long phonePeer(int transport) {
        AudioPeer candidate=soleBluetoothPeer(transport);
        return candidate==null ? 0 : candidate.identity();
    }
    long activePhonePeer(int transport) {
        if(!playingBluetooth() || activeSource==8)return 0;
        AudioPeer candidate=soleBluetoothPeer(transport);
        return candidate!=null && candidate.source==activeSource ? candidate.identity() : 0;
    }
    boolean playingSelectedInput(PlusLevels selected,int input) {
        if(!playing || voice)return false;
        if(input==USB_INPUT)return activeSource==USB_INPUT;
        AudioPeer target=selected.peer(input);
        if(target==null || !playingBluetooth())return false;
        for(AudioPeer peer:audioPeers)if(peer.source==activeSource && target.matches(peer))return true;
        return false;
    }
    void selectInput(PlusUsbProtocol.Transport link,int packet,PlusLevels shown,int input) throws IOException {
        if(!canSelectInput(input))throw new IOException("Connect the selected audio source first.");
        AudioPeer target=peer(input);
        if(target!=null && !target.matches(shown.peer(input)))
            throw new IOException("The Bluetooth sources changed. Select again.");
        byte[] current=PlusUsbProtocol.required(PlusUsbProtocol.read(link,0x50),0x50,16);
        if(!Arrays.equals(inputConfig,current))throw new IOException("Input settings changed. Select again.");
        if(target==null){
            // USB selects a source type and clears a pinned peer.
            current[1]=(byte)((current[1]&0xc0)|0x20|USB_INPUT);
            little(current).putInt(4,0);
        }else{
            int fallback=current[1]&15;
            if(target.transport==0 && (target.source==1 || target.source==2))fallback=target.source;
            if(target.transport==1 && target.source>=5 && target.source<=7)fallback=5;
            // An earlier USB preference must not win if the firmware falls back to source priority.
            current[1]=(byte)((current[1]&0xc0)|(target.transport<<4)|fallback);
            little(current).putInt(4,target.key);
        }
        PlusUsbProtocol.set(link,packet,0x50,current);
        if(!Arrays.equals(current,PlusUsbProtocol.required(PlusUsbProtocol.read(link,0x50),0x50,16)))
            throw new IOException("The device did not confirm the input priority.");
    }
    void setDac(PlusUsbProtocol.Transport link,int packet,boolean amp,int choice) throws IOException {
        if (voice || choice < 0 || choice > 1) throw new IOException("The DAC setting is unavailable.");
        byte[] current = PlusUsbProtocol.required(PlusUsbProtocol.read(link,0x40),0x40,8);
        if (!java.util.Arrays.equals(current,dacConfig)) throw new IOException("DAC settings changed. Use the updated selection.");
        int index = amp ? 2 : 1, mask = amp ? 4 : 48;
        current[index] = (byte)((current[index] & ~mask) | (choice << (amp ? 2 : 4)));
        PlusUsbProtocol.set(link, packet,0x40,current);
        if (!java.util.Arrays.equals(current,PlusUsbProtocol.required(PlusUsbProtocol.read(link,0x40),0x40,8)))
            throw new IOException("The device did not confirm the DAC setting.");
    }
    void setGain(PlusUsbProtocol.Transport link, int packet, PlusLevels displayed, int target) throws IOException {
        if (!adjustable() || gain != displayed.gain || ceiling != displayed.ceiling || target < floor()
                || target > ceiling || (target - floor()) % 30 != 0)
            throw new IOException("Hardware volume or its limit changed. Adjust again using the current level.");
        if (target == gain) return;
        PlusUsbProtocol.set(link, packet, 0x72, new byte[]{1, 0, (byte)target, (byte)(target >> 8)});
        if (read(link).gain != target) throw new IOException("The Plus did not confirm the requested volume.");
    }
}
