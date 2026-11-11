package org.dreamslab.weather;

import android.content.Context;

final class BuildConfigInfo {
    static String version(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
