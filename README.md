# 拾音 Android 项目

基于 WebView + MediaSession 的音乐播放器 Android 应用

## 更新日志

### v1.1.0 (2026-03-20)
- ✅ 修复进度条同步问题：歌曲完成后系统进度条正确重置
- ✅ 实现版权处理机制：自动检测、标记和跳过无版权歌曲
- ✅ 优化歌曲管理功能：自定义删除确认弹窗，禁用按钮防误操作
- ✅ 修复页面刷新问题：导入歌曲时点击外部链接返回，状态保持稳定
- ✅ 删除初始加载提示：移除本地文件环境的提示信息
- ✅ 优化触摸交互：添加完整触摸支持，提升操作流畅度

## 项目结构

```
ShiYinAndroid/
├── app/
│   ├── src/main/
│   │   ├── java/com/shiyin/music/
│   │   │   ├── MainActivity.kt          # 主Activity，WebView容器
│   │   │   ├── MediaSessionManager.kt   # MediaSession管理
│   │   │   ├── WebAppInterface.kt       # WebView Bridge接口
│   │   │   ├── MediaButtonReceiver.kt   # 媒体按钮接收器
│   │   │   └── MediaPlaybackService.kt  # 后台播放服务
│   │   ├── res/
│   │   │   ├── drawable/                # 图标资源
│   │   │   ├── values/                  # 颜色、字符串、主题
│   │   │   └── xml/                     # 配置文件
│   │   ├── assets/
│   │   │   ├── 拾音.html                 # 主页面
│   │   │   └── android-bridge.js        # Bridge通信脚本
│   │   └── AndroidManifest.xml          # 应用配置
│   ├── build.gradle.kts                 # 模块构建配置
│   └── proguard-rules.pro               # ProGuard规则
├── build.gradle.kts                     # 项目构建配置
├── settings.gradle.kts                  # 项目设置
└── gradle.properties                    # Gradle属性
```

## 核心功能

### 1. WebView 配置
- 硬件加速渲染
- JavaScript 支持
- 媒体播放无需用户手势
- 混合内容允许（HTTP/HTTPS）

### 2. MediaSession 集成
- 锁屏媒体控制
- 通知栏播放器
- 耳机/蓝牙控制支持
- 播放状态同步
- 进度条实时同步（修复歌曲完成后进度条停止更新问题）

### 3. Bridge 通信
- H5 → Android: 播放状态、歌曲信息、进度
- Android → H5: 播放、暂停、切歌、进度拖动

### 4. 版权处理机制
- 自动检测无版权歌曲（API返回特定错误信息）
- 无版权歌曲标记显示（红色文字 + "无版权"标签）
- 自动跳过无版权歌曲，无需手动重试
- 无版权歌曲列表持久化存储

### 5. 歌曲管理优化
- 自定义删除确认弹窗（美观的毛玻璃效果）
- 删除操作时禁用所有控制按钮，防止误操作
- 支持触摸和鼠标交互

### 6. 页面状态管理
- 页面状态自动保存（播放进度、歌曲信息、播放模式等）
- 页面刷新后自动恢复状态（30分钟内有效）
- 导入歌曲时点击外部链接返回，状态保持稳定

### 7. 触摸交互优化
- 完整的触摸事件支持（触摸开始、结束、取消）
- 触摸反馈效果（缩放、透明度变化）
- 同时支持移动端触摸和桌面端鼠标操作
- 响应灵敏，操作流畅

## 构建说明

### 环境要求

- Android Studio 2023.1.1 或更高版本
- JDK 17
- Android SDK 34

### 构建步骤

1. 打开 Android Studio
2. 选择 `File` → `Open`，选择 `ShiYinAndroid` 目录
3. 等待 Gradle 同步完成
4. 点击 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`

### 运行

1. 连接 Android 设备或启动模拟器
2. 点击 `Run` → `Run 'app'`

## 权限说明

| 权限                  | 用途                  |
| ------------------- | ------------------- |
| INTERNET            | 网络访问，加载音乐和封面        |
| POST\_NOTIFICATIONS | 通知栏播放器（Android 13+） |
| FOREGROUND\_SERVICE | 前台服务，后台播放           |
| WAKE\_LOCK          | 防止播放时休眠             |
| BLUETOOTH           | 蓝牙耳机控制              |

## API 兼容性

- **最低版本**: Android 7.0 (API 24)
- **目标版本**: Android 14 (API 34)
- **MediaSession**: 使用 `androidx.media:media:1.7.0` 兼容库

## 注意事项

1. **网络配置**: 已配置允许明文流量，支持 HTTP 和 HTTPS
2. **硬件加速**: 已在 Application 和 Activity 级别开启
3. **后台播放**: 切换应用到后台时，音乐继续播放
4. **锁屏控制**: 支持锁屏界面显示播放控制

## 自定义配置

### 修改主题色

编辑 `res/values/colors.xml`:

```xml
<color name="shiyin_primary">#FF5E9EED</color>
<color name="shiyin_accent">#FFE1826E</color>
```

### 修改应用名称

编辑 `res/values/strings.xml`:

```xml
<string name="app_name">你的应用名</string>
```

## 依赖库

- AndroidX Core: 1.12.0
- AndroidX Media: 1.7.0
- Material Design: 1.11.0
- Glide: 4.16.0 (图片加载)
- Kotlin Coroutines: 1.7.3

## 许可证

六神专属认证😉
