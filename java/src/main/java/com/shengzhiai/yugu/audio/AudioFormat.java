package com.shengzhiai.yugu.audio;

/** Container of an audio payload as detected by the SDK. */
public enum AudioFormat {
    /** RIFF WAVE file. */
    WAV,
    /** Raw PCM samples without header (16 bit little endian mono unless stated otherwise). */
    PCM,
    /** MPEG audio (ID3 tag or frame sync). */
    MP3,
    /** Anything else; only the size is checked. */
    OTHER
}
