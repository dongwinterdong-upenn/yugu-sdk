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

  run_it.py --driver build/it_driver --mock ../tools/mock-server/server.mjs --spec ../spec
            [--junit it.xml] [--log it.log]
"""
import argparse
import copy
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
LOG_LINES = []


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


def driver(*extra):
    cmd = [ARGS.driver, "--port", str(PORT)] + [str(x) for x in extra]
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
        c(e["userAgent"] == "yugu-ios-stcompat-sdk/2.1.0", "User-Agent %r" % e["userAgent"])
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


def s_local_errors_no_network(c):
    reset()
    cases = [
        (("--core-type", "open.eval", "--audio", audio("zh_short.wav")), 60003),
        (("--core-type", "align.eval", "--audio", audio("zh_short.wav")), 60003),
        (("--core-type", "my.custom.core", "--audio", audio("zh_short.wav")), 60003),
        (("--ref-text", "", "--audio", audio("zh_short.wav")), 60006),
        (("--audio-synth-ms", "500",), 60005),
        (("--audio", "/nonexistent/audio.wav"), 60001),
        (("--audio", "-"), 60002),
    ]
    messages = {60003: "coreType 不支持", 60006: "refText 为空", 60005: "音频短于 1 秒", 60002: "音频为空"}
    for args, want in cases:
        r = driver(*args)
        c(r["outcome"] == "error" and r["json"].get("errId") == want and r["attempts"] == 0,
          "%s -> errId %d, got %r" % (" ".join(args), want, r["json"]))
        if want in messages:
            c(r["json"].get("error") == messages[want], "errId %d message %r" % (want, r["json"].get("error")))
        if want == 60001:
            c(r["json"].get("error") == "音频文件不存在或不可读: /nonexistent/audio.wav", "60001 message")
    r = driver("--core-type", "sent.eval", "--ref-text", "", "--ref-pinyin", "ni3 hao3", "--audio", audio("zh_short.wav"))
    c(r["json"].get("errId") == 60006, "refPinyin does not replace refText for an English kernel")
    c(mock_log() == [], "no request reached the platform for local errors")


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


SCENARIOS = [
    s_success_envelope, s_word_params_custom, s_attach_audio_url, s_retry_500_same_key, s_retry_429_retry_after, s_timeout_then_replay,
    s_slow_inflight_replay, s_inflight_conflict_40901, s_nonce_reuse_rejected, s_no_retry_400, s_retries_exhausted, s_auto_retry, s_auto_retry_custom_errid, s_drop_then_success,
    s_hang_then_success, s_fastapi_detail, s_auth_errors, s_local_errors_no_network, s_pinyin, s_fixture_passthrough,
    s_paragraph_word_details,
    s_realtime_progress, s_realtime_english, s_realtime_refused_rest, s_realtime_kill_before_end,
    s_realtime_kill_after_end_reconnect, s_realtime_retries_exhausted, s_realtime_error_not_retryable,
    s_realtime_progress_mismatch, s_realtime_progress_off, s_realtime_heartbeat, s_realtime_result_timeout_replay,
    s_realtime_too_short,
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
    global ARGS, PORT
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
    results = []

    def stop_mock(signum, frame):  # noqa: ARG001 - signal handler signature
        proc.terminate()
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, stop_mock)
    signal.signal(signal.SIGINT, stop_mock)
    try:
        line = proc.stdout.readline()
        PORT = json.loads(line)["port"]
        log("mock platform listening on 127.0.0.1:%d (pid %d)" % (PORT, proc.pid))
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
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        log("mock platform stopped (exit %s)" % proc.returncode)
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
