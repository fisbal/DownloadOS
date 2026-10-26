package com.martinb.downloados;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.database.Cursor;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements InstallReceiver.Listener {

    // ---- MartinB OS palette ----
    private static final int BG = Color.parseColor("#0A0A0E");
    private static final int CARD = Color.parseColor("#15151C");
    private static final int CARD_LINE = Color.parseColor("#26262F");
    private static final int GOLD = Color.parseColor("#D4AF37");
    private static final int GOLD_SOFT = Color.parseColor("#33D4AF37");
    private static final int TEXT = Color.parseColor("#F2F2F5");
    private static final int MUTED = Color.parseColor("#9A9AA6");
    private static final int OK_GREEN = Color.parseColor("#5FD38D");
    private static final int DANGER = Color.parseColor("#E5685E");

    private static final int REQ_PICK_APK = 41;
    private static final long CACHE_FRESH_MS = 10 * 60 * 1000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final boolean fr = Locale.getDefault().getLanguage().equals("fr");

    private final List<GitHubApi.AppEntry> entries = new ArrayList<>();

    private LinearLayout appsList, filesList;
    private TextView statusText;
    private View[] panels;
    private TextView[] tabs;
    private EditText linkInput;
    private LinearLayout progressPanel;
    private ProgressBar progressBar;
    private TextView progressLabel, progressPct;

    private volatile boolean busy = false;
    private volatile boolean cancelRequested = false;
    private boolean resumed = false;
    private boolean refreshing = false;

    // Install waiting for the "install unknown apps" permission
    private File pendingFile;
    private String pendingLabel, pendingRepo, pendingTag;
    private File currentFile;

    private String t(String french, String english) { return fr ? french : english; }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // =================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        setContentView(buildUi());
        InstallReceiver.listener = this;

        loadCache();
        renderApps();
        if (entries.isEmpty() || System.currentTimeMillis() - Prefs.cacheTime(this) > CACHE_FRESH_MS) {
            refresh();
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        InstallReceiver.listener = this;
        if (pendingFile != null && getPackageManager().canRequestPackageInstalls()) {
            File f = pendingFile;
            pendingFile = null;
            startInstall(f, pendingLabel, pendingRepo, pendingTag);
        }
        renderApps();
        renderFiles();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        if (InstallReceiver.listener == this) InstallReceiver.listener = null;
        super.onDestroy();
    }

    // =================================================================== gold window bar
    // Same bar as every MartinB app: logo + name, then minimize / full screen / close.

    private boolean immersive = false;
    private android.widget.ImageButton maxButton;

    private View windowBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#0B0A08"));
        bar.setPadding(dp(12), 0, dp(2), 0);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(24), dp(24));
        ilp.rightMargin = dp(10);
        bar.addView(icon, ilp);

        TextView name = new TextView(this);
        android.text.SpannableString brand = new android.text.SpannableString("DownloadOS");
        brand.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#D4AD62")), 8, 10, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        name.setText(brand);
        name.setTextColor(Color.parseColor("#F3EFE6"));
        name.setTextSize(16);
        name.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        name.setSingleLine(true);
        bar.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(barButton(R.drawable.mb_min, t("Réduire", "Minimize"), v -> moveTaskToBack(true)));
        maxButton = barButton(R.drawable.mb_max, t("Plein écran", "Full screen"), v -> { immersive = !immersive; applyImmersive(); });
        bar.addView(maxButton);
        bar.addView(barButton(R.drawable.mb_close, t("Fermer", "Close"), v -> finishAndRemoveTask()));
        return bar;
    }

    private android.widget.ImageButton barButton(int drawable, String label, View.OnClickListener onClick) {
        android.widget.ImageButton b = new android.widget.ImageButton(this);
        b.setImageResource(drawable);
        b.setContentDescription(label);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) b.setBackgroundResource(tv.resourceId);
        else b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(44)));
        return b;
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(immersive
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_VISIBLE);
        if (maxButton != null) maxButton.setImageResource(immersive ? R.drawable.mb_restore : R.drawable.mb_max);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && immersive) applyImmersive();
    }

    // =================================================================== UI building

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.addView(windowBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        // Header -----------------------------------------------------------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(22), dp(12), dp(6));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        SpannableString s = new SpannableString("DownloadOS");
        s.setSpan(new ForegroundColorSpan(TEXT), 0, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(GOLD), 8, 10, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(s);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setLetterSpacing(0.02f);
        // One line that shrinks on small phones instead of being cut off.
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(18, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        titles.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        titles.addView(label(t("MartinB OS · installe tes APK en un geste",
                "MartinB OS · install your APKs in one tap"), 13, MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView askBtn = iconButton("✦");
        askBtn.setContentDescription(t("Demander à Claude", "Ask Claude"));
        askBtn.setOnClickListener(v -> showCopilot());
        header.addView(askBtn);
        TextView refreshBtn = iconButton("⟳");
        refreshBtn.setOnClickListener(v -> { showTab(0); refresh(); });
        header.addView(refreshBtn);
        TextView settingsBtn = iconButton("⚙");
        settingsBtn.setOnClickListener(v -> showSettings());
        header.addView(settingsBtn);
        root.addView(header);

        // Gold hairline
        View line = new View(this);
        line.setBackgroundColor(GOLD_SOFT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(20), dp(8), dp(20), dp(10));
        root.addView(line, lp);

        // Tabs -------------------------------------------------------------
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(16), 0, dp(16), dp(8));
        String[] names = {t("Mes apps", "My apps"), t("Lien", "Link"), t("Fichier", "File")};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView tab = label(names[i], 14, TEXT, true);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), dp(10), dp(4), dp(10));
            // One line always: with large system text on a small phone, the label shrinks instead of wrapping.
            tab.setMaxLines(1);
            tab.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            tab.setOnClickListener(v -> showTab(idx));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMargins(dp(4), 0, dp(4), 0);
            tabRow.addView(tab, tp);
            tabs[i] = tab;
        }
        root.addView(tabRow);

        // Panels -------------------------------------------------------------
        FrameLayout content = new FrameLayout(this);
        panels = new View[]{buildAppsPanel(), buildLinkPanel(), buildFilePanel()};
        for (View p : panels) content.addView(p, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Progress panel -----------------------------------------------------
        progressPanel = new LinearLayout(this);
        progressPanel.setOrientation(LinearLayout.VERTICAL);
        progressPanel.setPadding(dp(18), dp(14), dp(18), dp(16));
        progressPanel.setBackground(cardBg(CARD, GOLD_SOFT, 18));
        LinearLayout pRow = new LinearLayout(this);
        pRow.setGravity(Gravity.CENTER_VERTICAL);
        progressLabel = label("", 14, TEXT, true);
        pRow.addView(progressLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progressPct = label("", 14, GOLD, true);
        pRow.addView(progressPct);
        progressPanel.addView(pRow);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_LINE));
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        pbp.setMargins(0, dp(10), 0, dp(10));
        progressPanel.addView(progressBar, pbp);
        TextView cancel = pill(t("Annuler", "Cancel"), false);
        cancel.setOnClickListener(v -> cancelRequested = true);
        progressPanel.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progressPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams ppp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ppp.setMargins(dp(14), dp(6), dp(14), dp(14));
        root.addView(progressPanel, ppp);

        showTab(0);
        // Tablets and landscape: keep the content at a readable width, centred.
        Layouts.MaxWidth frame = new Layouts.MaxWidth(this, dp(720));
        frame.setBackgroundColor(BG);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private View buildAppsPanel() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(24));
        statusText = label("", 13, MUTED, false);
        statusText.setPadding(dp(4), dp(2), dp(4), dp(10));
        col.addView(statusText);
        appsList = new LinearLayout(this);
        appsList.setOrientation(LinearLayout.VERTICAL);
        col.addView(appsList);
        sv.addView(col);
        return sv;
    }

    private View buildLinkPanel() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(24));

        LinearLayout card = card();
        card.addView(label(t("Installer depuis un lien", "Install from a link"), 18, TEXT, true));
        TextView help = label(t(
                "Colle un lien direct .apk, une page GitHub (github.com/propriétaire/projet) ou juste propriétaire/projet. "
                        + "Astuce : dans Chrome, touche Partager → DownloadOS.",
                "Paste a direct .apk link, a GitHub page (github.com/owner/repo) or just owner/repo. "
                        + "Tip: in Chrome, tap Share → DownloadOS."), 13, MUTED, false);
        help.setPadding(0, dp(6), 0, dp(12));
        card.addView(help);

        linkInput = new EditText(this);
        linkInput.setHint("https://…");
        linkInput.setHintTextColor(MUTED);
        linkInput.setTextColor(TEXT);
        linkInput.setTextSize(15);
        linkInput.setSingleLine(true);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        linkInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        linkInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        linkInput.setBackground(cardBg(BG, CARD_LINE, 12));
        linkInput.setOnEditorActionListener((v, actionId, event) -> { startLinkDownload(); return true; });
        card.addView(linkInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ViewGroup row = buttonRow();
        TextView paste = pill(t("Coller", "Paste"), false);
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence txt = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (txt != null) linkInput.setText(txt.toString().trim());
            }
        });
        row.addView(paste, rowParams());
        TextView go = pill(t("Télécharger et installer", "Download & install"), true);
        go.setOnClickListener(v -> startLinkDownload());
        row.addView(go, rowParams());
        card.addView(row);
        col.addView(card, cardParams());
        sv.addView(col);
        return sv;
    }

    private View buildFilePanel() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(24));

        LinearLayout card = card();
        card.addView(label(t("Installer un APK du téléphone", "Install an APK from this phone"), 18, TEXT, true));
        TextView help = label(t(
                "Choisis un fichier .apk (Téléchargements, Drive, clé USB…). Tu peux aussi l'ouvrir directement avec DownloadOS.",
                "Pick an .apk file (Downloads, Drive, USB…). You can also open it directly with DownloadOS."), 13, MUTED, false);
        help.setPadding(0, dp(6), 0, dp(12));
        card.addView(help);
        TextView pick = pill(t("Choisir un APK", "Choose an APK"), true);
        pick.setOnClickListener(v -> pickApk());
        card.addView(pick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(card, cardParams());

        TextView h = label(t("Déjà téléchargés", "Already downloaded"), 13, GOLD, true);
        h.setPadding(dp(4), dp(18), dp(4), dp(8));
        col.addView(h);
        filesList = new LinearLayout(this);
        filesList.setOrientation(LinearLayout.VERTICAL);
        col.addView(filesList);
        sv.addView(col);
        return sv;
    }

    private void showTab(int idx) {
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean on = i == idx;
            tabs[i].setTextColor(on ? BG : TEXT);
            tabs[i].setBackground(cardBg(on ? GOLD : CARD, on ? GOLD : CARD_LINE, 22));
        }
        if (idx == 2) renderFiles();
    }

    // ---- small view helpers ----

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        tv.setLineSpacing(0, 1.15f);
        return tv;
    }

    private GradientDrawable cardBg(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#40FFFFFF")), content, null);
    }

    private TextView pill(String text, boolean primary) {
        TextView b = label(text, 14, primary ? BG : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(11), dp(16), dp(11));
        b.setBackground(ripple(cardBg(primary ? GOLD : CARD, primary ? GOLD : Color.parseColor("#3A3A45"), 22)));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private TextView iconButton(String glyph) {
        TextView b = label(glyph, 22, GOLD, false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(cardBg(CARD, CARD_LINE, 22)));
        b.setClickable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(44), dp(44));
        p.setMargins(dp(6), 0, dp(6), 0);
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setBackground(cardBg(CARD, CARD_LINE, 18));
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(12));
        return p;
    }

    /** Buttons wrap onto a second line on narrow phones instead of being squeezed. */
    private ViewGroup buttonRow() {
        Layouts.FlowRow row = new Layouts.FlowRow(this);
        row.setPadding(0, dp(12), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(8), dp(8));
        return p;
    }

    // =================================================================== app list

    private void loadCache() {
        entries.clear();
        String json = Prefs.cache(this);
        if (json.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) entries.add(GitHubApi.AppEntry.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) { }
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        final String owner = Prefs.owner(this);
        final String token = Prefs.token(this);
        statusText.setText(t("Recherche des APK de " + owner + " sur GitHub…",
                "Looking for " + owner + "'s APKs on GitHub…"));
        new Thread(() -> {
            String error = null;
            List<GitHubApi.AppEntry> list = null;
            try {
                list = new GitHubApi(token).listApps(owner);
                JSONArray arr = new JSONArray();
                for (GitHubApi.AppEntry e : list) arr.put(e.toJson());
                Prefs.setCache(this, arr.toString());
            } catch (GitHubApi.RateLimitException rl) {
                error = t("Limite GitHub atteinte (60 requêtes/heure sans jeton). Réessaie plus tard ou ajoute un jeton dans ⚙.",
                        "GitHub limit reached (60 requests/hour without a token). Try later or add a token in ⚙.");
            } catch (Exception e) {
                error = t("Impossible de joindre GitHub — vérifie Internet. (" + e.getMessage() + ")",
                        "Can't reach GitHub — check your connection. (" + e.getMessage() + ")");
            }
            final String err = error;
            final List<GitHubApi.AppEntry> result = list;
            ui.post(() -> {
                refreshing = false;
                if (result != null) {
                    entries.clear();
                    entries.addAll(result);
                }
                renderApps();
                if (err != null) statusText.setText(err);
            });
        }).start();
    }

    private PackageInfo installedInfo(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String stripV(String tag) {
        if (tag == null) return "";
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    private void renderApps() {
        if (appsList == null) return;
        appsList.removeAllViews();
        int updates = 0;
        for (GitHubApi.AppEntry e : entries) {
            if (addAppCard(e)) updates++;
        }
        if (!refreshing) {
            if (entries.isEmpty()) {
                statusText.setText(t("Aucun APK trouvé pour " + Prefs.owner(this)
                                + ". Publie un .apk dans une Release GitHub, puis touche ⟳.",
                        "No APK found for " + Prefs.owner(this) + ". Publish an .apk in a GitHub Release, then tap ⟳."));
            } else {
                String n = entries.size() + (fr ? " apps" : " apps");
                String u = updates == 0 ? t("tout est à jour", "everything is up to date")
                        : updates + t(" mise(s) à jour disponible(s)", " update(s) available");
                statusText.setText(n + " · " + u + " · " + Prefs.owner(this));
            }
        }
    }

    /** Adds one card; returns true when an update is available for an installed app. */
    private boolean addAppCard(final GitHubApi.AppEntry e) {
        final String key = e.owner + "/" + e.repo;
        final String pkg = Prefs.packageFor(this, key, e.repo);
        PackageInfo inst = installedInfo(pkg);
        boolean installed = inst != null;
        boolean upToDate = false;
        if (installed) {
            String vn = inst.versionName == null ? "" : inst.versionName;
            upToDate = vn.equals(stripV(e.tag)) || vn.equals(e.tag)
                    || (Prefs.installedTag(this, key).equals(e.tag)
                    && Prefs.installedCode(this, key) == Prefs.versionCode(inst));
        }

        LinearLayout c = card();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(e.repo, 18, TEXT, true);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chip;
        if (!installed) {
            chip = label(t("Non installée", "Not installed"), 11, MUTED, true);
            chip.setBackground(cardBg(BG, CARD_LINE, 10));
        } else if (upToDate) {
            chip = label(t("À jour ✓", "Up to date ✓"), 11, OK_GREEN, true);
            chip.setBackground(cardBg(BG, Color.parseColor("#3355D38D"), 10));
        } else {
            chip = label(t("Mise à jour", "Update"), 11, BG, true);
            chip.setBackground(cardBg(GOLD, GOLD, 10));
        }
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        top.addView(chip);
        c.addView(top);

        String meta = e.tag
                + (e.size > 0 ? "  ·  " + Formatter.formatShortFileSize(this, e.size) : "")
                + (e.publishedAt.length() >= 10 ? "  ·  " + e.publishedAt.substring(0, 10) : "");
        TextView metaTv = label(meta, 13, GOLD, false);
        metaTv.setPadding(0, dp(3), 0, 0);
        c.addView(metaTv);

        if (installed) {
            TextView iv = label(t("Installée : ", "Installed: ") + inst.versionName, 12, MUTED, false);
            iv.setPadding(0, dp(2), 0, 0);
            c.addView(iv);
        }
        if (e.description != null && !e.description.isEmpty()) {
            TextView d = label(e.description, 13, MUTED, false);
            d.setPadding(0, dp(6), 0, 0);
            c.addView(d);
        }

        ViewGroup row = buttonRow();
        String main = !installed ? t("Installer", "Install")
                : !upToDate ? t("Mettre à jour", "Update") : t("Réinstaller", "Reinstall");
        TextView mainBtn = pill(main, !installed || !upToDate);
        mainBtn.setOnClickListener(v -> download(e.apkUrl, e.apkName, e.repo, key, e.tag));
        row.addView(mainBtn, rowParams());

        if (installed) {
            final Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null && !pkg.equals(getPackageName())) {
                TextView open = pill(t("Ouvrir", "Open"), false);
                open.setOnClickListener(v -> startActivity(launch));
                row.addView(open, rowParams());
            }
            if (!pkg.equals(getPackageName())) {
                TextView del = pill("🗑", false);
                del.setTextColor(DANGER);
                del.setOnClickListener(v -> uninstall(pkg));
                row.addView(del, rowParams());
            }
        }
        TextView page = pill("↗", false);
        page.setOnClickListener(v -> openUrl(e.htmlUrl));
        row.addView(page, rowParams());
        c.addView(row);

        appsList.addView(c, cardParams());
        return installed && !upToDate;
    }

    // =================================================================== downloading

    private void startLinkDownload() {
        final String text = linkInput.getText().toString();
        if (busy) { toast(t("Un téléchargement est déjà en cours.", "A download is already running.")); return; }
        if (GitHubApi.extractUrl(text) == null) {
            toast(t("Colle d'abord un lien.", "Paste a link first."));
            return;
        }
        final String token = Prefs.token(this);
        showProgress(t("Recherche de l'APK…", "Finding the APK…"), -1);
        new Thread(() -> {
            try {
                final String[] r = new GitHubApi(token).resolve(text);
                ui.post(() -> {
                    hideProgress();
                    String labelName = r[2].isEmpty() ? r[1] : r[2].substring(r[2].indexOf('/') + 1);
                    download(r[0], r[1], labelName, r[2], "");
                });
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                ui.post(() -> {
                    hideProgress();
                    if ("NO_APK_IN_RELEASE".equals(msg)) {
                        toast(t("Ce projet n'a pas d'APK dans sa dernière Release.", "This repo has no APK in its latest release."));
                    } else if ("NO_URL".equals(msg)) {
                        toast(t("Lien non reconnu.", "Link not recognised."));
                    } else {
                        toast(t("Erreur : ", "Error: ") + msg);
                    }
                });
            }
        }).start();
    }

    private void download(final String url, String fileName, final String label, final String repoKey, final String tag) {
        if (busy) { toast(t("Un téléchargement est déjà en cours.", "A download is already running.")); return; }
        busy = true;
        cancelRequested = false;
        final File dir = new File(getCacheDir(), "apks");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String safe = fileName == null ? "download.apk" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!safe.toLowerCase().endsWith(".apk")) safe = safe + ".apk";
        final File out = new File(dir, safe);
        final File part = new File(dir, safe + ".part");
        showProgress(t("Téléchargement de ", "Downloading ") + label, 0);

        new Thread(() -> {
            String error = null;
            HttpURLConnection c = null;
            try {
                URL u = new URL(url);
                // Follow redirects manually (GitHub → its file CDN), max 6 hops.
                for (int hop = 0; hop < 6; hop++) {
                    c = (HttpURLConnection) u.openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", "DownloadOS-Android");
                    c.setRequestProperty("Accept", "application/octet-stream");
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(30000);
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null) throw new IOException("Redirect without location");
                        u = new URL(u, loc);
                        continue;
                    }
                    if (code >= 400) throw new IOException("HTTP " + code);
                    break;
                }
                if (c == null) throw new IOException("No connection");
                final long total = c.getContentLengthLong();
                long done = 0;
                long lastUi = 0;
                try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelRequested) throw new IOException("CANCELLED");
                        os.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 120) {
                            lastUi = now;
                            final long d = done;
                            ui.post(() -> updateProgress(d, total));
                        }
                    }
                }
                if (out.exists()) //noinspection ResultOfMethodCallIgnored
                    out.delete();
                if (!part.renameTo(out)) throw new IOException("Rename failed");
            } catch (Exception ex) {
                error = ex.getMessage();
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            } finally {
                if (c != null) c.disconnect();
            }

            final String err = error;
            ui.post(() -> {
                busy = false;
                hideProgress();
                if (err != null) {
                    if ("CANCELLED".equals(err)) toast(t("Téléchargement annulé.", "Download cancelled."));
                    else toast(t("Échec du téléchargement : ", "Download failed: ") + err);
                    return;
                }
                PackageInfo info = Installer.readApk(this, out);
                if (info == null) {
                    toast(t("Ce fichier n'est pas un APK valide.", "This file is not a valid APK."));
                    return;
                }
                Prefs.rememberPackage(this, repoKey, info.packageName);
                startInstall(out, label, repoKey, tag);
            });
        }).start();
    }

    private void showProgress(String text, long total) {
        progressPanel.setVisibility(View.VISIBLE);
        progressLabel.setText(text);
        progressBar.setIndeterminate(total < 0);
        progressBar.setProgress(0);
        progressPct.setText("");
    }

    private void updateProgress(long done, long total) {
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress((int) (done * 1000 / total));
            progressPct.setText((done * 100 / total) + " %");
        } else {
            progressBar.setIndeterminate(true);
            progressPct.setText(Formatter.formatShortFileSize(this, done));
        }
    }

    private void hideProgress() {
        progressPanel.setVisibility(View.GONE);
    }

    // =================================================================== installing

    private void startInstall(final File apk, final String label, final String repoKey, final String tag) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingFile = apk;
            pendingLabel = label;
            pendingRepo = repoKey;
            pendingTag = tag;
            new AlertDialog.Builder(this)
                    .setTitle(t("Une seule fois", "Just once"))
                    .setMessage(t("Android doit autoriser DownloadOS à installer des apps. Active « Autoriser depuis cette source », puis reviens : l'installation reprendra toute seule.",
                            "Android needs to allow DownloadOS to install apps. Turn on “Allow from this source”, then come back — the install continues by itself."))
                    .setPositiveButton(t("Ouvrir le réglage", "Open setting"), (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ex) {
                            startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                        }
                    })
                    .setNegativeButton(t("Plus tard", "Later"), (d, w) -> pendingFile = null)
                    .show();
            return;
        }
        currentFile = apk;
        showProgress(t("Installation de ", "Installing ") + label + "…", -1);
        new Thread(() -> {
            try {
                Installer.install(this, apk, label, repoKey, tag);
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                ui.post(() -> {
                    hideProgress();
                    if ("NOT_AN_APK".equals(msg)) toast(t("Ce fichier n'est pas un APK valide.", "This file is not a valid APK."));
                    else toast(t("Installation impossible : ", "Install failed: ") + msg);
                });
            }
        }).start();
    }

    @Override
    public boolean onConfirmNeeded(Intent confirm) {
        if (!resumed) return false;
        try {
            startActivity(confirm);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onInstallResult(int status, String message, String label, String repo, String tag, final String pkg) {
        Prefs.recordInstall(this, status, repo, tag, pkg);
        ui.post(() -> {
            hideProgress();
            switch (status) {
                case PackageInstaller.STATUS_SUCCESS:
                    toast("✓ " + label + t(" est installée.", " is installed."));
                    if (currentFile != null) //noinspection ResultOfMethodCallIgnored
                        currentFile.delete();
                    currentFile = null;
                    break;
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    toast(t("Installation annulée.", "Install cancelled."));
                    break;
                case PackageInstaller.STATUS_FAILURE_CONFLICT:
                    new AlertDialog.Builder(this)
                            .setTitle(t("Conflit de version", "Version conflict"))
                            .setMessage(t("Une version de " + label + " signée avec une autre clé (ou plus récente) est déjà installée. "
                                            + "Désinstalle-la, puis réessaie.\n\n",
                                    "A version of " + label + " signed with another key (or newer) is already installed. "
                                            + "Uninstall it, then try again.\n\n") + (message == null ? "" : message))
                            .setPositiveButton(t("Désinstaller", "Uninstall"), (d, w) -> uninstall(pkg))
                            .setNegativeButton("OK", null)
                            .show();
                    break;
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    toast(t("APK incompatible avec ce téléphone.", "APK not compatible with this phone."));
                    break;
                case PackageInstaller.STATUS_FAILURE_STORAGE:
                    toast(t("Espace de stockage insuffisant.", "Not enough storage."));
                    break;
                case PackageInstaller.STATUS_FAILURE_BLOCKED:
                    toast(t("Installation bloquée par Android (Play Protect ?).", "Install blocked by Android (Play Protect?)."));
                    break;
                case PackageInstaller.STATUS_FAILURE_INVALID:
                    toast(t("APK invalide ou corrompu.", "Invalid or corrupted APK."));
                    break;
                default:
                    toast(t("Échec : ", "Failed: ") + (message == null ? status : message));
            }
            renderApps();
            renderFiles();
        });
    }

    private void uninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    // =================================================================== files

    @SuppressWarnings("deprecation")
    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_PICK_APK);
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            installFromUri(data.getData());
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor cur = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "app.apk";
        return name;
    }

    private void installFromUri(final Uri uri) {
        if (busy) { toast(t("Un téléchargement est déjà en cours.", "A download is already running.")); return; }
        final String name = displayName(uri);
        showProgress(t("Préparation de ", "Preparing ") + name, -1);
        new Thread(() -> {
            File dir = new File(getCacheDir(), "apks");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (!safe.toLowerCase().endsWith(".apk")) safe += ".apk";
            final File out = new File(dir, safe);
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) throw new IOException("Cannot open file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (Exception ex) {
                error = ex.getMessage();
            }
            final String err = error;
            ui.post(() -> {
                hideProgress();
                if (err != null) { toast(t("Lecture impossible : ", "Can't read file: ") + err); return; }
                if (Installer.readApk(this, out) == null) {
                    toast(t("Ce fichier n'est pas un APK valide.", "This file is not a valid APK."));
                    return;
                }
                String lbl = name.replaceAll("(?i)\\.apk$", "");
                startInstall(out, lbl, "", "");
            });
        }).start();
    }

    private void renderFiles() {
        if (filesList == null) return;
        filesList.removeAllViews();
        File dir = new File(getCacheDir(), "apks");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".apk"));
        if (files == null || files.length == 0) {
            TextView none = label(t("Rien pour l'instant. Les APK installés avec succès sont effacés pour garder de la place.",
                    "Nothing yet. Successfully installed APKs are deleted to save space."), 13, MUTED, false);
            none.setPadding(dp(4), 0, dp(4), 0);
            filesList.addView(none);
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (final File f : files) {
            LinearLayout c = card();
            c.addView(label(f.getName(), 15, TEXT, true));
            PackageInfo info = Installer.readApk(this, f);
            String meta = Formatter.formatShortFileSize(this, f.length())
                    + (info != null ? "  ·  " + info.packageName + "  ·  " + info.versionName : "");
            c.addView(label(meta, 12, MUTED, false));
            ViewGroup row = buttonRow();
            TextView inst = pill(t("Installer", "Install"), true);
            inst.setOnClickListener(v -> startInstall(f, f.getName().replaceAll("(?i)\\.apk$", ""), "", ""));
            row.addView(inst, rowParams());
            TextView del = pill(t("Supprimer", "Delete"), false);
            del.setOnClickListener(v -> {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                renderFiles();
            });
            row.addView(del, rowParams());
            c.addView(row);
            filesList.addView(c, cardParams());
        }
    }

    // =================================================================== intents, settings, misc

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            showTab(2);
            installFromUri(intent.getData());
            intent.setAction(Intent.ACTION_MAIN);
        } else if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                showTab(1);
                linkInput.setText(text.trim());
                startLinkDownload();
            }
            intent.setAction(Intent.ACTION_MAIN);
        }
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);

        final EditText owner = new EditText(this);
        owner.setHint(t("Compte GitHub (ex. fisbal)", "GitHub account (e.g. fisbal)"));
        owner.setText(Prefs.owner(this));
        owner.setSingleLine(true);
        box.addView(owner);

        final EditText token = new EditText(this);
        token.setHint(t("Jeton GitHub (optionnel)", "GitHub token (optional)"));
        token.setText(Prefs.token(this));
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(token);

        final EditText claudeKey = new EditText(this);
        claudeKey.setHint(t("Clé API Claude (pour ✦)", "Claude API key (for ✦)"));
        claudeKey.setText(Prefs.claudeKey(this));
        claudeKey.setSingleLine(true);
        claudeKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        TextView help = new TextView(this);
        help.setText(t("Sans jeton : 60 requêtes/heure, apps publiques seulement. Un jeton « fine-grained » en lecture seule permet aussi tes dépôts privés.",
                "Without a token: 60 requests/hour, public apps only. A read-only fine-grained token also unlocks your private repos."));
        help.setTextSize(12);
        help.setPadding(0, dp(8), 0, 0);
        box.addView(help);
        box.addView(claudeKey);
        TextView claudeHelp = new TextView(this);
        claudeHelp.setText(t("Ta propre clé (console.anthropic.com), gardée seulement sur ce téléphone.",
                "Your own key (console.anthropic.com), kept on this phone only."));
        claudeHelp.setTextSize(12);
        claudeHelp.setPadding(0, dp(8), 0, 0);
        box.addView(claudeHelp);
        ScrollView boxScroll = new ScrollView(this);
        boxScroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(t("Réglages", "Settings"))
                .setView(boxScroll)
                .setPositiveButton(t("Enregistrer", "Save"), (d, w) -> {
                    Prefs.setClaudeKey(this, claudeKey.getText().toString().trim());
                    String o = owner.getText().toString().trim();
                    if (o.isEmpty()) o = "fisbal";
                    Prefs.setOwnerAndToken(this, o, token.getText().toString().trim());
                    entries.clear();
                    renderApps();
                    showTab(0);
                    refresh();
                })
                .setNegativeButton(t("Annuler", "Cancel"), null)
                .show();
    }

    // =================================================================== Claude copilot

    /** "Ask Claude": help with installing, updating or a failed install, in the phone's language. */
    private void showCopilot() {
        String key = Prefs.claudeKey(this);
        if (key.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(t("Demander à Claude", "Ask Claude"))
                    .setMessage(t("Ajoute ta clé API Claude dans ⚙ Réglages, puis touche ✦ à nouveau.",
                            "Add your Claude API key in ⚙ Settings, then tap ✦ again."))
                    .setPositiveButton(t("Réglages", "Settings"), (d, w) -> showSettings())
                    .setNegativeButton("OK", null)
                    .show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        final EditText question = new EditText(this);
        question.setHint(t("Ex. : pourquoi l'installation échoue ?", "E.g. why does the install fail?"));
        question.setMinLines(2);
        box.addView(question);
        final TextView answer = label("", 14, TEXT, false);
        answer.setTextIsSelectable(true);
        answer.setPadding(0, dp(12), 0, dp(8));
        box.addView(answer);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(t("✦ Demander à Claude", "✦ Ask Claude"))
                .setView(sv)
                .setPositiveButton(t("Demander", "Ask"), null)
                .setNegativeButton(t("Fermer", "Close"), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String q = question.getText().toString().trim();
            if (q.isEmpty()) return;
            answer.setText(t("Claude réfléchit…", "Claude is thinking…"));
            final String context = copilotContext();
            new Thread(() -> {
                String out;
                try {
                    out = Copilot.ask(key, context, q, fr);
                } catch (Exception ex) {
                    out = t("Claude n'a pas pu répondre : ", "Claude couldn't answer: ") + ex.getMessage();
                }
                final String text = out;
                ui.post(() -> answer.setText(text));
            }).start();
        }));
        dialog.show();
    }

    /** What Claude sees about this phone: the app list and its install state, never the tokens. */
    private String copilotContext() {
        StringBuilder b = new StringBuilder();
        b.append("Android ").append(android.os.Build.VERSION.RELEASE)
                .append(", ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(". Can install apps: ").append(getPackageManager().canRequestPackageInstalls())
                .append(". GitHub account: ").append(Prefs.owner(this)).append(".\nApps:\n");
        for (GitHubApi.AppEntry e : entries) {
            PackageInfo inst = installedInfo(Prefs.packageFor(this, e.owner + "/" + e.repo, e.repo));
            b.append("- ").append(e.repo).append(" latest ").append(e.tag)
                    .append(inst == null ? ", not installed" : ", installed " + inst.versionName).append('\n');
        }
        if (statusText != null) b.append("Status line: ").append(statusText.getText()).append('\n');
        return b.toString();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("unused")
    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("DownloadOS", text));
    }
}
