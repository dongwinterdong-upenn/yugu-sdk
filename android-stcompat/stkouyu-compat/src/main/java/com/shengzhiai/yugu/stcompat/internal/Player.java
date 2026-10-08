// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import android.media.MediaPlayer;

/** Plays a recording with {@link MediaPlayer}. All methods must be called on the main thread. */
public final class Player {
    /** Playback events, called on the main thread. */
    public interface Events {
        void onStart();

        void onFail(String reason);

        void onEnd();
    }

    private MediaPlayer player;
    private Events events;

    public boolean isPlaying() {
        return player != null;
    }

    public void play(String path, Events e) {
        stop(false);
        if (path == null || path.isEmpty()) {
            if (e != null) {
                e.onFail("no audio file to play");
            }
            return;
        }
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        events = e;
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer m) {
                if (player == mp) {
                    finish(true);
                }
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer m, int what, int extra) {
                if (player == mp) {
                    Events ev = events;
                    release();
                    if (ev != null) {
                        ev.onFail("playback error " + what + "/" + extra);
                    }
                }
                return true;
            }
        });
        try {
            mp.setDataSource(path);
            mp.prepare();
            mp.start();
        } catch (Exception ex) {
            release();
            if (e != null) {
                e.onFail("cannot play " + path + ": " + ex.getMessage());
            }
            return;
        }
        if (e != null) {
            e.onStart();
        }
    }

    /** Stops playback; {@code notify} reports onEnd for a playback that was running. */
    public void stop(boolean notify) {
        if (player == null) {
            return;
        }
        finish(notify);
    }

    private void finish(boolean notify) {
        Events ev = events;
        release();
        if (notify && ev != null) {
            ev.onEnd();
        }
    }

    private void release() {
        MediaPlayer mp = player;
        player = null;
        events = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (IllegalStateException ignored) {
                // not started
            }
            mp.release();
        }
    }
}
