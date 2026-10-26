package com.martinb.downloados;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.ParameterizedRobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Opens DownloadOS on phones, phone landscape and tablets, in English and French,
 * on each tab, and fails when anything is cut off or runs off the screen.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class ScreensTest {

    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static Collection<Object[]> sizes() {
        List<Object[]> out = new ArrayList<>();
        for (String lang : new String[]{"en", "fr"}) {
            for (String size : new String[]{
                    "w320dp-h568dp-port",      // small phone
                    "w411dp-h891dp-port",      // phone
                    "w891dp-h411dp-land",      // phone landscape
                    "w800dp-h1280dp-port",     // tablet
                    "w1280dp-h800dp-land",     // tablet landscape
            }) out.add(new Object[]{lang + "-" + size});
        }
        return out;
    }

    private final String qualifiers;

    public ScreensTest(String qualifiers) { this.qualifiers = qualifiers; }

    @Test
    public void everyTabFits() throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        java.util.Locale.setDefault(new java.util.Locale(qualifiers.substring(0, 2)));
        seedApps(RuntimeEnvironment.getApplication());

        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = ctl.get();
        View root = a.getWindow().getDecorView();
        List<String> problems = new ArrayList<>();
        for (int tab = 0; tab < 3; tab++) {
            java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showTab", int.class);
            show.setAccessible(true);
            show.invoke(a, tab);
            layout(root);
            check(root, root.getWidth(), "tab " + tab, problems);
        }
        ctl.pause().stop().destroy();
        assertTrue(qualifiers + ":\n" + String.join("\n", problems), problems.isEmpty());
    }

    /** A cached list of apps with long names, so no network is needed. */
    private static void seedApps(Context c) throws Exception {
        JSONArray arr = new JSONArray();
        for (String repo : Arrays.asList("oi1", "prodfos", "MarbleOS", "DownloadOS")) {
            GitHubApi.AppEntry e = new GitHubApi.AppEntry();
            e.owner = "fisbal";
            e.repo = repo;
            e.description = "A MartinB OS app with a fairly long description that has to wrap on small phones";
            e.tag = "v1.0.123";
            e.releaseName = e.tag;
            e.publishedAt = "2026-10-26T07:00:00Z";
            e.apkUrl = "https://github.com/fisbal/" + repo + "/releases/latest/download/app.apk";
            e.apkName = repo + "-android-arm64.apk";
            e.htmlUrl = "https://github.com/fisbal/" + repo;
            e.size = 123_456_789L;
            arr.put(e.toJson());
        }
        Prefs.setCache(c, arr.toString());
    }

    private static void layout(View root) {
        int w = root.getResources().getDisplayMetrics().widthPixels;
        int h = root.getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
    }

    private static void check(View v, int screenW, String where, List<String> problems) {
        if (v.getVisibility() != View.VISIBLE) return;
        int[] at = new int[2];
        v.getLocationInWindow(at);
        String name = describe(v);
        if (v.getWidth() > 0 && (at[0] < 0 || at[0] + v.getWidth() > screenW + 1)) {
            problems.add(where + ": " + name + " runs off the screen (x=" + at[0] + ", w=" + v.getWidth()
                    + ", screen " + screenW + ")");
        }
        if (v instanceof TextView && v.getParent() instanceof View) {
            View parent = (View) v.getParent();
            if (v.getRight() > parent.getWidth() - parent.getPaddingRight() + 1 && parent.getWidth() > 0
                    && !(parent instanceof android.widget.HorizontalScrollView)) {
                problems.add(where + ": " + name + " is cut off by its box");
            }
            TextView tv = (TextView) v;
            // Buttons (clickable pills) and tabs must keep their label on one line.
            if (tv.isClickable() && tv.getLayout() != null && tv.getLayout().getLineCount() > 1) {
                problems.add(where + ": button " + name + " breaks onto " + tv.getLayout().getLineCount() + " lines");
            }
            if (tv.getLayout() != null && tv.getLayout().getEllipsisCount(0) > 0) {
                problems.add(where + ": " + name + " is shortened with …");
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) check(g.getChildAt(i), screenW, where, problems);
        }
    }

    private static String describe(View v) {
        if (v instanceof TextView) return "\"" + ((TextView) v).getText() + "\"";
        return v.getClass().getSimpleName();
    }
}
