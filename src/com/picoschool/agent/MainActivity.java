package com.picoschool.agent;

import android.app.Activity;
import android.os.Bundle;

/**
 * Invisible starter. PICO blocks starting third-party processes by broadcast, so setup.ps1 starts the
 * agent with "am start" on this activity; it starts the service and closes without showing anything.
 */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AgentService.start(this);
        finish();
    }
}
