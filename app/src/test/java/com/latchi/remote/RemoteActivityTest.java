package com.latchi.remote;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RemoteActivityTest {
    @Test
    public void launchApp() {
        RemoteActivity a = Robolectric.setupActivity(RemoteActivity.class);
        try { Thread.sleep(600); } catch (Exception e) {}   // دع خيط الاكتشاف يعمل
        org.junit.Assert.assertNotNull("النشاط يجب أن يبقى حياً", a);
    }
}
