package com.shengzhiai.yugu

import com.shengzhiai.yugu.internal.JObj
import com.shengzhiai.yugu.internal.Json

/** 原生评测的 coreType。 */
public object CoreType {
    public const val WORD: String = "word"
    public const val SENTENCE: String = "sentence"
    public const val PASSAGE: String = "passage"
    public const val CONNECTED: String = "connected"
    public const val OPEN: String = "open"
    public const val ALPHA: String = "alpha"
    public const val PINYIN: String = "pinyin"

    /** 全部取值。 */
    @JvmField
    public val ALL: Set<String> = setOf(WORD, SENTENCE, PASSAGE, CONNECTED, OPEN, ALPHA, PINYIN)
}

/** 声通兼容接口的 coreType，取声通命名，用作路径 `/{coreType}`。 */
public object CompatCoreType {
    public const val WORD_EVAL: String = "word.eval"
    public const val WORD_EVAL_PRO: String = "word.eval.pro"
    public const val SENT_EVAL: String = "sent.eval"
    public const val SENT_EVAL_PRO: String = "sent.eval.pro"
    public const val PARA_EVAL: String = "para.eval"
    public const val ALPHA_EVAL: String = "alpha.eval"
    public const val WORD_EVAL_CN: String = "word.eval.cn"
    public const val SENT_EVAL_CN: String = "sent.eval.cn"
    public const val PARA_EVAL_CN: String = "para.eval.cn"
    public const val PINYIN: String = "pinyin"

    /** 全部取值。 */
    @JvmField
    public val ALL: Set<String> = setOf(
        WORD_EVAL, WORD_EVAL_PRO, SENT_EVAL, SENT_EVAL_PRO, PARA_EVAL,
        ALPHA_EVAL, WORD_EVAL_CN, SENT_EVAL_CN, PARA_EVAL_CN, PINYIN,
    )
}

/** 语言取值。 */
public object Language {
    public const val EN_US: String = "en-US"
    public const val EN_GB: String = "en-GB"
    public const val ZH_CN: String = "zh-CN"
}

/** 开放题题型。 */
public object TaskType {
    public const val PICTURE: String = "picture"
    public const val SITUATIONAL: String = "situational"
    public const val FREE: String = "free"
}

/**
 * 原生评测配置，对应 `config` 段的 JSON，也用作原生实时评测的开始帧。值为 null 的字段不发送，
 * 由平台取默认值。[extra] 中的字段原样追加，用于平台新增而 SDK 尚未列出的字段。
 */
public data class EvaluateConfig @JvmOverloads constructor(
    val coreType: String,
    val referenceText: String,
    val language: String? = null,
    val includeReport: Boolean? = null,
    val includeStandardAudio: Boolean? = null,
    val includeAsrText: Boolean? = null,
    /** 松紧度，取值 -1 到 1。 */
    val slack: Double? = null,
    /** 分制，取值 1 到 100。 */
    val scale: Int? = null,
    /** 精度，取值大于 0 不超过 1。 */
    val precision: Double? = null,
    /** 年龄段：1 学前，2 小学，3 十二岁以上。 */
    val agegroup: Int? = null,
    val toneWeight: Double? = null,
    val refPinyin: String? = null,
    val phonemeOutput: Boolean? = null,
    val taskType: String? = null,
    val paragraphNeedWordScore: Int? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** 序列化后的 `config` 段原文，即签名所用的文本。 */
    public fun toJson(): String = Json.write(toMap())

    internal fun toMap(): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["coreType"] = coreType
        m["referenceText"] = referenceText
        language?.let { m["language"] = it }
        includeReport?.let { m["includeReport"] = it }
        includeStandardAudio?.let { m["includeStandardAudio"] = it }
        includeAsrText?.let { m["includeAsrText"] = it }
        slack?.let { m["slack"] = it }
        scale?.let { m["scale"] = it }
        precision?.let { m["precision"] = it }
        agegroup?.let { m["agegroup"] = it }
        toneWeight?.let { m["toneWeight"] = it }
        refPinyin?.let { m["refPinyin"] = it }
        phonemeOutput?.let { m["phonemeOutput"] = it }
        taskType?.let { m["taskType"] = it }
        paragraphNeedWordScore?.let { m["paragraphNeedWordScore"] = it }
        for ((k, v) in extra) if (v != null && !m.containsKey(k)) m[k] = v
        return m
    }

    internal fun validate() {
        if (coreType !in CoreType.ALL) throw invalidArgument("coreType 取值不合法：$coreType，可选 ${CoreType.ALL}")
        if (referenceText.isBlank()) throw invalidArgument("referenceText 不能为空")
        if (referenceText.length > 1000) throw invalidArgument("referenceText 不能超过 1000 字")
        slack?.let { if (it < -1.0 || it > 1.0) throw invalidArgument("slack 取值 -1 到 1") }
        scale?.let { if (it < 1 || it > 100) throw invalidArgument("scale 取值 1 到 100") }
        precision?.let { if (it <= 0.0 || it > 1.0) throw invalidArgument("precision 取值大于 0 不超过 1") }
        for (v in listOf(slack, precision, toneWeight)) {
            if (v != null && (v.isNaN() || v.isInfinite())) throw invalidArgument("数值字段不能是 NaN 或无穷大")
        }
    }

    /** Java 调用方使用的构建器。 */
    public class Builder(private val coreType: String, private val referenceText: String) {
        private var language: String? = null
        private var includeReport: Boolean? = null
        private var includeStandardAudio: Boolean? = null
        private var includeAsrText: Boolean? = null
        private var slack: Double? = null
        private var scale: Int? = null
        private var precision: Double? = null
        private var agegroup: Int? = null
        private var toneWeight: Double? = null
        private var refPinyin: String? = null
        private var phonemeOutput: Boolean? = null
        private var taskType: String? = null
        private var paragraphNeedWordScore: Int? = null
        private val extra = LinkedHashMap<String, Any?>()

        public fun language(v: String?): Builder = apply { language = v }
        public fun includeReport(v: Boolean?): Builder = apply { includeReport = v }
        public fun includeStandardAudio(v: Boolean?): Builder = apply { includeStandardAudio = v }
        public fun includeAsrText(v: Boolean?): Builder = apply { includeAsrText = v }
        public fun slack(v: Double?): Builder = apply { slack = v }
        public fun scale(v: Int?): Builder = apply { scale = v }
        public fun precision(v: Double?): Builder = apply { precision = v }
        public fun agegroup(v: Int?): Builder = apply { agegroup = v }
        public fun toneWeight(v: Double?): Builder = apply { toneWeight = v }
        public fun refPinyin(v: String?): Builder = apply { refPinyin = v }
        public fun phonemeOutput(v: Boolean?): Builder = apply { phonemeOutput = v }
        public fun taskType(v: String?): Builder = apply { taskType = v }
        public fun paragraphNeedWordScore(v: Int?): Builder = apply { paragraphNeedWordScore = v }
        public fun extra(key: String, value: Any?): Builder = apply { extra[key] = value }

        public fun build(): EvaluateConfig = EvaluateConfig(
            coreType, referenceText, language, includeReport, includeStandardAudio, includeAsrText,
            slack, scale, precision, agegroup, toneWeight, refPinyin, phonemeOutput, taskType,
            paragraphNeedWordScore, LinkedHashMap(extra),
        )
    }

    public companion object {
        /** 创建构建器。 */
        @JvmStatic
        public fun builder(coreType: String, referenceText: String): Builder = Builder(coreType, referenceText)
    }
}

/**
 * 声通兼容评测配置，用于兼容 REST `POST /{coreType}` 与兼容实时评测 `WS /{coreType}`。
 * [params] 为其余表单字段，例如 `agegroup`，`scale`，`paragraph_need_word_score`，原样发送。
 * [realtimeFeedback] 只对实时评测有效，开启后服务端下发进度中间帧。
 */
public data class CompatConfig @JvmOverloads constructor(
    val coreType: String,
    val refText: String,
    val language: String? = null,
    val refPinyin: String? = null,
    val realtimeFeedback: Boolean = false,
    val params: Map<String, String> = emptyMap(),
) {
    internal fun formFields(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["refText"] = refText
        language?.takeIf { it.isNotEmpty() }?.let { m["language"] = it }
        refPinyin?.takeIf { it.isNotEmpty() }?.let { m["refPinyin"] = it }
        for ((k, v) in params) if (v.isNotEmpty() && !m.containsKey(k)) m[k] = v
        return m
    }

    internal fun paramFrame(): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m.putAll(formFields())
        if (realtimeFeedback) m["realtime_feedback"] = true
        return m
    }

    internal fun validate() {
        if (coreType !in CompatCoreType.ALL) throw invalidArgument("兼容 coreType 取值不合法：$coreType，可选 ${CompatCoreType.ALL}")
        if (refText.isBlank()) throw invalidArgument("refText 不能为空")
        if (coreType == CompatCoreType.PINYIN && refPinyin.isNullOrBlank() && params["refPinyin"].isNullOrBlank()) {
            throw invalidArgument("coreType=pinyin 时 refPinyin 必填")
        }
    }
}

/** 语音合成请求。`speed`，`pitch`，`volume` 取值 0 到 100。 */
public data class TtsRequest @JvmOverloads constructor(
    val text: String,
    val language: String = Language.ZH_CN,
    val voice: String = "xiaoyan",
    val format: String = "mp3",
    val speed: Int = 50,
    val pitch: Int = 50,
    val volume: Int = 50,
    val style: String? = null,
) {
    internal fun body(): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["text"] = text
        m["language"] = language
        m["voice"] = voice
        m["format"] = format
        m["speed"] = speed
        m["pitch"] = pitch
        m["volume"] = volume
        style?.let { m["style"] = it }
        return m
    }

    internal fun validate() {
        if (text.isBlank()) throw invalidArgument("text 不能为空")
        for ((name, v) in listOf("speed" to speed, "pitch" to pitch, "volume" to volume)) {
            if (v < 0 || v > 100) throw invalidArgument("$name 取值 0 到 100")
        }
    }
}

/** 时间区间，单位 10 毫秒。 */
public data class Span(val start: Int, val end: Int)

/** 音素得分。 */
public data class PhonemeScore(
    val phoneme: String,
    val pronunciation: Double?,
    val span: Span?,
    val raw: Map<String, Any?>,
)

/** 字词得分，对应 `result.words[]`。 */
public data class WordScore(
    val word: String,
    val pinyin: String?,
    val symbolPinyin: String?,
    /** 声调，形如 `tone3`，英文为 null。 */
    val tone: String?,
    val readStatus: String?,
    val overall: Double?,
    val pronunciation: Double?,
    val toneScore: Double?,
    val span: Span?,
    val phonemes: List<PhonemeScore>,
    val raw: Map<String, Any?>,
)

/**
 * 句子得分，对应 `result.sentences[]`，段落评测按句给分。
 * [words] 为该句逐字详分 `details[]`，段落评测需在配置里设 `paragraphNeedWordScore = 1`。
 */
public data class SentenceScore(
    val index: Int?,
    val text: String,
    val overall: Double?,
    val pronunciation: Double?,
    val fluency: Double?,
    val integrity: Double?,
    val span: Span?,
    val words: List<WordScore>,
    val raw: Map<String, Any?>,
)

/** ASR 对齐单元。`readStatus` 取值 correct，mispronounced，skipped，inserted。 */
public data class AsrAlignment(
    val char: String,
    val index: Int?,
    val readStatus: String?,
    val startTime: Int?,
    val endTime: Int?,
    val asrPinyin: String?,
    val asrTone: Int?,
    val gopScore: Double?,
)

/** ASR 识别文本与对齐，[raw] 保留其余字段，例如 `overreadDetected`，`substitutionDetected`。 */
public data class AsrText(val text: String, val alignment: List<AsrAlignment>, val raw: Map<String, Any?>)

/** 标准示范音。 */
public data class StandardAudio(val url: String, val format: String?, val duration: String?)

/** 评测报告，`raw` 保留完整字段，例如 `dimensions`，`suggestions`，`dimensionScores`。 */
public data class Report(
    val summary: String?,
    val suggestions: List<String>,
    /** 维度分，值为 null 表示本次不评该维，不等于零分。 */
    val dimensionScores: Map<String, Double?>,
    val raw: Map<String, Any?>,
)

/** 连读评测一个词间边界的判定，对应 `result.boundaries[]`，时间单位毫秒。 */
public data class ConnectedBoundary(
    /** 边界两侧的词。 */
    val between: List<String>,
    /** 边界上应有的现象，例如 `linking_CV`，`elision`，`reduction`。 */
    val tags: List<String>,
    /** 实现程度，0 到 1。 */
    val realized: Double?,
    val startMs: Double?,
    val endMs: Double?,
    val gapMs: Double?,
    val continuity: Double?,
    val raw: Map<String, Any?>,
)

/**
 * 连读评测 `coreType = connected` 的得分。该模式没有 `result.overall`，总分为 `result.connected_overall`，
 * [EvalResult.overall] 已按此取值。[summary] 与 [suggestions] 取自 `result.report`。
 */
public data class ConnectedScores(
    val overall: Double?,
    val linking: Double?,
    val rhythm: Double?,
    val elision: Double?,
    val reduction: Double?,
    val boundaryCount: Int?,
    val boundaries: List<ConnectedBoundary>,
    val summary: String?,
    val suggestions: List<String>,
    val raw: Map<String, Any?>,
)

/** 开放题内容维度，对应 `result.content`。 */
public data class OpenContent(
    val overall: Double?,
    val relevance: Double?,
    val coherence: Double?,
    val taskAchievement: Double?,
    val raw: Map<String, Any?>,
)

/** 开放题语言运用维度，对应 `result.languageUse`。 */
public data class OpenLanguageUse(
    val overall: Double?,
    val grammar: Double?,
    val vocabulary: Double?,
    val raw: Map<String, Any?>,
)

/** 开放题表达呈现维度，对应 `result.delivery`。[speechRate] 为每秒字数，[speechRateLabel] 为可直接展示的文字。 */
public data class OpenDelivery(
    val overall: Double?,
    val fluency: Double?,
    val pronunciation: Double?,
    val speechRate: Double?,
    val speechRateLabel: String?,
    val pauseCount: Int?,
    val raw: Map<String, Any?>,
)

/** 开放题评语，对应 `result.feedback`。 */
public data class OpenFeedback(
    val strengths: String?,
    val weaknesses: String?,
    val suggestions: List<String>,
    val raw: Map<String, Any?>,
)

/** 开放题审计，对应 `result.openTaskAudit`。`promptEcho` 命中总分封顶 60，`lowContent` 命中封顶 70。 */
public data class OpenTaskAudit(
    val promptEcho: Boolean?,
    val lowContent: Boolean?,
    val offTopic: Boolean?,
    val lowYield: Boolean?,
    val capApplied: Boolean?,
    val raw: Map<String, Any?>,
)

/** 开放题 `coreType = open` 的得分，总分为 [overall]，即 [EvalResult.overall]。 */
public data class OpenTaskScores(
    val overall: Double?,
    val taskType: String?,
    val transcript: String?,
    val hasSpeech: Boolean?,
    val durationSeconds: Double?,
    val content: OpenContent?,
    val languageUse: OpenLanguageUse?,
    val delivery: OpenDelivery?,
    val feedback: OpenFeedback?,
    val audit: OpenTaskAudit?,
    val rubricVersion: String?,
    val raw: Map<String, Any?>,
)

/**
 * 各维度分数。字段缺失时为 null。
 * [accuracy] 取 `result.accuracy`，缺失时取 [pronunciation]。
 */
public data class Dimensions(
    val pronunciation: Double?,
    val accuracy: Double?,
    val fluency: Double?,
    val integrity: Double?,
    val tone: Double?,
    val rhythm: Double?,
    val emotion: Double?,
    val readingSkill: Double?,
)

/**
 * 评测结果，统一承载原生与声通兼容两种响应。
 *
 * - [warnings]：平台给出的音频质量警告，来自顶层 `warnings` 与 `result.warning`，按码去重。
 * - [localWarnings]：本地预检给出的警告。
 * - [replayed]：响应头 `Idempotency-Replayed: true` 或结果帧 `"replayed": true` 时为 true，表示这是同一幂等键首次结果的重放，没有重复计费。
 * - [overall]：总分，取 `result.overall`，连读模式取 `result.connected_overall`。
 * - [connected]：连读模式的分项，其他模式为 null。
 * - [open]：开放题的分项，其他模式为 null。
 * - [raw]，[resultRaw] 与 [rawJson]：原始响应，`result` 节点与原文，便于读取 SDK 未建模的字段。
 */
public class EvalResult internal constructor(
    public val recordId: String?,
    public val eof: Int,
    public val overall: Double?,
    public val dimensions: Dimensions,
    public val speed: Double?,
    public val rearTone: String?,
    public val duration: String?,
    public val durationSeconds: Double?,
    public val coreType: String?,
    public val language: String?,
    public val words: List<WordScore>,
    public val sentences: List<SentenceScore>,
    public val asrText: AsrText?,
    public val report: Report?,
    public val standardAudio: StandardAudio?,
    public val warnings: List<Warning>,
    public val localWarnings: List<LocalWarning>,
    public val connected: ConnectedScores?,
    public val open: OpenTaskScores?,
    public val idempotencyKey: String?,
    public val replayed: Boolean,
    public val raw: Map<String, Any?>,
    public val resultRaw: Map<String, Any?>,
    public val rawJson: String,
) {
    /** 是否终评结果。 */
    public val isFinal: Boolean get() = eof == 1

    /** 是否带某个警告。 */
    public fun hasWarning(code: WarningCode): Boolean = warnings.any { it.code == code.code }

    override fun toString(): String =
        "EvalResult(recordId=$recordId, overall=$overall, coreType=$coreType, words=${words.size}, " +
            "warnings=${warnings.map { it.code }}, localWarnings=${localWarnings.map { it.code }}, replayed=$replayed)"
}

/** 兼容实时评测的进度中间帧 `{"eof":0,"result":{"bytes":n}}`。 */
public class PartialResult internal constructor(
    /** 服务端已收到的音频字节数。 */
    public val bytes: Long?,
    public val raw: Map<String, Any?>,
) {
    override fun toString(): String = "PartialResult(bytes=$bytes)"
}

/** 语音合成结果。[resolvedUrl] 已按基址拼好，可直接播放。 */
public class TtsResult internal constructor(
    public val audioUrl: String,
    public val resolvedUrl: String,
    public val duration: String?,
    public val durationSeconds: Double?,
    public val format: String?,
    public val idempotencyKey: String?,
    public val replayed: Boolean,
    public val raw: Map<String, Any?>,
    public val rawJson: String,
) {
    override fun toString(): String = "TtsResult(audioUrl=$audioUrl, duration=$duration, format=$format, replayed=$replayed)"
}

/** 报告查询结果，[data] 为响应的 `data` 节点。 */
public class ReportResult internal constructor(
    public val recordId: String,
    public val overall: Double?,
    public val data: Map<String, Any?>,
    public val rawJson: String,
) {
    override fun toString(): String = "ReportResult(recordId=$recordId, overall=$overall)"
}

internal fun invalidArgument(message: String, cause: Throwable? = null): YuguException =
    YuguErrors.local(90010, message, cause)

/** Parses platform result JSON into models. */
internal object ResultParser {

    fun evalResult(
        root: JObj,
        rawJson: String,
        idempotencyKey: String?,
        replayed: Boolean,
        localWarnings: List<LocalWarning>,
    ): EvalResult {
        val result = root.obj("result") ?: JObj(emptyMap())
        val dims = Dimensions(
            pronunciation = result.double("pronunciation"),
            accuracy = result.double("accuracy") ?: result.double("pronunciation"),
            fluency = result.double("fluency"),
            integrity = result.double("integrity"),
            tone = result.double("tone"),
            rhythm = result.double("rhythm"),
            emotion = result.double("emotion"),
            readingSkill = result.double("reading_skill"),
        )
        val coreType = result.str("_coreType")
        val connected = if (coreType == CoreType.CONNECTED || result.has("connected_overall")) connected(result) else null
        val open = if (coreType == CoreType.OPEN || (result.has("content") && result.has("delivery"))) open(result) else null
        return EvalResult(
            recordId = root.str("recordId"),
            eof = root.int("eof") ?: 1,
            overall = result.double("overall") ?: result.double("connected_overall"),
            dimensions = dims,
            speed = result.double("speed"),
            rearTone = result.str("rear_tone"),
            duration = result.str("duration"),
            durationSeconds = result.double("numeric_duration") ?: result.double("duration") ?: result.double("duration_s"),
            coreType = coreType,
            language = result.str("_language") ?: result.str("language"),
            words = result.objList("words").map(::word),
            sentences = result.objList("sentences").map(::sentence),
            asrText = root.obj("asrText")?.takeIf { it.map.isNotEmpty() }?.let(::asrText),
            report = root.obj("report")?.takeIf { it.map.isNotEmpty() }?.let(::report),
            standardAudio = root.obj("standardAudio")?.takeIf { !it.str("url").isNullOrEmpty() }
                ?.let { StandardAudio(it.str("url")!!, it.str("format"), it.str("duration")) },
            warnings = warnings(root, result),
            localWarnings = localWarnings,
            connected = connected,
            open = open,
            idempotencyKey = idempotencyKey,
            replayed = replayed || root.bool("replayed") == true,
            raw = root.map,
            resultRaw = result.map,
            rawJson = rawJson,
        )
    }

    private fun strings(list: List<Any?>?): List<String> = list?.mapNotNull { (it as? String) ?: (it as? Number)?.toString() } ?: emptyList()

    private fun connected(r: JObj): ConnectedScores {
        val report = r.obj("report")
        return ConnectedScores(
            overall = r.double("connected_overall"),
            linking = r.double("linking"),
            rhythm = r.double("rhythm"),
            elision = r.double("elision"),
            reduction = r.double("reduction"),
            boundaryCount = r.int("n_boundaries"),
            boundaries = r.objList("boundaries").map {
                ConnectedBoundary(
                    between = strings(it.list("between")),
                    tags = strings(it.list("tags")),
                    realized = it.double("realized"),
                    startMs = it.double("start_ms"),
                    endMs = it.double("end_ms"),
                    gapMs = it.double("gap_ms"),
                    continuity = it.double("continuity"),
                    raw = it.map,
                )
            },
            summary = report?.str("summary"),
            suggestions = strings(report?.list("suggestions")),
            raw = r.map,
        )
    }

    private fun open(r: JObj): OpenTaskScores = OpenTaskScores(
        overall = r.double("overall"),
        taskType = r.str("taskType"),
        transcript = r.str("transcript"),
        hasSpeech = r.bool("hasSpeech"),
        durationSeconds = r.double("duration_s"),
        content = r.obj("content")?.let { OpenContent(it.double("overall"), it.double("relevance"), it.double("coherence"), it.double("task_achievement"), it.map) },
        languageUse = r.obj("languageUse")?.let { OpenLanguageUse(it.double("overall"), it.double("grammar"), it.double("vocabulary"), it.map) },
        delivery = r.obj("delivery")?.let {
            OpenDelivery(it.double("overall"), it.double("fluency"), it.double("pronunciation"), it.double("speech_rate"), it.str("speech_rate_label"), it.int("n_pauses"), it.map)
        },
        feedback = r.obj("feedback")?.let { OpenFeedback(it.str("strengths"), it.str("weaknesses"), strings(it.list("suggestions")), it.map) },
        audit = r.obj("openTaskAudit")?.let {
            OpenTaskAudit(it.bool("promptEcho"), it.bool("lowContent"), it.bool("offTopic"), it.bool("lowYield"), it.bool("capApplied"), it.map)
        },
        rubricVersion = r.str("rubricVersion"),
        raw = r.map,
    )

    private fun span(o: JObj?): Span? = o?.let { Span(it.int("start") ?: 0, it.int("end") ?: 0) }

    private fun word(o: JObj): WordScore {
        val scores = o.obj("scores") ?: JObj(emptyMap())
        return WordScore(
            word = o.str("word") ?: "",
            pinyin = o.str("pinyin"),
            symbolPinyin = o.str("symbolpinyin"),
            tone = o.str("tone"),
            readStatus = o.str("read_status") ?: o.str("readStatus"),
            overall = scores.double("overall"),
            pronunciation = scores.double("pronunciation"),
            toneScore = scores.double("tone"),
            span = span(o.obj("span")),
            phonemes = o.objList("phonemes").map {
                PhonemeScore(it.str("phoneme") ?: it.str("phone") ?: "", it.double("pronunciation"), span(it.obj("span")), it.map)
            },
            raw = o.map,
        )
    }

    private fun sentence(o: JObj): SentenceScore {
        val scores = o.obj("scores") ?: JObj(emptyMap())
        return SentenceScore(
            index = o.int("index"),
            text = o.str("sentence") ?: o.str("text") ?: "",
            overall = o.double("overall") ?: scores.double("overall"),
            pronunciation = scores.double("pronunciation"),
            fluency = scores.double("fluency"),
            integrity = scores.double("integrity"),
            span = span(o.obj("span")),
            words = o.objList("details").map(::word),
            raw = o.map,
        )
    }

    private fun asrText(o: JObj): AsrText = AsrText(
        text = o.str("text") ?: "",
        alignment = o.objList("alignment").map {
            AsrAlignment(
                char = it.str("char") ?: "",
                index = it.int("index"),
                readStatus = it.str("read_status"),
                startTime = it.int("start_time"),
                endTime = it.int("end_time"),
                asrPinyin = it.str("asr_pinyin"),
                asrTone = it.int("asr_tone"),
                gopScore = it.double("gop_score"),
            )
        },
        raw = o.map,
    )

    private fun report(o: JObj): Report {
        val dimScores = LinkedHashMap<String, Double?>()
        o.obj("dimensionScores")?.let { ds -> for (k in ds.map.keys) dimScores[k] = ds.double(k) }
        return Report(o.str("summary"), strings(o.list("suggestions")), dimScores, o.map)
    }

    private fun warnings(root: JObj, result: JObj): List<Warning> {
        val out = LinkedHashMap<Int, Warning>()
        fun collect(list: List<Any?>?) {
            for (item in list ?: return) {
                val w: Warning? = when (item) {
                    is Number -> warning(item.toInt(), null)
                    is String -> item.trim().toIntOrNull()?.let { warning(it, null) }
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val o = JObj(item as Map<String, Any?>)
                        o.int("code")?.let { warning(it, o.str("message")) }
                    }
                    else -> null
                }
                if (w != null && !out.containsKey(w.code)) out[w.code] = w
            }
        }
        collect(root.list("warnings"))
        collect(result.list("warning"))
        collect(result.list("warnings"))
        return out.values.toList()
    }

    private fun warning(code: Int, message: String?): Warning =
        Warning(code, message?.takeIf { it.isNotBlank() } ?: WarningCode.fromCode(code)?.defaultMessage ?: "warning $code")
}
