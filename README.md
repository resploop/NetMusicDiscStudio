# NetMusic Disc Studio

> 把网易云专辑和 B 站视频刻进唱片，丢进唱片机当背景音乐。

**NetMusic Disc Studio** 是 [NetMusic](https://github.com/TartaricAcid/NetMusic) 与
[NetMusicCanPlayBili](https://github.com/zhongbai2333/NetMusicCanPlayBili) 的附属模组，
为**现代化唱片机**补上上游没有的两件事：

- 一张**能反复改写**的唱片 —— 换歌不用重新做一个
- 一张**装得下一整张专辑**的唱片 —— 顺序播放、自由切歌、随机与循环

## 依赖

| 模组 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 26.1.2 | |
| NeoForge | 26.1.2+ | |
| [NetMusic](https://github.com/TartaricAcid/NetMusic) | 1.5.1+ | **必需**。曲目数据模型与音频链路都来自它 |
| [NetMusicCanPlayBili](https://github.com/zhongbai2333/NetMusicCanPlayBili) | 0.7.0+ | **必需**。现代化唱片机与 B 站音源解析 |

需要 Java 25。

## 两个物品

| 物品 | 用途 |
| --- | --- |
| **网络CD** | 刻入一整张网易云专辑 / 歌单，放入唱片机后可**选曲、切歌**。刻录后封面会渲染成光盘贴图 |
| **可擦写网络唱片** | 黑胶外观。刻入 B 站 BV 号后可直接播放，并且**随时能改写** |

两者都是无序合成，都以 NetMusic 的空白唱片 `netmusic:music_cd` 为底：

| 产物 | 材料 |
| --- | --- |
| 网络CD | `netmusic:music_cd` + 红石 + 紫水晶碎片 + 末影珍珠 |
| 可擦写网络唱片 | `netmusic:music_cd` + 红石 + 荧石粉 + 铁粒 |

刻录用**唱片刻录机**（上游方块），播放用**现代化唱片机**。
创造模式物品栏会多出「NetMusic Disc Studio」一页。

## 功能

- **整张专辑刻录** —— 输入网易云专辑 / 歌单地址，一次性写入全部曲目。超出上限会截断，并在 tooltip 里提示
- **选曲与切歌** —— 在唱片机界面直接跳到任意一首
- **循环与随机** —— 顺序 / 单曲 / 列表 / 随机四种模式；随机模式下「换一首」也是随机的
- **专辑封面即唱片外观** —— 封面在后台下载并裁成光盘形状，直接作为物品贴图。下载失败会退回彩虹光盘，并在 tooltip 里说明原因
- **可擦写** —— 同一张碟换 BV 号、换专辑，不消耗新材料
- **也能放进 MP4** —— 网络CD塞进下游模组的 MP4 后，在播放列表里是一行 `[专辑] 专辑名`，
  点进去就是整张专辑的曲目（见下）
- **网易云 Cookie 登录** —— 登录后可以播放 VIP 歌曲（见下）

## 放进 MP4

把网络CD放进 MP4（上游的 `netmusic_can_play_bili:mp4`），它的播放列表里会多出一行
**`[专辑] 专辑名 (N首) >`**：

- **点这一行** → 列表切换成这张专辑的曲目，第 0 行是 `< 返回 · 专辑名`，再点一下就退回队列
- **点某首曲目** → 直接跳到那首开始播
- **上一首 / 下一首** 在专辑内部逐首走，走到专辑最后一首再继续就是队列里的下一张
- MP4 自己的 **单曲循环 / 顺序 / 列表循环** 对专辑曲目同样生效 —— 顺序播到专辑最后一首会继续
  后面的队列，列表循环会把这张专辑从头再来
- **随机**模式下「换一首」在当前视图范围内随机 —— 站在专辑里就专辑内随机，在列表层就整个队列随机

实现上，**一张网络CD = 一条队列项**，和普通唱片一样。专辑的曲目列表和「当前播到第几首」
都记在这张碟自己的数据里，所以整张专辑天然只占一条 —— 本模组不需要、也不会把它摊平。
（早期版本确实摊平过，结果"放一张 18 首的专辑就把队列占满、再也塞不进别的碟"，那套已经删掉了。）

> **上限**：MP4 队列最多 18 条（上游 `MP4Item.MAX_QUEUE_SIZE`），也就是**最多 18 张碟**，
> 一张碟里装多少首互不影响。队列满时上游会静默拒绝放入，本模组会补一条浮层提示。
> 上游的唱片机没有这个限制。

## 网易云登录与 VIP 播放

模组**不内置任何账号信息**，登录态只存在本机 `config/netmusic_disc_studio-netease.json`，不会上传。

### 推荐：Cookie 登录

浏览器登录 [music.163.com](https://music.163.com) → `F12` → Application / 存储 → Cookies →
复制 `MUSIC_U` 那一项 → 粘贴进模组的登录界面。

整条 Cookie（`__csrf=…; MUSIC_U=…`）、单独一项（`MUSIC_U=…`）、只复制了值（一长串没有等号的
token）三种写法都能识别，裸 token 会自动补上前缀。

### 扫码登录大概率失败，这是网易云的风控

失败时错误码是 **`8821`**（「请切换其他登录方式或升级新版本再试」）。这**不是本模组的 bug**：

- 未扫码时轮询稳定返回 `801 等待扫码`，四种组合（加密 / 明文接口 × 新旧 User-Agent）**全部如此**，
  说明我们的请求本身没有问题；
- 8821 只在**手机点下「确认登录」的那一刻**由服务端返回，是按账号 / IP 做的判定，客户端换请求头、
  换端点都无效；
- 维护最勤的第三方实现（ncmctl 等）至今也声明暂不支持扫码登录。

所以界面上扫码只是**备用入口**，且默认不会主动发任何请求（避免频繁请求加重风控）。日常请用 Cookie。

### 为什么 VIP 歌曲能播

上游走的 `music.163.com/song/media/outer/url` 这个老接口，对**收费曲目**恒返回 `302` 到 404
（响应体 0 字节），带不带 Cookie 都一样 —— 客户端拿到空流就会抛
`UnsupportedAudioFileException`。

本模组注册了一个优先级低于 B 站解析器的扩展点，改用 `player/url` 接口取真实直链，
因此 Cookie 登录后 VIP 曲目可以正常播放。若你的服务器需要严格遵守网易云 VIP 限制，
可以在配置里把 `allowNetEaseDirectPlayback` 关掉。

## 安装（玩家）

1. 装好上表里的 NetMusic 与 NetMusicCanPlayBili
2. 把本模组的 jar 丢进 `mods/`
3. 启动游戏

## 构建（开发者）

需要 JDK 25，以及一张 NetMusicCanPlayBili 的 jar。

```bash
# 1) 取 NetMusicCanPlayBili 的 jar 放进 libs/
#    它没有发布到任何 Maven 仓库，只能从 Release 下：
#    https://github.com/zhongbai2333/NetMusicCanPlayBili/releases
#    文件名形如 net_music_can_play_bili-<版本>+mc26.1.2-neoforge.jar
ls libs/*.jar

# 2) 构建
./gradlew build
# 产物：build/libs/netmusic-disc-studio-<版本>.jar
```

- NetMusic 会通过 Modrinth Maven **自动下载**，不要把它的 jar 放进 `libs/`（classpath 上会出现两份）
- 也可以用 `./gradlew build -PncpbJar=<jar 的绝对路径>` 临时指定上游 jar
- 文件名不写死：构建脚本会取 `libs/` 下所有 jar
- 开发环境：`./gradlew runClient` / `./gradlew runServer`

## 配置

`config/netmusic_disc_studio-common.toml`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `allowNetEaseDirectPlayback` | `true` | 允许标为 VIP 的网易云曲目在现代化唱片机上播放。关闭后严格遵循 VIP 限制 |

## 仓库结构

```
src/main/java/com/netmusic/discstudio/
├── api/          网易云专辑与歌曲信息
├── bili/         面向 NetMusicCanPlayBili 的音源解析器扩展
├── client/
│   ├── album/    MP4 播放列表的专辑折叠视图
│   ├── cover/    专辑封面下载与光盘图像生成
│   ├── model/    光盘的特殊物品模型
│   ├── netease/  登录与 Cookie 会话
│   ├── qr/       自实现的二维码编码器（不引入第三方依赖）
│   └── screen/   各个界面
├── disc/         音乐碟数据结构、循环模式与随机
├── init/         注册表
├── item/         两个物品
├── mixin/        对上游模组的接入点
├── network/      自定义网络包
└── server/       服务端落盘逻辑

tools/make_disc_textures.py    物品贴图生成脚本
```

### 贴图是脚本生成的，别手改 PNG

`textures/item/network_cd.png`（半透明彩虹光盘）与
`textures/item/erasable_network_disc.png`（黑胶）都由
`tools/make_disc_textures.py` 生成（8 倍超采样 + BOX 降采样）。改配色 / 改密纹请改脚本再重跑：

```bash
python tools/make_disc_textures.py --preview
```

`--preview` 会在 `tools/preview/` 下生成 16px 槽位模拟图与对比图，用于肉眼验收。需要 Pillow。

## 许可

MIT，见 [LICENSE](LICENSE)。

上游 NetMusic 与 NetMusicCanPlayBili 各有自己的许可，本仓库**不包含**它们的代码 ——
`libs/` 下的第三方 jar 也不随仓库分发。
