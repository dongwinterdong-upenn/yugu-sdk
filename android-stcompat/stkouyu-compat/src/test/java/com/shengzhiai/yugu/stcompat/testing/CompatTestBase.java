package com.shengzhiai.yugu.stcompat.testing;

import android.Manifest;
import android.app.Application;

import com.shengzhiai.yugu.stcompat.internal.CancelToken;
import com.shengzhiai.yugu.stcompat.internal.CompatClient;
import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.SkEgnEmulator;
import com.stkouyu.SkEgnManager;
import com.stkouyu.TestHooks;
import com.stkouyu.setting.EngineSetting;
import com.stkouyu.setting.SettingHooks;

import org.junit.After;
import org.junit.Before;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.robolectric.Shadows.shadowOf;

/** Fresh singletons, fake transport, instant back-off and microphone permission for every test. */
public abstract class CompatTestBase {
    protected Application app;
    protected FakeTransport transport;
    protected final List<Long> sleeps = Collections.synchronizedList(new ArrayList<Long>());

    @Before
    public void setUpCompat() {
        app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        TestHooks.resetManager();
        SettingHooks.resetEngineSetting();
        SkEgnEmulator.resetForTests();
        CompatConfig.reset();
        transport = new FakeTransport();
        CompatConfig.setTransport(transport);
        CompatConfig.setRandom(new Random(42));
        CompatConfig.setSleeper(new CompatClient.Sleeper() {
            @Override
            public boolean sleep(CancelToken token, long ms) {
                sleeps.add(ms);
                return !token.isCancelled();
            }
        });
    }

    @After
    public void tearDownCompat() {
        TestHooks.resetManager();
        SettingHooks.resetEngineSetting();
        SkEgnEmulator.resetForTests();
        CompatConfig.reset();
        Main.idle();
    }

    /** SkEgnManager initialised with test keys; init callbacks already delivered. */
    protected SkEgnManager initManager(Events events) {
        SkEgnManager m = SkEgnManager.getInstance(app);
        EngineSetting es = EngineSetting.getInstance(app).setOnInitEngineListener(events == null ? null : events.init);
        m.initEngine("test-app-key", "test-secret", "user-1", es);
        Main.idle();
        return m;
    }
}
