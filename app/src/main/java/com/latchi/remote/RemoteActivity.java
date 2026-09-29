package com.latchi.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 📱 LATCHI Remote v1.0.0 — ريموت تلفاز كامل لتطبيق LATCHI IPTV للحاسوب.
 * الاتصال: نفس شبكة الواي فاي — اكتشاف تلقائي (UDP) أو يدوي + رمز PIN.
 * الواجهة كلها برمجية (بلا XML) — Java صافية بلا أي اعتماديات خارجية.
 */
public class RemoteActivity extends Activity {

    // ═══ ثوابت ═══
    private static final int DISC_PORT = 37778;
    private static final String DISC_MSG = "LATCHI_REMOTE_DISCOVER";

    // ألوان LATCHI
    private static final int BG = 0xFF070B1C;
    private static final int PANEL = 0xFF121A38;
    private static final int PANEL_PRESS = 0xFF2A3568;
    private static final int STROKE = 0xFF2A3568;
    private static final int GOLD = 0xFFD9A94E;
    private static final int TXT = 0xFFE8ECFA;
    private static final int MUT = 0xFF8A90B8;
    private static final int GREEN = 0xFF39FF8B;
    private static final int RED = 0xFFFF5B5B;

    // ═══ حالة ═══
    private String host = null;          // ip الحاسوب
    private String hostName = "";
    private String pin = "";
    private boolean connected = false;
    private int fails = 0;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final List<TextView> foundRows = new ArrayList<>();
    private WifiManager.MulticastLock mlock = null;

    // عناصر
    private LinearLayout connectScreen, remoteScreen, foundList;
    private TextView searchStatus, headName, headState, nowPlaying;
    private EditText manualIp;

    // ═══════════════════ دورة الحياة ═══════════════════

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setContentView(root());
        try {
            mlock = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createMulticastLock("latchir");
            if (mlock != null) mlock.setReferenceCounted(false);
            if (mlock != null) mlock.acquire();
        } catch (Exception e) {}

        // اتصال سريع بآخر حاسوب إن وُجد — وإلا البحث
        SharedPreferences sp = getSharedPreferences("latchi_remote", MODE_PRIVATE);
        String lastHost = sp.getString("host", null);
        String lastPin = sp.getString("pin", "");
        if (lastHost != null) {
            host = lastHost; pin = lastPin;
            searchStatus.setText("⏳ محاولة الاتصال بآخر حاسوب…");
            net.execute(this::tryAutoConnect);
            startDiscovery();
        } else {
            startDiscovery();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (mlock != null) mlock.release(); } catch (Exception e) {}
        poll.removeCallbacksAndMessages(null);
        net.shutdownNow();
    }

    // أزرار الصوت في الهاتف نفسها تتحكم في الحاسوب
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (connected && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { sendCmd("{\"action\":\"volume\",\"delta\":0.05}"); return true; }
        if (connected && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { sendCmd("{\"action\":\"volume\",\"delta\":-0.05}"); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // ═══════════════════ الواجهة ═══════════════════

    private FrameLayout rootLay;

    private View root() { return rootLay; }

    @SuppressLint("RtlHardcoded")
    private void buildUi() {
        int dp = dp(1);
        rootLay = new FrameLayout(this);
        rootLay.setBackgroundColor(BG);

        // ───────── شاشة الاتصال ─────────
        ScrollView sc = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(24 * dp, 40 * dp, 24 * dp, 30 * dp);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sc.addView(c);
        connectScreen = c;

        TextView logo = new TextView(this);
        logo.setText("LATCHI Remote");
        logo.setTextSize(30);
        logo.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        logo.setTextColor(GOLD);
        logo.setGravity(Gravity.CENTER);
        c.addView(logo);

        TextView sub = new TextView(this);
        sub.setText("التحكم في تطبيق LATCHI IPTV للحاسوب من هاتفك");
        sub.setTextSize(14);
        sub.setTextColor(MUT);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 6 * dp, 0, 26 * dp);
        c.addView(sub);

        searchStatus = new TextView(this);
        searchStatus.setText("📡 جارٍ البحث عن حاسوب على الشبكة…");
        searchStatus.setTextSize(13);
        searchStatus.setTextColor(MUT);
        searchStatus.setGravity(Gravity.CENTER);
        searchStatus.setPadding(0, 0, 0, 12 * dp);
        c.addView(searchStatus);

        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        c.addView(foundList);

        TextView re = mkTxtBtn("🔄 إعادة البحث");
        re.setPadding(0, 8 * dp, 0, 4 * dp);
        re.setOnClickListener(v -> { haptic(v); startDiscovery(); });
        c.addView(re);

        // بطاقة الاتصال اليدوي
        LinearLayout man = panel();
        TextView mh = new TextView(this);
        mh.setText("أو اتصال يدوي");
        mh.setTextColor(TXT); mh.setTextSize(15); mh.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        man.addView(mh);
        manualIp = new EditText(this);
        manualIp.setHint("عنوان الحاسوب — مثال 192.168.1.10");
        manualIp.setTextColor(TXT); manualIp.setHintTextColor(0xFF5A5F85);
        manualIp.setTextSize(14);
        manualIp.setBackground(round(PANEL, 12 * dp, STROKE, 1 * dp));
        manualIp.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);
        man.addView(manualIp);
        LinearLayout.LayoutParams mp = (LinearLayout.LayoutParams) manualIp.getLayoutParams();
        mp.topMargin = 10 * dp; mp.bottomMargin = 10 * dp;
        manualIp.setLayoutParams(mp);
        Button go = mkBtn("🔗 اتصال", GOLD, TXT);
        go.setOnClickListener(v -> {
            haptic(v);
            String ip = manualIp.getText().toString().trim();
            if (ip.isEmpty()) { toast("أدخل عنوان الحاسوب"); return; }
            host = ip; pin = "";
            searchStatus.setText("⏳ جارٍ الاتصال بـ " + ip + " …");
            net.execute(() -> tryConnect(false));
        });
        man.addView(go);
        LinearLayout.LayoutParams manLp = new LinearLayout.LayoutParams(-1, -2);
        manLp.topMargin = 16 * dp;
        c.addView(man, manLp);

        TextView hint = new TextView(this);
        hint.setText("ℹ️ فعّل «ريموت الهاتف» من إعدادات تطبيق الحاسوب أولاً، واجعل الهاتف والحاسوب على نفس الواي فاي.");
        hint.setTextSize(12);
        hint.setTextColor(0xFF6A6F95);
        hint.setPadding(4 * dp, 20 * dp, 4 * dp, 0);
        c.addView(hint);

        // ───────── شاشة الريموت ─────────
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundColor(BG);
        r.setPadding(14 * dp, 16 * dp, 14 * dp, 12 * dp);
        r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        remoteScreen = r;

        LinearLayout head = panel();
        headName = new TextView(this);
        headName.setText("🖥 الحاسوب");
        headName.setTextColor(TXT); headName.setTextSize(16);
        headName.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        head.addView(headName);
        headState = new TextView(this);
        headState.setText("● متصل");
        headState.setTextColor(GREEN); headState.setTextSize(12);
        head.addView(headState);
        nowPlaying = new TextView(this);
        nowPlaying.setText("▶ لا شيء قيد التشغيل");
        nowPlaying.setTextColor(MUT); nowPlaying.setTextSize(13);
        nowPlaying.setSingleLine(true);
        nowPlaying.setPadding(0, 6 * dp, 0, 0);
        head.addView(nowPlaying);
        r.addView(head);

        // ─── لوحة الأسهم (D-Pad) ───
        FrameLayout pad = new FrameLayout(this);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        padLp.topMargin = 14 * dp;
        pad.setLayoutParams(padLp);

        Button ok = mkCircle("OK", 100 * dp, GOLD, 0xFF0A0E22, 22f);
        FrameLayout.LayoutParams okLp = new FrameLayout.LayoutParams(100 * dp, 100 * dp, Gravity.CENTER);
        ok.setOnClickListener(v -> { haptic(v); key("Enter"); });
        pad.addView(ok, okLp);

        Button up = mkCircle("▲", 66 * dp, PANEL, TXT, 20f);
        FrameLayout.LayoutParams upLp = new FrameLayout.LayoutParams(66 * dp, 66 * dp, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        up.setOnClickListener(v -> { haptic(v); key("ArrowUp"); });
        pad.addView(up, upLp);

        Button dn = mkCircle("▼", 66 * dp, PANEL, TXT, 20f);
        FrameLayout.LayoutParams dnLp = new FrameLayout.LayoutParams(66 * dp, 66 * dp, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dn.setOnClickListener(v -> { haptic(v); key("ArrowDown"); });
        pad.addView(dn, dnLp);

        Button lf = mkCircle("◀", 66 * dp, PANEL, TXT, 20f);
        FrameLayout.LayoutParams lfLp = new FrameLayout.LayoutParams(66 * dp, 66 * dp, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lf.setOnClickListener(v -> { haptic(v); key("ArrowLeft"); });
        pad.addView(lf, lfLp);

        Button rt = mkCircle("▶", 66 * dp, PANEL, TXT, 20f);
        FrameLayout.LayoutParams rtLp = new FrameLayout.LayoutParams(66 * dp, 66 * dp, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rt.setOnClickListener(v -> { haptic(v); key("ArrowRight"); });
        pad.addView(rt, rtLp);

        r.addView(pad);

        // ─── صف الأزرار 1: رجوع | الرئيسية | تشغيل/وقف ───
        LinearLayout row1 = row();
        row1.addView(flexBtn(row1, "↩ رجوع", "Escape"));
        row1.addView(flexBtn(row1, "🏠 الرئيسية", "Home"));
        row1.addView(flexBtn(row1, "⏯ تشغيل/وقف", " "));
        r.addView(row1);

        // ─── صف 2: صوت− | كتم | صوت+ ───
        LinearLayout row2 = row();
        row2.addView(flexBtn2(row2, "🔊 −", () -> sendCmd("{\"action\":\"volume\",\"delta\":-0.05}")));
        row2.addView(flexBtn2(row2, "🔇 كتم", () -> sendCmd("{\"action\":\"mute\"}")));
        row2.addView(flexBtn2(row2, "🔊 +", () -> sendCmd("{\"action\":\"volume\",\"delta\":0.05}")));
        r.addView(row2);

        // ─── صف 3: قناة+ | ملء الشاشة | قناة− ───
        LinearLayout row3 = row();
        row3.addView(flexBtn(row3, "➕ قناة", "PageUp"));
        row3.addView(flexBtn(row3, "⛶ ملء الشاشة", "f"));
        row3.addView(flexBtn(row3, "➖ قناة", "PageDown"));
        r.addView(row3);

        // ─── الأرقام 0-9 (قناة بالرقم) ───
        LinearLayout rowN = row();
        rowN.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // 0..9 من اليسار كيما التلفاز
        for (int i = 0; i <= 9; i++) {
            final String k = String.valueOf(i);
            Button n = mkBtn(k, PANEL, TXT);
            n.setTextSize(17);
            n.setOnClickListener(v -> { haptic(v); key(k); });
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -1, 1f);
            nlp.setMargins(3 * dp, 3 * dp, 3 * dp, 3 * dp);
            rowN.addView(n, nlp);
        }
        LinearLayout.LayoutParams rowNlp = new LinearLayout.LayoutParams(-1, 54 * dp);
        rowNlp.topMargin = 8 * dp;
        r.addView(rowN, rowNlp);

        rootLay.addView(sc);
        rootLay.addView(r);
        r.setVisibility(View.GONE);
    }

    /** زر بعرض متساوٍ داخل صف مع فعل عام */
    private View flexBtn2(LinearLayout row, String label, Runnable act) {
        Button b = mkBtn(label, PANEL, TXT);
        b.setOnClickListener(v -> { haptic(v); act.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(3 * dp(1), 3 * dp(1), 3 * dp(1), 3 * dp(1));
        row.addView(b, lp);
        return b;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 8 * dp(1);
        l.setLayoutParams(lp);
        return l;
    }

    private View flexBtn(LinearLayout row, String label, final String key) {
        Button b = mkBtn(label, PANEL, TXT);
        b.setOnClickListener(v -> { haptic(v); key(key); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(3 * dp(1), 3 * dp(1), 3 * dp(1), 3 * dp(1));
        row.addView(b, lp);
        return b;
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(round(PANEL, 16 * dp(1), STROKE, 1 * dp(1)));
        p.setPadding(16 * dp(1), 14 * dp(1), 16 * dp(1), 14 * dp(1));
        p.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return p;
    }

    private Button mkBtn(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        b.setBackground(press(bg, 13 * dp(1)));
        b.setPadding(6 * dp(1), 14 * dp(1), 6 * dp(1), 14 * dp(1));
        return b;
    }

    private TextView mkTxtBtn(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(GOLD);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private Button mkCircle(String label, int size, int bg, int fg, float ts) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(ts);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        b.setBackground(pressCircle(bg, size / 2));
        return b;
    }

    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor);
        return g;
    }

    private GradientDrawable circle(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), color == GOLD ? 0xFF8A6F35 : STROKE);
        return g;
    }

    /** خلفية بحالتي عادي/مضغوط — إحساس زر حقيقي */
    private StateListDrawable press(int normal, int radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(PANEL_PRESS, radius, GOLD, dp(1)));
        s.addState(new int[]{-android.R.attr.state_pressed}, round(normal, radius, STROKE, dp(1)));
        return s;
    }

    private StateListDrawable pressCircle(int normal, int r) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, circle(PANEL_PRESS, r));
        s.addState(new int[]{-android.R.attr.state_pressed}, circle(normal, r));
        return s;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void haptic(View v) { try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Exception e) {} }
    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ═══════════════════ الاتصال والاكتشاف ═══════════════════

    /** بث اكتشاف UDP على كل الشبكات + استقبال الردود 2.5 ثانية */
    private void startDiscovery() {
        searchStatus.setText("📡 جارٍ البحث عن حاسوب على الشبكة…");
        ui.post(() -> { foundList.removeAllViews(); foundRows.clear(); });
        new Thread(() -> {
            Set<String> seen = new HashSet<>();
            DatagramSocket s = null;
            try {
                s = new DatagramSocket();
                s.setBroadcast(true);
                s.setSoTimeout(400);
                byte[] msg = DISC_MSG.getBytes(StandardCharsets.UTF_8);
                List<InetAddress> targets = new ArrayList<>();
                targets.add(InetAddress.getByName("255.255.255.255"));
                // بث موجّه لكل واجهة (أدق من البث العام على بعض الراوترات)
                try {
                    Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
                    while (ns != null && ns.hasMoreElements()) {
                        NetworkInterface n = ns.nextElement();
                        if (!n.isUp() || n.isLoopback()) continue;
                        Enumeration<InetAddress> as = n.getInetAddresses();
                        while (as.hasMoreElements()) {
                            InetAddress a = as.nextElement();
                            if (a.getAddress().length == 4) {
                                byte[] b = a.getAddress();
                                b[3] = (byte) 255;
                                targets.add(InetAddress.getByAddress(b));
                            }
                        }
                    }
                } catch (Exception e) {}
                long end = System.currentTimeMillis() + 2600;
                while (System.currentTimeMillis() < end) {
                    for (InetAddress t : targets) {
                        try { s.send(new DatagramPacket(msg, msg.length, t, DISC_PORT)); } catch (Exception e) {}
                    }
                    byte[] buf = new byte[512];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        s.receive(p);
                        String json = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (json.contains("LATCHI_REMOTE") && !seen.contains(p.getAddress().getHostAddress())) {
                            seen.add(p.getAddress().getHostAddress());
                            final String ip = p.getAddress().getHostAddress();
                            final String name = nz(jstr(json, "name"), "حاسوب LATCHI");
                            final boolean needPin = json.contains("\"pin\":true") || json.contains("\"pin\": true");
                            final int port = jint(json, "port", 37777);
                            ui.post(() -> addFoundRow(ip, name, port, needPin));
                        }
                    } catch (SocketTimeoutException te) { /* نكشف البث من جديد */ }
                }
            } catch (Exception e) {
                // لا شيء — الوضع اليدوي متاح دائماً
            } finally {
                try { if (s != null && !s.isClosed()) s.close(); } catch (Exception e) {}
            }
            final int n = seen.size();
            ui.post(() -> {
                if (n == 0 && !connected) searchStatus.setText("لم أجد حاسوباً — تأكد من تفعيل الريموت في إعدادات الحاسوب");
            });
        }).start();
    }

    private void addFoundRow(String ip, String name, int port, boolean needPin) {
        if (connected) return;
        LinearLayout card = panel();
        TextView t = new TextView(this);
        t.setText("🖥 " + name);
        t.setTextColor(TXT); t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        card.addView(t);
        TextView d = new TextView(this);
        d.setText(ip + (needPin ? "  •  محمي برمز" : "") + "  —  انقر للربط");
        d.setTextColor(MUT); d.setTextSize(12);
        d.setPadding(0, 4 * dp(1), 0, 0);
        card.addView(d);
        card.setOnClickListener(v -> {
            haptic(v);
            host = ip; pin = "";
            searchStatus.setText("⏳ جارٍ الاتصال بـ " + name + " …");
            net.execute(() -> tryConnect(needPin));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = 10 * dp(1);
        foundList.addView(card, 0);
    }

    /** محاولة اتصال: تحقق من /ping ثم (PIN عند اللزوم) ثم الدخول لشاشة الريموت */
    private void tryConnect(final boolean needPinDirect) {
        try {
            HttpResp r = http("GET", "http://" + host + ":37777/ping", null, pin);
            if (r.code != 200) throw new Exception("HTTP " + r.code);
            String name = nz(jstr(r.body, "name"), "حاسوب LATCHI");
            boolean needPin = r.body.contains("\"pin\":true") || r.body.contains("\"pin\": true");
            hostName = name;
            if (needPin) {
                final String srvPin = askPinSync();
                if (srvPin == null) { ui.post(() -> searchStatus.setText("أُلغي الإدخال")); return; }
                HttpResp st = http("GET", "http://" + host + ":37777/status", null, srvPin);
                if (st.code == 401) { toast("رمز الربط غير صحيح"); return; }
                pin = srvPin;
            }
            final String nm = name;
            ui.post(() -> enterRemote(nm));
        } catch (Exception e) {
            ui.post(() -> searchStatus.setText("✗ تعذّر الوصول للحاسوب — تحقق من العنوان والشبكة"));
        }
    }

    private void tryAutoConnect() {
        try {
            HttpResp r = http("GET", "http://" + host + ":37777/ping", null, pin);
            if (r.code != 200) throw new Exception("x");
            if (r.body.contains("\"pin\":true") && pin.isEmpty()) { ui.post(this::showConnect); return; }
            HttpResp st = http("GET", "http://" + host + ":37777/status", null, pin);
            if (st.code == 401) { ui.post(this::showConnect); return; }
            final String nm = nz(jstr(r.body, "name"), "حاسوب LATCHI");
            hostName = nm;
            ui.post(() -> enterRemote(nm));
        } catch (Exception e) { ui.post(this::showConnect); }
    }

    /** ينتظر رمز PIN من المستخدم (حوار برمجي) — null = إلغاء */
    private String askPinSync() {
        final String[] out = {null};
        final Object lock = new Object();
        ui.post(() -> {
            LinearLayout wrap = new LinearLayout(RemoteActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(dp(8), dp(8), dp(8), dp(4));
            final EditText in = new EditText(RemoteActivity.this);
            in.setHint("رمز الربط من إعدادات الحاسوب");
            in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            in.setTextColor(TXT); in.setHintTextColor(0xFF5A5F85);
            in.setBackground(round(PANEL, dp(10), STROKE, dp(1)));
            in.setPadding(dp(12), dp(12), dp(12), dp(12));
            wrap.addView(in);
            new android.app.AlertDialog.Builder(RemoteActivity.this)
                    .setTitle("🔐 ربط بالحاسوب")
                    .setMessage("أدخل رمز الربط الظاهر في إعدادات تطبيق الحاسوب (قسم ريموت الهاتف)")
                    .setView(wrap)
                    .setPositiveButton("ربط", (d, w) -> { out[0] = in.getText().toString().trim(); synchronized (lock) { lock.notifyAll(); } })
                    .setNegativeButton("إلغاء", (d, w) -> { synchronized (lock) { lock.notifyAll(); } })
                    .setOnCancelListener(d -> { synchronized (lock) { lock.notifyAll(); } })
                    .show();
        });
        try { synchronized (lock) { lock.wait(120000); } } catch (InterruptedException e) {}
        return out[0];
    }

    // ═══════════════════ شاشة الريموت ═══════════════════

    private void showConnect() {
        if (connected) return;
        connectScreen.setVisibility(View.VISIBLE);
        remoteScreen.setVisibility(View.GONE);
        poll.removeCallbacksAndMessages(null);
    }

    private void enterRemote(String name) {
        connected = true;
        fails = 0;
        headName.setText("🖥 " + name);
        hostName = name;
        connectScreen.setVisibility(View.GONE);
        remoteScreen.setVisibility(View.VISIBLE);
        SharedPreferences.Editor ed = getSharedPreferences("latchi_remote", MODE_PRIVATE).edit();
        ed.putString("host", host).putString("pin", pin).apply();
        toast("✓ متصل بـ " + name);
        pollStatus();
    }

    private void pollStatus() {
        poll.removeCallbacksAndMessages(null);
        poll.postDelayed(new Runnable() {
            @Override public void run() {
                if (!connected) return;
                net.execute(() -> {
                    try {
                        HttpResp r = http("GET", "http://" + host + ":37777/status", null, pin);
                        if (r.code == 200) {
                            fails = 0;
                            final String np = jstr(r.body, "nowPlaying");
                            final String scr = jstr(r.body, "screen");
                            ui.post(() -> {
                                headState.setText("● متصل");
                                headState.setTextColor(GREEN);
                                nowPlaying.setText((np != null && !np.isEmpty()) ? "▶ " + np : "▶ لا شيء قيد التشغيل" + (scr == null ? "" : ""));
                                nowPlaying.setTextColor((np != null && !np.isEmpty()) ? GOLD : MUT);
                            });
                        } else if (r.code == 401) {
                            ui.post(() -> { toast("رمز الربط تغيّر — أعد الربط"); connected = false; showConnect(); });
                        } else throw new Exception("HTTP " + r.code);
                    } catch (Exception e) {
                        fails++;
                        if (fails >= 3) {
                            ui.post(() -> {
                                headState.setText("○ منقطع…");
                                headState.setTextColor(RED);
                            });
                            if (fails >= 6) {
                                ui.post(() -> { toast("انقطع الاتصال بالحاسوب"); connected = false; showConnect(); });
                            }
                        }
                    }
                    if (connected) poll.postDelayed(this, 2000);
                });
            }
        }, 1500);
    }

    // ═══════════════════ الشبكة ═══════════════════

    private void key(String k) { sendCmd("{\"key\":" + q(k) + "}"); }

    private void sendCmd(final String json) {
        if (host == null) return;
        if (!connected) { toast("غير متصل"); return; }
        net.execute(() -> {
            try {
                HttpResp r = http("POST", "http://" + host + ":37777/cmd", json, pin);
                if (r.code == 200) { if (fails > 0) { fails = 0; ui.post(() -> { headState.setText("● متصل"); headState.setTextColor(GREEN); }); } }
                else if (r.code == 401) ui.post(() -> { toast("رمز الربط غير صحيح"); });
            } catch (Exception e) {
                fails++;
                ui.post(() -> { headState.setText("○ منقطع…"); headState.setTextColor(RED); });
            }
        });
    }

    private static class HttpResp { int code; String body; }

    private HttpResp http(String method, String url, String body, String pinHeader) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(3000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (pinHeader != null && !pinHeader.isEmpty()) c.setRequestProperty("x-pin", pinHeader);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            HttpResp r = new HttpResp();
            r.code = c.getResponseCode();
            BufferedReader br = new BufferedReader(new InputStreamReader(
                    r.code >= 400 ? c.getErrorStream() : c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            r.body = sb.toString();
            return r;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception e) {}
        }
    }

    // ═══════════════════ JSON مصغّر (بلا مكتبات) ═══════════════════

    /** قيمة نصية من JSON — تتعامل مع \" */
    static String jstr(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        i = json.indexOf(':', i + pat.length());
        if (i < 0) return null;
        i++;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) { char n = json.charAt(i + 1); sb.append(n == 'n' ? '\n' : n); i += 2; continue; }
            if (ch == '"') break;
            sb.append(ch); i++;
        }
        return sb.toString();
    }

    static int jint(String json, String key, int def) {
        if (json == null) return def;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return def;
        i = json.indexOf(':', i + pat.length());
        if (i < 0) return def;
        i++;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        int s = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        try { return Integer.parseInt(json.substring(s, i)); } catch (Exception e) { return def; }
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\');
            sb.append(ch);
        }
        return sb.append('"').toString();
    }

    static String nz(String s, String def) { return (s == null || s.isEmpty()) ? def : s; }

    // زر الرجوع في شاشة الريموت = قطع والخروج للاتصال
    @Override
    public void onBackPressed() {
        if (connected) {
            connected = false;
            showConnect();
            startDiscovery();
            return;
        }
        super.onBackPressed();
    }
}
