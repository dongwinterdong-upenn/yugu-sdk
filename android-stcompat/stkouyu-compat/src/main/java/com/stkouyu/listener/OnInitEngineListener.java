// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.listener;

/** Engine initialisation callbacks, delivered on the main thread. */
public interface OnInitEngineListener {
    void onStartInitEngine();

    void onInitEngineSuccess();

    void onInitEngineFailed(String reason);
}
