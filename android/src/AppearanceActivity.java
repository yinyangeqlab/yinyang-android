package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.os.Build;

abstract class AppearanceActivity extends Activity {
    static final String STANDALONE = "standaloneAutoEq";
    private DeviceBinding binding;
    private ButtonLayoutPanel buttonLayout;
    private boolean resolvedBinding;
    final DeviceBinding binding(){
        if(!resolvedBinding){binding=DeviceBinding.forIntent(this,getIntent());resolvedBinding=true;}
        return binding;
    }
    final boolean standalone(){return getIntent().getBooleanExtra(STANDALONE,false);}
    private String workspace(){
        DeviceBinding b=binding();return b==null?"unlinked":b.profile.key;
    }
    final void addButtonLayoutPreview(LinearLayout parent, boolean plus) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        if (buttonLayout == null) buttonLayout = new ButtonLayoutPanel(this, plus);
        if (buttonLayout.getParent() instanceof ViewGroup)
            ((ViewGroup) buttonLayout.getParent()).removeView(buttonLayout);
        parent.addView(buttonLayout, -1, -2);
    }
    @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
        if("appearance".equals(name) || this instanceof DevicePicker)return super.getSharedPreferences(name,mode);
        return super.getSharedPreferences(workspace()+"."+name,mode);
    }
    private java.io.File scopedDirectory(java.io.File root){
        java.io.File dir=new java.io.File(root,workspace());
        if(!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())throw new IllegalStateException("Cannot open the device workspace.");
        return dir;
    }
    @Override public java.io.File getFilesDir(){return scopedDirectory(super.getFilesDir());}
    @Override public java.io.File getNoBackupFilesDir(){return scopedDirectory(super.getNoBackupFilesDir());}
    @Override public void startActivityForResult(android.content.Intent intent,int code,Bundle options){
        android.content.ComponentName target=intent.getComponent();
        if(!(this instanceof DevicePicker) && target!=null && getPackageName().equals(target.getPackageName()) && binding()!=null)
            binding().tag(intent);
        if(standalone() && target!=null && TuningActivity.class.getName().equals(target.getClassName()))
            intent.putExtra(STANDALONE,true);
        super.startActivityForResult(intent,code,options);
    }
    @SuppressWarnings("deprecation")
    static void applyInsets(View view, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets area = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(area.left, area.top, area.right, area.bottom);
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            AppearanceActivity.systemBarInsets(view,bars.top,bars.bottom,bars.left,bars.right);
        } else {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
    }

    static final String[] THEMES = {"Rain Garden", "AMOLED black", "Light", "Forest", "YinYangEQ theme"};
    private int applied;
    private boolean openingTab;
    @SuppressWarnings("deprecation")
    final void openTab(android.content.Intent intent,int request){
        if(openingTab || isFinishing())return;
        openingTab=true;
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("companionTab",true);
        try{
            startActivityForResult(intent,request,android.app.ActivityOptions.makeCustomAnimation(this,0,0).toBundle());
        }catch(RuntimeException failure){openingTab=false;throw failure;}
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data) {
        super.onActivityResult(request,result,data);
        openingTab=false;
    }
    @SuppressWarnings("deprecation")
    final void closeTab(){finish();overridePendingTransition(0,0);}

    static int theme(Context c) { return c.getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme",1); }
    static boolean hasScenery(int theme) { return theme == 0 || theme == 3; }

    @Override public void setContentView(View root) {
        if (hasScenery(applied)) {
            int artwork = applied == 0 ? R.drawable.theme_rain : R.drawable.theme_forest;
            root.setBackground(new ScenicSurface(this, artwork));
        }
        super.setContentView(root);
    }

    static String[] stages(Context c) {
        return new String[]{"Main EQ", "Channel Match"};
    }
    static String[] stageTabs(Context c) {
        return new String[]{"Main EQ · USR", "Channel Match · SPK"};
    }
    @Override protected void onCreate(Bundle b) {
        applied=theme(this);
        setTheme(applied==2 ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar);
        LabStyle.palette(applied);
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(LabStyle.BG));
        if(Build.VERSION.SDK_INT>=34 && getIntent().getBooleanExtra("companionTab",false)){
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,0,0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,0,0);
        }
        getWindow().setStatusBarColor(systemBarColor(this));
        getWindow().setNavigationBarColor(systemBarColor(this));
        View decor=getWindow().getDecorView();
        int flags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~flags);
    }
    static int systemBarColor(Context c) { return theme(c)==2 ? 0xff1d2634 : LabStyle.BG; }
    static void systemBarInsets(View root,int top,int bottom,int left,int right) {
        if(theme(root.getContext())!=2) return;
        root.setForeground(new android.graphics.drawable.Drawable() {
            final android.graphics.Paint p=new android.graphics.Paint();
            public void draw(android.graphics.Canvas canvas) {
                p.setColor(systemBarColor(root.getContext()));
                int w=root.getWidth(), h=root.getHeight();
                canvas.drawRect(0,0,w,top,p);canvas.drawRect(0,h-bottom,w,h,p);
                canvas.drawRect(0,0,left,h,p);canvas.drawRect(w-right,0,w,h,p);
            }
            public void setAlpha(int a) {}
            public void setColorFilter(android.graphics.ColorFilter filter) {}
            public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        });
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && android.os.Build.VERSION.SDK_INT>=30 && getWindow().getInsetsController()!=null) {
            int mask=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    |android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(0,mask);
        }
    }
    @Override protected void onStart() {
        super.onStart();
        if(!(this instanceof DevicePicker) && !(standalone() && (this instanceof LabActivity || this instanceof TuningActivity))){
            DeviceBinding b=binding();
            boolean plus=this instanceof PlusActivity || this instanceof PlusLab;
            if(b==null || !b.active(this) || (!(this instanceof TuningActivity) && plus!=(b.profile.model==DeviceProfile.Model.PLUS))){
                DeviceBinding.manage(this);return;
            }
        }
        if (applied!=theme(this)) recreate();
    }
    boolean observesHardwareDisconnects() { return false; }
    void hardwareDisconnected(String address,int transport) {}
    boolean observesBluetoothAudio() { return false; }
    void bluetoothAudioChanged() {}

    private boolean observing;
    private AudioManager observedAudio;
    private final AudioDeviceCallback audioDevices=new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { bluetoothAudioChanged(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { bluetoothAudioChanged(); }
    };
    private final android.content.BroadcastReceiver disconnects=new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c,android.content.Intent intent) {
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                BluetoothDevice device=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int transport=intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT,BluetoothDevice.TRANSPORT_AUTO);
                if(device!=null)hardwareDisconnected(device.getAddress(),transport);
            } else if (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,-1)==android.bluetooth.BluetoothAdapter.STATE_OFF) {
                hardwareDisconnected(null,BluetoothDevice.TRANSPORT_AUTO);
            }
        }
    };

    @Override protected void onResume() {
        super.onResume();openingTab=false;
        if(observesBluetoothAudio()){
            observedAudio=getSystemService(AudioManager.class);
            if(observedAudio!=null)observedAudio.registerAudioDeviceCallback(audioDevices,null);
        }
        if(!observesHardwareDisconnects())return;
        android.content.IntentFilter events=new android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED);
        events.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(disconnects,events,Context.RECEIVER_EXPORTED);
        else registerReceiver(disconnects,events);
        observing=true;
    }
    @Override protected void onPause() {
        if(observing) { unregisterReceiver(disconnects); observing=false; }
        if(observedAudio!=null){
            observedAudio.unregisterAudioDeviceCallback(audioDevices);
            observedAudio=null;
        }
        super.onPause();
    }

    void chooseTheme() {
        new AlertDialog.Builder(this).setTitle("Theme").setSingleChoiceItems(THEMES, applied,(d,i)->{
            d.dismiss();
            if (i!=applied) {
                getSharedPreferences("appearance",MODE_PRIVATE).edit().putInt("theme",i).apply();
                recreate();
            }
        }).setNegativeButton("Close",null).show();
    }

}
