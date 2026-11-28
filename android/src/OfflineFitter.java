package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** The cached program runs only in a worker with no native bridge or network access. */
final class OfflineFitter {
    private final AtomicFile file;
    private final String origin,bundled;
    private static final int LIMIT=1024*1024;
    OfflineFitter(Context context,String origin) throws IOException {
        this.origin=origin;
        file=new AtomicFile(new File(context.getNoBackupFilesDir(),"fitter-v1-all-band"));
        try(InputStream input=context.getAssets().open("fitter-version.txt")){bundled=read(input,128).trim();}
    }
    static String read(InputStream input,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];int n;
        while((n=input.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("File too large.");out.write(buf,0,n);}
        return out.toString("UTF-8");
    }
    static String hash(String data)throws Exception{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));return hex.toString();
    }
    String cached(){
        try(InputStream in=file.openRead()){
            String record=read(in,LIMIT+65);if(record.length()<=65 || record.charAt(64)!='\n')return "";
            String code=record.substring(65);return hash(code).equals(record.substring(0,64))?code:"";
        }catch(Exception ignored){return "";}
    }
    private String download(String suffix,int limit)throws IOException{
        HttpURLConnection c=(HttpURLConnection)new URL(origin+"/vendor/peqdb-autoeq/companion-v1."+suffix).openConnection();
        try{c.setInstanceFollowRedirects(false);c.setConnectTimeout(3500);c.setReadTimeout(8000);c.setUseCaches(false);
            if(c.getResponseCode()!=200)throw new IOException("Fitter update unavailable.");
            try(InputStream in=c.getInputStream()){return read(in,limit);}
        }finally{c.disconnect();}
    }
    String update(String current)throws Exception{
        JSONObject manifest=new JSONObject(download("json",1024));String expected=manifest.getString("sha256");
        int size=manifest.getInt("bytes");
        if(manifest.getInt("format")!=1 || !manifest.optBoolean("allBand",false) ||
                !expected.matches("[0-9a-f]{64}") || size<=0 || size>LIMIT)return null;
        if(expected.equals(bundled)){clear();return "";}
        if(!current.isEmpty() && hash(current).equals(expected))return null;
        String program=download("mjs",size);
        if(program.getBytes(StandardCharsets.UTF_8).length!=size || !hash(program).equals(expected))throw new IOException("Invalid fitter update.");
        FileOutputStream out=file.startWrite();
        try{out.write((expected+"\n"+program).getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(IOException e){file.failWrite(out);throw e;}
        return program;
    }
    void clear(){file.delete();}
}
