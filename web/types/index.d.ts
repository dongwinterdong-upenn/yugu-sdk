// Type definitions for @shengzhiai/yugu-web-sdk 2.0.0
// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Field names follow CONTRACT.md. CI checks that every runtime export has a declaration here.

import type { ErrorCategory as TableErrorCategory } from './error-table.js';

export { ERROR_TABLE, WARNING_TABLE, LOCAL_TABLE, HTTP_FALLBACK, RETRYABLE_HTTP, ErrorCodes } from './error-table.js';
export type { ErrorTableEntry } from './error-table.js';

declare global {
  // Lets the declarations compile in projects without the DOM library. With the DOM library or
  // @types/node these merge with the real AbortSignal and Blob and add nothing.
  interface AbortSignal {}
  interface Blob {}
}

// ---------------------------------------------------------------- shared primitives

/** Blob or File (anything with size, type and arrayBuffer()). */
export interface BlobLike {
  readonly size: number;
  readonly type: string;
  readonly name?: string;
  arrayBuffer(): Promise<ArrayBuffer>;
}

/** Caller audio for evaluate and evaluateCompat (C-06). */
export type AudioInput = BlobLike | ArrayBufferLike | ArrayBufferView;

/** Headers object or plain map. */
export interface HeadersLike {
  get(name: string): string | null;
}

/** Request passed to an injected fetch. */
export interface FetchInit {
  method: string;
  headers: Record<string, string>;
  body?: ArrayBuffer | string;
  signal: AbortSignal;
}

/** Response returned by an injected fetch. */
export interface FetchResponseLike {
  readonly status: number;
  readonly headers: HeadersLike;
  text(): Promise<string>;
}

/** fetch implementation; the global fetch of browsers and Node 18+ fits. */
export type FetchLike = (input: string, init: FetchInit) => Promise<FetchResponseLike>;

/** WebSocket constructor; the browser WebSocket and the Node ws package fit. */
export type WebSocketConstructorLike = new (url: string) => object;

/** Web Crypto object; globalThis.crypto by default, node:crypto webcrypto on Node 18. */
export interface CryptoLike {
  readonly subtle?: unknown;
  getRandomValues?: unknown;
  randomUUID?: unknown;
}

// ---------------------------------------------------------------- enums

/** Error categories. */
export type ErrorCategory = TableErrorCategory;
export declare const ErrorCategory: { readonly [K in TableErrorCategory]: K };

/** Log levels. */
export type LogLevel = 'OFF' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';
export declare const LogLevel: {
  readonly OFF: 'OFF';
  readonly ERROR: 'ERROR';
  readonly WARN: 'WARN';
  readonly INFO: 'INFO';
  readonly DEBUG: 'DEBUG';
};

/** Local audio precheck modes (C-04). */
export type AudioPrecheckMode = 'OFF' | 'WARN' | 'REJECT';
export declare const AudioPrecheck: { readonly OFF: 'OFF'; readonly WARN: 'WARN'; readonly REJECT: 'REJECT' };

/** Audio handling across a WebSocket reconnect. */
export type AudioBufferPolicy = 'REPLAY' | 'DROP' | 'FAIL';
export declare const AudioBufferPolicy: { readonly REPLAY: 'REPLAY'; readonly DROP: 'DROP'; readonly FAIL: 'FAIL' };

/** Heartbeat: "auto" pings once the server is known to answer pong, true always, false never. */
export type HeartbeatMode = 'auto' | boolean;
export declare const HeartbeatMode: { readonly AUTO: 'auto'; readonly ON: true; readonly OFF: false };

/** Streaming session states. */
export type SessionState =
  | 'IDLE'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'STARTED'
  | 'ENDING'
  | 'RECONNECTING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'CLOSED';
export declare const SessionState: { readonly [K in SessionState]: K };

/** Recorder states. */
export type RecorderState = 'IDLE' | 'RECORDING' | 'PAUSED' | 'STOPPED' | 'RELEASED';
export declare const RecorderState: { readonly [K in RecorderState]: K };

/** Audio quality warning codes of evaluation results. */
export declare const WarningCode: {
  readonly NO_VALID_AUDIO: 1001;
  readonly VOLUME_TOO_LOW: 1002;
  readonly VOLUME_TOO_HIGH: 1003;
  readonly AUDIO_NOISY: 1004;
  readonly AUDIO_INCOMPLETE: 1005;
  readonly SCORER_DEGRADED: 1009;
};
export type WarningCode = 1001 | 1002 | 1003 | 1004 | 1005 | 1009;

/** REST operation names reported to the EventListener. */
export type Operation = 'evaluate' | 'evaluateCompat' | 'tts' | 'getReport';

// ---------------------------------------------------------------- errors

/** Fields accepted by the error constructors and factories. */
export interface YuguErrorInit {
  category?: ErrorCategory;
  code?: number;
  httpStatus?: number;
  retryable?: boolean;
  idempotencyKey?: string | null;
  recordId?: string | null;
  attempts?: number;
  rawBody?: string | null;
  retryAfterMs?: number | null;
  traceId?: string | null;
  localWarnings?: LocalWarning[];
  message?: string;
  cause?: unknown;
}

/** Base error. Every error thrown or reported by the SDK is a YuguError. */
export declare class YuguError extends Error {
  constructor(message?: string, init?: YuguErrorInit);
  /** Class name, for example "AuthException". */
  name: string;
  /** Error category. */
  category: ErrorCategory;
  /** Platform code, warning code or local code (90001 and up); 0 when none. */
  code: number;
  /** HTTP status; 0 when there was no HTTP response. */
  httpStatus: number;
  /** Same answer as isRetryable(this). */
  retryable: boolean;
  /** Idempotency key of the call or session. */
  idempotencyKey: string | null;
  /** recordId when known. */
  recordId: string | null;
  /** Number of attempts made (REST) or connections opened (WebSocket). */
  attempts: number;
  /** Response body, at most 4 KB. */
  rawBody: string | null;
  /** Retry-After of the response in ms. */
  retryAfterMs: number | null;
  /** X-Trace-Id of the response. */
  traceId: string | null;
  /** Local precheck warnings collected before the error. */
  localWarnings: LocalWarning[];
  cause?: unknown;
  toJSON(): {
    name: string;
    category: ErrorCategory;
    code: number;
    httpStatus: number;
    message: string;
    retryable: boolean;
    idempotencyKey: string | null;
    recordId: string | null;
    attempts: number;
    retryAfterMs: number | null;
    traceId: string | null;
    rawBody: string | null;
  };
}

/** NETWORK: connection failed or reset, TLS failure (90011), reconnects exhausted (90006). */
export declare class NetworkException extends YuguError {}
/** TIMEOUT: connect, read, heartbeat (90002) or result (90007) timeout. */
export declare class RequestTimeoutException extends YuguError {}
/** AUTH: missing credentials, bad signature, expired or invalid token. */
export declare class AuthException extends YuguError {}
/** PERMISSION: not allowed, disabled account, microphone permission denied (90201). */
export declare class PermissionException extends YuguError {}
/** INVALID_PARAM: bad request parameters, local (90010) or server side. */
export declare class InvalidParameterException extends YuguError {}
/** NOT_FOUND. */
export declare class NotFoundException extends YuguError {}
/** CONFLICT: idempotency in progress (40901, retryable) or key reused (40903). */
export declare class ConflictException extends YuguError {}
/** RATE_LIMIT: request rate or concurrency limit, queue timeout. */
export declare class RateLimitException extends YuguError {}
/** QUOTA: balance, plan or daily limit exhausted. */
export declare class QuotaExceededException extends YuguError {}
/** SERVER and UPSTREAM. */
export declare class ServerException extends YuguError {}
/** AUDIO: local precheck (90101 to 90105) or server warning codes thrown on purpose. */
export declare class AudioQualityException extends YuguError {}
/** STATE: client closed (90004), invalid state (90009), recorder unavailable (90202, 90203). */
export declare class IllegalSessionStateException extends YuguError {}
/** CANCELLED: the caller aborted (90003). */
export declare class RequestCancelledException extends YuguError {}
/** PROTOCOL: unparsable response or frame, server broke the protocol (90005). */
export declare class ProtocolViolationException extends YuguError {}

/** Constructor type of the error classes. */
export type YuguErrorConstructor = new (message?: string, init?: YuguErrorInit) => YuguError;

/** One retry decision for retries, reconnects and callers. */
export declare function isRetryable(error: unknown): boolean;

/** Error factories built on the generated tables. */
export declare const YuguErrors: {
  /**
   * Typed error from a code in an error context: 9xxxx from the local table, server codes from the
   * error table (1004 and 1005 are USER_DISABLED and USER_LOCKED), warning-only codes give
   * AudioQualityException, unknown codes use the HTTP status category.
   */
  fromCode(code: number, init?: YuguErrorInit): YuguError;
  /** AudioQualityException from a code of result.warning (1001 to 1009) or a local precheck code. */
  fromWarning(code: number, init?: YuguErrorInit): AudioQualityException;
  /** Typed error from an HTTP error response body (platform, FastAPI detail, engine [code] pattern). */
  fromHttp(httpStatus: number, bodyText: string | null, init?: YuguErrorInit & { headers?: HeadersLike | Record<string, string> }): YuguError;
  /** Typed error from a WebSocket error frame. */
  fromWsFrame(frame: { event?: string; code?: number; message?: string }, init?: YuguErrorInit): YuguError;
  /** {code, message} from an error body. */
  parseErrorBody(bodyText: string | null): { code: number; message: string; json: unknown };
  /** Category of a code (local, error, then warning table) or else of the HTTP status. */
  categoryFor(code: number, httpStatus?: number): ErrorCategory;
  /** httpFallback table, then other 4xx INVALID_PARAM, other 5xx SERVER, else UNKNOWN. */
  httpCategory(httpStatus: number): ErrorCategory;
  classForCategory(category: ErrorCategory): YuguErrorConstructor;
  isRetryable(error: unknown): boolean;
};

// ---------------------------------------------------------------- policies and options

/** Retry of REST calls (A-02). */
export interface RetryPolicy {
  /** Retries after the first attempt. Default 2 (3 attempts). */
  maxRetries: number;
  /** Default 200. */
  initialDelayMs: number;
  /** Default 2. */
  multiplier: number;
  /** Default 4000. */
  maxDelayMs: number;
  /** Default 0.3, the delay varies by plus or minus 30 %. */
  jitter: number;
  /** Wait at least Retry-After. Default true. */
  respectRetryAfter: boolean;
  /** Cap of Retry-After. Default 30000. */
  maxRetryAfterMs: number;
}

/** Reconnect of streaming sessions (A-03). */
export interface ReconnectPolicy {
  /** Default true. */
  enabled: boolean;
  /**
   * Consecutive failed attempts before giving up with 90006. Default 8 (waits about 0.5, 1, 2, 4,
   * 4, 4, 4, 4 s). The count resets after a successful reconnect; one session makes at most three
   * times this many reconnect attempts in total.
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

export declare const DEFAULT_RETRY_POLICY: Readonly<RetryPolicy>;
export declare const DEFAULT_RECONNECT_POLICY: Readonly<ReconnectPolicy>;

/** Backoff without Retry-After: min(maxDelayMs, initialDelayMs * multiplier^(n-1)) * (1 + U(-jitter, +jitter)). */
export declare function computeBackoffDelay(
  policy: Pick<RetryPolicy, 'initialDelayMs' | 'multiplier' | 'maxDelayMs' | 'jitter'>,
  n: number,
  random?: () => number,
): number;

/** Retry delay including Retry-After. */
export declare function computeRetryDelay(policy: RetryPolicy, n: number, retryAfterMs: number | null | undefined, random?: () => number): number;

/** Retry-After value (seconds or HTTP date) in ms, or null. */
export declare function parseRetryAfter(value: string | null | undefined, nowMs?: number): number | null;

/** Log sink: (level, tag, message, error). Messages never contain secretKey, signature, token or audio. */
export type LogSink = (level: Exclude<LogLevel, 'OFF'>, tag: string, message: string, error: unknown) => void;

/** Metrics hooks (C-01). Every method is optional. */
export interface YuguEventListener {
  /** Before every attempt. */
  onRequestStart?(op: Operation, method: string, path: string, attempt: number): void;
  /** Once per logical call: final HTTP status (0 when none), total latency, attempts, final error. */
  onRequestEnd?(op: Operation, httpStatus: number, latencyMs: number, attempts: number, error: YuguError | null): void;
  /** Before every retry; attempt counts retries from 1. */
  onRetry?(op: Operation, attempt: number, delayMs: number, error: YuguError): void;
  /** Every session state change. */
  onSessionStateChanged?(sessionId: string, oldState: SessionState, newState: SessionState): void;
  /** Outcome of every reconnect attempt. */
  onReconnect?(sessionId: string, attempt: number, succeeded: boolean): void;
}

/** Client options. Defaults in CLIENT_DEFAULTS. */
export interface YuguClientOptions {
  /** REST base. Default https://open.shengzhiai.com */
  baseUrl?: string;
  /** WebSocket base. Default wss://open.shengzhiai.com, or derived from baseUrl when only baseUrl is given. */
  wsBaseUrl?: string;
  /** JWT, or a function returning one (called per request and per connection). */
  token?: string | (() => string | Promise<string>);
  /** appKey of the signature auth (with secretKey). */
  appKey?: string;
  /** secretKey of the signature auth. Never logged. */
  secretKey?: string;
  /** Alternative way to give the credentials. */
  auth?: { token: string | (() => string | Promise<string>) } | { appKey: string; secretKey: string };
  /** WebSocket connect plus session start timeout. Default 10000. */
  connectTimeoutMs?: number;
  /** Timeout of one REST attempt. Default 120000. */
  readTimeoutMs?: number;
  /** Deadline of one logical REST call including retries and waits. Default 300000. */
  totalTimeoutMs?: number;
  /** Wait for the final result after end(). Default 300000. */
  resultTimeoutMs?: number;
  retry?: Partial<RetryPolicy> | false;
  reconnect?: Partial<ReconnectPolicy> | false;
  /** Default REPLAY. */
  audioBufferPolicy?: AudioBufferPolicy;
  /** REPLAY buffer limit. Default 10485760. */
  maxReplayBytes?: number;
  /** Default "auto". */
  heartbeat?: HeartbeatMode;
  /** Default 15000. */
  heartbeatIntervalMs?: number;
  /** Missing pong for this long is a transport failure. Default 30000. */
  heartbeatTimeoutMs?: number;
  /** Generate an Idempotency-Key per write call when the caller gives none. Default true. */
  autoIdempotencyKey?: boolean;
  /** Default WARN. */
  logLevel?: LogLevel;
  /** Default: console. */
  logger?: LogSink;
  eventListener?: YuguEventListener | null;
  /** Default WARN. */
  audioPrecheck?: AudioPrecheckMode;
  /** Throw AudioQualityException when the result carries warning 1001. Default false. */
  strictAudio?: boolean;
  /** Sent as X-Yugu-SDK in browsers and as User-Agent elsewhere. Default yugu-web-sdk/2.0.0 */
  userAgent?: string;
  /** Default: global fetch. */
  fetch?: FetchLike;
  /** Default: global WebSocket. Node: the ws package. */
  WebSocket?: WebSocketConstructorLike;
  /** Default: globalThis.crypto. Node 18: webcrypto from node:crypto. */
  crypto?: CryptoLike;
  /** Random source in [0, 1) for jitter. Default Math.random. */
  random?: () => number;
}

/** Defaults of YuguClientOptions. */
export declare const CLIENT_DEFAULTS: {
  readonly baseUrl: string;
  readonly wsBaseUrl: string;
  readonly connectTimeoutMs: number;
  readonly readTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly resultTimeoutMs: number;
  readonly heartbeatIntervalMs: number;
  readonly heartbeatTimeoutMs: number;
  readonly maxReplayBytes: number;
  readonly autoIdempotencyKey: boolean;
  readonly logLevel: LogLevel;
  readonly audioPrecheck: AudioPrecheckMode;
  readonly audioBufferPolicy: AudioBufferPolicy;
  readonly heartbeat: HeartbeatMode;
  readonly userAgent: string;
};

/** Per-call options of REST calls. */
export interface RequestOptions {
  /** Caller key, 1 to 200 visible ASCII characters. Reused by every retry. */
  idempotencyKey?: string;
  /** Overrides readTimeoutMs for this call. */
  timeoutMs?: number;
  /** Overrides totalTimeoutMs for this call. */
  totalTimeoutMs?: number;
  /** Overrides the retry policy; false disables retries. */
  retry?: Partial<RetryPolicy> | false;
  /** Abort rejects the call with RequestCancelledException (90003). */
  signal?: AbortSignal | null;
  /** Extra request headers. */
  headers?: Record<string, string>;
}

/** Options of getReport (no idempotency key: GET is naturally idempotent). */
export type ReadOptions = Omit<RequestOptions, 'idempotencyKey'>;

/** Per-call options of evaluate and evaluateCompat. */
export interface EvaluateOptions extends RequestOptions {
  /** Picture for coreType open with taskType picture (evaluate only). */
  image?: AudioInput;
  /** Filename of the audio part. Default from File.name or the detected container. */
  filename?: string;
  /** Content-Type of the audio part. Default from Blob.type or the detected container. */
  contentType?: string;
  /** "pcm": the audio is raw PCM16 mono and is wrapped into WAV before upload. */
  audioFormat?: 'auto' | 'pcm';
  /** Sample rate of raw PCM. Default 16000. */
  sampleRate?: number;
  audioPrecheck?: AudioPrecheckMode;
  strictAudio?: boolean;
}

/** Per-session options of streaming calls. */
export interface StreamOptions {
  /** Caller key; sent on every (re)connection. */
  idempotencyKey?: string;
  reconnect?: Partial<ReconnectPolicy> | false;
  audioBufferPolicy?: AudioBufferPolicy;
  maxReplayBytes?: number;
  heartbeat?: HeartbeatMode;
  heartbeatIntervalMs?: number;
  heartbeatTimeoutMs?: number;
  connectTimeoutMs?: number;
  resultTimeoutMs?: number;
  /** Precheck of the accumulated audio at end(). REJECT fails the session locally. */
  audioPrecheck?: AudioPrecheckMode;
  /** Sample rate of the PCM sent, for the precheck duration. Default 16000. */
  sampleRate?: number;
  /** Extra handshake query parameters (signed with the others). */
  query?: Record<string, string | number | boolean | null | undefined>;
  /** Abort cancels the session. */
  signal?: AbortSignal | null;
}

// ---------------------------------------------------------------- requests

export type NativeCoreType = 'word' | 'sentence' | 'passage' | 'connected' | 'open' | 'alpha' | 'pinyin';
export type CompatCoreType =
  | 'word.eval'
  | 'word.eval.pro'
  | 'sent.eval'
  | 'sent.eval.pro'
  | 'para.eval'
  | 'alpha.eval'
  | 'word.eval.cn'
  | 'sent.eval.cn'
  | 'para.eval.cn'
  | 'pinyin';

/** config part of POST /api/v1/evaluate (EvaluateConfigDTO, CONTRACT 1). */
export interface EvaluateConfig {
  coreType: NativeCoreType;
  /** Reference text, at most 1000 characters; the prompt for coreType open. */
  referenceText: string;
  /** Default en-US. */
  language?: 'en-US' | 'en-GB' | 'zh-CN';
  /** Word and phoneme report. Default false. */
  includeReport?: boolean;
  /** Standard demo audio URL. Default false. */
  includeStandardAudio?: boolean;
  /** ASR text. Default false. */
  includeAsrText?: boolean;
  /** Strictness in [-1, 1]. Default 0. */
  slack?: number;
  /** Score scale in (0, 100]. Default 100. */
  scale?: number;
  /** Precision in (0, 1]. Default 1. */
  precision?: number;
  /** 1 preschool, 2 primary school, 3 older than 12 (default). */
  agegroup?: number;
  /** Reserved, in [0, 1]. */
  toneWeight?: number;
  /** Pinyin, for example "chong2 qing4". */
  refPinyin?: string;
  phonemeOutput?: boolean;
  /** Task of coreType open. */
  taskType?: 'picture' | 'situational' | 'free';
  /** passage: word scores, 1 or 0. */
  paragraphNeedWordScore?: 0 | 1;
}

/** Start frame of the native stream: the same fields as EvaluateConfig. */
export type StreamConfig = EvaluateConfig;

/** Value of a Shengtong compatible form field. */
export type CompatParamValue = string | number | boolean | null | undefined;

/** Form fields of POST /{coreType}; known fields are listed, others pass through. */
export interface CompatParams {
  refText?: string;
  text?: string;
  language?: string;
  refPinyin?: string;
  agegroup?: CompatParamValue;
  scale?: CompatParamValue;
  precision?: CompatParamValue;
  slack?: CompatParamValue;
  paragraph_need_word_score?: CompatParamValue;
  phoneme_output?: CompatParamValue;
  attachAudioUrl?: CompatParamValue;
  [field: string]: CompatParamValue;
}

/** Parameter frame of the compat stream. */
export interface CompatStreamParams extends CompatParams {
  /** Progress frames {"eof":0,"result":{"bytes":n}} delivered to onPartial. */
  realtime_feedback?: boolean | 0 | 1;
}

/** Body of POST /api/v1/tts/generate. */
export interface TtsRequest {
  text: string;
  /** Default zh-CN. */
  language?: 'zh-CN' | 'en-US' | 'en-GB';
  /** zh: xiaoyan, xiaofeng; en: female, male. */
  voice?: string;
  /** Default mp3. */
  format?: 'mp3' | 'wav' | 'ogg';
  /** 0 to 100, default 50. */
  speed?: number;
  /** 0 to 100, default 50. */
  pitch?: number;
  /** 0 to 100, default 50. */
  volume?: number;
  style?: string | null;
}

// ---------------------------------------------------------------- results

export interface Span {
  start: number;
  end: number;
}

/** Read status of a word or character. */
export type ReadStatus = 'correct' | 'mispronounced' | 'skipped' | 'inserted';

export interface StressScore {
  ref_stress: number;
  stress: number;
  overall: number;
  spell?: string;
  phoneme_offset?: number;
  phonetic?: string;
}

export interface WordScores {
  overall: number;
  pronunciation?: number;
  tone?: number;
  overall_pron?: number;
  prominence?: number;
  stress?: StressScore[];
  [key: string]: unknown;
}

export interface PhonemeScore {
  phoneme: string;
  phone?: string;
  category?: number;
  pronunciation: number;
  tone_index?: string;
  span?: Span;
  [key: string]: unknown;
}

export interface NormalizedSyllable {
  syllables: string;
  pinyin?: string;
  tone?: string;
  tone_sandhi?: string;
  phoneme_span?: number[];
}

export interface WordPart {
  part: string;
  charType?: number;
  beginIndex?: number;
  endIndex?: number;
}

export interface Phonic {
  spell: string;
  phoneme: string[];
  overall: number;
}

export interface Syllable {
  syllable: string;
  grapheme?: string;
  accuracy?: number;
}

export interface WordEvidence {
  scored?: boolean;
  rms_db?: number;
  rel_db?: number;
  dur_ms?: number;
  dur_ratio?: number;
  sfgop?: number;
  [key: string]: unknown;
}

/** One word (English) or character (Chinese). Times in 10 ms units. */
export interface WordScore {
  word: string;
  pinyin?: string;
  rawpinyin?: string;
  symbolpinyin?: string;
  charType?: number;
  readType?: number;
  tone?: string;
  scores: WordScores;
  span?: Span;
  pause?: { type: number; duration: number };
  phonemes?: PhonemeScore[];
  normalized_syllables?: NormalizedSyllable[];
  word_parts?: WordPart[];
  phonics?: Phonic[];
  linkable?: boolean;
  syllables?: Syllable[];
  evidence?: WordEvidence;
  /** alpha mode: kind of error of the letter. */
  error_type?: string;
  readStatus?: ReadStatus;
  read_status?: ReadStatus;
  [key: string]: unknown;
}

/** One sentence of sentence and passage modes. */
export interface SentenceScore {
  sentence: string;
  text?: string;
  index: number;
  overall?: number;
  scores: { overall: number; pronunciation?: number; fluency?: number; integrity?: number; [key: string]: unknown };
  span?: Span;
  details?: WordScore[];
  [key: string]: unknown;
}

export interface ParagraphScore {
  [key: string]: unknown;
}

/** Five composite dimensions plus details, all modes. */
export interface YuguScores {
  overall: number;
  integrity?: number;
  accuracy?: { overall: number; tone?: number; nasal?: number; retroflex?: number; phoneme?: number; initial?: number; final?: number; [key: string]: unknown };
  fluency?: { overall: number; speed_score?: number; naturalness?: number; [key: string]: unknown };
  reading_skill?: { overall: number; pause?: number; stress?: number; intonation?: number; rhythm?: number; [key: string]: unknown };
  prosody?: number;
  emotion?: number;
  tone?: number;
  rear_tone?: string;
  speed?: number;
  duration_s?: number;
  [key: string]: unknown;
}

export interface CompositeReport {
  compositeScore?: number;
  integrityScore?: number;
  accuracyScore?: number;
  pronunciationScore?: number;
  phonemeScore?: number;
  fluencyScore?: number;
  fluentScore?: number;
  readSpeedScore?: number;
  readSpeed?: number;
  skillScore?: number;
  stopConnScore?: number;
  stressScore?: number;
  intonationScore?: number;
  emotionScore?: number;
  literalTotal?: number;
  toneScore?: number;
  toneVoiceScore?: number;
  tongueScore?: number;
  nasalsScore?: number;
  rhythmScore?: number;
  completenessScore?: number;
  [key: string]: unknown;
}

/** One word boundary of connected speech mode. Times in ms. */
export interface ConnectedBoundary {
  /** The two words around the boundary. */
  between: string[];
  /** Expected phenomena, for example linking_CV, linking_VV, elision, reduction. */
  tags: string[];
  /** How well the phenomenon was realized, 0 to 1. */
  realized: number;
  start_ms: number;
  end_ms: number;
  gap_ms?: number;
  continuity?: number;
  posterior_linking?: number;
  covered?: boolean;
  [key: string]: unknown;
}

/** Coverage of connected speech mode. */
export interface ConnectedCoverage {
  ratio: number;
  covered_words?: number;
  n_words?: number;
  uncovered_words?: unknown[];
  scored?: boolean;
  [key: string]: unknown;
}

/** Feedback text of connected speech mode (result.report). */
export interface ConnectedReport {
  summary?: string;
  suggestions?: string[];
  dimensions?: Record<string, unknown>;
  source?: string;
  [key: string]: unknown;
}

/** Connected speech mode fields of EvalResult (coreType connected). */
export interface ConnectedScores {
  /** result.connected_overall: the total of this mode. */
  overall: number | null;
  linking: number | null;
  rhythm: number | null;
  elision: number | null;
  reduction: number | null;
  /** result.n_boundaries. */
  nBoundaries: number | null;
  boundaries: ConnectedBoundary[];
  coverage: ConnectedCoverage | null;
  /** result.raw: acoustic metrics such as linking_rate and nPVI_V. */
  metrics: Record<string, number> | null;
  report: ConnectedReport | null;
}

/** result.content of open mode. */
export interface OpenContentScores {
  overall: number;
  relevance?: number;
  coherence?: number;
  task_achievement?: number;
  [key: string]: unknown;
}

/** result.languageUse of open mode. */
export interface OpenLanguageUseScores {
  overall: number;
  grammar?: number;
  vocabulary?: number;
  [key: string]: unknown;
}

/** result.delivery of open mode. */
export interface OpenDeliveryScores {
  overall: number;
  fluency?: number;
  pronunciation?: number;
  /** Units per second. */
  speech_rate?: number;
  /** Readable speech rate, for example "233.8 字/分". */
  speech_rate_label?: string;
  n_pauses?: number;
  longest_pause_s?: number;
  voiced_s?: number;
  speech_span_s?: number;
  [key: string]: unknown;
}

/** result.feedback of open mode. */
export interface OpenFeedback {
  strengths?: string;
  weaknesses?: string;
  suggestions?: string[];
  [key: string]: unknown;
}

/** result.openTaskAudit of open mode (CONTRACT 7.4). */
export interface OpenTaskAudit {
  /** Reading the prompt back: total capped at 60. */
  promptEcho?: boolean;
  /** Empty talk: total capped at 70. */
  lowContent?: boolean;
  offTopic?: boolean;
  lowYield?: boolean;
  pictureUngrounded?: boolean;
  capApplied?: boolean;
  answerUnits?: number;
  note?: string;
  [key: string]: unknown;
}

/** Open mode fields of EvalResult (coreType open). */
export interface OpenScores {
  taskType: string | null;
  transcript: string | null;
  hasSpeech: boolean | null;
  content: OpenContentScores | null;
  languageUse: OpenLanguageUseScores | null;
  delivery: OpenDeliveryScores | null;
  feedback: OpenFeedback | null;
  audioQuality: { mos?: number; quality?: number; [key: string]: unknown } | null;
  openTaskAudit: OpenTaskAudit | null;
  /** How overall was computed: formula, weights, caps. */
  aggregation: Record<string, unknown> | null;
  rubricVersion: string | null;
}

/** The "result" object of the platform response. Fields depend on the mode. */
export interface RawResultBody {
  /** Total of every mode except connected. */
  overall?: number;
  /** Connected mode total. */
  connected_overall?: number;
  linking?: number;
  elision?: number;
  reduction?: number;
  n_boundaries?: number;
  boundaries?: ConnectedBoundary[];
  coverage?: ConnectedCoverage;
  /** Connected mode: acoustic metrics. */
  raw?: Record<string, number>;
  /** Connected mode: feedback text. */
  report?: ConnectedReport;
  calibrated?: boolean;
  scoringSource?: string;
  /** Open mode fields. */
  taskType?: string;
  language?: string;
  duration_s?: number;
  transcript?: string;
  hasSpeech?: boolean;
  content?: OpenContentScores;
  languageUse?: OpenLanguageUseScores;
  delivery?: OpenDeliveryScores;
  feedback?: OpenFeedback;
  audioQuality?: { mos?: number; quality?: number; [key: string]: unknown };
  openTaskAudit?: OpenTaskAudit;
  aggregation?: Record<string, unknown>;
  rubricVersion?: string;
  pronunciation?: number;
  tone?: number;
  fluency?: number;
  rhythm?: number;
  integrity?: number;
  speed?: number;
  rear_tone?: string;
  duration?: string;
  numeric_duration?: number;
  warning?: Array<number | { code: number; message?: string }>;
  words?: WordScore[];
  sentences?: SentenceScore[];
  paragraphs?: ParagraphScore[];
  accuracy?: number;
  reading_skill?: number;
  emotion?: number;
  compositeReport?: CompositeReport;
  yuguScores?: YuguScores;
  kernel_version?: string;
  resource_version?: string;
  _language?: string;
  _coreType?: string;
  [key: string]: unknown;
}

export interface AsrAlignment {
  char: string;
  index: number;
  read_status?: ReadStatus;
  start_time?: number;
  end_time?: number;
  asr_pinyin?: string;
  asr_tone?: number;
  asr_initial?: string;
  asr_final?: string;
  gop_score?: number;
  tone_pred?: number;
  p_neutral?: number;
  [key: string]: unknown;
}

/** asrText of the response; EvalResult.asrText is null when the platform sent none. */
export interface AsrText {
  text: string;
  alignment?: AsrAlignment[];
  /** word mode: the speaker read more than the target word. */
  overreadDetected?: boolean;
  extraTokens?: number;
  /** alpha mode: letters recognized as other letters. */
  substitutionDetected?: boolean;
  mismatchTokens?: number;
  mismatches?: Array<{ index: number; expected: string; recognized: string }>;
  source?: string;
  [key: string]: unknown;
}

export interface DimensionEvidence {
  word: string;
  type: 'wordSkip' | 'pitchLow' | 'severePhoneme';
  detail: string;
}

/** Chinese ASR arbitration (CONTRACT 7.3); absent in most results. */
export interface AsrArbitration {
  triggered: boolean;
  action: 'none' | 'sidecarUnavailable' | 'ceilingSoftened';
  divergence?: number;
  note?: string;
}

/** report of the platform response (CONTRACT 1 and 7). */
export interface EvalReport {
  summary?: string;
  dimensions?: Record<string, string>;
  suggestions?: string[];
  dimensionSuggestions?: Record<string, string[]>;
  /**
   * Dimension scores; null means the dimension was not scored (not 0). The keys depend on the
   * mode and language: affect only for Chinese, empty for open mode.
   */
  dimensionScores?: {
    accuracy?: number | null;
    fluency?: number | null;
    integrity?: number | null;
    reading_skill?: number | null;
    affect?: number | null;
    speechRate?: number | null;
    readSpeedRaw?: number | null;
    [key: string]: number | null | undefined;
  };
  dimensionEvidence?: Record<string, DimensionEvidence[]>;
  asrArbitration?: AsrArbitration;
  rubricVersion?: string;
  rubricBackfilled?: true;
  [key: string]: unknown;
}

export interface StandardAudio {
  url: string;
  format?: string;
  duration?: string;
}

/** Platform response (REST body or WS result frame). */
export interface RawEvalResponse {
  recordId?: string;
  eof?: number;
  event?: string;
  replayed?: boolean;
  result?: RawResultBody;
  report?: EvalReport;
  asrText?: AsrText;
  standardAudio?: StandardAudio;
  warnings?: Array<number | { code: number; message?: string }>;
  [key: string]: unknown;
}

/** Server audio quality warning. */
export interface AudioWarning {
  readonly code: number;
  readonly message: string;
}

/** Local precheck warning (90101 to 90105). */
export interface LocalWarning {
  readonly code: number;
  readonly name: string;
  readonly message: string;
}

/** Score dimensions; null when the mode does not produce the dimension. */
export interface EvalDimensions {
  integrity: number | null;
  accuracy: number | null;
  pronunciation: number | null;
  fluency: number | null;
  tone: number | null;
  rhythm: number | null;
  emotion: number | null;
  readingSkill: number | null;
}

/** Unified result of evaluate, evaluateCompat and streaming sessions. */
export interface EvalResult {
  recordId: string | null;
  eof: number | null;
  /** Total score: result.overall, or result.connected_overall in connected mode. */
  overall: number | null;
  dims: EvalDimensions;
  /** Speaking speed. */
  speed: number | null;
  /** numeric_duration, duration or duration_s (open mode). */
  durationSeconds: number | null;
  words: WordScore[];
  /** Sentences; passage mode fills details[] when paragraphNeedWordScore is 1. */
  sentences: SentenceScore[];
  paragraphs: ParagraphScore[];
  /** Connected speech mode only, else null. overall above is connected.overall. */
  connected: ConnectedScores | null;
  /** Open mode only, else null. */
  open: OpenScores | null;
  /** null when the platform sent none or an empty object. */
  asrText: AsrText | null;
  report: EvalReport | null;
  /** null when the platform sent none or an empty object. */
  standardAudio: StandardAudio | null;
  yuguScores: YuguScores | null;
  compositeReport: CompositeReport | null;
  /** Server warnings 1001 to 1009 (not thrown unless strictAudio). */
  warnings: AudioWarning[];
  /** Local precheck warnings. */
  localWarnings: LocalWarning[];
  coreType: string | null;
  language: string | null;
  mode: 'native' | 'compat';
  idempotencyKey: string | null;
  /** True when the platform replayed the result of an earlier request with the same key. */
  replayed: boolean;
  raw: RawEvalResponse;
}

export interface TtsResponse {
  code: number;
  message?: string;
  data?: { audioUrl: string; duration?: string; format?: string; warnings?: unknown[]; [key: string]: unknown };
  timestamp?: number;
}

export interface TtsResult {
  audioUrl: string;
  /** Playable URL. */
  absoluteUrl: string;
  duration: string | null;
  durationSeconds: number | null;
  format: string | null;
  warnings: unknown[];
  idempotencyKey: string | null;
  replayed: boolean;
  raw: TtsResponse;
}

/** data of GET /api/v1/report/{recordId}. */
export interface ReportData {
  recordId?: string;
  overall?: number;
  report?: EvalReport;
  [key: string]: unknown;
}

// ---------------------------------------------------------------- streaming

/** Progress frame of the compat stream. */
export interface StreamPartial {
  bytes: number | null;
  raw: Record<string, unknown>;
}

/** Data of onReconnected. */
export interface ReconnectInfo {
  /** Audio not part of the evaluation because of this reconnect (DROP). */
  droppedBytes: number;
  /** Audio sent again after this reconnect (REPLAY). */
  replayedBytes: number;
}

/**
 * Session listener. Exactly one of onResult and onError is called unless cancel() came first;
 * onClosed is always the last callback; callbacks never overlap.
 */
export interface StreamListener {
  onResult(result: EvalResult): void;
  onError(error: YuguError): void;
  onStateChanged?(oldState: SessionState, newState: SessionState): void;
  /** First connection established (reconnects call onReconnected instead). */
  onConnected?(): void;
  /** First server session started. */
  onStarted?(): void;
  onPartial?(partial: StreamPartial): void;
  onReconnecting?(attempt: number, delayMs: number, cause: YuguError): void;
  onReconnected?(attempt: number, info: ReconnectInfo): void;
  /** Local precheck warning of the accumulated audio at end(). */
  onWarning?(warning: LocalWarning): void;
  onClosed?(code: number, reason: string): void;
}

export interface SessionStats {
  state: SessionState;
  /** Reconnect attempts made in this session, all of them. */
  reconnectAttempts: number;
  acceptedBytes: number;
  replayBufferBytes: number;
  droppedBytes: number;
  replayOverflow: boolean;
  heartbeat: boolean;
}

/** Streaming evaluation session. Created by YuguClient.streamEvaluate and streamEvaluateCompat. */
export declare class YuguStreamSession {
  private constructor();
  readonly id: string;
  readonly idempotencyKey: string | null;
  readonly mode: 'native' | 'compat';
  readonly coreType: string;
  /** Equals the state announced by the last onStateChanged. */
  getState(): SessionState;
  isActive(): boolean;
  setListener(listener: StreamListener | null): void;
  getStats(): SessionStats;
  /** PCM16 mono audio, 640 bytes per 20 ms recommended. False when discarded. */
  sendAudio(chunk: ArrayBufferLike | ArrayBufferView, options?: { base64?: boolean }): boolean;
  /** Idempotent. */
  end(): void;
  /** Idempotent; no onResult or onError follows. */
  cancel(): void;
  /** Idempotent. */
  close(): void;
  waitForResult(): Promise<EvalResult>;
}

// ---------------------------------------------------------------- client

export declare class YuguClient {
  constructor(options: YuguClientOptions);
  readonly baseUrl: string;
  readonly wsBaseUrl: string;
  readonly authMode: 'signature' | 'token';
  readonly hasSigAuth: boolean;
  readonly hasTokenAuth: boolean;
  isClosed(): boolean;
  /** POST /api/v1/evaluate. */
  evaluate(audio: AudioInput, config: EvaluateConfig, options?: EvaluateOptions): Promise<EvalResult>;
  /** POST /{coreType}. */
  evaluateCompat(coreType: CompatCoreType, params: CompatParams | null, audio: AudioInput, options?: EvaluateOptions): Promise<EvalResult>;
  /** POST /api/v1/tts/generate. */
  tts(request: TtsRequest, options?: RequestOptions): Promise<TtsResult>;
  /** GET /api/v1/report/{recordId}. */
  getReport(recordId: string, options?: ReadOptions): Promise<ReportData>;
  /** WS /api/v1/ws/evaluate. */
  streamEvaluate(config: StreamConfig, listener: StreamListener, options?: StreamOptions): YuguStreamSession;
  /** WS /{coreType}. */
  streamEvaluateCompat(
    coreType: CompatCoreType,
    params: CompatStreamParams | null,
    listener: StreamListener,
    options?: StreamOptions,
  ): YuguStreamSession;
  resolveTtsUrl(audioUrl: string): string;
  resolveAudioUrl(url: string): string;
  /** Idempotent. Cancels sessions, aborts calls in flight; later calls throw IllegalSessionStateException 90004. */
  close(): Promise<void>;
}

// ---------------------------------------------------------------- recorder

/** getUserMedia constraints. */
export interface RecorderConstraints {
  audio?: boolean | object;
  video?: boolean | object;
  [key: string]: unknown;
}

/** Recorder listener; every method is optional. */
export interface RecorderListener {
  onStateChanged?(oldState: RecorderState, newState: RecorderState): void;
  /** 640 bytes of 16 kHz mono PCM16 (20 ms). */
  onFrame?(frame: Uint8Array): void;
  /** RMS of the input, 0 to 1. */
  onLevel?(level: number): void;
  /** Errors while recording (microphone unplugged 90202, processing 90203). */
  onError?(error: YuguError): void;
}

/** Environment overrides, for tests and special runtimes. */
export interface RecorderEnvironment {
  mediaDevices?: { getUserMedia(constraints: RecorderConstraints): Promise<unknown> } | null;
  AudioContext?: unknown;
  AudioWorkletNode?: unknown;
  createObjectURL?: ((blob: BlobLike) => string) | null;
}

export interface RecorderOptions {
  /** Default 16000. */
  targetSampleRate?: number;
  /** Default 640. */
  frameBytes?: number;
  constraints?: RecorderConstraints;
  /** Default true; false forces the ScriptProcessor path. */
  useAudioWorklet?: boolean;
  /** URL of a hosted copy of dist/yugu-pcm-worklet.js, for strict Content Security Policies. */
  workletModuleUrl?: string;
  listener?: RecorderListener | null;
  onFrame?: (frame: Uint8Array) => void;
  onLevel?: (level: number) => void;
  onError?: (error: YuguError) => void;
  onStateChanged?: (oldState: RecorderState, newState: RecorderState) => void;
  environment?: RecorderEnvironment;
}

/** Microphone recorder: 16 kHz, 16-bit, mono. */
export declare class YuguRecorder {
  constructor(options?: RecorderOptions);
  static isSupported(): boolean;
  getState(): RecorderState;
  setListener(listener: RecorderListener | null): void;
  isUsingAudioWorklet(): boolean;
  getDurationMs(): number;
  /** Rejects with PermissionException 90201 or IllegalSessionStateException 90202 or 90203. */
  start(options?: { session?: YuguStreamSession | null }): Promise<void>;
  pause(): Promise<void>;
  resume(): Promise<void>;
  /** Releases the microphone; recorded audio stays available. */
  stop(): Promise<void>;
  stopAndGetWav(): Promise<Blob>;
  exportWav(): Blob;
  exportWavBytes(): Uint8Array;
  /** Idempotent, never rejects. */
  release(): Promise<void>;
}

/** Source of the AudioWorklet processor. */
export declare const PCM_WORKLET_SOURCE: string;

// ---------------------------------------------------------------- utilities

/** Signed text: drop null and empty values, sort keys, join key=value with &. */
export declare function buildSignPayload(params: Record<string, string | number | boolean | null | undefined>): string;

/** Base64(HMAC_SHA256(buildSignPayload(params), secret)). */
export declare function signHmacSha256(
  params: Record<string, string | number | boolean | null | undefined>,
  secret: string,
  crypto?: CryptoLike,
): Promise<string>;

/** 32 lowercase hex characters (UUID v4 without dashes). */
export declare function generateIdempotencyKey(crypto?: CryptoLike): string;

/** 1 to 200 visible ASCII characters. */
export declare function isValidIdempotencyKey(key: unknown): key is string;

/** Result of precheckAudio. */
export interface PrecheckReport {
  container: 'wav' | 'pcm' | 'mp3' | 'ogg' | 'flac' | 'mp4' | 'webm' | 'unknown';
  size: number;
  durationMs: number | null;
  sampleRate: number | null;
  channels: number | null;
  bitsPerSample: number | null;
  peak: number | null;
  rms: number | null;
  rmsDbfs: number | null;
  warnings: LocalWarning[];
}

/** Local audio precheck (C-04). Does not throw for bad audio; inspect warnings. */
export declare function precheckAudio(
  audio: ArrayBufferLike | ArrayBufferView,
  options?: { format?: 'auto' | 'pcm'; sampleRate?: number; channels?: number; maxBytes?: number },
): PrecheckReport;

/** Parsed WAV header. */
export interface WavInfo {
  audioFormat: number;
  channels: number;
  sampleRate: number;
  byteRate: number;
  blockAlign: number;
  bitsPerSample: number;
  dataOffset: number;
  dataLength: number;
  isPcm16: boolean;
  valid: boolean;
}

/** RIFF WAVE header, or null when not WAV. */
export declare function parseWav(audio: ArrayBufferLike | ArrayBufferView): WavInfo | null;

/** PCM16 samples to a WAV Blob. */
export declare function encodeWav(pcm: Int16Array | ArrayBufferLike | ArrayBufferView, sampleRate?: number, channels?: number): Blob;

/** PCM16 samples to WAV bytes. */
export declare function encodeWavBytes(pcm: Int16Array | ArrayBufferLike | ArrayBufferView, sampleRate?: number, channels?: number): Uint8Array;

/** Float32 samples to Int16. */
export declare function floatToInt16(samples: Float32Array | ArrayLike<number>): Int16Array;

/** 50 MB, the REST upload limit checked by the precheck (90102). */
export declare const MAX_UPLOAD_BYTES: number;

/** 10 MB, the audio limit of one streaming round checked at end() (90102). */
export declare const MAX_STREAM_BYTES: number;

/** 10 MB, the default maxReplayBytes. */
export declare const DEFAULT_MAX_REPLAY_BYTES: number;

/** Platform response to EvalResult. */
export declare function normalizeEvalResult(
  raw: RawEvalResponse,
  meta?: {
    mode?: 'native' | 'compat';
    coreType?: string;
    idempotencyKey?: string | null;
    replayed?: boolean;
    localWarnings?: LocalWarning[];
  },
): EvalResult;

export declare const NATIVE_CORE_TYPES: readonly NativeCoreType[];
export declare const COMPAT_CORE_TYPES: readonly CompatCoreType[];
/** Warning code to server message. */
export declare const WARNING_MESSAGES: Readonly<Record<number, string>>;

/** Base64 of bytes. */
export declare function bytesToBase64(bytes: ArrayBufferLike | ArrayBufferView): string;

export declare const SDK_VERSION: string;
export declare const DEFAULT_BASE_URL: string;
export declare const DEFAULT_WS_BASE_URL: string;

/** Namespace object, also window.YuguSDK of the UMD bundle. */
declare const YuguSDK: {
  readonly YuguClient: typeof YuguClient;
  readonly CLIENT_DEFAULTS: typeof CLIENT_DEFAULTS;
  readonly YuguStreamSession: typeof YuguStreamSession;
  readonly SessionState: typeof SessionState;
  readonly AudioBufferPolicy: typeof AudioBufferPolicy;
  readonly HeartbeatMode: typeof HeartbeatMode;
  readonly DEFAULT_MAX_REPLAY_BYTES: typeof DEFAULT_MAX_REPLAY_BYTES;
  readonly YuguRecorder: typeof YuguRecorder;
  readonly RecorderState: typeof RecorderState;
  readonly PCM_WORKLET_SOURCE: typeof PCM_WORKLET_SOURCE;
  readonly YuguError: typeof YuguError;
  readonly NetworkException: typeof NetworkException;
  readonly RequestTimeoutException: typeof RequestTimeoutException;
  readonly AuthException: typeof AuthException;
  readonly PermissionException: typeof PermissionException;
  readonly InvalidParameterException: typeof InvalidParameterException;
  readonly NotFoundException: typeof NotFoundException;
  readonly ConflictException: typeof ConflictException;
  readonly RateLimitException: typeof RateLimitException;
  readonly QuotaExceededException: typeof QuotaExceededException;
  readonly ServerException: typeof ServerException;
  readonly AudioQualityException: typeof AudioQualityException;
  readonly IllegalSessionStateException: typeof IllegalSessionStateException;
  readonly RequestCancelledException: typeof RequestCancelledException;
  readonly ProtocolViolationException: typeof ProtocolViolationException;
  readonly YuguErrors: typeof YuguErrors;
  readonly isRetryable: typeof isRetryable;
  readonly ErrorCategory: typeof ErrorCategory;
  readonly WarningCode: typeof WarningCode;
  readonly ErrorCodes: typeof import('./error-table.js').ErrorCodes;
  readonly ERROR_TABLE: typeof import('./error-table.js').ERROR_TABLE;
  readonly WARNING_TABLE: typeof import('./error-table.js').WARNING_TABLE;
  readonly LOCAL_TABLE: typeof import('./error-table.js').LOCAL_TABLE;
  readonly HTTP_FALLBACK: typeof import('./error-table.js').HTTP_FALLBACK;
  readonly RETRYABLE_HTTP: typeof import('./error-table.js').RETRYABLE_HTTP;
  readonly DEFAULT_RETRY_POLICY: typeof DEFAULT_RETRY_POLICY;
  readonly DEFAULT_RECONNECT_POLICY: typeof DEFAULT_RECONNECT_POLICY;
  readonly computeBackoffDelay: typeof computeBackoffDelay;
  readonly computeRetryDelay: typeof computeRetryDelay;
  readonly parseRetryAfter: typeof parseRetryAfter;
  readonly signHmacSha256: typeof signHmacSha256;
  readonly buildSignPayload: typeof buildSignPayload;
  readonly generateIdempotencyKey: typeof generateIdempotencyKey;
  readonly isValidIdempotencyKey: typeof isValidIdempotencyKey;
  readonly AudioPrecheck: typeof AudioPrecheck;
  readonly precheckAudio: typeof precheckAudio;
  readonly parseWav: typeof parseWav;
  readonly encodeWav: typeof encodeWav;
  readonly encodeWavBytes: typeof encodeWavBytes;
  readonly floatToInt16: typeof floatToInt16;
  readonly MAX_UPLOAD_BYTES: typeof MAX_UPLOAD_BYTES;
  readonly MAX_STREAM_BYTES: typeof MAX_STREAM_BYTES;
  readonly normalizeEvalResult: typeof normalizeEvalResult;
  readonly NATIVE_CORE_TYPES: typeof NATIVE_CORE_TYPES;
  readonly COMPAT_CORE_TYPES: typeof COMPAT_CORE_TYPES;
  readonly WARNING_MESSAGES: typeof WARNING_MESSAGES;
  readonly LogLevel: typeof LogLevel;
  readonly bytesToBase64: typeof bytesToBase64;
  readonly SDK_VERSION: typeof SDK_VERSION;
  readonly DEFAULT_BASE_URL: typeof DEFAULT_BASE_URL;
  readonly DEFAULT_WS_BASE_URL: typeof DEFAULT_WS_BASE_URL;
};
export default YuguSDK;
