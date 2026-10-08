package com.stkouyu.util;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.shengzhiai.yugu.stcompat.testing.CompatTestBase;
import com.shengzhiai.yugu.stcompat.testing.Main;
import com.stkouyu.YuguCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class UtilTest extends CompatTestBase {
    @Test
    public void aiUtilHashesAndCounts() {
        new AiUtil();
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", AiUtil.sha1("abc"));
        assertNull(AiUtil.sha1(null));
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                AiUtil.md5(app, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
        assertNull(AiUtil.md5(app, null));
        assertNull(AiUtil.md5(app, new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }
        }));
        assertEquals(4, AiUtil.getWordCount("How are you, Tom's"));
        assertEquals(6, AiUtil.getWordCount("今天天气很好"));
        assertEquals(3, AiUtil.getWordCount("hi 你好"));
        assertEquals(0, AiUtil.getWordCount(null));
        assertEquals(2, AiUtil.getHanziCount("hi 你好 ab"));
        assertEquals(0, AiUtil.getHanziCount(null));
    }

    @Test
    public void aiUtilFiles() throws Exception {
        File ext = AiUtil.externalFilesDir(app);
        assertTrue(ext.isDirectory());
        assertNotNull(AiUtil.externalFilesDir(null));
        assertEquals(app.getFilesDir(), AiUtil.getFilesDir(app));
        assertNull(AiUtil.getFilesDir(null));
        File f = new File(app.getCacheDir(), "u/a.txt");
        AiUtil.writeToFile(f.getAbsolutePath(), "中文");
        assertEquals("中文", AiUtil.readFile(f));
        AiUtil.writeToFile(f, (String) null);
        assertEquals("", AiUtil.readFile(f));
        AiUtil.writeToFile(f, new ByteArrayInputStream("xyz".getBytes(StandardCharsets.UTF_8)));
        assertEquals("xyz", AiUtil.readFile(f));
        AiUtil.writeToFile((String) null, "x");
        AiUtil.writeToFile((File) null, "x");
        AiUtil.writeToFile((File) null, (InputStream) null);
        AiUtil.writeToFile(new File("/proc/forbidden/x.txt"), "x");
        AiUtil.writeToFile(new File("/proc/forbidden/y.txt"), new ByteArrayInputStream(new byte[1]));
        assertNull(AiUtil.readFile(null));
        assertNull(AiUtil.readFile(new File(app.getCacheDir(), "missing.txt")));
    }

    @Test
    public void aiUtilAssets() throws Exception {
        assertEquals("声通平替测试 asset\n", AiUtil.readFileFromAssets(app, "stcompat-test/hello.txt"));
        assertNull(AiUtil.readFileFromAssets(app, "stcompat-test/missing.txt"));
        assertNull(AiUtil.readFileFromAssets(null, "x"));
        File dir = AiUtil.unzipFile(app, "stcompat-test/res.zip");
        assertNotNull(dir);
        assertEquals("bb", new String(Files.readAllBytes(new File(dir, "res/b.txt").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(dir, "res/sub").isDirectory());
        assertNotNull(AiUtil.unzipFileCN(app, "stcompat-test/res.zip"));
        assertNull(AiUtil.unzipFile(app, "stcompat-test/evil.zip"));
        assertNull(AiUtil.unzipFile(app, "stcompat-test/hello.txt.zip"));
        assertNull(AiUtil.unzipFile(null, "x"));
        File copy = AiUtil.copyDb2SD(app, "stcompat-test/hello.txt");
        assertNotNull(copy);
        assertTrue(copy.isFile());
        assertNull(AiUtil.copyDb2SD(app, "stcompat-test/missing"));
        assertNull(AiUtil.copyDb2SD(null, "x"));
        AiUtil.copyNativeResToSD(app, "stcompat-test/hello.txt");
        try {
            AiUtil.copyNativeResToSD(app, "stcompat-test/missing");
            fail();
        } catch (IOException expected) {
            // ok
        }
        try {
            AiUtil.copyNativeResToSD(null, null);
            fail();
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void commandUtil() {
        new CommandUtil();
        assertEquals("CommandUtil", CommandUtil.TAG);
        assertEquals(0, CommandUtil.execute("true"));
        assertEquals(3, CommandUtil.execute(new String[] {"echo hi", null, "exit 3"}));
        assertEquals(-1, CommandUtil.execute(new String[0]));
        assertEquals(-1, CommandUtil.execute((String[]) null));
    }

    @Test
    public void countDownTimer() {
        final List<Long> ticks = new ArrayList<>();
        final List<Long> finish = new ArrayList<>();
        CountDownTimer t = new CountDownTimer(1000, 300) {
            @Override
            public void onTick(long millisUntilFinished) {
                ticks.add(millisUntilFinished);
            }

            @Override
            public void onFinish(long elapsedMillis) {
                finish.add(elapsedMillis);
            }
        };
        assertSame(t, t.start());
        assertSame(t, t.start());
        Main.idleFor(300);
        assertEquals(Collections.singletonList(700L), ticks);
        assertSame(t, t.stop());
        assertEquals(300, t.getNowTime());
        Main.idleFor(2000);
        assertEquals(1, ticks.size());
        t.start();
        Main.idleFor(1000);
        assertEquals(Collections.singletonList(1000L), finish);
        assertFalse(t.isCancelled());
        CountDownTimer c = new CountDownTimer(500, 0) {
            @Override
            public void onTick(long millisUntilFinished) {
                ticks.add(-1L);
            }

            @Override
            public void onFinish(long elapsedMillis) {
                finish.add(-1L);
            }
        };
        c.start();
        c.cancel();
        assertTrue(c.isCancelled());
        Main.idleFor(1000);
        assertEquals(1, finish.size());
        CountDownTimer z = new CountDownTimer(0, 10) {
            @Override
            public void onTick(long millisUntilFinished) {
            }

            @Override
            public void onFinish(long elapsedMillis) {
                finish.add(elapsedMillis);
            }
        };
        z.start();
        Main.idle();
        assertEquals(Long.valueOf(0), finish.get(1));
    }

    @Test
    public void deviceUtils() throws Exception {
        new DeviceUtils();
        assertTrue(DeviceUtils.hasFroyo());
        assertTrue(DeviceUtils.muteAudioFocus(app, true));
        assertTrue(DeviceUtils.muteAudioFocus(app, false));
        assertFalse(DeviceUtils.muteAudioFocus(null, true));
        try (ServerSocket server = new ServerSocket(0)) {
            YuguCompat.setBaseUrl("http://127.0.0.1:" + server.getLocalPort());
            assertTrue(DeviceUtils.ping());
        }
        ServerSocket closed = new ServerSocket(0);
        int port = closed.getLocalPort();
        closed.close();
        YuguCompat.setBaseUrl("http://127.0.0.1:" + port);
        assertFalse(DeviceUtils.ping());
    }

    @Test
    public void handlerUtils() throws Exception {
        HandlerUtils h = HandlerUtils.getInstance();
        assertSame(h, HandlerUtils.getInstance());
        final List<Integer> got = Collections.synchronizedList(new ArrayList<Integer>());
        HandlerUtils.HandlerDispose d = new HandlerUtils.HandlerDispose() {
            @Override
            public void handleMessage(Message msg) {
                got.add(msg.what);
            }
        };
        assertEquals(Looper.getMainLooper(), h.getUIHandler().getLooper());
        assertEquals(Looper.getMainLooper(), h.getNewHandler().getLooper());
        h.getNewHandlerCB(d).sendEmptyMessage(1);
        h.getUIHandlerCB(d).sendEmptyMessage(2);
        h.getNewHandlerCB(null).sendEmptyMessage(9);
        h.getUIHandlerCB(null).sendEmptyMessage(9);
        Main.idle();
        Handler child = h.getNewChildHandlerCB(d);
        child.sendEmptyMessage(3);
        h.getNewChildHandlerCB(null).sendEmptyMessage(9);
        Handler plain = h.getNewChildHandler();
        assertTrue(plain.getLooper() != Looper.getMainLooper());
        Main.await("child message", 5000, () -> got.contains(3));
        assertTrue(got.contains(1));
        assertTrue(got.contains(2));
        h.getUIHandler().postDelayed(() -> got.add(99), 10);
        h.UIOnFinish();
        Main.idleFor(100);
        assertFalse(got.contains(99));
    }

    @Test
    public void logs() throws Exception {
        new LogCat();
        File dir = new File(app.getCacheDir(), "logs");
        LogCat.initLogCat(app, dir.getAbsolutePath());
        YuguCompat.setLogLevel(YuguCompat.LOG_DEBUG);
        com.shengzhiai.yugu.stcompat.internal.YLog.w("hello log file", new RuntimeException("boom"));
        LogCat.pushLog(app);
        LogCat.pushLogManually(app);
        LogCat.destorytLogCat();
        String text = new String(Files.readAllBytes(new File(dir, "stkouyu_sdk.log").toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("W/YuguStCompat: hello log file"));
        assertTrue(text.contains("RuntimeException: boom"));
        LogCat.initLogCat(app, null, "custom.log");
        com.shengzhiai.yugu.stcompat.internal.YLog.e("second");
        LogCat.destorytLogCat();
        assertTrue(new File(new File(AiUtil.externalFilesDir(app), "log"), "custom.log").isFile());
        LogCat.initLogCat(app, "/proc/forbidden-log", " ");
        LogCat.destorytLogCat();

        MyLog log = new MyLog();
        log.context = app;
        MyLog.init(app, true);
        assertTrue(MyLog.MYLOG_PATH_SDCARD_DIR.endsWith("/log"));
        MyLog.w("t", (Object) 1);
        MyLog.e("t", (Object) 2);
        MyLog.d("t", (Object) 3);
        MyLog.i("t", (Object) 4);
        MyLog.v("t", (Object) null);
        MyLog.e("t", "x", new RuntimeException());
        MyLog.init(null, false);
        MyLog.w("t", "off");
        MyLog.e("t", "off");
        MyLog.e("t", "off", null);
        MyLog.d("t", "off");
        MyLog.i("t", "off");
        MyLog.v("t", "off");
        MyLog.init(app, true);
    }

    @Test
    public void myUtil() {
        new MyUtil();
        assertTrue(MyUtil.getSerialNumber(app, "k").matches("[0-9a-f]{32}"));
        assertEquals(MyUtil.getSerialNumber(app, "k"), MyUtil.getSerialNumber(null, null));
        assertTrue(MyUtil.isExistsProvisionFileInDD(app));
        assertTrue(MyUtil.isNull(null));
        assertTrue(MyUtil.isNull(" "));
        assertTrue(MyUtil.isNull("NULL"));
        assertFalse(MyUtil.isNull("x"));
        assertTrue(MyUtil.isNotNull("x"));
    }
}
