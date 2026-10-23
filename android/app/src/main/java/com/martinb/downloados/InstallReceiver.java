package com.martinb.downloados;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Receives the result of each install session and shows Android's confirm screen when needed. */
public class InstallReceiver extends BroadcastReceiver {

    interface Listener {
        /** Return true if the listener started the confirm screen itself. */
        boolean onConfirmNeeded(Intent confirm);
        void onInstallResult(int status, String message, String label, String repo, String tag, String pkg);
    }

    static volatile Listener listener;

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String label = intent.getStringExtra(Installer.EXTRA_LABEL);
        String repo = intent.getStringExtra(Installer.EXTRA_REPO);
        String tag = intent.getStringExtra(Installer.EXTRA_TAG);
        String pkg = intent.getStringExtra(Installer.EXTRA_PKG);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            Listener l = listener;
            if (l != null && l.onConfirmNeeded(confirm)) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (Exception e) {
                Toast.makeText(context, "DownloadOS: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }

        Listener l = listener;
        if (l != null) {
            l.onInstallResult(status, message, label, repo, tag, pkg);
        } else {
            Prefs.recordInstall(context, status, repo, tag, pkg);
            String text = status == PackageInstaller.STATUS_SUCCESS
                    ? (label + " ✓")
                    : (label + " ✗ " + (message == null ? "" : message));
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        }
    }
}
