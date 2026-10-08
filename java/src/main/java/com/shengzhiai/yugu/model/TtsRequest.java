package com.shengzhiai.yugu.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Speech synthesis request, JSON body of {@code POST /api/v1/tts/generate}. */
public class TtsRequest {
    private String text;
    private String language;
    private String voice;
    private String format;
    private Integer speed;
    private Integer pitch;
    private Integer volume;
    private String style;

    /** Creates an empty request; set at least the text. */
    public TtsRequest() {
    }

    /**
     * @param text     text to speak
     * @param language zh-CN or en-US
     * @param voice    xiaoyan or xiaofeng for Chinese, female or male for English
     */
    public TtsRequest(String text, String language, String voice) {
        this.text = text;
        this.language = language;
        this.voice = voice;
    }

    /** @param v text @return this */
    public TtsRequest text(String v) {
        this.text = v;
        return this;
    }

    /** @param v language @return this */
    public TtsRequest language(String v) {
        this.language = v;
        return this;
    }

    /** @param v voice @return this */
    public TtsRequest voice(String v) {
        this.voice = v;
        return this;
    }

    /** @param v mp3 (default), wav or ogg @return this */
    public TtsRequest format(String v) {
        this.format = v;
        return this;
    }

    /** @param v speed in [0, 100], default 50 @return this */
    public TtsRequest speed(Integer v) {
        this.speed = v;
        return this;
    }

    /** @param v pitch in [0, 100], default 50 @return this */
    public TtsRequest pitch(Integer v) {
        this.pitch = v;
        return this;
    }

    /** @param v volume in [0, 100], default 50 @return this */
    public TtsRequest volume(Integer v) {
        this.volume = v;
        return this;
    }

    /** @param v style @return this */
    public TtsRequest style(String v) {
        this.style = v;
        return this;
    }

    /** @return text */
    public String getText() {
        return text;
    }

    /** @return language */
    public String getLanguage() {
        return language;
    }

    /** @return voice */
    public String getVoice() {
        return voice;
    }

    /** @return format */
    public String getFormat() {
        return format;
    }

    /** @return speed */
    public Integer getSpeed() {
        return speed;
    }

    /** @return pitch */
    public Integer getPitch() {
        return pitch;
    }

    /** @return volume */
    public Integer getVolume() {
        return volume;
    }

    /** @return style */
    public String getStyle() {
        return style;
    }

    /**
     * Body fields in the order they are sent; null fields are left out.
     *
     * @return field name to value
     */
    public Map<String, Object> toBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "text", text);
        put(m, "language", language);
        put(m, "voice", voice);
        put(m, "format", format);
        put(m, "speed", speed);
        put(m, "pitch", pitch);
        put(m, "volume", volume);
        put(m, "style", style);
        return m;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }
}
