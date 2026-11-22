package com.yinyangeq.qudelixprobe;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static com.yinyangeq.qudelixprobe.LabStyle.*;

/** App-packaged library and fit engine. Only native HTTPS requests may contact the scoped collection API. */
abstract class CompanionLibrary extends AppearanceActivity {
    private static final String SITE="https://yinyangeq.com";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private WebView page;private LibraryGrant grant;private boolean busy,ready,away;
    private boolean autoEq,returnToLibrary;
    private volatile OfflineFitter fitter;
    private final TargetCatalog targets=new TargetCatalog();
    private final ExecutorService targetIO=Executors.newSingleThreadExecutor();
    private static final String OFFLINE_INDEX="Please restore your internet connection to resume indexing";
    private boolean online(){
        android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps!=null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private void requestTargets(int request,String key){
        targetIO.execute(()->{
            try{
                if(!online())throw new IOException(OFFLINE_INDEX);
                if(key.equals("headphone:index")){
                    JSONArray entries=targets.headphoneIndex().getJSONArray("rows");
                    runOnUiThread(()->{
                        for(int offset=0;offset<entries.length();offset+=200){
                            JSONArray page=new JSONArray();
                            for(int i=offset;i<Math.min(offset+200,entries.length());i++)page.put(entries.opt(i));
                            js("headphoneIndexPage("+request+","+page+")");
                        }
                        js("targetReply("+request+",null,null)");
                    });
                    return;
                }
                JSONObject result=key.startsWith("headphone:") || key.startsWith("bundled-headphone:")
                        ?targets.headphone(this,key):key.isEmpty()?targets.index():targets.curve(key);
                runOnUiThread(()->js("targetReply("+request+","+result.toString()+",null)"));
            }catch(Exception e){
                if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)
                    android.util.Log.d("YYEQ.Catalog", "Catalog request failed: "+e.getClass().getSimpleName()+" · "+e.getMessage());
                String reason=online()?"Target service unavailable. Try again.":OFFLINE_INDEX;
                runOnUiThread(()->js("targetReply("+request+",null,"+JSONObject.quote(reason)+")"));
            }
        });
    }
    private final ExecutorService updates=Executors.newSingleThreadExecutor();
    private LinearLayout navigation;
    private String pendingFitExport;
    abstract boolean plus();
    abstract boolean validFit(String shared,String match);
    private ValueCallback<Uri[]> selectedFiles;
    abstract void navigate(int dest);
    abstract void receive(String title,String shared,String match);
    abstract void compareFits(String payload);
    @Override public void onCreate(Bundle state){
        super.onCreate(state);grant=new LibraryGrant(this);
        if(state!=null)pendingFitExport=state.getString("fitExport");
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,i)->{AppearanceActivity.applyInsets(v,i);return i;});
        if(standalone())LabStyle.standaloneHeader(this,root);
        else LabStyle.modelHeader(this,root,plus(),this::chooseTheme,()->navigate(DIAGNOSTICS));
        FrameLayout content=new FrameLayout(this);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        page=new WebView(this);page.setAlpha(0f);page.setBackgroundColor(BG);content.addView(page,-1,-1);
        ProgressBar loading=new ProgressBar(this);loading.setContentDescription("Loading");
        FrameLayout.LayoutParams placement=new FrameLayout.LayoutParams(dp(this,24),dp(this,24),android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL);
        placement.topMargin=dp(this,32);content.addView(loading,placement);loading.setVisibility(android.view.View.GONE);
        content.postDelayed(()->{if(!ready && !isDestroyed())loading.setVisibility(android.view.View.VISIBLE);},180);
        autoEq=state==null ? getIntent().getBooleanExtra("autoEq",false) : state.getBoolean("autoEq");
        returnToLibrary=state!=null && state.getBoolean("returnToLibrary");
        boolean firstTab=autoEq;
        navigation=new LinearLayout(this);
        navigation.setOrientation(LinearLayout.VERTICAL);
        root.addView(navigation);
        drawNavigation();
        setContentView(root);
        WebSettings settings=page.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);settings.setBlockNetworkLoads(true);settings.setDomStorageEnabled(false);
        page.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> result,FileChooserParams params){
                if(selectedFiles!=null)selectedFiles.onReceiveValue(null);
                selectedFiles=result;
                try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),35);}
                catch(android.content.ActivityNotFoundException e){selectedFiles=null;result.onReceiveValue(null);}
                return true;
            }
        });
        page.addJavascriptInterface(new Bridge(),"Phone");
        page.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public void onPageFinished(WebView v,String url){
                if(ready)return;ready=true;
                if(autoEq!=firstTab)showTab();
                page.postVisualStateCallback(0,new WebView.VisualStateCallback(){
                    @Override public void onComplete(long id){page.setAlpha(1f);loading.setVisibility(android.view.View.GONE);}
                });
                updates.execute(()->{try{
                    fitter=new OfflineFitter(CompanionLibrary.this,SITE);
                    String cached=fitter.cached();
                    if(!cached.isEmpty())runOnUiThread(()->js("fitterUpdate("+JSONObject.quote(cached)+")"));
                    String next=fitter.update(cached);
                    if(next!=null)runOnUiThread(()->js("fitterUpdate("+JSONObject.quote(next)+")"));
                }catch(Exception ignored){/* The packaged fitter stays available. */}});
                if(grant.linked()){
                    io.execute(()->{JSONArray pages=grant.cachedPages();
                        runOnUiThread(()->{if(isDestroyed())return;
                            if(pages!=null){for(int i=0;i<pages.length();i++)js("stagePage("+(i==0)+",JSON.parse("+JSONObject.quote(pages.opt(i).toString())+"))");js("receivedOffline(staged);staged=null");}
                            fetch(false);
                        });});
                }else if(System.currentTimeMillis()-grant.issued()<600000)showCode();
            }
        });
        String bootstrap="<script>init("+plus()+","+grant.linked()+","+color(BG)+","+color(INK)+","+color(PANEL)+","+color(LINE)+",'"+
                (theme(this)==2?"light":"dark")+"',"+firstTab+","+color(BLUE)+","+color(MUTED)+","+color(ACTIVE)+","+standalone()+");</script>";
        io.execute(()->{
            try(InputStream f=getAssets().open("library.html")){
                String html=read(f,2*1024*1024)+bootstrap;
                runOnUiThread(()->{if(!isDestroyed() && !isFinishing())
                    page.loadDataWithBaseURL("https://library.invalid/",html,"text/html","UTF-8",null);});
            }catch(IOException e){runOnUiThread(()->{loading.setVisibility(android.view.View.GONE);toast("Could not open the packaged library.");});}
        });
    }
    @Override protected void onPause(){away=true;super.onPause();}
    @Override protected void onResume(){super.onResume();if(away && ready && (grant.linked() || System.currentTimeMillis()-grant.issued()<600000))fetch(false);away=false;}
    private LinearLayout eqMenu;
    private void drawNavigation(){
        if(eqMenu!=null)((LinearLayout)eqMenu.getParent()).removeView(eqMenu);
        eqMenu=null;
        if(autoEq){
            LinearLayout root=(LinearLayout)navigation.getParent();
            eqMenu=LabStyle.eqMenu(this,root,TUNING,standalone(),this::navigate);
            root.removeView(eqMenu);
            root.addView(eqMenu,root.indexOfChild((android.view.View)page.getParent()));
        }
        navigation.removeAllViews();
        LabStyle.nav(this,navigation,autoEq ? TUNING : LAB,standalone(),d->{
            if(d!=TUNING && d!=LAB && !(standalone() && d==EQ)){navigate(d);return;}
            boolean next=d!=LAB;
            if(next==autoEq)return;
            returnToLibrary=false;
            autoEq=next;
            drawNavigation();
            showTab();
        });
    }
    @Override public void onBackPressed(){
        if(ready){
            page.evaluateJavascript("closeTargetSearch()",closed->{
                if(!isFinishing() && !isDestroyed() && !"true".equals(closed))leaveLibrary();
            });
        }else leaveLibrary();
    }
    private void leaveLibrary(){
        if(returnToLibrary && autoEq){
            returnToLibrary=false;autoEq=false;drawNavigation();showTab();
        }else super.onBackPressed();
    }
    private void showTab(){
        if(ready)js("showTab("+autoEq+")");
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putBoolean("autoEq",autoEq);state.putBoolean("returnToLibrary",returnToLibrary);
        state.putString("fitExport",pendingFitExport);
        super.onSaveInstanceState(state);
    }
    private static String color(int v){return JSONObject.quote(String.format(java.util.Locale.ROOT,"#%06x",v&0xffffff));}
    private static String read(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Collection exceeds the phone transfer limit.");out.write(buf,0,n);}
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private void js(String code){if(!isDestroyed() && !isFinishing())page.evaluateJavascript(code,null);}
    private void message(String msg){js("status("+JSONObject.quote(msg)+")");}
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show();}
    private void showCode(){try{js("pairing("+JSONObject.quote(grant.challenge().substring(0,8).toUpperCase(java.util.Locale.ROOT))+","+grant.issued()+")");}catch(Exception e){message("Create a new approval link.");}}
    private void approval(boolean share){
        try{
            if(System.currentTimeMillis()-grant.issued()>=600000){js("requestEnded('expired')");return;}
            String url=SITE+"/companion-link#key="+grant.challenge()+"&at="+grant.issued()+"&ranks=1";
            startActivity(share?Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url),"Open in your signed-in browser"):
                    new Intent(Intent.ACTION_VIEW,Uri.parse(url)));
        }catch(Exception e){message("Could not open browser approval.");}
    }
    private void fetchPersonalRanks(int request){
        io.execute(()->{
            JSONObject data=null;
            String error=null;
            try{
                String secret=grant.linked()?grant.secret():null;
                if(secret==null)throw new IOException("Link your browser account in Sync to load your ranks.");
                javax.net.ssl.HttpsURLConnection connection=(javax.net.ssl.HttpsURLConnection)new URL(
                        SITE+"/api/companion/library?view=personal-ranks").openConnection();
                try{
                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);
                    connection.setConnectTimeout(8000);
                    connection.setReadTimeout(15000);
                    connection.setRequestProperty("Authorization","Bearer "+secret);
                    int code=connection.getResponseCode();
                    if(code==401 || code==403)throw new IOException("Check your browser link in Sync.");
                    if(code!=200)throw new IOException("Could not read synced ranks. Try again.");
                    try(InputStream input=connection.getInputStream()){
                        data=new JSONObject(read(input,262144));
                    }
                    if(!data.has("personalRankings"))throw new IOException("Synced ranks need the matching website update.");
                    if(!grant.linked() || !secret.equals(grant.secret()))return;
                }finally{connection.disconnect();}
            }catch(IOException failure){error=failure.getMessage();data=null;}
            catch(Exception failure){error="Could not read synced ranks. Try again.";data=null;}
            final String response="syncedRanksReply("+request+","+(data==null?"null":data.toString())+","+JSONObject.quote(error==null?"":error)+")";
            runOnUiThread(()->js(response));
        });
    }
    private void fetch(boolean disconnect){
        if(busy)return;busy=true;message(disconnect?"Disconnecting…":"Reading saved audio settings…");
        io.execute(()->{
            String failure=null;JSONObject data=null;boolean denied=false;boolean cancelled=false;
            try{
                String secret=grant.secret();if(secret==null)throw new IOException("Link your browser account first.");
                String query="";int offset=0,cacheSize=0;JSONArray cachePages=new JSONArray();
                do {
                    javax.net.ssl.HttpsURLConnection conn=(javax.net.ssl.HttpsURLConnection)new URL(SITE+"/api/companion/library"+query).openConnection();
                    try{
                        conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(30000);
                        conn.setUseCaches(false);conn.setRequestProperty("Authorization","Bearer "+secret);
                        if(disconnect)conn.setRequestMethod("DELETE");
                        int code=conn.getResponseCode();denied=code==401 || code==403;
                        if(code==410){
                            try(InputStream in=conn.getErrorStream()){
                                String result=new JSONObject(read(in,2048)).optString("decision");
                                cancelled="cancel".equals(result) || "wrong-code".equals(result);
                            }
                            if(!cancelled)throw new IOException("Unknown approval response.");
                            break;
                        }
                        if(code!=200)throw new IOException(denied?"Account approval is pending, expired or paused. Check Sync in your approved browser.":
                            code==404?"Companion account sync is awaiting the website update.":"Sync unavailable. Try again.");
                        try(InputStream in=conn.getInputStream()){data=new JSONObject(read(in,8*1024*1024));}
                        if(disconnect)break;
                        data.getJSONArray("eqs");data.getJSONArray("measurements");
                        final String payload=data.toString();final boolean first=offset==0;
                        if(cachePages!=null){cacheSize+=payload.length();if(cacheSize<=12*1024*1024)cachePages.put(data);else cachePages=null;}
                        runOnUiThread(()->js("stagePage("+first+",JSON.parse("+JSONObject.quote(payload)+"))"));
                        if(data.isNull("next"))break;
                        int next=data.getInt("next");if(next<=offset)throw new IOException("Invalid sync page.");
                        offset=next;query="?offset="+offset+"&epoch="+Uri.encode(data.getString("epoch"))+"&revision="+data.getLong("revision");
                    }finally{conn.disconnect();}
                } while(!Thread.currentThread().isInterrupted());
                if(!disconnect && !cancelled && data!=null && data.isNull("next")){
                    if(cachePages==null)grant.dropCache();else grant.storePages(cachePages);
                }
            }catch(Exception e){failure=e instanceof IOException?e.getMessage():"The collection response could not be verified.";}
            final String error=failure;final boolean inactive=denied;final boolean requestCancelled=cancelled;
            runOnUiThread(()->{busy=false;if(isDestroyed())return;
                if(requestCancelled){grant.clear();js("requestEnded('cancel')");return;}
                if(error!=null){js("staged=null");if(inactive && grant.linked()){grant.dropCache();js("accessPaused()");}message(error);return;}
                if(disconnect){grant.clear();js("signedOut()");}else{grant.verified();js("received(staged);staged=null");}
            });
        });
    }
    private boolean readingHistory;
    private String saveAfterHistory;
    private void libraryRequest(String payload,int offset){
        if(!grant.linked()){if(payload!=null)message("Link a browser with upload permission to save this entry.");return;}
        if(busy){
            if(payload!=null && readingHistory && saveAfterHistory==null)saveAfterHistory=payload;
            else if(payload!=null)message("A sync operation is in progress. Retry when it finishes.");
            return;
        }
        readingHistory=payload==null;
        busy=true;
        io.execute(()->{
            HttpURLConnection conn=null;
            try{
                boolean save=payload!=null;
                conn=(HttpURLConnection)new URL(SITE+"/api/companion/library"+(save?"":"?history="+offset)).openConnection();
                conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(12000);conn.setReadTimeout(30000);conn.setUseCaches(false);
                conn.setRequestProperty("Authorization","Bearer "+grant.secret());
                if(save){conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");
                    try(OutputStream out=conn.getOutputStream()){out.write(payload.getBytes(StandardCharsets.UTF_8));}}
                int code=conn.getResponseCode();
                if(code!=200){
                    String reason="Could not access My Collection. Refresh Sync and try again.";
                    try(InputStream in=conn.getErrorStream()){reason=new JSONObject(read(in,4096)).optString("error",reason);}catch(Exception ignored){}
                    throw new IOException(reason);
                }
                try(InputStream in=conn.getInputStream()){
                    final String data=new JSONObject(read(in,1024*1024)).toString();
                    runOnUiThread(()->{busy=false;readingHistory=false;if(isDestroyed())return;
                        if(save){js("saveFinished()");fetch(false);}
                        else{
                            js("receivedHistory("+offset+",JSON.parse("+JSONObject.quote(data)+"))");
                            String queued=saveAfterHistory;saveAfterHistory=null;
                            if(queued!=null)libraryRequest(queued,0);
                        }
                    });
                }
            }catch(Exception e){runOnUiThread(()->{busy=false;readingHistory=false;saveAfterHistory=null;message(e instanceof IOException?e.getMessage():"Sync unavailable.");});}
            finally{if(conn!=null)conn.disconnect();}
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==FitExports.PICK_DESTINATION){
            String payload=pendingFitExport;pendingFitExport=null;
            Uri destination=result==RESULT_OK && data!=null?data.getData():null;
            if(payload!=null && destination!=null && "content".equals(destination.getScheme())){
                io.execute(()->{
                    try{
                        String note=new FitExports(payload).save(getContentResolver(),destination);
                        runOnUiThread(()->{if(!isDestroyed())toast(note);});
                    }catch(JSONException invalid){runOnUiThread(()->toast("Could not read the export. Try saving again."));}
                });
            }
            return;
        }
        if(request==TuningActivity.REQUEST){
            if(result==RESULT_OK && data!=null){
                if(data.hasExtra("tuningText"))receive(data.getStringExtra("tuningName"),data.getStringExtra("tuningText"),"");
                else {
                    int dest=data.getIntExtra("nav",LAB);
                    if(dest==LAB || dest==TUNING){
                        returnToLibrary=false;autoEq=dest==TUNING;drawNavigation();showTab();
                    }else navigate(dest);
                }
            }
            return;
        }
        if(request!=35 || selectedFiles==null)return;
        Uri uri=result==RESULT_OK && data!=null?data.getData():null;
        selectedFiles.onReceiveValue(uri!=null && "content".equals(uri.getScheme())?new Uri[]{uri}:null);
        selectedFiles=null;
    }
    private final class Bridge {
        @JavascriptInterface public void openSavedLibrary(){
            runOnUiThread(()->{if(autoEq){returnToLibrary=false;autoEq=false;drawNavigation();showTab();}});
        }
        @JavascriptInterface public void openAutoEq(){
            runOnUiThread(()->{if(!autoEq){returnToLibrary=true;autoEq=true;drawNavigation();showTab();}});
        }
        @JavascriptInterface public void resetFitter(){if(fitter!=null)updates.execute(()->fitter.clear());}
        @JavascriptInterface public boolean onlineForTargets(){return online();}
        @JavascriptInterface public void targetRequest(int request,String key){if(key!=null && key.length()<160)requestTargets(request,key);}
        @JavascriptInterface public void syncedPersonalRanks(int request){fetchPersonalRanks(request);}
        @JavascriptInterface public boolean rememberComparison(String comparison){
            if(comparison==null || comparison.length()>4*1024*1024)return false;
            return getSharedPreferences("local-fit-comparison",MODE_PRIVATE).edit().putString("last",comparison).commit();
        }
        @JavascriptInterface public boolean keepMeasurement(String data){
            if(data==null || data.length()>4*1024*1024)return false;
            try{
                JSONObject value=new JSONObject(data);
                if(value.getJSONArray("points").length()<2)return false;
                String id=OfflineFitter.hash(data);
                android.util.AtomicFile file=new android.util.AtomicFile(new File(getFilesDir(),"curve-"+id));
                FileOutputStream out=file.startWrite();
                try{out.write(data.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
                catch(IOException error){file.failWrite(out);return false;}
                JSONObject meta=new JSONObject().put("id",id).put("type",value.getString("type")).put("name",value.getString("name"));
                return getSharedPreferences("attached-curves",MODE_PRIVATE).edit().putString(id,meta.toString()).commit();
            }catch(Exception error){return false;}
        }
        @JavascriptInterface public String phoneMeasurements(){
            JSONArray rows=new JSONArray();
            for(Object entry:getSharedPreferences("attached-curves",MODE_PRIVATE).getAll().values())
                try{rows.put(new JSONObject((String)entry));}catch(Exception ignored){}
            return rows.toString();
        }
        @JavascriptInterface public String phoneMeasurement(String id){
            if(id==null || !id.matches("[0-9a-f]{64}"))return "";
            try(InputStream in=new android.util.AtomicFile(new File(getFilesDir(),"curve-"+id)).openRead()){
                return read(in,4*1024*1024);
            }catch(IOException error){return "";}
        }
        @JavascriptInterface public String lastComparison(){return getSharedPreferences("local-fit-comparison",MODE_PRIVATE).getString("last","[]");}
        @JavascriptInterface public String editorPreset(){
            JSONObject v=new JSONObject();
            try{for(String key:new String[]{"main","mainName","match","matchName"})v.put(key,getIntent().getStringExtra(key));}catch(JSONException ignored){}
            return v.toString();
        }
        @JavascriptInterface public void history(int offset){if(offset>=0)runOnUiThread(()->libraryRequest(null,offset));}
        @JavascriptInterface public void saveLibrary(String payload){if(payload!=null && payload.length()<=450000)runOnUiThread(()->libraryRequest(payload,0));}
        @JavascriptInterface public void collection(){runOnUiThread(()->openTab(
                new Intent(CompanionLibrary.this,TuningActivity.class).putExtra("plus",plus()).putExtra("page",1),
                TuningActivity.REQUEST));}
        @JavascriptInterface public void browserDevices(){runOnUiThread(()->openTab(
                new Intent(CompanionLibrary.this,TuningActivity.class).putExtra("plus",plus())
                        .putExtra(CompanionLab.OPEN_BROWSER_DEVICES,true),TuningActivity.REQUEST));}
        @JavascriptInterface public boolean fits(String shared,String match){
            if(shared==null || match==null || shared.length()+match.length()>32768)return false;
            try{return validFit(shared,match);}catch(IllegalArgumentException e){return false;}
        }
        @JavascriptInterface public void copyPeq(String preset){
            if(preset==null || preset.isEmpty() || preset.length()>32768)return;
            runOnUiThread(()->{
                if(isFinishing() || isDestroyed())return;
                android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PEQ filters",preset));
                if(android.os.Build.VERSION.SDK_INT<33)toast("PEQ filters copied.");
            });
        }
        private Intent phoneEqIntent(){
            return new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.yinyangeq.android.playback");
        }
        @JavascriptInterface public boolean hasPhoneEq(){
            return phoneEqIntent().resolveActivity(getPackageManager())!=null;
        }
        @JavascriptInterface public void openPhoneEq(String name,String preset){
            if(name==null || name.length()>160 || preset==null || preset.isEmpty() || preset.length()>32768)return;
            runOnUiThread(()->{
                if(isDestroyed() || isFinishing())return;
                Intent handoff=phoneEqIntent()
                        .putExtra(Intent.EXTRA_SUBJECT,name).putExtra(Intent.EXTRA_TEXT,preset);
                try{startActivity(handoff);}
                catch(android.content.ActivityNotFoundException missing){toast("Install YinYang Phone EQ and set up Shizuku to use this fit for phone playback.");}
            });
        }
        @JavascriptInterface public void saveFitFiles(String payload){
            if(payload==null || payload.length()>262144)return;
            runOnUiThread(()->{
                if(pendingFitExport!=null || isDestroyed() || isFinishing())return;
                try{
                    Intent picker=new FitExports(payload).picker();
                    pendingFitExport=payload;
                    startActivityForResult(picker,FitExports.PICK_DESTINATION);
                }catch(JSONException | android.content.ActivityNotFoundException error){
                    pendingFitExport=null;
                    message("Could not open the Android file picker.");
                }
            });
        }
        @JavascriptInterface public void compareGeneratedFits(String payload){
            if(standalone() || payload==null || payload.length()>32768)return;
            runOnUiThread(()->{
                if(isDestroyed() || isFinishing() || binding()==null || !binding().active(CompanionLibrary.this))return;
                try{new FitPair(payload);compareFits(payload);}
                catch(JSONException error){message("Could not open these two fits in Blind A/B.");}
            });
        }
        @JavascriptInterface public void link(){runOnUiThread(()->{if(busy || grant.linked())return;try{grant.create();showCode();}catch(Exception e){message("Android secure credential storage is unavailable.");}});}
        @JavascriptInterface public void cancelRequest(){runOnUiThread(()->{
            if(busy || grant.linked())return;grant.clear();js("requestEnded('cancel')");
        });}
        @JavascriptInterface public void reportIssue(){runOnUiThread(()->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/yinyangeqlab/yinyang-eq-lab/issues/new")));}
            catch(android.content.ActivityNotFoundException e){message("No browser is available to open the issue form.");}
        });}
        @JavascriptInterface public void approvalInApp(){runOnUiThread(()->{
            if(grant.linked() || System.currentTimeMillis()-grant.issued()>=600000){message("Get a new approval code in Sync.");return;}
            openTab(new Intent(CompanionLibrary.this,TuningActivity.class).putExtra("plus",plus())
                    .putExtra(CompanionLab.OPEN_APPROVAL,true),TuningActivity.REQUEST);
        });}
        @JavascriptInterface public void openApproval(){runOnUiThread(()->approval(false));}
        @JavascriptInterface public void shareApproval(){runOnUiThread(()->approval(true));}
        @JavascriptInterface public void refresh(){runOnUiThread(()->fetch(false));}
        @JavascriptInterface public void disconnect(){runOnUiThread(()->new AlertDialog.Builder(CompanionLibrary.this)
            .setTitle("Disconnect account sync?").setPositiveButton("Disconnect",(d,w)->fetch(true)).setNegativeButton("Cancel",null).show());}
        @JavascriptInterface public void draft(String payload){if(standalone() || payload==null || payload.length()>32768)return;runOnUiThread(()->{
            try{JSONObject v=new JSONObject(payload);String title=v.getString("name"),shared=v.getString("shared"),match=v.getString("match");
                if(title.length()>256 || shared.isEmpty() && match.isEmpty())throw new IllegalArgumentException();
                new AlertDialog.Builder(CompanionLibrary.this).setTitle("Load into DSP editor?").setMessage(title)
                    .setPositiveButton("Load draft",(d,w)->receive(title,shared,match)).setNegativeButton("Cancel",null).show();
            }catch(Exception e){message("Invalid EQ draft.");}
        });}
    }
    @Override protected void onDestroy(){ready=false;targetIO.shutdownNow();updates.shutdownNow();io.shutdownNow();page.removeJavascriptInterface("Phone");page.destroy();super.onDestroy();}
}
