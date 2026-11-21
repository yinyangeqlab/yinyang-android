package com.yinyangeq.qudelixprobe;
import android.content.Context;
final class PlusFirmware {
    static String latest(Context c){return ControllerFirmware.latest(c,true);}
    static int compare(String a,String b){return ControllerFirmware.compare(a,b);}
}
