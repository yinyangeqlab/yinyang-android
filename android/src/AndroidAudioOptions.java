package com.yinyangeq.qudelixprobe;

import android.bluetooth.*;
import android.content.*;
import android.widget.*;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** These are system-owned settings. Opening a settings screen never implies a negotiated format change. */
final class AndroidAudioOptions extends LinearLayout {
    private final Context context;

    private final Button deviceDetails;
    private final Button[] formats=new Button[2];
    private final TextView systemHint;
    private final TextView streamFormat;
    private final Intent[] formatIntents=new Intent[2];

    private final TextView detail;
    static final String UNAVAILABLE = "Not available on this android system";
    private final boolean plus;
    private final String address;
    AndroidAudioOptions(Context c,boolean plus,String address) {
        super(c);this.address=address;
        this.plus=plus; context=c; setOrientation(VERTICAL);

        LinearLayout panel=this;
        CompanionActions.sectionTitle(panel,"Android output settings");
        streamFormat=text(c,"",14,MUTED,false);
        panel.addView(streamFormat);
        updateStreamFormat(false,0,0);
        for(int i=0;i<formats.length;i++){
            final int index=i;
            formats[i]=CompanionActions.secondary(c,i==0?"Bluetooth audio codec":"Bluetooth audio bits per sample",()->open(formatIntents[index]));
            panel.addView(formats[i]);
        }
        systemHint=text(c,"",14,MUTED,false);panel.addView(systemHint);
        deviceDetails=CompanionActions.secondary(c,"Open device Bluetooth configuration",() -> open(BluetoothPages.details(context,BluetoothPages.paired(context,address,plus))));
        panel.addView(deviceDetails);
        detail=text(c,"",14,MUTED,false);panel.addView(detail);
        update();
    }
    void updateStreamFormat(boolean playing, int sampleRateHz, int bitDepth) {
        String rate=sampleRateHz>0
                ? Peq.fmt("%.1f kHz",sampleRateHz/1000.0).replace(".0 kHz"," kHz")
                : "Sample rate unreported";
        String depth=bitDepth>0 ? bitDepth+"-bit" : "Bit depth unreported";
        String value=playing ? rate+" · "+depth : "Awaiting playback";
        String label="Qudelix Bluetooth stream · "+value;
        if(!label.contentEquals(streamFormat.getText()))streamFormat.setText(label);
    }
    static Button guide(Context c,boolean gain,boolean plus){
        String title=gain?(plus?"5K Plus output specifications":"Qudelix gain guide"):"Qudelix DAC profile guide";
        Button b=CompanionActions.secondary(c,title,()->{
            String url=gain?(plus?"https://support-qudelix.github.io/Qudelix-5K_Plus_User_Manual_EN.pdf#page=2":
                    "https://support-qudelix.github.io/Qudelix5K_User_Manual.pdf#page=17"):
                    "https://www.qudelix.com/blogs/5k-dac-amp/es9218p";
            try{c.startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));}
            catch(ActivityNotFoundException | SecurityException e){
                new android.app.AlertDialog.Builder(c).setTitle(title).setMessage("Open this address in a browser:\n"+url)
                    .setPositiveButton("Copy address",(dialog,which)->c.getSystemService(android.content.ClipboardManager.class)
                            .setPrimaryClip(ClipData.newPlainText(title,url))).setNegativeButton("Close",null).show();
            }
        });quiet(b);return b;
    }
    private boolean containsKey(android.content.res.Resources resources,String pkg,String xml,String key){
        int id=resources.getIdentifier(xml,"xml",pkg);
        if(id==0)return false;
        try(android.content.res.XmlResourceParser parser=resources.getXml(id)){
            while(parser.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
                if(parser.getEventType()!=org.xmlpull.v1.XmlPullParser.START_TAG)continue;
                int ref=parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android","key",0);
                String value=ref==0?parser.getAttributeValue("http://schemas.android.com/apk/res/android","key"):resources.getString(ref);
                if(key.equals(value))return true;
            }
        }catch(Exception ignored){}
        return false;
    }
    private void configureFormats(){
        Intent developer=new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        android.content.pm.PackageManager pm=context.getPackageManager();
        android.content.pm.ResolveInfo resolved=pm.resolveActivity(developer,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        boolean enabled=android.provider.Settings.Global.getInt(context.getContentResolver(),android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)!=0;
        String hint=UNAVAILABLE+".";
        boolean sharedDestination=false;
        if(enabled && resolved!=null && resolved.activityInfo.exported &&
                (resolved.activityInfo.applicationInfo.flags&android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0){
            String pkg=resolved.activityInfo.packageName;
            try{
                android.content.res.Resources res=pm.getResourcesForApplication(pkg);
                String[] keys={"bluetooth_audio_codec_settings_list","bluetooth_bit_per_sample_settings"};
                boolean nested=containsKey(res,pkg,"bluetooth_development_settings",keys[0]) &&
                    (containsKey(res,pkg,"development_settings","bluetooth_development_settings") ||
                     containsKey(res,pkg,"sec_development_settings","bluetooth_development_settings"));
                sharedDestination=nested;
                String settingsRoute=nested?"Developer options → Bluetooth developer settings":"Developer options";
                for(int i=0;i<keys.length;i++){
                    boolean present=nested?containsKey(res,pkg,"bluetooth_development_settings",keys[i]):
                        containsKey(res,pkg,"development_settings",keys[i]) || containsKey(res,pkg,"sec_development_settings",keys[i]);
                    if(!present)continue;
                    String key=nested?"bluetooth_development_settings":keys[i];
                    android.os.Bundle args=new android.os.Bundle();args.putString(":settings:fragment_args_key",key);
                    formatIntents[i]=new Intent(developer).setPackage(pkg).putExtra(":settings:fragment_args_key",key).putExtra(":settings:show_fragment_args",args);
                }
                if(formatIntents[0]!=null || formatIntents[1]!=null)hint=settingsRoute+". Select Bluetooth Audio Codec or Bluetooth Audio Bits Per Sample during A2DP playback. Android opens the settings page and controls which values are available.";
            }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        }else if(!enabled)hint=UNAVAILABLE+". Enable Developer options to check these controls.";
        if(sharedDestination && formatIntents[0]!=null && formatIntents[1]!=null){
            formats[0].setText("Find Bluetooth developer settings");formats[1].setVisibility(GONE);
        }else{formats[0].setText("Bluetooth audio codec");formats[1].setVisibility(VISIBLE);}
        for(int i=0;i<formats.length;i++)formats[i].setEnabled(formatIntents[i]!=null);
        systemHint.setText(hint);
    }
    static boolean isPlusName(String name) {
        if(name==null)return false;
        String n=name.toLowerCase(java.util.Locale.ROOT).replace("+","plus").replaceAll("[^a-z0-9]","");
        return n.contains("5kplus") || n.contains("qudelix5kp");
    }
    private void open(Intent target) {
        if(target==null){Toast.makeText(context,UNAVAILABLE,Toast.LENGTH_SHORT).show();return;}
        try { context.startActivity(target); }
        catch(ActivityNotFoundException | SecurityException e) { Toast.makeText(context,UNAVAILABLE,Toast.LENGTH_SHORT).show(); }
    }
    private void update() {
        java.util.Arrays.fill(formatIntents,null);configureFormats();
        BluetoothDevice device = BluetoothPages.paired(context,address,plus);
        boolean available = BluetoothPages.details(context,device)!=null;
        deviceDetails.setEnabled(available);
        detail.setText(available ? "Open Android settings for this paired Qudelix." :
                device == null ? "Connect the paired Qudelix to open its device settings." : UNAVAILABLE);
    }
    @Override protected void onWindowVisibilityChanged(int visibility){
        super.onWindowVisibilityChanged(visibility);
        if(visibility==VISIBLE && detail!=null)update();
    }
}
