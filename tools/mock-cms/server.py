#!/usr/bin/env python3
"""本地演示桩：苹果 CMS JSON、TVBox 配置、M3U 和 XMLTV。

只给开发时的模拟器或浏览器使用，不会打进 APK，也不包含任何真实片源。
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path("/tmp/jianxia-mock")
TZ = timezone(timedelta(hours=8))
PORT = 8090

MOVIES = [
    {"id": "1", "name": "山海灯市", "year": "2024", "type_id": "1", "type_name": "电影", "area": "演示", "actor": "林晚", "director": "周宁", "remarks": "HD", "color": "0x243044", "blurb": "一座只在夜里点灯的城市，信被风送到对岸。"},
    {"id": "2", "name": "北窗来信", "year": "2023", "type_id": "1", "type_name": "电影", "area": "演示", "actor": "陈穗", "director": "何川", "remarks": "超清", "color": "0x3a2a22", "blurb": "冬天的北窗后面，有人把没寄出的信重新写了一遍。"},
    {"id": "3", "name": "晚潮码头", "year": "2022", "type_id": "1", "type_name": "电影", "area": "演示", "actor": "苏野", "director": "江澄", "remarks": "HD", "color": "0x1d3340", "blurb": "码头的灯一盏盏灭掉，船还停在原来的位置。"},
]
SERIES = [
    {"id": "11", "name": "青瓷信札", "year": "2024", "type_id": "2", "type_name": "电视剧", "area": "演示", "actor": "沈照", "director": "顾白", "remarks": "更新至第2集", "color": "0x2a2438", "blurb": "两封隔了十年的信，在同一只青瓷碗里重逢。", "episodes": ["第1集", "第2集"]},
    {"id": "12", "name": "巷口小馆", "year": "2021", "type_id": "2", "type_name": "电视剧", "area": "演示", "actor": "马秋", "director": "梁石", "remarks": "全1集", "color": "0x3d2e24", "blurb": "巷口只卖一种汤，故事都发生在打烊之前。", "episodes": ["第1集"]},
]
OTHERS = [
    {"id": "21", "name": "灯下问答", "year": "2024", "type_id": "3", "type_name": "综艺", "area": "演示", "actor": "主持人甲", "director": "演示组", "remarks": "第1期", "color": "0x3a3320", "blurb": "把问题留在灯下，答案明天再拆。"},
    {"id": "31", "name": "纸船航海", "year": "2020", "type_id": "4", "type_name": "动漫", "area": "演示", "actor": "纸船", "director": "演示组", "remarks": "全1集", "color": "0x203428", "blurb": "一只纸船决定自己找到海。"},
    {"id": "41", "name": "城市的夜", "year": "2019", "type_id": "5", "type_name": "纪录片", "area": "演示", "actor": "", "director": "演示组", "remarks": "正片", "color": "0x22262e", "blurb": "记录一座虚构城市如何把灯关掉。"},
]
ALL = MOVIES + SERIES + OTHERS
CLASSES = [
    {"type_id": "1", "type_name": "电影"},
    {"type_id": "2", "type_name": "电视剧"},
    {"type_id": "3", "type_name": "综艺"},
    {"type_id": "4", "type_name": "动漫"},
    {"type_id": "5", "type_name": "纪录片"},
]


def ensure_media() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    clips = {
        "line-a.mp4": "0x1a2744",
        "line-b.mp4": "0x5c4630",
    }
    for name, color in clips.items():
        path = ROOT / name
        if path.exists() and path.stat().st_size > 1000:
            continue
        subprocess.check_call(
            [
                "ffmpeg", "-y", "-f", "lavfi", "-i", f"color=c={color}:s=1280x720:d=20",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=20",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
                str(path),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    for item in ALL:
        path = ROOT / f"poster-{item['id']}.png"
        if path.exists():
            continue
        subprocess.check_call(
            [
                "ffmpeg", "-y", "-f", "lavfi", "-i", f"color=c={item['color']}:s=480x720",
                "-frames:v", "1", str(path),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )


def xmltv_stamp(moment: datetime) -> str:
    return moment.astimezone(TZ).strftime("%Y%m%d%H%M%S +0800")


def item_json(item: dict, base: str) -> dict:
    episodes = item.get("episodes") or ["正片"]
    line_a = "#".join(f"{name}${base}/media/line-a.mp4" for name in episodes)
    line_b = "#".join(f"{name}${base}/media/line-b.mp4" for name in episodes)
    return {
        "vod_id": item["id"],
        "vod_name": item["name"],
        "vod_year": item["year"],
        "type_id": item["type_id"],
        "type_name": item["type_name"],
        "vod_area": item["area"],
        "vod_actor": item["actor"],
        "vod_director": item["director"],
        "vod_remarks": item["remarks"],
        "vod_pic": f"{base}/poster/{item['id']}.png",
        "vod_blurb": item["blurb"],
        "vod_content": item["blurb"],
        "vod_play_from": "线路甲$$$线路乙",
        "vod_play_url": f"{line_a}$$${line_b}",
    }


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)
        base = f"http://{self.headers.get('Host', f'127.0.0.1:{PORT}')}"
        if path in ("/", "/tvbox.json"):
            self._json(self.tvbox(base))
            return
        if path.startswith("/api.php"):
            self._json(self.cms(base, query))
            return
        if path == "/live.m3u":
            body = (
                "#EXTM3U\n"
                f'#EXTINF:-1 tvg-id="demo1" tvg-name="演示一台" group-title="演示",演示一台\n{base}/media/line-a.mp4\n'
                f'#EXTINF:-1 tvg-id="demo2" tvg-name="演示二台" group-title="演示",演示二台\n{base}/media/line-b.mp4\n'
            )
            self._bytes(body.encode(), "audio/x-mpegurl")
            return
        if path == "/epg.xml":
            self._bytes(self.epg().encode(), "application/xml")
            return
        if path.startswith("/poster/"):
            item_id = path.removeprefix("/poster/").removesuffix(".png")
            file = ROOT / f"poster-{item_id}.png"
            if file.exists():
                self._bytes(file.read_bytes(), "image/png")
                return
        if path.startswith("/media/"):
            file = ROOT / path.removeprefix("/media/")
            if file.exists() and file.suffix == ".mp4":
                self._bytes(file.read_bytes(), "video/mp4")
                return
        self.send_error(404)

    def tvbox(self, base: str) -> dict:
        return {
            "sites": [
                {
                    "key": "demo",
                    "name": "演示片库",
                    "type": 1,
                    "api": f"{base}/api.php/provide/vod/",
                    "searchable": 1,
                    "quickSearch": 1,
                },
                {
                    "key": "spider",
                    "name": "示例爬虫",
                    "type": 3,
                    "api": "csp_Demo",
                    "searchable": 0,
                },
            ],
            "lives": [
                {
                    "name": "演示直播",
                    "type": 0,
                    "url": f"{base}/live.m3u",
                    "epg": f"{base}/epg.xml",
                }
            ],
        }

    def cms(self, base: str, query: dict) -> dict:
        chosen = list(ALL)
        type_id = (query.get("t") or [""])[0]
        keyword = (query.get("wd") or [""])[0]
        ids = (query.get("ids") or [""])[0]
        if type_id:
            chosen = [item for item in chosen if item["type_id"] == type_id]
        if keyword:
            chosen = [item for item in chosen if keyword in item["name"]]
        if ids:
            wanted = {part.strip() for part in ids.split(",") if part.strip()}
            chosen = [item for item in chosen if item["id"] in wanted]
        return {
            "code": 1,
            "page": 1,
            "pagecount": 1,
            "total": len(chosen),
            "class": CLASSES,
            "list": [item_json(item, base) for item in chosen],
        }

    def epg(self) -> str:
        now = datetime.now(TZ).replace(minute=0, second=0, microsecond=0) - timedelta(minutes=20)
        slots = [
            ("demo1", "晚间试映", now, now + timedelta(hours=1)),
            ("demo1", "灯市夜话", now + timedelta(hours=1), now + timedelta(hours=2)),
            ("demo2", "码头天气", now, now + timedelta(hours=1)),
            ("demo2", "北窗来信", now + timedelta(hours=1), now + timedelta(hours=2)),
        ]
        programmes = []
        for channel, title, start, stop in slots:
            programmes.append(
                f'<programme start="{xmltv_stamp(start)}" stop="{xmltv_stamp(stop)}" channel="{channel}">'
                f"<title>{title}</title></programme>"
            )
        return (
            '<?xml version="1.0" encoding="UTF-8"?>'
            "<tv>"
            '<channel id="demo1"><display-name lang="zh">演示一台</display-name></channel>'
            '<channel id="demo2"><display-name lang="zh">演示二台</display-name></channel>'
            + "".join(programmes)
            + "</tv>"
        )

    def _json(self, payload: dict) -> None:
        self._bytes(json.dumps(payload, ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def _bytes(self, body: bytes, content_type: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args) -> None:
        print("[mock]", self.address_string(), fmt % args)


def main() -> None:
    ensure_media()
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"演示桩 http://127.0.0.1:{PORT}/tvbox.json")
    server.serve_forever()


if __name__ == "__main__":
    main()
