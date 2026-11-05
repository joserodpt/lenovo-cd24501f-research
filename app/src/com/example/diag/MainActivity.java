package com.example.diag;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.RouteInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * SmartClock Diag v2 — a dense, card-based device info dashboard for the Lenovo Smart Clock
 * (800x480 landscape, 240 dpi ≈ 533x320 dp). Also: touch test, key logger, input devices.
 * Keeps v1's always-on behaviour: screen on, Wi-Fi lock, Wi-Fi auto-connect watchdog.
 */
public class MainActivity extends Activity {

    // ---- Optional Wi-Fi auto-connect (2.4 GHz WPA2) ----
    // Leave blank to disable. If set, the app adds this network and keeps it connected
    // (handy to make the device a reliable always-on adb-over-Wi-Fi target).
    private static final String WIFI_SSID = "";   // e.g. "<WIFI_SSID>"
    private static final String WIFI_PASS = "";   // e.g. "<WIFI_PASSWORD>"

    // ---- palette ----
    private static final int BG = 0xFF0A0E13;
    private static final int SURFACE = 0xFF131A23;
    private static final int SURFACE2 = 0xFF1C2532;
    private static final int BORDER = 0xFF263142;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int MUTED = 0xFF8593A6;
    private static final int DIM = 0xFF5A6678;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int GOOD = 0xFF3DDC97;
    private static final int WARN = 0xFFFFC857;
    private static final int BAD = 0xFFFF6B6B;

    private static final String[] TABS = {"Overview", "Network", "Device", "Display", "Sensors", "System", "Input", "Apps"};

    private final Handler mHandler = new Handler();
    private HandlerThread mBgThread;
    private Handler mBg;

    private int mTab = 0;
    private final List<TextView> mRailItems = new ArrayList<>();
    private LinearLayout mPage;
    private ScrollView mPageScroll;
    private final List<Updatable> mUpdatables = new ArrayList<>();

    // header
    private TextView mClock, mDate, mChipIp, mChipWifi, mChipNet, mChipUp;

    // wifi
    private String mWifiMsg = "not attempted yet";
    private WifiManager.WifiLock mWifiLock;

    // probes (background)
    private volatile String mProbeDns = "…", mProbeHttp = "…", mProbeGw = "…", mProbeAt = "never";
    private volatile int mProbeColor = MUTED;
    private volatile String[] mGl = null;

    // traffic rate
    private long mLastRx = -1, mLastTx = -1, mLastTrafficT = 0;
    private String mRxRate = "…", mTxRate = "…";

    // cpu usage
    private long mLastCpuTotal = -1, mLastCpuIdle = -1;
    private String mCpuUsage = "n/a";
    private float mCpuFrac = -1f;

    // sensors
    private SensorManager mSm;
    private volatile float mLux = Float.NaN, mProx = Float.NaN, mProxMax = Float.NaN;
    private volatile long mSensorEvents = 0;
    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            mSensorEvents++;
            if (e.sensor.getType() == Sensor.TYPE_LIGHT) mLux = e.values[0];
            else if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) mProx = e.values[0];
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    // input test
    private final LinkedList<String> mKeyLog = new LinkedList<>();

    // ------------------------------------------------------------------------------------------
    // ticks
    // ------------------------------------------------------------------------------------------

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            updateTraffic();
            updateCpu();
            updateHeader();
            for (Updatable u : mUpdatables) u.update();
            mHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable mWifiWatch = new Runnable() {
        @Override public void run() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                if (!WIFI_SSID.isEmpty() && (ni == null || !ni.isConnected())) connectWifi();
            } catch (Throwable ignore) {}
            mHandler.postDelayed(this, 15000);
        }
    };

    private final Runnable mProbeLoop = new Runnable() {
        @Override public void run() {
            runProbes();
            mBg.postDelayed(this, 20000);
        }
    };

    // ------------------------------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().getDecorView().setBackgroundColor(BG);
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kiosk-wifi");
                mWifiLock.setReferenceCounted(false);
                mWifiLock.acquire();
            }
        } catch (Throwable ignore) {}

        mSm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        mBgThread = new HandlerThread("diag-bg");
        mBgThread.start();
        mBg = new Handler(mBgThread.getLooper());
        mBg.post(new Runnable() { @Override public void run() { mGl = queryGl(); } });
        mBg.postDelayed(mProbeLoop, 12000); // after the start-up Wi-Fi reconnect settles

        setContentView(buildRoot());
        selectTab(0);

        // First check shortly after start; only (re)connects if not already online, so an
        // `adb install -r` no longer drops the adb-over-Wi-Fi session.
        mHandler.removeCallbacks(mWifiWatch);
        mHandler.postDelayed(mWifiWatch, 2500);
    }

    @Override protected void onResume() {
        super.onResume();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
        try {
            // The clock's Ltr578 light/proximity sensors are *dynamic* sensors: they are not in
            // getSensorList()/getDefaultSensor(), only in getDynamicSensorList().
            for (Sensor s : allSensors()) registerIfWanted(s);
            mSm.registerDynamicSensorCallback(mDynCb, mHandler);
        } catch (Throwable ignore) {}
    }

    private final SensorManager.DynamicSensorCallback mDynCb = new SensorManager.DynamicSensorCallback() {
        @Override public void onDynamicSensorConnected(Sensor s) { registerIfWanted(s); }
    };

    private void registerIfWanted(Sensor s) {
        if (s.getType() == Sensor.TYPE_LIGHT || s.getType() == Sensor.TYPE_PROXIMITY) {
            mSm.registerListener(mSensorListener, s, SensorManager.SENSOR_DELAY_NORMAL);
            if (s.getType() == Sensor.TYPE_PROXIMITY) mProxMax = s.getMaximumRange();
        }
    }

    private List<Sensor> allSensors() {
        List<Sensor> l = new ArrayList<>(mSm.getSensorList(Sensor.TYPE_ALL));
        for (Sensor s : mSm.getDynamicSensorList(Sensor.TYPE_ALL)) if (!l.contains(s)) l.add(s);
        return l;
    }

    @Override protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
        try { mSm.unregisterListener(mSensorListener); mSm.unregisterDynamicSensorCallback(mDynCb); } catch (Throwable ignore) {}
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        if (mBgThread != null) mBgThread.quitSafely();
    }

    // We are the home screen: swallow Back instead of finishing.
    @Override public void onBackPressed() { logKey("BACK pressed (ignored — we are Home)"); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        InputDevice d = e.getDevice();
        String act = e.getAction() == KeyEvent.ACTION_DOWN ? "down" : e.getAction() == KeyEvent.ACTION_UP ? "up" : "multi";
        String ch = "";
        int u = e.getUnicodeChar();
        if (u > 31) ch = " '" + (char) u + "'";
        logKey(KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "") + " " + act + ch
                + "  ·  " + (d == null ? "virtual" : d.getName()) + (e.getRepeatCount() > 0 ? " ×" + e.getRepeatCount() : ""));
        return super.dispatchKeyEvent(e);
    }

    private void logKey(String s) {
        mKeyLog.addFirst(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s);
        while (mKeyLog.size() > 10) mKeyLog.removeLast();
    }

    // ------------------------------------------------------------------------------------------
    // UI scaffolding
    // ------------------------------------------------------------------------------------------

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // left rail
        ScrollView railScroll = new ScrollView(this);
        railScroll.setVerticalScrollBarEnabled(false);
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(dp(4), dp(4), dp(2), dp(4));
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(TABS[i]);
            t.setTextSize(11.5f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(10), 0, dp(4), 0);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectTab(idx); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(31));
            lp.bottomMargin = dp(3);
            rail.addView(t, lp);
            mRailItems.add(t);
        }
        railScroll.addView(rail);
        body.addView(railScroll, new LinearLayout.LayoutParams(dp(76), -1));

        // page
        mPageScroll = new ScrollView(this);
        mPageScroll.setFillViewport(true);
        mPage = new LinearLayout(this);
        mPage.setOrientation(LinearLayout.VERTICAL);
        mPage.setPadding(dp(2), dp(4), dp(6), dp(8));
        mPageScroll.addView(mPage, new ScrollView.LayoutParams(-1, -2));
        body.addView(mPageScroll, new LinearLayout.LayoutParams(0, -1, 1f));

        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(10), 0, dp(6), 0);
        h.setBackgroundColor(SURFACE);

        mClock = new TextView(this);
        mClock.setTextColor(TEXT);
        mClock.setTextSize(22);
        mClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        h.addView(mClock, new LinearLayout.LayoutParams(-2, -2));

        mDate = new TextView(this);
        mDate.setTextColor(MUTED);
        mDate.setTextSize(9.5f);
        mDate.setLineSpacing(0, 0.95f);
        mDate.setPadding(dp(8), 0, 0, 0);
        h.addView(mDate, new LinearLayout.LayoutParams(-2, -2));

        h.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        mChipIp = chip(h);
        mChipWifi = chip(h);
        mChipNet = chip(h);
        mChipUp = chip(h);
        mChipIp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectTab(1); }
        });
        return h;
    }

    private TextView chip(LinearLayout parent) {
        TextView t = new TextView(this);
        t.setTextSize(10.5f);
        t.setTextColor(TEXT);
        t.setSingleLine(true);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        t.setBackground(round(SURFACE2, 20, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(4);
        parent.addView(t, lp);
        return t;
    }

    private void selectTab(int idx) {
        mTab = idx;
        for (int i = 0; i < mRailItems.size(); i++) {
            TextView t = mRailItems.get(i);
            boolean sel = i == idx;
            t.setTextColor(sel ? TEXT : MUTED);
            t.setBackground(sel ? railSel() : null);
        }
        mUpdatables.clear();
        mPage.removeAllViews();
        switch (idx) {
            case 0: buildOverview(); break;
            case 1: buildNetwork(); break;
            case 2: buildDevice(); break;
            case 3: buildDisplay(); break;
            case 4: buildSensors(); break;
            case 5: buildSystem(); break;
            case 6: buildInput(); break;
            default: buildApps(); break;
        }
        for (Updatable u : mUpdatables) u.update();
        mPageScroll.scrollTo(0, 0);
    }

    /** selected rail item: rounded surface + accent stripe on the left */
    private android.graphics.drawable.Drawable railSel() {
        final float r = dp(8), sw = dp(3), inset = dp(8);
        return new android.graphics.drawable.Drawable() {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas c) {
                Rect b = getBounds();
                p.setColor(SURFACE2);
                c.drawRoundRect(new RectF(b), r, r, p);
                p.setColor(ACCENT);
                c.drawRoundRect(new RectF(b.left, b.top + inset, b.left + sw, b.bottom - inset), sw / 2, sw / 2, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        };
    }

    // ------------------------------------------------------------------------------------------
    // grid + card primitives
    // ------------------------------------------------------------------------------------------

    interface Updatable { void update(); }
    interface Rows { void fill(List<Row> out) throws Throwable; }
    interface TileSrc { Tile get() throws Throwable; }

    static final class Row {
        final String k, v; final float bar; final int color; final boolean header; boolean wide;
        Row(String k, String v, float bar, int color, boolean header) { this.k = k; this.v = v; this.bar = bar; this.color = color; this.header = header; }
    }
    /** row whose key gets most of the width (long identifiers like package names) */
    private static Row wide(String k, Object v, int color) { Row r = new Row(k, String.valueOf(v), -1f, color, false); r.wide = true; return r; }
    static final class Tile {
        String v = "—", sub = "", tag = ""; int color = TEXT; float bar = -1f; int barColor = ACCENT;
    }

    private static Row kv(String k, Object v) { return new Row(k, v == null ? "null" : String.valueOf(v), -1f, 0, false); }
    private static Row kvc(String k, Object v, int color) { return new Row(k, String.valueOf(v), -1f, color, false); }
    private static Row meter(String k, String v, float frac, int color) { return new Row(k, v, Math.max(0f, Math.min(1f, frac)), color, false); }
    private static Row sub(String title) { return new Row(title, "", -1f, 0, true); }

    /** a horizontal row of N equal columns; returns the columns */
    private LinearLayout[] grid(int n) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout[] cols = new LinearLayout[n];
        for (int i = 0; i < n; i++) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = dp(4);
            row.addView(c, lp);
            cols[i] = c;
        }
        mPage.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return cols;
    }

    private LinearLayout cardShell(LinearLayout parent, String title, String badge) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(SURFACE, 10, BORDER));
        card.setPadding(dp(9), dp(7), dp(9), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        parent.addView(card, lp);

        if (title != null) {
            LinearLayout th = new LinearLayout(this);
            th.setOrientation(LinearLayout.HORIZONTAL);
            th.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            t.setText(title.toUpperCase(Locale.US));
            t.setTextColor(ACCENT);
            t.setTextSize(9.5f);
            t.setLetterSpacing(0.12f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            th.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (badge != null) {
                TextView b = new TextView(this);
                b.setText(badge);
                b.setTextColor(DIM);
                b.setTextSize(9);
                th.addView(b);
            }
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
            tlp.bottomMargin = dp(4);
            card.addView(th, tlp);
        }
        return card;
    }

    /** key/value card; live=false => filled once */
    private void card(LinearLayout parent, String title, boolean live, final Rows src) {
        final LinearLayout card = cardShell(parent, title, live ? "live" : null);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body);
        final Updatable u = new Updatable() {
            String sig = null;
            final List<TextView> vals = new ArrayList<>();
            final List<Bar> bars = new ArrayList<>();
            @Override public void update() {
                List<Row> rows = new ArrayList<>();
                try { src.fill(rows); } catch (Throwable t) { rows.add(kvc("error", t.getClass().getSimpleName() + ": " + t.getMessage(), BAD)); }
                StringBuilder s = new StringBuilder();
                for (Row r : rows) s.append(r.k).append(r.header ? "#" : r.bar >= 0 ? "|" : ";");
                if (!s.toString().equals(sig)) {
                    sig = s.toString();
                    body.removeAllViews(); vals.clear(); bars.clear();
                    for (Row r : rows) addRowView(body, r, vals, bars);
                }
                int vi = 0, bi = 0;
                for (Row r : rows) {
                    if (r.header) continue;
                    TextView tv = vals.get(vi++);
                    if (!r.v.contentEquals(tv.getText())) tv.setText(r.v);
                    if (!r.wide) tv.setTextColor(r.color != 0 ? r.color : TEXT);
                    if (r.bar >= 0) { Bar b = bars.get(bi++); b.set(r.bar, r.color != 0 ? r.color : ACCENT); }
                }
            }
        };
        if (live) mUpdatables.add(u); else u.update();
    }

    private void addRowView(LinearLayout body, Row r, List<TextView> vals, List<Bar> bars) {
        if (r.header) {
            TextView h = new TextView(this);
            h.setText(r.k);
            h.setTextColor(MUTED);
            h.setTextSize(9.5f);
            h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            h.setPadding(0, dp(5), 0, dp(1));
            body.addView(h);
            View line = new View(this);
            line.setBackgroundColor(BORDER);
            body.addView(line, new LinearLayout.LayoutParams(-1, 1));
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(1), 0, dp(1));
        TextView k = new TextView(this);
        k.setText(r.k);
        k.setTextColor(MUTED);
        k.setTextSize(10.5f);
        if (r.wide) {
            k.setTextColor(r.color != 0 ? r.color : TEXT);
            k.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        } else {
            k.setSingleLine(true);
            k.setEllipsize(TextUtils.TruncateAt.END);
        }
        row.addView(k, new LinearLayout.LayoutParams(0, -2, r.wide ? 0.70f : 0.40f));
        TextView v = new TextView(this);
        v.setTextColor(TEXT);
        v.setTextSize(10.5f);
        v.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        v.setTextIsSelectable(false);
        if (r.wide) { v.setGravity(Gravity.END); v.setTextColor(MUTED); }
        row.addView(v, new LinearLayout.LayoutParams(0, -2, r.wide ? 0.30f : 0.60f));
        body.addView(row);
        vals.add(v);
        if (r.bar >= 0) {
            Bar b = new Bar(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(4));
            lp.topMargin = dp(1);
            lp.bottomMargin = dp(3);
            body.addView(b, lp);
            bars.add(b);
        }
    }

    private void tile(LinearLayout parent, String title, final TileSrc src) {
        LinearLayout card = cardShell(parent, title, null);
        final TextView big = new TextView(this);
        big.setTextSize(17);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setSingleLine(true);
        big.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(big);
        final Bar bar = new Bar(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(4));
        blp.topMargin = dp(3);
        card.addView(bar, blp);
        final TextView subv = new TextView(this);
        subv.setTextSize(9.5f);
        subv.setTextColor(MUTED);
        subv.setSingleLine(true);
        subv.setEllipsize(TextUtils.TruncateAt.END);
        subv.setPadding(0, dp(2), 0, 0);
        card.addView(subv);
        mUpdatables.add(new Updatable() {
            @Override public void update() {
                Tile t;
                try { t = src.get(); } catch (Throwable e) { t = new Tile(); t.v = "error"; t.sub = String.valueOf(e); t.color = BAD; }
                big.setText(t.v);
                big.setTextColor(t.color);
                subv.setText(t.sub);
                bar.setVisibility(t.bar >= 0 ? View.VISIBLE : View.INVISIBLE);
                if (t.bar >= 0) bar.set(t.bar, t.barColor);
            }
        });
    }

    private TextView button(LinearLayout parent, String text, int color, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(11);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, round(BORDER, 8, color));
        sl.addState(new int[]{}, round(SURFACE2, 8, BORDER));
        b.setBackground(sl);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(5);
        parent.addView(b, lp);
        return b;
    }

    /** thin progress bar */
    static final class Bar extends View {
        private float f = 0; private int c = ACCENT;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        Bar(Context ctx) { super(ctx); }
        void set(float frac, int color) { if (frac != f || color != c) { f = frac; c = color; invalidate(); } }
        @Override protected void onDraw(Canvas cv) {
            float r = getHeight() / 2f;
            rr.set(0, 0, getWidth(), getHeight());
            p.setColor(BORDER); cv.drawRoundRect(rr, r, r, p);
            rr.set(0, 0, Math.max(getHeight(), getWidth() * f), getHeight());
            p.setColor(c); cv.drawRoundRect(rr, r, r, p);
        }
    }

    // ------------------------------------------------------------------------------------------
    // header
    // ------------------------------------------------------------------------------------------

    private void updateHeader() {
        Date now = new Date();
        mClock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm:ss" : "h:mm:ss a", Locale.getDefault()).format(now));
        mDate.setText(new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(now) + "\n" + TimeZone.getDefault().getID());

        String ip = wifiIp();
        mChipIp.setText("IP  " + (ip == null ? "—" : ip));
        mChipIp.setTextColor(ip == null ? BAD : TEXT);

        try {
            WifiInfo wi = wifi().getConnectionInfo();
            int rssi = wi == null ? -127 : wi.getRssi();
            boolean conn = wi != null && wi.getNetworkId() != -1;
            int lvl = WifiManager.calculateSignalLevel(rssi, 5);
            mChipWifi.setText(conn ? "Wi-Fi  " + bars(lvl) + "  " + rssi + " dBm" : "Wi-Fi  off-line");
            mChipWifi.setTextColor(!conn ? BAD : lvl >= 3 ? TEXT : lvl == 2 ? WARN : BAD);
        } catch (Throwable t) { mChipWifi.setText("Wi-Fi  ?"); }

        boolean validated = isValidated();
        mChipNet.setText(validated ? "● Internet" : "● No internet");
        mChipNet.setTextColor(validated ? GOOD : BAD);

        mChipUp.setText("up " + dur(SystemClock.elapsedRealtime() / 1000));
        mChipUp.setTextColor(MUTED);
    }

    private static String bars(int lvl) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 4; i++) b.append(i < lvl ? "●" : "○");
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------
    // TAB: overview
    // ------------------------------------------------------------------------------------------

    private void buildOverview() {
        LinearLayout[] c = grid(3);
        tile(c[0], "Address", new TileSrc() { public Tile get() {
            Tile t = new Tile(); String ip = wifiIp();
            t.v = ip == null ? "no IP" : ip; t.color = ip == null ? BAD : TEXT;
            t.sub = "adb connect " + (ip == null ? "?" : ip) + ":" + orDash(prop("persist.adb.tcp.port"));
            return t; } });
        tile(c[1], "Wi-Fi", new TileSrc() { public Tile get() {
            Tile t = new Tile(); WifiInfo wi = wifi().getConnectionInfo();
            if (wi == null || wi.getNetworkId() == -1) { t.v = "disconnected"; t.color = BAD; t.sub = mWifiMsg; return t; }
            t.v = ssid(wi); int pct = WifiManager.calculateSignalLevel(wi.getRssi(), 101);
            t.bar = pct / 100f; t.barColor = pct > 60 ? GOOD : pct > 35 ? WARN : BAD;
            t.sub = wi.getRssi() + " dBm · " + wi.getLinkSpeed() + " Mbps · ch " + channel(wi.getFrequency());
            return t; } });
        tile(c[2], "Internet", new TileSrc() { public Tile get() {
            Tile t = new Tile(); boolean ok = isValidated();
            t.v = ok ? "online" : "offline"; t.color = ok ? GOOD : BAD;
            t.sub = "HTTP " + mProbeHttp + " · DNS " + mProbeDns; return t; } });

        c = grid(3);
        tile(c[0], "Memory", new TileSrc() { public Tile get() {
            Tile t = new Tile(); ActivityManager.MemoryInfo mi = memInfo();
            float used = 1f - (float) mi.availMem / mi.totalMem;
            t.v = human(mi.totalMem - mi.availMem) + " used"; t.bar = used; t.barColor = used > .85f ? BAD : used > .7f ? WARN : ACCENT;
            t.sub = human(mi.availMem) + " free of " + human(mi.totalMem); return t; } });
        tile(c[1], "Storage /data", new TileSrc() { public Tile get() {
            Tile t = new Tile(); StatFs fs = new StatFs("/data");
            long tot = fs.getTotalBytes(), free = fs.getAvailableBytes(); float used = 1f - (float) free / tot;
            t.v = human(free) + " free"; t.bar = used; t.barColor = used > .9f ? BAD : ACCENT;
            t.sub = human(tot - free) + " used of " + human(tot); return t; } });
        tile(c[2], "CPU", new TileSrc() { public Tile get() {
            Tile t = new Tile(); t.v = mCpuUsage.equals("n/a") ? curFreqs() : mCpuUsage;
            if (mCpuFrac >= 0) { t.bar = mCpuFrac; t.barColor = mCpuFrac > .8f ? BAD : ACCENT; }
            String la = loadAvg(); t.sub = Runtime.getRuntime().availableProcessors() + " cores · " + (la != null ? "load " + la
                    : orDash(readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")) + " governor");
            return t; } });

        c = grid(3);
        tile(c[0], "Ambient light", new TileSrc() { public Tile get() {
            Tile t = new Tile(); t.v = Float.isNaN(mLux) ? "waiting…" : fmt1(mLux) + " lux";
            if (!Float.isNaN(mLux)) { t.bar = luxFrac(mLux); t.barColor = WARN; }
            t.sub = "proximity " + (Float.isNaN(mProx) ? "—" : fmt1(mProx) + " cm"); return t; } });
        tile(c[1], "Touch", new TileSrc() { public Tile get() {
            Tile t = new Tile(); Configuration cf = getResources().getConfiguration();
            t.v = cf.touchscreen == Configuration.TOUCHSCREEN_FINGER ? "finger" : "n/a";
            t.sub = "multi-touch test on the Input tab"; return t; } });
        tile(c[2], "Uptime", new TileSrc() { public Tile get() {
            Tile t = new Tile(); t.v = dur(SystemClock.elapsedRealtime() / 1000);
            t.sub = "since " + new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(System.currentTimeMillis() - SystemClock.elapsedRealtime()));
            return t; } });

        c = grid(3);
        tile(c[0], "Android", new TileSrc() { public Tile get() {
            Tile t = new Tile(); t.v = Build.VERSION.RELEASE + " · SDK " + Build.VERSION.SDK_INT;
            t.sub = Build.ID + " · patch " + Build.VERSION.SECURITY_PATCH; return t; } });
        tile(c[1], "WebView", new TileSrc() { public Tile get() {
            Tile t = new Tile(); PackageInfo p = WebView.getCurrentWebViewPackage();
            t.v = p == null ? "none" : p.versionName; t.color = p == null ? BAD : TEXT;
            t.sub = p == null ? "" : p.packageName; return t; } });
        tile(c[2], "Screen", new TileSrc() { public Tile get() {
            Tile t = new Tile(); DisplayMetrics dm = realMetrics();
            t.v = dm.widthPixels + " × " + dm.heightPixels; t.sub = dm.densityDpi + " dpi · " + fmt1(display().getRefreshRate()) + " Hz · "
                    + Math.round(dm.widthPixels / dm.density) + "×" + Math.round(dm.heightPixels / dm.density) + " dp";
            return t; } });

        c = grid(1);
        card(c[0], "Device", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("Model", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"));
            o.add(kv("SoC / board", prop("ro.board.platform") + " · " + Build.HARDWARE + " · " + Build.BOARD));
            o.add(kv("App", getPackageName() + " · uid " + Process.myUid() + " · pid " + Process.myPid()));
        } });
    }

    // ------------------------------------------------------------------------------------------
    // TAB: network
    // ------------------------------------------------------------------------------------------

    private void buildNetwork() {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(4), 0, 0, dp(4));
        actions.setGravity(Gravity.CENTER_VERTICAL);
        button(actions, WIFI_SSID.isEmpty() ? "Wi-Fi auto-connect off" : "Reconnect Wi-Fi (" + WIFI_SSID + ")", ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { connectWifi(); }
        });
        button(actions, "Run probes now", TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { mProbeAt = "running…"; mBg.post(new Runnable() { public void run() { runProbes(); } }); }
        });
        final TextView msg = new TextView(this);
        msg.setTextColor(MUTED); msg.setTextSize(9.5f); msg.setSingleLine(true); msg.setEllipsize(TextUtils.TruncateAt.END);
        actions.addView(msg, new LinearLayout.LayoutParams(0, -2, 1f));
        mUpdatables.add(new Updatable() { public void update() { msg.setText("last connect: " + mWifiMsg); } });
        mPage.addView(actions);

        LinearLayout[] c = grid(2);
        card(c[0], "Wi-Fi link", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            WifiManager wm = wifi();
            o.add(kvc("Radio", wifiState(wm.getWifiState()), wm.isWifiEnabled() ? GOOD : BAD));
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                o.add(kv("SSID", ssid(wi)));
                String bssid = wi.getBSSID();
                o.add(kvc("BSSID", "02:00:00:00:00:00".equals(bssid) ? "masked (needs location perm)" : bssid, "02:00:00:00:00:00".equals(bssid) ? DIM : 0));
                o.add(kv("Supplicant", wi.getSupplicantState()));
                int pct = WifiManager.calculateSignalLevel(wi.getRssi(), 101);
                o.add(meter("Signal", wi.getRssi() + " dBm · " + pct + "%", pct / 100f, pct > 60 ? GOOD : pct > 35 ? WARN : BAD));
                o.add(kv("Link speed", wi.getLinkSpeed() + " Mbps"));
                o.add(kv("Frequency", wi.getFrequency() + " MHz · ch " + channel(wi.getFrequency())));
                o.add(kv("Network id", wi.getNetworkId()));
            }
            o.add(kv("MAC (wlan0)", macOf("wlan0")));
            o.add(kv("5 GHz support", yn(wm.is5GHzBandSupported())));
            o.add(kv("Wi-Fi Direct", yn(wm.isP2pSupported())));
            o.add(kv("Lock held", mWifiLock != null && mWifiLock.isHeld() ? "yes (high-perf)" : "no"));
        } });
        card(c[0], "IP configuration", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            DhcpInfo d = wifi().getDhcpInfo();
            if (d != null) {
                o.add(kv("IPv4", intToIp(d.ipAddress)));
                o.add(kv("Netmask", intToIp(d.netmask)));
                o.add(kv("Gateway", intToIp(d.gateway)));
                o.add(kv("DHCP server", intToIp(d.serverAddress)));
                o.add(kv("DNS", intToIp(d.dns1) + (d.dns2 != 0 ? ", " + intToIp(d.dns2) : "")));
                o.add(kv("Lease", d.leaseDuration > 0 ? dur(d.leaseDuration) : "—"));
            }
            LinkProperties lp = linkProps();
            if (lp != null) {
                o.add(sub("Link properties"));
                o.add(kv("Interface", lp.getInterfaceName()));
                for (LinkAddress a : lp.getLinkAddresses()) o.add(kv(a.getAddress() instanceof Inet4Address ? "Addr v4" : "Addr v6", a.toString()));
                StringBuilder dns = new StringBuilder();
                for (InetAddress a : lp.getDnsServers()) { if (dns.length() > 0) dns.append(", "); dns.append(a.getHostAddress()); }
                o.add(kv("DNS servers", dns.length() == 0 ? "—" : dns));
                if (lp.getDomains() != null) o.add(kv("Search domain", lp.getDomains()));
                for (RouteInfo r : lp.getRoutes()) o.add(kv("Route", r.getDestination() + (r.getGateway() != null && !r.getGateway().isAnyLocalAddress() ? " → " + r.getGateway().getHostAddress() : "")));
            }
        } });

        card(c[1], "Connectivity", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            ConnectivityManager cm = cm();
            NetworkInfo ni = cm.getActiveNetworkInfo();
            o.add(kvc("Active", ni == null ? "none" : ni.getTypeName() + " · " + ni.getDetailedState(), ni != null && ni.isConnected() ? GOOD : BAD));
            o.add(kvc("Validated", isValidated() ? "yes — internet reachable" : "no", isValidated() ? GOOD : BAD));
            o.add(kv("Metered", yn(cm.isActiveNetworkMetered())));
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null) {
                o.add(kv("Est. bandwidth", "↓ " + nc.getLinkDownstreamBandwidthKbps() / 1000 + " / ↑ " + nc.getLinkUpstreamBandwidthKbps() / 1000 + " Mbps"));
                o.add(kv("Captive portal", yn(nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))));
            }
            o.add(kv("Networks", cm.getAllNetworks().length));
        } });
        card(c[1], "Reachability probes", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kvc("DNS google.com", mProbeDns, mProbeDns.endsWith("ms") ? GOOD : WARN));
            o.add(kvc("HTTP generate_204", mProbeHttp, mProbeHttp.startsWith("204") ? GOOD : WARN));
            o.add(kvc("Gateway TCP", mProbeGw, mProbeGw.endsWith("ms") ? GOOD : WARN));
            o.add(kv("Last run", mProbeAt + " (every 20 s)"));
        } });
        card(c[1], "Traffic", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("Rate", "↓ " + mRxRate + "  ↑ " + mTxRate));
            o.add(kv("Total RX", human(TrafficStats.getTotalRxBytes()) + " · " + TrafficStats.getTotalRxPackets() + " pkts"));
            o.add(kv("Total TX", human(TrafficStats.getTotalTxBytes()) + " · " + TrafficStats.getTotalTxPackets() + " pkts"));
            int uid = Process.myUid();
            o.add(kv("This app", "↓ " + human(TrafficStats.getUidRxBytes(uid)) + " ↑ " + human(TrafficStats.getUidTxBytes(uid))));
        } });
        card(c[1], "Interfaces", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            List<NetworkInterface> l = Collections.list(NetworkInterface.getNetworkInterfaces());
            Collections.sort(l, new Comparator<NetworkInterface>() { public int compare(NetworkInterface a, NetworkInterface b) { return a.getName().compareTo(b.getName()); } });
            for (NetworkInterface nif : l) {
                StringBuilder s = new StringBuilder(nif.isUp() ? "up" : "down");
                try { s.append(" · mtu ").append(nif.getMTU()); } catch (Throwable ignore) {}
                for (InetAddress a : Collections.list(nif.getInetAddresses())) s.append("\n").append(stripScope(a.getHostAddress()));
                o.add(kvc(nif.getName(), s, nif.isUp() ? 0 : DIM));
            }
        } });
    }

    // ------------------------------------------------------------------------------------------
    // TAB: device
    // ------------------------------------------------------------------------------------------

    private void buildDevice() {
        LinearLayout[] c = grid(2);
        card(c[0], "Identity", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("Manufacturer", Build.MANUFACTURER));
            o.add(kv("Brand", Build.BRAND));
            o.add(kv("Model", Build.MODEL));
            o.add(kv("Device", Build.DEVICE));
            o.add(kv("Product", Build.PRODUCT));
            o.add(kv("Board", Build.BOARD));
            o.add(kv("Hardware", Build.HARDWARE));
            o.add(kv("Platform", prop("ro.board.platform")));
            o.add(kv("Characteristics", prop("ro.build.characteristics")));
            o.add(kv("Bootloader", Build.BOOTLOADER));
            o.add(kv("Radio", orDash(Build.getRadioVersion())));
            o.add(kv("Serial (prop)", orDash(prop("ro.serialno"))));
            o.add(kv("Android ID", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)));
        } });
        card(c[0], "Boot & security", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("Active slot", orDash(prop("ro.boot.slot_suffix"))));
            String vb = prop("ro.boot.verifiedbootstate");
            o.add(kvc("Verified boot", orDash(vb), "green".equals(vb) ? GOOD : vb.isEmpty() ? 0 : WARN));
            o.add(kv("Flash locked", orDash(prop("ro.boot.flash.locked"))));
            o.add(kv("Verity mode", orDash(prop("ro.boot.veritymode"))));
            o.add(kv("ro.secure", prop("ro.secure")));
            o.add(kv("ro.debuggable", prop("ro.debuggable")));
            o.add(kv("ro.adb.secure", prop("ro.adb.secure")));
            o.add(kv("adb tcp port", orDash(prop("persist.adb.tcp.port"))));
            o.add(kv("SELinux", selinux()));
            o.add(kv("Encryption", orDash(prop("ro.crypto.state")) + " " + prop("ro.crypto.type")));
        } });

        card(c[1], "Build", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("Android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"));
            o.add(kv("Codename", Build.VERSION.CODENAME));
            o.add(kv("Security patch", Build.VERSION.SECURITY_PATCH));
            o.add(kv("Build ID", Build.ID));
            o.add(kv("Incremental", Build.VERSION.INCREMENTAL));
            o.add(kv("Type / tags", Build.TYPE + " / " + Build.TAGS));
            o.add(kv("Built", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(Build.TIME))));
            o.add(kv("Built by", Build.USER + "@" + Build.HOST));
            o.add(kv("Display ID", Build.DISPLAY));
            o.add(kv("Fingerprint", Build.FINGERPRINT));
            o.add(kv("Things version", orDash(prop("ro.build.version.things"))));
        } });
        card(c[1], "Runtime", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("Kernel", System.getProperty("os.version")));
            String pv = readFile("/proc/version");
            if (pv != null) o.add(kv("/proc/version", pv));
            o.add(kv("Arch", System.getProperty("os.arch")));
            o.add(kv("ABIs", TextUtils.join(", ", Build.SUPPORTED_ABIS)));
            o.add(kv("VM", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")));
            o.add(kv("Locale", Locale.getDefault().toString()));
            o.add(kv("Timezone", TimeZone.getDefault().getID() + " (" + TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT) + ")"));
            o.add(kv("Heap max (prop)", prop("dalvik.vm.heapsize")));
            o.add(kv("OpenGL ES (prop)", glesVersionStr()));
        } });
    }

    // ------------------------------------------------------------------------------------------
    // TAB: display
    // ------------------------------------------------------------------------------------------

    private void buildDisplay() {
        LinearLayout[] c = grid(2);
        card(c[0], "Panel", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            Display d = display();
            DisplayMetrics dm = realMetrics();
            o.add(kv("Name", d.getName() + " (id " + d.getDisplayId() + ")"));
            o.add(kv("Resolution", dm.widthPixels + " × " + dm.heightPixels + " px"));
            o.add(kv("In dp", Math.round(dm.widthPixels / dm.density) + " × " + Math.round(dm.heightPixels / dm.density) + " dp"));
            o.add(kv("Density", dm.density + "× · " + dm.densityDpi + " dpi (" + densityBucket(dm.densityDpi) + ")"));
            if (dm.xdpi >= 1 && dm.ydpi >= 1) {
                o.add(kv("Exact dpi", fmt1(dm.xdpi) + " × " + fmt1(dm.ydpi)));
                double in = Math.sqrt(Math.pow(dm.widthPixels / dm.xdpi, 2) + Math.pow(dm.heightPixels / dm.ydpi, 2));
                o.add(kv("Diagonal (calc)", String.format(Locale.US, "%.1f\"", in)));
            } else o.add(kvc("Exact dpi", "not reported by panel", DIM));
            o.add(kv("Refresh", fmt1(d.getRefreshRate()) + " Hz"));
            Display.Mode[] modes = d.getSupportedModes();
            for (Display.Mode m : modes) o.add(kv("Mode " + m.getModeId(), m.getPhysicalWidth() + "×" + m.getPhysicalHeight() + " @ " + fmt1(m.getRefreshRate())));
            Display.HdrCapabilities hc = d.getHdrCapabilities();
            o.add(kv("HDR", hc == null || hc.getSupportedHdrTypes().length == 0 ? "none" : Arrays.toString(hc.getSupportedHdrTypes())));
            o.add(kv("Wide color", yn(d.isWideColorGamut())));
            o.add(kv("State", displayState(d.getState())));
        } });
        card(c[0], "Brightness & power", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            int b = sysInt(Settings.System.SCREEN_BRIGHTNESS, -1);
            if (b >= 0) o.add(meter("Brightness", b + " / 255", b / 255f, WARN)); else o.add(kv("Brightness", "n/a"));
            o.add(kv("Auto brightness", sysInt(Settings.System.SCREEN_BRIGHTNESS_MODE, -1) == 1 ? "on" : "off"));
            int to = sysInt(Settings.System.SCREEN_OFF_TIMEOUT, -1);
            o.add(kv("Screen timeout", to < 0 ? "n/a" : dur(to / 1000)));
            o.add(kv("Stay on plugged", glob(Settings.Global.STAY_ON_WHILE_PLUGGED_IN)));
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            o.add(kv("Interactive", yn(pm.isInteractive())));
            o.add(kv("Power save", yn(pm.isPowerSaveMode())));
            o.add(kv("Doze idle", yn(pm.isDeviceIdleMode())));
            o.add(kv("Keep-screen-on", "set by this app"));
        } });

        card(c[1], "Configuration", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            Configuration cf = getResources().getConfiguration();
            o.add(kv("Orientation", cf.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait"));
            o.add(kv("Rotation", display().getRotation() * 90 + "°"));
            o.add(kv("Window dp", cf.screenWidthDp + " × " + cf.screenHeightDp));
            o.add(kv("Smallest width", cf.smallestScreenWidthDp + " dp"));
            o.add(kv("Size class", screenSize(cf.screenLayout)));
            o.add(kv("Font scale", cf.fontScale + "×"));
            o.add(kv("Night mode", (cf.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? "yes" : "no"));
            o.add(kv("UI mode type", uiModeType(cf.uiMode & Configuration.UI_MODE_TYPE_MASK)));
            o.add(kv("Touchscreen", cf.touchscreen == Configuration.TOUCHSCREEN_FINGER ? "finger" : String.valueOf(cf.touchscreen)));
            o.add(kv("Keyboard", keyboardCfg(cf.keyboard)));
            o.add(kv("Navigation", navCfg(cf.navigation)));
            o.add(kv("Layout dir", cf.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? "RTL" : "LTR"));
        } });
        card(c[1], "GPU", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            String[] g = mGl;
            if (g == null) { o.add(kv("Status", "querying EGL…")); return; }
            o.add(kv("Vendor", g[0]));
            o.add(kv("Renderer", g[1]));
            o.add(kv("GL version", g[2]));
            o.add(kv("GLSL", g[3]));
            o.add(kv("EGL", g[4]));
            o.add(kv("Extensions", g[5]));
            o.add(kv("Required GLES", glesVersionStr()));
        } });
    }

    // ------------------------------------------------------------------------------------------
    // TAB: sensors / power / audio
    // ------------------------------------------------------------------------------------------

    private void buildSensors() {
        LinearLayout[] c = grid(2);
        card(c[0], "Live readings", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            if (Float.isNaN(mLux)) o.add(kv("Light", "waiting for event…"));
            else o.add(meter("Light", fmt1(mLux) + " lux", luxFrac(mLux), WARN));
            if (Float.isNaN(mProx)) o.add(kv("Proximity", "waiting for event…"));
            else {
                boolean near = !Float.isNaN(mProxMax) && mProx < mProxMax;
                o.add(kvc("Proximity", fmt1(mProx) + " cm" + (near ? " · NEAR" : " · far"), near ? ACCENT : 0));
            }
            o.add(kv("Events", mSensorEvents));
            o.add(kv("Tip", "cover the top-left of the bezel"));
        } });
        card(c[0], "Power", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) { o.add(kv("Battery", "no broadcast")); return; }
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            o.add(kv("Source", plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC adapter" : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB" : plugged == 0 ? "battery" : "other (" + plugged + ")"));
            o.add(kv("Battery present", yn(b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))));
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            o.add(kv("Reported level", lvl + "/" + sc + " (status " + b.getIntExtra(BatteryManager.EXTRA_STATUS, -1) + ")"));
            o.add(kv("Reported temp", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f + " °C"));
            if (!b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) o.add(kvc("Note", "no battery — values above are a stub", DIM));
            o.add(kv("Reported volt", b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " mV"));
            o.add(kv("Technology", b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)));
        } });
        card(c[0], "Audio", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int[] streams = {AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_VOICE_CALL};
            String[] names = {"Media", "Alarm", "Notification", "System", "Voice"};
            for (int i = 0; i < streams.length; i++) {
                int v = am.getStreamVolume(streams[i]), m = am.getStreamMaxVolume(streams[i]);
                o.add(meter(names[i], v + " / " + m + (am.isStreamMute(streams[i]) ? " (muted)" : ""), m == 0 ? 0 : (float) v / m, ACCENT));
            }
            o.add(kv("Mode", am.getMode()));
            o.add(kv("Music active", yn(am.isMusicActive())));
            o.add(kv("Mic muted", yn(am.isMicrophoneMute())));
            o.add(kv("Sample rate", orDash(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)) + " Hz"));
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_ALL))
                o.add(kv(d.isSink() ? "Output" : "Input", audioType(d.getType()) + " · " + d.getProductName()));
        } });

        card(c[1], "All sensors", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            List<Sensor> all = allSensors();
            o.add(kv("Count", all.size() + (mSm.isDynamicSensorDiscoverySupported() ? " (dynamic discovery on)" : "")));
            for (Sensor s : all) {
                o.add(sub(s.getName()));
                o.add(kv("Type", s.getStringType().replace("android.sensor.", "") + " (" + s.getType() + ")"));
                o.add(kv("Vendor / ver", s.getVendor() + " / v" + s.getVersion()));
                o.add(kv("Range / res", fmt1(s.getMaximumRange()) + " / " + s.getResolution()));
                o.add(kv("Power", s.getPower() + " mA"));
                o.add(kv("Delay", s.getMinDelay() + "–" + s.getMaxDelay() + " µs"));
                o.add(kv("Flags", (s.isWakeUpSensor() ? "wake-up " : "") + (s.isDynamicSensor() ? "dynamic " : "") + "fifo " + s.getFifoMaxEventCount()));
            }
        } });
        card(c[1], "Bluetooth", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba == null) { o.add(kv("Adapter", "none")); return; }
            try {
                o.add(kvc("State", btState(ba.getState()), ba.isEnabled() ? GOOD : MUTED));
                o.add(kv("Name", ba.getName()));
                o.add(kv("Bonded", ba.getBondedDevices().size() + " device(s)"));
                o.add(kv("LE supported", yn(getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))));
            } catch (SecurityException se) {
                o.add(kvc("Adapter", "present (BLUETOOTH perm not granted)", WARN));
            }
        } });
    }

    // ------------------------------------------------------------------------------------------
    // TAB: system
    // ------------------------------------------------------------------------------------------

    private void buildSystem() {
        LinearLayout[] c = grid(2);
        card(c[0], "CPU", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("SoC", orDash(cpuinfo("Hardware")) + " · " + prop("ro.board.platform")));
            o.add(kv("Core", orDash(cpuinfo("model name")) + " · part " + orDash(cpuinfo("CPU part"))));
            o.add(kv("Features", orDash(cpuinfo("Features"))));
            if (mCpuFrac >= 0) o.add(meter("Usage (all)", mCpuUsage, mCpuFrac, mCpuFrac > .8f ? BAD : ACCENT));
            else o.add(kv("Usage (all)", "n/a (/proc/stat blocked)"));
            String la = loadAvg();
            o.add(kv("Load avg", la == null ? "n/a" : la));
            int n = Runtime.getRuntime().availableProcessors();
            for (int i = 0; i < n; i++) {
                String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
                long cur = parseL(readFile(base + "scaling_cur_freq")), max = parseL(readFile(base + "cpuinfo_max_freq"));
                String online = readFile("/sys/devices/system/cpu/cpu" + i + "/online");
                if (cur > 0 && max > 0) o.add(meter("cpu" + i, cur / 1000 + " MHz / " + max / 1000, (float) cur / max, ACCENT));
                else o.add(kvc("cpu" + i, "0".equals(online) ? "offline" : "n/a", DIM));
            }
            o.add(kv("Governor", orDash(readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"))));
            o.add(kv("Min freq", parseL(readFile("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq")) / 1000 + " MHz"));
        } });
        card(c[0], "Thermal", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            int found = 0;
            for (int i = 0; i < 12; i++) {
                String base = "/sys/class/thermal/thermal_zone" + i + "/";
                String type = readFile(base + "type"), temp = readFile(base + "temp");
                if (type == null && temp == null) { if (i > 2) break; continue; }
                found++;
                long t = parseL(temp);
                o.add(kvc(type == null ? "zone" + i : type, temp == null ? "denied" : (t > 1000 ? t / 1000f : t) + " °C", t > 70000 ? BAD : 0));
            }
            if (found == 0) o.add(kvc("Zones", "not readable (SELinux)", DIM));
        } });
        card(c[0], "Time", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("Local", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())));
            o.add(kv("Timezone", TimeZone.getDefault().getID()));
            o.add(kv("Auto time", glob(Settings.Global.AUTO_TIME)));
            o.add(kv("Auto zone", glob(Settings.Global.AUTO_TIME_ZONE)));
            o.add(kv("24-hour", yn(DateFormat.is24HourFormat(MainActivity.this))));
            long el = SystemClock.elapsedRealtime(), up = SystemClock.uptimeMillis();
            o.add(kv("Uptime", dur(el / 1000)));
            o.add(kv("Awake", dur(up / 1000)));
            o.add(kv("Deep sleep", dur((el - up) / 1000)));
            o.add(kv("Booted", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(System.currentTimeMillis() - el))));
        } });

        card(c[1], "Memory", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = memInfo();
            float used = 1f - (float) mi.availMem / mi.totalMem;
            o.add(meter("RAM", human(mi.totalMem - mi.availMem) + " / " + human(mi.totalMem), used, used > .85f ? BAD : ACCENT));
            o.add(kv("Available", human(mi.availMem)));
            o.add(kvc("Low memory", yn(mi.lowMemory) + " (threshold " + human(mi.threshold) + ")", mi.lowMemory ? BAD : 0));
            Map<String, Long> m = meminfo();
            if (m.containsKey("Cached")) o.add(kv("Cached", human(m.get("Cached") * 1024)));
            if (m.containsKey("Buffers")) o.add(kv("Buffers", human(m.get("Buffers") * 1024)));
            if (m.containsKey("SwapTotal")) {
                long st = m.get("SwapTotal") * 1024, sf = m.containsKey("SwapFree") ? m.get("SwapFree") * 1024 : 0;
                if (st > 0) o.add(meter("Swap / zram", human(st - sf) + " / " + human(st), (float) (st - sf) / st, ACCENT));
                else o.add(kv("Swap / zram", "none"));
            }
            o.add(kv("Low-RAM device", yn(am.isLowRamDevice())));
            o.add(kv("App heap class", am.getMemoryClass() + " MB (large " + am.getLargeMemoryClass() + ")"));
            Runtime rt = Runtime.getRuntime();
            o.add(kv("App heap", human(rt.totalMemory() - rt.freeMemory()) + " / " + human(rt.maxMemory())));
            String rss = statusField("VmRSS");
            if (rss != null) o.add(kv("App RSS", rss));
        } });
        card(c[1], "Storage", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            // (/cache is an SELinux-denied symlink for apps; skipped)
            String[] paths = {"/data", "/system", "/vendor", "/oem", getFilesDir().getAbsolutePath()};
            String[] names = {"/data", "/system", "/vendor", "/oem", "app files"};
            for (int i = 0; i < paths.length; i++) {
                try {
                    StatFs fs = new StatFs(paths[i]);
                    long tot = fs.getTotalBytes(), free = fs.getAvailableBytes();
                    if (tot <= 0) continue;
                    float u = 1f - (float) free / tot;
                    o.add(meter(names[i], human(free) + " free / " + human(tot), u, u > .9f ? BAD : ACCENT));
                } catch (Throwable ignore) {}
            }
        } });
        card(c[1], "Global settings", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("adb_enabled", glob(Settings.Global.ADB_ENABLED)));
            o.add(kv("dev settings", glob(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)));
            o.add(kv("provisioned", glob(Settings.Global.DEVICE_PROVISIONED)));
            o.add(kv("airplane", glob(Settings.Global.AIRPLANE_MODE_ON)));
            o.add(kv("wifi_on", glob(Settings.Global.WIFI_ON)));
            o.add(kv("bluetooth_on", glob(Settings.Global.BLUETOOTH_ON)));
            o.add(kv("auto-update tz", glob(Settings.Global.AUTO_TIME_ZONE)));
            o.add(kv("anim scale", glob(Settings.Global.ANIMATOR_DURATION_SCALE)));
        } });
        card(c[1], "This process", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            o.add(kv("PID / UID", Process.myPid() + " / " + Process.myUid()));
            o.add(kv("Threads", orDash(statusField("Threads"))));
            o.add(kv("CPU time", Process.getElapsedCpuTime() / 1000f + " s"));
            o.add(kv("Started", dur((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000) + " ago"));
            o.add(kv("Is 64-bit", yn(Process.is64Bit())));
        } });
    }

    // ------------------------------------------------------------------------------------------
    // TAB: input  (touch test, key log, input devices, USB)
    // ------------------------------------------------------------------------------------------

    private void buildInput() {
        LinearLayout[] c = grid(2);

        card(c[0], "Key events", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            if (mKeyLog.isEmpty()) o.add(kvc("waiting", "press a hardware button (volume / action)", DIM));
            int i = 0;
            for (String s : mKeyLog) o.add(kv("#" + (++i), s));
        } });

        // --- touch test ---
        LinearLayout tc = cardShell(c[1], "Touch test", "multi-touch");
        TouchPad pad = new TouchPad(this);
        pad.setBackground(round(BG, 8, BORDER));
        tc.addView(pad, new LinearLayout.LayoutParams(-1, dp(118)));

        card(c[1], "Input devices", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d == null) continue;
                o.add(sub(d.getName() + (d.isVirtual() ? "  (virtual)" : "")));
                o.add(kv("id / vid:pid", id + " / " + String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId())));
                o.add(kv("Sources", sources(d.getSources())));
                boolean realKb = d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC && !d.isVirtual();
                o.add(kvc("Keyboard type", kbType(d.getKeyboardType()) + (d.isVirtual() && d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC ? " (software, for injected keys)" : ""), realKb ? GOOD : 0));
                InputDevice.MotionRange rx = d.getMotionRange(MotionEvent.AXIS_X), ry = d.getMotionRange(MotionEvent.AXIS_Y);
                if (rx != null && ry != null) o.add(kv("Touch range", Math.round(rx.getMax()) + " × " + Math.round(ry.getMax())));
                if (d.hasMicrophone()) o.add(kv("Microphone", "yes"));
            }
        } });
        card(c[1], "USB host devices", true, new Rows() { public void fill(List<Row> o) throws Throwable {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> l = um == null ? null : um.getDeviceList();
            o.add(kv("USB host", yn(getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST))));
            if (l == null || l.isEmpty()) { o.add(kvc("Attached", "none — a USB keyboard on the rear port would show here", DIM)); return; }
            for (UsbDevice d : l.values())
                o.add(kv(String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId()), orDash(d.getManufacturerName()) + " " + orDash(d.getProductName()) + " · class " + d.getDeviceClass()));
        } });
    }


    /** multi-touch visualiser */
    final class TouchPad extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] xs = new float[10], ys = new float[10], ps = new float[10];
        private final int[] ids = new int[10];
        private int n = 0, maxN = 0, taps = 0;
        private final int[] colors = {ACCENT, GOOD, WARN, BAD, 0xFFB388FF, 0xFFFF8A65, 0xFF80CBC4, 0xFFF06292, 0xFFAED581, 0xFF90A4AE};
        TouchPad(Context c) { super(c); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) taps++;
            boolean up = e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL;
            n = up ? 0 : Math.min(10, e.getPointerCount());
            for (int i = 0; i < n; i++) { xs[i] = e.getX(i); ys[i] = e.getY(i); ps[i] = e.getPressure(i); ids[i] = e.getPointerId(i); }
            if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                int gone = e.getActionIndex();
                for (int i = gone; i < n - 1; i++) { xs[i] = xs[i + 1]; ys[i] = ys[i + 1]; ps[i] = ps[i + 1]; ids[i] = ids[i + 1]; }
                n--;
            }
            maxN = Math.max(maxN, n);
            getParent().requestDisallowInterceptTouchEvent(!up);
            invalidate();
            return true;
        }
        @Override protected void onDraw(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(9.5f));
            p.setColor(DIM);
            c.drawText(n == 0 ? "touch here with one or more fingers" : n + " finger(s)", dp(8), dp(14), p);
            c.drawText("max " + maxN + " · touches " + taps, dp(8), getHeight() - dp(7), p);
            for (int i = 0; i < n; i++) {
                int col = colors[ids[i] % colors.length];
                p.setColor(col);
                p.setAlpha(60);
                c.drawCircle(xs[i], ys[i], dp(22), p);
                p.setAlpha(255);
                c.drawCircle(xs[i], ys[i], dp(5), p);
                p.setStrokeWidth(1);
                c.drawLine(0, ys[i], getWidth(), ys[i], p);
                c.drawLine(xs[i], 0, xs[i], getHeight(), p);
                p.setColor(TEXT);
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                c.drawText("#" + ids[i] + "  " + Math.round(xs[i] + loc[0]) + "," + Math.round(ys[i] + loc[1]) + "  p" + fmt2(ps[i]),
                        Math.min(xs[i] + dp(10), getWidth() - dp(110)), Math.max(ys[i] - dp(10), dp(26)), p);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // TAB: apps
    // ------------------------------------------------------------------------------------------

    private void buildApps() {
        LinearLayout[] c = grid(2);
        card(c[0], "Home & this app", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            PackageManager pm = getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            o.add(kv("Default HOME", ri == null || ri.activityInfo == null ? "none" : ri.activityInfo.packageName));
            List<ResolveInfo> homes = pm.queryIntentActivities(home, 0);
            for (ResolveInfo h : homes) o.add(kv("  candidate", h.activityInfo.packageName));
            Intent iot = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.IOT_LAUNCHER");
            for (ResolveInfo h : pm.queryIntentActivities(iot, 0)) o.add(kv("  IOT_LAUNCHER", h.activityInfo.packageName));
            PackageInfo me = pm.getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            o.add(sub("This app"));
            o.add(kv("Version", me.versionName + " (" + me.versionCode + ")"));
            o.add(kv("Installed", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(me.firstInstallTime))));
            o.add(kv("Updated", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(me.lastUpdateTime))));
            o.add(kv("APK", me.applicationInfo.sourceDir));
            if (me.requestedPermissions != null)
                for (int i = 0; i < me.requestedPermissions.length; i++) {
                    boolean g = (me.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                    o.add(kvc(me.requestedPermissions[i].replace("android.permission.", ""), g ? "granted" : "DENIED", g ? GOOD : BAD));
                }
        } });
        card(c[0], "WebView", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            PackageInfo p = WebView.getCurrentWebViewPackage();
            if (p == null) { o.add(kvc("Provider", "none", BAD)); return; }
            o.add(kv("Provider", p.packageName));
            o.add(kv("Version", p.versionName + " (" + p.versionCode + ")"));
            o.add(kv("APK", p.applicationInfo.sourceDir));
            o.add(kv("Updated", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(p.lastUpdateTime))));
        } });
        card(c[0], "System features", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            FeatureInfo[] fs = getPackageManager().getSystemAvailableFeatures();
            List<String> names = new ArrayList<>();
            for (FeatureInfo f : fs) if (f.name != null) names.add(f.name.replace("android.hardware.", "hw.").replace("android.software.", "sw."));
            Collections.sort(names);
            o.add(kv("Count", names.size()));
            for (String n : names) o.add(kv("•", n));
        } });

        card(c[1], "Installed packages", false, new Rows() { public void fill(List<Row> o) throws Throwable {
            PackageManager pm = getPackageManager();
            List<PackageInfo> all = pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS);
            Collections.sort(all, new Comparator<PackageInfo>() { public int compare(PackageInfo a, PackageInfo b) { return a.packageName.compareTo(b.packageName); } });
            int sys = 0, dis = 0;
            for (PackageInfo p : all) { if ((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) sys++; if (!p.applicationInfo.enabled) dis++; }
            o.add(kv("Total", all.size() + " · system " + sys + " · user " + (all.size() - sys) + " · disabled " + dis));
            for (PackageInfo p : all) {
                boolean s = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean en = p.applicationInfo.enabled;
                o.add(wide(p.packageName, orDash(p.versionName) + (s ? "" : " · user") + (en ? "" : " · OFF"), !en ? DIM : s ? 0 : ACCENT));
            }
        } });
    }

    // ------------------------------------------------------------------------------------------
    // wifi connect (unchanged behaviour from v1)
    // ------------------------------------------------------------------------------------------

    private void connectWifi() {
        if (WIFI_SSID.isEmpty()) { mWifiMsg = "no WIFI_SSID set"; return; }
        StringBuilder m = new StringBuilder();
        try {
            WifiManager wm = wifi();
            if (wm == null) { mWifiMsg = "no WifiManager"; return; }
            if (!wm.isWifiEnabled()) { wm.setWifiEnabled(true); m.append("enabled radio; "); }
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = "\"" + WIFI_SSID + "\"";
            c.preSharedKey = "\"" + WIFI_PASS + "\"";
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            c.status = WifiConfiguration.Status.ENABLED;
            c.priority = 1000;
            int id = wm.addNetwork(c);
            m.append("addNetwork=").append(id);
            if (id != -1) {
                wm.disconnect();
                boolean en = wm.enableNetwork(id, true);
                boolean rc = wm.reconnect();
                m.append(" enable=").append(en).append(" reconnect=").append(rc);
            } else m.append(" (add failed)");
        } catch (Throwable t) { m.append("ERR: ").append(t); }
        mWifiMsg = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + m;
    }

    // ------------------------------------------------------------------------------------------
    // background probes & GL
    // ------------------------------------------------------------------------------------------

    private void runProbes() {
        try {
            long t = System.nanoTime();
            InetAddress a = InetAddress.getByName("www.google.com");
            mProbeDns = a.getHostAddress() + " · " + ms(t);
        } catch (Throwable e) { mProbeDns = "fail: " + e.getClass().getSimpleName(); }
        HttpURLConnection c = null;
        try {
            long t = System.nanoTime();
            c = (HttpURLConnection) new URL("http://connectivitycheck.gstatic.com/generate_204").openConnection();
            c.setConnectTimeout(3000); c.setReadTimeout(3000); c.setUseCaches(false);
            int code = c.getResponseCode();
            mProbeHttp = code + " · " + ms(t);
        } catch (Throwable e) { mProbeHttp = "fail: " + e.getClass().getSimpleName(); }
        finally { if (c != null) c.disconnect(); }
        try {
            DhcpInfo d = wifi().getDhcpInfo();
            String gw = d == null || d.gateway == 0 ? null : intToIp(d.gateway);
            if (gw == null) mProbeGw = "no gateway";
            else {
                long t = System.nanoTime();
                Socket s = new Socket();
                try { s.connect(new InetSocketAddress(gw, 53), 1500); mProbeGw = gw + ":53 · " + ms(t); }
                catch (java.net.ConnectException ce) { mProbeGw = gw + " (refused, alive) · " + ms(t); }
                finally { try { s.close(); } catch (Throwable ignore) {} }
            }
        } catch (Throwable e) { mProbeGw = "fail: " + e.getClass().getSimpleName(); }
        mProbeAt = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String[] queryGl() {
        String[] r = {"?", "?", "?", "?", "?", "?"};
        EGLDisplay d = null; EGLContext ctx = null; EGLSurface s = null;
        try {
            d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            EGL14.eglInitialize(d, v, 0, v, 1);
            r[4] = v[0] + "." + v[1] + " · " + EGL14.eglQueryString(d, EGL14.EGL_VENDOR);
            int[] attr = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1]; int[] n = new int[1];
            EGL14.eglChooseConfig(d, attr, 0, cfg, 0, 1, n, 0);
            ctx = EGL14.eglCreateContext(d, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            s = EGL14.eglCreatePbufferSurface(d, cfg[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(d, s, s, ctx);
            r[0] = GLES20.glGetString(GLES20.GL_VENDOR);
            r[1] = GLES20.glGetString(GLES20.GL_RENDERER);
            r[2] = GLES20.glGetString(GLES20.GL_VERSION);
            r[3] = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION);
            String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            r[5] = ext == null ? "?" : String.valueOf(ext.trim().split("\\s+").length);
        } catch (Throwable t) {
            r[1] = "error: " + t;
        } finally {
            try {
                if (d != null) {
                    EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (s != null) EGL14.eglDestroySurface(d, s);
                    if (ctx != null) EGL14.eglDestroyContext(d, ctx);
                    EGL14.eglTerminate(d);
                }
            } catch (Throwable ignore) {}
        }
        return r;
    }

    // ------------------------------------------------------------------------------------------
    // periodic computations
    // ------------------------------------------------------------------------------------------

    private void updateTraffic() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), now = SystemClock.elapsedRealtime();
        if (mLastRx >= 0 && now > mLastTrafficT) {
            float dt = (now - mLastTrafficT) / 1000f;
            mRxRate = human((long) ((rx - mLastRx) / dt)) + "/s";
            mTxRate = human((long) ((tx - mLastTx) / dt)) + "/s";
        }
        mLastRx = rx; mLastTx = tx; mLastTrafficT = now;
    }

    private void updateCpu() {
        String l = readFirstLine("/proc/stat");
        if (l == null || !l.startsWith("cpu ")) return;
        String[] f = l.trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 1; i < f.length; i++) { long v = parseL(f[i]); total += v; if (i == 4 || i == 5) idle += v; }
        if (mLastCpuTotal >= 0 && total > mLastCpuTotal) {
            float u = 1f - (float) (idle - mLastCpuIdle) / (total - mLastCpuTotal);
            mCpuFrac = Math.max(0, Math.min(1, u));
            mCpuUsage = Math.round(mCpuFrac * 100) + "%";
        }
        mLastCpuTotal = total; mLastCpuIdle = idle;
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private WifiManager wifi() { return (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE); }
    private ConnectivityManager cm() { return (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE); }
    private Display display() { return getWindowManager().getDefaultDisplay(); }
    private DisplayMetrics realMetrics() { DisplayMetrics dm = new DisplayMetrics(); display().getRealMetrics(dm); return dm; }
    private ActivityManager.MemoryInfo memInfo() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi;
    }

    /** Android 8.1 hides the SSID without location permission; recover it from the saved network. */
    private String ssid(WifiInfo wi) {
        String s = unq(wi.getSSID());
        if (s != null && !s.equals("<unknown ssid>")) return s;
        try {
            List<WifiConfiguration> cs = wifi().getConfiguredNetworks();
            if (cs != null) for (WifiConfiguration c : cs) if (c.networkId == wi.getNetworkId()) return unq(c.SSID);
        } catch (Throwable ignore) {}
        return wi.getNetworkId() != -1 ? (WIFI_SSID.isEmpty() ? "connected" : WIFI_SSID + " (assumed)") : "—";
    }

    private String wifiIp() {
        try { int ip = wifi().getConnectionInfo().getIpAddress(); return ip == 0 ? null : intToIp(ip); } catch (Throwable t) { return null; }
    }

    private boolean isValidated() {
        try {
            ConnectivityManager cm = cm();
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    private LinkProperties linkProps() {
        ConnectivityManager cm = cm();
        Network n = cm.getActiveNetwork();
        return n == null ? null : cm.getLinkProperties(n);
    }

    private String glesVersionStr() {
        ConfigurationInfo ci = ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getDeviceConfigurationInfo();
        return ci.getGlEsVersion();
    }

    private int sysInt(String k, int def) {
        try { return Settings.System.getInt(getContentResolver(), k); } catch (Throwable t) { return def; }
    }
    private String glob(String k) {
        try { String v = Settings.Global.getString(getContentResolver(), k); return v == null ? "—" : v; } catch (Throwable t) { return "n/a"; }
    }

    private static String prop(String k) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, k);
        } catch (Throwable t) { return ""; }
    }

    // Paths that failed once (SELinux denial / missing) are never retried: each retry would
    // log an avc denial every second.
    private static final java.util.Set<String> sUnreadable = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static String readFile(String p) {
        if (sUnreadable.contains(p)) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(p))) {
            StringBuilder b = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) { if (b.length() > 0) b.append('\n'); b.append(l); }
            return b.toString().trim();
        } catch (Throwable t) { sUnreadable.add(p); return null; }
    }
    private static String readFirstLine(String p) {
        if (sUnreadable.contains(p)) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(p))) { return r.readLine(); }
        catch (Throwable t) { sUnreadable.add(p); return null; }
    }

    private static String cpuinfo(String key) {
        String all = readFile("/proc/cpuinfo");
        if (all == null) return null;
        for (String l : all.split("\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase(key)) return l.substring(i + 1).trim();
        }
        return null;
    }

    private static Map<String, Long> meminfo() {
        Map<String, Long> m = new HashMap<>();
        String all = readFile("/proc/meminfo");
        if (all == null) return m;
        for (String l : all.split("\n")) {
            int i = l.indexOf(':');
            if (i > 0) m.put(l.substring(0, i).trim(), parseL(l.substring(i + 1).replace("kB", "").trim()));
        }
        return m;
    }

    private static String statusField(String key) {
        String all = readFile("/proc/self/status");
        if (all == null) return null;
        for (String l : all.split("\n")) if (l.startsWith(key + ":")) return l.substring(key.length() + 1).trim();
        return null;
    }

    private static String loadAvg() {
        String l = readFirstLine("/proc/loadavg");
        if (l == null) return null;
        String[] f = l.split("\\s+");
        return f.length >= 3 ? f[0] + " " + f[1] + " " + f[2] : null;
    }

    private static String curFreqs() {
        long f = parseL(readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"));
        return f > 0 ? f / 1000 + " MHz" : "n/a";
    }

    private static String selinux() {
        String e = readFile("/sys/fs/selinux/enforce");
        if ("1".equals(e)) return "enforcing";
        if ("0".equals(e)) return "permissive";
        String p = prop("ro.boot.selinux");
        return p.isEmpty() ? "unknown (not readable)" : p;
    }

    private static String macOf(String ifname) {
        try {
            NetworkInterface n = NetworkInterface.getByName(ifname);
            byte[] mac = n == null ? null : n.getHardwareAddress();
            if (mac == null) return "n/a";
            StringBuilder b = new StringBuilder();
            for (byte x : mac) { if (b.length() > 0) b.append(':'); b.append(String.format("%02x", x)); }
            return b.toString();
        } catch (Throwable t) { return "n/a"; }
    }

    private static long parseL(String s) { try { return Long.parseLong(s.trim()); } catch (Throwable t) { return -1; } }
    private static String orDash(String s) { return s == null || s.isEmpty() ? "—" : s; }
    private static String yn(boolean b) { return b ? "yes" : "no"; }
    private static String unq(String s) { return s != null && s.length() > 1 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s; }
    private static String stripScope(String s) { int i = s.indexOf('%'); return i > 0 ? s.substring(0, i) : s; }
    private static String fmt1(float f) { return String.format(Locale.US, "%.1f", f); }
    private static String fmt2(float f) { return String.format(Locale.US, "%.2f", f); }
    private static String ms(long startNanos) { return (System.nanoTime() - startNanos) / 1000000 + " ms"; }
    private static int channel(int f) { return f >= 4915 ? (f - 5000) / 5 : f == 2484 ? 14 : (f - 2407) / 5; }
    private static float luxFrac(float lux) { return (float) Math.min(1.0, Math.log10(1 + lux) / 4.0); }

    private static String intToIp(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    private static String human(long bytes) {
        if (bytes < 0) return "n/a";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0; if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0; if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String dur(long s) {
        if (s < 60) return s + "s";
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        return m + "m " + (s % 60) + "s";
    }

    private static String wifiState(int s) {
        switch (s) {
            case WifiManager.WIFI_STATE_ENABLED: return "enabled";
            case WifiManager.WIFI_STATE_ENABLING: return "enabling";
            case WifiManager.WIFI_STATE_DISABLED: return "disabled";
            case WifiManager.WIFI_STATE_DISABLING: return "disabling";
            default: return "unknown";
        }
    }
    private static String btState(int s) {
        switch (s) {
            case BluetoothAdapter.STATE_ON: return "on";
            case BluetoothAdapter.STATE_OFF: return "off";
            case BluetoothAdapter.STATE_TURNING_ON: return "turning on";
            case BluetoothAdapter.STATE_TURNING_OFF: return "turning off";
            default: return String.valueOf(s);
        }
    }
    private static String displayState(int s) {
        switch (s) {
            case Display.STATE_ON: return "on";
            case Display.STATE_OFF: return "off";
            case Display.STATE_DOZE: return "doze";
            case Display.STATE_DOZE_SUSPEND: return "doze-suspend";
            default: return String.valueOf(s);
        }
    }
    private static String densityBucket(int dpi) {
        if (dpi <= 120) return "ldpi"; if (dpi <= 160) return "mdpi"; if (dpi <= 213) return "tvdpi";
        if (dpi <= 240) return "hdpi"; if (dpi <= 320) return "xhdpi"; if (dpi <= 480) return "xxhdpi"; return "xxxhdpi";
    }
    private static String screenSize(int layout) {
        switch (layout & Configuration.SCREENLAYOUT_SIZE_MASK) {
            case Configuration.SCREENLAYOUT_SIZE_SMALL: return "small";
            case Configuration.SCREENLAYOUT_SIZE_NORMAL: return "normal";
            case Configuration.SCREENLAYOUT_SIZE_LARGE: return "large";
            case Configuration.SCREENLAYOUT_SIZE_XLARGE: return "xlarge";
            default: return "undefined";
        }
    }
    private static String uiModeType(int t) {
        switch (t) {
            case Configuration.UI_MODE_TYPE_NORMAL: return "normal";
            case Configuration.UI_MODE_TYPE_DESK: return "desk";
            case Configuration.UI_MODE_TYPE_TELEVISION: return "television";
            case Configuration.UI_MODE_TYPE_APPLIANCE: return "appliance";
            case Configuration.UI_MODE_TYPE_WATCH: return "watch";
            default: return String.valueOf(t);
        }
    }
    private static String keyboardCfg(int k) {
        switch (k) {
            case Configuration.KEYBOARD_NOKEYS: return "none";
            case Configuration.KEYBOARD_QWERTY: return "QWERTY";
            case Configuration.KEYBOARD_12KEY: return "12-key";
            default: return "undefined";
        }
    }
    private static String navCfg(int n) {
        switch (n) {
            case Configuration.NAVIGATION_NONAV: return "none";
            case Configuration.NAVIGATION_DPAD: return "d-pad";
            case Configuration.NAVIGATION_TRACKBALL: return "trackball";
            case Configuration.NAVIGATION_WHEEL: return "wheel";
            default: return "undefined";
        }
    }
    private static String kbType(int t) {
        switch (t) {
            case InputDevice.KEYBOARD_TYPE_NONE: return "none";
            case InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC: return "buttons only";
            case InputDevice.KEYBOARD_TYPE_ALPHABETIC: return "full keyboard";
            default: return String.valueOf(t);
        }
    }
    private static String sources(int s) {
        List<String> l = new ArrayList<>();
        if ((s & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) l.add("touchscreen");
        if ((s & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) l.add("keyboard");
        if ((s & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) l.add("dpad");
        if ((s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) l.add("mouse");
        if ((s & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) l.add("touchpad");
        if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) l.add("gamepad");
        if ((s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) l.add("joystick");
        return l.isEmpty() ? String.format("0x%08x", s) : TextUtils.join(", ", l) + String.format(" (0x%x)", s);
    }
    private static String audioType(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "mic";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "headset";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "bt a2dp";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "bt sco";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "usb";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "telephony";
            case AudioDeviceInfo.TYPE_BUS: return "bus";
            default: return "type " + t;
        }
    }
}
