package com.stkouyu.setting;

/** Test access to package-private hooks of com.stkouyu.setting. */
public final class SettingHooks {
    private SettingHooks() {
    }

    public static void resetEngineSetting() {
        EngineSetting.resetForTests();
    }
}
