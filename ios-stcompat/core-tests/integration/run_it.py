#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Integration tests of the STKouyuEngine C core against the mock platform.

Starts tools/mock-server/server.mjs on a free port, drives it_driver through the scenarios of
DESIGN 10 (V-02 replay and billing, V-03 retry with the same Idempotency-Key, no retry on 400)
plus the Shengtong compat rules (errIds, envelope, no network I/O for local errors), asserts on
the driver output and on the mock's /__mock/log and /__mock/billing, writes a JUnit report and
stops the mock server in every case.

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
        c(e["userAgent"] == "yugu-ios-stcompat-sdk/2.0.0", "User-Agent %r" % e["userAgent"])
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


SCENARIOS = [
    s_success_envelope, s_word_params_custom, s_attach_audio_url, s_retry_500_same_key, s_retry_429_retry_after, s_timeout_then_replay,
    s_slow_inflight_replay, s_inflight_conflict_40901, s_nonce_reuse_rejected, s_no_retry_400, s_retries_exhausted, s_auto_retry, s_auto_retry_custom_errid, s_drop_then_success,
    s_hang_then_success, s_fastapi_detail, s_auth_errors, s_local_errors_no_network, s_pinyin, s_fixture_passthrough,
    s_paragraph_word_details,
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
