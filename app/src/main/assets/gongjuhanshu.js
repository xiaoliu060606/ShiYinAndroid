/**
 * 工具函数模块
 * 包含格式化、验证、随机数等通用工具函数
 */

// 全局配置常量（必须在其他模块之前定义）
const USE_NATIVE_PLAYER = true; // 设置为true使用原生播放器，false使用Web Audio
const defaultCover = "./morentupian.jpg";

// 随机数生成
function getRandomNumber(sui) {
    return sui[Math.floor(Math.random() * sui.length)]
}

// 格式化时间（秒 -> mm:ss）
function formatTime(seconds) {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
}

// HTML转义
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 从本地存储加载无版权歌曲列表
function loadNoCopyrightSongs() {
    try {
        const stored = localStorage.getItem('netease_no_copyright_songs');
        if (stored) {
            const list = JSON.parse(stored);
            noCopyrightSongs = new Set(list);
            console.log(`已加载 ${noCopyrightSongs.size} 首无版权歌曲`);
        }
    } catch (e) {
        console.error('加载无版权歌曲列表失败:', e);
    }
}

// 检查歌曲是否为无版权
function isSongNoCopyright(songId) {
    return noCopyrightSongs.has(songId);
}

// 标记歌曲为无版权
function markSongAsNoCopyright(songId) {
    noCopyrightSongs.add(songId);
    // 保存到本地存储
    try {
        const noCopyrightList = Array.from(noCopyrightSongs);
        localStorage.setItem('netease_no_copyright_songs', JSON.stringify(noCopyrightList));
    } catch (e) {
        console.error('保存无版权歌曲列表失败:', e);
    }
    // 重新渲染播放列表以显示标记
    if (typeof renderPlaylist === 'function') renderPlaylist();
}

// 清除歌曲的无版权标记
function clearNoCopyrightMark(songId) {
    noCopyrightSongs.delete(songId);
    try {
        const noCopyrightList = Array.from(noCopyrightSongs);
        localStorage.setItem('netease_no_copyright_songs', JSON.stringify(noCopyrightList));
    } catch (e) {
        console.error('保存无版权歌曲列表失败:', e);
    }
    if (typeof renderPlaylist === 'function') renderPlaylist();
}

// 从本地存储恢复页面状态
function restorePageState() {
    try {
        const stored = localStorage.getItem(CACHE_CONFIG.STATE_KEY);
        if (stored) {
            const state = JSON.parse(stored);
            const timeSinceSave = Date.now() - state.timestamp;

            // 只恢复30分钟内的状态
            if (timeSinceSave < 30 * 60 * 1000) {
                console.log('恢复页面状态:', state);

                // 恢复播放模式
                playMode = state.playMode;
                if (typeof updatePlaylistModeDisplay === 'function') updatePlaylistModeDisplay();

                // 恢复索引
                currentIndex = state.currentIndex;
                randomIndex = state.randomIndex;

                // 恢复进度
                currentProgress = state.currentProgress;
                totalDuration = state.totalDuration;

                // 恢复封面
                if (state.currentCoverImage) {
                    currentCoverImage = state.currentCoverImage;
                    if (typeof setCoverImagesDirect === 'function') setCoverImagesDirect(currentCoverImage);
                }

                // 恢复歌曲信息
                if (state.songTitle && state.songArtist) {
                    songTitle.textContent = state.songTitle;
                    songArtist.textContent = state.songArtist;
                }

                // 恢复总时长显示
                if (typeof formatTime === 'function') {
                    totalTimeDisplay.textContent = formatTime(state.totalDuration / 1000);
                }

                // 更新进度条
                if (typeof updateProgress === 'function') updateProgress();

                console.log('页面状态恢复成功');
            } else {
                console.log('页面状态已过期，不恢复');
                localStorage.removeItem(CACHE_CONFIG.STATE_KEY);
            }
        }
    } catch (e) {
        console.error('恢复页面状态失败:', e);
    }
}

// 清除页面状态
function clearPageState() {
    try {
        localStorage.removeItem(CACHE_CONFIG.STATE_KEY);
    } catch (e) {
        console.error('清除页面状态失败:', e);
    }
}
