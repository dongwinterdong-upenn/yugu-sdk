// Compile-only test (tsc --noEmit, strict). Consumes every export of the package through its
// published name, as a browser TypeScript project would. Lines marked @ts-expect-error prove
// that misspelled fields and wrong types are compile errors.
import YuguSDK, {
  AudioBufferPolicy,
  AudioPrecheck,
  AudioQualityException,
  AuthException,
  bytesToBase64,
  buildSignPayload,
  CLIENT_DEFAULTS,
  COMPAT_CORE_TYPES,
  computeBackoffDelay,
  computeRetryDelay,
  ConflictException,
  DEFAULT_BASE_URL,
  DEFAULT_MAX_REPLAY_BYTES,
  DEFAULT_RECONNECT_POLICY,
  DEFAULT_RETRY_POLICY,
  DEFAULT_WS_BASE_URL,
  encodeWav,
  encodeWavBytes,
  ERROR_TABLE,
  ErrorCategory,
  ErrorCodes,
  floatToInt16,
  generateIdempotencyKey,
  HeartbeatMode,
  HTTP_FALLBACK,
  IllegalSessionStateException,
  InvalidParameterException,
  isRetryable,
  isValidIdempotencyKey,
  LOCAL_TABLE,
  LogLevel,
  MAX_STREAM_BYTES,
  MAX_UPLOAD_BYTES,
  NATIVE_CORE_TYPES,
  NetworkException,
  normalizeEvalResult,
  NotFoundException,
  parseRetryAfter,
  parseWav,
  PCM_WORKLET_SOURCE,
  PermissionException,
  precheckAudio,
  ProtocolViolationException,
  QuotaExceededException,
  RateLimitException,
  RecorderState,
  RequestCancelledException,
  RequestTimeoutException,
  RETRYABLE_HTTP,
  SDK_VERSION,
  ServerException,
  SessionState,
  signHmacSha256,
  WARNING_MESSAGES,
  WARNING_TABLE,
  WarningCode,
  YuguClient,
  YuguError,
  YuguErrors,
  YuguRecorder,
  YuguStreamSession,
} from '@shengzhiai/yugu-web-sdk';
import type {
  AudioWarning,
  CompatCoreType,
  ConnectedBoundary,
  EvalResult,
  EvaluateConfig,
  LocalWarning,
  OpenScores,
  ReconnectInfo,
  RetryPolicy,
  SessionStats,
  StreamListener,
  TtsResult,
  WordScore,
  YuguClientOptions,
  YuguEventListener,
} from '@shengzhiai/yugu-web-sdk';

const events: YuguEventListener = {
  onRequestStart(op, method, path, attempt) {
    const o: 'evaluate' | 'evaluateCompat' | 'tts' | 'getReport' = op;
    console.info(o, method.toUpperCase(), path.length, attempt + 1);
  },
  onRequestEnd(op, status, latency, attempts, error) {
    console.info(op, status + latency + attempts, error ? error.code : 0);
  },
  onRetry(op, attempt, delayMs, error) {
    console.info(op, attempt, delayMs, error.retryable);
  },
  onSessionStateChanged(id, oldState, newState) {
    console.info(id, oldState, newState);
  },
  onReconnect(id, attempt, ok) {
    console.info(id, attempt, ok);
  },
};

const options: YuguClientOptions = {
  baseUrl: DEFAULT_BASE_URL,
  wsBaseUrl: DEFAULT_WS_BASE_URL,
  token: async () => 'jwt-from-backend',
  retry: { maxRetries: 2 },
  reconnect: { maxAttempts: 5 },
  audioBufferPolicy: AudioBufferPolicy.REPLAY,
  maxReplayBytes: DEFAULT_MAX_REPLAY_BYTES,
  heartbeat: HeartbeatMode.AUTO,
  logLevel: LogLevel.INFO,
  logger: (level, tag, message) => console.info(level, tag, message),
  eventListener: events,
  audioPrecheck: AudioPrecheck.WARN,
  fetch: window.fetch.bind(window),
  WebSocket,
  crypto: window.crypto,
};

// @ts-expect-error misspelled option
const badOptions: YuguClientOptions = { baseURL: 'https://x' };
void badOptions;

const client = new YuguClient(options);
const signed = new YuguClient({ appKey: 'ak', secretKey: 'sk', logLevel: 'WARN' });

async function wholeUtterance(file: File): Promise<number | null> {
  const config: EvaluateConfig = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN', includeReport: true };
  // @ts-expect-error misspelled config field
  const typo: EvaluateConfig = { coreType: 'sentence', referenceTxt: 'x' };
  void typo;
  // @ts-expect-error unknown coreType
  const wrongMode: EvaluateConfig = { coreType: 'sentense', referenceText: 'x' };
  void wrongMode;
  const ctl = new AbortController();
  const result: EvalResult = await client.evaluate(file, config, { idempotencyKey: generateIdempotencyKey(), signal: ctl.signal, timeoutMs: 20000 });
  const words: WordScore[] = result.words;
  const firstPhoneme: string | undefined = words[0]?.phonemes?.[0]?.phoneme;
  const warnings: AudioWarning[] = result.warnings;
  const local: LocalWarning[] = result.localWarnings;
  const dims: number | null = result.dims.fluency;
  const tone: number | null = result.dims.tone;
  const sentenceDetail: WordScore[] | undefined = result.sentences[0]?.details;
  const report = result.report?.dimensionScores?.accuracy;
  console.info(firstPhoneme, warnings.length, local.length, dims, tone, sentenceDetail, report, result.replayed, result.idempotencyKey);
  return result.overall;
}

async function perModeFields(bytes: Uint8Array): Promise<void> {
  const connected = await client.evaluate(bytes, { coreType: 'connected', referenceText: 'I want to eat an apple.' });
  const boundaries: ConnectedBoundary[] = connected.connected ? connected.connected.boundaries : [];
  const linking: number | null = connected.connected ? connected.connected.linking : null;
  console.info(connected.overall, boundaries[0]?.between.join(' '), boundaries[0]?.tags, linking);
  const open = await client.evaluate(bytes.buffer, { coreType: 'open', referenceText: '自我介绍', taskType: 'free' });
  const o: OpenScores | null = open.open;
  console.info(o?.content?.relevance, o?.delivery?.speech_rate_label, o?.feedback?.suggestions?.[0], o?.openTaskAudit?.promptEcho, o?.transcript);
  const raw = open.raw.result?.languageUse?.grammar;
  console.info(raw);
}

async function compatAndOthers(blob: Blob): Promise<void> {
  const ct: CompatCoreType = COMPAT_CORE_TYPES[0];
  // @ts-expect-error not a compat coreType
  const badCt: CompatCoreType = 'sentence';
  void badCt;
  const r = await client.evaluateCompat(ct, { refText: 'How are you', paragraph_need_word_score: 1 }, blob);
  console.info(r.mode, r.coreType, NATIVE_CORE_TYPES.length);
  const tts: TtsResult = await client.tts({ text: '你好', voice: 'xiaoyan', format: 'mp3', speed: 50 });
  // @ts-expect-error unknown TTS format
  await client.tts({ text: '你好', format: 'flac' });
  new Audio(tts.absoluteUrl).play().catch(() => undefined);
  const data = await client.getReport(r.recordId ?? 'eval_x');
  console.info(data.overall, client.resolveAudioUrl('/audio/x.wav'), client.resolveTtsUrl('/audio/x.mp3'));
}

function streaming(): YuguStreamSession {
  const listener: StreamListener = {
    onResult(result) {
      console.info(result.overall, result.replayed);
    },
    onError(error) {
      if (error instanceof NetworkException && error.code === ErrorCodes.RECONNECT_EXHAUSTED) console.warn('gave up');
      if (isRetryable(error)) console.warn('retryable');
    },
    onStateChanged(oldState, newState) {
      const s: SessionState = newState;
      console.info(oldState, s === SessionState.RECONNECTING);
    },
    onPartial(p) {
      console.info(p.bytes);
    },
    onReconnecting(attempt, delayMs, cause) {
      console.info(attempt, delayMs, cause.message);
    },
    onReconnected(attempt, info: ReconnectInfo) {
      console.info(attempt, info.droppedBytes, info.replayedBytes);
    },
    onWarning(w) {
      console.info(w.code, w.name);
    },
    onClosed(code, reason) {
      console.info(code, reason);
    },
  };
  // @ts-expect-error onResult and onError are required
  client.streamEvaluate({ coreType: 'sentence', referenceText: 'x' }, { onClosed() {} });
  const session = client.streamEvaluate({ coreType: 'sentence', referenceText: '今天天气很好' }, listener, {
    audioBufferPolicy: 'DROP',
    heartbeat: false,
    resultTimeoutMs: 60000,
    query: { tag: 'demo' },
  });
  const ok: boolean = session.sendAudio(new Uint8Array(640));
  const stats: SessionStats = session.getStats();
  console.info(ok, stats.reconnectAttempts, session.getState(), session.isActive(), session.id, session.idempotencyKey);
  session.end();
  session.waitForResult().then((r) => console.info(r.overall)).catch(() => undefined);
  const compat = client.streamEvaluateCompat('sent.eval.cn', { refText: '北京你好', realtime_feedback: true }, listener);
  compat.cancel();
  compat.close();
  return session;
}

async function recorder(session: YuguStreamSession): Promise<Blob> {
  const rec = new YuguRecorder({
    onLevel: (level) => console.info(level),
    listener: {
      onStateChanged(o, n) {
        const st: RecorderState = n;
        console.info(o, st === RecorderState.RECORDING);
      },
      onFrame(frame) {
        console.info(frame.byteLength);
      },
      onError(e) {
        if (e instanceof PermissionException) console.warn('no microphone permission');
        if (e instanceof IllegalSessionStateException) console.warn('microphone unavailable');
      },
    },
  });
  if (!YuguRecorder.isSupported()) throw new Error('unsupported');
  await rec.start({ session });
  await rec.pause();
  await rec.resume();
  const wav = await rec.stopAndGetWav();
  const bytes: Uint8Array = rec.exportWavBytes();
  console.info(bytes.length, rec.getDurationMs(), rec.isUsingAudioWorklet(), rec.getState(), PCM_WORKLET_SOURCE.length);
  rec.setListener(null);
  await rec.release();
  await rec.release();
  return wav;
}

function errors(e: unknown): string {
  const all = [
    NetworkException,
    RequestTimeoutException,
    AuthException,
    PermissionException,
    InvalidParameterException,
    NotFoundException,
    ConflictException,
    RateLimitException,
    QuotaExceededException,
    ServerException,
    AudioQualityException,
    IllegalSessionStateException,
    RequestCancelledException,
    ProtocolViolationException,
  ];
  for (const C of all) if (e instanceof C) return C.name;
  if (e instanceof YuguError) {
    const c: ErrorCategory = e.category;
    const fields = [e.code, e.httpStatus, e.attempts, e.retryAfterMs, e.rawBody, e.traceId, e.recordId, e.idempotencyKey];
    return `${c} ${fields.join(',')} ${e.toJSON().name}`;
  }
  const built = YuguErrors.fromCode(ErrorCodes.TOO_MANY_REQUESTS, { httpStatus: 429 });
  const warn = YuguErrors.fromWarning(WarningCode.NO_VALID_AUDIO);
  const http = YuguErrors.fromHttp(503, '{"code":50200,"message":"x"}');
  const frame = YuguErrors.fromWsFrame({ event: 'error', code: 40901, message: 'busy' });
  const cat: ErrorCategory = YuguErrors.categoryFor(40001, 400);
  const Cls = YuguErrors.classForCategory(ErrorCategory.QUOTA);
  return [built.retryable, warn.code, http.category, frame.retryable, cat, new Cls('x').name, YuguErrors.parseErrorBody('{}').code].join();
}

function tablesAndUtilities(): void {
  const entry = ERROR_TABLE[40001];
  const w = WARNING_TABLE[1002];
  const l = LOCAL_TABLE[90001];
  console.info(entry?.category, w?.message, l?.retryable, HTTP_FALLBACK[503], RETRYABLE_HTTP.includes(429), WARNING_MESSAGES[1001]);
  const policy: RetryPolicy = { ...DEFAULT_RETRY_POLICY, maxRetries: 3 };
  console.info(computeBackoffDelay(policy, 1, () => 0.5), computeRetryDelay(policy, 2, parseRetryAfter('1'), Math.random));
  console.info(DEFAULT_RECONNECT_POLICY.maxAttempts, CLIENT_DEFAULTS.readTimeoutMs, MAX_UPLOAD_BYTES, MAX_STREAM_BYTES, SDK_VERSION);
  console.info(buildSignPayload({ a: '1', b: null }), isValidIdempotencyKey('k'), bytesToBase64(new Uint8Array([1, 2])));
  signHmacSha256({ refText: 'x' }, 'secret').then((s) => console.info(s.length)).catch(() => undefined);
  const pcm = floatToInt16(new Float32Array(16000));
  const blob: Blob = encodeWav(pcm, 16000);
  const bytes = encodeWavBytes(pcm, 16000);
  const report = precheckAudio(bytes);
  const info = parseWav(bytes);
  console.info(blob.size, report.durationMs, report.warnings.map((x) => x.code), info?.sampleRate);
  const parsed = normalizeEvalResult({ recordId: 'r', result: { overall: 90 } }, { mode: 'native' });
  console.info(parsed.overall, YuguSDK.SDK_VERSION, YuguSDK.YuguClient === YuguClient);
}

void wholeUtterance;
void perModeFields;
void compatAndOthers;
void streaming;
void recorder;
void errors;
void tablesAndUtilities;
void signed;
