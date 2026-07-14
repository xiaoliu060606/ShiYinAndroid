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

    // 初始化桥接通信：检测Android环境并建立连接
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

    // 检查桥接是否可用（Android环境是否就绪）
    function isBridgeAvailable() {
        return typeof AndroidBridge !== 'undefined';
    }

    // 发送播放状态到原生端（是否播放中、当前位置、总时长）
    function sendPlayState(state) {
        if (!isBridgeAvailable()) return;
        
        const stateData = {
            isPlaying: state.isPlaying || false,
            position: state.position || 0,
            duration: state.duration || 0
        };
        
        try {
            console.log('[AndroidBridge] [同步] sendPlayState: isPlaying=' + stateData.isPlaying + ', pos=' + stateData.position + ', dur=' + stateData.duration);
            AndroidBridge.onPlayStateChanged(JSON.stringify(stateData));
        } catch (e) {
            console.error('[AndroidBridge] 发送播放状态失败:', e);
        }
    }

    // 统一同步H5状态到原生端（可同时推送播放状态、进度、歌曲信息）
    function syncToNative(playState, progress, songInfo) {
        if (!isBridgeAvailable()) return;
        
        if (playState !== undefined) {
            sendPlayState(playState);
        }
        
        if (progress !== undefined) {
            sendProgress(progress.position || 0, progress.duration || 0);
        }
        
        if (songInfo !== undefined) {
            sendSongInfo(songInfo);
        }
    }

    // 发送歌曲信息到原生端（歌名、歌手、封面、时长）
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
            console.log('[AndroidBridge] [同步] sendSongInfo: id=' + infoData.id + ', title=' + infoData.title + ', artist=' + infoData.artist);
            AndroidBridge.onSongInfoChanged(JSON.stringify(infoData));
        } catch (e) {
            console.error('[AndroidBridge] 发送歌曲信息失败:', e);
        }
    }

    // 发送歌词数据到原生端（用于悬浮窗后台同步）
    function sendLyricData(lyricsArray) {
        if (!isBridgeAvailable()) return;
        if (!Array.isArray(lyricsArray)) return;

        try {
            AndroidBridge.sendLyricData(JSON.stringify(lyricsArray));
        } catch (e) {
            console.error('[AndroidBridge] 发送歌词数据失败:', e);
        }
    }

    // 发送播放进度到原生端（当前位置和总时长，单位毫秒）
    function sendProgress(currentTime, duration) {
        if (typeof AndroidBridge === 'undefined') return;

        try {
            AndroidBridge.onProgressChanged(currentTime, duration);
        } catch (e) {
            console.error('[AndroidBridge] 发送进度失败:', e);
        }
    }

    // 处理原生命令分发（通知栏/蓝牙/线控按钮命令由此进入）
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

    // 处理播放命令：当前未播放时触发播放
    function handlePlayCommand() {
        console.log('[AndroidBridge] [命令] play: isPlaying=' + isPlaying);
        if (typeof handlePlayPause === 'function') {
            if (!isPlaying) {
                handlePlayPause();
            }
        }
    }

    // 处理暂停命令：当前播放中时触发暂停
    function handlePauseCommand() {
        console.log('[AndroidBridge] [命令] pause: isPlaying=' + isPlaying);
        if (typeof handlePlayPause === 'function') {
            if (isPlaying) {
                handlePlayPause();
            }
        }
    }

    // 处理切换命令：直接切换播放/暂停状态
    function handleToggleCommand() {
        if (typeof handlePlayPause === 'function') {
            handlePlayPause();
        }
    }

    // 处理上一首命令
    function handlePreviousCommand() {
        console.log('[AndroidBridge] [命令] previous');
        // 关键修复：随机模式下直接走H5路径，因为H5有正确的随机队列顺序
        // 原生GeQuQieHuanManager只有顺序列表，随机模式会选错歌
        var currentPlayMode = (typeof playMode !== 'undefined') ? playMode : 0;
        if (currentPlayMode !== 2 && typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.yuanShengQieGe === 'function') {
            try {
                AndroidBridge.yuanShengQieGe("previous");
                console.log('[AndroidBridge] [命令] previous: 已调用原生切歌');
                return;
            } catch (e) {
                console.error('[AndroidBridge] 原生切歌(上一首)失败:', e);
            }
        }
        console.log('[AndroidBridge] [命令] previous: 走H5路径 (模式=' + currentPlayMode + ')');
        if (typeof playPreviousSong === 'function') {
            playPreviousSong();
        }
    }

    function handleNextCommand() {
        console.log('[AndroidBridge] [命令] next');
        // 关键修复：随机模式下直接走H5路径
        var currentPlayMode = (typeof playMode !== 'undefined') ? playMode : 0;
        if (currentPlayMode !== 2 && typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.yuanShengQieGe === 'function') {
            try {
                AndroidBridge.yuanShengQieGe("next");
                console.log('[AndroidBridge] [命令] next: 已调用原生切歌');
                return;
            } catch (e) {
                console.error('[AndroidBridge] 原生切歌(下一首)失败:', e);
            }
        }
        console.log('[AndroidBridge] [命令] next: 走H5路径 (模式=' + currentPlayMode + ')');
        if (typeof playNextSong === 'function') {
            playNextSong();
        }
    }

    // 处理停止命令：当前播放中时触发暂停
    function handleStopCommand() {
        if (typeof handlePlayPause === 'function') {
            if (isPlaying) {
                handlePlayPause();
            }
        }
    }

    // 处理跳转命令：跳转到指定播放位置（毫秒）
    function handleSeekCommand(position) {
        if (typeof AndroidBridge !== 'undefined') {
            AndroidBridge.nativeSeekTo(position);
        }
        if (typeof updateProgress === 'function') {
            updateProgress();
        }
    }

    // 发送初始状态到原生端（桥接初始化后同步当前播放状态）
    function sendInitialState() {
        const currentSong = getCurrentSongInfo();
        if (currentSong) {
            sendSongInfo(currentSong);
        }
        
        sendPlayState({
            isPlaying: isPlaying || false,
            position: currentProgress || 0,
            duration: totalDuration || 0
        });
    }

    // 获取当前歌曲信息（从播放列表或界面元素中提取）
    function getCurrentSongInfo() {
        const songId = typeof getCurrentSongId === 'function' ? getCurrentSongId() : '';
        const currentSong = typeof playlistData !== 'undefined' && playlistData ? playlistData.find(s => s.id === songId) : null;
        
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
        if (originalPlayPreviousSong) {
            originalPlayPreviousSong();
        }
    };

    window.playNextSong = function() {
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
            originalLoadSong.apply(this, args);
            
            setTimeout(() => {
                const info = getCurrentSongInfo();
                sendSongInfo(info);
            }, 1000);
        };
    }

    window.handleNativeCommand = handleNativeCommand;
    window.AndroidBridgeReady = initBridge;
    window.syncToNative = syncToNative;
    window.sendLyricData = sendLyricData;
    window.qingLiGeCi = function() {
        if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.qingLiGeCi === 'function') {
            try { AndroidBridge.qingLiGeCi(); } catch(e) { console.error('[AndroidBridge] 清空歌词失败:', e); }
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            initBridge();
        });
    } else {
        initBridge();
    }

    window.addEventListener('load', () => {
        if (!BridgeState.isReady) {
            initBridge();
        }
    });

    // 保存播放列表到Android原生存储
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
                    // 规范化封面URL，防止双斜杠污染持久化存储
                    var normalizedPic = typeof guiFanHuaFengMianUrl === 'function'
                        ? guiFanHuaFengMianUrl(song.pic || '')
                        : (song.pic || '').replace(/(?<!:)\/\/+/g, '/');
                    return { id: song.id, name: song.name || '', artist: song.artist || '', pic: normalizedPic, source: song.source || 'wy' };
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

    // 等待桥接就绪（轮询检测AndroidBridge，超时返回false）
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

    // 添加单首歌曲到Android原生存储
    window.addSongToAndroid = async function(song) {
        console.log('[AndroidBridge] addSongToAndroid 被调用');
        
        const bridgeReady = await waitForBridge();
        
        if (!bridgeReady) {
            console.error('[AndroidBridge] AndroidBridge 不可用');
            return false;
        }

        try {
            // 规范化封面URL后再发送到原生存储
            var songToSave = {
                id: song.id,
                name: song.name,
                artist: song.artist,
                pic: typeof guiFanHuaFengMianUrl === 'function'
                    ? guiFanHuaFengMianUrl(song.pic || '')
                    : (song.pic || ''),
                source: song.source || 'wy'
            };
            const songJson = JSON.stringify(songToSave);
            console.log('[AndroidBridge] 正在添加歌曲:', songJson);
            const result = AndroidBridge.addSong(songJson);
            console.log('[AndroidBridge] 添加歌曲结果:', result);
            return result;
        } catch (e) {
            console.error('[AndroidBridge] 添加歌曲失败:', e);
            return false;
        }
    };

    // 从Android原生存储删除指定歌曲
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

    // 从Android原生存储加载播放列表JSON
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
        console.log('[AndroidBridge] 收到Native推送的完整播放列表，共', playlistData.songs ? playlistData.songs.length : 0, '首');

        if (typeof window.playlistJsonData !== 'undefined') {
            window.playlistJsonData = playlistData;
        }

        var songs = playlistData.songs || [];
        if (typeof window.songIds !== 'undefined') {
            window.songIds = songs.map(function(s) { return s.id; });
        }
        if (typeof window.playlistData !== 'undefined') {
            var defaultCover = typeof window.defaultCover !== 'undefined' ? window.defaultCover : '';
            window.playlistData = songs.map(function(song, index) {
                // 加载时也规范化封面URL，修复可能存在的历史脏数据
                var normalizedPic = typeof guiFanHuaFengMianUrl === 'function'
                    ? guiFanHuaFengMianUrl(song.pic || '')
                    : (song.pic || defaultCover);
                return {
                    id: song.id,
                    index: index,
                    name: song.name || '未知歌曲',
                    artist: song.artist || '未知歌手',
                    pic: normalizedPic,
                    source: song.source || 'wy'
                };
            });
        }

        if (typeof window.isPlaylistLoaded !== 'undefined') {
            window.isPlaylistLoaded = true;
        }

        if (typeof window.renderPlaylist === 'function') {
            window.renderPlaylist();
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

    // ==================== 全局异常处理 H5 端 ====================

    // 定期保存播放状态到原生端（每10秒）
    var zhuangTaiBaoCunDingShiQi = null;

    function qiDongZhuangTaiBaoCun() {
        if (zhuangTaiBaoCunDingShiQi) return;
        zhuangTaiBaoCunDingShiQi = setInterval(function() {
            baoCunDangQianZhuangTai();
        }, 10000);
    }

    function tingZhiZhuangTaiBaoCun() {
        if (zhuangTaiBaoCunDingShiQi) {
            clearInterval(zhuangTaiBaoCunDingShiQi);
            zhuangTaiBaoCunDingShiQi = null;
        }
    }

    // 保存当前播放状态到原生端
    function baoCunDangQianZhuangTai() {
        if (!isBridgeAvailable()) return;
        try {
            var geQuId = typeof currentPlayingSongId !== 'undefined' ? currentPlayingSongId : '';
            var geQuMing = '';
            var geShou = '';
            if (typeof songTitle !== 'undefined' && songTitle && songTitle.textContent) {
                geQuMing = songTitle.textContent;
            } else if (typeof playlistData !== 'undefined' && playlistData && geQuId) {
                var found = playlistData.find(function(s) { return String(s.id) === String(geQuId); });
                if (found) geQuMing = found.name || '';
            }
            if (typeof songArtist !== 'undefined' && songArtist && songArtist.textContent) {
                geShou = songArtist.textContent;
            } else if (typeof playlistData !== 'undefined' && playlistData && geQuId) {
                var found2 = playlistData.find(function(s) { return String(s.id) === String(geQuId); });
                if (found2) geShou = found2.artist || '';
            }
            var fengMian = typeof currentCoverImage !== 'undefined' ? currentCoverImage : '';
            var dangQianWeiZhi = typeof currentProgress !== 'undefined' ? currentProgress : 0;
            var zongShiChang = typeof totalDuration !== 'undefined' ? totalDuration : 0;
            var shiFouBoFangZhong = typeof isPlaying !== 'undefined' ? isPlaying : false;
            AndroidBridge.baoCunBoFangZhuangTai(
                geQuId, geQuMing, geShou, fengMian,
                dangQianWeiZhi, zongShiChang, shiFouBoFangZhong
            );
        } catch (e) {
            console.error('[AndroidBridge] 保存播放状态失败:', e);
        }
    }

    // 网络断开回调（由原生端调用）
    window.onWangLuoDuanKai = function() {
        console.warn('[AndroidBridge] 网络已断开');
        if (typeof showNetworkStatus === 'function') {
            showNetworkStatus(false);
        }
    };

    // 网络恢复回调（由原生端调用）
    window.onWangLuoHuiFu = function() {
        console.log('[AndroidBridge] 网络已恢复');
        if (typeof showNetworkStatus === 'function') {
            showNetworkStatus(true);
        }
        // 网络恢复后，仅在加载中（非用户主动暂停）时尝试重新加载
        if (typeof isLoading !== 'undefined' && isLoading && typeof isPlaying !== 'undefined' && isPlaying) {
            console.log('[AndroidBridge] 网络恢复，尝试重新加载当前歌曲');
            if (typeof loadAndPlaySong === 'function' && typeof currentIndex !== 'undefined') {
                loadAndPlaySong(currentIndex, 0, typeof currentProgress !== 'undefined' ? currentProgress : 0);
            }
        }
    };

    // 播放器无法恢复回调（由原生端调用）
    window.onBoFangQiWuFaHuiFu = function(cuoWuXinXi) {
        console.error('[AndroidBridge] 播放器无法恢复:', cuoWuXinXi);
        if (typeof showToast === 'function') {
            showToast('播放器出错，请尝试重新播放');
        }
    };

    // 恢复播放状态回调（由原生端调用，Service被杀后恢复）
    window.onHuiFuBoFangZhuangTai = function(zhuangTai) {
        console.log('[AndroidBridge] 收到恢复播放状态:', JSON.stringify(zhuangTai));
        if (!zhuangTai || !zhuangTai.geQuId) return;

        if (typeof songIds !== 'undefined' && songIds && Array.isArray(songIds)) {
            var index = songIds.indexOf(String(zhuangTai.geQuId));
            if (index >= 0 && typeof loadAndPlaySong === 'function') {
                console.log('[AndroidBridge] 恢复播放: 索引=' + index + ', 位置=' + zhuangTai.dangQianWeiZhi + 'ms');
                loadAndPlaySong(index, 0, zhuangTai.dangQianWeiZhi);
                if (isBridgeAvailable()) {
                    try { AndroidBridge.tongZhiBoFangQiHuiFuChengGong(); } catch(e) {}
                }
            } else {
                console.warn('[AndroidBridge] 恢复失败：歌曲已不在播放列表中 ' + zhuangTai.geQuId);
                if (typeof showToast === 'function') {
                    showToast('歌曲已从播放列表中移除', 'warning', 3000);
                }
            }
        }
    };

    // 页面加载完成后启动状态保存定时器
    window.addEventListener('load', function() {
        qiDongZhuangTaiBaoCun();
    });

    // 页面卸载前保存状态
    window.addEventListener('beforeunload', function() {
        baoCunDangQianZhuangTai();
    });

    console.log('[AndroidBridge] Bridge 模块已加载');
})();
