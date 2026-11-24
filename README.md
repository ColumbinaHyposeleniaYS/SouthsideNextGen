# SouthsideNextGen

基于 **Fabric 1.21.4** 的 Minecraft 客户端模组（Southside）。

## 构建

```bash
./gradlew build
```

产物位于 `build/libs/`。需要 JDK 21。

## 主要模块

| 模块 | 说明 |
|---|---|
| **Velocity** | 减免击退。两种模式：`Desync`（服务器侧包延迟 + 冲刺攻击减 KB）、`Reduce`（纯延迟放包式，扣包 → 条件满足 → flush） |
| **KillAura** | 自动攻击目标 |
| **Criticals** | 暴击（Switch / Stuck / Packet / StopSprint 四种模式） |
| **Scaffold** | 自动搭路 |
| **FakeLag** | 延迟发包 |
| **AntiBot** | 假人检测 |

## CI / Releases

- `push` / `pull_request` → 自动构建，artifact 上传 jar
- 推送 `v*` 标签 → 自动发布 GitHub Release

发布新版本：

```bash
# 1. 改 build.gradle 里的 version
# 2. 打 tag 并推送
git tag v2.0.4
git push origin v2.0.4
```

## 许可

仅供学习交流使用。
