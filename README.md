# SouthsideNextGen

基于 **Fabric 1.21.4** 的 Minecraft 客户端模组（Southside）。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/`。需要 JDK 21。

## 源码结构

```
src/main/java/dev/southside/
├── Southside.java          # 客户端入口
├── accessor/               # AccessWidener
├── anticheat/              # 对局内作弊行为检测（Blink/Scaffold/FastWeb 等 check）
├── command/                # 聊天命令系统
├── compat/                 # 兼容层
├── config/                 # 配置读写
├── discordipc/             # Discord Rich Presence
├── event/                  # 事件总线与事件定义
├── gui/                    # GUI（ClickGUI / HUD）
├── manager/                # 管理器（模块/配置/服务器包调度）
├── mixin/                  # Mixin 注入
├── module/                 # 功能模块
│   └── impl/
│       ├── combat/         # 战斗：Velocity、KillAura、Criticals、BackTrack、TickBase…
│       ├── movement/       # 移动
│       ├── player/         # 玩家：Scaffold、InvManager…
│       ├── misc/           # 杂项：FakeLag、AntiBot、Disabler…
│       ├── render/         # 渲染
│       ├── world/          # 世界
│       └── client/         # 客户端自身管理
├── nanovg/                 # NanoVG 渲染绑定
├── render/                 # 渲染管线
├── sound/                  # 音效
└── utils/                  # 工具类

src/main/resources/
├── fabric.mod.json         # 模组元数据
├── southside.mixins.json   # Mixin 配置
└── assets/                 # 资源文件
```

## 许可

仅供学习交流使用。
