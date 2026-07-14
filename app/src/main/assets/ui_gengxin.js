/**
 * UI更新模块
 * 包含Toast提示、原生播放器接口、动画控制、移动端错误处理等功能
 */

// ==================== Toast提示 ====================

// 显示播放模式切换提示
function showModeToast(message) {
    if (typeof modeToast === 'undefined' || !modeToast) return;
    modeToast.textContent = message;
    modeToast.classList.add('show');

    setTimeout(function() {
        modeToast.classList.remove('show');
    }, 1500);
}

// 显示自定义提示框
function showToast(message, type, duration) {
    type = type || 'info';
    duration = duration || 2000;

    var toast = document.getElementById('customToast');
    var toastMessage = document.getElementById('customToastMessage');
    var toastIcon = toast ? toast.querySelector('.custom-toast-icon') : null;

    if (!toast || !toastMessage) return;

    toastMessage.textContent = message;

    toast.className = 'custom-toast ' + type;
    var iconMap = {
        success: '<i class="fa-solid fa-circle-check"></i>',
        error: '<i class="fa-solid fa-circle-xmark"></i>',
        warning: '<i class="fa-solid fa-triangle-exclamation"></i>',
        info: '<i class="fa-solid fa-circle-info"></i>'
    };
    if (toastIcon) {
        toastIcon.innerHTML = iconMap[type] || iconMap.info;
    }

    toast.classList.add('show');

    if (toast._hideTimeout) {
        clearTimeout(toast._hideTimeout);
    }
    toast._hideTimeout = setTimeout(function() {
        toast.classList.remove('show');
    }, duration);
}

// 显示网络状态提示
function showNetworkStatus(isAvailable) {
    if (isAvailable) {
        showToast('网络已恢复', 'success', 2000);
    } else {
        showToast('网络已断开', 'warning', 3000);
    }
}

// ==================== 费雪-耶茨洗牌算法 ====================

function fisherYatesShuffle(array) {
    var shuffled = array.slice();
    for (var i = shuffled.length - 1; i > 0; i--) {
        var j = Math.floor(Math.random() * (i + 1));
        var temp = shuffled[i];
        shuffled[i] = shuffled[j];
        shuffled[j] = temp;
    }
    return shuffled;
}

// ==================== 封面图片 ====================

// 直接设置封面图片
function setCoverImagesDirect(url) {
    if (typeof img1 !== 'undefined' && img1) img1.src = url;
    if (typeof img2 !== 'undefined' && img2) img2.src = url;
    if (window.updateDynamicBackground) window.updateDynamicBackground(url);
}

// ==================== 动画控制 ====================

// 暂停所有动画
function pauseAnimations() {
    if (isPlaying) {
        if (typeof img1 !== 'undefined' && img1) img1.style.animationPlayState = 'paused';
        if (typeof img2 !== 'undefined' && img2) img2.style.animationPlayState = 'paused';
    }
}

// 恢复所有动画
function resumeAnimations() {
    if (isPlaying) {
        if (typeof img1 !== 'undefined' && img1) img1.style.animationPlayState = 'running';
        if (typeof img2 !== 'undefined' && img2) img2.style.animationPlayState = 'running';
    }
}

// 原生播放器状态（避免重复声明，这些变量可能在其他模块已定义）
if (typeof USE_NATIVE_PLAYER === 'undefined') USE_NATIVE_PLAYER = true;
if (typeof nativePlayerInitialized === 'undefined') nativePlayerInitialized = false;
if (typeof nativePlayerInitRetries === 'undefined') nativePlayerInitRetries = 0;
if (typeof MAX_NATIVE_PLAYER_INIT_RETRIES === 'undefined') MAX_NATIVE_PLAYER_INIT_RETRIES = 3;
if (typeof NATIVE_PLAYER_INIT_RETRY_DELAY === 'undefined') NATIVE_PLAYER_INIT_RETRY_DELAY = 500;

// ==================== 原生播放器接口 ====================

// 初始化原生播放器
function initNativePlayer() {
    if (!USE_NATIVE_PLAYER || nativePlayerInitialized) return true;
    if (window.AndroidBridge && typeof window.AndroidBridge.initNativePlayer === 'function') {
        try {
            var result = window.AndroidBridge.initNativePlayer();
            if (result === true || result === 'true') {
                nativePlayerInitialized = true;
                nativePlayerInitRetries = 0;
                console.log("原生音频播放器已初始化成功");
                return true;
            } else {
                nativePlayerInitialized = false;
                if (nativePlayerInitRetries < MAX_NATIVE_PLAYER_INIT_RETRIES) {
                    nativePlayerInitRetries++;
                    console.warn("原生播放器初始化失败，" + NATIVE_PLAYER_INIT_RETRY_DELAY + "ms后重试 (" + nativePlayerInitRetries + "/" + MAX_NATIVE_PLAYER_INIT_RETRIES + ")");
                    setTimeout(function() {
                        if (!nativePlayerInitialized) initNativePlayer();
                    }, NATIVE_PLAYER_INIT_RETRY_DELAY);
                } else {
                    console.error("原生播放器初始化失败，已达最大重试次数");
                }
                return false;
            }
        } catch (e) {
            console.error("初始化原生播放器时发生错误:", e);
            nativePlayerInitialized = false;
            return false;
        }
    }
    console.warn("AndroidBridge.initNativePlayer 不可用");
    return false;
}

// 使用原生播放器加载并播放
function nativeLoadAndPlay(url, startPosition) {
    startPosition = startPosition || 0;
    if (!USE_NATIVE_PLAYER) return false;
    if (!nativePlayerInitialized) {
        console.warn("原生播放器未初始化，尝试初始化...");
        var initialized = initNativePlayer();
        if (!initialized) {
            console.error("原生播放器初始化失败，无法播放");
            return false;
        }
    }
    if (window.AndroidBridge && typeof window.AndroidBridge.nativeLoadAndPlay === 'function') {
        try {
            var result = window.AndroidBridge.nativeLoadAndPlay(url, startPosition);
            if (result === true || result === 'true') {
                return true;
            } else {
                console.error("nativeLoadAndPlay 返回失败");
                return false;
            }
        } catch (e) {
            console.error("nativeLoadAndPlay 调用失败:", e);
            return false;
        }
    }
    console.warn("AndroidBridge.nativeLoadAndPlay 不可用");
    return false;
}

// 原生播放器播放
function nativePlay() {
    if (!USE_NATIVE_PLAYER) return false;
    if (!nativePlayerInitialized) {
        console.warn("原生播放器未初始化，尝试初始化...");
        var initialized = initNativePlayer();
        if (!initialized) return false;
    }
    if (window.AndroidBridge && typeof window.AndroidBridge.nativePlay === 'function') {
        try {
            window.AndroidBridge.nativePlay();
            return true;
        } catch (e) {
            console.error("nativePlay 调用失败:", e);
            return false;
        }
    }
    return false;
}

// 原生播放器恢复播放
function nativeResume() {
    if (!USE_NATIVE_PLAYER) return false;
    if (!nativePlayerInitialized) {
        console.warn("原生播放器未初始化，尝试初始化...");
        var initialized = initNativePlayer();
        if (!initialized) return false;
    }
    if (window.AndroidBridge && typeof window.AndroidBridge.nativeResume === 'function') {
        try {
            window.AndroidBridge.nativeResume();
            return true;
        } catch (e) {
            console.error("nativeResume 调用失败:", e);
            return false;
        }
    }
    return false;
}

// 原生播放器暂停
function nativePause() {
    if (!USE_NATIVE_PLAYER) return false;
    if (!nativePlayerInitialized) {
        console.warn("原生播放器未初始化");
        return false;
    }
    if (window.AndroidBridge && typeof window.AndroidBridge.nativePause === 'function') {
        try {
            window.AndroidBridge.nativePause();
            return true;
        } catch (e) {
            console.error("nativePause 调用失败:", e);
            return false;
        }
    }
    return false;
}

// 原生播放器跳转
function nativeSeekTo(position) {
    if (!USE_NATIVE_PLAYER) return false;
    if (!nativePlayerInitialized) {
        console.warn("原生播放器未初始化");
        return false;
    }
    if (window.AndroidBridge && typeof window.AndroidBridge.nativeSeekTo === 'function') {
        try {
            window.AndroidBridge.nativeSeekTo(position);
            return true;
        } catch (e) {
            console.error("nativeSeekTo 调用失败:", e);
            return false;
        }
    }
    return false;
}

// ==================== 原生端通知 ====================

// 通知原生端歌曲信息变化（带防抖，避免切歌时重复调用）
var _notifyNativeDebounceTimer = null;
function notifyNativeSongInfo() {
    if (_notifyNativeDebounceTimer) {
        clearTimeout(_notifyNativeDebounceTimer);
        _notifyNativeDebounceTimer = null;
    }
    _notifyNativeDebounceTimer = setTimeout(function() {
        _notifyNativeDebounceTimer = null;
        if (window.AndroidBridge && typeof window.AndroidBridge.onSongInfoChanged === 'function') {
            var songInfo = {
                id: currentPlayingSongId || "",
                title: songTitle.textContent || "",
                artist: songArtist.textContent || "",
                cover: currentCoverImage || "",
                duration: totalDuration
            };
            window.AndroidBridge.onSongInfoChanged(JSON.stringify(songInfo));
            console.log("[ShiYinSync] [歌曲信息] H5→Native:", songInfo.title, "-", songInfo.artist);
        }
    }, 50);
}

// 更新系统通知栏状态
function updateNotificationStatus(status, songName, artistName) {
    if (window.AndroidBridge && typeof window.AndroidBridge.updateNotificationStatus === 'function') {
        window.AndroidBridge.updateNotificationStatus(status, songName, artistName);
        console.log("已更新通知栏状态:", status, songName, artistName);
    }
}

// ==================== 移动端错误处理 ====================

// 移动端音频错误处理
function handleMobileAudioError(error) {
    var isMobile = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    if (!isMobile) return;

    console.log('移动端音频错误处理开始');

    if (player.error) {
        switch (player.error.code) {
            case 1:
                console.log('用户中止播放，忽略错误');
                break;
            case 2:
                console.log('网络错误，尝试使用备用方案');
                handleNetworkError();
                break;
            case 3:
                console.log('音频解码错误，尝试重新加载或使用原生播放器');
                handleDecodeError();
                break;
            case 4:
                console.log('音频格式不支持，尝试转换格式');
                handleFormatError();
                break;
            default:
                console.log('未知音频错误，尝试通用恢复方案');
                handleGenericError();
                break;
        }
    }
}

// 处理网络错误
function handleNetworkError() {
    if (isOnline) {
        console.log('移动端网络错误，尝试使用低音质版本');
        var lowQualityUrl = getLowQualityAudioUrl(currentPlayingSongId);
        if (lowQualityUrl) {
            player.src = lowQualityUrl;
            player.load();
            setTimeout(function() {
                player.play().catch(function(err) {
                    console.error('低音质版本播放失败:', err);
                    handleCacheFallback();
                });
            }, 1000);
        } else {
            handleCacheFallback();
        }
    } else {
        handleCacheFallback();
    }
}

// 处理解码错误
function handleDecodeError() {
    if (USE_NATIVE_PLAYER && window.AndroidBridge) {
        console.log('Web Audio解码失败，切换到原生播放器');
        if (currentPlayingSongId && player.src) {
            var currentSrc = player.src;
            player.pause();
            player.src = '';
            var nativeResult = nativeLoadAndPlay(currentSrc, 0);
            if (nativeResult) {
                isUsingNativePlayer = true;
                return;
            }
        }
    }

    console.log('尝试重新加载音频');
    player.load();
    setTimeout(function() {
        player.play().catch(function(err) {
            console.error('重新加载后播放失败:', err);
            if (typeof playNextSong === 'function') playNextSong();
        });
    }, 2000);
}

// 处理格式不支持错误
function handleFormatError() {
    console.log('格式不支持，尝试使用备用音频源');
    var alternativeUrl = getAlternativeAudioFormat(currentPlayingSongId);
    if (alternativeUrl) {
        if (USE_NATIVE_PLAYER && window.AndroidBridge && typeof window.AndroidBridge.nativeLoadAndPlay === 'function') {
            player.pause();
            player.src = '';
            var result = window.AndroidBridge.nativeLoadAndPlay(alternativeUrl, 0);
            if (!result) {
                console.error('备用格式原生播放失败');
                if (typeof playNextSong === 'function') playNextSong();
            }
        } else {
            player.src = alternativeUrl;
            player.load();
            setTimeout(function() {
                player.play().catch(function(err) {
                    console.error('备用格式播放失败:', err);
                    if (typeof playNextSong === 'function') playNextSong();
                });
            }, 1000);
        }
    } else {
        if (typeof playNextSong === 'function') playNextSong();
    }
}

// 通用错误处理
function handleGenericError() {
    console.log('执行移动端通用错误恢复策略');
    player.pause();
    player.currentTime = 0;

    setTimeout(function() {
        player.load();
        setTimeout(function() {
            player.play().catch(function(err) {
                console.error('通用恢复策略失败:', err);
                showToast('播放失败，自动跳过', 'error', 2000);
                setTimeout(function() {
                    if (typeof playNextSong === 'function') playNextSong();
                }, 500);
            });
        }, 500);
    }, 1000);
}

// 缓存回退处理
function handleCacheFallback() {
    console.log('尝试使用缓存回退方案');

    if (currentPlayingSongId) {
        var cachedSongData = typeof getSongCacheFromStorage === 'function' ? getSongCacheFromStorage(currentPlayingSongId) : null;
        if (cachedSongData && cachedSongData.url) {
            console.log('使用缓存的音频文件');
            if (USE_NATIVE_PLAYER && window.AndroidBridge && typeof window.AndroidBridge.nativeLoadAndPlay === 'function') {
                player.pause();
                player.src = '';
                var result = window.AndroidBridge.nativeLoadAndPlay(cachedSongData.url, 0);
                if (!result) {
                    console.error('缓存音频原生播放失败，删除缓存');
                    localStorage.removeItem(CACHE_CONFIG.SONG_CACHE_PREFIX + currentPlayingSongId);
                    if (!isOnline) {
                        showToast('缓存已失效，网络已断开', 'warning', 3000);
                        isLoading = false;
                        if (typeof enableButtons === 'function') enableButtons();
                        if (typeof hideLoading提示 === 'function') hideLoading提示();
                        return;
                    }
                    if (typeof playNextSong === 'function') playNextSong();
                }
            } else {
                player.src = cachedSongData.url;
                player.load();
                setTimeout(function() {
                    player.play().catch(function(err) {
                        console.error('缓存音频播放失败:', err);
                        localStorage.removeItem(CACHE_CONFIG.SONG_CACHE_PREFIX + currentPlayingSongId);
                        if (!isOnline) {
                            showToast('缓存已失效，网络已断开', 'warning', 3000);
                            isLoading = false;
                            if (typeof enableButtons === 'function') enableButtons();
                            if (typeof hideLoading提示 === 'function') hideLoading提示();
                            return;
                        }
                        if (typeof playNextSong === 'function') playNextSong();
                    });
                }, 1000);
            }
        } else {
            if (!isOnline) {
                showToast('网络已断开，暂无缓存', 'warning', 3000);
                isLoading = false;
                if (typeof enableButtons === 'function') enableButtons();
                if (typeof hideLoading提示 === 'function') hideLoading提示();
                return;
            }
            if (typeof playNextSong === 'function') playNextSong();
        }
    } else {
        if (typeof playNextSong === 'function') playNextSong();
    }
}

// 获取低音质音频URL
function getLowQualityAudioUrl(songId) {
    if (player.src && player.src.includes('level=exhigh')) {
        return player.src.replace('level=exhigh', 'level=standard');
    }
    return null;
}

// 获取备用音频格式
function getAlternativeAudioFormat(songId) {
    return null;
}

// 获取可用的缓存歌曲列表
function getAvailableCachedSongs() {
    var cachedSongs = [];
    try {
        for (var i = 0; i < localStorage.length; i++) {
            var key = localStorage.key(i);
            if (key && key.startsWith(CACHE_CONFIG.SONG_CACHE_PREFIX)) {
                try {
                    var data = localStorage.getItem(key);
                    if (data) {
                        var cache = JSON.parse(data);
                        if (Date.now() - cache.timestamp < CACHE_CONFIG.SONG_CACHE_DURATION) {
                            var songId = key.replace(CACHE_CONFIG.SONG_CACHE_PREFIX, '');
                            var songName = cache.name || '';
                            var songArtist = cache.artist || '';

                            // 如果缓存中名字/歌手为空，尝试从播放列表中查找
                            if ((!songName || !songArtist) && typeof playlistData !== 'undefined') {
                                for (var _j = 0; _j < playlistData.length; _j++) {
                                    var ps = playlistData[_j];
                                    // 匹配原始ID或带前缀的QQ ID
                                    var psId = String(ps.id);
                                    var cacheId = String(songId);
                                    if (psId === cacheId || psId === cacheId.replace(/^(qq_|wy_)/, '')) {
                                        if (!songName) songName = ps.name || '';
                                        if (!songArtist) songArtist = ps.artist || '';
                                        break;
                                    }
                                }
                            }

                            cachedSongs.push({
                                id: songId,
                                name: songName || '未知歌曲',
                                artist: songArtist || '未知歌手'
                            });
                        }
                    }
                } catch (e) {
                    // 忽略解析错误
                }
            }
        }
    } catch (error) {
        console.error('获取缓存歌曲列表失败:', error);
    }
    return cachedSongs;
}

// ==================== 页面状态保存 ====================

// 保存页面状态到本地存储
function savePageState() {
    try {
        var state = {
            currentPlayingSongId: currentPlayingSongId,
            currentIndex: currentIndex,
            randomIndex: randomIndex,
            playMode: playMode,
            isPlaying: isPlaying,
            currentProgress: currentProgress,
            totalDuration: totalDuration,
            currentCoverImage: currentCoverImage,
            songTitle: songTitle.textContent,
            songArtist: songArtist.textContent,
            timestamp: Date.now()
        };
        localStorage.setItem(CACHE_CONFIG.STATE_KEY, JSON.stringify(state));
        console.log('页面状态已保存');
    } catch (e) {
        console.error('保存页面状态失败:', e);
    }
}
