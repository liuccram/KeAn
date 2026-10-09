#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""课安「两个账号 + 私信 + IM 通道」端到端测试（在服务器上直接跑）。

用法（服务器上）：

    IM_RPW='<redis 密码>' python3 docs/ops/e2e-im-test.py

    可选环境变量：
      KEAN_BASE            默认 http://127.0.0.1:8080   （直连后端，绕开 Cloudflare / nginx）
      KEAN_WS_BASE         默认由 KEAN_BASE 推导（http->ws / https->wss），自研 WS 走 /ws/chat
      IM_WS_URL            默认 ws://127.0.0.1:8878/im  （im-server，路径写死 /im）
      IM_RPW               Redis 密码（不设置则所有 Redis 断言降级为 SKIP，其余照跑）
      KEAN_REDIS_CTR       默认 kean-redis-kean-redis-1 （docker exec 方式访问 Redis）
      KEAN_REDIS_HOST/PORT 默认 127.0.0.1 / 26739      （docker 方式不可用时的本机 redis-cli 回退）
      KEAN_UNIT            默认 kean                    （journalctl -u <unit> 抓验证码 / 抓镜像开关）
      KEAN_E2E_TAG         默认随机 6 位十六进制         （账号名后缀；固定它可重复跑并复用账号）
      KEAN_E2E_PASSWORD    默认脚本内随机生成的强密码     （固定 KEAN_E2E_TAG 时必须一并固定）
      KEAN_SCHOOL_ID       默认自动从 GET /api/schools 取最小 id
      KEAN_IM_TERMINAL     默认 app（=1）               （GET /api/im/token?terminal=）
      KEAN_E2E_WAIT         默认 8                      （等待 WS 帧的秒数）

设计约束（与仓库代码逐条核对过，见本文件末尾的契约注释）：
  * 纯 Python 3 标准库，无第三方依赖；HTTPS 自签证书用 ssl._create_unverified_context()。
  * 不硬编码任何密钥 / 密码；Redis 密码只从 IM_RPW 读。
  * 任何失败都不抛异常中断：打印实际拿到的内容（截断 200 字符）后继续跑能跑的步骤。
  * 退出码：硬断言全过 = 0，否则 = 1（SKIP 与 OBS 不影响退出码）。
  * 可重复运行：默认账号名带随机后缀（每次全新注册）；固定 KEAN_E2E_TAG 时第二次起自动走登录分支。
"""

import argparse
import base64
import json
import os
import random
import re
import socket
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

# --------------------------------------------------------------------------
# 通用工具
# --------------------------------------------------------------------------

CUT_LIMIT = 200

# 测试账号的邮箱 / 密码用系统级随机源（脚本不涉及任何真实凭证，但不用可预测的 PRNG）
_RNG = random.SystemRandom()


def cut(value, limit=CUT_LIMIT):
    """把任意值变成单行、截断到 limit 字符的字符串（用于打印原始响应）。"""
    if isinstance(value, str):
        text = value
    else:
        try:
            text = json.dumps(value, ensure_ascii=False)
        except Exception:
            text = repr(value)
    text = text.replace("\r", " ").replace("\n", " ")
    if len(text) > limit:
        return text[:limit] + "…(共 %d 字符)" % len(text)
    return text


def mask(secret):
    """打印 token 时的脱敏（绝不打印完整 token）。"""
    if not secret:
        return "(空)"
    return "%s…(len=%d，已省略)" % (str(secret)[:10], len(str(secret)))


def parse_json_lenient(text):
    """尽量把一段文本解析成 JSON（容忍前后噪声 / 帧头残留）。"""
    if not text:
        return None
    try:
        return json.loads(text)
    except Exception:
        pass
    start = text.find("{")
    if start < 0:
        return None
    try:
        return json.JSONDecoder().raw_decode(text[start:])[0]
    except Exception:
        return None


def decode_jwt_payload(token):
    """解 JWT payload（不校验签名）；失败返回 None。"""
    if not token or token.count(".") < 2:
        return None
    part = token.split(".")[1]
    part += "=" * (-len(part) % 4)
    try:
        raw = base64.urlsafe_b64decode(part.encode())
        return json.loads(raw.decode("utf-8", "replace"))
    except Exception:
        return None


def random_qq_email():
    """完整 QQ 邮箱：8 位数字且首位非 0（QqEmails.REQUIRED_PATTERN 要求 5-11 位、不以 0 开头）。"""
    digits = str(_RNG.randint(1, 9)) + "".join(str(_RNG.randint(0, 9)) for _ in range(7))
    return digits + "@qq.com"


def random_password():
    alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    body = "".join(_RNG.choice(alphabet) for _ in range(14))
    return "Ke" + body + "9"


# --------------------------------------------------------------------------
# 报告器
# --------------------------------------------------------------------------


class Reporter:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.skipped = 0
        self.obs_count = 0
        self.failures = []
        self.observations = []

    @staticmethod
    def _emit(tag, step, title, detail=""):
        print("[%s] %s %s" % (tag, step, title))
        if detail:
            for line in str(detail).splitlines():
                print("        " + line)

    def step(self, number, title):
        print("\n" + "-" * 74)
        print("步骤 %s：%s" % (number, title))
        print("-" * 74)

    def check(self, step, title, ok, detail=""):
        ok = bool(ok)
        if ok:
            self.passed += 1
            self._emit("PASS", step, title, detail)
        else:
            self.failed += 1
            self.failures.append("%s %s" % (step, title))
            self._emit("FAIL", step, title, detail)
        return ok

    def skip(self, step, title, reason=""):
        self.skipped += 1
        self._emit("SKIP", step, title, reason)
        return False

    def obs(self, step, title, detail=""):
        self.obs_count += 1
        self.observations.append("%s %s" % (step, title))
        self._emit("OBS ", step, title, detail)

    def warn(self, step, title, detail=""):
        self._emit("WARN", step, title, detail)


# --------------------------------------------------------------------------
# HTTP 客户端
# --------------------------------------------------------------------------

HTTP_OPENER = urllib.request.build_opener(
    urllib.request.HTTPSHandler(context=ssl._create_unverified_context())
)


class Http:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, body=None, token=None, timeout=25, headers=None):
        url = self.base + path
        hdrs = {"Accept": "application/json", "User-Agent": "kean-e2e-im-test/1.0"}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        if token:
            hdrs["Authorization"] = "Bearer " + token
        if headers:
            hdrs.update(headers)
        req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
        try:
            with HTTP_OPENER.open(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
                status = resp.status
        except urllib.error.HTTPError as ex:
            try:
                raw = ex.read().decode("utf-8", "replace")
            except Exception:
                raw = ""
            status = ex.code
        except Exception as ex:  # 连不上 / 超时 / TLS 失败
            return {"status": 0, "json": None, "text": "", "error": "%s: %s" % (type(ex).__name__, ex)}
        return {"status": status, "json": parse_json_lenient(raw), "text": raw, "error": None}

    @staticmethod
    def code(res):
        body = res.get("json")
        return body.get("code") if isinstance(body, dict) else None

    @staticmethod
    def data(res):
        body = res.get("json")
        if isinstance(body, dict) and isinstance(body.get("data"), dict):
            return body["data"]
        return {}

    @staticmethod
    def describe(res):
        if res.get("error"):
            return "网络层错误：%s" % res["error"]
        body = res.get("json")
        if isinstance(body, dict):
            return "HTTP %s  %s" % (
                res.get("status"),
                cut({"code": body.get("code"), "message": body.get("message"), "data": body.get("data")}),
            )
        return "HTTP %s  %s" % (res.get("status"), cut(res.get("text")))


# --------------------------------------------------------------------------
# WebSocket 客户端（纯 socket + 手写握手 / 帧；比任务书里的片段多了完整帧头解析与分片处理）
# --------------------------------------------------------------------------


class WsClient:
    def __init__(self, url, extra_headers=None, timeout=8.0, heartbeat=None, label=""):
        parsed = urllib.parse.urlparse(url)
        self.url = url
        self.label = label
        self.secure = parsed.scheme == "wss"
        self.host = parsed.hostname or "127.0.0.1"
        self.port = parsed.port or (443 if self.secure else 80)
        self.path = parsed.path or "/"
        if parsed.query:
            self.path += "?" + parsed.query
        self.extra_headers = dict(extra_headers or {})
        self.timeout = timeout
        self.heartbeat = heartbeat
        self.sock = None
        self.buf = b""
        self.frag = b""
        self.status_line = ""
        self.response_headers = {}
        self.received = []  # [{"raw": str, "json": obj|None}]
        self.closed = False
        self.note = ""
        self._last_beat = 0.0

    # ---------------- 握手 ----------------
    def connect(self):
        raw = socket.create_connection((self.host, self.port), timeout=self.timeout)
        if self.secure:
            ctx = ssl._create_unverified_context()
            raw = ctx.wrap_socket(raw, server_hostname=self.host)
        self.sock = raw
        key = base64.b64encode(os.urandom(16)).decode()
        lines = [
            "GET %s HTTP/1.1" % self.path,
            "Host: %s:%d" % (self.host, self.port),
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Key: %s" % key,
            "Sec-WebSocket-Version: 13",
        ]
        for name, value in self.extra_headers.items():
            lines.append("%s: %s" % (name, value))
        raw.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
        head = b""
        deadline = time.monotonic() + self.timeout
        while b"\r\n\r\n" not in head:
            remain = deadline - time.monotonic()
            if remain <= 0:
                raise RuntimeError("WS 握手超时（%s）" % self.url)
            raw.settimeout(max(0.1, remain))
            chunk = raw.recv(4096)
            if not chunk:
                raise RuntimeError("WS 握手期间连接被关闭（%s）" % self.url)
            head += chunk
        header_bytes, rest = head.split(b"\r\n\r\n", 1)
        self.buf = rest
        text = header_bytes.decode("latin-1")
        parts = text.split("\r\n")
        self.status_line = parts[0]
        for line in parts[1:]:
            if ":" in line:
                name, value = line.split(":", 1)
                self.response_headers[name.strip().lower()] = value.strip()
        return self.status_line

    # ---------------- 发送 ----------------
    def _send_frame(self, opcode, payload):
        header = bytearray([0x80 | opcode])
        length = len(payload)
        mask = os.urandom(4)
        if length < 126:
            header.append(0x80 | length)
        elif length < 65536:
            header.append(0x80 | 126)
            header += struct.pack("!H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack("!Q", length)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def send_text(self, text):
        self._send_frame(0x1, text.encode("utf-8"))
        return True

    def _beat_if_needed(self):
        if not self.heartbeat or self.sock is None or self.closed:
            return
        now = time.monotonic()
        if self._last_beat == 0.0:
            self._last_beat = now
            return
        if now - self._last_beat >= 15.0:
            try:
                self.send_text(self.heartbeat)
                self._last_beat = now
            except Exception as ex:
                self.note = "心跳发送失败：%s" % ex

    # ---------------- 接收 ----------------
    def _fill(self, need, deadline):
        while len(self.buf) < need:
            remain = deadline - time.monotonic()
            if remain <= 0:
                raise socket.timeout("ws read deadline")
            self.sock.settimeout(max(0.05, remain))
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError("对端已关闭连接")
            self.buf += chunk

    def _read_frame(self, deadline):
        head, self.buf = self._read_exact(2, deadline)
        byte0, byte1 = head[0], head[1]
        fin = bool(byte0 & 0x80)
        opcode = byte0 & 0x0F
        masked = bool(byte1 & 0x80)
        length = byte1 & 0x7F
        if length == 126:
            ext, self.buf = self._read_exact(2, deadline)
            length = struct.unpack("!H", ext)[0]
        elif length == 127:
            ext, self.buf = self._read_exact(8, deadline)
            length = struct.unpack("!Q", ext)[0]
        mask = b""
        if masked:
            mask, self.buf = self._read_exact(4, deadline)
        payload, self.buf = self._read_exact(length, deadline)
        if masked and payload:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        return fin, opcode, payload

    def _read_exact(self, count, deadline):
        self._fill(count, deadline)
        chunk = self.buf[:count]
        return chunk, self.buf[count:]

    def recv_message(self, deadline):
        """返回 (kind, text)：kind ∈ {text, closed, timeout, error}。"""
        while True:
            try:
                fin, opcode, payload = self._read_frame(deadline)
            except (socket.timeout, TimeoutError):
                # socket.timeout 在 Python 3.10+ 就是 TimeoutError，3.9 及更早是两个类，都要接住
                return "timeout", ""
            except ConnectionError as ex:
                self.closed = True
                return "closed", str(ex)
            except (OSError, ssl.SSLError) as ex:
                self.closed = True
                return "error", "%s: %s" % (type(ex).__name__, ex)
            if opcode == 0x8:
                self.closed = True
                return "closed", "收到 CLOSE 帧"
            if opcode == 0x9:
                try:
                    self._send_frame(0xA, payload)
                except Exception:
                    pass
                continue
            if opcode == 0xA:
                continue
            if opcode in (0x0, 0x1, 0x2):
                self.frag += payload
                if fin:
                    data = self.frag
                    self.frag = b""
                    return "text", data.decode("utf-8", "replace")
                continue
            # 其它帧（连续帧控制）忽略

    def collect(self, seconds, stop=None):
        """在 seconds 秒内收帧，返回本次新收的帧列表；stop(obj) 为真时提前结束。"""
        deadline = time.monotonic() + seconds
        found = []
        while True:
            remain = deadline - time.monotonic()
            if remain <= 0:
                break
            self._beat_if_needed()
            kind, text = self.recv_message(deadline)
            if kind == "timeout":
                break
            if kind in ("closed", "error"):
                if not self.note:
                    self.note = text
                break
            entry = {"raw": text, "json": parse_json_lenient(text)}
            self.received.append(entry)
            found.append(entry)
            if stop is not None and entry["json"] is not None and stop(entry["json"]):
                break
        return found

    def wait_for(self, predicate, seconds):
        """在 seconds 秒内等待满足 predicate 的帧（含历史帧），返回帧 dict 或 None。"""
        for entry in self.received:
            if entry["json"] is not None and predicate(entry["json"]):
                return entry
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            self.collect(max(0.05, deadline - time.monotonic()),
                         stop=lambda obj: predicate(obj))
            for entry in self.received:
                if entry["json"] is not None and predicate(entry["json"]):
                    return entry
        return None

    def history(self, limit=6):
        return " | ".join(cut(e["raw"], 120) for e in self.received[-limit:]) or "(未收到任何帧)"

    def close(self):
        try:
            if self.sock is not None and not self.closed:
                self._send_frame(0x8, struct.pack("!H", 1000))
        except Exception:
            pass
        try:
            if self.sock is not None:
                self.sock.close()
        except Exception:
            pass
        self.closed = True


# --------------------------------------------------------------------------
# Redis（docker exec redis-cli，回退到本机 redis-cli）
# --------------------------------------------------------------------------


class RedisCli:
    def __init__(self, password, container, host, port):
        self.password = password or ""
        self.container = container
        self.host = host
        self.port = str(port)
        self.prefix = None
        self.backend = ""

    def _prefixes(self):
        out = []
        if self.container:
            out.append(("docker", ["docker", "exec", self.container, "redis-cli",
                                   "--no-auth-warning", "-a", self.password]))
        out.append(("host", ["redis-cli", "--no-auth-warning", "-a", self.password,
                             "-h", self.host, "-p", self.port]))
        return out

    def _run(self, argv):
        try:
            proc = subprocess.run(argv, capture_output=True, text=True, timeout=25)
        except FileNotFoundError:
            return None, "", "命令不存在：%s" % argv[0]
        except Exception as ex:
            return None, "", "%s: %s" % (type(ex).__name__, ex)
        return proc.returncode, proc.stdout or "", proc.stderr or ""

    def probe(self):
        if not self.password:
            return False
        for name, prefix in self._prefixes():
            code, out, err = self._run(prefix + ["PING"])
            if code == 0 and "PONG" in out:
                self.prefix = prefix
                self.backend = name
                return True
        return False

    def run(self, *argv):
        if self.prefix is None:
            return None, "", "Redis 不可用"
        code, out, err = self._run(self.prefix + list(argv))
        return code, out, err

    def get(self, key):
        code, out, _ = self.run("GET", key)
        if code != 0:
            return None
        value = out.strip()
        return None if value in ("", "(nil)") else value

    def llen(self, key):
        code, out, _ = self.run("LLEN", key)
        if code != 0:
            return None
        try:
            return int(out.strip())
        except ValueError:
            return None

    def scan(self, pattern):
        code, out, _ = self.run("--scan", "--pattern", pattern)
        if code != 0:
            return []
        return [line.strip() for line in out.splitlines() if line.strip()]

    def redacted_backend(self):
        return "%s（密码已隐藏）" % self.backend


def journal_tail(unit, lines):
    """journalctl -u <unit> --no-pager -n <lines>；不可用时返回 None。"""
    try:
        proc = subprocess.run(["journalctl", "-u", unit, "--no-pager", "-n", str(lines)],
                              capture_output=True, text=True, timeout=30)
    except Exception:
        return None
    return (proc.stdout or "") + (proc.stderr or "")


def env_file_value(path, key):
    """只读取 env 文件里的某一个键（绝不打印其它行，避免泄露密钥）。"""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            content = handle.read()
    except Exception:
        return None
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith(key + "="):
            return stripped.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


# --------------------------------------------------------------------------
# 参数
# --------------------------------------------------------------------------


class Cfg:
    pass


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="课安 IM 端到端测试（两个账号 + 私信 + box 通道）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base", default=os.environ.get("KEAN_BASE", "http://127.0.0.1:8080"),
                        help="kean 后端地址（默认直连 127.0.0.1:8080，绕开 Cloudflare）")
    parser.add_argument("--ws-base", default=os.environ.get("KEAN_WS_BASE", ""),
                        help="kean 自研 WS 的 base（默认由 --base 推导，路径 /ws/chat）")
    parser.add_argument("--im-ws", default=os.environ.get("IM_WS_URL", "ws://127.0.0.1:8878/im"))
    parser.add_argument("--redis-password", default=os.environ.get("IM_RPW", ""))
    parser.add_argument("--redis-container", default=os.environ.get("KEAN_REDIS_CTR", "kean-redis-kean-redis-1"))
    parser.add_argument("--redis-host", default=os.environ.get("KEAN_REDIS_HOST", "127.0.0.1"))
    parser.add_argument("--redis-port", default=os.environ.get("KEAN_REDIS_PORT", "26739"))
    parser.add_argument("--unit", default=os.environ.get("KEAN_UNIT", "kean"))
    parser.add_argument("--env-file", default=os.environ.get("KEAN_ENV_FILE", "/opt/kean/.env.prod"))
    parser.add_argument("--tag", default=os.environ.get("KEAN_E2E_TAG", ""))
    parser.add_argument("--password", default=os.environ.get("KEAN_E2E_PASSWORD", ""))
    parser.add_argument("--school-id", default=os.environ.get("KEAN_SCHOOL_ID", ""))
    parser.add_argument("--terminal", default=os.environ.get("KEAN_IM_TERMINAL", "app"))
    parser.add_argument("--wait", type=float, default=env_float("KEAN_E2E_WAIT", 8.0))
    return parser.parse_args(argv)


def ws_base_from(base):
    parsed = urllib.parse.urlparse(base)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    netloc = parsed.netloc
    return "%s://%s/ws/chat" % (scheme, netloc)


def env_float(name, fallback):
    try:
        return float(os.environ.get(name, "") or fallback)
    except ValueError:
        return fallback


TERMINAL_CODE = {"web": 0, "app": 1, "pc": 2, "0": 0, "1": 1, "2": 2}


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------


def main(argv):
    args = parse_args(argv)
    cfg = Cfg()
    cfg.base = args.base
    cfg.self_ws = args.ws_base or ws_base_from(args.base)
    cfg.im_ws = args.im_ws
    cfg.wait = max(2.0, args.wait)
    cfg.terminal = args.terminal
    cfg.terminal_code = TERMINAL_CODE.get(args.terminal.strip().lower(), 1)
    cfg.tag = args.tag.strip() or uuid.uuid4().hex[:6]
    cfg.password = args.password.strip() or random_password()
    cfg.school_override = args.school_id.strip()
    cfg.unit = args.unit
    cfg.env_file = args.env_file

    rpt = Reporter()
    http = Http(cfg.base)
    redis = RedisCli(args.redis_password, args.redis_container, args.redis_host, args.redis_port)
    redis_ok = False

    print("=" * 74)
    print("课安 IM 端到端测试（两个账号 + 私信 + box 通道）")
    print("开始时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"))
    print("=" * 74)
    print("  kean BASE      : %s" % cfg.base)
    print("  kean 自研 WS   : %s" % cfg.self_ws)
    print("  im-server WS   : %s" % cfg.im_ws)
    print("  账号后缀 tag   : %s" % cfg.tag)
    print("  账号密码       : %s   （测试账号，可打印）" % cfg.password)
    print("  Redis 容器     : %s" % args.redis_container)
    print("  journalctl 单元: %s" % cfg.unit)

    ws_conns = []
    try:
        # ---------------- 步骤 0：环境自检 ----------------
        rpt.step("0", "环境自检（后端可用性 / Redis / 镜像投递开关）")

        health = http.call("GET", "/health", timeout=10)
        rpt.check("0.1", "kean 后端可达（GET /health）", health.get("status") == 200,
                  Http.describe(health))

        if not args.redis_password:
            rpt.skip("0.2", "Redis 断言", "未设置环境变量 IM_RPW → 所有 Redis 断言降级为 SKIP（其余照跑）")
        else:
            redis_ok = redis.probe()
            if redis_ok:
                rpt.check("0.2", "Redis 可达（redis-cli PING → PONG）", True, redis.redacted_backend())
                rpt.obs("0.2", "im:max_server_id", "值 = %s" % (redis.get("im:max_server_id") or "(不存在)"))
            else:
                rpt.skip("0.2", "Redis 断言",
                         "docker exec %s / 本机 redis-cli 均不可用（密码或容器名不对？）→ Redis 断言 SKIP"
                         % args.redis_container)

        logs = journal_tail(cfg.unit, 400)
        if logs is None:
            rpt.obs("0.3", "journalctl 不可用", "跳过镜像投递开关检查")
        else:
            hits = re.findall(r"\[IM 镜像投递\][^\n]*", logs)
            if hits:
                last = hits[-1]
                compact = last.replace(" ", "")
                rpt.obs("0.3", "kean 日志里的 IM 镜像投递开关", cut(last))
                if "mirrorEnabled=false" in compact or "enabled=false" in compact:
                    rpt.warn("0.3", "镜像投递被关闭（mirrorEnabled=false / enabled=false）",
                             "KEAN_IM_MIRROR_ENABLED=false 时 kean 不会往 box 队列写任何东西，"
                             "步骤 6.2 的 cmd=3 断言必然失败。请把它放回 true 并重启 kean。")
            else:
                rpt.obs("0.3", "未在最近 400 行日志里找到 [IM 镜像投递] 启动行",
                        cut(logs[-300:]))
        env_flag = env_file_value(cfg.env_file, "KEAN_IM_MIRROR_ENABLED")
        if env_flag is not None:
            if env_flag.lower() == "false":
                rpt.warn("0.4", "%s 里 KEAN_IM_MIRROR_ENABLED=false" % cfg.env_file,
                         "这是文档里记录的「当前回滚态」，会让步骤 6.2 一定失败。")
            else:
                rpt.obs("0.4", "%s 里的 KEAN_IM_MIRROR_ENABLED" % cfg.env_file,
                        env_flag or "(空 → Spring 默认 true)")

        # ---------------- 步骤 1：注册两个账号 ----------------
        rpt.step("1", "注册两个测试账号（发码 → 取码 → 注册；已存在则走登录分支）")

        school_id = None
        if cfg.school_override:
            try:
                school_id = int(cfg.school_override)
                rpt.obs("1.1", "schoolId 来自 KEAN_SCHOOL_ID", str(school_id))
            except ValueError:
                school_id = None
                rpt.warn("1.1", "KEAN_SCHOOL_ID 不是整数，已忽略", repr(cfg.school_override))
        if school_id is None:
            res = http.call("GET", "/api/schools")
            body = res.get("json") or {}
            items = body.get("data") if isinstance(body, dict) else None
            if res.get("status") == 200 and isinstance(items, list) and items:
                ids = [int(item["id"]) for item in items if item.get("id") is not None]
                if ids:
                    school_id = min(ids)
                    rpt.check("1.1", "从 GET /api/schools 取到一个真实启用的 schoolId", True,
                              "schoolId=%s（共 %d 所，取最小 id；学校列表只返回 status=1）"
                              % (school_id, len(items)))
                else:
                    rpt.check("1.1", "从 GET /api/schools 取到 schoolId", False,
                              "返回了 %d 所学校但都没有 id 字段；可用 KEAN_SCHOOL_ID=xx 显式指定"
                              % len(items))
            else:
                rpt.check("1.1", "从 GET /api/schools 取到 schoolId", False,
                          Http.describe(res) + "  → 可用 KEAN_SCHOOL_ID=xx 显式指定")
        if school_id is None:
            school_id = 1
            rpt.warn("1.1", "回落到 schoolId=1", "取不到学校列表时用 1 兜底；注册可能因 SCHOOL_INVALID 失败")

        accounts = {}
        # 允许直接指定两个现成账号（不注册，只登录）：KEAN_E2E_USER_A / KEAN_E2E_USER_B
        explicit = {"a": os.environ.get("KEAN_E2E_USER_A", "").strip(),
                    "b": os.environ.get("KEAN_E2E_USER_B", "").strip()}
        for label in ("a", "b"):
            username = explicit[label] or ("kean_e2e_%s_%s" % (label, cfg.tag))
            step = "1.%s" % ("2" if label == "a" else "3")
            account = prepare_account(rpt, http, redis, redis_ok, cfg, step, label, username,
                                     cfg.password, school_id)
            if account:
                accounts[label] = account

        # ---------------- 步骤 2：分别登录 ----------------
        rpt.step("2", "分别登录两个账号 → 拿 kean JWT → 从 payload 解出 userId")
        for label in ("a", "b"):
            account = accounts.get(label)
            step = "2.%s" % ("1" if label == "a" else "2")
            if not account:
                rpt.skip(step, "%s 账号不可用，跳过登录" % label.upper(), "步骤 1 未拿到账号")
                continue
            res = http.call("POST", "/api/auth/login",
                            {"username": account["username"], "password": cfg.password,
                             "turnstileToken": ""})
            ok = Http.code(res) == 0 and isinstance(Http.data(res).get("token"), str)
            if not ok:
                rpt.check(step, "%s 登录成功（POST /api/auth/login）" % label.upper(), False,
                          Http.describe(res) + "  → 若提示真人验证失败，说明 Turnstile 未关闭"
                          "（KEAN_TURNSTILE_ENABLED / TURNSTILE_ENABLED）")
                continue
            data = Http.data(res)
            account["token"] = data["token"]
            payload = decode_jwt_payload(account["token"]) or {}
            user = data.get("user") or {}
            account["user_id"] = user.get("id")
            account["nickname"] = user.get("nickname")
            account["jwt_user_id"] = payload.get("sub")
            same = str(payload.get("sub")) == str(user.get("id"))
            rpt.check(step, "%s 登录成功，userId=%s（JWT.payload.sub 与 data.user.id 一致）"
                      % (label.upper(), user.get("id")), ok and same,
                      "JWT=%s  payload.sub=%s  username=%s  role=%s"
                      % (mask(account["token"]), payload.get("sub"), payload.get("username"),
                         payload.get("role")))

        a = accounts.get("a")
        b = accounts.get("b")
        if not a or not b or not a.get("token") or not b.get("token"):
            rpt.warn("2.3", "两个账号未全部就绪", "步骤 3-9 将打印 SKIP；请先看步骤 1/2 的失败原因")

        # ---------------- 步骤 3：取 IM token ----------------
        rpt.step("3", "各自取一次 IM token（GET /api/im/token?terminal=%s）" % cfg.terminal)
        for label, account in (("a", a), ("b", b)):
            step = "3.%s" % ("1" if label == "a" else "2")
            if not account or not account.get("token"):
                rpt.skip(step, "%s 取 IM token" % label.upper(), "缺少 kean JWT")
                continue
            res = http.call("GET", "/api/im/token?terminal=%s" % urllib.parse.quote(cfg.terminal),
                            token=account["token"])
            data = Http.data(res)
            enabled = data.get("enabled") is True and bool(data.get("accessToken"))
            account["im_token"] = data.get("accessToken")
            payload = decode_jwt_payload(account["im_token"] or "")
            info = {}
            if payload and isinstance(payload.get("info"), str):
                info = parse_json_lenient(payload["info"]) or {}
            aud = payload.get("aud") if payload else None
            aud_value = aud[0] if isinstance(aud, list) and aud else aud
            aud_ok = str(aud_value) == str(account.get("user_id"))
            account["im_terminal"] = info.get("terminal", cfg.terminal_code)
            expire_at = data.get("expireAt")
            expire_text = ""
            if isinstance(expire_at, (int, float)) and expire_at > 0:
                expire_text = "expireAt=%s（%s，距今 %.1f 分钟）" % (
                    expire_at, time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(expire_at / 1000.0)),
                    (expire_at / 1000.0 - time.time()) / 60.0)
            rpt.check(step, "%s IM token 可用（enabled=true 且 aud=userId）" % label.upper(),
                      enabled and aud_ok,
                      "enabled=%s  %s  aud=%s  info=%s  accessToken=%s"
                      % (data.get("enabled"), expire_text, aud_value, cut(info, 90),
                         mask(account.get("im_token"))))
            if not enabled:
                rpt.warn(step, "IM 未启用", "后端 IM_JWT_SECRET 未配置或不足 32 字节 → "
                                            "GET /api/im/token 返回 enabled=false")

        # ---------------- 步骤 4：A 给 B 开会话 ----------------
        rpt.step("4", "A 给 B 开会话（POST /api/chats，body 字段 peerUserId）")
        session_id = None
        if not (a and b and a.get("token") and b.get("user_id")):
            rpt.skip("4.1", "开会话", "账号或 userId 缺失")
        else:
            res = http.call("POST", "/api/chats", {"peerUserId": b["user_id"]}, token=a["token"])
            data = Http.data(res)
            session_id = data.get("id")
            ok = Http.code(res) == 0 and isinstance(session_id, int)
            rpt.check("4.1", "开会话成功，sessionId=%s" % session_id, ok,
                      Http.describe(res))
            if ok:
                rpt.obs("4.2", "会话详情（ChatSessionVO）",
                        "id=%s peerUserId=%s peerNickname=%s lastSeqNo=%s unreadCount=%s"
                        % (data.get("id"), data.get("peerUserId"), data.get("peerNickname"),
                           data.get("lastSeqNo"), data.get("unreadCount")))
                rpt.check("4.3", "会话的 peerUserId = B 的 userId",
                          str(data.get("peerUserId")) == str(b["user_id"]),
                          "peerUserId=%s  B.userId=%s" % (data.get("peerUserId"), b["user_id"]))

        # ---------------- 步骤 5：box WS 登录 + Redis 槽位 ----------------
        rpt.step("5", "两条 box WS 连接（im-server /im）登录 + 自研 WS 连接 + Redis 在线槽位")
        box_a = None
        box_b = None
        if not (a and b and a.get("im_token") and b.get("im_token")):
            rpt.skip("5.1-5.4", "box WS 登录", "缺少 IM token（步骤 3 未通过）")
        else:
            for label, account in (("a", a), ("b", b)):
                step = "5.%s" % ("1" if label == "a" else "2")
                client = WsClient(cfg.im_ws, heartbeat='{"cmd":1,"data":{}}', label="%s/box" % label.upper())
                try:
                    status = client.connect()
                except Exception as ex:
                    rpt.check(step, "%s box WS 握手（%s）" % (label.upper(), cfg.im_ws), False,
                              "%s: %s" % (type(ex).__name__, ex))
                    client.close()
                    continue
                ws_conns.append(client)
                rpt.check(step, "%s box WS 握手成功（期望 101）" % label.upper(), "101" in status, status)
                client.send_text(json.dumps({"cmd": 0, "data": {
                    "accessToken": account["im_token"], "devId": "e2e-%s-%s" % (label, cfg.tag)}}))
                frame = client.wait_for(lambda obj: obj.get("cmd") == 0, cfg.wait)
                rpt.check(step, "%s box WS 登录成功（收到 {\"cmd\":0}）" % label.upper(),
                          frame is not None,
                          "收到：%s" % (cut(frame["raw"]) if frame else
                                        "未收到 cmd=0；已收到：%s（连接状态：%s）"
                                        % (client.history(), client.note or "open")))
                if label == "a":
                    box_a = client
                else:
                    box_b = client

        # Redis 在线槽位：key = im:user:server_id:{userId}:{terminal}（花括号是 box 的 hash tag，逐字节照抄）
        for label, account in (("a", a), ("b", b)):
            step = "5.%s" % ("3" if label == "a" else "4")
            if not (account and account.get("user_id")):
                rpt.skip(step, "%s 的在线槽位键" % label.upper(), "缺少 userId")
                continue
            if not redis_ok:
                rpt.skip(step, "%s 的在线槽位键" % label.upper(), "Redis 不可用")
                continue
            term = account.get("im_terminal", cfg.terminal_code)
            key = "im:user:server_id:{%s}:%s" % (account["user_id"], term)
            alt = "im:user:server_id:%s:%s" % (account["user_id"], term)
            value = redis.get(key)
            alt_value = redis.get(alt)
            rpt.check(step, "%s 的在线槽位键存在：%s" % (label.upper(), key), value is not None,
                      "值(serverId)=%s   同义键 %s=%s" % (value, alt, alt_value))
            if value is None and alt_value is not None:
                rpt.warn(step, "键名不一致（只有无花括号的版本存在）",
                         "kean 的 ImSenderService.readServerId() 读的是带花括号的 im:user:server_id:{userId}:{terminal}，"
                         "读不到就会判定「接收方离线」并跳过镜像投递 → 步骤 6.2 必失败。"
                         "请核对 im-server 版本 / box IMRedisKey.userServerIdKey() 的拼法。")
            elif value is None:
                rpt.warn(step, "槽位键不存在",
                         "可能原因：box WS 登录帧没被接受（密钥不一致 → 连上就断）、"
                         "terminal 与 token 里的 info.terminal 不一致、或 im-server 与 kean 用的不是同一个 Redis 库（必须 0 号库）")

        # 自研 WS（kean /ws/chat）：仅用于 READ 事件的断言与 MESSAGE 观察
        self_ws_a = None
        self_ws_b = None
        if a and a.get("token") and b and b.get("token"):
            for label, account in (("a", a), ("b", b)):
                step = "5.%s" % ("5" if label == "a" else "6")
                client = WsClient(cfg.self_ws, timeout=cfg.wait,
                                  extra_headers={"Authorization": "Bearer " + account["token"]},
                                  heartbeat='{"type":"PING"}', label="%s/self" % label.upper())
                try:
                    status = client.connect()
                except Exception as ex:
                    rpt.check(step, "%s 自研 WS 握手（%s）" % (label.upper(), cfg.self_ws), False,
                              "%s: %s" % (type(ex).__name__, ex))
                    client.close()
                    continue
                ws_conns.append(client)
                rpt.check(step, "%s 自研 WS 握手成功（握手期 Authorization 认证）" % label.upper(),
                          "101" in status, status)
                if label == "a":
                    self_ws_a = client
                else:
                    self_ws_b = client
            if self_ws_a is None or self_ws_b is None:
                rpt.warn("5.6", "自研 WS 未全部建立",
                         "步骤 7 的 READ 断言会 SKIP/FAIL（READ 事件只走自研 WS 通道，box 通道刻意不镜像 READ）")
        else:
            rpt.skip("5.5-5.6", "自研 WS 连接", "缺少 kean JWT")

        # ---------------- 步骤 6：B 通过 HTTP 给 A 发私信 ----------------
        rpt.step("6", "B 通过 HTTP 给 A 发一条私信（POST /api/chats/{id}/messages）")
        message = {}
        msg_ok = False
        local_id = "e2e" + uuid.uuid4().hex[:20]
        if not (session_id and b and b.get("token")):
            rpt.skip("6.1-6.6", "发消息", "缺少 sessionId 或 B 的 JWT")
        else:
            queue_keys_before = redis.scan("im:message:private*") if redis_ok else []
            body = {"type": "TEXT", "content": "e2e 通道验证 %s" % local_id, "localId": local_id}
            res = http.call("POST", "/api/chats/%s/messages" % session_id, body, token=b["token"])
            data = Http.data(res)
            message = data if isinstance(data, dict) else {}
            has_fields = (Http.code(res) == 0 and message.get("seqNo") is not None
                          and message.get("status") is not None and message.get("localId") == local_id)
            msg_ok = has_fields
            rpt.check("6.1", "HTTP 发消息成功且返回 seqNo/status/localId", has_fields,
                      Http.describe(res))
            seq_no = message.get("seqNo")

            if box_a is not None and seq_no is not None:
                frame = box_a.wait_for(
                    lambda obj: obj.get("cmd") == 3 and isinstance(obj.get("data"), dict)
                    and str(obj["data"].get("sessionId")) == str(session_id),
                    cfg.wait)
                rpt.check("6.2", "★ A 的 box WS 在 %.0fs 内收到 cmd=3 且 data.sessionId 匹配" % cfg.wait,
                          frame is not None,
                          "收到：%s" % (cut(frame["raw"]) if frame else
                                        "未收到；A 的 box 连接已收到：%s（状态：%s）"
                                        % (box_a.history(), box_a.note or "open")))
                if frame is not None:
                    inner = frame["json"].get("data") or {}
                    fields_ok = (str(inner.get("senderId")) == str(b.get("user_id"))
                                 and str(inner.get("seqNo")) == str(seq_no)
                                 and inner.get("localId") == local_id
                                 and str(inner.get("msgType", "")).upper() == "TEXT"
                                 and inner.get("status") is not None)
                    rpt.check("6.3", "cmd=3 的 data 字段与 HTTP 侧一致（senderId/seqNo/localId/msgType/status）",
                              fields_ok,
                              "sessionId=%s senderId=%s recvId=%s seqNo=%s localId=%s msgType=%s type=%s "
                              "status=%s createdAt=%s content=%s"
                              % (inner.get("sessionId"), inner.get("senderId"), inner.get("recvId"),
                                 inner.get("seqNo"), inner.get("localId"), inner.get("msgType"),
                                 inner.get("type"), inner.get("status"), inner.get("createdAt"),
                                 cut(inner.get("content"), 60)))
                    rpt.obs("6.3", "cmd=3 完整 data", cut(inner))
                    rpt.check("6.4", "cmd=3 的 data.sessionId 与步骤 4 的 sessionId 完全一致（气泡准入条件）",
                              str(inner.get("sessionId")) == str(session_id),
                              "data.sessionId=%s  sessionId=%s" % (inner.get("sessionId"), session_id))
            else:
                rpt.skip("6.2-6.4", "A 的 box WS 收到 cmd=3", "box 连接或 seqNo 缺失")

            if self_ws_a is not None:
                frame = self_ws_a.wait_for(lambda obj: obj.get("type") == "MESSAGE", 2.0)
                rpt.obs("6.5", "A 的自研 WS 是否也收到 MESSAGE（观察项：既有链路，非本任务要求）",
                        "收到：%s" % (cut(frame["raw"]) if frame else "2s 内未收到"))

            if redis_ok:
                time.sleep(1.0)  # 给消费者 leftPop 一点时间
                keys = sorted(set(redis.scan("im:message:private*")) | set(queue_keys_before))
                lengths = {key: redis.llen(key) for key in keys}
                server_ids = []
                for label, account in (("A", a), ("B", b)):
                    term = account.get("im_terminal", cfg.terminal_code)
                    value = redis.get("im:user:server_id:{%s}:%s" % (account.get("user_id"), term))
                    if value:
                        server_ids.append("%s→serverId=%s" % (label, value))
                dirty = {k: v for k, v in lengths.items() if (v or 0) > 0}
                bare = {k: v for k, v in dirty.items() if k == "im:message:private"}
                rpt.check("6.6", "im:message:private* 的 LLEN 全部回到 0（镜像队列被消费干净）",
                          not dirty,
                          "队列键与长度：%s；接收方槽位：%s"
                          % (cut(lengths if lengths else "（无队列键 = 已被消费/从未写过）", 160),
                             ", ".join(server_ids) or "(未读到槽位)"))
                if bare:
                    rpt.warn("6.6", "裸基键 im:message:private 里堆了消息",
                             "这是「队列键少了 :{serverId} 后缀」的典型特征：im-server 的拉取任务只拉 "
                             "im:message:private:{serverId}，写在裸键上的消息永远不会被消费。")
                elif dirty:
                    rpt.warn("6.6", "队列里还有未消费的消息",
                             "持续 >0 的典型原因：① 队列键少了 :{serverId} 后缀（im-server 只拉带后缀的键）；"
                             "② serverId 与当前 im-server 实例不符（重启后计数器漂移，旧键无人消费）；"
                             "③ im-server 进程没在跑（没有消费者）。")
            else:
                rpt.skip("6.6", "im:message:private:* 的 LLEN 归零", "Redis 不可用")
            a["last_seq"] = seq_no
            b["last_local_id"] = local_id

        # ---------------- 步骤 7：A 标记已读 → B 收到 READ ----------------
        rpt.step("7", "A 标记已读到 maxSeq（POST /api/chats/{id}/read）→ B 收 READ 事件")
        if not (session_id and a and a.get("token") and a.get("last_seq") is not None):
            rpt.skip("7.1-7.3", "标记已读", "缺少 sessionId / A 的 JWT / seqNo")
        else:
            res = http.call("POST", "/api/chats/%s/read" % session_id,
                            {"maxSeq": a["last_seq"]}, token=a["token"])
            rpt.check("7.1", "A 标记已读成功（body 传 {\"maxSeq\": %s}）" % a["last_seq"],
                      Http.code(res) == 0, Http.describe(res))

            if self_ws_b is None:
                rpt.skip("7.2", "B 的自研 WS 收到 READ", "B 的自研 WS 未建立")
            else:
                frame = self_ws_b.wait_for(lambda obj: obj.get("type") == "READ", cfg.wait)
                rpt.check("7.2", "★ B 的自研 WS 在 %.0fs 内收到 {\"type\":\"READ\"}" % cfg.wait,
                          frame is not None,
                          "收到：%s" % (cut(frame["raw"]) if frame else
                                        "未收到；B 的自研 WS 已收到：%s（状态：%s）"
                                        % (self_ws_b.history(), self_ws_b.note or "open")))
                if frame is not None:
                    inner = frame["json"]
                    ok = (str(inner.get("sessionId")) == str(session_id)
                          and str(inner.get("maxSeq")) == str(a["last_seq"])
                          and str(inner.get("readerId")) == str(a.get("user_id")))
                    rpt.check("7.3", "READ 事件字段正确（sessionId/maxSeq/readerId）", ok,
                              "sessionId=%s（期望 %s） maxSeq=%s（期望 %s） readerId=%s（期望 %s）"
                              % (inner.get("sessionId"), session_id, inner.get("maxSeq"),
                                 a["last_seq"], inner.get("readerId"), a.get("user_id")))

            if box_b is not None:
                box_b.collect(1.5)
                read_frames = [e for e in box_b.received
                               if isinstance(e["json"], dict) and e["json"].get("type") == "READ"]
                rpt.obs("7.4", "box 通道是否镜像了 READ（观察项）",
                        ("收到了 %d 条 READ（实现已变更？）" % len(read_frames)) if read_frames
                        else "未收到 —— 与代码一致：RealtimePublisher.read() 刻意不镜像 READ 到 box")
                rpt.obs("7.5", "B 的 box 连接收到的全部帧",
                        box_b.history())

        # ---------------- 步骤 8：增量拉取 ----------------
        rpt.step("8", "增量拉取 GET /api/chats/{id}/messages?afterSeq=0")
        count_before = None
        if not (session_id and a and a.get("token")):
            rpt.skip("8.1-8.3", "增量拉取", "缺少 sessionId 或 A 的 JWT")
        elif not msg_ok:
            rpt.skip("8.1-8.3", "增量拉取", "步骤 6 没有成功发出消息，无从验证增量拉取")
        else:
            res = http.call("GET", "/api/chats/%s/messages?afterSeq=0" % session_id, token=a["token"])
            data = Http.data(res)
            items = data.get("list") if isinstance(data.get("list"), list) else []
            count_before = len(items)
            mine = [item for item in items if item.get("localId") == local_id]
            rpt.check("8.1", "增量拉取成功且能拿到刚才那条消息（localId=%s）" % local_id,
                      Http.code(res) == 0 and len(mine) == 1,
                      "共 %d 条；命中 %d 条。%s" % (count_before, len(mine), Http.describe(res)))
            if mine:
                item = mine[0]
                status = item.get("status")
                rpt.check("8.2", "消息带 seqNo/localId/status 且已读后 status=3（READ）",
                          item.get("seqNo") is not None and item.get("localId") == local_id
                          and status == 3,
                          "seqNo=%s localId=%s status=%s readAt=%s createdAt=%s content=%s"
                          % (item.get("seqNo"), item.get("localId"), status, item.get("readAt"),
                             item.get("createdAt"), cut(item.get("content"), 60)))
                rpt.obs("8.3", "整条 ChatMessageVO（增量拉取）", cut(item))

        # ---------------- 步骤 9：幂等重发 ----------------
        rpt.step("9", "重复发送同一 localId（期望：不产生第二条，幂等）")
        if not (session_id and b and b.get("token") and b.get("last_local_id")):
            rpt.skip("9.1-9.3", "幂等重发", "缺少 sessionId 或 localId")
        elif not msg_ok:
            rpt.skip("9.1-9.3", "幂等重发", "步骤 6 没有成功发出消息，无从验证幂等")
        else:
            res = http.call("POST", "/api/chats/%s/messages" % session_id,
                            {"msgType": "TEXT", "content": "e2e 幂等重发 %s" % b["last_local_id"],
                             "localId": b["last_local_id"]}, token=b["token"])
            data = Http.data(res)
            first = message if isinstance(message, dict) else {}
            same = (Http.code(res) == 0 and data.get("id") is not None
                    and str(data.get("id")) == str(first.get("id")))
            rpt.check("9.1", "重发返回的是同一条消息（id 不变）", same,
                      "第一次 id=%s seqNo=%s；重发 id=%s seqNo=%s。%s"
                      % (first.get("id"), first.get("seqNo"), data.get("id"), data.get("seqNo"),
                         Http.describe(res)))
            rpt.check("9.2", "重发不会分配新的 seqNo", str(data.get("seqNo")) == str(first.get("seqNo")),
                      "第一次 seqNo=%s；重发 seqNo=%s" % (first.get("seqNo"), data.get("seqNo")))
            res2 = http.call("GET", "/api/chats/%s/messages?afterSeq=0" % session_id, token=a["token"])
            items2 = Http.data(res2).get("list") if isinstance(Http.data(res2).get("list"), list) else []
            rpt.check("9.3", "会话消息条数不变（幂等的最直接验证）",
                      count_before is not None and len(items2) == count_before,
                      "重发前 %s 条；重发后 %s 条" % (count_before, len(items2)))

        # ---------------- 步骤 10：清理说明 ----------------
        rpt.step("10", "清理（脚本不自动删数据）")
        print("  测试账号已留在库里（未做任何删除）：")
        for label in ("a", "b"):
            account = accounts.get(label)
            if account:
                print("    %s  username=%s  userId=%s  nickname=%s"
                      % (label.upper(), account.get("username"), account.get("user_id"),
                         account.get("nickname")))
            else:
                print("    %s  未创建成功" % label.upper())
        print("  手工清理方式（任选）：")
        print("   1) 自助注销接口（会逻辑删除 + 匿名化，保留与对方相关的聊天/评价记录）：")
        for label in ("a", "b"):
            account = accounts.get(label)
            if account and account.get("token"):
                print("      curl -sS -X POST '%s/api/me/delete-account' \\" % cfg.base)
                print("        -H 'Authorization: Bearer <该账号的 kean JWT>' \\")
                print("        -H 'Content-Type: application/json' \\")
                print("        -d '{\"password\":\"%s\"}'   # %s" % (cfg.password, account.get("username")))
        print("   2) 直接 SQL（库 kean，逻辑删除列 deleted=1，账号数据会一直留着）：")
        print("      UPDATE sys_user SET deleted=1 WHERE username LIKE 'kean_e2e_%';")
        print("      相关联的 chat_session / chat_message 不会随账号删除，需要的话一并清理。")

        # ---------------- 汇总 ----------------
        print("\n" + "=" * 74)
        print("汇总")
        print("=" * 74)
        print("  硬断言（决定退出码）：通过 %d，失败 %d，跳过 %d"
              % (rpt.passed, rpt.failed, rpt.skipped))
        print("  观察项（不决定退出码）：%d 条" % rpt.obs_count)
        if rpt.failures:
            print("  失败清单：")
            for item in rpt.failures:
                print("    - %s" % item)
        else:
            print("  失败清单：无")
        if not redis_ok:
            print("  提示：IM_RPW 未设置或 Redis 不可达 → 队列/槽位类断言被 SKIP（未计入失败）。")
        print("  退出码：%d" % (1 if rpt.failed else 0))
        print("=" * 74)
        return 1 if rpt.failed else 0
    finally:
        for client in ws_conns:
            client.close()


# --------------------------------------------------------------------------
# 步骤 1 的子流程
# --------------------------------------------------------------------------


def send_sms(rpt, http, step, email, scene="REGISTER"):
    """发验证码；同 IP 10s 冷却（AuthRateLimitService.SMS_IP_COOLDOWN）会回 40010，自动等 11s 重试。"""
    res = None
    for attempt in range(1, 5):
        res = http.call("POST", "/api/auth/sms",
                       {"email": email, "scene": scene, "turnstileToken": ""})
        code = Http.code(res)
        if code == 0:
            return res, attempt
        if code == 40010 and attempt < 4:
            rpt.obs(step, "发码被限流（40010），等 11s 重试（第 %d 次）" % attempt,
                    "AuthRateLimitService.assertSmsAllowed：同 IP 10s 冷却 / 同邮箱 60s 冷却")
            time.sleep(11.0)
            continue
        return res, attempt
    return res, 4


def resolve_code(rpt, redis, redis_ok, unit, step, email, sms_res):
    """取验证码：① 响应 data.debugCode ② Redis kean:sms:code:REGISTER:<email> ③ journalctl。"""
    data = Http.data(sms_res)
    debug_code = data.get("debugCode")
    if isinstance(debug_code, str) and re.fullmatch(r"\d{6}", debug_code.strip()):
        rpt.obs(step, "验证码来自发码响应 data.debugCode（kean.sms.expose-code=true）", debug_code.strip())
        return debug_code.strip()
    rpt.obs(step, "发码响应（SmsSendVO）", Http.describe(sms_res) + "  channel=%s" % data.get("channel"))

    if redis_ok:
        value = redis.get("kean:sms:code:REGISTER:%s" % email)
        if value and re.fullmatch(r"\d{6}", value):
            rpt.obs(step, "验证码来自 Redis kean:sms:code:REGISTER:<email>", value)
            return value

    logs = journal_tail(unit, 300)
    if logs is None:
        rpt.obs(step, "journalctl 不可用，无法回退抓码", "")
        return None
    code = None
    snippet = ""
    lines = logs.splitlines()
    for index, line in enumerate(lines):
        if email in line:
            window = lines[max(0, index - 8): index + 8]
            snippet = "\n".join(window)
            match = re.search(r"验证码[:：]\s*(\d{6})", snippet)
            if match:
                code = match.group(1)
                break
    if code is None:
        match = re.search(r"验证码[:：]\s*(\d{6})", logs)
        if match:
            code = match.group(1)
            snippet = snippet or "\n".join(lines[-12:])
    rpt.obs(step, "journalctl 抓码片段（-u %s --no-pager -n 300）" % unit, cut(snippet or logs[-300:]))
    if code is None:
        rpt.warn(step, "日志里没抓到 6 位验证码",
                 "若邮件服务已配置（MAIL 就绪），验证码只会发到邮箱、不会落日志。"
                 "此时请设置 IM_RPW 让脚本直接读 Redis 的 kean:sms:code:REGISTER:<email>，"
                 "或临时打开 kean.sms.expose-code。")
    return code


def prepare_account(rpt, http, redis, redis_ok, cfg, step, label, username, password, school_id):
    """确保账号存在：先试登录（重复运行分支），失败则 发码 → 取码 → 注册。"""
    existing = http.call("POST", "/api/auth/login",
                         {"username": username, "password": password, "turnstileToken": ""})
    if Http.code(existing) == 0:
        data = Http.data(existing)
        user = data.get("user") or {}
        rpt.obs(step, "%s 已存在（username=%s），走登录分支：不注册、不发码" % (label.upper(), username),
                "userId=%s nickname=%s" % (user.get("id"), user.get("nickname")))
        return {"label": label, "username": username, "user_id": user.get("id"),
                "nickname": user.get("nickname"), "email": user.get("email"), "existed": True}
    rpt.obs(step, "%s 登录失败（预期：账号还不存在）" % label.upper(), Http.describe(existing))

    email = random_qq_email()
    sms_res, attempts = send_sms(rpt, http, step, email)
    if Http.code(sms_res) != 0:
        rpt.check(step, "%s 发送注册验证码（email=%s）" % (label.upper(), email), False,
                  Http.describe(sms_res) + "  → 若提示真人验证失败，说明 Turnstile 未关闭"
                  "（TURNSTILE_ENABLED=false / kean.turnstile.enabled=false）")
        return None
    rpt.check(step, "%s 发送注册验证码成功（email=%s，第 %d 次尝试）" % (label.upper(), email, attempts),
              True, Http.describe(sms_res))

    code = resolve_code(rpt, redis, redis_ok, cfg.unit, step, email, sms_res)
    if not code:
        rpt.check(step, "%s 取到邮箱验证码" % label.upper(), False,
                  "响应无 debugCode、Redis 无 kean:sms:code:REGISTER:%s、日志也没抓到" % email)
        return None

    body = {
        "username": username,
        "password": password,
        "nickname": "",          # 选填：留空 → 服务端用雪花算法分配「课安用户xxxxxx」
        "gender": "MALE",        # 必填，仅 MALE / FEMALE
        "schoolId": school_id,   # 必填，必须是 status=1 的真实学校
        "campusText": "",        # 选填（改成了手输文本），空串也能注册
        "email": email,          # 必填，完整 QQ 邮箱
        "smsCode": code,         # 必填，6 位数字
        "turnstileToken": "",    # Turnstile 已临时关闭，留空即可
    }
    res = http.call("POST", "/api/auth/register", body)
    ok = Http.code(res) == 0
    rpt.check(step, "%s 注册成功（username=%s）" % (label.upper(), username), ok,
              Http.describe(res))
    if not ok:
        code_value = Http.code(res)
        if code_value == 40901:
            rpt.warn(step, "用户名已存在（40901）",
                     "固定 KEAN_E2E_TAG 时必须同时固定 KEAN_E2E_PASSWORD，否则第二次运行无法走登录分支。")
        elif code_value == 40001:
            rpt.warn(step, "学校无效（40001）", "用 KEAN_SCHOOL_ID 指定一个 status=1 的学校 id。")
        return None
    data = Http.data(res)
    rpt.obs(step, "注册响应（UserVO 节选）",
            cut({"id": data.get("id"), "username": data.get("username"),
                 "nickname": data.get("nickname"), "gender": data.get("gender"),
                 "schoolId": data.get("schoolId"), "schoolName": data.get("schoolName"),
                 "status": data.get("status"), "email": data.get("email")}))
    return {"label": label, "username": username, "user_id": data.get("id"),
            "nickname": data.get("nickname"), "email": email, "existed": False}


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
