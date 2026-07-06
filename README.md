# LiteSmsRelay

将收到的短信自动转发到指定号码。仅此而已。

> **合法使用提醒**
>
> LiteSmsRelay 是一个纯本地、离线运行的短信转发工具，只适用于你本人拥有或被明确授权管理的设备和号码。项目不包含联网、远程控制、云同步或数据上传能力，也不得被用于监控他人设备、窃取验证码、绕过账户安全机制、骚扰、诈骗或任何其他违法用途。

## 为什么需要这个项目

你有一台备用机，想把它收到的验证码、快递通知自动转发到主力机。你需要的是一个**只做这一件事**的 App。

市面上已有的短信转发应用：

- 功能多 → 权限多 → 隐私风险大
- 架构复杂 → 代码难读 → 想改不知道从哪下手
- 大部分用 Java，targetSdk 停留在 Android 13 以下

**LiteSmsRelay 的设计原则：能不做就不做。**

| 维度 | LiteSmsRelay | 典型竞品 |
|------|-------------|----------|
| 源文件 | 6 个 | 100+ |
| 第三方依赖 | 0 | 20+ |
| APK 大小 | 2.3 MB | 15+ MB |
| 权限数 | 8 | 15+ |
| 读懂全部代码 | 10 分钟 | 半天 |
| 编译依赖 | Android Framework API | Retrofit / OkHttp / Gson / Coroutine… |

## 功能

- 收到短信 → 自动转发到指定号码
- 可选：仅转发包含关键词的短信（如"验证码"）
- 可选：在转发内容中包含原始发送者号码
- 开机自启，后台常驻
- 对抗国产 ROM（MIUI / EMUI / ColorOS）的激进省电策略

**没有的功能（故意的）：**
- ~~转发到 Webhook / 钉钉 / 企业微信 / Telegram~~
- ~~多目标转发~~
- ~~通知监听转发~~
- ~~远程管理界面~~
- ~~云同步~~

如果你需要这些功能，推荐 [pppscn/SmsForwarder](https://github.com/pppscn/SmsForwarder)（26.9k★）。

## 下载

在 [Releases](../../releases) 页面下载最新 APK。

如果你从源码自行构建，请只安装自己信任的构建产物。项目默认没有签名配置，发布 release APK 时应使用你自己的签名密钥，且不要把签名文件或密钥密码提交到仓库。

## 架构

```
                短信到达
                   │
                   ▼
         ┌─────────────────┐
         │   SmsReceiver    │  静态注册 BroadcastReceiver
         │   (priority=999) │  SMS_RECEIVED 是豁免广播，
         └────────┬────────┘  即使 Doze / 进程被杀也能收到
                  │
                  ▼
         ┌─────────────────┐
         │  goAsync() +     │  不阻塞广播队列（10秒限制）
         │  后台线程转发     │  10秒 WakeLock 保证 CPU
         └────────┬────────┘
                  │
                  ▼
         ┌─────────────────┐
         │   SmsManager     │  发送短信到目标号码
         │   .sendText…()   │  支持长短信自动分段
         └─────────────────┘

常驻保活：
  ┌──────────────────────┐
  │  SmsForwardService    │  前台服务 (remoteMessaging 类型)
  │  IMPORTANCE_LOW 通知  │  START_STICKY，被杀后系统重启
  └──────────────────────┘
          ▲
          │ 开机触发
  ┌───────┴──────────────┐
  │    BootReceiver       │  BOOT_COMPLETED / MY_PACKAGE_REPLACED
  └──────────────────────┘
```

### 源文件一览

```
app/src/main/java/com/smsforward/
├── App.kt                # Application 入口，仅日志初始化
├── AppConfig.kt          # SharedPreferences 配置读写
├── SmsReceiver.kt        # 接收短信 + 转发（核心逻辑）
├── SmsForwardService.kt  # 前台保活服务
├── BootReceiver.kt       # 开机自启
└── MainActivity.kt       # 配置界面（目标号码、开关、关键词）
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `RECEIVE_SMS` | 接收短信 |
| `READ_SMS` | 读取短信内容（用于转发） |
| `SEND_SMS` | 发送转发短信 |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `FOREGROUND_SERVICE_REMOTE_MESSAGING` | Android 14+ 前台服务类型声明 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `POST_NOTIFICATIONS` | Android 13+ 显示保活通知 |
| `WAKE_LOCK` | 转发时短暂唤醒 CPU（10秒超时） |

**没有的权限（故意的）：**
- ~~`READ_PHONE_STATE`~~ — 不读取 SIM 卡信息
- ~~`ACCESS_FINE_LOCATION`~~ — 不获取位置
- ~~`INTERNET`~~ — 不联网，零网络权限
- ~~`READ_CONTACTS`~~ — 不读取通讯录

所有数据仅在本地处理，不经过任何网络。

## 使用方法

1. 安装 APK
2. 打开 App，输入目标手机号
3. 授予短信权限
4. 点击「请求电池优化豁免」（推荐，防止国产 ROM 杀后台）
5. 完成

## 编译

```bash
# 需要 JDK 17 和 Android SDK (compileSdk 35)
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android-sdk"

./gradlew assembleDebug      # Debug APK
./gradlew assembleRelease    # Release APK（需配置签名）
```

## GitHub 发布前检查

- `local.properties`、`.gradle/`、`app/build/`、签名密钥等本地文件不要提交。
- APK 构建产物不要提交到源码仓库；需要公开下载时，通过 GitHub Releases 发布。
- README、LICENSE、权限说明和免责声明应随源码一起提交。
- 发布前至少运行一次 `./gradlew assembleDebug` 或 `./gradlew assembleRelease` 验证构建。

## 系统要求

- Android 10 (API 29) 及以上
- 已测试：Android 10–15

## 技术栈

- Kotlin 2.0
- Android Gradle Plugin 8.7
- 零第三方依赖（纯 Android Framework API）
- Gradle 8.11

## 许可证

本项目采用 [MIT License](LICENSE)。

选择 MIT 的原因：项目代码量小、无第三方依赖，目标是方便个人审计、修改、二次构建和复用。MIT 也要求保留版权和许可声明。需要注意的是，开源许可证本身不能替代法律约束；任何使用者仍必须遵守所在地法律法规和通信服务条款。

## 免责声明

本项目仅供个人在自有设备或已获得明确授权的设备上使用。

- 仅转发设备本机收到的短信
- 不拦截、不删除原始短信
- 不上传任何数据到网络
- 禁止用于监控他人设备、窃取验证码、绕过账户安全机制、骚扰、诈骗或任何其他违法行为

使用者需遵守当地法律法规，作者不对任何滥用行为承担责任。

## 致谢

以下开源项目的架构思路对本项目有启发：

- [bogkonstantin/android_income_sms_gateway_webhook](https://github.com/bogkonstantin/android_income_sms_gateway_webhook) — 三层保活架构
- [warren-bank/Android-SMS-Automatic-Forwarding](https://github.com/warren-bank/Android-SMS-Automatic-Forwarding) — SMS-to-SMS 转发概念
- [pppscn/SmsForwarder](https://github.com/pppscn/SmsForwarder) — 功能边界参考
