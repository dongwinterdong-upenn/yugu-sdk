// Public entry of @shengzhiai/yugu-miniprogram-sdk. Every name exported here is declared in
// types/index.d.ts; test/unit/dist.test.mjs keeps the two lists equal.
export { YuguClient } from './client.js';
export { StreamSession, SessionState, AudioBufferPolicy } from './session.js';
export { YuguRecorder, RecorderState } from './recorder.js';
export {
  YuguError,
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
  ErrorCategory,
  WarningCode,
  YuguErrors,
  isRetryable,
  fromCode,
  fromWarningCode,
  fromHttpResponse,
} from './errors.js';
export { ERROR_TABLE, WARNING_TABLE, LOCAL_TABLE, HTTP_FALLBACK, RETRYABLE_HTTP, ErrorCodes } from './error-table.js';
export { LogLevel } from './logger.js';
export { AudioPrecheckMode, precheckAudio } from './precheck.js';
export {
  DEFAULT_RETRY_POLICY, DEFAULT_RECONNECT_POLICY, computeBackoffDelay, computeRetryDelay, parseRetryAfter,
} from './retry.js';
export { generateIdempotencyKey } from './idempotency.js';
export { signParams, buildSignPayload } from './signer.js';
export { parseEvalResult } from './result.js';
export { createAbortController } from './abort.js';
export { SDK_VERSION, USER_AGENT, CORE_TYPES, COMPAT_CORE_TYPES } from './constants.js';
