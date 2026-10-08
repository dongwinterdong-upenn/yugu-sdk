// Type declarations of @shengzhiai/yugu-miniprogram-sdk 2.0.0.
// They depend on the ES2017 library only (no DOM, no Node types), like the mini program runtime.
// Field names follow CONTRACT.md; nested score objects keep the platform's field names.

import type { ErrorCategory as ErrorCategoryName } from './error-table';

export {
  ERROR_TABLE, WARNING_TABLE, LOCAL_TABLE, HTTP_FALLBACK, RETRYABLE_HTTP, ErrorCodes,
} from './error-table';
export type { ErrorTableEntry } from './error-table';

// ---------------------------------------------------------------- constants

export declare const SDK_VERSION: '2.0.0';
/** Sent as the X-Yugu-SDK header, wx.request cannot set User-Agent. */
export declare const USER_AGENT: 'yugu-miniprogram-sdk/2.0.0';

/** coreType of native evaluation (REST /api/v1/evaluate and WS /api/v1/ws/evaluate). */
export type CoreType = 'word' | 'sentence' | 'passage' | 'connected' | 'open' | 'alpha' | 'pinyin';
/** Shengtong style coreType paths of compat REST POST /{coreType} and compat WS /{coreType}. */
export type CompatCoreType =
  | 'word.eval' | 'word.eval.pro' | 'sent.eval' | 'sent.eval.pro' | 'para.eval' | 'alpha.eval'
  | 'word.eval.cn' | 'sent.eval.cn' | 'para.eval.cn' | 'pinyin';
/** en-US is the platform default. Other values are passed through. */
export type Language = 'zh-CN' | 'en-US' | 'en-GB' | (string & {});

export declare const CORE_TYPES: readonly CoreType[];
export declare const COMPAT_CORE_TYPES: readonly CompatCoreType[];

// ---------------------------------------------------------------- enumerations

export declare const LogLevel: {
  readonly OFF: 'OFF';
  readonly ERROR: 'ERROR';
  readonly WARN: 'WARN';
  readonly INFO: 'INFO';
  readonly DEBUG: 'DEBUG';
};
export type LogLevel = (typeof LogLevel)[keyof typeof LogLevel];

export declare const SessionState: {
  readonly IDLE: 'IDLE';
  readonly CONNECTING: 'CONNECTING';
  readonly CONNECTED: 'CONNECTED';
  readonly STARTED: 'STARTED';
  readonly ENDING: 'ENDING';
  readonly RECONNECTING: 'RECONNECTING';
  readonly COMPLETED: 'COMPLETED';
  readonly FAILED: 'FAILED';
  readonly CANCELLED: 'CANCELLED';
  readonly CLOSED: 'CLOSED';
};
export type SessionState = (typeof SessionState)[keyof typeof SessionState];

export declare const RecorderState: {
  readonly IDLE: 'IDLE';
  readonly RECORDING: 'RECORDING';
  readonly PAUSED: 'PAUSED';
  readonly STOPPED: 'STOPPED';
  readonly RELEASED: 'RELEASED';
};
export type RecorderState = (typeof RecorderState)[keyof typeof RecorderState];

/**
 * What happens to audio around a reconnect.
 * REPLAY (default): every byte of the round is kept (10 MB at most) and sent again to the new
 * server session together with the start frame and, when end() was called, the end frame.
 * DROP: nothing is kept; the new server session scores only audio sent after the reconnect.
 * FAIL: no reconnect; a transport failure ends the session with onError.
 */
export declare const AudioBufferPolicy: {
  readonly REPLAY: 'REPLAY';
  readonly DROP: 'DROP';
  readonly FAIL: 'FAIL';
};
export type AudioBufferPolicy = (typeof AudioBufferPolicy)[keyof typeof AudioBufferPolicy];

/** OFF: no check. WARN (default): findings become localWarnings. REJECT: 90101, 90102, 90103, 90105 throw. */
export declare const AudioPrecheckMode: {
  readonly OFF: 'OFF';
  readonly WARN: 'WARN';
  readonly REJECT: 'REJECT';
};
export type AudioPrecheckMode = (typeof AudioPrecheckMode)[keyof typeof AudioPrecheckMode];

export declare const ErrorCategory: {
  readonly NETWORK: 'NETWORK';
  readonly TIMEOUT: 'TIMEOUT';
  readonly AUTH: 'AUTH';
  readonly PERMISSION: 'PERMISSION';
  readonly INVALID_PARAM: 'INVALID_PARAM';
  readonly NOT_FOUND: 'NOT_FOUND';
  readonly CONFLICT: 'CONFLICT';
  readonly RATE_LIMIT: 'RATE_LIMIT';
  readonly QUOTA: 'QUOTA';
  readonly SERVER: 'SERVER';
  readonly UPSTREAM: 'UPSTREAM';
  readonly AUDIO: 'AUDIO';
  readonly STATE: 'STATE';
  readonly CANCELLED: 'CANCELLED';
  readonly PROTOCOL: 'PROTOCOL';
  readonly UNKNOWN: 'UNKNOWN';
};
export type ErrorCategory = ErrorCategoryName;

/** Audio quality warning codes of result.warning and the top level warnings array. */
export declare const WarningCode: {
  readonly NO_VALID_AUDIO: 1001;
  readonly VOLUME_TOO_LOW: 1002;
  readonly VOLUME_TOO_HIGH: 1003;
  readonly AUDIO_NOISY: 1004;
  readonly AUDIO_INCOMPLETE: 1005;
  readonly SCORER_DEGRADED: 1009;
};
export type WarningCode = (typeof WarningCode)[keyof typeof WarningCode];

// ---------------------------------------------------------------- errors

export type CodeNamespace = 'errors' | 'warnings' | 'local' | 'unknown';

export interface YuguErrorInit {
  category?: ErrorCategory;
  code?: number;
  httpStatus?: number;
  idempotencyKey?: string | null;
  recordId?: string | null;
  attempts?: number;
  rawBody?: string | null;
  op?: string | null;
  retryAfterMs?: number;
  warnings?: Warning[];
  cause?: unknown;
}

export interface YuguErrorJson {
  name: string;
  message: string;
  category: ErrorCategory;
  code: number;
  httpStatus: number;
  retryable: boolean;
  idempotencyKey: string | null;
  recordId: string | null;
  attempts: number;
  op: string | null;
}

/** Base class of every SDK error. UNKNOWN errors are plain YuguError instances. */
export declare class YuguError extends Error {
  constructor(message: string, init?: YuguErrorInit);
  /** Error category; branch on it or on the subclass. */
  readonly category: ErrorCategory;
  /** Platform code, warning code or local 9xxxx code; 0 when the response had none. */
  readonly code: number;
  /**
   * Table the code belongs to: errors (platform error body), warnings (result warning), local
   * (9xxxx) or unknown. 1004 and 1005 exist in both errors and warnings.
   */
  readonly codeNamespace: CodeNamespace;
  /** HTTP status, 0 when there was no HTTP response. */
  readonly httpStatus: number;
  /** Same rule as isRetryable(error). */
  readonly retryable: boolean;
  /** Idempotency key of the call or session, when it had one. */
  readonly idempotencyKey: string | null;
  readonly recordId: string | null;
  /** Number of attempts made before the error was reported. */
  readonly attempts: number;
  /** Response body, cut to 4 KB. */
  readonly rawBody: string | null;
  /** evaluate, evaluateCompat, tts, getReport, streamEvaluate or streamEvaluateCompat. */
  readonly op: string | null;
  /** Retry-After of the response in milliseconds, when present. */
  readonly retryAfterMs?: number;
  /** All precheck findings, on AudioQualityException thrown by the precheck. */
  readonly warnings?: Warning[];
  readonly cause?: unknown;
  toJSON(): YuguErrorJson;
}
export declare class NetworkException extends YuguError {}
export declare class RequestTimeoutException extends YuguError {}
export declare class AuthException extends YuguError {}
export declare class PermissionException extends YuguError {}
export declare class InvalidParameterException extends YuguError {}
export declare class NotFoundException extends YuguError {}
export declare class ConflictException extends YuguError {}
export declare class RateLimitException extends YuguError {}
export declare class QuotaExceededException extends YuguError {}
/** Categories SERVER and UPSTREAM. */
export declare class ServerException extends YuguError {}
export declare class AudioQualityException extends YuguError {}
export declare class IllegalSessionStateException extends YuguError {}
export declare class RequestCancelledException extends YuguError {}
export declare class ProtocolViolationException extends YuguError {}

/**
 * The retry decision used by the SDK itself: local 90001, 90002, 90007 are retryable; a known
 * platform or warning code uses the retryable flag of the error table; otherwise HTTP 408, 425,
 * 429, 500, 502, 503 and 504 are retryable.
 */
export declare function isRetryable(error: unknown): boolean;
/** Typed error for a code of the error table. Platform meaning wins for 1004 and 1005. */
export declare function fromCode(code: number, init?: YuguErrorInit & { message?: string }): YuguError;
/** AudioQualityException for a warning code (1001 to 1005, 1009). */
export declare function fromWarningCode(code: number, init?: YuguErrorInit & { message?: string }): YuguError;
/**
 * Maps a non 2xx response: body code through the platform error table, then compat [2001] detail,
 * then the HTTP status: httpFallback, other 4xx INVALID_PARAM, other 5xx SERVER, else UNKNOWN.
 */
export declare function fromHttpResponse(
  status: number,
  body: string,
  init?: YuguErrorInit & { headers?: Record<string, string | string[]> },
): YuguError;
export declare const YuguErrors: {
  readonly fromCode: typeof fromCode;
  readonly fromWarningCode: typeof fromWarningCode;
  readonly fromHttpResponse: typeof fromHttpResponse;
  readonly isRetryable: typeof isRetryable;
};

// ---------------------------------------------------------------- policies

export interface RetryPolicy {
  /** Retries after the first attempt. Default 2 (3 attempts). */
  maxRetries: number;
  /** Default 200. */
  initialDelayMs: number;
  /** Default 2. */
  multiplier: number;
  /** Default 4000. */
  maxDelayMs: number;
  /** Relative jitter, default 0.3 (plus or minus 30 percent). */
  jitter: number;
  /** Default true. */
  respectRetryAfter: boolean;
  /** Upper bound for Retry-After, default 30000. */
  maxRetryAfterMs: number;
}

export interface ReconnectPolicy {
  /** Default true. */
  enabled: boolean;
  /**
   * Consecutive reconnect attempts per outage, default 8 (waits 0.5, 1, 2, 4, 4, 4, 4, 4 s). Restarts
   * after a successful reconnect; a session makes at most 3 x maxAttempts attempts in total.
   */
  maxAttempts: number;
  /** Default 500. */
  initialDelayMs: number;
  /** Default 2. */
  multiplier: number;
  /** Default 4000. */
  maxDelayMs: number;
  /** Default 0.3. */
  jitter: number;
}

export type BackoffPolicy = Pick<RetryPolicy, 'initialDelayMs' | 'multiplier' | 'maxDelayMs' | 'jitter'>;

export declare const DEFAULT_RETRY_POLICY: Readonly<RetryPolicy>;
export declare const DEFAULT_RECONNECT_POLICY: Readonly<ReconnectPolicy>;
/** min(maxDelayMs, initialDelayMs * multiplier^(attempt-1)) * (1 + U(-jitter, +jitter)), rounded. */
export declare function computeBackoffDelay(attempt: number, policy?: BackoffPolicy, random?: () => number): number;
/** computeBackoffDelay raised to min(retryAfterMs, maxRetryAfterMs) when respectRetryAfter. */
export declare function computeRetryDelay(attempt: number, policy?: RetryPolicy, random?: () => number, retryAfterMs?: number): number;
/** Retry-After header (seconds or HTTP date) in milliseconds. */
export declare function parseRetryAfter(value: string | null | undefined, now?: number): number | undefined;

// ---------------------------------------------------------------- helpers

/** 32 lowercase hex characters (UUID v4 without dashes). */
export declare function generateIdempotencyKey(): string;
export type SignValue = string | number | boolean | null | undefined;
/** Keys sorted, null and empty dropped, k=v joined with &, no URL encoding. */
export declare function buildSignPayload(params: Record<string, SignValue>): string;
/** Base64(HMAC_SHA256(payload, secretKey)). */
export declare function signParams(params: Record<string, SignValue>, secretKey: string): string;

export interface AbortSignalLike {
  readonly aborted: boolean;
  addEventListener?(type: 'abort', listener: (event: { type: 'abort' }) => void): void;
  removeEventListener?(type: 'abort', listener: (event: { type: 'abort' }) => void): void;
}
export interface SdkAbortSignal extends AbortSignalLike {
  readonly reason: unknown;
  onabort: ((event: { type: 'abort' }) => void) | null;
  addEventListener(type: 'abort', listener: (event: { type: 'abort' }) => void): void;
  removeEventListener(type: 'abort', listener: (event: { type: 'abort' }) => void): void;
}
export interface SdkAbortController {
  readonly signal: SdkAbortSignal;
  abort(reason?: unknown): void;
}
/** AbortController replacement for the mini program runtime. */
export declare function createAbortController(): SdkAbortController;

export interface PrecheckOptions {
  /** "pcm" for raw 16 bit little-endian PCM; WAV is detected. */
  format?: 'wav' | 'pcm';
  /** Check against the 10 MB limit of one streaming round instead of the 50 MB upload limit. */
  stream?: boolean;
  sampleRate?: number;
  numberOfChannels?: number;
}
export interface PrecheckReport {
  format: string;
  sizeBytes: number;
  durationMs: number | null;
  sampleRate: number | null;
  channels: number | null;
  bitsPerSample: number | null;
  peak: number | null;
  rms: number | null;
  rmsDbfs: number | null;
  /** 90101 to 90105 findings. */
  warnings: Warning[];
}
/** Runs the local audio precheck on WAV bytes or raw PCM. */
export declare function precheckAudio(audio: ArrayBuffer | ArrayBufferView, options?: PrecheckOptions): PrecheckReport;

/** Normalizes a raw platform response (REST body or WS result frame). */
export declare function parseEvalResult(response: unknown): EvalResult;

// ---------------------------------------------------------------- wx injection

export interface WxRequestOptions {
  url: string;
  method?: string;
  header?: Record<string, string>;
  data?: string | ArrayBuffer;
  timeout?: number;
  dataType?: string;
  responseType?: 'text' | 'arraybuffer';
  success?(res: { statusCode: number; header: Record<string, string>; data: unknown }): void;
  fail?(err: { errMsg: string; errno?: number }): void;
}
export interface WxSocketTask {
  send(options: { data: string | ArrayBuffer; success?(): void; fail?(err: { errMsg: string }): void }): void;
  close(options: { code?: number; reason?: string }): void;
  onOpen(listener: (res: unknown) => void): void;
  onClose(listener: (res: { code: number; reason: string }) => void): void;
  onError(listener: (res: { errMsg: string }) => void): void;
  onMessage(listener: (res: { data: string | ArrayBuffer }) => void): void;
}
export interface WxRecorderManager {
  start(options: Record<string, unknown>): void;
  pause(): void;
  resume(): void;
  stop(): void;
  onStart(listener: () => void): void;
  onPause(listener: () => void): void;
  onResume(listener: () => void): void;
  onStop(listener: (res: { tempFilePath: string; duration: number; fileSize: number }) => void): void;
  onError(listener: (res: { errMsg: string }) => void): void;
  onFrameRecorded(listener: (res: { frameBuffer: ArrayBuffer; isLastFrame: boolean }) => void): void;
  onInterruptionBegin?(listener: () => void): void;
  onInterruptionEnd?(listener: () => void): void;
}
/** The subset of the global `wx` object the SDK uses. Only needed for tests and simulators. */
export interface WxLike {
  request(options: WxRequestOptions): { abort(): void } | void;
  connectSocket(options: { url: string; header?: Record<string, string>; timeout?: number; fail?(err: { errMsg: string }): void }): WxSocketTask;
  getRecorderManager(): WxRecorderManager;
  getFileSystemManager(): {
    readFile(options: { filePath: string; success?(res: { data: ArrayBuffer | string }): void; fail?(err: { errMsg: string }): void }): void;
  };
}

// ---------------------------------------------------------------- client options

export interface TokenAuth {
  /** JWT, sent as Authorization: Bearer on REST and ?token= on WebSocket. */
  token: string;
}
export interface KeyAuth {
  appKey: string;
  /** Never ship a production secretKey inside a mini program package; prefer a short lived token. */
  secretKey: string;
}
export type Auth = TokenAuth | KeyAuth;

export type LogSink = (level: Exclude<LogLevel, 'OFF'>, tag: string, message: string, error?: unknown) => void;

export type RestOp = 'evaluate' | 'evaluateCompat' | 'tts' | 'getReport';

/** Metrics hooks; every method is optional and exceptions thrown by them are logged and ignored. */
export interface EventListener {
  onRequestStart?(op: RestOp, method: 'GET' | 'POST', path: string, attempt: number): void;
  onRequestEnd?(op: RestOp, httpStatus: number, latencyMs: number, attempts: number, error?: YuguError): void;
  onRetry?(op: RestOp, attempt: number, delayMs: number, error: YuguError): void;
  onSessionStateChanged?(sessionId: string, oldState: SessionState, newState: SessionState): void;
  onReconnect?(sessionId: string, attempt: number, succeeded: boolean): void;
}

export interface ClientSettings {
  /** Default https://open.shengzhiai.com */
  baseUrl?: string;
  /** Default wss://open.shengzhiai.com */
  wsBaseUrl?: string;
  /** WebSocket handshake until the server session started. Default 10000. */
  connectTimeoutMs?: number;
  /** One REST attempt. Default 120000. */
  readTimeoutMs?: number;
  /** One logical REST call including retries and waits. Default 300000. */
  totalTimeoutMs?: number;
  /** Partial RetryPolicy, or false to disable retries. */
  retry?: Partial<RetryPolicy> | false;
  /** Generate an idempotency key per write call when none is given. Default true. */
  autoIdempotencyKey?: boolean;
  /** Default WARN. */
  logLevel?: LogLevel;
  /** Log sink; the console when absent. */
  logger?: LogSink;
  eventListener?: EventListener;
  /** Default WARN. */
  audioPrecheck?: AudioPrecheckMode;
  /** Throw AudioQualityException when evaluate returns warning 1001. Default false. */
  strictAudio?: boolean;
  /** Value of the X-Yugu-SDK header. Default yugu-miniprogram-sdk/2.0.0. */
  userAgent?: string;
  /** Default ReconnectPolicy of sessions; false disables reconnects. */
  reconnect?: Partial<ReconnectPolicy> | false;
  /** Default REPLAY. */
  audioBufferPolicy?: AudioBufferPolicy;
  /** Application heartbeat interval; 0 disables it. Default 15000. */
  heartbeatIntervalMs?: number;
  /** No frame for this long while the heartbeat is active is a transport failure. Default 30000. */
  heartbeatTimeoutMs?: number;
  /** Wait for the final result after end(). Default 300000. */
  resultTimeoutMs?: number;
  /** REPLAY buffer limit. Default 10485760 (10 MB). */
  replayBufferLimitBytes?: number;
  /** wx API to use instead of the global wx (tests and simulators). */
  wx?: WxLike;
  /** Random source of the backoff jitter, [0, 1). Default Math.random. */
  random?: () => number;
}

/** Client options: settings plus auth, either as {auth} or, as in 1.x, at the top level. */
export type YuguClientOptions = ClientSettings & ({ auth: Auth } | TokenAuth | KeyAuth);

export interface RequestOptions {
  /** Caller key, 1 to 200 visible ASCII characters. Retries reuse it. */
  idempotencyKey?: string;
  /** Overrides readTimeoutMs for this call. */
  timeoutMs?: number;
  /** Overrides totalTimeoutMs for this call. */
  totalTimeoutMs?: number;
  retry?: Partial<RetryPolicy> | false;
  /** Any AbortSignal shaped object, for example createAbortController().signal. */
  signal?: AbortSignalLike | null;
  audioPrecheck?: AudioPrecheckMode;
  strictAudio?: boolean;
}

// ---------------------------------------------------------------- audio input

export type AudioBytes = ArrayBuffer | ArrayBufferView;
export interface AudioFile {
  /** Local path: a tempFilePath, wxfile:// or a user data path. */
  tempFilePath?: string;
  filePath?: string;
  path?: string;
  /** Bytes instead of a path. */
  data?: AudioBytes;
  /** wav, mp3, pcm and so on; raw PCM is wrapped in a WAV header. */
  format?: string;
  sampleRate?: number;
  numberOfChannels?: number;
}
/** Bytes, a local file path, a descriptor, or the result of YuguRecorder.stop(). */
export type AudioInput = AudioBytes | string | AudioFile | RecordingResult;

// ---------------------------------------------------------------- calls

export interface EvaluateConfig {
  coreType: CoreType;
  /** Reference text, or the prompt of an open question. */
  referenceText: string;
  language?: Language;
  includeReport?: boolean;
  includeStandardAudio?: boolean;
  includeAsrText?: boolean;
  /** [-1, 1], default 0. */
  slack?: number;
  /** (0, 100], default 100. */
  scale?: number;
  /** (0, 1], default 1. */
  precision?: number;
  /** 1 preschool, 2 primary, 3 older than 12. */
  agegroup?: number;
  toneWeight?: number;
  /** For pinyin questions and polyphonic characters, for example "chong2 qing4". */
  refPinyin?: string;
  phonemeOutput?: boolean;
  taskType?: 'picture' | 'situational' | 'free';
  /** 1 adds per word details to passage sentences. */
  paragraphNeedWordScore?: 0 | 1;
  /** Further config fields passed through unchanged. */
  extra?: Record<string, string | number | boolean>;
}

export interface AudioFormatHints {
  /** Format hint; "pcm" wraps raw PCM in a WAV header. */
  audioFormat?: string;
  sampleRate?: number;
  numberOfChannels?: number;
}

export type AudioSource = { audio: AudioInput; audioPath?: undefined } | { audioPath: string; audio?: undefined };

export type EvaluateParams = EvaluateConfig & AudioFormatHints & AudioSource & {
  /** Picture of an open question with taskType picture. */
  image?: AudioBytes | string | AudioFile;
};

export type EvaluateCompatParams = AudioFormatHints & AudioSource & {
  refText?: string;
  /** Alias of refText. */
  referenceText?: string;
  language?: Language;
  refPinyin?: string;
  /** Further form fields such as paragraph_need_word_score, sent as text fields. */
  fields?: Record<string, string | number | boolean>;
};

export interface TtsParams {
  text: string;
  /** Default zh-CN. */
  language?: Language;
  /** Default xiaoyan for Chinese, female otherwise. */
  voice?: 'xiaoyan' | 'xiaofeng' | 'female' | 'male' | (string & {});
  /** Default mp3. */
  format?: 'mp3' | 'wav' | 'ogg';
  /** 0 to 100, default 50. */
  speed?: number;
  pitch?: number;
  volume?: number;
  style?: string;
  extra?: Record<string, string | number | boolean>;
}

// ---------------------------------------------------------------- results

export interface Span {
  start: number;
  end: number;
}

export interface PhonemeScore {
  phoneme: string;
  phone?: string;
  pronunciation: number;
  span?: Span;
  [field: string]: unknown;
}

export interface WordScores {
  overall: number;
  pronunciation?: number;
  tone?: number;
  overall_pron?: number;
  prominence?: number;
  [field: string]: unknown;
}

export interface WordScore {
  word: string;
  pinyin?: string;
  symbolpinyin?: string;
  tone?: string;
  scores: WordScores;
  span?: Span;
  phonemes?: PhonemeScore[];
  /** correct, mispronounced, skipped or inserted. */
  read_status?: string;
  [field: string]: unknown;
}

export interface SentenceScore {
  sentence: string;
  text?: string;
  index?: number;
  overall?: number;
  scores?: { overall?: number; pronunciation?: number; fluency?: number; integrity?: number; [field: string]: unknown };
  span?: Span;
  /** Per word scores; passage mode fills them when paragraphNeedWordScore is 1. */
  details?: WordScore[];
  [field: string]: unknown;
}

/** Read aloud dimensions. null means not scored, never zero. */
export interface Dimensions {
  integrity: number | null;
  pronunciation: number | null;
  /** result.accuracy, or pronunciation when the platform sends no accuracy. */
  accuracy: number | null;
  fluency: number | null;
  tone: number | null;
  rhythm: number | null;
  emotion: number | null;
}

export interface ConnectedBoundary {
  between: string[];
  tags: string[];
  realized?: number;
  start_ms?: number;
  end_ms?: number;
  gap_ms?: number;
  continuity?: number;
  posterior_linking?: number;
  covered?: boolean;
  [field: string]: unknown;
}

/** Connected speech mode. overall is result.connected_overall. */
export interface ConnectedScores {
  overall: number | null;
  linking: number | null;
  rhythm: number | null;
  elision: number | null;
  reduction: number | null;
  /** result.n_boundaries */
  nBoundaries: number | null;
  boundaries: ConnectedBoundary[];
  coverage: Record<string, unknown> | null;
  /** result.raw: linking_rate, reduction_ratio, nPVI_V and the other raw measures. */
  metrics: Record<string, unknown> | null;
}

export interface OpenContent {
  overall: number;
  relevance?: number;
  coherence?: number;
  task_achievement?: number;
  [field: string]: unknown;
}
export interface OpenLanguageUse {
  overall: number;
  grammar?: number;
  vocabulary?: number;
  [field: string]: unknown;
}
export interface OpenDelivery {
  overall: number;
  fluency?: number;
  pronunciation?: number;
  speech_rate?: number;
  speech_rate_label?: string;
  n_pauses?: number;
  longest_pause_s?: number;
  voiced_s?: number;
  speech_span_s?: number;
  [field: string]: unknown;
}
export interface OpenFeedback {
  strengths?: string;
  weaknesses?: string;
  suggestions?: string[];
  [field: string]: unknown;
}
export interface OpenTaskAudit {
  /** Prompt read back: the total is capped at 60. */
  promptEcho?: boolean;
  /** Empty talk: the total is capped at 70. */
  lowContent?: boolean;
  offTopic?: boolean;
  lowYield?: boolean;
  pictureUngrounded?: boolean;
  capApplied?: boolean;
  answerUnits?: number;
  note?: string;
  [field: string]: unknown;
}

/** Open question mode. overall of the result is result.overall. */
export interface OpenScores {
  taskType: string | null;
  transcript: string | null;
  hasSpeech: boolean | null;
  /** result.duration_s */
  durationSec: number | null;
  content: OpenContent | null;
  languageUse: OpenLanguageUse | null;
  delivery: OpenDelivery | null;
  feedback: OpenFeedback | null;
  openTaskAudit: OpenTaskAudit | null;
  audioQuality: { mos?: number; quality?: number; [field: string]: unknown } | null;
  aggregation: Record<string, unknown> | null;
  rubricVersion: string | null;
}

export interface AsrAlignment {
  char: string;
  index: number;
  read_status: string;
  start_time?: number;
  end_time?: number;
  asr_pinyin?: string;
  asr_tone?: number;
  gop_score?: number;
  [field: string]: unknown;
}

export interface AsrText {
  text?: string;
  alignment?: AsrAlignment[];
  [field: string]: unknown;
}

export interface DimensionScores {
  accuracy: number | null;
  fluency: number | null;
  integrity: number | null;
  affect: number | null;
  speechRate: number | null;
}

export interface Report {
  summary?: string;
  dimensions?: Record<string, unknown>;
  suggestions?: string[];
  /** Always five keys; null means not scored. */
  dimensionScores?: DimensionScores | Record<string, never>;
  dimensionEvidence?: Record<string, Array<{ word: string; type: string; detail?: unknown }>>;
  /** Missing, or {triggered, action, divergence?, note?}. */
  asrArbitration?: { triggered: boolean; action: string; divergence?: number; note?: string };
  rubricVersion?: string;
  rubricBackfilled?: boolean;
  [field: string]: unknown;
}

export interface StandardAudio {
  url?: string;
  format?: string;
  duration?: string;
  [field: string]: unknown;
}

export interface Warning {
  code: number;
  message: string;
}

export interface EvalResult {
  recordId: string | null;
  eof: number | null;
  /** Mode reported by the engine (result._coreType), for example sentence or connected. */
  coreType: string | null;
  /** Language reported by the engine (result._language): zh or en. */
  language: string | null;
  /** result.overall; for connected mode result.connected_overall. */
  overall: number | null;
  dims: Dimensions;
  speed: number | null;
  rearTone: string | null;
  /** result.duration as sent, usually seconds as a string. */
  duration: string | number | null;
  /** Duration in seconds as a number. */
  durationSec: number | null;
  words: WordScore[];
  sentences: SentenceScore[];
  /** Connected speech scores, null for other modes. */
  connected: ConnectedScores | null;
  /** Open question scores, null for other modes. */
  open: OpenScores | null;
  asrText: AsrText | null;
  report: Report | null;
  standardAudio: StandardAudio | null;
  yuguScores: Record<string, unknown> | null;
  /** Platform audio quality warnings (WarningCode). */
  warnings: Warning[];
  /** Local precheck findings (90101 to 90105). */
  localWarnings: Warning[];
  idempotencyKey: string | null;
  /** True when the platform replayed an earlier result for the same idempotency key. */
  replayed: boolean;
  /** REST: attempts used. Sessions: connections used. */
  attempts: number;
  /** The whole platform response. */
  raw: Record<string, unknown>;
}

export interface TtsResult {
  audioUrl: string;
  /** Absolute URL, ready for an InnerAudioContext. */
  fullUrl: string;
  duration: number | null;
  format: string | null;
  warnings: Warning[];
  idempotencyKey: string | null;
  replayed: boolean;
  attempts: number;
  raw: Record<string, unknown>;
}

export interface ReportData {
  recordId?: string;
  overall?: number;
  report?: Report;
  [field: string]: unknown;
}

// ---------------------------------------------------------------- sessions

export interface PartialResult {
  /** Audio bytes the server received so far (compat realtime_feedback). */
  bytes: number;
  raw: Record<string, unknown>;
}

export interface ReconnectInfo {
  /** DROP: bytes the new server session will not score (sent on the broken connection or while reconnecting). */
  droppedBytes: number;
  totalDroppedBytes: number;
  /** REPLAY: bytes sent again to the new server session. */
  replayedBytes: number;
}

/**
 * Exactly one of onResult or onError per session unless cancel() came first, then neither.
 * onClosed is always the last callback. Callbacks of one session never nest.
 */
export interface StreamListener {
  onResult(result: EvalResult): void;
  onError(error: YuguError): void;
  onStateChanged?(oldState: SessionState, newState: SessionState): void;
  /** First connection only; later ones are reported by onReconnected. */
  onConnected?(): void;
  /** First server session start only. */
  onStarted?(): void;
  onPartial?(partial: PartialResult): void;
  onReconnecting?(attempt: number, delayMs: number, cause: YuguError): void;
  onReconnected?(attempt: number, info: ReconnectInfo): void;
  /** Precheck findings of the streamed audio at end(). */
  onWarning?(warning: Warning): void;
  /**
   * 1000 completed or cancelled; on failure the last close code, 1006 for transport errors. Close codes
   * 1002, 1003, 1007, 1008, 1009, 1010 and 4000 to 4999, and 1000 before the result, end the session
   * with ProtocolViolationException 90005 and no reconnect.
   */
  onClosed?(code: number, reason: string): void;
}

export interface StreamOptions {
  idempotencyKey?: string;
  reconnect?: Partial<ReconnectPolicy> | false;
  audioBufferPolicy?: AudioBufferPolicy;
  heartbeatIntervalMs?: number;
  heartbeatTimeoutMs?: number;
  resultTimeoutMs?: number;
  connectTimeoutMs?: number;
  replayBufferLimitBytes?: number;
  audioPrecheck?: AudioPrecheckMode;
  /** Extra handshake query parameters; they are signed too. */
  query?: Record<string, string | number | boolean>;
}

export type StreamEvaluateParams = EvaluateConfig & {
  /** Sample rate of the PCM given to sendAudio, for the precheck. Default 16000. */
  sampleRate?: number;
};

export interface StreamCompatParams {
  refText?: string;
  referenceText?: string;
  text?: string;
  language?: Language;
  refPinyin?: string;
  /** Sends realtime_feedback; the server then reports progress through onPartial. */
  realtimeFeedback?: boolean;
  /** Further parameter frame fields. */
  fields?: Record<string, string | number | boolean>;
  sampleRate?: number;
}

export interface SessionStats {
  bytesAccepted: number;
  /** Successful reconnects. */
  reconnects: number;
  /** Reconnect attempts of the session, successful or not; at most 3 x maxAttempts. */
  reconnectAttempts: number;
  droppedBytes: number;
  replayBufferBytes: number;
  heartbeat: 'probing' | 'active' | 'disabled';
}

export declare class StreamSession {
  private constructor();
  readonly id: string;
  readonly idempotencyKey: string | null;
  readonly mode: 'native' | 'compat';
  getState(): SessionState;
  isActive(): boolean;
  getStats(): SessionStats;
  setListener(listener: Partial<StreamListener> | null): void;
  removeListener(): void;
  /** 16 bit little-endian PCM; chunks above 32000 bytes go out as several frames. Returns false (ignored) after end() or when the session is over. */
  sendAudio(chunk: ArrayBuffer | ArrayBufferView): boolean;
  /** Returns false when already ended or over. */
  end(): boolean;
  /** No onResult or onError afterwards; onClosed follows. Idempotent. */
  cancel(): void;
  /** Same as cancel() while active. Idempotent. */
  close(): void;
}

// ---------------------------------------------------------------- recorder

export interface RecorderOptions {
  /** Default 16000. */
  sampleRate?: 8000 | 11025 | 12000 | 16000 | 22050 | 24000 | 32000 | 44100 | 48000;
  /** Default 1. */
  numberOfChannels?: 1 | 2;
  /** Default PCM. Frame callbacks need PCM or mp3. */
  format?: 'PCM' | 'pcm' | 'mp3' | 'wav' | 'aac';
  /** KB per onFrame callback, default 1. */
  frameSize?: number;
  /** Maximum recording length in ms, default 300000. */
  duration?: number;
  audioSource?: string;
  /** Give up waiting for the platform stop event after this long. Default 5000. */
  stopTimeoutMs?: number;
}

export interface RecordingResult {
  tempFilePath: string | null;
  duration: number;
  fileSize: number;
  format: string;
  sampleRate: number;
  numberOfChannels: number;
}

export interface RecorderListener {
  onStateChanged?(oldState: RecorderState, newState: RecorderState): void;
  onStart?(): void;
  onPause?(): void;
  onResume?(): void;
  onStop?(result: RecordingResult): void;
  onFrame?(frame: ArrayBuffer, isLastFrame: boolean): void;
  onError?(error: YuguError): void;
  onInterruptionBegin?(): void;
  onInterruptionEnd?(): void;
}

export interface PipeOptions {
  /** Call session.end() when the recording stops. Default true. */
  endOnStop?: boolean;
  /** Re-chunk frames to this many bytes. Default 640 (20 ms at 16000 Hz). */
  frameBytes?: number;
}

export declare class YuguRecorder {
  constructor(options?: RecorderOptions, deps?: { wx?: WxLike });
  getState(): RecorderState;
  setListener(listener: RecorderListener | null): void;
  removeListener(): void;
  start(): void;
  pause(): void;
  resume(): void;
  /** Releases the microphone; the result can be passed to evaluate as audio. */
  stop(): Promise<RecordingResult>;
  /** Idempotent; stops a running recording and drops listener and pipe. */
  release(): void;
  pipeTo(session: StreamSession, options?: PipeOptions): StreamSession;
  unpipe(): void;
}

// ---------------------------------------------------------------- client

export declare class YuguClient {
  constructor(options: YuguClientOptions);
  /** Native whole-file evaluation, POST /api/v1/evaluate. */
  evaluate(params: EvaluateParams, options?: RequestOptions): Promise<EvalResult>;
  /** Shengtong compatible evaluation, POST /{coreType}. Needs KeyAuth: the compat interface requires X-App-Key. */
  evaluateCompat(coreType: CompatCoreType, params: EvaluateCompatParams, options?: RequestOptions): Promise<EvalResult>;
  /** Speech synthesis, POST /api/v1/tts/generate. */
  tts(params: TtsParams, options?: RequestOptions): Promise<TtsResult>;
  /** GET /api/v1/report/{recordId}. */
  getReport(recordId: string, options?: RequestOptions): Promise<ReportData>;
  /** Native streaming evaluation. */
  streamEvaluate(params: StreamEvaluateParams, listener: StreamListener, options?: StreamOptions): StreamSession;
  /** Shengtong compatible streaming evaluation. */
  streamEvaluateCompat(coreType: CompatCoreType, params: StreamCompatParams, listener: StreamListener, options?: StreamOptions): StreamSession;
  createRecorder(options?: RecorderOptions): YuguRecorder;
  /** Cancels sessions, aborts running calls with 90004, releases recorders. Idempotent. */
  close(): void;
  isClosed(): boolean;
}
