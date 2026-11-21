package com.yinyangeq.qudelixprobe;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.IOException;
import java.util.regex.*;

/** The official Android controller ships its update images as model-specific assets. Read filenames only. */
final class ControllerFirmware {
    private static final Pattern IMAGE = Pattern.compile("(?i)^Qudelix[.]5k[.]plus[.].*?[.]v([0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3})[.]bin$");
    static int compare(String a,String b) {
        String[] x=a.split("[.]"), y=b.split("[.]");
        for(int i=0;i<3;i++) {
            int c=Integer.compare(Integer.parseInt(x[i]),Integer.parseInt(y[i]));
            if(c!=0)return c;
        }
        return 0;
    }
    static String latest(Context c,boolean plus) {
        try {
            Pattern image=plus ? IMAGE : Pattern.compile("(?i)^Qudelix5K[.]v([0-9]{1,3}[.][0-9]{1,3}[.][0-9]{1,3})(?:[.]Lot[0-9]+)?[.]bin$");
            String[] files=c.getPackageManager().getResourcesForApplication("com.qudelix.qudelix").getAssets().list(plus?"DFU/x5k.plus":"DFU/x5k");
            String latest=null;
            if(files!=null) for(String name:files) {
                Matcher m=image.matcher(name);
                if(m.matches() && (latest==null || compare(m.group(1),latest)>0)) latest=m.group(1);
            }
            return latest;
        } catch(PackageManager.NameNotFoundException | IOException | SecurityException e) { return null; }
    }
}
