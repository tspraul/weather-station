package org.dreamslab.weather;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts listening again after the panel is switched on or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        WeatherService.start(context);
    }
}
