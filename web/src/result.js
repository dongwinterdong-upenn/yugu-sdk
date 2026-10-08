// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.
// Result parsing for native REST, compat REST, native WS and compat WS (bare result JSON, no envelope).

import { WARNING_TABLE } from './error-table.js';

/** Native coreType values (CONTRACT 1). */
export const NATIVE_CORE_TYPES = Object.freeze(['word', 'sentence', 'passage', 'connected', 'open', 'alpha', 'pinyin']);

/** Shengtong compatible coreType paths (CONTRACT 3.2). */
export const COMPAT_CORE_TYPES = Object.freeze([
  'word.eval',
  'word.eval.pro',
  'sent.eval',
  'sent.eval.pro',
  'para.eval',
  'alpha.eval',
  'word.eval.cn',
  'sent.eval.cn',
  'para.eval.cn',
  'pinyin',
]);

/** Warning code to English server message, kept from 1.x. */
export const WARNING_MESSAGES = Object.freeze(
  Object.values(WARNING_TABLE).reduce((acc, e) => {
    acc[e.code] = e.message;
    return acc;
  }, {}),
);

function num(v) {
  if (v === null || v === undefined || v === '' || typeof v === 'boolean') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

const arr = (v) => (Array.isArray(v) ? v : []);
const obj = (v) => (v && typeof v === 'object' && !Array.isArray(v) ? v : null);

/** Server warnings as [{code, message}], from numbers, numeric strings or {code, message} objects. */
export function normalizeWarnings(...sources) {
  const out = [];
  const seen = new Set();
  for (const src of sources) {
    if (src == null) continue;
    for (const item of Array.isArray(src) ? src : [src]) {
      let code = null;
      let message = null;
      if (typeof item === 'number') code = item;
      else if (typeof item === 'string' && /^\d+$/.test(item)) code = Number(item);
      else if (item && typeof item === 'object' && item.code != null && Number.isFinite(Number(item.code))) {
        code = Number(item.code);
        if (typeof item.message === 'string') message = item.message;
      }
      if (code == null || seen.has(code)) continue;
      seen.add(code);
      const entry = WARNING_TABLE[code];
      out.push(Object.freeze({ code, message: message != null ? message : entry ? entry.message : '' }));
    }
  }
  return out;
}

const str = (v) => (typeof v === 'string' ? v : null);

/** Connected speech mode: no result.overall, the total is result.connected_overall. */
function connectedPart(r) {
  if (r.connected_overall == null && r.linking == null && !Array.isArray(r.boundaries)) return null;
  return {
    overall: num(r.connected_overall),
    linking: num(r.linking),
    rhythm: num(r.rhythm),
    elision: num(r.elision),
    reduction: num(r.reduction),
    nBoundaries: num(r.n_boundaries),
    boundaries: arr(r.boundaries),
    coverage: obj(r.coverage),
    metrics: obj(r.raw),
    report: obj(r.report),
  };
}

/** Open speaking mode: content, languageUse, delivery and feedback sub-objects. */
function openPart(r) {
  if (!obj(r.content) && !obj(r.delivery) && !obj(r.languageUse) && r.transcript == null) return null;
  return {
    taskType: str(r.taskType),
    transcript: str(r.transcript),
    hasSpeech: typeof r.hasSpeech === 'boolean' ? r.hasSpeech : null,
    content: obj(r.content),
    languageUse: obj(r.languageUse),
    delivery: obj(r.delivery),
    feedback: obj(r.feedback),
    audioQuality: obj(r.audioQuality),
    openTaskAudit: obj(r.openTaskAudit),
    aggregation: obj(r.aggregation),
    rubricVersion: str(r.rubricVersion),
  };
}

/**
 * Unified result. Works for native and compat responses and for WS result frames.
 * meta: {mode, coreType, idempotencyKey, replayed, localWarnings}
 * Every mode keeps its own fields in raw; overall falls back to connected_overall.
 */
export function normalizeEvalResult(raw, meta) {
  const m = meta || {};
  const o = obj(raw) || {};
  const r = obj(o.result) || {};
  const ys = obj(r.yuguScores);
  const ysReading = ys ? (obj(ys.reading_skill) ? ys.reading_skill.overall : ys.reading_skill) : null;
  const dims = {
    integrity: num(r.integrity),
    accuracy: num(r.accuracy != null ? r.accuracy : r.pronunciation),
    pronunciation: num(r.pronunciation),
    fluency: num(r.fluency),
    tone: num(r.tone),
    rhythm: num(r.rhythm),
    emotion: num(r.emotion != null ? r.emotion : ys ? ys.emotion : null),
    readingSkill: num(r.reading_skill != null ? r.reading_skill : ysReading),
  };
  const durationSource = r.numeric_duration != null ? r.numeric_duration : r.duration != null ? r.duration : r.duration_s;
  const asr = obj(o.asrText);
  const std = obj(o.standardAudio);
  return {
    recordId: o.recordId != null ? String(o.recordId) : null,
    eof: num(o.eof),
    overall: num(r.overall != null ? r.overall : r.connected_overall),
    dims,
    speed: num(r.speed),
    durationSeconds: num(durationSource),
    words: arr(r.words),
    sentences: arr(r.sentences),
    paragraphs: arr(r.paragraphs),
    connected: connectedPart(r),
    open: openPart(r),
    asrText: asr && typeof asr.text === 'string' ? asr : null,
    report: obj(o.report),
    standardAudio: std && typeof std.url === 'string' && std.url ? std : null,
    yuguScores: ys,
    compositeReport: obj(r.compositeReport),
    warnings: normalizeWarnings(r.warning, o.warnings),
    localWarnings: Array.isArray(m.localWarnings) ? m.localWarnings.slice() : [],
    coreType: m.coreType || str(r._coreType),
    language: str(r._language) || str(r.language),
    mode: m.mode || 'native',
    idempotencyKey: m.idempotencyKey || null,
    replayed: m.replayed === true || o.replayed === true,
    raw: o,
  };
}

/** TTS audio URL as a playable URL. /audio/... maps to {baseUrl}/tts/audio/... (CONTRACT 2.1). */
export function resolveTtsUrl(baseUrl, audioUrl) {
  if (!audioUrl) return '';
  if (/^https?:\/\//i.test(audioUrl)) return audioUrl;
  if (audioUrl.startsWith('/audio/')) return `${baseUrl}/tts${audioUrl}`;
  return audioUrl.startsWith('/') ? `${baseUrl}${audioUrl}` : `${baseUrl}/${audioUrl}`;
}

/** TTS result from the {code:0, data:{...}} envelope. */
export function normalizeTtsResult(raw, meta) {
  const m = meta || {};
  const o = obj(raw) || {};
  const d = obj(o.data) || {};
  const audioUrl = typeof d.audioUrl === 'string' ? d.audioUrl : '';
  return {
    audioUrl,
    absoluteUrl: resolveTtsUrl(m.baseUrl || '', audioUrl),
    duration: d.duration != null ? String(d.duration) : null,
    durationSeconds: num(d.duration),
    format: typeof d.format === 'string' ? d.format : null,
    warnings: arr(d.warnings),
    idempotencyKey: m.idempotencyKey || null,
    replayed: m.replayed === true,
    raw: o,
  };
}
