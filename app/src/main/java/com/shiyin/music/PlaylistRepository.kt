package com.shiyin.music

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 播放列表数据管理类
 * 负责保存和读取播放列表到应用私有存储
 */
class PlaylistRepository(private val context: Context) {

    companion object {
        private const val TAG = "PlaylistRepository"
        private const val PLAYLIST_FILE_NAME = "playlist.json"
        private val suo = Any()
    }

    private val playlistFile: File
        get() = File(context.filesDir, PLAYLIST_FILE_NAME)

    /**
     * 保存播放列表到本地
     * 关键修复：Android JSONObject.toString() 会将 / 转义为 \/，
     * 导致封面URL被污染为 https:\/\/... 格式，原生层Glide加载失败。
     * 写入前必须将 \/ 还原为 /。
     * @param playlistJson 播放列表 JSON 字符串
     * @return 是否保存成功
     */
    fun savePlaylist(playlistJson: String): Boolean {
        return synchronized(suo) {
            try {
                // 验证JSON有效性
                JSONObject(playlistJson)

                // 安全兜底：规范化封面URL，修复双斜杠污染
                var normalizedJson = guiFanHuaFengMianUrl(playlistJson)

                // 关键修复：Android JSONObject.toString() 会将 / 转义为 \/
                // 必须还原为 / 才能被 Glide 和 WebView 正确加载
                normalizedJson = quXiaoZhuanYi(normalizedJson)

                val tmpFile = File(context.filesDir, "$PLAYLIST_FILE_NAME.tmp")
                tmpFile.writeText(normalizedJson)
                val target = playlistFile
                if (target.exists()) target.delete()
                if (!tmpFile.renameTo(target)) {
                    tmpFile.delete()
                    target.writeText(normalizedJson)
                }
                Log.d(TAG, "播放列表已保存到: ${playlistFile.absolutePath}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存播放列表失败", e)
                false
            }
        }
    }

    /**
     * 去除 Android JSONObject.toString() 产生的 \/ 转义
     * 将 https:\/\/p3.music.126.net\/... 还原为 https://p3.music.126.net/...
     * 仅在 JSON 字符串值内部生效，不会破坏其他转义
     */
    private fun quXiaoZhuanYi(json: String): String {
        return json.replace("\\/", "/")
    }

    /**
     * 规范化 JSON 中的封面 URL：去除路径中的双斜杠
     * 例如: https://p3.music.126.net//abc//def.jpg → https://p3.music.126.net/abc/def.jpg
     */
    private fun guiFanHuaFengMianUrl(json: String): String {
        return try {
            val obj = JSONObject(json)
            val songs = obj.optJSONArray("songs") ?: return json
            var modified = false
            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                val pic = song.optString("pic", "")
                if (pic.isNotEmpty() && pic.contains("://")) {
                    val idx = pic.indexOf("://")
                    if (idx > 0) {
                        val protocol = pic.substring(0, idx + 3)
                        val rest = pic.substring(idx + 3)
                        val normalized = rest.replace(Regex("/{2,}"), "/")
                        if (normalized != rest) {
                            song.put("pic", protocol + normalized)
                            modified = true
                        }
                    }
                }
            }
            if (modified) {
                Log.d(TAG, "封面URL已规范化")
                obj.toString()
            } else {
                json
            }
        } catch (e: Exception) {
            Log.w(TAG, "规范化封面URL失败: ${e.message}")
            json
        }
    }

    /**
     * 从本地加载播放列表
     * 加载时同时修复历史脏数据中的 \/ 转义问题
     * @return 播放列表 JSON 字符串，如果不存在则返回 null
     */
    fun loadPlaylist(): String? {
        return try {
            if (playlistFile.exists()) {
                val content = playlistFile.readText().trim()
                if (content.isEmpty()) {
                    Log.d(TAG, "播放列表文件为空")
                    null
                } else {
                    // 修复历史脏数据：如果文件中仍有 \/ 转义，读取时一并修复
                    val fixedContent = quXiaoZhuanYi(content)
                    Log.d(TAG, "播放列表已加载，大小: ${fixedContent.length} 字符")
                    fixedContent
                }
            } else {
                Log.d(TAG, "播放列表文件不存在")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载播放列表失败", e)
            null
        }
    }

    /**
     * 检查是否有保存的播放列表
     */
    fun hasSavedPlaylist(): Boolean {
        return playlistFile.exists()
    }

    /**
     * 添加单首歌曲到播放列表
     * @param songJson 歌曲信息 JSON 字符串
     * @return 是否添加成功
     */
    fun addSong(songJson: String): Boolean {
        synchronized(suo) {
            Log.d(TAG, "addSong 被调用，接收到的 JSON: $songJson")
            return try {
                val currentPlaylist = loadPlaylist()
                Log.d(TAG, "当前播放列表: $currentPlaylist")

                val jsonObject = if (!currentPlaylist.isNullOrBlank()) {
                    try {
                        JSONObject(currentPlaylist)
                    } catch (e: Exception) {
                        Log.w(TAG, "现有播放列表JSON无效，创建新的")
                        JSONObject().put("songs", JSONArray())
                    }
                } else {
                    JSONObject().put("songs", JSONArray())
                }

                val songsArray = jsonObject.getJSONArray("songs")
                val newSong = JSONObject(songJson)

                val songId = newSong.optString("id")
                var exists = false
                for (i in 0 until songsArray.length()) {
                    val song = songsArray.getJSONObject(i)
                    if (song.optString("id") == songId) {
                        exists = true
                        break
                    }
                }

                if (!exists) {
                    songsArray.put(newSong)
                    jsonObject.put("songs", songsArray)
                    val saveResult = savePlaylist(jsonObject.toString())
                    Log.d(TAG, "歌曲已添加到播放列表: ${newSong.optString("name")}，保存结果: $saveResult")
                } else {
                    Log.d(TAG, "歌曲已存在，跳过添加: ${newSong.optString("name")}")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "添加歌曲失败: ${e.message}", e)
                false
            }
        }
    }

    /**
     * 从播放列表删除歌曲
     * @param songId 歌曲 ID
     * @return 是否删除成功
     */
    fun removeSong(songId: String): Boolean {
        return synchronized(suo) {
            try {
                val currentPlaylist = loadPlaylist() ?: return false
                val jsonObject = JSONObject(currentPlaylist)
                val songsArray = jsonObject.getJSONArray("songs")
                val newArray = JSONArray()

                for (i in 0 until songsArray.length()) {
                    val song = songsArray.getJSONObject(i)
                    if (song.optString("id") != songId) {
                        newArray.put(song)
                    }
                }

                jsonObject.put("songs", newArray)
                savePlaylist(jsonObject.toString())
                Log.d(TAG, "歌曲已从播放列表删除: $songId")
                true
            } catch (e: Exception) {
                Log.e(TAG, "删除歌曲失败", e)
                false
            }
        }
    }

    /**
     * 获取播放列表文件路径（用于 WebView 加载）
     */
    fun getPlaylistFilePath(): String {
        return playlistFile.absolutePath
    }

    /**
     * 初始化默认播放列表（从 assets 复制）
     */
    fun initDefaultPlaylistIfNeeded() {
        if (!hasSavedPlaylist()) {
            try {
                // 从 assets 复制默认播放列表
                context.assets.open(PLAYLIST_FILE_NAME).use { input ->
                    playlistFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "已从 assets 复制默认播放列表")
            } catch (e: Exception) {
                Log.e(TAG, "复制默认播放列表失败", e)
                // 创建一个空的播放列表
                savePlaylist("{\"songs\":[]}")
            }
        }
    }
}