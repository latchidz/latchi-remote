package com.latchi.remote;

import android.app.Dialog;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RemoteActivityTest {

    /** الإقلاع يبقى حياً بلا انهيار مع شاشتي البحث ولوحة التحكم */
    @Test
    public void launchApp() {
        RemoteActivity a = Robolectric.setupActivity(RemoteActivity.class);
        try { Thread.sleep(600); } catch (Exception e) {}
        assertNotNull("النشاط يجب أن يبقى حياً", a);
    }

    private static void tap(View v, float x, float y) {
        long t = android.os.SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0));
    }

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** D-Pad: الاتجاهات الأربعة ترسل الأسهم الصحيحة، المركز يرسل Enter، والمناطق القطرية لا ترسل شيئاً */
    @Test
    public void dpadZonesAndKeys() {
        Context ctx = RuntimeEnvironment.getApplication();
        List<String> keys = new ArrayList<>();
        RemoteActivity.DPadView v = new RemoteActivity.DPadView(ctx, keys::add);
        int s = Math.round(dp(ctx, 216));
        v.measure(View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, s, s);
        float c = s / 2f;
        float ring = dp(ctx, 76);   // منتصف سماكة قرص الاتجاهات

        tap(v, c, c - ring);                       // ↑
        tap(v, c + ring, c);                       // →
        tap(v, c, c + ring);                       // ↓
        tap(v, c - ring, c);                       // ←
        tap(v, c, c);                              // OK
        tap(v, c + dp(ctx, 54), c - dp(ctx, 54));  // قطري 45° = منطقة عازلة

        assertEquals("عدد المفاتيح المرسلة", 5, keys.size());
        assertEquals("ArrowUp", keys.get(0));
        assertEquals("ArrowRight", keys.get(1));
        assertEquals("ArrowDown", keys.get(2));
        assertEquals("ArrowLeft", keys.get(3));
        assertEquals("Enter", keys.get(4));
    }

    /** الانزلاق بين اتجاهين دون رفع الإصبع يرسل الاتجاهين */
    @Test
    public void dpadSlideBetweenDirections() {
        Context ctx = RuntimeEnvironment.getApplication();
        List<String> keys = new ArrayList<>();
        RemoteActivity.DPadView v = new RemoteActivity.DPadView(ctx, keys::add);
        int s = Math.round(dp(ctx, 216));
        v.measure(View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(s, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, s, s);
        float c = s / 2f;
        float ring = dp(ctx, 76);

        long t = android.os.SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, c, c - ring, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_MOVE, c + ring, c, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 90, MotionEvent.ACTION_UP, c + ring, c, 0));

        assertEquals(2, keys.size());
        assertEquals("ArrowUp", keys.get(0));
        assertEquals("ArrowRight", keys.get(1));
    }

    /** لوحة الأرقام: تنزلق من الأسفل وتحوي 12 زراً فعلياً (1-9 + + 0 + ↵) */
    @Test
    public void numbersSheetOpensWith12Keys() {
        RemoteActivity a = Robolectric.setupActivity(RemoteActivity.class);
        a.showNumbers();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("اللوحة تنفتح", d);
        List<Button> btns = new ArrayList<>();
        collectButtons((ViewGroup) d.getWindow().getDecorView(), btns);
        assertEquals("12 زر أرقام", 12, btns.size());
        List<String> labels = new ArrayList<>();
        for (Button b : btns) labels.add(b.getText().toString());
        for (String n : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"})
            assertTrue("يحوي " + n, labels.contains(n));
        // ج51: زرا الحذف والإدخال نصيان مترجمان (بلا رموز) — الفحص محايد للغة
        int txtBtns = 0;
        for (String l : labels) if (l.trim().length() > 1) txtBtns++;
        assertEquals("زران نصيان (حذف + إدخال)", 2, txtBtns);
    }

    @Test
    public void keyboardSheetOpensWithTypeBox() {
        RemoteActivity a = Robolectric.setupActivity(RemoteActivity.class);
        a.showKeyboard();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("لوحة الكيبورد تنفتح", d);
        // حقل الكتابة موجود
        List<android.widget.EditText> boxes = new ArrayList<>();
        collectEditTexts((ViewGroup) d.getWindow().getDecorView(), boxes);
        assertEquals("حقل كتابة واحد", 1, boxes.size());
        // الأزرار الثلاثة: مسافة + + ↵
        List<Button> btns = new ArrayList<>();
        collectButtons((ViewGroup) d.getWindow().getDecorView(), btns);
        assertEquals("3 أزرار مساعدة", 3, btns.size());
        List<String> labels = new ArrayList<>();
        for (Button b : btns) labels.add(b.getText().toString());
        // ج51: أزرار الحذف/الإدخال/المسافة نصية مترجمة — بلا أي رموز
        long txtBtns = 0;
        for (String l : labels) if (l.trim().length() > 1) txtBtns++;
        assertTrue("أزرار الحذف والإدخال والمسافة نصية مترجمة (3+)", txtBtns >= 3);
        boolean noSymbols = true;
        for (String l : labels) if (l.contains("\u232B") || l.contains("\u21B5")) noSymbols = false;
        assertTrue("بلا رموز الحذف/الإدخال القديمة (أيقونات/نص فقط)", noSymbols);
        // الكتابة في الحقل تُرسل فوراً: نحاكي إدراج حرف — المنفذ قد لا يكون موصولاً لكن لا يُسمح بأي استثناء
        boxes.get(0).getText().append("a");
        // يحذف آخر حرف من الحقل
        android.text.Editable e = boxes.get(0).getText();
        e.append("b"); e.delete(e.length() - 1, e.length());
        assertEquals("الحقل فارغ بعد الحذف", 1, boxes.get(0).getText().length());   // "a" بقيت
    }

    // ═══ ج51: البحث الشامل + أنواع الأجهزة (حاسوب/تلفاز) ═══

    /** parser مصفوفة نتائج /search يستخرج كائنات {..} حتى بالسلاسل المتشعبة */
    @Test
    public void jarrParsesSearchResults() {
        String body = "{\"ok\":true,\"results\":[{\"id\":\"L1\",\"name\":\"قناة الأولى\",\"type\":\"live\"},{\"id\":\"M77\",\"name\":\"فيلم\",\"type\":\"movie\"},{}]}";
        List<String> items = RemoteActivity.jarr(body, "results");
        assertEquals("نتيجتان + عنصر فارغ", 3, items.size());
        assertEquals("اسم النتيجة الأولى", "قناة الأولى", RemoteActivity.jstr(items.get(0), "name"));
        assertEquals("نوع النتيجة الثانية", "movie", RemoteActivity.jstr(items.get(1), "type"));
        assertTrue("مصفوفة مفقودة = قائمة فارغة", RemoteActivity.jarr("{}", "results").isEmpty());
    }

    /** تلفاز LATCHI TV: أيقونة مختلفة + تُخفى الفأرة والكيبورد (بلا أدوات حاسوب) */
    @Test
    public void tvDeviceHidesMouseAndKeyboard() {
        RemoteActivity a = Robolectric.setupActivity(RemoteActivity.class);
        a.hostType = "tv";
        a.enterRemote("LATCHI TV");
        assertNotNull("لوحة اللمس مبنية", a.padPanel);
        assertNotNull("زر الكيبورد مبني", a.kbBtnCtl);
        assertEquals("الفأرة مخفية للتلفاز", View.GONE, a.padPanel.getVisibility());
        assertEquals("الكيبورد مخفي للتلفاز", View.GONE, a.kbBtnCtl.getVisibility());

        a.hostType = "pc";
        a.enterRemote("LATCHI PC");
        assertEquals("الفأرة تعود للحاسوب", View.VISIBLE, a.padPanel.getVisibility());
        assertEquals("الكيبورد يعود للحاسوب", View.VISIBLE, a.kbBtnCtl.getVisibility());
    }

    /** بروتوكول ج51: مسارات البحث والتشغيل وتمرير نوع الجهاز موجودة بالمصدر */
    @Test
    public void g51ProtocolPresent() throws Exception {
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/latchi/remote/RemoteActivity.java")), "UTF-8");
        assertTrue("GET /search?q للبحث في محتوى الجهاز", src.contains("/search?q="));
        assertTrue("POST /play لتشغيل نتيجة عن بعد", src.contains("/play"));
        assertTrue("قراءة نوع الجهاز (type) من الاكتشاف وping", src.contains("jstr(json, \"type\"") && src.contains("jstr(r.body, \"type\""));
        assertTrue("أيقونة تلفاز مختلفة", src.contains("R.drawable.ic_tv"));
        assertTrue("ورقة البحث مثبتة بزر بالصف الذهبي", src.contains("showSearchSheet"));
    }

    private static void collectEditTexts(ViewGroup g, List<android.widget.EditText> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof android.widget.EditText) out.add((android.widget.EditText) c);
            else if (c instanceof ViewGroup) collectEditTexts((ViewGroup) c, out);
        }
    }

    private static void collectButtons(ViewGroup g, List<Button> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof Button) out.add((Button) c);
            else if (c instanceof ViewGroup) collectButtons((ViewGroup) c, out);
        }
    }
}
