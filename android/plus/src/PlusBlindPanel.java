package com.yinyangeq.qudelixprobe;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.util.Base64;
import android.widget.*;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Setup stays in the device's private workspace. Screen readers see the same blind labels as listeners. */
final class PlusBlindPanel implements PlusBlindRound.Journal {
    static final int FIRST_FILE=15, SECOND_FILE=16;
    static final String ROUND="plusBlindRoundV1";
    private static final String MATCH="blindMatchLevels";
    private static final String LIVE="@device", EDITOR="@editor";
    private final PlusActivity host;
    private final SharedPreferences prefs;
    String notice="";
    private boolean conflict;

    PlusBlindPanel(PlusActivity host, SharedPreferences prefs) { this.host=host;this.prefs=prefs; }
    boolean active() { return prefs.contains(ROUND); }
    public void save(byte[] bytes) throws IOException {
        if(!prefs.edit().putString(ROUND,Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())
            throw new IOException("The comparison could not be saved safely on this phone.");
    }
    public void clear() throws IOException {
        if(!prefs.edit().remove(ROUND).commit()) throw new IOException("The working EQ was verified. Retry to clear the saved round.");
    }
    private PlusBlindRound read() throws IOException {
        String value=prefs.getString(ROUND,null);
        if(value==null)throw new IOException("This comparison has already ended.");
        try {return PlusBlindRound.read(Base64.decode(value,Base64.DEFAULT));}
        catch(IllegalArgumentException e){throw new IOException("The saved comparison could not be opened.",e);}
    }

    private String sourceKey(int slot) {return "blindSource"+slot;}
    private String value(int slot){return prefs.getString(sourceKey(slot), slot==0?LIVE:EDITOR);}
    private String label(String value) {
        if(LIVE.equals(value))return "Starting device EQ";
        if(EDITOR.equals(value))return "Current editor draft";
        try{return new JSONObject(value).getString("name");}
        catch(JSONException e){return "Choose a source again";}
    }
    private PlusBlindRound.Choice choice(String value,String editor) throws IOException {
        if(LIVE.equals(value))return PlusBlindRound.Choice.device();
        boolean draft=EDITOR.equals(value);
        try {
            JSONObject data=new JSONObject(draft?editor:value);
            PlusPreset.Mode mode=PlusPreset.Mode.valueOf(data.getString("mode"));
            PlusPreset.Channel main=PlusPreset.parse(data.getString("shared"),false,30)[0];
            PlusPreset.Channel[] ears=mode==PlusPreset.Mode.LAYERED?
                    PlusPreset.parse(data.getString("match"),true,20):new PlusPreset.Channel[]{PlusPreset.Channel.flat(),PlusPreset.Channel.flat()};
            String name=data.optString("name","");
            if(draft)name=name.isEmpty()?"Editor draft":"Editor draft · "+name;
            return new PlusBlindRound.Choice(name,mode,PlusPreset.banks(mode,main,ears[0],ears[1],true));
        } catch(JSONException | IllegalArgumentException error){throw new IOException("Choose a supported Plus PEQ. "+error.getMessage(),error);}
    }
    private void select(int slot){
        List<String> titles=new ArrayList<>(Arrays.asList("Starting device EQ","Current editor draft","Import Main EQ file…"));
        List<String> named=new ArrayList<>();
        for(String key:prefs.getAll().keySet())if(key.startsWith("preset:"))named.add(key.substring(7));
        named.sort(String.CASE_INSENSITIVE_ORDER);
        titles.addAll(named);
        new AlertDialog.Builder(host).setTitle("Select source "+(slot+1)).setItems(titles.toArray(new String[0]),(dialog,index)->{
            if(index==2){host.blindFile(slot);return;}
            String selected=index==0?LIVE:index==1?EDITOR:prefs.getString("preset:"+named.get(index-3),null);
            if(selected!=null&&!active()){prefs.edit().putString(sourceKey(slot),selected).apply();notice="";host.refreshBlind();}
        }).setNegativeButton("Cancel",null).show();
    }

    void file(int slot,String name,String input) throws IOException {
        if(active())throw new IOException("End the active round before changing its sources.");
        try {
            PlusPreset.Channel main=PlusPreset.parse(input,false,30)[0];
            String selected=new JSONObject().put("name",name).put("mode",PlusPreset.Mode.SHARED.name())
                    .put("shared",PlusPreset.file(new PlusPreset.Channel[]{main},30)).toString();
            prefs.edit().putString(sourceKey(slot),selected).apply();
            notice="";host.refreshBlind();
        } catch(IllegalArgumentException | JSONException error){throw new IOException(error.getMessage(),error);}
    }
    private void button(LinearLayout parent,String label,boolean primary,boolean enabled,Runnable action){
        Button b=primary?CompanionActions.primary(host,label,action):CompanionActions.secondary(host,label,action);
        b.setEnabled(enabled);host.add(parent,b,5);
    }
    private void pair(LinearLayout parent,String left,String right,boolean enabled,java.util.function.IntConsumer click){
        LinearLayout row=host.row();host.add(parent,row,5);
        for(int i=0;i<2;i++){
            final int side=i;Button b=CompanionActions.secondary(host,i==0?left:right,()->click.accept(side));
            b.setEnabled(enabled);host.shareRow(row,b,i==0);
        }
    }
    void render(LinearLayout body,boolean ready,boolean busy) {
        host.add(body,host.text("Blind A/B",26,LabStyle.INK,true),8);
        String modeled="Estimates stereo PEQ level at 48 kHz and turns levels down to match. Hardware volume stays fixed.";
        String unchecked="Hardware volume stays fixed. The two EQs can have different playback levels.";
        if(!active()){
            host.add(body,host.text("Choose two EQs. The app shuffles their A and B labels for each round.",15,LabStyle.MUTED,false),8);
            for(int i=0;i<2;i++){final int slot=i;button(body,"Source "+(i+1)+" · "+label(value(i)),false,!busy,()->select(slot));}
            Switch toggle=new Switch(host);toggle.setText("Loudness match");LabStyle.toggle(toggle);
            toggle.setChecked(prefs.getBoolean(MATCH,true));toggle.setEnabled(!busy);
            toggle.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean(MATCH,on).apply();notice="";host.refreshBlind();});host.add(body,toggle,8);
            host.add(body,host.text(toggle.isChecked()?modeled:unchecked,14,LabStyle.MUTED,false),6);
            button(body,"Start blind round",true,ready&&!busy,this::start);
        }else try{
            PlusBlindRound state=read();
            if(state.round.hidden()){
                host.add(body,host.text("Listen to both sides, then pick the EQ you prefer.",16,LabStyle.INK,false),8);
            }else{
                int ia=state.round.source(0), ib=state.round.source(1);
                String a=state.names[ia], b=state.names[ib];
                if(state.levelMatched){a+=Peq.fmt(" · %.1f dB trim",-state.attenuation[ia]/10.0);b+=Peq.fmt(" · %.1f dB trim",-state.attenuation[ib]/10.0);}
                host.add(body,host.text("You preferred "+(state.round.favorite==0?"A":"B"),19,LabStyle.INK,true),8);
                host.add(body,host.text("A · "+a+"\nB · "+b,16,LabStyle.INK,false),4);
            }
            host.add(body,host.text(state.levelMatched?"Loudness match on · "+modeled:unchecked,14,LabStyle.MUTED,false),8);
            if(state.interrupted())host.add(body,host.text("An operation was interrupted. Reconnect the same Plus and restore its starting EQ.",15,LabStyle.MUTED,false),8);
            else{
                pair(body,"Hear A","Hear B",ready&&!busy,this::hear);
                if(state.round.hidden())pair(body,"Prefer A","Prefer B",ready&&!busy&&state.round.heardMask==3,this::choose);
            }
            button(body,"End & restore",true,ready&&!busy,this::end);
        }catch(IOException error){
            conflict=true;
            host.add(body,host.text("The saved round could not be read. You can keep the current device EQ.",15,LabStyle.MUTED,false),6);
        }
        if(!ready){
            host.add(body,host.text("Connect your linked 5K Plus with firmware 1.0.3 to audition and restore EQs.",14,LabStyle.MUTED,false),8);
            button(body,"Reconnect linked Plus",false,!busy,host::blindReconnect);
        }
        if(!notice.isEmpty())host.add(body,host.text(notice,14,LabStyle.MUTED,false),8);
        if(active()&&conflict)button(body,"Keep device EQ and close round…",false,!busy,this::keepDevice);
    }
    private void start() {
        String first=value(0),second=value(1),draft=host.blindDraft();
        boolean matchLevel=prefs.getBoolean(MATCH,true);
        conflict=false;
        host.blindJob((device,identity)->{
            if(active())throw new IOException("A comparison already exists.");
            PlusBlindRound.start(device,identity,choice(first,draft),choice(second,draft),matchLevel,this);
            return "Starting EQ saved. Choose A or B to listen.";
        });
    }
    private void hear(int letter) {
        host.blindJob((device,identity)->{
            read().hear(letter,device,identity,this);
            return "Playing "+(letter==0?"A":"B")+" · EQ verified.";
        });
    }
    private void choose(int letter) {
        try{read().choose(letter,this);notice="";host.refreshBlind();}
        catch(IOException e){host.blindMessage(e.getMessage());}
    }
    void end() { end(null); }
    void end(Runnable onRestored) {
        host.blindJob((device,identity)->{
            try{read().end(device,identity,this);}
            catch(PlusBlindRound.ExternalEdit e){host.runOnUiThread(()->conflict=true);throw e;}
            return "Starting EQ restored and verified.";
        },onRestored);
    }
    private void keepDevice(){
        new AlertDialog.Builder(host).setTitle("Keep the current device EQ?")
                .setMessage("Close this round and discard its saved starting EQ. The hardware stays exactly as it is.")
                .setNegativeButton("Cancel",null).setPositiveButton("Keep device EQ",(d,w)->{
                    try{clear();conflict=false;notice="Round closed. Current device EQ kept.";host.refreshBlind();}
                    catch(IOException e){host.blindMessage(e.getMessage());}
                }).show();
    }
}
