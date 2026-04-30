package com.shiyin.music

import android.webkit.JavascriptInterface
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebView JavaScript 接口
 * 实现 H5 与 Android 原生之间的双向通信
 */
class WebAppInterface(
    private val context: MainActivity,
    private val mediaSessionManager: MediaSessionManager,
    private val clipboardHelper: ClipboardHelper,
    private val playlistRepository: PlaylistRepository
) {

    // 原生音频播放器
    private var nativeAudioPlayer: NativeAudioPlayer? = null

    // 当前歌曲信息缓存
    private var currentSongInfo: SongInfo? = null

    // OkHttp 客户端，用于绕过 WebView CORS 限制
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 接收播放状态变化
     * @param stateJson JSON 格式：{"isPlaying": true, "position": 0, "duration": 300000}
     */
    @JavascriptInterface
    fun onPlayStateChanged(stateJson: String) {
        try {
            val json = JSONObject(stateJson)
            val isPlaying = json.optBoolean("isPlaying", false)
            val position = json.optLong("position", 0)
            val duration = json.optLong("duration", 0)

            // 更新 MediaSession 播放状态
            mediaSessionManager.apply {
                // 只在第一次播放时激活 MediaSession，避免循环触发
                if (isPlaying) {
                    setActive(true)
                }
                setPlayState(isPlaying)
                updateProgress(position, duration)
            }

            // 同步控制原生播放器（安全网：防止 JS 状态与原生不同步）
            if (!isPlaying) {
                nativeAudioPlayer?.pause()
            }

            // 启动后台服务（只启动一次，保持后台播放）
            if (isPlaying) {
                context.startPlaybackService()
            } else {
                // 暂停时只释放 WakeLock，不停止服务
                context.releaseWakeLock()
                // 更新 Service 通知为暂停状态
                context.updateServicePlaybackState(false)
            }

        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    /**
     * 接收歌曲信息变化
     * @param infoJson JSON 格式：{"id": "123", "title": "歌名", "artist": "歌手", "cover": "url", "duration": 300000}
     */
    @JavascriptInterface
    fun onSongInfoChanged(infoJson: String) {
        try {
            val json = JSONObject(infoJson)
            val songInfo = SongInfo(
                id = json.optString("id", ""),
                title = json.optString("title", "未知歌曲"),
                artist = json.optString("artist", "未知歌手"),
                album = json.optString("album", ""),
                cover = json.optString("cover", ""),
                duration = json.optLong("duration", 0)
            )

            currentSongInfo = songInfo

            // 更新 MediaSession 元数据
            mediaSessionManager.updateMetadata(
                title = songInfo.title,
                artist = songInfo.artist,
                albumArtUrl = songInfo.cover,
                durationMs = songInfo.duration
            )

            // 更新后台服务的歌曲信息（通知栏显示）
            context.updateServiceMetadata(
                title = songInfo.title,
                artist = songInfo.artist,
                cover = songInfo.cover,
                duration = songInfo.duration
            )

        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    /**
     * 接收播放进度变化
     * 接收播放进度变化
     * @param currentTime 当前播放位置（毫秒）
     * @param duration 总时长（毫秒）
     */
    @JavascriptInterface
    fun onProgressChanged(currentTime: Long, duration: Long) {
        mediaSessionManager.updateProgress(currentTime, duration)
    }

    /**
     * 原生向 H5 请求控制
     * @param action 控制动作：play, pause, previous, next, seek
     * @return 是否成功发送
     */
    @JavascriptInterface
    fun requestControl(action: String): Boolean {
        // 这个方法实际上不会从 H5 调用，
        // 而是 MainActivity 通过 sendCommandToWeb 发送命令到 H5
        return true
    }

    /**
     * 检查原生功能是否可用
     */
    @JavascriptInterface
    fun isNativeReady(): Boolean {
        return true
    }

    /**
     * 获取原生版本信息
     */
    @JavascriptInterface
    fun getNativeVersion(): String {
        return "1.0.0"
    }

    /**
     * 保存完整播放列表
     * @param playlistJson 播放列表 JSON 字符串
     * @return 是否保存成功
     */
    @JavascriptInterface
    fun savePlaylist(playlistJson: String): Boolean {
        return playlistRepository.savePlaylist(playlistJson)
    }

    /**
     * 加载播放列表
     * @return 播放列表 JSON 字符串
     */
    @JavascriptInterface
    fun loadPlaylist(): String {
        return playlistRepository.loadPlaylist() ?: "{\"songs\":[]}"
    }

    /**
     * 添加单首歌曲到播放列表
     * @param songJson 歌曲信息 JSON 字符串 {"id": "123", "name": "歌名", "artist": "歌手", "pic": "url"}
     * @return 是否添加成功
     */
    @JavascriptInterface
    fun addSong(songJson: String): Boolean {
        android.util.Log.d("WebAppInterface", "addSong 被调用: $songJson")
        val result = playlistRepository.addSong(songJson)
        android.util.Log.d("WebAppInterface", "addSong 返回结果: $result")
        return result
    }

    /**
     * 从播放列表删除歌曲
     * @param songId 歌曲 ID
     * @return 是否删除成功
     */
    @JavascriptInterface
    fun removeSong(songId: String): Boolean {
        return playlistRepository.removeSong(songId)
    }

    /**
     * 获取播放列表存储路径
     */
    @JavascriptInterface
    fun getPlaylistPath(): String {
        return "file://${playlistRepository.getPlaylistFilePath()}"
    }

    // ==================== 原生音频播放接口 ====================

    /**
     * 初始化原生音频播放器
     * @return 是否初始化成功
     */
    @JavascriptInterface
    fun initNativePlayer(): Boolean {
        return try {
            val mediaSession = mediaSessionManager.getMediaSession()
            if (mediaSession == null) {
                android.util.Log.e("WebAppInterface", "MediaSession 为空，无法初始化原生播放器")
                return false
            }
            
            nativeAudioPlayer = NativeAudioPlayer(context, mediaSession).apply {
                // 关键修复：注入播放器到 MediaSessionManager，使锁屏控制能直控原生播放器
                mediaSessionManager.setNativeAudioPlayer(this)
                
                callback = object : NativeAudioPlayer.AudioPlayerCallback {
                    override fun onPrepared(duration: Long) {
                        context.runOnUiThread {
                            context.getWebView().evaluateJavascript(
                                "javascript:onNativePlayerPrepared($duration)",
                                null
                            )
                        }
                    }

                    override fun onProgressChanged(position: Long, duration: Long) {
                        context.runOnUiThread {
                            context.getWebView().evaluateJavascript(
                                "javascript:onNativeProgressChanged($position, $duration)",
                                null
                            )
                        }
                    }

                    override fun onCompletion() {
                        context.runOnUiThread {
                            context.getWebView().evaluateJavascript(
                                "javascript:onNativePlaybackComplete()",
                                null
                            )
                        }
                    }

                    override fun onError(error: String) {
                        context.runOnUiThread {
                            context.getWebView().evaluateJavascript(
                                "javascript:onNativePlayerError('${error.replace("'", "\\'")}')",
                                null
                            )
                        }
                    }

                    override fun onPlayStateChanged(isPlaying: Boolean) {
                        android.util.Log.d("WebAppInterface", "onPlayStateChanged: $isPlaying")
                        context.runOnUiThread {
                            context.getWebView().evaluateJavascript(
                                "javascript:onNativePlayStateChanged($isPlaying)",
                                null
                            )
                        }
                    }
                }
            }
            android.util.Log.d("WebAppInterface", "原生音频播放器初始化成功")
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "初始化原生播放器失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 使用原生播放器加载并播放音频
     * @param url 音频URL
     * @param startPosition 起始位置（毫秒）
     * @return 是否成功开始加载
     */
    @JavascriptInterface
    fun nativeLoadAndPlay(url: String, startPosition: Long = 0): Boolean {
        try {
            if (nativeAudioPlayer == null) {
                val initialized = initNativePlayer()
                if (!initialized) {
                    android.util.Log.e("WebAppInterface", "原生播放器初始化失败")
                    return false
                }
            }
            nativeAudioPlayer?.loadAndPlay(url, startPosition)
            return true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativeLoadAndPlay 错误: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * 原生播放器播放
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativePlay(): Boolean {
        return try {
            val player = nativeAudioPlayer
            if (player != null) {
                player.play()
            } else {
                // 原生播放器已被释放，降级到JS控制HTML5音频
                // 同时通知JS更新播放状态，否则按钮UI不会更新
                evaluateJs("""
                    if(window.player && window.player.paused) window.player.play();
                    if(typeof onNativePlayStateChanged === 'function') onNativePlayStateChanged(true);
                """.trimIndent())
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativePlay 错误: ${e.message}")
            false
        }
    }

    /**
     * 原生播放器暂停
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativePause(): Boolean {
        return try {
            val player = nativeAudioPlayer
            if (player != null) {
                player.pause()
            } else {
                // 原生播放器已被释放，降级到JS控制HTML5音频
                // 同步通知JS更新播放状态，否则 isPlaying 一直为true导致按钮不更新
                evaluateJs("""
                    if(window.player && !window.player.paused) window.player.pause();
                    if(typeof onNativePlayStateChanged === 'function') onNativePlayStateChanged(false);
                """.trimIndent())
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativePause 错误: ${e.message}")
            false
        }
    }
    
    /**
     * 原生播放器恢复播放
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativeResume(): Boolean {
        return try {
            val player = nativeAudioPlayer
            if (player != null) {
                player.resume()
            } else {
                // 原生播放器已被释放，降级到JS控制HTML5音频
                // 同步通知JS更新播放状态
                evaluateJs("""
                    if(window.player && window.player.paused) window.player.play();
                    if(typeof onNativePlayStateChanged === 'function') onNativePlayStateChanged(true);
                """.trimIndent())
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativeResume 错误: ${e.message}")
            false
        }
    }

    /**
     * 原生播放器跳转到指定位置
     * @param position 位置（毫秒）
     * @return 是否成功
     */
    @JavascriptInterface
    fun nativeSeekTo(position: Long): Boolean {
        return try {
            val player = nativeAudioPlayer
            if (player != null) {
                player.seekTo(position)
            } else {
                // 原生播放器已释放，降级到JS通过HTML5 audio跳转
                val seekMs = position
                evaluateJs("if(window.player) window.player.currentTime = $seekMs / 1000;")
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "nativeSeekTo 错误: ${e.message}")
            false
        }
    }

    /**
     * 获取原生播放器当前位置
     */
    @JavascriptInterface
    fun nativeGetCurrentPosition(): Long {
        val player = nativeAudioPlayer
        if (player != null) {
            return player.getCurrentPosition()
        }
        // 原生播放器已释放时，无法同步获取位置，返回0避免错误
        return 0
    }

    /**
     * 获取原生播放器总时长
     */
    @JavascriptInterface
    fun nativeGetDuration(): Long {
        val player = nativeAudioPlayer
        if (player != null) {
            return player.getDuration()
        }
        return 0
    }

    /**
     * 原生播放器是否正在播放
     */
    @JavascriptInterface
    fun nativeIsPlaying(): Boolean {
        return nativeAudioPlayer?.isPlayingState() ?: false
    }

    /**
     * 释放原生播放器资源
     */
    @JavascriptInterface
    fun nativeRelease(): Boolean {
        // 关键修复：同时清除 MediaSessionManager 中的引用
        mediaSessionManager.setNativeAudioPlayer(null)
        nativeAudioPlayer?.release()
        nativeAudioPlayer = null
        return true
    }

    /**
     * 安全读取剪贴板内容（供 H5 调用）
     * @return 剪贴板文本内容，如果无法读取则返回 null
     */
    @JavascriptInterface
    fun readClipboard(): String? {
        return try {
            clipboardHelper.readClipboard()
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "读取剪贴板失败: ${e.message}")
            null
        }
    }

    /**
     * 安全写入剪贴板内容（供 H5 调用）
     * @param text 要写入的文本
     * @return 是否写入成功
     */
    @JavascriptInterface
    fun writeClipboard(text: String): Boolean {
        return try {
            clipboardHelper.writeClipboard(text)
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "写入剪贴板失败: ${e.message}")
            false
        }
    }

    // ==================== 原生网络请求接口（绕过 CORS）====================

    /**
     * 原生搜索歌曲（异步版本，绕过 WebView CORS 限制）
     * @param keyword 搜索关键词
     * @param page 页码（从1开始）
     * @param callback JS 回调函数名
     */
    @JavascriptInterface
    fun nativeSearchSongsAsync(keyword: String, page: Int, callback: String) {
        android.util.Log.d("WebAppInterface", "原生搜索(异步): keyword=$keyword, page=$page, callback=$callback")
        
        try {
            val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "https://apis.netstart.cn/music/search?keywords=$encodedKeyword"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json")
                .addHeader("Referer", "https://apis.netstart.cn/")
                .build()
            
            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    android.util.Log.e("WebAppInterface", "搜索失败: ${e.message}")
                    val errorResult = "{\"code\":-1,\"msg\":\"${e.message?.replace("'", "\\'")}\",\"data\":[]}"
                    invokeJsCallback(callback, errorResult)
                }
                
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        android.util.Log.d("WebAppInterface", "搜索成功，返回 ${responseBody.length} 字符")
                        invokeJsCallback(callback, responseBody)
                    } else {
                        android.util.Log.e("WebAppInterface", "搜索失败: HTTP ${response.code}")
                        val errorResult = "{\"code\":-1,\"msg\":\"请求失败\",\"data\":[]}"
                        invokeJsCallback(callback, errorResult)
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "搜索异常: ${e.message}")
            val errorResult = "{\"code\":-1,\"msg\":\"${e.message?.replace("'", "\\'")}\",\"data\":[]}"
            invokeJsCallback(callback, errorResult)
        }
    }

    /**
     * 原生请求歌曲详情（异步版本，绕过 WebView CORS 限制）
     * @param songId 歌曲 ID
     * @param callback JS 回调函数名
     */
    @JavascriptInterface
    fun nativeGetSongDetailAsync(songId: String, callback: String) {
        android.util.Log.d("WebAppInterface", "原生获取歌曲详情(异步): id=$songId, callback=$callback")
        
        try {
            val url = "https://api.cenguigui.cn/api/netease/music_v1.php?id=$songId&type=json&level=exhigh"
            
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json")
                .build()
            
            httpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    android.util.Log.e("WebAppInterface", "歌曲详情获取失败: ${e.message}")
                    val errorResult = "{\"code\":-1,\"msg\":\"${e.message?.replace("'", "\\'")}\"}"
                    invokeJsCallback(callback, errorResult)
                }
                
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        android.util.Log.d("WebAppInterface", "歌曲详情获取成功")
                        invokeJsCallback(callback, responseBody)
                    } else {
                        android.util.Log.e("WebAppInterface", "歌曲详情获取失败: HTTP ${response.code}")
                        val errorResult = "{\"code\":-1,\"msg\":\"请求失败\"}"
                        invokeJsCallback(callback, errorResult)
                    }
                }
            })
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "歌曲详情获取异常: ${e.message}")
            val errorResult = "{\"code\":-1,\"msg\":\"${e.message?.replace("'", "\\'")}\"}"
            invokeJsCallback(callback, errorResult)
        }
    }

    /**
     * 调用 JS 回调函数
     * @param callback 回调函数名
     * @param result JSON 结果字符串
     */
    private fun invokeJsCallback(callback: String, result: String) {
        try {
            val escapedResult = result.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            
            context.getWebView().post {
                val js = "if(typeof $callback === 'function') $callback('$escapedResult');"
                context.getWebView().evaluateJavascript(js, null)
            }
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "调用 JS 回调失败: ${e.message}")
        }
    }

    /**
     * 在 WebView 主线程执行 JavaScript（用于原生播放器释放后的降级控制）
     * @param js 要执行的 JavaScript 代码
     */
    private fun evaluateJs(js: String) {
        try {
            context.getWebView().post {
                context.getWebView().evaluateJavascript("javascript:try{$js}catch(e){console.error(e)}", null)
            }
        } catch (e: Exception) {
            android.util.Log.e("WebAppInterface", "evaluateJs 失败: ${e.message}")
        }
    }

    /**
     * 歌曲信息数据类
     */
    data class SongInfo(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String,
        val duration: Long
    )
}
