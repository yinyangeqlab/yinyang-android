package com.yinyangeq.qudelixprobe;

import android.net.Uri;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** Reads the website's approved targets and its published AutoEq source index. */
final class TargetCatalog {
    private static final String SITE="https://yinyangeq.com";
    private static final String INDEX="https://raw.githubusercontent.com/yinyangeqlab/yinyang-eq-lab/main/site/data/autoeq-target-sources.json";
    private final CookieManager visitor=new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final Map<String,JSONObject> curves=new HashMap<>();
    private String revision;
    private JSONArray headphones;
    JSONObject headphone(android.content.Context context,String key)throws Exception{
        if(headphones==null)try(InputStream file=context.getAssets().open("headphone-catalog.json")){
            headphones=new JSONArray(OfflineFitter.read(file,8000000));
        }
        JSONObject entry=headphones.getJSONObject(Integer.parseInt(key.substring("headphone:".length())));
        String repository=entry.optString("repository","jaakkopasanen/AutoEq");
        if(!repository.equals("jaakkopasanen/AutoEq") && !repository.equals("JaysAudio/JaysAudio.github.io"))
            throw new IOException("Unsupported measurement source.");
        String base="https://raw.githubusercontent.com/"+repository+(entry.has("repository")?"/main/":"/master/");
        JSONObject result=new JSONObject().put("csv",get(base+Uri.encode(entry.getString("path"),"/"),4000000,false)).put("column",-1);
        if(entry.has("rightPath"))result.put("rightCsv",get(base+Uri.encode(entry.getString("rightPath"),"/"),4000000,false));
        return result;
    }

    private String get(String url,int limit,boolean enter)throws Exception{
        URL u=new URL(url);
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        boolean site=u.getHost().equals("yinyangeq.com");
        try{
            c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(6000);c.setReadTimeout(10000);
            if(site)for(Map.Entry<String,List<String>> e:visitor.get(u.toURI(),Collections.emptyMap()).entrySet())
                for(String value:e.getValue())c.addRequestProperty(e.getKey(),value);
            int status=c.getResponseCode();
            if(site)visitor.put(u.toURI(),c.getHeaderFields());
            if(enter)return "";
            if(status==401 || status==403)throw new java.nio.file.AccessDeniedException(u.getHost());
            if(status!=200)throw new IOException("Target service returned "+status);
            try(InputStream in=c.getInputStream()){return OfflineFitter.read(in,limit);}
        }finally{c.disconnect();}
    }
    private JSONArray siteTargets()throws Exception{
        try{return new JSONArray(get(SITE+"/api/targets",12*1024*1024,false));}
        catch(java.nio.file.AccessDeniedException e){
            // Enter through the normal website route only when open access is enabled.
            if(new JSONObject(get(SITE+"/api/invite-system",4096,false)).getBoolean("enabled"))throw e;
            get(SITE+"/shootout",0,true);
            return new JSONArray(get(SITE+"/api/targets",12*1024*1024,false));
        }
    }
    JSONObject index()throws Exception{
        JSONArray rows=new JSONArray();Map<String,JSONObject> next=new HashMap<>();
        boolean website=false,sources=false;
        try{
            JSONArray targets=siteTargets();
            for(int i=0;i<targets.length();i++){
                JSONObject t=targets.getJSONObject(i);JSONArray points=t.optJSONArray("points");
                if(points==null || points.length()<2 || points.length()>20000)continue;
                String key="site:"+t.getString("id");
                next.put(key,new JSONObject().put("points",points));
                rows.put(new JSONObject().put("key",key).put("name",t.getString("name")).put("credit",t.optString("creator")));
            }website=true;
        }catch(Exception ignored){}
        try{
            JSONObject catalog=new JSONObject(get(INDEX,512000,false));String rev=catalog.getString("revision");
            if(!rev.matches("[a-f0-9]{40}"))throw new IOException("Invalid catalogue revision.");
            JSONArray entries=catalog.getJSONArray("entries");
            for(int i=0;i<entries.length();i++){
                JSONObject t=entries.getJSONObject(i);if("conversion".equals(t.optString("kind")))continue;
                String file=t.getString("file"),hash=t.getString("sha256");
                if(!hash.matches("[a-f0-9]{64}")||file.contains("/")||!file.endsWith(".csv"))continue;
                JSONArray cols=t.optJSONArray("columns");
                for(int k=0;k<(cols==null?1:cols.length());k++){
                    String key=hash+":"+k,name=cols==null?t.optString("name",file.substring(0,file.length()-4)):cols.getString(k)+" (combined table)";
                    next.put(key,new JSONObject().put("file",file).put("hash",hash).put("column",cols==null?-1:k));
                    rows.put(new JSONObject().put("key",key).put("name",name).put("credit",t.optString("credit")));
                }
            }revision=rev;sources=true;
        }catch(Exception ignored){}
        if(!sources && !website)throw new IOException("Target index unavailable. Try again.");
        curves.clear();curves.putAll(next);
        return new JSONObject().put("rows",rows).put("partial",!sources||!website);
    }
    JSONObject curve(String key)throws Exception{
        JSONObject t=curves.get(key);if(t==null)throw new IOException("Search the catalogue again.");
        if(t.has("points"))return t;
        String csv=get("https://raw.githubusercontent.com/jaakkopasanen/AutoEq/"+revision+"/targets/"+Uri.encode(t.getString("file")),200000,false);
        if(!OfflineFitter.hash(csv).equals(t.getString("hash")))throw new IOException("Target file differs from the published index.");
        return new JSONObject().put("csv",csv).put("column",t.getInt("column"));
    }
}
