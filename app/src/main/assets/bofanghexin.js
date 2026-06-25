/**
 * 播放器核心模块
 * 包含播放控制、进度管理、歌词显示、媒体会话同步等功能
 */

// 媒体会话同步相关变量
var supportsMediaSessionPositionState = 'mediaSession' in navigator && 'setPositionState' in navigator.mediaSession;
var mediaSessionSyncInterval = null;
var syncErrorCount = 0;
const MAX_SYNC_ERRORS = 3;
var lastSyncedTime = 0;
const SYNC_INTERVAL = 100;
var lastKnownTime = 0;

// 歌词相关变量
var lyricsEnabled = false;
var lyrics = [];
var currentLyricIndex = -1;
var loadedLyricSongId = null;
var lyricRetryCount = 0;
var lyricTimer = null;

// 加载相关变量
var loadingStartProgress = 0;
var loadingStartTime = 0;

// 播放模式枚举
const PlayMode = {
    SEQUENTIAL: 0,
    SINGLE_LOOP: 1,
    RANDOM: 2
};

// 获取当前播放歌曲ID（根据播放模式）
function getCurrentSongId() {
    // 优先使用 currentPlayingSongId（可能来自搜索播放）
    if (currentPlayingSongId) {
        return currentPlayingSongId;
    }

    // 关键修复：边界检查，防止数组越界
    if (playMode === PlayMode.RANDOM) {
        if (randomQueue.length === 0) return null;
        if (randomIndex < 0 || randomIndex >= randomQueue.length) {
            randomIndex = 0;
        }
        return randomQueue[randomIndex];
    } else {
        if (songIds.length === 0) return null;
        if (currentIndex < 0 || currentIndex >= songIds.length) {
            currentIndex = 0;
        }
        return songIds[currentIndex];
    }
}

// 强制同步媒体会话进度
function forceSyncMediaSession(position = null) {
    // 关键修改：移除 isLoading 限制，确保拖动时也能同步
    if (!('mediaSession' in navigator)) return;

    try {
        let safeCurrentTime;
        if (position !== null) {
            // 如果提供了位置参数（如拖动进度条时），使用提供的值
            safeCurrentTime = position;
        } else {
            // 原生播放器模式下使用 currentProgress（毫秒转秒）
            safeCurrentTime = currentProgress / 1000;
        }

        const safeDuration = totalDuration / 1000;
        const safePlaybackRate = Math.max(0.1, player.playbackRate || 1);

        // 确保数值有效
        if (isNaN(safeCurrentTime) || isNaN(safeDuration) || safeDuration <= 0) return;

        // 确保position不大于duration
        const clampedPosition = Math.min(safeCurrentTime, safeDuration);

        // 1. 调用 Web MediaSession API
        navigator.mediaSession.setPositionState({
            duration: safeDuration,
            position: clampedPosition,
            playbackRate: safePlaybackRate
        });

        navigator.mediaSession.playbackState = isPlaying ? "playing" : "paused";
        lastSyncedTime = Date.now();
        syncErrorCount = 0;
        if (typeof syncIndicator !== 'undefined' && syncIndicator) {
            syncIndicator.className = "sync-indicator";
        }

    } catch (error) {
        console.error("媒体会话同步失败:", error);
        syncErrorCount++;
        if (typeof syncIndicator !== 'undefined' && syncIndicator) {
            syncIndicator.className = syncErrorCount >= MAX_SYNC_ERRORS
                ? "sync-indicator error"
                : "sync-indicator syncing";
        }
    }
}

// 重置媒体会话进度
function resetMediaSession() {
    if (!('mediaSession' in navigator)) return;

    try {
        // 完全重置 MediaSession 状态
        navigator.mediaSession.playbackState = "none";

        // 清除元数据
        navigator.mediaSession.metadata = null;

        // 清除位置状态
        if (supportsMediaSessionPositionState) {
            navigator.mediaSession.setPositionState({
                duration: 0,
                position: 0,
                playbackRate: 1
            });
        }

        console.log("MediaSession 已完全重置");
    } catch (error) {
        console.error("重置 MediaSession 失败:", error);
    }
}

// 更新媒体会话的位置状态
function updateMediaSessionPosition() {
    if (!('mediaSession' in navigator) || !isPlaying || isLoading) return;

    const now = Date.now();
    const timeSinceLastSync = now - lastSyncedTime;

    if (timeSinceLastSync > SYNC_INTERVAL) {
        // 关键修复：原生播放器模式下使用 currentProgress
        let currentTime = currentProgress / 1000;

        if (!isNaN(currentTime) && totalDuration > 0) {
            navigator.mediaSession.setPositionState({
                duration: totalDuration / 1000,
                position: currentTime,
                playbackRate: player.playbackRate || 1
            });
            lastSyncedTime = now;
        }
    }
}

// 启动定期同步定时器
function startSyncInterval() {
    if (mediaSessionSyncInterval) clearInterval(mediaSessionSyncInterval);

    // 关键修改：将同步频率从2000ms提高到100ms
    mediaSessionSyncInterval = setInterval(() => {
        if (isPlaying && totalDuration > 0 && !isLoading && isOnline) {
            const now = Date.now();

            // 原生播放器模式下，currentProgress由onNativeProgressChanged回调更新
            // 这里只负责同步系统进度条
            forceSyncMediaSession();
        } else if (isLoading && 'mediaSession' in navigator) {
            navigator.mediaSession.setPositionState({
                duration: totalDuration / 1000,
                position: loadingStartProgress / 1000,
                playbackRate: player.playbackRate || 1
            });
            navigator.mediaSession.playbackState = "paused";
        }
    }, SYNC_INTERVAL);
}

// 停止定期同步定时器
function stopSyncInterval() {
    if (mediaSessionSyncInterval) {
        clearInterval(mediaSessionSyncInterval);
        mediaSessionSyncInterval = null;
    }
}

// 更新进度条和时间显示 - 重构后只使用 currentProgress，不读取 player.currentTime
function updateProgress() {
    if (totalDuration > 0) {
        const currentTime = currentProgress / 1000;
        const percent = Math.min(100, (currentTime / (totalDuration / 1000)) * 100);
        if (typeof progressBar !== 'undefined' && progressBar) {
            progressBar.style.width = `${percent}%`;
        }
        if (typeof currentTimeDisplay !== 'undefined' && currentTimeDisplay) {
            currentTimeDisplay.textContent = formatTime(currentTime);
        }
    }
}

// 切换歌词显示状态
function toggleLyrics() {
    lyricsEnabled = !lyricsEnabled;

    if (lyricsEnabled) {
        lyricsToggle.classList.add('active');
        lyricsContainer.classList.add('show');

        // 关键修复：始终加载当前播放歌曲的歌词
        loadLyricsForCurrentSong();
    } else {
        lastKnownTime = currentProgress;
        lyricsToggle.classList.remove('active');
        lyricsContainer.classList.remove('show');

        // 关闭歌词时清空通知栏歌词，恢复显示歌手名
        if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.updateGeCiHang === 'function') {
            try { AndroidBridge.updateGeCiHang(""); } catch(e) {}
        }
    }
}

// 为当前播放歌曲加载歌词
function loadLyricsForCurrentSong() {
    if (!isInitialized) return;

    // 只有当歌词未加载或与当前播放歌曲不匹配时才加载
    if (currentPlayingSongId && (!loadedLyricSongId || loadedLyricSongId !== currentPlayingSongId)) {
        lyricRetryCount = 0;
        loadLyrics(currentPlayingSongId);
    }
}

// 更新歌词显示 - 使用毫秒级时间比较
function updateLyricsDisplay(forceUpdate = false) {
    if (!lyricsEnabled || lyrics.length === 0) return;

    // 原生播放器模式下使用 currentProgress
    let currentTime;
    if (forceUpdate) {
        currentTime = lastKnownTime;
    } else {
        currentTime = currentProgress;
    }
    let newIndex = -1;

    // 找到当前应该显示的歌词（毫秒级比较）
    for (let i = 0; i < lyrics.length; i++) {
        if (lyrics[i].time > currentTime) break;
        newIndex = i;
    }

    // 如果歌词索引没有变化且不是强制更新，直接返回
    if (!forceUpdate && newIndex === currentLyricIndex) return;

    currentLyricIndex = newIndex;

    // 获取上一句、当前句和下一句歌词
    const prevItem = currentLyricIndex > 0 ? lyrics[currentLyricIndex - 1] : { text: "", trans: "" };
    const currentItem = currentLyricIndex >= 0 ? lyrics[currentLyricIndex] : { text: "", trans: "" };
    const nextItem = currentLyricIndex < lyrics.length - 1 ? lyrics[currentLyricIndex + 1] : { text: "", trans: "" };

    // 推送当前歌词到通知栏
    if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.updateGeCiHang === 'function') {
        try { AndroidBridge.updateGeCiHang(currentItem.text || ""); } catch(e) {}
    }

    // 应用歌词动画
    applyLyricAnimation(
        prevItem.text, prevItem.trans,
        currentItem.text, currentItem.trans,
        nextItem.text, nextItem.trans
    );
}

// 应用歌词滚动动画
function applyLyricAnimation(prevText, prevTrans, currentText, currentTrans, nextText, nextTrans) {
    // 重置动画状态
    lyricLine1.style.transform = "translateY(30px)";
    lyricLine1.style.opacity = "0";
    lyricTrans1.style.opacity = "0";
    lyricTrans1.style.transform = "translateY(10px)";

    lyricLine2.style.transform = "translateY(0)";
    lyricLine2.style.opacity = "1";
    lyricTrans2.style.opacity = currentTrans ? "0.8" : "0";
    lyricTrans2.style.transform = "translateY(0)";

    lyricLine3.style.transform = "translateY(-30px)";
    lyricLine3.style.opacity = "0";
    lyricTrans3.style.opacity = "0";
    lyricTrans3.style.transform = "translateY(-10px)";

    // 触发重绘
    void lyricLine1.offsetWidth;

    // 设置歌词内容
    lyricLine1.textContent = prevText;
    lyricTrans1.textContent = prevTrans;

    lyricLine2.textContent = currentText;
    lyricTrans2.textContent = currentTrans;

    lyricLine3.textContent = nextText;
    lyricTrans3.textContent = nextTrans;

    // 应用动画
    lyricLine1.style.transform = "translateY(0)";
    lyricLine1.style.opacity = "0.6";
    if (prevTrans) {
        lyricTrans1.style.opacity = "0.6";
        lyricTrans1.style.transform = "translateY(0)";
    }

    lyricLine2.style.transform = "translateY(0)";
    lyricLine2.style.opacity = "1";
    if (currentTrans) {
        lyricTrans2.style.opacity = "0.8";
        lyricTrans2.style.transform = "translateY(0)";
    } else {
        lyricTrans2.style.opacity = "0";
    }

    lyricLine3.style.transform = "translateY(0)";
    lyricLine3.style.opacity = "0.6";
    if (nextTrans) {
        lyricTrans3.style.opacity = "0.6";
        lyricTrans3.style.transform = "translateY(0)";
    }
}

// 切换专辑封面（淡入淡出效果）
// 关键修复：使用 switchId 防止快速连续调用时的竞态条件
// 旧版用 clearTimeout(_timer) 方式会在两次调用交替执行时清错定时器
switchAlbumImage._switchId = 0;
// 前台恢复保护窗口：回前台时由唯一权威路径(_processNativeSongChange 处理快照)设置，
// 记录"目标封面"与"过期时间"。窗口内任何与目标不一致的封面切换请求都会被抑制，
// 防止多个 visibilitychange 监听器 / onResume 反向通知引起的连环封面闪烁。
switchAlbumImage._resumeLockCover = null;     // 锁定的目标封面 URL
switchAlbumImage._resumeLockUntil = 0;        // 锁定过期时间戳(ms)
// 锁定窗口时长：略大于一次淡入淡出动画(500ms)，确保动画完成前不会有第二次切换
switchAlbumImage.RESUME_LOCK_MS = 800;
// 主动设置锁定（供权威恢复路径调用）
switchAlbumImage.setResumeLock = function(targetCover) {
    switchAlbumImage._resumeLockCover = (typeof targetCover === 'string') ? targetCover : null;
    switchAlbumImage._resumeLockUntil = Date.now() + switchAlbumImage.RESUME_LOCK_MS;
};
// 清除锁定
switchAlbumImage.clearResumeLock = function() {
    switchAlbumImage._resumeLockCover = null;
    switchAlbumImage._resumeLockUntil = 0;
};
function switchAlbumImage(newSrc) {
    // 关键修复：同封面去重 —— 如果新封面与当前显示的封面完全相同，跳过动画
    // 防止 updateIndexAfterLoad、_processNativeSongChange、visibilitychange 等多路径
    // 在同一切歌事件中重复调用导致连环淡入淡出闪烁
    var currentSrc = activeImage ? activeImage.src : '';
    if (currentSrc && newSrc && currentSrc === newSrc && !isSwitching) {
        console.log('[封面] 同封面去重，跳过动画: ' + String(newSrc).substring(0, 50));
        return;
    }
    // 前台恢复保护：若处于锁定窗口且本次请求的封面与锁定目标不一致，跳过
    // （用户主动切歌不会经过此分支，因为主动路径会先 clearResumeLock）
    if (switchAlbumImage._resumeLockCover !== null
        && Date.now() < switchAlbumImage._resumeLockUntil
        && newSrc !== switchAlbumImage._resumeLockCover) {
        console.log('[封面] 恢复保护窗口内，抑制非目标封面切换: ' + String(newSrc).substring(0, 50));
        return;
    }
    // 递增切换ID，使之前调用的所有异步回调立即失效
    var switchId = ++switchAlbumImage._switchId;
    // 清除上一次的交换定时器（只清除自己的，不影响并发）
    if (switchAlbumImage._swapTimer) {
        clearTimeout(switchAlbumImage._swapTimer);
        switchAlbumImage._swapTimer = null;
    }
    isSwitching = true;

    if (window.updateDynamicBackground) window.updateDynamicBackground(newSrc);

    const tempImg = new Image();
    tempImg.src = newSrc;

    tempImg.onload = function () {
        // 过期回调检测：如果不是最新调用，丢弃
        if (switchId !== switchAlbumImage._switchId) return;
        inactiveImage.src = newSrc;
        inactiveImage.style.opacity = '1';
        activeImage.style.opacity = '0';

        switchAlbumImage._swapTimer = setTimeout(function() {
            if (switchId !== switchAlbumImage._switchId) return;
            const temp = activeImage;
            activeImage = inactiveImage;
            inactiveImage = temp;
            isSwitching = false;
            switchAlbumImage._swapTimer = null;
        }, 500);
    };

    tempImg.onerror = function () {
        // 过期回调检测
        if (switchId !== switchAlbumImage._switchId) return;
        // 关键修复：图片加载失败时，尝试从播放列表获取封面降级（搜索添加的歌曲有正确封面）
        if (typeof playlistData !== 'undefined' && typeof currentPlayingSongId !== 'undefined') {
            var songEntry = null;
            for (var _p = 0; _p < playlistData.length; _p++) {
                if (String(playlistData[_p].id) === String(currentPlayingSongId)) { songEntry = playlistData[_p]; break; }
            }
            if (songEntry && songEntry.pic && songEntry.pic.length > 10 && songEntry.pic !== newSrc) {
                var fallbackPic = songEntry.pic;
                console.log('[封面] switchAlbumImage 降级使用播放列表封面: ' + fallbackPic.substring(0, 50));
                // 异步尝试加载降级封面
                var retryImg = new Image();
                retryImg.onload = function() {
                    if (switchId !== switchAlbumImage._switchId) return;
                    inactiveImage.src = fallbackPic;
                    inactiveImage.style.opacity = '1';
                    activeImage.style.opacity = '0';
                    switchAlbumImage._swapTimer = setTimeout(function() {
                        if (switchId !== switchAlbumImage._switchId) return;
                        var temp = activeImage;
                        activeImage = inactiveImage;
                        inactiveImage = temp;
                        isSwitching = false;
                        switchAlbumImage._swapTimer = null;
                    }, 500);
                };
                retryImg.onerror = function() {
                    if (switchId !== switchAlbumImage._switchId) return;
                    // 降级封面也加载失败，使用默认封面
                    inactiveImage.src = defaultCover;
                    inactiveImage.style.opacity = '1';
                    activeImage.style.opacity = '0';
                    switchAlbumImage._swapTimer = setTimeout(function() {
                        if (switchId !== switchAlbumImage._switchId) return;
                        var temp = activeImage;
                        activeImage = inactiveImage;
                        inactiveImage = temp;
                        isSwitching = false;
                        switchAlbumImage._swapTimer = null;
                    }, 500);
                };
                retryImg.src = fallbackPic;
                return;
            }
        }
        inactiveImage.src = defaultCover;
        inactiveImage.style.opacity = '1';
        activeImage.style.opacity = '0';

        switchAlbumImage._swapTimer = setTimeout(function() {
            if (switchId !== switchAlbumImage._switchId) return;
            const temp = activeImage;
            activeImage = inactiveImage;
            inactiveImage = temp;
            isSwitching = false;
            switchAlbumImage._swapTimer = null;
        }, 500);
    };
}
