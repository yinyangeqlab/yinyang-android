package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.os.Build;
import android.provider.Settings;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground control session. Discovery may fall through candidates only before a model handshake succeeds. */
final class PlusConnection {
    interface Listener {
        void changed();
        void missing(String reason);
        void levels(PlusLevels value);
        void error(String message);
    }
    interface Command { void run(PlusUsbProtocol.Transport link, PlusUsbProtocol.Status status) throws IOException; }
    private interface Open { void run(PlusUsbConnection.Operation operation) throws IOException; }
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final String USB_PERMISSION = "com.yinyangeq.q5kplus.USB_PERMISSION";
    static final int NEARBY = 81, ENABLE_RADIO = 82;
    private boolean enabling;
    private final Activity activity;
    private final DeviceBinding owner;
    private final Listener ui;
    private final SharedPreferences prefs;
    private final ArrayDeque<UsbDevice> usb = new ArrayDeque<>();
    private final ArrayDeque<BluetoothDevice> bluetooth = new ArrayDeque<>();
    private final BlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private volatile int generation;
    private volatile boolean audio;
    private boolean foreground, askedBluetooth, manualSearch;
    private String bluetoothAddress;
    private int bluetoothTransport=BluetoothDevice.TRANSPORT_AUTO;
    String address(){return bluetoothAddress!=null?bluetoothAddress:owner==null?"":owner.bluetooth.isEmpty()?owner.profile.address:owner.bluetooth;}
    private UsbDevice pendingUsb;

    PlusUsbProtocol.Status status;
    boolean searching;
    String label = "Offline";
    String batteryEta = BatteryEstimate.UNAVAILABLE;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = pendingUsb;
                if (device == null || !device.equals(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE))) return;
                pendingUsb = null;
                if (foreground && manager().hasPermission(device)) openUsb(device);
                else if (foreground) nextUsb();
            } else if (foreground && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && status == null && !searching) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && PlusUsbProtocol.known(d.getVendorId(), d.getProductId())) search();
            }
        }
    };
    PlusConnection(AppearanceActivity activity, Listener listener) {
        this.activity = activity;owner=activity.binding();
        ui = listener;
        prefs = activity.getSharedPreferences("links", Context.MODE_PRIVATE);
        IntentFilter f = new IntentFilter(USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, f);
    }
    void start() { foreground = true; if (!enabling) search(); }
    void stop() {
        foreground = false;bluetoothAddress=null;manualSearch=false;
        bluetoothTransport=BluetoothDevice.TRANSPORT_AUTO;
        generation++;
        pendingUsb = null;
        commands.clear();
        status = null;
        searching = false;
        label = "Offline";
    }
    void destroy() { stop(); activity.unregisterReceiver(receiver); }
    void bluetoothDisconnected(String address,int transport) {
        if(!foreground || status==null || bluetoothAddress==null
                || address!=null && !bluetoothAddress.equalsIgnoreCase(address)
                || transport!=BluetoothDevice.TRANSPORT_AUTO && transport!=bluetoothTransport)return;
        connectionClosed("5K Plus disconnected");
    }
    private void connectionClosed(String message) {
        generation++;
        bluetoothAddress=null;
        bluetoothTransport=BluetoothDevice.TRANSPORT_AUTO;
        commands.clear();
        status=null;
        searching=false;
        label=message;
        ui.changed();
    }
    void audio(boolean visible) { audio = visible; }
    boolean enqueue(Command command) {
        final int scheduled=generation;
        return owner!=null && owner.active(activity) && status!=null && foreground && commands.offer((link,state)->{
            if(scheduled!=generation || !owner.active(activity))return;
            try(DeviceBinding.Operation operation=owner.begin(activity)){command.run(link,state);}
        });
    }
    private UsbManager manager() { return activity.getSystemService(UsbManager.class); }
    private BluetoothAdapter adapter() {
        BluetoothManager service = activity.getSystemService(BluetoothManager.class);
        return service == null ? null : service.getAdapter();
    }
    boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 || activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }
    List<BluetoothDevice> paired() {
        List<BluetoothDevice> devices = new ArrayList<>();
        try {
            BluetoothAdapter a = adapter();
            if (a != null && hasBluetoothPermission() && a.isEnabled()) devices.addAll(a.getBondedDevices());
            devices.sort(Comparator.comparing(d -> title(d), String.CASE_INSENSITIVE_ORDER));
        } catch (SecurityException ignored) {}
        return devices;
    }
    static String title(BluetoothDevice d) {
        String name = Build.VERSION.SDK_INT >= 30 ? d.getAlias() : null;
        if (name == null) name = d.getName();
        return name == null ? "Paired device" : name;
    }
    static boolean plusName(String name) {
        return AndroidAudioOptions.isPlusName(name);
    }
    void settings() {
        try {
            Intent target = BluetoothPages.pairing(activity);
            if (BluetoothAdapter.ACTION_REQUEST_ENABLE.equals(target.getAction())) {
                enabling = true;
                activity.startActivityForResult(target, ENABLE_RADIO);
            } else activity.startActivity(target);
        } catch (ActivityNotFoundException | SecurityException e) {
            enabling = false;
            ui.error("Bluetooth settings are unavailable on this phone.");
        }
    }
    void enabledRadio(boolean accepted) {
        enabling = false;
        manualSearch = accepted;
        if (accepted && foreground) search();
        else if (!accepted) { label = "Bluetooth is off"; ui.changed(); }
    }
    void search() {
        if (!foreground || searching || status != null || owner==null || owner.profile.model!=DeviceProfile.Model.PLUS || !owner.active(activity)) return;
        searching = true;
        generation++;
        usb.clear();
        bluetooth.clear();

        label = "Looking for a 5K Plus…";
        ui.changed();
        UsbManager m = manager();
        if (m != null) for (UsbDevice d : m.getDeviceList().values())
            if (PlusUsbProtocol.known(d.getVendorId(), d.getProductId())) usb.add(d);
        nextUsb();
    }
    private void nextUsb() {
        UsbDevice d = usb.poll();
        if (d == null) { scanBluetooth(); return; }
        if (manager().hasPermission(d)) { openUsb(d); return; }
        pendingUsb = d;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        Intent i = new Intent(USB_PERMISSION).setPackage(activity.getPackageName());
        manager().requestPermission(d, PendingIntent.getBroadcast(activity, d.getDeviceId(), i, flags));
    }
    private void openUsb(UsbDevice d) {
        attempt("5K Plus · USB", null, op -> PlusUsbConnection.run(manager(), d, op), this::nextUsb);
    }
    private void scanBluetooth() {
        if (!hasBluetoothPermission()) {
            if (!askedBluetooth) {
                askedBluetooth = true;
                activity.requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"}, NEARBY);
            } else missing("Allow Nearby devices to find paired devices, or connect the Plus by USB.");
            return;
        }
        BluetoothAdapter radio=adapter();
        if(radio==null){missing("Bluetooth is unavailable on this phone.");return;}
        if(!radio.isEnabled()){searching=false;label="Bluetooth is off";ui.changed();if(manualSearch){manualSearch=false;settings();}return;}
        String last = prefs.getString("last", "");
        for (BluetoothDevice d : paired()) {
            if(!owner.radioMatches(d.getAddress()))continue;
            if (d.getAddress().equals(last)) bluetooth.addFirst(d);
            else if (plusName(d.getName()) || (Build.VERSION.SDK_INT >= 30 && plusName(d.getAlias()))) bluetooth.add(d);
        }
        nextBluetooth();
    }
    void permissionResult() {
        if (foreground && searching && pendingUsb == null) scanBluetooth();
    }
    void select(BluetoothDevice selected) {
        if(owner==null || !owner.radioMatches(selected.getAddress()))return;
        if (!foreground) return;
        if(!hasBluetoothPermission() || adapter()==null || !adapter().isEnabled()){settings();return;}
        if(!plusName(selected.getName()) && !(Build.VERSION.SDK_INT>=30 && plusName(selected.getAlias()))){ui.error("Choose a Qudelix 5K Plus.");return;}
        stop(); foreground = true; searching = true;
        bluetooth.clear(); bluetooth.add(selected);
        nextBluetooth();
    }
    void retry() { askedBluetooth=false;manualSearch=true;search(); }
    private void nextBluetooth() {
        BluetoothDevice d = bluetooth.poll();
        if (d == null) {
            if(manualSearch){manualSearch=false;missing("Linked 5K Plus unavailable. Check power and range, or open Manage linked device.");return;}
            missing("No reachable 5K Plus found. Power it on and bring it closer. If it still can’t connect, pair it again in Android settings.");
            return;
        }
        String device = title(d);
        label = "Connecting to " + device + "…";
        ui.changed();
        attempt(device + " · Bluetooth control", d, op -> PlusBluetooth.run(activity.getApplicationContext(), d, op), this::nextBluetooth);
    }
    private void missing(String reason) {
        searching = false; label = reason;
        ui.changed();
        ui.missing(reason);
    }
    private void post(int id, Runnable task) {
        activity.runOnUiThread(() -> { if (id == generation && foreground) task.run(); });
    }
    private void updateBatteryEta(BatteryEstimate estimate) {
        batteryEta=estimate.update(status.percent,status.charging?"Charging":null,android.os.SystemClock.elapsedRealtime());
    }
    private void attempt(String route, BluetoothDevice device, Open open, Runnable fallback) {
        int id = generation;
        worker.execute(() -> {
            if (id != generation) return;
            AtomicBoolean authenticated = new AtomicBoolean();
            try {
                open.run((raw, initial) -> {
                    DeviceProfile actual=DeviceProfile.plus(PlusDebugCheck.identity(raw));
                    owner.require(activity,actual);
                    PlusLevels.Stream link = new PlusLevels.Stream(new PlusUsbProtocol.Transport(){
                        public void send(byte[] report)throws IOException{
                            synchronized(DeviceBinding.gate){owner.require(activity,actual);raw.send(report);}
                        }
                        public byte[] receive(int timeout)throws IOException{return raw.receive(timeout);}
                    });
                    if (id != generation) return initial;
                    authenticated.set(true);
                    BatteryEstimate estimate=BatteryEstimate.forLink(owner.lease);
                    int transport=PlusBluetooth.transportType(raw);
                    post(id, () -> {
                        status = initial; updateBatteryEta(estimate); searching = false; manualSearch=false; label = route; bluetoothAddress=device==null?null:device.getAddress();
                        bluetoothTransport=transport;
                        if (device != null) prefs.edit().putString("last", device.getAddress()).apply();
                        ui.changed();
                    });
                    boolean subscribed = false;
                    java.util.Set<Long> previousPeers=null;
                    try {
                        while (id == generation) {
                            Command command;
                            try { command = commands.poll(audio ? 250 : 700, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                            if (id != generation) break;
                            if (command != null) command.run(link, initial);
                            PlusLevels levels = PlusLevels.read(link);
                            if(levels.hasPairingStatus){
                                boolean connected=previousPeers==null || !previousPeers.containsAll(levels.connectedPeers);
                                previousPeers=levels.connectedPeers;
                                if(connected && levels.pairing && !levels.automaticPairing && "1.0.3".equals(initial.firmware)){
                                    try{PlusUsbProtocol.stopPairing(link);}
                                    catch(IOException error){post(id,()->ui.error(error.getMessage()));}
                                }
                            }
                            boolean want = audio && levels.playing && !levels.voice && "1.0.3".equals(initial.firmware);
                            if (want != subscribed) {
                                link.resetMeter();
                                PlusLevels.meter(link, initial.packetSize, want); subscribed = want;
                            }
                            post(id, () -> { status = levels.status(initial); updateBatteryEta(estimate); ui.levels(levels); });
                        }
                    } finally {
                        if (subscribed) PlusLevels.meter(link, initial.packetSize, false);
                    }
                    return initial;
                });
            } catch (IOException | SecurityException failure) {
                post(id, () -> {
                    if (!authenticated.get()) { fallback.run(); return; }
                    connectionClosed("Connection closed. " + failure.getMessage());
                    ui.error(label);
                });
            }
        });
    }
}
