package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.view.Gravity;
import android.widget.*;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

final class PlusAudioPanel extends LinearLayout {
    interface Adjustment { void set(PlusLevels shown, int raw); }
    interface InputChange { void set(PlusLevels shown,int source); }
    private final SignalMonitor monitor;
    private final CompanionActions.UsbModes usbModes;
    private static final int[] INPUT_IDS={1,2,PlusLevels.USB_INPUT};
    private final Button[] inputs=new Button[INPUT_IDS.length];
    private final TextView inputState, overview;
    private final AndroidAudioOptions androidAudio;
    private boolean inputPending,inputWritable;
    private final LinearLayout meters;
    private final Switch volumeLock;
    interface DacChange { void set(PlusLevels shown, boolean amp, int choice); }
    private final Button[] dacButtons = new Button[4];
    private final TextView effective, latency;
    private final Button swapChannels;
    private final boolean usb;
    private final AudioManager audio;
    private final TextView hardwareValue, phoneValue, voltage, left, right;
    private final SeekBar hw, phone;
    private final ProgressBar leftBar, rightBar;
    private boolean dragging, draggingPhone,lastWritable;
    private PlusLevels shown, dragStart;
    private final Adjustment adjust;
    private long lastLatencyUpdate;
    PlusAudioPanel(Context c, Adjustment adjust, DacChange dacChange, InputChange inputChange, InputChange usbChange, Runnable swap, boolean usb, String address) {
        super(c);
        this.adjust = adjust; this.usb = usb;
        audio = c.getSystemService(AudioManager.class);
        setOrientation(VERTICAL);
        LinearLayout hardware = card(c, this);
        overview=label(hardware,"",17);
        hardwareValue = label(hardware, "Hardware volume", 19);
        hw = slider(hardware, "5K Plus hardware volume", BLUE);
        hw.setMax(120);
        LabStyle.divider(c,hardware);
        LinearLayout media = hardware;
        phoneValue = label(media, "Phone media volume", 19);
        phone = slider(media, "Phone media volume", CORAL);
        monitor=new SignalMonitor(c);media.addView(monitor);
        volumeLock=new Switch(c);
        toggle(volumeLock);
        volumeLock.setText("Lock volume sliders");
        android.content.SharedPreferences prefs=c.getSharedPreferences("audio-controls",Context.MODE_PRIVATE);
        volumeLock.setChecked(prefs.getBoolean("volumeLocked",false));
        media.addView(volumeLock);
        volumeLock.setOnCheckedChangeListener((v,on)->{
            prefs.edit().putBoolean("volumeLocked",on).apply();
            refreshPhone();
            hw.setEnabled(!on && lastWritable);
        });
        meters=new LinearLayout(c);meters.setOrientation(VERTICAL);hardware.addView(meters);
        LabStyle.divider(c,meters);
        voltage = label(meters, "", 14);
        latency = label(meters,"",14);
        label(meters, "Input RMS", 19);
        left = label(meters, "Left", 15);
        leftBar = meter(meters, CompanionEditor.left(c,null));
        right = label(meters, "Right", 15);
        rightBar = meter(meters, CompanionEditor.right(c));


        LinearLayout dac=this;
        CompanionActions.sectionTitle(this,"Output");
        String[] choices = {"Normal","High","Standard","Performance"};
        for (int group=0; group<2; group++) {
            boolean amp = group == 0;
            label(dac, amp ? "Amplifier gain" : "DAC profile",19);
            LinearLayout options = new LinearLayout(c);
            for (int k=0; k<2; k++) {
                int choice = k, index = group * 2 + k;
                Button button = CompanionActions.secondary(c,choices[index],() -> {
                    if (shown != null) dacChange.set(shown,amp,choice);
                });
                dacButtons[index] = button;
                options.addView(button,new LinearLayout.LayoutParams(0,-2,1));
            }
            dac.addView(options);
            label(dac, amp ? "Normal reduces noise and saves battery with sensitive earphones. High provides more voltage for headphones that need extra headroom."
                    : "Standard balances sound quality and battery life. Performance prioritizes DAC performance and uses more power.",14);
            dac.addView(AndroidAudioOptions.guide(c,amp,true));
        }
        effective = label(dac,"",14);
        label(dac,"Channel routing",19);
        swapChannels = CompanionActions.secondary(c,"Swap left and right",swap);
        dac.addView(swapChannels);
        label(dac,"Swaps the DAC outputs and the active left and right Channel Match preamps and filters. Main EQ stays in place.",14);
        LinearLayout source = this;
        CompanionActions.sectionTitle(this,"Inputs");
        label(source,"Preferred input",19);
        inputState=label(source,"",14);
        String[] titles={"Bluetooth 1","Bluetooth 2","USB DAC"};
        LinearLayout row=new LinearLayout(c);
        source.addView(row);
        for(int i=0;i<inputs.length;i++) {
            int selected=INPUT_IDS[i];
            inputs[i]=CompanionActions.secondary(c,titles[i],()->{ if(shown!=null) inputChange.set(shown,selected); });
            row.addView(inputs[i],new LinearLayout.LayoutParams(0,-2,1));
        }
        label(source,Build.VERSION.SDK_INT>=33 ? "Switching away can pause this phone’s media. Choose its Bluetooth input to resume."
                : "Start playback on the input you want to hear.",14);
        usbModes=new CompanionActions.UsbModes(c,(before,target)->{
            if(shown!=null && before!=target)usbChange.set(shown,target);
        });
        source.addView(usbModes);
        androidAudio=new AndroidAudioOptions(c,true,address);
        addView(androidAudio);

        hw.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) { dragging = true; dragStart = shown; }
            public void onProgressChanged(SeekBar b,int p,boolean user) {
                if (!user || shown == null) return;
                if (dragging && dragStart!=null){monitor.previewGain((dragStart.floor()+p*30-shown.gain)/60.0);displayGain(dragStart.floor()+p*30,p);}
                else adjust.set(shown, shown.floor()+p*30);
            }
            public void onStopTrackingTouch(SeekBar b) {
                dragging = false;
                if (dragStart != null) adjust.set(dragStart, dragStart.floor()+b.getProgress()*30);
                dragStart = null;monitor.previewGain(0);
            }
        });
        phone.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) { draggingPhone = true; }
            public void onStopTrackingTouch(SeekBar b) { draggingPhone = false; refreshPhone(); }
            public void onProgressChanged(SeekBar b,int p,boolean user) {
                if (!user || audio == null) return;
                try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0); } catch(SecurityException ignored) {}
                refreshPhone();
            }
        });
        refreshPhone();
        update(null, false,"");
    }
    private TextView label(LinearLayout panel,String title,int size) {
        TextView v = text(getContext(), title, size, size == 19 ? INK : MUTED, size == 19);
        v.setPadding(0,dp(getContext(),5),0,dp(getContext(),4));
        panel.addView(v);
        return v;
    }
    private SeekBar slider(LinearLayout panel,String name,int color) {
        SeekBar b = new FilledVolumeBar(getContext(),color);
        b.setContentDescription(name);
        panel.addView(b,-1,dp(getContext(),48));
        return b;
    }
    private ProgressBar meter(LinearLayout panel,int color) {
        ProgressBar bar = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(900);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
        panel.addView(bar,-1,dp(getContext(),15));
        return bar;
    }
    private final Runnable pollPhone = new Runnable() {
        public void run() { refreshPhone(); postDelayed(this, 450); }
    };
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(pollPhone); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(pollPhone); super.onDetachedFromWindow(); }
    private void refreshPhone() {
        if (audio == null) { phone.setEnabled(false); return; }
        int min = Build.VERSION.SDK_INT >= 28 ? audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) : 0;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), value = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        phone.setMin(min); phone.setMax(max);
        if (!draggingPhone) phone.setProgress(value);
        phone.setEnabled(!volumeLock.isChecked() && !audio.isVolumeFixed() && max > min);
        phoneValue.setText(Peq.fmt("Phone media  ·  %d%%", Math.round(100f * (value-min) / Math.max(1,max-min)))+(draggingPhone?monitor.sliderEstimate():""));
        phone.setContentDescription("Phone media volume, " + value + " of " + max);
    }
    private void displayGain(int raw, int step) {
        hardwareValue.setText(Peq.fmt("Hardware  ·  %+.1f dB  ·  %d%%",raw/60.0, Math.round(step*100f/120))+(dragging?monitor.sliderEstimate():""));
    }
    void routing(boolean allowed, boolean pending, boolean busy, PlusLevels levels) {
        swapChannels.setText(busy ? "Verifying channel swap…" : pending ? "Finish channel swap" :
                levels != null && levels.channelsSwapped ? "Restore normal channels" : "Swap left and right");
        swapChannels.setEnabled(allowed && !busy);
    }
    void inputPending(boolean pending) {
        inputPending=pending;
        updateInputButtons();
    }
    private void updateInputButtons() {
        for(int i=0;i<inputs.length;i++) {
            Button button=inputs[i];
            boolean selected=shown!=null && shown.preferredInput(INPUT_IDS[i]);
            if(button.getTag()==null || button.isSelected()!=selected) {
                LabStyle.tab(button,selected);
                button.setSelected(selected);
                button.setTag(true);
            }
            button.setEnabled(!inputPending && inputWritable && shown!=null && shown.canSelectInput(INPUT_IDS[i]));
        }
    }

    void update(PlusLevels levels, boolean supported,String batteryEta) {
        shown = levels;
        inputWritable=supported;
        PlusUsbProtocol.Status status=levels==null ? null : levels.status(
                new PlusUsbProtocol.Status("",PlusPreset.Mode.LAYERED,null,null,0));
        androidAudio.updateStreamFormat(levels!=null && levels.playingBluetooth(),
                status==null ? 0 : status.sampleRate, status==null ? 0 : status.bits);
        usbModes.update(levels==null?-1:levels.usbMode,supported && levels!=null && !levels.voice);
        ((FilledVolumeBar)hw).hardware(levels==null?0:levels.floor(),levels!=null);
        monitor.update(levels==null?Double.NaN:levels.left,levels==null?Double.NaN:levels.right,
                levels==null?Double.NaN:levels.muted?0:levels.millivolts,levels==null?0:levels.headphoneOhms,levels==null?0:levels.sensitivity);
        if(dragging && dragStart!=null && levels!=null)monitor.previewGain((dragStart.floor()+hw.getProgress()*30-levels.gain)/60.0);
        updateInputButtons();
        String input = status==null ? "Offline" : status.inputLabel();
        inputState.setVisibility(levels==null?GONE:VISIBLE);
        inputState.setText("Active input · "+input);
        for (int i=0; i<4; i++) {
            boolean selected = levels != null && (i < 2 ? levels.gainMode : levels.profile) == i%2;
            Button control = dacButtons[i];
            if (control.isSelected() != selected || control.getTag() == null) {
                LabStyle.tab(control,selected); control.setTag(true); control.setSelected(selected);
            }
            control.setEnabled(supported && levels != null && !levels.voice && !selected);
            if (selected) control.setTextColor(BLUE);
        }
        effective.setVisibility(levels==null?GONE:VISIBLE);
        effective.setText(levels == null ? "" :
                "Effective profile · " + (levels.effectiveProfile == 1 ? "Performance" : levels.effectiveProfile == 0 ? "Standard" : "Unavailable"));
        long time = android.os.SystemClock.elapsedRealtime();
        if (levels == null || lastLatencyUpdate == 0 || time - lastLatencyUpdate >= 2000) {
        lastLatencyUpdate = levels == null ? 0 : time;
        String rtt = levels == null ? "—" : Peq.fmt("%.1f ms control round trip",levels.readMillis);
        latency.setText("USB latency  ·  " + (usb ? rtt : "—") + "\nBluetooth latency  ·  " + (usb ? "—" : rtt)
                + (levels != null && levels.bluetoothAudioMs > 0 ? Peq.fmt("\nBluetooth audio delay  ·  %d ms",levels.bluetoothAudioMs) : ""));
        }
        boolean writable = supported && levels != null && levels.adjustable();
        lastWritable=writable;hw.setEnabled(writable && !volumeLock.isChecked());
        meters.setVisibility(levels==null?GONE:VISIBLE);
        if(levels==null)overview.setText("Connect your 5K Plus in Device to see live levels.");
        else {
            overview.setText(levels.streamTitle(status)+"\n"+(levels.muted?"Muted":Peq.fmt("Total %+.2f dB",(levels.source+levels.gain)/60.0))
                    +(status.percent<0?"":"   ·   Battery "+status.percent+"%   ·   "+batteryEta)
                    +(status.output.isEmpty()?"":"   ·   "+status.output)+"\n"+levels.filterName());
        }
        if (levels == null) {
            hw.setProgress(0); hardwareValue.setText("Hardware volume"); voltage.setText("Connect the 5K Plus to read its levels.");
        } else {
            int step = Math.max(0,Math.min(120,Math.round((levels.gain-levels.floor())/30f)));
            if (!dragging) { hw.setProgress(step); displayGain(levels.gain,step); }
            voltage.setText(Peq.fmt("%sOutput %.3f V RMS  ·  Source %+.1f dB\nHardware limit %+.1f dB",
                    levels.muted ? "Muted  ·  " : "", levels.millivolts/1000.0, levels.source/60.0, levels.ceiling/60.0));
        }
        channel(left,leftBar,"Left",levels == null ? Double.NaN : levels.left);
        channel(right,rightBar,"Right",levels == null ? Double.NaN : levels.right);
    }
    private void channel(TextView label, ProgressBar bar,String side,double value) {
        String reading = Double.isNaN(value) ? "—" : value == Double.NEGATIVE_INFINITY ? "−∞ dB" : Peq.fmt("%.1f dB",value);
        label.setText(side + "  ·  " + reading);
        bar.setProgress(Double.isFinite(value) ? Math.max(0, Math.min(900,(int)Math.round((value+90)*10))) : 0);
        bar.setContentDescription(side + " input RMS " + reading);
    }
}
