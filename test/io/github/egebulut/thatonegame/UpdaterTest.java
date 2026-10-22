package io.github.egebulut.thatonegame;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks Updater against a local HTTP server: added, changed and removed files, a corrupt and an interrupted
 * download, Skip, a new APK, and no change. Run (from the repo root, with Android Studio's JDK and org.json):
 *   J=/Applications/Android\ Studio.app/Contents; JSON="$J/plugins/grazie/lib/org.json-json.jar"; O=$(mktemp -d)
 *   "$J/jbr/Contents/Home/bin/javac" -cp "$JSON" -d $O src/io/github/egebulut/thatonegame/Updater.java test/io/github/egebulut/thatonegame/UpdaterTest.java
 *   "$J/jbr/Contents/Home/bin/java" -cp "$O:$JSON" io.github.egebulut.thatonegame.UpdaterTest
 */
public class UpdaterTest {
    static final Map<String, byte[]> served = new ConcurrentHashMap<>(); // URL path -> body
    static final Map<String, Integer> hits = new ConcurrentHashMap<>();
    static volatile String cut; // URL path whose body stops halfway

    public static void main(String[] args) throws Exception {
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
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/assets/app-manifest.json";
        File dir = Files.createTempDirectory("updater").toFile();

        Map<String, String> v1 = site("index.html", "home", "games/a/index.html", "a v1", "games/a/old.txt", "old",
                "io/b/game.js", "b v1", "games/index.html", "list");
        String bundled = manifest("v1", v1);
        Updater u = new Updater(dir, bundled);
        check(u.local("index.html") == null && gone(u, "nope.js"), "the packed site serves before any update");

        publish(url, "v1", v1);
        check(u.update(url) == -1 && u.total == 0, "no change: nothing to do");

        Map<String, String> v2 = new TreeMap<>(v1);
        v2.put("games/a/index.html", "a v2");
        v2.remove("games/a/old.txt");
        v2.put("io/c/new.js", "c v1");
        publish(url, "v2", v2);
        check(u.update(url) == 2, "two games changed (games/a/ and io/c/)");
        check(text(u.local("games/a/index.html")).equals("a v2") && text(u.local("io/c/new.js")).equals("c v1"), "changed and new files served");
        check(gone(u, "games/a/old.txt"), "removed file no longer served");
        check(u.local("io/b/game.js") == null && u.local("games/index.html") == null, "unchanged files still come from the APK");
        check(hits.get("/games/a/") == 1 && !hits.containsKey("/io/b/game.js"), "only changed files downloaded, at their clean URL");

        u = new Updater(dir, bundled);
        check(u.version.equals("v2") && text(u.local("games/a/index.html")).equals("a v2"), "the update survives a restart");
        check(new Updater(dir, manifest("v1b", v1)).version.equals("v1b"), "a new APK starts from its own site");

        Map<String, String> v3 = new TreeMap<>(v2);
        v3.put("io/b/game.js", "b v3");
        publish(url, "v3", v3);
        served.put("/io/b/game.js", "b X3".getBytes(StandardCharsets.UTF_8)); // same size, wrong bytes
        check(u.update(url) == -1 && u.version.equals("v2"), "corrupt download: the update is not installed");
        check(u.local("io/b/game.js") == null && !new File(dir, sha("b v3")).exists() && !new File(dir, "download.tmp").exists(),
                "corrupt download: rejected and removed, the old file still served");

        Map<String, String> v4 = new TreeMap<>(v2);
        v4.put("games/a/index.html", "a v4");
        v4.put("io/b/game.js", "b v4, long enough to stop halfway through");
        publish(url, "v4", v4);
        cut = "/io/b/game.js";
        u = new Updater(dir, bundled);
        check(u.update(url) == -1 && u.version.equals("v2") && text(u.local("games/a/index.html")).equals("a v2"),
                "interrupted download: still on the old version");
        check(!new File(dir, "download.tmp").exists(), "interrupted download: the partial file is removed");
        cut = null;
        u = new Updater(dir, bundled);
        check(u.update(url) == 2 && text(u.local("io/b/game.js")).startsWith("b v4"), "interrupted download: the next start finishes it");
        check(hits.get("/games/a/") == 2, "a file downloaded before the interruption is not downloaded again");
        check(!new File(dir, sha("a v2")).exists() && new File(dir, sha("c v1")).exists(), "replaced files deleted, kept ones stay");

        Map<String, String> v5 = new TreeMap<>(v4);
        v5.put("games/a/index.html", "a v1"); // back to the APK's copy
        v5.remove("io/c/new.js");
        publish(url, "v5", v5);
        u = new Updater(dir, bundled);
        u.cancel();
        check(u.update(url) == -1 && new Updater(dir, bundled).version.equals("v4"), "Skip: nothing installed");
        u = new Updater(dir, bundled);
        check(u.update(url) == 1 && u.local("games/a/index.html") == null && gone(u, "io/c/new.js"), "back to the packed copy, downloaded file removed");
        check(!new File(dir, sha("a v4")).exists() && !new File(dir, sha("c v1")).exists(), "files no longer listed are deleted");

        String outside = "../" + dir.getName() + "/manifest.json"; // a file that exists, so it would not be downloaded
        served.put("/assets/app-manifest.json", manifest("v6", v5).replace(sha("home"), outside).getBytes(StandardCharsets.UTF_8));
        check(u.update(url) == -1 && u.version.equals("v5"), "a manifest with a bad sha256 is refused");

        server.stop(0);
        long t = System.nanoTime();
        check(new Updater(dir, bundled).update(url) == -1 && System.nanoTime() - t < 4e9, "offline: skips at once");
        System.out.println("all checks passed");
    }

    static Map<String, String> site(String... kv) {
        Map<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String manifest(String version, Map<String, String> files) throws Exception {
        StringBuilder s = new StringBuilder("{\"format\":1,\"version\":\"" + version + "\",\"generatedAt\":\"now\",\"files\":{");
        for (Map.Entry<String, String> e : files.entrySet()) {
            s.append(s.charAt(s.length() - 1) == '{' ? "" : ",").append('"').append(e.getKey()).append("\":{\"sha256\":\"")
                    .append(sha(e.getValue())).append("\",\"size\":").append(e.getValue().getBytes(StandardCharsets.UTF_8).length).append('}');
        }
        return s.append("}}").toString();
    }

    /** Serves a site version the way Vercel does: clean URLs, and the manifest. */
    static void publish(String url, String version, Map<String, String> files) throws Exception {
        served.clear();
        for (Map.Entry<String, String> e : files.entrySet()) {
            served.put("/" + Updater.web(e.getKey()), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        served.put("/assets/app-manifest.json", manifest(version, files).getBytes(StandardCharsets.UTF_8));
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

    static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAILED: " + what);
            System.exit(1); // the server thread would keep the JVM running
        }
        System.out.println("ok: " + what);
    }
}
