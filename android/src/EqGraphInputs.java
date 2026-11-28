package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Executor;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Preview graph sources are local to the currently linked device workspace. */
final class EqGraphInputs {
    private static final int MEASURE=895, TARGET=896, LEFT=897, RIGHT=898;
    private static final int MAX_BYTES=4*1024*1024;
    final Activity activity;
    private final SharedPreferences prefs;
    private final Executor worker;
    private final Runnable redraw;
    ResponseCurve left,right,target;
    private String leftName="", rightName="", targetName="";
    private boolean example;
    boolean after, zeroLine;

    EqGraphInputs(Activity activity, SharedPreferences prefs, Executor worker, Runnable redraw) {
        this.activity=activity;this.prefs=prefs;this.worker=worker;this.redraw=redraw;
        after=prefs.getBoolean("after",true);
        zeroLine=prefs.getBoolean("zeroLine",true);
        example=prefs.getBoolean("example",false);
        try {
            left=unpack("left");
            right=unpack("right");
            target=unpack("target");
            leftName=prefs.getString("leftName","");
            rightName=prefs.getString("rightName","");
            targetName=prefs.getString("targetName","");
        } catch(JSONException | IllegalArgumentException invalid) {
            left=right=target=null;
            example=false;
        }
    }

    boolean measured() { return left!=null; }
    boolean stereo() { return left!=null && right!=null && Double.isFinite(ResponseCurve.reference(left,right)); }
    double measurementReference() { return ResponseCurve.reference(left,stereo()?right:null); }
    double targetReference() { return ResponseCurve.reference(left,stereo()?right:null,target); }
    boolean compareTarget() { return measured() && target!=null && Double.isFinite(targetReference()); }

    String summary() {
        if(example)return "Example measurement and target";
        String measurement=left==null ? "Choose a measurement" : right==null || leftName.equals(rightName)
                ? leftName : leftName+" / "+rightName;
        return target==null ? measurement : measurement+" · "+targetName;
    }

    void setAfter(boolean value) { after=value;prefs.edit().putBoolean("after",value).apply(); }
    void setZeroLine(boolean value) { zeroLine=value;prefs.edit().putBoolean("zeroLine",value).apply(); }

    void choose() {
        ArrayList<String> labels=new ArrayList<>();
        ArrayList<Runnable> actions=new ArrayList<>();
        option(labels,actions,"Choose saved measurement",this::chooseSaved);
        option(labels,actions,"Open measurement file",()->pick(MEASURE));
        option(labels,actions,"Open left measurement",()->pick(LEFT));
        option(labels,actions,"Open right measurement",()->pick(RIGHT));
        option(labels,actions,"Open target file",()->pick(TARGET));
        option(labels,actions,"Use example curves",this::example);
        if(left!=null || right!=null || target!=null)option(labels,actions,"Clear graph inputs",()->{
            left=right=target=null;
            leftName=rightName=targetName="";
            example=false;
            changed();
        });
        new AlertDialog.Builder(activity).setTitle("Graph inputs").setItems(labels.toArray(new String[0]),
                (dialog,index)->actions.get(index).run()).setNegativeButton("Close",null).show();
    }

    private static void option(ArrayList<String> labels,ArrayList<Runnable> actions,String label,Runnable action) {
        labels.add(label);actions.add(action);
    }

    private void chooseSaved() {
        Map<String,?> entries=activity.getSharedPreferences("attached-curves",Activity.MODE_PRIVATE).getAll();
        ArrayList<String[]> choices=new ArrayList<>();
        for(Map.Entry<String,?> entry:entries.entrySet()){
            if(!entry.getKey().matches("[0-9a-f]{64}") || !(entry.getValue() instanceof String))continue;
            try {
                JSONObject item=new JSONObject((String)entry.getValue());
                choices.add(new String[]{entry.getKey(),item.getString("name")});
            }catch(JSONException invalid){ /* An unreadable entry cannot be selected. */ }
        }
        choices.sort(Comparator.comparing(item->item[1],String.CASE_INSENSITIVE_ORDER));
        if(choices.isEmpty()){
            note("Save a measurement on this phone in Auto EQ, or open a curve file here.");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Saved measurements")
                .setItems(choices.stream().map(item->item[1]).toArray(String[]::new),(dialog,index)->{
                    String[] chosen=choices.get(index);
                    worker.execute(()->loadSaved(chosen));
                }).setNegativeButton("Cancel",null).show();
    }

    private void loadSaved(String[] chosen) {
        try(InputStream input=new android.util.AtomicFile(new File(activity.getFilesDir(),"curve-"+chosen[0])).openRead()){
            JSONObject stored=new JSONObject(read(input));
            JSONArray a=stored.optJSONArray("leftPoints"), b=stored.optJSONArray("rightPoints");
            final ResponseCurve first, second;
            if(a!=null && b!=null){
                first=curve(a,stored.optDouble("leftLevelDb",0));
                second=curve(b,stored.optDouble("rightLevelDb",0));
            }else{
                first=curve(stored.getJSONArray("points"),0);
                second=null;
            }
            deliver(()->setMeasurement(first,second,chosen[1]));
        }catch(IOException | JSONException | IllegalArgumentException bad){
            deliver(()->note("Could not open that saved measurement."));
        }
    }

    private void pick(int request) {
        try {
            activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),request);
        }catch(android.content.ActivityNotFoundException missing){note("Android's file picker is unavailable.");}
    }

    boolean result(int request,int result,Intent data) {
        if(request<MEASURE || request>RIGHT)return false;
        if(result!=Activity.RESULT_OK || data==null || data.getData()==null)return true;
        Uri uri=data.getData();
        worker.execute(()->{
            try(InputStream input=activity.getContentResolver().openInputStream(uri)){
                ResponseCurve[] curves=ResponseCurve.parseText(read(input),request==MEASURE);
                String name=name(uri);
                deliver(()->{
                    if(request==MEASURE)setMeasurement(curves[0],curves.length==2?curves[1]:null,name);
                    else {
                        if(example){left=right=target=null;leftName=rightName=targetName="";}
                        if(request==TARGET){target=curves[0];targetName=name;}
                        if(request==LEFT){left=curves[0];leftName=name;}
                        if(request==RIGHT){right=curves[0];rightName=name;}
                        example=false;
                        changed();
                    }
                });
            }catch(IOException | RuntimeException bad){
                deliver(()->note(bad.getMessage()==null?"Could not read the curve.":bad.getMessage()));
            }
        });
        return true;
    }

    private void setMeasurement(ResponseCurve a,ResponseCurve b,String name) {
        if(example){target=null;targetName="";}
        left=a;right=b;
        leftName=name;rightName=b==null?"":name;
        example=false;
        changed();
    }

    private String name(Uri uri) {
        try(android.database.Cursor cursor=activity.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null && cursor.moveToFirst() && !cursor.isNull(0))return cursor.getString(0);
        }catch(RuntimeException unavailable){ /* The curve itself is still usable. */ }
        return "Curve file";
    }

    private void example() {
        ResponseCurve.Points a=new ResponseCurve.Points(),b=new ResponseCurve.Points(),goal=new ResponseCurve.Points();
        for(int index=0;index<=160;index++){
            double t=index/160.0;
            double hz=index==160?20000:20*Math.pow(1000,t);
            double targetDb=3.5*Math.exp(-Math.pow((t-.12)/.21,2))-2.5*Math.exp(-Math.pow((t-1)/.21,2));
            double difference=2.7*Math.exp(-Math.pow((t-.37)/.12,2))-1.5*Math.exp(-Math.pow((t-.71)/.08,2));
            double shape=targetDb+.9*Math.sin(t*20);
            a.add(hz,shape+difference);b.add(hz,shape-difference);goal.add(hz,targetDb);
        }
        left=a.build();right=b.build();target=goal.build();
        leftName=rightName="Example measurement";targetName="Example target";
        example=true;
        changed();
    }

    void useFit(String payload) {
        if(payload==null || payload.length()>50000)return;
        try {
            JSONObject fit=new JSONObject(payload);
            ResponseCurve goal=curve(fit.getJSONArray("target"),0);
            JSONArray a=fit.optJSONArray("leftPoints"),b=fit.optJSONArray("rightPoints");
            ResponseCurve first,second;
            if(a!=null && b!=null){
                first=curve(a,fit.optDouble("leftLevelDb",0));
                second=curve(b,fit.optDouble("rightLevelDb",0));
            }else{
                first=curve(fit.getJSONArray("points"),0);
                second=null;
            }
            left=first;right=second;target=goal;
            leftName=fit.optString("name","Auto EQ measurement");
            rightName=second==null?"":leftName;
            targetName=fit.optString("targetName","Auto EQ target");
            example=false;
            persist();
        }catch(JSONException | IllegalArgumentException invalid){ /* The preset remains usable without the curve overlay. */ }
    }

    private void changed() {
        persist();
        redraw.run();
    }

    private void persist() {
        prefs.edit().putString("left",pack(left)).putString("right",pack(right)).putString("target",pack(target))
                .putString("leftName",leftName).putString("rightName",rightName).putString("targetName",targetName)
                .putBoolean("example",example).apply();
    }

    private void deliver(Runnable action) {
        activity.runOnUiThread(()->{if(!activity.isFinishing() && !activity.isDestroyed())action.run();});
    }
    private void note(String message) {
        new AlertDialog.Builder(activity).setTitle("Graph inputs").setMessage(message)
                .setPositiveButton("Close",null).show();
    }

    private ResponseCurve unpack(String key) throws JSONException {
        String data=prefs.getString(key,null);
        return data==null?null:curve(new JSONArray(data),0);
    }
    private static ResponseCurve curve(JSONArray samples,double offset) throws JSONException {
        ResponseCurve.Points points=new ResponseCurve.Points();
        for(int i=0;i<samples.length();i++){
            JSONArray point=samples.getJSONArray(i);
            points.add(point.getDouble(0),point.getDouble(1)+offset);
        }
        return points.build();
    }
    private static String pack(ResponseCurve curve) {
        if(curve==null)return null;
        JSONArray points=new JSONArray();
        try {
            for(int i=0;i<curve.frequencies.length;i++)
                points.put(new JSONArray().put(curve.frequencies[i]).put(curve.levels[i]));
        }catch(JSONException invalid){throw new IllegalArgumentException("Invalid graph curve.",invalid);}
        return points.toString();
    }
    private static String read(InputStream input) throws IOException {
        if(input==null)throw new IOException("Could not open the curve file.");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] block=new byte[4096];
        for(int count;(count=input.read(block))!=-1;){
            if(bytes.size()+count>MAX_BYTES)throw new IOException("Choose a curve file smaller than 4 MB.");
            bytes.write(block,0,count);
        }
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
}
