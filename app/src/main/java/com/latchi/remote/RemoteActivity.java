package com.latchi.remote;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
 * LATCHI Remote v1.0.3 — لوحة تحكم احترافية Premium بتصميم واحد متناسق.
 *
 * ج49 (إعادة تصميم UX/UI فوق نفس منطق الاتصال دون أي تغيير):
 * - D-Pad حقيقي: قطعة واحدة متصلة (DPadView مخصص على Canvas) — الأسهم ملاصقة لـ OK
 *   بلا أي فراغ، انزلاق الإصبع بين الاتجاهات يعمل، ومناطق قطرية عازلة ضد الضغط الخاطئ.
 * - لوحة لمس بمساحة كبيرة + شريط تمرير جانبي + تلميح صغير + Feedback عند اللمس.
 * - لوحة الأرقام صارت Bottom Sheet أنيقة (تنزلق من الأسفل) بدل شغل مساحة دائمة.
 * - وسائط/صوت صفّان مضغوطان بأيقونات متجهة + تسميات صغيرة — بلا إيموجي إطلاقاً.
 * - شريط حالة مصغر: اسم الحاسوب + نقطة حالة حية + زر قطع أيقوني + nowPlaying.
 * - Keyboard غير موجود عمداً: خادم الحاسوب لا يدعم إرسال حروف (فقط المفاتيح المخصصة)
 *   والتزاماً بقاعدة «لا أزرار وهمية».
 *
 * الشبكة (محمية بلا أي تعديل من v1.0.2): اكتشاف UDP بث عام + /24 لكل واجهة،
 * ping/status/poll كل 2ث، إعادة اتصال تلقائية 5×2.5ث، أوامر /cmd، حفظ آخر حاسوب.
 */
public class RemoteActivity extends Activity {

    // ═══ ثوابت ═══
    private static final int DISC_PORT = 37778;
    private static final String DISC_MSG = "LATCHI_REMOTE_DISCOVER";

    // هوية LATCHI
    private static final int BG = 0xFF070B1C;
    private static final int PANEL = 0xFF121A38;
    private static final int PANEL2 = 0xFF0B1129;
    private static final int PANEL_PRESS = 0xFF2A3568;
    private static final int STROKE = 0xFF2A3568;
    private static final int GOLD = 0xFFD9A94E;
    private static final int GOLD_DARK = 0xFFB8862F;
    private static final int INK = 0xFF0A0E22;       // نص فوق الذهبي
    private static final int TXT = 0xFFE8ECFA;
    private static final int MUT = 0xFF8A90B8;
    private static final int GREEN = 0xFF39FF8B;
    private static final int RED = 0xFFFF5B5B;

    // ═══ حالة ═══
    private String host = null;
    private String hostName = "";
    private String pin = "";
    private boolean connected = false;
    private String deskAppVer = "";   // ج50: نسخة تطبيق الحاسوب من /ping (فارغة = تحديث قديم بلا فأرة/كيبورد)
    String hostType = "pc";   // ج51: pc أو tv — أيقونة مختلفة + إخفاء الفأرة والكيبورد للتلفاز
    private int fails = 0;
    private boolean searching = false;
    private boolean reconnecting = false;
    private boolean userLeft = false;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler poll = new Handler(Looper.getMainLooper());
    private WifiManager.MulticastLock mlock = null;

    // شاشة البحث
    private ScrollView connectScreen;
    private LinearLayout foundList;
    private LinearLayout notFoundCard;
    private TextView searchStatus;
    private ImageView searchIcon;
    private LinearLayout searchBtn;

    // لوحة التحكم
    private LinearLayout remoteScreen;
    private ImageView typeIcon;                  // ج51: أيقونة الجهاز بالرأس (شاشة/تلفاز)
    FrameLayout padPanel;                // ج51: لوحة اللمس (تُخفى للتلفاز)
    LinearLayout kbBtnCtl;               // ج51: زر الكيبورد (يُخفى للتلفاز)
    private final android.util.LruCache<String, android.graphics.Bitmap> imgCache = new android.util.LruCache<>(24);   // شعارات نتائج البحث
    private final ExecutorService imgs = Executors.newSingleThreadExecutor();
    private TextView headName, headState, nowPlaying;
    private View headDot;
    private ImageView playIcon;

    // ═══════════════════ دورة الحياة ═══════════════════

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setContentView(rootLay);
        try {
            mlock = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createMulticastLock("latchir");
            if (mlock != null) mlock.setReferenceCounted(false);
            if (mlock != null) mlock.acquire();
        } catch (Exception e) {}

        maybeCheckUpdate();   // ج51: تحديث تلقائي — قناة remote_update.json مثل التلفاز

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
        ui.removeCallbacksAndMessages(null);
        net.shutdownNow();
        imgs.shutdownNow();   // ج51: شعارات نتائج البحث
    }

    // أزرار صوت الهاتف = صوت الحاسوب
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (connected && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { sendCmd("{\"action\":\"volume\",\"delta\":0.05}"); return true; }
        if (connected && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { sendCmd("{\"action\":\"volume\",\"delta\":-0.05}"); return true; }
        return super.onKeyDown(keyCode, event);
    }

    // ═══════════════════ الواجهة ═══════════════════

    private FrameLayout rootLay;

    @SuppressLint("RtlHardcoded")
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
    // ① شاشة البحث
    // ─────────────────────────────────────────────
    @SuppressLint("RtlHardcoded")
    private void buildConnectScreen() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(24 * dp(1), 46 * dp(1), 24 * dp(1), 28 * dp(1));
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sc.addView(c);
        connectScreen = sc;

        TextView logo = new TextView(this);
        logo.setText("LATCHI");
        logo.setTextSize(31);
        logo.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        logo.setTextColor(GOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setLetterSpacing(0.12f);
        c.addView(logo);

        TextView logo2 = new TextView(this);
        logo2.setText("R E M O T E");
        logo2.setTextSize(12);
        logo2.setTextColor(MUT);
        logo2.setGravity(Gravity.CENTER);
        logo2.setLetterSpacing(0.30f);
        c.addView(logo2);

        TextView sub = new TextView(this);
        sub.setText(R.string.subtitle);
        sub.setTextSize(12);
        sub.setTextColor(MUT);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(10 * dp(1), 16 * dp(1), 10 * dp(1), 0);
        c.addView(sub);

        // الزر الرئيسي الذهبي
        searchBtn = new LinearLayout(this);
        searchBtn.setOrientation(LinearLayout.HORIZONTAL);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.setBackground(goldPress());
        searchBtn.setPadding(dp(22), 0, dp(22), 0);
        ImageView si = icon(R.drawable.ic_search, INK, 21);
        LinearLayout.LayoutParams siLp = new LinearLayout.LayoutParams(-2, -2);
        searchBtn.addView(si, siLp);
        TextView st = new TextView(this);
        st.setText(R.string.search_btn);
        st.setTextColor(INK);
        st.setTextSize(16);
        st.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-2, -2);
        stLp.leftMargin = dp(10);
        searchBtn.addView(st, stLp);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, 58 * dp(1));
        sbLp.topMargin = 30 * dp(1);
        c.addView(searchBtn, sbLp);
        searchBtn.setOnClickListener(v -> { haptic(v); startDiscovery(); });

        // حالة البحث: رادار يدور
        searchIcon = icon(R.drawable.ic_radar, GOLD, 32);
        searchIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams ricLp = new LinearLayout.LayoutParams(-2, -2);
        ricLp.topMargin = 26 * dp(1);
        ricLp.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(searchIcon, ricLp);
        startSpin(searchIcon);

        searchStatus = new TextView(this);
        searchStatus.setText(R.string.searching);
        searchStatus.setTextSize(13);
        searchStatus.setTextColor(MUT);
        searchStatus.setGravity(Gravity.CENTER);
        searchStatus.setPadding(0, 12 * dp(1), 0, 0);
        c.addView(searchStatus);

        // الحواسيب المكتشفة
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        c.addView(foundList);

        // لم يُعثر
        notFoundCard = panel();
        notFoundCard.setGravity(Gravity.CENTER_HORIZONTAL);
        notFoundCard.setPadding(18 * dp(1), 26 * dp(1), 18 * dp(1), 20 * dp(1));
        notFoundCard.setVisibility(View.GONE);
        ImageView nfi = icon(R.drawable.ic_search_off, MUT, 36);
        LinearLayout.LayoutParams nfiLp = new LinearLayout.LayoutParams(-2, -2);
        nfiLp.gravity = Gravity.CENTER_HORIZONTAL;
        notFoundCard.addView(nfi, nfiLp);
        TextView nf1 = new TextView(this);
        nf1.setText(R.string.not_found_title);
        nf1.setTextColor(TXT); nf1.setTextSize(16);
        nf1.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        nf1.setGravity(Gravity.CENTER);
        nf1.setPadding(0, 12 * dp(1), 0, 0);
        notFoundCard.addView(nf1);
        TextView nf2 = new TextView(this);
        nf2.setText(R.string.not_found_hint);
        nf2.setTextColor(MUT); nf2.setTextSize(12);
        nf2.setGravity(Gravity.CENTER);
        nf2.setLineSpacing(dp(3), 1f);
        nf2.setPadding(6 * dp(1), 8 * dp(1), 6 * dp(1), 0);
        notFoundCard.addView(nf2);

        LinearLayout retry = new LinearLayout(this);
        retry.setOrientation(LinearLayout.HORIZONTAL);
        retry.setGravity(Gravity.CENTER);
        retry.setBackground(outlineGold());
        retry.setPadding(dp(18), 0, dp(18), 0);
        ImageView ri = icon(R.drawable.ic_refresh, GOLD, 18);
        retry.addView(ri);
        TextView rt = new TextView(this);
        rt.setText(R.string.retry_search);
        rt.setTextColor(GOLD); rt.setTextSize(14);
        rt.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(-2, -2);
        rtLp.leftMargin = dp(8);
        retry.addView(rt, rtLp);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(-1, 46 * dp(1));
        rLp.topMargin = 18 * dp(1);
        notFoundCard.addView(retry, rLp);
        retry.setOnClickListener(v -> { haptic(v); startDiscovery(); });

        LinearLayout.LayoutParams nfLp = new LinearLayout.LayoutParams(-1, -2);
        nfLp.topMargin = 10 * dp(1);
        c.addView(notFoundCard, nfLp);

        // إعدادات متقدمة (يدوي — صيانة فقط)
        TextView adv = new TextView(this);
        adv.setText(R.string.advanced_settings);
        adv.setTextColor(0xFF565C87);
        adv.setTextSize(11);
        adv.setGravity(Gravity.CENTER);
        adv.setPadding(0, 24 * dp(1), 0, 0);
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
    // ② لوحة التحكم
    // ─────────────────────────────────────────────
    @SuppressLint("RtlHardcoded")
    private void buildRemoteScreen() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundColor(BG);
        remoteScreen = r;

        // ── شريط حالة مصغر ──
        LinearLayout head = panel();
        head.setPadding(dp(14), dp(9), dp(10), dp(9));
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        typeIcon = icon(R.drawable.ic_monitor, GOLD, 19);   // ج51: تتبدل حسب نوع الجهاز (pc/tv)
        headRow.addView(typeIcon);

        headName = new TextView(this);
        headName.setText(R.string.found_pc);
        headName.setTextColor(TXT); headName.setTextSize(15);
        headName.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        headName.setSingleLine(true);
        headName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hnLp = new LinearLayout.LayoutParams(0, -2, 1f);
        hnLp.leftMargin = dp(9);
        headRow.addView(headName, hnLp);

        headDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(GREEN);
        headDot.setBackground(dot);
        headRow.addView(headDot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        headState = new TextView(this);
        headState.setText(R.string.state_connected);
        headState.setTextColor(GREEN); headState.setTextSize(11);
        LinearLayout.LayoutParams hsLp = new LinearLayout.LayoutParams(-2, -2);
        hsLp.leftMargin = dp(5); hsLp.rightMargin = dp(10);
        headRow.addView(headState, hsLp);

        ImageView power = icon(R.drawable.ic_power, MUT, 17);
        power.setBackground(pressCircle(PANEL2, dp(16)));
        power.setPadding(dp(7), dp(7), dp(7), dp(7));
        power.setContentDescription(getString(R.string.disconnect));
        power.setOnClickListener(v -> {
            haptic(v);
            userLeft = true;
            connected = false;
            showConnect();
        });
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        headRow.addView(power, pwLp);

        head.addView(headRow);

        nowPlaying = new TextView(this);
        nowPlaying.setText(R.string.nothing_playing);
        nowPlaying.setTextColor(MUT); nowPlaying.setTextSize(11);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowPlaying.setPadding(dp(28), dp(4), 0, 0);
        head.addView(nowPlaying);
        r.addView(head);

        // ── الجسم ──
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(14));
        s.addView(body);

        // ═ D-Pad: قطعة واحدة متصلة — الأسهم ملاصقة لـ OK ═
        DPadView dpad = new DPadView(this, k -> key(k));
        LinearLayout.LayoutParams dpLp = new LinearLayout.LayoutParams(216 * dp(1), 216 * dp(1));
        dpLp.topMargin = 4 * dp(1);
        dpLp.gravity = Gravity.CENTER_HORIZONTAL;
        body.addView(dpad, dpLp);

        // ═ رجوع / الرئيسية — أدوات ثانوية ملاصقة للـ D-Pad ═
        LinearLayout navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(-1, -2);
        nrLp.topMargin = 12 * dp(1);
        navRow.setLayoutParams(nrLp);

        pill(navRow, R.drawable.ic_back, R.string.btn_back, "Escape");
        pill(navRow, R.drawable.ic_home, R.string.btn_home, "Home");
        body.addView(navRow);

        // ═ لوحة اللمس — مساحة كبيرة + شريط تمرير جانبي ═
        FrameLayout tp = new FrameLayout(this);
        padPanel = tp;   // ج51: تُخفى للتلفاز (لا فأرة على LATCHI TV)
        tp.setBackground(round(PANEL2, 20 * dp(1), STROKE, 1 * dp(1)));
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(-1, 150 * dp(1));
        tpLp.topMargin = 16 * dp(1);
        tp.setLayoutParams(tpLp);

        LinearLayout tpMid = new LinearLayout(this);
        tpMid.setOrientation(LinearLayout.VERTICAL);
        tpMid.setGravity(Gravity.CENTER);
        tpMid.setClickable(false); tpMid.setFocusable(false);
        ImageView mi = icon(R.drawable.ic_mouse, 0xFF4E5580, 27);
        tpMid.addView(mi);
        TextView mh = new TextView(this);
        mh.setText(R.string.pad_hint);
        mh.setTextColor(0xFF4E5580);
        mh.setTextSize(10);
        mh.setPadding(0, dp(7), 0, 0);
        tpMid.addView(mh);
        tp.addView(tpMid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        View pad = new View(this);
        StateListDrawable padBg = new StateListDrawable();
        padBg.addState(new int[]{android.R.attr.state_pressed}, round(0x24D9A94E, 20 * dp(1), 0x59D9A94E, dp(1)));
        padBg.addState(new int[]{-android.R.attr.state_pressed}, round(0x00000000, 20 * dp(1), 0x00000000, 0));
        pad.setBackground(padBg);
        pad.setOnTouchListener((v, ev) -> touchpad(v, ev));
        tp.addView(pad, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER);
        rail.setPadding(0, dp(5), dp(6), 0);
        railStep(rail, R.drawable.ic_chevron_up, R.string.scroll_up, -1);
        railStep(rail, R.drawable.ic_chevron_down, R.string.scroll_down, 1);
        FrameLayout.LayoutParams rlLp = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        tp.addView(rail, rlLp);
        body.addView(tp);

        // ═ الكيبورد والأرقام — صف الأدوات الذهبي (ج50: الكيبورد مثل الأرقام تماماً) ═
        LinearLayout tools = ctlRow(12);
        LinearLayout srchBtn = ctl(tools, R.drawable.ic_search, R.string.search_all, this::showSearchSheet);   // ج51
        srchBtn.setBackground(goldOutlinePress());
        tintCtl(srchBtn, GOLD);
        LinearLayout kbBtn = ctl(tools, R.drawable.ic_keyboard, R.string.keyboard, this::showKeyboard);
        kbBtnCtl = kbBtn;   // ج51: يُخفى للتلفاز
        kbBtn.setBackground(goldOutlinePress());
        tintCtl(kbBtn, GOLD);
        LinearLayout numsBtn = ctl(tools, R.drawable.ic_numpad, R.string.numbers, this::showNumbers);
        numsBtn.setBackground(goldOutlinePress());
        tintCtl(numsBtn, GOLD);
        body.addView(tools);

        // ═ الوسائط — صف مضغوط بأيقونات ═
        LinearLayout media = ctlRow(8);
        ctl(media, R.drawable.ic_ch_down, R.string.btn_ch_down, () -> key("PageDown"));
        ctl(media, R.drawable.ic_play, R.string.btn_play_pause, () -> key(" "));
        playIcon = lastCtlIcon;
        ctl(media, R.drawable.ic_ch_up, R.string.btn_ch_up, () -> key("PageUp"));
        ctl(media, R.drawable.ic_fullscreen, R.string.btn_fs, () -> key("f"));
        body.addView(media);

        // ═ الصوت ═
        LinearLayout vols = ctlRow(8);
        ctl(vols, R.drawable.ic_vol_down, R.string.btn_vol_down, () -> sendCmd("{\"action\":\"volume\",\"delta\":-0.05}"));
        ctl(vols, R.drawable.ic_mute, R.string.btn_mute, () -> sendCmd("{\"action\":\"mute\"}"));
        ctl(vols, R.drawable.ic_vol_up, R.string.btn_vol_up, () -> sendCmd("{\"action\":\"volume\",\"delta\":0.05}"));
        body.addView(vols);

        r.addView(s, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    // ═══════════════════ D-Pad المخصص — قطعة واحدة ═══════════════════

    /**
     * D-Pad حقيقي كعنصر تحكم واحد: قرص دائري مقسوم لاتجاهات ملاصقة تماماً لبعضها
     * وOK ذهبي بالمنتصف. السحب بين الاتجاهات يعمل، ومناطق قطرية عازلة (14°) تمنع
     * الضغط الخاطئ. يرسل نفس مفاتيح الأسهم/Enter المعتادة — بلا أي تغيير بالبروتوكول.
     */
    public static class DPadView extends View {
        public interface L { void onPad(String key); }

        private final L l;
        private int zone = -1;                       // 0↑ 1→ 2↓ 3← 4=OK
        private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pArrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private static final int C_BG = 0xFF070B1C, C_PANEL = 0xFF121A38, C_STROKE = 0xFF2A3568;
        private static final int C_GOLD = 0xFFD9A94E, C_INK = 0xFF0A0E22, C_TXT = 0xFFE8ECFA;

        public DPadView(Context ctx, L listener) {
            super(ctx);
            l = listener;
            pStroke.setStyle(Paint.Style.STROKE);
            pTxt.setTextAlign(Paint.Align.CENTER);
            setClickable(true);
            setFocusable(false);
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
            int s = Math.min(w < 0 ? h : w, h < 0 ? w : h);
            if (s <= 0) s = (int) dp(216);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas cv) {
            final float c = getWidth() / 2f;
            final float rOut = c - dp(2);
            final float rOk = dp(39);
            final float rIn = rOk + dp(7);

            // قرص الاتجاهات
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(C_PANEL);
            cv.drawCircle(c, c, rOut, pFill);
            pStroke.setColor(C_STROKE);
            pStroke.setStrokeWidth(dp(1));
            cv.drawCircle(c, c, rOut, pStroke);

            // تمييز الاتجاه المضغوط (إسفين ذهبي)
            if (zone >= 0 && zone <= 3) {
                float start = zone == 0 ? -90 - 38 : zone == 1 ? -38 : zone == 2 ? 90 - 38 : 180 - 38;
                pFill.setColor(0x8CD9A94E);
                cv.drawArc(new RectF(0, 0, getWidth(), getHeight()), start, 76, true, pFill);
            }

            // فتحات الفصل القطرية (تُوحي بأربعة أرباع متناسقة)
            pFill.setColor(C_BG);
            cv.drawCircle(c, c, rIn - dp(2), pFill);
            pStroke.setColor(C_BG);
            pStroke.setStrokeWidth(dp(3));
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(45 + i * 90);
                float x1 = c + (float) Math.cos(a) * (rIn - dp(3));
                float y1 = c + (float) Math.sin(a) * (rIn - dp(3));
                float x2 = c + (float) Math.cos(a) * rOut;
                float y2 = c + (float) Math.sin(a) * rOut;
                cv.drawLine(x1, y1, x2, y2, pStroke);
            }

            // أسهم الاتجاهات
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(i * 90);   // 0→ يمين، 90↓ … (محاور الشاشة)
                float m = (rIn + rOut) / 2f;
                float ax = c + (float) Math.cos(a) * m;
                float ay = c + (float) Math.sin(a) * m;
                float s = dp(8.5f);
                pArrow.setStyle(Paint.Style.STROKE);
                pArrow.setStrokeWidth(dp(2.6f));
                pArrow.setStrokeCap(Paint.Cap.ROUND);
                pArrow.setColor(zone == i ? C_INK : C_TXT);
                path.reset();
                // سهم شيفرون يشير للخارج
                float nx = (float) Math.cos(a), ny = (float) Math.sin(a);
                float px = -ny, py = nx;
                path.moveTo(ax - px * s - nx * s * 0.45f, ay - py * s - ny * s * 0.45f);
                path.lineTo(ax + nx * s * 0.7f, ay + ny * s * 0.7f);
                path.lineTo(ax + px * s - nx * s * 0.45f, ay + py * s - ny * s * 0.45f);
                cv.drawPath(path, pArrow);
            }

            // زر OK الذهبي
            pFill.setStyle(Paint.Style.FILL);
            pFill.setColor(zone == 4 ? 0xFFB8862F : C_GOLD);
            cv.drawCircle(c, c, rOk, pFill);
            pStroke.setColor(0x66FFFFFF);
            pStroke.setStrokeWidth(dp(1));
            cv.drawCircle(c, c, rOk - dp(2), pStroke);
            pTxt.setColor(C_INK);
            pTxt.setTextSize(dp(17));
            pTxt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            pTxt.setFakeBoldText(true);
            float ty = c - (pTxt.descent() + pTxt.ascent()) / 2f;
            cv.drawText("OK", c, ty, pTxt);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            final float c = getWidth() / 2f;
            final float rOut = c - dp(2);
            final float rOk = dp(39);
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int z = hit(ev.getX(), ev.getY(), c, rOk, rOut);
                    fire(z);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    int z = hit(ev.getX(), ev.getY(), c, rOk, rOut);
                    if (z != zone) {
                        if (z >= 0 && z <= 3) fire(z);      // انزلاق لاتجاه جديد
                        else setZone(z);                     // منطقة عازلة/خارج = إلغاء الإبراز فقط
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setZone(-1);
                    return true;
            }
            return false;
        }

        private void fire(int z) {
            setZone(z);
            if (z < 0) return;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            l.onPad(z == 0 ? "ArrowUp" : z == 1 ? "ArrowRight" : z == 2 ? "ArrowDown" : z == 3 ? "ArrowLeft" : "Enter");
        }

        private void setZone(int z) {
            if (zone != z) { zone = z; invalidate(); }
        }

        /** 0↑ 1→ 2↓ 3← 4=OK −1 خارج/عازل — القطرات الأربع (±45°/±135°) عازلة 14° ضد الضغط الخاطئ */
        private int hit(float x, float y, float c, float rOk, float rOut) {
            float dx = x - c, dy = y - c;
            double r = Math.hypot(dx, dy);
            if (r <= rOk) return 4;
            if (r > rOut) return -1;
            double deg = Math.toDegrees(Math.atan2(dy, dx));   // -180..180
            if (deg >= -38 && deg <= 38) return 1;             // →
            if (deg >= 52 && deg <= 128) return 2;             // ↓
            if (deg >= -128 && deg <= -52) return 0;           // ↑
            if (deg >= 142 || deg <= -142) return 3;           // ←
            return -1;                                         // قطري عازل
        }
    }

    // ═══════════════════ لوحة الأرقام — Bottom Sheet ═══════════════════

    void showNumbers() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(PANEL, 0, 0, 0);
        bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        bg.setStroke(dp(1), STROKE);
        p.setBackground(bg);
        p.setPadding(dp(10), dp(10), dp(10), dp(16));

        View handle = new View(this);
        GradientDrawable hd = round(MUT, dp(2), 0, 0);
        handle.setBackground(hd);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        p.addView(handle, hLp);

        LinearLayout tRow = new LinearLayout(this);
        tRow.setOrientation(LinearLayout.HORIZONTAL);
        tRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
        trLp.topMargin = dp(12); trLp.bottomMargin = dp(4);
        tRow.setLayoutParams(trLp);
        TextView t = new TextView(this);
        t.setText(R.string.numbers_title);
        t.setTextColor(TXT); t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        tRow.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView x = icon(R.drawable.ic_close, MUT, 16);
        x.setBackground(pressCircle(PANEL2, dp(15)));
        x.setPadding(dp(6), dp(6), dp(6), dp(6));
        x.setContentDescription(getString(R.string.close));
        x.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams xLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        tRow.addView(x, xLp);
        p.addView(tRow);

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {null, "0", null}};
        for (String[] row : rows) {
            LinearLayout rr = new LinearLayout(this);
            rr.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                String k = row[col];
                Button b = mkBtn(k == null ? (col == 0 ? getString(R.string.key_del) : getString(R.string.key_enter)) : k,
                        PANEL, k == null ? GOLD : TXT);
                b.setTextSize(k == null ? 19 : 21);
                bindKey(b, k == null ? (col == 0 ? "Backspace" : "Enter") : k);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, 56 * dp(1), 1f);
                int m = dp(4);
                bl.setMargins(m, m, m, m);
                rr.addView(b, bl);
            }
            p.addView(rr, new LinearLayout.LayoutParams(-1, -2));
        }

        d.setContentView(p);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(R.style.SheetAnim);
        }
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    // ═══════════════════ لوحة المفاتيح (ج50) — Bottom Sheet مثل الأرقام تماماً ═══════════════════
    // كل حرف يُرسل فوراً للحاسوب وهو يدرجه في الحقل المركّز (البحث/الأكواد) — بلا زر «إرسال».
    void showKeyboard() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(PANEL, 0, 0, 0);
        bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        bg.setStroke(dp(1), STROKE);
        p.setBackground(bg);
        p.setPadding(dp(10), dp(10), dp(10), dp(16));

        View handle = new View(this);
        GradientDrawable hd = round(MUT, dp(2), 0, 0);
        handle.setBackground(hd);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        p.addView(handle, hLp);

        LinearLayout tRow = new LinearLayout(this);
        tRow.setOrientation(LinearLayout.HORIZONTAL);
        tRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
        trLp.topMargin = dp(12); trLp.bottomMargin = dp(4);
        tRow.setLayoutParams(trLp);
        TextView t = new TextView(this);
        t.setText(R.string.keyboard_title);
        t.setTextColor(TXT); t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        tRow.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView x = icon(R.drawable.ic_close, MUT, 16);
        x.setBackground(pressCircle(PANEL2, dp(15)));
        x.setPadding(dp(6), dp(6), dp(6), dp(6));
        x.setContentDescription(getString(R.string.close));
        x.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams xLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        tRow.addView(x, xLp);
        p.addView(tRow);

        // حقل الكتابة — كل تغيير يُرسل فوراً (الإضافة حرفاً حرفاً، والحذف Backspace)
        final EditText box = new EditText(this);
        box.setHint(R.string.kb_hint);
        box.setTextColor(TXT);
        box.setHintTextColor(MUT);
        box.setTextSize(15);
        box.setSingleLine(true);
        box.setBackground(round(PANEL2, 12 * dp(1), STROKE, 1 * dp(1)));
        box.setPadding(dp(12), dp(11), dp(12), dp(11));
        p.addView(box, new LinearLayout.LayoutParams(-1, -2));
        final String[] prev = {""};
        box.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String cur = s.toString();
                String oldTxt = prev[0];
                int common = 0;
                while (common < oldTxt.length() && common < cur.length() && oldTxt.charAt(common) == cur.charAt(common)) common++;
                for (int i = 0; i < oldTxt.length() - common; i++) key("Backspace");
                for (int i = common; i < cur.length(); i++) key(String.valueOf(cur.charAt(i)));
                prev[0] = cur;
            }
        });

        // أزرار مساعدة — نفس قياس أزرار الأرقام (56dp)
        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        Button sp = mkBtn(getString(R.string.kb_space), PANEL, TXT);
        sp.setTextSize(16);
        sp.setOnClickListener(v -> { haptic(v); key(" "); });
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 56 * dp(1), 2f);
        spLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        r1.addView(sp, spLp);
        Button del = mkBtn(getString(R.string.kb_del), PANEL, GOLD);
        del.setTextSize(19);
        del.setOnClickListener(v -> {
            haptic(v);
            android.text.Editable e = box.getText();
            if (e.length() > 0) e.delete(e.length() - 1, e.length());   // الحذف من الحقل يشعل المرسل تلقائياً
        });
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, 56 * dp(1), 1f);
        delLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        r1.addView(del, delLp);
        Button en = mkBtn(getString(R.string.kb_enter), PANEL, GOLD);
        en.setTextSize(19);
        bindKey(en, "Enter");
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(0, 56 * dp(1), 1f);
        enLp.setMargins(dp(4), dp(4), dp(4), dp(4));
        r1.addView(en, enLp);
        p.addView(r1, new LinearLayout.LayoutParams(-1, -2));

        d.setContentView(p);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(R.style.SheetAnim);
        }
        d.setCanceledOnTouchOutside(true);
        d.show();
        // فتح كيبورد الهاتف فوراً على الحقل
        box.postDelayed(() -> {
            box.requestFocus();
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(box, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 120);
    }

    // ═══════════════════ مكونات مساعدة ═══════════════════

    /** لوحة اللمس: سحب=تحريك (flush كل 40ms) — نقرة<300ms بلا حركة=نقر — السكة الجانبية=تمرير */
    private float padLastX = 0, padLastY = 0, accX = 0, accY = 0;
    private long padDownT = 0, lastSent = 0;
    private boolean padMoved = false;

    @SuppressLint("ClickableViewAccessibility")
    private boolean touchpad(View v, MotionEvent ev) {
        final int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            padLastX = ev.getX(); padLastY = ev.getY();
            accX = 0; accY = 0; padMoved = false;
            padDownT = SystemClock.uptimeMillis();
            v.setPressed(true);
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
            v.setPressed(false);
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

    /** دوران خفيف مستمر — رادار البحث */
    private void startSpin(View v) {
        RotateAnimation rot = new RotateAnimation(0, 360,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        rot.setDuration(1600);
        rot.setRepeatCount(Animation.INFINITE);
        rot.setInterpolator(new LinearInterpolator());
        v.startAnimation(rot);
    }

    private void bindKey(Button b, final String k) {
        b.setOnClickListener(v -> { haptic(v); key(k); });
    }

    private LinearLayout ctlRow(int topMarginDp) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = topMarginDp * dp(1);
        l.setLayoutParams(lp);
        return l;
    }

    private ImageView lastCtlIcon;   // مرجع أيقونة آخر زر أُنشئ (لتغيير حالة تشغيل/وقف)

    /** زر تحكم بأيقونة + تسمية صغيرة — يرجع الحاوية القابلة للنقر */
    private LinearLayout ctl(LinearLayout row, int iconRes, int labelRes, Runnable act) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(press(PANEL, 15 * dp(1)));
        b.setPadding(0, dp(7), 0, dp(6));
        b.setOnClickListener(v -> { haptic(v); act.run(); });
        ImageView iv = new ImageView(this);
        iv.setImageResource(iconRes);
        iv.setColorFilter(TXT);
        b.addView(iv, new LinearLayout.LayoutParams(dp(21), dp(21)));
        lastCtlIcon = iv;
        TextView tv = new TextView(this);
        tv.setText(labelRes);
        tv.setTextColor(TXT); tv.setTextSize(9);
        tv.setPadding(0, dp(5), 0, 0);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        b.addView(tv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 56 * dp(1), 1f);
        int m = dp(4);
        lp.setMargins(m, 0, m, 0);
        row.addView(b, lp);
        return b;
    }

    private void tintCtl(LinearLayout b, int color) {
        for (int i = 0; i < b.getChildCount(); i++) {
            View ch = b.getChildAt(i);
            if (ch instanceof ImageView) ((ImageView) ch).setColorFilter(color);
            else if (ch instanceof TextView) ((TextView) ch).setTextColor(color);
        }
    }

    /** حبة أفقية صغيرة (رجوع/الرئيسية) — أدوات ثانوية */
    private void pill(LinearLayout row, int iconRes, int labelRes, final String key) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(press(PANEL2, 22 * dp(1)));
        b.setPadding(dp(18), dp(11), dp(18), dp(11));
        b.setOnClickListener(v -> { haptic(v); key(key); });
        ImageView iv = icon(iconRes, TXT, 16);
        b.addView(iv);
        TextView tv = new TextView(this);
        tv.setText(labelRes);
        tv.setTextColor(TXT); tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(-2, -2);
        tvLp.leftMargin = dp(7);
        b.addView(tv, tvLp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6); lp.rightMargin = dp(6);
        row.addView(b, lp);
    }

    /** زر سكة التمرير الجانبية داخل لوحة اللمس */
    private void railStep(LinearLayout rail, int iconRes, int descRes, final int wheel) {
        ImageView b = icon(iconRes, TXT, 17);
        b.setBackground(pressCircle(PANEL, dp(19)));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setContentDescription(getString(descRes));
        b.setOnClickListener(v -> {
            haptic(v);
            sendCmd("{\"action\":\"mouse\",\"wheel\":" + wheel + "}");
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.bottomMargin = dp(6);
        rail.addView(b, lp);
    }

    private ImageView icon(int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(sizeDp * dp(1), sizeDp * dp(1)));
        return iv;
    }

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(round(PANEL, 16 * dp(1), STROKE, 1 * dp(1)));
        p.setPadding(16 * dp(1), 14 * dp(1), 16 * dp(1), 14 * dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 10 * dp(1);
        p.setLayoutParams(lp);
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
        g.setStroke(dp(1), color == GOLD ? 0xFF8A6F35 : STROKE);
        return g;
    }

    /** خلفية بحالتي عادي/مضغوط — إحساس ضغط حقيقي */
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

    /** الزر الرئيسي الذهبي */
    private StateListDrawable goldPress() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(GOLD_DARK, 17 * dp(1), 0xFFFFFFFF, dp(1)));
        s.addState(new int[]{-android.R.attr.state_pressed}, round(GOLD, 17 * dp(1), 0xFFFFFFFF, dp(1)));
        return s;
    }

    /** إطار ذهبي شفاف (إعادة البحث) */
    private StateListDrawable outlineGold() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(0x2ED9A94E, 15 * dp(1), GOLD, dp(1)));
        s.addState(new int[]{-android.R.attr.state_pressed}, round(0x00000000, 15 * dp(1), GOLD, dp(1)));
        return s;
    }

    /** زر الأرقام — ذهبي بارز */
    private StateListDrawable goldOutlinePress() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(0x3DD9A94E, 15 * dp(1), GOLD, dp(2)));
        s.addState(new int[]{-android.R.attr.state_pressed}, round(0x14D9A94E, 15 * dp(1), GOLD, dp(1)));
        return s;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void haptic(View v) { try { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); } catch (Exception e) {} }
    private void toast(String s) { ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ═══════════════════ الاتصال والاكتشاف (منطق محفوظ من v1.0.2) ═══════════════════

    private void startDiscovery() {
        if (searching) return;
        searching = true;
        userLeft = false;
        ui.post(() -> {
            foundList.removeAllViews();
            notFoundCard.setVisibility(View.GONE);
            searchBtn.setEnabled(false);
            searchBtn.setAlpha(0.45f);
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
                            final String type = nz(jstr(json, "type"), "pc");   // ج51
                            final boolean needPin = json.contains("\"pin\":true") || json.contains("\"pin\": true");
                            ui.post(() -> addFoundRow(ip, name, type, needPin, seen.size() == 1));
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
                searching = false;
                searchBtn.setEnabled(true);
                searchBtn.setAlpha(1f);
                searchIcon.setVisibility(View.GONE);
                searchIcon.clearAnimation();
                if (n == 0 && !connected) {
                    notFoundCard.setVisibility(View.VISIBLE);
                    searchStatus.setText("");
                }
            });
        }).start();
    }

    /** بطاقة الجهاز المكتشف (حاسوب أو تلفاز) — أول واحد يتصل تلقائياً، والبقية باللمس */
    private void addFoundRow(String ip, String name, String type, boolean needPin, boolean autoConnect) {
        if (connected) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(press(PANEL, 16 * dp(1)));
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setOnClickListener(v -> { haptic(v); hostType = type; connectTo(ip, name, needPin); });

        boolean isTv = "tv".equals(type);   // ج51: أيقونة مختلفة للتلفاز
        ImageView mon = icon(isTv ? R.drawable.ic_tv : R.drawable.ic_monitor, GOLD, 22);
        card.addView(mon);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0, -2, 1f);
        midLp.leftMargin = dp(12);
        card.addView(mid, midLp);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(TXT); t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(t);

        TextView d = new TextView(this);
        String desc = ip + (needPin ? "  •  " + getString(R.string.protected_by_pin) : "");
        d.setText(desc);
        d.setTextColor(MUT); d.setTextSize(11);
        d.setPadding(0, dp(3), 0, 0);
        mid.addView(d);

        TextView link = new TextView(this);
        link.setText(R.string.tap_to_link);
        link.setTextColor(GOLD); link.setTextSize(10);
        link.setPadding(0, dp(3), 0, 0);
        mid.addView(link);

        if (needPin) {
            ImageView lk = icon(R.drawable.ic_lock, MUT, 15);
            LinearLayout.LayoutParams lkLp = new LinearLayout.LayoutParams(-2, -2);
            lkLp.leftMargin = dp(8);
            card.addView(lk, lkLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = 10 * dp(1);
        foundList.addView(card, 0);
        if (autoConnect) connectTo(ip, name, needPin);
    }

    private void connectTo(String ip, String name, boolean needPin) {
        if (connected) return;
        host = ip; hostName = name; pin = ""; userLeft = false;
        searchStatus.setText(getString(R.string.connecting_to, name));
        net.execute(() -> tryConnect(needPin));
    }

    private void tryConnect(final boolean needPinDirect) {
        try {
            HttpResp r = http("GET", "http://" + host + ":37777/ping", null, pin);
            if (r.code != 200) throw new Exception("HTTP " + r.code);
            String name = nz(jstr(r.body, "name"), getString(R.string.found_pc));
            deskAppVer = nz(jstr(r.body, "appVer"), "");   // ج50: نسخة الحاسوب (فارغة = تحديث قديم)
            hostType = nz(jstr(r.body, "type"), "pc");      // ج51: pc أو tv
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
            deskAppVer = nz(jstr(r.body, "appVer"), "");   // ج50
            hostType = nz(jstr(r.body, "type"), "pc");      // ج51
            final String nm = nz(jstr(r.body, "name"), getString(R.string.found_pc));
            hostName = nm;
            ui.post(() -> enterRemote(nm));
        } catch (Exception e) { ui.post(this::resetConnectScreen); }
    }

    private void resetConnectScreen() {
        searchIcon.setVisibility(View.GONE);
        searchIcon.clearAnimation();
        searchStatus.setText("");
        searchBtn.setEnabled(true);
        searchBtn.setAlpha(1f);
    }

    /** ينتظر رمز PIN من المستخدم (للإصدارات القديمة من الحاسوب) — null = إلغاء */
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

    void enterRemote(String name) {
        connected = true;
        fails = 0;
        userLeft = false;
        headName.setText(name);
        hostName = name;
        // ج51: نوع الجهاز — تلفاز LATCHI TV أم حاسوب LATCHI PC (أيقونة + أدوات مختلفة)
        boolean isTv = "tv".equals(hostType);
        if (typeIcon != null) typeIcon.setImageResource(isTv ? R.drawable.ic_tv : R.drawable.ic_monitor);
        if (padPanel != null) padPanel.setVisibility(isTv ? View.GONE : View.VISIBLE);
        if (kbBtnCtl != null) kbBtnCtl.setVisibility(isTv ? View.GONE : View.VISIBLE);
        // ج50: كشف النسخة القديمة — تحديث الحاسوب القديم لا يدعم الفأرة والكيبورد
        if (deskAppVer.isEmpty()) toast(getString(R.string.old_pc_ver));
        headName.setOnLongClickListener(v -> {
            toast(getString(deskAppVer.isEmpty() ? R.string.pc_ver_unknown : R.string.pc_ver, deskAppVer.isEmpty() ? "" : deskAppVer));
            return true;
        });
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
                                setDot(GREEN);
                                nowPlaying.setText((np != null && !np.isEmpty())
                                        ? getString(R.string.now_playing, np)
                                        : getString(R.string.nothing_playing));
                                nowPlaying.setTextColor((np != null && !np.isEmpty()) ? GOLD : MUT);
                                // أيقونة تشغيل/وقف تعكس الحالة
                                if (playIcon != null) {
                                    boolean playing = np != null && !np.isEmpty();
                                    playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                                    playIcon.setColorFilter(TXT);
                                }
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
                                setDot(GOLD);
                            });
                            if (fails >= 6) startAutoReconnect();
                        }
                    }
                    if (connected) poll.postDelayed(this, 2000);
                });
            }
        }, 1500);
    }

    private void setDot(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        headDot.setBackground(dot);
    }

    /** إعادة اتصال تلقائية: 5 محاولات كل 2.5 ث قبل إخبار المستخدم */
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

    /** أمر بحث قابل للتمرير (بلا java.util.function — توافق مع أندرويد 5) */
    private interface QRun { void go(String q); }

    // ═══════════════════ ج51: البحث في محتوى الجهاز المتصل (حاسوب أو تلفاز) ═══════════════════

    /** نافذة البحث: اكتب اسم قناة/فيلم/مسلسل → نتائج بشعارات → الضغط يشغّلها فوراً على الجهاز */
    void showSearchSheet() {
        if (!connected) { toast(getString(R.string.not_connected)); return; }
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = round(BG, 0, 0, 0);
        bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        p.setBackground(bg);
        p.setPadding(dp(12), dp(10), dp(12), dp(14));

        View handle = new View(this);
        GradientDrawable hd = round(MUT, dp(2), 0, 0);
        handle.setBackground(hd);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hLp.gravity = Gravity.CENTER_HORIZONTAL;
        p.addView(handle, hLp);

        LinearLayout tRow = new LinearLayout(this);
        tRow.setOrientation(LinearLayout.HORIZONTAL);
        tRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(-1, -2);
        trLp.topMargin = dp(12); trLp.bottomMargin = dp(4);
        tRow.setLayoutParams(trLp);
        TextView t = new TextView(this);
        t.setText(getString(R.string.search_in, hostName));
        t.setTextColor(TXT); t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tRow.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView x = icon(R.drawable.ic_close, MUT, 16);
        x.setBackground(pressCircle(PANEL2, dp(15)));
        x.setPadding(dp(6), dp(6), dp(6), dp(6));
        x.setContentDescription(getString(R.string.close));
        x.setOnClickListener(v -> d.dismiss());
        tRow.addView(x, new LinearLayout.LayoutParams(dp(30), dp(30)));
        p.addView(tRow);

        // حقل البحث + زر
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, -2);
        barLp.topMargin = dp(8);
        bar.setLayoutParams(barLp);
        final EditText in = new EditText(this);
        in.setHint(R.string.search_hint);
        in.setSingleLine(true);
        in.setTextColor(TXT); in.setHintTextColor(0xFF5A5F85);
        in.setTextSize(14);
        in.setBackground(round(PANEL, dp(12), STROKE, dp(1)));
        in.setPadding(dp(14), dp(12), dp(14), dp(12));
        bar.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout goBtn = new LinearLayout(this);
        goBtn.setOrientation(LinearLayout.HORIZONTAL);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setBackground(goldPress());
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-2, dp(46));
        gLp.leftMargin = dp(8);
        goBtn.setPadding(dp(16), 0, dp(16), 0);
        ImageView gi = icon(R.drawable.ic_search, INK, 18);
        goBtn.addView(gi);
        bar.addView(goBtn, gLp);
        p.addView(bar);

        final TextView status = new TextView(this);
        status.setTextColor(MUT); status.setTextSize(12);
        status.setPadding(dp(4), dp(10), dp(4), dp(6));
        p.addView(status);

        ScrollView sc = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        android.view.WindowManager wm = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        int maxH = 460 * dp(1);
        try { maxH = Math.min(maxH, (wm.getDefaultDisplay().getHeight() * 2) / 3); } catch (Exception e) {}
        p.addView(sc, new LinearLayout.LayoutParams(-1, maxH));

        final QRun run = q -> runSearch(q, list, status, d);
        goBtn.setOnClickListener(v -> { haptic(v); run.accept(in.getText().toString().trim()); });
        in.setOnEditorActionListener((v, actId, ev) -> {
            if (actId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getAction() == KeyEvent.ACTION_UP && (ev.getKeyCode() == KeyEvent.KEYCODE_ENTER || ev.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER))) {
                run.accept(in.getText().toString().trim());
                return true;
            }
            return false;
        });
        in.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence cs, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence cs, int a, int b2, int c) {}
            @Override public void afterTextChanged(android.text.Editable ed) {
                final String q = ed.toString().trim();
                ui.removeCallbacks(pendingSearch);
                if (q.isEmpty()) { list.removeAllViews(); status.setText(""); return; }
                pendingSearch = () -> run.accept(q);
                ui.postDelayed(pendingSearch, 550);
            }
        });

        d.setContentView(p);
        d.show();
        android.view.Window w = d.getWindow();
        if (w != null) w.setLayout(-1, -2);
        try { in.requestFocus(); } catch (Exception e) {}
    }

    private Runnable pendingSearch = null;

    /** GET /search?q — يبحث الجهاز المتصل في كل محتواه (قنوات/أفلام/مسلسلات) */
    private void runSearch(final String query, final LinearLayout list, final TextView status, final Dialog d) {
        if (query == null || query.isEmpty()) return;
        status.setText(R.string.searching_content);
        net.execute(() -> {
            try {
                String q = java.net.URLEncoder.encode(query, "UTF-8");
                HttpResp r = http("GET", "http://" + host + ":37777/search?q=" + q, null, pin);
                if (r.code != 200) throw new Exception("HTTP " + r.code);
                final List<String> items = jarr(r.body, "results");
                final boolean ok = r.body.contains("\"ok\":true") || !items.isEmpty();
                ui.post(() -> {
                    list.removeAllViews();
                    if (!ok || items.isEmpty()) {
                        status.setText(R.string.no_results);
                        return;
                    }
                    status.setText(getString(R.string.results_count, items.size()));
                    for (String it : items) addResultRow(list, it, d);
                });
            } catch (Exception e) {
                ui.post(() -> status.setText(R.string.search_failed));
            }
        });
    }

    /** صف نتيجة: شعار + اسم + نوع — الضغط يشغّله فوراً على الجهاز */
    private void addResultRow(final LinearLayout list, final String itemJson, final Dialog d) {
        final String name = nz(jstr(itemJson, "name"), "?");
        String type = nz(jstr(itemJson, "type"), "live");
        final String logo = nz(jstr(itemJson, "logo"), "");
        String group = nz(jstr(itemJson, "group"), "");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(press(PANEL, dp(12)));
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setOnClickListener(v -> { haptic(v); playRemote(itemJson); d.dismiss(); });

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(0xFF1A2244);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(46), dp(34));
        row.addView(iv, ivLp);
        loadLogo(iv, logo);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0, -2, 1f);
        midLp.leftMargin = dp(10);
        row.addView(mid, midLp);

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(TXT); n.setTextSize(14);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(n);

        TextView g = new TextView(this);
        String typeLbl = "series".equals(type) ? getString(R.string.res_series)
                : "movie".equals(type) ? getString(R.string.res_movie) : getString(R.string.res_live);
        g.setText(typeLbl + (group.isEmpty() ? "" : "  •  " + group));
        g.setTextColor(MUT); g.setTextSize(11);
        g.setSingleLine(true);
        g.setEllipsize(android.text.TextUtils.TruncateAt.END);
        g.setPadding(0, dp(3), 0, 0);
        mid.addView(g);

        ImageView pl = icon(R.drawable.ic_play, GOLD, 16);
        pl.setBackground(pressCircle(PANEL2, dp(17)));
        pl.setPadding(dp(6), dp(6), dp(6), dp(6));
        row.addView(pl, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(7);
        list.addView(row, lp);
    }

    /** POST /play — تبديل ما يشغَّل على الجهاز المتصل فوراً */
    private void playRemote(final String itemJson) {
        if (!connected) { toast(getString(R.string.not_connected)); return; }
        net.execute(() -> {
            try {
                HttpResp r = http("POST", "http://" + host + ":37777/play", "{\"item\":" + itemJson + "}", pin);
                final boolean ok = r.code == 200 && (r.body.contains("\"ok\":true"));
                ui.post(() -> toast(getString(ok ? R.string.sent_to_device : R.string.play_failed)));
            } catch (Exception e) {
                ui.post(() -> toast(getString(R.string.play_failed)));
            }
        });
    }

    /** تحميل شعار نتيجة (HTTP) بذاكرة صغيرة — بلا أي مكتبات خارجية */
    private void loadLogo(final ImageView iv, final String url) {
        if (url == null || url.isEmpty()) { iv.setVisibility(View.INVISIBLE); return; }
        android.graphics.Bitmap b = imgCache.get(url);
        if (b != null) { iv.setImageBitmap(b); return; }
        iv.setTag(url);
        imgs.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(2000); c.setReadTimeout(2500);
                final android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                if (bm != null) {
                    imgCache.put(url, bm);
                    ui.post(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(bm); });
                }
            } catch (Exception e) {
            } finally { if (c != null) try { c.disconnect(); } catch (Exception e) {} }
        });
    }

    /** JSON مصفوفة مصغرة: عناصر {..} لمفتاح مصفوفة — يكفي لنتائج البحث */
    static List<String> jarr(String json, String key) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        String pat = "\"" + key + "\":[";
        int i = json.indexOf(pat);
        if (i < 0) return out;
        i += pat.length();
        int depth = 1, start = -1;
        for (; i < json.length() && depth > 0; i++) {
            char ch = json.charAt(i);
            if (ch == '{') { if (depth == 1) start = i; depth++; }
            else if (ch == '}') { depth--; if (depth == 1 && start >= 0) { out.add(json.substring(start, i + 1)); start = -1; } }
            else if (ch == ']') depth--;
        }
        return out;
    }

    // ═══════════════════ ج51: التحديث التلقائي (remote_update.json على LATCHI-RELEASES) ═══════════════════

    private static final String UPDATE_URL = "https://raw.githubusercontent.com/latchidz/LATCHI-RELEASES/main/remote_update.json";

    /** يفحص التحديث عند كل تشغيل (بحد أقصى مرة كل 6 ساعات) */
    private void maybeCheckUpdate() {
        SharedPreferences sp = getSharedPreferences("latchi_remote", MODE_PRIVATE);
        long last = sp.getLong("upd_checked", 0);
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return;
        sp.edit().putLong("upd_checked", System.currentTimeMillis()).apply();
        new Thread(this::checkUpdate).start();
    }

    private void checkUpdate() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(UPDATE_URL).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Cache-Control", "no-cache");
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            c.disconnect();
            String body = sb.toString();
            int newCode = Integer.parseInt(nz(jstr(body, "versionCode"), "0"));
            String apkUrl = nz(jstr(body, "apkUrl"), "");
            String verName = nz(jstr(body, "versionName"), "");
            String notes = nz(jstr(body, "notes_ar"), nz(jstr(body, "notes"), ""));
            if (newCode > BuildConfig.VERSION_CODE && !apkUrl.isEmpty()) {
                ui.post(() -> showUpdateDialog(newCode, verName, notes, apkUrl));
            }
        } catch (Exception e) { /* لا شيء — الفحص صامت */ }
    }

    /** مربع «تحديث جديد» بأسلوب التطبيق — تنزيل فوري بالـDownloadManager ثم فتح المثبت */
    private void showUpdateDialog(final int newCode, final String verName, final String notes, final String apkUrl) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(8), dp(8), dp(8), dp(4));
        TextView msg = new TextView(this);
        msg.setTextColor(TXT); msg.setTextSize(13);
        String bodyTxt = getString(R.string.update_body, verName.isEmpty() ? String.valueOf(newCode) : verName);
        msg.setText(notes == null || notes.isEmpty() ? bodyTxt : bodyTxt + "\n\n" + notes);
        wrap.addView(msg);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.update_title)
                .setView(wrap)
                .setPositiveButton(R.string.update_now, (d, w) -> downloadUpdate(apkUrl))
                .setNegativeButton(R.string.update_later, null)
                .setCancelable(true)
                .show();
    }

    private void downloadUpdate(String apkUrl) {
        try {
            String name = "latchi-remote-update.apk";
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(android.net.Uri.parse(apkUrl));
            req.setTitle(getString(R.string.update_downloading));
            req.setDescription("LATCHI Remote");
            req.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, name);
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            final long id = dm.enqueue(req);
            toast(getString(R.string.update_downloading));
            BroadcastReceiver done = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (!intent.getAction().equals(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)) return;
                    long doneId = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (doneId != id) return;
                    try { unregisterReceiver(this); } catch (Exception e) {}
                    try {
                        android.net.Uri uri = dm.getUriForDownloadedFile(id);
                        if (uri == null) { toast(getString(R.string.update_failed)); return; }
                        Intent open = new Intent(Intent.ACTION_VIEW);
                        open.setDataAndType(uri, "application/vnd.android.package-archive");
                        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(open);
                    } catch (Exception e) { toast(getString(R.string.update_failed)); }
                }
            };
            IntentFilter f = new IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(done, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(done, f);
        } catch (Exception e) {
            toast(getString(R.string.update_failed));
        }
    }

    // ═══════════════════ الشبكة ═══════════════════

    private void key(String k) { sendCmd("{\"key\":" + q(k) + "}"); }

    private void sendCmd(final String json) {
        if (host == null) return;
        if (!connected) { toast(getString(R.string.not_connected)); return; }
        net.execute(() -> {
            try {
                HttpResp r = http("POST", "http://" + host + ":37777/cmd", json, pin);
                if (r.code == 200) {
                    if (fails > 0) {
                        fails = 0;
                        ui.post(() -> { headState.setText(R.string.state_connected); headState.setTextColor(GREEN); setDot(GREEN); });
                    }
                }
                else if (r.code == 401) ui.post(() -> { toast(getString(R.string.pin_wrong)); });
            } catch (Exception e) {
                fails++;
                ui.post(() -> { headState.setText(R.string.state_disconnected); headState.setTextColor(RED); setDot(RED); });
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

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') sb.append('\\');
            sb.append(ch);
        }
        return sb.append('"').toString();
    }

    static String nz(String s, String def) { return (s == null || s.isEmpty()) ? def : s; }

    // زر الرجوع في لوحة التحكم = قطع يدوي (بلا إعادة اتصال) والعودة للبحث
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
