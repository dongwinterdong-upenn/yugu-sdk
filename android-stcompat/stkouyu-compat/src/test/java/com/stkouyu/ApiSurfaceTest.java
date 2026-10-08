package com.stkouyu;

import com.shengzhiai.yugu.stcompat.internal.CompatConfig;
import com.shengzhiai.yugu.stcompat.internal.RetryPolicy;
import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.stkouyu.lame.SimpleLame;
import com.stkouyu.listener.OnSTRecorderListener;
import com.stkouyu.setting.EngineSetting;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Constants keep the Shengtong values; configuration hooks and settings behave. */
@RunWith(RobolectricTestRunner.class)
public class ApiSurfaceTest extends CompatTestBase {
    @Test
    public void constantValues() {
        new AgeGroup();
        new AppConfig();
        new AudioType();
        new Build();
        new CoreType();
        new EngineType();
        new Mode();
        new QType();
        assertEquals(3, AgeGroup.AGEGROUP3);
        assertEquals("ws://api.stkouyu.com:8080", AppConfig.CLOUD_SERVER_ADDRESS);
        assertEquals("skegn.provision", AppConfig.PROVISION);
        assertEquals("mp3", AudioType.MP3);
        assertEquals("1.0.62", Build.VERSION);
        assertEquals("sent.eval.cn", CoreType.CN_SENT_EVAL);
        assertEquals("para.eval.kr", CoreType.KR_PARA_EVAL);
        assertEquals("multi", EngineType.ENGINE_MULTI);
        assertEquals("home", Mode.HOME);
        assertEquals(8, QType.QTYPE_SENTENCE_ALOUD);
        assertEquals(-1, SkEgnManager.CODE_SKEGN_START_FAIL);
        assertEquals(16, SkEgnManager.CODE_PLAY_START_FAIL);
        assertEquals("native", SkEgnManager.SERVER_TYPE_NATIVE);
        assertEquals(4, SkEgnManager.engine_status.values().length);
        assertEquals(SkEgnManager.engine_status.STOP, SkEgnManager.engine_status.valueOf("STOP"));
        CustomParam p = new CustomParam("k", 1);
        p.setKey("k2");
        p.setValue("v");
        assertEquals("k2", p.getKey());
        assertEquals("v", p.getValue());
        new OnSTRecorderListener() {
            @Override
            public void onStart() {
            }
        }.onStart();
    }

    @Test
    public void simpleLameIsAStub() {
        new SimpleLame();
        SimpleLame.init(16000, 1, 0, 5, 0);
        assertEquals(-1, SimpleLame.encodeInterleaved(new short[4], 4, new byte[16]));
        assertEquals(-1, SimpleLame.encode(new short[4], new short[4], 4, new byte[16]));
        assertEquals(-1, SimpleLame.flush(new byte[16]));
        SimpleLame.tags("x");
        SimpleLame.close();
    }

    @Test
    public void yuguCompatHooks() {
        assertEquals("2.0.0", YuguCompat.VERSION);
        assertEquals("yugu-stkouyu-compat/2.0.0 (api 1.0.62)", YuguCompat.getVersion());
        assertNull(YuguCompat.getBaseUrl());
        YuguCompat.setBaseUrl("wss://sandbox.example.com/");
        assertEquals("https://sandbox.example.com", YuguCompat.getBaseUrl());
        try {
            YuguCompat.setBaseUrl("sandbox.example.com");
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
        YuguCompat.setLogLevel(YuguCompat.LOG_INFO);
        assertEquals(YuguCompat.LOG_INFO, YuguCompat.getLogLevel());
        YuguCompat.setLogLevel(99);
        assertEquals(YuguCompat.LOG_DEBUG, YuguCompat.getLogLevel());
        YuguCompat.setLogLevel(-5);
        assertEquals(YuguCompat.LOG_OFF, YuguCompat.getLogLevel());
        YuguCompat.setRetryPolicy(4, 100, 1.5, 1000, 0.1);
        RetryPolicy rp = CompatConfig.retryPolicy();
        assertEquals(4, rp.maxRetries);
        assertEquals(100, rp.initialDelayMs);
        YuguCompat.setTimeouts(1000, 2000, 3000);
        assertEquals(1000, CompatConfig.connectTimeoutMs());
        assertEquals(2000, CompatConfig.readTimeoutMs());
        assertEquals(3000, CompatConfig.totalTimeoutMs());
        YuguCompat.reset();
        assertNull(YuguCompat.getBaseUrl());
        assertSame(RetryPolicy.DEFAULT, CompatConfig.retryPolicy());
        assertEquals(-1, CompatConfig.connectTimeoutMs());
        assertEquals(300000, CompatConfig.totalTimeoutMs());
        assertEquals(YuguCompat.LOG_WARN, YuguCompat.getLogLevel());
    }

    @Test
    public void engineSettingDefaultsAndSetters() {
        EngineSetting s = EngineSetting.getInstance(app);
        assertSame(s, EngineSetting.getInstance(null));
        assertEquals(AppConfig.CLOUD_SERVER_ADDRESS, s.getServerAddress());
        assertEquals(10, s.getConnectTimeout());
        assertEquals(120, s.getServerTimeout());
        assertEquals("cloud", s.getEngineType());
        assertEquals(1, s.getLogLevel());
        assertFalse(s.isVADEnabled());
        assertNull(s.provisionFile);
        assertTrue(s.getDefaultProvisionFile().getPath().endsWith("skegn.provision"));
        assertSame(s, s.setProvisionPath("/p").setServerList("l").setNativeResourcePath("n").setNativeCNResourcePath("c")
                .setNativeDbPath("d").setSDKLogEnabled(true).setUseOnlineProvision(true).setNeedUpdateOnlineProvision(true)
                .setUserId("u").setSdkCfgAddr("cfg").setVADEnabled(true).setOnInitEngineListener(null));
        s.setAutoDetectNetwork(true);
        s.setEnableUploadLog(true);
        s.setEnableSaveLogCatToFile(true);
        assertEquals("/p", s.getProvisionPath());
        assertEquals("l", s.getServerList());
        assertEquals("n", s.getNativeResourcePath());
        assertEquals("c", s.getNativeCNResourcePath());
        assertEquals("d", s.getNativeDbPath());
        assertTrue(s.isSDKLogEnabled());
        assertTrue(s.isUseOnlineProvision());
        assertTrue(s.isNeedUpdateOnlineProvision());
        assertEquals("u", s.getUserId());
        assertEquals("cfg", s.getSdkCfgAddr());
        assertTrue(s.isVADEnabled());
        assertNull(s.getOnInitEngineListener());
        assertTrue(s.isAutoDetectNetwork());
        assertTrue(s.getEnableUploadLog());
        assertTrue(s.getEnableSaveLogCatToFile());
        assertEquals("native", s.getDefaultNativeInstance().getEngineType());
        assertEquals("cloud", s.getDefaultCloudInstance().getEngineType());
    }
}
