package com.yinyangeq.qudelixprobe;

import android.bluetooth.*;
import android.content.*;
import android.media.*;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

final class BluetoothPages {
    static final String PAIR="android.settings.BLUETOOTH_PAIRING_SETTINGS";
    static final String SAMSUNG_DEVICE="com.samsung.settings.DEVICE_PROFILES_SETTINGS";
    static final String DEVICE="android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS";
    static BluetoothAdapter adapter(Context c){
        BluetoothManager manager=c.getSystemService(BluetoothManager.class);return manager==null?null:manager.getAdapter();
    }
    static Intent pairing(Context c){
        BluetoothAdapter a=adapter(c);
        try{if(a!=null && !a.isEnabled())return new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);}catch(SecurityException ignored){}
        Intent i=new Intent(PAIR);return i.resolveActivity(c.getPackageManager())!=null?i:new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
    }
    static boolean model(String name,boolean plus){
        return plus?AndroidAudioOptions.isPlusName(name):name!=null && name.replaceAll("[^A-Za-z0-9]","").equalsIgnoreCase("Qudelix5K");
    }
    static BluetoothDevice paired(Context c,String address,boolean plus){
        try{
            BluetoothAdapter a=adapter(c);if(a==null || !a.isEnabled())return null;
            BluetoothDevice one=null;int count=0;
            for(BluetoothDevice d:a.getBondedDevices()){
                if(d.getAddress().equalsIgnoreCase(address))return d;
                if(model(d.getName(),plus)){one=d;count++;}
            }
            return address==null && count==1?one:null;
        }catch(SecurityException noAccess){return null;}
    }
    static Intent details(Context c,BluetoothDevice device){
        if(device==null)return null;
        for(String action:new String[]{SAMSUNG_DEVICE,DEVICE}){
            Intent i=new Intent(action).putExtra(BluetoothDevice.EXTRA_DEVICE,device).putExtra("device_address",device.getAddress());
            Bundle args=new Bundle();args.putString("device_address",device.getAddress());i.putExtra(":settings:show_fragment_args",args);
            if(i.resolveActivity(c.getPackageManager())!=null)return i;
        }
        return null;
    }
    static String audioState(Context c,String address,boolean plus){
        BluetoothAdapter a=adapter(c);if(a==null)return "Bluetooth unavailable";
        try{if(!a.isEnabled())return "Bluetooth is off";}catch(SecurityException unavailable){return "Nearby devices permission required";}
        AudioManager manager=c.getSystemService(AudioManager.class);
        if(manager!=null)for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            int t=d.getType();
            if(t!=AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && t!=AudioDeviceInfo.TYPE_BLE_HEADSET && t!=AudioDeviceInfo.TYPE_BLE_SPEAKER)continue;
            String output = Build.VERSION.SDK_INT >= 28 ? d.getAddress() : "";
            boolean exact = BluetoothAdapter.checkBluetoothAddress(output) && !"02:00:00:00:00:00".equals(output);
            boolean known = BluetoothAdapter.checkBluetoothAddress(address);
            if (known ? exact && address.equalsIgnoreCase(output) : model(String.valueOf(d.getProductName()),plus))
                return "Android Bluetooth audio output available";
        }
        return "No matching Android Bluetooth audio output";
    }
}
