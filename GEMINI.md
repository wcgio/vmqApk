# V免签 Android 监控端 (2026 优化版) - 项目指南

## 📋 项目概览
V免签是一个开源的个人收款监控解决方案。该 Android 监控端通过系统级 `NotificationListenerService` 监听微信和支付宝的收款通知，自动提取金额并回调至用户自建的服务端，实现免签约的个人支付回调系统。

### 核心技术栈
- **Android SDK:** 35 (Target), 24 (Min)
- **语言:** Java 17
- **网络:** OkHttp 4.12.0
- **二维码:** ZXing 3.5.3
- **保活机制:** Foreground Service (API 34+), WakeLock, Heartbeat, BootReceiver

## 🏗️ 核心架构与原理

### 1. 通知监听机制 (`NeNotificationService2`)
- **系统授权:** 需用户手动授予“通知使用权”。
- **识别规则:** 
  - **微信:** 匹配包名 `com.tencent.mm` 及标题关键词（微信支付、微信收款商业版等）。
  - **支付宝:** 匹配包名 `com.eg.android.AlipayGphone` 及内容关键词（扫码向你付款、成功收款等）。
- **金额提取:** 采用四级正则匹配算法，精确识别如 `¥100.50`、`100.50元` 等格式。
- **去重机制:** 5秒窗口内重复通知自动过滤，防止重复回调。

### 2. 保活与稳定性 (`NeNotificationService2`)
- **前台服务:** 持久通知栏显示，声明 `specialUse` 类型（适配 Android 14/15）。
- **电源锁:** 持有 `PARTIAL_WAKE_LOCK` 防止手机休眠导致 CPU 停工。
- **自动重连:** 监听服务断开时自动调用 `requestRebind()`。
- **心跳机制:** 每 30 秒与服务端通信，维持进程活跃并同步状态。

## 🛠️ 构建与运行

### 环境要求
- JDK 17+
- Android Studio Ladybug (2024.2) 或更高
- Android SDK 35

### 编译指令
```bash
# 构建 Debug 版 APK
./gradlew assembleDebug

# 清理项目
./gradlew clean
```
产物路径: `app/build/outputs/apk/debug/app-debug.apk`

## 📖 开发约定

### 1. 金额识别优化
若发现新版微信/支付宝通知无法识别，需在 `NeNotificationService2.java` 的 `MONEY_PATTERNS` 中添加对应正则表达式。

### 2. 回调安全
所有回调请求（`appPush`）和心跳（`appHeart`）均带有 MD5 签名：
- **算法:** `md5(type + price + timestamp + key)`
- **Key:** 用户在服务端配置的通讯密钥。

### 3. 日志调试
使用 adb 查看详细的通知捕获及推送过程：
```bash
adb logcat -s VmqNotification:D
```

## ⚠️ 部署注意事项
1. **电池优化:** 必须在手机设置中将“V免签”设为“不受限制”，否则会被系统后台杀死。
2. **通知开关:** 必须确保微信/支付宝的系统通知、金额详情显示已开启。
3. **商业版支持:** 已针对微信收款商业版进行关键词适配。

## 📁 关键目录结构
- `/app/src/main/java/com/vone/vmq/`
  - `NeNotificationService2.java`: 核心逻辑（监听、保活、推送）
  - `MainActivity.java`: UI 逻辑（扫码配置、测试、权限引导、实时日志控制台）
  - `BootReceiver.java`: 开机自启动广播接收器
- `/app/src/main/res/layout/activity_main.xml`: 界面布局，新增实时运行日志展示区域
- `/app/src/main/AndroidManifest.xml`: 声明通知监听服务及 Android 14+ 所需权限
