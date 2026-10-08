#!/usr/bin/env node
// Fails when types/index.d.ts drifts from the runtime: every runtime export needs a declaration
// and the reverse, class members, enum-like objects and the main data models must match.
// The same names must come out of src/index.js, dist/yugu-sdk.mjs and dist/yugu-sdk.umd.js.
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO = path.resolve(ROOT, '..');
const require = createRequire(import.meta.url);
const problems = [];

const runtime = await import(pathToFileURL(path.join(ROOT, 'src/index.js')).href);
const esm = await import(pathToFileURL(path.join(ROOT, 'dist/yugu-sdk.mjs')).href);
const umd = require(path.join(ROOT, 'dist/yugu-sdk.umd.js'));

const sorted = (xs) => [...new Set(xs)].sort();
function diff(label, have, want) {
  const a = new Set(have);
  const b = new Set(want);
  const missing = [...b].filter((x) => !a.has(x));
  const extra = [...a].filter((x) => !b.has(x));
  if (missing.length) problems.push(`${label}: missing ${missing.join(', ')}`);
  if (extra.length) problems.push(`${label}: not expected ${extra.join(', ')}`);
}

const runtimeNames = sorted(Object.keys(runtime));
diff('dist/yugu-sdk.mjs exports vs src/index.js', Object.keys(esm), runtimeNames);
diff('dist/yugu-sdk.umd.js exports vs src/index.js', Object.keys(umd), runtimeNames);
diff('default export keys vs named exports', Object.keys(runtime.default).concat('default'), runtimeNames);

// ---------------------------------------------------------------- declarations
const dts = path.join(ROOT, 'types/index.d.ts');
const program = ts.createProgram([dts], {
  strict: true,
  noEmit: true,
  target: ts.ScriptTarget.ES2020,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  lib: ['lib.es2020.d.ts', 'lib.dom.d.ts'],
  types: [],
  skipLibCheck: false,
});
const diags = ts.getPreEmitDiagnostics(program);
for (const d of diags) problems.push(`tsc: ${ts.flattenDiagnosticMessageText(d.messageText, '\n')}`);
const checker = program.getTypeChecker();
const sf = program.getSourceFile(dts);
const modSym = checker.getSymbolAtLocation(sf);
const declared = new Map();
for (const s of checker.getExportsOfModule(modSym)) {
  const target = s.flags & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(s) : s;
  declared.set(s.getName(), target);
}
const declaredValues = sorted([...declared].filter(([, s]) => s.flags & ts.SymbolFlags.Value).map(([n]) => n));
diff('types/index.d.ts value exports vs runtime exports', declaredValues, runtimeNames);

function propsOfType(type) {
  return sorted(checker.getPropertiesOfType(type).map((p) => p.getName()));
}
function typeOfValue(name) {
  const s = declared.get(name);
  return checker.getTypeOfSymbolAtLocation(s, sf);
}
function interfaceProps(name) {
  const s = declared.get(name);
  if (!s) {
    problems.push(`types/index.d.ts: no declaration ${name}`);
    return [];
  }
  return propsOfType(checker.getDeclaredTypeOfSymbol(s));
}
const publicKeys = (o) => Object.keys(o).filter((k) => !k.startsWith('_'));

// Enum-like objects and namespace objects: declared keys equal runtime keys.
for (const name of [
  'SessionState',
  'RecorderState',
  'LogLevel',
  'AudioPrecheck',
  'AudioBufferPolicy',
  'HeartbeatMode',
  'ErrorCategory',
  'WarningCode',
  'YuguErrors',
  'CLIENT_DEFAULTS',
  'DEFAULT_RETRY_POLICY',
  'DEFAULT_RECONNECT_POLICY',
  'ErrorCodes',
]) {
  diff(`${name} keys`, propsOfType(typeOfValue(name)), Object.keys(runtime[name]));
}
diff('default export (YuguSDK) keys', propsOfType(typeOfValue('default')), Object.keys(runtime.default));

// Classes: prototype members and statics.
function classMembers(name) {
  const s = declared.get(name);
  const decl = s.declarations.find((d) => ts.isClassDeclaration(d));
  const inst = [];
  const stat = [];
  for (const m of decl.members) {
    if (ts.isConstructorDeclaration(m) || !m.name) continue;
    const isStatic = (ts.getCombinedModifierFlags(m) & ts.ModifierFlags.Static) !== 0;
    const isPrivate = (ts.getCombinedModifierFlags(m) & ts.ModifierFlags.Private) !== 0;
    if (isPrivate) continue;
    (isStatic ? stat : inst).push(m.name.getText(sf));
  }
  return { inst: sorted(inst), stat: sorted(stat) };
}
function runtimeProto(cls) {
  return Object.getOwnPropertyNames(cls.prototype).filter((n) => n !== 'constructor' && !n.startsWith('_'));
}
function runtimeStatics(cls) {
  return Object.getOwnPropertyNames(cls).filter((n) => !['length', 'name', 'prototype'].includes(n) && !n.startsWith('_'));
}
for (const name of ['YuguClient', 'YuguStreamSession', 'YuguRecorder']) {
  const { inst, stat } = classMembers(name);
  diff(`${name} members`, inst, runtimeProto(runtime[name]));
  diff(`${name} static members`, stat, runtimeStatics(runtime[name]));
}
{
  const { inst } = classMembers('YuguError');
  const sample = new runtime.YuguError('x', { cause: 1 });
  diff('YuguError members', inst, [...publicKeys(sample), ...runtimeProto(runtime.YuguError)].filter((n) => n !== 'message'));
  for (const sub of [
    'NetworkException',
    'RequestTimeoutException',
    'AuthException',
    'PermissionException',
    'InvalidParameterException',
    'NotFoundException',
    'ConflictException',
    'RateLimitException',
    'QuotaExceededException',
    'ServerException',
    'AudioQualityException',
    'IllegalSessionStateException',
    'RequestCancelledException',
    'ProtocolViolationException',
  ]) {
    if (!(new runtime[sub]('x') instanceof runtime.YuguError)) problems.push(`${sub} does not extend YuguError at runtime`);
    const decl = declared.get(sub).declarations[0];
    const base = decl.heritageClauses && decl.heritageClauses[0].types[0].expression.getText(sf);
    if (base !== 'YuguError') problems.push(`${sub} is not declared as extending YuguError`);
  }
}

// Data models built by the runtime.
const fixtures = path.join(REPO, 'spec/fixtures/platform');
const load = (f) => JSON.parse(fs.readFileSync(path.join(fixtures, f), 'utf8'));
const evalResult = runtime.normalizeEvalResult(load('native_evaluate_sentence_zh.json'));
diff('EvalResult fields', interfaceProps('EvalResult'), Object.keys(evalResult));
diff('EvalDimensions fields', interfaceProps('EvalDimensions'), Object.keys(evalResult.dims));
const connected = runtime.normalizeEvalResult(load('native_connected_en.json')).connected;
diff('ConnectedScores fields', interfaceProps('ConnectedScores'), Object.keys(connected));
const open = runtime.normalizeEvalResult(load('native_open_zh.json')).open;
diff('OpenScores fields', interfaceProps('OpenScores'), Object.keys(open));
const { normalizeTtsResult } = await import(pathToFileURL(path.join(ROOT, 'src/result.js')).href);
diff('TtsResult fields', interfaceProps('TtsResult'), Object.keys(normalizeTtsResult(load('tts_generate.json'), {})));
const wav = fs.readFileSync(path.join(REPO, 'spec/fixtures/audio/zh_short.wav'));
const report = runtime.precheckAudio(new Uint8Array(wav));
diff('PrecheckReport fields', interfaceProps('PrecheckReport'), Object.keys(report));
diff('WavInfo fields', interfaceProps('WavInfo'), Object.keys(runtime.parseWav(new Uint8Array(wav))));
const silent = runtime.precheckAudio(new Uint8Array(fs.readFileSync(path.join(REPO, 'spec/fixtures/audio/silent.wav'))));
diff('LocalWarning fields', interfaceProps('LocalWarning'), Object.keys(silent.warnings[0]));
diff('AudioWarning fields', interfaceProps('AudioWarning'), Object.keys(runtime.normalizeEvalResult({ result: { warning: [1002] } }).warnings[0]));
diff('YuguError toJSON fields', propsOfType(checker.getReturnTypeOfSignature(checker.getSignaturesOfType(
  checker.getTypeOfSymbolAtLocation(checker.getPropertyOfType(checker.getDeclaredTypeOfSymbol(declared.get('YuguError')), 'toJSON'), sf),
  ts.SignatureKind.Call,
)[0])), Object.keys(new runtime.YuguError('x').toJSON()));

// SessionStats from a live session object (no network: the WebSocket stub never connects).
class StubSocket {
  constructor() {
    this.readyState = 0;
  }
  send() {}
  close() {}
}
const client = new runtime.YuguClient({ token: 't', baseUrl: 'http://127.0.0.1:9', WebSocket: StubSocket, logLevel: 'OFF' });
const session = client.streamEvaluate({ coreType: 'sentence', referenceText: 'x' }, { onResult() {}, onError() {} });
diff('SessionStats fields', interfaceProps('SessionStats'), Object.keys(session.getStats()));
session.cancel();
await client.close();

if (problems.length) {
  for (const p of problems) console.error(`check-types: ${p}`);
  console.error(`check-types failed: ${problems.length} problem(s)`);
  process.exit(1);
}
console.log(`check-types ok: ${runtimeNames.length} runtime exports declared, classes, enums and models in sync`);
