// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/** Playback callbacks, delivered on the main thread. */
public interface OnPlayerListener {
    void onPlayStart();

    void onPlayStartFail(String reason);

    void onPlayEnd();
}
