package com.shiyin.music;

/**
 * WebView JavaScript 接口
 * 实现 H5 与 Android 原生之间的双向通信
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000Z\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\u0002\n\u0002\b\n\n\u0002\u0010\t\n\u0002\b\u000e\n\u0002\u0010\b\n\u0002\b\u0013\u0018\u00002\u00020\u0001:\u0001BB%\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007\u0012\u0006\u0010\b\u001a\u00020\t\u00a2\u0006\u0002\u0010\nJ\u0010\u0010\u0011\u001a\u00020\u00122\u0006\u0010\u0013\u001a\u00020\u0014H\u0007J\u0010\u0010\u0015\u001a\u00020\u00162\u0006\u0010\u0017\u001a\u00020\u0014H\u0002J\b\u0010\u0018\u001a\u00020\u0014H\u0007J\b\u0010\u0019\u001a\u00020\u0014H\u0007J\b\u0010\u001a\u001a\u00020\u0012H\u0007J\u0018\u0010\u001b\u001a\u00020\u00162\u0006\u0010\u001c\u001a\u00020\u00142\u0006\u0010\u001d\u001a\u00020\u0014H\u0002J\b\u0010\u001e\u001a\u00020\u0012H\u0007J\b\u0010\u001f\u001a\u00020\u0014H\u0007J\b\u0010 \u001a\u00020!H\u0007J\b\u0010\"\u001a\u00020!H\u0007J\u0018\u0010#\u001a\u00020\u00162\u0006\u0010$\u001a\u00020\u00142\u0006\u0010\u001c\u001a\u00020\u0014H\u0007J\b\u0010%\u001a\u00020\u0012H\u0007J\u001a\u0010&\u001a\u00020\u00122\u0006\u0010\'\u001a\u00020\u00142\b\b\u0002\u0010(\u001a\u00020!H\u0007J\b\u0010)\u001a\u00020\u0012H\u0007J\b\u0010*\u001a\u00020\u0012H\u0007J\b\u0010+\u001a\u00020\u0012H\u0007J\b\u0010,\u001a\u00020\u0012H\u0007J \u0010-\u001a\u00020\u00162\u0006\u0010.\u001a\u00020\u00142\u0006\u0010/\u001a\u0002002\u0006\u0010\u001c\u001a\u00020\u0014H\u0007J\u0010\u00101\u001a\u00020\u00122\u0006\u00102\u001a\u00020!H\u0007J\u0010\u00103\u001a\u00020\u00162\u0006\u00104\u001a\u00020\u0014H\u0007J\u0018\u00105\u001a\u00020\u00162\u0006\u00106\u001a\u00020!2\u0006\u00107\u001a\u00020!H\u0007J\u0010\u00108\u001a\u00020\u00162\u0006\u00109\u001a\u00020\u0014H\u0007J\n\u0010:\u001a\u0004\u0018\u00010\u0014H\u0007J\u0010\u0010;\u001a\u00020\u00122\u0006\u0010$\u001a\u00020\u0014H\u0007J\u0010\u0010<\u001a\u00020\u00122\u0006\u0010=\u001a\u00020\u0014H\u0007J\u0010\u0010>\u001a\u00020\u00122\u0006\u0010?\u001a\u00020\u0014H\u0007J\u0010\u0010@\u001a\u00020\u00122\u0006\u0010A\u001a\u00020\u0014H\u0007R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u000b\u001a\u0004\u0018\u00010\fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\r\u001a\u00020\u000eX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u000f\u001a\u0004\u0018\u00010\u0010X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\tX\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006C"}, d2 = {"Lcom/shiyin/music/WebAppInterface;", "", "context", "Lcom/shiyin/music/MainActivity;", "mediaSessionManager", "Lcom/shiyin/music/MediaSessionManager;", "clipboardHelper", "Lcom/shiyin/music/ClipboardHelper;", "playlistRepository", "Lcom/shiyin/music/PlaylistRepository;", "(Lcom/shiyin/music/MainActivity;Lcom/shiyin/music/MediaSessionManager;Lcom/shiyin/music/ClipboardHelper;Lcom/shiyin/music/PlaylistRepository;)V", "currentSongInfo", "Lcom/shiyin/music/WebAppInterface$SongInfo;", "httpClient", "Lokhttp3/OkHttpClient;", "nativeAudioPlayer", "Lcom/shiyin/music/NativeAudioPlayer;", "addSong", "", "songJson", "", "evaluateJs", "", "js", "getNativeVersion", "getPlaylistPath", "initNativePlayer", "invokeJsCallback", "callback", "result", "isNativeReady", "loadPlaylist", "nativeGetCurrentPosition", "", "nativeGetDuration", "nativeGetSongDetailAsync", "songId", "nativeIsPlaying", "nativeLoadAndPlay", "url", "startPosition", "nativePause", "nativePlay", "nativeRelease", "nativeResume", "nativeSearchSongsAsync", "keyword", "page", "", "nativeSeekTo", "position", "onPlayStateChanged", "stateJson", "onProgressChanged", "currentTime", "duration", "onSongInfoChanged", "infoJson", "readClipboard", "removeSong", "requestControl", "action", "savePlaylist", "playlistJson", "writeClipboard", "text", "SongInfo", "app_debug"})
public final class WebAppInterface {
    @org.jetbrains.annotations.NotNull()
    private final com.shiyin.music.MainActivity context = null;
    @org.jetbrains.annotations.NotNull()
    private final com.shiyin.music.MediaSessionManager mediaSessionManager = null;
    @org.jetbrains.annotations.NotNull()
    private final com.shiyin.music.ClipboardHelper clipboardHelper = null;
    @org.jetbrains.annotations.NotNull()
    private final com.shiyin.music.PlaylistRepository playlistRepository = null;
    @org.jetbrains.annotations.Nullable()
    private com.shiyin.music.NativeAudioPlayer nativeAudioPlayer;
    @org.jetbrains.annotations.Nullable()
    private com.shiyin.music.WebAppInterface.SongInfo currentSongInfo;
    @org.jetbrains.annotations.NotNull()
    private final okhttp3.OkHttpClient httpClient = null;
    
    public WebAppInterface(@org.jetbrains.annotations.NotNull()
    com.shiyin.music.MainActivity context, @org.jetbrains.annotations.NotNull()
    com.shiyin.music.MediaSessionManager mediaSessionManager, @org.jetbrains.annotations.NotNull()
    com.shiyin.music.ClipboardHelper clipboardHelper, @org.jetbrains.annotations.NotNull()
    com.shiyin.music.PlaylistRepository playlistRepository) {
        super();
    }
    
    /**
     * 接收播放状态变化
     * @param stateJson JSON 格式：{"isPlaying": true, "position": 0, "duration": 300000}
     */
    @android.webkit.JavascriptInterface()
    public final void onPlayStateChanged(@org.jetbrains.annotations.NotNull()
    java.lang.String stateJson) {
    }
    
    /**
     * 接收歌曲信息变化
     * @param infoJson JSON 格式：{"id": "123", "title": "歌名", "artist": "歌手", "cover": "url", "duration": 300000}
     */
    @android.webkit.JavascriptInterface()
    public final void onSongInfoChanged(@org.jetbrains.annotations.NotNull()
    java.lang.String infoJson) {
    }
    
    /**
     * 接收播放进度变化
     * 接收播放进度变化
     * @param currentTime 当前播放位置（毫秒）
     * @param duration 总时长（毫秒）
     */
    @android.webkit.JavascriptInterface()
    public final void onProgressChanged(long currentTime, long duration) {
    }
    
    /**
     * 原生向 H5 请求控制
     * @param action 控制动作：play, pause, previous, next, seek
     * @return 是否成功发送
     */
    @android.webkit.JavascriptInterface()
    public final boolean requestControl(@org.jetbrains.annotations.NotNull()
    java.lang.String action) {
        return false;
    }
    
    /**
     * 检查原生功能是否可用
     */
    @android.webkit.JavascriptInterface()
    public final boolean isNativeReady() {
        return false;
    }
    
    /**
     * 获取原生版本信息
     */
    @android.webkit.JavascriptInterface()
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getNativeVersion() {
        return null;
    }
    
    /**
     * 保存完整播放列表
     * @param playlistJson 播放列表 JSON 字符串
     * @return 是否保存成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean savePlaylist(@org.jetbrains.annotations.NotNull()
    java.lang.String playlistJson) {
        return false;
    }
    
    /**
     * 加载播放列表
     * @return 播放列表 JSON 字符串
     */
    @android.webkit.JavascriptInterface()
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String loadPlaylist() {
        return null;
    }
    
    /**
     * 添加单首歌曲到播放列表
     * @param songJson 歌曲信息 JSON 字符串 {"id": "123", "name": "歌名", "artist": "歌手", "pic": "url"}
     * @return 是否添加成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean addSong(@org.jetbrains.annotations.NotNull()
    java.lang.String songJson) {
        return false;
    }
    
    /**
     * 从播放列表删除歌曲
     * @param songId 歌曲 ID
     * @return 是否删除成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean removeSong(@org.jetbrains.annotations.NotNull()
    java.lang.String songId) {
        return false;
    }
    
    /**
     * 获取播放列表存储路径
     */
    @android.webkit.JavascriptInterface()
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getPlaylistPath() {
        return null;
    }
    
    /**
     * 初始化原生音频播放器
     * @return 是否初始化成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean initNativePlayer() {
        return false;
    }
    
    /**
     * 使用原生播放器加载并播放音频
     * @param url 音频URL
     * @param startPosition 起始位置（毫秒）
     * @return 是否成功开始加载
     */
    @android.webkit.JavascriptInterface()
    public final boolean nativeLoadAndPlay(@org.jetbrains.annotations.NotNull()
    java.lang.String url, long startPosition) {
        return false;
    }
    
    /**
     * 原生播放器播放
     * @return 是否成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean nativePlay() {
        return false;
    }
    
    /**
     * 原生播放器暂停
     * @return 是否成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean nativePause() {
        return false;
    }
    
    /**
     * 原生播放器恢复播放
     * @return 是否成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean nativeResume() {
        return false;
    }
    
    /**
     * 原生播放器跳转到指定位置
     * @param position 位置（毫秒）
     * @return 是否成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean nativeSeekTo(long position) {
        return false;
    }
    
    /**
     * 获取原生播放器当前位置
     */
    @android.webkit.JavascriptInterface()
    public final long nativeGetCurrentPosition() {
        return 0L;
    }
    
    /**
     * 获取原生播放器总时长
     */
    @android.webkit.JavascriptInterface()
    public final long nativeGetDuration() {
        return 0L;
    }
    
    /**
     * 原生播放器是否正在播放
     */
    @android.webkit.JavascriptInterface()
    public final boolean nativeIsPlaying() {
        return false;
    }
    
    /**
     * 释放原生播放器资源
     */
    @android.webkit.JavascriptInterface()
    public final boolean nativeRelease() {
        return false;
    }
    
    /**
     * 安全读取剪贴板内容（供 H5 调用）
     * @return 剪贴板文本内容，如果无法读取则返回 null
     */
    @android.webkit.JavascriptInterface()
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String readClipboard() {
        return null;
    }
    
    /**
     * 安全写入剪贴板内容（供 H5 调用）
     * @param text 要写入的文本
     * @return 是否写入成功
     */
    @android.webkit.JavascriptInterface()
    public final boolean writeClipboard(@org.jetbrains.annotations.NotNull()
    java.lang.String text) {
        return false;
    }
    
    /**
     * 原生搜索歌曲（异步版本，绕过 WebView CORS 限制）
     * @param keyword 搜索关键词
     * @param page 页码（从1开始）
     * @param callback JS 回调函数名
     */
    @android.webkit.JavascriptInterface()
    public final void nativeSearchSongsAsync(@org.jetbrains.annotations.NotNull()
    java.lang.String keyword, int page, @org.jetbrains.annotations.NotNull()
    java.lang.String callback) {
    }
    
    /**
     * 原生请求歌曲详情（异步版本，绕过 WebView CORS 限制）
     * @param songId 歌曲 ID
     * @param callback JS 回调函数名
     */
    @android.webkit.JavascriptInterface()
    public final void nativeGetSongDetailAsync(@org.jetbrains.annotations.NotNull()
    java.lang.String songId, @org.jetbrains.annotations.NotNull()
    java.lang.String callback) {
    }
    
    /**
     * 调用 JS 回调函数
     * @param callback 回调函数名
     * @param result JSON 结果字符串
     */
    private final void invokeJsCallback(java.lang.String callback, java.lang.String result) {
    }
    
    /**
     * 在 WebView 主线程执行 JavaScript（用于原生播放器释放后的降级控制）
     * @param js 要执行的 JavaScript 代码
     */
    private final void evaluateJs(java.lang.String js) {
    }
    
    /**
     * 歌曲信息数据类
     */
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000*\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0010\t\n\u0002\b\u0011\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0002\b\u0086\b\u0018\u00002\u00020\u0001B5\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0003\u0012\u0006\u0010\u0005\u001a\u00020\u0003\u0012\u0006\u0010\u0006\u001a\u00020\u0003\u0012\u0006\u0010\u0007\u001a\u00020\u0003\u0012\u0006\u0010\b\u001a\u00020\t\u00a2\u0006\u0002\u0010\nJ\t\u0010\u0013\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0014\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0015\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0016\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0017\u001a\u00020\u0003H\u00c6\u0003J\t\u0010\u0018\u001a\u00020\tH\u00c6\u0003JE\u0010\u0019\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u00032\b\b\u0002\u0010\u0005\u001a\u00020\u00032\b\b\u0002\u0010\u0006\u001a\u00020\u00032\b\b\u0002\u0010\u0007\u001a\u00020\u00032\b\b\u0002\u0010\b\u001a\u00020\tH\u00c6\u0001J\u0013\u0010\u001a\u001a\u00020\u001b2\b\u0010\u001c\u001a\u0004\u0018\u00010\u0001H\u00d6\u0003J\t\u0010\u001d\u001a\u00020\u001eH\u00d6\u0001J\t\u0010\u001f\u001a\u00020\u0003H\u00d6\u0001R\u0011\u0010\u0006\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000b\u0010\fR\u0011\u0010\u0005\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\r\u0010\fR\u0011\u0010\u0007\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000e\u0010\fR\u0011\u0010\b\u001a\u00020\t\u00a2\u0006\b\n\u0000\u001a\u0004\b\u000f\u0010\u0010R\u0011\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0011\u0010\fR\u0011\u0010\u0004\u001a\u00020\u0003\u00a2\u0006\b\n\u0000\u001a\u0004\b\u0012\u0010\f\u00a8\u0006 "}, d2 = {"Lcom/shiyin/music/WebAppInterface$SongInfo;", "", "id", "", "title", "artist", "album", "cover", "duration", "", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V", "getAlbum", "()Ljava/lang/String;", "getArtist", "getCover", "getDuration", "()J", "getId", "getTitle", "component1", "component2", "component3", "component4", "component5", "component6", "copy", "equals", "", "other", "hashCode", "", "toString", "app_debug"})
    public static final class SongInfo {
        @org.jetbrains.annotations.NotNull()
        private final java.lang.String id = null;
        @org.jetbrains.annotations.NotNull()
        private final java.lang.String title = null;
        @org.jetbrains.annotations.NotNull()
        private final java.lang.String artist = null;
        @org.jetbrains.annotations.NotNull()
        private final java.lang.String album = null;
        @org.jetbrains.annotations.NotNull()
        private final java.lang.String cover = null;
        private final long duration = 0L;
        
        public SongInfo(@org.jetbrains.annotations.NotNull()
        java.lang.String id, @org.jetbrains.annotations.NotNull()
        java.lang.String title, @org.jetbrains.annotations.NotNull()
        java.lang.String artist, @org.jetbrains.annotations.NotNull()
        java.lang.String album, @org.jetbrains.annotations.NotNull()
        java.lang.String cover, long duration) {
            super();
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String getId() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String getTitle() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String getArtist() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String getAlbum() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String getCover() {
            return null;
        }
        
        public final long getDuration() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String component1() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String component2() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String component3() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String component4() {
            return null;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final java.lang.String component5() {
            return null;
        }
        
        public final long component6() {
            return 0L;
        }
        
        @org.jetbrains.annotations.NotNull()
        public final com.shiyin.music.WebAppInterface.SongInfo copy(@org.jetbrains.annotations.NotNull()
        java.lang.String id, @org.jetbrains.annotations.NotNull()
        java.lang.String title, @org.jetbrains.annotations.NotNull()
        java.lang.String artist, @org.jetbrains.annotations.NotNull()
        java.lang.String album, @org.jetbrains.annotations.NotNull()
        java.lang.String cover, long duration) {
            return null;
        }
        
        @java.lang.Override()
        public boolean equals(@org.jetbrains.annotations.Nullable()
        java.lang.Object other) {
            return false;
        }
        
        @java.lang.Override()
        public int hashCode() {
            return 0;
        }
        
        @java.lang.Override()
        @org.jetbrains.annotations.NotNull()
        public java.lang.String toString() {
            return null;
        }
    }
}