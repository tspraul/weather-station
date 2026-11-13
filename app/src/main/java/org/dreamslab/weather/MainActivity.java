package org.dreamslab.weather;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 7;
    private WebView web;

    static String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS);
        return p.toArray(new String[0]);
    }

    static boolean hasBluetoothPermissions(Context ctx) {
        if (Build.VERSION.SDK_INT >= 31) {
            return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(this), "Weather");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN);
        WeatherService.start(this);
    }

    private void askPermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            WeatherService.start(this);
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        WeatherService.start(this);
    }

    @Override
    public void onBackPressed() {
        // Leave the app running behind the home screen instead of closing it.
        moveTaskToBack(true);
    }

    /** Functions the page can call as Weather.something(). */
    public class Bridge {
        private final Context ctx;
        Bridge(Context c) { ctx = c.getApplicationContext(); }

        @JavascriptInterface public String status() { return WeatherStore.get(ctx).statusJson(); }
        @JavascriptInterface public String log(int days) { return WeatherStore.get(ctx).logJson(days); }
        @JavascriptInterface public String sky() { return WeatherStore.get(ctx).obsJson("sky"); }
        @JavascriptInterface public void setSky(String date, String v) { WeatherStore.get(ctx).setObs("sky", date, v); }
        @JavascriptInterface public String wind() { return WeatherStore.get(ctx).obsJson("wind"); }
        @JavascriptInterface public void setWind(String date, String v) { WeatherStore.get(ctx).setObs("wind", date, v); }
        @JavascriptInterface public String csv() { return WeatherStore.get(ctx).logCsv(); }
        @JavascriptInterface public String version() { return BuildConfigInfo.version(ctx); }

        @JavascriptInterface public void forgetBoard() { WeatherService.forget(ctx); }

        @JavascriptInterface public void fixPermissions() {
            runOnUiThread(new Runnable() { @Override public void run() { askPermissions(); } });
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface public void turnOnBluetooth() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                } catch (Exception ignored) { }
            }});
        }
    }
}
