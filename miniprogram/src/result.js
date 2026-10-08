// Result parsing. Evaluate and compat answer with the bare result JSON (no envelope); TTS and report
// use {code: 0, data: {...}} (DESIGN 2.8).
import { WARNING_TABLE } from './error-table.js';
import { fromCode, ProtocolViolationException } from './errors.js';
import { toInt, toNumberOrNull, headerValue } from './util.js';

function nn(v) {
  return v === undefined ? null : v;
}

/** Merges `result.warning` and top level `warnings`. Entries are codes or {code, message} objects. */
export function extractWarnings(resp) {
  const out = [];
  const seen = {};
  const push = (list) => {
    if (!Array.isArray(list)) return;
    for (let i = 0; i < list.length; i++) {
      const w = list[i];
      const code = toInt(w && typeof w === 'object' ? w.code : w);
      if (!code || seen[code]) continue;
      seen[code] = true;
      const entry = WARNING_TABLE[code];
      const message = w && typeof w === 'object' && w.message ? String(w.message) : entry ? entry.message : 'warning ' + code;
      out.push({ code, message });
    }
  };
  const result = resp && resp.result;
  push(result && result.warning);
  push(resp && resp.warnings);
  return out;
}

function objOrNull(v) {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : null;
}

/**
 * Connected speech mode (coreType connected): the total is result.connected_overall and the result
 * has no `overall`. Returns null for every other mode.
 */
function connectedScores(res) {
  if (res.connected_overall === undefined) return null;
  return {
    overall: toNumberOrNull(res.connected_overall),
    linking: toNumberOrNull(res.linking),
    rhythm: toNumberOrNull(res.rhythm),
    elision: toNumberOrNull(res.elision),
    reduction: toNumberOrNull(res.reduction),
    nBoundaries: toNumberOrNull(res.n_boundaries),
    boundaries: Array.isArray(res.boundaries) ? res.boundaries : [],
    coverage: objOrNull(res.coverage),
    metrics: objOrNull(res.raw),
  };
}

/**
 * Open question mode (coreType open): content, languageUse and delivery sub scores, transcript,
 * feedback and the task audit. Returns null for every other mode.
 */
function openScores(res) {
  const isOpen = res._coreType === 'open' || res.content !== undefined || res.delivery !== undefined ||
    res.languageUse !== undefined || res.openTaskAudit !== undefined;
  if (!isOpen) return null;
  return {
    taskType: nn(res.taskType),
    transcript: typeof res.transcript === 'string' ? res.transcript : null,
    hasSpeech: typeof res.hasSpeech === 'boolean' ? res.hasSpeech : null,
    durationSec: toNumberOrNull(res.duration_s),
    content: objOrNull(res.content),
    languageUse: objOrNull(res.languageUse),
    delivery: objOrNull(res.delivery),
    feedback: objOrNull(res.feedback),
    openTaskAudit: objOrNull(res.openTaskAudit),
    audioQuality: objOrNull(res.audioQuality),
    aggregation: objOrNull(res.aggregation),
    rubricVersion: nn(res.rubricVersion),
  };
}

/**
 * Normalized EvalResult. Score fields per mode:
 * - word, sentence, passage, alpha, pinyin: result.overall, pronunciation (accuracy), fluency,
 *   integrity, rhythm, tone (Chinese), rear_tone, speed, words[], sentences[] (passage sentences carry
 *   details[] when paragraphNeedWordScore is 1);
 * - connected: no result.overall; `overall` falls back to result.connected_overall and `connected`
 *   holds linking, rhythm, elision, reduction, n_boundaries and boundaries[];
 * - open: result.overall plus `open` with content, languageUse, delivery, transcript, feedback,
 *   openTaskAudit.
 * `raw` keeps the whole platform response.
 */
export function parseEvalResult(resp) {
  const r = resp && typeof resp === 'object' ? resp : {};
  const res = r.result && typeof r.result === 'object' ? r.result : {};
  const pronunciation = toNumberOrNull(res.pronunciation);
  const accuracy = toNumberOrNull(res.accuracy);
  const connected = connectedScores(res);
  let overall = toNumberOrNull(res.overall);
  if (overall === null && connected) overall = connected.overall;
  return {
    recordId: r.recordId !== undefined && r.recordId !== null ? String(r.recordId) : null,
    eof: r.eof !== undefined ? toInt(r.eof) : null,
    coreType: typeof res._coreType === 'string' ? res._coreType : null,
    language: typeof res._language === 'string' ? res._language : null,
    overall,
    dims: {
      integrity: toNumberOrNull(res.integrity),
      pronunciation,
      accuracy: accuracy !== null ? accuracy : pronunciation,
      fluency: toNumberOrNull(res.fluency),
      tone: toNumberOrNull(res.tone),
      rhythm: toNumberOrNull(res.rhythm),
      emotion: toNumberOrNull(res.emotion),
    },
    speed: toNumberOrNull(res.speed),
    rearTone: nn(res.rear_tone),
    duration: nn(res.duration),
    durationSec: toNumberOrNull(res.numeric_duration !== undefined ? res.numeric_duration : res.duration),
    words: Array.isArray(res.words) ? res.words : [],
    sentences: Array.isArray(res.sentences) ? res.sentences : [],
    connected,
    open: openScores(res),
    asrText: nn(r.asrText),
    report: nn(r.report),
    standardAudio: nn(r.standardAudio),
    yuguScores: nn(res.yuguScores),
    warnings: extractWarnings(r),
    localWarnings: [],
    idempotencyKey: null,
    replayed: r.replayed === true,
    attempts: 0,
    raw: r,
  };
}

export function isReplayedHeader(headers) {
  const v = headerValue(headers, 'Idempotency-Replayed');
  return typeof v === 'string' && v.trim().toLowerCase() === 'true';
}

/** JSON body of a 2xx response; a body that is not JSON is a protocol violation (90005). */
export function parseJsonBody(text, init) {
  try {
    const v = JSON.parse(text);
    if (v && typeof v === 'object') return v;
  } catch (e) {
    // handled below
  }
  const o = init || {};
  return failProtocol('response body is not a JSON object', text, o);
}

function failProtocol(message, text, o) {
  throw new ProtocolViolationException(message, {
    code: 90005,
    rawBody: typeof text === 'string' ? text : null,
    httpStatus: o.httpStatus,
    idempotencyKey: o.idempotencyKey,
  });
}

/** Unwraps {code: 0, data} envelopes; a non zero code becomes the typed error of that code. */
export function unwrapEnvelope(json, init) {
  const o = init || {};
  const code = toInt(json.code);
  if (code !== 0) {
    // An envelope is a server error body: its code is looked up in the platform error table only.
    throw fromCode(code, {
      namespace: 'errors',
      message: json.message ? String(json.message) : undefined,
      httpStatus: o.httpStatus,
      idempotencyKey: o.idempotencyKey,
      rawBody: o.rawBody,
    });
  }
  return json.data && typeof json.data === 'object' ? json.data : {};
}

/** Absolute URL of a TTS audio file. Paths under /audio/ are served at <base>/tts/audio/... (CONTRACT 2.1). */
export function resolveAudioUrl(baseUrl, audioUrl) {
  const u = String(audioUrl || '');
  if (!u) return '';
  if (/^https?:\/\//i.test(u)) return u;
  if (u.indexOf('/audio/') === 0) return baseUrl + '/tts' + u;
  if (u.charAt(0) === '/') return baseUrl + u;
  return u;
}

export function parseTtsData(data, baseUrl) {
  const audioUrl = data.audioUrl ? String(data.audioUrl) : '';
  return {
    audioUrl,
    fullUrl: resolveAudioUrl(baseUrl, audioUrl),
    duration: toNumberOrNull(data.duration),
    format: data.format ? String(data.format) : null,
    warnings: extractWarnings({ warnings: data.warnings }),
  };
}
