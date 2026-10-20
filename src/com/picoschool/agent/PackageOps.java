package com.picoschool.agent;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installs APKs sent from the PC and deletes apps, one job at a time.
 *
 * <p>A normal app can only do this through the system prompt ("Install?" / "Delete?"). The agent opens that
 * prompt itself and {@link AgentAccessibilityService} presses the confirm button. If PICO doesn't let it,
 * the prompt stays up for the wearer and the job reports {@code needsWearer}.
 *
 * <p>The APK is not uploaded through the agent's HTTP server: the PC serves the file and the agent downloads it.
 */
final class PackageOps {
    private static final String TAG = "PicoAgent";
    private static final String ACTION_RESULT = "com.picoschool.agent.PACKAGE_RESULT";
    private static final String INSTALLER_PKG = "com.android.packageinstaller";
    // PICO drops a prompt opened while its VR shell is still waking ("no-history finish"), so after waking the
    // headset wait this long before showing it, and show it again if nothing was clicked after REPROMPT_MS.
    private static final long WAKE_SETTLE_MS = 2500;
    private static final long REPROMPT_MS = 5000;
    private static final int MAX_PROMPTS = 3;
    // No confirm click by then (all prompts shown): the wearer has to press the button.
    private static final long CLICK_GRACE_MS = MAX_PROMPTS * REPROMPT_MS + 2000;
    // Prompt not answered by then: give up on the job (the prompt may still be answered later).
    private static final long CONFIRM_TIMEOUT_MS = 3 * 60 * 1000;
    // A finished job stays in /api/status this long, so the PC's poll sees the result.
    private static final long KEEP_FINISHED_MS = 60 * 1000;
    private static final long POLL_MS = 500;
    // After a click, give the prompt this long to close before pressing again (the button can start disabled).
    private static final long RECLICK_MS = 3000;
    // Screen-on time while a prompt is open: it has to be on for the prompt to show and take the click.
    private static final long PROMPT_AWAKE_MS = 20000;

    private static volatile PackageOps instance;

    /** The running instance, or null while the agent service isn't up. Used by the accessibility service. */
    static PackageOps get() {
        return instance;
    }

    private final Context ctx;
    private final AppControl apps;
    private final PackageManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Job job;

    static final class Job {
        final String kind; // install | uninstall
        String pkg;
        String label;
        String state = "downloading"; // downloading | installing | confirming | done | failed
        int progress;
        String error;
        String message;
        long confirmSince;
        long clickedAt;
        Intent prompt; // the system's confirm screen, kept to show it again
        int prompts;
        long promptAt;
        long finishedAt;

        Job(String kind) {
            this.kind = kind;
        }

        boolean finished() {
            return state.equals("done") || state.equals("failed");
        }
    }

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onResult(intent);
        }
    };

    PackageOps(Context ctx, AppControl apps) {
        this.ctx = ctx.getApplicationContext();
        this.apps = apps;
        this.pm = this.ctx.getPackageManager();
        // Registered at runtime: PICO blocks broadcasts that would start a third-party process,
        // and this one only matters while the agent is running anyway.
        this.ctx.registerReceiver(resultReceiver, new IntentFilter(ACTION_RESULT));
        instance = this;
    }

    void stop() {
        instance = null;
        try {
            ctx.unregisterReceiver(resultReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        worker.shutdownNow();
    }

    // ---------- Requests ----------

    synchronized JSONObject install(String url) throws Exception {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new AgentException("missing_url");
        }
        Job j = begin("install");
        worker.execute(() -> runInstall(j, url));
        return toJson(j);
    }

    synchronized JSONObject uninstall(String pkg) throws Exception {
        if (pkg == null || pkg.isEmpty()) throw new AgentException("missing_package");
        if (pkg.equals(ctx.getPackageName()) || !apps.isUserPackage(pkg)) throw new AgentException("not_user_app");
        Job j = begin("uninstall");
        j.pkg = pkg;
        j.label = apps.label(pkg);
        j.state = "confirming";
        worker.execute(() -> {
            // Android stops the app itself when deleting it. Only the app in use is closed first (home + kill), so
            // the headset isn't left on a dead app; deleting any other app must not interrupt the wearer.
            if (pkg.equals(apps.foregroundPackage()) || pkg.equals(apps.lastUserApp())) {
                try {
                    apps.close(pkg);
                } catch (AgentException e) {
                    Log.w(TAG, "uninstall: could not close " + pkg + ": " + e.getMessage());
                }
            }
            apps.wakeUp(PROMPT_AWAKE_MS);
            ctx.getPackageManager().getPackageInstaller().uninstall(pkg, resultSender(j));
            startConfirmWatch(j);
        });
        return toJson(j);
    }

    private Job begin(String kind) throws AgentException {
        if (job != null && !job.finished()) throw new AgentException("busy");
        job = new Job(kind);
        return job;
    }

    /** The current or last job for /api/status, or null. */
    synchronized JSONObject status() throws Exception {
        if (job == null) return null;
        if (job.finished() && System.currentTimeMillis() - job.finishedAt > KEEP_FINISHED_MS) {
            job = null;
            return null;
        }
        return toJson(job);
    }

    private static JSONObject toJson(Job j) throws Exception {
        JSONObject o = new JSONObject()
                .put("kind", j.kind)
                .put("state", j.state)
                .put("progress", j.progress)
                .put("package", j.pkg == null ? JSONObject.NULL : j.pkg)
                .put("label", j.label == null ? JSONObject.NULL : j.label)
                .put("needsWearer", j.state.equals("confirming") && j.clickedAt == 0
                        && j.confirmSince > 0 && System.currentTimeMillis() - j.confirmSince > CLICK_GRACE_MS);
        if (j.error != null) o.put("error", j.error);
        if (j.message != null) o.put("message", j.message);
        return o;
    }

    // ---------- Install ----------

    private void runInstall(Job j, String url) {
        File apk = new File(ctx.getCacheDir(), "install.apk");
        try {
            download(j, url, apk);

            PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), 0);
            if (info == null) {
                fail(j, "invalid_apk", null);
                return;
            }
            info.applicationInfo.sourceDir = apk.getPath();
            info.applicationInfo.publicSourceDir = apk.getPath();
            synchronized (this) {
                j.pkg = info.packageName;
                j.label = String.valueOf(pm.getApplicationLabel(info.applicationInfo));
                j.state = "installing";
            }

            PackageInstaller installer = pm.getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(info.packageName);
            params.setSize(apk.length());
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                apps.wakeUp(PROMPT_AWAKE_MS);
                session.commit(resultSender(j));
            }
            Log.i(TAG, "install committed: " + info.packageName);
            startConfirmWatch(j);
        } catch (AgentException e) {
            fail(j, e.getMessage(), null);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            fail(j, "install_failed", String.valueOf(e.getMessage()));
        }
    }

    private void download(Job j, String url, File to) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        try {
            if (conn.getResponseCode() != 200) throw new AgentException("download_failed");
            long total = conn.getContentLengthLong();
            long done = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        synchronized (this) {
                            j.progress = (int) (done * 100 / total);
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw new AgentException("download_failed");
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + url, e);
            throw new AgentException("download_failed");
        } finally {
            conn.disconnect();
        }
    }

    // ---------- Results ----------

    private IntentSender resultSender(Job j) {
        Intent intent = new Intent(ACTION_RESULT).setPackage(ctx.getPackageName());
        // The installer fills in the status extras, so the PendingIntent must be mutable.
        return PendingIntent.getBroadcast(ctx, j.kind.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE).getIntentSender();
    }

    private void onResult(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Job j;
        synchronized (this) {
            j = job;
        }
        if (j == null || j.finished()) return;
        Log.i(TAG, j.kind + " result " + status + " " + message);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                fail(j, j.kind + "_failed", "no confirmation intent");
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            synchronized (this) {
                j.state = "confirming";
                j.prompt = confirm;
                j.promptAt = System.currentTimeMillis(); // keeps the watch from re-prompting meanwhile
                if (j.confirmSince == 0) j.confirmSince = System.currentTimeMillis();
            }
            // Off the main thread: waking waits for the headset to settle.
            worker.execute(() -> showPrompt(j));
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            synchronized (this) {
                j.state = "done";
                j.progress = 100;
                j.finishedAt = System.currentTimeMillis();
            }
            cleanup();
            return;
        }
        String code = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "cancelled"
                : status == PackageInstaller.STATUS_FAILURE_STORAGE ? "no_space"
                : status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? "incompatible"
                : status == PackageInstaller.STATUS_FAILURE_CONFLICT ? "conflict"
                : j.kind + "_failed";
        fail(j, code, message);
    }

    /** Wakes the headset and opens the confirm screen; called again by the watch when it got dropped. */
    private void showPrompt(Job j) {
        Intent prompt;
        synchronized (this) {
            if (j != job || j.finished() || j.clickedAt > 0 || j.prompt == null) return;
            prompt = j.prompt;
        }
        if (apps.wakeUp(PROMPT_AWAKE_MS)) pause(WAKE_SETTLE_MS);
        try {
            ctx.startActivity(prompt);
        } catch (RuntimeException e) {
            fail(j, j.kind + "_failed", "prompt refused: " + e.getMessage());
            return;
        }
        synchronized (this) {
            j.prompts++;
            j.promptAt = System.currentTimeMillis();
        }
        Log.i(TAG, j.kind + ": prompt shown (" + j.prompts + ")");
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void fail(Job j, String code, String message) {
        synchronized (this) {
            if (j.finished()) return;
            j.state = "failed";
            j.error = code;
            j.message = message;
            j.finishedAt = System.currentTimeMillis();
        }
        Log.w(TAG, j.kind + " failed: " + code + " " + message);
        cleanup();
    }

    private void cleanup() {
        new File(ctx.getCacheDir(), "install.apk").delete();
    }

    // ---------- Confirming ----------

    /**
     * While a prompt is waiting, ask the accessibility service to press its confirm button every
     * {@link #POLL_MS}. Polling as well as reacting to events, because PICO renders the prompt inside VR and
     * the events alone aren't guaranteed.
     */
    private void startConfirmWatch(Job j) {
        synchronized (this) {
            if (j.confirmSince == 0) j.confirmSince = System.currentTimeMillis();
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                synchronized (PackageOps.this) {
                    if (j != job || j.finished()) return;
                    long now = System.currentTimeMillis();
                    if (now - j.confirmSince > CONFIRM_TIMEOUT_MS) {
                        fail(j, "not_confirmed", null);
                        return;
                    }
                    if (j.prompt != null && j.clickedAt == 0 && j.prompts < MAX_PROMPTS
                            && j.promptAt > 0 && now - j.promptAt > REPROMPT_MS) {
                        j.promptAt = now;
                        worker.execute(() -> showPrompt(j));
                    }
                }
                tryConfirm();
                main.postDelayed(this, POLL_MS);
            }
        });
    }

    /** True while a job's prompt is open and still needs the confirm click. */
    synchronized boolean waiting() {
        return job != null && !job.finished() && job.state.equals("confirming");
    }

    /** Called from the accessibility service on installer events, and from the poll above. */
    void tryConfirm() {
        Job j;
        synchronized (this) {
            j = job;
            if (j == null || j.finished()) return;
            if (j.clickedAt > 0 && System.currentTimeMillis() - j.clickedAt < RECLICK_MS) return;
        }
        AgentAccessibilityService a11y = AgentAccessibilityService.get();
        if (a11y == null) return;
        if (a11y.clickConfirm(INSTALLER_PKG, j.kind.equals("uninstall"))) {
            synchronized (this) {
                j.clickedAt = System.currentTimeMillis();
            }
            Log.i(TAG, j.kind + ": confirmed " + j.pkg);
        }
    }
}
