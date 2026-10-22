package io.github.egebulut.thatonegame;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays the ThatOneGame mobile site offline. The whole site is packed into assets/site and served
 * from a fake https origin, so absolute paths, ES modules and localStorage work as on the web.
 * On start it fetches the files the live site changed since (see Updater) and serves those instead.
 * Game saves live in that origin's localStorage; the app mirrors it to a file in its own storage and
 * puts it back if the WebView's data is ever cleared (see backup and restore).
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/m/";
    static final String SITE = "https://thatonegame.vercel.app/";
    static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("html", "text/html");
        TYPES.put("js", "text/javascript");
        TYPES.put("mjs", "text/javascript");
        TYPES.put("css", "text/css");
        TYPES.put("json", "application/json");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("webp", "image/webp");
        TYPES.put("gif", "image/gif");
        TYPES.put("glb", "model/gltf-binary");
        TYPES.put("gltf", "model/gltf+json");
        TYPES.put("bin", "application/octet-stream");
        TYPES.put("wav", "audio/wav");
        TYPES.put("mp3", "audio/mpeg");
        TYPES.put("ogg", "audio/ogg");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("txt", "text/plain");
    }

    static final int PICK = 1, CREATE = 2;
    static final long BACKUP_EVERY = 3 * 60 * 1000;
    /** The page's whole localStorage as one JSON object string. */
    static final String DUMP = "(function(){var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
            + "o[k]=localStorage.getItem(k)}return JSON.stringify(o)})()";

    static Updater updater; // one per app process
    static boolean updateChecked; // at most one update check per launch

    WebView web;
    boolean playing;
    ValueCallback<Uri[]> picked; // the page's open file chooser
    String pendingSave; // an export waiting for the save-as screen (before Android 10)
    String lastBackup;
    /** True once the backup was compared with localStorage: before that it must not be overwritten. */
    boolean checked;
    final Handler timer = new Handler(Looper.getMainLooper());
    final Runnable tick = new Runnable() {
        @Override
        public void run() {
            backup();
            timer.postDelayed(this, BACKUP_EVERY);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xff10172a); // the site's --page, so opening the games does not flash black
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "TOGApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                return HOST.equals(u.getHost()) ? serve(u.getPath()) : null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (HOST.equals(req.getUrl().getHost())) return false;
                // Links that leave the app (the GitHub release page) open in the browser.
                startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!checked && HOST.equals(Uri.parse(url).getHost())) restore();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            /** "Import file" on the Saves page: any file type, since a .json save often arrives as text or octet-stream. */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (picked != null) picked.onReceiveValue(null);
                picked = cb;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try {
                    startActivityForResult(pick, PICK);
                    return true;
                } catch (ActivityNotFoundException e) {
                    picked = null;
                    return false;
                }
            }
        });
        if (updater == null) {
            try {
                updater = new Updater(new File(getFilesDir(), "update"),
                        Updater.read(getAssets().open("site/" + Updater.MANIFEST), 10 << 20));
            } catch (Exception e) {
                throw new IllegalStateException("the APK has no site manifest: build it with build.sh", e);
            }
        }
        if (state != null) web.restoreState(state);
        if (state != null || updateChecked) play();
        else checkForUpdates();
    }

    /** Shows the games, and loads the start page unless the WebView already holds one. */
    void play() {
        if (playing || isFinishing() || isDestroyed()) return;
        playing = true;
        setContentView(web);
        immersive();
        if (web.getUrl() == null) web.loadUrl(START);
    }

    /** The short start screen: checks the site for changed files and downloads them, then opens the games. */
    void checkForUpdates() {
        updateChecked = true;
        String url = SITE + Updater.MANIFEST, test = getIntent().getStringExtra("manifest");
        // Only a test build (DEBUG=1 ./build.sh) takes another manifest URL, such as a local server (see README).
        if (test != null && (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) url = test;
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xff10172a); // the site's colours: --page, --panel, --accent
        ImageView logo = new ImageView(this);
        logo.setImageResource(getApplicationInfo().icon);
        TextView text = new TextView(this);
        text.setText("Checking for updates");
        text.setTextColor(0xfffffdf6);
        text.setTextSize(16);
        text.setPadding(0, (int) (16 * dp), 0, (int) (12 * dp));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(0xffff8a00));
        bar.setIndeterminateTintList(ColorStateList.valueOf(0xffff8a00));
        Button skip = new Button(this);
        skip.setText("Skip");
        skip.setTextColor(0xffffc23d);
        skip.setBackgroundColor(0);
        skip.setVisibility(View.INVISIBLE);
        skip.setOnClickListener(v -> {
            updater.cancel();
            play();
        });
        int wrap = LinearLayout.LayoutParams.WRAP_CONTENT;
        box.addView(logo, new LinearLayout.LayoutParams((int) (96 * dp), (int) (96 * dp)));
        box.addView(text, new LinearLayout.LayoutParams(wrap, wrap));
        box.addView(bar, new LinearLayout.LayoutParams((int) (240 * dp), (int) (12 * dp)));
        box.addView(skip, new LinearLayout.LayoutParams(wrap, wrap));
        setContentView(box);
        immersive();
        String from = url;
        new Thread(() -> {
            int games = updater.update(from);
            runOnUiThread(() -> {
                if (games > 0 && !isFinishing()) toast("Updated: " + games + (games == 1 ? " game" : " games"));
                play();
            });
        }).start();
        long start = SystemClock.uptimeMillis();
        timer.post(new Runnable() {
            @Override
            public void run() {
                if (playing || isFinishing() || isDestroyed()) return;
                long total = updater.total;
                if (total > 0) {
                    text.setText("Updating");
                    bar.setIndeterminate(false);
                    bar.setProgress((int) (updater.done * 1000 / total));
                }
                if (SystemClock.uptimeMillis() - start > 3000) skip.setVisibility(View.VISIBLE);
                timer.postDelayed(this, 100);
            }
        });
    }

    /** Maps a URL path to a packed file the way the web host does: "/x/" serves x/index.html, "/x" serves x.html. */
    WebResourceResponse serve(String path) {
        if (path == null || path.isEmpty()) path = "/";
        String[] tries = path.endsWith("/")
                ? new String[] { path + "index.html", path.substring(0, path.length() - 1) + ".html" }
                : new String[] { path, path + ".html", path + "/index.html" };
        for (String p : tries) {
            try {
                InputStream in = open(p);
                String ext = p.substring(p.lastIndexOf('.') + 1).toLowerCase();
                String type = TYPES.containsKey(ext) ? TYPES.get(ext) : "application/octet-stream";
                return new WebResourceResponse(type, type.startsWith("text/") || type.endsWith("json") ? "utf-8" : null, in);
            } catch (IOException missing) {
                // try the next form of the path
            }
        }
        try {
            return new WebResourceResponse("text/html", "utf-8", 404, "Not Found", null, open("/404.html"));
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
        }
    }

    /** A site file: the updated copy when there is one, else the one packed in the APK. */
    InputStream open(String path) throws IOException {
        File f = updater.local(path.substring(1));
        return f != null ? new FileInputStream(f) : getAssets().open("site" + path);
    }

    /** Called from the site (window.TOGApp). */
    class Bridge {
        /** Lock landscape while a landscape game runs, free rotation elsewhere. */
        @JavascriptInterface
        public void orient(String mode) {
            runOnUiThread(() -> setRequestedOrientation("landscape".equals(mode)
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_FULL_USER));
        }

        /**
         * Saves an exported save file (assets/saves.js) for the player: into Downloads on Android 10 and newer
         * (MediaStore, no permission needed), through Android's save-as screen before that. Returns "downloads",
         * "picker", or "" when it could not be saved.
         */
        @JavascriptInterface
        public String saveFile(String name, String text) {
            if (name == null || text == null || !name.matches("[\\w.-]{1,100}\\.json") || text.length() > 10_000_000) return "";
            if (Build.VERSION.SDK_INT < 29) {
                runOnUiThread(() -> {
                    pendingSave = text;
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), CREATE);
                    } catch (ActivityNotFoundException e) {
                        pendingSave = null;
                        toast("Could not save the file. Use Copy text instead.");
                    }
                });
                return "picker";
            }
            // A second try with the time in the name, for when Downloads still lists a deleted file of that name.
            for (String n : new String[] { name, name.replace(".json", "-" + System.currentTimeMillis() / 1000 + ".json") }) {
                if (download(n, text)) {
                    toast("Saved to Downloads: " + n);
                    return "downloads";
                }
            }
            toast("Could not save the file. Use Copy text instead.");
            return "";
        }
    }

    /** Writes a file into the shared Downloads folder through MediaStore (Android 10+, no permission needed). */
    boolean download(String name, String text) {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            try (OutputStream out = cr.openOutputStream(uri)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return true;
        } catch (Exception e) {
            if (uri != null) cr.delete(uri, null, null);
            return false;
        }
    }

    void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    File backupFile() {
        return new File(getFilesDir(), "saves-backup.json");
    }

    /** Copies the site's localStorage (every game's save and the site settings) to a file in the app's own storage. */
    void backup() {
        String url = web.getUrl();
        if (!checked || url == null || !HOST.equals(Uri.parse(url).getHost())) return;
        web.evaluateJavascript(DUMP, result -> {
            try {
                Object dump = new JSONTokener(result).nextValue();
                if (!(dump instanceof String) || dump.equals(lastBackup)) return; // storage blocked, page gone, or no change
                new JSONObject((String) dump); // only a whole JSON object replaces the last good copy
                File f = backupFile();
                File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(((String) dump).getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if (!tmp.renameTo(f)) throw new IOException("could not replace " + f);
                lastBackup = (String) dump;
            } catch (Exception e) {
                // the previous backup stays as it was
            }
        });
    }

    /**
     * Once per start, on the first page: when localStorage holds none of the backup's keys (an update or "clear data"
     * wiped the WebView's storage), the backup is written back. When any of them is there, nothing is overwritten.
     */
    void restore() {
        String json;
        try (FileInputStream in = new FileInputStream(backupFile()); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            json = new JSONObject(buf.toString("UTF-8")).toString(); // checked and re-escaped before it goes into the page
        } catch (Exception none) {
            checked = true; // no backup yet, or a damaged one the next backup replaces
            return;
        }
        web.evaluateJavascript("(function(b){try{var k=Object.keys(b),i;"
                + "for(i=0;i<k.length;i++)if(localStorage.getItem(k[i])!==null)return 'kept';"
                + "for(i=0;i<k.length;i++)localStorage.setItem(k[i],b[k[i]]);return k.length?'restored':'kept'}"
                + "catch(e){return 'error'}})(" + json + ")", result -> {
            if ("\"restored\"".equals(result)) {
                toast("Your game saves were restored from the app's backup.");
                web.reload();
            }
            if ("\"restored\"".equals(result) || "\"kept\"".equals(result)) checked = true;
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        Uri uri = res == RESULT_OK && data != null ? data.getData() : null;
        if (req == PICK && picked != null) {
            picked.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            picked = null;
        } else if (req == CREATE && pendingSave != null) {
            String text = pendingSave;
            pendingSave = null;
            if (uri == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                toast("Save file saved.");
            } catch (Exception e) {
                toast("Could not save the file. Use Copy text instead.");
            }
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    @SuppressWarnings("deprecation")
    void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }

    @Override
    protected void onPause() {
        backup();
        timer.removeCallbacks(tick);
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        timer.postDelayed(tick, BACKUP_EVERY);
    }

    @Override
    protected void onDestroy() {
        if (!playing) updater.cancel(); // closed during the update: stop it, the next app start tries again
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
