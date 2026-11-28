package com.yinyangeq.qudelixprobe;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.*;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** The only exported entry. Candidate discovery performs identity reads only. */
public final class DevicePicker extends AppearanceActivity {
    private static final int NEARBY=401;
    private static final int SAVE_LOCAL_LIBRARY=402, RESTORE_LOCAL_LIBRARY=403;
    private static final String RESTORE_OFFER="localLibraryRestoreOfferSeen";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private DeviceBinding current;
    private UsbDevice pending;
    private int generation;
    private boolean verifying;
    private boolean usingBackup;
    private AlertDialog restoreOffer;
    private boolean watchingBluetooth;
    private Set<String> knownBluetoothDevices;
    private String message="";
    private LinearLayout body;
    private final List<Runnable> bluetoothRows=new ArrayList<>();
    private final String permission="com.yinyangeq.companion.PICK_USB";
    private final BroadcastReceiver devices=new BroadcastReceiver(){
        public void onReceive(Context c,Intent intent){
            if(permission.equals(intent.getAction())){
                UsbDevice actual=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if(pending==null || !pending.equals(actual))return;
                pending=null;
                if(usb().hasPermission(actual))checkUsb(actual);
                else {message="USB permission is needed to identify this device.";draw();}
            }else {draw();}
        }
    };
    private final BroadcastReceiver bluetoothChanges=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){
            if(BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())
                    && intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.ERROR)==BluetoothDevice.BOND_BONDING)
                return;
            refreshBluetooth();
        }
    };
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        current=DeviceBinding.current(this);
        if(b!=null){
            ArrayList<String> addresses=b.getStringArrayList("knownBluetoothDevices");
            if(addresses!=null)knownBluetoothDevices=new HashSet<>(addresses);
        }
        IntentFilter filter=new IntentFilter(permission);filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(devices,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(devices,filter);
        if(current!=null && !getIntent().getBooleanExtra("manage",false)){enter(current);return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v,i)->{AppearanceActivity.applyInsets(v,i);return i;});
        addBuildLabel(this,root,20);
        ScrollView scroll=new ScrollView(this);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(this,20),dp(this,12),dp(this,20),dp(this,20));scroll.addView(body);
        setContentView(root);draw();
        if(!getIntent().getBooleanExtra("manage",false))offerLocalRestore();
    }
    @Override protected void onResume(){
        super.onResume();
        if(body==null || isFinishing())return;
        IntentFilter filter=new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        // Bluetooth broadcasts can come from a privileged process with a separate UID.
        if(Build.VERSION.SDK_INT>=33)registerReceiver(bluetoothChanges,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(bluetoothChanges,filter);
        watchingBluetooth=true;
        refreshBluetooth();
    }
    @Override protected void onPause(){
        if(watchingBluetooth){
            unregisterReceiver(bluetoothChanges);
            watchingBluetooth=false;
        }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle b){
        if(knownBluetoothDevices!=null)
            b.putStringArrayList("knownBluetoothDevices",new ArrayList<>(knownBluetoothDevices));
        super.onSaveInstanceState(b);
    }
    @Override protected void onDestroy(){
        if(restoreOffer!=null)restoreOffer.dismiss();
        generation++;unregisterReceiver(devices);io.shutdownNow();super.onDestroy();
    }
    private UsbManager usb(){return getSystemService(UsbManager.class);}
    private boolean allowed(){return Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private List<BluetoothDevice> pairedQudelixes(BluetoothAdapter adapter){
        List<BluetoothDevice> paired=new ArrayList<>(adapter.getBondedDevices());
        paired.removeIf(device->(current==null || !current.radioMatches(device.getAddress()))
                && !BluetoothPages.model(device.getName(),true)
                && !BluetoothPages.model(device.getName(),false));
        return paired;
    }
    private void refreshBluetooth(){
        BluetoothAdapter adapter=BluetoothPages.adapter(this);
        if(allowed() && adapter!=null && adapter.isEnabled()){
            try{
                Set<String> addresses=new HashSet<>();
                BluetoothDevice newlyPaired=null;
                int newCount=0;
                for(BluetoothDevice device:pairedQudelixes(adapter)){
                    String address=device.getAddress();
                    addresses.add(address);
                    if(knownBluetoothDevices!=null && !knownBluetoothDevices.contains(address)){
                        newlyPaired=device;
                        newCount++;
                    }
                }
                knownBluetoothDevices=addresses;
                if(newCount==1 && current==null && !verifying && pending==null){
                    checkRadio(newlyPaired,BluetoothPages.model(newlyPaired.getName(),true));
                    return;
                }
            }catch(SecurityException ignored){/* Permission changed while reading paired devices. */}
        }
        draw();
    }
    private TextView label(String s,int size,boolean heading){
        TextView t=text(this,s,size,heading?INK:MUTED,heading);if(heading && Build.VERSION.SDK_INT>=28)t.setAccessibilityHeading(true);
        t.setPadding(0,dp(this,8),0,dp(this,6));body.addView(t);
        return t;
    }
    @Override boolean observesBluetoothAudio(){return body!=null;}
    @Override void bluetoothAudioChanged(){
        for(Runnable row:bluetoothRows)row.run();
    }
    private void action(String name,Runnable click){
        Button b=CompanionActions.secondary(this,name,click);b.setEnabled(!usingBackup && !verifying && pending==null);body.addView(b,-1,-2);
    }
    private void draw(){
        if(body==null || isFinishing())return;
        body.removeAllViews();
        bluetoothRows.clear();
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);body.addView(top);
        top.addView(text(this,"Devices",27,INK,true),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(CompanionActions.secondary(this,"Theme",this::chooseTheme));
        if(!message.isEmpty())label(message,15,false);
        boolean linkedShown=drawBluetooth();
        if(current!=null){
            label("Device link",16,true);
            if(!linkedShown)action("Open "+current.profile.title(),()->enter(current));
            label(current.profile.bluetoothMacLabel(),14,false).setTextIsSelectable(true);
            action("Unlink device",()->new AlertDialog.Builder(this).setTitle("Unlink "+current.profile.title()+"?")
                .setMessage("Its drafts stay on this phone. Link the same device to open them again.")
                .setPositiveButton("Unlink",(d,w)->unlink()).setNegativeButton("Cancel",null).show());
        }else {
            label("Link a Qudelix for device controls, or generate EQ on this phone.",15,false);
            action("Use Auto EQ without a device",()->openTab(new Intent(this,LabActivity.class)
                    .putExtra(STANDALONE,true).putExtra("autoEq",true),-1));
        }
        label("USB",18,true);
        int count=0;
        UsbManager manager=usb();
        if(manager!=null)for(UsbDevice d:manager.getDeviceList().values()){
            if(!PlusUsbProtocol.known(d.getVendorId(),d.getProductId()))continue;
            count++;action("Qudelix USB · "+d.getDeviceName().substring(d.getDeviceName().lastIndexOf('/')+1),()->chooseUsb(d));
        }
        if(count==0)label("Connect a 5K Plus by USB to list it here.",14,false);
        label("Local library",18,true);
        action("Back up local library",()->pickLocalBackup(true));
        action("Restore local library",()->pickLocalBackup(false));
        if (ReportClient.available(this)) action("Report a bug", () -> {
            org.json.JSONObject data = ReportClient.deviceDiagnostics();
            if (current != null) ReportClient.field(data, "dac", current.profile.model.label);
            ReportClient.show(this, "companion", data);
        });
    }
    private boolean drawBluetooth(){
        label("Bluetooth",18,true);
        BluetoothAdapter adapter=BluetoothPages.adapter(this);
        if(adapter==null){label("Bluetooth is unavailable on this phone.",14,false);return false;}
        if(!allowed()){
            label("Allow Nearby devices to see which Qudelix devices are paired.",14,false);
            action("Allow Nearby devices",()->requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},NEARBY));
            action("Bluetooth settings",this::openBluetoothSettings);
            return false;
        }
        try{
            if(!adapter.isEnabled()){
                label("Bluetooth is off.",14,false);
                action("Turn on Bluetooth",this::systemPairing);
                return false;
            }
            List<BluetoothDevice> paired=pairedQudelixes(adapter);
            if(paired.isEmpty()){
                label("No Qudelix paired.",14,false);
                action("Pair a Qudelix",this::systemPairing);
                return false;
            }
            paired.sort(Comparator.comparing(d->String.valueOf(d.getName()),String.CASE_INSENSITIVE_ORDER));
            boolean linkedShown=false;
            for(BluetoothDevice d:paired){
                String address=d.getAddress();
                boolean linked=current!=null && current.radioMatches(address);
                boolean plus=linked?current.profile.model==DeviceProfile.Model.PLUS:BluetoothPages.model(d.getName(),true);
                String title=plus?"Qudelix 5K Plus":"Qudelix 5K";
                action((linked?"Open ":"Link ")+title,()->{if(linked)enter(current);else checkRadio(d,plus);});
                TextView status=label("",14,false);
                String outputAddress=Build.VERSION.SDK_INT>=28?address:paired.size()==1?null:address;
                Runnable update=()->status.setText(BluetoothPages.pairedStatus(this,outputAddress,plus)
                        +(linked?" · Linked in Companion":"")
                        +(paired.size()>1?"\n"+address:""));
                bluetoothRows.add(update);
                update.run();
                linkedShown|=linked;
            }
            action("Bluetooth settings",this::openBluetoothSettings);
            return linkedShown;
        }catch(SecurityException e){label("Allow Nearby devices to list paired devices.",14,false);return false;}
    }
    private void offerLocalRestore(){
        SharedPreferences preferences=getPreferences(MODE_PRIVATE);
        if(preferences.getBoolean(RESTORE_OFFER,false))return;
        io.execute(()->{
            if(new CompanionLocalBackup(this).hasEntries())return;
            main.post(()->{
                if(isFinishing() || isDestroyed() || current!=null || verifying || usingBackup)return;
                Runnable seen=()->preferences.edit().putBoolean(RESTORE_OFFER,true).apply();
                restoreOffer=new AlertDialog.Builder(this)
                    .setTitle("Import a previous library backup?")
                    .setMessage("Choose a library backup made by YYEQ5K. If this is your first time using the app, tap Skip.")
                    .setPositiveButton("Import backup",(dialog,which)->{seen.run();pickLocalBackup(false);})
                    .setNegativeButton("Skip",(dialog,which)->seen.run())
                    .setOnCancelListener(dialog->seen.run()).show();
            });
        });
    }

    private void pickLocalBackup(boolean exporting){
        Intent picker=new Intent(exporting?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(exporting?"application/json":"*/*");
        if(exporting)picker.putExtra(Intent.EXTRA_TITLE,"YYEQ5K-Local-Library.json");
        try{startActivityForResult(picker,exporting?SAVE_LOCAL_LIBRARY:RESTORE_LOCAL_LIBRARY);}
        catch(ActivityNotFoundException missing){message="No Android file picker is available.";draw();}
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SAVE_LOCAL_LIBRARY && request!=RESTORE_LOCAL_LIBRARY)return;
        if(result!=RESULT_OK)return;
        Uri uri=data==null?null:data.getData();
        if(uri==null || !"content".equals(uri.getScheme())){
            if(request==RESTORE_LOCAL_LIBRARY){
                Toast.makeText(this,CompanionLocalBackup.INVALID_BACKUP,Toast.LENGTH_LONG).show();
            }
            return;
        }
        usingBackup=true;
        message=request==SAVE_LOCAL_LIBRARY?"Saving library backup…":"Restoring library…";
        draw();
        io.execute(()->{
            String outcome;
            boolean completed=false;
            try{
                CompanionLocalBackup library=new CompanionLocalBackup(this);
                if(request==SAVE_LOCAL_LIBRARY){
                    byte[] contents=library.exportLibrary();
                    try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")){
                        if(output==null)throw new IOException("Could not open the backup destination.");
                        output.write(contents);
                    }
                    outcome="Library backup saved.";
                }else{
                    int count;
                    try(InputStream input=getContentResolver().openInputStream(uri)){
                        if(input==null)throw new IOException("Could not open the backup file.");
                        count=library.restore(input);
                    }
                    outcome=count==0?"No new library entries to restore.":count==1?"Restored 1 library entry.":
                        "Restored "+count+" library entries.";
                }
                completed=true;
            }catch(IOException error){
                outcome=error.getMessage();
                if(outcome==null)outcome="Could not read or write the library backup.";
            }catch(org.json.JSONException | IllegalArgumentException error){
                outcome=CompanionLocalBackup.INVALID_BACKUP;
            }catch(SecurityException error){
                outcome="Open the file picker again to grant access.";
            }
            String note=outcome;
            boolean restoreFailed=request==RESTORE_LOCAL_LIBRARY && !completed;
            main.post(()->{
                if(isFinishing() || isDestroyed())return;
                usingBackup=false;
                message=note;
                draw();
                if(restoreFailed)Toast.makeText(this,note,Toast.LENGTH_LONG).show();
            });
        });
    }
    private void systemPairing(){try{startActivity(BluetoothPages.pairing(this));}
        catch(ActivityNotFoundException|SecurityException e){openBluetoothSettings();}}
    private void openBluetoothSettings(){
        try{startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));}
        catch(ActivityNotFoundException|SecurityException e){message="Open Bluetooth in Android Settings.";draw();}
    }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r){
        super.onRequestPermissionsResult(code,p,r);
        if(code==NEARBY)refreshBluetooth();
    }
    private void chooseUsb(UsbDevice d){
        if(usb().hasPermission(d)){checkUsb(d);return;}
        pending=d;message="Allow USB access to verify this device.";draw();
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
        try{usb().requestPermission(d,PendingIntent.getBroadcast(this,d.getDeviceId(),new Intent(permission).setPackage(getPackageName()),flags));}
        catch(RuntimeException e){pending=null;message="Could not request USB permission.";draw();}
    }
    private interface Verify {DeviceProfile read() throws IOException;}
    private void checkUsb(UsbDevice d){
        verify("",()->{
            DeviceProfile[] found={null};
            PlusUsbConnection.run(usb(),d,(link,status)->{found[0]=DeviceProfile.plus(PlusDebugCheck.identity(link));return status;});
            return found[0];
        });
    }
    private void checkRadio(BluetoothDevice d,boolean plus){
        verify(d.getAddress(),()->{
            if(d.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("Pair this device in Android first.");
            if(plus){
                DeviceProfile[] found={null};
                PlusBluetooth.run(getApplicationContext(),d,(link,status)->{found[0]=DeviceProfile.plus(PlusDebugCheck.identity(link));return status;});
                return found[0];
            }
            try(BluetoothSocket s=d.createRfcommSocketToServiceRecord(GaiaProbe.SERIAL_PORT)){
                Runnable timeout=()->{try{s.close();}catch(IOException ignored){}};
                main.postDelayed(timeout,8000);
                try{
                    s.connect();
                    try(GaiaProbe.Connection link=new GaiaProbe.Connection(s.getInputStream(),s.getOutputStream(),line->{},s::close)){
                        return DeviceProfile.original(link.readDevice().model,d.getAddress());
                    }
                }finally{main.removeCallbacks(timeout);}
            }
        });
    }
    private void verify(String radio,Verify task){
        if(verifying)return;
        verifying=true;int attempt=++generation;message="Verifying device…";draw();
        io.execute(()->{
            try{
                DeviceProfile profile=task.read();
                main.post(()->{
                    if(attempt!=generation || isFinishing())return;
                    verifying=false;message="";draw();
                    if(current!=null && !current.profile.matches(profile)){
                        new AlertDialog.Builder(this).setTitle("Switch to "+profile.title()+"?")
                            .setMessage("Your "+current.profile.title()+" drafts stay saved on this phone. The app will connect only to the selected device.")
                            .setNegativeButton("Cancel",null).setPositiveButton("Switch device",(d,w)->link(profile,radio)).show();
                    }else link(profile,radio);
                });
            }catch(IOException|SecurityException e){main.post(()->{
                if(attempt!=generation || isFinishing())return;
                verifying=false;message="Could not verify the device. Check power, connection and whether another app is using it.";draw();
            });}
        });
    }
    private void link(DeviceProfile verified,String radio){
        try{DeviceBinding b=DeviceBinding.replace(this,current,verified,radio);enter(b);}
        catch(IOException e){current=DeviceBinding.current(this);message=e.getMessage();draw();}
    }
    private void unlink(){
        try{DeviceBinding.unlink(this,current);current=null;message="";draw();}
        catch(IOException e){current=DeviceBinding.current(this);message=e.getMessage();draw();}
    }
    private void enter(DeviceBinding b){startActivity(b.editor(this));finish();}
    @Override public void onBackPressed(){if(current!=null && !verifying && pending==null)enter(current);else super.onBackPressed();}
}
