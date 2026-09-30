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

    /** لوحة الأرقام: تنزلق من الأسفل وتحوي 12 زراً فعلياً (1-9 + ⌫ + 0 + ↵) */
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
        for (String n : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "⌫", "↵"})
            assertTrue("يحوي " + n, labels.contains(n));
    }

    private static void collectButtons(ViewGroup g, List<Button> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof Button) out.add((Button) c);
            else if (c instanceof ViewGroup) collectButtons((ViewGroup) c, out);
        }
    }
}
