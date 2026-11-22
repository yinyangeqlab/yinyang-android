package com.yinyangeq.qudelixprobe;
import android.content.Intent;
public final class LabActivity extends CompanionLibrary {
    boolean validFit(String shared,String match){
        Peq.flat(0).importText(shared);Peq.flat(1).importText(match);return true;
    }
    boolean plus(){return false;}
    void navigate(int dest){
        if(standalone()){
            if(dest==LabStyle.HEADPHONES)closeTab();
            else if(dest==LabStyle.WEBSITE)CompanionNavigation.go(this,dest,null);
            return;
        }
        if(dest==getIntent().getIntExtra("returnTab",-1)){closeTab();return;}
        CompanionNavigation.go(this,dest,getIntent().getStringExtra("device"));
    }
    void receive(String title,String shared,String match){
        if(standalone())return;
        openTab(new Intent(this,DeviceWorkbench.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("device",getIntent().getStringExtra("device"))
            .putExtra("libraryDraft",true).putExtra("tuningText",shared).putExtra("matchText",match).putExtra("tuningName",title),-1);
        closeTab();
    }
}
