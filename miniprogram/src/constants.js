// Fixed values of the wire contract and the SDK identity.

export const SDK_NAME = 'yugu-miniprogram-sdk';
export const SDK_VERSION = '2.0.0';
export const USER_AGENT = SDK_NAME + '/' + SDK_VERSION;

export const DEFAULT_BASE_URL = 'https://open.shengzhiai.com';
export const DEFAULT_WS_BASE_URL = 'wss://open.shengzhiai.com';

/** coreType values of native REST and native WS evaluation. */
export const CORE_TYPES = Object.freeze(['word', 'sentence', 'passage', 'connected', 'open', 'alpha', 'pinyin']);

/** Shengtong style coreType paths of compat REST `POST /{coreType}` and compat WS `/{coreType}`. */
export const COMPAT_CORE_TYPES = Object.freeze([
  'word.eval', 'word.eval.pro', 'sent.eval', 'sent.eval.pro', 'para.eval', 'alpha.eval',
  'word.eval.cn', 'sent.eval.cn', 'para.eval.cn', 'pinyin',
]);

export const PATH_EVALUATE = '/api/v1/evaluate';
export const PATH_TTS = '/api/v1/tts/generate';
export const PATH_REPORT = '/api/v1/report/';
export const PATH_WS_NATIVE = '/api/v1/ws/evaluate';

/** Local limits shared with the platform (DESIGN 2.9, CONTRACT 1 and 9). */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
export const MAX_STREAM_BYTES = 10 * 1024 * 1024;
export const MAX_AUDIO_SECONDS = 300;
export const MIN_AUDIO_SECONDS = 1.0;
/** The platform closes WebSocket frames above 128 KB with 1009; audio goes out in frames of at most 1 s. */
export const MAX_AUDIO_FRAME_BYTES = 32000;
