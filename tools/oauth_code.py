#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从库洛 PC 启动器的本地缓存里抓取 oauthCode，可选一键推送到手机 App。

顺带也能把桌面版的抽卡记录（``pool.json``）推进 App —— 抽卡分析需要的历史数据
一次导入就永久可用，不用再管令牌过不过期。

背景
----
官方 PC 启动器把登录态写在缓存文件里::

    %APPDATA%\\KR_G152\\<子目录>\\KRSDKUserLauncherCache.json   # 国服
    %APPDATA%\\KR_G153\\<子目录>\\KRSDKUserLauncherCache.json   # 国际服/港服

其中 ``oauthCode`` 字段用 **XOR 5** 混淆（每个字符的码位异或 5）。
本脚本会自动定位该文件、解码、打印，并可选地写进 Android App 的
SharedPreferences，省得手抄。

用法
----
默认行为就是「把 oauthCode 打到终端」，方便直接复制去手机里粘贴::

    python oauth_code.py                  # 只输出一行 oauthCode
    python oauth_code.py --copy           # 顺便复制到系统剪贴板
    python oauth_code.py -v               # 额外显示账号/来源（走 stderr）
    python oauth_code.py --list           # 列出所有缓存文件与账号
    python oauth_code.py --push           # 直接写入手机 App（需要 adb）
    python oauth_code.py --push --launch  # 写入后顺便把 App 拉起来
    python oauth_code.py --file /path/to/KRSDKUserLauncherCache.json
    python oauth_code.py --push-gacha "D:/wuwa/.../data/<roleId>/pool.json"
    python oauth_code.py --push-gacha pool.json --launch

抽卡链接
--------
游戏把「抽卡记录」的链接写在 ``Client.log`` 里（XOR 加密）。但**那个链接会过期** ——
日志里存的只是最后一次打开「唤取记录」页时的令牌，可能几小时前就失效了。
所以下面的命令都会**先真打一次接口验证**，通过才继续；不通过会明确告诉你
「链接已过期，去游戏里重新开一次唤取记录页」，而不是把一条死链接交出来::

    python oauth_code.py --gacha-url      # 验证后打印链接（stdout 只有这一行）
    python oauth_code.py --gacha-url -v   # 顺带显示用的哪个日志、哪个角色
    python oauth_code.py --sync-gacha     # 验证 → 抓 13 个卡池 → 直接推到手机
    python oauth_code.py --sync-gacha -v --launch

``--sync-gacha`` 是 PC 玩家的推荐用法：**一条命令，全程不用复制粘贴**。
抓到的记录推给 App 后会自动和本地历史合并，所以只推增量也不会丢老数据。
游戏装在非默认位置时用 ``--game-dir``（或环境变量 ``WWT_GAME_LOG``）指路。

``--push-gacha`` 推的是桌面版现成的 ``pool.json`` 文件；``--sync-gacha`` 是
自己去抓最新的。两者都写进 App 私有目录的 ``files/gacha/import.json``，
**下次打开 App 的抽卡页会自动导入并合并**（App 会把这个文件删掉，推一次导一次）。
这是应用私有目录，没法直接 push，靠 ``run-as`` 以应用身份写入。

stdout 永远只有裸 oauthCode（``--list`` 和 ``--gacha-url`` 除外），所以
``> code.txt`` 之类也安全。

依赖：仅 Python 3.8+ 标准库。``--copy`` 需要 wl-copy/xclip/xsel/pbcopy 之一，
``--push`` / ``--push-gacha`` / ``--sync-gacha`` 需要 PATH 里有 ``adb``。
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from typing import Dict, List, NamedTuple, Optional, Tuple

CACHE_NAME = "KRSDKUserLauncherCache.json"
GAME_DIRS = ("KR_G152", "KR_G153")  # G152=国服, G153=国际服(含港服)
DEFAULT_PACKAGE = "cn.tealc.wwtcompanion"
PREFS_NAME = "wwt_companion"
PREFS_KEY = "oauth_code"
# 单条 adb 命令的等待上限；设备掉线时不能让脚本无声无息地挂着
ADB_TIMEOUT_SEC = 60.0


# --------------------------------------------------------------------------- #
# 定位缓存文件
# --------------------------------------------------------------------------- #
class CacheFile(NamedTuple):
    path: str
    mtime: float


def _roaming_roots() -> List[str]:
    """列出所有可能包含库洛启动器缓存的 ``AppData/Roaming`` 目录。"""
    roots: List[str] = []

    # 直接在 Windows 上跑
    appdata = os.environ.get("APPDATA")
    if appdata:
        roots.append(appdata)

    if sys.platform == "win32":
        return roots

    # 在 Linux 上跑：去找挂载好的 Windows 分区 / Wine 前缀
    home = os.path.expanduser("~")
    patterns = [
        "/run/media/*/*/Users/*/AppData/Roaming",
        "/media/*/*/Users/*/AppData/Roaming",
        "/mnt/*/Users/*/AppData/Roaming",
        "/mnt/*/*/Users/*/AppData/Roaming",
        os.path.join(home, ".wine/drive_c/users/*/AppData/Roaming"),
    ]
    for pattern in patterns:
        roots.extend(glob.glob(pattern))

    # 去重且保序
    seen = set()
    unique = []
    for root in roots:
        real = os.path.realpath(root)
        if real not in seen and os.path.isdir(root):
            seen.add(real)
            unique.append(root)
    return unique


def find_cache_files(extra: Optional[List[str]] = None) -> List[CacheFile]:
    """返回所有找到的缓存文件，按修改时间从新到旧排序。"""
    candidates: List[str] = list(extra or [])

    env_path = os.environ.get("WWT_LAUNCHER_CACHE")
    if env_path:
        candidates.append(env_path)

    for root in _roaming_roots():
        for game in GAME_DIRS:
            candidates.extend(glob.glob(os.path.join(root, game, "*", CACHE_NAME)))
            # 少数版本可能直接放在游戏目录下
            candidates.append(os.path.join(root, game, CACHE_NAME))

    found: Dict[str, CacheFile] = {}
    for path in candidates:
        if not os.path.isfile(path):
            continue
        real = os.path.realpath(path)
        try:
            mtime = os.path.getmtime(path)
        except OSError:
            mtime = 0.0
        # 同一个文件被多个挂载点看到时，保留先出现的那个
        found.setdefault(real, CacheFile(path, mtime))

    return sorted(found.values(), key=lambda c: c.mtime, reverse=True)


# --------------------------------------------------------------------------- #
# 解码
# --------------------------------------------------------------------------- #
def decode_xor5(value: str) -> str:
    """库洛的混淆：每个字符与 5 异或。"""
    return "".join(chr(ord(ch) ^ 5) for ch in value)


class Account(NamedTuple):
    cache: CacheFile
    oauth_code: str
    email: str
    nickname: str
    cuid: str


def _text(raw: object) -> str:
    """缓存里除 oauthCode 之外的字段都是明文，直接用。"""
    return raw if isinstance(raw, str) else ""


def load_accounts(cache: CacheFile) -> List[Account]:
    try:
        with open(cache.path, "r", encoding="utf-8", errors="replace") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as exc:
        raise RuntimeError("读取失败 %s: %s" % (cache.path, exc)) from exc

    if isinstance(data, dict):
        data = [data]

    accounts: List[Account] = []
    for entry in data or []:
        if not isinstance(entry, dict):
            continue
        raw_code = entry.get("oauthCode")
        if not isinstance(raw_code, str) or not raw_code:
            continue
        accounts.append(
            Account(
                cache=cache,
                oauth_code=decode_xor5(raw_code),
                email=_text(entry.get("email")),
                nickname=_text(entry.get("thirdNickName")),
                cuid=_text(entry.get("cuid")) or _text(entry.get("id")),
            )
        )
    return accounts


# --------------------------------------------------------------------------- #
# 推送到手机
# --------------------------------------------------------------------------- #
def _adb(*args: str, check: bool = True, stdin: Optional[bytes] = None,
         timeout: float = ADB_TIMEOUT_SEC) -> str:
    adb = shutil.which("adb")
    if not adb:
        raise RuntimeError("PATH 里找不到 adb，无法推送到手机")
    try:
        proc = subprocess.run(
            [adb, *args],
            input=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        # 没有这个的话，设备中途掉线/卡死会让脚本一声不吭地永远挂着
        raise RuntimeError(
            "adb %s 超过 %.0f 秒没响应，设备可能掉线了（重插数据线或 adb kill-server 再试）"
            % (" ".join(args[:3]), timeout)
        )
    output = proc.stdout.decode("utf-8", errors="replace")
    if check and proc.returncode != 0:
        raise RuntimeError("adb %s 失败:\n%s" % (" ".join(args), output.strip()))
    return output


def _check_package(package: str) -> str:
    """校验包名。

    包名会被拼进设备端的 `sh -c '...'` 字符串里，不校验的话
    `--package 'cn.tealc.wwtcompanion; rm -rf /sdcard'` 就会在手机上执行任意命令。
    合法包名只有字母、数字、下划线和点。
    """
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.]*", package or ""):
        raise RuntimeError("包名不合法：%r（只允许字母、数字、下划线和点）" % package)
    return package


def _xml_escape(value: str) -> str:
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def _ensure_device() -> None:
    """确认有一台已授权的设备，否则抛出带指引的异常。"""
    devices = _adb("devices").strip().splitlines()[1:]
    if not any(line.strip().endswith("device") for line in devices):
        raise RuntimeError("没有已授权的设备。请插好数据线并在手机上允许 USB 调试。")


def push_to_device(oauth_code: str, package: str, launch: bool) -> None:
    _check_package(package)
    _ensure_device()

    xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        '    <string name="%s">%s</string>\n'
        "</map>\n" % (PREFS_KEY, _xml_escape(oauth_code))
    )

    # 必须先停掉 App：否则它内存里那份旧配置会在退出时把文件覆盖回去
    _adb("shell", "am", "force-stop", package)

    rel = "shared_prefs/%s.xml" % PREFS_NAME
    _adb(
        "shell",
        "run-as %s sh -c 'mkdir -p shared_prefs && cat > %s && chmod 660 %s'" % (package, rel, rel),
        stdin=xml.encode("utf-8"),
    )

    written = _adb("shell", "run-as", package, "cat", rel).strip()
    if oauth_code not in written:
        raise RuntimeError("写入后校验失败，手机上的内容:\n%s" % written)

    if launch:
        _adb("shell", "am", "start", "-n", "%s/.MainActivity" % package, check=False)


def push_gacha_payload(data: Dict[str, list], package: str, launch: bool) -> int:
    """把卡池数据写进 App 私有目录，返回推过去的记录条数。

    App 下次打开抽卡页会自动导入并**与本地历史合并**，所以这里只推新抓到的
    那部分也没问题 —— 更老的记录 App 自己留着。
    """
    _check_package(package)

    total = sum(len(v) for v in data.values() if isinstance(v, list))
    if total == 0:
        raise RuntimeError("一条抽卡记录都没有，不推了")

    raw = json.dumps(data, ensure_ascii=False).encode("utf-8")

    _ensure_device()
    # 停掉 App，保证它下次进抽卡页时 LaunchedEffect 会重新跑一遍
    _adb("shell", "am", "force-stop", package)

    rel = "files/gacha/import.json"
    _adb(
        "shell",
        "run-as %s sh -c 'mkdir -p files/gacha && cat > %s'" % (package, rel),
        stdin=raw,
    )

    size_line = _adb("shell", "run-as", package, "wc", "-c", rel).strip()
    written = int(size_line.split()[0]) if size_line.split() else -1
    if written != len(raw):
        raise RuntimeError("写入校验失败：本地 %d 字节，手机上 %d 字节" % (len(raw), written))

    if launch:
        _adb("shell", "am", "start", "-n", "%s/.MainActivity" % package, check=False)
    return total


def push_gacha_file(source: str, package: str, launch: bool) -> None:
    """把桌面版的 ``pool.json`` 推进 App 私有目录，App 下次打开抽卡页会自动导入。

    走的是 App 自己的 ``files/gacha/import.json``：应用私有目录没法直接 push，
    必须借 ``run-as`` 以应用身份写入。导入成功后 App 会把这个文件删掉。
    """
    path = os.path.expanduser(source)
    if not os.path.isfile(path):
        raise RuntimeError("找不到文件：%s" % path)

    with open(path, "rb") as handle:
        raw = handle.read()

    # 先自己校验一遍：推一个坏文件上去，App 只会静默地什么都不做，很难排查
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise RuntimeError("这不是合法的 JSON：%s" % exc)
    if not isinstance(data, dict):
        raise RuntimeError("pool.json 应当是以卡池名为键的对象")

    total = sum(len(v) for v in data.values() if isinstance(v, list))
    if total == 0:
        raise RuntimeError("里面一条抽卡记录都没有，检查一下是不是推错文件了")

    pushed = push_gacha_payload(data, package, launch)
    print("✓ 已推送 %d 条抽卡记录（%d 个卡池）" % (pushed, len(data)), file=sys.stderr)


def copy_to_clipboard(text: str) -> Optional[str]:
    """把文本塞进系统剪贴板；成功返回所用工具名，都不行则返回 None。"""
    candidates = (
        ("wl-copy",),
        ("xclip", "-selection", "clipboard"),
        ("xsel", "--clipboard", "--input"),
        ("pbcopy",),
    )
    for cmd in candidates:
        exe = shutil.which(cmd[0])
        if not exe:
            continue
        try:
            proc = subprocess.run(
                [exe, *cmd[1:]],
                input=text.encode("utf-8"),
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=5,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if proc.returncode == 0:
            return cmd[0]
    return None


# --------------------------------------------------------------------------- #
# 抽卡链接：从游戏日志里抠出来，并且**验证它还能不能用**
# --------------------------------------------------------------------------- #
GACHA_URL_RE = re.compile(r"""https?://[^\s"']*?/aki/gacha/index\.html#/record[^\s"']*""")
GACHA_API = {
    "global": "https://gmserver-api.aki-game2.net/gacha/record/query",
    "cn": "https://gmserver-api.aki-game2.com/gacha/record/query",
}
GAME_LOG_TAIL = "Client/Saved/Logs/Client.log"
# 游戏根目录的几种常见嵌套（父目录 / 父目录的子目录）
GAME_LOG_PATTERNS = (
    "Wuthering Waves Game/" + GAME_LOG_TAIL,
    "*/Wuthering Waves Game/" + GAME_LOG_TAIL,
    "Wuthering Waves/" + GAME_LOG_TAIL,
    "*/" + GAME_LOG_TAIL,
)

# 卡池名（顺序即请求里的 cardPoolType 1..13），与 App / 桌面版一致
POOL_NAMES: Tuple[str, ...] = (
    "角色活动唤取", "武器活动唤取", "角色常驻唤取", "武器常驻唤取",
    "新手唤取", "新手自选唤取", "新手自选唤取（感恩定向唤取）",
    "角色新旅唤取", "武器新旅唤取", "角色联动唤取",
    "武器联动唤取", "角色忆旅唤取", "武器忆旅唤取",
)

# XOR 表：每个字节按低半字节的奇偶决定异或 0xA5 还是 0xEF。
# 这是逐字节替换，所以预先算成 256 项的表，用 bytes.translate 走 C 速度。
_LOG_XOR = bytes(
    (b ^ 0xA5) if ((b & 0x0F) % 2) == 1 else (b ^ 0xEF) for b in range(256)
)


def _game_roots() -> List[str]:
    """可能放着游戏安装目录的位置。"""
    roots: List[str] = []
    for pattern in ("/run/media/*/*", "/media/*/*", "/media/*", "/mnt/*", "/mnt/*/*"):
        roots.extend(glob.glob(pattern))
    for drive in "CDEFGH":
        roots.append("%s:/" % drive)
    home = os.path.expanduser("~")
    roots.extend(glob.glob(os.path.join(home, ".wine/drive_c/*")))
    seen, unique = set(), []
    for root in roots:
        if root not in seen:
            seen.add(root)
            unique.append(root)
    return unique


def find_game_log(explicit: Optional[str] = None) -> Optional[str]:
    """定位游戏的 ``Client.log``。可用 ``WWT_GAME_LOG`` 直接指定。"""
    if explicit:
        path = os.path.expanduser(explicit)
        if os.path.isdir(path):
            path = os.path.join(path, GAME_LOG_TAIL)
        return path if os.path.isfile(path) else None

    env = os.environ.get("WWT_GAME_LOG")
    if env and os.path.isfile(env):
        return env

    hits: List[str] = []
    for root in _game_roots():
        for pattern in GAME_LOG_PATTERNS:
            hits.extend(glob.glob(os.path.join(root, pattern)))
    if not hits:
        return None
    # 多个客户端时取最近写过的那个。用 getmtime 排序时文件可能刚好被移走/换掉，
    # 拿不到 mtime 的当成最旧，而不是让 OSError 冒出去变成 traceback
    def _mtime(path: str) -> float:
        try:
            return os.path.getmtime(path)
        except OSError:
            return 0.0

    return max(hits, key=_mtime)


def extract_gacha_params(log_path: str) -> Optional[Dict[str, str]]:
    """解密日志并抠出卡池链接里的请求参数。取**最后一条**（也就是最新的）。"""
    try:
        with open(log_path, "rb") as handle:
            text = handle.read().translate(_LOG_XOR).decode("utf-8", "replace")
    except OSError as exc:
        # 日志被游戏占用、权限不对、刚好被轮转掉，都会走到这里。
        # 抛出脚本自己的提示，比丢一个 PermissionError traceback 有用得多
        raise RuntimeError(
            "读不了游戏日志 %s：%s\n"
            "  游戏正在运行时日志可能被占用，可以先把游戏关掉再试。" % (log_path, exc)
        )

    matches = GACHA_URL_RE.findall(text)
    if not matches:
        return None

    url = matches[-1]
    # 日志里 & 可能被转义成 \u0026
    url = re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), url)
    query = url.split("#/record?", 1)[-1]
    pairs = dict(re.findall(r"([A-Za-z_][A-Za-z0-9_]*)=([^&\s]+)", query))

    player_id = pairs.get("player_id")
    record_id = pairs.get("record_id")
    if not player_id or not record_id:
        return None
    return {
        "playerId": player_id,
        "recordId": record_id,
        "cardPoolId": pairs.get("resources_id", ""),
        "serverId": pairs.get("svr_id", ""),
        "languageCode": pairs.get("lang", "zh-Hans"),
        "svrArea": pairs.get("svr_area", ""),
        "url": url,
    }


def _gacha_endpoint(params: Dict[str, str]) -> str:
    area = (params.get("svrArea") or "").lower()
    if area == "global" or (not area and not params["playerId"].startswith("1")):
        return GACHA_API["global"]
    return GACHA_API["cn"]


def _post_json(url: str, payload: dict, timeout: int = 20) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_gacha(params: Dict[str, str], progress=None) -> Dict[str, List[dict]]:
    """拉取全部 13 个卡池。

    单池失败不会中断整体：拿到几个算几个。全部失败时抛 ``RuntimeError``，
    并把服务端原话带出来（通常是「请求游戏获取日志异常!」= 链接已过期）。
    """
    endpoint = _gacha_endpoint(params)
    result: Dict[str, List[dict]] = {}
    errors: List[str] = []
    succeeded = 0

    for index, name in enumerate(POOL_NAMES, start=1):
        if progress:
            progress(index, len(POOL_NAMES), name)
        payload = {
            "playerId": params["playerId"],
            "recordId": params["recordId"],
            "cardPoolId": params["cardPoolId"],
            "cardPoolType": str(index),
            "serverId": params["serverId"],
            "languageCode": params["languageCode"],
        }
        try:
            body = _post_json(endpoint, payload)
        except (urllib.error.URLError, ValueError, OSError) as exc:
            errors.append("%s: %s" % (name, exc))
            continue
        if body.get("code") == 0:
            succeeded += 1
            result[name] = body.get("data") or []
        else:
            errors.append("%s: %s" % (name, body.get("message") or body.get("code")))

    if succeeded == 0:
        detail = errors[0] if errors else "未知错误"
        raise RuntimeError(
            "13 个卡池全部取不到数据（%s）。\n"
            "  多半是链接已经过期 —— 请到游戏里打开一次「唤取记录」页面再试。" % detail
        )
    return result


def resolve_fresh_gacha(
    game_log: Optional[str],
    verbose: bool,
) -> Dict[str, str]:
    """定位日志 → 抠参数 → **实测一次**，确认链接真的可用后才返回。

    只验证第一个卡池即可：令牌是全卡池共用的，通不过就是过期了。
    """
    log_path = find_game_log(game_log)
    if not log_path:
        raise RuntimeError(
            "没找到游戏的 Client.log。\n"
            "  可用 --game-dir 指定游戏目录，或设环境变量 WWT_GAME_LOG。"
        )
    if verbose:
        print("日志  : %s" % log_path, file=sys.stderr)

    params = extract_gacha_params(log_path)
    if not params:
        raise RuntimeError(
            "日志里没有抽卡链接。\n"
            "  请先在游戏里打开一次「抽卡 → 唤取记录」页面，游戏才会把链接写进日志。"
        )

    payload = {
        "playerId": params["playerId"],
        "recordId": params["recordId"],
        "cardPoolId": params["cardPoolId"],
        "cardPoolType": "1",
        "serverId": params["serverId"],
        "languageCode": params["languageCode"],
    }
    try:
        body = _post_json(_gacha_endpoint(params), payload)
    except (urllib.error.URLError, ValueError, OSError) as exc:
        raise RuntimeError("验证链接时网络出错：%s" % exc)

    if body.get("code") != 0:
        raise RuntimeError(
            "日志里这条链接**已经不能用了**（服务端说：%s）。\n"
            "  日志只保存最后一次打开「唤取记录」页时的令牌，会过期。\n"
            "  请到游戏里重新打开一次该页面，再跑一遍。" % (body.get("message") or body.get("code"))
        )

    if verbose:
        print("角色  : %s" % params["playerId"], file=sys.stderr)
        print("令牌  : %s…（已验证可用）" % params["recordId"][:8], file=sys.stderr)
    return params


# --------------------------------------------------------------------------- #
# 入口
# --------------------------------------------------------------------------- #
def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="从库洛 PC 启动器缓存抓取 oauthCode（XOR-5 解码）。"
                    "默认只往 stdout 打印裸 oauthCode，方便直接复制。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--file", action="append", metavar="PATH",
                        help="手动指定缓存文件，可重复")
    parser.add_argument("--list", action="store_true", help="列出所有找到的缓存文件与账号")
    parser.add_argument("--account", type=int, default=0, metavar="N",
                        help="有多个账号时选第 N 个（从 0 开始，默认 0=最新）")
    parser.add_argument("-v", "--verbose", action="store_true",
                        help="额外显示账号与来源（输出到 stderr，不影响 stdout）")
    parser.add_argument("--copy", action="store_true", help="顺便复制到系统剪贴板")
    parser.add_argument("--push", action="store_true", help="把 oauthCode 写进手机 App")
    parser.add_argument("--push-gacha", metavar="POOL_JSON",
                        help="把桌面版的 pool.json 推进 App（打开抽卡页自动导入）")
    parser.add_argument("--gacha-url", action="store_true",
                        help="从游戏日志抠出抽卡链接，**验证可用后**才打印")
    parser.add_argument("--gacha-record-id", action="store_true",
                        help="只打印 record_id（App 里填这一个就够），同样先验证")
    parser.add_argument("--sync-gacha", action="store_true",
                        help="从游戏日志抓最新抽卡记录并推送到手机 App")
    parser.add_argument("--game-dir", metavar="PATH",
                        help="手动指定游戏目录（含 Client/ 的那个）")
    parser.add_argument("--launch", action="store_true", help="推送后顺带启动 App")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help="App 包名")
    parser.add_argument("-q", "--quiet", action="store_true",
                        help="兼容旧用法；默认已经是纯输出，此参数现在无作用")
    args = parser.parse_args(argv)

    # 推抽卡数据跟 oauthCode 毫无关系，先短路掉，免得还要去找启动器缓存
    if args.push_gacha:
        try:
            push_gacha_file(args.push_gacha, args.package, args.launch)
        except RuntimeError as exc:
            print("✗ %s" % exc, file=sys.stderr)
            return 1
        return 0

    if args.gacha_url or args.sync_gacha or args.gacha_record_id:
        # 关键：日志里的链接可能早就过期了，必须先真打一次接口确认
        try:
            params = resolve_fresh_gacha(args.game_dir, args.verbose)
        except RuntimeError as exc:
            print("✗ %s" % exc, file=sys.stderr)
            return 1

        # App 里只填 record_id 就够了：UID 它自己知道，区服能从 UID 首位推出来
        if args.gacha_record_id:
            print(params["recordId"])
            return 0

        if args.gacha_url:
            print(params["url"])
            return 0

        print("链接有效，开始抓取 13 个卡池…", file=sys.stderr)
        try:
            data = fetch_gacha(
                params,
                progress=lambda i, n, name: print(
                    "  [%2d/%d] %s" % (i, n, name), file=sys.stderr
                ) if args.verbose else None,
            )
            pushed = push_gacha_payload(data, args.package, args.launch)
        except RuntimeError as exc:
            print("✗ %s" % exc, file=sys.stderr)
            return 1

        got = sum(len(v) for v in data.values())
        print("✓ 抓到 %d 条记录，已推送到手机（App 下次打开自动合并）" % got, file=sys.stderr)
        if got != pushed:
            print("! 推送条数与抓取条数不一致", file=sys.stderr)
        return 0

    caches = find_cache_files(args.file)
    if not caches:
        print("✗ 没找到 %s" % CACHE_NAME, file=sys.stderr)
        print("  请确认：① 电脑上装过库洛官方启动器并登录过；"
              "② Windows 分区已挂载（或已设置 WWT_LAUNCHER_CACHE）", file=sys.stderr)
        for root in _roaming_roots():
            print("  已扫描: %s" % root, file=sys.stderr)
        return 1

    accounts: List[Account] = []
    for cache in caches:
        try:
            accounts.extend(load_accounts(cache))
        except RuntimeError as exc:
            print("! %s" % exc, file=sys.stderr)

    if not accounts:
        print("✗ 找到了缓存文件，但里面没有 oauthCode（可能没登录过）", file=sys.stderr)
        return 1

    if args.list:
        for index, acc in enumerate(accounts):
            print("[%d] %s" % (index, acc.nickname or "(无昵称)"))
            print("    邮箱  : %s" % (acc.email or "-"))
            print("    cuid  : %s" % (acc.cuid or "-"))
            print("    来源  : %s" % acc.cache.path)
        return 0

    if args.account < 0 or args.account >= len(accounts):
        print("✗ --account %d 越界，共 %d 个账号" % (args.account, len(accounts)),
              file=sys.stderr)
        return 1

    chosen = accounts[args.account]

    if args.verbose:
        print("账号  : %s %s" % (chosen.nickname or "-", chosen.email or ""), file=sys.stderr)
        print("来源  : %s" % chosen.cache.path, file=sys.stderr)
        if len(accounts) > 1:
            print("提示  : 共 %d 个账号，用 --list 查看、--account N 切换" % len(accounts),
                  file=sys.stderr)

    if args.copy:
        tool = copy_to_clipboard(chosen.oauth_code)
        if tool:
            print("✓ 已复制到剪贴板（%s）" % tool, file=sys.stderr)
        else:
            print("! 没找到剪贴板工具（wl-copy/xclip/xsel/pbcopy），请手动复制", file=sys.stderr)

    if args.push:
        try:
            push_to_device(chosen.oauth_code, args.package, args.launch)
        except RuntimeError as exc:
            print("✗ 推送失败: %s" % exc, file=sys.stderr)
            return 1
        # 已经写进手机了就别再把登录态打到终端：它会留在回滚缓冲、
        # 重定向出来的文件、CI 日志里。想看明文就不要加 --push。
        print("✓ 已写入手机 %s" % args.package, file=sys.stderr)
    else:
        # stdout 只放裸 oauthCode 一行，方便选中复制 / 重定向
        print(chosen.oauth_code)

    return 0


if __name__ == "__main__":
    sys.exit(main())
