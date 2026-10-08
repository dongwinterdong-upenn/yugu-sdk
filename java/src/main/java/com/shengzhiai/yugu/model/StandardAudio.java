package com.shengzhiai.yugu.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Reference audio returned when {@code includeStandardAudio} is set. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StandardAudio {
    @JsonProperty("url")
    private String url;
    @JsonProperty("format")
    private String format;
    @JsonProperty("duration")
    private String duration;

    /** @return audio URL, may be relative to the base URL */
    public String getUrl() {
        return url;
    }

    /** @return format such as wav */
    public String getFormat() {
        return format;
    }

    /** @return duration in seconds as sent by the platform */
    public String getDuration() {
        return duration;
    }
}
