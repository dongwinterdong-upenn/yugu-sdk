#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Integration tests of the STKouyuEngine C core against the mock platform.

Starts tools/mock-server/server.mjs on a free port, drives it_driver through the scenarios of
DESIGN 10 (V-02 replay and billing, V-03 retry with the same Idempotency-Key, no retry on 400)
plus the Shengtong compat rules (errIds, envelope, no network I/O for local errors) and the
realtime_feedback rounds of 2.1.0 over the compat WebSocket (signed handshake, parameter frame,
eof 0 progress with incremental words, REST fallback before end, reconnect and replay after end,
heartbeat, result timeout), asserts on the driver output and on the mock's /__mock/log and
/__mock/billing, writes a JUnit report and stops the mock server in every case.

2.1.1: the default stream round without realtime_feedback (no progress asked for, REST fallback
with the same tokenId), timing=1 and "timing":1 only at log level INFO, and the per-phase summary
line. The Server-Timing header and the platform timing object are not in the shared mock, so
those scenarios run against timing_server.py next to this file (BRIEF 2.1.1 rule 5).

2.2.0: the 60003 text names the coreType (KYTestEngine and skegn resolution), the summary line
carries recordId, and TtsClient.tts and ReportClient.getReport run against the mock's
/api/v1/tts/generate and /api/v1/report/{recordId}: signature checked with an independent HMAC,
Idempotency-Key reused across retries, no key on report lookups, retries and errors, and
waitForFinalMs over a recordId containing "pending".

2.3.0: waitForFinalMs looks up again through reportStatus partial (the AI summary and suggestions are
ready, the per-dimension comments are still being written) and stops at final, against
timing_server.py, which answers a configured sequence of statuses (the shared mock has no partial).
Pipelined evaluation lives in the Objective-C engine; its busy rule and log lines are C core unit
tests (test_pipeline.c), the engine itself is tested by XCTest on macOS.

  run_it.py --driver build/it_driver --mock ../tools/mock-server/server.mjs --spec ../spec
            [--junit it.xml] [--log it.log]
"""
import argparse
import base64
import copy
import hashlib
import hmac
import json
import os
import re
import signal
import subprocess
import sys
import time
import traceback
import urllib.request

ARGS = None
PORT = None
TIMING_PORT = None
LOG_LINES = []
HERE = os.path.dirname(os.path.abspath(__file__))


def log(msg):
    LOG_LINES.append(msg)
    print(msg, flush=True)


def http(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (PORT, path), data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode() or "null")


def reset():
    http("POST", "/__mock/reset")


def faults(*items):
    http("POST", "/__mock/faults", {"faults": [{"match": m, "fault": f} for m, f in items]})


def mock_log():
    return http("GET", "/__mock/log")


def billing():
    return http("GET", "/__mock/billing")


def timing_server(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (TIMING_PORT, path), data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode() or "null")


def driver(*extra, port=None):
    cmd = [ARGS.driver, "--port", str(port or PORT)] + [str(x) for x in extra]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise AssertionError("driver exit %d: %s" % (p.returncode, p.stderr))
    lines = [l for l in p.stdout.splitlines() if l.strip()]
    if len(lines) != 1:
        raise AssertionError("driver printed %d lines: %r" % (len(lines), p.stdout))
    out = json.loads(lines[0])
    out["_stderr"] = p.stderr
    return out


def audio(name):
    return os.path.join(ARGS.spec, "fixtures", "audio", name)


def fixture_result(name):
    with open(os.path.join(ARGS.spec, "fixtures", "platform", name), encoding="utf-8") as f:
        return json.load(f)["result"]


def aligned(result):
    """Independent reference of the DESIGN 6 result-shape alignment, applied to a platform result."""
    r = copy.deepcopy(result)
    sentences = r.get("sentences") if isinstance(r, dict) else None
    for s in sentences if isinstance(sentences, list) else []:
        details = s.get("details") if isinstance(s, dict) else None
        for d in details if isinstance(details, list) else []:
            if not isinstance(d, dict) or "overall" in d or not isinstance(d.get("scores"), dict):
                continue
            if "overall" in d["scores"]:
                d["overall"] = d["scores"]["overall"]
            if "pronunciation" not in d and "pronunciation" in d["scores"]:
                d["pronunciation"] = d["scores"]["pronunciation"]
    return r


class Check:
    def __init__(self):
        self.failures = []
        self.count = 0

    def __call__(self, cond, what):
        self.count += 1
        if not cond:
            self.failures.append(what)


DT = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3}$")


def entries_for(token):
    return [e for e in mock_log() if e.get("idempotencyKey") == token]


# ------------------------------------------------------------------ scenarios


def s_success_envelope(c):
    reset()
    r = driver("--audio", audio("zh_short.wav"))
    j = r["json"]
    c(r["outcome"] == "result", "outcome result, got %r" % r)
    c(r["attempts"] == 1, "one attempt")
    c(re.fullmatch(r"[0-9a-f]{32}", r["tokenId"]) is not None, "tokenId is 32 lowercase hex")
    c(j.get("tokenId") == r["tokenId"], "envelope tokenId")
    c(str(j.get("recordId", "")).startswith("eval_"), "recordId from the platform")
    c(j.get("applicationId") == "mock-app-key", "applicationId is the appKey")
    c(j.get("userId") == "it-user", "userId")
    c(j.get("refText") == "今天天气很好", "refText")
    c(j.get("eof") == 1, "eof 1")
    c(DT.match(j.get("dtLastResponse", "")) is not None, "dtLastResponse format")
    c(j.get("result") == aligned(fixture_result("compat_sent.eval.cn.json")),
      "result as the platform sent it, plus details[].overall and pronunciation")
    c(j.get("result") != fixture_result("compat_sent.eval.cn.json"), "the fixture has details to align")
    c("params" not in j and "audioUrl" not in j, "no params or audioUrl by default")
    c(list(j.keys())[:7] == ["tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse"],
      "envelope key order %r" % list(j.keys()))
    c(list(j.keys()) == ["tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse", "result"],
      "no other members")
    entries = mock_log()
    c(len(entries) == 1, "one request reached the platform")
    if entries:
        e = entries[0]
        c(e["path"] == "/sent.eval.cn", "path /{coreType}")
        c(e["idempotencyKey"] == r["tokenId"], "Idempotency-Key is the tokenId")
        c(e.get("fields") == {"refText": "今天天气很好", "phoneme_output": "1"}, "form fields %r" % e.get("fields"))
        c(e["contentType"].startswith("multipart/form-data; boundary=----YuguSTCompat"), "multipart content type")
        c(e["userAgent"] == "yugu-ios-stcompat-sdk/2.3.0", "User-Agent %r" % e["userAgent"])
    b = billing()
    c(b["billed"] == 1 and b["byKey"].get(r["tokenId"]) == 1, "billed once %r" % b)


def s_word_params_custom(c):
    reset()
    r = driver("--core-type", "word.eval", "--ref-text", "apple", "--audio", audio("en_apple.wav"), "--get-param",
               "--scale", "100", "--custom", "language=en-US")
    j = r["json"]
    c(r["outcome"] == "result", "outcome result %r" % r)
    c(j.get("result") == aligned(fixture_result("compat_word.eval.json")) == fixture_result("compat_word.eval.json"),
      "word.eval result unchanged (no details)")
    p = j.get("params", {})
    c(p.get("app", {}).get("applicationId") == "mock-app-key", "params.app.applicationId")
    c(re.fullmatch(r"\d{10}", p.get("app", {}).get("timestamp", "")) is not None, "params.app.timestamp in seconds")
    c(p.get("audio", {}).get("sampleRate") == 16000, "params.audio.sampleRate")
    req = p.get("request", {})
    c(req.get("coreType") == "word.eval" and req.get("refText") == "apple" and req.get("scale") == "100",
      "params.request %r" % req)
    c(req.get("tokenId") == r["tokenId"], "params.request.tokenId")
    e = entries_for(r["tokenId"])
    c(len(e) == 1 and e[0].get("fields") == {"refText": "apple", "scale": "100", "phoneme_output": "1",
                                             "language": "en-US"}, "signed form fields %r" % (e and e[0].get("fields")))


def s_attach_audio_url(c):
    """attachAudioUrl=1 goes out as a signed form field. Like the platform, the mock then answers with a
    top-level audioUrl; the envelope carries that link unchanged and the link downloads the uploaded audio."""
    reset()
    r = driver("--audio", audio("zh_short.wav"), "--attach-audio-url")
    e = entries_for(r["tokenId"])
    c(r["outcome"] == "result", "outcome result %r" % r["json"].get("errId"))
    c(e and e[0].get("fields", {}).get("attachAudioUrl") == "1", "attachAudioUrl=1 sent %r" % (e and e[0].get("fields")))
    url = r["json"].get("audioUrl") or ""
    c(re.search(r"/rec/[0-9]{8}/eval_[A-Za-z0-9_]+-[0-9a-f]{32}[.]wav$", url) is not None, "audioUrl from the mock %r" % url)
    if url:
        with urllib.request.urlopen(url, timeout=10) as resp:
            body = resp.read()
        with open(audio("zh_short.wav"), "rb") as f:
            c(body == f.read(), "audioUrl downloads the uploaded audio")


def s_retry_500_same_key(c):
    reset()
    faults(("/sent.eval.cn", "status:500"))
    r = driver("--audio", audio("zh_short.wav"))
    c(r["outcome"] == "result" and r["attempts"] == 2, "500 then success in 2 attempts %r" % r)
    e = entries_for(r["tokenId"])
    c(len(e) == 2, "two requests with the same Idempotency-Key")
    c(e and e[0].get("fault") == "status:500", "first attempt got the fault")
    b = billing()
    c(b["byKey"].get(r["tokenId"]) == 1 and b["billed"] == 1, "billed once %r" % b)


def s_retry_429_retry_after(c):
    reset()
    faults(("/sent.eval.cn", "status:429:code=42901:retryAfter=1"))
    r = driver("--audio", audio("zh_short.wav"))
    c(r["outcome"] == "result" and r["attempts"] == 2, "429 then success %r" % r)
    c(r["elapsedMs"] >= 1000, "waited for Retry-After 1 s, elapsed %d ms" % r["elapsedMs"])
    c(len(entries_for(r["tokenId"])) == 2, "same key on both attempts")
    c(billing()["byKey"].get(r["tokenId"]) == 1, "billed once")


def s_timeout_then_replay(c):
    """V-02: three submissions with one key, one hits an injected read timeout, billing 1."""
    reset()
    faults(("/sent.eval.cn", "delay:1500"))
    r = driver("--audio", audio("zh_short.wav"), "--read-timeout-ms", "700")
    tok = r["tokenId"]
    c(r["outcome"] == "result" and r["attempts"] == 2, "timeout then success %r" % r)
    c("read timeout" in r["_stderr"], "first attempt ended in a read timeout")
    time.sleep(2.0)  # the delayed first request finishes on the server and is answered from the replay store
    r3 = driver("--audio", audio("zh_short.wav"), "--token", tok)
    c(r3["outcome"] == "result" and r3["replayed"] == 1, "third submission replayed %r" % r3)
    c(r3["json"].get("recordId") == r["json"].get("recordId"), "same recordId on replay")
    e = entries_for(tok)
    c(len(e) == 3, "three submissions reached the platform, got %d" % len(e))
    c(sum(1 for x in e if x.get("replayed")) == 2, "two of them replayed %r" % [x.get("replayed") for x in e])
    b = billing()
    c(b["byKey"].get(tok) == 1 and b["billed"] == 1, "billing 1 %r" % b)


def s_slow_inflight_replay(c):
    """The first attempt is registered and still processing when the retry with the same key arrives."""
    reset()
    faults(("/sent.eval.cn", "slow:1500"))
    # attempt 1 times out at 1000 ms while the platform works until about 1550 ms; the retry arrives
    # at 1140 to 1260 ms (200 ms back-off, 30 % jitter), still in flight, and has 1000 ms for the
    # replay. With 700 ms the retry could time out before the replay when the jitter was short.
    r = driver("--audio", audio("zh_short.wav"), "--read-timeout-ms", "1000")
    tok = r["tokenId"]
    c(r["outcome"] == "result" and r["attempts"] == 2, "timeout while the platform works, then success %r" % r)
    c(r["replayed"] == 1, "the retry was answered from the first attempt (Idempotency-Replayed)")
    time.sleep(0.3)
    e = entries_for(tok)
    c(len(e) == 2 and e[1].get("replayed") is True, "second request replayed %r" % [x.get("replayed") for x in e])
    b = billing()
    c(b["byKey"].get(tok) == 1 and b["billed"] == 1, "billed once %r" % b)


def s_inflight_conflict_40901(c):
    """Retry still in flight after the platform waited: 409 40901 with Retry-After, retried with the same key."""
    reset()
    faults(("/sent.eval.cn", "slow:9000"))
    r = driver("--audio", audio("zh_short.wav"), "--read-timeout-ms", "3500")
    tok = r["tokenId"]
    c(r["outcome"] == "result" and r["attempts"] == 3, "timeout, 40901, then the replayed result %r" % r)
    c("code=40901" in r["_stderr"] or "biz=40901" in r["_stderr"], "the second attempt got 409 40901")
    c(r["replayed"] == 1, "final answer replayed")
    time.sleep(0.3)
    c(len(entries_for(tok)) == 3, "three requests, one key")
    b = billing()
    c(b["byKey"].get(tok) == 1 and b["billed"] == 1, "billed once %r" % b)


def s_nonce_reuse_rejected(c):
    """Every request must carry a fresh X-Nonce: a nonce seen within 300 s is answered 401 2003."""
    reset()
    nonce = "0123456789abcdef"
    r = driver("--audio", audio("zh_short.wav"), "--fixed-nonce", nonce)
    c(r["outcome"] == "result", "first use of the nonce is accepted %r" % r["json"].get("errId"))
    r = driver("--audio", audio("zh_short.wav"), "--fixed-nonce", nonce)
    c(r["outcome"] == "error" and r["json"].get("errId") == 2003 and r["attempts"] == 1,
      "reused nonce rejected with 2003, not retried %r" % r["json"])
    c(r["json"].get("error") == "重复的请求", "platform message kept %r" % r["json"].get("error"))
    # the real path draws a new nonce for every attempt, retries included
    faults(("/sent.eval.cn", "status:503"), ("/sent.eval.cn", "status:503"))
    r = driver("--audio", audio("zh_short.wav"))
    c(r["outcome"] == "result" and r["attempts"] == 3, "fresh nonce on each of 3 attempts %r" % r)


def s_no_retry_400(c):
    reset()
    faults(("/sent.eval.cn", "status:400:code=40001"))
    r = driver("--audio", audio("zh_short.wav"))
    j = r["json"]
    c(r["outcome"] == "error" and r["attempts"] == 1, "400 is not retried %r" % r)
    c(j.get("errId") == 40001, "errId is the platform code")
    c(list(j.keys()) == ["tokenId", "errId", "error", "eof", "applicationId"], "error JSON keys %r" % list(j.keys()))
    c(j.get("eof") == 1 and j.get("applicationId") == "mock-app-key" and j.get("tokenId") == r["tokenId"],
      "error JSON fields")
    c(len(entries_for(r["tokenId"])) == 1, "one request")
    c(billing()["billed"] == 0, "nothing billed")


def s_retries_exhausted(c):
    reset()
    faults(("/sent.eval.cn", "status:503"), ("/sent.eval.cn", "status:503"), ("/sent.eval.cn", "status:503"))
    r = driver("--audio", audio("zh_short.wav"))
    c(r["outcome"] == "error" and r["json"].get("errId") == 20009, "errId 20009 after retries %r" % r)
    c(r["json"].get("error", "").endswith(": HTTP 503 code=50000 mock fault 503 (attempts 3)"),
      "20009 message %r" % r["json"].get("error"))
    c(r["attempts"] == 3, "3 attempts")
    c(len(entries_for(r["tokenId"])) == 3, "same key on all attempts")
    c(billing()["billed"] == 0, "nothing billed")
    # 2.2.0: an error has no record
    c((r.get("timing") or "").startswith("timing tokenId=%s recordId=- mode=rest " % r["tokenId"]) and
      " errId=20009" in r.get("timing", ""), "summary line of the error %r" % r.get("timing"))


def s_auto_retry(c):
    reset()
    for _ in range(3):
        faults(("/sent.eval.cn", "status:500"))
    r = driver("--audio", audio("zh_short.wav"), "--auto-retry")
    c(r["outcome"] == "result" and r["attempts"] == 4, "autoRetry resubmits after errId 20009 %r" % r)
    c(len(entries_for(r["tokenId"])) == 4, "4 requests, one key")
    c(billing()["byKey"].get(r["tokenId"]) == 1, "billed once")


def s_auto_retry_custom_errid(c):
    reset()
    faults(("/sent.eval.cn", "status:400:code=40001"), ("/sent.eval.cn", "status:400:code=40001"))
    r = driver("--audio", audio("zh_short.wav"), "--auto-retry", "--err-id", "40001")
    c(r["outcome"] == "result" and r["attempts"] == 3, "errIds [40001] resubmits twice %r" % r)
    reset()
    faults(("/sent.eval.cn", "status:400:code=40001"))
    r = driver("--audio", audio("zh_short.wav"), "--auto-retry")
    c(r["outcome"] == "error" and r["attempts"] == 1, "default errIds do not resubmit 40001 %r" % r)


def s_drop_then_success(c):
    reset()
    faults(("/sent.eval.cn", "drop"))
    r = driver("--audio", audio("zh_short.wav"))
    c(r["outcome"] == "result" and r["attempts"] == 2, "connection drop is retried %r" % r)
    c(billing()["byKey"].get(r["tokenId"]) == 1, "billed once")


def s_hang_then_success(c):
    reset()
    faults(("/sent.eval.cn", "hang"))
    r = driver("--audio", audio("zh_short.wav"), "--read-timeout-ms", "500")
    c(r["outcome"] == "result" and r["attempts"] == 2, "silent server is retried after the read timeout %r" % r)
    c(billing()["billed"] == 1, "billed once")


def s_fastapi_detail(c):
    reset()
    for _ in range(3):
        faults(("/sent.eval.cn", "status:502:detail=upstream%20busy"))
    r = driver("--audio", audio("zh_short.wav"))
    c(r["outcome"] == "error" and r["json"].get("errId") == 20009, "502 detail body retried then 20009 %r" % r)
    c(r["json"].get("error") == "网络或服务端临时故障，可重评，autoRetry 默认重评此码: HTTP 502 upstream busy (attempts 3)",
      "20009 message carries status, detail text and attempts: %r" % r["json"].get("error"))


def s_auth_errors(c):
    reset()
    r = driver("--audio", audio("zh_short.wav"), "--secret", "wrong-secret")
    c(r["outcome"] == "error" and r["json"].get("errId") == 2003 and r["attempts"] == 1, "bad signature 2003 %r" % r)
    c(r["json"].get("error") == "签名验证失败", "platform message")
    r = driver("--audio", audio("zh_short.wav"), "--app-key", "no-such-key")
    c(r["outcome"] == "error" and r["json"].get("errId") == 2010, "unknown appKey 2010 %r" % r)
    c(billing()["billed"] == 0, "nothing billed")


UNSUPPORTED = "不支持的评测内核："  # 2.2.0 shared decision 1, full-width colon U+FF1A


def s_local_errors_no_network(c):
    reset()
    short = audio("zh_short.wav")
    cases = [
        (("--core-type", "open.eval", "--audio", short), 60003, UNSUPPORTED + "open.eval"),
        (("--core-type", "align.eval", "--audio", short), 60003, UNSUPPORTED + "align.eval"),
        (("--core-type", "my.custom.core", "--audio", short), 60003, UNSUPPORTED + "my.custom.core"),
        # the requested coreType, trimmed
        (("--core-type", "  sent.eval.fr \t", "--audio", short), 60003, UNSUPPORTED + "sent.eval.fr"),
        # KYTestEngine: a blank coreTypeNS names the KYTestType, by its Shengtong string or its value
        (("--core-type", "", "--core-type-enum", "3", "--audio", short), 60003, UNSUPPORTED + "open.eval"),
        (("--core-type", " ", "--core-type-enum", "19", "--audio", short), 60003, UNSUPPORTED + "word.eval.jp"),
        (("--core-type", "", "--core-type-enum", "22", "--audio", short), 60003, UNSUPPORTED + "KYTestType 22"),
        # skegn_start: a blank or missing request.coreType is not a coreType
        (("--skegn", "--core-type", "  ", "--audio", short), 60003, UNSUPPORTED + "未指定"),
        (("--skegn", "--core-type", "", "--audio", short), 60003, UNSUPPORTED + "未指定"),
        (("--skegn", "--core-type", " open.eval ", "--audio", short), 60003, UNSUPPORTED + "open.eval"),
        (("--ref-text", "", "--audio", short), 60006, "refText 为空"),
        (("--audio-synth-ms", "500",), 60005, "音频短于 1 秒"),
        (("--audio", "/nonexistent/audio.wav"), 60001, "音频文件不存在或不可读: /nonexistent/audio.wav"),
        (("--audio", "-"), 60002, "音频为空"),
    ]
    for args, want, message in cases:
        r = driver(*args)
        j = r["json"]
        c(r["outcome"] == "error" and j.get("errId") == want and r["attempts"] == 0,
          "%r -> errId %d, got %r" % (args, want, j))
        c(j.get("error") == message, "%r: errId %d message %r" % (args, want, j.get("error")))
        c(list(j.keys()) == ["tokenId", "errId", "error", "eof", "applicationId"] and j.get("eof") == 1 and
          j.get("applicationId") == "mock-app-key", "the rest of the error JSON unchanged %r" % j)
    r = driver("--core-type", "sent.eval", "--ref-text", "", "--ref-pinyin", "ni3 hao3", "--audio", audio("zh_short.wav"))
    c(r["json"].get("errId") == 60006, "refPinyin does not replace refText for an English kernel")
    c(mock_log() == [], "no request reached the platform for local errors")
    # a supported coreType resolves as before: skegn and KYTestEngine alike
    r = driver("--skegn", "--core-type", " sent.eval.cn ", "--audio", short)
    c(r["outcome"] == "result", "skegn resolution of a supported coreType %r" % r["json"].get("errId"))
    r = driver("--core-type", "", "--core-type-enum", "10", "--audio", short)
    c(r["outcome"] == "result" and [e["path"] for e in entries_for(r["tokenId"])] == ["/sent.eval.cn"],
      "KYTestType_Sentence_Cn without coreTypeNS goes to /sent.eval.cn")


def s_pinyin(c):
    reset()
    r = driver("--core-type", "pinyin", "--ref-text", "重庆", "--audio", audio("zh_short.wav"))
    c(r["outcome"] == "error" and r["json"].get("errId") == 40001 and "refPinyin" in r["json"].get("error", ""),
      "pinyin without refPinyin answered 40001 %r" % r["json"])
    c(r["attempts"] == 1, "not retried")
    r = driver("--core-type", "pinyin", "--ref-text", "", "--ref-pinyin", "chong2 qing4", "--audio",
               audio("zh_short.wav"))
    c(r["outcome"] == "result", "pinyin with refPinyin evaluates %r" % r)


def s_fixture_passthrough(c):
    reset()
    for core, fx, wav in (("para.eval.cn", "compat_para.eval.cn.json", "zh_short.wav"),
                          ("sent.eval", "compat_sent.eval.json", "en_apple.wav")):
        r = driver("--core-type", core, "--ref-text", "x", "--audio", audio(wav))
        c(r["outcome"] == "result" and r["json"].get("result") == aligned(fixture_result(fx)),
          "%s result as sent plus the details alignment" % core)
    for core in ("word.eval.pro", "sent.eval.pro", "word.eval.cn", "alpha.eval"):
        r = driver("--core-type", core, "--ref-text", "a", "--audio", audio("en_apple.wav"))
        c(r["outcome"] == "result", "%s evaluates" % core)


def s_paragraph_word_details(c):
    """DESIGN 6: para.* always sends paragraph_need_word_score=1 and details carry overall."""
    reset()
    r = driver("--core-type", "para.eval.cn", "--audio", audio("zh_short.wav"))
    e = entries_for(r["tokenId"])
    c(r["outcome"] == "result", "para.eval.cn evaluates %r" % r["json"].get("errId"))
    c(e and e[0].get("fields", {}).get("paragraph_need_word_score") == "1",
      "paragraph_need_word_score=1 without isParagraphNeedWordScore %r" % (e and e[0].get("fields")))
    c(r["json"].get("result") == fixture_result("compat_para.eval.cn.json"), "no details in this fixture: unchanged")
    r = driver("--core-type", "para.eval", "--ref-text", "How are you", "--custom", "paragraph_need_word_score=0",
               "--audio", audio("en_apple.wav"))
    e = entries_for(r["tokenId"])
    c(e and e[0].get("fields", {}).get("paragraph_need_word_score") == "1", "customParams cannot switch it off")
    details = [d for s in r["json"].get("result", {}).get("sentences", []) for d in s.get("details", [])]
    c(details and all(d.get("overall") == d["scores"]["overall"] and
                      d.get("pronunciation") == d["scores"]["pronunciation"] for d in details),
      "every details item carries overall and pronunciation from scores")
    r = driver("--core-type", "sent.eval.cn", "--audio", audio("zh_short.wav"))
    e = entries_for(r["tokenId"])
    c(e and "paragraph_need_word_score" not in e[0].get("fields", {}), "other kernels unchanged")


# ------------------------------------------------------------------ realtime_feedback (2.1.0)

ZH_TEXT = "我是中国人，我是中国人。我们都是中国人。中华民族是一家人。"
EN_TEXT = "Nice to see you again. I am a student, and I like reading books every day."
PROGRESS_KEYS = ["tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse", "result"]


def s_open_question(c):
    """2.1.2: open.eval.cn goes to its own path with refText and qType as set, refPinyin cannot replace its
    refText, the result passes through, and a realtime_feedback round delivers no reading progress."""
    reset()
    r = driver("--core-type", "open.eval.cn", "--ref-text", "我是学生|我不是学生", "--custom", "qType=6",
               "--audio", audio("zh_short.wav"))
    e = entries_for(r["tokenId"])
    c(r["outcome"] == "result" and r["json"].get("result") == fixture_result("compat_open.eval.cn.json"),
      "open.eval.cn result as sent %r" % r["json"].get("errId"))
    c(e and e[0].get("path") == "/open.eval.cn", "path /open.eval.cn %r" % (e and e[0].get("path")))
    f = (e and e[0].get("fields")) or {}
    c(f.get("refText") == "我是学生|我不是学生" and f.get("qType") == "6", "refText and qType sent %r" % f)
    c("paragraph_need_word_score" not in f, "no paragraph flag for an open question")
    r = driver("--core-type", "open.eval.cn", "--ref-text", "", "--ref-pinyin", "wo3 shi4", "--audio", audio("zh_short.wav"))
    c(r["json"].get("errId") == 60006 and r["attempts"] == 0, "refPinyin does not replace the refText of an open question")
    r = driver("--realtime", "--core-type", "open.eval.cn", "--audio", audio("zh_progress.wav"),
               "--ref-text", "请说一说你周末做了什么。", "--chunk-bytes", "6400", "--pace-ms", "10")
    c(r["outcome"] == "result" and r.get("mode") == "ws", "realtime round over the WebSocket %r" % r.get("mode"))
    c(r["progress"] == [], "no reading progress for an open question")
    c(r["json"].get("result") == fixture_result("compat_open.eval.cn.json"), "final result as sent")


def units_of(text, chinese):
    """Reading units of CONTRACT 5.5: Chinese by character without punctuation, English by word."""
    if chinese:
        return [ch for ch in text if not re.match(r"[\s\W_]", ch)]
    return [w for w in (re.sub(r"^\W+|\W+$", "", t) for t in text.split()) if w]


def pcm_len(name):
    """Bytes of the data chunk of a PCM WAV fixture."""
    with open(audio(name), "rb") as f:
        b = f.read()
    pos = 12
    while pos + 8 <= len(b):
        size = int.from_bytes(b[pos + 4:pos + 8], "little")
        if b[pos:pos + 4] == b"data":
            return size
        pos += 8 + size + (size & 1)
    raise AssertionError("no data chunk in " + name)


def realtime(*extra):
    return driver("--realtime", "--audio", audio("zh_progress.wav"), "--ref-text", ZH_TEXT, *extra)


def check_progress(c, r, units, ref_text=ZH_TEXT):
    """Every eof 0 envelope: the key order, recordId "", the result passed through with its words.
    The words of all envelopes together are the units read so far, each exactly once and in order."""
    seen = []
    last = 0
    for p in r["progress"]:
        c(list(p.keys()) == PROGRESS_KEYS, "eof 0 envelope keys %r" % list(p.keys()))
        c(p.get("eof") == 0 and p.get("recordId") == "" and p.get("tokenId") == r["tokenId"], "eof 0, recordId \"\"")
        c(p.get("applicationId") == "mock-app-key" and p.get("userId") == "it-user" and p.get("refText") == ref_text,
          "envelope fields")
        c(DT.match(p.get("dtLastResponse", "")) is not None, "dtLastResponse format")
        res = p.get("result", {})
        for k in ("kernel_version", "resource_version", "bytes", "readUnits", "matchedUnits", "totalUnits", "progress",
                  "words"):
            c(k in res, "result.%s passed through" % k)
        words = res.get("words") or []
        c(len(words) > 0, "an eof 0 callback carries words")
        c(res.get("readUnits", 0) > last, "every callback reaches further: %r after %r" % (res.get("readUnits"), last))
        last = res.get("readUnits", last)
        c(res.get("totalUnits") == len(units), "totalUnits %r" % res.get("totalUnits"))
        for w in words:
            c(sorted(w.keys()) == ["charType", "scores", "word", "word_parts"], "Shengtong item %r" % w)
            seen.append(w.get("word"))
    c(seen == units[:len(seen)], "words in order without repeats: %r" % seen)
    return seen


def s_realtime_progress(c):
    """realtime_feedback over the compat WebSocket: signed handshake, parameter frame, eof 0 progress
    through the result callback, the final envelope exactly like REST, one evaluation billed."""
    reset()
    r = realtime("--chunk-bytes", "6400", "--pace-ms", "20")
    units = units_of(ZH_TEXT, True)
    c(r["outcome"] == "result" and r.get("mode") == "ws", "evaluated over the WebSocket %r" % {
        k: r.get(k) for k in ("outcome", "mode", "restReason")})
    c(r["connections"] == 1 and r["attempts"] == 1 and r["held"] == 0, "one connection")
    pf = r.get("paramFrame") or {}
    c(list(pf.keys()) == ["refText", "phoneme_output", "realtime_feedback", "idempotencyKey"],
      "parameter frame members %r" % list(pf.keys()))
    c(pf == {"refText": ZH_TEXT, "phoneme_output": "1", "realtime_feedback": True, "idempotencyKey": r["tokenId"]},
      "parameter frame = the REST form fields plus realtime_feedback and idempotencyKey %r" % pf)
    seen = check_progress(c, r, units)
    # the mock reads one unit per 8000 bytes and one more after end
    c(len(seen) == pcm_len("zh_progress.wav") // 8000 + 1, "units read %d" % len(seen))
    c(all(w["scores"]["skipped"] == 0 and w["scores"]["overall"] == 100 and w["charType"] == 0
          for p in r["progress"] for w in p["result"]["words"]), "matched characters score 100")
    j = r["json"]
    c(list(j.keys()) == ["tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse", "result"],
      "final envelope keys %r" % list(j.keys()))
    c(j.get("eof") == 1 and str(j.get("recordId", "")).startswith("eval_"), "final eof 1 with recordId")
    c(j.get("result") == aligned(fixture_result("compat_sent.eval.cn.json")), "final result as REST, aligned")
    log_entries = mock_log()
    c([(e.get("method"), e.get("path"), e.get("idempotencyKey")) for e in log_entries] ==
      [("WS", "/sent.eval.cn", r["tokenId"])], "one WebSocket round, idempotencyKey = tokenId: %r" % log_entries)
    b = billing()
    c(b["billed"] == 1 and b["records"][0]["op"] == "ws-compat" and b["records"][0]["idemKey"] == r["tokenId"],
      "billed once %r" % b)
    c(b["records"] and b["records"][0].get("bytes") == pcm_len("zh_progress.wav"), "every PCM byte reached the platform")


def s_realtime_english(c):
    reset()
    r = driver("--realtime", "--audio", audio("en_progress.wav"), "--core-type", "sent.eval", "--ref-text", EN_TEXT,
               "--chunk-bytes", "6400", "--pace-ms", "10")
    c(r["outcome"] == "result" and r.get("mode") == "ws", "evaluated over the WebSocket %r" % r.get("restReason"))
    seen = check_progress(c, r, units_of(EN_TEXT, False), EN_TEXT)
    c(len(seen) > 5, "English words delivered %r" % seen)
    c(all(w["charType"] == 1 and w["scores"]["tone"] == 0 for p in r["progress"] for w in p["result"]["words"]),
      "English items: charType 1, tone 0")


def s_realtime_refused_rest(c):
    """Handshake refused (503): no progress, the round goes over REST at stop with the same tokenId."""
    reset()
    faults(("/sent.eval.cn", "ws-refuse"))
    r = realtime("--chunk-bytes", "6400", "--pace-ms", "10")
    c(r["outcome"] == "result" and r.get("mode") == "rest", "REST after the refused handshake %r" % r.get("mode"))
    c(r["progress"] == [], "no progress without the WebSocket")
    c("refused" in r.get("restReason", ""), "reason %r" % r.get("restReason"))
    c(r["json"].get("result") == aligned(fixture_result("compat_sent.eval.cn.json")), "REST result")
    entries = mock_log()
    c([(e["method"], e.get("rejected"), e.get("idempotencyKey")) for e in entries] ==
      [("WS", 503, r["tokenId"]), ("POST", None, r["tokenId"])], "WebSocket refused, then POST, one tokenId %r" % entries)
    b = billing()
    c(b["billed"] == 1 and b["records"][0]["op"] == "compat", "billed once over REST %r" % b)


def s_realtime_kill_before_end(c):
    """The connection dies while the learner reads: progress stops, REST evaluates at stop."""
    reset()
    faults(("/sent.eval.cn", "ws-kill-after:5"))
    r = realtime("--chunk-bytes", "6400", "--pace-ms", "20", "--feed-after-start")
    c(r["outcome"] == "result" and r.get("mode") == "rest", "REST after the dead connection %r" % r.get("mode"))
    c(r["connections"] == 1, "no reconnect before end")
    check_progress(c, r, units_of(ZH_TEXT, True))
    entries = mock_log()
    c([(e["method"], e.get("idempotencyKey")) for e in entries] == [("WS", r["tokenId"]), ("POST", r["tokenId"])],
      "WebSocket then POST with the same tokenId %r" % entries)
    c(billing()["billed"] == 1, "billed once")


def s_realtime_kill_after_end_reconnect(c):
    """The connection dies right on end: reconnect with the same tokenId, replay, the progress of the
    replay is not delivered again, one evaluation billed."""
    reset()
    pcm = pcm_len("zh_progress.wav")
    frames = 1 + (pcm + 6399) // 6400 + 1  # parameter frame, one frame per step, end
    faults(("/sent.eval.cn", "ws-kill-after:%d" % frames))
    r = realtime("--chunk-bytes", "6400", "--pace-ms", "0", "--feed-after-start")
    c(r["outcome"] == "result" and r.get("mode") == "ws", "result over the WebSocket %r" % r.get("restReason"))
    c(r["connections"] == 2 and r["attempts"] == 2, "one reconnect, attempts %r" % r["attempts"])
    c(r["held"] >= 1, "replayed progress held back, held %r" % r["held"])
    seen = check_progress(c, r, units_of(ZH_TEXT, True))
    c(len(seen) == pcm // 8000 + 1, "every unit once across both connections: %d" % len(seen))
    entries = mock_log()
    c([(e["method"], e.get("idempotencyKey")) for e in entries] == [("WS", r["tokenId"])] * 2,
      "two WebSocket rounds with one tokenId %r" % entries)
    b = billing()
    c(b["billed"] == 1 and b["records"][0]["bytes"] == pcm, "billed once, full replay %r" % b)


def s_realtime_retries_exhausted(c):
    """Every connection fails after end (retryable error frame): the same 20009 as REST, attempts 3."""
    reset()
    r = realtime("--chunk-bytes", "32000", "--ws-fault", "ws-error:50200")
    j = r["json"]
    c(r["outcome"] == "error" and j.get("errId") == 20009, "20009 %r" % j)
    c(j.get("error", "").endswith(": code=50200 mock upstream error (attempts 3)"), "message %r" % j.get("error"))
    c(list(j.keys()) == ["tokenId", "errId", "error", "eof", "applicationId"], "error JSON keys")
    c(r["connections"] == 3, "three connections")
    entries = mock_log()
    # the mockFault query parameter is signed with the others: a wrong signature would be refused with 403
    c(len(entries) == 3 and all(e.get("fault") == "ws-error:50200" and "rejected" not in e for e in entries),
      "three signed handshakes %r" % entries)
    c(billing()["billed"] == 0, "nothing billed")


def s_realtime_error_not_retryable(c):
    reset()
    r = realtime("--chunk-bytes", "32000", "--ws-fault", "ws-error:40001")
    j = r["json"]
    c(r["outcome"] == "error" and j.get("errId") == 40001 and r["connections"] == 1, "errId 40001, no reconnect %r" % j)
    c(j.get("error") == "mock upstream error", "platform message %r" % j.get("error"))


def s_realtime_progress_mismatch(c):
    reset()
    faults(("/sent.eval.cn", "progress-mismatch:7"))
    r = realtime("--chunk-bytes", "6400", "--pace-ms", "10")
    c(r["outcome"] == "result" and r.get("mode") == "ws", "result %r" % r.get("restReason"))
    seen = check_progress(c, r, units_of(ZH_TEXT, True))
    items = [w for p in r["progress"] for w in p["result"]["words"]]
    c(len(items) > 8, "unit 7 was read")
    if len(items) > 8:
        c(items[7]["scores"]["skipped"] == 1 and items[7]["scores"]["overall"] == 0, "unit 7 read, not matched %r" % items[7])
        c(all(w["scores"]["skipped"] == 0 for i, w in enumerate(items) if i != 7), "the others matched")
    c(len(seen) == len(items), "items")


def s_realtime_progress_off(c):
    reset()
    faults(("/sent.eval.cn", "progress-off"))
    r = realtime("--chunk-bytes", "6400", "--pace-ms", "10")
    c(r["outcome"] == "result" and r.get("mode") == "ws", "result over the WebSocket")
    c(r["progress"] == [], "bytes-only frames are not delivered")
    c(billing()["billed"] == 1, "billed once")


def s_realtime_heartbeat(c):
    """No pong from the platform: the heartbeat ends the connection before end, REST at stop."""
    reset()
    r = realtime("--chunk-bytes", "3200", "--pace-ms", "50", "--ws-fault", "ws-silent:3", "--ping-interval-ms", "200",
                 "--pong-timeout-ms", "400")
    c(r["outcome"] == "result" and r.get("mode") == "rest", "REST after the heartbeat %r" % r.get("restReason"))
    c(r.get("restReason") == "timeout: no pong within 400 ms", "reason %r" % r.get("restReason"))
    c(billing()["billed"] == 1, "billed once")


def s_realtime_result_timeout_replay(c):
    """No final within the attempt timeout: reconnect, the platform replays the first evaluation."""
    reset()
    faults(("/sent.eval.cn", "ws-delay-result:3000"))
    r = realtime("--chunk-bytes", "32000", "--attempt-timeout-ms", "2500")
    c(r["outcome"] == "result" and r["replayed"] == 1 and r["connections"] == 2, "replayed after the timeout %r" % {
        k: r.get(k) for k in ("outcome", "replayed", "connections", "attempts")})
    time.sleep(0.3)
    c(billing()["billed"] == 1, "billed once")


def s_realtime_too_short(c):
    """A recording under 1 s: the local check answers 60005 at stop, no end, nothing evaluated."""
    reset()
    r = driver("--realtime", "--audio-synth-ms", "500", "--ref-text", ZH_TEXT, "--chunk-bytes", "3200", "--pace-ms", "20")
    c(r["outcome"] == "error" and r["json"].get("errId") == 60005 and r.get("mode") == "local", "60005 %r" % r["json"])
    time.sleep(0.3)
    c(billing()["billed"] == 0, "nothing evaluated")


# ------------------------------------------------------------------ stream upload and timing (2.1.1)

ENVELOPE_KEYS = ["tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse", "result"]
SUMMARY = re.compile(r"^timing tokenId=([0-9a-f]{32}) recordId=(\S+) mode=(rest|ws|ws>rest) audio=\S+ afterStop=\S+ "
                     r"upload=\S+ wait=\S+ download=\S+ parse=\S+ deliver=\S+ ")


def summary_fields(line):
    """The key=value members of the INFO line (the format of the Android drop-in); markers such as
    deflate, gzip or replayed map to True."""
    out = {}
    for kv in re.findall(r'(\S+?)=("[^"]*"|\S+)|(?<= )([a-z]+)(?= |$)', line):
        if kv[0]:
            out[kv[0]] = kv[1]
        elif kv[2]:
            out[kv[2]] = True
    return out


def check_summary(c, r, mode):
    """2.2.0: recordId right after tokenId, the recordId of the result, "-" for an error."""
    line = r.get("timing") or ""
    m = SUMMARY.match(line)
    want = r["json"].get("recordId") or "-"
    c(m is not None and m.group(1) == r["tokenId"] and m.group(2) == want and m.group(3) == mode,
      "summary line %r, recordId %r" % (line, want))
    return summary_fields(line) if m else {}


def s_stream_default(c):
    """2.1.1: a recording streams over the compat WebSocket without realtime_feedback: the parameter
    frame has no realtime_feedback key, the platform sends only the final, the envelope is the REST one."""
    reset()
    r = driver("--stream", "--audio", audio("zh_progress.wav"), "--ref-text", ZH_TEXT, "--chunk-bytes", "6400",
               "--pace-ms", "10")
    c(r["outcome"] == "result" and r.get("mode") == "ws" and r["connections"] == 1, "streamed %r" % {
        k: r.get(k) for k in ("outcome", "mode", "restReason", "connections")})
    pf = r.get("paramFrame") or {}
    c(pf == {"refText": ZH_TEXT, "phoneme_output": "1", "idempotencyKey": r["tokenId"]},
      "parameter frame = the REST form fields plus idempotencyKey, no realtime_feedback, no timing %r" % pf)
    c(r["progress"] == [] and r["held"] == 0, "no progress asked for, none delivered")
    j = r["json"]
    c(list(j.keys()) == ENVELOPE_KEYS and j.get("result") == aligned(fixture_result("compat_sent.eval.cn.json")),
      "final envelope as REST %r" % list(j.keys()))
    entries = mock_log()
    c([(e.get("method"), e.get("idempotencyKey")) for e in entries] == [("WS", r["tokenId"])], "one round %r" % entries)
    b = billing()
    c(b["billed"] == 1 and b["records"][0]["op"] == "ws-compat" and b["records"][0]["bytes"] == pcm_len("zh_progress.wav"),
      "billed once, every byte streamed %r" % b)
    f = check_summary(c, r, "ws")
    c("connections" not in f and "errId" not in f and f.get("final", "-").endswith("B") and f.get("wire") == f.get("final")
      and f.get("audio") == "%.2fs" % (pcm_len("zh_progress.wav") / 32000.0), "summary members %r" % f)
    c(f.get("queued") == "0B" and f.get("handshake", "-") != "-" and f.get("upload", "-") != "-", "phases %r" % f)
    c("server.total" not in f, "the mock reports no server timing")


def s_stream_refused_rest(c):
    """2.1.1 fallback: the handshake is refused, the recording goes over REST after stop, same tokenId."""
    reset()
    faults(("/sent.eval.cn", "ws-refuse"))
    r = driver("--stream", "--audio", audio("zh_progress.wav"), "--ref-text", ZH_TEXT, "--chunk-bytes", "6400",
               "--pace-ms", "5")
    c(r["outcome"] == "result" and r.get("mode") == "rest" and "refused" in r.get("restReason", ""),
      "REST after the refused handshake %r" % r.get("restReason"))
    entries = mock_log()
    c([(e["method"], e.get("rejected"), e.get("idempotencyKey")) for e in entries] ==
      [("WS", 503, r["tokenId"]), ("POST", None, r["tokenId"])], "one tokenId %r" % entries)
    c(billing()["billed"] == 1, "billed once")
    f = check_summary(c, r, "ws>rest")
    c(f.get("attempts") == "1" and f.get("upload", "-") != "-" and f.get("sent", "-") != "-", "REST part %r" % f)
    c("refused" in f.get("fallback", ""), "the fallback reason %r" % f.get("fallback"))


def s_timing_only_at_info(c):
    """timing=1 (REST, signed with the other fields) and "timing":1 (parameter frame) only at INFO:
    the mock accepts both, ignores them, and the envelope stays the 2.1.0 one."""
    reset()
    r = driver("--timing", "--audio", audio("zh_short.wav"), "--get-param")
    c(r["outcome"] == "result", "signed with timing=1 %r" % r["json"].get("error"))
    e = entries_for(r["tokenId"])
    c(len(e) == 1 and e[0].get("fields", {}).get("timing") == "1", "timing=1 sent %r" % e)
    c("timing" not in json.dumps(r["json"]["params"]), "params of the result JSON never show timing")
    r2 = driver("--audio", audio("zh_short.wav"))
    e2 = entries_for(r2["tokenId"])
    c(len(e2) == 1 and "timing" not in e2[0].get("fields", {}), "no timing field below INFO %r" % e2)
    r3 = driver("--stream", "--timing", "--audio", audio("zh_progress.wav"), "--ref-text", ZH_TEXT, "--chunk-bytes",
                "32000")
    pf = r3.get("paramFrame") or {}
    c(r3["outcome"] == "result" and list(pf.keys()) == ["refText", "phoneme_output", "timing", "idempotencyKey"] and
      pf.get("timing") == 1, "parameter frame with \"timing\":1 %r" % pf)
    r4 = driver("--realtime", "--timing", "--audio", audio("zh_progress.wav"), "--ref-text", ZH_TEXT, "--chunk-bytes",
                "32000")
    pf = r4.get("paramFrame") or {}
    c(list(pf.keys()) == ["refText", "phoneme_output", "realtime_feedback", "timing", "idempotencyKey"],
      "realtime_feedback and timing together %r" % pf)


def s_server_timing_rest(c):
    """Server-Timing on every REST response, the timing object only with timing=1, never in the envelope."""
    timing_server("POST", "/__ctl/reset")
    r = driver("--timing", "--audio", audio("zh_short.wav"), port=TIMING_PORT)
    j = r["json"]
    c(r["outcome"] == "result" and list(j.keys()) == ENVELOPE_KEYS, "envelope keys %r" % list(j.keys()))
    c("timing" not in j and "receivedAt" not in json.dumps(j), "the timing object never reaches the app")
    f = check_summary(c, r, "rest")
    c((f.get("server.total"), f.get("server.engine"), f.get("server.platform")) == ("321ms", "300ms", "21ms"),
      "server timing %r" % f)
    c(f.get("body", "").endswith("B") and f.get("download", "-") != "-" and f.get("afterStop", "-") != "-",
      "phases %r" % f)
    d = r.get("timingStages", "")
    c(d.startswith("timing tokenId=%s engineStages text_frontend_ms=12.5 asr_ms=33.0 pipeline_total_ms=290.0 "
                   "receivedAt=" % r["tokenId"]) and "wsFirstAudioAt" not in d, "engine stages %r" % d)
    c(" requestStart=+" in r.get("timingPhases", ""), "phases line %r" % r.get("timingPhases"))
    log_entries = timing_server("GET", "/__ctl/log")
    c(log_entries and log_entries[-1]["fields"].get("timing") == "1", "timing=1 reached the server")
    # without timing=1 the header alone still gives the platform's share
    r = driver("--audio", audio("zh_short.wav"), port=TIMING_PORT)
    f = check_summary(c, r, "rest")
    c(f.get("server.total") == "321ms", "Server-Timing without timing=1 %r" % f)
    c(r.get("timingStages") == "", "no timing object without timing=1")


def s_server_timing_ws(c):
    """The WebSocket final with "timing" (wsFirstAudioAt): server times in the summary, not in the envelope."""
    timing_server("POST", "/__ctl/reset")
    r = driver("--stream", "--timing", "--audio", audio("zh_progress.wav"), "--chunk-bytes", "6400", "--pace-ms", "5",
               port=TIMING_PORT)
    j = r["json"]
    c(r["outcome"] == "result" and r.get("mode") == "ws" and list(j.keys()) == ENVELOPE_KEYS, "streamed %r" % r.get("mode"))
    c("timing" not in j, "the timing object never reaches the app")
    f = check_summary(c, r, "ws")
    c(f.get("server.total") == "321ms" and f.get("queued") == "0B", "summary %r" % f)
    d = r.get("timingPhases", "")
    c(all(k in d for k in (" connect=+", " handshake=+", " params=+", " firstAudio=+", " stop=+", " endWritten=+",
                           " final=+", " parsed=+", " delivered=+")), "phases %r" % d)
    d = r.get("timingStages", "")
    c("wsFirstAudioAt=" in d and "audioBytes=%d" % pcm_len("zh_progress.wav") in d, "engine stages %r" % d)
    entry = timing_server("GET", "/__ctl/log")[-1]
    c(entry["params"].get("timing") == 1 and "realtime_feedback" not in entry["params"], "parameter frame %r" % entry["params"])
    # below INFO: no timing asked for, the WebSocket has no header, the server share is unknown
    r = driver("--stream", "--audio", audio("zh_progress.wav"), "--chunk-bytes", "32000", port=TIMING_PORT)
    f = check_summary(c, r, "ws")
    c("server.total" not in f and "timing" not in timing_server("GET", "/__ctl/log")[-1]["params"],
      "no timing without INFO %r" % f)


# ------------------------------------------------------------------ TtsClient and ReportClient (2.2.0)

SECRET = "mock-secret-key"
TTS_PATH = "/api/v1/tts/generate"


def contract_signature(params, secret=SECRET):
    """CONTRACT 2.2 written out independently: non-empty values, sorted keys, k=v joined by &, HMAC-SHA256,
    Base64."""
    keys = sorted(k for k, v in params.items() if v is not None and v != "")
    payload = "&".join("%s=%s" % (k, params[k]) for k in keys)
    return base64.b64encode(hmac.new(secret.encode(), payload.encode("utf-8"), hashlib.sha256).digest()).decode()


def literal(v):
    """The literal text of a scalar JSON value (CONTRACT 2.3)."""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def api(*extra, port=None):
    """The JSON line of the driver in --tts or --report mode, against the mock or the given port."""
    cmd = [ARGS.driver, "--port", str(port or PORT)] + [str(x) for x in extra]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise AssertionError("driver exit %d: %s" % (p.returncode, p.stderr))
    lines = [l for l in p.stdout.splitlines() if l.strip()]
    if len(lines) != 1:
        raise AssertionError("driver printed %d lines: %r" % (len(lines), p.stdout))
    out = json.loads(lines[0])
    out["_stderr"] = p.stderr
    return out


LOG_LINE = re.compile(r"^(tts|getReport)( recordId=\S+)? status=(\S+)( code=\d+)?( lookups=\d+)? attempts=\d+ ms=\d+$")


def s_tts(c):
    """TtsClient.tts: the members that are set, signed with their literal text, one Idempotency-Key, the
    absolute audio URL against the base, the INFO line without the text."""
    reset()
    r = api("--tts", "--tts-text", "你好，世界", "--tts-set", "language=zh-CN", "--tts-set", "voice=xiaoyan",
            "--tts-set", "format=wav", "--tts-set", "speed=50", "--tts-set", "pitch=0", "--tts-set", "volume=100",
            "--tts-set", "sampleRate=16000", "--tts-set", "emotion=warm", "--tts-set", "style=温柔地朗读",
            "--tts-set", "pinyin=ni3 hao3 shi4 jie4", "--tts-set", "genre=prose")
    c(r["outcome"] == "result" and r["code"] == 0 and r["attempts"] == 1 and r["httpStatus"] == 200, "result %r" % r)
    req = r.get("request") or {}
    c(list(req.keys()) == ["text", "language", "voice", "format", "speed", "pitch", "volume", "sampleRate", "emotion",
                           "style", "pinyin", "genre"], "members in the order of the request %r" % list(req.keys()))
    c(req.get("speed") == 50 and req.get("pitch") == 0 and req.get("sampleRate") == 16000 and req.get("text") == "你好，世界",
      "integers as JSON numbers, 0 sent %r" % req)
    flat = {k: literal(v) for k, v in req.items() if v is not None and not isinstance(v, (dict, list))}
    c(r.get("signature") == contract_signature(flat), "signed over the top-level scalars %r" % r.get("signature"))
    c(r.get("signature") != contract_signature({}), "not the empty set")
    c(re.fullmatch(r"[0-9a-f]{32}", r.get("idempotencyKey", "")) is not None, "a fresh key %r" % r.get("idempotencyKey"))
    c(str(r.get("audioUrl", "")).startswith("/audio/mock-"), "audioUrl as returned %r" % r.get("audioUrl"))
    c(r.get("absoluteAudioUrl") == "http://127.0.0.1:%d/tts%s" % (PORT, r.get("audioUrl")),
      "absolute against the base %r" % r.get("absoluteAudioUrl"))
    c(r.get("hasDuration") == 1 and abs(r.get("duration", 0) - 1.348) < 1e-9 and r.get("format") == "wav",
      "duration and format %r" % {k: r.get(k) for k in ("duration", "format")})
    raw = r.get("raw") or {}
    c(raw.get("code") == 0 and raw.get("data", {}).get("audioUrl") == r.get("audioUrl"), "the raw answer %r" % raw)
    c(LOG_LINE.match(r.get("log", "")) is not None and r["log"].startswith("tts status=ok attempts=1 ms="),
      "INFO line %r" % r.get("log"))
    c("你好" not in r["log"] and SECRET not in r["log"] and r["signature"] not in r["log"], "nothing sensitive logged")
    e = mock_log()
    c(len(e) == 1 and e[0]["method"] == "POST" and e[0]["path"] == TTS_PATH, "one POST %r" % e)
    if e:
        c(e[0]["idempotencyKey"] == r["idempotencyKey"], "Idempotency-Key sent")
        c(e[0]["contentType"] == "application/json; charset=utf-8", "JSON body %r" % e[0]["contentType"])
        c(e[0]["userAgent"] == "yugu-ios-stcompat-sdk/2.3.0", "User-Agent %r" % e[0]["userAgent"])
        c(e[0]["bytes"] == len(json.dumps(req, ensure_ascii=False, separators=(",", ":")).encode("utf-8")),
          "the body as built")
    b = billing()
    c(b["billed"] == 1 and b["records"][0]["op"] == "tts" and b["records"][0]["idemKey"] == r["idempotencyKey"],
      "billed once %r" % b)
    # only what is set: text alone
    r = api("--tts", "--tts-text", "早上好")
    c(r["outcome"] == "result" and r.get("request") == {"text": "早上好"}, "text only %r" % r.get("request"))
    c(r.get("signature") == contract_signature({"text": "早上好"}) and r.get("format") == "mp3", "signed text only")


def s_tts_retries_and_errors(c):
    """The retry rules of CONTRACT 7 with the shim's controller: one key and a fresh nonce on every attempt,
    20009 when the retries are used up, platform codes as they are, local checks without a request."""
    reset()
    faults((TTS_PATH, "status:503"))
    r = api("--tts", "--tts-text", "你好")
    e = mock_log()
    c(r["outcome"] == "result" and r["attempts"] == 2, "503 then success %r" % r)
    c([x["idempotencyKey"] for x in e] == [r["idempotencyKey"]] * 2, "the same key on both attempts %r" % e)
    c(billing()["byKey"].get(r["idempotencyKey"]) == 1, "billed once")
    reset()
    faults((TTS_PATH, "status:503"), (TTS_PATH, "status:503"), (TTS_PATH, "status:503"))
    r = api("--tts", "--tts-text", "你好")
    c(r["outcome"] == "error" and r["code"] == 20009 and r["attempts"] == 3 and r["httpStatus"] == 503,
      "20009 after three attempts %r" % r)
    c(r["message"].endswith(": HTTP 503 code=50000 mock fault 503 (attempts 3)"), "message %r" % r["message"])
    c(r["log"].startswith("tts status=error code=20009 attempts=3 ms="), "log %r" % r["log"])
    c(billing()["billed"] == 0, "nothing billed")
    reset()
    faults((TTS_PATH, "status:400:code=40001:message=text%20%E4%B8%8D%E8%83%BD%E4%B8%BA%E7%A9%BA"))
    r = api("--tts", "--tts-text", "你好")
    c(r["outcome"] == "error" and r["code"] == 40001 and r["attempts"] == 1 and r["message"] == "text 不能为空",
      "400 not retried, platform text %r" % r)
    r = api("--tts", "--tts-text", "你好", "--secret", "wrong-secret")
    c(r["code"] == 2003 and r["message"] == "签名验证失败" and r["httpStatus"] == 401 and r["attempts"] == 1,
      "bad signature %r" % r)
    r = api("--tts", "--tts-text", "你好", "--app-key", "no-such-key")
    c(r["code"] == 2010 and r["attempts"] == 1, "unknown appKey %r" % r)
    reset()
    r = api("--tts", "--tts-text", "  \n ")
    c(r["outcome"] == "error" and r["code"] == 90010 and r["attempts"] == 0 and r["message"] == "TtsRequest.text 为空",
      "blank text answered locally %r" % r)
    r = api("--tts", "--tts-text", "你好", "--idempotency-key", "has space")
    c(r["code"] == 90010 and r["attempts"] == 0, "invalid key answered locally %r" % r)
    c(mock_log() == [], "no request for local errors")


def s_tts_idempotency_key(c):
    """TtsOptions.idempotencyKey: the caller's key; the same key and body again is the first answer."""
    reset()
    r1 = api("--tts", "--tts-text", "今天天气很好", "--idempotency-key", "order-42")
    r2 = api("--tts", "--tts-text", "今天天气很好", "--idempotency-key", "order-42")
    c(r1["outcome"] == r2["outcome"] == "result", "both answered %r %r" % (r1["code"], r2["code"]))
    c(r1.get("audioUrl") == r2.get("audioUrl"), "the replay is the first answer")
    c([x["idempotencyKey"] for x in mock_log()] == ["order-42", "order-42"], "the caller's key")
    c(billing()["byKey"].get("order-42") == 1, "billed once")
    r3 = api("--tts", "--tts-text", "另一句话", "--idempotency-key", "order-42")
    c(r3["outcome"] == "error" and r3["code"] == 40903 and r3["attempts"] == 1, "same key, other body: 40903 %r" % r3)


MOCK_FINAL_SUGGESTION = "“清”的后鼻音收得再完整一些。"


def check_report_line(c, r, record_id, status, lookups):
    want = "getReport recordId=%s status=%s lookups=%d attempts=" % (record_id, status, lookups)
    c(LOG_LINE.match(r.get("log", "")) is not None and r["log"].startswith(want), "INFO line %r, want %r" % (r.get("log"), want))
    c("mock report" not in r["log"] and "即时稿" not in r["log"], "no report text in the log")


def s_report(c):
    """ReportClient.getReport: the empty signed set, no Idempotency-Key, the fields of the answer."""
    reset()
    r = api("--report", "eval_abc123")
    c(r["outcome"] == "result" and r["lookups"] == 1 and r["attempts"] == 1, "one lookup %r" % r)
    c(r.get("signature") == contract_signature({}), "CONTRACT 2.3: the empty set")
    c(r.get("recordId") == "eval_abc123" and r.get("reportStatus") == "final" and r.get("isFinal") == 1,
      "final %r" % {k: r.get(k) for k in ("recordId", "reportStatus", "isFinal")})
    c(r.get("summary") == "mock report" and r.get("suggestions") == [MOCK_FINAL_SUGGESTION], "summary and suggestions")
    c(r.get("overall") == 93.7, "overall from score.overall %r" % r.get("overall"))
    data = r.get("data") or {}
    c(r.get("report") == data.get("report") and r.get("report", {}).get("source") == "llm" and
      r["report"].get("skillInsights", [{}])[0].get("confidence") == "medium", "the report object %r" % r.get("report"))
    c(r.get("score") == {"overall": 93.7, "integrity": 100}, "the score object %r" % r.get("score"))
    c(r.get("timing") == data.get("timing") and (r.get("timing") or {}).get("channel") == "rest", "timing %r" % r.get("timing"))
    c(data.get("recordId") == "eval_abc123" and data.get("reportStatus") == "final", "the raw data")
    check_report_line(c, r, "eval_abc123", "final", 1)
    e = mock_log()
    c([(x["method"], x["path"], x["idempotencyKey"]) for x in e] == [("GET", "/api/v1/report/eval_abc123", None)],
      "one GET without a key %r" % e)
    c(e and e[0]["userAgent"] == "yugu-ios-stcompat-sdk/2.3.0", "User-Agent")
    c(billing()["billed"] == 0, "a lookup is not billed")
    # the recordId is trimmed and percent-encoded as one path segment
    r = api("--report", "  eval_a b  ")
    c(r["outcome"] == "result" and r.get("recordId") == "eval_a b" and r.get("path") == "/api/v1/report/eval_a%20b",
      "encoded %r" % {k: r.get(k) for k in ("recordId", "path", "code", "message")})
    c(mock_log()[-1]["path"] == "/api/v1/report/eval_a%20b", "path on the wire %r" % mock_log()[-1]["path"])


def s_report_wait_for_final(c):
    """waitForFinalMs: pending answers are looked up again every 1000 ms until final or the time is used up;
    still pending is a result."""
    reset()
    r = api("--report", "eval_pending_1")
    c(r["outcome"] == "result" and r["lookups"] == 1, "waitForFinalMs 0: one lookup %r" % r)
    c(r.get("reportStatus") == "pending" and r.get("isFinal") == 0 and r.get("isPending") == 1, "pending %r" % r.get("reportStatus"))
    c(r.get("summary") == "即时稿：整体读得流畅。" and r.get("suggestions") == ["注意后鼻音。"], "the instant draft")
    check_report_line(c, r, "eval_pending_1", "pending", 1)
    r = api("--report", "eval_pending_2", "--wait-for-final-ms", "6000")
    c(r["outcome"] == "result" and r["lookups"] == 3 and r.get("reportStatus") == "final" and r.get("isFinal") == 1,
      "final on the third lookup %r" % {k: r.get(k) for k in ("lookups", "reportStatus", "lookupMs")})
    starts = r.get("lookupMs") or []
    c(len(starts) == 3 and all(1000 <= b - a < 2000 for a, b in zip(starts, starts[1:])), "one second apart %r" % starts)
    c(r.get("summary") == "mock report" and r["attempts"] == 3, "the AI draft, three attempts in all")
    check_report_line(c, r, "eval_pending_2", "final", 3)
    # the time is used up while pending: the latest answer, not an error
    r = api("--report", "eval_pending_3", "--wait-for-final-ms", "1500")
    c(r["outcome"] == "result" and r["lookups"] == 2 and r.get("reportStatus") == "pending",
      "pending after 1500 ms %r" % {k: r.get(k) for k in ("lookups", "reportStatus", "lookupMs")})
    c(r["elapsedMs"] < 2500, "returned when the next lookup would start too late %r" % r["elapsedMs"])
    # not pending: no wait
    r = api("--report", "eval_final_4", "--wait-for-final-ms", "6000")
    c(r["lookups"] == 1 and r.get("reportStatus") == "final", "final at once %r" % r["lookups"])
    c([x["method"] for x in mock_log()] == ["GET"] * 7, "seven lookups %r" % len(mock_log()))


def s_report_errors(c):
    reset()
    r = api("--report", "nope")
    c(r["outcome"] == "error" and r["code"] == 40001 and r["httpStatus"] == 400 and r["attempts"] == 1,
      "not found: platform code, not retried %r" % r)
    c(r["message"] == "评测记录不存在: nope", "platform text %r" % r["message"])
    c(LOG_LINE.match(r["log"]) is not None and
      r["log"].startswith("getReport recordId=nope status=error code=40001 lookups=1 attempts=1 ms="), "error line %r" % r["log"])
    faults(("/api/v1/report/", "status:503"))
    r = api("--report", "eval_retry")
    c(r["outcome"] == "result" and r["attempts"] == 2 and r["lookups"] == 1, "503 retried %r" % r)
    e = [x for x in mock_log() if x["path"] == "/api/v1/report/eval_retry"]
    c(len(e) == 2 and all(x["idempotencyKey"] is None for x in e), "two GETs, no key %r" % e)
    reset()
    for _ in range(3):
        faults(("/api/v1/report/", "drop"))
    r = api("--report", "eval_drop")
    c(r["outcome"] == "error" and r["code"] == 20009 and r["attempts"] == 3 and r["httpStatus"] == 0,
      "network failure after the retries: 20009 %r" % r)
    # the first lookup's error ends the call with that error, waitForFinalMs or not
    reset()
    faults(("/api/v1/report/eval_pending_w", "status:403:code=40300"))
    r = api("--report", "eval_pending_w", "--wait-for-final-ms", "5000")
    c(r["outcome"] == "error" and r["code"] == 40300 and r["lookups"] == 1 and "warn" not in r,
      "403 on the first lookup %r" % r)
    reset()
    r = api("--report", "eval_x", "--secret", "wrong-secret")
    c(r["code"] == 2003 and r["attempts"] == 1, "bad signature %r" % r)
    reset()
    r = api("--report", "  ")
    c(r["outcome"] == "error" and r["code"] == 90010 and r["attempts"] == 0 and r["message"] == "recordId 为空",
      "blank recordId answered locally %r" % r)
    c(mock_log() == [], "no request")


def report_with_fault_after_first(record_id, fault, count, wait_ms):
    """getReport with waitForFinalMs while the mock fails every lookup after the first answer: the faults
    are queued once the mock has seen the first lookup, so they hit the following ones."""
    path = "/api/v1/report/" + record_id
    p = subprocess.Popen([ARGS.driver, "--port", str(PORT), "--report", record_id, "--wait-for-final-ms", str(wait_ms)],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        deadline = time.time() + 20
        while time.time() < deadline and not [e for e in mock_log() if e.get("path") == path]:
            time.sleep(0.02)
        faults(*[(path, fault)] * count)
        out, err = p.communicate(timeout=90)
    finally:
        if p.poll() is None:
            p.kill()
            p.wait()
    if p.returncode != 0:
        raise AssertionError("driver exit %d: %s" % (p.returncode, err))
    r = json.loads([l for l in out.splitlines() if l.strip()][0])
    r["_stderr"] = err
    return r, [e for e in mock_log() if e.get("path") == path]


def s_report_later_lookup_fails(c):
    """waitForFinalMs as the Android shim decides it: a lookup that fails after an answer ends the call with
    the latest answer, still pending, as a result, with one WARN line naming the recordId, the code and the
    lookups so far."""
    reset()
    rid = "eval_pending_later_503"
    r, gets = report_with_fault_after_first(rid, "status:503", 3, 6000)
    c(r["outcome"] == "result" and r["code"] == 0 and r["message"] == "",
      "the latest answer, not an error %r" % {k: r.get(k) for k in ("outcome", "code", "message")})
    c(r.get("reportStatus") == "pending" and r.get("isFinal") == 0 and r.get("summary") == "即时稿：整体读得流畅。",
      "the pending answer of the first lookup %r" % r.get("reportStatus"))
    c(r["lookups"] == 2 and r["attempts"] == 4, "an answer, then a lookup failed after three attempts %r" % {
        k: r.get(k) for k in ("lookups", "attempts", "lookupMs")})
    c(r.get("failedCode") == 20009 and r.get("warn") ==
      "getReport recordId=%s lookup failed while waiting: code=20009 lookups=2, returning the latest answer" % rid,
      "WARN line %r" % r.get("warn"))
    c(r["log"].startswith("getReport recordId=%s status=pending lookups=2 attempts=4 ms=" % rid), "INFO line %r" % r["log"])
    c([e.get("fault") for e in gets] == [None, "status:503", "status:503", "status:503"],
      "one answer and three failed attempts %r" % [e.get("fault") for e in gets])
    # not retried: the same, at once
    rid = "eval_pending_later_403"
    r, gets = report_with_fault_after_first(rid, "status:403:code=40300", 1, 6000)
    c(r["outcome"] == "result" and r.get("reportStatus") == "pending" and r["lookups"] == 2 and r["attempts"] == 2,
      "403 on the second lookup: the first answer %r" % {k: r.get(k) for k in ("outcome", "code", "lookups", "attempts")})
    c(r.get("warn", "").endswith(" code=40300 lookups=2, returning the latest answer"), "WARN line %r" % r.get("warn"))
    c(len(gets) == 2, "two lookups %r" % len(gets))


TS_AI_SUMMARY = "AI 点评：整体读得流畅，个别字的声调可以更准。"
TS_AI_SUGGESTIONS = ["“清”的后鼻音收得再完整一些。", "长句中间换气再自然一些。"]


def ts_reports():
    return [(e["recordId"], e["status"], e["idempotencyKey"]) for e in timing_server("GET", "/__ctl/log")
            if e.get("kind") == "report"]


def s_report_partial(c):
    """2.3.0 reportStatus partial: the AI summary and suggestions are ready, the per-dimension comments are
    still being written. waitForFinalMs looks up again every 1000 ms through pending and partial and stops at
    final; isFinal is 1 for final only; without waitForFinalMs, or when the time runs out, the partial answer
    is the result. Against timing_server.py, which answers the configured statuses in turn."""
    timing_server("POST", "/__ctl/reset")
    timing_server("POST", "/__ctl/config", {"reportStatuses": ["pending", "partial", "partial", "final"]})
    r = api("--report", "eval_partial_1", "--wait-for-final-ms", "8000", port=TIMING_PORT)
    c(r["outcome"] == "result" and r["lookups"] == 4 and r.get("reportStatus") == "final" and r.get("isFinal") == 1,
      "final on the fourth lookup %r" % {k: r.get(k) for k in ("outcome", "lookups", "reportStatus", "code", "message")})
    starts = r.get("lookupMs") or []
    c(len(starts) == 4 and all(1000 <= b - a < 2000 for a, b in zip(starts, starts[1:])), "one second apart %r" % starts)
    c(r.get("summary") == TS_AI_SUMMARY and r.get("suggestions") == TS_AI_SUGGESTIONS, "the AI final draft")
    c("dimensions" in (r.get("report") or {}), "the final draft has the per-dimension comments")
    c(r.get("isPartial") == 0 and r.get("isPending") == 0, "final is neither pending nor partial")
    check_report_line(c, r, "eval_partial_1", "final", 4)
    c(ts_reports() == [("eval_partial_1", s, None) for s in ("pending", "partial", "partial", "final")],
      "the lookups the server answered, without Idempotency-Key %r" % ts_reports())
    # waitForFinalMs 0: one lookup, partial is a normal result, not final
    timing_server("POST", "/__ctl/reset")
    timing_server("POST", "/__ctl/config", {"reportStatuses": ["partial", "final"]})
    r = api("--report", "eval_partial_2", port=TIMING_PORT)
    c(r["outcome"] == "result" and r["lookups"] == 1 and r.get("reportStatus") == "partial", "one lookup %r" % {
        k: r.get(k) for k in ("outcome", "lookups", "reportStatus")})
    c(r.get("isFinal") == 0 and r.get("isPartial") == 1 and r.get("isPending") == 0, "partial is not final %r" % {
        k: r.get(k) for k in ("isFinal", "isPartial", "isPending")})
    c(r.get("summary") == TS_AI_SUMMARY and r.get("suggestions") == TS_AI_SUGGESTIONS and r.get("hasSummary") == 1,
      "the AI summary and suggestions of a partial report %r" % r.get("summary"))
    c("dimensions" not in (r.get("report") or {}), "no per-dimension comments yet")
    check_report_line(c, r, "eval_partial_2", "partial", 1)
    c("AI 点评" not in r["log"], "no report text in the log")
    # the time runs out while partial: the latest answer, not an error
    timing_server("POST", "/__ctl/config", {"reportStatuses": ["partial"]})
    r = api("--report", "eval_partial_3", "--wait-for-final-ms", "1500", port=TIMING_PORT)
    c(r["outcome"] == "result" and r["lookups"] == 2 and r.get("reportStatus") == "partial" and r.get("isFinal") == 0,
      "partial after 1500 ms %r" % {k: r.get(k) for k in ("outcome", "lookups", "reportStatus", "lookupMs")})
    c(r["elapsedMs"] < 2500, "returned when the next lookup would start too late %r" % r["elapsedMs"])
    # instant does not wait: the AI final draft will not come
    timing_server("POST", "/__ctl/config", {"reportStatuses": ["instant", "final"]})
    r = api("--report", "eval_partial_4", "--wait-for-final-ms", "5000", port=TIMING_PORT)
    c(r["lookups"] == 1 and r.get("reportStatus") == "instant", "instant at once %r" % r.get("lookups"))
    timing_server("POST", "/__ctl/reset")


SCENARIOS = [
    s_success_envelope, s_word_params_custom, s_attach_audio_url, s_retry_500_same_key, s_retry_429_retry_after, s_timeout_then_replay,
    s_slow_inflight_replay, s_inflight_conflict_40901, s_nonce_reuse_rejected, s_no_retry_400, s_retries_exhausted, s_auto_retry, s_auto_retry_custom_errid, s_drop_then_success,
    s_hang_then_success, s_fastapi_detail, s_auth_errors, s_local_errors_no_network, s_pinyin, s_fixture_passthrough,
    s_paragraph_word_details, s_open_question,
    s_realtime_progress, s_realtime_english, s_realtime_refused_rest, s_realtime_kill_before_end,
    s_realtime_kill_after_end_reconnect, s_realtime_retries_exhausted, s_realtime_error_not_retryable,
    s_realtime_progress_mismatch, s_realtime_progress_off, s_realtime_heartbeat, s_realtime_result_timeout_replay,
    s_realtime_too_short,
    s_stream_default, s_stream_refused_rest, s_timing_only_at_info, s_server_timing_rest, s_server_timing_ws,
    s_tts, s_tts_retries_and_errors, s_tts_idempotency_key, s_report, s_report_wait_for_final, s_report_errors,
    s_report_later_lookup_fails, s_report_partial,
]


def write_junit(path, results):
    from xml.sax.saxutils import escape, quoteattr
    fails = sum(1 for r in results if r["failures"])
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<testsuite name="ios-stcompat-integration" tests="%d" failures="%d">\n' % (len(results), fails))
        for r in results:
            f.write('  <testcase classname="integration" name=%s time="%.3f"' % (quoteattr(r["name"]), r["seconds"]))
            if r["failures"]:
                f.write("><failure message=%s>%s</failure></testcase>\n" % (
                    quoteattr(r["failures"][0][:300]), escape("\n".join(r["failures"]))))
            else:
                f.write("/>\n")
        f.write("</testsuite>\n")


def main():
    global ARGS, PORT, TIMING_PORT
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", required=True)
    ap.add_argument("--mock", required=True)
    ap.add_argument("--spec", required=True)
    ap.add_argument("--junit")
    ap.add_argument("--log")
    ap.add_argument("--node", default="node")
    ARGS = ap.parse_args()
    proc = subprocess.Popen([ARGS.node, ARGS.mock, "--port", "0", "--processing-ms", "50"], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True)
    timing_proc = subprocess.Popen([sys.executable, os.path.join(HERE, "timing_server.py"), "--port", "0"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    results = []

    def stop_mock(signum, frame):  # noqa: ARG001 - signal handler signature
        proc.terminate()
        timing_proc.terminate()
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, stop_mock)
    signal.signal(signal.SIGINT, stop_mock)
    try:
        line = proc.stdout.readline()
        PORT = json.loads(line)["port"]
        log("mock platform listening on 127.0.0.1:%d (pid %d)" % (PORT, proc.pid))
        TIMING_PORT = json.loads(timing_proc.stdout.readline())["port"]
        log("timing server listening on 127.0.0.1:%d (pid %d)" % (TIMING_PORT, timing_proc.pid))
        for fn in SCENARIOS:
            c = Check()
            t0 = time.time()
            try:
                fn(c)
            except Exception as e:  # noqa: BLE001 - report every failure as a test failure
                c.failures.append("exception: %s\n%s" % (e, traceback.format_exc()))
            dt = time.time() - t0
            results.append({"name": fn.__name__[2:], "failures": c.failures, "seconds": dt, "checks": c.count})
            log("%s %s (%d checks, %.2f s)" % ("ok  " if not c.failures else "FAIL", fn.__name__[2:], c.count, dt))
            for f in c.failures:
                log("     " + f.replace("\n", "\n     "))
    finally:
        for p in (proc, timing_proc):
            p.terminate()
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
                p.wait()
        log("mock platform stopped (exit %s), timing server stopped (exit %s)" % (proc.returncode,
                                                                                   timing_proc.returncode))
    failed = [r for r in results if r["failures"]]
    log("integration: %d scenarios, %d failed, %d checks" % (len(results), len(failed),
                                                             sum(r["checks"] for r in results)))
    if ARGS.junit:
        write_junit(ARGS.junit, results)
    if ARGS.log:
        with open(ARGS.log, "w", encoding="utf-8") as f:
            f.write("\n".join(LOG_LINES) + "\n")
    return 1 if failed or len(results) != len(SCENARIOS) else 0


if __name__ == "__main__":
    sys.exit(main())
