package io.github.egebulut.thatonegame;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * New versions of the app itself. The site publishes assets/app-latest.json ({versionCode, versionName, url, sha256,
 * size, notes}; latest.sh writes it for each release). The app reads it at most once every 6 hours, offers a newer
 * version in the game list, downloads the APK, and hands it to Android's installer only when its size and sha256 match.
 * The state is one file, dir/app-update.json: {"checked": time, "latest": {...}, "later": {"code", "until"}}.
 */
final class AppUpdate {
    static final long EVERY = 6 * 3600_000L, LATER = 24 * 3600_000L;

    final File state;

    AppUpdate(File dir) {
        state = new File(dir, "app-update.json");
    }

    JSONObject load() {
        try {
            return new JSONObject(Updater.read(new FileInputStream(state), 1 << 20));
        } catch (Exception none) {
            return new JSONObject();
        }
    }

    void save(JSONObject s) throws IOException {
        File tmp = new File(state.getPath() + ".tmp");
        Updater.write(tmp, s.toString());
        if (!tmp.renameTo(state)) throw new IOException("could not save " + state);
    }

    /** Fetches app-latest.json when the last successful check is 6 hours old or more. Offline or on error, nothing changes. */
    synchronized boolean check(String url, long now) {
        JSONObject s = load();
        if (now - s.optLong("checked") < EVERY && now >= s.optLong("checked")) return false;
        try {
            JSONObject l = new JSONObject(Updater.read(Updater.open(url, 5000), 64 << 10));
            if (l.getInt("versionCode") < 1 || l.getString("versionName").isEmpty() || l.getLong("size") < 1
                    || !l.getString("sha256").matches("[0-9a-f]{64}") || !l.getString("url").matches("https?://.+")) {
                throw new IOException("bad app-latest.json");
            }
            save(s.put("checked", now).put("latest", l));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The newer version to offer, or null: none known, not newer than ours, or "Later" for it less than 24 hours ago. */
    synchronized JSONObject offer(long ours, long now) {
        JSONObject s = load(), l = s.optJSONObject("latest"), later = s.optJSONObject("later");
        if (l == null || l.optLong("versionCode") <= ours) return null;
        if (later != null && later.optLong("code") == l.optLong("versionCode") && now < later.optLong("until")) return null;
        return l;
    }

    /** "Later": hides the banner for that version for 24 hours. */
    synchronized void later(long code, long now) {
        try {
            save(load().put("later", new JSONObject().put("code", code).put("until", now + LATER)));
        } catch (Exception ignored) {
            // the banner shows again next time
        }
    }

    /**
     * Downloads the APK into dir and returns it only when its size and sha256 match app-latest.json. Anything else
     * deletes the file and throws: an unverified APK is never kept, so it can never be installed.
     */
    static File download(JSONObject l, File dir, long[] prog) throws Exception {
        dir.mkdirs();
        File tmp = new File(dir, "update.tmp"), apk = new File(dir, "ThatOneGame-" + l.getLong("versionCode") + ".apk");
        long size = l.getLong("size"), n = 0;
        prog[0] = 0;
        prog[1] = size;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try {
            try (InputStream in = Updater.open(l.getString("url"), 15000); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[65536];
                for (int r; (r = in.read(b)) > 0; n += r, prog[0] = n) {
                    if (n + r > size) throw new IOException("larger than app-latest.json says");
                    md.update(b, 0, r);
                    out.write(b, 0, r);
                }
                out.getFD().sync();
            }
            StringBuilder hex = new StringBuilder();
            for (byte x : md.digest()) hex.append(String.format("%02x", x));
            if (n != size || !hex.toString().equals(l.getString("sha256"))) throw new IOException("the APK does not match app-latest.json");
            if (!tmp.renameTo(apk)) throw new IOException("could not keep the APK");
            return apk;
        } catch (Exception e) {
            tmp.delete();
            apk.delete();
            throw e;
        }
    }
}
