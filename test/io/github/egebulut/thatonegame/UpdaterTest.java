package io.github.egebulut.thatonegame;

import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Checks Updater against a local HTTP server that serves a small site the way Vercel does, with both manifests written
 * by the site's real tools/offline_list.py: the shell-only update on start, a game's download and update when opened
 * and from the game list, a game never downloaded while offline, corrupt and interrupted downloads, a new APK, the old
 * updater's files, format 1 for installed old apps, and the old app's updater itself. Run test/run.sh.
 */
public class UpdaterTest {
    static final Map<String, byte[]> served = new ConcurrentHashMap<>(); // URL path -> body
    static final Map<String, Integer> hits = new ConcurrentHashMap<>();
    static volatile String cut; // URL path whose body stops halfway
    static Path site, pub;

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> { // the server thread would keep the JVM running
            e.printStackTrace();
            System.exit(1);
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            hits.merge(p, 1, Integer::sum);
            byte[] body = served.get(p);
            try (OutputStream out = ex.getResponseBody()) {
                if (body == null) {
                    ex.sendResponseHeaders(404, -1);
                } else {
                    ex.sendResponseHeaders(200, body.length);
                    out.write(body, 0, p.equals(cut) ? body.length / 2 : body.length);
                }
            } catch (Exception closedEarly) {
                // the cut download
            }
        });
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        String url = root + "/assets/app-manifest-v2.json", url1 = root + "/assets/app-manifest.json";
        site = Files.createTempDirectory("site");
        pub = site.resolve("public");
        Files.createDirectories(site.resolve("tools"));
        Files.copy(Path.of(System.getProperty("tools")), site.resolve("tools/offline_list.py"));
        File dir = Files.createTempDirectory("updater").toFile();

        // A site with a page, the game list, a shared library, two live games and one unfinished game folder.
        put("index.html", "<body>home</body>");
        put("m/index.html", "<body>games</body>");
        put("vendor/lib.js", "lib v1");
        put("games/a/index.html", "a v1");
        put("games/a/thumb.png", "a thumb");
        put("games/a/data.bin", "a data v1");
        put("io/b/index.html", "b v1");
        put("io/b/thumb.png", "b thumb");
        put("io/b/game.js", "b code v1");
        put("games/wip/index.html", "not finished");
        live("/games/a/", "/io/b/");
        publish();
        String bundled = text(pub.resolve(Updater.MANIFEST).toFile()), bundled1 = text(pub.resolve("assets/app-manifest.json").toFile());

        // Format 1 for the apps already installed: as before, every deployed file but the manifests, nothing new.
        JSONObject f1 = new JSONObject(bundled1);
        check(f1.getInt("format") == 1 && f1.length() == 4 && f1.has("version") && f1.has("generatedAt")
                && f1.getJSONObject("files").similar(expectedFiles()), "format 1: unchanged, every file but the manifests");
        JSONObject m2 = new JSONObject(bundled);
        check(m2.getJSONObject("packs").keySet().equals(Set("games/a/", "io/b/"))
                && m2.getJSONObject("shell").getJSONObject("files").keySet().equals(Set("index.html", "m/index.html", "vendor/lib.js",
                "assets/games.json", "assets/offline.json", "games/a/thumb.png", "io/b/thumb.png")), "format 2: the shell with the thumbnails, one pack per live game");

        Updater u = new Updater(dir, bundled);
        check(u.local("index.html") == null && u.local("games/a/thumb.png") == null, "the APK serves its shell");
        check(gone(u, "games/a/index.html") && gone(u, "io/b/game.js") && gone(u, "games/wip/index.html"), "the APK has no games");
        check("games/a/".equals(u.gate("games/a/index.html")) && u.gate("games/a/data.bin") == null && u.gate("index.html") == null,
                "opening a game never downloaded shows the download screen");
        check(state(u, "games/a/").equals("get") && status(u, "games/a/").getLong("bytes") == 13, "the list marks it with its size");

        check(u.update(url) == -1 && u.start[1] == 0, "no change: nothing to do");

        // First open of a game: only that game downloads.
        hits.clear();
        check(open(u, "games/a/index.html", url) && text(u.local("games/a/index.html")).equals("a v1"), "first open downloads the game, then it opens");
        check(hits.containsKey("/games/a/") && hits.containsKey("/games/a/data.bin") && hits.size() == 2, "only that game's files were downloaded");
        check(u.gate("games/a/index.html") == null && state(u, "games/a/").equals("ready") && state(u, "io/b/").equals("get"), "it is ready, the other game is not");
        check(open(u, "io/b/index.html", url), "a second game downloads when opened");

        // A deploy that changes the shell, both games, and adds a game.
        put("index.html", "<body>home v2</body>");
        put("games/a/index.html", "a v2");
        put("io/b/game.js", "b code v2");
        put("games/c/index.html", "c v1");
        put("games/c/thumb.png", "c thumb");
        live("/games/a/", "/io/b/", "/games/c/");
        publish();
        hits.clear();
        check(u.update(url) == 1, "start: the shell updates, one new game");
        check(text(u.local("index.html")).contains("home v2") && text(u.local("games/c/thumb.png")).equals("c thumb")
                && text(u.local("assets/games.json")).contains("/games/c/"), "the new game's card and thumbnail are there");
        check(!hits.containsKey("/games/a/") && !hits.containsKey("/io/b/game.js") && !hits.containsKey("/games/c/"), "no game file was downloaded on start");
        check(text(u.local("games/a/index.html")).equals("a v1") && text(u.local("io/b/game.js")).equals("b code v1"), "installed games still play as they were");
        check(state(u, "games/a/").equals("update") && status(u, "games/a/").getLong("bytes") == 4 && state(u, "games/c/").equals("get"),
                "the list shows an update for the changed game, a download for the new one");

        // Opening the changed game updates it first.
        check("games/a/".equals(u.gate("games/a/index.html")), "opening an outdated game shows the update screen");
        hits.clear();
        check(open(u, "games/a/index.html", url) && text(u.local("games/a/index.html")).equals("a v2"), "it updates, then opens the new version");
        check(hits.keySet().equals(Set("/games/a/")), "only the changed file of that game was downloaded");
        check(state(u, "io/b/").equals("update") && text(u.local("io/b/game.js")).equals("b code v1"), "the other game is not touched");

        // The update button in the list: TOGApp.games() and TOGApp.install(), without opening the game.
        JSONObject list = new JSONObject(u.games());
        check(list.getJSONObject("io/b/").getString("s").equals("update"), "the list's update button shows on the outdated game");
        hits.clear();
        check(u.begin("io/b/") && state(u, "io/b/").equals("busy") && !u.begin("io/b/"), "tapping it starts one download, shown at once");
        check(u.install("io/b/", url) && new JSONObject(u.games()).getJSONObject("io/b/").getString("s").equals("ready"), "done: the mark goes away");
        check(hits.keySet().equals(Set("/io/b/game.js")) && text(u.local("io/b/game.js")).equals("b code v2"), "the game opens on its new version");

        // A corrupt download, then an interrupted one, change nothing.
        put("games/a/index.html", "a v3");
        put("games/a/data.bin", "a data v3, long enough to stop halfway through");
        publish();
        check(u.update(url) == 0 && state(u, "games/a/").equals("update"), "a new version of the game");
        served.put("/games/a/data.bin", "a data X3, long enough to stop halfway through".getBytes(StandardCharsets.UTF_8));
        check(!open(u, "games/a/index.html", url) && text(u.local("games/a/index.html")).equals("a v2"), "corrupt download: still the old version");
        check(!new File(dir, sha("a data X3, long enough to stop halfway through")).exists() && !new File(dir, "download.tmp").exists(), "corrupt download: rejected");
        publish();
        cut = "/games/a/data.bin";
        hits.clear();
        check(!open(u, "games/a/index.html", url) && text(u.local("games/a/index.html")).equals("a v2") && text(u.local("games/a/data.bin")).equals("a data v1"),
                "interrupted download: the game plays its old version, whole");
        check(status(u, "games/a/").has("error") && !new File(dir, "download.tmp").exists(), "interrupted download: reported, partial file removed");
        cut = null;
        u = new Updater(dir, bundled);
        check(u.update(url) == -1 && open(u, "games/a/index.html", url) && text(u.local("games/a/data.bin")).startsWith("a data v3"), "after a restart it finishes");
        check(!hits.containsKey("/games/a/") && !new File(dir, sha("a v2")).exists() && !new File(dir, sha("a data v1")).exists(),
                "a file that arrived before the cut is not downloaded again; replaced files are deleted");

        // The app as released before (v2.0.0, format 1) against this site: it updates as it always did.
        Updater skipped = new Updater(dir, bundled);
        skipped.cancel();
        put("index.html", "<body>home v4</body>");
        publish();
        check(skipped.update(url) == -1 && new Updater(dir, bundled).inShell("index.html")
                && text(new Updater(dir, bundled).local("index.html")).contains("home v2"), "Skip on start: nothing installed");
        if (System.getProperty("old") != null) {
            File oldDir = Files.createTempDirectory("old").toFile();
            URLClassLoader cl = new URLClassLoader(new URL[] { new File(System.getProperty("old")).toURI().toURL(),
                    new File(System.getProperty("json")).toURI().toURL() }, ClassLoader.getPlatformClassLoader());
            Class<?> c = cl.loadClass("io.github.egebulut.thatonegame.Updater");
            Constructor<?> make = c.getDeclaredConstructor(File.class, String.class);
            Method update = c.getDeclaredMethod("update", String.class), local = c.getDeclaredMethod("local", String.class);
            make.setAccessible(true);
            update.setAccessible(true);
            local.setAccessible(true);
            Object old = make.newInstance(oldDir, bundled1);
            hits.clear();
            int games = (int) update.invoke(old, url1);
            check(games == 3 && text((File) local.invoke(old, "games/a/data.bin")).startsWith("a data v3")
                    && text((File) local.invoke(old, "index.html")).contains("home v4"), "old app: updates every changed file from format 1");
            check(!hits.containsKey("/assets/app-manifest-v2.json") && !hits.containsKey("/games/wip/"), "old app: never fetches the new manifest");
        }
        // A new version of the app itself (AppUpdate): the 6-hour check, the version compare, Later, the sha256 check.
        File ad = Files.createTempDirectory("app").toFile();
        String lu = root + "/assets/app-latest.json", body = "a new apk, release-signed";
        served.put("/apk/new.apk", body.getBytes(StandardCharsets.UTF_8));
        served.put("/assets/app-latest.json", ("{\"versionCode\":15,\"versionName\":\"3.0.2\",\"url\":\"" + root + "/apk/new.apk\",\"sha256\":\""
                + sha(body) + "\",\"size\":" + body.length() + ",\"notes\":\"\"}").getBytes(StandardCharsets.UTF_8));
        AppUpdate au = new AppUpdate(ad);
        long t0 = 1_800_000_000_000L, h = 3600_000L;
        hits.clear();
        check(au.offer(14, t0) == null && au.check(lu, t0) && au.offer(14, t0).getString("versionName").equals("3.0.2"), "new app version: found, offered to an older app");
        check(au.offer(15, t0) == null && au.offer(16, t0) == null, "not offered to the same or a newer app");
        check(!au.check(lu, t0 + 5 * h) && hits.get("/assets/app-latest.json") == 1, "checked at most every 6 hours");
        check(au.check(lu, t0 + 6 * h) && hits.get("/assets/app-latest.json") == 2, "after 6 hours it checks again");
        au.later(15, t0 + 6 * h);
        check(au.offer(14, t0 + 29 * h) == null && au.offer(14, t0 + 30 * h) != null, "Later hides it for 24 hours");
        long[] prog = new long[2];
        File newApk = AppUpdate.download(au.offer(14, t0 + 30 * h), ad, prog);
        check(text(newApk).equals(body) && prog[0] == prog[1] && prog[1] == body.length(), "the new APK downloads and matches its sha256");
        served.put("/apk/new.apk", "a bad apk, same length here".substring(0, body.length()).getBytes(StandardCharsets.UTF_8));
        check(threw(() -> AppUpdate.download(au.offer(14, t0 + 30 * h), ad, new long[2])) && !newApk.exists() && !new File(ad, "update.tmp").exists(),
                "a bad sha256: the APK is deleted, so it can never be installed");
        served.put("/apk/new.apk", body.getBytes(StandardCharsets.UTF_8));
        cut = "/apk/new.apk";
        check(threw(() -> AppUpdate.download(au.offer(14, t0 + 30 * h), ad, new long[2])) && !newApk.exists() && !new File(ad, "update.tmp").exists(),
                "a cut download: deleted");
        cut = null;
        served.put("/assets/app-latest.json", "{\"versionCode\":99}".getBytes(StandardCharsets.UTF_8));
        check(!au.check(lu, t0 + 40 * h) && au.offer(14, t0 + 40 * h).getLong("versionCode") == 15, "a broken app-latest.json is ignored");

        check(u.update(url) == 0 && text(u.local("index.html")).contains("home v4"), "the next start installs the shell");

        // Offline: a game never downloaded cannot open; an outdated game plays its installed copy.
        put("io/b/game.js", "b code v5");
        publish();
        check(u.update(url) == 0 && state(u, "io/b/").equals("update"), "an update for b before going offline");
        server.stop(0);
        long t = System.nanoTime();
        check(new Updater(dir, bundled).update(url) == -1 && System.nanoTime() - t < 4e9, "offline start: skips at once");
        check(!new AppUpdate(Files.createTempDirectory("app2").toFile()).check(root + "/assets/app-latest.json", 0), "offline: no new-version check, nothing saved");
        check(!open(u, "games/c/index.html", url), "offline, never downloaded: the download fails");
        JSONObject c = status(u, "games/c/");
        check(c.getString("s").equals("get") && !c.getBoolean("installed") && c.has("error") && gone(u, "games/c/index.html")
                && "games/c/".equals(u.gate("games/c/index.html")), "it stays on the 'needs the internet' screen, nothing stale served");
        check(!open(u, "io/b/index.html", url) && status(u, "io/b/").getBoolean("installed"), "offline, outdated: the update fails");
        u.skip.add("io/b/"); // TOGApp.playInstalled
        check(u.gate("io/b/index.html") == null && text(u.local("io/b/game.js")).equals("b code v2"), "it opens its installed copy");

        // A new APK keeps the downloaded games; the old updater's files become downloads to reuse.
        Updater apk = new Updater(dir, bundled.replace(m2.getString("version"), "newapk000000"));
        check(apk.local("index.html") == null && text(apk.local("games/a/index.html")).equals("a v3"), "a new APK: its own shell, the games stay");
        File legacy = Files.createTempDirectory("legacy").toFile();
        Files.writeString(new File(legacy, "manifest.json").toPath(), bundled1);
        Files.writeString(new File(legacy, sha("a data v1")).toPath(), "a data v1");
        Files.writeString(new File(legacy, sha("gone")).toPath(), "gone");
        Updater moved = new Updater(legacy, bundled);
        check(moved.update(url) == -1 && !new File(legacy, "manifest.json").exists() && !new File(legacy, sha("gone")).exists()
                && new File(legacy, sha("a data v1")).exists(), "the old updater's files: its manifest goes, files a game still uses stay");

        String evil = bundled.replace("\"games/a/data.bin\"", "\"io/b/../../x\"");
        check(threw(() -> Updater.check(new JSONObject(evil))), "a manifest with a path outside its game is refused");
        System.out.println("all checks passed");
    }

    /** Opens a game page as the app does: the download screen calls TOGApp.install when the game must download first. */
    static boolean open(Updater u, String page, String url) {
        String k = u.gate(page);
        return k == null || u.begin(k) && u.install(k, url);
    }

    static String state(Updater u, String k) {
        return status(u, k).optString("s");
    }

    static JSONObject status(Updater u, String k) {
        return u.status(k);
    }

    static java.util.Set<String> Set(String... s) {
        return new java.util.TreeSet<>(java.util.Arrays.asList(s));
    }

    static void put(String rel, String text) throws Exception {
        Path p = pub.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    static void live(String... paths) throws Exception {
        StringBuilder s = new StringBuilder("{\"games\":[");
        for (String p : paths) s.append(s.charAt(s.length() - 1) == '[' ? "" : ",").append("{\"path\":\"").append(p).append("\",\"status\":\"live\"}");
        put("assets/games.json", s.append(",{\"path\":\"/games/wip/\",\"status\":\"soon\"}]}").toString());
    }

    /** Runs the site's tools/offline_list.py, then serves every file at its clean URL, as Vercel does. */
    static void publish() throws Exception {
        Process p = new ProcessBuilder("python3", "-I", site.resolve("tools/offline_list.py").toString()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException(out);
        served.clear();
        try (Stream<Path> all = Files.walk(pub)) {
            for (Path f : (Iterable<Path>) all.filter(Files::isRegularFile)::iterator) {
                served.put("/" + Updater.web(pub.relativize(f).toString()), Files.readAllBytes(f));
            }
        }
    }

    static JSONObject expectedFiles() throws Exception {
        Map<String, Object> m = new TreeMap<>();
        try (Stream<Path> all = Files.walk(pub)) {
            for (Path f : (Iterable<Path>) all.filter(Files::isRegularFile)::iterator) {
                String rel = pub.relativize(f).toString();
                if (rel.equals("assets/app-manifest.json") || rel.equals(Updater.MANIFEST)) continue;
                byte[] b = Files.readAllBytes(f);
                m.put(rel, new JSONObject().put("sha256", sha(new String(b, StandardCharsets.UTF_8))).put("size", b.length));
            }
        }
        return new JSONObject(m);
    }

    static String sha(String text) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static String text(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static boolean gone(Updater u, String path) {
        try {
            u.local(path);
            return false;
        } catch (FileNotFoundException expected) {
            return true;
        }
    }

    interface Step {
        void run() throws Exception;
    }

    static boolean threw(Step s) {
        try {
            s.run();
            return false;
        } catch (Exception expected) {
            return true;
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAILED: " + what);
            System.exit(1); // the server thread would keep the JVM running
        }
        System.out.println("ok: " + what);
    }
}
