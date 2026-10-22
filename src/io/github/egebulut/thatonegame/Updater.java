package io.github.egebulut.thatonegame;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the packed site up to date without a new APK. The site publishes assets/app-manifest.json with every file's
 * sha256 and size (the site's tools/offline_list.py writes it). Files whose sha256 differs from the APK's copy are
 * downloaded into dir, each named by its sha256, and served in place of the packed ones. dir/manifest.json, the
 * installed manifest, is replaced only after every file of an update arrived and matched, so a failed, corrupt or
 * interrupted update leaves the app on the version it had.
 */
final class Updater {
    static final String MANIFEST = "assets/app-manifest.json";
    static final Pattern GAME = Pattern.compile("(games|io)/[^/]+/");

    final File dir;
    final JSONObject bundled; // the files of the manifest packed in the APK
    final String base; // its version
    volatile JSONObject files; // the files the app serves now: the installed manifest's, else the bundled ones
    String version;
    volatile long done, total; // bytes downloaded and to download, for the progress bar
    volatile boolean cancelled;

    Updater(File dir, String bundledManifest) throws JSONException {
        this.dir = dir;
        JSONObject b = new JSONObject(bundledManifest), now = b;
        try {
            JSONObject m = new JSONObject(read(new FileInputStream(new File(dir, "manifest.json")), 10 << 20));
            // An installed update counts only on top of the site this APK packs: a new APK starts from its own copy.
            if (b.getString("version").equals(m.getString("base"))) now = m;
        } catch (Exception none) {
            // no update installed yet
        }
        bundled = b.getJSONObject("files");
        base = b.getString("version");
        files = now.getJSONObject("files");
        version = now.getString("version");
    }

    /** The downloaded copy to serve for a site path, or null when the APK's packed copy is current. */
    File local(String path) throws FileNotFoundException {
        String sha = sha(files, path);
        if (sha == null) throw new FileNotFoundException(path); // not (or no longer) part of the site
        return sha.equals(sha(bundled, path)) ? null : new File(dir, sha);
    }

    static String sha(JSONObject files, String path) {
        JSONObject f = files.optJSONObject(path);
        return f == null ? null : f.optString("sha256");
    }

    /**
     * Fetches the site's manifest and installs what changed. Returns how many games changed, or -1 when nothing was
     * installed: offline, no change, skipped, or any error. The app then plays what it has.
     */
    int update(String manifestUrl) {
        try {
            JSONObject remote = new JSONObject(read(open(manifestUrl, 3000), 10 << 20));
            if (remote.getInt("format") != 1 || remote.getString("version").equals(version)) return -1;
            JSONObject want = remote.getJSONObject("files");
            Map<String, String> get = new LinkedHashMap<>(); // sha256 -> a path to download it from
            Set<String> keep = new HashSet<>(), games = new HashSet<>();
            long bytes = 0;
            for (Iterator<String> it = want.keys(); it.hasNext(); ) {
                String p = it.next(), sha = want.getJSONObject(p).getString("sha256");
                if (!sha.matches("[0-9a-f]{64}")) throw new IOException("bad sha256 for " + p);
                // Only plain site paths: never a parent folder, an absolute path or the admin pages.
                if (p.contains("..") || p.startsWith("/") || p.startsWith("admin/") || p.contains("\\")) throw new IOException("bad path " + p);
                keep.add(sha);
                Matcher game = GAME.matcher(p);
                if (!sha.equals(sha(files, p)) && game.lookingAt()) games.add(game.group());
                if (!sha.equals(sha(bundled, p)) && !new File(dir, sha).exists() && !get.containsKey(sha)) {
                    get.put(sha, p);
                    bytes += want.getJSONObject(p).getLong("size");
                }
            }
            total = bytes;
            dir.mkdirs();
            for (Map.Entry<String, String> e : get.entrySet()) {
                String p = e.getValue();
                fetch(URI.create(manifestUrl).resolve(new URI(null, null, "/" + web(p), null)).toString(),
                        e.getKey(), want.getJSONObject(p).getLong("size"));
            }
            remote.put("base", base);
            File installed = new File(dir, "manifest.json"), tmp = new File(dir, "manifest.tmp");
            write(tmp, remote.toString());
            synchronized (this) {
                if (cancelled) return -1;
                if (!tmp.renameTo(installed)) throw new IOException("could not install " + installed);
                files = want;
                version = remote.getString("version");
            }
            for (File f : dir.listFiles()) {
                if (!keep.contains(f.getName()) && !f.equals(installed)) f.delete(); // replaced and removed files
            }
            return games.size();
        } catch (Exception e) {
            new File(dir, "download.tmp").delete(); // what a broken download left
            return -1;
        }
    }

    /** Skip: stops the download, and an update that has not been installed yet is not installed. */
    synchronized void cancel() {
        cancelled = true;
    }

    /** Downloads one file and keeps it as dir/sha only when its sha256 matches the manifest. */
    void fetch(String url, String sha, long size) throws Exception {
        File tmp = new File(dir, "download.tmp");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long n = 0;
        try (InputStream in = open(url, 15000); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536];
            for (int r; (r = in.read(b)) > 0; n += r, done += r) {
                if (cancelled || n + r > size) throw new IOException("skipped, or larger than the manifest says: " + url);
                md.update(b, 0, r);
                out.write(b, 0, r);
            }
            out.getFD().sync();
        }
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) hex.append(String.format("%02x", x));
        if (!hex.toString().equals(sha)) {
            tmp.delete();
            throw new IOException(url + " does not match the manifest");
        }
        if (!tmp.renameTo(new File(dir, sha))) throw new IOException("could not keep " + url);
    }

    static InputStream open(String url, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        // A page like /404/ may come with an error status: its sha256 decides whether it is the right file.
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) throw new IOException(url + ": HTTP " + c.getResponseCode());
        return in;
    }

    /** The URL path the site serves a file at (vercel.json: cleanUrls, trailingSlash), as in tools/offline_list.py. */
    static String web(String path) {
        if (path.equals("index.html") || path.endsWith("/index.html")) return path.substring(0, path.length() - 10);
        return path.endsWith(".html") ? path.substring(0, path.length() - 5) + "/" : path;
    }

    static String read(InputStream in, int max) throws IOException {
        try (InputStream i = in; ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] b = new byte[65536];
            for (int n; (n = i.read(b)) > 0; buf.write(b, 0, n)) {
                if (buf.size() > max) throw new IOException("too large");
            }
            return buf.toString("UTF-8");
        }
    }

    static void write(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
            out.getFD().sync();
        }
    }
}
