package com.yinyangeq.qudelixprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.json.JSONObject;

public final class DeviceWorkbench extends AppearanceActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout body;
    private TextView message, androidLabel;
    private TextView batteryEtaStatus;
    private String batteryEta=BatteryEstimate.UNAVAILABLE;
    private SeekBar androidBar;
    private CompanionEditor.Viewport scroll;
    private CompanionEditor.StickyStages eqStages;
    private EqGraphInputs graphInputs;
    private int drawnEditorGroup=-1;
    private String address, feedback = "";
    private boolean volumeLocked;
    private boolean audio, visible, busy, dragging, fresh, readingFile;
    private boolean workerOutstanding, statusPoll;
    private Runnable deferredAudioChange;
    private int group, channel, epoch;
    private final Object transportLock = new Object();
    private BluetoothSocket socket;
    private Peq draft;
    private PeqSession session;
    private final EqEditHistory[] edits = {new EqEditHistory(), new EqEditHistory()};
    private final boolean[] liveOnStage = new boolean[2];
    private int audioPanel, usbPanel;
    private DacSettings dacSettings;
    private boolean phoneDragging;
    private SignalMonitor compactSignal;
    private byte[] outputSpecs;
    private DeviceLevels levels;
    private boolean usbPage, devicePage;
    private String availableFirmware;
    private long nextConsoleCheck;
    private String consoleStatus="";
    private static boolean isUsbAudioDevice(android.media.AudioDeviceInfo device) {
        int type = device.getType();
        return type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
                || type == android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }
    private String consoleKey(){return address+".autoUsb.console";}
    private boolean automaticUsb(){return address!=null && records.contains(consoleKey());}
    private UsbAudioSettings usbSettings;
    private String detectedFirmware;
    private boolean disconnected;
    private final String[] hardwareTitles = new String[2];
    private boolean presetsOpen, showFactory;
    private CompanionResponse inlinePlot;
    private FilterTable.SyncStatus inlineStatus;
    private LinearLayout eqMenu;
    private Button undoEditor, redoEditor, restoreEditor;
    private Button hardwareSave;
    private PairFiles pairFiles;
    private boolean pairedPage;
    private final java.util.ArrayList<View> frozenButtons=new java.util.ArrayList<>();
    private boolean verifiedInline;
    private void freezeButtons(View view){
        if(view instanceof Button && view.isEnabled() && !"preamp-sign".equals(view.getTag())){view.setEnabled(false);frozenButtons.add(view);}
        if(view instanceof android.view.ViewGroup){
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)freezeButtons(group.getChildAt(i));
        }
    }
    private void thawButtons(){
        for(View view:frozenButtons)view.setEnabled(true);
        frozenButtons.clear();
    }
    private String[] names = new String[20];
    private int selected = -1;
    private String exportData, fileTitle;
    private android.net.Uri document;
    private boolean comparing, audition;
    private BlindRound round;
    private boolean blind() { return round != null || (!audition && records.getBoolean("ab.blind",true)); }
    private String matchKey() { return blind() ? "ab.blindMatch" : "ab.match"; }
    private void storeRound() {
        String k=key()+".blind.";
        SharedPreferences.Editor e=records.edit();
        if (round==null) {
            e.remove(k+"swap").remove(k+"heard").remove(k+"pick");
            for (int i=0;i<2;i++) e.remove(k+i).remove(k+i+".name");
        } else {
            e.putBoolean(k+"swap",round.swapped).putInt(k+"heard",round.heardMask).putInt(k+"pick",round.favorite);
            for (int i=0;i<2;i++) e.putString(k+i,enc(pair[i])).putString(k+i+".name",pairNames[i]);
        }
        e.apply();
    }
    private void newRound() {
        round=BlindRound.start();
        heard=-1;
        feedback="";
        storeRound();
        draw();
    }
    private volatile GaiaProbe.Connection comparisonLink, audioLink;
    private int heard = -1;
    private final Peq[] pair = new Peq[2];
    private final String[] pairNames = new String[2];
    private ProbeLog log;
    private SharedPreferences records;
    private PairedPresets library;
    private PresetHistory history;
    private EqSnapshot recent;
    private Boolean routingShown;
    private String routeKey() { return workspaceKey(1) + ".route."; }
    private boolean routingPending() { return records.contains(routeKey() + "target"); }

    // File channels always refer to the earpieces in their original orientation.
    private Peq mapFileChannels(Peq value) {
        boolean swapped = records.getBoolean(routeKey() + "swapped", false);
        return value.group == 1 && value.parametric() && swapped ? value.swapChannels() : value.copy();
    }

    int editorBank() { return group; }
    private String[] stageLabels() { return AppearanceActivity.stages(this); }
    private String workspaceKey(int g) { return (address==null ? "offline" : address)+"."+g; }
    private String pairKey() { return workspaceKey(0)+".pairName"; }
    private static final int IMPORT = 1, EXPORT = 2, SIDE_A = 3, SIDE_B = 4;
    private static final String[] GROUPS = {"Main EQ", "Channel Match"};
    private final Runnable tick = new Runnable() {
        int count;
        public void run() {
            if (!visible || !audio)
                return;
            phoneVolume();
            updateSignal();
            if (address!=null && !busy && !dragging && automaticUsb() && !records.contains(usbBackupKey())
                    && android.os.SystemClock.elapsedRealtime()>=nextConsoleCheck) pollConsole();
            else if(!usbPage && address!=null && !busy && (devicePage?++count%5==0:true))refresh();
            ui.postDelayed(this, 1000);
        }
    };
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        pairFiles = new PairFiles(this, this::note, state);
        graphInputs = new EqGraphInputs(this,getSharedPreferences("eq-response-inputs",MODE_PRIVATE),worker,this::draw);
        pairedPage = state != null && state.getBoolean("pairedPage");
        records = getSharedPreferences("peq-workbench", MODE_PRIVATE);
        library = new PairedPresets(getSharedPreferences("paired-presets",MODE_PRIVATE));
        history = new PresetHistory(getSharedPreferences("peq-history", MODE_PRIVATE));
        log = new ProbeLog(getFilesDir());
        address = binding()!=null && binding().profile.model==DeviceProfile.Model.ORIGINAL?binding().profile.address:null;
        audio = getIntent().getBooleanExtra("audio", false);
        devicePage=getIntent().getBooleanExtra("devicePage",false);
        availableFirmware=ControllerFirmware.latest(this,false);
        usbPage = audio && state != null && state.getBoolean("usbPage");
        if (state != null) {
            presetsOpen=state.getBoolean("presetsOpen");
            audioPanel = state.getInt("audioPanel");
            usbPanel = state.getInt("usbPanel");
        }
        group = state == null ? getIntent().getIntExtra("group",0) : state.getInt("group");
        if(group != 1)group=0;
        if (address != null && !audio) group = records.getInt(address+".auditionGroup",group);
        if (address != null && !audio && routingPending()) group = 1;
        channel = state == null ? 0 : state.getInt("channel");
        loadWorkspace();
        if (state == null && !audio && getIntent().getBooleanExtra("blind", false) && !routingPending()) {
            comparing = true;
            if (!audition) records.edit().putBoolean("ab.blind", true).apply();
            if (!audition && getIntent().hasExtra(FitPair.EXTRA)) loadGeneratedComparison();
        }
        if(state==null && getIntent().getBooleanExtra("libraryDraft",false))importLibrary();
        else if (state == null && getIntent().hasExtra("tuningText")) importFromTuning();
        if (state != null) {
            byte[] bytes = state.getByteArray("draft");
            if (bytes != null && state.getInt("group") == group)
                draft = new Peq(group, bytes);
            exportData = state.getString("export");
            comparing |= state.getBoolean("compare");
            feedback = state.getString("feedback", "");
        }
        LinearLayout root = column();
        root.setBackgroundColor(LabStyle.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            ProbeActivity.applyInsets(v, insets);
            return insets;
        });
        setContentView(root);
        LinearLayout header = column();
        LabStyle.modelHeader(this,header,false,()->{if(!busy && !comparing)chooseTheme();else note("Finish the current operation to change theme.");},()->{if(!busy && !comparing)CompanionNavigation.go(this,LabStyle.DIAGNOSTICS,address);});
        if(!audio)eqMenu=LabStyle.eqMenu(this,header,comparing ? LabStyle.BLIND : LabStyle.EQ,this::selectEqPage);
        if(audio)root.addView(header);
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll = new CompanionEditor.Viewport(this);
        content.addView(scroll,-1,-1);
        LinearLayout container=CompanionEditor.pageBody(scroll);
        if(!audio) {
            container.addView(header);
            eqStages=new CompanionEditor.StickyStages(content,scroll,container);
        }
        body=column();
        container.addView(body,-1,-2);
        body.setPadding(dp(12),dp(4),dp(12),dp(6));
        int tab = devicePage ? LabStyle.HEADPHONES : audio ? LabStyle.AUDIO : LabStyle.EQ;
        LabStyle.nav(this, root, tab, d -> {
            if (d != tab && (!workerOutstanding || statusPoll)) {
                if (audition) note("End the comparison to restore your saved preset first.");
                else CompanionNavigation.go(this, d, address);
            }
        });
        draw();
    }
    private void importLibrary(){
        if(comparing || recoveryInEitherStage() || routingPending()){feedback="Finish the current device operation before loading a library draft.";return;}
        String[] files={getIntent().getStringExtra("tuningText"),getIntent().getStringExtra("matchText")};
        String title=getIntent().getStringExtra("tuningName");
        PairedPresets.Stage[] stages=new PairedPresets.Stage[2];
        try{
            for(int i=0;i<2;i++)if(files[i]!=null && !files[i].isEmpty()){
                Peq base=stage(i).eq;
                stages[i]=new PairedPresets.Stage(base.importText(files[i]),title,null);
            }
            SharedPreferences.Editor e=records.edit().remove(pairKey());
            for(int i=0;i<2;i++)if(stages[i]!=null)stageLocally(e,i,stages[i]);
            e.apply();group=stages[0]==null?1:0;channel=0;loadWorkspace();
            graphInputs.useFit(getIntent().getStringExtra("graphInputs"));
            feedback="Library draft loaded.";
        }catch(IllegalArgumentException e){feedback=e.getMessage();}
    }
    private void importFromTuning() {
        if (group != 0 || audio || comparing || recoveryInEitherStage() || !draft.parametric()) {
            feedback = "Finish the current device operation and select a stereo PEQ preset before importing.";
            return;
        }
        try {
            Peq imported = draft.importText(getIntent().getStringExtra("tuningText"));
            String title = getIntent().getStringExtra("tuningName");
            SharedPreferences.Editor edit = records.edit().remove(pairKey());
            stageLocally(edit, 0, new PairedPresets.Stage(imported, title, null));
            String transferId = getIntent().getStringExtra("tuningId");
            if (transferId != null) edit.putString("receivedTuningId", transferId);
            edit.apply();
            draft = imported;
            fileTitle = title;
            document = null;
            feedback = "Imported from Tuning. Review the filters before trying them on the 5K.";
        } catch (IllegalArgumentException error) {
            feedback = "Could not import this EQ. " + error.getMessage();
        }
    }

    private String key() {
        return workspaceKey(group);
    }
    private static String enc(Peq p) {
        return Base64.getEncoder().encodeToString(p.bytes);
    }
    private Peq dec(String s) {
        return new Peq(group, Base64.getDecoder().decode(s));
    }
    private void loadWorkspace() {
        verifiedInline=false;
        String key = key();
        edits[group].reload(group, records.getString(key + ".editUndo", ""), records.getString(key + ".editRedo", ""));
        fileTitle = records.getString(key + ".title", GROUPS[group]);
        String uri = records.getString(key + ".document", null);
        document = uri == null ? null : android.net.Uri.parse(uri);
        audition = records.getBoolean(key + ".audition", false);
        comparing |= audition;
        for (int i = 0; i < 2; i++) {
            String side = records.getString("ab." + group + "." + i, null);
            pair[i] = side == null ? null : mapFileChannels(dec(side));
            pairNames[i] = records.getString("ab." + group + "." + i + ".name", "");
        }
        String bk=key+".blind.";
        round=null;
        if (records.contains(bk+"swap")) {
            round=new BlindRound(records.getBoolean(bk+"swap",false),records.getInt(bk+"heard",0),records.getInt(bk+"pick",-1));
            for (int i=0;i<2;i++) {
                pair[i]=dec(records.getString(bk+i,""));
                pairNames[i]=records.getString(bk+i+".name","");
            }
            comparing=true;
        }
        session = null;
        fresh = false;
        selected = -1;
        names = new String[20];
        draft = Peq.flat(group);
        String original = records.getString(key + ".original", null);
        if (original != null) {
            session = new PeqSession(group, records.getInt(key + ".selector", -1), dec(original));
            session.confirmed = dec(records.getString(key + ".confirmed", original));
            String pending = records.getString(key + ".pending", null);
            if (pending != null)
                session.pending = dec(pending);
            session.saveTarget = records.getInt(key + ".target", -1);
            session.newSlotName = records.getString(key + ".newName", null);
            draft = session.pending == null ? session.confirmed.copy() : session.pending.copy();
        }
        String saved = records.getString(key + ".draft", null);
        if (saved != null)
            draft = dec(saved);
    }
    private void persist(PeqSession s) throws IOException {
        String k = (address == null ? "offline" : address) + "." + s.group;
        SharedPreferences.Editor e = records.edit()
                                             .putString(k + ".original", enc(s.original))
                                             .putInt(k + ".selector", s.originalSelector)
                                             .putString(k + ".confirmed", enc(s.confirmed))
                                             .putInt(k + ".target", s.saveTarget)
                                             .putString(k + ".newName", s.newSlotName);
        if (s.pending == null)
            e.remove(k + ".pending");
        else
            e.putString(k + ".pending", enc(s.pending));
        if (!e.commit())
            throw new IOException("Could not store the editor recovery state.");
    }
    private void keepDraft() {
        records.edit().putString(key() + ".draft", enc(draft))
                .putString(key() + ".editUndo", edits[group].undoBlocks())
                .putString(key() + ".editRedo", edits[group].redoBlocks()).apply();
    }
    private void clearWorkspace() {
        SharedPreferences.Editor e = records.edit();
        edits[group].clear();
        liveOnStage[group] = false;
        for (String part :
                new String[] {"original", "selector", "confirmed", "pending", "target", "newName", "draft", "staged", "editUndo", "editRedo"})
            e.remove(key() + "." + part);
        e.apply();
        loadWorkspace();
    }
    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        pairFiles.saveState(b);
        b.putBoolean("pairedPage", pairedPage);
        b.putBoolean("presetsOpen",presetsOpen);
        b.putBoolean("usbPage",usbPage);
        b.putInt("audioPanel", audioPanel);
        b.putInt("usbPanel", usbPanel);
        b.putInt("group", group);
        b.putInt("channel", channel);
        b.putByteArray("draft", draft.bytes);
        b.putString("export", exportData);
        b.putBoolean("compare", comparing);
        b.putString("feedback", feedback);
    }
    @Override
    protected void onStart() {
        super.onStart();
        if(isFinishing())return;
        visible = true;
        if (address != null && !readingFile)
            refresh();
        if (audio)
            ui.post(tick);
    }
    @Override
    protected void onStop() {
        visible = false;
        ui.removeCallbacks(tick);
        cancel();
        fresh = false;
        levels = null;
        usbSettings = null;
        dacSettings = null;
        super.onStop();
    }

    @Override boolean observesHardwareDisconnects() { return true; }

    @Override void hardwareDisconnected(String device,int transport) {
        if(transport==BluetoothDevice.TRANSPORT_LE)return;
        if(address==null || device!=null && !device.equalsIgnoreCase(address))return;
        disconnected=true;
        cancel();
        fresh=false; levels=null; usbSettings=null; dacSettings=null;
        feedback="5K disconnected. Reconnect from Device.";
        draw();
    }

    @Override boolean observesBluetoothAudio(){return devicePage;}
    @Override void bluetoothAudioChanged(){
        if(bluetoothAudioStatus!=null)bluetoothAudioStatus.setText(BluetoothPages.linkedStatus(this,address,false));
    }

    private void cancel() {
        synchronized (transportLock) {
            epoch++;
            comparisonLink = null;
            audioLink = null;
            heard = -1;
            if (socket != null)
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
        }
        busy = workerOutstanding || readingFile;
    }
    private interface Job {
        String run(int generation) throws Exception;
    }
    private void work(String operation, Job job) {
        if (busy || workerOutstanding || !visible)
            return;
        final boolean typing=operation.equals("Sync filter values");
        final boolean quiet = audio && statusPoll && levels != null;
        final long started = android.os.SystemClock.elapsedRealtime();
        final DeviceLevels previousLevels = levels;
        final DacSettings previousDac = dacSettings;
        final int previousUsbMode=UsbAudioSettings.platform(outputSpecs);
        workerOutstanding = true;
        busy = true;
        int generation;
        synchronized (transportLock) {
            generation = ++epoch;
        }
        if(typing){freezeButtons(body);inlineStatus.syncing();}
        else if (!quiet)draw();
        Runnable timeout = () -> {
            if (busy && epoch == generation) {
                cancel();
                fresh = false;
                levels = null;
                usbSettings = null;
                log.record("Device operation timed out on "+(usbPage ? "USB and microphone" : audio ? "Audio" : "EQ editor"));
                feedback = "Connection timed out. Refresh to check the 5K.";
                log.timing(operation, android.os.SystemClock.elapsedRealtime() - started, "timeout");
                draw();
            }
        };
        ui.postDelayed(timeout, 20000);
        worker.execute(() -> {
            String result;
            boolean ok = true;
            try (DeviceBinding.Operation operationLease=binding().begin(this)) {
                result = job.run(generation);
            } catch (Exception e) {
                ok = false;
                result = e.getMessage();
                if (result == null || result.trim().isEmpty())
                    result = "The control connection ended. Close other controllers and refresh.";
            }
            final String outcome = result;
            final boolean success = ok;
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            ui.post(() -> {
                ui.removeCallbacks(timeout);
                if(typing)thawButtons();
                workerOutstanding = false;
                statusPoll = false;
                if (epoch != generation || !visible) {
                    if(disconnected || !visible){fresh=false;levels=null;usbSettings=null;}
                    busy = false;
                    deferredAudioChange = null;
                    if (visible && !isDestroyed()) {
                        draw();
                        if (address != null && !disconnected && !fresh && !feedback.startsWith("Connection timed out"))
                            refresh();
                    }
                    return;
                }
                busy = false;
                if (!success) {
                    usbSettings = null;
                    closeComparisonLink();
                    closeAudioLink();
                    heard = -1;
                    fresh = false;
                    levels = null;
                    log.record("Workbench operation incomplete · "
                            + outcome.replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}", "[device]"));
                }
                boolean changed = previousLevels == null || !previousLevels.sameReading(levels) || previousUsbMode!=UsbAudioSettings.platform(outputSpecs)
                        || previousDac != dacSettings && (previousDac == null || !previousDac.sameReading(dacSettings));
                if(success && levels!=null && levels!=previousLevels){
                    String powerState="Discharging".equals(levels.charging)?null:
                            "Charging".equals(levels.charging)?"Charging":"On USB power";
                    batteryEta=BatteryEstimate.forLink(binding().lease)
                            .update(levels.battery,powerState,android.os.SystemClock.elapsedRealtime());
                    if(batteryEtaStatus!=null && !batteryEta.contentEquals(batteryEtaStatus.getText()))
                        batteryEtaStatus.setText(batteryEta);
                }

                if(typing && success){
                    feedback=outcome;
                    if(!draft.same(session.confirmed)){applyLiveEq(true);return;}
                    liveOnStage[group]=false;verifiedInline=true;inlineStatus.verified();updateInlineCommands();
                }else if (!quiet || !success || (changed && !dragging)) {
                    feedback = outcome;
                    draw();
                }
                Runnable volume = deferredAudioChange;
                deferredAudioChange = null;
                if (volume != null && success) volume.run();
                if (success && operation.equals("Read editor and preset names") && liveOnStage[group]
                        && !comparing && !audition) applyLiveEq();
            });
            log.timing(operation, elapsed, success ? "complete" : "failed");
        });
    }
    private GaiaProbe.Connection open(int generation) throws IOException {
        DeviceBinding owner=binding();
        if(owner==null || !owner.active(this) || owner.profile.model!=DeviceProfile.Model.ORIGINAL || !owner.radioMatches(address))
            throw new IOException("Reconnect your linked original 5K.");
        if (address == null)
            throw new IOException("Connect to your paired 5K from Device first.");
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED)
            throw new IOException("Grant Nearby devices on the Presets screen first.");
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null)
            throw new IOException("Bluetooth is unavailable.");
        BluetoothDevice d = adapter.getRemoteDevice(address);
        if (d.getBondState() != BluetoothDevice.BOND_BONDED || !"Qudelix-5K".equalsIgnoreCase(d.getName()))
            throw new IOException("Reconnect the paired original 5K from Device.");
        BluetoothSocket s = d.createRfcommSocketToServiceRecord(GaiaProbe.SERIAL_PORT);
        synchronized (transportLock) {
            if (epoch != generation) {
                s.close();
                throw new IOException("Operation closed.");
            }
            socket = s;
        }
        try {
            s.connect();
            GaiaProbe.Connection link=new GaiaProbe.Connection(s.getInputStream(), owner.guard(this,s.getOutputStream()), trace -> {
                if (!audio)
                    log.record(trace);
            }, s::close);
            owner.require(this,DeviceProfile.original(link.readDevice().model,d.getAddress()));
            return link;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }
    private EqSnapshot snapshot(GaiaProbe.Connection link) throws IOException {
        link.requireEditorFirmware();
        return link.read();
    }
    private static String slotName(GaiaProbe.Connection link, int g, int slot) throws IOException {
        return PresetDirectory.readName(link,g,slot);
    }
    private void refresh() {
        if (address == null || busy)
            return;
        disconnected=false;
        if (usbPage) {
            work("Read USB and microphone", g -> {
                closeAudioLink();
                try (GaiaProbe.Connection link=open(g)) { usbSettings=link.usbAudio(); }
                return "";
            });
            return;
        }
        statusPoll = audio;
        String operation = audio ? (audioPanel == 1 ? "Read DAC and audio status" : "Read audio status")
                : "Read editor and preset names";
        work(operation, g -> {
            if (audio) {
                levels = audioReading(g);
                if(!devicePage && (!dragging || dacSettings==null || outputSpecs==null)){
                    dacSettings=audioLink.dacSettings();outputSpecs=audioLink.outputSpecifications();
                }
                return "";
            }
            try (GaiaProbe.Connection link = open(g)) {
                loadWorkspace();
                EqSnapshot state = snapshot(link);
                recent=state;
                if (group == 1 || group == 0 && !records.contains(routeKey() + "swapped")) {
                    routingShown = link.channelsSwapped();
                    if (!routingPending())
                        records.edit().putBoolean(routeKey() + "swapped", routingShown).apply();
                }
                if (routingPending() && group == 1) {
                    fresh = true;
                    return "Channel swap was interrupted. Finish it to verify routing and both PEQs.";
                }
                Peq live = PeqSession.block(state, group);
                String[] list = new String[20];
                for (int i = 0; i < 20; i++)
                    if ((state.configuration[6 + 9 * group + i / 8] & (1 << (i % 8))) != 0)
                        list[i] = slotName(link, group, i);
                int other=1-group;
                int otherSelector=PeqSession.selector(state,other);
                String otherTitle=otherSelector==255 ? "Unsaved device EQ" : "Factory #"+otherSelector;
                if (otherSelector>=22 && otherSelector<42) {
                    int index=otherSelector-22;
                    boolean named=(state.configuration[6+9*other+index/8] & (1<<(index%8)))!=0;
                    String name=named ? slotName(link,other,index) : "";
                    otherTitle="Custom #"+(index+1)+(name.isEmpty() ? "" : " · "+name);
                }
                if (!Arrays.equals(state.configuration, link.readEqConfiguration()))
                    throw new IOException("Preset selection changed. Refresh again.");
                hardwareTitles[other] = otherTitle;
                names = list;
                selected = PeqSession.selector(state, group);
                if (session!=null && session.originalSelector!=selected && selected<42
                        && session.pending==null && session.saveTarget<0 && !records.getBoolean(key()+".staged",false)
                        && draft.same(session.confirmed) && session.confirmed.same(session.original)) {
                    edits[group].clear();
                    document=null; fileTitle=selected>=22 ? slot(selected-22) : "Factory #"+selected;
                    records.edit().remove(key()+".document").putString(key()+".title",fileTitle).apply();
                }
                if(document==null && selected>=22 && selected<42 && !records.getBoolean(key()+".staged",false)) {
                    fileTitle=slot(selected-22);
                    records.edit().putString(key()+".title",fileTitle).apply();
                }
                boolean dirty = session != null
                        && ((selected == 255 && session.originalSelector != 255 && live.same(session.confirmed))
                                || session.pending != null || session.saveTarget >= 0
                                || !session.confirmed.same(session.original) || !draft.same(session.confirmed));
                boolean staged=records.getBoolean(key()+".staged",false);
                if ((!dirty || staged && session==null) && !audition) {
                    session = new PeqSession(group, selected, live);
                    if (!staged){
                        draft=live.copy();
                        records.edit().putString(key()+".reload",enc(live)).apply();
                    }
                    persist(session);
                    keepDraft();
                } else if (session.saveTarget < 0)
                    session.guard(state);
                fresh = true;
                String linkedMessage = trackUsrSlot(state);
                if (linkedMessage != null) return linkedMessage;
                return live.parametric()
                        ? ""
                        : "This group is using GEQ. Load a parametric preset to edit its filters.";
            }
        });
    }
    private void ensureWrite() throws IOException {
        if (routingPending())
            throw new IOException("Finish the channel swap in Channel Match first.");
        if (new EqTestJournal(getFilesDir()).exists())
            throw new IOException("Finish the pending recovery in Diagnostics first.");
    }
    private void adoptCurrentRouting() {
        new AlertDialog.Builder(this).setTitle("Use the current 5K settings?")
            .setMessage("End this interrupted swap and replace the editor draft with the routing and filters currently on the 5K.")
            .setNegativeButton("Cancel", null).setPositiveButton("Use current settings", (dialog, which) -> work("Read current channel routing", g -> {
                String k = routeKey();
                try (GaiaProbe.Connection link = open(g)) {
                    EqSnapshot live = snapshot(link);
                    boolean swapped = link.channelsSwapped();
                    Peq active = PeqSession.block(live, 1);
                    int selector = PeqSession.selector(live, 1);
                    PeqSession adopted = new PeqSession(1, selector, active);
                    persist(adopted);
                    if (!records.edit().putString(key() + ".draft", enc(active)).remove(key() + ".staged")
                             .remove(key() + ".document").putString(key() + ".title", GROUPS[1])
                             .remove(k + "target").remove(k + "config").remove(k + "usr").remove(k + "spk")
                             .remove(k + "draft").putBoolean(k + "swapped", swapped).commit())
                        throw new IOException("Could not record the current state.");
                    document = null;
                    fileTitle = GROUPS[1];
                    selected = selector;
                    session = adopted;
                    draft = active;
                    recent = live;
                    routingShown = swapped;
                    edits[1].clear();
                    keepDraft();
                    fresh = true;
                }
                return "Editor now matches the current 5K routing and filters.";
            })).show();
    }

    private void swapChannels() {
        if (address == null || session == null || group != 1) return;
        final Boolean displayed = routingShown;
        work("Change channel routing", g -> {
            String k = routeKey();
            boolean retry = routingPending();
            if (new EqTestJournal(getFilesDir()).exists() || session.saveTarget >= 0 || audition)
                throw new IOException("Finish the current hardware operation first.");
            boolean target;
            Peq nextDraft;
            EqSnapshot baseline;
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot before = snapshot(link);
                boolean current = link.channelsSwapped();
                if (retry) {
                    byte[] config = Base64.getDecoder().decode(records.getString(k + "config", ""));
                    byte[] usr = Base64.getDecoder().decode(records.getString(k + "usr", ""));
                    Peq start = dec(records.getString(k + "spk", ""));
                    baseline = new EqSnapshot(before.device, config, usr, start.bytes);
                    target = records.getBoolean(k + "target", false);
                    nextDraft = dec(records.getString(k + "draft", ""));
                } else {
                    ensureWrite();
                    Peq live = PeqSession.block(before, 1);
                    session.guard(before, !live.same(live.swapChannels()));
                    if (displayed == null || current != displayed || session.pending != null)
                        throw new IOException("Refresh Channel Match before swapping.");
                    baseline = before;
                    target = !current;
                    nextDraft = draft.swapChannels();
                    if (!records.edit().putBoolean(k + "target", target).putString(k + "draft", enc(nextDraft))
                            .putString(k + "config", Base64.getEncoder().encodeToString(before.configuration))
                            .putString(k + "usr", Base64.getEncoder().encodeToString(before.usr))
                            .putString(k + "spk", enc(PeqSession.block(before, 1))).commit())
                        throw new IOException("Could not store the channel routing recovery point.");
                }
                ChannelRouting.apply(link, baseline, before, session, target, this::persist);
            }
            ReversibleEqTest.settle();
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot after = snapshot(link);
                ChannelRouting.verify(link, baseline, after, session, target);
                recent = after;
                selected = PeqSession.selector(after, 1);
            }
            if (!records.edit().putBoolean(k + "swapped", target).putString(key() + ".draft", enc(nextDraft))
                      .remove(k + "target").remove(k + "config").remove(k + "usr")
                      .remove(k + "spk").remove(k + "draft").commit())
                throw new IOException("Finish the channel swap again to verify the saved state.");
            routingShown = target;
            edits[1].clear();
            draft = nextDraft;
            keepDraft();
            for (int i = 0; i < 2; i++)
                if (pair[i] != null) pair[i] = pair[i].swapChannels();
            fresh = true;
            return target ? "Channels swapped. DAC routing and both Channel Match PEQs verified."
                          : "Normal routing and both Channel Match PEQs verified.";
        });
    }

    private void applyLiveEq(){applyLiveEq(false);}
    private void applyLiveEq(boolean typing) {
        if (!fresh || busy || address == null || session == null || session.saveTarget >= 0
                || comparing || audition || routingPending() || !draft.parametric()) return;
        if (session.pending == null && draft.same(session.confirmed)) {
            liveOnStage[group] = false;
            return;
        }
        final Peq target = draft.copy();
        feedback = "Applying live EQ…";
        work(typing?"Sync filter values":"Live EQ", g -> {
            ensureWrite();
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot start = snapshot(link);
                selected = session.preview(link, start, target, this::persist);
            }
            if(!typing)liveOnStage[group] = false;
            fresh = true;
            return "Live EQ · Unsaved";
        });
    }

    private boolean canReset() {
        return fresh && !busy && address != null && session != null && session.saveTarget < 0
                && session.originalSelector >= 0 && session.originalSelector < 42 && !routingPending();
    }

    private void restore() {
        if (!canReset()) return;
        final Peq previous = draft.copy();
        feedback = "Reading saved EQ…";
        work("Reset to saved EQ", g -> {
            ensureWrite();
            EqSnapshot before;
            try (GaiaProbe.Connection link = open(g)) {
                before = snapshot(link);
                session.restore(link, before);
            }
            ReversibleEqTest.settle();
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot after = snapshot(link);
                int slot = session.originalSelector;
                if (PeqSession.selector(after, group) != slot)
                    throw new IOException("The saved selection could not be verified.");
                Peq saved = PeqSession.block(after, group);
                session.verifyTransition(before, after, saved);
                PeqSession restored = new PeqSession(group, slot, saved);
                persist(restored);
                session = restored;
                selected = slot;
                records.edit().remove(key() + ".staged").apply();
                draft = saved;
                edits[group].record(previous, draft);
                liveOnStage[group] = false;
                keepDraft();
                recent = after;
            }
            fresh = true;
            return "Saved EQ restored";
        });
    }

    private String slot(int i) {
        return "Custom #" + (i + 1) + (names[i] == null ? "" : " · " + names[i]);
    }
    private Button action(LinearLayout parent, String title, Runnable run, boolean enabled) {
        Button b = new Button(this);
        LabStyle.quiet(b);
        b.setText(title);
        b.setEnabled(enabled);
        b.setOnClickListener(v -> run.run());
        parent.addView(b, -2, dp(48));
        return b;
    }
    private void selectEqPage(int destination) {
        if (busy) return;
        if (audition) { note("End the comparison to restore your saved preset first."); return; }
        if (routingPending() || session != null && (session.pending != null || session.saveTarget >= 0)) {
            note("Finish the pending device operation in Filters first.");
            return;
        }
        if (destination == LabStyle.BLIND) {
            comparing = true;
            records.edit().putBoolean("ab.blind", true).apply();
            feedback = "";
            draw();
            scroll.top();
            return;
        }
        if (comparing) exitComparison();
        if (destination == LabStyle.EQ) scroll.top();
        else CompanionNavigation.go(this, destination, address);
    }

    private void editorTools(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Rename PEQ file…").setOnMenuItemClickListener(item -> { renameFile(); return true; });
        menu.getMenu().add("Rename 5K preset…")
                .setEnabled(fresh && selected >= 22 && selected < 42 && session != null && session.saveTarget < 0)
                .setOnMenuItemClickListener(item -> { renamePreset(); return true; });
        int activeSlot = session == null ? -1 : session.originalSelector;
        menu.getMenu().add("Preset history…").setEnabled(fresh && savedVersion(activeSlot) != null)
                .setOnMenuItemClickListener(item -> { showPresetHistory(activeSlot); return true; });
        menu.getMenu().add("Refresh from 5K").setEnabled(address != null)
                .setOnMenuItemClickListener(item -> { refresh(); return true; });
        if (session != null && !fresh && session.saveTarget < 0 && session.pending == null)
            menu.getMenu().add("Reopen current preset…").setOnMenuItemClickListener(item -> {
                new AlertDialog.Builder(this).setTitle("Discard this editor draft?")
                        .setMessage("Read the EQ currently playing on the 5K.")
                        .setPositiveButton("Reopen", (d, w) -> { clearWorkspace(); refresh(); })
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
        menu.show();
    }

    private void renamePreset() {
        final int raw = selected, i = raw - 22;
        final String expected = names[i];
        nameDialog("Rename 5K preset", expected == null ? "Custom #" + (i+1) : expected, "Save name", name -> {
            Peq.require(name.getBytes(StandardCharsets.UTF_8).length <= 32, "Use at most 32 UTF-8 bytes for a 5K name.");
            if (name.equals(expected)) return;
            work("Rename preset", g -> {
                ensureWrite();
                EqSnapshot before;
                try (GaiaProbe.Connection link = open(g)) {
                    before = snapshot(link);
                    boolean named = (before.configuration[6 + 9*group + i/8] & (1 << (i%8))) != 0;
                    if (PeqSession.selector(before,group) != raw || named != (expected != null)
                            || (named && !expected.equals(slotName(link,group,i))))
                        throw new IOException("The selected preset changed. Refresh before renaming.");
                    link.nameSlot(group, i, name);
                }
                ReversibleEqTest.settle();
                try (GaiaProbe.Connection link = open(g)) {
                    EqSnapshot after = snapshot(link);
                    byte[] config = before.configuration.clone();
                    config[6 + 9*group + i/8] |= 1 << (i%8);
                    if (!Arrays.equals(config, after.configuration) || !Arrays.equals(before.usr, after.usr)
                            || !Arrays.equals(before.spk, after.spk) || !name.equals(slotName(link,group,i)))
                        throw new IOException("Rename needs verification. Refresh to read the saved name.");
                }
                names[i] = name;
                return "Preset name saved and verified. All filters match.";
            });
        });
    }
    private void nameDialog(String title, String previous, String positive, java.util.function.Consumer<String> save) {
        LinearLayout box = form();
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setText(previous);
        input.setContentDescription("Name");
        box.addView(input);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setNegativeButton("Cancel", null).setPositiveButton(positive, null).create();
        d.setOnShowListener(w -> d.getButton(-1).setOnClickListener(v -> {
            String n = input.getText().toString().trim();
            try {
                Peq.require(!n.isEmpty() && n.length() <= 120 && !n.equals(".") && !n.equals("..")
                        && n.codePoints().noneMatch(c -> c == '/' || c == 92 || Character.isISOControl(c)),
                        "Enter a name without slashes or control characters.");
                save.accept(n);
                d.dismiss();
            } catch (IllegalArgumentException e) { input.setError(e.getMessage()); }
        }));
        d.show();
    }
    private String displayName(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {}
        return GROUPS[group];
    }
    private static String stem(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0,dot) : n;
    }
    private void bindDocument(android.net.Uri uri, int flags, String providerName) {
        int grant = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri,grant); } catch (SecurityException ignored) {}
        document = uri;
        fileTitle = stem(providerName);
        records.edit().putString(key()+".title",fileTitle).putString(key()+".document",uri.toString()).apply();
    }
    private void renameFile() {
        nameDialog(document == null ? "Name this PEQ" : "Rename PEQ file", fileTitle, "Save name", name -> {
            if (document != null) {
                android.net.Uri original=document;
                work("Rename PEQ file", g -> {
                    String oldName=displayName(original);
                    int dot=oldName.lastIndexOf('.');
                    String wanted=name+(dot>0 ? oldName.substring(dot) : "");
                    if (oldName.equals(wanted)) return "File name saved.";
                    android.net.Uri renamed;
                    try { renamed=android.provider.DocumentsContract.renameDocument(getContentResolver(),original,wanted); }
                    catch(Exception error) { renamed=null; }
                    if(renamed==null) throw new IOException("This location can’t rename the file. Use Tools → Export PEQ.");
                    String actual=displayName(renamed);
                    library.renamed(original.toString(),renamed.toString(),stem(actual));
                    bindDocument(renamed,Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION,actual);
                    return "File renamed to "+actual+".";
                });
            } else {
                fileTitle = name;
                records.edit().putString(key()+".title", name).apply();
                file(false);
            }
        });
    }
    private CompanionActions.RoutingPanel channelRouting() {
        boolean pending = routingPending();
        boolean known = fresh && routingShown != null;
        int state = pending ? CompanionActions.RoutingPanel.INTERRUPTED
                : !known ? CompanionActions.RoutingPanel.UNAVAILABLE
                : routingShown ? CompanionActions.RoutingPanel.SWAPPED : CompanionActions.RoutingPanel.NORMAL;
        boolean enabled = !busy && address != null && session != null
                && (pending || fresh && session.pending == null && session.saveTarget < 0 && draft.parametric());
        String reason = busy ? "Wait for the current 5K operation to finish."
                : !fresh && !pending ? "Connect from Device and read the 5K to swap channels."
                : session == null ? "Read the 5K before changing channel routing."
                : !draft.parametric() ? "Select a parametric Channel Match preset on the 5K."
                : "Finish the current 5K operation before changing channel routing.";
        return new CompanionActions.RoutingPanel(this)
                .setActions(this::swapChannels, this::adoptCurrentRouting)
                .setState(state, enabled, reason)
                .setRecoveryEnabled(!busy && address != null);
    }

    private void showRouting() {
        CompanionActions.RoutingPanel panel = channelRouting();
        AlertDialog dialog = editorDialog(panel);
        panel.setActions(() -> { dialog.dismiss(); swapChannels(); },
                () -> { dialog.dismiss(); adoptCurrentRouting(); });
        dialog.show();
    }

    private void addEditorBlock(View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        body.addView(child, params);
    }

    private PairedPresets.Stage stage(int g) {
        String k=workspaceKey(g);
        if (g==group) return new PairedPresets.Stage(mapFileChannels(draft),fileTitle,document==null?null:document.toString());
        String data=records.getString(k+".draft",null);
        boolean staged=records.getBoolean(k+".staged",false) || data!=null && !data.equals(records.getString(k+".confirmed",data));
        Peq eq = recent!=null && !staged ? PeqSession.block(recent,g) : data!=null
                ? new Peq(g,Base64.getDecoder().decode(data)) : Peq.flat(g);
        String title=records.getString(k+".title",GROUPS[g]), uri=records.getString(k+".document",null);
        if (recent!=null && !staged && hardwareTitles[g]!=null && (uri==null || !enc(eq).equals(data))) {
            title=hardwareTitles[g];
            uri=null;
        }
        return new PairedPresets.Stage(mapFileChannels(eq),title,uri);
    }
    void attachSyncDraft(Intent intent) {
        if(draft==null || records==null)return;
        for(int g=0;g<2;g++){
            PairedPresets.Stage value=stage(g);
            if(value.eq.parametric()){
                String key=g==0?"main":"match";
                intent.putExtra(key,value.eq.exportText()).putExtra(key+"Name",componentName(value,g));
            }
        }
    }
    private String componentName(PairedPresets.Stage stage,int index) {
        return stage.name==null || stage.name.isEmpty() || (GROUPS[index].equals(stage.name) || (index==0 && "PEQ Filters".equals(stage.name))) && stage.uri==null
                ? "Unnamed draft" : stage.name;
    }
    private String componentNames(PairedPresets.Stage[] stages) {
        String[] labels=stageLabels();
        return labels[0]+" · "+componentName(stages[0],0)+"\n"+labels[1]+" · "+componentName(stages[1],1);
    }
    private void saveOnPhone(){
        new AlertDialog.Builder(this).setTitle("Save on this phone")
                .setItems(new String[]{stageLabels()[group]+" as a PEQ file","Main EQ and Channel Match as a paired preset"},
                        (d,i)->{if(i==0)file(false);else savePair();})
                .setNegativeButton("Cancel",null).show();
    }

    private AlertDialog editorDialog(View content) {
        ScrollView scroller = new ScrollView(this);
        scroller.setPadding(dp(12), dp(12), dp(12), dp(4));
        scroller.addView(content, -1, -2);
        return new AlertDialog.Builder(this).setView(scroller).setNegativeButton("Close", null).create();
    }

    private void savePair() {
        PairedPresets.Stage[] stages={stage(0),stage(1)};
        if (!stages[0].eq.parametric() || !stages[1].eq.parametric()) { note("Choose a parametric EQ in both tabs."); return; }
        String proposed=records.getString(pairKey(),stages[0].name);
        nameDialog("Name EQ + Channel Match preset",proposed,"Continue",name->{
            boolean exists=library.read(name)!=null;
            new AlertDialog.Builder(this).setTitle(exists?"Replace "+name+"?":"Save "+name+"?")
                .setMessage(componentNames(stages))
                .setPositiveButton("Save pair",(d,w)->{
                    String source=stages[0].uri;
                    if(source==null && recent!=null) source=usrSlotKey(PeqSession.selector(recent,0));
                    library.save(name,stages,source);
                    SharedPreferences.Editor points=records.edit();
                    for(int g=0;g<2;g++)points.putString(workspaceKey(g)+".reload",enc(mapFileChannels(stages[g].eq)));
                    points.apply();
                    records.edit().putString(pairKey(),name).apply(); note("EQ + Channel Match preset saved on this phone.");
                }).setNegativeButton("Cancel",null).show();
        });
    }
    private void openPair(String name) {
        new AlertDialog.Builder(this).setTitle("Open "+name+"?")
            .setMessage("Replace both editor drafts. A connected 5K plays each stage when you open its tab. Save to store that stage on the device.")
            .setPositiveButton("Open",(dialog,w)->{
                PairedPresets.Stage[] stages=library.read(name);
                if(stages==null || recoveryInEitherStage()){note("Finish pending hardware recovery before opening a pair.");return;}
                Peq[] drafts={pairedDraft(0,stages[0]),pairedDraft(1,stages[1])};
                SharedPreferences.Editor e=records.edit().putString(pairKey(),name);
                storeStage(e,0,stages[0],drafts[0]);storeStage(e,1,stages[1],drafts[1]);e.apply();
                Peq next = drafts[group];
                edits[group].record(draft, next);
                edits[1 - group].clear();
                liveOnStage[1 - group] = address != null;
                draft = next;
                fileTitle = stages[group].name;
                keepDraft();
                document=stages[group].uri==null?null:android.net.Uri.parse(stages[group].uri);
                presetsOpen=false;
                note("EQ and Channel Match loaded into the editor.");
                scroll.top();
                applyLiveEq();
            }).setNegativeButton("Cancel",null).show();
    }
    private boolean recoveryInEitherStage() {
        if (routingPending()) return true;
        for(int g=0;g<2;g++) {
            String k=workspaceKey(g);
            if(records.contains(k+".pending") || records.getInt(k+".target",-1)>=0 || records.getBoolean(k+".audition",false)) return true;
        }
        return false;
    }
    private void selectEqStage(int stage) {
        if (busy || routingPending()) return;
        if (stage==2 && recoveryInEitherStage()) {
            note("Finish recovery before viewing both stages.");
            return;
        }
        boolean wasPair = pairedPage;
        pairedPage = stage == 2;
        if (wasPair == pairedPage && (pairedPage || stage == group)) return;
        presetsOpen = false;
        scroll.top();
        if (!pairedPage && stage != group) changeGroup(stage);
        else draw();
    }

    private void browsePairs() {
        String[] choices = library.names();
        if (choices.length == 0) {
            note("No paired presets saved on this phone.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Load paired EQ").setItems(choices,(dialog,item)->openPair(choices[item]))
                .setNegativeButton("Cancel",null).show();
    }

    private void importPair(PairFiles.Pair file) {
        // Read both stages completely before storing either. The original 5K has ten slots per channel.
        Peq[] stages = file.forOriginal();
        String title = PairFiles.uniqueTitle(file.title, candidate -> library.read(candidate) != null);
        library.save(title, new PairedPresets.Stage[]{
                new PairedPresets.Stage(stages[0],title,null),new PairedPresets.Stage(stages[1],title,null)}, null);
        note(title + " saved on this phone. Use Load preset to open the pair.");
    }

    private void drawPair() {
        PairedPresets.Stage main=stage(0), match=stage(1);
        boolean ready=!busy && !recoveryInEitherStage();
        boolean peq=main.eq.parametric() && match.eq.parametric();
        CompanionEditor.presetName(body,records.getString(pairKey(),"Paired EQ"));
        CompanionEditor.presetActions(body,false,ready,this::browsePairs,"Save to 5K",false,()->{},
                ready && peq,this::savePair);
        CompanionEditor.files(body,ready,ready && peq,pairFiles::pick,()->exportPair(main,match));
        if (peq) {
            CompanionEditor.graph(body,ResponseTraces.Stage.PAIR,graphInputs,new PeqResponse(main.eq,0),
                    new PeqResponse(match.eq,0),new PeqResponse(match.eq,1));
        }
        CompanionEditor.pairStage(body,"Main EQ",componentName(main,0),()->selectEqStage(0));
        CompanionEditor.pairStage(body,"Channel Match",componentName(match,1),()->selectEqStage(1));
        text(body,"Save each stage to its own 5K slot. A paired file carries both stages together.",13);
    }

    private void exportPair(PairedPresets.Stage main, PairedPresets.Stage match) {
        try {
            pairFiles.write(new PairFiles.Pair(records.getString(pairKey(),componentName(main,0)),
                    main.eq.exportText(),match.eq.exportText()));
        } catch (IllegalArgumentException incompatible) {
            note(incompatible.getMessage());
        }
    }

    private void reloadStage(){
        if(busy || routingPending() || session!=null && session.saveTarget>=0)return;
        Peq baseline=reloadPoint();
        if(!baseline.parametric()){note("This preset uses graphical EQ.");return;}
        if(draft.same(baseline)){note("Starting filters already loaded.");return;}
        setEditedDraft(baseline.copy());
    }
    private Peq reloadPoint(){
        String source=records.getString(key()+".reload",null);
        if(source!=null)return dec(source);
        if(session!=null)return session.original;
        Peq first=edits[group].first();
        return first!=null?first:draft;
    }
    private void rememberStartingFilters(){
        String key=key()+".reload";
        if(!records.contains(key))records.edit().putString(key,enc(reloadPoint())).apply();
    }
    private void clearFilters(){
        if(!busy && !routingPending() && draft.parametric()
                && (session==null || session.saveTarget<0) && draft.hasFilters())
            setEditedDraft(draft.clearFilters());
    }
    private void stageLocally(SharedPreferences.Editor e,int g,PairedPresets.Stage stage) {
        storeStage(e,g,stage,mapFileChannels(stage.eq));
    }
    private Peq pairedDraft(int g,PairedPresets.Stage stage) {
        Peq portable=mapFileChannels(stage.eq);
        String verified=records.getString(workspaceKey(g)+".confirmed",null);
        Peq destination=recent!=null ? PeqSession.block(recent,g)
                : verified!=null ? new Peq(g,Base64.getDecoder().decode(verified)) : null;
        return destination==null ? portable : destination.withPortableSettings(portable);
    }
    private void storeStage(SharedPreferences.Editor e,int g,PairedPresets.Stage stage,Peq value) {
        String k=workspaceKey(g);
        e.remove(k + ".editUndo").remove(k + ".editRedo");
        e.putString(k+".reload",enc(value)).putString(k+".draft",enc(value)).putBoolean(k+".staged",true)
                .putString(k+".title",stage.name).putString(k+".document",stage.uri);
    }
    private String usrSlotKey(int raw) {
        return address==null || raw<22 || raw>=42 ? null : address+"/USR/"+raw;
    }
    private String recallCompanion(String source) {
        String name=library.linked(source);
        PairedPresets.Stage[] stages=name==null?null:library.read(name);
        if(stages==null) {records.edit().remove(pairKey()).apply();return null;}
        if(recoveryInEitherStage()) return "Paired Channel Match is available after the pending hardware operation.";
        SharedPreferences.Editor e=records.edit().putString(pairKey(),name);
        Peq match=pairedDraft(1,stages[1]);
        storeStage(e,1,stages[1],match);e.apply();
        if(group==1) { draft=match; fileTitle=stages[1].name;
            document=stages[1].uri==null?null:android.net.Uri.parse(stages[1].uri); }
        return "Paired Channel Match loaded as a draft · "+name;
    }
    private String trackUsrSlot(EqSnapshot state) {
        int raw=PeqSession.selector(state,0);
        if(raw==255 || records.getInt(workspaceKey(0)+".seenSlot",-1)==raw) return null;
        records.edit().putInt(workspaceKey(0)+".seenSlot",raw).apply();
        return recallCompanion(usrSlotKey(raw));
    }
    private void captureSide(int i, Peq p, String name) {
        pair[i] = p.copy();
        pairNames[i] = name;
        String prefix = "ab." + group + "." + i;
        records.edit().putString(prefix, enc(mapFileChannels(p))).putString(prefix + ".name", name).apply();
    }
    private void loadGeneratedComparison() {
        try {
            FitPair fits = new FitPair(getIntent().getStringExtra(FitPair.EXTRA));
            Peq first = Peq.flat(0).importText(fits.main[0]), second = Peq.flat(0).importText(fits.main[1]);
            if (first.same(second)) throw new IllegalArgumentException("Choose two distinct Main PEQ fits.");
            SharedPreferences.Editor edit = records.edit();
            for (int i = 0; i < 2; i++) {
                pair[i] = i == 0 ? first : second;
                pairNames[i] = fits.names[i];
                String prefix = "ab.0." + i;
                edit.putString(prefix, enc(pair[i])).putString(prefix + ".name", pairNames[i]);
            }
            round = null;
            storeRound();
            edit.apply();
            feedback = "Two Main PEQ fits ready. The current Channel Match stays fixed.";
        } catch (org.json.JSONException | IllegalArgumentException error) {
            feedback = "Could not prepare the two Main PEQ fits.";
        }
    }
    private void chooseSide(int i) {
        String[] options = session != null && fresh
                ? new String[]{"Current editor draft", "Saved baseline on 5K", "Open PEQ file…"}
                : new String[]{"Current editor draft", "Open PEQ file…"};
        new AlertDialog.Builder(this).setTitle("Choose EQ " + (blind() ? String.valueOf(i+1) : i == 0 ? "A" : "B"))
                .setItems(options, (dlg, which) -> {
                    if (which == options.length - 1) { file(i == 0 ? SIDE_A : SIDE_B); return; }
                    boolean saved = which == 1;
                    String name = saved && session.originalSelector >= 22 && session.originalSelector < 42
                            ? slot(session.originalSelector-22) : saved ? "Saved EQ" : fileTitle;
                    captureSide(i, saved ? session.original : draft, name);
                    feedback = ""; draw();
                }).setNegativeButton("Cancel",null).show();
    }
    private void drawComparison() {
        boolean blind = blind();
        if (round == null && !audition) {
            Switch mode = new Switch(this);
            LabStyle.toggleLabel(mode,"Blind A/B",blind);
            LabStyle.toggle(mode);
            mode.setChecked(blind);
            mode.setEnabled(!busy);
            mode.setOnCheckedChangeListener((v,on) -> {
                records.edit().putBoolean("ab.blind",on).apply();
                feedback=""; draw();
            });
            body.addView(mode);
        }
        if (round != null)
            text(body, round.hidden() ? "Blind round" : "Your favorite · " + (round.favorite==0 ? "A" : "B"),20);
        if (round == null || !round.hidden()) for (int i=0; i<2; i++) {
            final int side = round==null ? i : round.source(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(row);
            TextView letter = text(row, blind && round==null ? String.valueOf(i+1) : i==0 ? "A" : "B", 24);
            letter.setTextColor(i==0 ? LabStyle.BLUE : LabStyle.CORAL);
            TextView label = text(row, pair[side]==null ? "Choose an EQ" : pairNames[side], 16);
            label.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
            if (round==null) action(row, pair[i]==null ? "Choose…" : "Change…", () -> chooseSide(side), !busy && !audition);
            LabStyle.divider(this,body);
        }
        if ((!blind || (round!=null && !round.hidden())) && pair[0] != null && pair[1] != null) {
            if (group == 1)
                segments(new String[]{"Left","Right"},channel,this::changeChannel);
            TextView caption = text(body,"EQ curves before level match",13);
            caption.setTextColor(LabStyle.MUTED);
            body.addView(new ResponseView(this,pair[round==null?0:round.source(0)],pair[round==null?1:round.source(1)],channel,heard),-1,dp(106));
        }
        Switch match = new Switch(this);
        LabStyle.toggle(match);
        LabStyle.toggleLabel(match,"Loudness match",records.getBoolean(matchKey(),true));
        match.setMinHeight(dp(48));
        match.setChecked(records.getBoolean(matchKey(),true));
        match.setEnabled(!busy && !audition && round==null);
        match.setOnCheckedChangeListener((v,on) -> {
            records.edit().putBoolean(matchKey(),on).apply();
            LabStyle.toggleLabel(match,"Loudness match",on);
        });
        body.addView(match);
        text(body,"Uses loudness attenuation to match the two EQs by turning levels down. Hardware volume stays fixed.",14);
        boolean have = pair[0] != null && pair[1] != null;
        boolean canListen = have && fresh && !busy && session != null && session.originalSelector >= 0
                && session.originalSelector < 42 && session.saveTarget < 0 && session.pending == null
                && (audition || (selected == session.originalSelector && session.confirmed.same(session.original)));
        if (blind && round==null) {
            text(body, address == null ? "Choose two EQs here, then connect your 5K from Device to listen. Each round randomly assigns A and B." : "Choose two EQs. Each round randomly assigns A and B.",14);
            button(body,"Start blind round",this::newRound,have && !busy);
            return;
        }
        LinearLayout controls = new LinearLayout(this);
        body.addView(controls);
        for (int i = 0; i < 2; i++) {
            int side = i;
            Button b = button(controls, (heard==i ? "Playing " : "Hear ") + (i==0 ? "A" : "B"), () -> listen(side), canListen && heard != i);
            LabStyle.tab(b, heard==i);
            b.setSelected(heard == i);
            b.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));
        }
        if (round != null && round.hidden()) {
            text(body,"Listen to both sides, then pick your favorite to reveal the EQs.",14);
            rowButtons("Prefer A","Prefer B",() -> vote(0),() -> vote(1),
                    !busy && round.heardMask==3,!busy && round.heardMask==3);
        } else if (round != null) {
            button(body,"New blind round",this::newRound,!busy);
        }
        if (audition) {
            button(body,"End & restore",this::endComparison,!busy);
            if (!fresh && !busy) {
                action(body,"Reconnect",this::refresh,true);
                action(body,"Leave current EQ on 5K…",this::leaveAudition,true);
            }
        } else if (address == null)
            text(body,"Connect your 5K from Device to listen. You can prepare both EQs offline.",14);
        else if (!fresh && !busy)
            action(body,"Refresh from 5K",this::refresh,true);
        else if (session != null && (selected != session.originalSelector || selected >= 42 || !session.confirmed.same(session.original)))
            text(body,"Load or restore a saved preset to establish your listening starting point.",14);
    }
    private void vote(int side) {
        round.pick(side); storeRound(); feedback=""; draw();
    }
    private void closeComparisonLink() {
        GaiaProbe.Connection link = comparisonLink;
        comparisonLink = null;
        if (link != null) try { link.close(); } catch (IOException ignored) {}
    }
    private void markAudition(boolean active) throws IOException {
        SharedPreferences.Editor edit = records.edit().putBoolean(key()+".audition",active);
        if (active) edit.putInt(address+".auditionGroup",group);
        else edit.remove(address+".auditionGroup");
        if (!edit.commit()) throw new IOException("Could not record comparison recovery.");
        audition = active;
    }
    private void listen(int i) {
        boolean normalize = records.getBoolean(matchKey(),true);
        work("Hear A/B side", g -> {
            ensureWrite();
            if (comparisonLink == null) comparisonLink = open(g);
            GaiaProbe.Connection link = comparisonLink;
            EqSnapshot start = snapshot(link);
            if (!audition && (PeqSession.selector(start,group) != session.originalSelector
                    || !PeqSession.block(start,group).same(session.original)))
                throw new IOException("The 5K changed. Refresh before comparing.");
            Peq other = (start.configuration[4+9*(1-group)] & 1) != 0 ? PeqSession.block(start,1-group) : null;
            PeqComparison comparison = new PeqComparison(pair[0],pair[1],session.original,other,normalize);
            if (!audition) markAudition(true);
            int source = round==null ? i : round.source(i);
            session.preview(link,start,comparison.side(source),this::persist);
            if (round!=null) { round.verified(i); storeRound(); }
            heard = i;
            return (i == 0 ? "A" : "B") + " verified on 5K" + ((round==null || !round.hidden()) && normalize ? Peq.fmt(" · %.1f dB trim", -comparison.attenuation[source]/10.0) : "") + ".";
        });
    }
    private void exitComparison() {
        round=null; storeRound();
        comparing = false;
        heard = -1;
        feedback = "";
        draw();
    }
    private void leaveAudition() {
        new AlertDialog.Builder(this).setTitle("Leave the current EQ on your 5K?")
                .setMessage("The original preset will no longer be restored by this comparison. Your saved slots and PEQ files stay available.")
                .setNegativeButton("Cancel",null).setPositiveButton("Leave current EQ",(d,w) -> {
                    try { markAudition(false); }
                    catch (IOException e) { note(e.getMessage()); return; }
                    closeComparisonLink();
                    clearWorkspace();
                    exitComparison();
                    refresh();
                }).show();
    }
    private void endComparison() {
        work("End and restore A/B", g -> {
            ensureWrite();
            closeComparisonLink();
            EqSnapshot before;
            try (GaiaProbe.Connection link = open(g)) {
                before = snapshot(link);
                session.restore(link,before);
            }
            ReversibleEqTest.settle();
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot after = snapshot(link);
                session.verifyTransition(before,after,session.original);
                if (PeqSession.selector(after,group) != session.originalSelector)
                    throw new IOException("The original preset selection needs verification.");
            }
            selected = session.originalSelector;
            session.confirmed = session.original.copy();
            session.pending = null;
            persist(session);
            markAudition(false);
            round=null; storeRound();
            fresh = true;
            heard = -1;
            comparing = false;
            return "Listening comparison ended. Original preset restored and verified.";
        });
    }
    @Override public void onBackPressed() {
        if (usbPage) { showUsb(false); return; }
        if (busy && !audio) return;
        if (audition) { note("End the comparison to restore your saved preset first."); return; }
        if (comparing) { exitComparison(); return; }
        super.onBackPressed();
    }
    private void chooseSave() {
        if(!fresh || busy || session==null || session.pending!=null || session.saveTarget>=0)return;
        String[] choices = new String[20];
        for (int i=0;i<choices.length;i++)
            choices[i]=String.format(java.util.Locale.ROOT,"%02d   %s%s",i+1,
                    names[i]==null?"Empty slot":names[i].isEmpty()?"Unnamed preset":names[i],selected==22+i?"   · Active":"");
        new AlertDialog.Builder(this)
                .setTitle("Save "+(group==0?"USR":"Speaker")+" EQ to slot")
                .setItems(choices, (d, i) -> confirmSave(22 + i))
                .setNegativeButton("Cancel", null)
                .show();
    }
    private void confirmSave(int target) {
        if(!fresh || busy || session==null)return;
        int i=target-22;
        if(names[i]==null) {
            nameDialog("Name "+(group==0?"USR":"Speaker")+" slot "+(i+1),"Custom #"+(i+1),"Save to 5K",name->{
                Peq.require(name.getBytes(StandardCharsets.UTF_8).length<=32,"Use at most 32 UTF-8 bytes for a 5K name.");
                save(target,false,name);
            });
            return;
        }
        Peq previous = savedVersion(target);
        String comparison = previous == null
                ? "Automatic history requires the saved slot to be loaded in the editor."
                : "The previous version will be kept on this phone.\n\n" + PresetDiff.describe(previous,draft);
        new AlertDialog.Builder(this)
                .setTitle("Overwrite saved preset?")
                .setMessage(stageLabels()[group] + " will be saved to " + slot(target - 22)
                        + ".\n\n" + comparison)
                .setPositiveButton("Save to 5K", (d, w) -> save(target,false,null,previous))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Peq savedVersion(int target) {
        if (!fresh || busy || address == null || session == null || session.pending != null || session.saveTarget >= 0
                || target < 22 || target >= 42 || names[target-22] == null || session.originalSelector != target
                || (selected != target && selected != 255) || (group == 1 && routingShown == null)
                || !session.original.parametric()) return null;
        return session.original.copy();
    }

    private void showPresetHistory(int target) {
        Peq previous = savedVersion(target);
        if (previous == null) return;
        try {
            List<PresetHistory.Version> versions = history.versions(key(),target,group);
            if (versions.isEmpty()) {
                note("No previous versions for this slot. The app keeps a copy before overwriting a loaded preset.");
                return;
            }
            DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT);
            String[] choices = new String[versions.size()];
            for (int i = 0; i < choices.length; i++) {
                PresetHistory.Version version = versions.get(i);
                choices[i] = format.format(new Date(version.time))
                        + (version.name.isEmpty() ? "" : " · " + version.name);
            }
            new AlertDialog.Builder(this).setTitle("History · "+slot(target-22))
                    .setItems(choices,(dialog,index)->reviewHistoricalVersion(target,previous,versions.get(index)))
                    .setNegativeButton("Close",null).show();
        } catch (IOException error) {
            note(error.getMessage());
        }
    }

    private void reviewHistoricalVersion(int target, Peq previous, PresetHistory.Version version) {
        if (!previous.same(savedVersion(target))) return;
        Peq restored;
        try {
            restored = PresetDiff.restore(previous,version.eq,group == 1
                    && version.swapped != Boolean.TRUE.equals(routingShown));
        } catch (IllegalArgumentException error) {
            note("This version contains an unsupported 5K filter.");
            return;
        }
        if (restored.same(previous)) {
            note("This version already matches the saved filters.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Restore this version?")
                .setMessage(slot(target-22)+" will keep its current name. This replaces the editor draft and saves the filters. "
                        +"The current version will be kept in History.\n\n"+PresetDiff.describe(previous,restored))
                .setPositiveButton("Restore to 5K",(dialog,index)->{
                    if (!previous.same(savedVersion(target))) return;
                    draft = restored;
                    document = null;
                    fileTitle = slot(target-22);
                    records.edit().remove(key()+".document").putString(key()+".title",fileTitle)
                            .putBoolean(key()+".staged",true).apply();
                    keepDraft();
                    save(target,false,null,previous);
                })
                .setNegativeButton("Cancel",null).show();
    }

    private void save(int target, boolean verifyOnly) { save(target,verifyOnly,null,null); }
    private void save(int target, boolean verifyOnly,String newName) { save(target,verifyOnly,newName,null); }

    private void save(int target, boolean verifyOnly,String newName,Peq reviewed) {
        if(!fresh || busy || session==null)return;
        final Peq wanted = verifyOnly ? session.confirmed.copy() : draft.copy();
        work("Save to preset slot", g -> {
            ensureWrite();
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot before = snapshot(link);
                if (!verifyOnly) {
                    int i = target - 22;
                    boolean named = (before.configuration[6 + 9 * group + i / 8] & (1 << (i % 8))) != 0;
                    if (named != (names[i] != null) || (named && !names[i].equals(slotName(link, group, i))))
                        throw new IOException("Destination slot changed. Refresh before saving.");
                    if (reviewed != null) {
                        session.guard(before);
                        int active = PeqSession.selector(before,group);
                        Peq saved = active == target ? PeqSession.block(before,group)
                                : active == 255 && session.originalSelector == target ? session.original : null;
                        if (!reviewed.same(saved))
                            throw new IOException("The saved EQ changed. Refresh it before overwriting.");
                        boolean swapped = group == 1 && link.channelsSwapped();
                        if (group == 1 && !Boolean.valueOf(swapped).equals(routingShown))
                            throw new IOException("The 5K channel routing changed. Refresh before saving.");
                        if (!reviewed.same(wanted))
                            history.remember(key(),target,names[i],reviewed,swapped);
                    }
                    session.preview(link, before, wanted, this::persist);
                    session.saveTarget = target;
                    session.newSlotName = named ? null : newName;
                    persist(session);
                    link.preset(group, target, true);
                }
            }
            ReversibleEqTest.settle();
            // Check active data first. A reload on a separate connection then proves the flash slot contains
            // it.
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot current = snapshot(link);
                if (PeqSession.selector(current, group) != target
                        || !PeqSession.block(current, group).same(wanted))
                    throw new IOException(
                            "Save could not be confirmed. Check the active preset before retrying.");
                if (session.newSlotName != null) {
                    int i = target - 22;
                    boolean named = (current.configuration[6 + 9 * group + i / 8] & (1 << (i % 8))) != 0;
                    String present=named?slotName(link,group,i):"";
                    if(!present.equals(session.newSlotName)) {
                        if(!present.isEmpty() && !present.equals("Custom #"+(i+1)))
                            throw new IOException("The destination name changed. Inspect the slot before retrying.");
                        link.nameSlot(group,i,session.newSlotName);
                    }
                }
                link.preset(group, target, false);
            }
            ReversibleEqTest.settle();
            try (GaiaProbe.Connection link = open(g)) {
                EqSnapshot after = snapshot(link);
                if (PeqSession.selector(after, group) != target || !PeqSession.block(after, group).same(wanted))
                    throw new IOException("The saved slot differs after reconnect.");
                if (session.newSlotName != null) {
                    String actualName = slotName(link, group, target - 22);
                    if (!actualName.equals(session.newSlotName))
                        throw new IOException("The slot was saved but its name needs verification.");
                    names[target - 22] = actualName;
                }
                selected = target;
                if (session.originalSelector != target) edits[group].clear();
                liveOnStage[group] = false;
                session = new PeqSession(group, target, wanted);
                records.edit().remove(key()+".staged").apply();
                draft = wanted.copy();
                records.edit().putString(key()+".reload",enc(wanted)).apply();
                persist(session);
                keepDraft();
            }
            fresh = true;
            return slot(target-22)+" saved and verified after reload.";
        });
    }
    private void closeAudioLink() {
        GaiaProbe.Connection link = audioLink;
        audioLink=null;
        if (link!=null) try { link.close(); } catch (IOException ignored) {}
    }
    private DeviceLevels audioReading(int generation) throws IOException {
        GaiaProbe.Connection cached = audioLink;
        if (cached != null) {
            try { return cached.levels(); }
            catch (IOException stale) { closeAudioLink(); }
        }
        GaiaProbe.Connection link=open(generation);
        audioLink=link;
        GaiaProbe.Device device=link.readDevice();
        detectedFirmware=device.model==1 ? device.version : null;
        return link.levels();
    }
    private void hardwareVolume(int target, DeviceLevels displayed) {
        if (volumeLocked) return;
        if (statusPoll && busy) {
            deferredAudioChange = () -> hardwareVolume(target, displayed);
            return;
        }
        work("Set hardware volume", g -> {
            DeviceLevels now = audioReading(g);
            GaiaProbe.Connection link=audioLink;
            if (link==null) throw new IOException("Audio connection closed.");
            levels = link.setCheckedVolume(displayed, now, target);
            return "Hardware volume verified.";
        });
    }
    private void draw() {
        if(eqMenu!=null)LabStyle.selectEqMenu(eqMenu,comparing ? LabStyle.BLIND : LabStyle.EQ);
        int nextGroup=audio || comparing ? -1 : pairedPage ? 2 : group;
        if(nextGroup>=0 && nextGroup==drawnEditorGroup)scroll.preservePosition();
        drawnEditorGroup=nextGroup;
        body.removeAllViews();
        androidLabel = null;
        androidBar = null;
        bluetoothAudioStatus = null;
        batteryEtaStatus = null;
        if (audio || comparing) {
            if(eqStages!=null)eqStages.hide();
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            body.addView(heading);
            TextView title = LabStyle.text(this, audio ? (usbPage ? "USB & microphone" : devicePage ? "Connection" : "Audio levels") : blind() ? "Blind A/B" : "Compare EQ",
                    24, LabStyle.INK, true);
            if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (comparing && !audition) action(heading, "Done", this::exitComparison, !busy);
        } else {
            if (pairedPage && recoveryInEitherStage()) pairedPage=false;
            eqStages.show(pairedPage ? 2 : group,this::selectEqStage);
            if (pairedPage && !routingPending() && !recoveryInEitherStage()) {
                drawPair();
                if (!feedback.isEmpty()) text(body,feedback,13);
                return;
            }
            boolean canStore=!busy && fresh && session!=null && session.pending==null && session.saveTarget<0 && draft.parametric() && !routingPending();
            int active = session == null ? selected : session.originalSelector;
            String preset = active >= 22 && active < 42 ? slot(active - 22)
                    : active == 255 ? "Unsaved device EQ" : active >= 0 ? "Factory #" + active : fileTitle;
            if(fresh && (selected==255 || session!=null && !draft.same(session.original)))preset+=" · Unsaved";
            CompanionEditor.presetName(body,preset);
            inlineStatus=new FilterTable.SyncStatus(this,"5K");
            body.addView(inlineStatus);
            if(verifiedInline && fresh && session!=null && draft.same(session.confirmed))inlineStatus.verified();
            else inlineStatus.message(fresh?"":"Draft on this phone");
            hardwareSave=CompanionEditor.presetActions(body,presetsOpen,!busy && !routingPending(),
                    ()->{presetsOpen=!presetsOpen;draw();},"Save to 5K",canStore,this::chooseSave,
                    !busy && !routingPending() && draft.parametric(),this::saveOnPhone);
            boolean filesReady=!busy && !routingPending() && draft.parametric();
            CompanionEditor.files(body,filesReady && (session==null || session.saveTarget<0),filesReady,()->file(true),()->file(false));


        }

        if(presetsOpen && !audio && !comparing && !routingPending())drawPresets();
        String shown = feedback;
        if (comparing && round != null && round.hidden() && feedback.startsWith("Paired Channel Match"))
            shown = "";
        if (!shown.isEmpty() && !shown.equals("Live EQ · Unsaved")) {
            message = LabStyle.text(this, shown, 13, LabStyle.MUTED, false);
            message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            if (audio) {
                message.setMaxLines(2);
                message.setEllipsize(android.text.TextUtils.TruncateAt.END);
                final String detail = shown;
                message.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage(detail)
                        .setPositiveButton("Close", null).show());
            }
            body.addView(message, -1, -2);
        }
        if (audio) {
            if (usbPage) drawUsbSettings();
            else drawLevels();
            return;
        }
        if (comparing) {
            drawComparison();
            return;
        }
        if (routingPending()) {
            addEditorBlock(channelRouting());
            return;
        }
        boolean editable = !busy && draft.parametric() && (session == null || session.saveTarget < 0);
        boolean ready = editable && fresh && session != null;
        EqEditHistory history = edits[group];
        if (session != null && session.saveTarget >= 0)
            button(body, "Verify save", () -> save(session.saveTarget, true), !busy && fresh);
        if (!fresh && session != null && session.pending != null)
            button(body, "Read 5K to recover live EQ", this::refresh, !busy && address != null);
        else if (fresh && session != null && session.pending != null)
            button(body, "Retry live EQ", this::applyLiveEq, !busy && session.saveTarget < 0);
        if (!draft.parametric()) {
            text(body, "Graphical EQ is active. Choose a parametric preset to edit filters.", 15);
            presetTools();
            return;
        }
        if(group==0){
            inlinePlot=CompanionEditor.graph(body,ResponseTraces.Stage.MAIN,graphInputs,new PeqResponse(draft,0));
        }else{
            inlinePlot=CompanionEditor.graph(body,ResponseTraces.Stage.MATCH,graphInputs,null,
                    new PeqResponse(draft,0),new PeqResponse(draft,1));
        }
        undoEditor=CompanionEditor.historyAction(this,"Undo",false,editable && history.canUndo(),()->historyEdit(true));
        redoEditor=CompanionEditor.historyAction(this,"Redo",true,editable && history.canRedo(),()->historyEdit(false));
        restoreEditor=compactButton("Restore saved device EQ",this::restore,canReset(),false);
        CompanionEditor.bandHeading(body,"Bands",null,undoEditor,redoEditor,editable,this::reloadStage,
                editable && draft.hasFilters(),this::clearFilters);
        for(int ear=0;ear<draft.channels;ear++){
            if(group==1 && !CompanionEditor.visibleEar(records,ear))continue;
            final int ch=ear;
            FilterTable.pre(body,group==0?"Preamp":ch==0?"Left preamp":"Right preamp",draft.pre(ch),editable,value->{
                Peq next=draft.copy();next.pre(ch,Peq.number(value,-24,24,10,"Preamp"));setInlineDraft(next);
            });
        }
        if(group==0) FilterTable.preampScope(body,true);
        if(group==1){
            drawMatches(editable);
            presetTools();
            addEditorBlock(settingButton(fresh && Boolean.TRUE.equals(routingShown)?"Restore L/R":"Swap L/R",this::showRouting,!busy));
            return;
        }
        boolean hasFilter=false;
        for(int i=0;i<10;i++)if(draft.type(channel,i)!=0){hasFilter=true;break;}
        if(hasFilter)FilterTable.headers(body);
        int free=-1;
        for(int i=0;i<10;i++){
            if(draft.type(channel,i)!=0)inlineBand(body,channel,i,editable);
            else if(free<0)free=i;
        }
        final int slot=free;
        if(slot>=0)addEditorBlock(settingButton("Add filter",()->{
            Peq next=draft.copy();next.band(channel,slot,5,1000,0,1024);setEditedDraft(next);
        },editable));
        presetTools();
    }
    private void presetTools(){
        if(draft.parametric() && !pairedPage && restoreEditor!=null && restoreEditor.getParent()==null)
            addEditorBlock(restoreEditor);
        Button tools=compactButton("Preset tools",null,!busy && !routingPending(),false);
        tools.setOnClickListener(this::editorTools);
        addEditorBlock(tools);
    }
    private void drawPresets(){
        text(body,"Device presets · "+stageLabels()[group],16);
        if(!fresh){text(body,"Connect your 5K to read stored presets.",14);drawPhonePresets();return;}
        boolean ready=!busy && session!=null && session.pending==null && session.saveTarget<0;
        int count=0;
        for(int i=0;i<names.length;i++)if(names[i]!=null){
            int target=22+i;count++;button(body,slot(i),()->choosePreset(target,slot(target-22)),ready);
        }
        if(count==0)text(body,"No saved custom presets in this bank.",14);
        android.widget.Switch toggle=new android.widget.Switch(this);toggle.setText("Factory presets");
        toggle.setChecked(showFactory);body.addView(toggle);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);
        int limit=group==0?PresetDirectory.FACTORY_NAMES.length:1;
        for(int i=0;i<limit;i++){
            final int target=i;String title=PresetDirectory.FACTORY_NAMES[i];
            button(list,title,()->choosePreset(target,title),ready);
        }
        list.setVisibility(showFactory?View.VISIBLE:View.GONE);
        toggle.setOnCheckedChangeListener((v,on)->{showFactory=on;list.setVisibility(on?View.VISIBLE:View.GONE);});
        drawPhonePresets();
    }
    private void drawPhonePresets(){
        String[] names=library.names();
        text(body,"Phone presets",16);
        if(names.length==0)text(body,"No paired presets saved on this phone.",14);
        for(String name:names)button(body,name,()->openPair(name),!busy && !routingPending());
    }
    private void choosePreset(int target,String title){
        Runnable load=()->work("Load device preset",generation->{
            ensureWrite();
            byte[] expected;
            try(GaiaProbe.Connection link=open(generation)){
                EqSnapshot before=snapshot(link);
                if(PeqSession.selector(before,group)!=selected || !PeqSession.block(before,group).same(session.confirmed))
                    throw new IOException("The active EQ changed. Refresh before loading.");
                if(target>=22 && !names[target-22].equals(slotName(link,group,target-22)))
                    throw new IOException("The slot name changed. Refresh before loading.");
                expected=before.configuration.clone();expected[3+9*group]=(byte)target;
                link.preset(group,target,false);
            }
            ReversibleEqTest.settle();
            try(GaiaProbe.Connection link=open(generation)){
                EqSnapshot after=snapshot(link);
                if(!Arrays.equals(expected,after.configuration))throw new IOException("The 5K did not confirm the selected preset. Refresh to check it.");
                Peq loaded=PeqSession.block(after,group);
                PeqSession next=new PeqSession(group,target,loaded);persist(next);
                records.edit().putString(key()+".reload",enc(loaded)).apply();
                session=next;selected=target;draft=loaded.copy();fresh=true;recent=after;
                fileTitle=title;document=null;edits[group].clear();liveOnStage[group]=false;
                records.edit().remove(key()+".document").remove(key()+".staged").putString(key()+".title",title).apply();
                keepDraft();presetsOpen=false;
                String paired=trackUsrSlot(after);
                return paired==null?"Loaded "+title:paired;
            }
        });
        if(selected==255 || records.getBoolean(key()+".staged",false))
            new AlertDialog.Builder(this).setTitle("Load "+title+"?").setMessage("This replaces the working EQ in this bank.")
                .setNegativeButton("Cancel",null).setPositiveButton("Load",(d,w)->load.run()).show();
        else load.run();
    }
    private void inlineBand(LinearLayout parent,int ear,int band,boolean enabled){
        FilterTable.row(parent,String.valueOf(band+1),FilterTable.TYPES,draft.type(ear,band),draft.hz(ear,band),draft.gain(ear,band),draft.q(ear,band),enabled,
            (type,hz,gain,q)->{Peq next=draft.copy();next.edit(ear,band,type,hz,gain,q);
                if(type==0)setEditedDraft(next);else setInlineDraft(next);},()->{
                Peq next=draft.copy();next.removeBand(ear,band);setEditedDraft(next);
            });
    }
    private void drawMatches(boolean editable) {
        int[] free={-1,-1},counts={0,0};
        for(int ear=0;ear<2;ear++)for(int i=0;i<10;i++){
            if(draft.type(ear,i)!=0)counts[ear]++;
            else if(free[ear]<0)free[ear]=i;
        }
        CompanionEditor.matches(body,records,Peq.fmt("Left %d/10   ·   Right %d/10",counts[0],counts[1]),editable,new CompanionEditor.Matches() {
            public int slots(int ear){return 10;}
            public boolean active(int ear,int i){return draft.type(ear,i)!=0;}
            public boolean canAdd(int lane){return free[lane]>=0;}
            public void add(int lane){Peq next=draft.copy();
                next.band(lane,free[lane],5,1000,0,1024);setEditedDraft(next);}
            public void table(LinearLayout into,int ear,int i){inlineBand(into,ear,i,editable);}
            public boolean sameShape(int i){
                return draft.type(0,i)==draft.type(1,i) && draft.hz(0,i)==draft.hz(1,i) && draft.q(0,i)==draft.q(1,i);
            }
            public void paired(CompanionEditor.MatchRow row,int i){
                FilterTable.pairedRow(row,String.valueOf(i+1),FilterTable.TYPES,draft.type(0,i),draft.hz(0,i),
                    draft.gain(0,i),draft.gain(1,i),draft.q(0,i),editable,(type,hz,left,right,q)->{
                        Peq next=draft.copy();
                        next.edit(0,i,type,hz,left,q);
                        next.edit(1,i,type,hz,right,q);
                        if(type==0)setEditedDraft(next);else setInlineDraft(next);
                    },scope->{
                        Peq next=draft.copy();
                        if(scope!=FilterTable.Removal.RIGHT)next.removeBand(0,i);
                        if(scope!=FilterTable.Removal.LEFT)next.removeBand(1,i);
                        setEditedDraft(next);
                    });
            }
        },this::draw);
    }

    private Button compactButton(String label, Runnable run, boolean enabled, boolean primary) {
        Button button = CompanionActions.buttonForSize(this, label, primary, run);
        button.setEnabled(enabled);
        return button;
    }

    private void addCompact(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() != 0) params.setMarginStart(dp(4));
        parent.addView(button, params);
    }

    private LinearLayout filterRow(LinearLayout parent, String[] values, boolean header) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (header) row.setBackgroundColor(LabStyle.PANEL);
        else
            row.setBackground(LabStyle.surface(this, LabStyle.BG, 0, 4));
        parent.addView(row, -1, dp(header ? 24 : 32));
        float[] widths = {.68f, .8f, 1.22f, 1f, 1.2f};
        for (int n = 0; n < values.length; n++) {
            TextView v = LabStyle.text(this, values[n], header ? 12 : 14,
                    header ? LabStyle.MUTED
                            : n == 1 ? LabStyle.BLUE
                                     : LabStyle.INK,
                    n == 1);
            if (n>0) {
                View rule=new View(this);
                rule.setBackgroundColor(LabStyle.LINE);
                row.addView(rule,1,-1);
            }
            v.setPaddingRelative(dp(4),0,dp(4),0);
            v.setGravity(n < 2 ? Gravity.START : Gravity.END);
            v.setFontFeatureSettings("tnum");
            row.addView(v, new LinearLayout.LayoutParams(0, -2, widths[n]));
        }
        View rule=new View(this);
        rule.setBackgroundColor(LabStyle.LINE);
        parent.addView(rule,-1,1);
        return row;
    }
    private void segments(String[] options, int selected, java.util.function.IntConsumer select) {
        LinearLayout row = new LinearLayout(this);
        row.setBackground(LabStyle.box(this, LabStyle.PANEL, LabStyle.LINE, 8));
        LinearLayout.LayoutParams strip = new LinearLayout.LayoutParams(-1, -2);
        strip.topMargin = dp(5);
        body.addView(row, strip);
        for (int n = 0; n < options.length; n++) {
            final int index = n;
            Button b = compactButton(options[n], () -> {
                if (index != selected) select.accept(index);
            }, !busy, false);
            LabStyle.markTab(b, n == selected);
            LinearLayout.LayoutParams segment = new LinearLayout.LayoutParams(0, -2, 1);
            if (n > 0) segment.setMarginStart(dp(4));
            row.addView(b, segment);
        }
    }
    private void changeGroup(int g) {
        group = g;
        channel = 0;
        feedback = "";
        loadWorkspace();
        draw();
        if (address != null)
            refresh();
    }
    private void changeChannel(int c) {
        channel = c;
        draw();
    }
    private void setInlineDraft(Peq next){
        if(draft.same(next))return;
        rememberStartingFilters();
        edits[group].record(draft,next);
        draft=next;verifiedInline=false;liveOnStage[group]=fresh;
        records.edit().putBoolean(key()+".staged",true).apply();keepDraft();
        if(group==0)inlinePlot.update(new PeqResponse(draft,0),new PeqResponse[0]);
        else inlinePlot.update(null,new PeqResponse[]{new PeqResponse(draft,0),new PeqResponse(draft,1)});
        if(!busy){
            updateInlineCommands();
            if(fresh && session!=null)applyLiveEq(true);
            else inlineStatus.message("Draft on phone");
        }
    }
    private void updateInlineCommands(){
        undoEditor.setEnabled(edits[group].canUndo());
        redoEditor.setEnabled(edits[group].canRedo());
        restoreEditor.setEnabled(canReset());
        hardwareSave.setTag(fresh && session!=null && session.saveTarget<0 && session.pending==null);
        hardwareSave.setEnabled(draft.parametric() && !routingPending());
    }
    private void setEditedDraft(Peq next) {
        if (draft.same(next)) return;
        rememberStartingFilters();
        edits[group].record(draft, next);
        draft = next;
        changed();
    }

    private void historyEdit(boolean undo) {
        rememberStartingFilters();
        draft = undo ? edits[group].undo(draft) : edits[group].redo(draft);
        changed();
    }

    private void changed() {
        verifiedInline=false;
        records.edit().putBoolean(key()+".staged",true).apply();
        keepDraft();
        feedback = fresh ? "Live EQ · Unsaved" : "Draft on phone";
        if (fresh && session != null && (!draft.same(session.confirmed) || session.pending != null)) applyLiveEq();
        else draw();
    }
    private void preamp() {
        LinearLayout box = form();
        EditText input = field(box, "Preamp (dB)", Double.toString(draft.pre(channel) / 10.0));
        formDialog("Edit preamp", box, () -> {
            Peq next = draft.copy();
            next.pre(channel, Peq.number(input.getText().toString(), -24, 24, 10, "Preamp"));
            setEditedDraft(next);
        });
    }
    private void edit(int b){edit(channel,b,-1);}
    private void edit(int ear,int b,int pairedRight) {
        LinearLayout box = form();
        text(box, "Filter type", 15);
        Spinner type = new Spinner(this, Spinner.MODE_DROPDOWN);
        type.setPopupBackgroundDrawable(LabStyle.box(this, LabStyle.PANEL, LabStyle.LINE, 10));
        type.setBackgroundTintList(android.content.res.ColorStateList.valueOf(LabStyle.BLUE));
        ArrayAdapter<String> choices = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_dropdown_item, FilterTable.TYPES) {
            @Override
            public View getDropDownView(int pos, View convert, android.view.ViewGroup parent) {
                TextView row = (TextView) super.getDropDownView(pos, convert, parent);
                row.setTextColor(LabStyle.INK);
                row.setBackground(LabStyle.surface(DeviceWorkbench.this,
                        pos == type.getSelectedItemPosition() ? LabStyle.ACTIVE : LabStyle.PANEL, 0, 4));
                return row;
            }
        };
        type.setAdapter(choices);
        type.setSelection(group==1 && draft.type(ear,b)==0 ? 5 : Math.min(5,draft.type(ear,b)));
        box.addView(type);
        EditText hz = field(box, "Frequency (Hz)", Integer.toString(draft.hz(ear, b)));
        editSlider(box, hz, "Frequency", 10, 22000, true, 0);
        EditText gain = field(box, "Gain (dB)", Double.toString(draft.gain(ear, b) / 10.0));
        editSlider(box, gain, "Gain", -50, 50, false, 1);
        EditText q = field(box, "Q", Peq.fmt("%.8f", draft.q(ear, b) / 1024.0));
        editSlider(box, q, "Q", 0.01, 15, true, 3);
        formDialog(
                "Filter " + (b + 1) + (group == 1 ? (pairedRight>=0?" · Both":ear == 0 ? " · Left" : " · Right") : ""), box, () -> {
                    Peq next = draft.copy();
                    next.edit(ear, b, type.getSelectedItemPosition(), hz.getText().toString(),
                            gain.getText().toString(), q.getText().toString());
                    if(pairedRight>=0)next.band(1,pairedRight,next.type(ear,b),next.hz(ear,b),next.gain(ear,b),next.q(ear,b));
                    setEditedDraft(next);
                });
    }
    private void editSlider(LinearLayout parent, EditText field, String label, double min, double max,
            boolean log, int digits) {
        SeekBar slider = new SeekBar(this);
        slider.setMax(1000);
        slider.setContentDescription(label + " adjustment");
        LabStyle.slider(slider, LabStyle.BLUE);
        parent.addView(slider, -1, dp(38));
        Runnable follow = () -> {
            try {
                double val = Double.parseDouble(field.getText().toString());
                double t = log ? Math.log(Math.max(min, val) / min) / Math.log(max / min)
                               : (val - min) / (max - min);
                slider.setProgress((int) Math.round(1000 * Math.max(0, Math.min(1, t))));
            } catch (NumberFormatException ignored) {
            }
        };
        follow.run();
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                follow.run();
            }
            public void afterTextChanged(android.text.Editable text) {}
        });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int progress, boolean user) {
                if (!user)
                    return;
                double t = progress / 1000.0;
                double val = log ? min * Math.pow(max / min, t) : min + t * (max - min);
                field.setText(Peq.fmt("%." + digits + "f", val));
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });
    }
    private void formDialog(String title, LinearLayout box, Runnable apply) {
        ScrollView fields = new ScrollView(this);
        fields.addView(box);
        AlertDialog d = new AlertDialog.Builder(this)
                                .setTitle(title)
                                .setView(fields)
                                .setPositiveButton("Apply EQ", null)
                                .setNegativeButton("Cancel", null)
                                .create();
        d.setOnShowListener(x -> d.getButton(-1).setOnClickListener(v -> {
            try {
                apply.run();
                d.dismiss();
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        d.show();
    }
    private LinearLayout form() {
        LinearLayout box = column();
        box.setPadding(dp(20), 0, dp(20), 0);
        return box;
    }
    private EditText field(LinearLayout box, String label, String value) {
        TextView caption = text(box, label, 15);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setText(value);
        box.addView(field);
        return field;
    }
    private void file(boolean importing) { file(importing ? IMPORT : EXPORT); }
    private void file(int request) {
        boolean importing = request != EXPORT;
        Intent i = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                           .addCategory(Intent.CATEGORY_OPENABLE);
        if (importing)
            i.setType("*/*");
        else {
            try {
                exportData = mapFileChannels(draft).exportText();
            } catch (IllegalArgumentException e) {
                note(e.getMessage());
                return;
            }
            i.setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, fileTitle + ".txt");
        }
        try {
            readingFile = true;
            startActivityForResult(i, request);
        } catch (android.content.ActivityNotFoundException e) {
            readingFile = false;
            note("Android’s file picker is unavailable.");
        }
    }
    @Override
    protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (TuningActivity.originalResult(this,request,result,intent)) return;
        if (pairFiles.result(request,result,intent,worker,this::importPair) || graphInputs.result(request,result,intent)) return;
        readingFile = false;
        if (result != RESULT_OK || intent == null || intent.getData() == null) {
            busy = false;
            exportData = null;
            draw();
            return;
        }
        if (request != IMPORT && request != EXPORT && request != SIDE_A && request != SIDE_B)
            return;
        android.net.Uri uri = intent.getData();
        Peq base = draft.copy();
        long started = android.os.SystemClock.elapsedRealtime();
        String file = exportData;
        exportData = null;
        readingFile = true;
        busy = true;
        draw();
        worker.execute(() -> {
            Peq imported = null;
            String resultMessage;
            String providerName=null;
            try {
                if (request != EXPORT) {
                    imported = mapFileChannels(readFile(uri, mapFileChannels(base)));
                    resultMessage = "PEQ file loaded into the editor.";
                } else {
                    if (file == null)
                        throw new IOException("Select Export PEQ again.");
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null)
                            throw new IOException("File unavailable.");
                        output.write(file.getBytes(StandardCharsets.UTF_8));
                    }
                    resultMessage = "PEQ file exported.";
                }
                providerName=displayName(uri);
                if (request==IMPORT && base.group==0)
                    library.linkedDocument(uri.toString(),stem(providerName),imported);
            } catch (Exception e) {
                log.record("EQ "+(request==EXPORT ? "export" : "import")+" failed · "+e.getClass().getSimpleName());
                resultMessage = "File operation failed. " + e.getMessage();
            }
            Peq resultDraft = imported;
            String outcome = resultMessage;
            String resolvedName=providerName;
            long elapsed = android.os.SystemClock.elapsedRealtime() - started;
            ui.post(() -> {
                if (isDestroyed())
                    return;
                if (resultDraft != null && (request == SIDE_A || request == SIDE_B)) {
                    captureSide(request - SIDE_A, resultDraft, stem(resolvedName));
                } else if (resultDraft != null) {
                    bindDocument(uri, intent.getFlags(), resolvedName);
                    edits[group].record(draft, resultDraft);
                    draft = resultDraft;
                    records.edit().putString(key()+".reload",enc(resultDraft)).apply();
                    liveOnStage[group] = address != null;
                    records.edit().putBoolean(key()+".staged",true).apply();
                    keepDraft();
                }
                readingFile = false;
                busy = false;
                if (request == EXPORT && outcome.equals("PEQ file exported.")){
                    bindDocument(uri, intent.getFlags(), resolvedName);
                    records.edit().putString(key()+".reload",enc(draft)).apply();
                }
                String linked = request==IMPORT && group==0 && resultDraft!=null ? recallCompanion(uri.toString()) : null;
                note(linked!=null ? linked : (request == SIDE_A || request == SIDE_B) && resultDraft != null ? "Comparison EQ loaded." : outcome);
                if (visible && address != null)
                    refresh();
            });
            log.timing(request == EXPORT ? "Export PEQ file" : "Read PEQ file", elapsed,
                    outcome.startsWith("File operation failed") ? "failed" : "complete");
        });
    }
    private Peq readFile(android.net.Uri uri, Peq base) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null)
                throw new IOException("File unavailable.");
            byte[] chunk = new byte[2048];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (buffer.size() + n > 65536)
                    throw new IOException("Choose a PEQ file smaller than 64 KB.");
                buffer.write(chunk, 0, n);
            }
        }
        String value = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        if (value.trim().startsWith("{")) {
            JSONObject json = new JSONObject(value);
            if (!"yinyang-eq-qudelix-active-eq-v1".equals(json.getString("format"))
                    || json.getInt("deviceModelId") != 1 || !"3.2.9".equals(json.getString("firmware"))
                    || !"USR/SPK".equals(json.getString("mode")))
                throw new IOException("Unsupported EQ snapshot.");
            Peq source = new Peq(base.group,
                    Base64.getDecoder().decode(
                            json.getString(base.group == 0 ? "usrPresetBase64" : "spkPresetBase64")));
            value = source.exportText();
        }
        return base.importText(value);
    }
    private void note(String s) {
        feedback = s;
        draw();
    }

    private static final boolean MEASUREMENT_MODE_ENABLED = false;

    private String usbBackupKey() { return address + ".usb711.original"; }

    private void showUsb(boolean show) {
        if (busy) return;
        closeAudioLink();
        usbPage = show;
        usbSettings = null;
        feedback = "";
        draw();
        refresh();
    }

    private void drawUsbSettings() {
        UsbAudioSettings current = usbSettings;
        boolean saved = records.contains(usbBackupKey());
        boolean available = !busy && current != null;
        segments(new String[]{"USB", "Microphone"}, usbPanel, panel -> {
            usbPanel = panel;
            draw();
        });
        if (current == null) {
            text(body, address == null ? "Connect your 5K from Device." : "Read USB and microphone settings from your 5K.", 14);
        } else if (usbPanel == 0) {
            addEditorBlock(settingButton("Sample rate · " + UsbAudioSettings.RATES[current.rate], this::pickRate, available && !saved));
            addEditorBlock(settingButton(automaticUsb() ? "Switching · Automatic" : "Switching · Manual only",
                    this::chooseSwitching, available));
            if (!consoleStatus.isEmpty()) text(body, consoleStatus, 14);
            usbModes(current.platform,available && !saved && !automaticUsb());
            text(body, "USB playback supports up to 24-bit. The host and USB mode set the active rate and bit depth.", 14);
        } else {
            addEditorBlock(settingButton("Mic source · " + (current.source == 1 ? "Earphone inline" : "5K built-in"),
                    () -> pickUsb("Mic source", new String[]{"5K built-in mic", "Earphone inline mic"},
                            new int[]{0, 1}, UsbAudioSettings.Field.SOURCE), available && !saved));
            addEditorBlock(settingButton("Mic gain · " + current.gain, () -> {
                String[] labels = new String[23];
                int[] values = new int[23];
                for (int i = 0; i < labels.length; i++) { values[i] = i; labels[i] = Integer.toString(i); }
                pickUsb("Mic gain", labels, values, UsbAudioSettings.Field.GAIN);
            }, available));
            addEditorBlock(settingButton("Music mic pass-through · " + (current.music == 1 ? "On" : "Off"),
                    () -> pickUsb("Music mic pass-through", new String[]{"Off", "On"}, new int[]{0, 1},
                            UsbAudioSettings.Field.MUSIC), available && !saved));
            addEditorBlock(settingButton("Voice mic pass-through · " + (current.voice == 1 ? "On" : "Off"),
                    () -> pickUsb("Voice mic pass-through", new String[]{"Off", "On"}, new int[]{0, 1},
                            UsbAudioSettings.Field.VOICE), available && !saved));
        }
        if (saved) addEditorBlock(settingButton("Restore previous USB and mic settings", () -> confirmUsbMode(true), available));
        addEditorBlock(CompanionActions.row(this,
                settingButton("Back to Audio", () -> showUsb(false), !busy),
                settingButton("Read 5K", this::refresh, !busy && address != null)));
    }

    private Button settingButton(String label, Runnable run, boolean enabled) {
        return compactButton(label, run, enabled, false);
    }

    private void chooseSwitching() {
        new AlertDialog.Builder(this).setTitle("USB profile switching").setItems(new String[]{"Manual only","Automatic · follow my PS5"},(d,i)->{
            if(i==0) {
                records.edit().remove(consoleKey()).apply();consoleStatus="Automatic switching is off.";draw();return;
            }
            EditText input=new EditText(this);input.setSingleLine(true);input.setHint("PS5 local IPv4 address");
            input.setText(records.getString(consoleKey(),""));
            new AlertDialog.Builder(this).setTitle("Follow PS5 power while Audio is open")
                .setMessage("Use PS5 mode when this console replies awake and the 5K has a USB host. Use regular DAC mode for standby or a USB-connected phone. The console’s power state cannot identify which machine the 5K cable is plugged into. Switching may interrupt audio.")
                .setView(input).setNegativeButton("Cancel",null).setPositiveButton("Enable",(dialog,w)->{
                    try {
                        String ip=ConsolePresence.localAddress(input.getText().toString()).getHostAddress();
                        records.edit().putString(consoleKey(),ip).apply();nextConsoleCheck=0;
                        consoleStatus="Waiting for console status.";draw();
                    }catch(IOException e){note(e.getMessage());}
                }).show();
        }).setNegativeButton("Close",null).show();
    }
    private void usbModes(int selected,boolean enabled){
        CompanionActions.UsbModes modes=new CompanionActions.UsbModes(this,this::choosePlatform);
        modes.update(selected,enabled);addEditorBlock(modes);
    }
    private void choosePlatform(int before,int target){
        if(before==target || !UsbMode.supported(before) || !UsbMode.supported(target))return;
        String chosen=UsbMode.values()[target].label;
        new AlertDialog.Builder(this).setTitle("Switch USB mode to "+chosen+"?")
                .setMessage("USB audio may briefly disconnect. PlayStation mode can change how host volume is handled.")
                .setPositiveButton("Switch",(d,w)->{
                    Runnable change=()->work("Switch USB mode",g->switchPlatform(g,before,target));
                    if(busy && statusPoll)deferredAudioChange=change;else change.run();
                }).setNegativeButton("Cancel",null).show();
    }
    private String switchPlatform(int generation,int original,int target) throws Exception {
        closeAudioLink();
        try(GaiaProbe.Connection link=open(generation)) {
            if(records.contains(usbBackupKey())) throw new IOException("Restore the previous USB settings before switching platforms.");
            if(link.usbAudio().platform!=original) throw new IOException("USB platform changed. Read the settings again.");
            link.checkUsbRestart();
            try {link.setUsbPlatform(target);} catch(IOException reconnecting){ /* Verify uncertain delivery on a fresh link. */ }
        }
        for(int retry=0;retry<10;retry++) {
            Thread.sleep(850);
            synchronized(transportLock){if(epoch!=generation)throw new IOException("USB platform check interrupted.");}
            try(GaiaProbe.Connection link=open(generation)) {
                UsbAudioSettings actual=link.usbAudio();
                if(actual.platform==target){
                    if(!usbPage)outputSpecs=link.outputSpecifications();
                    usbSettings=actual;return "Verified USB mode · "+UsbMode.values()[target].label;
                }
            } catch(IOException reconnecting) { }
        }
        throw new IOException("Could not verify the new USB platform. Read the 5K again.");
    }
    private void pollConsole() {
        nextConsoleCheck=android.os.SystemClock.elapsedRealtime()+20000;
        final String host=records.getString(consoleKey(),null);
        if(host==null)return;
        work("Automatic USB platform check", g->{
            boolean phoneHost=false;
            for(android.media.AudioDeviceInfo d:getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                if(isUsbAudioDevice(d) && d.getProductName().toString().toLowerCase(java.util.Locale.ROOT).contains("qudelix"))phoneHost=true;
            ConsolePresence.State state=phoneHost ? ConsolePresence.State.UNKNOWN : ConsolePresence.query(host);
            DeviceLevels reading=audioReading(g);
            levels=reading;
            UsbAudioSettings current=audioLink.usbAudio();
            usbSettings=current;
            int target=ConsolePresence.target(state,phoneHost,reading.usbEnumerated);
            consoleStatus=phoneHost ? "This phone hosts the USB DAC." : state==ConsolePresence.State.AWAKE ? "PS5 reports awake."
                    : state==ConsolePresence.State.STANDBY ? "PS5 reports standby." : "Console unavailable. Keeping current USB profile.";
            if(target<0 || target==current.platform || current.platform==2 || !host.equals(records.getString(consoleKey(),null))) return consoleStatus;
            if(reading.playing || reading.call) return consoleStatus+" Waiting for idle audio to switch.";
            return consoleStatus+" "+switchPlatform(g,current.platform,target);
        });
    }
    private void pickRate() {
        int[] values = usbSettings.platform == 0 ? new int[]{0,1,2,3,4,5,6} : new int[]{2,3,5,6};
        String[] labels = new String[values.length];
        for (int i=0; i<values.length; i++) labels[i]=UsbAudioSettings.RATES[values[i]];
        pickUsb("USB sample rate",labels,values,UsbAudioSettings.Field.RATE);
    }

    private void pickUsb(String title,String[] labels,int[] values,UsbAudioSettings.Field field) {
        final UsbAudioSettings shown=usbSettings;
        int selected=-1;
        for (int i=0; i<values.length; i++) if (values[i]==shown.value(field)) selected=i;
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels,selected,(dialog,index)->{
            dialog.dismiss();
            int value=values[index];
            if (shown.value(field)==value) return;
            Runnable apply=() -> changeUsb(shown,field,value,false);
            if (field==UsbAudioSettings.Field.RATE)
                new AlertDialog.Builder(this).setTitle("Change USB profile?").setMessage("USB audio may disconnect while the 5K restarts.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Change", (d,w)->apply.run()).show();
            else apply.run();
        }).setNegativeButton("Cancel",null).show();
    }

    private void confirmUsbMode(boolean restore) {
        if (!restore && !MEASUREMENT_MODE_ENABLED) return;
        final UsbAudioSettings shown=usbSettings;
        new AlertDialog.Builder(this).setTitle(restore ? "Restore previous settings?" : "Enable 711 measurement mode?")
            .setMessage(restore ? "Restore the saved USB profile, mic source, gain and pass-through settings. USB audio may reconnect."
                    : "Switch to 48 kHz with the inline mic and turn off mic pass-through. The app saves your current settings first. USB audio may reconnect.")
            .setNegativeButton("Cancel",null).setPositiveButton(restore ? "Restore" : "Enable",(d,w)->changeUsb(shown,null,0,restore)).show();
    }

    private void changeUsb(UsbAudioSettings shown,UsbAudioSettings.Field edit,int value,boolean restore) {
        if (edit == null && !restore && !MEASUREMENT_MODE_ENABLED) return;
        work("Change USB and microphone settings", g -> {
            closeAudioLink();
            UsbAudioSettings target;
            try (GaiaProbe.Connection link=open(g)) {
                UsbAudioSettings now=link.usbAudio();
                if (now.packed()!=shown.packed()) throw new IOException("Settings changed. Read the 5K before trying again.");
                boolean saved=records.contains(usbBackupKey());
                if (restore && !saved) throw new IOException("No saved USB settings for this device.");
                if (saved && UsbAudioSettings.unpack(records.getInt(usbBackupKey(),-1)).platform != now.platform)
                    throw new IOException("Return to the original USB platform to restore the saved settings.");
                target=restore ? UsbAudioSettings.unpack(records.getInt(usbBackupKey(),-1))
                        : edit==null ? now.measurement() : now.with(edit,value);
                if (now.rate!=target.rate) link.checkUsbRestart();
                if (!restore && edit==null && !saved && !records.edit().putInt(usbBackupKey(),now.packed()).commit())
                    throw new IOException("Could not save the original USB settings.");
                now.applyMicChanges(target,(field,setting) -> link.usbField(now,field,setting));
                if (now.rate!=target.rate) {
                    try { link.usbField(now,UsbAudioSettings.Field.RATE,target.rate); }
                    catch (IOException lostDuringRestart) { /* Resolve uncertain delivery by read-back only. */ }
                }
            }
            for (int attempt=0; attempt<10; attempt++) {
                Thread.sleep(attempt==0 ? 450 : 800);
                synchronized (transportLock) { if (epoch!=g) throw new IOException("USB settings check interrupted."); }
                UsbAudioSettings actual;
                try (GaiaProbe.Connection check=open(g)) {
                    actual=check.usbAudio();
                } catch (IOException reconnecting) {
                    continue;
                }
                if (actual.packed()!=target.packed()) continue;
                if (restore && !records.edit().remove(usbBackupKey()).commit())
                    throw new IOException("Settings restored. Retry to clear the saved recovery record.");
                usbSettings=actual;
                return restore ? "Original USB and mic settings restored and verified after reconnect."
                        : "5K settings verified after reconnect.";
            }
            throw new IOException("Could not verify the requested settings. Read the 5K again.");
        });
    }

    private void drawLevels() {
        if(devicePage){drawAudioDevice();return;}
        drawVolume();
        drawOutput();
        AndroidAudioOptions androidAudio=new AndroidAudioOptions(this,false,address);
        if(levels!=null)androidAudio.updateStreamFormat(levels.playingBluetooth(),levels.sampleRate,levels.bitDepth);
        addEditorBlock(androidAudio);
    }

    private TextView bluetoothAudioStatus;
    private void drawAudioDevice() {
        DeviceLevels reading = levels;
        bluetoothAudioStatus=text(body,BluetoothPages.linkedStatus(this,address,false),15);
        addEditorBlock(settingButton("Connect",()->startActivity(new Intent(this,ProbeActivity.class).putExtra("connectRadio",true)),!busy));
        if(binding()!=null){
            DeviceProfile profile=binding().profile;
            text(body,profile.model.label,15);
            text(body,profile.bluetoothMacLabel(),14).setTextIsSelectable(true);
        }
        addEditorBlock(settingButton("Bluetooth & devices",()->DeviceBinding.manage(this),!busy || statusPoll));
        if(reading!=null && audioLink!=null && !disconnected){
            addEditorBlock(settingButton("Turn off automatic pairing",()->{},false));
            text(body,"The companion cannot change Bluetooth pairing mode on the original 5K. Use the pairing controls on the 5K itself.",14);
        }
        CompanionActions.sectionTitle(body,"Device status");
        text(body, reading == null ? "5K audio status unavailable" : reading.stream, 15);
        String battery = reading != null && reading.battery <= 100 ? reading.battery + "% · " + reading.charging : "Unavailable";
        text(body, "Battery · "+battery, 15);
        batteryEtaStatus=text(body,reading==null?BatteryEstimate.UNAVAILABLE:batteryEta,15);
        addEditorBlock(settingButton("Refresh 5K status", this::refresh, !busy && address != null));
        addButtonLayoutPreview(body, false);
        LabStyle.divider(this,body);
        CompanionActions.FirmwareEntry firmware = new CompanionActions.FirmwareEntry(this, this::showFirmwareInfo);
        firmware.setVersion(detectedFirmware);
        firmware.setEnabled(!busy);
        addEditorBlock(firmware);
        if(availableFirmware!=null)text(body,"Available in the installed Qudelix app · "+availableFirmware,14);
        Switch notice=new Switch(this);notice.setText("Notify when newer firmware is available");LabStyle.toggle(notice);
        notice.setChecked(records.getBoolean("firmwareNotices",true));
        notice.setOnCheckedChangeListener((v,on)->{records.edit().putBoolean("firmwareNotices",on).remove("noticedFirmware").apply();if(on)checkFirmware();});
        addEditorBlock(notice);
        addEditorBlock(settingButton("Open Qudelix app",this::openOfficialController,true));
        checkFirmware();

    }

    private void checkFirmware(){
        if(!visible || detectedFirmware==null || !detectedFirmware.matches("[0-9]+[.][0-9]+[.][0-9]+") || availableFirmware==null
                || !records.getBoolean("firmwareNotices",true) || availableFirmware.equals(records.getString("noticedFirmware",""))
                || ControllerFirmware.compare(availableFirmware,detectedFirmware)<=0)return;
        records.edit().putString("noticedFirmware",availableFirmware).apply();
        new AlertDialog.Builder(this).setTitle("Firmware "+availableFirmware+" available")
                .setPositiveButton("Open Qudelix",(d,w)->openOfficialController()).setNegativeButton("Later",null).show();
    }
    private void drawOutput() {
        DeviceLevels reading = levels;
        DacSettings dac = dacSettings;
        boolean canChange = !busy && reading != null && !reading.call && dac != null && dac.supported();
        CompanionActions.sectionTitle(body,"Output");
        addEditorBlock(CompanionActions.heading(this, "Amplifier gain"));
        dacChoices("Normal gain", "High gain", dac == null ? -1 : dac.gain,
                choice -> changeDac(dac, DacSettings.Field.GAIN, choice), canChange);
        text(body,"Normal gain suits sensitive earphones. High gain provides more output voltage for harder-to-drive headphones.",14);
        addEditorBlock(AndroidAudioOptions.guide(this,true,false));
        addEditorBlock(CompanionActions.heading(this, "DAC profile"));
        dacChoices("Standard", "Performance", dac == null ? -1 : dac.profile,
                choice -> changeDac(dac, DacSettings.Field.PROFILE, choice), canChange);
        if (dac != null && dac.profile == 0 && dac.effectiveProfile == 1)
            text(body, "USB power is using Performance.", 13).setTextColor(LabStyle.MUTED);
        text(body,"Standard saves battery. Performance prioritizes DAC performance and uses more power.",14);
        addEditorBlock(AndroidAudioOptions.guide(this,false,false));
        CompanionActions.sectionTitle(body,"Inputs");
        int priority = reading == null ? -1 : reading.priority;
        String[] inputs = DeviceLevels.inputNames();
        addEditorBlock(CompanionActions.heading(this,"Preferred input"));
        if(reading!=null)text(body,"Active input · "+reading.activeInput(),14);
        LinearLayout inputRow=new LinearLayout(this);
        boolean canSelect=reading!=null && priority>=0 && !reading.call && (!busy || statusPoll);
        for(int input:new int[]{1,2,0}){
            Button button=settingButton(inputs[input],()->changeInputPriority(priority,input),
                    canSelect && (input!=0 || reading.usbEnumerated));
            LabStyle.tab(button,priority==input);
            button.setSelected(priority==input);
            inputRow.addView(button,new LinearLayout.LayoutParams(0,-2,1));
        }
        addEditorBlock(inputRow);
        text(body,"The 5K uses the preferred input when that source sends audio.",14);
        boolean automatic=automaticUsb();
        usbModes(reading==null?-1:UsbAudioSettings.platform(outputSpecs),
                reading!=null && !reading.call && (!busy || statusPoll) && !automatic && !records.contains(usbBackupKey()));
        if(automatic)text(body,"Automatic PS5 switching is on. Choose Manual only in USB & microphone to select a mode.",13);
        addEditorBlock(settingButton("USB & microphone", () -> showUsb(true), !busy && reading != null));
        if (dac == null && !busy && address != null) addEditorBlock(settingButton("Read output settings", this::refresh, true));
    }

    private void dacChoices(String first, String second, int selected, java.util.function.IntConsumer change, boolean enabled) {
        LinearLayout choices = LabStyle.switcher(this,
                settingButton(first, () -> change.accept(0), enabled),
                settingButton(second, () -> change.accept(1), enabled), selected);
        body.addView(choices, -1, -2);
    }

    private void changeDac(DacSettings shown, DacSettings.Field field, int choice) {
        if (shown == null || shown.value(field) == choice) return;
        if (statusPoll && busy) {
            deferredAudioChange = () -> changeDac(shown, field, choice);
            return;
        }
        boolean profile = field == DacSettings.Field.PROFILE;
        work(profile ? "Set DAC profile" : "Set amplifier gain", g -> {
            DeviceLevels before = audioReading(g);
            if (before.call) throw new IOException("Wait for the call to end before switching output settings.");
            GaiaProbe.Connection link = audioLink;
            if (link == null) throw new IOException("Audio connection closed.");
            dacSettings = link.setDacSetting(shown, field, choice);
            levels = link.levels();
            return profile ? "DAC profile verified." : "Amplifier gain verified.";
        });
    }

    private void updateSignal() {
        if(compactSignal==null) return;
        DeviceLevels l=levels;
        compactSignal.update(l==null?Double.NaN:l.leftRms,l==null?Double.NaN:l.rightRms,
                dacSettings==null?Double.NaN:dacSettings.nominalMillivolts(l,outputSpecs),spec(130,14),spec(144,11));
    }

    private double spec(int bit,int width) {
        return outputSpecs==null ? 0 : UsbAudioSettings.bits(outputSpecs,bit,width)/10.0;
    }
    private void drawVolume() {
        DeviceLevels reading = levels;
        LinearLayout volume = LabStyle.card(this, body);
        TextView hwLabel = text(volume,
                reading == null ? "Hardware volume"
                                : Peq.fmt("Hardware volume · %.1f dB · %.0f%%", reading.volume / 60.0, (reading.volume-reading.limit+3600)/36.0),
                19);
        FilledVolumeBar hardware = new FilledVolumeBar(this,LabStyle.BLUE);
        hardware.hardware(reading==null?0:reading.limit-3600,reading!=null);
        hardware.setContentDescription("Qudelix hardware volume");
        hardware.setMax(120);
        if (reading != null)
            hardware.setProgress((reading.volume - reading.limit + 3600) / 30);
        hardware.setEnabled(!volumeLocked && !busy && reading != null && reading.adjustable());
        hardware.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {
                dragging = true;
            }
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user && reading != null && compactSignal!=null)compactSignal.previewGain((reading.limit-3600+30*p-reading.volume)/60.0);
                if (user && reading != null)
                    hwLabel.setText(
                            Peq.fmt("Hardware volume · %.1f dB", (reading.limit - 3600 + 30 * p) / 60.0)+(compactSignal==null?"":compactSignal.sliderEstimate()));
                if (user && !dragging && (!busy || statusPoll))
                    hardwareVolume(reading.limit - 3600 + 30 * p, reading);
            }
            public void onStopTrackingTouch(SeekBar b) {
                dragging = false;
                compactSignal.previewGain(0);
                hardwareVolume(reading.limit - 3600 + 30 * b.getProgress(), reading);
            }
        });
        volumeRow(volume, hardware, "hardware",
                (!busy || statusPoll) && reading != null && reading.adjustable() && reading.volume > reading.limit - 3600,
                (!busy || statusPoll) && reading != null && reading.adjustable() && reading.volume < reading.limit,
                delta -> hardwareVolume(reading.volume + delta * 30, reading));
        scale(volume, reading == null ? "−60 dB range" : Peq.fmt("%.0f dB", (reading.limit - 3600) / 60.0),
                reading == null ? "Device limit" : Peq.fmt("Limit %.1f dB", reading.limit / 60.0));
        LabStyle.divider(this, volume);
        androidLabel = text(volume, "Android media volume", 19);
        androidBar = new FilledVolumeBar(this,LabStyle.CORAL);
        androidBar.setContentDescription("Android media volume");
        AudioManager phone = getSystemService(AudioManager.class);
        volumeRow(
                volume, androidBar, "Android media", !phone.isVolumeFixed(), !phone.isVolumeFixed(), delta -> {
                    try {
                        phone.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                                delta > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                    } catch (SecurityException ignored) {
                    }
                    phoneVolume();
                });
        scale(volume, "Quiet", "Loud");
        compactSignal = new SignalMonitor(this); compactSignal.nominal=true; volume.addView(compactSignal); updateSignal();
        TextView summary=LabStyle.text(this,reading==null?"Connect your 5K in Device to see live levels.":reading.stream+"\n"+Peq.fmt("Total %+.2f dB   ·   Battery %d%%",reading.sourceDb+reading.volume/60.0,reading.battery)+(dacSettings==null?"":"\n"+dacSettings.filterName()),16,LabStyle.INK,true);
        volume.addView(summary,0);
        if(reading!=null){
            batteryEtaStatus=LabStyle.text(this,batteryEta,14,LabStyle.MUTED,false);
            volume.addView(batteryEtaStatus,1);
        }
        Switch lock = new Switch(this);
        LabStyle.toggle(lock);
        lock.setText("Lock volume sliders");
        lock.setChecked(volumeLocked);
        lock.setOnCheckedChangeListener((view, checked) -> { volumeLocked = checked; draw(); });
        volume.addView(lock);

        phoneVolume();
        androidBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {
                dragging = true;phoneDragging=true;phoneVolume();
            }
            public void onStopTrackingTouch(SeekBar b) {
                dragging = false;phoneDragging=false;
                phoneVolume();
            }
            public void onProgressChanged(SeekBar b, int p, boolean user) {
                if (user) {
                    try {
                        getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
                    } catch (SecurityException ignored) {
                    }
                    phoneVolume();
                }
            }
        });
    }

    private void changeInputPriority(int shown, int choice) {
        if (statusPoll && busy) {
            deferredAudioChange = () -> changeInputPriority(shown,choice);
            return;
        }
        work("Set input priority", g -> {
            DeviceLevels before = audioReading(g);
            if (choice==0 && !before.usbEnumerated) return "Connect a USB audio source to the 5K first.";
            if (before.call || before.priority!=shown) return "Input settings changed. Select again.";
            GaiaProbe.Connection link = audioLink;
            if (link == null) throw new IOException("Audio connection closed.");
            levels = link.setInputPriority(before,shown,choice);
            if(!levels.playingInput(choice)){
                ReversibleEqTest.settle();
                levels=link.levels();
            }
            String input=DeviceLevels.inputNames()[choice];
            return levels.playingInput(choice) ? "Active input · "+input
                    : "Waiting for audio from "+input+".";
        });
    }

    private void showFirmwareInfo() {
        String installed = detectedFirmware == null ? "Connect the 5K to read its installed firmware."
                : "Last read from the 5K · "+detectedFirmware;
        new AlertDialog.Builder(this).setTitle("5K firmware details")
                .setMessage(installed+"\n\nThe official Qudelix app checks for and installs firmware updates when it connects. "
                        +"Companion device controls currently require original 5K firmware "+GaiaProbe.EDITOR_FIRMWARE+".")
                .setPositiveButton("Open Qudelix app",(dialog,index)->openOfficialController())
                .setNeutralButton("Compatibility check",(dialog,index)->{
                    closeAudioLink();
                    startActivity(new Intent(this,ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            .putExtra(ProbeActivity.OPEN_COMPATIBILITY,true));
                })
                .setNegativeButton("Close",null).show();
    }

    private void openOfficialController() {
        closeAudioLink();
        String packageName = "com.qudelix.qudelix";
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        try {
            if (launch != null) {
                startActivity(launch);
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://play.google.com/store/apps/details?id="+packageName)));
        } catch (android.content.ActivityNotFoundException error) {
            note("Open Google Play and look for Qudelix to check firmware.");
        }
    }
    private void scale(LinearLayout panel, String lower, String upper) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(48), 0, dp(48), dp(6));
        panel.addView(row);
        row.addView(LabStyle.text(this, lower, 11, LabStyle.MUTED, false), new LinearLayout.LayoutParams(0,-2,1));
        row.addView(LabStyle.text(this, upper, 11, LabStyle.MUTED, false));
    }
    private void volumeRow(LinearLayout panel, SeekBar slider, String label, boolean down, boolean up,
            java.util.function.IntConsumer step) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(row);
        for (int d : new int[] {-1, 1}) {
            if (d == 1)
                row.addView(slider, new LinearLayout.LayoutParams(0, dp(48), 1));
            Button b = new Button(this);
            LabStyle.quiet(b);
            b.setTextSize(23);
            b.setText(d < 0 ? "−" : "+");
            b.setContentDescription((d < 0 ? "Lower " : "Raise ") + label + " volume one step");
            b.setEnabled(!volumeLocked && (d < 0 ? down : up));
            b.setOnClickListener(v -> step.accept(d));
            row.addView(b, dp(44), dp(46));
        }
    }
    private void phoneVolume() {
        if (androidBar == null)
            return;
        AudioManager a = getSystemService(AudioManager.class);
        int stream = AudioManager.STREAM_MUSIC;
        int max = a.getStreamMaxVolume(stream), current = a.getStreamVolume(stream);
        androidBar.setMax(max);
        if (Build.VERSION.SDK_INT >= 28)
            androidBar.setMin(a.getStreamMinVolume(stream));
        androidBar.setProgress(current);
        androidBar.setEnabled(!volumeLocked && !a.isVolumeFixed());
        androidLabel.setText("Android media volume · " + current + " / " + max + " · " + Math.round(100f*current/Math.max(1,max))+"%"+(phoneDragging && compactSignal!=null?compactSignal.sliderEstimate():""));
    }
    private void rowButtons(
            String a, String b, Runnable first, Runnable second, boolean enableA, boolean enableB) {
        LinearLayout row = new LinearLayout(this);
        body.addView(row);
        for (Button v : new Button[] {button(row, a, first, enableA), button(row, b, second, enableB)})
            v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
    private TextView text(LinearLayout p, String s, int size) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(LabStyle.INK);
        v.setTextSize(size);
        v.setPadding(dp(2), dp(5), dp(2), dp(4));
        p.addView(v);
        return v;
    }
    private Button button(LinearLayout p, String s, Runnable action, boolean enabled) {
        Button v = new Button(this);
        v.setAllCaps(false);
        v.setText(s);
        v.setEnabled(enabled);
        v.setMinHeight(dp(48));
        v.setOnClickListener(w -> action.run());
        LabStyle.button(v, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(3), 0, dp(3));
        p.addView(v, params);
        return v;
    }
}
