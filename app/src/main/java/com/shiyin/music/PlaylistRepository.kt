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
    }

    private val playlistFile: File
        get() = File(context.filesDir, PLAYLIST_FILE_NAME)

    /**
     * 保存播放列表到本地
     * @param playlistJson 播放列表 JSON 字符串
     * @return 是否保存成功
     */
    fun savePlaylist(playlistJson: String): Boolean {
        return try {
            // 验证 JSON 格式
            JSONObject(playlistJson)

            // 写入文件
            playlistFile.writeText(playlistJson)
            Log.d(TAG, "播放列表已保存到: ${playlistFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存播放列表失败", e)
            false
        }
    }

    /**
     * 从本地加载播放列表
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
                    Log.d(TAG, "播放列表已加载，大小: ${content.length} 字符")
                    content
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
        Log.d(TAG, "addSong 被调用，接收到的 JSON: $songJson")
        return try {
            val currentPlaylist = loadPlaylist()
            Log.d(TAG, "当前播放列表: $currentPlaylist")
            
            // 检查是否为空或无效JSON
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

            // 检查是否已存在相同 ID 的歌曲
            val songId = newSong.optString("id")
            var exists = false
            for (i in 0 until songsArray.length()) {
                val song = songsArray.getJSONObject(i)
                if (song.optString("id") == songId) {
                    exists = true
                    break
                }
            }

            // 如果不存在则添加
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

    /**
     * 从播放列表删除歌曲
     * @param songId 歌曲 ID
     * @return 是否删除成功
     */
    fun removeSong(songId: String): Boolean {
        return try {
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