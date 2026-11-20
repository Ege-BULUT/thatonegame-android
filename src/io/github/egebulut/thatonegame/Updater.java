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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the app up to date without a new APK. The site publishes assets/app-manifest-v2.json (its tools/offline_list.py
 * writes it): the shell (pages, assets, the game list and thumbnails, shared libraries) and one pack per game, each file
 * with its sha256 and size, each part with its own hash.
 *
 * The APK packs only the shell. On start, update() brings the shell up to date and records the newest manifest. A game's
 * pack is downloaded by install(), only when that game is opened or its update button is tapped, so a game nobody plays
 * never costs a download. Files are kept in dir named by their sha256; dir/state.json records the newest manifest and the
 * installed packs, and changes only after every file of a step arrived and matched, so a failed, corrupt or interrupted
 * download leaves the app as it was.
 */
final class Updater {
    static final String MANIFEST = "assets/app-manifest-v2.json";
    static final Pattern GAME = Pattern.compile("(games|io)/[^/]+/");

    final File dir;
    final JSONObject bundled; // the shell files packed in the APK
    final String base; // the APK's manifest version
    volatile JSONObject latest; // the newest manifest known: the last one fetched, else the APK's
    volatile JSONObject shell; // its shell files, which the app serves
    JSONObject packs; // installed games: pack -> {"hash", "files"}; guarded by this
    final Set<String> skip = ConcurrentHashMap.newKeySet(); // games played as installed after a failed update
    final Map<String, long[]> busy = new ConcurrentHashMap<>(); // pack -> {bytes done, bytes to download}
    final Map<String, String> failed = new ConcurrentHashMap<>(); // pack -> why its last download failed
    final long[] start = new long[2]; // the start update's progress
    final Object lock = new Object(); // one download at a time, so clean-up never removes a file still being used
    volatile boolean cancelled; // Skip on the start screen

    Updater(File dir, String bundledManifest) throws JSONException {
        this.dir = dir;
        JSONObject b = new JSONObject(bundledManifest), s = null;
        try {
            s = new JSONObject(read(new FileInputStream(new File(dir, "state.json")), 50 << 20));
        } catch (Exception none) {
            // nothing installed yet (or the old updater's files, which clean-up turns into reusable downloads)
        }
        bundled = b.getJSONObject("shell").getJSONObject("files");
        base = b.getString("version");
        // A new APK starts from its own shell, and keeps the games already downloaded.
        latest = s != null && base.equals(s.optString("base")) ? s.getJSONObject("latest") : b;
        shell = latest.getJSONObject("shell").getJSONObject("files");
        packs = s != null ? s.getJSONObject("packs") : new JSONObject();
    }

    /**
     * The downloaded copy to serve for a site path, or null when the APK's packed copy is current. A game's files come
     * only from its installed pack: never from the APK or an older download.
     */
    File local(String path) throws FileNotFoundException {
        String sha = sha(shell, path);
        if (sha != null) return sha.equals(sha(bundled, path)) ? null : new File(dir, sha);
        Matcher m = GAME.matcher(path);
        JSONObject p;
        synchronized (this) {
            p = m.lookingAt() ? packs.optJSONObject(m.group()) : null;
        }
        sha = p == null ? null : sha(p.optJSONObject("files"), path);
        if (sha == null) throw new FileNotFoundException(path); // not part of the site, or the game is not installed
        return new File(dir, sha);
    }

    boolean inShell(String path) {
        return sha(shell, path) != null;
    }

    /**
     * The game a page belongs to when that game must download or update before it opens, else null. Opening the
     * page then shows the app's download screen (assets/tog/gate.html) instead.
     */
    String gate(String path) {
        Matcher m = GAME.matcher(path);
        if (!path.endsWith(".html") || !m.lookingAt() || inShell(path)) return null;
        String k = m.group();
        JSONObject want = latest.optJSONObject("packs").optJSONObject(k);
        if (want == null || !want.optJSONObject("files").has(path)) return null;
        String s = status(k).optString("s");
        return s.equals("get") || s.equals("busy") || s.equals("update") && !skip.contains(k) ? k : null;
    }

    /**
     * A game's state for the page: s is "get" (never downloaded), "update", "busy" (downloading) or "ready"; bytes is
     * what a download or update fetches; done and total show a running download; error says why the last one failed.
     */
    synchronized JSONObject status(String k) {
        JSONObject want = latest.optJSONObject("packs").optJSONObject(k), have = packs.optJSONObject(k), o = new JSONObject();
        try {
            long[] b = busy.get(k);
            o.put("installed", have != null);
            if (b != null) o.put("s", "busy").put("done", b[0]).put("total", b[1]);
            else if (want == null || have != null && want.optString("hash").equals(have.optString("hash"))) o.put("s", "ready");
            else o.put("s", have == null ? "get" : "update");
            if (want != null) o.put("bytes", have == null ? want.optLong("bytes") : changed(want, have));
            if (failed.containsKey(k)) o.put("error", failed.get(k));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o;
    }

    /** Every game of the newest manifest with its status, for the game list (TOGApp.games). */
    String games() {
        JSONObject o = new JSONObject();
        for (Iterator<String> it = latest.optJSONObject("packs").keys(); it.hasNext(); ) {
            String k = it.next();
            try {
                o.put(k, status(k));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        return o.toString();
    }

    /** The bytes of want's files that differ from the installed copy. */
    static long changed(JSONObject want, JSONObject have) {
        JSONObject w = want.optJSONObject("files"), h = have.optJSONObject("files");
        long n = 0;
        for (Iterator<String> it = w.keys(); it.hasNext(); ) {
            String p = it.next();
            if (!w.optJSONObject(p).optString("sha256").equals(sha(h, p))) n += w.optJSONObject(p).optLong("size");
        }
        return n;
    }

    static String sha(JSONObject files, String path) {
        JSONObject f = files == null ? null : files.optJSONObject(path);
        return f == null ? null : f.optString("sha256");
    }

    /**
     * On start: fetches the site's manifest and brings the shell up to date. Game packs are not touched; the games
     * whose packs changed show an update button. Returns how many new games the site added, or -1 when nothing was
     * installed: offline, no change, skipped, or any error. The app then plays what it has.
     */
    int update(String manifestUrl) {
        synchronized (lock) {
            try {
                cleanup();
                JSONObject remote = new JSONObject(read(open(manifestUrl, 3000), 20 << 20));
                if (remote.getInt("format") != 2 || remote.getString("version").equals(latest.getString("version"))) return -1;
                check(remote);
                fetchAll(remote.getJSONObject("shell").getJSONObject("files"), manifestUrl, start);
                int added = 0;
                synchronized (this) {
                    if (cancelled) return -1;
                    JSONObject now = remote.getJSONObject("packs"), before = latest.getJSONObject("packs");
                    for (Iterator<String> it = now.keys(); it.hasNext(); ) if (!before.has(it.next())) added++;
                    JSONObject kept = new JSONObject();
                    for (Iterator<String> it = packs.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (now.has(k)) kept.put(k, packs.get(k)); // a game the site removed goes with its files
                    }
                    save(remote, kept);
                    latest = remote;
                    shell = remote.getJSONObject("shell").getJSONObject("files");
                    packs = kept;
                }
                cleanup();
                return added;
            } catch (Exception e) {
                new File(dir, "download.tmp").delete(); // what a broken download left
                return -1;
            }
        }
    }

    /** Marks a game as downloading, so the page shows it at once. False when it is already, or is not a game. */
    synchronized boolean begin(String k) {
        if (k == null || latest.optJSONObject("packs").optJSONObject(k) == null || busy.containsKey(k)) return false;
        failed.remove(k);
        skip.remove(k);
        busy.put(k, new long[2]);
        return true;
    }

    /**
     * Downloads a game's pack from the newest manifest (after begin) and installs it once every file arrived and
     * matched. Files that already arrived, for this game or another, are not downloaded again. True when installed.
     */
    boolean install(String k, String manifestUrl) {
        long[] prog = busy.get(k);
        if (prog == null) return false;
        synchronized (lock) {
            try {
                JSONObject want = latest.getJSONObject("packs").getJSONObject(k);
                fetchAll(want.getJSONObject("files"), manifestUrl, prog);
                synchronized (this) {
                    JSONObject now = new JSONObject(packs.toString());
                    now.put(k, new JSONObject().put("hash", want.getString("hash")).put("files", want.getJSONObject("files")));
                    save(latest, now);
                    packs = now;
                }
                cleanup();
                return true;
            } catch (Exception e) {
                new File(dir, "download.tmp").delete();
                failed.put(k, String.valueOf(e.getMessage()));
                return false;
            } finally {
                busy.remove(k);
            }
        }
    }

    /** Skip: stops the start download, and a shell update that has not been installed yet is not installed. */
    synchronized void cancel() {
        cancelled = true;
    }

    /** Refuses a manifest with a bad sha256, a path outside the site or the admin pages, or a pack with others' files. */
    static void check(JSONObject m) throws Exception {
        JSONObject packs = m.getJSONObject("packs");
        checkFiles(m.getJSONObject("shell").getJSONObject("files"), "");
        for (Iterator<String> it = packs.keys(); it.hasNext(); ) {
            String k = it.next();
            if (!GAME.matcher(k).matches()) throw new IOException("bad pack " + k);
            checkFiles(packs.getJSONObject(k).getJSONObject("files"), k);
            packs.getJSONObject(k).getString("hash");
        }
    }

    static void checkFiles(JSONObject files, String prefix) throws Exception {
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            String p = it.next();
            if (!files.getJSONObject(p).getString("sha256").matches("[0-9a-f]{64}")) throw new IOException("bad sha256 for " + p);
            files.getJSONObject(p).getLong("size");
            if (!p.startsWith(prefix) || p.contains("..") || p.startsWith("/") || p.startsWith("admin/") || p.contains("\\")) {
                throw new IOException("bad path " + p);
            }
        }
    }

    /** Downloads every file the app has neither packed nor downloaded yet, each checked against its sha256. */
    void fetchAll(JSONObject files, String manifestUrl, long[] prog) throws Exception {
        Map<String, String> get = new LinkedHashMap<>(); // sha256 -> a path to download it from
        long bytes = 0;
        for (Iterator<String> it = files.keys(); it.hasNext(); ) {
            String p = it.next(), sha = files.getJSONObject(p).getString("sha256");
            if (!sha.equals(sha(bundled, p)) && !new File(dir, sha).exists() && !get.containsKey(sha)) {
                get.put(sha, p);
                bytes += files.getJSONObject(p).getLong("size");
            }
        }
        prog[1] = bytes;
        dir.mkdirs();
        for (Map.Entry<String, String> e : get.entrySet()) {
            String p = e.getValue();
            fetch(URI.create(manifestUrl).resolve(new URI(null, null, "/" + web(p), null)).toString(),
                    e.getKey(), files.getJSONObject(p).getLong("size"), prog);
        }
    }

    /** Downloads one file and keeps it as dir/sha only when its sha256 matches the manifest. */
    void fetch(String url, String sha, long size, long[] prog) throws Exception {
        File tmp = new File(dir, "download.tmp");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long n = 0;
        try (InputStream in = open(url, 15000); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[65536];
            for (int r; (r = in.read(b)) > 0; n += r, prog[0] += r) {
                if (prog == start && cancelled || n + r > size) throw new IOException("skipped, or larger than the manifest says: " + url);
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

    /** Writes dir/state.json in one step: the old one stays until the new one is complete. */
    void save(JSONObject newest, JSONObject installed) throws Exception {
        dir.mkdirs();
        File tmp = new File(dir, "state.tmp");
        write(tmp, new JSONObject().put("base", base).put("latest", newest).put("packs", installed).toString());
        if (!tmp.renameTo(new File(dir, "state.json"))) throw new IOException("could not save " + dir + "/state.json");
    }

    /**
     * Deletes every file nothing needs: not the newest shell or any game of the newest manifest (a download that was cut
     * off resumes from them), nor an installed game. Runs only under lock, so no download is running.
     */
    void cleanup() {
        File[] all = dir.listFiles();
        if (all == null) return;
        Set<String> keep = new HashSet<>();
        keep.add("state.json");
        synchronized (this) {
            shas(latest.optJSONObject("shell"), keep);
            JSONObject p = latest.optJSONObject("packs");
            for (Iterator<String> it = p.keys(); it.hasNext(); ) shas(p.optJSONObject(it.next()), keep);
            for (Iterator<String> it = packs.keys(); it.hasNext(); ) shas(packs.optJSONObject(it.next()), keep);
        }
        for (File f : all) {
            if (!keep.contains(f.getName())) f.delete(); // replaced files, the old updater's manifest, leftovers
        }
    }

    static void shas(JSONObject part, Set<String> into) {
        JSONObject files = part == null ? null : part.optJSONObject("files");
        if (files == null) return;
        for (Iterator<String> it = files.keys(); it.hasNext(); ) into.add(sha(files, it.next()));
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
