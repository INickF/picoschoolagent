package com.picoschool.agent;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Lists, launches and closes apps on this headset. */
final class AppControl {
    // Closing = send the app to the background, then kill it once Android considers it a background process.
    private static final long[] KILL_DELAYS_MS = {1500, 4000};
    // Switching apps: time for the old app to reach the background before it is killed, then before the launch.
    private static final long SWITCH_KILL_DELAY_MS = 1500;
    private static final long SWITCH_LAUNCH_DELAY_MS = 500;
    private static final long HISTORY_MS = 24L * 60 * 60 * 1000;
    // Preinstalled PICO services that look like user apps but have nothing to show.
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList("com.bytedance.pico.matrix"));

    private final Context ctx;
    private final PackageManager pm;
    private final ActivityManager am;
    private final Handler main = new Handler(Looper.getMainLooper());

    // "package/activity" -> time it was resumed, for activities not paused since. Fed incrementally from usage events.
    private final Map<String, Long> resumed = new HashMap<>();
    private long eventsUntil = 0;
    // Most recently resumed or launched user app, kept after it pauses (e.g. when the headset sleeps).
    private String lastUserApp;

    AppControl(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pm = this.ctx.getPackageManager();
        this.am = (ActivityManager) this.ctx.getSystemService(Context.ACTIVITY_SERVICE);
    }

    // ---------- Permissions ----------

    boolean canDrawOverlays() {
        return Settings.canDrawOverlays(ctx);
    }

    boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    // ---------- Apps ----------

    /**
     * Launchable user-installed apps, sorted by label. PICO system apps (settings, setup screens, store
     * internals) are left out: they are not something to start for a class.
     */
    JSONArray listApps() throws Exception {
        String self = ctx.getPackageName();
        TreeMap<String, JSONObject> sorted = new TreeMap<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (!isUserApp(ai) || pkg.equals(self) || HIDDEN.contains(pkg) || launchIntent(pkg) == null) continue;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            JSONObject app = new JSONObject()
                    .put("package", pkg)
                    .put("label", label)
                    .put("system", false);
            sorted.put(label.toLowerCase() + "\u0000" + pkg, app);
        }
        JSONArray out = new JSONArray();
        for (JSONObject app : sorted.values()) out.put(app);
        return out;
    }

    String label(String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    void launch(String pkg, boolean closePrevious) throws AgentException {
        Intent intent = launchIntent(pkg);
        if (intent == null) throw new AgentException("not_launchable");
        // Android 10+ blocks activity starts from a background service unless the app may draw overlays.
        if (!canDrawOverlays()) throw new AgentException("no_overlay_permission");

        // PICO asks the wearer to confirm when a VR app is started while another one is running, so close the
        // running app first (home + kill), the same as closing it by hand, and only then start the new one.
        // While nobody wears the headset the app is paused and not "in front"; fall back to the last app used
        // or launched. Closing an app that already exited is harmless.
        String previous = null;
        if (closePrevious) {
            previous = foregroundPackage();
            if (previous == null) previous = lastUserApp();
        }
        if (previous != null && !previous.equals(pkg)) {
            wakeUp();
            goHome();
            pause(SWITCH_KILL_DELAY_MS);
            am.killBackgroundProcesses(previous);
            forget(previous);
            pause(SWITCH_LAUNCH_DELAY_MS);
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ctx.startActivity(intent);
        synchronized (this) {
            lastUserApp = pkg;
        }
        // The old app may only reach the background now (PICO defers transitions while nobody wears the headset).
        if (previous != null && !previous.equals(pkg)) scheduleKill(previous);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Closes {@code pkg}, or the foreground app when null. Returns the package that was closed,
     * or null when nothing was running.
     */
    String close(String pkg) throws AgentException {
        if (pkg == null) pkg = foregroundPackage();
        if (pkg == null) pkg = lastUserApp();
        if (pkg == null) return null;
        wakeUp();
        goHome();
        scheduleKill(pkg);
        forget(pkg);
        return pkg;
    }

    /** Returns to home and kills every user app plus the foreground app. Returns the foreground app or null. */
    String closeAll() throws AgentException {
        String fg = foregroundPackage();
        if (fg == null) fg = lastUserApp();
        wakeUp();
        goHome();
        String self = ctx.getPackageName();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (isUserApp(ai) && !ai.packageName.equals(self)) scheduleKill(ai.packageName);
        }
        if (fg != null) scheduleKill(fg);
        synchronized (this) {
            lastUserApp = null;
        }
        return fg;
    }

    private void goHome() throws AgentException {
        // PICO refuses CATEGORY_HOME intents from third-party apps ("refuse third part app start launcher"),
        // so start the home activity by component name. (A Home key press via accessibility is ignored by
        // PICO's shell, so it is not used.)
        if (!canDrawOverlays()) throw new AgentException("no_overlay_permission");
        Intent home = new Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ResolveInfo ri = pm.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
            home.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
        } else {
            home.addCategory(Intent.CATEGORY_HOME);
        }
        try {
            ctx.startActivity(home);
        } catch (RuntimeException e) {
            throw new AgentException("home_refused");
        }
    }

    /**
     * A sleeping headset (nobody wearing it) keeps the VR app on top in a paused state, where neither Home nor
     * killBackgroundProcesses affect it. Turn the screen on briefly so the Home press takes effect.
     */
    private void wakeUp() {
        wakeUp(6000);
    }

    /** Turns the screen on (if off) and keeps it on for {@code holdMs}. Returns true if it was off. */
    @SuppressWarnings("deprecation")
    boolean wakeUp(long holdMs) {
        PowerManager power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        boolean wasOn = power.isInteractive();
        PowerManager.WakeLock lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "PicoAgent:wake");
        lock.acquire(holdMs);
        if (!wasOn) pause(1000);
        return !wasOn;
    }

    private void scheduleKill(String pkg) {
        for (long delay : KILL_DELAYS_MS) {
            main.postDelayed(() -> am.killBackgroundProcesses(pkg), delay);
        }
    }

    private Intent launchIntent(String pkg) {
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent != null) return intent;
        // VR apps sometimes declare only a VR-specific category instead of LAUNCHER.
        List<ResolveInfo> mains = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(pkg), 0);
        if (mains.isEmpty()) return null;
        ResolveInfo ri = mains.get(0);
        return new Intent(Intent.ACTION_MAIN).setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
    }

    private static boolean isUserApp(ApplicationInfo ai) {
        return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    // ---------- Foreground ----------

    /** The app in front, or null for the home screen / unknown. Needs usage access. */
    synchronized String foregroundPackage() {
        if (!hasUsageAccess()) return null;
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        long from = eventsUntil == 0 ? now - HISTORY_MS : eventsUntil;
        UsageEvents events = usm.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            String key = e.getPackageName() + "/" + e.getClassName();
            int type = e.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                resumed.put(key, e.getTimeStamp());
                if (isUserPackage(e.getPackageName()) && !e.getPackageName().equals(ctx.getPackageName())) {
                    lastUserApp = e.getPackageName();
                }
            } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND || type == 23 /* ACTIVITY_STOPPED */) {
                resumed.remove(key);
            }
        }
        eventsUntil = now;

        // PICO keeps 2D system panels (home, settings) resumed on other virtual displays at the same time
        // as the VR app, so only user-installed apps count as "the app in front".
        String best = null;
        long bestAt = Long.MIN_VALUE;
        for (Map.Entry<String, Long> entry : resumed.entrySet()) {
            String pkg = entry.getKey().substring(0, entry.getKey().indexOf('/'));
            if (entry.getValue() > bestAt && isUserPackage(pkg) && !pkg.equals(ctx.getPackageName())) {
                bestAt = entry.getValue();
                best = pkg;
            }
        }
        return best;
    }

    boolean isAsleep() {
        return !((PowerManager) ctx.getSystemService(Context.POWER_SERVICE)).isInteractive();
    }

    private synchronized void forget(String pkg) {
        if (pkg.equals(lastUserApp)) lastUserApp = null;
    }

    /** The last user app that was in front, even if it is paused now; refreshed from usage events. */
    synchronized String lastUserApp() {
        foregroundPackage();
        return lastUserApp;
    }

    boolean isUserPackage(String pkg) {
        try {
            return isUserApp(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
