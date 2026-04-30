/**
 * Android WebView Bridge 通信模块
 * 用于 H5 与 Android 原生 MediaSession 通信
 */

(function() {
    'use strict';

    console.log('[AndroidBridge] 脚本开始加载');
    console.log('[AndroidBridge] AndroidBridge 是否存在:', typeof AndroidBridge !== 'undefined');
    if (typeof AndroidBridge !== 'undefined') {
        console.log('[AndroidBridge] AndroidBridge 方法:', Object.keys(AndroidBridge));
    }

    const BridgeState = {
        isReady: false,
        isAndroid: false,
        callbacks: {},
        retryCount: 0,
        maxRetries: 10
    };

    function initBridge() {
        BridgeState.isAndroid = typeof AndroidBridge !== 'undefined';
        
        console.log('[AndroidBridge] initBridge 检查: AndroidBridge 存在 =', BridgeState.isAndroid);
        
        if (BridgeState.isAndroid) {
            console.log('[AndroidBridge] 检测到 Android 环境，Bridge 已就绪');
            BridgeState.isReady = true;
            
            try {
                AndroidBridge.isNativeReady();
            } catch (e) {
                console.error('[AndroidBridge] Bridge 初始化失败:', e);
            }
            
            sendInitialState();
        } else {
            BridgeState.retryCount++;
            if (BridgeState.retryCount <= BridgeState.maxRetries) {
                console.log(`[AndroidBridge] 未检测到 Android 环境，重试 ${BridgeState.retryCount}/${BridgeState.maxRetries}`);
                setTimeout(initBridge, 100);
            } else {
                console.log('[AndroidBridge] 非 Android 环境，Bridge 未激活');
            }
        }
    }

    function isBridgeAvailable() {
        return typeof AndroidBridge !== 'undefined';
    }

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

    function sendProgress(currentTime, duration) {
        if (typeof AndroidBridge === 'undefined') return;

        try {
            AndroidBridge.onProgressChanged(currentTime, duration);
        } catch (e) {
            console.error('[AndroidBridge] 发送进度失败:', e);
        }
    }

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

    function handlePlayCommand() {
        if (typeof handlePlayPause === 'function' && player.paused) {
            handlePlayPause();
        }
    }

    function handlePauseCommand() {
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
        if (typeof handlePlayPause === 'function' && !player.paused) {
            handlePlayPause();
        }
    }

    function handleSeekCommand(position) {
        if (typeof AndroidBridge !== 'undefined') {
            AndroidBridge.nativeSeekTo(position);
        }
        if (player && typeof position === 'number') {
            player.currentTime = position / 1000;
            updateProgress();
        }
    }

    function sendInitialState() {
        const currentSong = getCurrentSongInfo();
        if (currentSong) {
            sendSongInfo(currentSong);
        }
        
        sendPlayState({
            isPlaying: player ? !player.paused : false,
            position: currentProgress || 0,
            duration: totalDuration || 0
        });
    }

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

    const originalHandlePlayPause = typeof handlePlayPause === 'function' ? handlePlayPause : null;
    const originalPlayPreviousSong = typeof playPreviousSong === 'function' ? playPreviousSong : null;
    const originalPlayNextSong = typeof playNextSong === 'function' ? playNextSong : null;
    const originalLoadSong = typeof loadSong === 'function' ? loadSong : null;
    const originalUpdateProgress = typeof updateProgress === 'function' ? updateProgress : null;

    window.handlePlayPause = function() {
        if (originalHandlePlayPause) {
            originalHandlePlayPause();
        }
    };

    window.playPreviousSong = function() {
        if (typeof AndroidBridge !== 'undefined') {
            AndroidBridge.nativeRelease();
        }
        
        if (originalPlayPreviousSong) {
            originalPlayPreviousSong();
        }
    };

    window.playNextSong = function() {
        if (typeof AndroidBridge !== 'undefined') {
            AndroidBridge.nativeRelease();
        }
        
        if (originalPlayNextSong) {
            originalPlayNextSong();
        }
    };

    window.updateProgress = function() {
        if (originalUpdateProgress) {
            originalUpdateProgress();
        }
        
        const now = Date.now();
        if (!window._lastProgressSend || now - window._lastProgressSend > 1000) {
            window._lastProgressSend = now;
            sendProgress(currentProgress || 0, totalDuration || 0);
        }
    };

    if (originalLoadSong) {
        window.loadSong = function(...args) {
            if (typeof AndroidBridge !== 'undefined') {
                AndroidBridge.nativeRelease();
            }
            
            originalLoadSong.apply(this, args);
            
            setTimeout(() => {
                const info = getCurrentSongInfo();
                sendSongInfo(info);
            }, 1000);
        };
    }

    function setupSongChangeListener() {
        if (player) {
            player.addEventListener('loadedmetadata', () => {
                const info = getCurrentSongInfo();
                sendSongInfo(info);
            });
            
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
            
            player.addEventListener('ended', () => {
                sendPlayState({
                    isPlaying: false,
                    position: totalDuration || 0,
                    duration: totalDuration || 0
                });
            });
        }
    }

    window.handleNativeCommand = handleNativeCommand;
    window.AndroidBridgeReady = initBridge;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            initBridge();
            setupSongChangeListener();
        });
    } else {
        initBridge();
        setupSongChangeListener();
    }

    window.addEventListener('load', () => {
        if (!BridgeState.isReady) {
            initBridge();
        }
        setupSongChangeListener();
    });

    window.savePlaylistToAndroid = async function() {
        const bridgeReady = await waitForBridge();
        
        if (!bridgeReady) {
            console.error('[AndroidBridge] AndroidBridge 不可用');
            if (typeof savePlaylistToJSON === 'function') {
                savePlaylistToJSON();
            }
            return false;
        }

        try {
            const playlistData = {
                songs: songIds.map(id => {
                    const song = window.playlistData ? window.playlistData.find(s => s.id === id) : null;
                    if (!song) {
                        console.warn('[AndroidBridge] 保存播放列表时找不到歌曲 ID:', id, '，跳过');
                        return null;
                    }
                    return { id: song.id, name: song.name || '', artist: song.artist || '', pic: song.pic || '' };
                }).filter(s => s !== null)
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

    window.addSongToAndroid = async function(song) {
        console.log('[AndroidBridge] addSongToAndroid 被调用');
        
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

    window.reloadPlaylistFromAndroid = function(playlistData) {
        console.log('[AndroidBridge] 收到重新加载播放列表命令');
        
        if (typeof window.playlistJsonData !== 'undefined') {
            window.playlistJsonData = playlistData;
        }
        
        if (typeof window.loadPlaylistFromDefault === 'function') {
            window.loadPlaylistFromDefault().then(() => {
                console.log('[AndroidBridge] 播放列表已重新加载');
            });
        }
    };

    const originalSavePlaylistToJSON = typeof savePlaylistToJSON === 'function' ? savePlaylistToJSON : null;
    
    window.savePlaylistToJSON = function() {
        if (BridgeState.isAndroid) {
            window.savePlaylistToAndroid().then(success => {
                if (success) {
                    console.log('[AndroidBridge] 播放列表已保存到本地');
                } else {
                    console.error('[AndroidBridge] 保存播放列表失败');
                }
            });
        } else if (originalSavePlaylistToJSON) {
            originalSavePlaylistToJSON();
        }
    };

    console.log('[AndroidBridge] Bridge 模块已加载');
})();
