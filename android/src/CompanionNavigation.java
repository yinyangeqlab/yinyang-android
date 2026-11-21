package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.content.Intent;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

final class CompanionNavigation {
    static void go(Activity a,int dest,String address) {
        if (dest == WEBSITE) {
            ((AppearanceActivity)a).openTab(new Intent(a,TuningActivity.class),TuningActivity.REQUEST);
            return;
        }
        boolean probe=dest==DIAGNOSTICS;
        Class<?> target=probe?ProbeActivity.class:(dest==LAB || dest==TUNING)?LabActivity.class:DeviceWorkbench.class;
        Intent i=new Intent(a,target).putExtra("device",address)
            .putExtra("autoEq",dest==TUNING).putExtra("blind",dest==BLIND)
            .putExtra("audio",dest==AUDIO || dest==HEADPHONES).putExtra("devicePage",dest==HEADPHONES);
        if(target==LabActivity.class && a instanceof DeviceWorkbench)((DeviceWorkbench)a).attachSyncDraft(i);
        if(probe) {
            i.putExtra(ProbeActivity.OPEN_COMPATIBILITY,dest==DIAGNOSTICS);
            if(a instanceof DeviceWorkbench)i.putExtra("bank",((DeviceWorkbench)a).editorBank());
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        }
        if(a instanceof DeviceWorkbench && target==LabActivity.class){
            i.putExtra("returnTab",a.getIntent().getBooleanExtra("audio",false)?
                    (a.getIntent().getBooleanExtra("devicePage",false)?HEADPHONES:AUDIO):EQ);
            ((AppearanceActivity)a).openTab(i,-1);return;
        }
        if(a instanceof LabActivity && a.getIntent().hasExtra("returnTab") && target==DeviceWorkbench.class)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        AppearanceActivity source=(AppearanceActivity)a;
        source.openTab(i,-1);
        if(!probe && (a instanceof DeviceWorkbench || a instanceof LabActivity))source.closeTab();
    }
}
