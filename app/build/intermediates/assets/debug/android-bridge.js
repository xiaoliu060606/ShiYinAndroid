/**
 * Android WebView Bridge 通信模块
 * 用于 H5 与 Android 原生 MediaSession 通信
 */

(function() {
    'use strict';

    // 立即检查并记录 AndroidBridge 状态
    console.log('[AndroidBridge] 脚本开始加载');
    console.log('[AndroidBridge] AndroidBridge 是否存在:', typeof AndroidBridge !== 'undefined');
    if (typeof AndroidBridge !== 'undefined') {
        console.log('[AndroidBridge] AndroidBridge 方法:', Object.keys(AndroidBridge));
    }

    // Bridge 状态
    const BridgeState = {
        isReady: false,
        isAndroid: false,
        callbacks: {},
        retryCount: 0,
        maxRetries: 10
    };

    /**
     * 初始化 Bridge
     */
    function initBridge() {
        // 检测是否在 Android WebView 中
        BridgeState.isAndroid = typeof AndroidBridge !== 'undefined';
        
        console.log('[AndroidBridge] initBridge 检查: AndroidBridge 存在 =', BridgeState.isAndroid);
        
        if (BridgeState.isAndroid) {
            console.log('[AndroidBridge] 检测到 Android 环境，Bridge 已就绪');
            BridgeState.isReady = true;
            
            // 通知 Android Bridge 已就绪
            try {
                AndroidBridge.isNativeReady();
            } catch (e) {
                console.error('[AndroidBridge] Bridge 初始化失败:', e);
            }
            
            // 发送初始状态
            sendInitialState();
        } else {
            // 如果还没有检测到 AndroidBridge，尝试多次重试
            BridgeState.retryCount++;
            if (BridgeState.retryCount <= BridgeState.maxRetries) {
                console.log(`[AndroidBridge] 未检测到 Android 环境，重试 ${BridgeState.retryCount}/${BridgeState.maxRetries}`);
                setTimeout(initBridge, 100);
            } else {
                console.log('[AndroidBridge] 非 Android 环境，Bridge 未激活');
            }
        }
    }

    /**
     * 检查 Bridge 是否可用
     */
    function isBridgeAvailable() {
        // 实时检查，不依赖缓存的状态
        return typeof AndroidBridge !== 'undefined';
    }

    /**
     * 发送播放状态到 Android
     * @param {Object} state - 播放状态
     */
    function sendPlayState(state) {
        if (!isBridgeAvailable()) return;
        
        const stateData = {
            isPlaying: state.isPlaying || false,
            position: state.position || 0,
            duration: state.duration || 0
        };
        
        try {
            AndroidBridge.onPlayStateChanged(JSON.stringify(stateData));
        } catch (e) {
            console.error('[AndroidBridge] 发送播放状态失败:', e);
        }
    }

    /**
     * 发送歌曲信息到 Android
     * @param {Object} info - 歌曲信息
     */
    function sendSongInfo(info) {
        if (!isBridgeAvailable()) return;
        
        const infoData = {
            id: info.id || '',
            title: info.title || '未知歌曲',
            artist: info.artist || '未知歌手',
            album: info.album || '',
            cover: info.cover || '',
            duration: info.duration || 0
        };
        
        try {
            AndroidBridge.onSongInfoChanged(JSON.stringify(infoData));
        } catch (e) {
            console.error('[AndroidBridge] 发送歌曲信息失败:', e);
        }
    }

    /**
     * 发送播放进度到 Android
     * @param {number} currentTime - 当前时间（毫秒）
     * @param {number} duration - 总时长（毫秒）
     */
    function sendProgress(currentTime, duration) {
        // 实时检查 Bridge 是否可用，不依赖缓存状态
        if (typeof AndroidBridge === 'undefined') return;

        try {
            AndroidBridge.onProgressChanged(currentTime, duration);
        } catch (e) {
            console.error('[AndroidBridge] 发送进度失败:', e);
        }
    }

    /**
     * 处理来自 Android 的控制命令
     * @param {string} command - 命令字符串
     */
    function handleNativeCommand(command) {
        console.log('[AndroidBridge] 收到原生命令:', command);
        
        switch (command) {
            case 'play':
                handlePlayCommand();
                break;
            case 'pause':
                handlePauseCommand();
                break;
            case 'toggle':
                handleToggleCommand();
                break;
            case 'previous':
                handlePreviousCommand();
                break;
            case 'next':
                handleNextCommand();
                break;
            case 'stop':
                handleStopCommand();
                break;
            default:
                if (command.startsWith('seek:')) {
                    const position = parseInt(command.split(':')[1], 10);
                    handleSeekCommand(position);
                }
                break;
        }
    }

    // 命令处理函数
    function handlePlayCommand() {
        // 使用 player.paused 而不是 isPlaying 变量，避免状态不同步
        if (typeof handlePlayPause === 'function' && player.paused) {
            handlePlayPause();
        }
    }

    function handlePauseCommand() {
        // 使用 player.paused 而不是 isPlaying 变量，避免状态不同步
        if (typeof handlePlayPause === 'function' && !player.paused) {
            handlePlayPause();
        }
    }

    function handleToggleCommand() {
        if (typeof handlePlayPause === 'function') {
            handlePlayPause();
        }
    }

    function handlePreviousCommand() {
        if (typeof playPreviousSong === 'function') {
            playPreviousSong();
        }
    }

    function handleNextCommand() {
        if (typeof playNextSong === 'function') {
            playNextSong();
        }
    }

    function handleStopCommand() {
        // 使用 player.paused 而不是 isPlaying 变量，避免状态不同步
        if (typeof handlePlayPause === 'function' && !player.paused) {
            handlePlayPause();
        }
    }

    function handleSeekCommand(position) {
        if (player && typeof position === 'number') {
            player.currentTime = position / 1000;
            updateProgress();
        }
    }

    /**
     * 发送初始状态
     */
    function sendInitialState() {
        // 获取当前歌曲信息
        const currentSong = getCurrentSongInfo();
        if (currentSong) {
            sendSongInfo(currentSong);
        }
        
        // 发送当前播放状态
        sendPlayState({
            isPlaying: player ? !player.paused : false,
            position: currentProgress || 0,
            duration: totalDuration || 0
        });
    }

    /**
     * 获取当前歌曲信息
     */
    function getCurrentSongInfo() {
        const songId = getCurrentSongId ? getCurrentSongId() : '';
        const currentSong = playlistData ? playlistData.find(s => s.id === songId) : null;
        
        if (currentSong) {
            return {
                id: String(currentSong.id),
                title: currentSong.name || '未知歌曲',
                artist: currentSong.artist || '未知歌手',
                album: '',
                cover: currentSong.pic || currentCoverImage || '',
                duration: totalDuration || 0
            };
        }
        
        return {
            id: '',
            title: songTitle ? songTitle.textContent : '未知歌曲',
            artist: songArtist ? songArtist.textContent : '未知歌手',
            album: '',
            cover: currentCoverImage || '',
            duration: totalDuration || 0
        };
    }

    // 重写原有的关键函数以添加 Bridge 通信
    
    // 保存原始函数引用
    const originalHandlePlayPause = typeof handlePlayPause === 'function' ? handlePlayPause : null;
    const originalPlayPreviousSong = typeof playPreviousSong === 'function' ? playPreviousSong : null;
    const originalPlayNextSong = typeof playNextSong === 'function' ? playNextSong : null;
    const originalLoadSong = typeof loadSong === 'function' ? loadSong : null;
    const originalUpdateProgress = typeof updateProgress === 'function' ? updateProgress : null;

    /**
     * 包装播放/暂停函数
     */
    window.handlePlayPause = function() {
        if (originalHandlePlayPause) {
            originalHandlePlayPause();
        }
        
        // 延迟发送状态，等待状态更新
        setTimeout(() => {
            sendPlayState({
                isPlaying: !player.paused,
                position: currentProgress || 0,
                duration: totalDuration || 0
            });
        }, 100);
    };

    /**
     * 包装上一首函数
     */
    window.playPreviousSong = function() {
        if (originalPlayPreviousSong) {
            originalPlayPreviousSong();
        }
        
        setTimeout(() => {
            const info = getCurrentSongInfo();
            sendSongInfo(info);
            sendPlayState({
                isPlaying: !player.paused,
                position: 0,
                duration: totalDuration || 0
            });
        }, 500);
    };

    /**
     * 包装下一首函数
     */
    window.playNextSong = function() {
        if (originalPlayNextSong) {
            originalPlayNextSong();
        }

        setTimeout(() => {
            const info = getCurrentSongInfo();
            sendSongInfo(info);
            sendPlayState({
                isPlaying: !player.paused,
                position: 0,
                duration: totalDuration || 0
            });
        }, 500);
    };

    /**
     * 包装进度更新函数
     */
    window.updateProgress = function() {
        if (originalUpdateProgress) {
            originalUpdateProgress();
        }
        
        // 定期发送进度到 Android（每 300 毫秒，让系统控件更流畅）
        const now = Date.now();
        if (!window._lastProgressSend || now - window._lastProgressSend > 300) {
            window._lastProgressSend = now;
            sendProgress(currentProgress || 0, totalDuration || 0);
        }
    };

    /**
     * 包装歌曲加载函数（如果存在）
     */
    if (originalLoadSong) {
        window.loadSong = function(...args) {
            originalLoadSong.apply(this, args);
            
            setTimeout(() => {
                const info = getCurrentSongInfo();
                sendSongInfo(info);
            }, 1000);
        };
    }

    /**
     * 监听歌曲切换事件
     */
    function setupSongChangeListener() {
        // 监听音频源变化
        if (player) {
            player.addEventListener('loadedmetadata', () => {
                const info = getCurrentSongInfo();
                sendSongInfo(info);
            });
            
            player.addEventListener('play', () => {
                sendPlayState({
                    isPlaying: true,
                    position: currentProgress || 0,
                    duration: totalDuration || 0
                });
            });
            
            player.addEventListener('pause', () => {
                sendPlayState({
                    isPlaying: false,
                    position: currentProgress || 0,
                    duration: totalDuration || 0
                });
            });
            
            player.addEventListener('ended', () => {
                sendPlayState({
                    isPlaying: false,
                    position: totalDuration || 0,
                    duration: totalDuration || 0
                });
            });
        }
    }

    // 暴露全局函数供 Android 调用
    window.handleNativeCommand = handleNativeCommand;
    window.AndroidBridgeReady = initBridge;

    // 页面加载完成后初始化
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            initBridge();
            setupSongChangeListener();
        });
    } else {
        initBridge();
        setupSongChangeListener();
    }

    // 也监听 window.onload 以确保所有资源加载完成
    window.addEventListener('load', () => {
        if (!BridgeState.isReady) {
            initBridge();
        }
        setupSongChangeListener();
    });

    /**
     * 保存播放列表到 Android 本地存储
     * 替代原生的下载 JSON 文件功能
     */
    window.savePlaylistToAndroid = async function() {
        const bridgeReady = await waitForBridge();
        
        if (!bridgeReady) {
            console.error('[AndroidBridge] AndroidBridge 不可用');
            // 非 Android 环境，使用原生的下载功能
            if (typeof savePlaylistToJSON === 'function') {
                savePlaylistToJSON();
            }
            return false;
        }

        // Android 环境：通过 Bridge 保存
        try {
            // 获取当前播放列表数据
            const playlistData = {
                songs: songIds.map(id => {
                    const song = window.playlistData ? window.playlistData.find(s => s.id === id) : null;
                    // 关键修复：找不到歌曲时不保存空数据，跳过这首歌
                    if (!song) {
                        console.warn('[AndroidBridge] 保存播放列表时找不到歌曲 ID:', id, '，跳过');
                        return null;
                    }
                    return { id: song.id, name: song.name || '', artist: song.artist || '', pic: song.pic || '' };
                }).filter(s => s !== null)  // 过滤掉 null 项
            };
            
            const jsonStr = JSON.stringify(playlistData);
            console.log('[AndroidBridge] 正在保存播放列表，共 ' + playlistData.songs.length + ' 首');
            const result = AndroidBridge.savePlaylist(jsonStr);
            console.log('[AndroidBridge] 播放列表保存结果:', result);
            return result;
        } catch (e) {
            console.error('[AndroidBridge] 保存播放列表失败:', e);
            return false;
        }
    };

    /**
     * 等待 AndroidBridge 就绪
     * @param {number} timeout - 最大等待时间(毫秒)
     * @returns {Promise<boolean>}
     */
    function waitForBridge(timeout = 3000) {
        return new Promise((resolve) => {
            if (typeof AndroidBridge !== 'undefined') {
                resolve(true);
                return;
            }

            const startTime = Date.now();
            const checkInterval = setInterval(() => {
                if (typeof AndroidBridge !== 'undefined') {
                    clearInterval(checkInterval);
                    resolve(true);
                } else if (Date.now() - startTime > timeout) {
                    clearInterval(checkInterval);
                    console.log('[AndroidBridge] 等待超时，AndroidBridge 不可用');
                    resolve(false);
                }
            }, 50);
        });
    }

    /**
     * 添加单首歌曲到 Android 本地存储
     */
    window.addSongToAndroid = async function(song) {
        console.log('[AndroidBridge] addSongToAndroid 被调用');
        
        // 等待 Bridge 就绪
        const bridgeReady = await waitForBridge();
        
        if (!bridgeReady) {
            console.error('[AndroidBridge] AndroidBridge 不可用');
            return false;
        }

        try {
            const songJson = JSON.stringify(song);
            console.log('[AndroidBridge] 正在添加歌曲:', songJson);
            const result = AndroidBridge.addSong(songJson);
            console.log('[AndroidBridge] 添加歌曲结果:', result);
            return result;
        } catch (e) {
            console.error('[AndroidBridge] 添加歌曲失败:', e);
            return false;
        }
    };

    /**
     * 从 Android 本地存储删除歌曲
     */
    window.removeSongFromAndroid = async function(songId) {
        const bridgeReady = await waitForBridge();
        
        if (!bridgeReady) {
            console.error('[AndroidBridge] AndroidBridge 不可用');
            return false;
        }

        try {
            const result = AndroidBridge.removeSong(String(songId));
            console.log('[AndroidBridge] 删除歌曲结果:', result);
            return result;
        } catch (e) {
            console.error('[AndroidBridge] 删除歌曲失败:', e);
            return false;
        }
    };

    /**
     * 从 Android 加载播放列表
     */
    window.loadPlaylistFromAndroid = async function() {
        const bridgeReady = await waitForBridge();
        
        if (!bridgeReady) {
            console.error('[AndroidBridge] AndroidBridge 不可用');
            return null;
        }

        try {
            const playlistJson = AndroidBridge.loadPlaylist();
            console.log('[AndroidBridge] 已加载播放列表');
            return playlistJson;
        } catch (e) {
            console.error('[AndroidBridge] 加载播放列表失败:', e);
            return null;
        }
    };

    /**
     * 从 Android 接收重新加载播放列表的命令
     */
    window.reloadPlaylistFromAndroid = function(playlistData) {
        console.log('[AndroidBridge] 收到重新加载播放列表命令');
        
        // 更新本地播放列表数据
        if (typeof window.playlistJsonData !== 'undefined') {
            window.playlistJsonData = playlistData;
        }
        
        // 如果有重新加载函数，调用它
        if (typeof window.loadPlaylistFromDefault === 'function') {
            window.loadPlaylistFromDefault().then(() => {
                console.log('[AndroidBridge] 播放列表已重新加载');
            });
        }
    };

    // 重写原有的 savePlaylistToJSON 函数
    const originalSavePlaylistToJSON = typeof savePlaylistToJSON === 'function' ? savePlaylistToJSON : null;
    
    window.savePlaylistToJSON = function() {
        if (BridgeState.isAndroid) {
            // Android 环境下使用 Bridge 保存
            window.savePlaylistToAndroid().then(success => {
                if (success) {
                    console.log('[AndroidBridge] 播放列表已保存到本地');
                } else {
                    console.error('[AndroidBridge] 保存播放列表失败');
                }
            });
        } else if (originalSavePlaylistToJSON) {
            // 非 Android 环境使用原生的下载功能
            originalSavePlaylistToJSON();
        }
    };

    console.log('[AndroidBridge] Bridge 模块已加载');
})();
