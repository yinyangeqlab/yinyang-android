package com.yinyangeq.qudelixprobe;

import android.bluetooth.BluetoothDevice;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static com.yinyangeq.qudelixprobe.LabStyle.*;

public final class PlusActivity extends AppearanceActivity {
    private static final int[] FREQUENCY_COLORS = {
        0xffec6463, 0xfff79d58, 0xffe8c954, 0xffa5d05a, 0xff66cac2, 0xff73b8f0, 0xffa59af6, 0xffda8ad8
    };
    private static final int LAB_RESULT=20;
    private String officialFirmware, debugReport="";
    private ProbeLog log;
    private PlusDebugCheck reversible;
    private PlusBlindPanel blind;
    private boolean blindBusy, channelSwapBusy;
    private static final String SWAP_JOURNAL = "channelSwapJournal";
    private static final int BLIND_PAGE=5;
    private int debugTab;
    private static final int IMPORT_SHARED = 11, IMPORT_MATCH = 12, EXPORT = 13;
    private static final String[] PREAMP_LABELS = {"Preamp", "Left preamp", "Right preamp"};
    private static final String[] TYPES = {"Low pass", "High pass", "Low shelf", "High shelf", "Peak"};
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PlusPreset.Channel[] draft = {PlusPreset.Channel.flat(), PlusPreset.Channel.flat(), PlusPreset.Channel.flat()};
    private PlusPreset.Channel inactiveMain;
    private PlusPreset.Mode mode = PlusPreset.Mode.LAYERED;
    private SharedPreferences storage;
    private String name = "";
    private int page, currentBank;
    private String awaitingExport;
    private CompanionEditor.Viewport scroll;
    private LinearLayout eqBody;
    private PlusConnection connection;
    private PlusLevels levels;
    private PlusAudioPanel audioPanel;
    private FilterTable.SyncStatus syncLabel;
    private CompanionResponse responsePlot;
    private Button undoEdit, redoEdit;
    private final android.os.Handler editHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable flushEdit=this::syncPending;
    private String syncMessage = "Phone draft · Connect to apply to your 5K Plus.";
    private long queuedRevision;
    private boolean applying, libraryBusy;
    private volatile int sessionEpoch;
    private boolean inputPending;
    private PhoneMediaHandoff phoneMedia;
    private final String[] stageNames = {"", "", ""};
    private TextView deviceInfo;
    private boolean missingShown;

    private PlusLibrary catalogue;
    private int pairFirst=-1;
    private boolean presetsOpen, showFactory, loadTogether;
    private boolean requestCatalogue;
    private boolean loadingLinkedEq;
    private String initialEqMessage="Connect your linked 5K Plus to read its active EQ.";
    private AlertDialog applyDraftPrompt;


    @Override public void onCreate(Bundle state) {
        SharedPreferences appearance = getSharedPreferences("appearance", MODE_PRIVATE);
        if (!appearance.contains("theme")) appearance.edit().putInt("theme", 1).apply();
        super.onCreate(state);
        phoneMedia=new PhoneMediaHandoff(this);
        storage = getSharedPreferences("plus-presets-v1", MODE_PRIVATE);
        if(binding()!=null && !binding().lease.equals(storage.getString("editLease","")))
            storage.edit().remove("pendingRevision").apply();
        log=new ProbeLog(getFilesDir()); reversible=new PlusDebugCheck(this);
        blind=new PlusBlindPanel(this,storage);
        if(loadDraft(storage.getString("draft", ""),true))ensureReloadPoints();
        if (state != null) {
            presetsOpen=state.getBoolean("presetsOpen");
            page = state.getInt("page");
            if(page==1){page=0;presetsOpen=true;}
            currentBank = state.getInt("bank") == 0 ? 0 : 1;
            awaitingExport = state.getString("export");
        }
        if(blind.active() || (state==null && getIntent().getBooleanExtra("blind",false)))page=BLIND_PAGE;
        else if(swapPending())page=3;
        connection = new PlusConnection(this, new PlusConnection.Listener() {
            public void changed() {
                if(applyDraftPrompt!=null)applyDraftPrompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(writable());
                log.record(connection.status==null ? "Device discovery or disconnection" : "5K Plus connected · firmware "+connection.status.firmware);
                if (connection.status == null) {
                    inputPending=false;
                    catalogue=null;requestCatalogue=false;sessionEpoch++;blindBusy=false;channelSwapBusy=false;loadingLinkedEq=false; libraryBusy = false; levels = null; queuedRevision = 0; applying = false;
                    syncMessage = storage.contains("pendingRevision") ? "Phone draft · Waiting for your 5K Plus." : "Phone draft · Connect to apply to your 5K Plus.";
                } else {
                    missingShown=false;

                    checkFirmwareNotice();
                    if(blind.active())page=BLIND_PAGE;else if(storage.contains("draft"))syncPending();else firstLinkedEq();
                }
                render(true);
            }
            public void levels(PlusLevels next) {
                levels = next;pairingState();
                if (audioPanel != null) {
                    audioPanel.update(next, writable(),connection.batteryEta);
                    updateChannelSwap();
                }
                updateDeviceInfo();
            }
            public void missing(String message) {
                if(missingShown)return;missingShown=true;
                toast(message);
            }
            public void error(String message) { log.record(message); toast(message); }
        });
        render(false);
    }

    @Override protected void onStart() {
        super.onStart(); if(isFinishing())return;officialFirmware=PlusFirmware.latest(this); connection.start();
    }
    @Override protected void onStop() {
        editHandler.removeCallbacks(flushEdit);
        sessionEpoch++;inputPending=false;
        blindBusy=false;channelSwapBusy=false;
        connection.stop();
        super.onStop();
    }
    @Override boolean observesHardwareDisconnects(){return true;}
    @Override void hardwareDisconnected(String address,int transport){connection.bluetoothDisconnected(address,transport);}
    @Override boolean observesBluetoothAudio(){return true;}
    @Override public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        super.onRequestPermissionsResult(request, perms, results);
        if (request == PlusConnection.NEARBY) connection.permissionResult();
    }
    private void retryDiscovery(){missingShown=false;connection.retry();}
    private boolean writable() { return connection.status != null && "1.0.3".equals(connection.status.firmware); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
        state.putBoolean("presetsOpen",presetsOpen);
        state.putInt("bank", currentBank);
        state.putString("export", awaitingExport);
    }

    @Override protected void onDestroy() {
        connection.destroy();
        // Finish an in-flight apply and its readback even if Android rotates the screen.
        io.shutdown();
        super.onDestroy();
    }

    private int dp(float size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    TextView text(String content, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private Button button(String title, boolean highlighted, Runnable action) {
        return highlighted ? CompanionActions.primary(this, title, action) : CompanionActions.secondary(this, title, action);
    }

    void add(LinearLayout root, View child, int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(gap);
        root.addView(child, lp);
    }

    void shareRow(LinearLayout root, View child, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = first ? 0 : dp(7);
        root.addView(child, lp);
    }

    private void render(boolean keepScroll) {
        if(keepScroll && page==0 && eqBody!=null){
            scroll.preservePosition();
            eqBody.removeAllViews();
            renderEq(eqBody);
            return;
        }
        pairingButton=null;pairingDetail=null;eqBody=null;undoEdit=null;redoEdit=null;
        audioPanel = null; syncLabel = null; deviceInfo = null; bluetoothAudioStatus = null;
        connection.audio(page == 3);
        int y = keepScroll && scroll != null ? scroll.getScrollY() : 0;
        LinearLayout screen = column();
        screen.setBackgroundColor(BG);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(system.left, system.top, system.right, Math.max(system.bottom, keyboard.bottom));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            systemBarInsets(view, view.getPaddingTop(), view.getPaddingBottom(), view.getPaddingLeft(), view.getPaddingRight());
            return insets;
        });
        LabStyle.modelHeader(this,screen,true,()->{
            if(applying || blindBusy)toast("Wait for the device operation to finish before changing theme.");
            else chooseTheme();
        },()->navigate(DIAGNOSTICS));
        if(page==0 || page==BLIND_PAGE) LabStyle.eqMenu(this,screen,page==BLIND_PAGE?LabStyle.BLIND:EQ,this::navigate);

        scroll = new CompanionEditor.Viewport(this);
        scroll.setFillViewport(true);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = CompanionEditor.pageBody(scroll);
        body.setPadding(dp(14), dp(6), dp(14), dp(18));
        if (page == 0) {eqBody=body;renderEq(body);}
        if (page == 2) {
            add(body, text("Connection", 24, INK, true), 6);
            renderDevice(body);
        }

        if (page == 3) {
            add(body, text("Audio levels", 24, INK, true), 6);

            audioPanel = new PlusAudioPanel(this, this::adjustVolume, this::changeDac, this::changeInput, this::chooseUsbMode, this::askChannelSwap, connection.label.endsWith(" · USB"), connection.address());
            body.addView(audioPanel);
            audioPanel.update(levels, writable(),connection.batteryEta);
            audioPanel.inputPending(inputPending);
            updateChannelSwap();
        }
        if (page==4) renderDebug(body);
        if (page==BLIND_PAGE) blind.render(body,writable(),blindBusy||applying||libraryBusy);
        LabStyle.nav(this,screen,page==2 ? HEADPHONES : page==3 ? AUDIO : page==4 ? DIAGNOSTICS : EQ,
                this::navigate);
        setContentView(screen);
        screen.requestApplyInsets();
        if(y>0)scroll.restorePosition(y);
    }

    private void navigate(int dest) {
        if(channelSwapBusy){toast("Wait for the channel swap to finish.");return;}
        if(dest==LabStyle.BLIND){if(page!=BLIND_PAGE){page=BLIND_PAGE;render(false);}return;}
        if(blind.active() || blindBusy){toast("End and restore the comparison before opening another screen.");return;}
        if(dest==WEBSITE){openTab(new Intent(this,TuningActivity.class).putExtra("plus",true),LAB_RESULT);return;}
        if(dest==LAB || dest==TUNING) {
            Intent open=new Intent(this,PlusLab.class).putExtra("autoEq",dest==TUNING);
            int g=mode==PlusPreset.Mode.LAYERED?0:2;
            open.putExtra("main",PlusPreset.file(new PlusPreset.Channel[]{draft[0]},30)).putExtra("mainName",stageNames[g].isEmpty()?name:stageNames[g]);
            if(mode==PlusPreset.Mode.LAYERED)open.putExtra("match",PlusPreset.file(new PlusPreset.Channel[]{draft[1],draft[2]},20)).putExtra("matchName",stageNames[1].isEmpty()?name:stageNames[1]);
            openTab(open,LAB_RESULT); return;
        }
        int next=dest==DIAGNOSTICS ? 4 : dest==HEADPHONES ? 2 : dest==AUDIO ? 3 : 0;
        if(page!=next){page=next;render(false);}
    }
    @Override public void onBackPressed(){
        if(page==BLIND_PAGE && (blind.active()||blindBusy)){
            AlertDialog.Builder prompt=new AlertDialog.Builder(this).setTitle("Leave this comparison?")
                .setMessage("Restore the starting EQ now or leave this round saved for later.")
                .setNegativeButton("Stay",null);
            if(writable() && blind.active() && !blindBusy)
                prompt.setPositiveButton("Restore & leave",(d,w)->blind.end(()->navigate(EQ)));
            prompt.setNeutralButton("Leave for later",(d,w)->moveTaskToBack(true)).show();
            return;
        }
        if(page==BLIND_PAGE){navigate(EQ);return;}
        if(page==4){navigate(HEADPHONES);return;}
        super.onBackPressed();
    }
    private void checkFirmwareNotice() {
        String installed=connection.status.firmware;
        if (officialFirmware==null || PlusFirmware.compare(officialFirmware,installed)<=0 ||
                !storage.getBoolean("firmwareNotices",true) || officialFirmware.equals(storage.getString("noticed",""))) return;
        storage.edit().putString("noticed",officialFirmware).apply();
        new AlertDialog.Builder(this).setTitle("Firmware "+officialFirmware+" available")
            .setMessage("Your 5K Plus runs "+installed+". Open Qudelix to install the update.")
            .setPositiveButton("Open Qudelix",(d,w)->openOfficial()).setNegativeButton("Later",null).show();
    }
    private LinearLayout firmwareCard() {
        LinearLayout f=column();
        CompanionActions.sectionTitle(f,"Firmware");
        String current=connection.status==null ? "Connect the Plus to read its version." : "Installed on the Plus · "+connection.status.firmware;
        add(f,text(current,15,INK,false),6);
        add(f,text(officialFirmware==null ? "Update the official Qudelix app to check available firmware." :
                "Available in the installed Qudelix app · "+officialFirmware,14,MUTED,false),4);
        android.widget.Switch toggle=new android.widget.Switch(this);
        toggle.setText("Notify when newer firmware is available");
        LabStyle.toggle(toggle); toggle.setChecked(storage.getBoolean("firmwareNotices",true));
        toggle.setOnCheckedChangeListener((v,on)->{
            storage.edit().putBoolean("firmwareNotices",on).remove("noticed").apply();
            if(on && connection.status!=null) checkFirmwareNotice();
        }); add(f,toggle,6);
        add(f,button("Open Qudelix app",false,this::openOfficial),4);
        return f;
    }
    private void renderDebug(LinearLayout body) {
        add(body,text("Debug",26,INK,true),6);
        String[] groups={"Device tests","Simulations","Logs"};
        LinearLayout tabs=row();add(body,tabs,5);
        for(int i=0;i<groups.length;i++){
            final int tab=i;shareRow(tabs,button(groups[i],i==debugTab,()->{debugTab=tab;render(false);}),i==0);
        }
        if(debugTab==0) {
            add(body,button("Check device and active banks",true,this::diagnostics),8);
            add(body,button("Read and export active EQ",false,this::exportLive),8);
            add(body,button(reversible.pending() ? "Check EQ restoration" : "Reversible EQ test",false,this::reversibleTest),8);
        } else if(debugTab==1) {
            add(body,button("Run offline simulation",false,()->simulate(false)),8);
            add(body,button("Simulate a dropped connection",false,()->simulate(true)),8);
        } else {
            add(body,button("View debug log",false,()-> {
                try { new AlertDialog.Builder(this).setTitle("Debug log").setMessage(log.snapshot(null)).setPositiveButton("Close",null).show(); }
                catch(IOException e){showError(e.getMessage());}
            }),8);
            add(body,button("Export debug log",false,()-> {
                try { writeDocument("Q5K-plus-debug.txt",log.snapshot(null)); }
                catch(IOException e){showError(e.getMessage());}
            }),8);
        }
        if(!debugReport.isEmpty()) add(body,text(debugReport,15,INK,false),10);
    }
    private void diagnosticResult(String report) {
        debugReport=report; log.record(report); if(page==4)render(true);
    }
    private void simulate(boolean drop) {
        try {
            if(drop) {
                PlusUsbProtocol.status(new PlusUsbProtocol.Transport(){
                    public void send(byte[] report) {}
                    public byte[] receive(int timeout)throws IOException { throw new IOException("Simulated device disconnect"); }
                });
            } else {
                List<PlusPreset.Band> flat=Collections.nCopies(20,new PlusPreset.Band(5,1000,0,1024));
                PlusPreset.Banks banks=PlusPreset.banks(PlusPreset.Mode.LAYERED,new PlusPreset.Channel(0,flat),
                        PlusPreset.Channel.flat(),PlusPreset.Channel.flat(),false);
                diagnosticResult("Offline routing verified · "+banks.user.bands.size()+" USR, "+banks.match[0].bands.size()+" left and "+banks.match[1].bands.size()+" right.");
            }
        } catch(IOException e) { diagnosticResult("Disconnect simulation · "+e.getMessage()+". The handshake ended before any writes."); }
    }
    private void diagnostics() {
        if(connection.status==null){connection.retry();toast("Connect the Plus and run the check again.");return;}
        connection.enqueue((link,state)->{
            long start=System.nanoTime();
            try {
                PlusLevels.read(link);
                for(int id:new int[]{0,1,2}) PlusLibrary.decode(id,PlusUsbProtocol.required(
                        PlusUsbProtocol.read(link,PlusLibrary.base(id)+3),PlusLibrary.base(id)+3,PlusLibrary.size(id)));
                String result=Peq.fmt("Firmware %s · volume, input and all three working banks read in %.0f ms.",state.firmware,(System.nanoTime()-start)/1e6);
                runOnUiThread(()->{if(!isDestroyed())diagnosticResult(result);});
            } catch(IOException e){runOnUiThread(()->{if(!isDestroyed())diagnosticResult("Read check failed · "+e.getMessage());});}
        });
    }
    private void reversibleTest() {
        new AlertDialog.Builder(this).setTitle("Reversible EQ check")
            .setMessage("Pause playback and unplug headphones. The check changes the working preamp by 0.1 dB, reads it back and restores the original EQ.")
            .setNegativeButton("Cancel",null).setPositiveButton("Run check",(d,w)->libraryTask((link,packet)->{
                String result=reversible.run(link,PlusUsbProtocol.status(link));
                return () -> { diagnosticResult(result); syncPending(); };
            })).show();
    }
    private void exportLive() {
        new AlertDialog.Builder(this).setTitle("Export active hardware bank").setItems(PlusLibrary.GROUPS,(d,g)->
            libraryTask((link,packet)->{
                int base=PlusLibrary.base(g);
                PlusPreset.Channel[] values=PlusLibrary.decode(g,PlusUsbProtocol.required(PlusUsbProtocol.read(link,base+3),base+3,PlusLibrary.size(g)));
                String file=PlusPreset.file(values,PlusLibrary.bands(g));
                return () -> writeDocument("Q5K-plus-"+new String[]{"USR","SPK","30-band"}[g]+".txt",file);
            })).show();
    }
    private void writeDocument(String filename,String contents) {
        awaitingExport=contents;
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE,filename),EXPORT);
        } catch(android.content.ActivityNotFoundException e) { awaitingExport=null;toast("No document picker is available."); }
    }
    private void stageTabs(LinearLayout screen) {
        int active=mode==PlusPreset.Mode.SHARED?2:currentBank;
        CompanionEditor.stages(screen,active,true,stage->{
            PlusPreset.Mode next=stage==2?PlusPreset.Mode.SHARED:PlusPreset.Mode.LAYERED;
            if(next!=mode){
                try{PlusPreset.banks(next,mainFor(next),draft[1],draft[2],true);}
                catch(IllegalArgumentException error){showError(error.getMessage());return;}
            }
            pairFirst=-1;loadTogether=false;currentBank=stage==1?1:0;
            if(next!=mode){
                switchMode(next);
                saveDraft();
            }
            render(false);
        });
    }

    private void firstLinkedEq(){
        if(loadingLinkedEq || blind.active() || blindBusy || !writable() || storage.contains("draft"))return;
        loadingLinkedEq=true;int epoch=sessionEpoch;
        connection.enqueue((link,state)->{
            try{
                int g=state.mode==PlusPreset.Mode.SHARED?2:0;
                int shared=PlusLibrary.base(g)+3,match=PlusLibrary.base(1)+3;
                java.util.Map<Integer,byte[]> blocks=PlusUsbProtocol.read(link,shared,match);
                PlusPreset.Channel main=PlusLibrary.decode(g,PlusUsbProtocol.required(blocks,shared,PlusLibrary.size(g)))[0];
                PlusPreset.Channel[] ears=PlusLibrary.decode(1,PlusUsbProtocol.required(blocks,match,PlusLibrary.size(1)));
                runOnUiThread(()->{
                    if(isDestroyed() || epoch!=sessionEpoch || !binding().active(this))return;
                    loadingLinkedEq=false;
                    if(storage.contains("draft"))return;
                    mode=state.mode;draft=new PlusPreset.Channel[]{main,ears[0],ears[1]};
                    rememberPreset();ensureReloadPoints();storage.edit().putString("draft",draftJson()).apply();
                    syncText("Active EQ read from linked 5K Plus.");render(true);
                });
            }catch(IOException e){runOnUiThread(()->{
                if(isDestroyed() || epoch!=sessionEpoch)return;
                loadingLinkedEq=false;initialEqMessage="Could not read the linked device’s active EQ. "+e.getMessage();render(true);
            });}
        });
    }
    private void renderEq(LinearLayout body) {
        if(!storage.contains("draft")){
            add(body,text(loadingLinkedEq?"Reading active EQ from your linked 5K Plus…":
                    connection.status!=null && !writable()?"The EQ editor requires Plus firmware 1.0.3.":initialEqMessage,15,INK,false),8);
            if(writable() && !loadingLinkedEq)add(body,button("Read active EQ",false,()->{firstLinkedEq();render(true);}),8);
            return;
        }
        boolean layered = mode == PlusPreset.Mode.LAYERED;
        int bank = layered ? currentBank : 0;
        PlusPreset.Channel channel = draft[bank];
        int used = channel.bands.size();
        int limit = !layered ? 30 : bank == 0
                ? PlusPreset.sharedCapacity(draft[1], draft[2]) : PlusPreset.matchCapacity(draft[0]);
        int saveGroup=layered?bank:2;
        stageTabs(body);
        String preset = stageNames[saveGroup].isEmpty() ? (name.isEmpty() ? "Phone draft" : name) : stageNames[saveGroup];
        CompanionEditor.preset(body,preset,presetsOpen,true,
                ()->{presetsOpen=!presetsOpen;render(true);});
        CompanionEditor.saves(body,"Save to 5K Plus",writable(),()->nameDevicePreset(saveGroup),"Save on this phone",true,this::saveNamed);
        eqFiles(body,bank);
        if(presetsOpen)renderPresets(body);
        CompanionEditor.responseTitle(body,layered?"Combined response · dB":"Main EQ response · dB",layered);
        PeqResponse[] ears=layered ? new PeqResponse[]{PlusPreset.response(draft[1],48000),PlusPreset.response(draft[2],48000)} : new PeqResponse[1];
        responsePlot=new CompanionResponse(this,PlusPreset.response(draft[0],48000),ears,CompanionEditor.left(this,storage),CompanionEditor.right(this));
        body.addView(responsePlot, new LinearLayout.LayoutParams(-1, dp(124)));
        syncLabel = new FilterTable.SyncStatus(this);
        syncText(syncMessage);
        add(body, syncLabel, 3);

        LinearLayout historyRow = new LinearLayout(this);
        undoEdit = CompanionActions.secondary(this,"Undo",()->moveEdit(true));
        redoEdit = CompanionActions.secondary(this,"Redo",()->moveEdit(false));
        historyRow.addView(undoEdit,new LinearLayout.LayoutParams(0,-2,1));
        historyRow.addView(redoEdit,new LinearLayout.LayoutParams(0,-2,1));
        body.addView(historyRow);
        showHistoryState();
        boolean canEditPage=!libraryBusy && !blind.active() && !blindBusy;
        CompanionEditor.filterHeading(body,bank!=0?"Channel Match filters":layered?"Main PEQ filters":"30-band PEQ filters",
                bank==0?used+" / "+limit+" bands":null,canEditPage,()->reloadStage(saveGroup),
                canEditPage && (used>0 || bank!=0 && !draft[2].bands.isEmpty()),()->clearFilters(saveGroup));
        for(int i=bank==0?0:1;i<=(bank==0?0:2);i++){
            if(i>0 && !CompanionEditor.visibleEar(storage,i-1))continue;
            final int ch=i;FilterTable.pre(body,PREAMP_LABELS[ch],draft[ch].preamp,true,val->{
                draft[ch]=new PlusPreset.Channel(Peq.number(val,-24,24,10,"Preamp"),draft[ch].bands);saveInline();});
        }
        if (bank == 0) FilterTable.preampScope(body,layered);


        if (bank != 0) {
            matchColumns(body, limit);
            return;
        }
        if(used>0)FilterTable.headers(body);
        for(int i=0;i<used;i++) inlineBand(body,bank,i);
        if(used<limit)add(body,button("Add filter",false,()->{
            draft[bank]=draft[bank].replace(used,new PlusPreset.Band(5,1000,0,1024));saveFilterEdit(false);render(true);}),8);
        LinearLayout slots = column();
        CompanionActions.sectionTitle(slots,"Band allocation");
        add(slots, text(layered
                ? Peq.fmt("Main EQ %d    ·    Left %d    ·    Right %d", draft[0].bands.size(), draft[1].bands.size(), draft[2].bands.size())
                : Peq.fmt("Main EQ %d / 30", used), 15, INK, true), 6);
        add(slots, text(layered
                ? "Your channel match leaves room for " + PlusPreset.sharedCapacity(draft[1], draft[2])
                    + " Main EQ bands. Each Main EQ band after 10 uses one slot in each ear."
                : "All 30 slots serve both ears. Left and right match drafts are kept on this phone.", 14, MUTED, false), 5);
        add(body, slots, 0);
    }
    private void eqFiles(LinearLayout body,int bank){
        CompanionEditor.files(body,true,true,()->pick(bank==0 ? IMPORT_SHARED : IMPORT_MATCH),()->chooseExport(bank));
    }

    private void inlineBand(LinearLayout parent,int bank,int index){
        PlusPreset.Band b=draft[bank].bands.get(index);
        String[] types=TYPES;
        FilterTable.row(parent,String.valueOf(index+1),types,b.type-1,b.hz,b.gain,b.q,true,(type,hz,gain,q)->{
            PlusPreset.Band next=PlusPreset.Band.input(type+1,hz,gain,q);
            draft[bank]=draft[bank].replace(index,next);
            saveInline();
        },()->{
            draft[bank]=draft[bank].replace(index,null);
            saveFilterEdit(false);render(true);
        });
    }
    private void matchColumns(LinearLayout body,int limit) {
        String usage=Peq.fmt("Left %d/%d   ·   Right %d/%d   ·   Main EQ capacity %d",
                draft[1].bands.size(),limit,draft[2].bands.size(),limit,PlusPreset.sharedCapacity(draft[1],draft[2]));
        CompanionEditor.matches(body,storage,usage,true,new CompanionEditor.Matches() {
            public int slots(int ear){return draft[ear+1].bands.size();}
            public boolean active(int ear,int i){return true;}
            public boolean canAdd(int lane){return slots(lane)<limit;}
            public void add(int lane){PlusPreset.Band b=new PlusPreset.Band(5,1000,0,1024);
                draft[lane+1]=draft[lane+1].replace(slots(lane),b);
                saveFilterEdit(false);render(true);}
            public void table(LinearLayout into,int ear,int i){inlineBand(into,ear+1,i);}
            public boolean sameShape(int i){
                PlusPreset.Band l=draft[1].bands.get(i),r=draft[2].bands.get(i);
                return l.type==r.type && l.hz==r.hz && l.q==r.q;
            }
            public void paired(CompanionEditor.MatchRow row,int i){
                PlusPreset.Band l=draft[1].bands.get(i),r=draft[2].bands.get(i);
                FilterTable.pairedRow(row,String.valueOf(i+1),TYPES,l.type-1,l.hz,l.gain,r.gain,l.q,true,
                    (type,hz,left,right,q)->{
                        PlusPreset.Band leftBand=PlusPreset.Band.input(type+1,hz,left,q);
                        PlusPreset.Band rightBand=PlusPreset.Band.input(type+1,hz,right,q);
                        draft[1]=draft[1].replace(i,leftBand);
                        draft[2]=draft[2].replace(i,rightBand);
                        saveInline();
                    },scope->{
                        if(scope!=FilterTable.Removal.RIGHT)draft[1]=draft[1].replace(i,null);
                        if(scope!=FilterTable.Removal.LEFT)draft[2]=draft[2].replace(i,null);
                        saveFilterEdit(false);render(true);
                    });
            }
        },()->render(true));
    }
    private void chooseExport(int bank){
        boolean match=bank!=0;
        new AlertDialog.Builder(this).setTitle("Export "+(match?"Channel Match":"Main EQ"))
            .setItems(new String[]{"Editable filter file","Qudelix device bank file"},(d,i)->{
                if(i==1){exportBank(match);return;}
                writeDocument(match?"Q5K-plus-channel-match.txt":"Q5K-plus-PEQ.txt",
                    PlusPreset.file(match?new PlusPreset.Channel[]{draft[1],draft[2]}:new PlusPreset.Channel[]{draft[0]},match?20:30));
            }).setNegativeButton("Cancel",null).show();
    }
    private void renderPresets(LinearLayout body) {
        LinearLayout device=column();body.addView(device);
        int stage=mode==PlusPreset.Mode.SHARED?2:currentBank;
        int g=loadTogether && pairFirst!=-1?1:stage;
        add(device,text("Device presets · "+PlusLibrary.GROUPS[g],16,INK,true),4);
        if(catalogue==null && writable() && !applying && !requestCatalogue && !libraryBusy){
            requestCatalogue=true;device.post(()->{if(page!=0 || !presetsOpen || !writable()){requestCatalogue=false;return;}loadCatalogue();});
        }
        if(catalogue!=null){
            if(stage==0){
                CheckBox together=new CheckBox(this);together.setText("Load USR with a channel match");together.setChecked(loadTogether);
                together.setOnCheckedChangeListener((v,on)->{loadTogether=on;pairFirst=-1;render(true);});device.addView(together);
            }
            if(loadTogether && pairFirst!=-1)add(device,text("Choose a channel match for "+catalogue.title(0,pairFirst),14,INK,true),2);
            int count=0;for(int i=0;i<20;i++)if(catalogue.names[g][i]!=null){int slot=i;count++;
                add(device,button(Peq.fmt("%02d   %s",i+1,catalogue.names[g][i]),false,()->openListed(g,slot)),1);}
            if(count==0)add(device,text("No saved custom presets in this bank.",14,MUTED,false),4);
            Switch factoryToggle=new Switch(this);factoryToggle.setText("Factory presets");factoryToggle.setChecked(showFactory);
            device.addView(factoryToggle);
            LinearLayout factoryList=column();device.addView(factoryList);
            String[] factory=g==1?new String[]{"Flat"}:PlusLibrary.FACTORY;
            for(int i=0;i<factory.length;i++){int slot=PlusLibrary.factory(i);add(factoryList,button(factory[i],false,()->openListed(g,slot)),1);}
            factoryList.setVisibility(showFactory?View.VISIBLE:View.GONE);
            factoryToggle.setOnCheckedChangeListener((v,on)->{showFactory=on;factoryList.setVisibility(on?View.VISIBLE:View.GONE);});
        }else add(device,text(writable()?"Read the device to list stored presets.":"Connect your 5K Plus to read stored presets.",14,MUTED,false),5);
        if(writable())add(device,button("Refresh presets",false,this::loadCatalogue),4);
        add(body,text("Phone presets",16,INK,true),8);
        java.util.ArrayList<String> names=new java.util.ArrayList<>();
        for(String key:storage.getAll().keySet())if(key.startsWith("preset:"))names.add(key.substring(7));
        Collections.sort(names,String.CASE_INSENSITIVE_ORDER);
        if(names.isEmpty())add(body,text("No presets saved on this phone.",14,MUTED,false),4);
        for(String saved:names){
            LinearLayout line=row();body.addView(line);
            line.addView(button(saved,false,()->{if(loadDraft(storage.getString("preset:"+saved,""))){rememberPreset();loadTogether=false;pairFirst=-1;presetsOpen=false;saveDraft();render(false);}}),new LinearLayout.LayoutParams(0,-2,1));
            line.addView(button("Delete",false,()->new AlertDialog.Builder(this).setMessage("Delete "+saved+"?")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{storage.edit().remove("preset:"+saved).apply();render(true);}).show()));
        }
    }
    private void loadCatalogue(){
        if(libraryBusy || applying){requestCatalogue=false;return;}requestCatalogue=true;
        libraryTask((link,packet)->{PlusLibrary c=PlusLibrary.read(link);return ()->{catalogue=c;if(page==0 && presetsOpen)render(true);};});
    }
    private void openListed(int group,int slot){
        if(libraryBusy || catalogue==null || reversible.pending())return;
        if(loadTogether && group==0){pairFirst=slot;render(true);return;}
        int[] selected={pairFirst,-1,-1};selectSlot(catalogue,loadTogether?PlusLibrary.PAIR:group,group,selected,slot);
    }
    private Button pairingButton;
    private TextView pairingDetail;
    private void pairingState(){
        if(pairingButton==null)return;
        boolean available=writable() && levels!=null && levels.hasPairingStatus;
        pairingButton.setEnabled(available);
        if (!available) {
            pairingButton.setText("Turn off automatic pairing");
            if (pairingDetail != null) pairingDetail.setText(writable()
                    ? "Reading Bluetooth pairing status from your 5K Plus…"
                    : "Pairing control requires a connected 5K Plus with supported firmware.");
            return;
        }
        boolean automatic = levels.automaticPairing;
        boolean open = levels.pairing;
        pairingButton.setText(automatic ? "Turn off automatic pairing"
                : open ? "Close pairing mode on 5K Plus" : "Turn on automatic pairing");
        String status = open ? "Your 5K Plus is in Bluetooth pairing mode. New devices can pair."
                : "Bluetooth pairing mode is closed on your 5K Plus.";
        String explanation = automatic
                ? "Turning off automatic pairing closes pairing mode and keeps the 5K Plus from opening it automatically."
                : open ? "Automatic pairing is off. Close pairing mode to stop accepting new devices now."
                : "Automatic pairing is off. You can start pairing manually on the 5K Plus to add a new device.";
        if (pairingDetail != null) pairingDetail.setText(status + " " + explanation
                + " Previously paired devices can still reconnect.");
    }
    private void changePairing(){
        if(!writable() || levels==null || !levels.hasPairingStatus)return;
        boolean enable=!levels.automaticPairing && !levels.pairing;
        if(connection.enqueue((link,state)->{
            try{PlusUsbProtocol.automaticPairing(link,state.packetSize,enable);}
            catch(IOException e){runOnUiThread(()->toast(e.getMessage()));}
        }))pairingButton.setEnabled(false);
    }
    private TextView bluetoothAudioStatus;
    @Override void bluetoothAudioChanged(){
        if(bluetoothAudioStatus==null)return;
        String state=BluetoothPages.audioState(this,connection.address(),true);
        bluetoothAudioStatus.setText(state);
        bluetoothAudioStatus.setVisibility(state.equalsIgnoreCase(connection.label)?View.GONE:View.VISIBLE);
    }

    private void renderDevice(LinearLayout body) {
        LinearLayout status = column();
        add(status, text(connection.label, 15, INK, true), 8);
        bluetoothAudioStatus=text("",14,MUTED,false);
        add(status,bluetoothAudioStatus,4);
        bluetoothAudioChanged();
        if (connection.status == null && !connection.searching)
            add(status, button("Connect", true, this::retryDiscovery), 8);
        if(binding()!=null){
            DeviceProfile profile=binding().profile;
            add(status,text(profile.model.label,15,INK,true),4);
            TextView mac=text(profile.bluetoothMacLabel(),14,MUTED,false);
            mac.setTextIsSelectable(true);
            add(status,mac,4);
        }
        Button manage=button("Manage linked device",false,()->DeviceBinding.manage(this));
        manage.setEnabled(!applying && !libraryBusy);add(status,manage,8);
        if (connection.status != null) {
            pairingButton=button("Turn off automatic pairing",false,this::changePairing);
            add(status,pairingButton,6);pairingState();
            pairingDetail=text(writable()?"Reading Bluetooth pairing status…":"Pairing control is unavailable for this firmware.",14,MUTED,false);
            add(status,pairingDetail,4);pairingState();
            CompanionActions.sectionTitle(status,"Device status");
            deviceInfo = text("",15,INK,false);
            add(status,deviceInfo,8); updateDeviceInfo();

        }
        add(body, status, 0);
        addButtonLayoutPreview(body, true);
        LabStyle.divider(this,body);

        add(body,firmwareCard(),0);

    }

    private void updateDeviceInfo() {
        if (deviceInfo == null || connection.status == null) return;
        PlusUsbProtocol.Status r = connection.status;
        String input = r.inputLabel() + (r.sampleRate>0 ? Peq.fmt(" · %.1f kHz",r.sampleRate/1000.0) : "")
                + (r.bits>0 ? " · "+r.bits+" bit" : "");
        deviceInfo.setText(r.mode.deviceLabel+(r.percent>=0 ? "\nBattery · "+r.percent+"%\n"+connection.batteryEta : "")
                +"\nInput · "+input+(r.output.isEmpty() ? "" : "\nOutput · "+r.output));
    }

    private interface HardwareTask { Runnable run(PlusUsbProtocol.Transport link,int packet) throws IOException; }

    private void libraryTask(HardwareTask task) {
        if(swapPending() || channelSwapBusy){toast("Finish the channel swap in Audio first.");return;}
        if(blind.active() || blindBusy){toast("End and restore the comparison first.");return;}
        if (!writable()) { toast(connection.status == null ? "Connect a 5K Plus to browse its presets." : "Device presets require firmware 1.0.3."); return; }
        if (libraryBusy || applying) { toast("Wait for the current device operation to finish."); return; }
        int epoch=sessionEpoch;
        libraryBusy=true;
        syncText("Reading device presets…");
        connection.enqueue((link,state) -> {
            Runnable result;
            try {
                if(blind.active())throw new IOException("End and restore the comparison first.");
                result=task.run(link,state.packetSize);
            }
            catch(IOException | IllegalArgumentException e) { result=() -> showError(e.getMessage()); }
            final Runnable done=result;
            runOnUiThread(() -> {
                if (isDestroyed() || epoch!=sessionEpoch || connection.status==null) return;
                libraryBusy=false; done.run();
            });
        });
    }

    private void selectSlot(PlusLibrary library,int choice,int group,int[] selected,int slot) {
            selected[group]=slot;

            libraryTask((link,packet)-> {
                PlusPreset.Channel[][] banks=PlusLibrary.load(link,packet,choice,selected);
                return () -> {
                    String mapping = choice==PlusLibrary.PAIR ? storage.getString(PlusLibrary.pairKey(banks),"") : "";
                    switchMode(choice==2 ? PlusPreset.Mode.SHARED : PlusPreset.Mode.LAYERED);
                    if (choice==2) draft[0]=banks[2][0];
                    else { draft[0]=banks[0][0]; draft[1]=banks[1][0]; draft[2]=banks[1][1]; }
                    if (!mapping.isEmpty()) loadDraft(mapping);
                    rememberStages(PlusLibrary.groups(choice));
                    for(int g=0;g<3;g++) stageNames[g]=library.title(g,selected[g]);
                    pairFirst=-1;loadTogether=false;presetsOpen=false;name=""; currentBank=choice==1 ? 1 : 0; page=0;
                    storage.edit().putString("draft",draftJson()).remove("pendingRevision").apply();
                    syncText("Loaded from the 5K Plus."); render(false);
                };
            });
    }

    private void nameDevicePreset(int stage) {
        if(stage==2){saveDevicePreset(2);return;}
        new AlertDialog.Builder(this).setTitle("Save to 5K Plus")
            .setItems(new String[]{PlusLibrary.GROUPS[stage],"Main EQ and Channel Match together"},(d,i)->saveDevicePreset(i==1?PlusLibrary.PAIR:stage))
            .setNegativeButton("Cancel",null).show();
    }
    private void saveDevicePreset(int group) {
        if(reversible.pending()){toast("Finish the EQ restoration check in Debug first.");return;}
        if (!writable()) { toast("Connect a 5K Plus to save a device preset."); return; }
        if (group==0 && draft[0].bands.size()>10) {
            showError("This USR bank holds 10 physical filters. Save a USR + SPK pair to retain the shared overflow, or save a 30-band EQ."); return;
        }
        PlusPreset.Channel[][] banks=new PlusPreset.Channel[3][];
        try {
            PlusPreset.Banks routed = PlusPreset.banks(group==2 ? PlusPreset.Mode.SHARED : PlusPreset.Mode.LAYERED,
                group==1 ? PlusPreset.Channel.flat() : draft[0], group==0 ? PlusPreset.Channel.flat() : draft[1],
                group==0 ? PlusPreset.Channel.flat() : draft[2],true);
            if(group==2) banks[2]=new PlusPreset.Channel[]{routed.user};
            else { banks[0]=new PlusPreset.Channel[]{routed.user}; banks[1]=routed.match; }
        } catch(IllegalArgumentException e) { showError(e.getMessage()); return; }
        String logicalDraft = draftJson();
        EditText field=new EditText(this); field.setSingleLine(true);
        LinearLayout fields=form(); labelled(fields,"Preset name",field);
        String what=group==PlusLibrary.PAIR ? "USR + SPK pair" : PlusLibrary.GROUPS[group];
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Save "+what)
                .setMessage("This applies the selected bank or banks and saves them in an empty device slot.")
                .setView(fields).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(ignored -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title=field.getText().toString().trim();
            if(title.isEmpty() || title.getBytes(StandardCharsets.UTF_8).length>32) { field.setError("Use 1–32 UTF-8 bytes."); return; }
            d.dismiss(); libraryTask((link,packet)-> {
                int slot=PlusLibrary.create(link,packet,group,title,banks);
                PlusPreset.Channel[][] physical=PlusLibrary.current(link,group);
                return () -> { catalogue=null;requestCatalogue=false;pairFirst=-1;loadTogether=false;
                    if (group==PlusLibrary.PAIR)
                        storage.edit().putString(PlusLibrary.pairKey(banks),logicalDraft).apply();
                    switchMode(group==2 ? PlusPreset.Mode.SHARED : PlusPreset.Mode.LAYERED);
                    if(group==2) draft[0]=physical[2][0];
                    else if(group!=PlusLibrary.PAIR) { draft[0]=physical[0][0]; draft[1]=physical[1][0]; draft[2]=physical[1][1]; }
                    currentBank=group==1 ? 1 : 0;
                    rememberStages(PlusLibrary.groups(group));
                    for (int g:PlusLibrary.groups(group)) stageNames[g]=title;
                    storage.edit().putString("draft",draftJson()).apply();
                    toast("Verified "+what+" in slot "+(slot+1)+"."); render(false);
                };
            });
        })); d.show();
    }

    private LinearLayout form() {
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(6), dp(20), dp(8));
        fields.setFocusableInTouchMode(true);
        return fields;
    }

    private void labelled(LinearLayout view, String label, EditText field) {
        field.setId(View.generateViewId());
        TextView caption = text(label, 14, INK, false);
        caption.setLabelFor(field.getId());
        add(view, caption, 10);
        add(view, field, 0);
    }

    private void rememberStages(int... groups){
        android.content.SharedPreferences.Editor change=storage.edit();
        for(int g:groups){
            change.remove("undo:"+g).remove("redo:"+g);
            change.putString("reload:"+g,stageText(g));
        }
        change.apply();
    }
    private void rememberPreset(){if(mode==PlusPreset.Mode.SHARED)rememberStages(2);else rememberStages(0,1);}
    private PlusPreset.Channel mainFor(PlusPreset.Mode layout){
        if(layout==mode)return draft[0];
        if(inactiveMain!=null)return inactiveMain;
        String known=storage.getString("reload:"+(layout==PlusPreset.Mode.SHARED?2:0),"");
        if(!known.isEmpty()){
            try{return PlusPreset.parse(known,false,30)[0];}
            catch(IllegalArgumentException malformed){/* Start from the current draft. */}
        }
        // Older versions used the same Main draft on both tabs. Snapshot it once when upgrading.
        return layout==PlusPreset.Mode.LAYERED && draft[0].bands.size()>PlusPreset.sharedCapacity(draft[1],draft[2])
                ? PlusPreset.Channel.flat() : draft[0];
    }
    private void switchMode(PlusPreset.Mode layout){
        if(layout==mode)return;
        PlusPreset.Channel previous=draft[0];
        draft[0]=mainFor(layout);
        inactiveMain=previous;
        mode=layout;
    }
    private void ensureReloadPoints(){
        if(inactiveMain==null)inactiveMain=mainFor(mode==PlusPreset.Mode.LAYERED?PlusPreset.Mode.SHARED:PlusPreset.Mode.LAYERED);
        SharedPreferences.Editor edit=storage.edit();
        for(int g=0;g<3;g++){
            String key="reload:"+g;
            if(!storage.getString(key,"").isEmpty())continue;
            String original=editSteps("undo:"+g).optString(0,"");
            edit.putString(key,original.isEmpty()?stageText(g):original);
        }
        edit.apply();
    }
    private void clearFilters(int g){
        if(g!=editStage() || libraryBusy || blind.active() || blindBusy)return;
        ensureReloadPoints();
        if(g==1){
            draft[1]=draft[1].clearFilters();
            draft[2]=draft[2].clearFilters();
        }else draft[0]=draft[0].clearFilters();
        saveFilterEdit(false);
        render(true);
    }
    private void reloadStage(int g){
        if(g!=editStage() || libraryBusy || blind.active() || blindBusy)return;
        ensureReloadPoints();
        String source=storage.getString("reload:"+g,"");
        try{
            if(source.equals(stageText(g))){toast("Starting filters already loaded.");return;}
            PlusPreset.Channel[] saved=PlusPreset.parse(source,g==1,g==1?20:30),next=draft.clone();
            if(g==1){next[1]=saved[0];next[2]=saved[1];}else next[0]=saved[0];
            PlusPreset.banks(mode,next[0],next[1],next[2],true);
            draft=next;saveFilterEdit(false);render(true);
        }catch(IllegalArgumentException error){showError(error.getMessage());}
    }
    private String draftJson() {
        try {
            return new JSONObject().put("name", name).put("mode", mode.name()).put("usrName",stageNames[0]).put("spkName",stageNames[1]).put("allName",stageNames[2])
                .put("shared", PlusPreset.file(new PlusPreset.Channel[]{draft[0]}, 30))
                .put("inactiveMain", PlusPreset.file(new PlusPreset.Channel[]{mainFor(
                        mode==PlusPreset.Mode.LAYERED?PlusPreset.Mode.SHARED:PlusPreset.Mode.LAYERED)}, 30))
                .put("match", PlusPreset.file(new PlusPreset.Channel[]{draft[1], draft[2]}, 20)).toString();
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean loadDraft(String value) { return loadDraft(value,false); }
    private boolean loadDraft(String value,boolean restoreWorkspace) {
        if (value.isEmpty()) return false;
        try {
            JSONObject data = new JSONObject(value);
            PlusPreset.Channel shared = PlusPreset.parse(data.getString("shared"), false, 30)[0];
            PlusPreset.Channel[] match = PlusPreset.parse(data.getString("match"), true, 20);
            PlusPreset.Mode layout=PlusPreset.Mode.valueOf(data.getString("mode"));
            if(restoreWorkspace){
                String inactive=data.optString("inactiveMain","");
                try{inactiveMain=inactive.isEmpty()?null:PlusPreset.parse(inactive,false,30)[0];}
                catch(IllegalArgumentException malformed){inactiveMain=null;}
                mode=layout;
            }else switchMode(layout);
            name = data.getString("name");
            stageNames[0]=data.optString("usrName"); stageNames[1]=data.optString("spkName"); stageNames[2]=data.optString("allName");
            draft = new PlusPreset.Channel[]{shared, match[0], match[1]};
            currentBank = 0;
            return true;
        } catch (IllegalArgumentException | JSONException error) {
            return false;
        }
    }

    private void saveInline(){
        boolean layered=mode==PlusPreset.Mode.LAYERED;
        responsePlot.update(PlusPreset.response(draft[0],48000),layered?
                new PeqResponse[]{PlusPreset.response(draft[1],48000),PlusPreset.response(draft[2],48000)}:new PeqResponse[1]);
        saveFilterEdit(true);
    }
    private int editStage(){return mode==PlusPreset.Mode.SHARED?2:currentBank;}
    private org.json.JSONArray editSteps(String key) {
        try {return new org.json.JSONArray(storage.getString(key,"[]"));}
        catch(JSONException malformed){return new org.json.JSONArray();}
    }
    private void pushStep(SharedPreferences.Editor change,String key,String stageText) {
        org.json.JSONArray steps=editSteps(key);
        if(steps.length()>=40)steps.remove(0);
        steps.put(stageText);
        change.putString(key,steps.toString());
    }
    private String stageText(int stage) {
        return PlusPreset.file(stage==1?new PlusPreset.Channel[]{draft[1],draft[2]}:
                new PlusPreset.Channel[]{mainFor(stage==2?PlusPreset.Mode.SHARED:PlusPreset.Mode.LAYERED)},stage==1?20:30);
    }
    private void showHistoryState(){
        if(undoEdit==null || redoEdit==null)return;
        int stage=editStage();
        undoEdit.setEnabled(!libraryBusy && editSteps("undo:"+stage).length()>0);
        redoEdit.setEnabled(!libraryBusy && editSteps("redo:"+stage).length()>0);
    }
    private void saveFilterEdit(boolean typing) {
        int stage=editStage();
        SharedPreferences.Editor change=storage.edit();
        try {
            JSONObject before=new JSONObject(storage.getString("draft","{}"));
            String previous=before.optString(stage==1?"match":"shared");
            if(!previous.isEmpty() && !previous.equals(stageText(stage))){
                if(storage.getString("reload:"+stage,"").isEmpty())change.putString("reload:"+stage,previous);
                pushStep(change,"undo:"+stage,previous);
                change.remove("redo:"+stage);
            }
        } catch(JSONException malformed){change.remove("undo:"+stage).remove("redo:"+stage);}
        saveDraft(typing,change);
        showHistoryState();
    }
    private void moveEdit(boolean undo){
        if(libraryBusy || blind.active() || blindBusy)return;
        int stage=editStage();
        String from=(undo?"undo:":"redo:")+stage;
        org.json.JSONArray steps=editSteps(from);
        if(steps.length()==0)return;
        try {
            PlusPreset.Channel[] recovered=PlusPreset.parse(steps.getString(steps.length()-1),stage==1,stage==1?20:30);
            PlusPreset.Channel[] next=draft.clone();
            if(stage==1){next[1]=recovered[0];next[2]=recovered[1];}else next[0]=recovered[0];
            PlusPreset.banks(mode,next[0],next[1],next[2],true);
            SharedPreferences.Editor change=storage.edit();
            pushStep(change,(undo?"redo:":"undo:")+stage,stageText(stage));
            steps.remove(steps.length()-1);
            change.putString(from,steps.toString());
            draft=next;
            saveDraft(false,change);
            render(true);
        }catch(IllegalArgumentException | JSONException problem){showError(problem.getMessage());}
    }
    private void saveDraft(){saveDraft(false,storage.edit());}
    private void saveDraft(boolean typing,SharedPreferences.Editor change) {
        synchronized (storage) {
            long revision = storage.getLong("editRevision", 0) + 1;
            change.putString("draft", draftJson()).putString("editLease",binding().lease)
                .putLong("editRevision", revision).putLong("pendingRevision",revision).apply();
        }
        editHandler.removeCallbacks(flushEdit);
        if(typing && writable()){
            syncText("Syncing EQ…");editHandler.postDelayed(flushEdit,180);
        }else syncPending();
    }

    private void saveNamed() {
        EditText value = new EditText(this);
        value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        value.setText(name);
        value.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        LinearLayout fields = form();
        labelled(fields, "Preset name", value);
        AlertDialog prompt = new AlertDialog.Builder(this).setTitle("Save preset on this phone").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        prompt.setOnShowListener(ignored -> prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String entered = value.getText().toString().trim();
            if (entered.isEmpty()) { value.setError("Enter a preset name."); return; }
            if (storage.contains("preset:" + entered)) {
                new AlertDialog.Builder(this).setTitle("Replace " + entered + "?").setNegativeButton("Cancel", null)
                    .setPositiveButton("Replace", (dialog, which) -> saveNamed(entered, prompt)).show();
            } else saveNamed(entered, prompt);
        }));
        prompt.show();
    }

    private void saveNamed(String entered, AlertDialog prompt) {
        name = entered;rememberPreset();
        String serialized = draftJson();
        storage.edit().putString("preset:" + name, serialized).putString("draft", serialized).apply();
        prompt.dismiss();
        toast("Preset saved on this phone.");
        render(true);
    }

    private void pick(int request) {
        Intent file = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
        startActivityForResult(file, request);
    }

    private void exportBank(boolean matching) {
        try {
            PlusPreset.Banks plan = PlusPreset.banks(mode, draft[0], draft[1], draft[2], true);
            PlusPreset.Channel[] channels = matching ? plan.match : new PlusPreset.Channel[]{plan.user};
            int slots = matching ? 20 : mode == PlusPreset.Mode.LAYERED ? 10 : 30;
            awaitingExport = PlusPreset.file(channels, slots);
            String bank = matching ? "spk" : mode == PlusPreset.Mode.LAYERED ? "usr" : "b30";
            String filename = (name.isEmpty() ? "YinYang-EQ" : name).replaceAll("[^A-Za-z0-9_-]+", "-");
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "x5k.plus." + bank + "." + filename + ".txt");
            startActivityForResult(save, EXPORT);
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==PlusBlindPanel.FIRST_FILE || request==PlusBlindPanel.SECOND_FILE){
            if(result!=RESULT_OK || data==null || data.getData()==null)return;
            Uri uri=data.getData();
            io.execute(()->{
                try{
                    String fileName="Imported Main EQ";
                    try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
                        if(cursor!=null && cursor.moveToFirst() && !cursor.isNull(0))fileName=cursor.getString(0);
                    }
                    String contents=readText(uri), title=fileName.length()>128?fileName.substring(0,128):fileName;
                    runOnUiThread(()->{if(isDestroyed())return;try{blind.file(request-PlusBlindPanel.FIRST_FILE,title,contents);}
                        catch(IOException e){showError(e.getMessage());}});
                }catch(IOException | RuntimeException e){runOnUiThread(()->{if(!isDestroyed())showError("Could not open the comparison file. "+e.getMessage());});}
            });
            return;
        }
        if (request == PlusConnection.ENABLE_RADIO) { connection.enabledRadio(result == RESULT_OK); return; }
        if(request==LAB_RESULT && result==RESULT_OK && data!=null) {
            String tuning=data.getStringExtra("tuningText");
            if(tuning==null) navigate(data.getIntExtra("nav",EQ));
            else try {
                String match=data.getStringExtra("matchText");
                PlusPreset.Channel[] next=draft.clone();
                PlusPreset.Mode layout=match!=null && !match.isEmpty()?PlusPreset.Mode.LAYERED:mode;
                next[0]=mainFor(layout);
                if(match!=null && !match.isEmpty()){
                    PlusPreset.Channel[] ears=PlusPreset.parse(match,true,20);next[1]=ears[0];next[2]=ears[1];
                }
                if(!tuning.isEmpty())next[0]=PlusPreset.parse(tuning,false,30)[0];
                PlusPreset.banks(layout,next[0],next[1],next[2],true);
                switchMode(layout);draft=next;
                if(!tuning.isEmpty())rememberStages(mode==PlusPreset.Mode.SHARED?2:0);
                if(match!=null && !match.isEmpty())rememberStages(1);
                name=data.getStringExtra("tuningName");currentBank=tuning.isEmpty()?1:0;page=0;
                storage.edit().putString("draft",draftJson()).remove("pendingRevision").apply();
                String id=data.getStringExtra("tuningId");
                if(id!=null && !id.isEmpty())getSharedPreferences("peq-workbench",MODE_PRIVATE).edit().putString("receivedTuningId",id).apply();
                syncText("Library draft loaded."); render(false);
                applyDraftPrompt=new AlertDialog.Builder(this).setTitle("Apply this draft to the Plus?").setMessage(name)
                    .setPositiveButton("Use EQ",(d,w)->{if(writable())saveDraft();})
                    .setNegativeButton("Keep on phone",null).setOnDismissListener(d->applyDraftPrompt=null).show();
                applyDraftPrompt.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(writable());
            } catch(IllegalArgumentException e) {showError(e.getMessage());}
            return;
        }
        if (request != IMPORT_SHARED && request != IMPORT_MATCH && request != EXPORT) return;
        String pending = awaitingExport;
        if (request == EXPORT) awaitingExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int limit = mode == PlusPreset.Mode.SHARED ? 30 : request == IMPORT_MATCH
            ? PlusPreset.matchCapacity(draft[0]) : PlusPreset.sharedCapacity(draft[1], draft[2]);
        io.execute(() -> {
            try {
                if (request == EXPORT) {
                    if (pending == null) throw new IOException("Prepare the preset export again.");
                    try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IOException("The selected file could not be opened.");
                        output.write(pending.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> toast("Preset file exported."));
                } else {
                    boolean stereo = request == IMPORT_MATCH;
                    PlusPreset.Channel[] imported = PlusPreset.parse(readText(uri), stereo, limit);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        if (stereo) { draft[1] = imported[0]; draft[2] = imported[1]; }
                        else draft[0] = imported[0];
                        rememberStages(stereo?1:mode==PlusPreset.Mode.SHARED?2:0);
                        currentBank = stereo ? 1 : 0;
                        page = 0;
                        saveDraft();
                        render(false);
                        toast(stereo ? "Left and right match imported." : "Main EQ imported.");
                    });
                }
            } catch (IOException | IllegalArgumentException error) {
                runOnUiThread(() -> showError(error.getMessage()));
            }
        });
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("The selected file could not be opened.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[4096];
            int count;
            while ((count = input.read(block)) != -1) {
                if (bytes.size() + count > 100000) throw new IOException("Choose a preset text file under 100 KB.");
                bytes.write(block, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void syncText(String message) {
        syncMessage = message;
        if(syncLabel==null)return;
        if("Syncing EQ…".equals(message))syncLabel.syncing();
        else if("EQ verified on the 5K Plus.".equals(message))syncLabel.verified();
        else syncLabel.message(message);
    }

    private void syncPending() {
        if(swapPending() || channelSwapBusy){syncText("Finish the channel swap in Audio first.");return;}
        if(blind.active() || blindBusy){syncText("A blind comparison is active.");return;}
        long revision = storage.getLong("pendingRevision", 0);
        if (reversible.pending()) { syncText("Open Debug to finish the EQ restoration check."); return; }
        if (revision == 0 || revision == queuedRevision) return;
        if (connection.status == null) { syncText("Phone draft · Waiting for your 5K Plus."); return; }
        PlusPreset.Mode layout = mode;
        PlusPreset.Banks plan;
        try { plan = PlusPreset.banks(layout, draft[0], draft[1], draft[2], true); }
        catch (IllegalArgumentException error) { syncText(error.getMessage()); return; }
        if (!writable()) { syncText("Live EQ requires firmware 1.0.3."); return; }
        queuedRevision = revision;
        applying = true;
        syncText("Syncing EQ…");
        connection.enqueue((link, state) -> {
            // A replaced edit can be skipped. A started write must finish verification or rollback.
            synchronized (storage) {
                if (blind.active() || storage.getLong("pendingRevision", 0) != revision) return;
                storage.edit().remove("pendingRevision").apply();
            }
            try {
                new PlusApply(link, state).apply(layout, plan);
                runOnUiThread(() -> {
                    if (!isDestroyed() && queuedRevision == revision) {
                        applying=false;
                        if(storage.getLong("editRevision",0)==revision && connection.status!=null)syncText("EQ verified on the 5K Plus.");
                    }
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        applying = false; syncText("Sync failed. " + error.getMessage());
                        showError("EQ sync failed. " + error.getMessage());
                    }
                });
                throw error;
            }
        });
    }

    void refreshBlind(){if(page==BLIND_PAGE && !isDestroyed())render(true);}
    String blindDraft(){return draftJson();}
    void blindFile(int slot){pick(PlusBlindPanel.FIRST_FILE+slot);}
    void blindMessage(String message){blind.notice=message;refreshBlind();}
    void blindReconnect(){if(connection.status==null)retryDiscovery();else blindMessage("Live comparison requires Plus firmware 1.0.3.");}
    interface BlindJob{String run(PlusApply device,String identity)throws IOException;}
    void blindJob(BlindJob job){blindJob(job,null);}
    void blindJob(BlindJob job,Runnable onSuccess){
        if(swapPending() || channelSwapBusy){blindMessage("Finish the channel swap in Audio first.");return;}
        if(!writable()){blindMessage("Connect your linked Plus with firmware 1.0.3.");return;}
        if(blindBusy || libraryBusy || applying || loadingLinkedEq || (!blind.active() && storage.contains("pendingRevision"))){
            blindMessage("Wait for the current EQ operation to finish.");return;
        }
        if(reversible.pending()){blindMessage("Open Debug and finish the saved EQ restoration check first.");return;}
        int epoch=sessionEpoch;
        blindBusy=true;blind.notice="Checking the working EQ…";refreshBlind();
        if(!connection.enqueue((link,state)->{
            String result;boolean success=false;
            try{
                DeviceProfile actual=DeviceProfile.plus(PlusDebugCheck.identity(link));
                binding().require(this,actual);
                result=job.run(new PlusApply(link,state),actual.key);success=true;
            }catch(IOException | IllegalArgumentException e){result=e.getMessage()==null?"Comparison interrupted. Reconnect the same Plus.":e.getMessage();}
            final String status=result;final boolean verified=success;
            runOnUiThread(()->{
                if(isDestroyed() || epoch!=sessionEpoch)return;
                blindBusy=false;blindMessage(status);
                if(verified && onSuccess!=null){toast(status);onSuccess.run();}
            });
        })){blindBusy=false;blindMessage("Reconnect the linked Plus to continue.");}
    }

    private boolean swapPending() { return storage.contains(SWAP_JOURNAL); }

    private boolean canSwapChannels() {
        return writable() && levels != null && !levels.voice && !applying && !libraryBusy
                && !blind.active() && !blindBusy && !reversible.pending()
                && (swapPending() || !storage.contains("pendingRevision"));
    }

    private void updateChannelSwap() {
        if (audioPanel != null) audioPanel.routing(canSwapChannels(),swapPending(),channelSwapBusy,levels);
    }

    private void askChannelSwap() {
        if (!canSwapChannels() || channelSwapBusy) return;
        boolean shown = levels.channelsSwapped;
        if (swapPending()) { swapChannels(shown); return; }
        new AlertDialog.Builder(this).setTitle(shown ? "Restore normal channels?" : "Swap left and right?")
                .setMessage("The Plus changes output routing and swaps the active left and right Channel Match filters, including preamps. Main EQ and saved presets stay in place.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->swapChannels(shown)).show();
    }

    private void swapChannels(boolean shown) {
        if (!canSwapChannels() || channelSwapBusy) return;
        channelSwapBusy = true;
        updateChannelSwap();
        int epoch = sessionEpoch;
        if (!connection.enqueue((link,state)->{
            PlusChannelSwap.Result outcome = null;
            String problem = null;
            try {
                if (PlusLevels.read(link).voice) throw new IOException("Wait for the call to finish.");
                DeviceProfile actual = DeviceProfile.plus(PlusDebugCheck.identity(link));
                binding().require(this,actual);
                outcome = PlusChannelSwap.run(new PlusApply(link,state),actual.key,shown,new PlusChannelSwap.Journal(){
                    public byte[] load() throws IOException {
                        String value = storage.getString(SWAP_JOURNAL,null);
                        if (value == null) return null;
                        try { return java.util.Base64.getDecoder().decode(value); }
                        catch (IllegalArgumentException bad) { throw new IOException("Unreadable channel swap recovery record.",bad); }
                    }
                    public void save(byte[] bytes) throws IOException {
                        if (!storage.edit().putString(SWAP_JOURNAL,java.util.Base64.getEncoder().encodeToString(bytes)).commit())
                            throw new IOException("Could not save channel swap recovery.");
                    }
                });
            } catch (IOException error) { problem = error.getMessage(); }
            final PlusChannelSwap.Result verified = outcome;
            final String failure = problem;
            runOnUiThread(()->{
                if (isDestroyed() || epoch != sessionEpoch) return;
                channelSwapBusy = false;
                if (verified == null) showSwapProblem(failure);
                else finishChannelSwap(verified);
                updateChannelSwap();
            });
        })) {
            channelSwapBusy = false;
            updateChannelSwap();
            toast("Reconnect your 5K Plus.");
        }
    }

    private void showSwapProblem(String message) {
        AlertDialog.Builder problem = new AlertDialog.Builder(this).setTitle("Channel swap could not finish")
                .setMessage(message == null ? "Reconnect your 5K Plus and check its channel routing." : message)
                .setPositiveButton("Close",null);
        if (swapPending()) problem.setNeutralButton("Keep device settings",(d,w)->new AlertDialog.Builder(this)
                .setTitle("Keep the routing currently on the 5K Plus?")
                .setMessage("This clears the interrupted operation. Review your routing and Channel Match before applying another preset.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Keep settings",(confirm,button)->{
                    if (!storage.edit().remove(SWAP_JOURNAL).remove("pendingRevision").commit()) {
                        showError("Could not close the channel swap.");
                        return;
                    }
                    updateChannelSwap();
                }).show());
        problem.show();
    }

    private void finishChannelSwap(PlusChannelSwap.Result result) {
        SharedPreferences.Editor change = storage.edit().remove(SWAP_JOURNAL);
        PlusPreset.Channel[] previous = draft;
        if (result.layered && mode == PlusPreset.Mode.LAYERED && storage.contains("draft")) {
            draft = new PlusPreset.Channel[]{draft[0],draft[2],draft[1]};
            change.putString("draft",draftJson()).remove("undo:1").remove("redo:1");
            String reload = storage.getString("reload:1","");
            if (!reload.isEmpty()) {
                try {
                    PlusPreset.Channel[] saved = PlusPreset.parse(reload,true,20);
                    change.putString("reload:1",PlusPreset.file(new PlusPreset.Channel[]{saved[1],saved[0]},20));
                } catch (IllegalArgumentException invalid) { change.remove("reload:1"); }
            }
        }
        if (!change.commit()) {
            draft = previous;
            showError("Routing verified. Finish the swap again to update your phone draft.");
            return;
        }
        toast(result.swapped ? "Swapped channels verified." : "Normal channel routing verified.");
        syncPending();
    }

    private void chooseUsbMode(PlusLevels shown,int target){
        if(!writable() || !UsbMode.supported(target) || shown.usbMode==target)return;
        int epoch=sessionEpoch;
        new AlertDialog.Builder(this).setTitle("Switch USB mode to "+UsbMode.values()[target].label+"?")
            .setMessage("USB audio may briefly disconnect. If the control link closes, reconnect to read the active mode.")
            .setNegativeButton("Cancel",null).setPositiveButton("Switch",(d,w)->{
                if(epoch!=sessionEpoch || !writable()){toast("Reconnect and check the current USB mode.");return;}
                if(applying || libraryBusy || reversible.pending() || channelSwapBusy || swapPending()){toast("Finish the current device operation before changing USB mode.");return;}
                connection.enqueue((link,state)->{
                    try{
                        shown.setUsbMode(link,state.packetSize,target);
                        runOnUiThread(()->{if(epoch==sessionEpoch)toast("Verified USB mode · "+UsbMode.values()[target].label);});
                    }catch(IOException e){
                        runOnUiThread(()->{if(epoch==sessionEpoch)toast("USB mode unverified. "+e.getMessage());});
                    }
                });
            }).show();
    }
    private void changeInput(PlusLevels shown,int source) {
        if(!writable() || inputPending)return;
        int epoch=sessionEpoch;
        String address=connection.address();
        markInputPending(true);
        boolean enqueued=connection.enqueue((link,state)->{
            if(epoch!=sessionEpoch)return;
            String result;
            try {
                PlusLevels fresh=PlusLevels.read(link);
                if(fresh.voice)throw new IOException("Wait for the call to finish.");
                int phoneTransport=phoneMedia.routedTransport(address);
                long playingPhone=fresh.activePhonePeer(phoneTransport);
                long targetPeer=fresh.bluetoothInputPeer(source);
                boolean resumePhone=targetPeer!=0 && targetPeer==fresh.phonePeer(phoneTransport)
                        && targetPeer==shown.bluetoothInputPeer(source) && !fresh.playingSelectedInput(shown,source);
                boolean resumed=resumePhone && epoch==sessionEpoch && hasWindowFocus()
                        && phoneMedia.send(address,phoneTransport,false);
                if(resumed) {
                    // Let Android start feeding the chosen source before asking the DAC to select it.
                    android.os.SystemClock.sleep(400);
                    if(epoch!=sessionEpoch)return;
                    fresh=PlusLevels.read(link);
                }
                fresh.selectInput(link,state.packetSize,shown,source);
                PlusLevels after=PlusLevels.read(link);
                boolean paused=false;
                if(playingPhone!=0 && playingPhone!=targetPeer && after.activePhonePeer(phoneTransport)==playingPhone
                        && epoch==sessionEpoch && hasWindowFocus()) {
                    // Give firmware the first opportunity to perform the switch.
                    android.os.SystemClock.sleep(180);
                    if(epoch!=sessionEpoch)return;
                    after=PlusLevels.read(link);
                    paused=epoch==sessionEpoch && hasWindowFocus() && after.activePhonePeer(phoneTransport)==playingPhone
                            && phoneMedia.send(address,phoneTransport,true);
                }
                if(resumed || paused) {
                    after=awaitInput(link,fresh,after,source,epoch);
                }
                boolean switched=after.playingSelectedInput(fresh,source);
                String target=source==PlusLevels.USB_INPUT ? "USB DAC" : "Bluetooth "+source;
                String active=after.status(state).inputLabel();
                if(switched)result="Active input · "+active;
                else if(after.voice)result="Input saved. Wait for the call to finish.";
                else if(after.playing)result="Preferred input saved. Still playing "+active+".";
                else result="Start playback on "+target+".";
            }catch(IOException e){result=e.getMessage();}
            String message=result;
            runOnUiThread(()->{
                if(epoch!=sessionEpoch)return;
                markInputPending(false);
                toast(message);
            });
        });
        if(!enqueued)markInputPending(false);
    }
    private PlusLevels awaitInput(PlusUsbProtocol.Transport link,PlusLevels selected,PlusLevels after,int input,int epoch)
            throws IOException {
        for(int i=0;i<6 && epoch==sessionEpoch && !after.voice && !after.playingSelectedInput(selected,input);i++) {
            android.os.SystemClock.sleep(200);
            if(epoch!=sessionEpoch)break;
            after=PlusLevels.read(link);
        }
        return after;
    }
    private void markInputPending(boolean pending) {
        inputPending=pending;
        if(audioPanel!=null)audioPanel.inputPending(pending);
    }
    private void changeDac(PlusLevels shown, boolean amp, int choice) {
        if (!writable() || channelSwapBusy || swapPending()) return;
        connection.enqueue((link,state) -> {
            try {
                if (PlusLevels.read(link).voice) throw new IOException("Wait for the call to finish.");
                shown.setDac(link,state.packetSize,amp,choice);
            } catch (IOException e) { runOnUiThread(() -> toast(e.getMessage())); }
        });
    }
    private void adjustVolume(PlusLevels shown, int target) {
        if (!writable() || shown == null) return;
        connection.enqueue((link,state) -> {
            try { PlusLevels.read(link).setGain(link, state.packetSize, shown, target); }
            catch (IOException e) { runOnUiThread(() -> toast(e.getMessage())); }
        });
    }

    private void openOfficial() {
        Intent app = getPackageManager().getLaunchIntentForPackage("com.qudelix.qudelix");
        if (app == null) app = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.qudelix.qudelix"));
        try {
            startActivity(app);
        } catch (android.content.ActivityNotFoundException error) {
            toast("The Qudelix app or a compatible browser is unavailable.");
        }
    }

    private void toast(String message) {
        if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void showError(String message) {
        if (!isDestroyed()) new AlertDialog.Builder(this).setTitle("Preset needs attention").setMessage(message)
            .setPositiveButton("OK", null).show();
    }

}
