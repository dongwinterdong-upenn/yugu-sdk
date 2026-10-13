// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat;

/**
 * Speech synthesis request, the JSON body of {@code POST /api/v1/tts/generate} (SDK 2.2.0). Only
 * {@code text} is required; fields left null or empty are not sent and the platform uses its
 * defaults.
 *
 * <pre>
 * TtsRequest r = new TtsRequest("床前明月光").setLanguage("zh-CN").setVoice("xiaoyan").setFormat("mp3");
 * </pre>
 */
public final class TtsRequest {
    private String text;
    private String language;
    private String voice;
    private String format;
    private Integer speed;
    private Integer pitch;
    private Integer volume;
    private Integer sampleRate;
    private String emotion;
    private String style;
    private String pinyin;
    private String genre;

    public TtsRequest() {
    }

    /** @param text text to speak, required, at most 1000 characters */
    public TtsRequest(String text) {
        this.text = text;
    }

    /** Text to speak, required, at most 1000 characters. */
    public TtsRequest setText(String text) {
        this.text = text;
        return this;
    }

    public String getText() {
        return text;
    }

    /** {@code zh-CN}, {@code en-US} or {@code en-GB}. */
    public TtsRequest setLanguage(String language) {
        this.language = language;
        return this;
    }

    public String getLanguage() {
        return language;
    }

    /** {@code xiaoyan} or {@code xiaofeng} for Chinese, {@code female} or {@code male} for English. */
    public TtsRequest setVoice(String voice) {
        this.voice = voice;
        return this;
    }

    public String getVoice() {
        return voice;
    }

    /** {@code mp3} (platform default), {@code wav} or {@code ogg}. */
    public TtsRequest setFormat(String format) {
        this.format = format;
        return this;
    }

    public String getFormat() {
        return format;
    }

    /** 0 to 100, platform default 50. */
    public TtsRequest setSpeed(Integer speed) {
        this.speed = speed;
        return this;
    }

    public Integer getSpeed() {
        return speed;
    }

    /** 0 to 100, platform default 50. */
    public TtsRequest setPitch(Integer pitch) {
        this.pitch = pitch;
        return this;
    }

    public Integer getPitch() {
        return pitch;
    }

    /** 0 to 100, platform default 50. */
    public TtsRequest setVolume(Integer volume) {
        this.volume = volume;
        return this;
    }

    public Integer getVolume() {
        return volume;
    }

    /** Sample rate in Hz, 8000 to 48000; the platform reports in its warnings when it cannot honour it. */
    public TtsRequest setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    public Integer getSampleRate() {
        return sampleRate;
    }

    /** Emotion preset {@code neutral}, {@code warm} or {@code energetic}; wins over {@link #setStyle}. */
    public TtsRequest setEmotion(String emotion) {
        this.emotion = emotion;
        return this;
    }

    public String getEmotion() {
        return emotion;
    }

    /** Style instruction in natural language, at most 200 characters. */
    public TtsRequest setStyle(String style) {
        this.style = style;
        return this;
    }

    public String getStyle() {
        return style;
    }

    /** Reading of Chinese text, numbered pinyin per character separated by spaces, for example {@code qu1 xiang4}. */
    public TtsRequest setPinyin(String pinyin) {
        this.pinyin = pinyin;
        return this;
    }

    public String getPinyin() {
        return pinyin;
    }

    /** Text genre of Chinese text, {@code classical_poem} or {@code prose}; detected when not set. */
    public TtsRequest setGenre(String genre) {
        this.genre = genre;
        return this;
    }

    public String getGenre() {
        return genre;
    }
}
