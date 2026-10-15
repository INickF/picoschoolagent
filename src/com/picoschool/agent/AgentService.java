package com.picoschool.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

/**
 * Foreground service that keeps the control API alive on port {@link #PORT}.
 *
 * <pre>
 * GET  /api/status   agent, device, battery, asleep, foreground app, permissions
 * GET  /api/apps     launchable apps
 * POST /api/launch   {"package": "...", "closePrevious": true}
 * POST /api/close    {"package": "..."} or {} for the foreground app, {"all": true} for every app
 * </pre>
 */
public final class AgentService extends Service {
    static final int PORT = 3350;
    static final String VERSION = "1.4";
    private static final String TAG = "PicoAgent";
    private static final String CHANNEL = "agent";

    private HttpServer server;
    private AppControl apps;

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, AgentService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        apps = new AppControl(this);
        server = new HttpServer(PORT, this::route);
        server.start();
        Log.i(TAG, "agent started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private JSONObject route(String method, String path, JSONObject body) throws Exception {
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        JSONObject out = new JSONObject().put("ok", true);

        if (path.equals("/api/status") && (get || post)) {
            return status(out);
        }
        if (path.equals("/api/apps") && (get || post)) {
            return out.put("apps", apps.listApps());
        }
        if (path.equals("/api/launch") && post) {
            String pkg = body.optString("package", "");
            if (pkg.isEmpty()) throw new AgentException("missing_package");
            apps.launch(pkg, body.optBoolean("closePrevious", true));
            Log.i(TAG, "launch " + pkg);
            return out.put("package", pkg);
        }
        if (path.equals("/api/close") && post) {
            String closed;
            if (body.optBoolean("all", false)) {
                closed = apps.closeAll();
            } else {
                String pkg = body.optString("package", "");
                closed = apps.close(pkg.isEmpty() ? null : pkg);
            }
            Log.i(TAG, "close " + closed);
            return out.put("package", closed == null ? JSONObject.NULL : closed);
        }
        return null;
    }

    private JSONObject status(JSONObject out) throws Exception {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        // A sleeping headset (nobody wearing it) pauses its VR app; still report it as the current app.
        boolean asleep = apps.isAsleep();
        String fg = apps.foregroundPackage();
        if (fg == null && asleep) fg = apps.lastUserApp();

        JSONObject perms = new JSONObject()
                .put("overlay", apps.canDrawOverlays())
                .put("usage", apps.hasUsageAccess());
        JSONObject foreground = fg == null ? null
                : new JSONObject().put("package", fg).put("label", apps.label(fg));

        return out
                .put("version", VERSION)
                .put("model", Build.MODEL)
                .put("name", name == null ? Build.MODEL : name)
                .put("android", Build.VERSION.RELEASE)
                .put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("charging", bm.isCharging())
                .put("asleep", asleep)
                .put("foreground", foreground == null ? JSONObject.NULL : foreground)
                .put("permissions", perms);
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Agent", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("PICO School Agent")
                .setContentText("Remote app control is active")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true)
                .build();
    }
}
