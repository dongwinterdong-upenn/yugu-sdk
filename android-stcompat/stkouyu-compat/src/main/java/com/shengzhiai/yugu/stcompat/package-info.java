// SPDX-License-Identifier: Apache-2.0
/**
 * Yugu platform calls for apps on the Shengtong drop-in that the Shengtong SDK does not have (SDK 2.2.0):
 * speech synthesis ({@link com.shengzhiai.yugu.stcompat.TtsClient}) and the AI report of an evaluation
 * ({@link com.shengzhiai.yugu.stcompat.ReportClient}). Both use the appKey and secretKey of the engine
 * initialised through {@code com.stkouyu.SkEgnManager}, or explicit credentials, and fail with the
 * unchecked {@link com.shengzhiai.yugu.stcompat.YuguApiException}. The Shengtong-compatible API stays in
 * {@code com.stkouyu}.
 */
package com.shengzhiai.yugu.stcompat;
