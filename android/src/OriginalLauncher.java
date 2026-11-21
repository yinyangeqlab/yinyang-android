package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.os.Bundle;

/** Only a bonded original 5K previously selected in the companion can resume its workspace. */
public final class OriginalLauncher extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String saved=getSharedPreferences("ProbeActivity",MODE_PRIVATE).getString("preset_device",null);
        String device=null;
        try {
            BluetoothManager manager=getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(saved!=null && adapter!=null){
                BluetoothDevice d=adapter.getRemoteDevice(saved);
                if(d.getBondState()==BluetoothDevice.BOND_BONDED && "Qudelix-5K".equalsIgnoreCase(d.getName()))device=saved;
            }
        } catch(SecurityException | IllegalArgumentException ignored){}
        startActivity(new Intent(this,DeviceWorkbench.class).putExtra("device",device));
        finish();
    }
}
