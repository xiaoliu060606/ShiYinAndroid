package com.shiyin.music;

/**
 * MediaSession 管理类
 * 负责管理媒体会话、锁屏控制、通知栏播放器
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000b\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\t\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0007\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0014\u0018\u0000 ;2\u00020\u0001:\u0001;B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\b\u0010\u001f\u001a\u0004\u0018\u00010 J\n\u0010!\u001a\u0004\u0018\u00010\nH\u0002J\u000b\u0010\"\u001a\u00020\u0017\u00a2\u0006\u0002\u0010#J\u000b\u0010$\u001a\u00020\u0017\u00a2\u0006\u0002\u0010#J\u0010\u0010%\u001a\u00020&2\b\u0010\'\u001a\u0004\u0018\u00010(J\b\u0010)\u001a\u00020&H\u0002J\u0006\u0010*\u001a\u00020&J\u0006\u0010\u0011\u001a\u00020\u0012J\u0006\u0010+\u001a\u00020&J\u000e\u0010,\u001a\u00020&2\u0006\u0010-\u001a\u00020\u0012J\u0010\u0010.\u001a\u00020&2\b\u0010/\u001a\u0004\u0018\u00010\u001aJ\u000e\u00100\u001a\u00020&2\u0006\u00101\u001a\u00020\u0012J&\u00102\u001a\u00020&2\u0006\u00103\u001a\u00020\f2\u0006\u00104\u001a\u00020\f2\u0006\u00105\u001a\u00020\f2\u0006\u00106\u001a\u00020\u0006J\b\u00107\u001a\u00020&H\u0002J\u001a\u00108\u001a\u00020&2\u0006\u00101\u001a\u00020\u00122\b\b\u0002\u00109\u001a\u00020\u0006H\u0002J\u0016\u0010:\u001a\u00020&2\u0006\u00109\u001a\u00020\u00062\u0006\u0010\u0010\u001a\u00020\u0006R\u000e\u0010\u0005\u001a\u00020\u0006X\u0082D\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0007\u001a\u0004\u0018\u00010\bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0010\u0010\t\u001a\u0004\u0018\u00010\nX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000b\u001a\u00020\fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\r\u001a\u00020\u0006X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000e\u001a\u00020\fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000f\u001a\u00020\fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0010\u001a\u00020\u0006X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0011\u001a\u00020\u0012X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0013\u001a\u00020\u0014X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0015\u001a\u00020\u0006X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0016\u001a\u00020\u0017X\u0082\u000e\u00a2\u0006\u0004\n\u0002\u0010\u0018R\u0010\u0010\u0019\u001a\u0004\u0018\u00010\u001aX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u001b\u001a\u0004\u0018\u00010\u001cX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u001d\u001a\u00020\u001eX\u0082\u000e\u00a2\u0006\u0002\n\u0000\u00a8\u0006<"}, d2 = {"Lcom/shiyin/music/MediaSessionManager;", "", "context", "Lcom/shiyin/music/MainActivity;", "(Lcom/shiyin/music/MainActivity;)V", "MIN_STATE_CHANGE_INTERVAL_MS", "", "audioManager", "Landroid/media/AudioManager;", "currentAlbumArt", "Landroid/graphics/Bitmap;", "currentArtist", "", "currentPosition", "currentSongId", "currentTitle", "duration", "isPlaying", "", "isUpdatingFromWeb", "Ljava/util/concurrent/atomic/AtomicBoolean;", "lastStateChangeTime", "mediaSession", "error/NonExistentClass", "Lerror/NonExistentClass;", "nativeAudioPlayer", "Lcom/shiyin/music/NativeAudioPlayer;", "notificationManager", "Landroid/app/NotificationManager;", "playbackSpeed", "", "getCurrentNotification", "Landroid/app/Notification;", "getDefaultAlbumArt", "getMediaSession", "()Lerror/NonExistentClass;", "getSessionToken", "handleMediaButtonEvent", "", "event", "Landroid/view/KeyEvent;", "initMediaSession", "initNotificationChannel", "release", "setActive", "active", "setNativeAudioPlayer", "player", "setPlayState", "playing", "updateMetadata", "title", "artist", "albumArtUrl", "durationMs", "updateNotification", "updatePlaybackState", "position", "updateProgress", "Companion", "app_debug"})
public final class MediaSessionManager {
    @org.jetbrains.annotations.NotNull()
    private final com.shiyin.music.MainActivity context = null;
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String CHANNEL_ID = "shiyin_music_channel_v2";
    public static final int NOTIFICATION_ID = 1001;
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_PLAY = "com.shiyin.music.PLAY";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_PAUSE = "com.shiyin.music.PAUSE";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_PREVIOUS = "com.shiyin.music.PREVIOUS";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_NEXT = "com.shiyin.music.NEXT";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_STOP = "com.shiyin.music.STOP";
    @org.jetbrains.annotations.Nullable()
    private error.NonExistentClass mediaSession;
    @org.jetbrains.annotations.Nullable()
    private android.app.NotificationManager notificationManager;
    @org.jetbrains.annotations.Nullable()
    private android.media.AudioManager audioManager;
    private boolean isPlaying = false;
    private long currentPosition = 0L;
    private long duration = 0L;
    private float playbackSpeed = 1.0F;
    @org.jetbrains.annotations.NotNull()
    private java.util.concurrent.atomic.AtomicBoolean isUpdatingFromWeb;
    @org.jetbrains.annotations.Nullable()
    private com.shiyin.music.NativeAudioPlayer nativeAudioPlayer;
    private long lastStateChangeTime = 0L;
    private final long MIN_STATE_CHANGE_INTERVAL_MS = 500L;
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentSongId = "";
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentTitle = "";
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentArtist = "";
    @org.jetbrains.annotations.Nullable()
    private android.graphics.Bitmap currentAlbumArt;
    @org.jetbrains.annotations.NotNull()
    public static final com.shiyin.music.MediaSessionManager.Companion Companion = null;
    
    public MediaSessionManager(@org.jetbrains.annotations.NotNull()
    com.shiyin.music.MainActivity context) {
        super();
    }
    
    /**
     * 初始化 MediaSession
     */
    private final void initMediaSession() {
    }
    
    /**
     * 初始化通知渠道（Android 8.0+）
     */
    public final void initNotificationChannel() {
    }
    
    /**
     * 更新播放状态
     */
    private final void updatePlaybackState(boolean playing, long position) {
    }
    
    /**
     * 更新歌曲元数据
     */
    public final void updateMetadata(@org.jetbrains.annotations.NotNull()
    java.lang.String title, @org.jetbrains.annotations.NotNull()
    java.lang.String artist, @org.jetbrains.annotations.NotNull()
    java.lang.String albumArtUrl, long durationMs) {
    }
    
    /**
     * 获取默认专辑封面图标
     */
    private final android.graphics.Bitmap getDefaultAlbumArt() {
        return null;
    }
    
    /**
     * 更新播放进度
     */
    public final void updateProgress(long position, long duration) {
    }
    
    /**
     * 设置播放状态（从 WebView 接收）
     */
    public final void setPlayState(boolean playing) {
    }
    
    /**
     * 设置原生音频播放器（由 WebAppInterface 调用）
     * 关键修复：注入播放器引用，使锁屏控制可以直控原生播放器
     */
    public final void setNativeAudioPlayer(@org.jetbrains.annotations.Nullable()
    com.shiyin.music.NativeAudioPlayer player) {
    }
    
    /**
     * 获取当前播放状态
     */
    public final boolean isPlaying() {
        return false;
    }
    
    /**
     * 获取 MediaSession 实例
     */
    @org.jetbrains.annotations.Nullable()
    public final error.NonExistentClass getMediaSession() {
        return null;
    }
    
    /**
     * 更新通知栏播放器
     */
    private final void updateNotification() {
    }
    
    /**
     * 设置 MediaSession 活跃状态
     */
    public final void setActive(boolean active) {
    }
    
    /**
     * 释放资源
     */
    public final void release() {
    }
    
    /**
     * 获取 MediaSession Token（用于通知）
     */
    @org.jetbrains.annotations.Nullable()
    public final error.NonExistentClass getSessionToken() {
        return null;
    }
    
    /**
     * 处理媒体按钮事件
     */
    public final void handleMediaButtonEvent(@org.jetbrains.annotations.Nullable()
    android.view.KeyEvent event) {
    }
    
    @org.jetbrains.annotations.Nullable()
    public final android.app.Notification getCurrentNotification() {
        return null;
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u001a\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0006\n\u0002\u0010\b\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0007\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u000bX\u0086T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\f"}, d2 = {"Lcom/shiyin/music/MediaSessionManager$Companion;", "", "()V", "ACTION_NEXT", "", "ACTION_PAUSE", "ACTION_PLAY", "ACTION_PREVIOUS", "ACTION_STOP", "CHANNEL_ID", "NOTIFICATION_ID", "", "app_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
}