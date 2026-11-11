package org.dreamslab.weather;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Runs in the background: finds the micro:bit weather board, stays connected,
 * and passes every line it sends to WeatherStore. Reconnects on its own.
 */
@SuppressLint("MissingPermission")
public class WeatherService extends Service {
    static final UUID UART_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    // The micro:bit sends on this one (it uses "indicate").
    static final UUID UART_TX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final String ACTION_FORGET = "org.dreamslab.weather.FORGET";
    static final String CHANNEL = "weather";
    static final int NOTE_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WeatherStore store;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private boolean connecting = false;
    private boolean connected = false;
    private long connectStarted = 0;
    private long lastData = 0;
    private final StringBuilder lineBuf = new StringBuilder();
    private final Set<String> notWeatherBoards = new HashSet<>();

    public static void start(Context ctx) {
        if (!MainActivity.hasBluetoothPermissions(ctx)) return;
        Intent i = new Intent(ctx, WeatherService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) { }
    }

    public static void forget(Context ctx) {
        Intent i = new Intent(ctx, WeatherService.class);
        i.setAction(ACTION_FORGET);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = WeatherStore.get(this);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        goForeground("Starting…");
        handler.postDelayed(watchdog, 5000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(noteText());
        if (intent != null && ACTION_FORGET.equals(intent.getAction())) {
            store.forgetBoard();
            store.setBoardName("");
            notWeatherBoards.clear();
            closeGatt();
        }
        tick();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGatt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- main loop: every 15 s make sure we are connected ----------

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            tick();
            handler.postDelayed(this, 15000);
        }
    };

    private void tick() {
        long now = System.currentTimeMillis();
        if (!MainActivity.hasBluetoothPermissions(this)) { store.setState("no_permission"); return; }
        if (adapter == null) { store.setState("no_bluetooth"); return; }
        if (!adapter.isEnabled()) { store.setState("bt_off"); stopScan(); closeGatt(); return; }

        if (connected) {
            // The board sends every 10 s. Nothing for 60 s means the link is stuck.
            if (now - lastData > 60000) {
                store.setState("reconnecting");
                closeGatt();
            } else {
                return;
            }
        }
        if (connecting) {
            if (now - connectStarted > 30000) closeGatt(); else return;
        }
        if (scanning) return;

        String saved = store.getSavedBoard();
        if (saved != null) connect(adapter.getRemoteDevice(saved));
        else startScan();
        updateNote();
    }

    // ---------- finding the board ----------

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = null;
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null) {
                try { name = d.getName(); } catch (Exception ignored) { }
            }
            if (name == null || !name.toLowerCase(Locale.US).contains("micro:bit")) return;
            if (notWeatherBoards.contains(d.getAddress())) return;
            stopScan();
            store.setBoardName(name);
            connect(d);
        }
    };

    private void startScan() {
        if (adapter == null) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        store.setState("searching");
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            // Pause the search after 25 s; the watchdog starts it again.
            handler.postDelayed(new Runnable() {
                @Override public void run() { if (scanning) stopScan(); }
            }, 25000);
        } catch (Exception e) {
            scanning = false;
        }
    }

    private void stopScan() {
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
        scanning = false;
    }

    // ---------- connecting and receiving ----------

    private void connect(BluetoothDevice d) {
        closeGatt();
        connecting = true;
        connectStarted = System.currentTimeMillis();
        store.setState("connecting");
        try {
            if (Build.VERSION.SDK_INT >= 23) gatt = d.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else gatt = d.connectGatt(this, false, gattCallback);
        } catch (Exception e) {
            connecting = false;
        }
    }

    private void closeGatt() {
        connected = false;
        connecting = false;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, int status, int newState) {
            handler.post(new Runnable() { @Override public void run() {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    store.setState("connecting");
                    g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    closeGatt();
                    store.setState("reconnecting");
                    updateNote();
                }
            }});
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, int status) {
            handler.post(new Runnable() { @Override public void run() {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(UART_SERVICE);
                BluetoothGattCharacteristic tx = s == null ? null : s.getCharacteristic(UART_TX);
                if (tx == null) {
                    // Some other micro:bit (not running the weather program). Skip it.
                    notWeatherBoards.add(g.getDevice().getAddress());
                    if (g.getDevice().getAddress().equals(store.getSavedBoard())) store.forgetBoard();
                    closeGatt();
                    store.setState("searching");
                    return;
                }
                g.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
                if (cccd != null) {
                    boolean indicate = (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    byte[] value = indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                            : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                    writeDescriptor(g, cccd, value);
                }
                connecting = false;
                connected = true;
                lastData = System.currentTimeMillis();
                store.saveBoard(g.getDevice().getAddress());
                try {
                    String n = g.getDevice().getName();
                    if (n != null) store.setBoardName(n);
                } catch (Exception ignored) { }
                store.setState("connected");
                updateNote();
            }});
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue();
            if (v != null) handler.post(new Runnable() { @Override public void run() { onBytes(v); } });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, final byte[] v) {
            if (v != null) handler.post(new Runnable() { @Override public void run() { onBytes(v); } });
        }
    };

    @SuppressWarnings("deprecation")
    private void writeDescriptor(BluetoothGatt g, BluetoothGattDescriptor d, byte[] value) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value);
        } else {
            d.setValue(value);
            g.writeDescriptor(d);
        }
    }

    private void onBytes(byte[] v) {
        lastData = System.currentTimeMillis();
        if (!connected) { connected = true; connecting = false; store.setState("connected"); }
        lineBuf.append(new String(v, StandardCharsets.UTF_8));
        int nl;
        while ((nl = indexOfNewline(lineBuf)) >= 0) {
            String line = lineBuf.substring(0, nl);
            lineBuf.delete(0, nl + 1);
            if (line.trim().length() > 0 && store.addLine(line)) updateNote();
        }
        if (lineBuf.length() > 500) lineBuf.setLength(0);
    }

    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char ch = sb.charAt(i);
            if (ch == '\n' || ch == '\r') return i;
        }
        return -1;
    }

    // ---------- the small notification that keeps the service alive ----------

    private String noteText() {
        String s = store.getState();
        if ("connected".equals(s) && store.getLatestTime() > 0) {
            return "Connected · " + Math.round(store.getTemp()) + " °C";
        }
        if ("connected".equals(s)) return "Connected to the weather board";
        if ("bt_off".equals(s)) return "Bluetooth is off";
        if ("no_permission".equals(s)) return "Open the app to allow Bluetooth";
        return "Looking for the weather board…";
    }

    private void updateNote() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(NOTE_ID, buildNote(noteText())); } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNote(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Weather board", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_LOW);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Weather Station")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void goForeground(String text) {
        Notification n = buildNote(text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTE_ID, n);
            }
        } catch (Exception e) {
            // Permission missing: run without the foreground notice until the app is opened.
        }
    }

}
