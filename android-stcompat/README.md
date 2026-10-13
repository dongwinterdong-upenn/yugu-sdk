# 声通安卓平替接入说明

`com.shengzhiai.yugu:stkouyu-compat:2.1.1` 是声通 `17kouyu_1.0.0.jar` 的平替 AAR。`com.stkouyu` 包中的公共类，接口，方法，字段与常量都与声通 jar 一致，评测在优谷雅言云平台完成。接入方替换依赖，删除 `.so` 文件之后，原有代码不改动即可编译运行，需要更换的只有 appKey 与 secretKey，服务地址可选。

各端平替层的概览与切换步骤见仓库根目录 [SHENGTONG-MIGRATION.md](../SHENGTONG-MIGRATION.md)，版本兼容矩阵见 [COMPATIBILITY.md](../COMPATIBILITY.md)。

## 适用范围

| 项目 | 说明 |
|---|---|
| 对应的声通版本 | `17kouyu_1.0.0.jar`，`Build.VERSION` 为 `1.0.62` |
| 平替层版本 | `2.1.1`，`getSDKVersion()` 返回 `yugu-stkouyu-compat/2.1.1 (api 1.0.62)` |
| 系统要求 | Android 5.0 及更高版本，`minSdk 21` |
| 字节码版本 | Java 8 |
| 运行时依赖 | 无，网络请求用 `HttpURLConnection`，边录边传与朗读进度用 AAR 内置的 WebSocket 客户端，录音用 `AudioRecord`，JSON 用系统自带的 `org.json` |
| 评测方式 | 云端评测，离线引擎与双引擎的调用都转到云端 |
| 许可 | Apache-2.0，见同目录 `LICENSE` 与 `NOTICE` |

## 依赖替换

### 原有配置

接入声通时，`stkouyu_evaluation` 模块的 `build.gradle` 一般含有这两处：

```groovy
android {
    sourceSets {
        main {
            jniLibs.srcDirs = ['libs']
        }
    }
}

dependencies {
    api files('libs/17kouyu_1.0.0.jar')
}
```

### 平替配置

在 `settings.gradle` 中加入优谷雅言 Maven 仓库：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://open.shengzhiai.com/maven/' }
    }
}
```

工程仍在根目录 `build.gradle` 的 `allprojects.repositories` 中声明仓库时，把同一行 `maven { url 'https://open.shengzhiai.com/maven/' }` 加在那里。

模块依赖改为：

```groovy
dependencies {
    api 'com.shengzhiai.yugu:stkouyu-compat:2.1.1'
}
```

不使用远程仓库时，把 `stkouyu-compat-2.1.1.aar` 放进 `libs` 目录，依赖写成 `api files('libs/stkouyu-compat-2.1.1.aar')`。AAR 没有传递依赖，单个文件即可使用。依赖方式保持 `api`，依赖 `stkouyu_evaluation` 的其他模块照常访问 `com.stkouyu` 中的类。

### 需要删除的文件与配置

- `libs/17kouyu_1.0.0.jar`
- 各 ABI 目录中的 `libskegn.so` 与 `libmp3lamest.so`
- 只为声通设置的 `jniLibs.srcDirs`，`ndk.abiFilters` 与 `packagingOptions` 条目

混淆规则中针对 `com.stkouyu` 的 `-keep` 可以保留，AAR 自带同样的规则。AAR 的清单文件声明了 `INTERNET` 与 `RECORD_AUDIO` 两项权限，与声通 SDK 的要求相同，录音权限仍需在运行时申请。

## 代码改动

业务代码不用改。初始化时传入优谷雅言开放平台签发的 appKey 与 secretKey，联调期间使用沙箱密钥。沙箱与生产使用同一个地址，区别只在密钥与额度，见 [SANDBOX.md](../SANDBOX.md)：

```java
SkEgnManager manager = SkEgnManager.getInstance(context);
EngineSetting setting = EngineSetting.getInstance(context);
setting.setOnInitEngineListener(initListener);
manager.initEngine("优谷雅言 appKey", "优谷雅言 secretKey", userId, setting);
```

服务地址可选，`EngineSetting.setServerAddress` 的取值按下表处理：

| 取值 | 实际使用的平台地址 |
|---|---|
| 未设置，空串，`AppConfig.CLOUD_SERVER_ADDRESS` 或其他 `stkouyu.com` 地址 | `https://open.shengzhiai.com` |
| 其他 `http://` 或 `https://` 地址 | 原样使用 |
| 其他 `ws://` 或 `wss://` 地址 | 协议头换成 `http://` 或 `https://` 后使用 |
| 不带协议头的其他地址 | 初始化失败，`onInitEngineFailed` 给出原因 |

## 生命周期示例

```java
// 创建：进入评测页面时
SkEgnManager manager = SkEgnManager.getInstance(context);
EngineSetting es = EngineSetting.getInstance(context).setOnInitEngineListener(new OnInitEngineListener() {
    @Override public void onStartInitEngine() { }
    @Override public void onInitEngineSuccess() { /* 可以开始评测 */ }
    @Override public void onInitEngineFailed(String reason) { /* appKey 为空，地址不合法等 */ }
});
manager.initEngine(appKey, secretKey, userId, es);

// 使用：每道题一次
RecordSetting rs = new RecordSetting(CoreType.CN_SENT_EVAL, "今天天气很好");
rs.setDuration(15000);
manager.startRecord(rs, new OnRecorderListener() {
    @Override public void onStart() { }
    @Override public void onStartRecordFail(String reason) { }
    @Override public void onPause() { }
    @Override public void onTick(long millisUntilFinished, double percentUntilFinished) { }
    @Override public void onRecordEnd() { }
    @Override public void onRecording(int vadStatus, int soundIntensity) { }
    @Override public void onScore(String json) {
        // 结果 JSON 或错误 JSON，见“结果 JSON”与“错误 JSON”两节
    }
});
manager.stopRecord();

// 释放：离开页面时，可以重复调用
manager.clearActivityListener();
manager.recycle();
```

`recycle()` 释放录音器，播放器，工作线程与全部监听器引用，重复调用不报错。`recycle()` 之后再次评测需要重新 `initEngine`。`cancel()` 与拼写有误的同名方法 `cancle()` 停止当前评测，丢弃音频，之后不再有回调。

## 支持的评测内核

| coreType | 常量 | 说明 |
|---|---|---|
| `word.eval` | `CoreType.EN_WORD_EVAL` | 英文单词 |
| `word.eval.pro` | `CoreType.WORD_EVAL_PRO` | 英文单词，自适应年龄段 |
| `sent.eval` | `CoreType.EN_SENT_EVAL` | 英文句子 |
| `sent.eval.pro` | `CoreType.SENT_EVAL_PRO` | 英文句子，自适应年龄段 |
| `para.eval` | `CoreType.EN_PARA_EVAL` | 英文段落 |
| `word.eval.cn` | `CoreType.CN_WORD_EVAL` | 中文字词 |
| `sent.eval.cn` | `CoreType.CN_SENT_EVAL` | 中文句子 |
| `para.eval.cn` | `CoreType.CN_PARA_EVAL` | 中文段落 |
| `alpha.eval` | 没有常量，直接传字符串 | 英文字母 |
| `pinyin` | 没有常量，直接传字符串 | 拼音，需要同时设置 `refPinyin` |

`choice.rec`，`open.eval`，`asr.rec`，`asr.eval`，`align.eval` 与法语，日语，韩语内核不受支持，平替层不发起网络请求，直接回调 errId 为 60003 的错误 JSON。

## 参数映射

RecordSetting 的字段按下表转成平台表单字段。值为空的字段不发送，也不参与签名。

| RecordSetting | 表单字段 | 说明 |
|---|---|---|
| `coreType` | 请求路径 `/{coreType}` | 取值见上一节 |
| `refText` | `refText` | 中文内核设置了 `refPinyin` 时可以为空 |
| `refPinyin` | `refPinyin` | |
| `agegroup` | `agegroup` | 设为 1，2，3 时发送，含义与声通相同 |
| `scale`，`scaleD` | `scale` | |
| `precision` | `precision` | 大于 0 时发送 |
| `slack` | `slack` | 不为 0 时发送 |
| `needWordScoreInParagraph` | `paragraph_need_word_score=1` | `para.eval` 与 `para.eval.cn` 总是发送，其他内核设置为 true 时发送 |
| `needPhonemeOutputInWord` | `phoneme_output=1` | |
| `needAttachAudioUrlInResult` | `attachAudioUrl=1` | 结果 JSON 带平台返回的录音下载地址 `audioUrl`，地址保留 7 天 |
| `dict_type`，`dict_dialect`，`customized_lexicon`，`customized_pron` | 同名 | 原样传递 |
| `readtypeDiagnosis` | `readtype_diagnosis` | |
| `output_rawtext`，`punctuate`，`itn`，`detect_nonscorable`，`vad_detction` | 同名 | 原样传递，评测引擎可能忽略 |
| `keywords`，`keypoints`，`keypoints_weight`，`negative_keypoints`，`negativeReftext`，`mode`，`qType` | 同名 | 原样传递，评测引擎可能忽略 |
| `newParams` | 每个 `CustomParam` 对应一个字段 | 键值原样传递，与前面的字段同名时覆盖前者 |
| `request` | `request` | 原始 JSON 字符串，平台合并其中的标量字段 |
| `realtime_feedback` | 不进表单，设为 1 时实时评测通道的参数帧带 `"realtime_feedback": true` | 录音过程中 `onScore` 先收到 `eof` 为 0 的中间结果，见“朗读进度”一节。其他取值时录音同样边录边传，只回调最终结果，见“边录边传”一节 |

只在本地起作用的字段：`duration`，`durationInterval`，`seek`，`ref_length`，`VADEnabled`，`needSoundIntensity`，`forceRecord`，`muteMusic`，`autoRetry`，`errIds`，`serverTimeout`，`isStream`，`audioPath`，`recordFilePath`，`recordName`，`audioSource`，`audioType`，`sampleRate`，`channel`，`needRequestParamsInResult`。

保留但不起作用的字段：`refAudio`，`compress`，`protocol`，`chunkSize`，`max_ogg_delay`，`customized_sig`，`customized_sig_url`，`blendPhonemeEnable`，`coreProvideType`。

## 回调与线程

全部回调都在主线程执行。`OnRecorderListener` 的回调顺序：

1. `onStart`：录音开始
2. `onRecording(vad_status, sound_intensity)`：开启 VAD 或 `needSoundIntensity` 时约每 100 毫秒一次，VAD 状态变化时立即回调。状态 0 为未开始说话，1 为说话中，2 为说话结束，音强取 0 到 100
3. `onTick(millisUntilFinished, percentUntilFinished)`：设置 `duration` 后每隔 `durationInterval` 毫秒一次，默认 100 毫秒。第二个参数为剩余时长占总时长的百分比，从 100 降到 0
4. `onRecordEnd`：录音结束，开始评测
5. `onScore(json)`：结果 JSON 或错误 JSON，`eof` 为 1，每次评测只回调一次。`realtime_feedback` 设为 1 时另有 `eof` 为 0 的中间结果，都在这一次之前，见“朗读进度”一节

`OnRecordListener` 有 `onRecordStart`，`onRecording` 与 `onRecordEnd(json)` 三个回调，结果 JSON 与错误 JSON 都经 `onRecordEnd(json)` 返回，没有中间结果的回调。`setOnRecordBufferListener` 设置的监听器在主线程收到每段录音 PCM 的副本。

开始阶段的失败按下表回调：

| 情形 | `OnRecorderListener` | `OnRecordListener` |
|---|---|---|
| 引擎正忙 | `onStartRecordFail("engine is busy")` | `onRecordEnd(json)`，errId 60008 |
| 麦克风不可用或没有录音权限 | `onStartRecordFail`，参数为原因 | `onRecordEnd(json)`，errId 60004 |
| 引擎未初始化 | `onScore`，errId 60007 | `onRecordEnd(json)`，errId 60007 |
| coreType 不支持 | `onScore`，errId 60003 | `onRecordEnd(json)`，errId 60003 |
| refText 为空 | `onScore`，errId 60006 | `onRecordEnd(json)`，errId 60006 |

`getEngineStatus()` 返回的状态：

| 状态 | 含义 | 进入时机 |
|---|---|---|
| `IDLE` | 空闲 | 初始化之后，`cancel`，`recycle` |
| `RECORDING` | 录音中 | `startRecord`，`restartRecord` |
| `PAUSED` | 已暂停 | `pauseRecord`，之后会回调 `onPause` |
| `STOP` | 录音结束，等待结果或已返回结果 | `stopRecord`，VAD 判定说话结束，到达 `duration`，录音达到 300 秒，`existsAudioTrans` |

上一次评测的结果返回之前引擎处于忙碌状态，新的 `startRecord` 与 `existsAudioTrans` 会被拒绝。结果回调执行时引擎已经空闲，可以在 `onScore` 中直接开始下一题。开启 VAD 时，说话结束后静音达到 `seek` 乘以 10 毫秒，默认 600 毫秒，录音自动停止。`forceRecord` 为 true 时 VAD 只报告状态，不停止录音。

### 朗读进度

`RecordSetting.setRealtime_feedback(1)` 时，录音开始后平替层经平台的兼容实时评测通道边录边传，学生朗读过程中 `OnRecorderListener.onScore` 收到 `eof` 为 0 的中间结果，给出已经读到参考文本的哪个位置。停止录音后照常回调一次 `eof` 为 1 的最终结果，内容与整段上传时相同。中间结果的结构沿用声通 `realtime_feedback`，`result.words` 为这一次新读到的字或词：

```json
{
  "tokenId": "3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c",
  "recordId": "",
  "applicationId": "your-app-key",
  "userId": "user-1",
  "refText": "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。",
  "eof": 0,
  "dtLastResponse": "2026-10-12 08:00:02:516",
  "result": {
    "kernel_version": "1.0.0",
    "resource_version": "1.0.0",
    "words": [
      {
        "charType": 0,
        "scores": { "pronunciation": 100, "skipped": 0, "tone": 100, "overall": 100, "overall_pron": 100 },
        "word": "中",
        "word_parts": [ { "beginIndex": 0, "charType": 0, "endIndex": 0, "part": "中" } ]
      }
    ],
    "bytes": 64000,
    "readUnits": 3,
    "matchedUnits": 3,
    "totalUnits": 25,
    "progress": [
      { "word": "我", "read": true, "matched": true },
      { "word": "是", "read": true, "matched": true },
      { "word": "中", "read": true, "matched": true },
      { "word": "国", "read": false, "matched": false }
    ]
  }
}
```

示例中的 `progress` 只列出前 4 项，实际列出参考文本的全部 25 个字。

| 字段 | 说明 |
|---|---|
| `eof` | 0 表示中间结果 |
| `recordId` | 空串，评测记录编号在最终结果中给出 |
| `result.words` | 这一次新读到的字或词，按参考文本顺序，每项结构同最终结果的 `words`。各项分数只表示识别结果与参考文本是否一致：一致时为 100，`skipped` 为 0，不一致时为 0，`skipped` 为 1，英文的 `tone` 为 0。回调之后不再更新，发音与声调的得分以最终结果为准 |
| `result.progress` | 参考文本全部单位的朗读状态，中文按字，英文按词，标点与空白不计。`read` 为 true 表示朗读位置已越过这个单位，`matched` 为 true 表示识别结果与这个单位一致。`read` 为 true 而 `matched` 为 false 可能是读错，漏读或识别误差，以最终结果为准 |
| `result.readUnits` | 已读的单位数，即当前朗读位置 |
| `result.matchedUnits`，`result.totalUnits` | 已对上的单位数与单位总数 |
| `result.bytes` | 平台已收到的音频字节数 |
| `result.kernel_version`，`result.resource_version` | 与最终结果相同 |

- 只在有新读到的字或词时回调，`words` 不会为空，把各次的 `words` 依次拼起来就是已读的部分。界面跟随朗读位置时，可以累加 `words` 的个数，也可以直接取 `readUnits`。
- 进度一般比朗读晚 1 到 2 秒，随网络情况变化。停止录音后平台对句末再识别一次，最后几个字的中间结果在 `onRecordEnd` 之后，最终结果之前到达。最终结果之后不再有中间结果。
- 中间结果只回调给 `OnRecorderListener.onScore`，`OnRecordListener` 只收到 `onRecordEnd(json)` 的最终结果。
- 录音与 `setIsStream(true)` 的外部音频都支持，音频须为 16000 Hz 单声道 16 位 PCM，平替层的录音符合这一要求。`existsAudioTrans` 照常整段上传，没有中间结果。参考文本为空或平台当时无法跟踪进度时没有中间结果，最终结果不受影响。
- 网络：录音开始时连接同一平台地址的实时评测通道，`https://open.shengzhiai.com` 对应 `wss://open.shengzhiai.com/{coreType}`，端口 443，设备所在网络需要放行 WebSocket。握手与 REST 请求使用同一对 appKey 与 secretKey 签名。
- 退回整段上传：通道在结束帧发出之前连不上或断开时，平替层关闭通道，记录一条 WARN 日志，停止录音后照常经兼容接口上传整段录音，`tokenId` 不变，之后没有新的中间结果。平台在评测开始时才登记幂等键，退回上传不会重复评测，也不会重复计费。
- 断线重连：结束帧发出之后断开时，平替层用同一个 `tokenId` 重新连接，重发参数帧，全部音频与结束帧，最多 2 次。平台对同一个 `tokenId` 重放首次结果，只计费一次。重连后平台从第一个字重新计算进度，已经回调过的字不再回调。仍然失败时回调 errId 为 20009 的错误 JSON，开启 `autoRetry` 时在实时评测通道上重新提交。
- `cancel()` 与 `cancle()` 立即关闭通道，之后没有回调，平台不评测，`recycle()` 同样关闭通道。
- `SkEgn`：`request` 带 `"realtime_feedback": 1`，`audio` 为 16000 Hz 单声道 16 位的 wav 或 pcm 时同样边写边传，中间结果经 `skegn_callback` 回调，结构相同。

### 朗读进度示例

按 `result.progress` 给参考文本着色，未读为灰色，已读已对上为绿色，已读未对上为红色。中文参考文本中的字与 `progress` 逐一对应，标点不在 `progress` 中：

```java
RecordSetting rs = new RecordSetting(CoreType.CN_SENT_EVAL, refText);
rs.setRealtime_feedback(1);
manager.startRecord(rs, new OnRecorderListener() {
    @Override public void onScore(String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            return;
        }
        if (o.optInt("eof", 1) != 0) {
            showResult(o); // 最终结果或错误 JSON
            return;
        }
        JSONArray progress = o.optJSONObject("result").optJSONArray("progress");
        SpannableString text = new SpannableString(refText);
        int unit = 0;
        for (int i = 0; i < refText.length() && unit < progress.length(); i++) {
            if (!Character.isLetterOrDigit(refText.charAt(i))) {
                continue;
            }
            JSONObject u = progress.optJSONObject(unit++);
            int color = !u.optBoolean("read") ? Color.GRAY : u.optBoolean("matched") ? 0xFF1B8A3A : Color.RED;
            text.setSpan(new ForegroundColorSpan(color), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        refTextView.setText(text);
    }
    @Override public void onStart() { }
    @Override public void onStartRecordFail(String reason) { }
    @Override public void onPause() { }
    @Override public void onTick(long millisUntilFinished, double percentUntilFinished) { }
    @Override public void onRecordEnd() { }
    @Override public void onRecording(int vadStatus, int soundIntensity) { }
});
```

英文参考文本按词对应，`progress` 中的词已去掉首尾标点。

## 边录边传

`2.1.1` 起，录音与 `setIsStream(true)` 的外部音频默认经平台的兼容实时评测通道边录边传，与声通 SDK 的做法一致，与 `realtime_feedback` 的取值无关。停止录音时只剩尚未发出的少量音频与结束帧，评测结果随后经同一通道返回，不再等整段录音上传。

- 中间结果：只有 `realtime_feedback` 设为 1 时参数帧才带 `"realtime_feedback": true`，平台才下发朗读进度。其他取值时 `onScore` 只收到一次最终结果，结果 JSON 与整段上传时相同。
- 录音文件：照常写入，`getLastRecordPath()` 与 `playback()` 不受影响。上传的音频仍为 16000 Hz 单声道 16 位 PCM，每帧最多 32000 字节，不做编码。
- 网络：录音开始时连接平台地址对应的 wss 地址，路径为 `/{coreType}`，端口 443，握手与 REST 请求使用同一对 appKey 与 secretKey 签名，设备所在网络需要放行 WebSocket。
- 退回整段上传：通道连不上或在结束帧发出之前断开时，平替层关闭通道，记录一条写明原因的 WARN 日志，停止录音后照常经兼容接口上传整段录音，`tokenId` 不变，不重复计费。结束帧发出之后断开时用同一个 `tokenId` 重连，规则见“朗读进度”一节。
- 关闭：调用 `YuguCompat.setStreamUpload(false)` 之后开始的录音改回停止录音后整段上传，与 `2.1.0` 相同，`realtime_feedback` 设为 1 时仍然边录边传。`YuguCompat.reset()` 恢复边录边传。
- 其他接口：`existsAudioTrans` 照常上传整个文件。`SkEgn` 只在 `request` 带 `realtime_feedback` 1 时边写边传，其他情况在 `skegn_stop` 之后上传，与 `2.1.0` 相同。

### 结果压缩

- 实时评测通道：握手时请求 WebSocket 压缩扩展 permessage-deflate，平台同意后下发的消息经过压缩，平替层解压后再回调。JSON 结果压缩后一般只有原来的十分之一左右。平台未同意时照常不压缩传输。解压后的大小同样受 8 MB 上限约束，超过上限的消息在解压过程中即被拒绝。
- 兼容接口：请求带 `Accept-Encoding: gzip`，平台返回 gzip 压缩的结果，由平替层解压，日志因此能同时给出网络上的字节数与解压后的字节数。

## 结果 JSON

```json
{
  "tokenId": "3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c",
  "recordId": "eval_7954d149c40a",
  "applicationId": "your-app-key",
  "userId": "user-1",
  "refText": "今天天气很好",
  "eof": 1,
  "dtLastResponse": "2026-10-08 12:00:00:123",
  "result": {
    "overall": 94.6,
    "pronunciation": 100,
    "fluency": 96,
    "integrity": 100,
    "tone": 83,
    "words": [
      {
        "word": "今",
        "scores": { "overall": 78, "pronunciation": 78, "tone": 100 },
        "phonemes": [ { "phoneme": "J", "pronunciation": 78 }, { "phoneme": "IN", "pronunciation": 78 } ]
      }
    ],
    "warning": []
  }
}
```

| 字段 | 说明 |
|---|---|
| `tokenId` | 32 位小写十六进制，同时作为请求的 `Idempotency-Key` |
| `recordId` | 平台评测记录编号 |
| `applicationId` | appKey |
| `userId` | `initEngine` 传入的 userId |
| `refText` | 参考文本 |
| `eof` | 结果 JSON 与错误 JSON 为 1，朗读进度的中间结果为 0 |
| `dtLastResponse` | 收到结果时的本地时间，格式 `yyyy-MM-dd HH:mm:ss:SSS` |
| `result` | 平台兼容接口返回的 `result`。除段落逐字条目中补充的 `overall` 与 `pronunciation` 外，逐字节保留 |
| `params` | 设置 `setNeedRequestParamsInResult(true)` 时出现，含 `app`，`audio` 与 `request` |
| `audioUrl` | 设置 `setNeedAttachAudioUrlInResult(true)` 时出现，为平台返回的录音下载地址，平替层原样放入，地址保留 7 天。未设置时没有这个字段 |

`result` 中的常用字段：

| 字段 | 说明 |
|---|---|
| `result.overall` | 总分 |
| `result.pronunciation`，`result.fluency`，`result.integrity`，`result.rhythm` | 发音，流利度，完整度，韵律 |
| `result.tone` | 声调，中文内核 |
| `result.words[].scores.overall` | 字词得分 |
| `result.words[].phonemes[].pronunciation` | 音素得分 |
| `result.sentences[]` | 句子得分，段落内核。参考文本只有一句时同样有一个句子 |
| `result.sentences[].details[].overall` | 段落内核的逐字得分，平替层从 `scores.overall` 复制，与声通的取法一致 |
| `result.sentences[].details[].scores.overall` | 段落内核的逐字得分，平台给出的位置 |
| `result.warning` | 音频质量警告，码表见仓库根目录 [ERRORS.md](../ERRORS.md) |

## 错误 JSON

```json
{"tokenId":"3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c","errId":20009,"error":"网络或服务端临时故障，可重评，autoRetry 默认重评此码: HTTP 503 code=50200 上游评测服务暂不可用 (attempts 3)","eof":1,"applicationId":"your-app-key"}
```

## errId 表

| errId | 含义 | 处理建议 |
|---|---|---|
| 20009 | 网络或服务端临时故障，重试已用尽 | 提示稍后再试，或开启 `autoRetry` |
| 60001 | 音频文件不存在或不可读 | 检查 `audioPath` 与录音目录 |
| 60002 | 音频为空 | 重新录音 |
| 60003 | coreType 不支持 | 换用支持的内核 |
| 60004 | 麦克风不可用或没有录音权限 | 申请录音权限，或关闭占用麦克风的应用 |
| 60005 | 音频短于 1 秒 | 重新录音 |
| 60006 | refText 为空 | 传入参考文本 |
| 60007 | 引擎未初始化 | 先调用 `initEngine` |
| 60008 | 引擎正忙，上一次评测未结束 | 等待结果，或调用 `cancel` |
| 60009 | 音频大于 50 MB，或 WAV 与 PCM 音频长于 300 秒 | 缩短音频 |
| 90005 | 平台响应无法解析 | 检查网络代理，联系技术支持 |
| 90011 | TLS 证书校验失败 | 检查系统时间与网络代理 |
| 平台业务码 | 例如 40001 参数校验失败，40100 未认证，40300 无权限，2003 签名验证失败，2010 appKey 不存在，40902 额度不足，42903 沙箱当日调用次数达到上限 | 完整码表见仓库根目录 [ERRORS.md](../ERRORS.md) |

响应体中的业务码按服务端错误码表解释，1004 与 1005 在错误响应中表示用户禁用与用户锁定，出现在 `result.warning` 中的同号码为音频质量警告，不作为错误处理。响应没有业务码时按 HTTP 状态归类：可重试的状态给 20009，400，405，413，415，422 给 40001，401 给 40100，403 给 40300，404 给 40400，409 给 40900，501 给 50010，其他 4xx 给 40001，其他 5xx 给 50000，其余状态给 90005。

## 重试与幂等

- 每次评测生成一个 `tokenId`，这次评测的全部请求都用这个 `tokenId` 作为 `Idempotency-Key`。网络重试与 `autoRetry` 重新提交复用同一个值，平台对同一个值只计费一次。
- 连接失败，读取超时，HTTP 408，425，429，500，502，503，504 以及码表标为可重试的业务码自动重试 2 次，间隔约 200 毫秒与 400 毫秒，各有 30 % 的随机浮动。响应带 `Retry-After` 时按其等待，最长 30 秒。每次重试都写一条 WARN 日志。
- 参数错误，鉴权失败等不可重试的错误不重试，直接回调。
- 一次提交含重试的总时长上限为 300 秒，`autoRetry` 的每次重新提交另计。
- `RecordSetting.setAutoRetry(true)` 时，错误 JSON 的 errId 若在 `errIds` 中，默认值为 `["20009"]`，同一段音频用同一个 `tokenId` 重新提交，最多再提交 2 次。
- 连接超时取 `EngineSetting.setConnectTimeout`，默认 10 秒。读取超时依次取 `RecordSetting.setServerTimeout` 与 `EngineSetting.setServerTimeout`，默认 120 秒。实时评测通道使用同样的连接超时与读取超时。
- 边录边传时，实时评测通道同样以 `tokenId` 为幂等键。结束帧发出之前断开时退回整段上传，之后断开时在通道上重连，规则见“朗读进度”一节。

## 平替层配置

`com.stkouyu.YuguCompat` 是平替层在 `com.stkouyu` 包中额外提供的类，为声通编写的代码不用调用这个类。

| 方法 | 作用 |
|---|---|
| `setBaseUrl(String)` | 指定平台地址，例如测试环境或私有部署，优先于 `EngineSetting.setServerAddress` |
| `setLogLevel(int)` | 日志级别 `LOG_OFF` 到 `LOG_DEBUG`，默认 `LOG_WARN`，优先于 `EngineSetting` 的日志设置 |
| `setRetryPolicy(int, long, double, long, double)` | 重试次数，初始间隔，倍数，最大间隔，浮动比例 |
| `setTimeouts(int, int, long)` | 连接超时，读取超时，总时长，单位为毫秒 |
| `setStreamUpload(boolean)` | 录音与外部音频是否边录边传，默认 true，见“边录边传”一节 |
| `isStreamUpload()` | 当前是否边录边传 |
| `getVersion()` | 版本字符串 |
| `reset()` | 恢复默认值 |

日志不记录 secretKey，签名与音频内容，appKey 只记录前 4 位。`EngineSetting.setSDKLogEnabled(true)` 时 `setLogLevel` 的 0 到 3 对应 error，warn，info，debug。`setEnableSaveLogCatToFile(true)` 把日志写入 `<externalFilesDir>/log/stkouyu_sdk.log`。打开方法与每次评测的分段耗时见“日志与分段耗时”一节。

### 日志与分段耗时

默认日志级别为 WARN，评测顺利时不输出日志。按声通的写法打开 SDK 日志，把级别设为 2，对应 INFO，每次评测在回调结果之前输出一行 `timing`：

```java
EngineSetting setting = EngineSetting.getInstance(context);
setting.setSDKLogEnabled(true);
setting.setLogLevel(2);                  // 0 error，1 warn，2 info，3 debug
setting.setEnableSaveLogCatToFile(true); // 同时写入日志文件
manager.initEngine(appKey, secretKey, userId, setting);
```

- 生效时间：`initEngine` 时生效，`2.1.1` 起在 `initEngine` 之后设置也立即生效。`setSDKLogEnabled(false)` 恢复 WARN。`YuguCompat.setLogLevel` 优先于这些设置。
- 输出位置：logcat，标签为 `YuguStCompat`。`setEnableSaveLogCatToFile(true)` 时同时写入 `<externalFilesDir>/log/stkouyu_sdk.log`，一般即 `/sdcard/Android/data/<应用包名>/files/log/stkouyu_sdk.log`，可以用 `adb pull` 取出。打开日志文件时文件已超过 2 MB 则从头写。`LogCat.initLogCat(context, dir)` 可以改写到指定目录。
- 日志内容：不含 secretKey，签名与音频，appKey 只记录前 4 位，`tokenId` 用于对应评测。

一行 `timing` 的示例：

```text
timing tokenId=3f0b6a1c9d2e4f5a8b7c6d5e4f3a2b1c mode=ws audio=4.48s afterStop=1650ms upload=60ms wait=1420ms download=150ms parse=15ms deliver=5ms handshake=210ms queued=3200B final=41210B wire=6120B deflate server.total=1380ms server.engine=1205ms server.platform=175ms
```

| 字段 | 含义 |
|---|---|
| `mode` | `ws` 为边录边传，`rest` 为停止录音后整段上传或文件评测，`ws>rest` 为通道在结束帧之前失败，已退回整段上传，行末的 `fallback` 给出原因 |
| `audio` | 音频时长 |
| `afterStop` | 从停止录音到结果回调开始执行，即接入方感受到的等待时间。停止录音包括调用 `stopRecord`，VAD 判定说话结束，到达 `duration` 与录音达到 300 秒，`existsAudioTrans` 与 `skegn_stop` 从调用时算起 |
| `upload` | 从停止录音到最后一个字节发出，边录边传时为结束帧，整段上传时为最后一次请求的请求体 |
| `wait` | 从发完到收到结果的第一个字节，主要是平台评测的时间 |
| `download` | 从结果的第一个字节到最后一个字节，含解压 |
| `parse` | 生成结果 JSON |
| `deliver` | 切换到主线程 |
| `handshake` | 实时评测通道的连接与握手，发生在录音开始时 |
| `queued` | 停止录音时尚未发出的音频字节数 |
| `final`，`body` | 结果的字节数，按解压后计 |
| `wire` | 结果在网络上的字节数，其后的 `deflate` 或 `gzip` 表示经过压缩 |
| `attempts`，`sent` | 整段上传的尝试次数与最后一次请求的字节数 |
| `connections` | 结束帧之后断线重连时的连接数 |
| `server.total` | 平台从收到结束帧或整个请求到结果备好的时间 |
| `server.engine`，`server.platform` | `server.total` 中评测引擎的时间与平台其余处理的时间 |
| `replayed` | 平台按同一个 `tokenId` 重放了首次结果 |
| `errId` | 以错误 JSON 结束时的 errId |

- 查找慢的环节：`upload` 长说明上行慢，边录边传时可以对照 `queued`。`wait` 明显大于 `server.total` 时，差值主要是网络往返与平台接收前的排队。`download` 长说明下行慢，可以对照 `wire` 与压缩标记。
- 值未知时记为 `-`，平台没有给出耗时时不输出 `server` 开头的字段。
- 级别为 DEBUG，即 `setLogLevel(3)` 时，另外按发生顺序输出 `stage` 行，记录每一步距评测开始的毫秒数。平台给出评测引擎各环节的耗时时再输出一行 `engineStages`，环节名称由引擎决定，可能变化。
- 级别为 INFO 或 DEBUG 时，请求带 `timing=1`，平台在结果中附带分段耗时，平替层读取后从结果中删除，回调给应用的 JSON 与 `2.1.0` 相同。级别低于 INFO 时不发送这一字段。

## 外部音频与文件评测

- 外部音频：`setIsStream(true)` 后调用 `startRecord`，平替层不打开麦克风，用 `feed(byte[])` 写入 16000 Hz 单声道 16 位 PCM，写入的音频默认边写入边发送，`stopRecord` 后完成评测。开头带 WAV 文件头时自动跳过文件头。`realtime_feedback` 设为 1 时同样有朗读进度。
- 文件评测：`existsAudioTrans` 评测 `RecordSetting.setAudioPath` 指定的文件。平替层按文件内容识别 WAV，MP3，OGG，AMR，AAC，M4A，FLAC，然后带上对应的 `Content-Type` 上传，不带文件头的 PCM 先补上 WAV 文件头。其他格式原样上传，能否评测以平台为准。参数 `bufSize` 不起作用，参数 `timeout` 不大于 1000 时按秒计，大于 1000 时按毫秒计。
- 录音文件保存为 `recordFilePath` 目录中的 `recordName`，未设置时保存为 `<externalFilesDir>/record/<tokenId>.wav`，`getLastRecordPath()` 返回该路径，`playback()` 播放该文件。录音内容一律为 WAV，显式设置的 `recordName` 以 `.mp3` 结尾时沿用该文件名。
- 上传前的本地检查：文件不存在或不可读给 60001，音频为空给 60002，WAV 与 PCM 短于 1 秒给 60005，大于 50 MB 或 WAV 与 PCM 长于 300 秒给 60009。50 MB 是平台整段上传接口的上限。录音与外部音频达到 300 秒时自动停止，随后照常评测。
- `activeMic()` 预先打开麦克风，`releaseMic()` 释放预先打开的麦克风，录音进行中返回 false。

## 与声通 SDK 的行为差异

| 项目 | 声通 SDK | 平替层 |
|---|---|---|
| 评测位置 | 端侧引擎，支持离线 | 只在云端，`ENGINE_NATIVE` 与 `ENGINE_MULTI` 记录一条 WARN 日志后按云端运行，`getCurrentEngineType()` 返回 `cloud` |
| MP3 录音 | 内置 Lame 编码器 | 不带 MP3 编码器，录音一律为 WAV。默认文件名为 `<tokenId>.wav`，显式设置的 `recordName` 以 `.mp3` 结尾时沿用该文件名，文件内容仍为 WAV。`SimpleLame` 的方法保留，返回 -1 |
| 音频地址 | `attachAudioUrl` 开启时结果带音频下载地址 | 同样返回，`setNeedAttachAudioUrlInResult(true)` 时结果 JSON 带平台返回的 `audioUrl`，地址保留 7 天，到期删除。录音同时保存在本地，`getLastRecordPath()` 返回路径 |
| 实时反馈 | `realtime_feedback` 开启时有中间结果 | 同样有中间结果，`onScore` 收到 `eof` 为 0 的 JSON，`result.words` 为新读到的字或词，结构同声通，分数只表示是否与参考文本一致。另有 `result.progress` 逐字给出朗读状态。需要访问平台的 wss 地址，连不上时退回整段上传，只有最终结果。`OnRecordListener` 没有中间结果 |
| 上传方式 | 录音过程中上传 | 默认同样边录边传，需要访问平台的 wss 地址，连不上时停止录音后整段上传。`YuguCompat.setStreamUpload(false)` 改为停止录音后整段上传 |
| 授权文件 | 需要 provision 文件 | 不需要，`updateProvision` 返回 true，`inquireProvision` 回调 `{"provision":"cloud","message":"cloud mode, no provision file needed"}` |
| VAD | 引擎内置 | 本地能量 VAD，状态取值相同 |
| 日志上传 | `LogCat` 定时上传 | 只写本地文件，`pushLog` 与 `pushLogManually` 不上传 |
| `SkEgn` | JNI 调用本地库 | 同名接口的 Java 实现，引擎句柄为编号，结果经 `skegn_callback` 在主线程回调，错误号沿用 `skegn_errno.h` |
| 录音格式 | 支持多种采样率 | 固定 16000 Hz，单声道，16 位 |
| `STRecorder` | 声通录音器 | 同名接口，PCM 经 `Callback` 在录音线程回调，其他事件以 `SkEgnManager.CODE_*` 消息发往 `setHandler` 设置的 Handler |
| 构造参数 | 按声通定义的顺序 | `RecordSetting(String, String)` 与快捷 `startRecord` 按取值识别 coreType 与 refText，两种顺序都能用 |
| `agegroup` 默认值 | 声通默认值 | 默认 0，表示不发送该字段 |
| 字节码版本 | Java 7 | Java 8，Android Gradle 插件 3.0 及更高版本均可使用 |

## 接口一致性核对工具

`tools/api-diff/run.sh` 核对平替 AAR 与接入方手中的声通 jar 接口是否一致，接入方可以用自己的 jar 运行：

```bash
tools/api-diff/run.sh path/to/17kouyu_1.0.0.jar stkouyu-compat-2.1.1.aar --report-dir api-diff-report
```

核对分两步。第一步用 japicmp `0.23.1` 检查公共与受保护元素的二进制兼容与源码兼容，japicmp 在首次运行时从 Maven Central 下载，校验 SHA-1 与 SHA-256 后存入 `tools/api-diff/lib`。随后严格检查 japicmp 的报告，注解与修饰符的变化也算差异，只容许类文件版本从 Java 7 升到 Java 8。第二步用 javap 逐条比对类声明，成员签名，泛型签名，异常声明与常量值，`native` 修饰符除外。发现缺失或改变的元素时退出码为 1，报告写入 `--report-dir` 指定的目录。

运行条件为 JDK 11 或更高版本，bash，首次运行还需要 curl 或 wget。环境变量 `JAPICMP_JAR` 可以指定已下载的 japicmp，`ANDROID_JAR` 可以指定 `android.jar`。

`2.1.1` 与声通 `1.0.0` jar 的核对结果：39 个公共类，480 个公共与受保护成员，缺失 0 个，改变 0 个，多出的公共类只有 `com.stkouyu.YuguCompat`。

## 构建与测试

```bash
cd android-stcompat
./gradlew :stkouyu-compat:testDebugUnitTest :stkouyu-compat:coverageSummary
./gradlew :stkouyu-compat:assembleRelease :compat-demo:assembleDebug
api-compat-test/run.sh
../ci/android-stcompat.sh
```

单元测试与 Robolectric 测试覆盖参数映射，结果与错误 JSON，errId 映射，WAV 读写，VAD，状态机，autoRetry，生命周期，回调线程与 `SkEgn` 接口，以及朗读进度的中间结果，实时评测通道的退回与重连，WebSocket 客户端的握手，掩码，分片，心跳，关闭与帧大小上限。`2.1.1` 增加的测试覆盖边录边传的默认与关闭，permessage-deflate 的协商，压缩消息的解压与解压后的大小上限，gzip 结果的解压，`timing=1` 的发送条件与从结果中删除，分段耗时日志的内容与日志中没有密钥。压缩测试使用测试内的 WebSocket 服务端，分别在保留与不保留压缩上下文时压缩消息。集成测试启动仓库中 `tools/mock-server` 的平台模拟服务，需要 Node 20 与 `tools/mock-server/node_modules`。`api-compat-test` 中的代码按声通接口编写，分别对声通 jar 与平替 AAR 用 javac 编译，两次都要通过。声通 jar 不随仓库分发，路径由环境变量 `ST_ORIGINAL_JAR` 指定，文件不存在时跳过这一项，同时给出提示。

沙箱集成测试 `SandboxIntegrationTest` 按声通接入方的写法调用真实平台，覆盖句子评测，段落逐字得分，未知 appKey 的鉴权错误，录音下载地址，朗读进度，边录边传时最终结果的压缩与平台耗时，以及整段上传时的 `Server-Timing`，每次运行最多 9 次平台调用，实时评测连接也计入。朗读进度一项按实时速度送入 `spec/fixtures/audio/zh_progress.wav`。设置环境变量 `YUGU_SANDBOX_APPKEY` 与 `YUGU_SANDBOX_SECRET` 后运行，`YUGU_SANDBOX_BASE` 默认为 `https://open.shengzhiai.com`，平台地址经 `EngineSetting.setServerAddress` 设置。未设置密钥时这些测试跳过，`./gradlew test` 照常通过。

示例应用 `compat-demo` 只使用 `com.stkouyu` 接口，appKey 与 secretKey 由 Gradle 属性传入：

```bash
./gradlew :compat-demo:assembleDebug -PyuguAppKey=your-app-key -PyuguSecretKey=your-secret-key
```

## 常见问题

### 运行时报 UnsatisfiedLinkError

平替层不加载任何本地库。出现 `UnsatisfiedLinkError` 说明工程里仍有声通 jar 或其他引用 `libskegn.so` 的代码，检查 `libs` 目录与依赖声明。

### 编译时报类重复

声通 jar 与平替 AAR 同时在依赖中时会出现 `Duplicate class com.stkouyu...`，删除声通 jar 即可。

### 开始录音立即失败

`onStartRecordFail` 的参数给出原因。常见原因是没有在运行时申请 `RECORD_AUDIO` 权限，或其他应用占用了麦克风。上一次评测的结果尚未返回时会收到 `engine is busy`。

### 评测返回 2003 或 2010

2003 表示签名验证失败，2010 表示 appKey 不存在。检查传给 `initEngine` 的是优谷雅言开放平台签发的 appKey 与 secretKey，同时检查设备时间，时间偏差超过 5 分钟时签名会被拒绝。

### 结果字段名

`result` 来自平台兼容接口。总分，字词得分与音素得分的位置与声通结果相同，见“结果 JSON”一节。段落内核的逐字得分在 `result.sentences[].details[]` 中，平台给出的位置是 `scores.overall`。为兼容按声通写的解析代码，平替层在缺少 `overall` 的条目上补充 `overall`，取值复制自 `scores.overall`，`scores` 中有 `pronunciation` 而条目缺少时同时补充 `pronunciation`。已有字段不改不删，`result` 的其余内容与平台返回的文本一致。段落内核总是请求逐字得分，不设置 `setNeedWordScoreInParagraph(true)` 也有 `details`。平台还会返回带下划线前缀的字段与 `compositeReport` 等扩展字段，解析时忽略即可。

### 弱网与重复计费

可重试的错误由平替层自动重试，所有重试使用同一个 `tokenId` 作为幂等键，平台只计费一次。业务层不需要自行重试，需要更多次数时开启 `autoRetry`。

### 离线评测

平替层只做云端评测，没有网络时返回 errId 20009。需要离线能力的场景请继续使用声通端侧引擎。

### 收不到朗读进度

先确认已设置 `setRealtime_feedback(1)`，回调使用的是 `OnRecorderListener`。日志中有 `realtime channel failed before end` 一行时，实时评测通道没有连上或中途断开，评测已退回整段上传，常见原因是网络拦截了 WebSocket，需要放行平台地址的 wss 连接，端口 443。外部音频须为 16000 Hz 单声道 16 位 PCM，参考文本为空时没有朗读进度。以上情况最终结果都照常返回。

### 评测结果返回慢

按“日志与分段耗时”一节打开 INFO 日志，从 `timing` 行判断慢在上传，平台评测还是下载。录音的 `mode` 为 `rest` 时，说明调用过 `YuguCompat.setStreamUpload(false)`，录音在停止后才整段上传。`mode` 为 `ws>rest` 时，实时评测通道没有连上或中途断开，同时有一行 `realtime channel failed before end` 的 WARN 日志给出原因，需要放行平台地址的 wss 连接。
