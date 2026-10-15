package com.picoschool.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the agent after boot and after the agent itself is updated. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AgentService.start(context);
    }
}
