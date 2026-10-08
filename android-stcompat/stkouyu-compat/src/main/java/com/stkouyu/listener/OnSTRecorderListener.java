// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/** Called on the main thread when {@code STRecorder} has started capturing. */
public abstract class OnSTRecorderListener {
    public abstract void onStart();
}
