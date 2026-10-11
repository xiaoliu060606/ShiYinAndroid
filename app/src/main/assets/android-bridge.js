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
        console.log('[sendLyricData] 发送歌词数据, 条数=' + (Array.isArray(lyricsArray) ? lyricsArray.length : '非数组') + ', lyricsEnabled=' + (typeof lyricsEnabled !== 'undefined' ? lyricsEnabled : 'undefined') + ', xuanFuGeCiYiKaiQi=' + (typeof xuanFuGeCiYiKaiQi !== 'undefined' ? xuanFuGeCiYiKaiQi : 'undefined'));
        if (!isBridgeAvailable()) {
            console.log('[sendLyricData] Bridge 不可用, 取消发送');
            return;
        }
        if (!Array.isArray(lyricsArray)) {
            console.log('[sendLyricData] 歌词数据不是数组, 取消发送');
            return;
        }

        try {
            AndroidBridge.sendLyricData(JSON.stringify(lyricsArray));
            console.log('[sendLyricData] 歌词数据已发送到原生');
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

    // 历史遗留说明（曾有的 window.* 包装器已删除）：
    // 本脚本在 <script src="android-bridge.js"> 处最先加载，此时拾音.html 内联脚本里的
    // handlePlayPause/playPreviousSong/playNextSong/updateProgress 等正式函数声明尚未执行，
    // 原代码在此捕获的 original* 全是 null，随后内联脚本的函数声明又直接覆盖 window.*，
    // 包装器从未生效过（连带其中的节流 sendProgress 也从不执行）。
    // 进度推送无需前端主动发起：NativeAudioPlayer 每秒回调 onProgressChanged，
    // 原生直接更新 MediaSession/通知栏，并经 onNativeProgressChanged 回推 H5 更新 currentProgress。
    // 原生命令分发 handleNativeCommand 由拾音.html 内联脚本定义（全局函数声明），
    // 原生通过 window.handleNativeCommand 调用的是 HTML 版本，此处不再重复定义。

    window.AndroidBridgeReady = initBridge;
    window.syncToNative = syncToNative;
    window.sendLyricData = sendLyricData;
    // 暴露停止函数：本模块内的10秒状态保存定时器需纳入页面卸载时的统一清理（防内存泄漏）
    window.tingZhiZhuangTaiBaoCun = tingZhiZhuangTaiBaoCun;
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
                    var songSrc = song.source || 'wy';
                    return { id: song.id, name: song.name || '', artist: song.artist || '', pic: normalizedPic, source: songSrc, qqIndex: song.qqIndex || 1 };
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
                source: song.source || 'wy',
                qqIndex: song.qqIndex || 1
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

            // 构建 localSourceMap：原生推送的数据中 source 缺失时，用 localStorage 和当前内存兜底
            // 防止 QQ 音乐标识在原生推送恢复时被默认 'wy' 覆盖
            var localSourceMap = {};
            try {
                var localJson = localStorage.getItem('playlistJson');
                if (localJson) {
                    var localData = JSON.parse(localJson);
                    if (localData.songs && Array.isArray(localData.songs)) {
                        localData.songs.forEach(function(s) {
                            if (s.source) {
                                localSourceMap[String(s.id)] = s.source;
                            }
                        });
                    }
                }
            } catch(e) {
                // 本地播放列表解析失败时仅记录，source 兜底逻辑继续执行
                console.warn('[AndroidBridge] 解析本地播放列表JSON失败:', e);
            }
            // 当前内存中的 playlistData 也作为兜底源
            if (window.playlistData && Array.isArray(window.playlistData)) {
                window.playlistData.forEach(function(s) {
                    if (s.source) {
                        localSourceMap[String(s.id)] = s.source;
                    }
                });
            }

            window.playlistData = songs.map(function(song, index) {
                // 加载时也规范化封面URL，修复可能存在的历史脏数据
                var normalizedPic = typeof guiFanHuaFengMianUrl === 'function'
                    ? guiFanHuaFengMianUrl(song.pic || '')
                    : (song.pic || defaultCover);
                var finalSource = song.source || localSourceMap[String(song.id)] || 'wy';
                if (finalSource !== (song.source || 'wy')) {
                    console.log('[AndroidBridge] source兜底修正: id=' + song.id + ', 原生=' + (song.source || '无') + ', 修正=' + finalSource);
                }
                // 同步持久化 source 映射表
                if (typeof playlistSourceMap !== 'undefined') {
                    playlistSourceMap[String(song.id)] = finalSource;
                }
                return {
                    id: song.id,
                    index: index,
                    name: song.name || '未知歌曲',
                    artist: song.artist || '未知歌手',
                    pic: normalizedPic,
                    source: finalSource,
                    qqIndex: song.qqIndex || 1
                };
            });

            // 打印 source 分布，便于排查
            var sourceCount = {};
            window.playlistData.forEach(function(s) {
                sourceCount[s.source] = (sourceCount[s.source] || 0) + 1;
            });
            console.log('[AndroidBridge] source分布:', JSON.stringify(sourceCount));
        }

        if (typeof window.isPlaylistLoaded !== 'undefined') {
            window.isPlaylistLoaded = true;
        }

        if (typeof window.renderPlaylist === 'function') {
            window.renderPlaylist();
        }
    };

    // 说明：此处原有的 window.savePlaylistToJSON 包装器已删除（与已删除的 window.* 包装器同源问题）：
    // 本脚本最先加载时 savePlaylistToJSON 尚未定义，originalSavePlaylistToJSON 捕获为 null；
    // 且 bofangliebiao.js 中的全局 async function savePlaylistToJSON 后加载会直接覆盖 window.*，
    // 该包装器从未生效。现行生效版本在 bofangliebiao.js（内部已走 window.savePlaylistToAndroid）。

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

    // 以下原生→H5 回调勿在此处定义：onWangLuoDuanKai / onWangLuoHuiFu /
    // onBoFangQiWuFaHuiFu / onHuiFuBoFangZhuangTai 的生效版本在拾音.html 内联脚本中
    // （后加载的函数声明会覆盖此处 window.* 赋值，本文件版本是死码，已删除）。
    // 注意：网络恢复重载逻辑经评估未合并——加载中（isLoading）失败时 handleApiError
    // 已将 isLoading 置false并走缓存/切API/提示路径，恢复时再重载会造成重复加载。

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
