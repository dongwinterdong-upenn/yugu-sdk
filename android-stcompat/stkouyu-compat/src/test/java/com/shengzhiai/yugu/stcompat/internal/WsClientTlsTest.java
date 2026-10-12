package com.shengzhiai.yugu.stcompat.internal;

import com.shengzhiai.yugu.stcompat.testing.TestWsServer;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * wss: TLS through an SSLSocketFactory, SNI, the certificate name check (endpoint identification
 * on a JVM, an explicit HostnameVerifier as on Android) and the Android 5 and 6 SNI route.
 * The server certificate (SAN dns:localhost) is generated with keytool for the run.
 */
public class WsClientTlsTest {
    private static File dir;
    private static SSLContext server;
    private static SSLContext client;
    private static java.security.Provider conscrypt;
    private static int conscryptPosition;

    @BeforeClass
    public static void certificate() throws Exception {
        // Robolectric puts Conscrypt first in this JVM; the JDK's TLS cannot initialise on top of its EC
        // parameters, so it steps aside while these JDK-side tests run
        java.security.Provider[] providers = java.security.Security.getProviders();
        for (int i = 0; i < providers.length; i++) {
            if ("Conscrypt".equals(providers[i].getName())) {
                conscrypt = providers[i];
                conscryptPosition = i + 1;
                java.security.Security.removeProvider("Conscrypt");
            }
        }
        try {
            Class.forName("sun.security.ssl.SignatureScheme");
        } catch (Throwable t) {
            Assume.assumeNoException("the JDK TLS stack failed to initialise earlier in this JVM", t);
        }
        File keytool = new File(System.getProperty("java.home"), "bin/keytool");
        Assume.assumeTrue("keytool not found next to the test JVM", keytool.isFile());
        dir = java.nio.file.Files.createTempDirectory("yugu-ws-tls").toFile();
        File ks = new File(dir, "server.p12");
        Process p = new ProcessBuilder(keytool.getPath(), "-genkeypair", "-alias", "server", "-keyalg", "EC",
                "-groupname", "secp256r1", "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-keystore", ks.getPath(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
                "-noprompt").redirectErrorStream(true).start();
        String out = read(p.getInputStream());
        assertEquals(out, 0, p.waitFor());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(ks)) {
            keys.load(in, "changeit".toCharArray());
        }
        // the JDK's TLS on both sides: Robolectric puts Conscrypt first in this JVM for Android parity
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509", "SunJSSE");
        kmf.init(keys, "changeit".toCharArray());
        server = SSLContext.getInstance("TLS", "SunJSSE");
        server.init(kmf.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", keys.getCertificate("server"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX", "SunJSSE");
        tmf.init(trust);
        client = SSLContext.getInstance("TLS", "SunJSSE");
        client.init(null, tmf.getTrustManagers(), null);
    }

    @AfterClass
    public static void cleanUp() {
        if (conscrypt != null) {
            java.security.Security.insertProviderAt(conscrypt, conscryptPosition);
        }
        if (dir != null) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static WsClient.Options options(TestWsServer s, String host) {
        WsClient.Options o = new WsClient.Options();
        o.url = "wss://" + host + ":" + s.port() + "/sent.eval.cn?appKey=a";
        o.connectTimeoutMs = 5000;
        o.readTimeoutMs = 5000;
        o.sslSocketFactory = client.getSocketFactory();
        o.resolver = h -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        return o;
    }

    private static FutureTask<WsClient> connectAsync(WsClient.Options o, WsConnection.Listener l) {
        FutureTask<WsClient> f = new FutureTask<>(() -> WsClient.connect(o, l));
        Thread t = new Thread(f);
        t.setDaemon(true);
        t.start();
        return f;
    }

    private static final class Texts implements WsConnection.Listener {
        final LinkedBlockingQueue<String> texts = new LinkedBlockingQueue<>();

        @Override
        public void onText(String text) {
            texts.add(text);
        }

        @Override
        public void onBinary(byte[] data) {
        }

        @Override
        public void onClosed(int code, String reason) {
        }

        @Override
        public void onFailure(IOException error) {
        }
    }

    /** Connects to "localhost" and checks SNI, the Host header and one message each way. */
    private static void roundTrip(TestWsServer s, WsClient.Options o) throws Exception {
        Texts l = new Texts();
        FutureTask<WsClient> f = connectAsync(o, l);
        TestWsServer.Peer p = s.next().readRequest();
        SSLSession session = ((SSLSocket) p.socket).getSession();
        List<SNIServerName> names = ((ExtendedSSLSession) session).getRequestedServerNames();
        assertEquals(Collections.singletonList(new SNIHostName("localhost")), names);
        assertEquals("localhost:" + s.port(), p.headers.get("host"));
        p.accept101();
        WsClient c = f.get(5, TimeUnit.SECONDS);
        p.text("over tls");
        assertEquals("over tls", l.texts.poll(5, TimeUnit.SECONDS));
        c.sendText("你好");
        assertEquals("你好", p.readFrame().text());
        c.abort();
    }

    private static IOException tlsFailure(WsClient.Options o) throws Exception {
        FutureTask<WsClient> f = connectAsync(o, new Texts());
        try {
            f.get(10, TimeUnit.SECONDS);
            fail("the certificate must not be accepted for " + o.url.substring(0, o.url.indexOf('?')));
            return null;
        } catch (ExecutionException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof IOException);
            CompatError mapped = ErrorMapper.fromWsException((IOException) e.getCause());
            assertEquals(String.valueOf(e.getCause()), ErrorTable.TLS_ERROR, mapped.code);
            assertFalse(mapped.retryable);
            return (IOException) e.getCause();
        }
    }

    @Test
    public void endpointIdentificationChecksTheNameInTheHandshake() throws Exception {
        try (TestWsServer s = new TestWsServer(server)) {
            WsClient.Options o = options(s, "localhost");
            o.hostnameVerifier = WsClient.HANDSHAKE_CHECKS_NAME;
            roundTrip(s, o);
            WsClient.Options wrong = options(s, "yugu.test");
            wrong.hostnameVerifier = WsClient.HANDSHAKE_CHECKS_NAME;
            assertTrue(tlsFailure(wrong) instanceof SSLHandshakeException);
        }
    }

    @Test
    public void defaultVerifierAcceptsOnlyTheCertificateName() throws Exception {
        // a plain JVM's placeholder verifier or the one Robolectric installs: either way only "localhost" passes
        try (TestWsServer s = new TestWsServer(server)) {
            roundTrip(s, options(s, "localhost"));
            IOException e = tlsFailure(options(s, "yugu.test"));
            assertTrue(String.valueOf(e), e instanceof SSLHandshakeException || e instanceof SSLPeerUnverifiedException);
        }
    }

    @Test
    public void untrustedCertificateFails() throws Exception {
        try (TestWsServer s = new TestWsServer(server)) {
            WsClient.Options o = options(s, "localhost");
            o.sslSocketFactory = null; // the default trust store does not know the test certificate
            FutureTask<WsClient> f = connectAsync(o, new Texts());
            try {
                f.get(10, TimeUnit.SECONDS);
                fail("an unknown certificate must not be accepted");
            } catch (ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof SSLHandshakeException);
                assertEquals(ErrorTable.TLS_ERROR, ErrorMapper.fromWsException((IOException) e.getCause()).code);
            }
        }
    }

    @Test
    public void anExplicitVerifierDecidesLikeOnAndroid() throws Exception {
        try (TestWsServer s = new TestWsServer(server)) {
            List<String> asked = Collections.synchronizedList(new ArrayList<String>());
            WsClient.Options refuse = options(s, "localhost");
            refuse.hostnameVerifier = (host, session) -> {
                asked.add(host);
                return false;
            };
            FutureTask<WsClient> f = connectAsync(refuse, new Texts());
            try {
                f.get(10, TimeUnit.SECONDS);
                fail("the verifier refused the name");
            } catch (ExecutionException e) {
                assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof SSLPeerUnverifiedException);
                assertEquals(ErrorTable.TLS_ERROR, ErrorMapper.fromWsException((IOException) e.getCause()).code);
            }
            assertEquals(Collections.singletonList("localhost"), asked);

            WsClient.Options accept = options(s, "localhost");
            accept.hostnameVerifier = (host, session) -> "localhost".equals(host) && session.isValid();
            FutureTask<WsClient> ok = connectAsync(accept, new Texts());
            // the refused connection left an accepted socket behind on the server
            TestWsServer.Peer p = null;
            while (p == null || p.socket.isClosed()) {
                p = s.next();
                try {
                    p.readRequest();
                } catch (IOException closed) {
                    p = null;
                }
            }
            p.accept101();
            ok.get(10, TimeUnit.SECONDS).abort();
        }
    }

    // ================================================================ Android 5 and 6 route

    /** SSLParameters of Android 5 and 6: no endpoint identification. */
    static class OldParameters extends SSLParameters {
        @Override
        public void setEndpointIdentificationAlgorithm(String algorithm) {
            throw new NoSuchMethodError("setEndpointIdentificationAlgorithm");
        }
    }

    /** Minimal SSLSocket recording the configuration calls. */
    public static final class OldSocket extends SSLSocket {
        final List<String> hostnames = new ArrayList<>();
        SSLParameters set;
        boolean old;

        @Override
        public SSLParameters getSSLParameters() {
            return old ? new OldParameters() : new SSLParameters();
        }

        @Override
        public void setSSLParameters(SSLParameters params) {
            set = params;
        }

        /** Conscrypt of Android 5 and 6 takes the SNI name here. */
        public void setHostname(String host) {
            hostnames.add(host);
        }

        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) { }
        @Override public String[] getSupportedProtocols() { return new String[0]; }
        @Override public String[] getEnabledProtocols() { return new String[0]; }
        @Override public void setEnabledProtocols(String[] protocols) { }
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void startHandshake() { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) { }
        @Override public boolean getNeedClientAuth() { return false; }
        @Override public void setWantClientAuth(boolean want) { }
        @Override public boolean getWantClientAuth() { return false; }
        @Override public void setEnableSessionCreation(boolean flag) { }
        @Override public boolean getEnableSessionCreation() { return true; }
    }

    @Test
    public void sniThroughSetHostnameWhenSniHostNameIsNotUsable() {
        // Android 5 and 6 lack SNIHostName (NoClassDefFoundError); a name SNIHostName refuses takes the same route
        OldSocket legacy = new OldSocket();
        assertTrue("no endpoint identification asked", WsClient.configureTls(legacy, "bad_host.example", false));
        assertEquals(Collections.singletonList("bad_host.example"), legacy.hostnames);
        assertEquals("nothing to set through SSLParameters", null, legacy.set);

        OldSocket old = new OldSocket();
        old.old = true;
        assertFalse("endpoint identification is not available", WsClient.configureTls(old, "open.shengzhiai.com", true));
        assertEquals(Collections.singletonList(new SNIHostName("open.shengzhiai.com")), old.set.getServerNames());

        OldSocket modern = new OldSocket();
        assertTrue(WsClient.configureTls(modern, "open.shengzhiai.com", true));
        assertEquals("HTTPS", modern.set.getEndpointIdentificationAlgorithm());
        assertEquals(Collections.singletonList(new SNIHostName("open.shengzhiai.com")), modern.set.getServerNames());
        assertTrue(modern.hostnames.isEmpty());

        OldSocket ip = new OldSocket();
        assertTrue(WsClient.configureTls(ip, "10.0.0.1", false));
        assertTrue("no SNI for an IP address", ip.hostnames.isEmpty());
        assertEquals(null, ip.set);
    }
}
