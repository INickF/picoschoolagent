package com.picoschool.agent;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps the agent alive across reboots. PICO blocks third-party processes started by broadcasts
 * (including BOOT_COMPLETED), but the system itself binds enabled accessibility services at boot and
 * rebinds them if their process dies. setup.ps1 enables this service; it starts {@link AgentService}.
 * Being a bound accessibility service also lets the agent start activities from the background.
 *
 * <p>It also tries to press the confirm button in the install / delete prompt that {@link PackageOps} opened,
 * and touches nothing else: events come only from the package installer (res/xml/accessibility.xml) and are
 * ignored unless a job is waiting for its prompt. On PICO 4 (Android 10) this is best effort: PICO shows the
 * prompt on a virtual display, which accessibility doesn't reach (verified while the headset is asleep), so
 * normally the wearer presses the button and the PC shows "confirm on the headset".
 */
public final class AgentAccessibilityService extends AccessibilityService {
    private static final String TAG = "PicoAgent";
    // Confirm buttons of the installer prompts, as seen on PICO 4 (Android 10).
    private static final String[] CONFIRM_IDS = {
            "com.android.packageinstaller:id/install_button_install", // PICO 4 install prompt
            "android:id/button1", // delete prompt (alert dialog)
            "com.android.packageinstaller:id/ok_button"};
    // Fallback when PICO's build uses other ids. Lower case, compared with the whole button text.
    private static final String[] INSTALL_TEXTS = {"install", "update", "установить", "обновить", "安装", "更新"};
    private static final String[] DELETE_TEXTS = {"ok", "uninstall", "delete", "ок", "удалить", "确定", "卸载"};

    private static volatile AgentAccessibilityService instance;
    // Root of the last installer window seen in an event. PICO shows 2D panels such as the installer on
    // virtual displays, which getWindows() doesn't cover on Android 10; their events still arrive here.
    private volatile AccessibilityNodeInfo eventRoot;

    static AgentAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AgentService.start(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        PackageOps ops = PackageOps.get();
        if (ops == null || !ops.waiting()) return;
        AccessibilityNodeInfo node = event.getSource();
        if (node != null) {
            while (true) {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent == null) break;
                node = parent;
            }
            eventRoot = node;
        }
        ops.tryConfirm();
    }

    @Override
    public void onInterrupt() {
    }

    /** Presses the confirm button in a window of {@code installerPkg}. Returns true if a click was sent. */
    boolean clickConfirm(String installerPkg, boolean uninstall) {
        for (AccessibilityNodeInfo root : roots()) {
            if (root == null || !installerPkg.equals(String.valueOf(root.getPackageName()))) continue;
            AccessibilityNodeInfo button = findById(root);
            if (button == null) button = findByText(root, uninstall ? DELETE_TEXTS : INSTALL_TEXTS);
            if (button == null) continue;
            if (!button.isEnabled()) return false; // the install button starts disabled for a moment
            boolean clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "confirm click on " + button.getViewIdResourceName() + " \"" + button.getText() + "\": " + clicked);
            return clicked;
        }
        return false;
    }

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) out.add(w.getRoot());
        } catch (RuntimeException e) {
            Log.w(TAG, "getWindows failed", e);
        }
        out.add(getRootInActiveWindow());
        AccessibilityNodeInfo r = eventRoot;
        if (r != null && r.refresh()) out.add(r);
        return out;
    }

    private static AccessibilityNodeInfo findById(AccessibilityNodeInfo root) {
        for (String id : CONFIRM_IDS) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
                if (n.isVisibleToUser() && n.isClickable()) return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findByText(AccessibilityNodeInfo node, String[] texts) {
        if (node == null) return null;
        if (node.isClickable() && node.isVisibleToUser() && node.getText() != null) {
            String text = node.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (String t : texts) {
                if (text.equals(t)) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findByText(node.getChild(i), texts);
            if (hit != null) return hit;
        }
        return null;
    }
}
