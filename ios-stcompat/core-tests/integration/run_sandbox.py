#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Sandbox end-to-end cases of the STKouyuEngine C core against the real platform (acceptance A-05-2).

The cases run it_driver built with -DYGST_IT_TLS: the same C core path as the mock integration
tests (coreType check, local audio checks, field mapping, signing, multipart, retry controller,
envelope, result alignment), over HTTPS to YUGU_SANDBOX_BASE with certificate verification.

  run_sandbox.py --spec ../spec [--driver build/it_driver_tls] [--junit sandbox.xml] [--log sandbox.log]

Runs only when YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are set (YUGU_SANDBOX_BASE defaults to
https://open.shengzhiai.com). Otherwise it writes a JUnit report with skipped cases and exits 77,
so push builds never call the platform. The cases run one after another (the sandbox account
allows 2 concurrent evaluations): three evaluations, then two error paths the platform answers
without evaluating, sent once. No autoRetry. A run makes at most 6 platform calls: an evaluation
gets one retry only while every later case still has its call.

The keys reach the driver through its environment, never through argv. Nothing printed or
written carries them: applicationId is compared with the appKey, never shown, every line is
scrubbed of both values, and the envelope itself is never printed.
"""
import argparse
import copy
import json
import os
import re
import subprocess
import sys
import time
import traceback
import urllib.parse

MAX_CALLS = 6
USER_ID = "sandbox-ios-stcompat"
DEFAULT_BASE = "https://open.shengzhiai.com"
SKIP_REASON = ("YUGU_SANDBOX_APPKEY and YUGU_SANDBOX_SECRET are not set: the sandbox cases call the real "
               "platform and are skipped")
DT = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3}$")
ENVELOPE_KEYS = ["tokenId", "recordId", "applicationId", "userId", "refText", "eof", "dtLastResponse", "result"]

ARGS = None
APPKEY = None
SECRET = None
BASE = DEFAULT_BASE
CHILD_ENV = None
AUTH_CODES = set()
LOG_LINES = []
CALLS = {"used": 0}
LATER = {"cases": 0}  # cases still to run after the current one, each needs one call


def env_value(name):
    v = os.environ.get(name)
    v = v.strip() if v else ""
    return v or None


def redact(text):
    text = str(text)
    for v in (SECRET, APPKEY):
        if v:
            text = text.replace(v, "***")
    return text


def log(msg):
    msg = redact(msg)
    LOG_LINES.append(msg)
    print(msg, flush=True)


def audio(name):
    return os.path.join(ARGS.spec, "fixtures", "audio", name)


def is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def aligned(result):
    """Independent reference of the DESIGN 6 result-shape alignment (the one run_it.py uses)."""
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


def describe(j):
    """errId, error and recordId only: the envelope also carries applicationId, which is never shown."""
    return "errId=%s error=%s recordId=%s" % (j.get("errId"), j.get("error"), j.get("recordId"))


class Check:
    def __init__(self):
        self.failures = []
        self.count = 0

    def __call__(self, cond, what):
        self.count += 1
        if not cond:
            self.failures.append(redact(what))
        return cond


def driver(*extra, retries=1, app_key=None, secret=None):
    """One call through the C core: 1 platform request plus at most `retries` retries, fewer when
    the budget must keep one call for each later case. app_key and secret replace the sandbox keys,
    which otherwise come from the environment."""
    remaining = MAX_CALLS - CALLS["used"]
    if remaining - LATER["cases"] < 1:
        raise AssertionError("call budget of %d platform calls per run: %d left for this case and %d later ones" % (
            MAX_CALLS, remaining, LATER["cases"]))
    retries = max(0, min(retries, remaining - 1 - LATER["cases"]))
    cmd = [ARGS.driver, "--base-url", BASE, "--user-id", USER_ID, "--max-retries", str(retries),
           "--read-timeout-ms", "60000"]
    cmd += ["--app-key", app_key] if app_key is not None else ["--app-key-env", "YUGU_SANDBOX_APPKEY"]
    cmd += ["--secret", secret] if secret is not None else ["--secret-env", "YUGU_SANDBOX_SECRET"]
    cmd += [str(x) for x in extra]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=420, env=CHILD_ENV)
    if p.returncode != 0:
        raise AssertionError("driver exit %d: %s" % (p.returncode, redact(p.stderr.strip())))
    lines = [l for l in p.stdout.splitlines() if l.strip()]
    if len(lines) != 1:
        raise AssertionError("driver printed %d lines" % len(lines))
    out = json.loads(lines[0])
    CALLS["used"] += out["attempts"]
    out["_stderr"] = p.stderr
    return out


def success_envelope(c, r, ref_text, extra_keys=()):
    j = r["json"]
    if not c(r["outcome"] == "result" and "errId" not in j, "platform answered with an error: %s, driver log: %s" % (
            describe(j), r["_stderr"].strip())):
        return False
    c(re.fullmatch(r"[0-9a-f]{32}", r["tokenId"] or "") is not None, "tokenId is 32 lowercase hex")
    c(j.get("tokenId") == r["tokenId"], "envelope tokenId is the Idempotency-Key that was sent")
    c(isinstance(j.get("recordId"), str) and j["recordId"] != "", "recordId from the platform")
    c(j.get("applicationId") == APPKEY, "applicationId is not the appKey")
    c(j.get("userId") == USER_ID, "userId")
    c(j.get("refText") == ref_text, "refText")
    c(j.get("eof") == 1, "eof 1")
    c(DT.match(j.get("dtLastResponse", "")) is not None, "dtLastResponse format")
    c(list(j.keys()) == ENVELOPE_KEYS + list(extra_keys), "envelope keys %r" % list(j.keys()))
    c(isinstance(j.get("result"), dict), "result is an object")
    return isinstance(j.get("result"), dict)


# ------------------------------------------------------------------ cases


def s_sentence(c):
    """sent.eval.cn, zh_short.wav, refText 今天天气很好: success envelope with a numeric result.overall."""
    r = driver("--core-type", "sent.eval.cn", "--ref-text", "今天天气很好", "--audio", audio("zh_short.wav"))
    if not success_envelope(c, r, "今天天气很好"):
        return "attempts=%d" % r["attempts"]
    result = r["json"]["result"]
    overall = result.get("overall")
    c(is_number(overall) and 0 <= overall <= 100, "result.overall numeric in 0..100: %r" % (overall,))
    c(result == aligned(result), "details items carry overall and pronunciation from scores")
    words = result.get("words") if isinstance(result.get("words"), list) else []
    return "overall=%s, words=%d, attempts=%d, recordId=%s" % (overall, len(words), r["attempts"],
                                                               r["json"].get("recordId"))


def s_paragraph(c):
    """para.eval.cn, zh_para.wav and its text: details[] carry overall the way Shengtong's do."""
    text = "今天天气很好。我们一起去公园散步。"
    r = driver("--core-type", "para.eval.cn", "--ref-text", text, "--audio", audio("zh_para.wav"))
    if not success_envelope(c, r, text):
        return "attempts=%d" % r["attempts"]
    result = r["json"]["result"]
    c(is_number(result.get("overall")), "result.overall numeric: %r" % (result.get("overall"),))
    sentences = result.get("sentences") if isinstance(result.get("sentences"), list) else []
    c(len(sentences) > 0, "sentences in the paragraph result")
    details = [d for s in sentences if isinstance(s, dict) for d in (s.get("details") or [])]
    c(len(details) > 0, "details in the paragraph result (paragraph_need_word_score=1 is always sent)")
    no_overall = [d.get("word") for d in details if not is_number(d.get("overall"))]
    c(not no_overall, "details items without a numeric overall: %r" % no_overall[:5])
    differs = [d.get("word") for d in details if isinstance(d.get("scores"), dict) and "overall" in d["scores"]
               and d.get("overall") != d["scores"]["overall"]]
    c(not differs, "details overall differs from scores.overall: %r" % differs[:5])
    no_pron = [d.get("word") for d in details if isinstance(d.get("scores"), dict)
               and "pronunciation" in d["scores"] and "pronunciation" not in d]
    c(not no_pron, "details items without pronunciation: %r" % no_pron[:5])
    c(result == aligned(result), "alignment reference agrees")
    return "overall=%s, sentences=%d, details=%d, attempts=%d, recordId=%s" % (
        result.get("overall"), len(sentences), len(details), r["attempts"], r["json"].get("recordId"))


def url_shape(url):
    """scheme, host and file type of a URL: the full download URL is not printed."""
    if not isinstance(url, str):
        return repr(type(url).__name__)
    u = urllib.parse.urlsplit(url)
    return "%s://%s/...%s" % (u.scheme, u.hostname, os.path.splitext(u.path)[1])


def s_attach_audio_url(c):
    """sent.eval.cn with attachAudioUrl=1: the envelope ends with the platform's audioUrl, an https URL."""
    r = driver("--core-type", "sent.eval.cn", "--ref-text", "今天天气很好", "--audio", audio("zh_short.wav"),
               "--attach-audio-url")
    if not success_envelope(c, r, "今天天气很好", extra_keys=["audioUrl"]):
        return "attempts=%d" % r["attempts"]
    url = r["json"].get("audioUrl")
    c(isinstance(url, str) and url.startswith("https://"), "audioUrl is an https URL: %s" % url_shape(url))
    c(is_number(r["json"]["result"].get("overall")), "result.overall numeric")
    names_record = isinstance(url, str) and r["json"].get("recordId", "") in url
    return "audioUrl %s, names the recordId: %s, attempts=%d, recordId=%s" % (
        url_shape(url), names_record, r["attempts"], r["json"].get("recordId"))


def error_json(c, r, app_key):
    j = r["json"]
    c(r["outcome"] == "error" and "result" not in j, "expected an error JSON, got outcome %s recordId %s" % (
        r["outcome"], j.get("recordId")))
    c(r["attempts"] == 1, "a non-retryable platform error is sent once, attempts %d" % r["attempts"])
    c(j.get("eof") == 1 and j.get("tokenId") == r["tokenId"], "eof 1 and tokenId")
    c(j.get("applicationId") == app_key, "applicationId is not the appKey that was sent")
    c(isinstance(j.get("error"), str) and j["error"] != "", "error message")
    c(list(j.keys()) == ["tokenId", "errId", "error", "eof", "applicationId"], "error JSON keys %r" % list(j.keys()))
    return j


def s_unknown_app_key(c):
    """An appKey the platform does not know: an authentication errId, no retry, no evaluation."""
    key = "no-such-app-key-%x" % int(time.time() * 1000)
    r = driver("--core-type", "sent.eval.cn", "--ref-text", "今天天气很好", "--audio", audio("zh_short.wav"),
               retries=0, app_key=key, secret="not-a-secret")
    j = error_json(c, r, key)
    c(j.get("errId") in AUTH_CODES, "errId %r is not an authentication code of spec/errors.json (%s)" % (
        j.get("errId"), describe(j)))
    return "errId=%s, error=%s, attempts=%d" % (j.get("errId"), j.get("error"), r["attempts"])


def s_pinyin_without_ref_pinyin(c):
    """pinyin without refPinyin: the platform's 400 40001 as errId 40001 with its message, no retry."""
    r = driver("--core-type", "pinyin", "--ref-text", "重庆", "--audio", audio("zh_short.wav"), retries=0)
    j = error_json(c, r, APPKEY)
    c(j.get("errId") == 40001, "errId 40001 from the platform code (%s)" % describe(j))
    c("refPinyin" in str(j.get("error")), "the platform message names refPinyin: %r" % j.get("error"))
    return "errId=%s, error=%s, attempts=%d" % (j.get("errId"), j.get("error"), r["attempts"])


CASES = [s_sentence, s_paragraph, s_attach_audio_url, s_unknown_app_key, s_pinyin_without_ref_pinyin]


def write_junit(path, results, skipped=None):
    from xml.sax.saxutils import escape, quoteattr
    fails = sum(1 for r in results if r["failures"])
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<testsuite name="ios-stcompat-sandbox" tests="%d" failures="%d" skipped="%d">\n' % (
            len(results), fails, len(results) if skipped else 0))
        for r in results:
            f.write('  <testcase classname="sandbox" name=%s time="%.3f"' % (quoteattr(r["name"]), r["seconds"]))
            if skipped:
                f.write("><skipped message=%s/></testcase>\n" % quoteattr(skipped))
            elif r["failures"]:
                f.write("><failure message=%s>%s</failure></testcase>\n" % (
                    quoteattr(r["failures"][0][:300]), escape("\n".join(r["failures"]))))
            else:
                f.write("/>\n")
        f.write("</testsuite>\n")


def finish(results, code, skipped=None):
    if ARGS.junit:
        write_junit(ARGS.junit, results, skipped)
    if ARGS.log:
        with open(ARGS.log, "w", encoding="utf-8") as f:
            f.write("\n".join(LOG_LINES) + "\n")
    return code


def main():
    global ARGS, APPKEY, SECRET, BASE, CHILD_ENV
    ap = argparse.ArgumentParser()
    ap.add_argument("--driver", help="it_driver built with -DYGST_IT_TLS (needed when the keys are set)")
    ap.add_argument("--spec", required=True)
    ap.add_argument("--junit")
    ap.add_argument("--log")
    ARGS = ap.parse_args()
    APPKEY = env_value("YUGU_SANDBOX_APPKEY")
    SECRET = env_value("YUGU_SANDBOX_SECRET")
    BASE = env_value("YUGU_SANDBOX_BASE") or DEFAULT_BASE
    if not APPKEY or not SECRET:
        log("sandbox: " + SKIP_REASON)
        return finish([{"name": fn.__name__[2:], "failures": [], "seconds": 0.0} for fn in CASES], 77, SKIP_REASON)
    if not ARGS.driver or not os.access(ARGS.driver, os.X_OK):
        log("sandbox: the keys are set but there is no it_driver built with -DYGST_IT_TLS (--driver); "
            "building it needs the OpenSSL development files")
        return finish([], 1)
    CHILD_ENV = dict(os.environ, YUGU_SANDBOX_APPKEY=APPKEY, YUGU_SANDBOX_SECRET=SECRET)
    with open(os.path.join(ARGS.spec, "errors.json"), encoding="utf-8") as f:
        AUTH_CODES.update(e["code"] for e in json.load(f)["errors"] if e.get("category") == "AUTH")
    u = urllib.parse.urlsplit(BASE)
    log("sandbox: %s://%s, appKey from YUGU_SANDBOX_APPKEY, at most %d platform calls, one at a time" % (
        u.scheme, u.hostname, MAX_CALLS))
    results = []
    for i, fn in enumerate(CASES):
        LATER["cases"] = len(CASES) - i - 1
        c = Check()
        t0 = time.time()
        summary = ""
        try:
            summary = fn(c) or ""
        except Exception as e:  # noqa: BLE001 - report every failure as a test failure
            c.failures.append(redact("exception: %s\n%s" % (e, traceback.format_exc())))
        dt = time.time() - t0
        results.append({"name": fn.__name__[2:], "failures": c.failures, "seconds": dt, "checks": c.count})
        log("%s %s (%d checks, %.2f s) %s" % ("ok  " if not c.failures else "FAIL", fn.__name__[2:], c.count, dt,
                                              summary))
        for f in c.failures:
            log("     " + f.replace("\n", "\n     "))
    over = CALLS["used"] > MAX_CALLS
    if over:
        log("FAIL more platform calls than the budget")
    failed = [r for r in results if r["failures"]]
    log("sandbox: %d cases, %d failed, %d checks, %d platform calls of at most %d" % (
        len(results), len(failed), sum(r["checks"] for r in results), CALLS["used"], MAX_CALLS))
    return finish(results, 1 if failed or over else 0)


if __name__ == "__main__":
    sys.exit(main())
