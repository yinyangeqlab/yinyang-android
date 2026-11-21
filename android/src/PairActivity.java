package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Only the one-time pairing code crosses the public intent boundary. */
public final class PairActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = getIntent().getData();
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && uri != null
                && "yinyangeq-companion".equals(uri.getScheme()) && "pair".equals(uri.getEncodedAuthority())
                && (uri.getPath() == null || uri.getPath().isEmpty()) && uri.getQuery() == null) {
            String code = uri.getFragment();
            if (code != null && code.matches("[A-HJ-NP-Z2-9]{10}")) {
                Intent target = new Intent(this, LabActivity.class);
                target.putExtra("pairCode",code);
                target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(target);
            }
        }
        finish();
    }
}
