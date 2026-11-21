package com.yinyangeq.qudelixprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ProbeActivity extends AppearanceActivity {
    static final String OPEN_COMPATIBILITY = "open_firmware_compatibility";
    private static final int FIRST_BLUETOOTH_REQUEST = 10;
    private static final int REQUEST_SYSTEM_BLUETOOTH = 5;
    private static final String SYSTEM_PAIRING_PENDING = "system_pairing_pending";
    private static final String SYSTEM_PAIRING_RETURNED = "system_pairing_returned";
    private static final int REQUEST_LOG_EXPORT = 2;
    private static final int REQUEST_EQ_EXPORT = 4;
    private static final String LAST_COMPATIBILITY_REPORT = "last_compatibility_report";
    private static final long PROBE_TIMEOUT_MS = 10_000;
    private static final long DIRECTORY_TIMEOUT_MS = 60_000;
    private static final long EQ_TEST_TIMEOUT_MS = 20_000;
    private static final long COMPATIBILITY_STEP_TIMEOUT_MS = 9_000;
    private static final long COMPATIBILITY_TIMEOUT_MS = 80_000;
    private static final String SAVED_STATUS = "probe_status";
    private static final String SAVED_SNAPSHOT = "pending_eq_snapshot";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Attempt active;
    private CompanionScreen screen;
    private boolean diagnostics, returningToDevice;
    private boolean systemPairingPending, systemPairingReturned;
    private int bank;
    private java.util.ArrayDeque<BluetoothDevice> connectCandidates;
    private boolean requestedEnable;
    private BluetoothDevice presetDevice;
    private PresetDirectory library;
    private Button connect;
    private Button compatibilityCheck, compatibilityReport;
    private Button readEq;
    private Button reversibleTest;
    private Button simulate;
    private Button simulateDrop;
    private Button exportLog;
    private TextView status;
    private int diagnosticPage;
    private final View[] diagnosticSections = new View[4];
    private final Button[] diagnosticTabs = new Button[4];
    private ProbeLog log;
    private EqTestJournal recovery;
    private String pendingSnapshot;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log = new ProbeLog(getFilesDir());
        recovery = new EqTestJournal(getFilesDir());
        if (savedInstanceState != null) pendingSnapshot = savedInstanceState.getString(SAVED_SNAPSHOT);
        if (savedInstanceState == null) {
            String version = "unknown";
            try {
                version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException ignored) { }
            log.record("Probe " + version + " opened on " + Build.MANUFACTURER + " " + Build.MODEL
                    + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        }
        diagnostics = savedInstanceState == null ? getIntent().getBooleanExtra(OPEN_COMPATIBILITY,false)
                : savedInstanceState.getBoolean("diagnostics");
        systemPairingPending = savedInstanceState != null && savedInstanceState.getBoolean(SYSTEM_PAIRING_PENDING);
        requestedEnable = savedInstanceState != null && savedInstanceState.getBoolean("requestedEnable");
        systemPairingReturned = savedInstanceState != null && savedInstanceState.getBoolean(SYSTEM_PAIRING_RETURNED);
        diagnosticPage = savedInstanceState == null ? 0 : savedInstanceState.getInt("diagnosticPage");
        bank = savedInstanceState == null ? getIntent().getIntExtra("bank",0) : savedInstanceState.getInt("bank");
        screen = new CompanionScreen(this, () -> navigate(false), () -> navigate(true),
                this::connectOrRefresh, dest -> CompanionNavigation.go(this,dest,deviceAddress()),
                group -> { bank=group; renderLibrary(); }, this::editor);
        returningToDevice=savedInstanceState==null?getIntent().getBooleanExtra("connectRadio",false) || getIntent().getBooleanExtra("pairedPicker",false):savedInstanceState.getBoolean("returningToDevice");
        if(returningToDevice)screen.connectionOnly();
        LinearLayout content = screen.diagnostics;
        TextView title = addText(content, "Debug", 30);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        connect = CompanionActions.secondary(this, "Probe paired 5K", () -> selectDevice(Operation.PROBE));
        compatibilityCheck = CompanionActions.primary(this, "Check firmware compatibility",
                () -> selectDevice(Operation.COMPATIBILITY));
        compatibilityReport = CompanionActions.secondary(this, "View compatibility report", this::showCompatibilityReport);
        readEq = CompanionActions.secondary(this, "Read and export active EQ", () -> selectDevice(Operation.CAPTURE));
        reversibleTest = CompanionActions.secondary(this, "",
                () -> selectDevice(recovery.exists() ? Operation.CHECK : Operation.TEST));
        refreshEqTestButton();
        simulate = CompanionActions.secondary(this, "Run offline simulation", () -> simulate(false));
        simulateDrop = CompanionActions.secondary(this, "Simulate a dropped connection", () -> simulate(true));
        exportLog = CompanionActions.secondary(this, "Export debug log", () -> chooseDestination(REQUEST_LOG_EXPORT));

        String[] groups = {"Firmware", "5K tests", "Simulations", "Logs"};
        for (int i = 0; i < diagnosticTabs.length; i++) {
            int page = i;
            diagnosticTabs[i] = CompanionActions.secondary(this, groups[i], () -> selectDiagnosticPage(page));
        }
        content.addView(LabStyle.switcher(this, diagnosticTabs[0], diagnosticTabs[1], -1));
        content.addView(LabStyle.switcher(this, diagnosticTabs[2], diagnosticTabs[3], -1));
        diagnosticSections[0] = diagnosticSection(content, "Firmware compatibility",
                "Check 5K replies after a firmware update. The check only reads device settings.",
                compatibilityCheck, compatibilityReport);
        diagnosticSections[1] = diagnosticSection(content, "5K diagnostics",
                "Pair the original 5K in Android Settings and close other controllers before running a probe or EQ test.",
                connect, readEq, reversibleTest);
        diagnosticSections[2] = diagnosticSection(content, "Offline simulations", null, simulate, simulateDrop);
        diagnosticSections[3] = diagnosticSection(content, "Logs",
                "The log includes command timings, connection errors, device tests and EQ file failures.", exportLog);
        String initialStatus = recovery.exists()
                ? "An EQ test is pending. Keep headphones unplugged and tap Check EQ restoration."
                : "Ready. Unofficial Qudelix compatibility probe.";
        status = addText(content, savedInstanceState == null ? initialStatus
                : savedInstanceState.getString(SAVED_STATUS, initialStatus), 16);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setMaxLines(3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Button result = CompanionActions.secondary(this, "View result", () ->
                new AlertDialog.Builder(this).setTitle("Diagnostic result").setMessage(status.getText())
                        .setPositiveButton("Close", null).show());
        content.addView(result);
        selectDiagnosticPage(recovery.exists() && savedInstanceState == null && !diagnostics ? 1 : diagnosticPage);
        screen.tab(diagnostics);
        renderLibrary();
        setControlsEnabled(true);
    }

    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        if (active==null) {
            bank=intent.getIntExtra("bank",bank);
            renderLibrary();
            boolean compatibility = intent.getBooleanExtra(OPEN_COMPATIBILITY, false);
            if (compatibility) selectDiagnosticPage(0);
            navigate(compatibility);
        }
    }

    private void renderLibrary() { screen.render(library,bank,this::selectSlot,recovery.exists()); }

    private String deviceAddress() { return library==null || presetDevice==null ? null : presetDevice.getAddress(); }

    private void editor() {
        String address=deviceAddress();
        if(active!=null)return;
        startActivity(new Intent(this,DeviceWorkbench.class).putExtra("device",address)
                .putExtra("group",bank));
    }

    @Override boolean observesHardwareDisconnects() { return true; }

    @Override void hardwareDisconnected(String address,int transport) {
        if(transport==BluetoothDevice.TRANSPORT_LE)return;
        if (library==null || presetDevice==null || address!=null && !address.equalsIgnoreCase(presetDevice.getAddress()))return;
        library=null;
        if(active!=null && active.operation==Operation.COMPATIBILITY) {
            renderLibrary();
            return;
        }
        if(active!=null) active.library=null;
        if(active!=null) finish(active,interruptionMessage(active,"5K disconnected."));
        renderLibrary();
        setControlsEnabled(true);
        showStatus("5K disconnected. Reconnect to use device controls.");
    }

    private void navigate(boolean showDiagnostics) {
        if (active != null || diagnostics == showDiagnostics) return;
        diagnostics = showDiagnostics;
        screen.tab(diagnostics);
        if (diagnostics && recovery.exists() && diagnosticPage != 0) selectDiagnosticPage(1);
        if (!diagnostics) refreshKnownDevice();
    }

    @Override public void onBackPressed() {
        if (diagnostics && active == null) finish();
        else super.onBackPressed();
    }

    @Override protected void onStart() {
        super.onStart();
        if(isFinishing())return;
        if(getIntent().getBooleanExtra("connectRadio",false)){getIntent().removeExtra("connectRadio");connectOrRefresh();return;}
        if(getIntent().getBooleanExtra("pairedPicker",false)){
            getIntent().removeExtra("pairedPicker");selectDevice(Operation.LIST);return;
        }
        if (!systemPairingPending && (!diagnostics || presetDevice!=null)) refreshKnownDevice();
    }

    private void connectOrRefresh() {
        if (active != null || systemPairingPending) return;
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},894);return;
        }
        BluetoothAdapter radio=BluetoothPages.adapter(this);
        if(radio==null){showStatus("Bluetooth is unavailable.");return;}
        if (!radio.isEnabled()) {
            requestedEnable = true;
            openPairing();
            return;
        }
        tryCandidates(true);
    }

    private void tryCandidates(boolean allowPairing) {
        BluetoothAdapter radio = BluetoothPages.adapter(this);
        if (radio == null || !radio.isEnabled()) { showStatus("Bluetooth is off."); return; }
        String last = getPreferences(MODE_PRIVATE).getString("preset_device", "");
        connectCandidates = new java.util.ArrayDeque<>();
        for (BluetoothDevice d : radio.getBondedDevices()) {
            if(!linkedOriginal(d))continue;
            if (last.equals(d.getAddress())) connectCandidates.addFirst(d);
            else if (BluetoothPages.model(d.getName(), false)) connectCandidates.addLast(d);
        }
        if (!connectCandidates.isEmpty()) startProbe(connectCandidates.removeFirst(), Operation.LIST);
        else {
            connectCandidates = null;
            showStatus("No paired 5K found. Power it on and pair it nearby.");
            if (allowPairing) openPairing();
        }
    }

    private void openPairing() {
        systemPairingPending = true;
        try {
            startActivityForResult(BluetoothPages.pairing(this), REQUEST_SYSTEM_BLUETOOTH);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            systemPairingPending = false;
            requestedEnable = false;
            showStatus("Open Bluetooth in Android Settings and pair your Qudelix-5K, then return to EQ.");
        }
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (systemPairingPending && systemPairingReturned) {
            systemPairingPending = false;
            systemPairingReturned = false;
            boolean allowPairing = requestedEnable;
            requestedEnable = false;
            tryCandidates(allowPairing);
        }
    }

    private void refreshKnownDevice() {
        library = null;
        renderLibrary();
        setControlsEnabled(true);
        if (presetDevice == null) {
            String address = binding()==null?null:binding().profile.address;
            if (address == null || (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) return;
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) return;
            try {
                BluetoothDevice saved = adapter.getRemoteDevice(address);
                if (saved.getBondState() == BluetoothDevice.BOND_BONDED && "Qudelix-5K".equalsIgnoreCase(saved.getName())) presetDevice = saved;
            } catch (SecurityException | IllegalArgumentException ignored) { return; }
        }
        if (presetDevice != null) startProbe(presetDevice, Operation.LIST);
    }

    private void selectSlot(int slot) {
        if (library == null || presetDevice == null || active != null || recovery.exists()) return;
        if (deviceEditPending(presetDevice, bank)) {
            screen.feedback("Finish the EQ operation in PEQ before loading a preset.");
            return;
        }
        if (library.selector(bank) == 255) {
            new AlertDialog.Builder(this).setTitle("Replace unsaved EQ?")
                    .setMessage("Loading this preset replaces the unsaved "+AppearanceActivity.stages(this)[bank]+" on the 5K.")
                    .setPositiveButton("Load preset", (dialog, which) -> startProbe(presetDevice, Operation.SWITCH, slot))
                    .setNegativeButton("Cancel", null).show();
        } else {
            startProbe(presetDevice, Operation.SWITCH, slot);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshEqTestButton();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("requestedEnable", requestedEnable);
        outState.putBoolean(SYSTEM_PAIRING_PENDING, systemPairingPending);
        outState.putBoolean(SYSTEM_PAIRING_RETURNED, systemPairingReturned);
        outState.putBoolean("diagnostics", diagnostics);
        outState.putBoolean("returningToDevice",returningToDevice);
        outState.putInt("bank",bank);
        outState.putInt("diagnosticPage", diagnosticPage);
        outState.putString(SAVED_STATUS, active == null ? status.getText().toString()
                : interruptionMessage(active, "The operation paused when the screen changed."));
        outState.putString(SAVED_SNAPSHOT, pendingSnapshot);
        super.onSaveInstanceState(outState);
    }

    private View diagnosticSection(LinearLayout parent, String label, String instructions, View... actions) {
        LinearLayout section = CompanionActions.section(this, label);
        if (instructions != null) {
            TextView hint = LabStyle.text(this, instructions, 14, LabStyle.MUTED, false);
            hint.setPadding(0, LabStyle.dp(this, 3), 0, 0);
            section.addView(hint);
        }
        section.addView(CompanionActions.section(this, null, actions));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = LabStyle.dp(this, 10);
        params.bottomMargin = LabStyle.dp(this, 6);
        parent.addView(section, params);
        return section;
    }

    private void selectDiagnosticPage(int page) {
        diagnosticPage = page;
        for (int i = 0; i < diagnosticSections.length; i++) {
            diagnosticSections[i].setVisibility(page == i ? View.VISIBLE : View.GONE);
            LabStyle.markTab(diagnosticTabs[i], page == i);
        }
    }

    private TextView addText(LinearLayout layout, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(LabStyle.INK);
        text.setPadding(0, 0, 0, Math.round(12 * getResources().getDisplayMetrics().density));
        layout.addView(text);
        return text;
    }

    private void selectDevice(Operation operation) {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            showStatus("Bluetooth Classic is unavailable on this Android system.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    FIRST_BLUETOOTH_REQUEST + operation.ordinal());
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            showStatus("Bluetooth is unavailable. You can still run the offline simulation.");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                showStatus("The Bluetooth adapter is off. Enable it in Android Settings if your Android system supports it, or run an offline simulation.");
                return;
            }
            String savedAddress = null;
            if (operation == Operation.CHECK) {
                try {
                    savedAddress = recovery.read().address;
                } catch (IOException error) {
                    showStatus("The saved EQ recovery data could not be opened. Keep the disposable preset selected and export the debug log.");
                    return;
                }
            }
            List<BluetoothDevice> devices = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if(!linkedOriginal(device))continue;
                if (!"Qudelix-5K".equalsIgnoreCase(device.getName())) continue;
                if (savedAddress != null && !savedAddress.equalsIgnoreCase(device.getAddress())) continue;
                devices.add(device);
                labels.add("Qudelix-5K · " + device.getAddress().substring(12));
            }
            if (devices.isEmpty()) {
                showStatus(operation == Operation.CHECK ? "Pair the same 5K used for the EQ test."
                        : "No paired Qudelix-5K found. Tap Connect, choose Pair new device in Android Bluetooth, and select your original 5K.");
                return;
            }
            if ((operation == Operation.LIST || operation == Operation.COMPATIBILITY) && devices.size() == 1) {
                startProbe(devices.get(0), operation);
                return;
            }
            new AlertDialog.Builder(this).setTitle("Choose your 5K")
                    .setItems(labels.toArray(new String[0]), (dialog, selection) -> confirmOperation(devices.get(selection), operation))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (SecurityException ignored) {
            showStatus("Android denied Bluetooth access. Review Nearby devices permission.");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if(request==894){if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)connectOrRefresh();else showStatus("Allow Nearby devices to connect.");return;}
        int index = request - FIRST_BLUETOOTH_REQUEST;
        Operation[] operations = Operation.values();
        if (index < 0 || index >= operations.length) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) selectDevice(operations[index]);
        else {
            showStatus("Allow Nearby devices so Companion can read the paired 5K.");
            if (results.length > 0 && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                new AlertDialog.Builder(this).setTitle("Allow Nearby devices")
                        .setMessage("Open Permissions in app settings, allow Nearby devices, then return and tap Connect.")
                        .setPositiveButton("App settings", (dialog, which) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.fromParts("package", getPackageName(), null)));
                            } catch (ActivityNotFoundException | SecurityException unavailable) {
                                showStatus("Open Companion’s permissions in Android Settings and allow Nearby devices.");
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        }
    }

    private void confirmOperation(BluetoothDevice device, Operation operation) {
        if (!operation.isEqTest()) {
            startProbe(device, operation);
            return;
        }
        boolean starting = operation == Operation.TEST;
        new AlertDialog.Builder(this)
                .setTitle(starting ? "Use a disposable preset" : "Check EQ restoration")
                .setMessage(starting
                        ? "Unplug your headphones. Select a saved disposable USR PEQ preset from Custom #1–#20 and close any other controller. "
                                + "Keep this screen open while the app tests the 1 dB cut, restores the gain and preset, and verifies both after reconnecting."
                        : "Keep your headphones unplugged. The app will check the saved EQ, restore it if needed, and verify it on a fresh connection.")
                .setPositiveButton(starting ? "Run test" : "Check now", (dialog, which) -> startProbe(device, operation))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void simulate(boolean interrupted) {
        long started = SystemClock.elapsedRealtime();
        String outcome;
        try {
            outcome = interrupted ? Simulation.runInterrupted() : Simulation.run();
        } catch (IOException error) {
            outcome = "Simulation failed. " + error.getMessage();
        }
        showStatus(outcome);
        log.timing(interrupted ? "Simulate dropped connection" : "Offline simulation", SystemClock.elapsedRealtime() - started,
                outcome.startsWith("Simulation failed") ? "failed" : "complete");
        log.record(outcome);
    }

    private void showStatus(String message) {
        if (!diagnostics) {
            screen.feedback(message);
            return;
        }
        status.setText(message);
    }

    @SuppressWarnings("deprecation")
    private void chooseDestination(int request) {
        boolean eq = request == REQUEST_EQ_EXPORT;
        String fileName = eq ? "YinYang-EQ-active-preset.json" : "YinYang-EQ-Probe-debug.txt";
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(eq ? "application/json" : "text/plain")
                .putExtra(Intent.EXTRA_TITLE,fileName);
        try {
            startActivityForResult(picker, request);
        } catch (android.content.ActivityNotFoundException ignored) {
            if (eq) pendingSnapshot = null;
            showStatus("Android’s file picker is unavailable on this system.");
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (TuningActivity.originalResult(this,request,result,data)) return;
        if (request == REQUEST_SYSTEM_BLUETOOTH) {
            // Android Bluetooth settings do not return a selected device or a meaningful result code.
            systemPairingReturned = true;
            return;
        }
        if (request != REQUEST_LOG_EXPORT && request != REQUEST_EQ_EXPORT) return;
        boolean eq = request == REQUEST_EQ_EXPORT;
        String snapshot = eq ? pendingSnapshot : null;
        if (eq) pendingSnapshot = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (eq && snapshot == null) {
            showStatus("Run the EQ capture again before saving a snapshot.");
            return;
        }
        String compatibility = eq ? null : lastCompatibilityReport();
        setControlsEnabled(false);
        new Thread(() -> {
            String message;
            try {
                byte[] bytes = (eq ? snapshot : log.snapshot(compatibility)).getBytes(StandardCharsets.UTF_8);
                try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    if (stream == null) throw new IOException("Missing export stream");
                    stream.write(bytes);
                }
                message = eq ? "Active EQ snapshot saved." : "Debug log saved. You can attach the text file to the chat.";
            } catch (IOException | SecurityException error) {
                message = "The file could not be saved. Try exporting to another folder.";
            }
            String finished = message;
            main.post(() -> {
                if (isDestroyed()) return;
                setControlsEnabled(active == null);
                showStatus(finished);
            });
        }, "probe-file-export").start();
    }

    private void startProbe(BluetoothDevice device, Operation operation) {
        if (active != null) return;
        startProbe(device, operation, -1);
    }

    private void startProbe(BluetoothDevice device, Operation operation, int slot) {
        if(!linkedOriginal(device)){showStatus("Connect your linked original 5K.");return;}
        if (active != null) return;
        if ((operation == Operation.SWITCH || operation == Operation.TEST || operation == Operation.CHECK)
                && getSharedPreferences("peq-workbench", MODE_PRIVATE).contains(device.getAddress()+".auditionGroup")) {
            showStatus("A/B listening is active. Open EQ editor and select End & restore.");
            return;
        }
        if (operation == Operation.SWITCH && deviceEditPending(device, bank)) {
            showStatus("Finish the pending change in PEQ before loading a preset.");
            return;
        }
        Attempt attempt = new Attempt(operation, bank, slot, library);
        active = attempt;
        setControlsEnabled(false);
        showStatus(operation == Operation.SWITCH ? "Checking the selected preset on your 5K…" :
                operation == Operation.LIST ? "Reading saved presets from your 5K…" :
                operation == Operation.COMPATIBILITY ? "Checking firmware replies. Keep the 5K connected…" :
                "Connecting over Bluetooth Classic…");
        log.record(operation.label + " started · secure RFCOMM via paired Serial Port service");
        main.postAtTime(() -> finish(attempt, interruptionMessage(attempt, "The operation timed out. Check pairing and close other controllers.")),
                attempt, SystemClock.uptimeMillis() + operation.timeout());
        new Thread(() -> {
            String result = probe(device, attempt);
            main.post(() -> {
                if (active != attempt) {
                    if (!isDestroyed()) refreshEqTestButton();
                    return;
                }
                if (attempt.library != null) {
                    presetDevice = device;
                    getPreferences(MODE_PRIVATE).edit().putString("preset_device",device.getAddress()).apply();
                }
                if (attempt.report != null) {
                    getPreferences(MODE_PRIVATE).edit().putString(LAST_COMPATIBILITY_REPORT,attempt.report).apply();
                    log.recordCompatibilityReport(attempt.report);
                }
                finish(attempt, result);
                if (attempt.report != null) showCompatibilityReport();
                if (attempt.snapshot != null) {
                    pendingSnapshot = attempt.snapshot;
                    chooseDestination(REQUEST_EQ_EXPORT);
                }
            });
        }, "qudelix-probe").start();
    }

    private boolean deviceEditPending(BluetoothDevice device, int group) {
        android.content.SharedPreferences editing = getSharedPreferences("peq-workbench", MODE_PRIVATE);
        String prefix = device.getAddress();
        String stage = prefix + "." + group;
        return editing.contains(prefix + ".auditionGroup") || editing.contains(prefix + ".1.route.target")
                || editing.contains(stage + ".pending") || editing.getInt(stage + ".target", -1) >= 0;
    }

    private void resetEditorAfterPresetChange(BluetoothDevice device, int group) {
        android.content.SharedPreferences.Editor editing = getSharedPreferences("peq-workbench", MODE_PRIVATE).edit();
        String stage = device.getAddress() + "." + group + ".";
        for (String item : new String[]{"original", "selector", "confirmed", "pending", "target", "newName",
                "draft", "staged", "title", "document", "editUndo", "editRedo"})
            editing.remove(stage + item);
        editing.apply();
    }

    private String probe(BluetoothDevice device, Attempt attempt) {
        try (DeviceBinding.Operation operationLease=binding().begin(this)) {
            if (attempt.operation == Operation.COMPATIBILITY) {
                attempt.report = FirmwareCompatibility.run(() -> openConnection(device,attempt), attempt::isClosed);
                return "Firmware compatibility check finished. Open the report to review the results.";
            }
            if (attempt.operation == Operation.SWITCH) {
                if (recovery.exists()) throw new ProtocolException("Open Debug and finish EQ restoration before switching presets.");
                if (deviceEditPending(device, attempt.group)) throw new ProtocolException("Finish the EQ operation before switching presets.");
                attempt.library = attempt.before.switchTo(() -> openConnection(device, attempt), attempt.group, attempt.slot);
                resetEditorAfterPresetChange(device, attempt.group);
                return (attempt.group==0?"USR":"Speaker")+" preset selection verified.";
            }
            ReversibleEqTest.Connector connector = () -> openConnection(device, attempt);
            if (attempt.operation == Operation.TEST) {
                return ReversibleEqTest.run(connector, recovery, device.getAddress(), log::record);
            }
            if (attempt.operation == Operation.CHECK) {
                return ReversibleEqTest.check(connector, recovery, device.getAddress(), log::record);
            }
            try (GaiaProbe.Connection connection = openConnection(device, attempt)) {
                if (attempt.operation == Operation.LIST) {
                    attempt.library = PresetDirectory.read(connection);
                    return "USR and Speaker preset lists verified.";
                }
                if (attempt.operation == Operation.PROBE) {
                    GaiaProbe.Device info=connection.readDevice();
                    if(info.model==1 && GaiaProbe.EDITOR_FIRMWARE.equals(info.version))
                        attempt.library=PresetDirectory.read(connection,info);
                    return info.describe();
                }
                EqSnapshot snapshot = connection.read();
                attempt.snapshot = snapshot.toJson();
                return snapshot.summary();
            }
        } catch (EOFException ignored) {
            return interruptionMessage(attempt, "The control socket closed before a complete reply.");
        } catch (ProtocolException error) {
            return interruptionMessage(attempt, error.getMessage());
        } catch (SecurityException ignored) {
            return interruptionMessage(attempt, "Android denied Bluetooth access. Review Nearby devices permission.");
        } catch (IOException error) {
            if (attempt.operation.isEqTest()) return interruptionMessage(attempt, "The EQ operation could not finish.");
            String detail = error.getMessage();
            if (detail != null && detail.startsWith("GAIA ")) return detail;
            return interruptionMessage(attempt, "Could not complete the secure Bluetooth control connection. Check pairing and close other controllers.");
        }
    }

    private boolean linkedOriginal(BluetoothDevice device){
        DeviceBinding b=binding();return b!=null && b.profile.model==DeviceProfile.Model.ORIGINAL && b.radioMatches(device.getAddress());
    }
    private GaiaProbe.Connection openConnection(BluetoothDevice device, Attempt attempt) throws IOException {
        if(!linkedOriginal(device))throw new IOException("This device is outside the active link.");
        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(GaiaProbe.SERIAL_PORT);
        Runnable stopSlowRead = () -> attempt.disconnect(socket);
        boolean opened = false;
        try {
            if (!attempt.attach(socket)) throw new IOException("Probe canceled.");
            if (attempt.operation == Operation.COMPATIBILITY)
                main.postDelayed(stopSlowRead,COMPATIBILITY_STEP_TIMEOUT_MS);
            socket.connect();
            log.record("RFCOMM socket connected");
            GaiaProbe.Connection connection = new GaiaProbe.Connection(socket.getInputStream(), binding().guard(this,socket.getOutputStream()),
                    log::record, () -> {
                        main.removeCallbacks(stopSlowRead);
                        attempt.disconnect(socket);
                    });
            binding().require(this,DeviceProfile.original(connection.readDevice().model,device.getAddress()));
            opened = true;
            return connection;
        } finally {
            if (!opened) {
                main.removeCallbacks(stopSlowRead);
                attempt.disconnect(socket);
            }
        }
    }

    private void finish(Attempt attempt, String message) {
        if (active != attempt) return;
        active = null;
        main.removeCallbacksAndMessages(attempt);
        attempt.close();
        if (attempt.operation == Operation.LIST || attempt.operation == Operation.SWITCH || attempt.operation == Operation.PROBE) {
            library = attempt.library;
            renderLibrary();
        }
        setControlsEnabled(true);
        refreshEqTestButton();
        log.timing(attempt.operation.label, SystemClock.elapsedRealtime() - attempt.started, "finished");
        log.record(message);
        if (attempt.library == null || attempt.operation != Operation.LIST) showStatus(message);
        if (attempt.library != null && getSharedPreferences("peq-workbench", MODE_PRIVATE).contains(deviceAddress()+".auditionGroup"))
            screen.feedback("A/B listening is active. End & restore in EQ editor.");
        if (attempt.library != null && attempt.operation == Operation.SWITCH) screen.restoreRowFocus(attempt.slot);
        if(returningToDevice && attempt.library!=null && attempt.operation==Operation.LIST){
            connectCandidates=null;
            startActivity(new Intent(this,DeviceWorkbench.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("device",deviceAddress()).putExtra("audio",true).putExtra("devicePage",true));
            finish();return;
        }
        if (connectCandidates != null && attempt.operation == Operation.LIST) {
            if (attempt.library != null) connectCandidates = null;
            else if (!connectCandidates.isEmpty()) startProbe(connectCandidates.removeFirst(), Operation.LIST);
            else {
                connectCandidates = null;
                showStatus("No reachable 5K. Bring it closer, power it on or pair it again.");
            }
        }
    }

    private void setControlsEnabled(boolean enabled) {
        screen.enabled(enabled, recovery.exists(),enabled || (active!=null && active.operation==Operation.LIST));
        connect.setEnabled(enabled);
        compatibilityCheck.setEnabled(enabled);
        compatibilityReport.setEnabled(enabled && lastCompatibilityReport() != null);
        readEq.setEnabled(enabled && library!=null);
        reversibleTest.setEnabled(enabled && (library!=null || recovery.exists()));
        simulate.setEnabled(enabled);
        simulateDrop.setEnabled(enabled);
        exportLog.setEnabled(enabled);
    }

    private String lastCompatibilityReport() {
        return getPreferences(MODE_PRIVATE).getString(LAST_COMPATIBILITY_REPORT,null);
    }

    private void showCompatibilityReport() {
        String report = lastCompatibilityReport();
        if (report == null) return;
        new AlertDialog.Builder(this).setTitle("Firmware compatibility report").setMessage(report)
                .setPositiveButton("Export debug log",(dialog,index)->chooseDestination(REQUEST_LOG_EXPORT))
                .setNegativeButton("Close",null).show();
    }

    private void refreshEqTestButton() {
        reversibleTest.setText(recovery.exists() ? "Check EQ restoration" : "Run reversible 1 dB test");
    }

    private String interruptionMessage(Attempt attempt, String message) {
        if (attempt.operation.isEqTest() && recovery.exists()) {
            return message + " Keep headphones unplugged and tap Check EQ restoration.";
        }
        return message;
    }

    @Override protected void onStop() {
        connectCandidates = null;
        if (active != null) finish(active, interruptionMessage(active, "The operation paused when the screen closed."));
        super.onStop();
    }

    private enum Operation {
        PROBE("Probe"), CAPTURE("EQ capture"), TEST("Reversible EQ test"), CHECK("EQ restoration check"),
        LIST("Preset list"), SWITCH("Preset switch"), COMPATIBILITY("Firmware compatibility check");

        final String label;

        Operation(String label) { this.label = label; }

        boolean isEqTest() { return this == TEST || this == CHECK; }

        long timeout() {
            if (this == COMPATIBILITY) return COMPATIBILITY_TIMEOUT_MS;
            if (this == LIST || this == SWITCH || this == PROBE) return DIRECTORY_TIMEOUT_MS;
            return isEqTest() ? EQ_TEST_TIMEOUT_MS : PROBE_TIMEOUT_MS;
        }
    }

    private static final class Attempt {
        final Operation operation;
        final long started = SystemClock.elapsedRealtime();
        private BluetoothSocket socket;
        private boolean closed;
        private String snapshot;
        private String report;

        final int group, slot;
        final PresetDirectory before;
        private volatile PresetDirectory library;

        Attempt(Operation operation, int group, int slot, PresetDirectory before) {
            this.operation = operation;
            this.group = group;
            this.slot = slot;
            this.before = before;
        }

        synchronized boolean attach(BluetoothSocket value) {
            if (closed) return false;
            socket = value;
            return true;
        }

        synchronized boolean isClosed() { return closed; }

        synchronized void disconnect(BluetoothSocket value) {
            try { value.close(); } catch (IOException ignored) { }
            if (socket == value) socket = null;
        }

        synchronized void close() {
            closed = true;
            if (socket != null) disconnect(socket);
        }
    }
}
