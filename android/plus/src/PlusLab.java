package com.yinyangeq.qudelixprobe;
import android.content.Intent;
public final class PlusLab extends CompanionLibrary {
    boolean validFit(String shared,String match){
        PlusPreset.Channel usr=PlusPreset.parse(shared,false,30)[0];
        PlusPreset.Channel[] ears=PlusPreset.parse(match,true,20);
        PlusPreset.banks(PlusPreset.Mode.LAYERED,usr,ears[0],ears[1],true);return true;
    }
    boolean plus(){return true;}
    void navigate(int dest){setResult(RESULT_OK,new Intent().putExtra("nav",dest));closeTab();}
    void receive(String title,String shared,String match){setResult(RESULT_OK,new Intent().putExtra("tuningName",title)
        .putExtra("tuningText",shared).putExtra("matchText",match));closeTab();}
}
