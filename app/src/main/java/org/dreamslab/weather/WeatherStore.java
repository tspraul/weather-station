package org.dreamslab.weather;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Keeps the newest reading in memory and saves one reading every 10 minutes
 * to log.csv (time,temp,humidity,pressure,uv,light). Sky choices made by
 * students are kept in SharedPreferences, one per date.
 */
public final class WeatherStore {
    public static final long LOG_SLOT_MS = 10 * 60 * 1000L;

    private static WeatherStore instance;

    public static synchronized WeatherStore get(Context ctx) {
        if (instance == null) instance = new WeatherStore(ctx.getApplicationContext());
        return instance;
    }

    private final File logFile;
    private final SharedPreferences prefs;

    // Newest reading (NaN = none yet)
    private double t = Double.NaN, h = Double.NaN, p = Double.NaN, u = Double.NaN, l = Double.NaN;
    private long latestTime = 0;
    private long lastLoggedSlot = -1;

    // Connection status shown in the app
    private String state = "starting";
    private String boardName = "";

    private WeatherStore(Context ctx) {
        logFile = new File(ctx.getFilesDir(), "log.csv");
        prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
        lastLoggedSlot = readLastSlot();
    }

    private long readLastSlot() {
        if (!logFile.exists()) return -1;
        String last = null;
        try (BufferedReader r = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = r.readLine()) != null) if (line.length() > 0) last = line;
        } catch (Exception ignored) { }
        if (last == null) return -1;
        try {
            return Long.parseLong(last.split(",")[0]) / LOG_SLOT_MS;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Parse one line from the micro:bit, e.g. "T:23.5,H:48.2,P:101.3,U:0.12,L:530". */
    public synchronized boolean addLine(String line) {
        double nt = Double.NaN, nh = Double.NaN, np = Double.NaN, nu = Double.NaN, nl = Double.NaN;
        for (String part : line.trim().split(",")) {
            int c = part.indexOf(':');
            if (c < 1) continue;
            String k = part.substring(0, c).trim();
            double v;
            try {
                v = Double.parseDouble(part.substring(c + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            switch (k) {
                case "T": nt = v; break;
                case "H": nh = v; break;
                case "P": np = v; break;
                case "U": nu = v; break;
                case "L": nl = v; break;
                default: break;
            }
        }
        if (Double.isNaN(nt) || Double.isNaN(nh)) return false;
        // A board that is not sending real readings gives all zeros.
        if (nt == 0 && nh == 0 && (Double.isNaN(np) || np == 0)) return false;
        // Ignore impossible values.
        if (nt < -50 || nt > 70 || nh < 0 || nh > 100) return false;

        t = nt; h = nh; p = np; u = nu; l = nl;
        latestTime = System.currentTimeMillis();

        long slot = latestTime / LOG_SLOT_MS;
        if (slot != lastLoggedSlot) {
            lastLoggedSlot = slot;
            appendLog(latestTime);
        }
        return true;
    }

    private void appendLog(long time) {
        try (FileWriter w = new FileWriter(logFile, true)) {
            w.write(time + "," + fmt(t) + "," + fmt(h) + "," + fmt(p) + "," + fmt(u) + "," + fmt(l) + "\n");
        } catch (Exception ignored) { }
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "";
        return String.valueOf(Math.round(v * 100) / 100.0);
    }

    public synchronized void setState(String s) { state = s; }
    public synchronized String getState() { return state; }
    public synchronized void setBoardName(String n) { boardName = n == null ? "" : n; }
    public synchronized long getLatestTime() { return latestTime; }
    public synchronized double getTemp() { return t; }

    public synchronized String statusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("board", boardName);
            o.put("savedBoard", getSavedBoard() != null);
            o.put("time", latestTime);
            o.put("now", System.currentTimeMillis());
            if (latestTime > 0) {
                o.put("t", t);
                o.put("h", h);
                if (!Double.isNaN(p)) o.put("p", p);
                if (!Double.isNaN(u)) o.put("u", u);
                if (!Double.isNaN(l)) o.put("l", l);
            }
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Readings from the last {@code days} days as [[time,t,h,p,u,l], ...]. */
    public synchronized String logJson(int days) {
        long since = System.currentTimeMillis() - days * 24L * 3600L * 1000L;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (logFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split(",", -1);
                    if (f.length < 6) continue;
                    long time;
                    try { time = Long.parseLong(f[0]); } catch (Exception e) { continue; }
                    if (time < since) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append('[').append(time);
                    for (int i = 1; i < 6; i++) sb.append(',').append(f[i].length() == 0 ? "null" : f[i]);
                    sb.append(']');
                }
            } catch (Exception ignored) { }
        }
        return sb.append(']').toString();
    }

    public synchronized String logCsv() {
        StringBuilder sb = new StringBuilder("time,temperature_C,humidity_pct,pressure_kPa,uv_index,light\n");
        if (logFile.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(logFile))) {
                String line;
                java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
                while ((line = r.readLine()) != null) {
                    int c = line.indexOf(',');
                    if (c < 1) continue;
                    try {
                        sb.append(df.format(new java.util.Date(Long.parseLong(line.substring(0, c)))))
                          .append(line.substring(c)).append('\n');
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    // ----- Student observations, one per date ("yyyy-MM-dd" -> "sunny", "breeze", ...) -----

    public synchronized String obsJson(String kind) {
        try {
            return new JSONObject(prefs.getString(kind, "{}")).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public synchronized void setObs(String kind, String date, String value) {
        try {
            JSONObject o = new JSONObject(prefs.getString(kind, "{}"));
            if (value == null || value.length() == 0) o.remove(date);
            else o.put(date, value);
            prefs.edit().putString(kind, o.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ----- Which micro:bit we listen to -----

    public String getSavedBoard() { return prefs.getString("board", null); }

    public void saveBoard(String address) { prefs.edit().putString("board", address).apply(); }

    public void forgetBoard() { prefs.edit().remove("board").apply(); }
}
