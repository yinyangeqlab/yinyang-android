package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.content.*;
import java.io.*;
import java.util.UUID;

/** The application has one link. Each activity carries the lease it was created for. */
final class DeviceBinding {
    private static final String FILE="companion-device-link";
    private static final String LEASE="deviceLease";
    static final Object gate=new Object();
    private static int activeOperations;
    final DeviceProfile profile;
    final String lease;
    final String bluetooth;
    private DeviceBinding(DeviceProfile profile,String lease,String bluetooth){
        this.profile=profile;this.lease=lease;this.bluetooth=bluetooth;
    }
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(FILE,Context.MODE_PRIVATE);}
    static DeviceBinding current(Context c){
        synchronized(gate){
            SharedPreferences p=prefs(c);
            try{
                String token=p.getString(LEASE,"");
                if(token.isEmpty())return null;
                return new DeviceBinding(new DeviceProfile(DeviceProfile.Model.valueOf(p.getString("model","")),p.getString("address","")),
                        token,p.getString("bluetooth",""));
            }catch(IllegalArgumentException|IOException ignored){return null;}
        }
    }
    static DeviceBinding replace(Context c,DeviceBinding expected,DeviceProfile verified,String radio) throws IOException {
        synchronized(gate){
            DeviceBinding now=current(c);
            if(now==null?expected!=null:expected==null || !now.lease.equals(expected.lease))
                throw new IOException("The linked device changed. Open Devices and try again.");
            boolean same=now!=null && now.profile.matches(verified);
            if(!same)requireIdle();
            if(verified.model==DeviceProfile.Model.ORIGINAL)recoverOriginalWorkspace(c,verified);
            String token=same?now.lease:UUID.randomUUID().toString();
            if(same && radio.isEmpty())radio=now.bluetooth;
            if(!prefs(c).edit().clear().putString("model",verified.model.name()).putString("address",verified.address)
                    .putString("bluetooth",radio).putString(LEASE,token).commit())throw new IOException("Could not save the device link.");
            return new DeviceBinding(verified,token,radio);
        }
    }
    /** Older original-5K releases kept several devices in one preference file. */
    private static void recoverOriginalWorkspace(Context c,DeviceProfile verified)throws IOException{
        Context app=c.getApplicationContext();
        SharedPreferences destination=app.getSharedPreferences(verified.key+".peq-workbench",Context.MODE_PRIVATE);
        if(destination.getBoolean("deviceMigrationDone",false))return;
        SharedPreferences.Editor copy=destination.edit();
        for(java.util.Map.Entry<String,?> entry:app.getSharedPreferences("peq-workbench",Context.MODE_PRIVATE).getAll().entrySet()){
            String k=entry.getKey();
            if(!k.startsWith(verified.address+".") || destination.contains(k))continue;
            Object v=entry.getValue();
            if(v instanceof String)copy.putString(k,(String)v);
            else if(v instanceof Boolean)copy.putBoolean(k,(Boolean)v);
            else if(v instanceof Integer)copy.putInt(k,(Integer)v);
            else if(v instanceof Long)copy.putLong(k,(Long)v);
            else if(v instanceof Float)copy.putFloat(k,(Float)v);
        }
        EqTestJournal old=new EqTestJournal(app.getFilesDir());
        if(old.exists()){
            EqTestJournal.Entry record=old.read();
            if(verified.address.equalsIgnoreCase(record.address)){
                File dir=new File(app.getFilesDir(),verified.key);
                if(!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())throw new IOException("Could not open the device recovery folder.");
                EqTestJournal journal=new EqTestJournal(dir);if(!journal.exists())journal.save(record);
            }
        }
        if(!copy.putBoolean("deviceMigrationDone",true).commit())throw new IOException("Could not restore this device’s prior workspace.");
    }
    static void unlink(Context c,DeviceBinding expected)throws IOException{
        synchronized(gate){
            if(expected==null || !expected.active(c))throw new IOException("The linked device changed.");
            requireIdle();
            if(!prefs(c).edit().clear().commit())throw new IOException("Could not unlink this device.");
        }
    }
    private static void requireIdle()throws IOException{
        if(activeOperations!=0)throw new IOException("A device operation is finishing. Try switching again in a moment.");
    }
    static final class Operation implements java.io.Closeable {
        private boolean closed;
        @Override public void close(){
            synchronized(gate){if(!closed){closed=true;activeOperations--;}}
        }
    }
    Operation begin(Context c)throws IOException{
        synchronized(gate){require(c,profile);activeOperations++;return new Operation();}
    }
    static DeviceBinding forIntent(Context c,Intent i){
        DeviceBinding now=current(c);
        return now!=null && i!=null && now.lease.equals(i.getStringExtra(LEASE))?now:null;
    }
    Intent tag(Intent intent){return intent.putExtra(LEASE,lease);}
    boolean active(Context c){DeviceBinding now=current(c);return now!=null && lease.equals(now.lease);}
    void require(Context c,DeviceProfile actual)throws IOException{
        if(!profile.matches(actual) || !active(c))throw new IOException("This hardware does not match the active device link.");
    }
    boolean radioMatches(String address){return profile.address.equalsIgnoreCase(address) || (!bluetooth.isEmpty() && bluetooth.equalsIgnoreCase(address));}
    Intent editor(Context c){
        return tag(new Intent(c,profile.model==DeviceProfile.Model.PLUS?PlusActivity.class:DeviceWorkbench.class)
                .putExtra("device",profile.address));
    }
    static void manage(Activity a){
        a.startActivity(new Intent(a,DevicePicker.class).putExtra("manage",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        a.finish();
    }
    OutputStream guard(Context c,OutputStream out){
        return new FilterOutputStream(out){
            @Override public void write(byte[] data,int off,int len)throws IOException{
                synchronized(gate){require(c,profile);out.write(data,off,len);}
            }
            @Override public void write(int b)throws IOException{synchronized(gate){require(c,profile);out.write(b);}}
        };
    }
}
