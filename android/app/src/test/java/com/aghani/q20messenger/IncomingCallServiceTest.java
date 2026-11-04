package com.aghani.q20messenger;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=19)
public class IncomingCallServiceTest {
    @Test public void listenerIsForegroundAndCanBeStopped() {
        // Keep the worker away from any real account or server during this test.
        AppPrefs.save(RuntimeEnvironment.getApplication(), "http://127.0.0.1:1", "1000");
        AppPrefs.saveApiToken(RuntimeEnvironment.getApplication(), "fixture-only");
        ServiceController<IncomingCallService> controller = Robolectric.buildService(IncomingCallService.class).create();
        IncomingCallService service = controller.get();
        try {
            Notification notification = Shadows.shadowOf(service).getLastForegroundNotification();
            assertNotNull(notification);
            assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            assertTrue(notification.actions.length > 0);
            assertEquals("Stop listening", notification.actions[0].title.toString());
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(new Intent().setAction("stop"), 0, 1));
        } finally { controller.destroy(); }
    }
}
