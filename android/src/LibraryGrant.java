package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import android.util.AtomicFile;
import org.json.JSONArray;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** A scoped device credential. No browser cookies or account passwords are used. */
final class LibraryGrant {
    private final SharedPreferences prefs;
    private static final String ALIAS="companion-library-v1";
    private final AtomicFile cache;
    LibraryGrant(Context c){prefs=c.getSharedPreferences(ALIAS,Context.MODE_PRIVATE);cache=new AtomicFile(new File(c.getNoBackupFilesDir(),"companion-offline-library"));}
    synchronized JSONArray cachedPages(){
        if(!linked())return null;
        try(DataInputStream input=new DataInputStream(cache.openRead())){
            byte[] iv=new byte[12];input.readFully(iv);int n=input.readInt();if(n<16||n>12*1024*1024)return null;
            byte[] body=new byte[n];input.readFully(body);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));cipher.updateAAD(challenge().getBytes(StandardCharsets.US_ASCII));
            return new JSONArray(new String(cipher.doFinal(body),StandardCharsets.UTF_8));
        }catch(Exception ignored){return null;}
    }
    synchronized void storePages(JSONArray pages){
        try{
            byte[] data=pages.toString().getBytes(StandardCharsets.UTF_8);if(data.length>12*1024*1024-16){dropCache();return;}
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(challenge().getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted=cipher.doFinal(data);FileOutputStream out=cache.startWrite();
            try{DataOutputStream dest=new DataOutputStream(out);dest.write(cipher.getIV());dest.writeInt(encrypted.length);dest.write(encrypted);dest.flush();cache.finishWrite(out);}
            catch(IOException e){cache.failWrite(out);throw e;}
        }catch(Exception ignored){dropCache();}
    }
    synchronized void dropCache(){cache.delete();}
    private java.security.Key key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return store.getKey(ALIAS,null);
        KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    String secret() throws Exception {
        String data=prefs.getString("cipher",null);if(data==null)return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return hex(c.doFinal(Base64.decode(data,Base64.NO_WRAP)));
    }
    void create() throws Exception {
        byte[] secret=new byte[32];new SecureRandom().nextBytes(secret);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        byte[] data=c.doFinal(secret);
        if(!prefs.edit().putString("cipher",Base64.encodeToString(data,Base64.NO_WRAP)).putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP))
            .putLong("issued",System.currentTimeMillis()).putBoolean("linked",false).commit())throw new java.io.IOException("Device credential could not be stored.");
    }
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    String challenge()throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(secret().getBytes("US-ASCII")));}
    long issued(){return prefs.getLong("issued",0);}
    boolean linked(){return prefs.getBoolean("linked",false);}
    void verified(){prefs.edit().putBoolean("linked",true).apply();}
    void clear(){dropCache();prefs.edit().clear().apply();}
}
