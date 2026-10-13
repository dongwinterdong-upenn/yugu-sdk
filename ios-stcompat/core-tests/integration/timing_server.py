#!/usr/bin/env python3
# Copyright 2026 优谷雅言 open.shengzhiai.com
# SPDX-License-Identifier: Apache-2.0
"""Test-local stand-in for the platform features of 2.1.1 that tools/mock-server does not have.

The mock platform has no compression and no timing, and is shared (BRIEF 2.1.1 rule 5), so the
tests of this package start this server instead for those cases:

- POST /{coreType}: the compat REST call. The response always carries
  Server-Timing: total;dur=<ms>, engine;dur=<ms>, platform;dur=<ms>, the body gains the top-level
  "timing" object when the form has timing=1, and the body is gzip-compressed when the request
  accepts gzip (the platform's nginx since 2026-10-12).
- GET /{coreType} with Upgrade: websocket: the compat WebSocket round (connected, parameter frame,
  started, audio, end, final). The final carries "timing" with wsFirstAudioAt when the parameter
  frame has "timing":1. permessage-deflate (RFC 7692) is negotiated the way Tomcat does it when
  the client offers it: client_max_window_bits=15 in the answer only when offered, context
  takeover unless no_context_takeover was asked for, every message compressed, a long message sent
  in fragments of 8 KB with RSV1 on the first one only. Compressed client messages are inflated.
- POST /__ctl/config {"deflate":"accept"|"decline","fragment":8192,"resultWords":N,"gzip":true,
  "serverTiming":true}, POST /__ctl/reset, GET /__ctl/log: what each request carried.

Signatures are not checked: the mock platform covers them. Stdlib only.

  timing_server.py [--port 0]      prints {"port": N} on the first line of stdout
"""
import argparse
import base64
import gzip
import hashlib
import json
import socketserver
import sys
import threading
import time
import zlib

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
SERVER_TIMING = {"total": 321, "engine": 300, "platform": 21}
ENGINE_STAGES = {"text_frontend_ms": 12.5, "asr_ms": 33.0, "pipeline_total_ms": 290.0}
DEFAULTS = {"deflate": "accept", "fragment": 8192, "resultWords": 40, "gzip": True, "serverTiming": True}

LOCK = threading.Lock()
STATE = {"config": dict(DEFAULTS), "log": [], "seq": 0}


def now_ms():
    return int(time.time() * 1000)


def next_seq():
    with LOCK:
        STATE["seq"] += 1
        return STATE["seq"]


def config():
    with LOCK:
        return dict(STATE["config"])


def record(entry):
    with LOCK:
        STATE["log"].append(entry)


def result_object(core_type, words):
    """A paragraph-like result: repetitive word items, so that it compresses like the real one."""
    items = []
    for i in range(words):
        items.append({"word": "w%d" % i, "charType": 0, "scores": {"overall": 90 + i % 10, "pronunciation": 91,
                                                                   "tone": 88, "fluency": 87},
                      "word_parts": [{"part": "w%d" % i, "beginIndex": 0, "endIndex": 1, "charType": 0}],
                      "start": i * 300, "end": i * 300 + 280})
    return {"overall": 88.5, "pronunciation": 90, "fluency": 87, "integrity": 100, "coreType": core_type,
            "kernel_version": "1.0.0", "resource_version": "1.0.0", "words": items}


def timing_object(received_at, audio_bytes, first_audio_at=None):
    t = {"receivedAt": received_at, "totalMs": SERVER_TIMING["total"], "engineMs": SERVER_TIMING["engine"],
         "platformMs": SERVER_TIMING["platform"], "engineStages": dict(ENGINE_STAGES), "audioBytes": audio_bytes}
    if first_audio_at is not None:
        t["wsFirstAudioAt"] = first_audio_at
    return t


def wants_timing(v):
    return v is True or v == 1 or (isinstance(v, str) and v.strip().lower() in ("1", "true"))


# ------------------------------------------------------------------ HTTP


class Request:
    def __init__(self, method, target, headers, body):
        self.method = method
        self.target = target
        self.path = target.split("?", 1)[0]
        self.query = target.split("?", 1)[1] if "?" in target else ""
        self.headers = headers
        self.body = body

    def header(self, name):
        return self.headers.get(name.lower(), "")


def read_request(rfile):
    line = rfile.readline(65536)
    if not line:
        return None
    parts = line.decode("latin-1").rstrip("\r\n").split(" ")
    if len(parts) < 3:
        return None
    headers = {}
    while True:
        h = rfile.readline(65536)
        if not h or h in (b"\r\n", b"\n"):
            break
        k, _, v = h.decode("latin-1").partition(":")
        headers[k.strip().lower()] = v.strip()
    body = b""
    if headers.get("transfer-encoding", "").lower() == "chunked":
        while True:
            size = int(rfile.readline().split(b";")[0].strip() or b"0", 16)
            if size == 0:
                while rfile.readline() not in (b"\r\n", b"\n", b""):
                    pass
                break
            body += rfile.read(size)
            rfile.readline()
    elif headers.get("content-length"):
        body = rfile.read(int(headers["content-length"]))
    return Request(parts[0], parts[1], headers, body)


def multipart_fields(req):
    ctype = req.header("content-type")
    marker = "boundary="
    if marker not in ctype:
        return {}, 0
    boundary = ctype.split(marker, 1)[1].split(";")[0].strip().strip('"').encode("latin-1")
    fields = {}
    audio = 0
    for part in req.body.split(b"--" + boundary):
        if part in (b"", b"--", b"--\r\n") or part.startswith(b"--"):
            continue
        head, _, value = part.lstrip(b"\r\n").partition(b"\r\n\r\n")
        if value.endswith(b"\r\n"):
            value = value[:-2]
        disp = head.decode("utf-8", "replace")
        name = disp.split('name="', 1)[1].split('"', 1)[0] if 'name="' in disp else ""
        if "filename=" in disp:
            audio += len(value)
        elif name:
            fields[name] = value.decode("utf-8", "replace")
    return fields, audio


def send_response(wfile, status, body, headers):
    reason = {200: "OK", 400: "Bad Request", 404: "Not Found"}.get(status, "OK")
    head = ["HTTP/1.1 %d %s" % (status, reason), "Content-Length: %d" % len(body), "Connection: close"]
    head += ["%s: %s" % kv for kv in headers]
    wfile.write(("\r\n".join(head) + "\r\n\r\n").encode("latin-1") + body)
    wfile.flush()


def json_response(wfile, obj, status=200):
    send_response(wfile, status, json.dumps(obj).encode(), [("Content-Type", "application/json")])


def compat_rest(req, wfile):
    received_at = now_ms()
    cfg = config()
    core_type = req.path.lstrip("/")
    fields, audio = multipart_fields(req)
    out = {"recordId": "eval_rest%d" % next_seq(), "eof": 1, "result": result_object(core_type, cfg["resultWords"])}
    if wants_timing(fields.get("timing")):
        out["timing"] = timing_object(received_at, audio)
    body = json.dumps(out, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    plain = len(body)
    headers = [("Content-Type", "application/json;charset=UTF-8")]
    if cfg["serverTiming"]:
        headers.append(("Server-Timing", "total;dur=%d, engine;dur=%d, platform;dur=%d" % (
            SERVER_TIMING["total"], SERVER_TIMING["engine"], SERVER_TIMING["platform"])))
    zipped = cfg["gzip"] and "gzip" in req.header("accept-encoding").lower()
    if zipped:
        body = gzip.compress(body)
        headers.append(("Content-Encoding", "gzip"))
        headers.append(("Vary", "Accept-Encoding"))
    record({"kind": "rest", "path": req.path, "fields": fields, "audioBytes": audio,
            "acceptEncoding": req.header("accept-encoding"), "userAgent": req.header("user-agent"),
            "idempotencyKey": req.header("idempotency-key"), "gzip": zipped, "bodyBytes": plain, "wireBytes": len(body)})
    send_response(wfile, 200, body, headers)


# ------------------------------------------------------------------ WebSocket


def parse_extension_offers(value):
    """Sec-WebSocket-Extensions as a list of (name, {param: value or None})."""
    offers = []
    for offer in value.split(","):
        items = [x.strip() for x in offer.split(";") if x.strip()]
        if not items:
            continue
        params = {}
        for p in items[1:]:
            k, eq, v = p.partition("=")
            params[k.strip().lower()] = v.strip().strip('"') if eq else None
        offers.append((items[0].lower(), params))
    return offers


def negotiate_deflate(offered):
    """Tomcat's PerMessageDeflate.negotiate: the first acceptable permessage-deflate offer."""
    for name, params in parse_extension_offers(offered):
        if name != "permessage-deflate":
            continue
        smwb = params.get("server_max_window_bits")
        if smwb is not None and smwb != "15":
            continue  # the JDK deflater cannot use a smaller window
        answer = ["permessage-deflate"]
        agreed = {"server_no_context_takeover": "server_no_context_takeover" in params,
                  "client_no_context_takeover": "client_no_context_takeover" in params}
        if agreed["server_no_context_takeover"]:
            answer.append("server_no_context_takeover")
        if smwb is not None:
            answer.append("server_max_window_bits=15")
        if agreed["client_no_context_takeover"]:
            answer.append("client_no_context_takeover")
        if "client_max_window_bits" in params:
            answer.append("client_max_window_bits=15")
        return ";".join(answer), agreed
    return None, None


class WsSession:
    def __init__(self, sock, rfile, entry, agreed, fragment):
        self.sock = sock
        self.rfile = rfile
        self.entry = entry
        self.agreed = agreed  # None: no deflate
        self.fragment = max(16, int(fragment))
        self.compressor = None
        self.inflater = None
        if agreed is not None:
            self.compressor = zlib.compressobj(zlib.Z_DEFAULT_COMPRESSION, zlib.DEFLATED, -15)
            self.inflater = zlib.decompressobj(-15)
        self.lock = threading.Lock()

    # outgoing: server frames are not masked
    def _frame(self, fin, rsv1, opcode, payload):
        b0 = (0x80 if fin else 0) | (0x40 if rsv1 else 0) | opcode
        n = len(payload)
        if n < 126:
            head = bytes([b0, n])
        elif n < 65536:
            head = bytes([b0, 126]) + n.to_bytes(2, "big")
        else:
            head = bytes([b0, 127]) + n.to_bytes(8, "big")
        return head + payload

    def send_message(self, opcode, data):
        """A data message: compressed when deflate was agreed, in fragments of at most fragment bytes."""
        compressed = self.compressor is not None
        if compressed:
            if self.agreed["server_no_context_takeover"]:
                self.compressor = zlib.compressobj(zlib.Z_DEFAULT_COMPRESSION, zlib.DEFLATED, -15)
            payload = self.compressor.compress(data) + self.compressor.flush(zlib.Z_SYNC_FLUSH)
            if payload.endswith(b"\x00\x00\xff\xff"):
                payload = payload[:-4]
        else:
            payload = data
        pieces = [payload[i:i + self.fragment] for i in range(0, len(payload), self.fragment)] or [b""]
        out = b"".join(self._frame(i == len(pieces) - 1, compressed and i == 0, opcode if i == 0 else 0, p)
                       for i, p in enumerate(pieces))
        with self.lock:
            self.sock.sendall(out)
        return len(payload), len(pieces), compressed

    def send_json(self, obj):
        return self.send_message(0x1, json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))

    def send_control(self, opcode, payload=b""):
        with self.lock:
            self.sock.sendall(self._frame(True, False, opcode, payload))

    # incoming: client frames are masked
    def read_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.rfile.read(n - len(data))
            if not chunk:
                raise ConnectionError("closed")
            data += chunk
        return data

    def read_frame(self):
        b0, b1 = self.read_exact(2)
        n = b1 & 0x7F
        if n == 126:
            n = int.from_bytes(self.read_exact(2), "big")
        elif n == 127:
            n = int.from_bytes(self.read_exact(8), "big")
        mask = self.read_exact(4) if b1 & 0x80 else b"\0\0\0\0"
        payload = bytearray(self.read_exact(n))
        for i in range(n):
            payload[i] ^= mask[i & 3]
        return bool(b0 & 0x80), bool(b0 & 0x40), b0 & 0x0F, bytes(payload), bool(b1 & 0x80)

    def read_message(self):
        """The next data message as (opcode, bytes, compressed); control frames are answered here."""
        opcode = None
        compressed = False
        parts = []
        while True:
            fin, rsv1, op, payload, masked = self.read_frame()
            if not masked:
                self.entry["unmaskedFrames"] = self.entry.get("unmaskedFrames", 0) + 1
            if op == 0x8:
                self.send_control(0x8, payload[:2])
                return None, None, False
            if op == 0x9:
                self.send_control(0xA, payload)
                self.entry["pings"] = self.entry.get("pings", 0) + 1
                continue
            if op == 0xA:
                continue
            if op != 0:
                opcode = op
                compressed = rsv1
            parts.append(payload)
            if fin:
                break
        data = b"".join(parts)
        if compressed:
            if self.inflater is None:
                raise ConnectionError("RSV1 without permessage-deflate")
            if self.agreed["client_no_context_takeover"]:
                self.inflater = zlib.decompressobj(-15)
            data = self.inflater.decompress(data + b"\x00\x00\xff\xff")
        return opcode, data, compressed


def compat_ws(req, sock, rfile):
    cfg = config()
    key = req.header("sec-websocket-key")
    offered = req.header("sec-websocket-extensions")
    entry = {"kind": "ws", "path": req.path, "query": req.query, "offered": offered or None, "negotiated": None,
             "userAgent": req.header("user-agent"), "params": None, "paramCompressed": None,
             "clientCompressedMessages": 0, "audioBytes": 0, "audioFrames": 0, "end": False,
             "finalBytes": None, "finalWireBytes": None, "finalFrames": None, "finalCompressed": None}
    record(entry)
    accept = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    answer, agreed = (negotiate_deflate(offered) if offered and cfg["deflate"] == "accept" else (None, None))
    head = ["HTTP/1.1 101 Switching Protocols", "Upgrade: websocket", "Connection: Upgrade",
            "Sec-WebSocket-Accept: " + accept]
    if answer:
        head.append("Sec-WebSocket-Extensions: " + answer)
        entry["negotiated"] = answer
    sock.sendall(("\r\n".join(head) + "\r\n\r\n").encode("latin-1"))
    ws = WsSession(sock, rfile, entry, agreed, cfg["fragment"])
    core_type = req.path.lstrip("/")
    params = None
    audio = 0
    first_audio_at = None
    ws.send_json({"event": "connected", "coreType": core_type})
    try:
        while True:
            opcode, data, compressed = ws.read_message()
            if opcode is None:
                break
            if compressed:
                entry["clientCompressedMessages"] += 1
            if opcode == 0x2:
                audio += len(data)
                entry["audioBytes"] = audio
                entry["audioFrames"] += 1
                if first_audio_at is None:
                    first_audio_at = now_ms()
                continue
            msg = json.loads(data.decode("utf-8"))
            if msg.get("cmd") == "ping":
                ws.send_json({"event": "pong", "ts": now_ms()})
            elif msg.get("cmd") == "end" or msg.get("end") is True:
                received_at = now_ms()
                entry["end"] = True
                final = {"recordId": "eval_ws%d" % next_seq(), "eof": 1,
                         "result": result_object(core_type, cfg["resultWords"])}
                if params is not None and wants_timing(params.get("timing")):
                    final["timing"] = timing_object(received_at, audio, first_audio_at)
                text = json.dumps(final, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                wire, frames, packed = ws.send_message(0x1, text)
                entry.update({"finalBytes": len(text), "finalWireBytes": wire, "finalFrames": frames,
                              "finalCompressed": packed})
            else:
                params = msg
                entry["params"] = msg
                entry["paramCompressed"] = compressed
                ws.send_json({"event": "started", "coreType": core_type})
    except (ConnectionError, OSError, ValueError, zlib.error) as e:
        entry["closedBy"] = str(e)


# ------------------------------------------------------------------ server


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        req = read_request(self.rfile)
        if req is None:
            return
        if req.path == "/__ctl/config" and req.method == "POST":
            with LOCK:
                STATE["config"].update(json.loads(req.body.decode() or "{}"))
                cfg = dict(STATE["config"])
            json_response(self.wfile, cfg)
        elif req.path == "/__ctl/reset" and req.method == "POST":
            with LOCK:
                STATE["config"] = dict(DEFAULTS)
                STATE["log"] = []
            json_response(self.wfile, {"ok": True})
        elif req.path == "/__ctl/log" and req.method == "GET":
            with LOCK:
                snapshot = json.loads(json.dumps(STATE["log"]))
            json_response(self.wfile, snapshot)
        elif req.method == "GET" and req.header("upgrade").lower() == "websocket":
            compat_ws(req, self.connection, self.rfile)
        elif req.method == "POST" and req.path.count("/") == 1 and len(req.path) > 1:
            compat_rest(req, self.wfile)
        else:
            json_response(self.wfile, {"code": 40400, "message": "not found"}, 404)


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=0)
    a = ap.parse_args()
    srv = Server(("127.0.0.1", a.port), Handler)
    sys.stdout.write(json.dumps({"port": srv.server_address[1]}) + "\n")
    sys.stdout.flush()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
