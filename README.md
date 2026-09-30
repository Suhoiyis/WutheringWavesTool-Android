# WWT Companion

鸣潮（Wuthering Waves）的 Android 伴侣端：一屏看账号日常状态，附带抽卡分析。

自用，未上架，仓库私有。是 PC 端 [leck995/WutheringWavesTool](https://github.com/leck995/WutheringWavesTool) 的非官方移植，接口协议细节均为实测所得。

---

## 功能

### 主界面

打开即拉取当天状态，**下拉即可刷新**：

| 项目 | 说明 |
| --- | --- |
| 体力 / 结晶单质 | 显示当前值与回满倒计时；结晶单质在体力满之后才开始累积 |
| 活跃度 | 当日活跃度进度 |
| 周本剩余 | 每周一 04:00（Asia/Shanghai）重置，显示距下次刷新的倒计时 |
| 电台 | 电台等级与本周经验 |
| 奇藏箱 | 朴素 / 基准 / 精密 / 辉光，四种 |
| 声骸宝箱 | 潮汐之遗·绿 / 紫 / 金，三种 |

跟随系统深色模式。

### 抽卡分析

顶栏三个标签页 —— **统计 / 卡池 / 记录**：

- **各卡池独立统计**，角色活动、武器活动、角色常驻、新手、新手自选等互不干扰
- 五星平均抽取数、五星已垫 / 四星已垫抽数、不歪率
- 按 `record_id` **导出完整抽卡链接**，可直接粘进别的统计工具

---

## 环境要求

- JDK 17 或更高
- Android SDK，需含 **platform 37**（`compileSdk` / `targetSdk` = 37，`minSdk` = 24）
- 项目根目录放一个 `local.properties` 指向 SDK（该文件已被 gitignore）：

```properties
sdk.dir=/path/to/Android/Sdk
```

## 构建

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`，安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用

### 1. 首次使用：填 oauthCode

主界面数据来自库洛 PC 启动器的登录态，首次打开 App 需要粘一个 `oauthCode`。
用 `tools/oauth_code.py` 写进去最省事（需要 `adb` 在 PATH 里）：

```bash
python3 tools/oauth_code.py --push --launch
```

脚本会自动定位 `%APPDATA%\KR_G153\...\KRSDKUserLauncherCache.json`（国际服 / 港服；
国服在 `KR_G152`），按 XOR-5 解码取出 `oauthCode`，再经 `run-as` 以应用身份写进
App 私有目录的 SharedPreferences。

其他用法：

```bash
python3 tools/oauth_code.py          # 只打印 oauthCode
python3 tools/oauth_code.py --copy   # 顺便复制到系统剪贴板
python3 tools/oauth_code.py --list   # 列出所有缓存文件与账号
python3 tools/oauth_code.py -v       # 显示账号与来源（走 stderr）
```

换账号时在 PC 启动器重新登录，再跑一次 `--push`。App 右下角齿轮进设置页可以查看或替换凭证。

### 2. 抽卡数据

三种导入方式，选一种即可：

| 方式 | 适用场景 |
| --- | --- |
| 粘贴抽卡链接 | 通用。游戏里打开「唤取记录」页复制整条链接；**也可以只粘 `record_id`**（32 位十六进制），其余参数自动补全 |
| 导入 `pool.json` | 已有桌面版历史数据，用顶栏「导入历史数据」 |
| PC 端推送 | PC 玩家最省事：`python3 tools/oauth_code.py --sync-gacha --launch` |

数据存在 App 私有目录 `files/gacha/`。导入的历史记录会与本地合并，**只推增量也不会丢老数据**。

顶栏「导出完整链接」能把当前账号的抽卡链接还原出来，方便导进别的统计工具。其中
`gacha_id` / `gacha_type` / `resources_id` / `platform` 是照游戏格式补的占位值 ——
官方接口不读这几个字段，粘到别处也不影响。

---

## 目录结构

```
app/src/main/java/cn/tealc/wwtcompanion/
├── MainActivity.kt       主界面 + 设置页
├── LauncherApi.kt        账号数据接口
├── AccountData.kt        账号数据模型
├── CredentialStore.kt    oauthCode 存储
├── GameLogLocator.kt     游戏日志路径（当前未被引用）
└── gacha/                抽卡分析
    ├── GachaApi.kt       接口调用 + 链接拼装
    ├── GachaJson.kt      pool.json / 接口响应解析
    ├── GachaStats.kt     统计计算
    ├── GachaStore.kt     本地存储与合并
    ├── GachaScreen.kt    抽卡页 UI
    ├── GachaAvatar.kt    角色头像
    └── GachaModels.kt    数据模型

tools/oauth_code.py       从 PC 启动器缓存提取 oauthCode / 抽卡链接
```

单元测试在 `app/src/test/`，覆盖 JSON 解析、统计口径与链接拼装。

---

## 已知情况

- **国服未实测**：`GachaApi.kt` 里国服的记录页域名与 `svr_area=cn` 是按国际服类推的，
  手上只有港服账号，未实际验证。
- **`record_id` 会过期**：它按平台会话签发。失效后到游戏里重开一次「唤取记录」页面即可。
- **PS5 令牌拿不到**：`--sync-gacha` 只能读 PC 的 `Client.log`，主机端玩家请用粘贴链接的方式。

## 来源说明

本项目是 PC 端 [leck995/WutheringWavesTool](https://github.com/leck995/WutheringWavesTool)
的非官方 Android 移植。上游仓库**没有 LICENSE 文件**（README 里的 MIT 徽章没有对应的许可正文，
且链接指向无关章节），因此上游默认保留全部权利。

本仓库仅作私有自用，未获授权分发；如需公开，请先取得上游作者许可。
