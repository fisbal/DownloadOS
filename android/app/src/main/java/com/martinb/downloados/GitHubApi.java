package com.martinb.downloados;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads apps (repos with an .apk in their latest release) from GitHub's public API. */
final class GitHubApi {

    static final class RateLimitException extends IOException {
        RateLimitException() { super("GitHub rate limit"); }
    }

    /** One installable app = one repo whose latest release carries an .apk asset. */
    static final class AppEntry {
        String owner, repo, description, tag, releaseName, publishedAt, apkUrl, apkName, htmlUrl;
        long size;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("owner", owner); o.put("repo", repo); o.put("description", description);
            o.put("tag", tag); o.put("releaseName", releaseName); o.put("publishedAt", publishedAt);
            o.put("apkUrl", apkUrl); o.put("apkName", apkName); o.put("htmlUrl", htmlUrl);
            o.put("size", size);
            return o;
        }

        static AppEntry fromJson(JSONObject o) {
            AppEntry e = new AppEntry();
            e.owner = o.optString("owner"); e.repo = o.optString("repo");
            e.description = o.optString("description"); e.tag = o.optString("tag");
            e.releaseName = o.optString("releaseName"); e.publishedAt = o.optString("publishedAt");
            e.apkUrl = o.optString("apkUrl"); e.apkName = o.optString("apkName");
            e.htmlUrl = o.optString("htmlUrl"); e.size = o.optLong("size");
            return e;
        }
    }

    private final String token;

    GitHubApi(String token) {
        this.token = token == null ? "" : token.trim();
    }

    private String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "DownloadOS-Android");
        if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code == 403 || code == 429) throw new RateLimitException();
            if (code >= 400) throw new IOException("HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Latest release of owner/repo that contains an .apk, or null. */
    AppEntry latestApk(String owner, String repo, String description) throws IOException {
        String body = get("https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest");
        if (body == null) return null;
        try {
            JSONObject rel = new JSONObject(body);
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                if (name.toLowerCase().endsWith(".apk")) {
                    AppEntry e = new AppEntry();
                    e.owner = owner;
                    e.repo = repo;
                    e.description = description == null ? "" : description;
                    e.tag = rel.optString("tag_name", "");
                    e.releaseName = rel.optString("name", e.tag);
                    e.publishedAt = rel.optString("published_at", "");
                    e.apkUrl = a.optString("browser_download_url", "");
                    e.apkName = name;
                    e.size = a.optLong("size", 0);
                    e.htmlUrl = rel.optString("html_url", "https://github.com/" + owner + "/" + repo);
                    return e;
                }
            }
            return null;
        } catch (Exception ex) {
            throw new IOException("Bad GitHub answer: " + ex.getMessage());
        }
    }

    /** Every non-fork repo of the owner that has an APK in its latest release, newest first. */
    List<AppEntry> listApps(String owner) throws IOException {
        String body = get("https://api.github.com/users/" + owner + "/repos?per_page=100&sort=updated");
        if (body == null) throw new IOException("GitHub user not found: " + owner);
        final List<String[]> repos = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                if (r.optBoolean("fork", false) || r.optBoolean("archived", false)) continue;
                String name = r.optString("name", "");
                if (name.isEmpty() || name.toLowerCase().endsWith(".github.io")) continue;
                String desc = r.isNull("description") ? "" : r.optString("description", "");
                repos.add(new String[]{name, desc});
            }
        } catch (Exception ex) {
            throw new IOException("Bad GitHub answer: " + ex.getMessage());
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<AppEntry>> futures = new ArrayList<>();
        for (final String[] r : repos) {
            futures.add(pool.submit(() -> latestApk(owner, r[0], r[1])));
        }
        List<AppEntry> out = new ArrayList<>();
        IOException rate = null;
        for (Future<AppEntry> f : futures) {
            try {
                AppEntry e = f.get();
                if (e != null) out.add(e);
            } catch (Exception ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RateLimitException) rate = (RateLimitException) cause;
            }
        }
        pool.shutdown();
        if (out.isEmpty() && rate != null) throw rate;
        Collections.sort(out, (a, b) -> b.publishedAt.compareTo(a.publishedAt));
        return out;
    }

    private static final Pattern REPO_URL =
            Pattern.compile("^https?://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s?#]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Turns what the user typed/shared into a direct APK link:
     * a direct .apk link stays as-is; a github.com/owner/repo page becomes its latest release APK.
     * Returns {url, suggestedFileName, repoKeyOrEmpty}.
     */
    String[] resolve(String input) throws IOException {
        String s = extractUrl(input);
        if (s == null) throw new IOException("NO_URL");
        String lower = s.toLowerCase();
        boolean direct = lower.contains("/releases/download/") || lower.split("[?#]")[0].endsWith(".apk");
        Matcher m = REPO_URL.matcher(s);
        if (!direct && m.find()) {
            String owner = m.group(1);
            String repo = m.group(2);
            if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
            AppEntry e = latestApk(owner, repo, "");
            if (e == null) throw new IOException("NO_APK_IN_RELEASE");
            return new String[]{e.apkUrl, e.apkName, owner + "/" + repo};
        }
        String name = s.split("[?#]")[0];
        name = name.substring(name.lastIndexOf('/') + 1);
        if (!name.toLowerCase().endsWith(".apk")) name = "download.apk";
        String key = "";
        if (m.find(0)) key = m.group(1) + "/" + m.group(2);
        return new String[]{s, name, key};
    }

    static String extractUrl(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text.trim());
        if (m.find()) return m.group().replaceAll("[)\\]>,.;'\"]+$", "");
        String t = text.trim();
        if (t.matches("^[\\w.-]+/[\\w.-]+$")) return "https://github.com/" + t; // "owner/repo"
        return null;
    }
}
