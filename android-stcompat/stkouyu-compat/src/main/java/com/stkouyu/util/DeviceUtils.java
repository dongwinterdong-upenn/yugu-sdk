// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.util;

import android.content.Context;
import android.media.AudioManager;

import com.shengzhiai.yugu.stcompat.internal.CompatEngine;
import com.shengzhiai.yugu.stcompat.internal.ServerAddress;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/** Device helpers: audio focus and a reachability check of the evaluation platform. */
public class DeviceUtils {
    private static final AudioManager.OnAudioFocusChangeListener FOCUS = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            // recording keeps going whatever other apps do
        }
    };

    /** Always true: the minimum supported Android version is far above Froyo. */
    public static boolean hasFroyo() {
        return true;
    }

    /**
     * Requests ({@code true}) or abandons ({@code false}) transient audio focus so background
     * music pauses while recording. Returns true when the request was granted.
     */
    public static boolean muteAudioFocus(Context context, boolean mute) {
        if (context == null) {
            return false;
        }
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return false;
        }
        int r;
        if (mute) {
            r = am.requestAudioFocus(FOCUS, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } else {
            r = am.abandonAudioFocus(FOCUS);
        }
        return r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    /** True when a TCP connection to the evaluation platform (base URL host and port) succeeds within 3 s. */
    public static final boolean ping() {
        Socket s = new Socket();
        try {
            URL u = new URL(ServerAddress.effectiveBase(CompatEngine.currentBaseUrl()));
            int port = u.getPort() > 0 ? u.getPort() : u.getDefaultPort();
            s.connect(new InetSocketAddress(u.getHost(), port), 3000);
            return true;
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }
}
