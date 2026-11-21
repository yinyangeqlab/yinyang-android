package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** First-party browser for both device profiles. Hardware writes remain in the model-specific editor. */
public final class TuningActivity extends CompanionLab {
    static final int REQUEST = 489;
    private TextView hint;

    private boolean plus() {
        DeviceBinding device = binding();
        return device == null ? getIntent().getBooleanExtra("plus", false) : device.profile.model == DeviceProfile.Model.PLUS;
    }
    @Override void siteHeader(LinearLayout root) {
        modelHeader(this,root,plus(),this::chooseTheme,()->leave(DIAGNOSTICS));
    }
    @Override void labNavigation(LinearLayout root) {
        hint = LabStyle.text(this,
                "After linking Sync, find saved measurements and targets from saved EQs in EQ → Auto EQ. Open Sync → My library for presets and channel matches.",
                13,MUTED,false);
        hint.setPadding(LabStyle.dp(this,14),LabStyle.dp(this,6),LabStyle.dp(this,14),LabStyle.dp(this,6));
        root.addView(hint);
        collectionHint(false);
        nav(this,root,syncBrowser() ? LAB : WEBSITE,this::leave);
    }
    @Override void collectionHint(boolean visible) {
        if (hint != null) hint.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
    @Override void validateTuning(String text) {
        if (plus()) PlusPreset.parse(text,false,30);
        else super.validateTuning(text);
    }
    @Override void openEditor(String text,String name,String id) {
        setResult(RESULT_OK,new Intent().putExtra("tuningText",text).putExtra("tuningName",name).putExtra("tuningId",id));
        closeTab();
    }
    private void leave(int destination) {
        setResult(RESULT_OK,new Intent().putExtra("nav",destination));
        closeTab();
    }
    static boolean originalResult(AppearanceActivity host,int request,int result,Intent data) {
        if (request != REQUEST) return false;
        if (result != Activity.RESULT_OK || data == null) return true;
        DeviceBinding device = host.binding();
        if (device == null || !device.active(host)) return true;
        if (data.hasExtra("tuningText")) {
            Intent editor = new Intent(host,DeviceWorkbench.class).putExtras(data)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            host.openTab(editor,-1);
            if (host instanceof DeviceWorkbench) host.closeTab();
        } else {
            int destination=data.getIntExtra("nav",EQ);
            if (host instanceof DeviceWorkbench) {
                Intent initial=host.getIntent();
                int current=initial.getBooleanExtra("devicePage",false)?HEADPHONES:initial.getBooleanExtra("audio",false)?AUDIO:EQ;
                if (destination==current) return true;
            } else if (host instanceof ProbeActivity && destination==DIAGNOSTICS) return true;
            CompanionNavigation.go(host,destination,device.profile.address);
        }
        return true;
    }
}
