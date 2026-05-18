package com.j2cheng.cam2stream;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Auto-starts the streaming service on device boot. This is the primary
 * entry point: the app has no Activity, so without this receiver (or an
 * explicit ADB invocation) the service would never run.
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Intent svc = new Intent(context, StreamerService.class)
                    .setAction(StreamerService.ACTION_START);
            ContextCompat.startForegroundService(context, svc);
        }
    }
}
