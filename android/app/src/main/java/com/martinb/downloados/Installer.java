package com.martinb.downloados;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Installs an APK file through Android's PackageInstaller session API. */
final class Installer {

    static final String ACTION_STATUS = "com.martinb.downloados.INSTALL_STATUS";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_REPO = "repo";
    static final String EXTRA_TAG = "tag";
    static final String EXTRA_PKG = "pkg";

    private Installer() {}

    /** Package name + version found inside an APK file, or null if it isn't a valid APK. */
    static PackageInfo readApk(Context ctx, File apk) {
        try {
            return ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    static void install(Context ctx, File apk, String label, String repoKey, String tag) throws IOException {
        PackageInfo info = readApk(ctx, apk);
        if (info == null) throw new IOException("NOT_AN_APK");

        PackageManager pm = ctx.getPackageManager();
        PackageInstaller pi = pm.getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(info.packageName);
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: apps DownloadOS installed itself can update silently (no confirm screen).
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }

        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sessionId);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }

            Intent status = new Intent(ctx, InstallReceiver.class);
            status.setAction(ACTION_STATUS);
            status.putExtra(EXTRA_LABEL, label);
            status.putExtra(EXTRA_REPO, repoKey == null ? "" : repoKey);
            status.putExtra(EXTRA_TAG, tag == null ? "" : tag);
            status.putExtra(EXTRA_PKG, info.packageName);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, status, flags);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }
}
