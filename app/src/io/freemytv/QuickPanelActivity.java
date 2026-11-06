package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeProvider;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quick settings panel opened from the home screen's settings icon: glass tiles for power, sleep timer,
 * network, bluetooth, sound, picture, inputs and home settings. Slides in from the right.
 */
public class QuickPanelActivity extends Activity {
    private static final class Tile {
        final String label, symbol;
        final int color;
        final Intent[] intents;

        Tile(String label, String symbol, int color, Intent... intents) {
            this.label = label;
            this.symbol = symbol;
            this.color = color;
            this.intents = intents;
        }
    }

    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final int POWER = 0, TIMER = 1, NETWORK = 2, BLUETOOTH = 3, INPUTS = 6, HOME_SETTINGS = 7;
    private static final float W = 600, H = 800, MARGIN = 40;

    private PanelView view;
    private Click click;
    private volatile List<WifiNetwork> wifiScan;
    private BluetoothProfile a2dpProxy, hidProxy;
    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private final Map<String, BluetoothDevice> btDeviceMap = new HashMap<>();
    private boolean discovering;
    private BroadcastReceiver btReceiver;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        click = new Click(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = dm.widthPixels / 1920f;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Math.round(MARGIN * k);
        lp.y = Math.round(MARGIN * k);
        lp.width = Math.round(W * k);
        lp.height = Math.round(H * k);
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        overridePendingTransition(0, 0);
        view = new PanelView(this, k, tiles());
        setContentView(view);
        startWifiScan();
        setupBluetoothProxies();
    }

    private Tile[] tiles() {
        return new Tile[] {
            new Tile(Ui.tr("Power"), "bolt", 0xFFFF453A),
            new Tile(Ui.tr("Timer"), "bedtime", 0xFFBF5AF2),
            new Tile(Ui.tr("Network"), "wifi", 0xFF0A84FF),
            new Tile(Ui.tr("Bluetooth"), "bluetooth", 0xFF3F7CFF),
            new Tile(Ui.tr("Sound"), "speaker", 0xFFFF375F,
                    new Intent("com.android.tv.settings.SOUND").setPackage(TV_SETTINGS),
                    new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS").setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_SOUND_SETTINGS)),
            new Tile(Ui.tr("Picture"), "tv_settings", 0xFFFF9F0A,
                    new Intent("com.android.tv.settings.DISPLAY").setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            new Tile(Ui.tr("Inputs"), "hdmi", 0xFF30B0C7),
            new Tile(Ui.tr("Home Settings"), "home", 0xFF8E8E9A),
        };
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.close();
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        click.release();
        stopDiscovery();
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null) {
            if (a2dpProxy != null) bt.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
            if (hidProxy != null) bt.closeProfileProxy(4, hidProxy); // HID_HOST = 4
        }
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean open(Intent... intents) {
        for (Intent intent : intents) {
            try {
                startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("quick panel: couldn't open " + intent + ": " + e.getMessage());
            }
        }
        return false;
    }

    private void showPowerDialog() {
        String[] items = {Ui.tr("Sleep"), Ui.tr("Restart"), Ui.tr("Power Off")};
        new AlertDialog.Builder(this)
            .setTitle(Ui.tr("Power"))
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0: // Sleep
                        new Thread(() -> {
                            try (Adb adb = Adb.connect(this, 10_000, null)) {
                                adb.shell("input keyevent KEYCODE_SLEEP");
                            } catch (IOException e) {
                                AppLog.w("quick panel: sleep: " + e.getMessage());
                            }
                        }).start();
                        finish();
                        break;
                    case 1: // Restart
                        new Thread(() -> {
                            try (Adb adb = Adb.connect(this, 10_000, null)) {
                                adb.shell("reboot");
                            } catch (IOException e) {
                                AppLog.w("quick panel: restart: " + e.getMessage());
                            }
                        }).start();
                        break;
                    case 2: // Power Off
                        new Thread(() -> {
                            try (Adb adb = Adb.connect(this, 10_000, null)) {
                                adb.shell("reboot -p");
                            } catch (IOException e) {
                                AppLog.w("quick panel: power off: " + e.getMessage());
                            }
                        }).start();
                        break;
                }
            })
            .setNegativeButton(Ui.tr("Cancel"), null)
            .show();
    }

    private void setupBluetoothProxies() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) return;
            BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy;
                    else hidProxy = proxy;
                }
                @Override public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = null;
                    else hidProxy = null;
                }
            };
            bt.getProfileProxy(this, listener, BluetoothProfile.A2DP);
            bt.getProfileProxy(this, listener, 4); // HID_HOST
        } catch (Exception e) {
            AppLog.w("quick panel: bt proxy: " + e.getMessage());
        }
    }

    private void startDiscovery() {
        if (discovering) return;
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt == null || !bt.isEnabled()) return;
            discoveredDevices.clear();
            btReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                        BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (dev == null) return;
                        String name = dev.getName();
                        if (name == null || name.isEmpty()) return;
                        if (dev.getBondState() == BluetoothDevice.BOND_BONDED) return;
                        for (BluetoothDevice d : discoveredDevices) if (d.getAddress().equals(dev.getAddress())) return;
                        discoveredDevices.add(dev);
                        if (view != null && view.listOpen && view.listOrigin == BLUETOOTH) {
                            view.listItems = bluetoothRows();
                            view.invalidate();
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            registerReceiver(btReceiver, filter);
            bt.startDiscovery();
            discovering = true;
        } catch (Exception e) {
            AppLog.w("quick panel: bt discovery: " + e.getMessage());
        }
    }

    private void stopDiscovery() {
        if (!discovering) return;
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null) bt.cancelDiscovery();
        } catch (Exception e) { /* */ }
        if (btReceiver != null) {
            try { unregisterReceiver(btReceiver); } catch (Exception e) { /* */ }
            btReceiver = null;
        }
        discovering = false;
    }

    private void connectBluetooth(BluetoothDevice dev) {
        boolean done = false;
        if (a2dpProxy != null) done = callProfileMethod(a2dpProxy, "connect", dev);
        if (!done && hidProxy != null) done = callProfileMethod(hidProxy, "connect", dev);
        if (!done) {
            try {
                Method m = dev.getClass().getMethod("createBond");
                m.invoke(dev);
            } catch (Exception e) {
                AppLog.w("quick panel: bt pair: " + e.getMessage());
            }
        }
    }

    private void disconnectBluetooth(BluetoothDevice dev) {
        if (a2dpProxy != null) callProfileMethod(a2dpProxy, "disconnect", dev);
        if (hidProxy != null) callProfileMethod(hidProxy, "disconnect", dev);
    }

    private static boolean callProfileMethod(BluetoothProfile proxy, String name, BluetoothDevice dev) {
        try {
            Method m = proxy.getClass().getMethod(name, BluetoothDevice.class);
            Object result = m.invoke(proxy, dev);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class WifiNetwork {
        final String ssid;
        final int rssi;
        final boolean secured;
        WifiNetwork(String ssid, int rssi, boolean secured) { this.ssid = ssid; this.rssi = rssi; this.secured = secured; }
    }

    private void startWifiScan() {
        new Thread(() -> {
            try (Adb adb = Adb.connect(this, 10_000, null)) {
                String out = adb.shell("cmd wifi list-scan-results");
                List<WifiNetwork> nets = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (String line : out.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("BSSID") || line.startsWith("----")) continue;
                    // BSSID  Freq  RSSI  Age(sec)  SSID  Flags
                    String[] parts = line.split("\\s+", 6);
                    if (parts.length < 5) continue;
                    String ssid = parts[4];
                    if (ssid.isEmpty() || "----".equals(ssid)) continue;
                    int rssi;
                    try { rssi = Integer.parseInt(parts[2]); } catch (NumberFormatException e) { continue; }
                    boolean secured = parts.length >= 6 && (parts[5].contains("WPA") || parts[5].contains("WEP") || parts[5].contains("RSN"));
                    if (seen.add(ssid)) nets.add(new WifiNetwork(ssid, rssi, secured));
                }
                wifiScan = nets;
            } catch (IOException e) {
                AppLog.w("quick panel: wifi scan: " + e.getMessage());
            }
        }).start();
    }

    private String connectedSsid() {
        try {
            WifiManager wm = getSystemService(WifiManager.class);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    String s = info.getSSID();
                    if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
                    if (!s.equals("<unknown ssid>")) return s;
                }
            }
        } catch (Exception e) { /* */ }
        return null;
    }

    private List<HomeApps.Item> networkRows() {
        List<HomeApps.Item> rows = new ArrayList<>();
        String connected = null;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            android.net.Network active = cm != null ? cm.getActiveNetwork() : null;
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    HomeApps.Item it = new HomeApps.Item();
                    it.label = Ui.tr("Ethernet");
                    it.symbol = "ethernet";
                    it.connected = true;
                    it.kind = HomeApps.DEVICE;
                    rows.add(it);
                }
            }
        } catch (Exception e) { /* */ }
        connected = connectedSsid();
        // Connected WiFi at the top
        if (connected != null) {
            HomeApps.Item it = new HomeApps.Item();
            it.label = connected;
            it.symbol = "wifi";
            it.connected = true;
            it.kind = HomeApps.DEVICE;
            rows.add(it);
        }
        // Available networks from the background scan
        List<WifiNetwork> scan = wifiScan;
        if (scan != null) {
            for (WifiNetwork net : scan) {
                if (net.ssid.equals(connected)) continue;
                HomeApps.Item it = new HomeApps.Item();
                it.label = net.ssid;
                it.symbol = "wifi";
                it.connected = false;
                it.kind = HomeApps.DEVICE;
                rows.add(it);
            }
        }
        HomeApps.Item settings = new HomeApps.Item();
        settings.label = Ui.tr("Wi-Fi Settings");
        settings.symbol = "settings";
        settings.connected = false;
        settings.kind = HomeApps.DEVICE;
        settings.intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        rows.add(settings);
        return rows;
    }

    private static boolean isDeviceConnected(BluetoothDevice dev) {
        try {
            Method m = dev.getClass().getMethod("isConnected");
            Object result = m.invoke(dev);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return false;
        }
    }

    private List<HomeApps.Item> bluetoothRows() {
        btDeviceMap.clear();
        List<HomeApps.Item> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.isEnabled()) {
                // Bonded devices: connected first, then paired but not connected
                Set<BluetoothDevice> bonded = bt.getBondedDevices();
                if (bonded != null) {
                    List<HomeApps.Item> connectedDevs = new ArrayList<>();
                    List<HomeApps.Item> pairedDevs = new ArrayList<>();
                    for (BluetoothDevice dev : bonded) {
                        HomeApps.Item it = btItem(dev, isDeviceConnected(dev));
                        seen.add(dev.getAddress());
                        if (it.connected) connectedDevs.add(it);
                        else pairedDevs.add(it);
                    }
                    rows.addAll(connectedDevs);
                    rows.addAll(pairedDevs);
                }
                // Discovered nearby devices (not yet paired)
                for (BluetoothDevice dev : discoveredDevices) {
                    if (seen.contains(dev.getAddress())) continue;
                    HomeApps.Item it = btItem(dev, false);
                    it.symbol = "bluetooth";
                    rows.add(it);
                }
            }
        } catch (Exception e) {
            AppLog.w("quick panel: bluetooth: " + e.getMessage());
        }
        HomeApps.Item settings = new HomeApps.Item();
        settings.label = Ui.tr("Bluetooth Settings");
        settings.symbol = "settings";
        settings.connected = false;
        settings.kind = HomeApps.DEVICE;
        settings.intent = new Intent("android.bluetooth.devicepicker.action.LAUNCH");
        rows.add(settings);
        return rows;
    }

    private HomeApps.Item btItem(BluetoothDevice dev, boolean connected) {
        HomeApps.Item it = new HomeApps.Item();
        it.label = dev.getName() != null ? dev.getName() : dev.getAddress();
        it.key = dev.getAddress();
        it.symbol = "bluetooth";
        it.connected = connected;
        it.kind = HomeApps.DEVICE;
        btDeviceMap.put(dev.getAddress(), dev);
        return it;
    }

    private static int mix(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    private final class PanelView extends View {
        private static final float PAD = 40, GAP = 14, TILE_H = 132, GRID_TOP = 184, ROW_H = 76, ROW_GAP = 10,
                CARD_R = 46, TILE_R = 30, CHIP_R = 25, SYMBOL = 24;
        private static final int ROWS_VISIBLE = 6;
        private static final int ROW_IDS = 1000;

        private final float k;
        private final Tile[] tiles;
        private List<HomeApps.Item> listItems;
        private String listTitle = "";
        private String listEmpty = "";
        private int listOrigin = -1;
        private boolean listOpen, closing;
        private int focus, listFocus;

        private final Spring show = new Spring(0.5f, 0.78f, 0);
        private final Spring lensX = new Spring(0.36f, 0.72f, 0), lensY = new Spring(0.36f, 0.72f, 0);
        private final Spring lensW = new Spring(0.36f, 0.8f, 0), lensH = new Spring(0.36f, 0.8f, 0);
        private final Spring press = new Spring(0.22f, 0.6f, 0);
        private final Spring page = new Spring(0.4f, 0.9f, 0);
        private long lastFrame;

        private final boolean night;
        private final int ink, focusInk, tileFill;
        private String dateText = "", clockText = "";
        private float clockBearing;
        private final Spoken spoken;

        private final Paint date, clock, label, rowLabel, title, empty, timerPaint, dot;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint layer = new Paint(), atop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), card = new RectF();
        private LinearGradient tint, sheen, cardRim, tileRim, lens;
        private RadialGradient[] glows;
        private final float[] glowX = {0.2f, 0.95f, 0.3f}, glowY = {0.15f, 0.55f, 0.95f};
        private final int[] chipColor = new int[16];
        private final LinearGradient[] chipShader = new LinearGradient[16];
        private int chips;
        private final float[] tileX = new float[8], tileY = new float[8], labelSize = new float[8];
        private float tileW;

        private final Runnable frame = new Runnable() {
            @Override public void run() {
                invalidate();
            }
        };
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                dateText = Ui.date(now);
                clockText = Ui.clock(now);
                clockBearing = Ui.leftBearing(clock, clockText);
                invalidate();
                postDelayed(this, 60_000 - now % 60_000);
            }
        };

        PanelView(Context c, float k, Tile[] tiles) {
            super(c);
            this.k = k;
            this.tiles = tiles;
            night = HomeData.get(c).isNight(System.currentTimeMillis());
            ink = night ? 0xFFFFFFFF : 0xFF1D1D1F;
            focusInk = night ? 0xFF1D1D1F : 0xFFFFFFFF;
            tileFill = night ? 0x17FFFFFF : 0x0D000000;
            date = Ui.text(Ui.medium, 24 * k, Ui.alpha(ink, 0.8f));
            clock = Ui.numbers(Ui.displaySemibold, 80 * k, ink);
            label = Ui.text(Ui.semibold, 24 * k, ink);
            rowLabel = Ui.text(Ui.semibold, 26 * k, ink);
            title = Ui.text(Ui.semibold, 30 * k, ink);
            empty = Ui.text(Ui.medium, 24 * k, Ui.alpha(ink, 0.7f));
            timerPaint = Ui.text(Ui.medium, 18 * k, Ui.alpha(ink, 0.7f));
            dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(0xFF30D158);
            rim.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    if (!listOpen) {
                        for (int i = 0; i < tiles.length; i++) out.add(i + 1);
                        return;
                    }
                    int first = firstRow();
                    int count = listItems != null ? listItems.size() : 0;
                    for (int i = first; i < Math.min(count, first + ROWS_VISIBLE); i++) out.add(ROW_IDS + i + 1);
                }
                @Override public String text(int id) {
                    if (id <= ROW_IDS) return id - 1 < tiles.length ? tiles[id - 1].label : "";
                    int i = id - ROW_IDS - 1;
                    if (listItems == null || i >= listItems.size()) return "";
                    return listItems.get(i).label + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", listItems.size());
                }
                @Override public boolean bounds(int id, Rect out) {
                    if (id <= ROW_IDS) {
                        int i = id - 1;
                        if (listOpen || i >= tiles.length) return false;
                        out.set(Math.round(tileX[i]), Math.round(tileY[i]), Math.round(tileX[i] + tileW),
                                Math.round(tileY[i] + TILE_H * k));
                        return true;
                    }
                    int i = id - ROW_IDS - 1;
                    if (!listOpen || listItems == null || i >= listItems.size()) return false;
                    float y = rowTop(i);
                    out.set(Math.round(PAD * k), Math.round(y), Math.round(getWidth() - PAD * k), Math.round(y + ROW_H * k));
                    return true;
                }
                @Override public int focused() { return listOpen ? ROW_IDS + listFocus + 1 : focus + 1; }
                @Override public void focus(int id) {
                    if (id > ROW_IDS) listFocus = id - ROW_IDS - 1;
                    else focus = id - 1;
                    aimLens(false);
                    invalidate();
                }
                @Override public void click(int id) { select(); }
            });
            atop.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            for (Tile t : tiles) Symbols.warm(c, t.symbol, SYMBOL * k);
            Symbols.warm(c, "tv", SYMBOL * k);
            Symbols.warm(c, "ethernet", SYMBOL * k);
            Symbols.warm(c, "settings", SYMBOL * k);
            show.target = 1;
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            card.set(0, 0, w, h);
            tint = night ? new LinearGradient(0, 0, 0, h, 0xF22C2C30, 0xF71C1C1E, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, h, 0xF7FBFBFD, 0xF2EBEBF0, Shader.TileMode.CLAMP);
            sheen = new LinearGradient(0, 0, 0, h * 0.42f, night ? 0x1FFFFFFF : 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            cardRim = new LinearGradient(0, 0, w, h, new int[] {0xB3FFFFFF, 0x1AFFFFFF, 0x14FFFFFF, 0x66FFFFFF},
                    new float[] {0, 0.35f, 0.65f, 1}, Shader.TileMode.CLAMP);
            tileRim = night ? new LinearGradient(0, 0, 0, TILE_H * k, 0x40FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, TILE_H * k, 0x0A000000, 0x1A000000, Shader.TileMode.CLAMP);
            lens = night ? new LinearGradient(0, 0, 0, TILE_H * k, 0xFFFFFFFF, 0xFFE4E8F4, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, TILE_H * k, 0xFF3A3A3C, 0xFF1D1D1F, Shader.TileMode.CLAMP);
            float r = 380 * k;
            glows = new RadialGradient[] {
                new RadialGradient(0, 0, r, 0x267A4DFF, 0x007A4DFF, Shader.TileMode.CLAMP),
                new RadialGradient(0, 0, r, 0x2022C4FF, 0x0022C4FF, Shader.TileMode.CLAMP),
                new RadialGradient(0, 0, r * 0.85f, 0x1AFF3D8B, 0x00FF3D8B, Shader.TileMode.CLAMP),
            };
            tileW = (w - 2 * PAD * k - GAP * k) / 2;
            float room = tileW - 44 * k;
            for (int i = 0; i < tiles.length; i++) {
                label.setTextSize(24 * k);
                labelSize[i] = 24 * k * Math.min(1, room / Math.max(1, label.measureText(tiles[i].label)));
            }
            for (int i = 0; i < tiles.length; i++) {
                tileX[i] = PAD * k + (i % 2) * (tileW + GAP * k);
                tileY[i] = GRID_TOP * k + (i / 2) * (TILE_H + GAP) * k;
            }
            aimLens(true);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            tick.run();
            spoken.readAgain();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            removeCallbacks(frame);
            super.onDetachedFromWindow();
        }

        void close() {
            if (closing) return;
            closing = true;
            show.target = 0;
            invalidate();
        }

        private void aimLens(boolean snap) {
            float x, y, w, h;
            if (listOpen) {
                x = PAD * k;
                y = rowTop(listFocus);
                w = getWidth() - 2 * PAD * k;
                h = ROW_H * k;
            } else {
                x = tileX[focus];
                y = tileY[focus];
                w = tileW;
                h = TILE_H * k;
            }
            if (snap) {
                lensX.snap(x); lensY.snap(y); lensW.snap(w); lensH.snap(h);
            } else {
                lensX.target = x; lensY.target = y; lensW.target = w; lensH.target = h;
            }
        }

        private int firstRow() {
            return Math.max(0, Math.min(listFocus - ROWS_VISIBLE + 1, listItems == null ? 0 : listItems.size() - ROWS_VISIBLE));
        }

        private float rowTop(int i) {
            return (GRID_TOP + 58) * k + (i - firstRow()) * (ROW_H + ROW_GAP) * k;
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = show.step(dt);
            moving |= lensX.step(dt) | lensY.step(dt) | lensW.step(dt) | lensH.step(dt);
            moving |= press.step(dt) | page.step(dt);
            if (closing && show.value < 0.03f) {
                finish();
                return;
            }
            if (!listOpen && page.value < 0.01f) listItems = null;

            float s = Math.max(0, show.value);
            c.save();
            c.translate((1 - s) * (getWidth() + MARGIN * k), 0);
            int alpha = Math.round(255 * Math.min(1, s * 1.4f));
            c.saveLayerAlpha(card, alpha);
            drawGlass(c);
            drawHeader(c);
            float p = page.value;
            if (p < 0.999f) drawTiles(c, 1 - p);
            if (listItems != null && p > 0.001f) drawList(c, p);
            c.restore();
            c.restore();
            if (moving) {
                postOnAnimation(frame);
            } else {
                lastFrame = 0;
            }
        }

        private void drawGlass(Canvas c) {
            float r = CARD_R * k;
            c.saveLayer(card, layer);
            fill.setAlpha(255);
            fill.setShader(tint);
            c.drawRoundRect(card, r, r, fill);
            fill.setShader(null);
            float w = card.width(), h = card.height();
            for (int i = 0; i < glows.length; i++) {
                c.save();
                c.translate(glowX[i] * w, glowY[i] * h);
                atop.setShader(glows[i]);
                c.drawCircle(0, 0, 400 * k, atop);
                c.restore();
            }
            atop.setShader(null);
            c.restore();
            fill.setShader(sheen);
            c.drawRoundRect(card, r, r, fill);
            fill.setShader(null);
            rim.setStrokeWidth(1.6f * k);
            rim.setShader(cardRim);
            rect.set(card);
            rect.inset(0.8f * k, 0.8f * k);
            c.drawRoundRect(rect, r, r, rim);
            rim.setShader(null);
        }

        private void drawHeader(Canvas c) {
            c.drawText(dateText, PAD * k, (PAD + 24) * k, date);
            c.drawText(clockText, PAD * k - clockBearing, (PAD + 116) * k, clock);
        }

        private void drawTiles(Canvas c, float a) {
            int saved = c.saveLayerAlpha(card, Math.round(255 * a));
            c.translate(0, (1 - a) * -24 * k);
            float r = TILE_R * k;
            for (int i = 0; i < tiles.length; i++) {
                rect.set(tileX[i], tileY[i], tileX[i] + tileW, tileY[i] + TILE_H * k);
                fill.setColor(tileFill);
                c.drawRoundRect(rect, r, r, fill);
                drawRim(c, rect, r);
            }
            if (!listOpen) drawLens(c, r);
            for (int i = 0; i < tiles.length; i++) {
                float on = nearness(tileX[i], tileY[i], tileW);
                float lift = on * (1 - 0.04f * press.value);
                float cx = tileX[i] + tileW / 2, cy = tileY[i] + TILE_H * k / 2;
                c.save();
                c.scale(1 + 0.03f * lift, 1 + 0.03f * lift, cx, cy);
                float chipX = tileX[i] + (22 + CHIP_R) * k, chipY = tileY[i] + (22 + CHIP_R) * k;
                drawChip(c, chipX, chipY, tiles[i].color, tiles[i].symbol, on);
                label.setColor(mix(ink, focusInk, on));
                label.setTextSize(labelSize[i]);
                c.drawText(tiles[i].label, tileX[i] + 22 * k, tileY[i] + TILE_H * k - 24 * k, label);
                // Timer countdown below the label
                if (i == TIMER) {
                    int mins = SleepTimer.minutesLeft(getContext());
                    if (mins > 0) {
                        timerPaint.setColor(mix(Ui.alpha(ink, 0.7f), focusInk, on));
                        String countdown = SleepTimer.duration(mins);
                        c.drawText(countdown, tileX[i] + 22 * k, tileY[i] + TILE_H * k - 6 * k, timerPaint);
                    }
                }
                c.restore();
            }
            c.restoreToCount(saved);
        }

        private void drawList(Canvas c, float a) {
            int saved = c.saveLayerAlpha(card, Math.round(255 * a));
            c.translate(0, (1 - a) * 24 * k);
            c.drawText(listTitle, PAD * k, (GRID_TOP + 30) * k, title);
            if (listItems.isEmpty()) {
                c.drawText(listEmpty, PAD * k, (GRID_TOP + 100) * k, empty);
                c.restoreToCount(saved);
                return;
            }
            float r = 24 * k, w = getWidth() - 2 * PAD * k;
            int first = firstRow();
            int last = Math.min(listItems.size(), first + ROWS_VISIBLE);
            for (int i = first; i < last; i++) {
                float y = rowTop(i);
                rect.set(PAD * k, y, PAD * k + w, y + ROW_H * k);
                fill.setColor(tileFill);
                c.drawRoundRect(rect, r, r, fill);
                drawRim(c, rect, r);
            }
            if (listOpen) drawLens(c, r);
            for (int i = first; i < last; i++) {
                HomeApps.Item it = listItems.get(i);
                float y = rowTop(i);
                float on = nearness(PAD * k, y, w);
                float dim = it.connected ? 1 : 0.5f;
                int chipColor = listOrigin == NETWORK ? 0xFF0A84FF
                        : listOrigin == BLUETOOTH ? 0xFF3F7CFF : 0xFF30B0C7;
                drawChip(c, PAD * k + 46 * k, y + ROW_H * k / 2, chipColor,
                        it.symbol != null ? it.symbol : "wifi", on);
                rowLabel.setColor(Ui.alpha(mix(ink, focusInk, on), Math.max(dim, on)));
                c.drawText(it.label, PAD * k + 92 * k, y + ROW_H * k / 2 + Ui.halfCap(rowLabel), rowLabel);
                if (it.connected && listOrigin != INPUTS) {
                    float statusX = getWidth() - PAD * k - 16 * k;
                    c.drawCircle(statusX, y + ROW_H * k / 2, 5 * k, dot);
                }
            }
            c.restoreToCount(saved);
        }

        private float nearness(float x, float y, float w) {
            float d = Math.abs(lensX.value - x) + Math.abs(lensY.value - y);
            return Math.max(0, 1 - d / (w * 0.6f));
        }

        private void drawRim(Canvas c, RectF r, float radius) {
            c.save();
            c.translate(r.left, r.top);
            rim.setStrokeWidth(1.2f * k);
            rim.setShader(tileRim);
            rect.set(0.6f * k, 0.6f * k, r.width() - 0.6f * k, r.height() - 0.6f * k);
            c.drawRoundRect(rect, radius, radius, rim);
            rim.setShader(null);
            c.restore();
        }

        private void drawLens(Canvas c, float radius) {
            float vx = Math.abs(lensX.velocity), vy = Math.abs(lensY.velocity);
            float stretchX = Math.min(0.16f, vx / (9000 * k)), stretchY = Math.min(0.16f, vy / (9000 * k));
            float w = lensW.value * (1 + stretchX - stretchY * 0.5f) * (1 - 0.04f * press.value);
            float h = lensH.value * (1 + stretchY - stretchX * 0.5f) * (1 - 0.04f * press.value);
            float cx = lensX.value + lensW.value / 2, cy = lensY.value + lensH.value / 2;
            int accent = listOpen ? tiles[listOrigin].color : tiles[focus].color;
            for (int i = 3; i >= 1; i--) {
                float g = i * 7 * k;
                rect.set(cx - w / 2 - g, cy - h / 2 - g, cx + w / 2 + g, cy + h / 2 + g);
                fill.setColor(Ui.alpha(accent, 0.10f));
                c.drawRoundRect(rect, radius + g, radius + g, fill);
            }
            rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            c.save();
            c.translate(0, rect.top);
            fill.setAlpha(255);
            fill.setShader(lens);
            rect.offset(0, -rect.top);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
            rim.setStrokeWidth(1.4f * k);
            rim.setColor(night ? 0xFFFFFFFF : 0x33000000);
            c.drawRoundRect(rect, radius, radius, rim);
            c.restore();
        }

        private void drawChip(Canvas c, float cx, float cy, int color, String symbol, float on) {
            float r = CHIP_R * k;
            LinearGradient lit = null;
            for (int i = 0; i < chips && lit == null; i++) if (chipColor[i] == color) lit = chipShader[i];
            if (lit == null) {
                lit = new LinearGradient(0, -r, 0, r, mix(color, 0xFFFFFFFF, 0.28f), color, Shader.TileMode.CLAMP);
                if (chips < chipColor.length) {
                    chipColor[chips] = color;
                    chipShader[chips++] = lit;
                }
            }
            c.save();
            c.translate(cx, cy);
            fill.setAlpha(255);
            fill.setShader(lit);
            c.drawCircle(0, 0, r, fill);
            fill.setShader(null);
            c.restore();
            rim.setStrokeWidth(1f * k);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.35f + 0.3f * on));
            c.drawCircle(cx, cy, r - 0.5f * k, rim);
            Symbols.draw(getContext(), c, symbol, cx, cy, SYMBOL * k, 0xFFFFFFFF);
        }

        boolean onKey(KeyEvent e) {
            if (closing) return true;
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    if (!down) {
                        if (listOpen) {
                            if (listOrigin == BLUETOOTH) stopDiscovery();
                            listOpen = false;
                            page.target = 0;
                            aimLens(false);
                            invalidate();
                            spoken.focusMoved();
                        } else {
                            close();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_NOTIFICATION:
                    if (!down) close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(code);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) {
                        press.target = 1;
                        invalidate();
                    } else if (!down) {
                        press.target = 0;
                        invalidate();
                        if (spoken.keyClick()) select();
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void move(int code) {
            if (listOpen) {
                int before = listFocus;
                int count = listItems != null ? listItems.size() : 0;
                if (code == KeyEvent.KEYCODE_DPAD_UP) listFocus = Math.max(0, listFocus - 1);
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) listFocus = Math.min(count - 1, listFocus + 1);
                if (listFocus == before) return;
            } else {
                int before = focus;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && focus % 2 == 1) focus--;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && focus % 2 == 0 && focus + 1 < tiles.length) focus++;
                if (code == KeyEvent.KEYCODE_DPAD_UP && focus >= 2) focus -= 2;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && focus + 2 < tiles.length) focus += 2;
                if (focus == before) return;
            }
            click.move();
            aimLens(false);
            invalidate();
            spoken.focusMoved();
        }

        private void openList(List<HomeApps.Item> items, String title, String emptyText, int origin) {
            listItems = items;
            listTitle = title;
            listEmpty = emptyText;
            listOrigin = origin;
            listOpen = true;
            listFocus = 0;
            page.target = 1;
            aimLens(false);
            invalidate();
            spoken.focusMoved();
        }

        private void select() {
            click.open();
            if (listOpen) {
                if (listItems == null || listItems.isEmpty()) return;
                HomeApps.Item it = listItems.get(listFocus);
                if (it.intent != null) {
                    if (open(it.intent)) finish();
                } else if (listOrigin == BLUETOOTH && it.key != null) {
                    BluetoothDevice dev = btDeviceMap.get(it.key);
                    if (dev != null) {
                        if (it.connected) {
                            disconnectBluetooth(dev);
                        } else {
                            connectBluetooth(dev);
                        }
                        // Refresh the list after a short delay for state to settle
                        postDelayed(() -> {
                            listItems = bluetoothRows();
                            invalidate();
                        }, 1500);
                    }
                } else if (listOrigin == INPUTS) {
                    if (HomeApps.open(QuickPanelActivity.this, it, this, 0, 0, getWidth(), getHeight())) finish();
                }
                return;
            }
            if (focus == POWER) {
                showPowerDialog();
                return;
            }
            if (focus == TIMER) {
                SleepTimer.step(QuickPanelActivity.this, 1);
                invalidate();
                return;
            }
            if (focus == NETWORK) {
                openList(networkRows(), Ui.tr("Network"), Ui.tr("No network"), NETWORK);
                return;
            }
            if (focus == BLUETOOTH) {
                startDiscovery();
                openList(bluetoothRows(), Ui.tr("Bluetooth"), Ui.tr("No devices"), BLUETOOTH);
                return;
            }
            if (focus == INPUTS) {
                openList(HomeApps.allInputs(getContext()), Ui.tr("Inputs"), Ui.tr("No inputs"), INPUTS);
                return;
            }
            if (focus == HOME_SETTINGS) {
                startActivity(new Intent(QuickPanelActivity.this, SettingsActivity.class));
                finish();
                return;
            }
            if (open(tiles[focus].intents)) finish();
        }
    }
}
