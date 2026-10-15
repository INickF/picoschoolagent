package com.picoschool.agent;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Keeps the agent alive across reboots. PICO blocks third-party processes started by broadcasts
 * (including BOOT_COMPLETED), but the system itself binds enabled accessibility services at boot and
 * rebinds them if their process dies. setup.ps1 enables this service; it only starts {@link AgentService}.
 * Being a bound accessibility service also lets the agent start activities from the background.
 */
public final class AgentAccessibilityService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AgentService.start(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
