# libs/

**这个目录只放 NetMusicCanPlayBili 的 jar**，不要放 NetMusic。

上游不用编译，直接下官方 Release：

https://github.com/zhongbai2333/NetMusicCanPlayBili/releases

取 `net_music_can_play_bili-<版本>+mc26.1.2-neoforge.jar` 丢进这里即可。

- 它是**编译期依赖**（本模组代码 import 了 `ModernTurntableBlockEntity`、
  `BiliAudioResolver`、`BlackGoldScreen` 等上游类），不是要你去编译上游。
- 建议用**和游戏里装的一致**的版本，避免编译期与运行期 API 不一致。
- NetMusic 已通过 Modrinth Maven 自动下载，它的 jar 放进来会导致 classpath 上出现两份。
- 构建脚本会取本目录下所有 jar，不写死文件名；也可用
  `./gradlew build -PncpbJar=<jar 的绝对路径>` 临时指定。
