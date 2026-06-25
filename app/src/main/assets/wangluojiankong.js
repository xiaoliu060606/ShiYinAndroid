/**
 * 网络监控模块
 * 包含网络状态监听、质量检测、离线处理等功能
 */

// 网络状态变量
var isOnline = true;
var lastOnlineTime = Date.now();
var networkInterruptionCount = 0;
var offlineStartTime = 0;
var offlineDuration = 0;
var networkQuality = 'excellent';
var downloadSpeed = 0;
var lastQualityCheck = 0;
const QUALITY_CHECK_INTERVAL = 60000;
const MIN_RESUME_INTERVAL = 5000;
var lastResumeAttempt = 0;

// 网络状态指示器（由HTML主script声明，此处延迟获取）
// var networkIndicator 在HTML主script中通过 const 声明

// 更新网络状态指示器
function updateNetworkIndicator() {
    // networkIndicator 在HTML主script中通过 const 声明
    if (typeof networkIndicator === 'undefined' || !networkIndicator) {
        // HTML主script尚未执行或元素不存在
        var el = document.getElementById('networkIndicator');
        if (!el) return;
        el.style.backgroundColor = isOnline ? "#4CAF50" : "#F44336";
        return;
    }

    // 根据网络状态和质量设置不同颜色
    if (!isOnline) {
        networkIndicator.style.backgroundColor = "#F44336"; // 红色 - 离线
    } else {
        switch (networkQuality) {
            case 'excellent':
                networkIndicator.style.backgroundColor = "#4CAF50"; // 绿色 - 优秀
                break;
            case 'good':
                networkIndicator.style.backgroundColor = "#8BC34A"; // 浅绿色 - 良好
                break;
            case 'fair':
                networkIndicator.style.backgroundColor = "#FFC107"; // 黄色 - 一般
                break;
            case 'poor':
                networkIndicator.style.backgroundColor = "#FF9800"; // 橙色 - 较差
                break;
            default:
                networkIndicator.style.backgroundColor = "#9E9E9E"; // 灰色 - 未知
        }
    }
}

// 初始化网络状态监听
function initNetworkMonitoring() {
    // 初始检查网络状态
    checkNetworkOnLoad();

    // 监听网络状态变化
    window.addEventListener('online', handleNetworkOnline);
    window.addEventListener('offline', handleNetworkOffline);

    // 定期检查网络质量
    setInterval(checkNetworkQuality, QUALITY_CHECK_INTERVAL);

    // 初始化网络质量
    checkNetworkQuality();
}

// 检查网络状态
function checkNetworkOnLoad() {
    isOnline = typeof AndroidBridge !== 'undefined' && AndroidBridge.wangLuoShiFouKeYong
        ? AndroidBridge.wangLuoShiFouKeYong()
        : navigator.onLine;

    // 如果通过Android API检查在线，则直接信任
    if (isOnline) {
        lastOnlineTime = Date.now();
        networkQuality = 'excellent';
        updateNetworkIndicator();
        return;
    }

    // 如果Android API返回离线，尝试通过网络请求验证
    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.wangLuoShiFouKeYong) {
        const xhr = new XMLHttpRequest();
        xhr.open('HEAD', 'https://music.163.com/favicon.ico', true);
        xhr.timeout = 2000;

        xhr.onload = function () {
            if (xhr.status >= 200 && xhr.status < 400) {
                isOnline = true;
                lastOnlineTime = Date.now();
                networkQuality = 'excellent';
                console.log("网络验证通过，已在线");
            } else {
                isOnline = false;
                networkQuality = 'offline';
                console.log("网络验证失败，响应状态异常");
            }
            updateNetworkIndicator();
        };

        xhr.onerror = function () {
            isOnline = false;
            networkQuality = 'offline';
            console.log("网络验证失败，请求出错");
            updateNetworkIndicator();
        };

        xhr.ontimeout = function () {
            isOnline = false;
            networkQuality = 'offline';
            console.log("网络验证失败，请求超时");
            updateNetworkIndicator();
        };

        xhr.send();
    } else {
        // Web环境，使用navigator.onLine
        networkQuality = isOnline ? 'excellent' : 'offline';
        updateNetworkIndicator();
    }
}

// 检查网络质量
function checkNetworkQuality() {
    // 如果当前离线，不检查质量
    if (!isOnline) {
        networkQuality = 'offline';
        updateNetworkIndicator();
        return;
    }

    const now = Date.now();
    // 检查是否需要进行网络质量检查
    if (now - lastQualityCheck < QUALITY_CHECK_INTERVAL) {
        return;
    }

    // 如果有下载速度数据，使用它来评估网络质量
    if (downloadSpeed > 0) {
        if (downloadSpeed > 1000) { // 1MB/s以上
            networkQuality = 'excellent';
        } else if (downloadSpeed > 500) { // 500KB/s以上
            networkQuality = 'good';
        } else if (downloadSpeed > 200) { // 200KB/s以上
            networkQuality = 'fair';
        } else {
            networkQuality = 'poor';
        }
    } else if (typeof AndroidBridge !== 'undefined' && AndroidBridge.wangLuoShiFouKeYong) {
        const effectiveType = AndroidBridge.wangLuoShiFouKeYong();
        // 简单根据连接类型评估质量
        if (effectiveType === 'wifi' || effectiveType === '4g' || effectiveType === '5g') {
            networkQuality = 'excellent';
        } else if (effectiveType === '3g') {
            networkQuality = 'good';
        } else if (effectiveType === '2g') {
            networkQuality = 'fair';
        } else {
            // 没有connection API，我们尝试通过快速请求估算网络质量
            estimateNetworkQuality();
        }
    } else {
        // 如果没有connection API，我们尝试通过快速请求估算网络质量
        estimateNetworkQuality();
    }

    // 记录网络质量检查时间
    lastQualityCheck = Date.now();

    // 根据网络质量调整播放策略
    adjustPlaybackStrategy();

    // 更新网络指示器颜色
    updateNetworkIndicator();
}

// 估算网络质量
function estimateNetworkQuality() {
    const startTime = performance.now();
    const xhr = new XMLHttpRequest();
    xhr.open('GET', 'https://api.music.163.com/api/v3/search/pc?keywords=test', true);
    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');

    xhr.timeout = 3000;

    xhr.onload = function () {
        if (xhr.status === 200) {
            const duration = performance.now() - startTime;
            const responseSize = xhr.responseText.length;
            downloadSpeed = (responseSize / 1024) / (duration / 1000);

            // 根据响应时间和下载速度评估网络质量
            if (duration < 300 && downloadSpeed > 1000) {
                networkQuality = 'excellent';
            } else if (duration < 800 && downloadSpeed > 500) {
                networkQuality = 'good';
            } else if (duration < 1500 && downloadSpeed > 200) {
                networkQuality = 'fair';
            } else {
                networkQuality = 'poor';
            }

            // 调整播放策略
            adjustPlaybackStrategy();
            updateNetworkIndicator();
        }
    };

    xhr.ontimeout = xhr.onerror = function () {
        networkQuality = 'poor';
        downloadSpeed = 0;
        adjustPlaybackStrategy();
        updateNetworkIndicator();
    };

    xhr.send();
}

// 根据网络质量调整播放策略
function adjustPlaybackStrategy() {
    // 根据网络质量调整缓冲策略
    if (networkQuality === 'poor') {
        // 较差网络下增加预加载时间
        if (typeof player !== 'undefined') {
            player.preload = 'auto';
        }
    } else {
        // 良好网络下使用默认预加载
        if (typeof player !== 'undefined') {
            player.preload = 'metadata';
        }
    }
}

// 显示网络状态
function showNetworkStatus() {
    if (!isOnline) {
        if (typeof showToast === 'function') {
            showToast('当前处于离线模式', 'warning', 2000);
        }
    } else {
        if (typeof showToast === 'function') {
            showToast(`网络状态: ${getNetworkQualityText()}`, 'info', 1500);
        }
    }
}

// 获取网络质量文本
function getNetworkQualityText() {
    switch (networkQuality) {
        case 'excellent':
            return '优秀';
        case 'good':
            return '良好';
        case 'fair':
            return '一般';
        case 'poor':
            return '较差';
        case 'offline':
            return '离线';
        default:
            return '未知';
    }
}

// 处理网络在线事件
function handleNetworkOnline() {
    if (!isOnline) {
        isOnline = true;
        updateNetworkIndicator();

        if (offlineStartTime > 0) {
            offlineDuration = Date.now() - offlineStartTime;
            offlineStartTime = 0;

            // 记录网络恢复信息
            console.log(`网络恢复，离线时间: ${Math.round(offlineDuration / 1000)}秒`);

            // 如果离线时间较长，显示提示信息
            if (offlineDuration > 30000) { // 30秒以上
                if (typeof showLoading提示 === 'function') {
                    showLoading提示(`网络恢复，离线${Math.round(offlineDuration / 1000)}秒`);
                    setTimeout(() => {
                        if (typeof hideLoading提示 === 'function') hideLoading提示();
                    }, 2000);
                }
            }

            // 关键修复：根据播放器类型选择正确的进度源
            if (typeof USE_NATIVE_PLAYER !== 'undefined' && USE_NATIVE_PLAYER && typeof nativePlayerInitialized !== 'undefined' && nativePlayerInitialized) {
                // 原生播放器模式下，currentProgress 已由回调更新
                if (typeof updateProgress === 'function') updateProgress();
                if (typeof forceSyncMediaSession === 'function') forceSyncMediaSession();
            } else {
                // Web Audio 模式下使用音频元素的真实时间
                if (typeof player !== 'undefined') {
                    const actualProgress = Math.floor(player.currentTime * 1000);
                    currentProgress = actualProgress;
                    if (typeof updateProgress === 'function') updateProgress();
                    if (typeof forceSyncMediaSession === 'function') forceSyncMediaSession();
                }
            }

            if (typeof lyricsEnabled !== 'undefined' && lyricsEnabled && typeof updateLyricsDisplay === 'function') {
                updateLyricsDisplay();
            }
        }

        // 重置网络中断计数
        networkInterruptionCount = 0;

        // 立即进行一次网络质量检查
        checkNetworkQuality();

        // 网络恢复后尝试恢复播放（仅Web Audio模式）
        if (typeof USE_NATIVE_PLAYER !== 'undefined' && !USE_NATIVE_PLAYER && typeof isPlaying !== 'undefined' && isPlaying && typeof player !== 'undefined' && player.paused) {
            // 尝试断点续传
            attemptResumePlayback();
        }
    }
}

// 尝试恢复播放（断点续传）
function attemptResumePlayback() {
    const now = Date.now();

    // 防止频繁尝试恢复播放
    if (now - lastResumeAttempt < MIN_RESUME_INTERVAL) {
        return;
    }

    lastResumeAttempt = now;
    console.log("尝试断点续传播放...");

    // 记录恢复位置
    const resumePosition = player.currentTime;

    // 优化：先尝试直接播放，避免不必要的重新加载
    if (player.readyState >= player.HAVE_CURRENT_DATA) {
        console.log("音频数据足够，直接尝试播放");

        // 设置恢复位置
        if (resumePosition > 0) {
            player.currentTime = resumePosition;
        }

        player.play().then(() => {
            console.log("断点续传成功");
            if (typeof startSyncInterval === 'function') startSyncInterval();
        }).catch(error => {
            console.error("直接播放失败，尝试预加载:", error);

            // 如果直接播放失败，尝试预加载更多数据
            if (player.readyState < player.HAVE_ENOUGH_DATA) {
                console.log("音频数据不足，尝试预加载更多数据");
                player.load();

                // 延迟后再次尝试播放
                setTimeout(() => {
                    if (isOnline && isPlaying && player.paused) {
                        player.play().then(() => {
                            console.log("预加载后播放成功");
                            if (typeof startSyncInterval === 'function') startSyncInterval();
                        }).catch(error => {
                            console.error("预加载后播放失败:", error);
                            // 最后尝试重新加载当前歌曲
                            reloadCurrentSongWithResume();
                        });
                    }
                }, 1500);
            } else {
                // 尝试重新加载当前歌曲
                reloadCurrentSongWithResume();
            }
        });
    } else {
        console.log("音频数据不足，先尝试预加载更多数据");

        // 尝试从当前位置加载
        player.load();

        // 延迟后再次尝试播放
        setTimeout(() => {
            if (isOnline && isPlaying && player.paused) {
                // 设置恢复位置
                if (resumePosition > 0) {
                    player.currentTime = resumePosition;
                }

                player.play().then(() => {
                    console.log("断点续传成功");
                    if (typeof startSyncInterval === 'function') startSyncInterval();
                }).catch(error => {
                    console.error("延迟恢复播放失败:", error);
                    // 尝试重新加载当前歌曲
                    reloadCurrentSongWithResume();
                });
            }
        }, 2000);
    }
}

// 重新加载当前歌曲并尝试恢复到之前的位置
function reloadCurrentSongWithResume() {
    // 保存当前播放位置
    const currentPos = player.currentTime;
    console.log(`重新加载歌曲，尝试从${currentPos.toFixed(1)}秒恢复`);

    if (typeof showLoading提示 === 'function') showLoading提示("正在恢复播放...");

    // 如果当前有歌曲正在播放，重新加载它
    if (currentPlayingSongId) {
        let targetIndex;
        if (typeof playMode !== 'undefined' && typeof PlayMode !== 'undefined' && playMode === PlayMode.RANDOM) {
            targetIndex = randomIndex;
        } else {
            targetIndex = currentIndex;
        }

        // 使用当前位置作为恢复点
        if (typeof loadAndPlaySong === 'function') {
            loadAndPlaySong(targetIndex, 0, currentPos);
        }
    } else {
        // 如果没有正在播放的歌曲，2秒后自动隐藏加载提示
        setTimeout(() => {
            if (typeof hideLoading提示 === 'function') hideLoading提示();
        }, 2000);
    }
}

// 处理网络离线事件
function handleNetworkOffline() {
    if (isOnline) {
        isOnline = false;
        offlineStartTime = Date.now();
        updateNetworkIndicator();

        networkInterruptionCount++;
        console.log(`网络中断 (第${networkInterruptionCount}次)`);

        const currentSongId = currentPlayingSongId;
        // 优先检查Native缓存（音频文件），再检查localStorage缓存（URL）
        var hasCache = null;
        if (currentSongId) {
            if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.huoQuHuanCunLuJing === 'function') {
                try {
                    var nativeCachePath = AndroidBridge.huoQuHuanCunLuJing(String(currentSongId));
                    if (nativeCachePath && nativeCachePath.length > 0) {
                        hasCache = { url: nativeCachePath, type: 'native' };
                    }
                } catch(e) {}
            }
            if (!hasCache && typeof getSongCacheFromStorage === 'function') {
                var urlCache = getSongCacheFromStorage(currentSongId);
                if (urlCache && urlCache.url) {
                    hasCache = urlCache;
                    hasCache.type = 'url';
                }
            }
        }

        if (typeof USE_NATIVE_PLAYER !== 'undefined' && USE_NATIVE_PLAYER && typeof nativePlayerInitialized !== 'undefined' && nativePlayerInitialized) {
            if (typeof isLoading !== 'undefined' && !isLoading && typeof isPlaying !== 'undefined' && isPlaying) {
                if (hasCache) {
                    console.log("原生播放器模式：网络断开，有缓存，继续播放");
                    if (typeof showToast === 'function') showToast('网络已断开，使用缓存继续播放', 'info', 2000);
                } else {
                    console.log("原生播放器模式：网络断开，无缓存");
                    if (typeof showToast === 'function') showToast('网络已断开，当前歌曲无缓存', 'warning', 2000);
                }
            }
            return;
        }

        if (typeof isLoading !== 'undefined' && !isLoading && typeof isPlaying !== 'undefined' && isPlaying) {
            if (hasCache) {
                console.log("网络断开，但有缓存，继续播放");
                if (typeof showToast === 'function') showToast('网络已断开，使用缓存继续播放', 'info', 2000);
            } else {
                console.log("网络断开，无缓存，暂停播放");
                if (typeof player !== 'undefined') {
                    resumePosition = player.currentTime;
                    currentProgress = Math.floor(player.currentTime * 1000);
                    player.pause();
                }
                clearInterval(progressUpdateInterval);
                if (typeof updateProgress === 'function') updateProgress();
                if (typeof forceSyncMediaSession === 'function') forceSyncMediaSession();
                if (typeof showToast === 'function') showToast('网络已断开，暂无缓存', 'warning', 2000);
            }
        }
    }
}
