// SPDX-License-Identifier: Apache-2.0
package com.stkouyu;

/** Extra request parameter. Each entry of {@code RecordSetting.setNewParams} is sent as form field key=value. */
public class CustomParam {
    private String key;
    private Object value;

    public CustomParam(String key, Object value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
