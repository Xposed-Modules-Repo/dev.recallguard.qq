# QQ Recall Guard

面向 QQ `9.2.60`（versionCode `13010`，`arm64-v8a`）的 LSPosed 防撤回模块，基于 libxposed Modern API 102。

## 功能

- QQ 正在运行时，保留好友与群聊中被撤回的原消息，并插入“尝试撤回”提示。
- 在 ColorOS/HeyTap 推送服务已经收到通知、但 QQ 随后冷启动同步到撤回记录的情况下，尝试从本地推送快照恢复通知中已有的文本正文。
- 为恢复的文本气泡保留原发送者身份，使 QQ 自身继续解析头像、好友备注或群成员名称。
- 仅恢复经过真实撤回记录匹配的文本；不会下载或重建图片、视频、语音等媒体。
- 解析、IPC 或存储失败时保持 fail-open，不阻断 QQ 启动和系统通知。

## 兼容性与作用域

| 项目 | 要求 |
| --- | --- |
| 目标应用 | QQ 9.2.60（13010） |
| CPU 架构 | arm64-v8a |
| Android | 8.0（API 26）及以上 |
| 框架 | 支持 libxposed Modern API 102 的 LSPosed |
| 模块版本 | 1.3.0（versionCode 22） |

LSPosed 作用域需要同时选择：

- `com.tencent.mobileqq`
- `com.heytap.mcs`（仅用于 ColorOS/OPush 冷启动恢复能力）

QQ 的 Java 类、native 签名和内部消息接口均严格适配 9.2.60。其他版本不会被视为受支持版本。

## 隐私与数据

模块不联网，不包含更新器、常驻服务、长期轮询、WakeLock、通知监听或外部共享存储。ColorOS 冷启动恢复功能会把通知中已经存在的文本及必要会话元数据写入模块私有 SQLite：最多保留 200 条候选记录，有效期 7 天。卸载模块会删除该私有缓存。

## 安装

1. 从 GitHub Releases 下载并安装 APK。
2. 在 LSPosed 中启用模块，并勾选 QQ 与 `com.heytap.mcs`。
3. 重启这两个目标进程；首次安装时也可以直接重启设备。

停用模块时，从 LSPosed 移除作用域并重启目标进程，或直接卸载 `dev.recallguard.qq`。

## 构建

需要 JDK 17、Android SDK Platform 37、NDK `29.0.13599879` 和 CMake `3.31.0`。

运行自检：

```powershell
.\tools\run-parser-self-test.ps1
python .\tools\test-push-cache-policy.py
```

生成未签名 release APK：

```powershell
.\gradlew.bat :app:assembleRelease
```

生成本地签名的测试 APK 时，先通过环境变量提供本地 keystore 密码：

```powershell
Set-Item Env:QQ_RECALL_GUARD_KEYSTORE_PASSWORD (Read-Host "Keystore password")
.\tools\build-local-release.ps1
```

脚本会在已忽略的 `signing-private/` 目录中创建或复用本地 keystore，并把产物写入已忽略的 `dist/`。不要提交密码、keystore 或签名后的本地产物。

## 相关项目

- [WeChat Anti-Recall](https://github.com/yylsping/wechat-anti-recall)：面向微信 8.0.69 的独立防撤回模块，保留他人撤回的原消息并提供原生定位入口。

两个项目分别适配 QQ 与微信，没有运行时或构建依赖。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 免责声明

本项目仅供学习、研究和个人设备使用，与腾讯、QQ、HeyTap、ColorOS 或 LSPosed 项目无隶属或认可关系。使用前请确认符合当地法律及相关服务条款。
