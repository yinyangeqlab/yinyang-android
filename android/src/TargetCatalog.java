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
    private JSONArray headphones,bundledHeadphones;

    JSONObject headphoneIndex()throws Exception{
        JSONArray entries=new JSONObject(siteApi("/api/headphone-catalog",8*1024*1024)).getJSONArray("entries");
        JSONArray rows=new JSONArray();
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);
            String source=entry.optString("source",""),rig=entry.optString("rig","");
            String credit=source.isEmpty()?rig:rig.isEmpty()?source:source+" · "+rig;
            rows.put(new JSONObject().put("key","headphone:"+i).put("name",entry.getString("name"))
                    .put("credit",credit).put("type",entry.getString("type")));
        }
        headphones=entries;
        return new JSONObject().put("rows",rows);
    }

    JSONObject headphone(android.content.Context context,String key)throws Exception{
        boolean bundled=key.startsWith("bundled-headphone:");
        if(bundled && bundledHeadphones==null)
            try(InputStream file=context.getAssets().open("headphone-catalog.json")){
                bundledHeadphones=new JSONArray(OfflineFitter.read(file,8*1024*1024));
            }
        JSONArray entries=bundled?bundledHeadphones:headphones;
        if(entries==null)throw new IOException("Search the headphone catalogue again.");
        JSONObject entry=entries.getJSONObject(Integer.parseInt(key.substring(key.indexOf(':')+1)));
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
            if(site){
                List<String> cookies=visitor.get(u.toURI(),Collections.emptyMap()).get("Cookie");
                if(cookies!=null && !cookies.isEmpty())c.setRequestProperty("Cookie",String.join("; ",cookies));
            }
            int status=c.getResponseCode();
            if(site){
                visitor.put(u.toURI(),c.getHeaderFields());
                // Send browser-style cookies, without obsolete RFC 2965 quoting or attributes.
                for(HttpCookie cookie:visitor.getCookieStore().getCookies())cookie.setVersion(0);
            }
            if(enter)return "";
            if(status==401 || status==403)throw new java.nio.file.AccessDeniedException(u.getHost());
            if(status!=200)throw new IOException("Target service returned "+status);
            try(InputStream in=c.getInputStream()){return OfflineFitter.read(in,limit);}
        }finally{c.disconnect();}
    }
    private String siteApi(String path,int limit)throws Exception{
        try{return get(SITE+path,limit,false);}
        catch(java.nio.file.AccessDeniedException e){
            // Enter through the normal website route only when open access is enabled.
            if(new JSONObject(get(SITE+"/api/invite-system",4096,false)).getBoolean("enabled"))throw e;
            get(SITE+"/shootout",0,true);
            return get(SITE+path,limit,false);
        }
    }
    private Map<String,Integer> globalRanks()throws Exception{
        JSONObject research=new JSONObject(siteApi("/api/research",8*1024*1024));
        JSONArray rows=research.getJSONObject("allFitters").getJSONObject("all").getJSONArray("rows");
        Map<String,Integer> ranks=new HashMap<>();
        for(int i=0;i<rows.length();i++)ranks.put(rows.getJSONObject(i).getString("id"),i+1);
        return ranks;
    }

    private static JSONObject row(String key,String id,String name,String credit)throws JSONException{
        return new JSONObject().put("key",key).put("id",id).put("name",name).put("credit",credit);
    }

    JSONObject index()throws Exception{
        JSONArray rows=new JSONArray();Map<String,JSONObject> next=new HashMap<>();
        boolean website=false,sources=false,ranked=false;
        try{
            JSONArray targets=new JSONArray(siteApi("/api/targets",12*1024*1024));
            for(int i=0;i<targets.length();i++){
                JSONObject t=targets.getJSONObject(i);JSONArray points=t.optJSONArray("points");
                if(points==null || points.length()<2 || points.length()>20000)continue;
                String id=t.getString("id"),key="site:"+id;
                next.put(key,new JSONObject().put("points",points));
                rows.put(row(key,id,t.getString("name"),t.optString("creator")));
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
                String builtIn=t.optString("builtInId","");
                if(!builtIn.isEmpty() && next.containsKey("site:"+builtIn))continue;
                JSONArray cols=t.optJSONArray("columns");
                JSONArray shared=t.optJSONArray("shared");
                for(int k=0;k<(cols==null?1:cols.length());k++){
                    String key=hash+":"+k,name=cols==null?t.optString("name",file.substring(0,file.length()-4)):cols.getString(k)+" (combined table)";
                    JSONObject variant=shared==null?null:shared.optJSONObject(k);
                    String localId="autoeq-local-"+hash.substring(0,32)+"-"+k;
                    String id=variant!=null?variant.getString("id"):builtIn.isEmpty()?localId:builtIn;
                    next.put(key,new JSONObject().put("file",file).put("hash",hash).put("column",cols==null?-1:k));
                    rows.put(row(key,id,name,t.optString("credit")).put("localId",localId));
                }
            }revision=rev;sources=true;
        }catch(Exception ignored){}
        if(!sources && !website)throw new IOException("Target index unavailable. Try again.");
        try{
            Map<String,Integer> ranks=globalRanks();
            for(int i=0;i<rows.length();i++){
                JSONObject entry=rows.getJSONObject(i);
                Integer rank=ranks.get(entry.getString("id"));
                if(rank!=null)entry.put("globalRank",rank);
            }
            ranked=true;
        }catch(Exception ignored){}
        curves.clear();curves.putAll(next);
        return new JSONObject().put("rows",rows).put("partial",!sources||!website).put("ranked",ranked);
    }
    JSONObject curve(String key)throws Exception{
        JSONObject t=curves.get(key);if(t==null)throw new IOException("Search the catalogue again.");
        if(t.has("points"))return t;
        String csv=get("https://raw.githubusercontent.com/jaakkopasanen/AutoEq/"+revision+"/targets/"+Uri.encode(t.getString("file")),200000,false);
        if(!OfflineFitter.hash(csv).equals(t.getString("hash")))throw new IOException("Target file differs from the published index.");
        return new JSONObject().put("csv",csv).put("column",t.getInt("column"));
    }
}
