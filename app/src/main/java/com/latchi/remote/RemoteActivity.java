package com.latchi.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
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
 * 📱 LATCHI Remote v1.0.2 — ريموت احترافي لتطبيق LATCHI IPTV للحاسوب.
 *
 * التجربة: فتح التطبيق → «البحث عن الحاسوب» → اكتشاف تلقائي (UDP) → اتصال تلقائي بلا أي كود
 * → لوحة تحكم احترافية: تنقل (D-Pad مدمج) + لوحة لمس (مؤشر/نقر/تمرير حقيقي)
 * + لوحة أرقام (0-9 + ⌫ + ↵) + وسائط (تشغيل/قنوات/صوت/ملء شاشة).
 *
 * - الواجهة كلها برمجية (بلا XML) — Java صافية بلا أي اعتماديات خارجية.
 * - النصوص كلها من strings.xml (عربي/إنجليزي/فرنسي).
 * - الحاسوب (v1.0.0+) يشغّل الخادم تلقائياً منذ الإقلاع بلا رمز — الاتصال فوري.
 * - إعادة اتصال تلقائية عند الانقطاع قبل مطالبة المستخدم.
 */
public class RemoteActivity extends Activity {

    // ═══ ثوابت ═══
    private static final int DISC_PORT = 37778;
    private static final String DISC_MSG = "LATCHI_REMOTE_DISCOVER";

    // ألوان LATCHI
    private static final int BG = 0xFF070B1C;
    private static final int PANEL = 0xFF121A38;
    private static final int PANEL2 = 0xFF0B1129;
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
    private boolean searching = false;
    private boolean reconnecting = false;
    private boolean userLeft = false;      // المستخدم قطع بنفسه — لا تعِد الاتصال تلقائياً

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final List<TextView> foundRows = new ArrayList<>();
    private WifiManager.MulticastLock mlock = null;

    // عناصر شاشة البحث
    private ScrollView connectScreen;
    private LinearLayout foundList, notFoundCard;
    private TextView searchStatus, searchIcon;
    private Button searchBtn;

    // عناصر لوحة التحكم
    private LinearLayout remoteScreen;
    private TextView headName, headState, nowPlaying;

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

        // اتصال صامت بآخر حاسوب إن وُجد — وإلا تبقى شاشة البحث (زر واضح)
        SharedPreferences sp = getSharedPreferences("latchi_remote", MODE_PRIVATE);
        String lastHost = sp.getString("host", null);
        String lastPin = sp.getString("pin", "");
        if (lastHost != null && !lastHost.isEmpty()) {
            host = lastHost; pin = lastPin; userLeft = false;
            searchStatus.setText(getString(R.string.connecting_to, lastHost));
            net.execute(this::tryAutoConnect);
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

    @SuppressLint({"RtlHardcoded", "ClickableViewAccessibility"})
    private void buildUi() {
        rootLay = new FrameLayout(this);
        rootLay.setBackgroundColor(BG);
        buildConnectScreen();
        buildRemoteScreen();
        rootLay.addView(connectScreen);
        rootLay.addView(remoteScreen);
        remoteScreen.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────
    // ① شاشة البحث — زر رئيسي واحد + حالات أنيقة
    // ─────────────────────────────────────────────
    @SuppressLint("RtlHardcoded")
    private void buildConnectScreen() {
        ScrollView sc = new ScrollView(this);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(24 * dp(1), 42 * dp(1), 24 * dp(1), 30 * dp(1));
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sc.addView(c);
        connectScreen = sc;   // الشاشة القابلة للإظهار/الإخفاء = السكرول نفسه

        TextView logo = new TextView(this);
        logo.setText("LATCHI Remote");
        logo.setTextSize(30);
        logo.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        logo.setTextColor(GOLD);
        logo.setGravity(Gravity.CENTER);
        c.addView(logo);

        TextView sub = new TextView(this);
        sub.setText(R.string.subtitle);
        sub.setTextSize(14);
        sub.setTextColor(MUT);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 6 * dp(1), 0, 30 * dp(1));
        c.addView(sub);

        // الزر الرئيسي
        searchBtn = mkBtn(getString(R.string.search_btn), GOLD, 0xFF0A0E22);
        searchBtn.setTextSize(18);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, 60 * dp(1));
        c.addView(searchBtn, sbLp);
        searchBtn.setOnClickListener(v -> {
            haptic(v);
            startDiscovery();
        });

        // حالة البحث: أيقونة تدور + نص
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setGravity(Gravity.CENTER);
        st.setPadding(0, 26 * dp(1), 0, 6 * dp(1));
        searchIcon = new TextView(this);
        searchIcon.setText("📡");
        searchIcon.setTextSize(30);
        searchIcon.setGravity(Gravity.CENTER);
        searchIcon.setVisibility(View.GONE);
        st.addView(searchIcon);
        searchStatus = new TextView(this);
        searchStatus.setText(R.string.searching);
        searchStatus.setTextSize(14);
        searchStatus.setTextColor(MUT);
        searchStatus.setGravity(Gravity.CENTER);
        searchStatus.setPadding(0, 8 * dp(1), 0, 4 * dp(1));
        st.addView(searchStatus);
        c.addView(st);
        startSpin(searchIcon);

        // قائمة الحواسيب المكتشفة
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        c.addView(foundList);

        // حالة «لم يتم العثور» — أنيقة بلا أخطاء تقنية
        notFoundCard = panel();
        notFoundCard.setGravity(Gravity.CENTER);
        notFoundCard.setPadding(16 * dp(1), 26 * dp(1), 16 * dp(1), 22 * dp(1));
        TextView nf1 = new TextView(this);
        nf1.setText(R.string.not_found_title);
        nf1.setTextColor(TXT); nf1.setTextSize(17);
        nf1.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        nf1.setGravity(Gravity.CENTER);
        notFoundCard.addView(nf1);
        TextView nf2 = new TextView(this);
        nf2.setText(R.string.not_found_hint);
        nf2.setTextColor(MUT); nf2.setTextSize(13);
        nf2.setGravity(Gravity.CENTER);
        nf2.setPadding(0, 8 * dp(1), 0, 16 * dp(1));
        notFoundCard.addView(nf2);
        Button retry = mkBtn(getString(R.string.retry_search), PANEL, TXT);
        notFoundCard.addView(retry, new LinearLayout.LayoutParams(-1, 50 * dp(1)));
        retry.setOnClickListener(v -> { haptic(v); startDiscovery(); });
        notFoundCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams nfLp = new LinearLayout.LayoutParams(-1, -2);
        nfLp.topMargin = 8 * dp(1);
        c.addView(notFoundCard, nfLp);

        // إعدادات متقدمة (يدوي — للصيانة فقط)
        TextView adv = new TextView(this);
        adv.setText(R.string.advanced_settings);
        adv.setTextColor(0xFF565C87);
        adv.setTextSize(12);
        adv.setGravity(Gravity.CENTER);
        adv.setPadding(0, 22 * dp(1), 0, 0);
        c.addView(adv);
        adv.setOnClickListener(v -> showManualDialog());
    }

    /** حوار الاتصال اليدوي — داخل «إعدادات متقدمة» فقط */
    private void showManualDialog() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(10), dp(6), dp(10), dp(2));
        final EditText in = new EditText(this);
        in.setHint(R.string.manual_ip_hint);
        in.setTextColor(TXT); in.setHintTextColor(0xFF5A5F85);
        in.setTextSize(14);
        in.setBackground(round(PANEL2, dp(12), STROKE, dp(1)));
        in.setPadding(dp(12), dp(12), dp(12), dp(12));
        wrap.addView(in);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.manual_title)
                .setView(wrap)
                .setPositiveButton(R.string.connect, (d, w) -> {
                    String ip = in.getText().toString().trim();
                    if (ip.isEmpty()) { toast(getString(R.string.enter_ip)); return; }
                    host = ip; pin = ""; userLeft = false;
                    searchStatus.setText(getString(R.string.connecting_to, ip));
                    net.execute(() -> tryConnect(false));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ─────────────────────────────────────────────
    // ② لوحة التحكم — شريط حالة + 4 أقسام
    // ─────────────────────────────────────────────
    @SuppressLint("RtlHardcoded")
    private void buildRemoteScreen() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundColor(BG);
        r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        remoteScreen = r;

        // ── شريط الحالة المصغر ──
        LinearLayout head = panel();
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(-1, -2);
        headRow.setLayoutParams(hrLp);

        headName = new TextView(this);
        headName.setText("🖥 " + getString(R.string.found_pc));
        headName.setTextColor(TXT); headName.setTextSize(16);
        headName.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        headName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        headRow.addView(headName);

        headState = new TextView(this);
        headState.setText(R.string.state_connected);
        headState.setTextColor(GREEN); headState.setTextSize(12);
        int hsPad = dp(8);
        headState.setPadding(hsPad, 0, hsPad, 0);
        headRow.addView(headState);

        Button disc = mkBtn(getString(R.string.disconnect), PANEL2, MUT);
        disc.setTextSize(12);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, 36 * dp(1));
        disc.setPadding(dp(12), 0, dp(12), 0);
        disc.setOnClickListener(v -> {
            haptic(v);
            userLeft = true;
            connected = false;
            showConnect();
        });
        headRow.addView(disc, dLp);
        head.addView(headRow);

        nowPlaying = new TextView(this);
        nowPlaying.setText(R.string.nothing_playing);
        nowPlaying.setTextColor(MUT); nowPlaying.setTextSize(13);
        nowPlaying.setSingleLine(true);
        nowPlaying.setPadding(0, 7 * dp(1), 0, 0);
        head.addView(nowPlaying);
        r.addView(head);

        // ── الأقسام (تمرير عمودي) ──
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(12 * dp(1), 12 * dp(1), 12 * dp(1), 20 * dp(1));
        s.addView(body);

        // ═ القسم 1: التنقل — D-Pad مدمج مريح للإبهام ═
        LinearLayout nav = section(R.string.sec_navigation);

        FrameLayout pad = new FrameLayout(this);
        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, 212 * dp(1));
        padLp.topMargin = 10 * dp(1);
        pad.setLayoutParams(padLp);

        Button ok = mkCircle("OK", 88 * dp(1), GOLD, 0xFF0A0E22, 22f);
        FrameLayout.LayoutParams okLp = new FrameLayout.LayoutParams(88 * dp(1), 88 * dp(1), Gravity.CENTER);
        ok.setOnClickListener(v -> { haptic(v); key("Enter"); });
        pad.addView(ok, okLp);

        Button up = mkCircle("▲", 58 * dp(1), PANEL, TXT, 19f);
        FrameLayout.LayoutParams upLp = new FrameLayout.LayoutParams(58 * dp(1), 58 * dp(1), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        upLp.topMargin = 2 * dp(1);
        up.setOnClickListener(v -> { haptic(v); key("ArrowUp"); });
        pad.addView(up, upLp);

        Button dn = mkCircle("▼", 58 * dp(1), PANEL, TXT, 19f);
        FrameLayout.LayoutParams dnLp = new FrameLayout.LayoutParams(58 * dp(1), 58 * dp(1), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dnLp.bottomMargin = 2 * dp(1);
        dn.setOnClickListener(v -> { haptic(v); key("ArrowDown"); });
        pad.addView(dn, dnLp);

        Button lf = mkCircle("◀", 58 * dp(1), PANEL, TXT, 19f);
        FrameLayout.LayoutParams lfLp = new FrameLayout.LayoutParams(58 * dp(1), 58 * dp(1), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lfLp.leftMargin = 2 * dp(1);
        lf.setOnClickListener(v -> { haptic(v); key("ArrowLeft"); });
        pad.addView(lf, lfLp);

        Button rt = mkCircle("▶", 58 * dp(1), PANEL, TXT, 19f);
        FrameLayout.LayoutParams rtLp = new FrameLayout.LayoutParams(58 * dp(1), 58 * dp(1), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rtLp.rightMargin = 2 * dp(1);
        rt.setOnClickListener(v -> { haptic(v); key("ArrowRight"); });
        pad.addView(rt, rtLp);
        nav.addView(pad);

        LinearLayout navRow = row();
        flexKey(navRow, R.string.btn_back, "Escape");
        flexKey(navRow, R.string.btn_home, "Home");
        nav.addView(navRow);
        body.addView(nav);

        // ═ القسم 2: لوحة اللمس — تحريك/نقر/تمرير حقيقي ═
        LinearLayout tp = section(R.string.sec_touchpad);

        FrameLayout padArea = new FrameLayout(this);
        padArea.setBackground(round(PANEL2, dp(18), STROKE, dp(1)));
        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(-1, 168 * dp(1));
        paLp.topMargin = 10 * dp(1);
        padArea.setLayoutParams(paLp);

        TextView padHint = new TextView(this);
        padHint.setText(R.string.pad_hint);
        padHint.setTextColor(0xFF4E5580);
        padHint.setTextSize(12);
        padHint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams phLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        padArea.addView(padHint, phLp);
        padArea.setOnTouchListener((v, ev) -> touchpad(ev));
        tp.addView(padArea);

        LinearLayout scRow = row();
        flexCmd(scRow, R.string.scroll_up, () -> sendCmd("{\"action\":\"mouse\",\"wheel\":-1}"));
        flexCmd(scRow, R.string.scroll_down, () -> sendCmd("{\"action\":\"mouse\",\"wheel\":1}"));
        tp.addView(scRow);
        body.addView(tp);

        // ═ القسم 3: لوحة الأرقام — 0-9 + ⌫ + ↵ (أحداث حقيقية) ═
        LinearLayout num = section(R.string.sec_numbers);
        // الصفوف: 1..9 ثم [⌫][0][↵]
        LinearLayout r1 = row(); r1.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 1; i <= 3; i++) numKey(r1, String.valueOf(i));
        LinearLayout r2 = row(); r2.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 4; i <= 6; i++) numKey(r2, String.valueOf(i));
        LinearLayout r3 = row(); r3.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 7; i <= 9; i++) numKey(r3, String.valueOf(i));
        LinearLayout r4 = row(); r4.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button del = mkBtn(getString(R.string.key_del), PANEL, GOLD);
        del.setTextSize(20);
        bindKey(del, "Backspace");
        weight(r4, del, 58);
        numKey(r4, "0");
        Button ent = mkBtn(getString(R.string.key_enter), PANEL, GOLD);
        ent.setTextSize(20);
        bindKey(ent, "Enter");
        weight(r4, ent, 58);
        num.addView(r1); num.addView(r2); num.addView(r3); num.addView(r4);
        body.addView(num);

        // ═ القسم 4: الوسائط ═
        LinearLayout md = section(R.string.sec_media);
        LinearLayout m1 = row();
        flexKey(m1, R.string.btn_play_pause, " ");
        flexKey(m1, R.string.btn_fs, "f");
        md.addView(m1);
        LinearLayout m2 = row();
        flexKey(m2, R.string.btn_ch_up, "PageUp");
        flexKey(m2, R.string.btn_ch_down, "PageDown");
        md.addView(m2);
        LinearLayout m3 = row();
        flexCmd(m3, R.string.btn_vol_down, () -> sendCmd("{\"action\":\"volume\",\"delta\":-0.05}"));
        flexCmd(m3, R.string.btn_mute, () -> sendCmd("{\"action\":\"mute\"}"));
        flexCmd(m3, R.string.btn_vol_up, () -> sendCmd("{\"action\":\"volume\",\"delta\":0.05}"));
        md.addView(m3);
        body.addView(md);

        r.addView(s, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    // ═══════════════════ مكونات مساعدة ═══════════════════

    /** لوحة لمس: سحب = تحريك المؤشر (مجمّع كل 40ms) — نقرة سريعة = نقر — الأزرار للتمرير */
    private float padLastX = 0, padLastY = 0, accX = 0, accY = 0;
    private long padDownT = 0, lastSent = 0;
    private boolean padMoved = false;

    @SuppressLint("ClickableViewAccessibility")
    private boolean touchpad(MotionEvent ev) {
        final int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            padLastX = ev.getX(); padLastY = ev.getY();
            accX = 0; accY = 0; padMoved = false;
            padDownT = SystemClock.uptimeMillis();
            return true;
        }
        if (a == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - padLastX, dy = ev.getY() - padLastY;
            padLastX = ev.getX(); padLastY = ev.getY();
            if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) padMoved = true;
            accX += dx; accY += dy;
            long now = SystemClock.uptimeMillis();
            if (now - lastSent >= 40) { flushPad(); lastSent = now; }
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            flushPad();
            if (!padMoved && SystemClock.uptimeMillis() - padDownT < 300) {
                sendCmd("{\"action\":\"mouse\",\"click\":true}");
            }
            return true;
        }
        return false;
    }

    private void flushPad() {
        if (Math.abs(accX) >= 0.5f || Math.abs(accY) >= 0.5f) {
            sendCmd("{\"action\":\"mouse\",\"dx\":" + (int) accX + ",\"dy\":" + (int) accY + "}");
        }
        accX = 0; accY = 0;
    }

    /** قسم بعنوان ذهبي صغير */
    private LinearLayout section(int titleRes) {
        LinearLayout p = panel();
        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextColor(GOLD); t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        p.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 10 * dp(1);
        p.setLayoutParams(lp);
        return p;
    }

    /** دوران خفيف مستمر — لأنيميشن البحث */
    private void startSpin(View v) {
        RotateAnimation rot = new RotateAnimation(0, 360,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        rot.setDuration(1400);
        rot.setRepeatCount(Animation.INFINITE);
        rot.setInterpolator(new LinearInterpolator());
        v.startAnimation(rot);
    }

    /** زر رقمي في شبكة لوحة الأرقام */
    private void numKey(LinearLayout rowLayout, String k) {
        Button n = mkBtn(k, PANEL, TXT);
        n.setTextSize(20);
        bindKey(n, k);
        weight(rowLayout, n, 58);
    }

    private void bindKey(Button b, final String k) {
        b.setOnClickListener(v -> { haptic(v); key(k); });
    }

    private void weight(LinearLayout rowLayout, Button b, int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, hDp * dp(1), 1f);
        int m = dp(3);
        lp.setMargins(m, m, m, m);
        rowLayout.addView(b, lp);
    }

    /** زر بعرض متساوٍ داخل صف — يرسل مفتاحاً */
    private void flexKey(LinearLayout rowLayout, int labelRes, final String key) {
        Button b = mkBtn(getString(labelRes), PANEL, TXT);
        bindKey(b, key);
        weight(rowLayout, b, 50);
    }

    /** زر بعرض متساوٍ داخل صف — فعل عام */
    private void flexCmd(LinearLayout rowLayout, int labelRes, Runnable act) {
        Button b = mkBtn(getString(labelRes), PANEL, TXT);
        b.setOnClickListener(v -> { haptic(v); act.run(); });
        weight(rowLayout, b, 50);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 6 * dp(1);
        l.setLayoutParams(lp);
        return l;
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
        b.setPadding(6 * dp(1), 12 * dp(1), 6 * dp(1), 12 * dp(1));
        return b;
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

    /** بث اكتشاف UDP على كل الشبكات + استقبال الردود 2.6 ثانية — ثم اتصال تلقائي بأول حاسوب */
    private void startDiscovery() {
        if (searching) return;
        searching = true;
        userLeft = false;
        ui.post(() -> {
            foundList.removeAllViews(); foundRows.clear();
            notFoundCard.setVisibility(View.GONE);
            searchBtn.setEnabled(false);
            searchBtn.setTextColor(GOLD);
            searchIcon.setVisibility(View.VISIBLE);
            searchStatus.setText(R.string.searching);
        });
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
                            final String name = nz(jstr(json, "name"), getString(R.string.found_pc));
                            final boolean needPin = json.contains("\"pin\":true") || json.contains("\"pin\": true");
                            final int port = jint(json, "port", 37777);
                            ui.post(() -> addFoundRow(ip, name, port, needPin, seen.size() == 1));
                        }
                    } catch (SocketTimeoutException te) { /* نكشف البث من جديد */ }
                }
            } catch (Exception e) {
                // لا شيء — الوضع اليدوي متاح دائماً
            } finally {
                try { if (s != null && !s.isClosed()) s.close(); } catch (Exception e) {}
            }
            final int n = seen.size();
            final boolean wasFirst = (n > 0);
            ui.post(() -> {
                searching = false;
                searchBtn.setEnabled(true);
                searchBtn.setTextColor(0xFF0A0E22);
                searchIcon.setVisibility(View.GONE);
                if (n == 0 && !connected) {
                    notFoundCard.setVisibility(View.VISIBLE);
                    searchStatus.setText("");
                }
            });
        }).start();
    }

    /** بطاقة الحاسوب المكتشف — أول واحد يتصل تلقائياً، والبقية باللمس */
    private void addFoundRow(String ip, String name, int port, boolean needPin, boolean autoConnect) {
        if (connected) return;
        LinearLayout card = panel();
        TextView t = new TextView(this);
        t.setText("🖥 " + name);
        t.setTextColor(TXT); t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        card.addView(t);
        TextView d = new TextView(this);
        String desc = ip + (needPin ? " • " + getString(R.string.protected_by_pin) : "") + " " + getString(R.string.tap_to_link);
        d.setText(desc);
        d.setTextColor(MUT); d.setTextSize(12);
        d.setPadding(0, 4 * dp(1), 0, 0);
        card.addView(d);
        card.setOnClickListener(v -> {
            haptic(v);
            connectTo(ip, name, needPin);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = 10 * dp(1);
        foundList.addView(card, 0);
        if (autoConnect) connectTo(ip, name, needPin);   // 🎯 اتصال تلقائي بأول حاسوب يُكتشف
    }

    /** يبدأ الاتصال بحاسوب مكتشف: بطاقة حالة «جارٍ الاتصال…» ثم tryConnect */
    private void connectTo(String ip, String name, boolean needPin) {
        if (connected) return;
        host = ip; hostName = name; pin = ""; userLeft = false;
        searchStatus.setText(getString(R.string.connecting_to, name));
        net.execute(() -> tryConnect(needPin));
    }

    /** محاولة اتصال: تحقق من /ping ثم (PIN عند اللزوم — للإصدارات القديمة) ثم الدخول للوحة التحكم */
    private void tryConnect(final boolean needPinDirect) {
        try {
            HttpResp r = http("GET", "http://" + host + ":37777/ping", null, pin);
            if (r.code != 200) throw new Exception("HTTP " + r.code);
            String name = nz(jstr(r.body, "name"), getString(R.string.found_pc));
            boolean needPin = r.body.contains("\"pin\":true") || r.body.contains("\"pin\": true");
            hostName = name;
            if (needPin) {
                final String srvPin = askPinSync();
                if (srvPin == null) { ui.post(() -> searchStatus.setText(R.string.cancelled)); return; }
                HttpResp st = http("GET", "http://" + host + ":37777/status", null, srvPin);
                if (st.code == 401) { toast(getString(R.string.pin_wrong)); return; }
                pin = srvPin;
            }
            final String nm = name;
            ui.post(() -> enterRemote(nm));
        } catch (Exception e) {
            ui.post(() -> searchStatus.setText(R.string.conn_failed));
        }
    }

    private void tryAutoConnect() {
        try {
            HttpResp r = http("GET", "http://" + host + ":37777/ping", null, pin);
            if (r.code != 200) throw new Exception("x");
            if (r.body.contains("\"pin\":true") && pin.isEmpty()) { ui.post(this::resetConnectScreen); return; }
            HttpResp st = http("GET", "http://" + host + ":37777/status", null, pin);
            if (st.code == 401) { ui.post(this::resetConnectScreen); return; }
            final String nm = nz(jstr(r.body, "name"), getString(R.string.found_pc));
            hostName = nm;
            ui.post(() -> enterRemote(nm));
        } catch (Exception e) { ui.post(this::resetConnectScreen); }
    }

    /** يعيد شاشة البحث لحالتها الأولى (زر البحث جاهز) */
    private void resetConnectScreen() {
        searchIcon.setVisibility(View.GONE);
        searchStatus.setText("");
        searchBtn.setEnabled(true);
        searchBtn.setTextColor(0xFF0A0E22);
    }

    /** ينتظر رمز PIN من المستخدم (حوار برمجي — للإصدارات القديمة من الحاسوب) — null = إلغاء */
    private String askPinSync() {
        final String[] out = {null};
        final Object lock = new Object();
        ui.post(() -> {
            LinearLayout wrap = new LinearLayout(RemoteActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(dp(8), dp(8), dp(8), dp(4));
            final EditText in = new EditText(RemoteActivity.this);
            in.setHint(R.string.pin_hint);
            in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            in.setTextColor(TXT); in.setHintTextColor(0xFF5A5F85);
            in.setBackground(round(PANEL, dp(10), STROKE, dp(1)));
            in.setPadding(dp(12), dp(12), dp(12), dp(12));
            wrap.addView(in);
            new android.app.AlertDialog.Builder(RemoteActivity.this)
                    .setTitle(R.string.pin_title)
                    .setMessage(R.string.pin_msg)
                    .setView(wrap)
                    .setPositiveButton(R.string.link, (d, w) -> { out[0] = in.getText().toString().trim(); synchronized (lock) { lock.notifyAll(); } })
                    .setNegativeButton(R.string.cancel, (d, w) -> { synchronized (lock) { lock.notifyAll(); } })
                    .setOnCancelListener(d -> { synchronized (lock) { lock.notifyAll(); } })
                    .show();
        });
        try { synchronized (lock) { lock.wait(120000); } } catch (InterruptedException e) {}
        return out[0];
    }

    // ═══════════════════ لوحة التحكم + مراقبة الاتصال ═══════════════════

    private void showConnect() {
        if (connected) return;
        connectScreen.setVisibility(View.VISIBLE);
        remoteScreen.setVisibility(View.GONE);
        poll.removeCallbacksAndMessages(null);
        resetConnectScreen();
    }

    private void enterRemote(String name) {
        connected = true;
        fails = 0;
        userLeft = false;
        headName.setText("🖥 " + name);
        hostName = name;
        connectScreen.setVisibility(View.GONE);
        remoteScreen.setVisibility(View.VISIBLE);
        SharedPreferences.Editor ed = getSharedPreferences("latchi_remote", MODE_PRIVATE).edit();
        ed.putString("host", host).putString("pin", pin).apply();
        toast(getString(R.string.connected_toast, name));
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
                            ui.post(() -> {
                                headState.setText(R.string.state_connected);
                                headState.setTextColor(GREEN);
                                nowPlaying.setText((np != null && !np.isEmpty())
                                        ? getString(R.string.now_playing, np)
                                        : getString(R.string.nothing_playing));
                                nowPlaying.setTextColor((np != null && !np.isEmpty()) ? GOLD : MUT);
                            });
                        } else if (r.code == 401) {
                            ui.post(() -> { toast(getString(R.string.pin_changed)); connected = false; showConnect(); });
                        } else throw new Exception("HTTP " + r.code);
                    } catch (Exception e) {
                        fails++;
                        if (fails >= 3) {
                            ui.post(() -> {
                                headState.setText(R.string.state_reconnecting);
                                headState.setTextColor(GOLD);
                            });
                            if (fails >= 6) startAutoReconnect();
                        }
                    }
                    if (connected) poll.postDelayed(this, 2000);
                });
            }
        }, 1500);
    }

    /** 🔄 إعادة اتصال تلقائية: 5 محاولات كل 2.5 ث قبل إخبار المستخدم */
    private void startAutoReconnect() {
        if (reconnecting || userLeft) return;
        reconnecting = true;
        new Thread(() -> {
            int tries = 0;
            while (tries < 5 && !connected && !userLeft) {
                tries++;
                try { Thread.sleep(2500); } catch (Exception e) { return; }
                try {
                    HttpResp r = http("GET", "http://" + host + ":37777/ping", null, pin);
                    if (r.code == 200) {
                        ui.post(() -> enterRemote(hostName));
                        reconnecting = false;
                        return;
                    }
                } catch (Exception e) {}
            }
            reconnecting = false;
            if (!connected && !userLeft) {
                ui.post(() -> {
                    toast(getString(R.string.disconnected_toast));
                    connected = false;
                    showConnect();
                    startDiscovery();
                });
            }
        }).start();
    }

    // ═══════════════════ الشبكة ═══════════════════

    private void key(String k) { sendCmd("{\"key\":" + q(k) + "}"); }

    private void sendCmd(final String json) {
        if (host == null) return;
        if (!connected) { toast(getString(R.string.not_connected)); return; }
        net.execute(() -> {
            try {
                HttpResp r = http("POST", "http://" + host + ":37777/cmd", json, pin);
                if (r.code == 200) { if (fails > 0) { fails = 0; ui.post(() -> { headState.setText(R.string.state_connected); headState.setTextColor(GREEN); }); } }
                else if (r.code == 401) ui.post(() -> { toast(getString(R.string.pin_wrong)); });
            } catch (Exception e) {
                fails++;
                ui.post(() -> { headState.setText(R.string.state_disconnected); headState.setTextColor(RED); });
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

    // زر الرجوع في لوحة التحكم = قطع (يدوي — لا إعادة اتصال تلقائية) والعودة للبحث
    @Override
    public void onBackPressed() {
        if (connected) {
            userLeft = true;
            connected = false;
            showConnect();
            return;
        }
        super.onBackPressed();
    }
}
