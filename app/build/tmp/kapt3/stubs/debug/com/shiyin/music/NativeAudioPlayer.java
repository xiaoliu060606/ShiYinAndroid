package com.shiyin.music;

/**
 * 原生音频播放器
 * 使用 Android MediaPlayer 实现，提供更好的系统进度条同步
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000N\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\u0002\n\u0002\b\u000e\u0018\u00002\u00020\u0001:\u0001-B\u0015\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\u0002\u0010\u0006J\u0006\u0010\u001c\u001a\u00020\bJ\u0006\u0010\u001d\u001a\u00020\bJ\u0006\u0010\u001e\u001a\u00020\u000bJ\u0018\u0010\u001f\u001a\u00020 2\u0006\u0010!\u001a\u00020\u00132\b\b\u0002\u0010\"\u001a\u00020\bJ\u0006\u0010#\u001a\u00020 J\u0006\u0010$\u001a\u00020 J\u0006\u0010%\u001a\u00020 J\u0006\u0010&\u001a\u00020 J\u000e\u0010\'\u001a\u00020 2\u0006\u0010(\u001a\u00020\bJ\b\u0010)\u001a\u00020 H\u0002J\b\u0010*\u001a\u00020 H\u0002J\b\u0010+\u001a\u00020 H\u0002J\b\u0010,\u001a\u00020 H\u0002R\u000e\u0010\u0007\u001a\u00020\bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u000bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u001c\u0010\f\u001a\u0004\u0018\u00010\rX\u0086\u000e\u00a2\u0006\u000e\n\u0000\u001a\u0004\b\u000e\u0010\u000f\"\u0004\b\u0010\u0010\u0011R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0012\u001a\u00020\u0013X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0014\u001a\u00020\u0015X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0016\u001a\u00020\u000bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0017\u001a\u0004\u0018\u00010\u0018X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0004\u001a\u00020\u0005X\u0082\u0004\u00a2\u0006\u0004\n\u0002\u0010\u0019R\u0010\u0010\u001a\u001a\u0004\u0018\u00010\u001bX\u0082\u000e\u00a2\u0006\u0002\n\u0000\u00a8\u0006."}, d2 = {"Lcom/shiyin/music/NativeAudioPlayer;", "", "context", "Landroid/content/Context;", "mediaSession", "error/NonExistentClass", "(Landroid/content/Context;Lerror/NonExistentClass;)V", "_currentPosition", "", "_duration", "_isPlaying", "", "callback", "Lcom/shiyin/music/NativeAudioPlayer$AudioPlayerCallback;", "getCallback", "()Lcom/shiyin/music/NativeAudioPlayer$AudioPlayerCallback;", "setCallback", "(Lcom/shiyin/music/NativeAudioPlayer$AudioPlayerCallback;)V", "currentUrl", "", "handler", "Landroid/os/Handler;", "isPrepared", "mediaPlayer", "Landroid/media/MediaPlayer;", "Lerror/NonExistentClass;", "progressRunnable", "Ljava/lang/Runnable;", "getCurrentPosition", "getDuration", "isPlayingState", "loadAndPlay", "", "url", "startPosition", "pause", "play", "release", "resume", "seekTo", "position", "startProgressUpdate", "stopProgressUpdate", "updateMediaSessionMetadata", "updateMediaSessionState", "AudioPlayerCallback", "app_debug"})
public final class NativeAudioPlayer {
    @org.jetbrains.annotations.NotNull()
    private final android.content.Context context = null;
    @org.jetbrains.annotations.Nullable()
    private final error.NonExistentClass mediaSession = null;
    @org.jetbrains.annotations.Nullable()
    private android.media.MediaPlayer mediaPlayer;
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentUrl = "";
    private boolean isPrepared = false;
    private long _currentPosition = 0L;
    private long _duration = 0L;
    private boolean _isPlaying = false;
    @org.jetbrains.annotations.NotNull()
    private final android.os.Handler handler = null;
    @org.jetbrains.annotations.Nullable()
    private java.lang.Runnable progressRunnable;
    @org.jetbrains.annotations.Nullable()
    private com.shiyin.music.NativeAudioPlayer.AudioPlayerCallback callback;
    
    public NativeAudioPlayer(@org.jetbrains.annotations.NotNull()
    android.content.Context context, @org.jetbrains.annotations.Nullable()
    error.NonExistentClass mediaSession) {
        super();
    }
    
    @org.jetbrains.annotations.Nullable()
    public final com.shiyin.music.NativeAudioPlayer.AudioPlayerCallback getCallback() {
        return null;
    }
    
    public final void setCallback(@org.jetbrains.annotations.Nullable()
    com.shiyin.music.NativeAudioPlayer.AudioPlayerCallback p0) {
    }
    
    /**
     * 加载并播放音频
     */
    public final void loadAndPlay(@org.jetbrains.annotations.NotNull()
    java.lang.String url, long startPosition) {
    }
    
    /**
     * 播放
     */
    public final void play() {
    }
    
    /**
     * 暂停
     */
    public final void pause() {
    }
    
    /**
     * 恢复播放
     */
    public final void resume() {
    }
    
    /**
     * 跳转到指定位置
     */
    public final void seekTo(long position) {
    }
    
    /**
     * 获取当前位置
     */
    public final long getCurrentPosition() {
        return 0L;
    }
    
    /**
     * 获取总时长
     */
    public final long getDuration() {
        return 0L;
    }
    
    /**
     * 是否正在播放
     */
    public final boolean isPlayingState() {
        return false;
    }
    
    /**
     * 释放资源
     */
    public final void release() {
    }
    
    /**
     * 开始进度更新
     */
    private final void startProgressUpdate() {
    }
    
    /**
     * 停止进度更新
     */
    private final void stopProgressUpdate() {
    }
    
    /**
     * 更新 MediaSession 播放状态
     * 这是关键：实时同步系统进度条
     */
    private final void updateMediaSessionState() {
    }
    
    /**
     * 更新 MediaSession 元数据
     */
    private final void updateMediaSessionMetadata() {
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000*\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\t\n\u0002\b\u0003\bf\u0018\u00002\u00020\u0001J\b\u0010\u0002\u001a\u00020\u0003H&J\u0010\u0010\u0004\u001a\u00020\u00032\u0006\u0010\u0005\u001a\u00020\u0006H&J\u0010\u0010\u0007\u001a\u00020\u00032\u0006\u0010\b\u001a\u00020\tH&J\u0010\u0010\n\u001a\u00020\u00032\u0006\u0010\u000b\u001a\u00020\fH&J\u0018\u0010\r\u001a\u00020\u00032\u0006\u0010\u000e\u001a\u00020\f2\u0006\u0010\u000b\u001a\u00020\fH&\u00a8\u0006\u000f"}, d2 = {"Lcom/shiyin/music/NativeAudioPlayer$AudioPlayerCallback;", "", "onCompletion", "", "onError", "error", "", "onPlayStateChanged", "isPlaying", "", "onPrepared", "duration", "", "onProgressChanged", "position", "app_debug"})
    public static abstract interface AudioPlayerCallback {
        
        public abstract void onPrepared(long duration);
        
        public abstract void onProgressChanged(long position, long duration);
        
        public abstract void onCompletion();
        
        public abstract void onError(@org.jetbrains.annotations.NotNull()
        java.lang.String error);
        
        public abstract void onPlayStateChanged(boolean isPlaying);
    }
}