// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

/**
 * Shengtong configuration constants, kept with their original values because they are inlined
 * into customer code. {@link #CLOUD_SERVER_ADDRESS} (and any other stkouyu.com address) is mapped
 * to the Yugu platform base {@code https://open.shengzhiai.com} by this compat layer.
 */
public class AppConfig {
    public static final String CLOUD_SERVER_ADDRESS = "ws://api.stkouyu.com:8080";
    public static final String PROVISION = "skegn.provision";
}
