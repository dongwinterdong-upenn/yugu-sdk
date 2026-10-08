// Compile-only check of the published declarations (npm run typecheck). Uses every export of the
// package with strict settings and the ES2017 library of the mini program runtime. Lines marked
// with ts-expect-error prove that wrong input fails to compile.
import {
  YuguClient, StreamSession, SessionState, AudioBufferPolicy, YuguRecorder, RecorderState,
  YuguError, NetworkException, RequestTimeoutException, AuthException, PermissionException,
  InvalidParameterException, NotFoundException, ConflictException, RateLimitException,
  QuotaExceededException, ServerException, AudioQualityException, IllegalSessionStateException,
  RequestCancelledException, ProtocolViolationException, ErrorCategory, WarningCode, YuguErrors,
  isRetryable, fromCode, fromWarningCode, fromHttpResponse,
  ERROR_TABLE, WARNING_TABLE, LOCAL_TABLE, HTTP_FALLBACK, RETRYABLE_HTTP, ErrorCodes,
  LogLevel, AudioPrecheckMode, precheckAudio,
  DEFAULT_RETRY_POLICY, DEFAULT_RECONNECT_POLICY, computeBackoffDelay, computeRetryDelay, parseRetryAfter,
  generateIdempotencyKey, signParams, buildSignPayload, parseEvalResult, createAbortController,
  SDK_VERSION, USER_AGENT, CORE_TYPES, COMPAT_CORE_TYPES,
} from '@shengzhiai/yugu-miniprogram-sdk';
import type {
  YuguClientOptions, RequestOptions, EvaluateParams, EvaluateCompatParams, TtsParams, EvalResult, TtsResult,
  ReportData, StreamListener, StreamOptions, StreamEvaluateParams, StreamCompatParams, PartialResult,
  ReconnectInfo, RecordingResult, RecorderListener, RecorderOptions, PipeOptions, Warning, WordScore,
  SentenceScore, Dimensions, ConnectedScores, ConnectedBoundary, OpenScores, OpenContent, OpenDelivery,
  OpenLanguageUse, OpenFeedback, OpenTaskAudit, AsrText, AsrAlignment, Report, StandardAudio, RetryPolicy,
  ReconnectPolicy, EventListener, LogSink, WxLike, WxSocketTask, WxRecorderManager, AudioInput, AudioFile,
  PrecheckReport, SessionStats, ErrorTableEntry, CoreType, CompatCoreType, Auth, YuguErrorJson,
  SdkAbortController, AbortSignalLike, BackoffPolicy, RestOp, DimensionScores, PhonemeScore, Span, CodeNamespace,
} from '@shengzhiai/yugu-miniprogram-sdk';

declare const wx: WxLike;
declare const audioBuffer: ArrayBuffer;
declare function show(text: string): void;

const version: '2.0.0' = SDK_VERSION;
const ua: string = USER_AGENT;
const firstCore: CoreType = CORE_TYPES[0];
const firstCompat: CompatCoreType = COMPAT_CORE_TYPES[0];
show(version + ua + firstCore + firstCompat);

// ---- options and client
const events: EventListener = {
  onRequestStart(op: RestOp, method, path, attempt) { show(op + method + path + attempt); },
  onRequestEnd(op, status, latency, attempts, error) { show(op + status + latency + attempts + (error ? error.code : '')); },
  onRetry(op, attempt, delayMs, error) { show(`${op} ${attempt} ${delayMs} ${error.message}`); },
  onSessionStateChanged(id, oldState, newState) { show(id + oldState + newState); },
  onReconnect(id, attempt, ok) { show(id + attempt + ok); },
};
const sink: LogSink = (level, tag, message) => show(level + tag + message);
const retry: Partial<RetryPolicy> = { maxRetries: 3, jitter: 0.2 };
const reconnect: Partial<ReconnectPolicy> = { maxAttempts: 5 };
const auth: Auth = { token: 'jwt' };
const options: YuguClientOptions = {
  auth,
  baseUrl: 'https://open.shengzhiai.com',
  wsBaseUrl: 'wss://open.shengzhiai.com',
  connectTimeoutMs: 10000,
  readTimeoutMs: 120000,
  totalTimeoutMs: 300000,
  retry,
  autoIdempotencyKey: true,
  logLevel: LogLevel.INFO,
  logger: sink,
  eventListener: events,
  audioPrecheck: AudioPrecheckMode.WARN,
  strictAudio: false,
  userAgent: 'my-app/1.0',
  reconnect,
  audioBufferPolicy: AudioBufferPolicy.REPLAY,
  heartbeatIntervalMs: 15000,
  heartbeatTimeoutMs: 30000,
  resultTimeoutMs: 300000,
  replayBufferLimitBytes: 10 * 1024 * 1024,
  wx,
  random: () => 0.5,
};
const client = new YuguClient(options);
const legacy = new YuguClient({ appKey: 'ak', secretKey: 'sk', wx });
legacy.close();
// @ts-expect-error auth is required
new YuguClient({ baseUrl: 'https://x' });
// @ts-expect-error unknown log level
new YuguClient({ auth, logLevel: 'TRACE' });
// @ts-expect-error unknown buffer policy
new YuguClient({ auth, audioBufferPolicy: 'KEEP' });

async function rest(): Promise<void> {
  const ctl: SdkAbortController = createAbortController();
  const signal: AbortSignalLike = ctl.signal;
  const ro: RequestOptions = { idempotencyKey: generateIdempotencyKey(), timeoutMs: 30000, totalTimeoutMs: 60000, retry: false, signal, audioPrecheck: 'REJECT', strictAudio: true };
  const file: AudioFile = { tempFilePath: 'wxfile://tmp.pcm', format: 'pcm', sampleRate: 16000 };
  const inputs: AudioInput[] = [audioBuffer, new Uint8Array(4), 'wxfile://tmp.wav', file];
  const params: EvaluateParams = { coreType: 'sentence', referenceText: '今天天气很好', language: 'zh-CN', audio: inputs[0], includeReport: true, paragraphNeedWordScore: 1, extra: { future: 1 } };
  const r: EvalResult = await client.evaluate(params, ro);
  const dims: Dimensions = r.dims;
  const overall: number | null = r.overall;
  const words: WordScore[] = r.words;
  const firstWord: WordScore | undefined = words[0];
  const phoneme: PhonemeScore | undefined = firstWord && firstWord.phonemes ? firstWord.phonemes[0] : undefined;
  const span: Span | undefined = firstWord ? firstWord.span : undefined;
  const sentences: SentenceScore[] = r.sentences;
  const details: WordScore[] | undefined = sentences[0] ? sentences[0].details : undefined;
  const warnings: Warning[] = r.warnings.concat(r.localWarnings);
  const asr: AsrText | null = r.asrText;
  const align: AsrAlignment | undefined = asr && asr.alignment ? asr.alignment[0] : undefined;
  const report: Report | null = r.report;
  const ds: DimensionScores | undefined = report && report.dimensionScores && 'accuracy' in report.dimensionScores ? report.dimensionScores as DimensionScores : undefined;
  const std: StandardAudio | null = r.standardAudio;
  const connected: ConnectedScores | null = r.connected;
  const boundary: ConnectedBoundary | undefined = connected ? connected.boundaries[0] : undefined;
  const open: OpenScores | null = r.open;
  const content: OpenContent | null = open ? open.content : null;
  const delivery: OpenDelivery | null = open ? open.delivery : null;
  const languageUse: OpenLanguageUse | null = open ? open.languageUse : null;
  const feedback: OpenFeedback | null = open ? open.feedback : null;
  const audit: OpenTaskAudit | null = open ? open.openTaskAudit : null;
  show(JSON.stringify([dims.accuracy, overall, phoneme, span, details, warnings, align, ds, std, boundary, content, delivery, languageUse, feedback, audit, r.replayed, r.idempotencyKey, r.attempts, r.raw]));
  if (connected) show(String(connected.linking) + connected.nBoundaries + (boundary ? boundary.start_ms : ''));
  if (delivery) show(String(delivery.speech_rate_label));
  // @ts-expect-error coreType is checked at compile time
  await client.evaluate({ coreType: 'essay', referenceText: 'x', audio: audioBuffer });
  // @ts-expect-error referenceText is required
  await client.evaluate({ coreType: 'word', audio: audioBuffer });
  // @ts-expect-error audio or audioPath is required
  await client.evaluate({ coreType: 'word', referenceText: 'x' });
  await client.evaluate({ coreType: 'word', referenceText: 'apple', audioPath: 'wxfile://a.wav' });
  const compat: EvaluateCompatParams = { refText: 'How are you', language: 'en-US', fields: { paragraph_need_word_score: 1 }, audio: audioBuffer };
  const c: EvalResult = await client.evaluateCompat('sent.eval', compat);
  // @ts-expect-error unknown compat coreType
  await client.evaluateCompat('sent.eval.xx', compat);
  const ttsParams: TtsParams = { text: '你好', voice: 'xiaoyan', format: 'mp3', speed: 50 };
  const t: TtsResult = await client.tts(ttsParams);
  const rep: ReportData = await client.getReport(c.recordId || '');
  show(t.fullUrl + String(rep.overall) + c.dims.fluency);
  ctl.abort('done');
}

// ---- sessions, recorder
function streaming(): void {
  const listener: StreamListener = {
    onResult(result: EvalResult) { show(String(result.overall)); },
    onError(error: YuguError) {
      if (error instanceof NetworkException && isRetryable(error)) show('network');
      show(error.category + error.code + error.httpStatus + String(error.idempotencyKey));
    },
    onStateChanged(oldState: SessionState, newState: SessionState) { show(oldState + newState); },
    onConnected() { show('connected'); },
    onStarted() { show('started'); },
    onPartial(p: PartialResult) { show(String(p.bytes)); },
    onReconnecting(attempt, delayMs, cause) { show(attempt + ' ' + delayMs + cause.message); },
    onReconnected(attempt, info: ReconnectInfo) { show(attempt + ' ' + info.droppedBytes + info.replayedBytes); },
    onWarning(w) { show(String(w.code)); },
    onClosed(code, reason) { show(code + reason); },
  };
  const so: StreamOptions = { reconnect: false, audioBufferPolicy: 'DROP', heartbeatIntervalMs: 0, query: { trace: 1 } };
  const sp: StreamEvaluateParams = { coreType: 'sentence', referenceText: '今天天气很好', sampleRate: 16000 };
  const session: StreamSession = client.streamEvaluate(sp, listener, so);
  const cp: StreamCompatParams = { refText: '北京你好', realtimeFeedback: true };
  const compat = client.streamEvaluateCompat('sent.eval.cn', cp, listener);
  const state: SessionState = session.getState();
  const stats: SessionStats = session.getStats();
  const accepted: boolean = session.sendAudio(new Uint8Array(640));
  session.setListener({ onClosed() { show('closed'); } });
  session.removeListener();
  show(state + stats.heartbeat + accepted + session.isActive() + session.id + session.mode + String(session.idempotencyKey) + SessionState.CLOSED);
  session.end();
  session.cancel();
  session.close();
  compat.close();
  // @ts-expect-error sessions come from the client only
  new StreamSession();
  // @ts-expect-error onResult and onError are required
  client.streamEvaluate(sp, { onResult() {} });

  const ro: RecorderOptions = { sampleRate: 16000, numberOfChannels: 1, format: 'PCM', frameSize: 1, duration: 60000 };
  const recorder: YuguRecorder = client.createRecorder(ro);
  const own = new YuguRecorder({ format: 'mp3' }, { wx });
  const rl: RecorderListener = {
    onStateChanged(o: RecorderState, n: RecorderState) { show(o + n); },
    onFrame(frame: ArrayBuffer, last: boolean) { show(frame.byteLength + String(last)); },
    onStop(result: RecordingResult) { show(String(result.tempFilePath)); },
    onError(e) { show(e.message); },
  };
  recorder.setListener(rl);
  const pipe: PipeOptions = { endOnStop: true, frameBytes: 640 };
  recorder.pipeTo(session, pipe);
  recorder.start();
  recorder.pause();
  recorder.resume();
  recorder.stop().then((res) => client.evaluate({ coreType: 'sentence', referenceText: 'x', audio: res }));
  recorder.unpipe();
  recorder.removeListener();
  recorder.release();
  own.release();
  show(recorder.getState() + RecorderState.RELEASED);
  // @ts-expect-error unsupported sample rate
  client.createRecorder({ sampleRate: 15000 });
  client.close();
  show(String(client.isClosed()));
}

// ---- errors and tables
function errors(e: unknown): void {
  const all: YuguError[] = [
    new NetworkException('n'), new RequestTimeoutException('t'), new AuthException('a'), new PermissionException('p'),
    new InvalidParameterException('i'), new NotFoundException('nf'), new ConflictException('c'), new RateLimitException('r'),
    new QuotaExceededException('q'), new ServerException('s'), new AudioQualityException('au'),
    new IllegalSessionStateException('st'), new RequestCancelledException('cx'), new ProtocolViolationException('pv'),
    new YuguError('base', { category: ErrorCategory.UNKNOWN, code: 0 }),
  ];
  const json: YuguErrorJson = all[0].toJSON();
  const fromTable: YuguError = fromCode(ErrorCodes.IDEMPOTENCY_IN_PROGRESS);
  const w: YuguError = fromWarningCode(WarningCode.NO_VALID_AUDIO);
  const h: YuguError = fromHttpResponse(503, '{"code":50200}', { headers: { 'Retry-After': '2' } });
  const n: YuguError = YuguErrors.fromCode(40001);
  const entry: ErrorTableEntry = ERROR_TABLE[40001];
  const warnEntry: ErrorTableEntry = WARNING_TABLE[1001];
  const localEntry: ErrorTableEntry = LOCAL_TABLE[90001];
  const fallback: ErrorCategory = HTTP_FALLBACK[503];
  const retryableStatuses: readonly number[] = RETRYABLE_HTTP;
  const cat: ErrorCategory = h.category;
  const ns: CodeNamespace = h.codeNamespace;
  show(ns);
  if (e instanceof RateLimitException && e.retryAfterMs !== undefined) show(String(e.retryAfterMs));
  show(JSON.stringify([json, fromTable.retryable, w.code, n.code, entry.name, warnEntry, localEntry, fallback, retryableStatuses, cat, YuguErrors.isRetryable(e), isRetryable(h)]));
  // @ts-expect-error error fields are read only
  h.code = 1;
}

// ---- helpers
function helpers(): void {
  const policy: Readonly<RetryPolicy> = DEFAULT_RETRY_POLICY;
  const rp: Readonly<ReconnectPolicy> = DEFAULT_RECONNECT_POLICY;
  const backoff: BackoffPolicy = rp;
  const d1: number = computeBackoffDelay(1, backoff, () => 0.5);
  const d2: number = computeRetryDelay(2, policy, Math.random, 1000);
  const ra: number | undefined = parseRetryAfter('30');
  const sig: string = signParams({ coreType: 'sent.eval.cn', language: 'zh-CN', refText: '北京你好', empty: null }, 'test_secret_key_123');
  const payload: string = buildSignPayload({ a: 1, b: true });
  const parsed: EvalResult = parseEvalResult({ result: { overall: 80 } });
  const pre: PrecheckReport = precheckAudio(audioBuffer, { format: 'pcm', sampleRate: 16000, stream: true });
  show(JSON.stringify([d1, d2, ra, sig, payload, parsed.overall, pre.warnings, AudioPrecheckMode.OFF, LogLevel.OFF]));
}

// ---- the wx injection shapes
function shapes(task: WxSocketTask, rec: WxRecorderManager): void {
  task.send({ data: new ArrayBuffer(2) });
  task.onMessage((m) => show(typeof m.data === 'string' ? m.data : String(m.data.byteLength)));
  rec.onFrameRecorded((f) => show(String(f.isLastFrame)));
}

void rest;
void streaming;
void errors;
void helpers;
void shapes;
