/**
 * 缓存管理模块
 * 包含本地存储缓存、缓存清理、定时器管理等功能
 */

// 缓存配置
const CACHE_CONFIG = {
    SONG_CACHE_PREFIX: 'netease_song_cache_',
    SONG_CACHE_DURATION: 3 * 60 * 60 * 1000, // 3小时
    LYRIC_CACHE_PREFIX: 'netease_lyric_cache_',
    LYRIC_CACHE_DURATION: 7 * 24 * 60 * 60 * 1000, // 7天
    IMAGE_CACHE_PREFIX: 'netease_image_cache_',
    IMAGE_CACHE_DURATION: 24 * 60 * 60 * 1000, // 24小时
    STATE_KEY: 'netease_page_state',
    MAX_CACHE_SIZE: 100 * 1024 * 1024, // 100MB
    CLEANUP_INTERVAL: 30 * 60 * 1000 // 30分钟
};

// 定时器变量（由HTML主script声明，此处仅引用）
// backgroundStateCheckInterval, lyricRetryTimeout, touchTimer,
// cleanupExpiredCacheTimer, networkQualityTimer 均在HTML主script中声明

// 清理所有定时器（防止内存泄漏）
function cleanupAllTimers() {
    console.log('清理所有定时器...');
    clearInterval(backgroundStateCheckInterval);
    clearInterval(cleanupExpiredCacheTimer);
    clearInterval(networkQualityTimer);
    clearInterval(progressUpdateInterval);
    clearInterval(mediaSessionSyncInterval);
    clearTimeout(retryTimeout);
    clearTimeout(lyricRetryTimeout);
    clearTimeout(touchTimer);
}

// 从本地存储获取歌曲缓存
function getSongCacheFromStorage(songId) {
    if (!songId || songId === 'undefined') {
        return null;
    }
    try {
        const key = CACHE_CONFIG.SONG_CACHE_PREFIX + songId;
        const data = localStorage.getItem(key);
        if (data) {
            const cache = JSON.parse(data);
            // 检查是否过期
            if (Date.now() - cache.timestamp < CACHE_CONFIG.SONG_CACHE_DURATION) {
                // 更新访问记录
                updateCacheAccess(songId);
                return cache;
            } else {
                // 过期了，删除并标记需要重新获取
                localStorage.removeItem(key);
                console.log(`歌曲 ${songId} 缓存已过期，已删除，需要重新获取`);

                // 标记该歌曲需要重新获取缓存
                markCacheForRefresh(songId);
            }
        }
    } catch (error) {
        console.error('读取歌曲缓存失败:', error);
    }
    return null;
}

// 标记缓存需要刷新
function markCacheForRefresh(songId) {
    if (!songId) return;

    // 使用单独的标记来记录需要刷新的缓存
    const refreshKey = CACHE_CONFIG.SONG_CACHE_PREFIX + 'refresh_' + songId;
    localStorage.setItem(refreshKey, Date.now().toString());

    console.log(`标记歌曲 ${songId} 需要刷新缓存`);

    // 如果在线且有网络，立即尝试刷新
    if (isOnline) {
        refreshExpiredCache(songId);
    }
}

// 刷新过期缓存
function refreshExpiredCache(songId) {
    if (!songId || !isOnline) return;

    console.log(`开始刷新歌曲 ${songId} 的过期缓存`);

    // 使用后台任务刷新缓存，不影响当前播放
    setTimeout(() => {
        // 检查当前缓存是否包含URL，只有包含URL的缓存（搜索歌曲）才刷新
        const existingCache = getSongCacheFromStorage(songId);
        if (!existingCache || !existingCache.url) {
            console.log(`歌曲 ${songId} 无URL缓存，跳过刷新（播放列表歌曲不缓存URL）`);
            // 清除刷新标记
            const refreshKey = CACHE_CONFIG.SONG_CACHE_PREFIX + 'refresh_' + songId;
            localStorage.removeItem(refreshKey);
            return;
        }

        const currentSongInfo = typeof playlistData !== 'undefined' ? playlistData.find(s => s.id === songId) : null;
        const songName = currentSongInfo ? currentSongInfo.name : '';
        const apiUrl = typeof getApiUrl === 'function' ? getApiUrl(songId, 'exhigh', songName) : null;

        if (!apiUrl) {
            console.error(`[API] 无法获取歌曲 ${songId} 的API URL`);
            return;
        }

        axios.get(apiUrl, {
            timeout: 15000
        }).then(res => {
            const parsedData = typeof parseApiResponse === 'function' ? parseApiResponse(res, songId) : null;
            if (parsedData && parsedData.url) {
                // 检查版权
                const songUrl = parsedData.url || '';
                const isNoCopyright = songUrl.includes('获取歌曲地址失败，可能是会员到期了');

                if (!isNoCopyright) {
                    // 保存新的缓存
                    saveSongCacheToStorage(songId, {
                        url: parsedData.url,
                        name: parsedData.name,
                        artist: parsedData.artist,
                        pic: parsedData.pic,
                        duration: parsedData.duration,
                        album: parsedData.album || "",
                        timestamp: Date.now()
                    });

                    // 清除刷新标记
                    const refreshKey = CACHE_CONFIG.SONG_CACHE_PREFIX + 'refresh_' + songId;
                    localStorage.removeItem(refreshKey);

                    console.log(`歌曲 ${songId} 缓存刷新成功`);
                } else {
                    console.log(`歌曲 ${songId} 无版权，跳过缓存刷新`);
                }
            }
        }).catch(error => {
            console.error(`刷新歌曲 ${songId} 缓存失败:`, error);
        });
    }, 1000);
}

// 保存歌曲缓存到本地存储
function saveSongCacheToStorage(songId, songData) {
    if (!songId || songId === 'undefined') {
        console.warn('无效的歌曲ID，跳过缓存');
        return;
    }
    const key = CACHE_CONFIG.SONG_CACHE_PREFIX + songId;
    // 读取现有缓存，保留可能丢失的字段（如url）
    let existingUrl = null;
    try {
        const existing = localStorage.getItem(key);
        if (existing) {
            const parsed = JSON.parse(existing);
            if (parsed.url) existingUrl = parsed.url;
        }
    } catch(e) { /* 忽略 */ }
    const cache = {
        ...songData,
        // 如果新数据没有url但旧缓存有，保留旧缓存的url
        ...(existingUrl && !songData.url ? { url: existingUrl } : {}),
        timestamp: Date.now(),
        accessCount: 1,
        lastAccessed: Date.now()
    };
    try {
        localStorage.setItem(key, JSON.stringify(cache));
        console.log(`歌曲 ${songId} 已缓存到本地`);
    } catch (error) {
        console.error('保存歌曲缓存失败:', error);
        if (error.name === 'QuotaExceededError') {
            cleanupExpiredCache();
            try {
                localStorage.setItem(key, JSON.stringify(cache));
                console.log(`歌曲 ${songId} 清理后重新缓存成功`);
            } catch (retryError) {
                console.error('清理后仍然无法缓存:', retryError);
            }
        }
    }
}

// 保存歌曲到playlist.json文件
function saveSongToPlaylistJson(songInfo) {
    try {
        console.log('[Playlist] 保存歌曲到playlist.json:', songInfo);

        // 通过Android原生代码写入文件
        if (window.AndroidBridge && typeof window.AndroidBridge.addSong === 'function') {
            const result = window.AndroidBridge.addSong(JSON.stringify(songInfo));
            if (result) {
                console.log('[Playlist] 歌曲已成功写入playlist.json');
                return true;
            } else {
                console.error('[Playlist] 写入playlist.json失败');
                return false;
            }
        } else {
            console.warn('[Playlist] AndroidBridge.addSong方法不可用');
            return false;
        }
    } catch (error) {
        console.error('[Playlist] 保存歌曲到playlist.json异常:', error);
        return false;
    }
}

// 更新缓存访问记录
function updateCacheAccess(songId) {
    if (!songId) return;
    try {
        const key = CACHE_CONFIG.SONG_CACHE_PREFIX + songId;
        const data = localStorage.getItem(key);
        if (data) {
            const cache = JSON.parse(data);
            cache.accessCount = (cache.accessCount || 0) + 1;
            cache.lastAccessed = Date.now();
            localStorage.setItem(key, JSON.stringify(cache));
        }
    } catch (error) {
        console.error('更新缓存访问记录失败:', error);
    }
}

// 清理过期的歌曲播放缓存（只清理歌曲播放缓存，播放列表永久保留）
function cleanupExpiredCache() {
    try {
        const now = Date.now();
        let cleanedCount = 0;

        // 遍历localStorage查找过期的歌曲缓存
        for (let i = localStorage.length - 1; i >= 0; i--) {
            const key = localStorage.key(i);
            // 只清理歌曲播放缓存，不清理播放列表
            if (key && key.startsWith(CACHE_CONFIG.SONG_CACHE_PREFIX)) {
                try {
                    const data = localStorage.getItem(key);
                    if (data) {
                        const cache = JSON.parse(data);
                        if (now - cache.timestamp >= CACHE_CONFIG.SONG_CACHE_DURATION) {
                            localStorage.removeItem(key);
                            cleanedCount++;
                        }
                    }
                } catch (e) {
                    // 解析失败的缓存也清理
                    localStorage.removeItem(key);
                    cleanedCount++;
                }
            }
        }

        if (cleanedCount > 0) {
            console.log(`已清理 ${cleanedCount} 个过期缓存`);
        }
    } catch (error) {
        console.error('清理过期缓存失败:', error);
    }
}

// 初始化后台状态监控
function initBackgroundStateMonitoring() {
    // 启动后台状态检查定时器
    backgroundStateCheckInterval = setInterval(() => {
        checkBackgroundAudioState();
    }, 10000); // 10秒检查一次
}

// 检查后台音频状态 - 优化：增强后台播放稳定性
function checkBackgroundAudioState() {
    // 如果不在后台或没有播放，不需要检查
    if (!isInBackground || !isPlaying) return;

    const now = Date.now();
    // 避免过于频繁的检查
    if (now - lastAudioStateCheck < 2000) return;

    lastAudioStateCheck = now;

    // 关键修复：原生播放器模式下跳过Web Audio状态检查
    if (USE_NATIVE_PLAYER && nativePlayerInitialized) {
        // 原生播放器模式下，依赖原生播放器的回调来同步状态
        // 不需要检查 player.paused，因为 player.src 可能未设置
        return;
    }

    // 检查音频元素状态与实际播放状态是否一致（仅Web Audio模式）
    if (isPlaying && player.paused) {
        console.log("检测到音频状态不同步：正在播放但音频已暂停");

        // 尝试恢复播放 - 优化：增加恢复策略
        if (backgroundPlayAttempts < MAX_BACKGROUND_PLAY_ATTEMPTS) {
            backgroundPlayAttempts++;
            console.log(`尝试后台恢复播放 (${backgroundPlayAttempts}/${MAX_BACKGROUND_PLAY_ATTEMPTS})`);

            player.play().then(() => {
                console.log("后台播放恢复成功");
                backgroundPlayAttempts = 0;
            }).catch(error => {
                console.error("后台播放恢复失败:", error);
            });
        }
    }
}
