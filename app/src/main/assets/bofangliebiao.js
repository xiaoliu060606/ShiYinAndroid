/**
 * 播放列表管理模块
 * 包含播放列表加载、保存、渲染、搜索、导入、音源设置等功能
 */

// 搜索结果缓存
var searchResults = [];
var searchCancelToken = null;
var lastSearchKeyword = '';
var searchDebounceTimer = null;
var currentSearchTab = 'wy'; // 'wy' = 网易云, 'qq' = QQ音乐

// QQ音乐搜索结果缓存（独立于网易云）
var qqSearchResults = [];
var qqSearchKeyword = '';

// 搜索历史常量
var SOU_SUO_LI_SHI_KEY = 'shiyin_search_history';
var SOU_SUO_LI_SHI_MAX = 15;

/**
 * 规范化封面URL：去除双斜杠、升级 HTTP→HTTPS（解决CSP拦截）
 * CSP 只允许 HTTPS 图片源，而搜索 API 返回的封面是 HTTP URL，
 * 不升级的话 switchAlbumImage 加载图片时会被 CSP 拦截导致封面不显示。
 * @param {string} url 原始URL
 * @returns {string} 规范化后的URL
 */
function guiFanHuaFengMianUrl(url) {
    if (!url || typeof url !== 'string') return '';
    // 0. 升级 HTTP 到 HTTPS，绕过 CSP img-src 限制
    if (url.indexOf('http://') === 0) {
        url = 'https://' + url.substring(7);
    }
    // 0.5 协议相对URL（//example.com/path）升级为 HTTPS
    if (url.indexOf('//') === 0) {
        url = 'https:' + url;
    }
    // 1. 去除 https:// 之后路径中的双斜杠（保留协议部分的 ://
    var idx = url.indexOf('://');
    if (idx > 0) {
        var protocol = url.substring(0, idx + 3); // 如 "https://"
        var rest = url.substring(idx + 3);
        // 将连续多个 / 替换为单个 /
        rest = rest.replace(/\/{2,}/g, '/');
        url = protocol + rest;
    }
    // 2. 确保是有效的 http/https URL
    if (url.indexOf('http') !== 0) {
        return url;
    }
    return url;
}

// ==================== 播放列表加载与保存 ====================

// 从默认JSON文件加载播放列表
async function loadPlaylistFromDefault() {
    try {
        // Android环境：优先从Native加载最新播放列表（用户可能已添加/删除歌曲）
        if (typeof AndroidBridge !== 'undefined' && AndroidBridge.loadPlaylist) {
            console.log('Android 环境 detected，通过 Bridge 加载播放列表...');
            var playlistJson = AndroidBridge.loadPlaylist();
            if (playlistJson) {
                var data = JSON.parse(playlistJson);
                playlistJsonData = data;

                // 从 localStorage 恢复 source 字段（原生层不存储 source，QQ音乐标识会丢失）
                var localSourceMap = {};
                try {
                    var localJson = localStorage.getItem('playlistJson');
                    if (localJson) {
                        var localData = JSON.parse(localJson);
                        if (localData.songs && Array.isArray(localData.songs)) {
                            localData.songs.forEach(function(s) {
                                localSourceMap[String(s.id)] = s.source || 'wy';
                            });
                        }
                    }
                } catch(e) { /* ignore */ }

                songIds = data.songs.map(function(song) { return song.id; });
                playlistData = data.songs.map(function(song, index) {
                    return {
                        id: song.id,
                        index: index,
                        name: song.name || '未知歌曲',
                        artist: song.artist || '未知歌手',
                        pic: guiFanHuaFengMianUrl(song.pic || defaultCover),
                        source: localSourceMap[String(song.id)] || song.source || 'wy',
                        picTimestamp: song.picTimestamp || Date.now()
                    };
                });
                isPlaylistLoaded = true;
                console.log('从 Android 加载播放列表:', data.songs.length, '首歌曲');
                // 同步播放列表详情到原生切歌管理器
                if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.gengXinBoFangLieBiaoXiangQing === 'function') {
                    try {
                        AndroidBridge.gengXinBoFangLieBiaoXiangQing(JSON.stringify(data.songs));
                        console.log('播放列表详情已同步到原生层');
                    } catch(e) {
                        console.warn('同步播放列表详情到原生层失败:', e);
                    }
                }

                // 初始化 localStorage 缓存（首次启动后立即可用，供 source 字段恢复）
                try {
                    localStorage.setItem('playlistJson', JSON.stringify(data));
                } catch(e) { /* ignore */ }

                return data.songs;
            }
            console.warn('Android Bridge 返回空播放列表，尝试从默认文件加载');
        }

        // 非Android环境：检查内存中是否已有数据
        if (typeof playlistJsonData !== 'undefined' && playlistJsonData && playlistJsonData.songs && playlistJsonData.songs.length > 0 && songIds && songIds.length > 0) {
            console.log('播放列表已在内存中，跳过重复加载，共', songIds.length, '首歌曲');
            isPlaylistLoaded = true;
            return playlistJsonData.songs;
        }

        // 从assets加载默认播放列表
        var response = await fetch('playlist.json');
        if (!response.ok) {
            throw new Error('无法加载播放列表');
        }
        var data = await response.json();
        playlistJsonData = data;
        if (data.songs && Array.isArray(data.songs)) {
            songIds = data.songs.map(function(song) { return song.id; });
            playlistData = data.songs.map(function(song, index) {
                return {
                    id: song.id,
                    index: index,
                    name: song.name || '未知歌曲',
                    artist: song.artist || '未知歌手',
                    pic: song.pic || defaultCover,
                    source: song.source || 'wy',
                    picTimestamp: song.picTimestamp || Date.now()
                };
            });
            isPlaylistLoaded = true;
            console.log('从默认文件加载播放列表:', data.songs.length, '首歌曲');
            return data.songs;
        }
    } catch (error) {
        console.error('加载播放列表失败:', error);
        songIds = [];
    }
    return [];
}

// 保存播放列表（同步到内存数据和Android存储）
async function savePlaylistToJSON() {
    try {
        // 更新内存中的playlistJsonData，规范化封面URL
        playlistJsonData.songs = songIds.map(function(id) {
            var song = playlistData.find(function(s) { return s.id === id; });
            return {
                id: id,
                name: song ? song.name : '',
                artist: song ? song.artist : '',
                pic: song ? guiFanHuaFengMianUrl(song.pic) : defaultCover,
                source: song ? (song.source || 'wy') : 'wy',
                picTimestamp: song ? song.picTimestamp || Date.now() : Date.now()
            };
        });

        // 通过AndroidBridge保存到本地存储
        if (typeof window.savePlaylistToAndroid === 'function') {
            var result = await window.savePlaylistToAndroid();
            if (result) {
                console.log('播放列表已保存到Android存储');
                // 同步播放列表详情到原生切歌管理器
                if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.gengXinBoFangLieBiaoXiangQing === 'function') {
                    try {
                        AndroidBridge.gengXinBoFangLieBiaoXiangQing(JSON.stringify(playlistJsonData.songs));
                    } catch(e) {}
                }
            } else {
                console.warn('播放列表保存到Android存储失败');
            }
            return result;
        }

        console.log('播放列表数据已更新（无Android存储）');
        return true;
    } catch (error) {
        console.error('保存播放列表失败:', error);
        return false;
    }
}

// ==================== 播放列表初始化 ====================

// 初始化播放列表
function initPlaylist() {
    loadPlaylistData();

    // 点击页面其他地方关闭面板
    EventListenerManager.add(document, 'mousedown', function(e) {
        if (playlistPanel.classList.contains('show') && !playlistPanel.contains(e.target) && !playlistToggle.contains(e.target)) {
            closePlaylist();
        }
        if (searchPanel.classList.contains('show') && !searchPanel.contains(e.target) && !searchToggle.contains(e.target)) {
            closeSearchPanel();
        }
        if (settingsPanel.classList.contains('show') && !settingsPanel.contains(e.target) && !settingsToggle.contains(e.target)) {
            closeSettingsPanel();
        }
    });

    EventListenerManager.add(document, 'touchstart', function(e) {
        if (playlistPanel.classList.contains('show') && !playlistPanel.contains(e.target) && !playlistToggle.contains(e.target)) {
            closePlaylist();
        }
        if (searchPanel.classList.contains('show') && !searchPanel.contains(e.target) && !searchToggle.contains(e.target)) {
            closeSearchPanel();
        }
        if (settingsPanel.classList.contains('show') && !settingsPanel.contains(e.target) && !settingsToggle.contains(e.target)) {
            closeSettingsPanel();
        }
    }, { passive: true });

    // 设置项点击事件
    if (typeof exportLogItem !== 'undefined' && exportLogItem) {
        exportLogItem.addEventListener('click', handleExportLog);
    }
    if (typeof clearLogItem !== 'undefined' && clearLogItem) {
        clearLogItem.addEventListener('click', handleClearLog);
    }


}

// 加载播放列表数据
async function loadPlaylistData() {
    if (playlistData && playlistData.length > 0) {
        console.log('从JSON加载播放列表:', playlistData.length, '首歌曲');
        isPlaylistLoaded = true;
        renderPlaylist();

        var currentSongId = typeof getCurrentSongId === 'function' ? getCurrentSongId() : null;
        var currentSong = playlistData.find(function(s) { return s.id === currentSongId; });
        if (currentSong && typeof playlistCurrentTitle !== 'undefined' && playlistCurrentTitle) {
            playlistCurrentTitle.textContent = currentSong.name;
        }
        return;
    }

    console.log('从API加载播放列表...');
    await loadPlaylistFromAPI();
}

// 从API加载完整播放列表
async function loadPlaylistFromAPI() {
    playlistData = [];

    var batchSize = 5;
    for (var i = 0; i < songIds.length; i += batchSize) {
        var batch = songIds.slice(i, i + batchSize);
        var promises = batch.map(async function(songId, index) {
            try {
                var currentSongInfo = playlistData.find(function(s) { return s.id === songId; });
                var songName = currentSongInfo ? currentSongInfo.name : '';
                var apiUrl = typeof getApiUrl === 'function' ? getApiUrl(songId, 'exhigh', songName) : null;

                if (!apiUrl) {
                    console.error('[API] 无法获取歌曲 ' + songId + ' 的API URL');
                    return null;
                }

                var response = await axios.get(apiUrl, { timeout: 8000 });
                var parsedData = typeof parseApiResponse === 'function' ? parseApiResponse(response, songId) : null;
                if (parsedData && parsedData.url) {
                    return {
                        id: songId,
                        index: i + index,
                        name: parsedData.name || '未知歌曲',
                        artist: parsedData.artist || '未知歌手',
                        pic: guiFanHuaFengMianUrl(parsedData.pic || defaultCover),
                        source: 'wy',
                        picTimestamp: Date.now()
                    };
                }
            } catch (error) {
                console.error('加载歌曲 ' + songId + ' 失败:', error);
            }
            return null;
        });

        var results = await Promise.all(promises);
        results.filter(function(r) { return r !== null; }).forEach(function(song) {
            playlistData.push(song);
        });

        if (playlistPanel.classList.contains('show')) {
            renderPlaylist();
        }
    }

    playlistData.sort(function(a, b) { return a.index - b.index; });
    renderPlaylist();
}

// 加载缺失的歌曲
async function loadMissingSongs(missingIds) {
    var batchSize = 5;
    for (var i = 0; i < missingIds.length; i += batchSize) {
        var batch = missingIds.slice(i, i + batchSize);
        var promises = batch.map(async function(songId) {
            try {
                var currentSongInfo = playlistData.find(function(s) { return s.id === songId; });
                var songName = currentSongInfo ? currentSongInfo.name : '';
                var apiUrl = typeof getApiUrl === 'function' ? getApiUrl(songId, 'exhigh', songName) : null;

                if (!apiUrl) {
                    console.error('[API] 无法获取歌曲 ' + songId + ' 的API URL');
                    return null;
                }

                var response = await axios.get(apiUrl, { timeout: 8000 });
                var parsedData = typeof parseApiResponse === 'function' ? parseApiResponse(response, songId) : null;
                if (parsedData && parsedData.url) {
                    var index = songIds.indexOf(songId);
                    var songInfo = {
                        id: songId,
                        index: index,
                        name: parsedData.name || '未知歌曲',
                        artist: parsedData.artist || '未知歌手',
                        pic: guiFanHuaFengMianUrl(parsedData.pic || defaultCover),
                        source: 'wy'
                    };
                    addSongToPlaylistCache(songInfo);
                    return songInfo;
                }
            } catch (error) {
                console.error('加载新增歌曲 ' + songId + ' 失败:', error);
            }
            return null;
        });

        var results = await Promise.all(promises);
        results.filter(function(r) { return r !== null; }).forEach(function(song) {
            playlistData.push(song);
        });

        if (playlistPanel.classList.contains('show')) {
            renderPlaylist();
        }
    }

    playlistData.sort(function(a, b) { return a.index - b.index; });
    savePlaylistToJSON();
}

// ==================== 播放列表渲染 ====================

// 渲染播放列表
function renderPlaylist() {
    if (playlistData.length === 0) {
        playlistContent.innerHTML = '<div style="text-align: center; padding: 60px 40px; color: rgba(255,255,255,0.5); font-size: 14px;">暂无歌曲</div>';
        return;
    }

    // 使用 currentPlayingSongId 作为当前歌曲的唯一标识，避免与 getCurrentSongId() 的索引计算不一致
    var currentSongId = currentPlayingSongId || (typeof getCurrentSongId === 'function' ? getCurrentSongId() : null);

    playlistContent.innerHTML = playlistData.map(function(song, displayIndex) {
        var isActive = String(song.id) === String(currentSongId);
        var isCurrentPlaying = song.id === currentPlayingSongId;
        var isNoCopyright = typeof isSongNoCopyright === 'function' ? isSongNoCopyright(song.id) : false;
        var source = song.source || 'wy';

        var numberOrDelete = isDeleteMode
            ? '<div class="playlist-item-delete" data-index="' + song.index + '" title="删除"><i class="fa-solid fa-trash"></i></div>'
            : '<div class="playlist-item-number ' + (isNoCopyright ? 'no-copyright' : '') + '">' +
              (isNoCopyright ? '无版权' : (isActive ? '<i class="fa-solid fa-volume-high"></i>' : displayIndex + 1)) +
              '</div>';

        return '<div class="playlist-item ' + (isActive ? 'active' : '') + ' ' + (isDeleteMode ? 'delete-mode' : '') + ' ' + (isNoCopyright ? 'no-copyright-item' : '') + '" data-index="' + song.index + '" data-id="' + song.id + '">' +
            numberOrDelete +
            '<span class="playlist-item-source ' + source + '">' + (source === 'qq' ? 'QQ' : 'WY') + '</span>' +
            '<div class="playlist-item-info ' + (isNoCopyright ? 'no-copyright-text' : '') + '">' +
            '<div class="playlist-item-title">' + (typeof escapeHtml === 'function' ? escapeHtml(song.name) : song.name) + '</div>' +
            '<div class="playlist-item-artist">' + (typeof escapeHtml === 'function' ? escapeHtml(song.artist) : song.artist) + '</div>' +
            '</div></div>';
    }).join('');

    // 绑定事件
    if (isDeleteMode) {
        playlistContent.querySelectorAll('.playlist-item-delete').forEach(function(btn) {
            EventListenerManager.add(btn, 'click', function(e) {
                e.stopPropagation();
                var index = parseInt(btn.dataset.index);
                deleteSongFromPlaylist(index);
            });
        });
        playlistContent.querySelectorAll('.playlist-item').forEach(function(item) {
            item.style.cursor = 'default';
        });
    } else {
        playlistContent.querySelectorAll('.playlist-item').forEach(function(item) {
            var touchStartX = 0;
            var touchStartY = 0;
            var isTouchMoving = false;
            var isTouchDevice = false;
            var touchHandled = false;

            EventListenerManager.add(item, 'touchstart', function(e) {
                isTouchDevice = true;
                touchHandled = false;
                touchStartX = e.touches[0].clientX;
                touchStartY = e.touches[0].clientY;
                isTouchMoving = false;
                item.style.transform = 'scale(0.98)';
                item.style.opacity = '0.8';
            }, { passive: true });

            EventListenerManager.add(item, 'touchmove', function(e) {
                var deltaX = Math.abs(e.touches[0].clientX - touchStartX);
                var deltaY = Math.abs(e.touches[0].clientY - touchStartY);
                if (deltaY > 10 || deltaX > 10) {
                    isTouchMoving = true;
                    item.style.transform = '';
                    item.style.opacity = '';
                }
            }, { passive: true });

            EventListenerManager.add(item, 'touchend', function(e) {
                e.stopPropagation();
                item.style.transform = '';
                item.style.opacity = '';
                touchHandled = true;
                if (!isTouchMoving) {
                    var index = parseInt(item.dataset.index);
                    playSongFromPlaylist(index);
                }
                setTimeout(function() {
                    touchHandled = false;
                    isTouchDevice = false;
                }, 100);
            }, { passive: true });

            EventListenerManager.add(item, 'touchcancel', function() {
                item.style.transform = '';
                item.style.opacity = '';
            }, { passive: true });

            EventListenerManager.add(item, 'click', function(e) {
                if (isTouchDevice || touchHandled) {
                    e.stopPropagation();
                    e.preventDefault();
                    return;
                }
                var index = parseInt(item.dataset.index);
                playSongFromPlaylist(index);
            });
        });
    }
}

// ==================== 播放列表操作 ====================

// 添加歌曲到播放列表
async function addToPlaylist(index) {
    var song = searchResults[index];
    if (!song) return;

    if (songIds.includes(song.id)) {
        showToast('该歌曲已在播放列表中', 'warning', 2000);
        return;
    }

    var addBtn = searchContent.querySelector('.search-result-add[data-index="' + index + '"]');
    if (addBtn) {
        addBtn.classList.add('disabled');
        addBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i>';
        addBtn.style.pointerEvents = 'none';
    }

    var artistNames = (song.artists && song.artists.length > 0) ? song.artists.map(function(a) { return a.name; }).join(', ') : '未知歌手';
    // 兼容多种封面字段格式，并规范化URL防止双斜杠污染
    var rawPic = (song.al && song.al.picUrl) || 
                 (song.album && song.album.picUrl) || 
                 (song.album && song.album.blurPicUrl) ||
                 song.pic || 
                 song.picUrl || 
                 song.cover || 
                 defaultCover;
    var songPic = guiFanHuaFengMianUrl(rawPic);
    
    console.log('[添加歌曲] 歌曲封面:', songPic.substring(0, 50), '歌曲:', song.name, 'album字段:', !!song.album, 'al字段:', !!song.al);

    songIds.push(song.id);

    var songInfo = {
        id: song.id,
        index: songIds.length - 1,
        name: song.name,
        artist: artistNames,
        pic: songPic,
        source: 'wy',
        picTimestamp: Date.now()
    };

    playlistData.push(songInfo);
    playlistData.sort(function(a, b) { return a.index - b.index; });

    addSongToPlaylistCache(songInfo);
    renderSearchResults(searchResults);

    // 关键修复：添加歌曲后立即同步播放列表详情到原生切歌管理器
    // 确保原生侧 GeQuQieHuanManager.boFangLieBiao 中的封面URL是最新的
    // 解决搜索添加歌曲→播放列表播放→封面不显示的Bug
    if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.gengXinBoFangLieBiaoXiangQing === 'function') {
        try {
            var songsForNative = playlistJsonData.songs || playlistData.map(function(s) {
                return { id: String(s.id), name: s.name || '', artist: s.artist || '', pic: guiFanHuaFengMianUrl(s.pic || defaultCover) };
            });
            AndroidBridge.gengXinBoFangLieBiaoXiangQing(JSON.stringify(songsForNative));
            console.log('[添加歌曲] 已同步播放列表详情到原生层，共 ' + songsForNative.length + ' 首');
        } catch(e) {
            console.warn('[添加歌曲] 同步播放列表详情到原生层失败:', e);
        }
    }

    if (playlistPanel.classList.contains('show')) {
        renderPlaylist();
    }

    var androidSongData = {
        id: String(song.id),
        name: songInfo.name,
        artist: songInfo.artist,
        pic: songInfo.pic,
        source: songInfo.source || 'wy'
    };

    if (typeof window.addSongToAndroid === 'function') {
        try {
            var saveResult = await window.addSongToAndroid(androidSongData);
            if (saveResult) {
                showToast('已添加 "' + songInfo.name + '" 到播放列表', 'success', 2000);
            } else {
                showToast('已添加到播放列表，但存储失败', 'warning', 2500);
            }
        } catch (e) {
            console.error('[H5] 调用Android存储失败:', e);
            showToast('已添加到播放列表，但存储失败', 'warning', 2500);
        }
    } else {
        showToast('已添加 "' + songInfo.name + '" 到播放列表', 'success', 2000);
    }
}

// 添加单首歌曲到播放列表缓存
function addSongToPlaylistCache(songInfo) {
    try {
        var existingIndex = playlistData.findIndex(function(s) { return s.id === songInfo.id; });
        if (existingIndex >= 0) {
            playlistData[existingIndex] = songInfo;
        } else {
            playlistData.push(songInfo);
        }
        playlistData.sort(function(a, b) { return a.index - b.index; });
        savePlaylistToJSON();
    } catch (error) {
        console.error('添加歌曲失败:', error);
    }
}

// 清理已删除的歌曲信息
function cleanupDeletedSongs() {
    try {
        if (!songIds || songIds.length === 0) {
            console.log('跳过清理: songIds 为空, 播放列表尚未加载');
            return 0;
        }

        var currentIds = new Set(songIds);
        var deletedCount = 0;

        var originalLength = playlistData.length;
        playlistData = playlistData.filter(function(song) {
            if (currentIds.has(song.id)) {
                return true;
            } else {
                deletedCount++;
                return false;
            }
        });

        playlistData.forEach(function(song, i) {
            song.index = i;
        });

        if (deletedCount > 0) {
            savePlaylistToJSON();
            console.log('清理了 ' + deletedCount + ' 首已删除的歌曲');
        }

        return deletedCount;
    } catch (error) {
        console.error('清理已删除歌曲失败:', error);
        return 0;
    }
}

// ==================== 播放列表面板控制 ====================

// 切换播放列表显示
function togglePlaylist() {
    if (playlistPanel.classList.contains('show')) {
        closePlaylist();
    } else {
        openPlaylist();
    }
}

// 打开播放列表
function openPlaylist() {
    playlistPanel.classList.add('show');
    playlistToggle.classList.add('active');

    var isMobile = window.innerWidth <= 768;
    if (isMobile && typeof playlistOverlay !== 'undefined' && playlistOverlay) {
        playlistOverlay.classList.add('show');
    }

    renderPlaylist();
    updatePlaylistCurrentInfo();
    updatePlaylistModeDisplay();

    setTimeout(scrollToCurrentSong, 100);
}

// 关闭播放列表
function closePlaylist() {
    playlistPanel.classList.remove('show');
    playlistToggle.classList.remove('active');

    if (typeof playlistOverlay !== 'undefined' && playlistOverlay) {
        playlistOverlay.classList.remove('show');
    }
}

// 滚动到当前播放的歌曲
function scrollToCurrentSong() {
    var currentItem = playlistContent.querySelector('.playlist-item.active');
    if (currentItem) {
        currentItem.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
}

// 更新播放列表当前播放信息
function updatePlaylistCurrentInfo() {
    var currentSongId = currentPlayingSongId || (typeof getCurrentSongId === 'function' ? getCurrentSongId() : null);
    var currentSong = playlistData.find(function(s) { return s.id === currentSongId; });
    if (currentSong && typeof playlistCurrentTitle !== 'undefined' && playlistCurrentTitle) {
        playlistCurrentTitle.textContent = currentSong.name;
    } else if (typeof songTitle !== 'undefined' && songTitle && typeof playlistCurrentTitle !== 'undefined' && playlistCurrentTitle) {
        playlistCurrentTitle.textContent = songTitle.textContent;
    }
}

// 更新播放列表模式显示
function updatePlaylistModeDisplay() {
    if (typeof playMode === 'undefined') return;
    if (typeof PlayMode === 'undefined') return;

    switch (playMode) {
        case PlayMode.SEQUENTIAL:
            if (typeof playlistModeIcon !== 'undefined' && playlistModeIcon) playlistModeIcon.className = 'fa-solid fa-repeat';
            if (typeof playlistModeText !== 'undefined' && playlistModeText) playlistModeText.textContent = '顺序播放';
            break;
        case PlayMode.SINGLE_LOOP:
            if (typeof playlistModeIcon !== 'undefined' && playlistModeIcon) playlistModeIcon.className = 'fa-solid fa-rotate';
            if (typeof playlistModeText !== 'undefined' && playlistModeText) playlistModeText.textContent = '单曲循环';
            break;
        case PlayMode.RANDOM:
            if (typeof playlistModeIcon !== 'undefined' && playlistModeIcon) playlistModeIcon.className = 'fa-solid fa-random';
            if (typeof playlistModeText !== 'undefined' && playlistModeText) playlistModeText.textContent = '随机播放';
            break;
    }
}

// ==================== 删除操作 ====================

// 切换删除模式
function toggleDeleteMode() {
    isDeleteMode = !isDeleteMode;
    if (isDeleteMode) {
        playlistDeleteToggle.classList.add('active');
    } else {
        playlistDeleteToggle.classList.remove('active');
    }
    renderPlaylist();
}

// 从播放列表删除歌曲
function deleteSongFromPlaylist(index) {
    if (index < 0 || index >= songIds.length) return;

    var songIdToDelete = songIds[index];
    var songName = (playlistData.find(function(s) { return s.id === songIdToDelete; }) || {}).name || '这首歌曲';
    var isCurrentlyPlaying = songIdToDelete === currentPlayingSongId;

    var confirmMsg = isCurrentlyPlaying
        ? '"' + songName + '" 正在播放，删除后将自动播放下一首，确定删除吗？'
        : '确定要从播放列表删除 "' + songName + '" 吗？';

    showConfirmDialog(
        '确认删除',
        confirmMsg,
        function() {
            performDeleteSong(index, songIdToDelete, songName, isCurrentlyPlaying);
        },
        function() {
            console.log('取消删除歌曲');
        }
    );
}

// 执行删除歌曲操作
function performDeleteSong(index, songIdToDelete, songName, isCurrentlyPlaying) {
    songIds.splice(index, 1);
    playlistData = playlistData.filter(function(s) { return s.id !== songIdToDelete; });

    if (typeof clearNoCopyrightMark === 'function') clearNoCopyrightMark(songIdToDelete);

    playlistData.forEach(function(song, i) {
        song.index = i;
    });

    savePlaylistToJSON();
    console.log('已删除歌曲 "' + songName + '"');

    if (isCurrentlyPlaying) {
        console.log('删除当前播放歌曲，自动播放下一首');
        if (songIds.length === 0) {
            player.pause();
            player.src = '';
            currentPlayingSongId = null;
            isPlaying = false;
            isInitialized = false;
            icons.classList.remove('fa-pause');
            icons.classList.add('fa-play');
            songTitle.textContent = '没有歌曲';
            songArtist.textContent = '请添加歌曲';
            showToast('播放列表已空，请添加歌曲', 'warning', 2500);
        } else {
            if (playMode === PlayMode.RANDOM) {
                var posInRandomQueue = randomQueue.indexOf(songIdToDelete);
                if (posInRandomQueue >= 0) {
                    randomQueue.splice(posInRandomQueue, 1);
                    if (randomIndex >= randomQueue.length) {
                        randomIndex = 0;
                    }
                }
                setTimeout(function() { if (typeof playNextSong === 'function') playNextSong(); }, 100);
            } else {
                if (currentIndex >= songIds.length) {
                    currentIndex = 0;
                }
                setTimeout(function() { if (typeof loadAndPlaySong === 'function') loadAndPlaySong(currentIndex, 0); }, 100);
            }
        }
    } else {
        if (currentIndex > index) {
            currentIndex--;
        }
        var posInRandomQueue = randomQueue.indexOf(songIdToDelete);
        if (posInRandomQueue >= 0) {
            randomQueue.splice(posInRandomQueue, 1);
            if (randomIndex > posInRandomQueue) {
                randomIndex--;
            }
        }
    }

    renderPlaylist();
    updatePlaylistCurrentInfo();
}

// ==================== 从播放列表播放 ====================

// 从播放列表播放歌曲
function playSongFromPlaylist(index) {
    if (index < 0 || index >= songIds.length) return;

    closePlaylist();

    if (typeof cancelPendingRequest === 'function') cancelPendingRequest();
    if (typeof clearRetryTimeout === 'function') clearRetryTimeout();
    if (typeof cancelLyricRequest === 'function') cancelLyricRequest();
    if (typeof clearLyricRetryTimeout === 'function') clearLyricRetryTimeout();
    lyricRetryCount = 0;

    var targetSongId = songIds[index];
    // 关键修复：统一转为字符串比较，防止 number/string 类型不匹配
    var targetSongIdStr = String(targetSongId);

    if (playMode === PlayMode.RANDOM) {
        // 关键修复：简化随机模式逻辑，避免重复插入导致队列错乱
        // 先确保 randomQueue 已初始化
        if (randomQueue.length === 0) {
            if (typeof initRandomQueue === 'function') initRandomQueue();
        }
        // 查找目标歌曲在 randomQueue 中的位置
        var queuePos = -1;
        for (var _qi = 0; _qi < randomQueue.length; _qi++) {
            if (String(randomQueue[_qi]) === targetSongIdStr) { queuePos = _qi; break; }
        }
        if (queuePos >= 0) {
            // 歌曲已在随机队列中，直接设置索引
            randomIndex = queuePos;
        } else {
            // 歌曲不在队列中（新添加的歌曲），插入到当前位置后面
            randomQueue.splice(randomIndex + 1, 0, targetSongId);
            randomIndex = randomIndex + 1;
        }
        // 播放：传入 randomQueue 中的位置
        if (typeof loadAndPlaySong === 'function') {
            loadAndPlaySong(randomIndex, 0, 0);
        }
    } else {
        currentIndex = index;
        if (typeof loadAndPlaySong === 'function') {
            loadAndPlaySong(index, 0, 0);
        }
    }
}

// ==================== 确认弹窗 ====================

// 显示自定义确认弹窗
function showConfirmDialog(title, message, onConfirm, onCancel) {
    var overlay = document.createElement('div');
    overlay.className = 'confirm-dialog-overlay';
    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.5);backdrop-filter:blur(8px);display:flex;align-items:center;justify-content:center;z-index:3000;opacity:0;visibility:hidden;transition:all 0.35s ease;';

    var dialog = document.createElement('div');
    dialog.className = 'confirm-dialog';
    dialog.style.cssText = 'position:relative;background:rgba(255,255,255,0.10);border-radius:20px;border:1px solid rgba(255,255,255,0.12);box-shadow:0 32px 80px rgba(0,0,0,0.8);padding:24px;min-width:300px;max-width:400px;transform:scale(0.9);transition:all 0.3s cubic-bezier(0.34,1.56,0.64,1);';
    // 添加液体玻璃图层
    dialog.classList.add('lg-container');
    dialog.insertAdjacentHTML('afterbegin',
        '<div class="lg-backdrop"></div>' +
        '<div class="lg-specular"></div>'
    );

    var titleEl = document.createElement('div');
    titleEl.className = 'confirm-dialog-title';
    titleEl.style.cssText = 'font-size:18px;font-weight:600;color:#fff;margin-bottom:12px;';
    titleEl.textContent = title;

    var messageEl = document.createElement('div');
    messageEl.className = 'confirm-dialog-message';
    messageEl.style.cssText = 'font-size:14px;color:rgba(255,255,255,0.8);line-height:1.5;margin-bottom:24px;word-break:break-word;';
    messageEl.textContent = message;

    var buttonContainer = document.createElement('div');
    buttonContainer.className = 'confirm-dialog-buttons';
    buttonContainer.style.cssText = 'display:flex;gap:12px;justify-content:flex-end;';

    var cancelButton = document.createElement('button');
    cancelButton.className = 'confirm-dialog-button cancel';
    cancelButton.style.cssText = 'padding:10px 20px;border:none;border-radius:10px;background:rgba(255,255,255,0.1);color:rgba(255,255,255,0.8);font-size:14px;cursor:pointer;transition:all 0.3s ease;touch-action:manipulation;';
    cancelButton.textContent = '取消';
    EventListenerManager.add(cancelButton, 'click', function() {
        hideConfirmDialog(overlay, dialog);
        if (onCancel) onCancel();
    });

    var confirmButton = document.createElement('button');
    confirmButton.className = 'confirm-dialog-button confirm';
    confirmButton.style.cssText = 'padding:10px 20px;border:none;border-radius:10px;background:linear-gradient(135deg,#f87171,#ef4444);color:#fff;font-size:14px;font-weight:500;cursor:pointer;transition:all 0.3s ease;touch-action:manipulation;';
    confirmButton.textContent = '确认删除';
    EventListenerManager.add(confirmButton, 'click', function() {
        hideConfirmDialog(overlay, dialog);
        if (onConfirm) onConfirm();
    });

    addTouchSupport(cancelButton);
    addTouchSupport(confirmButton);

    buttonContainer.appendChild(cancelButton);
    buttonContainer.appendChild(confirmButton);
    dialog.appendChild(titleEl);
    dialog.appendChild(messageEl);
    dialog.appendChild(buttonContainer);
    overlay.appendChild(dialog);
    document.body.appendChild(overlay);

    requestAnimationFrame(function() {
        overlay.style.opacity = '1';
        overlay.style.visibility = 'visible';
        dialog.style.transform = 'scale(1)';
    });

    disableAllButtons();
}

// 隐藏确认弹窗
function hideConfirmDialog(overlay, dialog) {
    overlay.style.opacity = '0';
    overlay.style.visibility = 'hidden';
    dialog.style.transform = 'scale(0.9)';

    setTimeout(function() {
        document.body.removeChild(overlay);
        enableAllButtons();
    }, 300);
}

// 禁用所有控制按钮
function disableAllButtons() {
    if (typeof zuo !== 'undefined' && zuo) zuo.classList.add('btn-disabled');
    if (typeof you !== 'undefined' && you) you.classList.add('btn-disabled');
    if (typeof bofang !== 'undefined' && bofang) bofang.classList.add('btn-disabled');
    if (typeof playlistToggle !== 'undefined' && playlistToggle) playlistToggle.classList.add('btn-disabled');
    if (typeof searchToggle !== 'undefined' && searchToggle) searchToggle.classList.add('btn-disabled');
}

// 启用所有控制按钮
function enableAllButtons() {
    if (typeof zuo !== 'undefined' && zuo) zuo.classList.remove('btn-disabled');
    if (typeof you !== 'undefined' && you) you.classList.remove('btn-disabled');
    if (typeof bofang !== 'undefined' && bofang) bofang.classList.remove('btn-disabled');
    if (typeof playlistToggle !== 'undefined' && playlistToggle) playlistToggle.classList.remove('btn-disabled');
    if (typeof searchToggle !== 'undefined' && searchToggle) searchToggle.classList.remove('btn-disabled');
}

// 添加触摸事件支持
function addTouchSupport(element) {
    var isTouching = false;
    var touchHandled = false;

    EventListenerManager.add(element, 'touchstart', function(e) {
        isTouching = true;
        touchHandled = false;
        element.style.transform = 'scale(0.95)';
        element.style.opacity = '0.8';
    }, { passive: true });

    EventListenerManager.add(element, 'touchend', function(e) {
        element.style.transform = 'scale(1)';
        element.style.opacity = '1';
        touchHandled = true;
        isTouching = false;
    }, { passive: true });

    EventListenerManager.add(element, 'touchcancel', function(e) {
        element.style.transform = 'scale(1)';
        element.style.opacity = '1';
        touchHandled = false;
        isTouching = false;
    }, { passive: true });

    EventListenerManager.add(element, 'click', function(e) {
        if (touchHandled) {
            e.stopPropagation();
            e.preventDefault();
            return;
        }
        element.style.transform = 'scale(1)';
        element.style.opacity = '1';
    });
}

// ==================== 搜索功能 ====================

// 读取搜索历史
function duQuSouSuoLiShi() {
    try {
        var data = localStorage.getItem(SOU_SUO_LI_SHI_KEY);
        return data ? JSON.parse(data) : [];
    } catch (e) {
        return [];
    }
}

// 保存搜索历史
function baoCunSouSuoLiShi(liShi) {
    try {
        localStorage.setItem(SOU_SUO_LI_SHI_KEY, JSON.stringify(liShi));
    } catch (e) {}
}

// 添加搜索历史
function tianJiaSouSuoLiShi(guanJianCi) {
    if (!guanJianCi || !guanJianCi.trim()) return;
    var ci = guanJianCi.trim();
    var liShi = duQuSouSuoLiShi();
    liShi = liShi.filter(function(item) { return item !== ci; });
    liShi.unshift(ci);
    if (liShi.length > SOU_SUO_LI_SHI_MAX) {
        liShi = liShi.slice(0, SOU_SUO_LI_SHI_MAX);
    }
    baoCunSouSuoLiShi(liShi);
}

// 渲染搜索历史
function xuanRanSouSuoLiShi() {
    searchContent.classList.remove('has-results');
    var liShi = duQuSouSuoLiShi();
    if (liShi.length === 0) {
        searchContent.innerHTML = '<div class="search-placeholder">输入关键词搜索歌曲</div>';
        return;
    }

    var html = '<div class="sou-suo-li-shi-qu">';
    liShi.forEach(function(ci) {
        html += '<div class="sou-suo-li-shi-xiang" data-ci="' + (typeof escapeHtml === 'function' ? escapeHtml(ci) : ci) + '">' + (typeof escapeHtml === 'function' ? escapeHtml(ci) : ci) + '</div>';
    });
    html += '</div>';
    html += '<div class="sou-suo-li-shi-qing-kong" id="qingKongSouSuoLiShi">清空搜索记录</div>';
    searchContent.innerHTML = html;

    searchContent.querySelectorAll('.sou-suo-li-shi-xiang').forEach(function(item) {
        EventListenerManager.add(item, 'click', function() {
            var ci = item.dataset.ci;
            searchInput.value = ci;
            performSearch(ci);
        });
    });

    var qingKongBtn = document.getElementById('qingKongSouSuoLiShi');
    if (qingKongBtn) {
        EventListenerManager.add(qingKongBtn, 'click', function() {
            baoCunSouSuoLiShi([]);
            searchContent.innerHTML = '<div class="search-placeholder">输入关键词搜索歌曲</div>';
        });
    }
}

// 显示搜索历史或占位
function xianShiSouSuoLiShiHuoZhanWei() {
    if (searchInput.value.trim()) {
        performSearch(searchInput.value.trim());
    } else {
        xuanRanSouSuoLiShi();
    }
}

// 执行搜索
async function performSearch(keyword) {
    if (!keyword) {
        searchContent.innerHTML = '<div class="search-placeholder">输入关键词搜索歌曲</div>';
        lastSearchKeyword = '';
        return;
    }

    if (keyword === lastSearchKeyword && searchResults.length > 0) {
        console.log('[搜索详细] 关键词相同且有结果，跳过重复搜索: "' + keyword + '"');
        return;
    }

    if (searchDebounceTimer) {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = null;
    }

    searchDebounceTimer = setTimeout(function() {
        searchDebounceTimer = null;
        if (currentSearchTab === 'qq') {
            executeQqSearch(keyword);
        } else {
            executeSearch(keyword);
        }
    }, 300);
}

/**
 * 将残影搜索 API 响应转换为网易云官方搜索格式
 */
function normalizeApicxSearchResponse(rawData) {
    if (!rawData || rawData.code !== 200 || !rawData.data || !Array.isArray(rawData.data.songs)) {
        return { code: 200, result: { songs: [] } };
    }
    var songs = rawData.data.songs.map(function(song) {
        var artistsArr = [];
        var artistsStr = song.artists || '';
        if (typeof artistsStr === 'string' && artistsStr.trim() !== '') {
            artistsStr.split(/[,/]/).forEach(function(part) {
                var trimmed = part.trim();
                if (trimmed) artistsArr.push({ name: trimmed });
            });
        }
        var albumName = song.album || '';
        var pic = song.pic || '';
        return {
            id: song.id,
            name: song.name,
            artists: artistsArr,
            album: { name: albumName, picUrl: pic },
            picUrl: pic,
            duration: song.time || 0
        };
    });
    return { code: 200, result: { songs: songs } };
}

/** QQ音乐搜索结果标准化 */
function normalizeQqmusicSearchResponse(rawData) {
    if (!rawData || rawData.code !== 200 || !rawData.data || !Array.isArray(rawData.data.songs)) {
        return [];
    }
    return rawData.data.songs.map(function(song) {
        return {
            id: song.song_id || '',
            name: song.name || '',
            artist: song.artist || '',
            album: song.album || '',
            pic: (song.cover || '').replace('http://', 'https://'),
            duration: song.duration || 0,
            source: 'qq',
            qqIndex: song.index || 1
        };
    });
}

/** QQ音乐搜索 */
async function executeQqSearch(keyword) {
    // 关键词未变且有结果缓存，直接渲染缓存
    if (keyword === qqSearchKeyword && qqSearchResults.length > 0) {
        console.log('[QQ搜索] 关键词相同且有缓存结果，跳过重复搜索');
        renderQqSearchResults(qqSearchResults);
        return;
    }
    console.log('[QQ搜索] ===== 开始QQ音乐搜索 =====');
    console.log('[QQ搜索] 关键词: "' + keyword + '"');
    qqSearchKeyword = keyword;

    searchContent.innerHTML = '<div class="search-loading"><i class="fa-solid fa-spinner fa-spin"></i> QQ音乐搜索中...</div>';

    try {
        var qqmusicUrl = window.__QQMUSIC_API_URL__;
        var qqmusicToken = window.__QQMUSIC_API_TOKEN__;
        if (!qqmusicUrl || !qqmusicToken) {
            console.error('[QQ搜索] QQ音乐API未配置');
            // 降级：尝试通过原生桥接
            if (window.AndroidBridge && typeof window.AndroidBridge.nativeSearchQqmusicAsync === 'function') {
                var result = await new Promise(function(resolve, reject) {
                    var cb = '_qqSearchCallback_' + Date.now();
                    window[cb] = function(json) {
                        try { resolve(JSON.parse(json)); } catch(e) { reject(e); }
                        delete window[cb];
                    };
                    window.AndroidBridge.nativeSearchQqmusicAsync(keyword, cb);
                });
                qqSearchResults = normalizeQqmusicSearchResponse(result);
            } else {
                searchContent.innerHTML = '<div class="search-no-result">QQ音乐API未配置</div>';
                return;
            }
        } else {
            var response = await axios.get(qqmusicUrl + '?msg=' + encodeURIComponent(keyword) + '&token=' + encodeURIComponent(qqmusicToken), { timeout: 10000 });
            qqSearchResults = normalizeQqmusicSearchResponse(response.data);
        }

        if (qqSearchResults.length > 0) {
            console.log('[QQ搜索] 搜索结果: 找到 ' + qqSearchResults.length + ' 首歌曲');
            tianJiaSouSuoLiShi(keyword);
            renderQqSearchResults(qqSearchResults);
        } else {
            tianJiaSouSuoLiShi(keyword);
            searchContent.innerHTML = '<div class="search-no-result">QQ音乐未找到相关歌曲</div>';
        }
    } catch (error) {
        console.error('[QQ搜索] 搜索失败:', error.message);
        searchContent.innerHTML = '<div class="search-no-result">QQ音乐搜索失败，请重试</div>';
    }
}

/** 渲染QQ音乐搜索结果 */
function renderQqSearchResults(songs) {
    if (songs.length === 0) {
        searchContent.innerHTML = '<div class="search-no-result">未找到相关歌曲</div>';
        return;
    }

    var html = '<div class="search-results">';
    for (var i = 0; i < songs.length; i++) {
        var song = songs[i];
        var coverStyle = song.pic ? 'background-image: url(' + song.pic + ')' : '';
        var artistName = song.artist || '未知歌手';
        var isInPlaylist = songIds.indexOf(String(song.id)) >= 0;
        html += '<div class="search-result-item" data-index="' + i + '" data-source="qq">' +
            '<div class="search-result-cover" style="' + coverStyle + '"></div>' +
            '<div class="search-result-info">' +
            '<div class="search-result-name">' + (typeof escapeHtml === 'function' ? escapeHtml(song.name) : song.name) + '</div>' +
            '<div class="search-result-artist">' + (typeof escapeHtml === 'function' ? escapeHtml(artistName) : artistName) + '</div>' +
            '</div>' +
            '<div class="search-result-add' + (isInPlaylist ? ' added' : '') + '" data-index="' + i + '" data-source="qq" title="' + (isInPlaylist ? '已添加' : '添加到播放列表') + '">' + (isInPlaylist ? '✓' : '+') + '</div>' +
            '</div>';
    }
    html += '</div>';
    searchContent.innerHTML = html;
    searchContent.classList.add('has-results');

    // 绑定点击播放事件
    searchContent.querySelectorAll('.search-result-item').forEach(function(item) {
        EventListenerManager.add(item, 'click', function(e) {
            if (e.target.closest('.search-result-add')) return;
            var index = parseInt(item.dataset.index, 10);
            playQqSearchResult(index);
        });
    });

    // 绑定添加按钮事件
    searchContent.querySelectorAll('.search-result-add').forEach(function(btn) {
        EventListenerManager.add(btn, 'click', function(e) {
            e.stopPropagation();
            var index = parseInt(btn.dataset.index, 10);
            addQqSearchResultToPlaylist(index);
        });
    });
}

async function executeSearch(keyword) {
    console.log('[搜索详细] ===== 开始搜索 =====');
    console.log('[搜索详细] 关键词: "' + keyword + '"');
    lastSearchKeyword = keyword;

    searchContent.innerHTML = '<div class="search-loading"><i class="fa-solid fa-spinner fa-spin"></i> 搜索中...</div>';

    try {
        var searchData;

        if (window.AndroidBridge && typeof window.AndroidBridge.nativeSearchSongsAsync === 'function') {
            console.log('[搜索详细] 使用原生异步接口');
            searchData = await new Promise(function(resolve, reject) {
                var callbackName = '_searchCallback_' + Date.now();
                window[callbackName] = function(resultJson) {
                    try {
                        var data = JSON.parse(resultJson);
                        resolve(data);
                    } catch (e) {
                        reject(e);
                    } finally {
                        delete window[callbackName];
                    }
                };
                window.AndroidBridge.nativeSearchSongsAsync(keyword, 1, callbackName);
            });
        } else {
            console.log('[搜索详细] 降级到 axios');
            var searchApiUrl = window.__SEARCH_API_URL__;
            var searchApiToken = window.__SEARCH_API_TOKEN__;
            if (!searchApiUrl || !searchApiToken) { console.error('[搜索详细] 搜索API未配置'); return; }
            var response = await axios.get(searchApiUrl + '?gm=' + encodeURIComponent(keyword) + '&n&br=lossless&token=' + encodeURIComponent(searchApiToken), { timeout: 10000 });
            searchData = normalizeApicxSearchResponse(response.data);
        }

        if (searchData && searchData.result && searchData.result.songs) {
            console.log('[搜索详细] 搜索结果: 找到 ' + searchData.result.songs.length + ' 首歌曲');
            searchResults = searchData.result.songs;
            tianJiaSouSuoLiShi(keyword);
            renderSearchResults(searchResults);
        } else {
            tianJiaSouSuoLiShi(keyword);
            searchContent.innerHTML = '<div class="search-no-result">未找到相关歌曲</div>';
        }
    } catch (error) {
        console.error('[搜索详细] 搜索失败:', error.message);
        searchContent.innerHTML = '<div class="search-no-result">搜索失败，请重试</div>';
    }
}

/** 播放QQ音乐搜索结果 */
async function playQqSearchResult(index) {
    var song = qqSearchResults[index];
    if (!song) return;

    closeSearchPanel();

    if (typeof cancelPendingRequest === 'function') cancelPendingRequest();
    if (typeof clearRetryTimeout === 'function') clearRetryTimeout();
    if (typeof cancelLyricRequest === 'function') cancelLyricRequest();
    if (typeof clearLyricRetryTimeout === 'function') clearLyricRetryTimeout();
    lyricRetryCount = 0;

    if (typeof showLoading提示 === 'function') showLoading提示("获取QQ音乐...", 1);

    try {
        var qqmusicUrl = window.__QQMUSIC_API_URL__;
        var qqmusicToken = window.__QQMUSIC_API_TOKEN__;
        if (!qqmusicUrl || !qqmusicToken) {
            console.error('[QQ播放] QQ音乐API未配置');
            showToast('QQ音乐API未配置', 'error', 3000);
            if (typeof hideLoading提示 === 'function') hideLoading提示();
            return;
        }

        // 获取QQ音乐详情（含播放地址）
        // 传 song.id 确保精确匹配（多个歌手同歌名时，name+index 会匹配错误）
        var qqApiUrl = qqmusicUrl + '?msg=' + encodeURIComponent(song.name) + '&n=' + song.qqIndex + '&token=' + encodeURIComponent(qqmusicToken);
        if (song.id) { qqApiUrl += '&id=' + encodeURIComponent(String(song.id)); }
        var response = await axios.get(qqApiUrl, { timeout: 10000 });

        if (response.data && response.data.code === 200 && response.data.data) {
            var detail = response.data.data;
            var playUrl = detail.play_url || '';
            if (!playUrl) {
                showToast('该歌曲暂时无法播放', 'warning', 2500);
                if (typeof hideLoading提示 === 'function') hideLoading提示();
                return;
            }

            // 更新UI
            currentCoverImage = (detail.cover || song.pic || '').replace('http://', 'https://') || defaultCover;
            songTitle.textContent = detail.name || song.name;
            songArtist.textContent = detail.artist || song.artist || '未知歌手';

            // duration from detail is like "04:29"
            if (detail.duration && detail.duration.includes(':')) {
                var parts = detail.duration.split(':');
                totalDuration = (parseInt(parts[0], 10) * 60 + parseInt(parts[1], 10)) * 1000;
                totalTimeDisplay.textContent = detail.duration;
            }

            if (isInitialized) {
                if (typeof switchAlbumImage === 'function') switchAlbumImage(currentCoverImage);
            } else {
                if (typeof setCoverImagesDirect === 'function') setCoverImagesDirect(currentCoverImage);
            }

            if ('mediaSession' in navigator) {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: songTitle.textContent,
                    artist: songArtist.textContent,
                    album: '',
                    artwork: [{ src: currentCoverImage, sizes: '96x96', type: 'image/jpeg' },
                             { src: currentCoverImage, sizes: '256x256', type: 'image/jpeg' }]
                });
            }

            currentPlayingSongId = song.id;
            currentSearchSong = { id: song.id, name: song.name, artist: song.artist || '', source: 'qq' };

            if (typeof notifyNativeSongInfo === 'function') notifyNativeSongInfo();

            // 使用原生播放器播放
            if (USE_NATIVE_PLAYER && window.AndroidBridge && typeof window.AndroidBridge.nativeLoadAndPlay === 'function') {
                if (typeof player !== 'undefined') { player.pause(); player.src = ''; }
                var result = typeof nativeLoadAndPlay === 'function' ? nativeLoadAndPlay(playUrl, 0) : false;
                if (result) {
                    if (lyricsEnabled && typeof loadLyrics === 'function') {
                        lyricRetryCount = 0;
                        // QQ音乐自带歌词，直接使用
                        if (detail.lyrics && detail.lyrics.formatted) {
                            // 使用标准解析路径（parseLyrics 将时间转为毫秒，与 updateLyricsDisplay 一致）
                            if (typeof parseLyrics === 'function') {
                                parseLyrics(detail.lyrics.formatted, '');
                            } else {
                                var lrcData = typeof parseLrcContent === 'function' ? parseLrcContent(detail.lyrics.formatted) : [];
                                if (lrcData && lrcData.length > 0) {
                                    lyrics = lrcData.map(function(l) {
                                        return { time: l.time * 1000, text: l.text, trans: '' };
                                    });
                                    updateLyricsDisplay(true);
                                    if (typeof sendLyricData === 'function') sendLyricData(lyrics);
                                }
                            }
                            loadedLyricSongId = song.id;
                        } else {
                            loadLyrics(song.id);
                        }
                    }
                    // 启动H5进度同步定时器（确保歌词滚动/进度条更新）
                    if (typeof startSyncInterval === 'function') startSyncInterval();
                } else {
                    console.warn("原生播放器播放失败，回退到Web Audio");
                    if (typeof playWithWebAudio === 'function') {
                        playWithWebAudio(playUrl, song.id);
                    } else {
                        showToast('播放失败', 'error', 2000);
                    }
                }
            }

            // 缓存歌曲信息
            if (typeof saveSongCacheToStorage === 'function') {
                saveSongCacheToStorage('qq_' + song.id, {
                    source: 'qq',
                    url: playUrl,
                    name: songTitle.textContent,
                    artist: songArtist.textContent,
                    pic: currentCoverImage,
                    duration: detail.duration || '',
                    album: detail.album || ''
                });
            }

            if (typeof hideLoading提示 === 'function') hideLoading提示();
        } else {
            throw new Error('获取QQ音乐详情失败');
        }
    } catch (error) {
        console.error('[QQ播放] 失败:', error.message);
        if (typeof hideLoading提示 === 'function') hideLoading提示();
        showToast('QQ音乐播放失败', 'error', 2500);
    }
}

/** 将QQ音乐搜索结果添加到播放列表 */
function addQqSearchResultToPlaylist(index) {
    var song = qqSearchResults[index];
    if (!song) return;

    // 检查是否已在播放列表中
    if (songIds.indexOf(String(song.id)) >= 0) {
        showToast('该歌曲已在播放列表中', 'info', 2000);
        return;
    }

    var songPic = guiFanHuaFengMianUrl(song.pic || defaultCover);

    songIds.push(song.id);

    var songInfo = {
        id: song.id,
        index: songIds.length - 1,
        name: song.name,
        artist: song.artist || '未知歌手',
        pic: songPic,
        source: 'qq',
        qqIndex: song.qqIndex,
        picTimestamp: Date.now()
    };

    playlistData.push(songInfo);
    playlistData.sort(function(a, b) { return a.index - b.index; });

    addSongToPlaylistCache(songInfo);
    // 重新渲染以更新按钮状态（已添加的歌曲显示 ✓）
    renderQqSearchResults(qqSearchResults);
    renderPlaylist();

    // 同步播放列表到原生
    if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.gengXinBoFangLieBiaoXiangQing === 'function') {
        try {
            var songsForNative = playlistData.map(function(s) {
                return { id: String(s.id), name: s.name || '', artist: s.artist || '', pic: guiFanHuaFengMianUrl(s.pic || defaultCover), source: s.source || 'wy' };
            });
            AndroidBridge.gengXinBoFangLieBiaoXiangQing(JSON.stringify(songsForNative));
        } catch(e) {}
    }

    // 显示添加成功提示
    if (typeof showToast === 'function') {
        showToast('已添加 "' + song.name + '" 到播放列表', 'success', 2000);
    }
}

// 渲染搜索结果
function renderSearchResults(songs) {
    if (songs.length === 0) {
        searchContent.innerHTML = '<div class="search-no-result">未找到相关歌曲</div>';
        return;
    }

    searchContent.innerHTML = songs.map(function(song, index) {
        var artistNames = (song.artists && song.artists.length > 0) ? song.artists.map(function(a) { return a.name; }).join(', ') : '未知歌手';
        var isInPlaylist = songIds.includes(song.id);

        return '<div class="search-result-item" data-index="' + index + '" data-id="' + song.id + '">' +
            '<div class="search-result-info">' +
            '<div class="search-result-title">' + (typeof escapeHtml === 'function' ? escapeHtml(song.name) : song.name) + '</div>' +
            '<div class="search-result-artist">' + (typeof escapeHtml === 'function' ? escapeHtml(artistNames) : artistNames) + '</div>' +
            '</div>' +
            '<div class="search-result-add" data-index="' + index + '" title="' + (isInPlaylist ? '已在播放列表' : '添加到播放列表') + '">' +
            '<i class="fa-solid ' + (isInPlaylist ? 'fa-check' : 'fa-plus') + '"></i>' +
            '</div></div>';
    }).join('');
    searchContent.classList.add('has-results');

    searchContent.querySelectorAll('.search-result-item').forEach(function(item) {
        EventListenerManager.add(item, 'click', function(e) {
            if (!e.target.closest('.search-result-add')) {
                var index = parseInt(item.dataset.index);
                playSearchResult(index);
            }
        });
    });

    searchContent.querySelectorAll('.search-result-add').forEach(function(btn) {
        EventListenerManager.add(btn, 'click', function(e) {
            e.stopPropagation();
            var index = parseInt(btn.dataset.index);
            addToPlaylist(index);
        });
    });
}

// 播放搜索结果的歌曲
async function playSearchResult(index) {
    var song = searchResults[index];
    if (!song) return;

    closeSearchPanel();

    if (typeof cancelPendingRequest === 'function') cancelPendingRequest();
    if (typeof clearRetryTimeout === 'function') clearRetryTimeout();
    if (typeof cancelLyricRequest === 'function') cancelLyricRequest();
    if (typeof clearLyricRetryTimeout === 'function') clearLyricRetryTimeout();
    lyricRetryCount = 0;

    // 如果歌曲在播放列表中已存在，直接走 loadAndPlaySong 流程
    var songIndexInPlaylist = typeof songIds !== 'undefined' ? songIds.indexOf(song.id) : -1;
    if (songIndexInPlaylist >= 0) {
        console.log('[搜索播放详细] 歌曲在播放列表中已存在，走loadAndPlaySong: index=' + songIndexInPlaylist);
        if (typeof loadAndPlaySong === 'function') {
            loadAndPlaySong(songIndexInPlaylist, 0, 0);
        }
        return;
    }

    var skipCache = song._skipCache;
    if (song._skipCache) delete song._skipCache;

    var cachedSongData = !skipCache ? (typeof getSongCacheFromStorage === 'function' ? getSongCacheFromStorage(song.id) : null) : null;
    if (cachedSongData) {
        // 验证缓存有效性
        if (!cachedSongData.url || cachedSongData.url.includes('file:///android_asset') || cachedSongData.name === '未知歌曲' || !cachedSongData.url.startsWith('http')) {
            console.warn('[搜索播放详细] 缓存数据无效，删除并重新请求: ' + song.id);
            try { localStorage.removeItem(CACHE_CONFIG.SONG_CACHE_PREFIX + song.id); } catch(e) {}
            cachedSongData = null;
        } else {
            console.log('[搜索播放详细] 搜索歌曲有缓存，使用缓存播放: ' + song.id);
            playSearchResultWithCache(song, cachedSongData);
            return;
        }
    }

    if (!isOnline) {
        console.log('网络已断开且搜索歌曲无缓存，无法播放');
        if (typeof hideLoading提示 === 'function') hideLoading提示();
        isLoading = false;
        if (typeof enableButtons === 'function') enableButtons();
        showToast('网络已断开，该歌曲无缓存，无法播放', 'warning', 3000);
        return;
    }

    if (typeof showLoading提示 === 'function') showLoading提示("连接中...", 1);

    try {
        console.log('[搜索播放详细] 开始请求 API 获取歌曲: ' + song.id);

        var currentApi = (typeof API_SERVERS !== 'undefined' && API_SERVERS) ? API_SERVERS[typeof currentApiIndex !== 'undefined' ? currentApiIndex : 0] : null;
        var songName = '';
        if (currentApi && currentApi.mode === 'search') {
            songName = song.name || '';
        }

        var apiUrl = typeof getApiUrl === 'function' ? getApiUrl(song.id, 'exhigh', songName) : null;

        if (!apiUrl) {
            console.error('[搜索播放详细] 无法生成API请求');
            if (typeof hideLoading提示 === 'function') hideLoading提示();
            showToast('无法生成API请求', 'error', 3000);
            return;
        }

        var songData;
        try {
            var result = await (typeof fetchSongDetailViaAxios === 'function' ? fetchSongDetailViaAxios(String(song.id), song.name || '') : Promise.reject(new Error('fetchSongDetailViaAxios不可用')));
            var detailData = result.data;
            if (detailData && detailData.code === 200 && detailData.data) {
                var geQuXinXi = typeof tiQuGeQuYuanShuJu === 'function' ? tiQuGeQuYuanShuJu(detailData.data) : null;
                console.log('[搜索播放详细] tiQuGeQuYuanShuJu结果: name=' + (geQuXinXi ? geQuXinXi.name : 'null') + ', artist=' + (geQuXinXi ? geQuXinXi.artist : 'null') + ', pic=' + (geQuXinXi && geQuXinXi.pic ? '有(' + geQuXinXi.pic.substring(0, 30) + ')' : '无') + ', url=' + (geQuXinXi && geQuXinXi.url ? '有' : '无'));
                if (geQuXinXi && geQuXinXi.url) {
                    songData = {
                        id: song.id,
                        name: geQuXinXi.name || '未知歌曲',
                        artist: geQuXinXi.artist || '未知歌手',
                        album: geQuXinXi.album || '',
                        pic: geQuXinXi.pic && geQuXinXi.pic.length > 10 ? geQuXinXi.pic : defaultCover,
                        url: geQuXinXi.url,
                        duration: geQuXinXi.durationMs || 0,
                        durationStr: geQuXinXi.duration || '',
                        lrc: geQuXinXi.lrc || ''
                    };
                }
            } else {
                throw new Error('API返回无效数据');
            }
        } catch (e) {
            console.error('[搜索播放详细] fetchSongDetailViaAxios失败:', e.message);
            if (typeof hideLoading提示 === 'function') hideLoading提示();
            showToast('获取歌曲详情失败，请检查网络', 'error', 3000);
            return;
        }

        if (songData && songData.url) {
            if (!songData.url || songData.url.trim() === '' || songData.url.includes('file:///android_asset')) {
                console.error('API 返回无效 URL:', songData.url);
                if (typeof hideLoading提示 === 'function') hideLoading提示();
                showToast('该歌曲暂时无法播放，请尝试其他歌曲', 'warning', 2500);
                return;
            }

            currentCoverImage = songData.pic || defaultCover;

            if (USE_NATIVE_PLAYER && window.AndroidBridge && typeof window.AndroidBridge.nativeLoadAndPlay === 'function') {
                player.pause();
                player.src = '';
            } else {
                player.src = songData.url;
            }
            songTitle.textContent = songData.name;
            songArtist.textContent = songData.artist;

            if (songData.duration && songData.duration > 0) {
                totalDuration = songData.duration;
                var minutes = Math.floor(totalDuration / 60000);
                var seconds = Math.floor((totalDuration % 60000) / 1000);
                totalTimeDisplay.textContent = minutes + ':' + seconds.toString().padStart(2, '0');
            } else if (songData.durationStr && songData.durationStr.includes(':')) {
                var parts = songData.durationStr.split(':');
                totalDuration = (parseInt(parts[0], 10) * 60 + parseInt(parts[1], 10)) * 1000;
                totalTimeDisplay.textContent = songData.durationStr;
            }

            if (isInitialized) {
                if (typeof switchAlbumImage === 'function') switchAlbumImage(currentCoverImage);
            } else {
                if (typeof setCoverImagesDirect === 'function') setCoverImagesDirect(currentCoverImage);
            }

            if ('mediaSession' in navigator) {
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: songData.name,
                    artist: songData.artist,
                    album: songData.album || "",
                    artwork: [
                        { src: currentCoverImage, sizes: '96x96', type: 'image/jpeg' },
                        { src: currentCoverImage, sizes: '256x256', type: 'image/jpeg' }
                    ]
                });
            }

            if (typeof notifyNativeSongInfo === 'function') notifyNativeSongInfo();
            currentPlayingSongId = song.id;

            // 标记当前播放的是搜索歌曲（不在播放列表中）
            var songIndexInPlaylist = songIds.indexOf(song.id);
            if (songIndexInPlaylist < 0) {
                currentSearchSong = { id: song.id, name: song.name, artist: song.artist || '' };
                console.log('[搜索播放详细] 标记为搜索歌曲: ' + song.name);
            } else {
                currentSearchSong = null;
            }
            if (songIndexInPlaylist >= 0) {
                if (playMode === PlayMode.RANDOM) {
                    var posInRandomQueue = randomQueue.indexOf(song.id);
                    if (posInRandomQueue >= 0) {
                        randomIndex = posInRandomQueue;
                    }
                } else {
                    currentIndex = songIndexInPlaylist;
                }
            }

            if (typeof saveSongCacheToStorage === 'function') {
                saveSongCacheToStorage(song.id, {
                    url: songData.url,
                    name: songData.name,
                    artist: songData.artist,
                    pic: songData.pic,
                    duration: songData.durationStr || songData.duration,
                    album: songData.album || ""
                });
            }

            if (USE_NATIVE_PLAYER && window.AndroidBridge && typeof window.AndroidBridge.nativeLoadAndPlay === 'function') {
                player.pause();
                player.src = '';
                var result = typeof nativeLoadAndPlay === 'function' ? nativeLoadAndPlay(songData.url, 0) : false;
                if (result) {
                    if (lyricsEnabled && typeof loadLyrics === 'function') {
                        lyricRetryCount = 0;
                        loadLyrics(song.id);
                    }
                    updatePlaylistCurrentInfo();

                    // 触发Native音频缓存下载（异步，不影响当前播放）
                    if (typeof AndroidBridge.xiaZaiYinPinHuanCun === 'function') {
                        try {
                            AndroidBridge.xiaZaiYinPinHuanCun(String(song.id), songData.name || '');
                        } catch(e) {}
                    }
                    if (typeof startSyncInterval === 'function') startSyncInterval();
                } else {
                    console.warn("原生播放器播放失败，回退到Web Audio");
                    playWithWebAudio(songData.url, song.id);
                }
            } else {
                playWithWebAudio(songData.url, song.id);
            }
        } else {
            if (typeof hideLoading提示 === 'function') hideLoading提示();
            showToast('歌曲加载失败', 'error', 2500);
        }
    } catch (error) {
        console.error('获取歌曲信息失败:', error);
        if (typeof handleApiError === 'function') handleApiError(error, song.id);
    }
}

// 使用Web Audio播放歌曲（搜索结果）
function playWithWebAudio(url, songId, seekTime) {
    seekTime = seekTime || 0;
    player.src = url;
    player.load();

    var startPlay = function() {
        if (seekTime > 0) {
            player.currentTime = seekTime;
        }
        player.play().then(function() {
            isLoading = false;
            isInitialized = true;
            if (typeof enableButtons === 'function') enableButtons();
            if (typeof hideLoading提示 === 'function') hideLoading提示();
            if (typeof startSyncInterval === 'function') startSyncInterval();

            if (lyricsEnabled && typeof loadLyrics === 'function') {
                lyricRetryCount = 0;
                loadLyrics(songId);
            }

            updatePlaylistCurrentInfo();
        }).catch(function(err) {
            console.error('播放失败:', err);
            if (typeof hideLoading提示 === 'function') hideLoading提示();
        });
    };

    if (seekTime > 0) {
        player.addEventListener('canplay', function onCanPlay() {
            player.removeEventListener('canplay', onCanPlay);
            startPlay();
        }, { once: true });
    } else {
        startPlay();
    }
}

// 使用缓存播放搜索结果的歌曲
function playSearchResultWithCache(song, cachedData) {
    isLoading = true;
    if (typeof disableButtons === 'function') disableButtons();
    if (typeof showLoading提示 === 'function') showLoading提示("从缓存加载...");

    if (loadingTimeoutId) clearTimeout(loadingTimeoutId);
    loadingTimeoutId = setTimeout(function() {
        if (isLoading && currentPlayingSongId === song.id) {
            console.warn("缓存播放超时，删除过期缓存并从API重新加载");
            localStorage.removeItem(CACHE_CONFIG.SONG_CACHE_PREFIX + song.id);
            isLoading = false;
            if (typeof enableButtons === 'function') enableButtons();
            if (typeof hideLoading提示 === 'function') hideLoading提示();
            var idx = searchResults.indexOf(song);
            if (idx >= 0) {
                searchResults.splice(idx, 1, song);
                playSearchResult(idx);
            }
        }
    }, LOADING_TIMEOUT_MS);

    currentCoverImage = cachedData.pic || defaultCover;
    songTitle.textContent = cachedData.name;
    songArtist.textContent = cachedData.artist;

    if (cachedData.duration) {
        var parts = cachedData.duration.split(':');
        totalDuration = (parseInt(parts[0]) * 60 + parseInt(parts[1])) * 1000;
        totalTimeDisplay.textContent = cachedData.duration;
    }

    if (isInitialized) {
        if (typeof switchAlbumImage === 'function') switchAlbumImage(currentCoverImage);
    } else {
        if (typeof setCoverImagesDirect === 'function') setCoverImagesDirect(currentCoverImage);
    }

    if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = new MediaMetadata({
            title: cachedData.name,
            artist: cachedData.artist,
            album: cachedData.album || "",
            artwork: [
                { src: currentCoverImage, sizes: '96x96', type: 'image/jpeg' },
                { src: currentCoverImage, sizes: '256x256', type: 'image/jpeg' }
            ]
        });
    }

    if (typeof notifyNativeSongInfo === 'function') notifyNativeSongInfo();
    currentPlayingSongId = song.id;

    var songIndexInPlaylist = songIds.indexOf(song.id);
    if (songIndexInPlaylist >= 0) {
        if (playMode === PlayMode.RANDOM) {
            var posInRandomQueue = randomQueue.indexOf(song.id);
            if (posInRandomQueue >= 0) {
                randomIndex = posInRandomQueue;
            }
        } else {
            currentIndex = songIndexInPlaylist;
        }
        currentSearchSong = null;
    } else {
        currentSearchSong = { id: song.id, name: song.name, artist: song.artist || '' };
        console.log('[搜索播放详细] 标记为搜索歌曲(缓存): ' + song.name);
    }

    if (USE_NATIVE_PLAYER && window.AndroidBridge && typeof window.AndroidBridge.nativeLoadAndPlay === 'function') {
        player.pause();
        player.src = '';
        var result = typeof nativeLoadAndPlay === 'function' ? nativeLoadAndPlay(cachedData.url, 0) : false;
        if (result) {
            if (lyricsEnabled && typeof loadLyrics === 'function') {
                lyricRetryCount = 0;
                loadLyrics(song.id);
            }
            updatePlaylistCurrentInfo();

            // 触发Native音频缓存下载（异步，不影响当前播放）
            if (typeof AndroidBridge.xiaZaiYinPinHuanCun === 'function') {
                try {
                    AndroidBridge.xiaZaiYinPinHuanCun(String(song.id), cachedData.name || '');
                } catch(e) {}
            }
            if (typeof startSyncInterval === 'function') startSyncInterval();
        } else {
            console.warn("原生播放器缓存播放失败，删除缓存并从API重新加载");
            localStorage.removeItem(CACHE_CONFIG.SONG_CACHE_PREFIX + song.id);
            if (loadingTimeoutId) { clearTimeout(loadingTimeoutId); loadingTimeoutId = null; }
            isLoading = false;
            if (typeof enableButtons === 'function') enableButtons();
            if (typeof hideLoading提示 === 'function') hideLoading提示();
            var idx = searchResults.indexOf(song);
            if (idx >= 0) {
                playSearchResult(idx);
            }
        }
    } else {
        player.src = cachedData.url;
        playSearchResultWithWebAudio(song, cachedData);
    }
}

// 使用Web Audio播放搜索结果的缓存歌曲
function playSearchResultWithWebAudio(song, cachedData) {
    if (loadingTimeoutId) { clearTimeout(loadingTimeoutId); loadingTimeoutId = null; }
    player.src = cachedData.url;
    player.load();
    player.play().then(function() {
        if (loadingTimeoutId) { clearTimeout(loadingTimeoutId); loadingTimeoutId = null; }
        isLoading = false;
        isInitialized = true;
        if (typeof enableButtons === 'function') enableButtons();
        if (typeof hideLoading提示 === 'function') hideLoading提示();
        if (typeof startSyncInterval === 'function') startSyncInterval();

        if (lyricsEnabled && typeof loadLyrics === 'function') {
            lyricRetryCount = 0;
            loadLyrics(song.id);
        }

        updatePlaylistCurrentInfo();
        showToast('已从缓存播放', 'info', 1500);
    }).catch(function(err) {
        console.error('缓存播放失败:', err);
        localStorage.removeItem(CACHE_CONFIG.SONG_CACHE_PREFIX + song.id);
        if (loadingTimeoutId) { clearTimeout(loadingTimeoutId); loadingTimeoutId = null; }
        isLoading = false;
        if (typeof enableButtons === 'function') enableButtons();
        if (typeof hideLoading提示 === 'function') hideLoading提示();
        console.log('缓存URL已失效，重新从API加载');
        var idx = searchResults.indexOf(song);
        if (idx >= 0) {
            var songCopy = Object.assign({}, song, { _skipCache: true });
            searchResults[idx] = songCopy;
            playSearchResult(idx);
        }
    });
}

// ==================== 搜索面板控制 ====================

function toggleSearchPanel() {
    if (searchPanel.classList.contains('show')) {
        closeSearchPanel();
    } else {
        openSearchPanel();
    }
}

function openSearchPanel() {
    searchPanel.classList.add('show');
    searchToggle.classList.add('active');

    var isMobile = window.innerWidth <= 768;
    if (isMobile && typeof searchOverlay !== 'undefined' && searchOverlay) {
        searchOverlay.classList.add('show');
    }

    xianShiSouSuoLiShiHuoZhanWei();

    setTimeout(function() {
        searchInput.focus();
    }, 100);
}

function closeSearchPanel() {
    searchPanel.classList.remove('show');
    searchToggle.classList.remove('active');

    if (typeof searchOverlay !== 'undefined' && searchOverlay) {
        searchOverlay.classList.remove('show');
    }
}

// ==================== 导入功能 ====================

function showImportPanel() {
    searchPanel.classList.remove('show');
    importPanel.classList.add('show');
    var isMobile = window.innerWidth <= 768;
    if (isMobile && typeof searchOverlay !== 'undefined' && searchOverlay) {
        searchOverlay.classList.add('show');
    }
    importTextarea.value = '';
    hideImportProgress();
}

function hideImportPanel() {
    importPanel.classList.remove('show');
    searchPanel.classList.add('show');
    if (typeof searchOverlay !== 'undefined' && searchOverlay) {
        searchOverlay.classList.add('show');
    }
    hideImportProgress();
}

function showImportToast(results) {
    var message = '成功：' + results.success.length + ' 首';
    if (results.failed.length > 0) {
        message += '\n失败：' + results.failed.length + ' 首';
        message += '\n\n失败歌曲：' + results.failed.slice(0, 5).map(function(s) { return s.name; }).join('、');
        if (results.failed.length > 5) {
            message += ' 等共 ' + results.failed.length + ' 首';
        }
    }
    importToastContent.textContent = message;
    importToast.classList.add('show');

    setTimeout(function() {
        hideImportToast();
    }, 3000);
}

function hideImportToast() {
    importToast.classList.remove('show');
}

function showImportProgress() {
    importProgressContainer.style.display = 'block';
}

function hideImportProgress() {
    importProgressContainer.style.display = 'none';
    importProgressFill.style.width = '0%';
}

function updateImportProgress(current, total, songName) {
    var percentage = Math.round((current / total) * 100);
    importProgressFill.style.width = percentage + '%';
    importProgressText.textContent = '正在导入 ' + songName + ' (' + current + '/' + total + ')';
}

// 开始导入歌曲
async function startImportSongs() {
    var text = importTextarea.value.trim();
    if (!text) {
        showToast('请输入要导入的歌曲列表', 'warning', 2000);
        return;
    }

    importBtnConfirm.disabled = true;
    importBtnConfirm.textContent = '导入中...';

    var songs = parseImportText(text);
    if (songs.length === 0) {
        showToast('未能识别有效的歌曲格式', 'warning', 2500);
        importBtnConfirm.disabled = false;
        importBtnConfirm.textContent = '确定导入';
        return;
    }

    showImportProgress();

    var results = { success: [], failed: [] };

    for (var i = 0; i < songs.length; i++) {
        var song = songs[i];
        updateImportProgress(i + 1, songs.length, song.name);

        try {
            var result = await importSingleSong(song);
            if (result.success) {
                results.success.push(result);
            } else {
                results.failed.push(Object.assign({}, song, { reason: result.reason }));
            }
        } catch (error) {
            results.failed.push(Object.assign({}, song, { reason: '导入失败' }));
        }

        await new Promise(function(resolve) { setTimeout(resolve, 300); });
    }

    hideImportProgress();
    importBtnConfirm.disabled = false;
    importBtnConfirm.textContent = '确定导入';

    showImportToast(results);

    if (results.success.length > 0 && typeof window.savePlaylistToAndroid === 'function') {
        window.savePlaylistToAndroid().then(function(success) {
            if (success) {
                console.log('[Import] 播放列表已保存到 Android');
            }
        });
    }

    importTextarea.value = '';
}

// 解析导入文本
function parseImportText(text) {
    var lines = text.split('\n').filter(function(line) { return line.trim(); });
    var songs = [];

    for (var i = 0; i < lines.length; i++) {
        var parsed = parseSongLine(lines[i].trim());
        if (parsed) {
            songs.push(parsed);
        }
    }

    return songs;
}

// 解析单行歌曲信息
function parseSongLine(line) {
    var match = line.match(/^(.+?)\s*[-\u2013\u2014]\s*(.+)$/);
    if (!match) return null;

    var name = match[1].trim();
    var artistsText = match[2].trim();
    var artists = artistsText.split(/[\/\\,，&]/).map(function(a) { return a.trim(); }).filter(function(a) { return a; });

    return { name: name, artists: artists };
}

// 导入单首歌曲
async function importSingleSong(song) {
    console.log('[导入详细] 开始导入歌曲: name="' + song.name + '"');
    try {
        var searchData;
        if (window.AndroidBridge && typeof window.AndroidBridge.nativeSearchSongsAsync === 'function') {
            searchData = await new Promise(function(resolve, reject) {
                var callbackName = '_importSearchCallback_' + Date.now();
                window[callbackName] = function(resultJson) {
                    try {
                        resolve(JSON.parse(resultJson));
                    } catch (e) {
                        reject(e);
                    } finally {
                        delete window[callbackName];
                    }
                };
                window.AndroidBridge.nativeSearchSongsAsync(song.name, 1, callbackName);
            });
        } else {
            var searchApiUrl = window.__SEARCH_API_URL__;
            var searchApiToken = window.__SEARCH_API_TOKEN__;
            if (!searchApiUrl || !searchApiToken) { return { success: false, reason: '搜索API未配置' }; }
            var response = await axios.get(searchApiUrl + '?gm=' + encodeURIComponent(song.name) + '&n&br=lossless&token=' + encodeURIComponent(searchApiToken), { timeout: 10000 });
            searchData = normalizeApicxSearchResponse(response.data);
        }

        if (!searchData || !searchData.result || !searchData.result.songs || searchData.result.songs.length === 0) {
            return { success: false, reason: '未找到歌曲' };
        }

        var searchSongResults = searchData.result.songs;
        var matchedSong = findBestMatch(song, searchSongResults);

        if (!matchedSong) {
            return { success: false, reason: '未找到匹配歌手' };
        }

        if (songIds.includes(matchedSong.id)) {
            return { success: false, reason: '已在播放列表中' };
        }

        var detailData;
        try {
            var result = await (typeof fetchSongDetailViaAxios === 'function' ? fetchSongDetailViaAxios(String(matchedSong.id), matchedSong.name || '') : Promise.reject(new Error('fetchSongDetailViaAxios不可用')));
            detailData = result.data;
            if (!detailData || detailData.code !== 200 || !detailData.data) {
                throw new Error('API返回无效数据');
            }
        } catch (e) {
            console.error('[导入详细] fetchSongDetailViaAxios失败:', e.message);
            return { success: false, reason: '获取歌曲详情失败' };
        }

        var parsedData = typeof parseApiResponse === 'function' ? parseApiResponse(detailData, matchedSong.id) : null;
        if (!parsedData || !parsedData.url) {
            return { success: false, reason: '无法获取歌曲详情' };
        }

        var songData = parsedData;
        songIds.push(matchedSong.id);

        var songInfo = {
            id: matchedSong.id,
            index: songIds.length - 1,
            name: songData.name || matchedSong.name,
            artist: songData.artist || matchedSong.artists.map(function(a) { return a.name; }).join(' / '),
            pic: guiFanHuaFengMianUrl(songData.pic || defaultCover),
            source: 'wy'
        };

        playlistData.push(songInfo);
        playlistData.sort(function(a, b) { return a.index - b.index; });

        addSongToPlaylistCache(songInfo);
        if (typeof saveSongCacheToStorage === 'function') {
            saveSongCacheToStorage(matchedSong.id, {
                url: songData.url,
                name: songData.name,
                artist: songData.artist,
                pic: songData.pic,
                duration: songData.duration,
                album: songData.album || ''
            });
        }

        if (playlistPanel.classList.contains('show')) {
            renderPlaylist();
        }

        return { success: true, song: songInfo };
    } catch (error) {
        console.error('[导入详细] 导入失败:', error.message);
        return { success: false, reason: '网络错误' };
    }
}

// 查找最匹配的歌曲
function findBestMatch(targetSong, searchSongResults) {
    var bestMatch = null;
    var bestScore = 0;

    for (var i = 0; i < searchSongResults.length; i++) {
        var score = calculateMatchScore(targetSong, searchSongResults[i]);
        if (score > bestScore && score >= 0.6) {
            bestScore = score;
            bestMatch = searchSongResults[i];
        }
    }

    return bestMatch;
}

// 计算匹配分数
function calculateMatchScore(target, result) {
    var nameScore = calculateSimilarity(target.name, result.name);

    var artistScore = 0;
    var resultArtists = result.artists.map(function(a) { return a.name; });

    for (var i = 0; i < target.artists.length; i++) {
        for (var j = 0; j < resultArtists.length; j++) {
            var sim = calculateSimilarity(target.artists[i], resultArtists[j]);
            if (sim > artistScore) {
                artistScore = sim;
            }
        }
    }

    return nameScore * 0.6 + artistScore * 0.4;
}

// 计算文本相似度（编辑距离算法）
function calculateSimilarity(str1, str2) {
    str1 = str1.toLowerCase().trim();
    str2 = str2.toLowerCase().trim();

    if (str1 === str2) return 1;
    if (str1.includes(str2) || str2.includes(str1)) return 0.9;

    var len1 = str1.length;
    var len2 = str2.length;

    var matrix = [];
    for (var i = 0; i <= len1; i++) {
        matrix[i] = [i];
    }
    for (var j = 0; j <= len2; j++) {
        matrix[0][j] = j;
    }

    for (var i = 1; i <= len1; i++) {
        for (var j = 1; j <= len2; j++) {
            var cost = str1[i - 1] === str2[j - 1] ? 0 : 1;
            matrix[i][j] = Math.min(
                matrix[i - 1][j] + 1,
                matrix[i][j - 1] + 1,
                matrix[i - 1][j - 1] + cost
            );
        }
    }

    var maxLen = Math.max(len1, len2);
    var distance = matrix[len1][len2];

    return 1 - distance / maxLen;
}

// ==================== 设置面板 ====================

function toggleSettingsPanel() {
    if (!settingsPanel) return;
    if (settingsPanel.classList.contains('show')) {
        closeSettingsPanel();
    } else {
        openSettingsPanel();
    }
}

function openSettingsPanel() {
    if (!settingsPanel || !settingsToggle) return;
    if (typeof closePlaylist === 'function') closePlaylist();
    if (typeof closeSearchPanel === 'function') closeSearchPanel();
    settingsPanel.classList.add('show');
    settingsToggle.classList.add('active');
    if (typeof settingsOverlay !== 'undefined' && settingsOverlay) {
        settingsOverlay.classList.add('show');
    }
}

function closeSettingsPanel() {
    if (!settingsPanel || !settingsToggle) return;
    settingsPanel.classList.remove('show');
    settingsToggle.classList.remove('active');
    if (typeof settingsOverlay !== 'undefined' && settingsOverlay) {
        settingsOverlay.classList.remove('show');
    }
}

function handleExportLog() {
    try {
        if (typeof AndroidBridge !== 'undefined' && AndroidBridge.daoChuRiZhi) {
            var result = AndroidBridge.daoChuRiZhi();
            console.log('[日志导出]', result);
            showToast('日志已导出，请选择分享方式', 'success', 3000);
        } else {
            showToast('当前环境不支持导出日志', 'warning', 3000);
        }
    } catch (err) {
        console.error('[日志导出失败]', err);
        showToast('导出失败: ' + err.message, 'error', 3000);
    }
}

function handleClearLog() {
    try {
        if (typeof AndroidBridge !== 'undefined' && AndroidBridge.qingKongRiZhi) {
            AndroidBridge.qingKongRiZhi();
            showToast('日志已清空', 'info', 2000);
        } else {
            showToast('当前环境不支持清空日志', 'warning', 3000);
        }
    } catch (err) {
        console.error('[清空日志失败]', err);
        showToast('清空失败: ' + err.message, 'error', 3000);
    }
}

// ==================== 缓存管理 ====================

var cacheManagerPanel = null;
var cacheTabUrl = null;
var cacheTabAudio = null;
var cacheTabIndicator = null;
var cacheManagerList = null;
var cacheTotalSize = null;
var cacheClearAll = null;
var cacheManagerBack = null;
var currentCacheType = 'url';

function initCacheManager() {
    cacheManagerPanel = document.getElementById('cacheManagerPanel');
    cacheTabUrl = document.getElementById('cacheTabUrl');
    cacheTabAudio = document.getElementById('cacheTabAudio');
    cacheTabIndicator = document.getElementById('cacheTabIndicator');
    cacheManagerList = document.getElementById('cacheManagerList');
    cacheTotalSize = document.getElementById('cacheTotalSize');
    cacheClearAll = document.getElementById('cacheClearAll');
    cacheManagerBack = document.getElementById('cacheManagerBack');
    cacheManagerOverlay = document.getElementById('cacheManagerOverlay');

    var openCacheManagerItem = document.getElementById('openCacheManagerItem');
    if (openCacheManagerItem) {
        openCacheManagerItem.addEventListener('click', openCacheManager);
    }
    if (cacheManagerBack) {
        cacheManagerBack.addEventListener('click', closeCacheManager);
    }
    if (cacheManagerOverlay) {
        cacheManagerOverlay.addEventListener('click', closeCacheManager);
    }
    if (cacheTabUrl) {
        cacheTabUrl.addEventListener('click', function() { switchCacheTab('url'); });
    }
    if (cacheTabAudio) {
        cacheTabAudio.addEventListener('click', function() { switchCacheTab('audio'); });
    }
    if (cacheClearAll) {
        cacheClearAll.addEventListener('click', clearAllCache);
    }
}

function openCacheManager() {
    if (!cacheManagerPanel) return;
    closeSettingsPanel();
    cacheManagerPanel.classList.add('show');
    if (typeof cacheManagerOverlay !== 'undefined' && cacheManagerOverlay) {
        cacheManagerOverlay.classList.add('show');
    }
    switchCacheTab('url');
}

function closeCacheManager() {
    if (!cacheManagerPanel) return;
    cacheManagerPanel.classList.remove('show');
    if (typeof cacheManagerOverlay !== 'undefined' && cacheManagerOverlay) {
        cacheManagerOverlay.classList.remove('show');
    }
}

function switchCacheTab(type) {
    currentCacheType = type;
    if (cacheTabUrl) cacheTabUrl.classList.toggle('active', type === 'url');
    if (cacheTabAudio) cacheTabAudio.classList.toggle('active', type === 'audio');
    if (cacheTabIndicator) cacheTabIndicator.classList.toggle('audio', type === 'audio');
    loadCacheList(type);
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function loadCacheList(type) {
    if (!cacheManagerList) return;
    cacheManagerList.innerHTML = '';

    if (type === 'url') {
        loadUrlCacheList();
    } else {
        loadAudioCacheList();
    }
}

function loadUrlCacheList() {
    var cacheItems = [];
    var totalSize = 0;

    for (var i = 0; i < localStorage.length; i++) {
        var key = localStorage.key(i);
        if (key && key.startsWith('netease_song_cache_')) {
            try {
                var data = JSON.parse(localStorage.getItem(key));
                var songId = key.replace('netease_song_cache_', '');
                var size = new Blob([localStorage.getItem(key)]).size;
                totalSize += size;

                var songName = data.name || '';
                var songArtist = data.artist || '';

                // 如果缓存中名字/歌手为空，尝试从播放列表中查找
                if ((!songName || !songArtist) && typeof playlistData !== 'undefined') {
                    for (var _j = 0; _j < playlistData.length; _j++) {
                        var ps = playlistData[_j];
                        var psId = String(ps.id);
                        if (psId === songId || psId === songId.replace(/^(qq_|wy_)/, '')) {
                            if (!songName) songName = ps.name || '';
                            if (!songArtist) songArtist = ps.artist || '';
                            break;
                        }
                    }
                }

                cacheItems.push({
                    id: songId,
                    name: songName || '未知歌曲',
                    artist: songArtist || '未知歌手',
                    size: size
                });
            } catch (e) {
                // 忽略解析错误
            }
        }
    }

    if (cacheTotalSize) {
        cacheTotalSize.textContent = '总大小: ' + formatSize(totalSize);
    }

    if (cacheItems.length === 0) {
        cacheManagerList.innerHTML = '<div class="cache-empty"><i class="fa-solid fa-database"></i>暂无URL缓存</div>';
        return;
    }

    cacheItems.forEach(function(item) {
        var div = document.createElement('div');
        div.className = 'cache-item';
        div.innerHTML = 
            '<div class="cache-item-info">' +
                '<div class="cache-item-name">' + item.name + '</div>' +
                '<div class="cache-item-artist">' + item.artist + '</div>' +
            '</div>' +
            '<div class="cache-item-size">' + formatSize(item.size) + '</div>' +
            '<button class="cache-item-delete" data-id="' + item.id + '" data-type="url"><i class="fa-solid fa-trash"></i></button>';
        cacheManagerList.appendChild(div);
    });

    cacheManagerList.querySelectorAll('.cache-item-delete').forEach(function(btn) {
        btn.addEventListener('click', function() {
            deleteCacheItem(this.dataset.id, this.dataset.type);
        });
    });
}

function loadAudioCacheList() {
    if (typeof AndroidBridge !== 'undefined' && AndroidBridge.huoQuYinPinHuanCunLieBiao) {
        try {
            var result = AndroidBridge.huoQuYinPinHuanCunLieBiao();
            var audioCaches = JSON.parse(result || '[]');
            var totalSize = 0;

            audioCaches.forEach(function(item) {
                totalSize += item.size || 0;
            });

            if (cacheTotalSize) {
                cacheTotalSize.textContent = '总大小: ' + formatSize(totalSize);
            }

            if (audioCaches.length === 0) {
                cacheManagerList.innerHTML = '<div class="cache-empty"><i class="fa-solid fa-music"></i>暂无音频缓存</div>';
                return;
            }

            audioCaches.forEach(function(item) {
                var div = document.createElement('div');
                div.className = 'cache-item';
                div.innerHTML = 
                    '<div class="cache-item-info">' +
                        '<div class="cache-item-name">' + (item.name || '未知歌曲') + '</div>' +
                        '<div class="cache-item-artist">' + (item.artist || '未知歌手') + '</div>' +
                    '</div>' +
                    '<div class="cache-item-size">' + formatSize(item.size || 0) + '</div>' +
                    '<button class="cache-item-delete" data-id="' + item.id + '" data-type="audio"><i class="fa-solid fa-trash"></i></button>';
                cacheManagerList.appendChild(div);
            });

            cacheManagerList.querySelectorAll('.cache-item-delete').forEach(function(btn) {
                btn.addEventListener('click', function() {
                    deleteCacheItem(this.dataset.id, this.dataset.type);
                });
            });
        } catch (e) {
            console.error('[缓存管理] 加载音频缓存失败:', e);
            cacheManagerList.innerHTML = '<div class="cache-empty"><i class="fa-solid fa-exclamation-circle"></i>加载失败</div>';
        }
    } else {
        cacheManagerList.innerHTML = '<div class="cache-empty"><i class="fa-solid fa-mobile-screen"></i>原生接口不可用</div>';
    }
}

function deleteCacheItem(id, type) {
    if (type === 'url') {
        localStorage.removeItem('netease_song_cache_' + id);
        showToast('已删除URL缓存', 'info', 1500);
    } else {
        if (typeof AndroidBridge !== 'undefined' && AndroidBridge.shanChuYinPinHuanCun) {
            AndroidBridge.shanChuYinPinHuanCun(id);
            showToast('已删除音频缓存', 'info', 1500);
        }
    }
    loadCacheList(type);
}

function clearAllCache() {
    if (currentCacheType === 'url') {
        var keysToRemove = [];
        for (var i = 0; i < localStorage.length; i++) {
            var key = localStorage.key(i);
            if (key && key.startsWith('netease_song_cache_')) {
                keysToRemove.push(key);
            }
        }
        keysToRemove.forEach(function(key) {
            localStorage.removeItem(key);
        });
        showToast('已清空所有URL缓存', 'success', 2000);
    } else {
        if (typeof AndroidBridge !== 'undefined' && AndroidBridge.qingKongYinPinHuanCun) {
            AndroidBridge.qingKongYinPinHuanCun();
            showToast('已清空所有音频缓存', 'success', 2000);
        }
    }
    loadCacheList(currentCacheType);
}

// ==================== 悬浮窗歌词设置 ====================

// 初始化悬浮窗歌词设置
function initXuanFuGeCiSettings() {
    var xuanFuKaiGuanItem = document.getElementById('xuanFuKaiGuanItem');
    var xuanFuSwitchItem = document.getElementById('xuanFuSwitchItem');
    var xuanFuToggle = document.getElementById('xuanFuToggle');
    var xuanFuSettingsModal = document.getElementById('xuanFuSettingsModal');
    var xuanFuSettingsOverlay = document.getElementById('xuanFuSettingsOverlay');
    var xuanFuPermissionBtn = document.getElementById('xuanFuPermissionBtn');
    var xuanFuPermissionSection = document.getElementById('xuanFuPermissionSection');
    var xuanFuConfigSection = document.getElementById('xuanFuConfigSection');

    function hasPermission() {
        if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.youXuanFuChuangQuanXian === 'function') {
            try { return AndroidBridge.youXuanFuChuangQuanXian(); } catch(e) {}
        }
        return false;
    }

    function updatePermissionUI() {
        if (!xuanFuPermissionSection || !xuanFuConfigSection) return;
        if (hasPermission()) {
            xuanFuPermissionSection.style.display = 'none';
            xuanFuConfigSection.style.display = 'block';
        } else {
            xuanFuPermissionSection.style.display = 'block';
            xuanFuConfigSection.style.display = 'none';
        }
    }

    function openModal() {
        if (!xuanFuSettingsModal) return;
        closeSettingsPanel();
        updatePermissionUI();
        gengXinXuanFuKaiGuanZhuangTai();
        jiaZaiXuanFuPeiZhi();
        xuanFuSettingsModal.classList.add('show');
        if (xuanFuSettingsOverlay) {
            xuanFuSettingsOverlay.classList.add('show');
        }
    }

    function closeModal() {
        if (!xuanFuSettingsModal) return;
        xuanFuSettingsModal.classList.remove('show');
        if (xuanFuSettingsOverlay) {
            xuanFuSettingsOverlay.classList.remove('show');
        }
    }

    // 点击设置入口打开弹窗
    if (xuanFuKaiGuanItem) {
        xuanFuKaiGuanItem.addEventListener('click', openModal);
    }

    // 点击遮罩层关闭弹窗
    if (xuanFuSettingsOverlay) {
        xuanFuSettingsOverlay.addEventListener('click', closeModal);
    }

    // 点击弹窗背景（空白区域）关闭弹窗
    if (xuanFuSettingsModal) {
        xuanFuSettingsModal.addEventListener('click', function(e) {
            if (e.target === xuanFuSettingsModal) {
                closeModal();
            }
        });
    }

    // 点击权限按钮
    if (xuanFuPermissionBtn) {
        xuanFuPermissionBtn.addEventListener('click', function() {
            if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.qingQiuXuanFuQuanXian === 'function') {
                try { AndroidBridge.qingQiuXuanFuQuanXian(); } catch(e) {}
            }
        });
    }

    // 弹窗内开关
    if (xuanFuSwitchItem && xuanFuToggle) {
        xuanFuSwitchItem.addEventListener('click', function() {
            var isOn = xuanFuToggle.classList.contains('active');
            if (isOn) {
                if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.guanBiXuanFuGeCi === 'function') {
                    try { AndroidBridge.guanBiXuanFuGeCi(); } catch(e) {}
                }
                xuanFuToggle.classList.remove('active');
                window.xuanFuGeCiYiKaiQi = false;
            } else {
                if (hasPermission()) {
                    if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.kaiQiXuanFuGeCi === 'function') {
                        try { AndroidBridge.kaiQiXuanFuGeCi(); } catch(e) {}
                    }
                    xuanFuToggle.classList.add('active');
                    window.xuanFuGeCiYiKaiQi = true;
                } else {
                    if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.qingQiuXuanFuQuanXian === 'function') {
                        try { AndroidBridge.qingQiuXuanFuQuanXian(); } catch(e) {}
                    }
                }
            }
        });
    }

    // 对齐按钮
    var duiQiRow = document.getElementById('xuanFuDuiQiRow');
    if (duiQiRow) {
        var btns = duiQiRow.querySelectorAll('.xuanfu-btn');
        btns.forEach(function(btn) {
            btn.addEventListener('click', function() {
                btns.forEach(function(b) { b.classList.remove('active'); });
                btn.classList.add('active');
                var duiQi = btn.getAttribute('data-duiqi');
                if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.sheZhiXuanFuDuiQi === 'function') {
                    try { AndroidBridge.sheZhiXuanFuDuiQi(duiQi); } catch(e) {}
                }
            });
        });
    }

    // 滑块事件：拖动结束后再同步原生
    bangDingXuanFuHuaKuai('xuanFuZuoYou', 'sheZhiXuanFuZuoYou', true);
    bangDingXuanFuHuaKuai('xuanFuShangXia', 'sheZhiXuanFuShangXia', true);
    bangDingXuanFuHuaKuai('xuanFuKuanDu', 'sheZhiXuanFuKuanDu', true);
    bangDingXuanFuHuaKuai('xuanFuTouMingDu', 'sheZhiXuanFuTouMingDu', true);
    bangDingXuanFuHuaKuai('xuanFuZiHao', 'sheZhiXuanFuZiHao', false);

    // 颜色选择
    var yanSeRow = document.getElementById('xuanFuYanSeRow');
    if (yanSeRow) {
        var swatches = yanSeRow.querySelectorAll('.xuanfu-color-swatch');
        swatches.forEach(function(sw) {
            sw.addEventListener('click', function() {
                swatches.forEach(function(s) { s.classList.remove('active'); });
                sw.classList.add('active');
                var color = sw.getAttribute('data-color');
                if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.sheZhiXuanFuYanSe === 'function') {
                    try { AndroidBridge.sheZhiXuanFuYanSe(color); } catch(e) {}
                }
            });
        });
    }
}

// 绑定滑块事件：change 事件避免拖动过程中频繁重排
function bangDingXuanFuHuaKuai(elementId, bridgeMethod, isInt) {
    var slider = document.getElementById(elementId);
    if (!slider) return;
    slider.addEventListener('change', function() {
        var val = isInt ? parseInt(slider.value) : parseFloat(slider.value);
        if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge[bridgeMethod] === 'function') {
            try { AndroidBridge[bridgeMethod](val); } catch(e) {}
        }
    });
}

// 从Native加载当前配置到UI
function jiaZaiXuanFuPeiZhi() {
    if (typeof AndroidBridge === 'undefined' || typeof AndroidBridge.huoQuXuanFuPeiZhi !== 'function') return;
    try {
        var json = JSON.parse(AndroidBridge.huoQuXuanFuPeiZhi());
        sheZhiHuaKuaiZhi('xuanFuZuoYou', json.weiZhiX || 50);
        sheZhiHuaKuaiZhi('xuanFuShangXia', json.weiZhiY || 300);
        sheZhiHuaKuaiZhi('xuanFuKuanDu', json.kuanDu || 80);
        sheZhiHuaKuaiZhi('xuanFuTouMingDu', json.touMingDu || 90);
        sheZhiHuaKuaiZhi('xuanFuZiHao', json.ziHao || 16);
        // 对齐按钮
        var duiQiRow = document.getElementById('xuanFuDuiQiRow');
        if (duiQiRow) {
            var btns = duiQiRow.querySelectorAll('.xuanfu-btn');
            btns.forEach(function(btn) {
                btn.classList.toggle('active', btn.getAttribute('data-duiqi') === (json.duiQi || 'center'));
            });
        }
        // 颜色选择
        var yanSeRow = document.getElementById('xuanFuYanSeRow');
        if (yanSeRow) {
            var swatches = yanSeRow.querySelectorAll('.xuanfu-color-swatch');
            swatches.forEach(function(sw) {
                sw.classList.toggle('active', sw.getAttribute('data-color') === (json.yanSe || '#1DB954'));
            });
        }
    } catch(e) {}
}

function sheZhiHuaKuaiZhi(id, val) {
    var el = document.getElementById(id);
    if (el) el.value = val;
}

// 更新悬浮窗开关状态
function gengXinXuanFuKaiGuanZhuangTai() {
    var xuanFuToggle = document.getElementById('xuanFuToggle');
    if (!xuanFuToggle) return;
    var isOpen = false;
    if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.xuanFuGeCiShiFouKaiQi === 'function') {
        try { isOpen = AndroidBridge.xuanFuGeCiShiFouKaiQi(); } catch(e) {}
    }
    if (isOpen) {
        xuanFuToggle.classList.add('active');
    } else {
        xuanFuToggle.classList.remove('active');
    }
}

// 悬浮窗权限回调（从原生端调用）
window.onXuanFuQuanXianJieGuo = function(success) {
    var xuanFuToggle = document.getElementById('xuanFuToggle');
    var xuanFuPermissionSection = document.getElementById('xuanFuPermissionSection');
    var xuanFuConfigSection = document.getElementById('xuanFuConfigSection');
    if (success) {
        if (xuanFuPermissionSection) xuanFuPermissionSection.style.display = 'none';
        if (xuanFuConfigSection) xuanFuConfigSection.style.display = 'block';
        if (xuanFuToggle) xuanFuToggle.classList.add('active');
        if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.kaiQiXuanFuGeCi === 'function') {
            try { AndroidBridge.kaiQiXuanFuGeCi(); } catch(e) {}
        }
    }
};

// 初始化悬浮窗设置
setTimeout(initXuanFuGeCiSettings, 500);

// ==================== 搜索Tab切换 ====================

// 搜索Tab点击切换
(function() {
    var tabs = document.querySelectorAll('.search-tab');
    if (tabs.length > 0) {
        tabs.forEach(function(tab) {
            tab.addEventListener('click', function() {
                var source = this.dataset.source;
                if (source === currentSearchTab) return;
                
                // 切换Tab样式
                document.querySelectorAll('.search-tab').forEach(function(t) { t.classList.remove('active'); });
                this.classList.add('active');
                
                currentSearchTab = source;
                searchInput.value = '';
                
                // 切换到对应Tab时优先显示缓存结果
                if (source === 'qq' && qqSearchResults.length > 0) {
                    renderQqSearchResults(qqSearchResults);
                    searchInput.placeholder = '搜索QQ音乐歌曲...';
                } else if (source === 'wy' && searchResults.length > 0) {
                    renderSearchResults(searchResults);
                    searchInput.placeholder = '搜索歌曲、歌手...';
                } else {
                    if (source === 'qq') {
                        searchInput.placeholder = '搜索QQ音乐歌曲...';
                    } else {
                        searchInput.placeholder = '搜索歌曲、歌手...';
                    }
                    xianShiSouSuoLiShiHuoZhanWei();
                }
            });
        });
    }
})();
