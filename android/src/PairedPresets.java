package com.yinyangeq.qudelixprobe;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Phone library. A pair is an immutable copy of both stages, including both SPK channels. */
final class PairedPresets {
    private final SharedPreferences store;
    PairedPresets(SharedPreferences store) { this.store = store; }
    PresetShelf shelf(){return new PresetShelf(store,"pair/");}
    String[] names(){return shelf().names().toArray(new String[0]);}
    static final class Stage {
        final Peq eq;
        final String name, uri;
        Stage(Peq eq,String name,String uri) {this.eq=eq; this.name=name; this.uri=uri;}
        JSONObject pack() throws JSONException {
            return new JSONObject().put("eq",java.util.Base64.getEncoder().encodeToString(eq.bytes))
                    .put("name",name).put("uri",uri);
        }
    }
    Stage[] read(String name) {
        String data = store.getString("pair/"+name,null);
        if (data==null) return null;
        try {
            JSONArray rows=new JSONArray(data);
            Stage[] stages=new Stage[2];
            for (int i=0;i<2;i++) {
                JSONObject o=rows.getJSONObject(i);
                stages[i]=new Stage(new Peq(i,java.util.Base64.getDecoder().decode(o.getString("eq"))),
                        o.getString("name"),o.optString("uri",null));
            }
            return stages;
        } catch (JSONException | IllegalArgumentException e) { return null; }
    }
    void save(String name, Stage[] stages, String source) {
        try {
            String data = new JSONArray().put(stages[0].pack()).put(stages[1].pack()).toString();
            SharedPreferences.Editor e=store.edit().putString("pair/"+name,data);
            for (String k : store.getAll().keySet())
                if (k.startsWith("link/") && name.equals(store.getString(k,null))) e.remove(k);
            if (source!=null) e.putString("link/"+source,name);
            if (stages[0].uri!=null) e.putString("link/"+stages[0].uri,name);
            e.apply();
        } catch (JSONException e) { throw new IllegalArgumentException("Could not store the paired preset.",e); }
    }
    void relocateLink(String source,String destination){
        String name=linked(source);
        if(name==null)return;
        SharedPreferences.Editor edit=store.edit().remove("link/"+source);
        if(destination!=null)edit.putString("link/"+destination,name);
        edit.apply();
    }
    String linked(String source) { return source==null ? null : store.getString("link/"+source,null); }
    String linkedDocument(String uri,String title,Peq imported) {
        String direct=linked(uri);
        if(direct!=null) return direct;
        // Android Downloads can give the same document a raw ID first and a MediaStore ID later.
        String match=null;
        String filters=imported.exportText();
        for(String candidate:names()) {
            Stage[] stages=read(candidate);
            if(stages==null || stages[0].uri==null || !title.equals(stages[0].name)
                    || !imported.same(stages[0].eq) && !filters.equals(stages[0].eq.exportText())) continue;
            if(match!=null) return null;
            match=candidate;
        }
        if(match!=null) store.edit().putString("link/"+uri,match).apply();
        return match;
    }
    void renamed(String oldUri,String newUri,String newName) {
        SharedPreferences.Editor e=store.edit();
        for(String name : names()) {
            Stage[] stages=read(name);
            if(stages==null) continue;
            boolean changed=false;
            for(int i=0;i<2;i++) if(oldUri.equals(stages[i].uri)) {
                stages[i]=new Stage(stages[i].eq,newName,newUri); changed=true;
            }
            if(changed) try {
                e.putString("pair/"+name,new JSONArray().put(stages[0].pack()).put(stages[1].pack()).toString());
            } catch(JSONException ignored) {}
        }
        String link=linked(oldUri);
        if(link!=null) e.remove("link/"+oldUri).putString("link/"+newUri,link);
        e.apply();
    }
}
