// 录音，整段评测，实时评测，展示结果。生命周期：onLoad 创建客户端与录音器，onUnload 全部释放。
var sdk = require('@shengzhiai/yugu-miniprogram-sdk');
var config = require('../../config.js');

var YuguClient = sdk.YuguClient;
var DIMENSION_NAMES = {
  pronunciation: '发音',
  fluency: '流利',
  integrity: '完整',
  tone: '声调',
  rhythm: '节奏',
  emotion: '情感'
};

function hasCredentials(auth) {
  return !!auth && (!!auth.token || (!!auth.appKey && !!auth.secretKey));
}

function scoreLevel(score) {
  if (typeof score !== 'number') return 'none';
  if (score >= 80) return 'good';
  if (score >= 60) return 'mid';
  return 'bad';
}

// EvalResult 转为页面数据。connected 与 open 两种题型的分项在各自的子对象里。
function toView(r) {
  var dims = [];
  Object.keys(DIMENSION_NAMES).forEach(function (k) {
    if (r.dims[k] !== null && r.dims[k] !== undefined) dims.push({ name: DIMENSION_NAMES[k], value: r.dims[k] });
  });
  if (r.connected) {
    dims.push({ name: '连读', value: r.connected.linking });
    dims.push({ name: '失爆', value: r.connected.elision });
    dims.push({ name: '弱读', value: r.connected.reduction });
  }
  if (r.open) {
    if (r.open.content) dims.push({ name: '内容', value: r.open.content.overall });
    if (r.open.languageUse) dims.push({ name: '语言', value: r.open.languageUse.overall });
    if (r.open.delivery) dims.push({ name: '表达', value: r.open.delivery.overall });
  }
  var words = r.words.map(function (w, i) {
    var score = w.scores ? w.scores.overall : null;
    return { key: i, text: w.word, pinyin: w.symbolpinyin || w.pinyin || '', score: score, level: scoreLevel(score) };
  });
  var warnings = r.warnings.concat(r.localWarnings).map(function (w) {
    return '[' + w.code + '] ' + w.message;
  });
  return {
    overall: r.overall,
    recordId: r.recordId,
    replayed: r.replayed,
    dims: dims,
    words: words,
    warnings: warnings,
    transcript: r.open ? r.open.transcript : ''
  };
}

Page({
  data: {
    configError: '',
    coreTypes: sdk.CORE_TYPES.slice(),
    coreTypeIndex: 1,
    languages: ['zh-CN', 'en-US', 'en-GB'],
    langIndex: 0,
    referenceText: '今天天气很好',
    refPinyin: '',
    recording: false,
    hasRecording: false,
    recordingInfo: '',
    streaming: false,
    sessionState: 'IDLE',
    busy: false,
    result: null,
    log: []
  },

  onLoad: function () {
    if (!hasCredentials(config.auth)) {
      this.setData({ configError: '请先在 config.js 填写 token，或者沙箱 appKey 与 secretKey' });
      return;
    }
    this.client = new YuguClient({
      auth: config.auth,
      baseUrl: config.baseUrl,
      wsBaseUrl: config.wsBaseUrl,
      logLevel: 'INFO'
    });
    this.recorder = this.client.createRecorder();
    this.recorder.setListener({
      onError: this.onRecorderError.bind(this)
    });
    this.lastRecording = null;
    this.session = null;
  },

  onUnload: function () {
    // 释放顺序：会话，录音器，客户端。三者都可以重复调用。
    if (this.session) this.session.close();
    if (this.recorder) this.recorder.release();
    if (this.client) this.client.close();
  },

  onCoreTypeChange: function (e) {
    this.setData({ coreTypeIndex: Number(e.detail.value) });
  },

  onLangChange: function (e) {
    this.setData({ langIndex: Number(e.detail.value) });
  },

  onRefInput: function (e) {
    this.setData({ referenceText: e.detail.value });
  },

  onPinyinInput: function (e) {
    this.setData({ refPinyin: e.detail.value });
  },

  params: function () {
    var p = {
      coreType: this.data.coreTypes[this.data.coreTypeIndex],
      referenceText: this.data.referenceText,
      language: this.data.languages[this.data.langIndex],
      includeReport: true
    };
    if (this.data.refPinyin) p.refPinyin = this.data.refPinyin;
    return p;
  },

  addLog: function (text) {
    var log = [text].concat(this.data.log).slice(0, 8);
    this.setData({ log: log });
  },

  // 录音：开始与停止。停止后的结果可以直接作为 evaluate 的 audio。
  onRecordTap: function () {
    var self = this;
    if (!this.client || this.data.streaming) return;
    if (this.data.recording) {
      this.recorder.stop().then(function (res) {
        self.lastRecording = res;
        self.setData({ recording: false, hasRecording: true, recordingInfo: (res.duration / 1000).toFixed(1) + ' 秒，' + res.fileSize + ' 字节' });
      }).catch(function (err) {
        self.setData({ recording: false });
        self.showError(err);
      });
      return;
    }
    this.setData({ recording: true, result: null });
    this.recorder.start();
  },

  // 整段评测：上传录音文件，SDK 自动带幂等键并按策略重试。
  onEvaluateTap: function () {
    var self = this;
    if (!this.lastRecording) {
      wx.showToast({ title: '请先录音', icon: 'none' });
      return;
    }
    var p = this.params();
    p.audio = this.lastRecording;
    this.setData({ busy: true });
    this.client.evaluate(p).then(function (r) {
      self.showResult(r);
    }).catch(function (err) {
      self.showError(err);
    }).then(function () {
      self.setData({ busy: false });
    });
  },

  // 实时评测：录音帧直接推入会话，再点一次停止录音并结束会话。
  onStreamTap: function () {
    var self = this;
    if (!this.client || this.data.recording) return;
    if (this.data.streaming) {
      this.recorder.stop().catch(function () {});
      return;
    }
    var session = this.client.streamEvaluate(this.params(), {
      onStateChanged: function (oldState, newState) {
        self.setData({ sessionState: newState });
      },
      onReconnecting: function (attempt, delayMs, cause) {
        self.addLog('第 ' + attempt + ' 次重连，' + delayMs + ' 毫秒后：' + cause.message);
      },
      onReconnected: function (attempt) {
        self.addLog('第 ' + attempt + ' 次重连成功');
      },
      onWarning: function (w) {
        self.addLog('预检 [' + w.code + '] ' + w.message);
      },
      onResult: function (r) {
        self.showResult(r);
      },
      onError: function (err) {
        self.showError(err);
      },
      onClosed: function () {
        self.setData({ streaming: false });
      }
    });
    this.session = session;
    this.recorder.pipeTo(session);
    this.recorder.start();
    this.setData({ streaming: true, result: null });
  },

  onRecorderError: function (err) {
    this.setData({ recording: false });
    this.showError(err);
  },

  showResult: function (r) {
    this.setData({ result: toView(r) });
    this.addLog('评测完成 ' + r.recordId + (r.replayed ? '（重放）' : ''));
  },

  showError: function (err) {
    var text = (err.name || 'Error') + ' ' + (err.code || '') + '：' + err.message;
    if (err.code === 90201) text = '没有录音权限，请在设置里打开麦克风';
    this.addLog(text);
    wx.showModal({ title: '出错了', content: text, showCancel: false });
  }
});
