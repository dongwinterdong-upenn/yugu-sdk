// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

/**
 * Audio types. The compat layer records WAV: the default file name is {@code <tokenId>.wav}, and an
 * explicit recordName ending in .mp3 is kept as given while the file holds WAV data.
 */
public class AudioType {
    public static final String WAV = "wav";
    public static final String MP3 = "mp3";
}
