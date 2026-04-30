# 拾音Android — 三大Bug修复计划

## 问题分析

### 问题1：应用内点击暂停，歌曲还是会播放，动画按钮却暂停了

**根因分析**：经过深入代码追踪，发现以下关键问题：

1. **`handlePlayPause` 中原生模式只暂停了 NativeAudioPlayer，没有暂停 HTML5 `player`**  
   - [拾音.html:9186](file:///c:/Users/wesha/Desktop/ShiYinAndroid/app/src/main/assets/拾音.html#L9186) 的 `handlePlayPause` 在原生模式下只调用 `nativePause()`，不调用 `player.pause()`
   - 如果 HTML5 `player` 也在播放（例如从搜索结果播放时 `player.src` 未清空），则只暂停原生播放器，HTML5 播放器继续出声

2. **`sendPlayState` 竞态条件**  
   - [android-bridge.js:277-283](file:///c:/Users/wesha/Desktop/ShiYinAndroid/app/src/main/assets/android-bridge.js#L277-L283) 的 `handlePlayPause` 包装函数在 100ms 后发送 `sendPlayState`
   - 但 `onNativePlayStateChanged(false)` 回调通过 `runOnUiThread` + `evaluateJavascript` 异步执行
   - 如果 100ms 内回调未处理完，`isPlaying` 仍为 `true`，会发送 `sendPlayState({isPlaying: true})`
   - 这导致 `WebAppInterface.onPlayStateChanged({isPlaying: true})` → `mediaSessionManager.setPlayState(true)` → 通知栏状态翻转

3. **`setupSongChangeListener` 中 HTML5 player 事件在原生模式下仍会触发**  
   - [android-bridge.js:384-406](file:///c:/Users/wesha/Desktop/ShiYinAndroid/app/src/main/assets/android-bridge.js#L384-L406) 的 `player.play/pause` 事件监听器不检查原生模式
   - 如果 HTML5 player 意外在播放，其事件会发送冲突的 `sendPlayState`

4. **`playWithCachedData` 未清空 `player.src`**  
   - [拾音.html:9414](file:///c:/Users/wesha/Desktop/ShiYinAndroid/app/src/main/assets/拾音.html#L9414) 的 `playWithCachedData` 在原生模式下没有设置 `player.src = ''`
   - 导致 HTML5 player 可能保留上一首歌的源并继续播放

### 问题2：播放搜索出来的歌曲会很卡动画

**根因分析**：

1. **双播放器同时运行** — 搜索歌曲播放时，HTML5 player 和 NativeAudioPlayer 可能同时播放，导致 WebView 负载翻倍
2. **进度更新过于频繁** — `onNativeProgressChanged` 每500ms回调 + `updateProgress` 每100ms执行 + `sendProgress` 每300ms发送，叠加造成WebView线程阻塞
3. **切歌时重复发送状态** — `playPreviousSong`/`playNextSong` 包装函数在500ms后发送 `sendSongInfo` + `sendPlayState`，同时 `player` 事件也会触发，单次切歌可能触发4-6次跨线程通信

### 问题3：通知栏默认图标不是应用图标

**根因分析**：

1. **`getDefaultAlbumArt()` 使用 `R.drawable.kou_kou_yun`** — [MediaSessionManager.kt:307](file:///c:/Users/wesha/Desktop/ShiYinAndroid/app/src/main/java/com/shiyin/music/MediaSessionManager.kt#L307) 使用 `kou_kou_yun.png` 作为默认封面，而非应用启动图标
2. **`res/mipmap-*/ic_launcher.png` 已存在** — 应用启动图标已在标准位置，但未被通知栏使用

---

## 修复方案

### 修复1：解决暂停Bug — 消除双播放器和竞态条件

#### 1a. 修改 `android-bridge.js` — 将 `sendPlayState` 移到 `onNativePlayStateChanged` 回调中

**文件**：`app/src/main/assets/android-bridge.js`

**修改 `handlePlayPause` 包装函数**：移除 `setTimeout` 中的 `sendPlayState`，因为 `onNativePlayStateChanged` 回调会负责发送状态

```javascript
window.handlePlayPause = function() {
    if (originalHandlePlayPause) {
        originalHandlePlayPause();
    }
};
```

**修改 `setupSongChangeListener`**：在原生模式下不监听 HTML5 player 的 play/pause 事件

```javascript
player.addEventListener('play', () => {
    if (!(typeof USE_NATIVE_PLAYER !== 'undefined' && USE_NATIVE_PLAYER && typeof nativePlayerInitialized !== 'undefined' && nativePlayerInitialized)) {
        sendPlayState({
            isPlaying: true,
            position: currentProgress || 0,
            duration: totalDuration || 0
        });
    }
});

player.addEventListener('pause', () => {
    if (!(typeof USE_NATIVE_PLAYER !== 'undefined' && USE_NATIVE_PLAYER && typeof nativePlayerInitialized !== 'undefined' && nativePlayerInitialized)) {
        sendPlayState({
            isPlaying: false,
            position: currentProgress || 0,
            duration: totalDuration || 0
        });
    }
});
```

#### 1b. 修改 `拾音.html` — 在原生模式下也暂停 HTML5 player

**文件**：`app/src/main/assets/拾音.html`

**修改 `handlePlayPause` 函数**（约第9186行）：在原生模式暂停分支中，也暂停 HTML5 player

```javascript
// 在 nativePause() 之后添加：
nativePause();
if (!player.paused) {
    player.pause();
}
```

**修改 `playWithCachedData` 函数**（约第9457行）：在原生模式下清空 `player.src`

```javascript
// 在 nativeLoadAndPlay 调用之前添加：
player.pause();
player.src = '';
```

### 修复2：解决搜索歌曲播放卡顿

#### 2a. 降低进度更新频率

**文件**：`app/src/main/java/com/shiyin/music/NativeAudioPlayer.kt`

将 `startProgressUpdate` 的间隔从 500ms 改为 1000ms

#### 2b. 减少 `sendProgress` 的发送频率

**文件**：`app/src/main/assets/android-bridge.js`

将 `updateProgress` 包装函数的节流间隔从 300ms 改为 1000ms

#### 2c. 减少切歌时的重复状态发送

**文件**：`app/src/main/assets/android-bridge.js`

在 `playPreviousSong` 和 `playNextSong` 包装函数中，移除 `setTimeout` 中的 `sendSongInfo` + `sendPlayState`，因为 `loadSong` 包装函数和 `player` 事件已经会发送这些信息

### 修复3：通知栏默认图标改为应用图标

**文件**：`app/src/main/java/com/shiyin/music/MediaSessionManager.kt`

将 `getDefaultAlbumArt()` 中的 `R.drawable.kou_kou_yun` 改为 `R.mipmap.ic_launcher`

```kotlin
private fun getDefaultAlbumArt(): Bitmap? {
    return try {
        android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    } catch (e: Exception) {
        null
    }
}
```

---

## 修改文件清单

| 文件 | 修改内容 |
|------|---------|
| `app/src/main/assets/android-bridge.js` | 移除 handlePlayPause 中的 sendPlayState；原生模式下屏蔽 player 事件；降低 sendProgress 频率；减少切歌重复发送 |
| `app/src/main/assets/拾音.html` | 原生模式暂停时也暂停 HTML5 player；playWithCachedData 中清空 player.src |
| `app/src/main/java/com/shiyin/music/MediaSessionManager.kt` | getDefaultAlbumArt 改用 R.mipmap.ic_launcher |
| `app/src/main/java/com/shiyin/music/NativeAudioPlayer.kt` | 进度更新间隔从 500ms 改为 1000ms |

---

## 验证清单

- [ ] 点击应用内暂停按钮 → 音乐暂停 + 动画暂停
- [ ] 点击应用内播放按钮 → 音乐恢复 + 动画恢复
- [ ] 点击通知栏暂停 → 音乐暂停
- [ ] 点击通知栏播放 → 音乐恢复
- [ ] 搜索歌曲播放 → 动画流畅不卡顿
- [ ] 切歌 → 只有一首歌在播放
- [ ] 通知栏默认图标为应用启动图标
- [ ] 歌曲封面加载后替换为歌曲图片
- [ ] 通知栏只显示一张卡片
