// SPDX-License-Identifier: Apache-2.0
package com.stkouyu.setting;

import android.content.Context;

import com.stkouyu.AppConfig;
import com.stkouyu.EngineType;
import com.stkouyu.listener.OnInitEngineListener;
import com.stkouyu.util.AiUtil;

import java.io.File;

/**
 * Engine options. In the compat layer:
 * <ul>
 * <li>serverAddress: empty or any stkouyu.com address uses {@code https://open.shengzhiai.com};
 * other http(s) and ws(s) addresses are used as the platform base</li>
 * <li>connectTimeout and serverTimeout are seconds (defaults 10 and 120) for the REST calls</li>
 * <li>VADEnabled turns on the local energy VAD; SDKLogEnabled and logLevel (0 error, 1 warn,
 * 2 info, 3 debug) set the SDK log level; enableSaveLogCatToFile writes SDK logs to
 * {@code <externalFilesDir>/log/stkouyu_sdk.log}</li>
 * <li>native resources, provision files, serverList, sdkCfgAddr and autoDetectNetwork are kept
 * but have no effect (cloud only)</li>
 * </ul>
 */
public class EngineSetting {
    private static volatile EngineSetting instance;

    public File provisionFile;

    private volatile Context context;
    private boolean autoDetectNetwork;
    private String provisionPath;
    private String serverAddress = AppConfig.CLOUD_SERVER_ADDRESS;
    private String serverList;
    private int connectTimeout = 10;
    private int serverTimeout = 120;
    private String nativeResourcePath;
    private String nativeCNResourcePath;
    private String nativeDbPath;
    private boolean vadEnabled;
    private boolean sdkLogEnabled;
    private boolean useOnlineProvision;
    private boolean needUpdateOnlineProvision;
    private String userId;
    private OnInitEngineListener onInitEngineListener;
    private String sdkCfgAddr;
    private String engineType = EngineType.ENGINE_CLOUD;
    private int logLevel = 1;
    private boolean enableUploadLog;
    private boolean enableSaveLogCatToFile;

    private EngineSetting(Context context) {
        this.context = context;
    }

    public boolean isAutoDetectNetwork() {
        return autoDetectNetwork;
    }

    public void setAutoDetectNetwork(boolean autoDetectNetwork) {
        this.autoDetectNetwork = autoDetectNetwork;
    }

    public static EngineSetting getInstance(Context context) {
        EngineSetting s = instance;
        if (s == null) {
            synchronized (EngineSetting.class) {
                s = instance;
                if (s == null) {
                    s = new EngineSetting(app(context));
                    instance = s;
                }
            }
        }
        if (context != null) {
            s.context = app(context);
        }
        return s;
    }

    private static Context app(Context c) {
        if (c == null) {
            return null;
        }
        Context a = c.getApplicationContext();
        return a != null ? a : c;
    }

    /** Selects the cloud engine and returns this setting. */
    public EngineSetting getDefaultCloudInstance() {
        engineType = EngineType.ENGINE_CLOUD;
        return this;
    }

    /** Selects the native engine type; the compat layer still evaluates on cloud (logged at WARN). */
    public EngineSetting getDefaultNativeInstance() {
        engineType = EngineType.ENGINE_NATIVE;
        return this;
    }

    /** {@code <externalFilesDir>/skegn.provision}; no provision file is needed in cloud mode. */
    public File getDefaultProvisionFile() {
        return new File(AiUtil.externalFilesDir(context), AppConfig.PROVISION);
    }

    public String getProvisionPath() {
        return provisionPath;
    }

    public EngineSetting setProvisionPath(String provisionPath) {
        this.provisionPath = provisionPath;
        return this;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public EngineSetting setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
        return this;
    }

    public String getServerList() {
        return serverList;
    }

    public EngineSetting setServerList(String serverList) {
        this.serverList = serverList;
        return this;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public EngineSetting setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public EngineSetting setServerTimeout(int serverTimeout) {
        this.serverTimeout = serverTimeout;
        return this;
    }

    public int getServerTimeout() {
        return serverTimeout;
    }

    public String getNativeResourcePath() {
        return nativeResourcePath;
    }

    public EngineSetting setNativeResourcePath(String nativeResourcePath) {
        this.nativeResourcePath = nativeResourcePath;
        return this;
    }

    public String getNativeCNResourcePath() {
        return nativeCNResourcePath;
    }

    public EngineSetting setNativeCNResourcePath(String nativeCNResourcePath) {
        this.nativeCNResourcePath = nativeCNResourcePath;
        return this;
    }

    public String getNativeDbPath() {
        return nativeDbPath;
    }

    public EngineSetting setNativeDbPath(String nativeDbPath) {
        this.nativeDbPath = nativeDbPath;
        return this;
    }

    public boolean isVADEnabled() {
        return vadEnabled;
    }

    public EngineSetting setVADEnabled(boolean vadEnabled) {
        this.vadEnabled = vadEnabled;
        return this;
    }

    public boolean isSDKLogEnabled() {
        return sdkLogEnabled;
    }

    public EngineSetting setSDKLogEnabled(boolean sdkLogEnabled) {
        this.sdkLogEnabled = sdkLogEnabled;
        return this;
    }

    public boolean isUseOnlineProvision() {
        return useOnlineProvision;
    }

    public EngineSetting setUseOnlineProvision(boolean useOnlineProvision) {
        this.useOnlineProvision = useOnlineProvision;
        return this;
    }

    public EngineSetting setNeedUpdateOnlineProvision(boolean needUpdateOnlineProvision) {
        this.needUpdateOnlineProvision = needUpdateOnlineProvision;
        return this;
    }

    public boolean isNeedUpdateOnlineProvision() {
        return needUpdateOnlineProvision;
    }

    public String getUserId() {
        return userId;
    }

    public EngineSetting setUserId(String userId) {
        this.userId = userId;
        return this;
    }

    public OnInitEngineListener getOnInitEngineListener() {
        return onInitEngineListener;
    }

    public EngineSetting setOnInitEngineListener(OnInitEngineListener onInitEngineListener) {
        this.onInitEngineListener = onInitEngineListener;
        return this;
    }

    public String getSdkCfgAddr() {
        return sdkCfgAddr;
    }

    public EngineSetting setSdkCfgAddr(String sdkCfgAddr) {
        this.sdkCfgAddr = sdkCfgAddr;
        return this;
    }

    public String getEngineType() {
        return engineType;
    }

    public void setEngineType(String engineType) {
        this.engineType = engineType;
    }

    public int getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(int logLevel) {
        this.logLevel = logLevel;
    }

    public void setEnableUploadLog(boolean enableUploadLog) {
        this.enableUploadLog = enableUploadLog;
    }

    public boolean getEnableUploadLog() {
        return enableUploadLog;
    }

    public void setEnableSaveLogCatToFile(boolean enableSaveLogCatToFile) {
        this.enableSaveLogCatToFile = enableSaveLogCatToFile;
    }

    public boolean getEnableSaveLogCatToFile() {
        return enableSaveLogCatToFile;
    }

    /** Test hook (package-private, not API): forget the process-wide instance. */
    static void resetForTests() {
        instance = null;
    }
}
