package com.yinyangeq.qudelixprobe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** First-party web workspace. Android owns navigation and the document pickers. */
abstract class CompanionLab extends AppearanceActivity {
    abstract void labNavigation(LinearLayout root);
    abstract void siteHeader(LinearLayout root);
    abstract void collectionHint(boolean visible);
    abstract void openEditor(String text, String name, String id);
    void validateTuning(String text) { Peq.flat(0).importText(text); }
    static final String OPEN_APPROVAL = "approveLink", OPEN_BROWSER_DEVICES = "browserDevices";
    private static final String ORIGIN = "https://yinyangeq.com";
    private static final String[] PATHS = {
            "/preference-tuning", "/shootout?tab=eqs"};
    private static final String[] LABELS = {"Tuning", "My Collection"};
    private static final int OPEN = 1, SAVE = 2, CHUNK = 32768;
    private final Button[] tabs = new Button[PATHS.length];
    private WebView web;
    private ProbeLog log;
    private long pageStarted;
    private String timedPage;
    private ProgressBar loading;
    private LinearLayout error;
    private Button retry;
    private android.net.ConnectivityManager network;
    private android.net.ConnectivityManager.NetworkCallback networkWatch;
    private TextView errorTitle;
    private void showFailure(boolean disconnected) {
        errorTitle.setText(disconnected ? "You’re offline" : "Can’t reach YinYang EQ");
        loading.setVisibility(View.INVISIBLE);
        error.setVisibility(View.VISIBLE);
        web.setVisibility(View.INVISIBLE);
    }
    private void clearFailure() {
        error.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }
    private void load(String url) {
        expectedUrl = url;
        clearFailure();
        web.loadUrl(url);
    }
    private ValueCallback<Uri[]> upload;
    private String expectedUrl, saveId;
    private long size;
    private java.util.concurrent.CompletableFuture<String> pendingSlice;
    private String sliceTag;
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private int section;
    final boolean syncBrowser() {
        return getIntent().getBooleanExtra(OPEN_APPROVAL,false)
                || getIntent().getBooleanExtra(OPEN_BROWSER_DEVICES,false);
    }
    static boolean trusted(Uri u) {
        return u != null && "https".equalsIgnoreCase(u.getScheme())
                && "yinyangeq.com".equalsIgnoreCase(u.getHost()) && u.getUserInfo() == null
                && (u.getPort() == -1 || u.getPort() == 443);
    }
    private static boolean ours(String url) {
        return url != null && trusted(Uri.parse(url));
    }
    private void recordPageTime(String url, String outcome) {
        if (!url.equals(timedPage)) return;
        log.timing("Load Lab " + LABELS[section], android.os.SystemClock.elapsedRealtime() - pageStarted, outcome);
        timedPage = null;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        log = new ProbeLog(getFilesDir());
        network = getSystemService(android.net.ConnectivityManager.class);
        section = Math.min(
                PATHS.length - 1, Math.max(0, state == null ? getIntent().getIntExtra("page", 0) : state.getInt("section")));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LabStyle.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(v, insets);
            return insets;
        });
        setContentView(root);
        siteHeader(root);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(row);
        if (syncBrowser()) {
            Button back = CompanionActions.secondary(this,"Back to Sync",this::closeTab);
            row.addView(back,-2,-2);
        } else {
            for (int i = 0; i < tabs.length; i++) {
                final int page = i;
                Button b = new Button(this);
                tabs[i] = b;
                b.setText(LABELS[i]);
                b.setContentDescription(i == 0 ? "Personal EQ Tuning" : LABELS[i]);
                b.setMinWidth(0);
                b.setPadding(dp(2),0,dp(2),0);
                b.setAllCaps(false);
                b.setOnClickListener(v -> showPage(page));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(dp(2), 0, dp(2), 0);
                row.addView(b, lp);
            }
        }
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(loading, -1, dp(2));
        FrameLayout pane = new FrameLayout(this);
        root.addView(pane, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this);
        web.setAlpha(0f);
        pane.addView(web);
        web.setBackgroundColor(LabStyle.BG);
        WebSettings settings = web.getSettings();
        settings.setUserAgentString(settings.getUserAgentString() + " YinYangCompanion/1 YinYangPeqImport/1 YinYangPeqSync/1");
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        error = new LinearLayout(this);
        error.setOrientation(LinearLayout.VERTICAL);
        error.setGravity(Gravity.CENTER);
        error.setBackgroundColor(LabStyle.BG);
        error.setPadding(dp(24), 0, dp(24), 0);
        errorTitle = LabStyle.text(this, "", 23, LabStyle.INK, true);
        errorTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.addView(errorTitle);
        TextView advice = LabStyle.text(this, "Check your connection settings and try again.", 16, LabStyle.MUTED, false);
        advice.setGravity(Gravity.CENTER);
        advice.setPadding(0, dp(6), 0, dp(16));
        error.addView(advice);
        retry = new Button(this);
        retry.setText("Try again");
        LabStyle.button(retry, true);
        retry.setOnClickListener(v -> {
            load(expectedUrl);
        });
        error.addView(retry);
        Button connection = new Button(this);
        connection.setText("Connection settings");
        LabStyle.quiet(connection);
        connection.setOnClickListener(v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (ActivityNotFoundException e) { notice("Open the network settings on this device."); }
        });
        error.addView(connection);
        pane.addView(error);
        error.setVisibility(View.GONE);
        labNavigation(root);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (trusted(u))
                    return false;
                if (r.isForMainFrame() && r.hasGesture()
                        && ("https".equals(u.getScheme()) || "mailto".equals(u.getScheme())))
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (ActivityNotFoundException e) {
                        notice("No app can open that link.");
                    }
                return true;
            }
            @Override
            public void onPageStarted(WebView w, String url, Bitmap icon) {
                if (ours(url)) {
                    expectedUrl = url;
                    timedPage = url;
                    pageStarted = android.os.SystemClock.elapsedRealtime();
                    clearFailure();
                    loading.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void doUpdateVisitedHistory(WebView w, String url, boolean reload) {
                selectPageForUrl(url);
            }
            @Override
            public void onReceivedError(WebView w, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    expectedUrl = r.getUrl().toString();
                    log.record("Lab "+LABELS[section]+" page load failed · WebView "+e.getErrorCode());
                    recordPageTime(r.getUrl().toString(), "failed");
                    showFailure(network.getActiveNetwork() == null);
                }
            }
            @Override
            public void onReceivedHttpError(WebView w, WebResourceRequest r, WebResourceResponse response) {
                if (r.isForMainFrame()) log.record("Lab "+LABELS[section]+" HTTP "+response.getStatusCode());
                if (r.isForMainFrame() && response.getStatusCode() >= 500) {
                    expectedUrl = r.getUrl().toString();
                    recordPageTime(expectedUrl, "HTTP failure");
                    showFailure(false);
                }
            }
            @Override
            public void onPageFinished(WebView w, String url) {
                if (ours(url) && ours(w.getUrl())) {
                    recordPageTime(url, "WebView finished");
                    applyLabTheme(w);
                    w.evaluateJavascript(EXPORT_HOOK, null);
                    if (w.getAlpha()==0f) w.postVisualStateCallback(0,new WebView.VisualStateCallback(){
                        @Override public void onComplete(long id){
                            if(isDestroyed())return;
                            w.setAlpha(1f);
                            if(w.getProgress()==100)loading.setVisibility(View.INVISIBLE);
                        }
                    });
                }
                CookieManager.getInstance().flush();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView w, int p) {
                loading.setProgress(p);
                loading.setVisibility((p == 100 && w.getAlpha()>0f) || error.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
            }
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (!ours(w.getUrl()))
                    return false;
                if (upload != null)
                    upload.onReceiveValue(null);
                upload = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                   .addCategory(Intent.CATEGORY_OPENABLE)
                                   .setType("*/*")
                                   .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                           params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                java.util.ArrayList<String> types = new java.util.ArrayList<>();
                for (String type : params.getAcceptTypes())
                    if (type.contains("/") && !type.contains(","))
                        types.add(type);
                if (!types.isEmpty())
                    i.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                try {
                    startActivityForResult(i, OPEN);
                } catch (ActivityNotFoundException e) {
                    upload.onReceiveValue(null);
                    upload = null;
                    notice("Android’s file picker is unavailable.");
                }
                return true;
            }
            @Override
            public boolean onJsPrompt(WebView w, String url, String msg, String data, JsPromptResult result) {
                if ("yinyang-peq-device".equals(msg)) {
                    if (tuningPage(url) && tuningPage(w.getUrl())) companionIdentity(result);
                    else result.cancel();
                    return true;
                }
                if ("yinyang-import-peq".equals(msg)) {
                    if (tuningPage(url) && tuningPage(w.getUrl())) offerTuningDraft(data, result);
                    else result.cancel();
                    return true;
                }
                if ("yinyang-export-slice".equals(msg) && ours(url) && ours(w.getUrl())) {
                    result.confirm("");
                    if (pendingSlice != null && sliceTag != null && data != null && data.startsWith(sliceTag)) {
                        pendingSlice.complete(data.substring(sliceTag.length()));
                        pendingSlice = null;
                        sliceTag = null;
                    }
                    return true;
                }
                if (!"yinyang-file-export".equals(msg) || !ours(url) || !ours(w.getUrl()))
                    return super.onJsPrompt(w, url, msg, data, result);
                result.confirm("");
                offer(data);
                return true;
            }
        });
        web.setDownloadListener((url, agent, disposition, mime, bytes) -> {
            if (!ours(web.getUrl()))
                return;
            if (ours(url) || url.startsWith("blob:" + ORIGIN + "/")) {
                String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
                web.evaluateJavascript("window.yyOffer && window.yyOffer(" + JSONObject.quote(url) + ","
                                + JSONObject.quote(name) + ")",
                        null);
            } else
                notice("This download needs an external file source.");
        });
        WebBackForwardList history = state == null ? null : web.restoreState(state);
        WebHistoryItem current = history == null ? null : history.getCurrentItem();
        if (current != null && ours(current.getUrl())) {
            expectedUrl = current.getUrl();
            selectPageForUrl(expectedUrl);
            return;
        }
        if (getIntent().getBooleanExtra(OPEN_APPROVAL,false)) {
            LibraryGrant grant = new LibraryGrant(this);
            try {
                if (!grant.linked() && grant.issued()>0 && System.currentTimeMillis()-grant.issued()<600000) {
                    collectionHint(false);
                    load(ORIGIN+"/companion-link#key="+grant.challenge()+"&at="+grant.issued());
                    return;
                }
            } catch (Exception ignored) { /* The code may have expired or been replaced. */ }
            notice("Return to Sync for a new approval code.");
            closeTab();
            return;
        }
        if (getIntent().getBooleanExtra(OPEN_BROWSER_DEVICES,false)) {
            collectionHint(false);
            load(ORIGIN+"/account-sync");
            return;
        }
        showPage(section);
    }
    private void selectPageForUrl(String url) {
        if (!ours(url) || syncBrowser()) return;
        Uri u = Uri.parse(url);
        int active = tuningPage(url) ? 0 : "/shootout".equals(u.getPath()) && "eqs".equals(u.getQueryParameter("tab")) ? 1 : -1;
        if (active >= 0) section = active;
        styleTabs(active);
        collectionHint(active == 0);
    }
    private static boolean tuningPage(String url) {
        if (!ours(url)) return false;
        String path = Uri.parse(url).getPath();
        return "/preference-tuning".equals(path) || "/preference-tuning/".equals(path);
    }

    private void companionIdentity(JsPromptResult result) {
        android.content.SharedPreferences prefs = getSharedPreferences("companion-sync", MODE_PRIVATE);
        String key = prefs.getString("installKey", null);
        if (key == null) {
            key = (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()).replace("-", "");
            prefs.edit().putString("installKey", key).apply();
        }
        String name = null;
        try { name = android.provider.Settings.Global.getString(getContentResolver(), "device_name"); }
        catch (SecurityException ignored) { /* Some Android builds restrict the system device name. */ }
        if (name == null || name.trim().isEmpty()) name = android.os.Build.MODEL;
        if (name == null || name.trim().isEmpty()) name = "Android companion";
        name = name.replaceAll("\\p{Cntrl}", " ").trim();
        String received = getSharedPreferences("peq-workbench", MODE_PRIVATE).getString("receivedTuningId", "");
        try {
            result.confirm(new JSONObject().put("name", name.substring(0, Math.min(60, name.length())))
                    .put("installKey", key).put("received", received).toString());
        } catch (org.json.JSONException error) { result.cancel(); }
    }

    private void offerTuningDraft(String payload, JsPromptResult result) {
        try {
            if (payload == null || payload.length() > 8192) throw new IllegalArgumentException();
            JSONObject preset = new JSONObject(payload);
            String text = preset.getString("text");
            String name = preset.getString("name").trim();
            if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException();
            String id = preset.optString("id", "");
            if (!id.isEmpty()) {
                java.util.UUID.fromString(id);
                if (id.equals(getSharedPreferences("peq-workbench", MODE_PRIVATE).getString("receivedTuningId", ""))) {
                    result.confirm("opened");
                    return;
                }
            }
            validateTuning(text);
            new android.app.AlertDialog.Builder(this)
                .setTitle("Import to Main EQ?")
                .setMessage("Replace the current stereo editor draft with “" + name
                    + "”. You can review the filters before sending them to your device.")
                .setNegativeButton("Cancel", (dialog, which) -> result.cancel())
                .setOnCancelListener(dialog -> result.cancel())
                .setPositiveButton("Import draft", (dialog, which) -> {
                    openEditor(text,name,id);
                    result.confirm("opened");
                }).show();
        } catch (org.json.JSONException | IllegalArgumentException error) {
            result.confirm("invalid");
        }
    }

    private void applyLabTheme(WebView page) {
        String scheme=AppearanceActivity.theme(this)==2 ? "light" : "dark";
        String css=String.format(java.util.Locale.ROOT,
                ":root{color-scheme:%s!important;--background:#%06x;--foreground:#%06x;--card:#%06x;--ring:#%06x}"
                +"body{background-color:#%06x} select,option,optgroup{color-scheme:%s}",
                scheme,LabStyle.BG&0xffffff,LabStyle.INK&0xffffff,LabStyle.PANEL&0xffffff,LabStyle.BLUE&0xffffff,LabStyle.BG&0xffffff,scheme);
        page.evaluateJavascript("(()=>{document.documentElement.dataset.siteTheme='"+scheme+"';"
                +"let s=document.querySelector('style[data-yinyang-app]');if(!s){s=document.createElement('style');"
                +"s.dataset.yinyangApp='';document.head.append(s)}s.textContent="+JSONObject.quote(css)+"})()",null);
    }
    private void showPage(int index) {
        section = index;
        styleTabs(index);
        collectionHint(index == 0);
        String dest = ORIGIN + PATHS[index];
        if (network.getActiveNetwork() == null && (web.getUrl() == null || error.getVisibility() == View.VISIBLE)) {
            log.record("Lab "+LABELS[section]+" unavailable offline");
            expectedUrl = dest;
            showFailure(true);
            return;
        }
        if (error.getVisibility() != View.VISIBLE
                && dest.equals(web.getUrl()))
            return;
        // Let the site use its own client router when the destination is already linked on this page.
        if (error.getVisibility() == View.GONE && ours(web.getUrl())) {
            final int target = index;
            final String before = web.getUrl();
            expectedUrl = dest;
            String href = JSONObject.quote(dest);
            web.evaluateJavascript("(()=>{const a=Array.from(document.links).find(a=>a.href===" + href
                    + "&&!a.target&&!a.hasAttribute('download'));if(!a)return false;a.click();return true})()", result -> {
                if (section == target && dest.equals(expectedUrl) && !"true".equals(result)) load(dest);
                else if ("true".equals(result)) web.postDelayed(() -> {
                    if (section == target && dest.equals(expectedUrl) && before.equals(web.getUrl())) load(dest);
                }, 1200);
            });
            return;
        }
        load(dest);
    }
    private void styleTabs(int active) {
        for (int i = 0; i < tabs.length; i++) {
            LabStyle.tab(tabs[i], i == active);
            tabs[i].setSelected(i == active);
            tabs[i].setTextSize(14);
        }
    }
    private int dp(int n) {
        return LabStyle.dp(this, n);
    }
    private void notice(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
    // The site creates short-lived blob URLs, including detached download anchors. Keep its Blob alive
    // until the system file picker returns. Only the first-party main page can offer a file.
    private static final String EXPORT_HOOK =
            "(()=>{if(window.yyOffer)return;let active;window.yyOffer=async(u,n)=>{try{"
            + "if(active)return;if(!(u.startsWith('blob:'+location.origin+'/')||new "
              + "URL(u).origin===location.origin))return;"
            + "let r=await fetch(u);if(!r.ok)throw Error();let b=await r.blob();let id=String(Date.now());"
            + "active={b,id};window.prompt('yinyang-file-export',JSON.stringify({id,name:n,size:b.size,type:b."
              + "type}));"
            + "}catch(e){active=null;alert('This file could not be exported.')}};"
            + "window.yyRead=async(id,pos)=>{if(active?.id!==id)throw Error();let a=new Uint8Array(await "
              + "active.b.slice(pos,pos+32768).arrayBuffer());return btoa(String.fromCharCode(...a))};"
            + "window.yyRelease=id=>{if(active?.id===id)active=null};"
            + "const take=a=>a&&a.hasAttribute('download')&&a.href.startsWith('blob:'+location.origin+'/');"
            + "const "
              + "click=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){if(take("
              + "this)){window.yyOffer(this.href,this.download);return}return click.call(this)};"
            + "document.addEventListener('click',e=>{let "
              + "a=e.target.closest?.('a[download]');if(take(a)){e.preventDefault();window.yyOffer(a.href,a."
              + "download)}},true)})();";
    private void offer(String data) {
        try {
            JSONObject meta = new JSONObject(data);
            String id = meta.getString("id");
            if (!id.matches("[0-9]{1,18}") || saveId != null)
                return;
            saveId = id;
            size = meta.getLong("size");
            String name = meta.optString("name", "YinYang EQ.txt").replaceAll("[\\p{Cntrl}/\\\\]", "_");
            if (name.length() > 150)
                name = name.substring(0, 150);
            if (name.isEmpty())
                name = "YinYang EQ.txt";
            String type = meta.optString("type", "").split(";")[0];
            if (!type.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
                type = "application/octet-stream";
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                           .addCategory(Intent.CATEGORY_OPENABLE)
                                           .setType(type)
                                           .putExtra(Intent.EXTRA_TITLE, name),
                    SAVE);
        } catch (Exception e) {
            release();
            notice("Could not open the export picker.");
        }
    }
    private void release() {
        if (saveId != null && web != null && ours(web.getUrl()))
            web.evaluateJavascript("window.yyRelease?.(" + JSONObject.quote(saveId) + ")", null);
        if (pendingSlice != null)
            pendingSlice.complete(null);
        pendingSlice = null;
        sliceTag = null;
        saveId = null;
    }
    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OPEN && upload != null) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null)
                    for (int i = 0; i < data.getClipData().getItemCount(); i++)
                        uris.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null)
                    uris.add(data.getData());
            }
            uris.removeIf(u -> u == null || !"content".equals(u.getScheme()));
            upload.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
            upload = null;
        }
        if (request == SAVE) {
            if (result != RESULT_OK || data == null || data.getData() == null || saveId == null) {
                release();
                return;
            }
            Uri dest = data.getData();
            String id = saveId;
            long total = size;
            files.execute(() -> writeDownload(dest, id, total));
        }
    }
    private void writeDownload(Uri dest, String id, long total) {
        String outcome = "File exported.";
        try (OutputStream out = getContentResolver().openOutputStream(dest, "wt")) {
            if (out == null || total < 0)
                throw new IOException();
            for (long offset = 0; offset < total; offset += CHUNK) {
                final long pos = offset;
                java.util.concurrent.CompletableFuture<String> chunk =
                        new java.util.concurrent.CompletableFuture<>();
                runOnUiThread(() -> {
                    if (isDestroyed() || !id.equals(saveId) || !ours(web.getUrl())) {
                        chunk.complete(null);
                        return;
                    }
                    pendingSlice = chunk;
                    sliceTag = id + ":" + pos + ":";
                    String tag = JSONObject.quote(sliceTag);
                    web.evaluateJavascript("window.yyRead(" + JSONObject.quote(id) + "," + pos + ")"
                                    + ".then(s=>prompt('yinyang-export-slice'," + tag
                                    + "+s)).catch(()=>prompt('yinyang-export-slice'," + tag + "+'!'))",
                            null);
                });
                String encoded = chunk.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (encoded == null || encoded.length() > (CHUNK + 2) / 3 * 4)
                    throw new IOException();
                byte[] bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
                if (bytes.length != Math.min(CHUNK, total - offset))
                    throw new IOException();
                out.write(bytes);
            }
        } catch (Exception e) {
            log.record("Lab export interrupted · "+e.getClass().getSimpleName());
            outcome = "Export interrupted. Choose the file again to retry.";
        }
        String msg = outcome;
        runOnUiThread(() -> {
            if (!isDestroyed()) {
                release();
                notice(msg);
            }
        });
    }
    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else
            super.onBackPressed();
    }
    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("section", section);
        web.saveState(state);
    }
    @Override
    protected void onStart() {
        super.onStart();
        networkWatch = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onLost(android.net.Network n) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && network.getActiveNetwork() == null) showFailure(true);
                });
            }
        };
        network.registerDefaultNetworkCallback(networkWatch);
        if (network.getActiveNetwork() == null && web.getUrl() == null) showFailure(true);
    }
    @Override
    protected void onStop() {
        network.unregisterNetworkCallback(networkWatch);
        super.onStop();
    }
    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }
    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (tuningPage(web.getUrl())) web.evaluateJavascript("window.dispatchEvent(new Event('yinyang-companion-resume'))", null);
    }
    @Override
    protected void onDestroy() {
        release();
        if (upload != null)
            upload.onReceiveValue(null);
        files.shutdownNow();
        ((android.view.ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
