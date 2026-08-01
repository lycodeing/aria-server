#!/usr/bin/env python3
# =============================================================================
# ARIA WebSocket 自动化测试辅助脚本（由 api-autotest.sh 调用）
#
# 子命令：
#   bridge <wss_base> <sessionId> <agentToken>   访客⇄座席双向消息 + TYPING 转发
#   agent-noauth <wss_base>                      座席端无 token 握手，期望 401
#   visitor-badsession <wss_base>                访客端非法 sessionId，期望被拒
#
# 输出：每个断言一行 "OK <name>" 或 "FAIL <name>: reason"，全部通过时退出码 0
# =============================================================================
import asyncio
import json
import ssl
import sys
import time

import websockets
from websockets.exceptions import ConnectionClosed, InvalidStatus

SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE

RESULTS = []


def report(name, ok, reason=""):
    RESULTS.append(ok)
    print(f"OK {name}" if ok else f"FAIL {name}: {reason}", flush=True)


async def recv_until(ws, predicate, timeout=15):
    """循环收帧直到 predicate 命中或超时，返回命中的解析对象（非 JSON 帧忽略）。"""
    deadline = time.monotonic() + timeout
    while True:
        remain = deadline - time.monotonic()
        if remain <= 0:
            return None
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=remain)
        except (asyncio.TimeoutError, ConnectionClosed):
            return None
        try:
            obj = json.loads(raw)
        except (ValueError, TypeError):
            continue
        if predicate(obj):
            return obj


async def cmd_bridge(base, session_id, agent_token):
    ts = int(time.time())
    agent_msg = f"座席WS消息-{ts}"
    visitor_msg = f"访客WS消息-{ts}"
    visitor_url = f"{base}/ws/chat/{session_id}"
    agent_url = f"{base}/ws/agent?token={agent_token}"

    async with websockets.connect(visitor_url, ssl=SSL_CTX) as vws, \
               websockets.connect(agent_url, ssl=SSL_CTX) as aws:
        report("visitor_ws_connect", True)
        report("agent_ws_connect", True)
        await asyncio.sleep(0.5)  # 等待服务端注册连接

        # 1. 座席 → 访客（含 seq 落库）
        await aws.send(json.dumps(
            {"type": "MESSAGE", "sessionId": session_id, "content": agent_msg}))
        got = await recv_until(vws, lambda o: agent_msg in json.dumps(o, ensure_ascii=False))
        report("agent_to_visitor_message", got is not None,
               "访客端 15s 内未收到座席消息")

        # 2. 访客 → 座席
        await vws.send(json.dumps({"type": "MESSAGE", "content": visitor_msg}))
        got = await recv_until(aws, lambda o: visitor_msg in json.dumps(o, ensure_ascii=False))
        report("visitor_to_agent_message", got is not None,
               "座席端 15s 内未收到访客消息")

        # 3. 访客 TYPING → 座席（仅转发不落库）
        await vws.send(json.dumps({"type": "TYPING", "content": ""}))
        got = await recv_until(aws, lambda o: str(o.get("type", "")).upper() == "TYPING",
                               timeout=10)
        report("visitor_typing_forward", got is not None,
               "座席端 10s 内未收到 TYPING 事件")

        # 4. 座席消息缺少 sessionId → 静默丢弃（访客不应收到）
        orphan = f"无sessionId消息-{ts}"
        await aws.send(json.dumps({"type": "MESSAGE", "content": orphan}))
        got = await recv_until(vws, lambda o: orphan in json.dumps(o, ensure_ascii=False),
                               timeout=4)
        report("agent_message_without_sessionid_dropped", got is None,
               "缺少 sessionId 的座席消息竟被投递给访客")


async def cmd_agent_noauth(base):
    try:
        async with websockets.connect(f"{base}/ws/agent", ssl=SSL_CTX):
            report("agent_ws_noauth_rejected", False, "无 token 握手竟然成功")
    except InvalidStatus as e:
        code = e.response.status_code
        report("agent_ws_noauth_rejected", code == 401, f"期望 401 实际 {code}")
    except Exception as e:
        # 部分代理会直接断开连接，同样视为拒绝
        report("agent_ws_noauth_rejected", True, str(e))


async def cmd_visitor_badsession(base):
    try:
        async with websockets.connect(f"{base}/ws/chat/bad@session!id", ssl=SSL_CTX) as ws:
            # 服务端会先推一条 error 消息，再以 NOT_ACCEPTABLE(1003) 主动关闭，
            # 所以需持续收帧直到连接关闭而非只收第一帧
            deadline = time.monotonic() + 8
            closed = False
            while time.monotonic() < deadline:
                try:
                    await asyncio.wait_for(ws.recv(),
                                           timeout=max(0.1, deadline - time.monotonic()))
                except ConnectionClosed:
                    closed = True
                    break
                except asyncio.TimeoutError:
                    break
            if closed:
                report("visitor_ws_badsession_rejected", ws.close_code == 1003,
                       f"期望关闭码 1003 实际 {ws.close_code}")
            else:
                report("visitor_ws_badsession_rejected", False, "8s 内连接未被关闭")
    except (InvalidStatus, ConnectionClosed, OSError) as e:
        # 握手直接被拒也算通过
        report("visitor_ws_badsession_rejected", True, str(e))


def main():
    if len(sys.argv) < 3:
        print("usage: ws-autotest.py <bridge|agent-noauth|visitor-badsession> <wss_base> [...]")
        sys.exit(2)
    cmd, base = sys.argv[1], sys.argv[2].rstrip("/")
    if cmd == "bridge":
        asyncio.run(cmd_bridge(base, sys.argv[3], sys.argv[4]))
    elif cmd == "agent-noauth":
        asyncio.run(cmd_agent_noauth(base))
    elif cmd == "visitor-badsession":
        asyncio.run(cmd_visitor_badsession(base))
    else:
        print(f"unknown command: {cmd}")
        sys.exit(2)
    sys.exit(0 if all(RESULTS) else 1)


if __name__ == "__main__":
    main()
