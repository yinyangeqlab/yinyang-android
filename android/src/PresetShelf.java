package com.yinyangeq.qudelixprobe;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONException;

/** Local ordering and preferred entry for an existing library of named drafts. */
final class PresetShelf {
    private final SharedPreferences store;
    private final String prefix;

    PresetShelf(SharedPreferences store,String prefix){
        this.store=store;
        this.prefix=prefix;
    }

    List<String> names(){
        ArrayList<String> remaining=new ArrayList<>();
        for(String key:store.getAll().keySet())
            if(key.startsWith(prefix))remaining.add(key.substring(prefix.length()));
        remaining.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        List<String> ordered=new ArrayList<>();
        try {
            JSONArray order=new JSONArray(store.getString(orderKey(),"[]"));
            for(int i=0;i<order.length();i++){
                String name=order.getString(i);
                if(remaining.remove(name))ordered.add(name);
            }
        } catch(JSONException ignored) {}
        ordered.addAll(remaining);
        return ordered;
    }

    private String orderKey(){return "order-index/"+prefix;}
    private String defaultKey(){return "default-preset/"+prefix;}
    String preferred(){return store.getString(defaultKey(),"");}

    void manage(Context c,String name,Runnable refresh,Consumer<String> updateActive){
        List<String> ordered=names();
        int index=ordered.indexOf(name);
        if(index<0)return;
        boolean preferred=name.equals(preferred());
        List<String> items=new ArrayList<>();
        List<Runnable> actions=new ArrayList<>();
        items.add("Rename");
        actions.add(()->askName(c,"Rename saved draft",name,"Save name",48,value->{
            if(value.equals(name))return true;
            if(store.contains(prefix+value))return false;
            SharedPreferences.Editor edit=store.edit().remove(prefix+name)
                    .putString(prefix+value,store.getString(prefix+name,""));
            if(preferred)edit.putString(defaultKey(),value);
            for(String key:store.getAll().keySet())
                if(key.startsWith("link/") && name.equals(store.getString(key,"")))edit.putString(key,value);
            ordered.set(index,value);
            edit.putString(orderKey(),new JSONArray(ordered).toString()).apply();
            updateActive.accept(value);
            refresh.run();
            return true;
        }));
        if(index>0){items.add("Move up");actions.add(()->move(ordered,index,index-1,refresh));}
        if(index<ordered.size()-1){items.add("Move down");actions.add(()->move(ordered,index,index+1,refresh));}
        items.add(preferred?"Clear default":"Set as default");
        actions.add(()->{store.edit().putString(defaultKey(),preferred?"":name).apply();refresh.run();});
        items.add("Delete");
        actions.add(()->new AlertDialog.Builder(c).setTitle("Delete "+name+"?")
                .setMessage("This removes the saved draft from this phone.")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->{
                    SharedPreferences.Editor edit=store.edit().remove(prefix+name);
                    if(preferred)edit.remove(defaultKey());
                    for(String key:store.getAll().keySet())
                        if(key.startsWith("link/") && name.equals(store.getString(key,"")))edit.remove(key);
                    ordered.remove(name);
                    edit.putString(orderKey(),new JSONArray(ordered).toString()).apply();
                    refresh.run();
                }).show());
        new AlertDialog.Builder(c).setTitle(name).setItems(items.toArray(new String[0]),(d,i)->actions.get(i).run())
                .setNegativeButton("Cancel",null).show();
    }

    private void move(List<String> ordered,int from,int to,Runnable refresh){
        Collections.swap(ordered,from,to);
        store.edit().putString(orderKey(),new JSONArray(ordered).toString()).apply();
        refresh.run();
    }

    interface NameAction { boolean save(String value); }

    static void askName(Context c,String title,String previous,String action,int maxBytes,NameAction save){
        EditText input=new EditText(c);
        input.setSingleLine();
        input.setText(previous);
        input.setSelectAllOnFocus(true);
        input.setContentDescription("Preset name");
        LinearLayout box=new LinearLayout(c);
        int space=Math.round(20*c.getResources().getDisplayMetrics().density);
        box.setPadding(space,0,space,0);
        box.addView(input,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(c).setTitle(title).setView(box)
                .setPositiveButton(action,null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(-1).setOnClickListener(v->{
            String value=input.getText().toString().trim();
            if(value.isEmpty()){input.setError("Enter a preset name.");return;}
            if(value.equals(".") || value.equals("..") || value.codePoints().anyMatch(ch->ch=='/' || ch==92 || Character.isISOControl(ch))){
                input.setError("Use a name without slashes or control characters.");
                return;
            }
            if(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes){
                input.setError("Use at most "+maxBytes+" UTF-8 bytes.");return;
            }
            if(!save.save(value)){input.setError("This name is already in use.");return;}
            dialog.dismiss();
        }));
        dialog.show();
    }
}
