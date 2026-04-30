package com.shiyin.music;

/**
 * 媒体播放服务
 * 用于后台播放保活
 * 通知由 MediaSessionManager 统一管理，此服务仅保持前台运行
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000L\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0010!\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0004\u0018\u0000  2\u00020\u0001:\u0001 B\u0005\u00a2\u0006\u0002\u0010\u0002J\b\u0010\t\u001a\u00020\nH\u0002J\b\u0010\u000b\u001a\u00020\fH\u0016J\b\u0010\r\u001a\u00020\fH\u0016J\"\u0010\u000e\u001a\u00020\u000f2\u0006\u0010\u0010\u001a\u00020\u00042\u0006\u0010\u0011\u001a\u00020\u00122\b\u0010\u0013\u001a\u0004\u0018\u00010\u0014H\u0016J$\u0010\u0015\u001a\u00020\f2\u0006\u0010\u0016\u001a\u00020\u00042\u0012\u0010\u0017\u001a\u000e\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u001a0\u00190\u0018H\u0016J\"\u0010\u001b\u001a\u00020\u00122\b\u0010\u001c\u001a\u0004\u0018\u00010\u001d2\u0006\u0010\u001e\u001a\u00020\u00122\u0006\u0010\u001f\u001a\u00020\u0012H\u0016R\u0014\u0010\u0003\u001a\u00020\u00048BX\u0082\u0004\u00a2\u0006\u0006\u001a\u0004\b\u0005\u0010\u0006R\u000e\u0010\u0007\u001a\u00020\u0004X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\u0004X\u0082\u000e\u00a2\u0006\u0002\n\u0000\u00a8\u0006!"}, d2 = {"Lcom/shiyin/music/MediaPlaybackService;", "Landroidx/media/MediaBrowserServiceCompat;", "()V", "channelId", "", "getChannelId", "()Ljava/lang/String;", "currentArtist", "currentTitle", "createMinimalNotification", "Landroid/app/Notification;", "onCreate", "", "onDestroy", "onGetRoot", "Landroidx/media/MediaBrowserServiceCompat$BrowserRoot;", "clientPackageName", "clientUid", "", "rootHints", "Landroid/os/Bundle;", "onLoadChildren", "parentId", "result", "Landroidx/media/MediaBrowserServiceCompat$Result;", "", "error/NonExistentClass", "onStartCommand", "intent", "Landroid/content/Intent;", "flags", "startId", "Companion", "app_debug"})
public final class MediaPlaybackService extends androidx.media.MediaBrowserServiceCompat {
    public static final int NOTIFICATION_ID = 1001;
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_UPDATE_METADATA = "com.shiyin.music.UPDATE_METADATA";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String ACTION_STOP = "com.shiyin.music.STOP";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String EXTRA_TITLE = "title";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String EXTRA_ARTIST = "artist";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String EXTRA_COVER = "cover";
    @org.jetbrains.annotations.NotNull()
    public static final java.lang.String EXTRA_DURATION = "duration";
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentTitle = "\u6263\u6263\u4e91";
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentArtist = "\u6b63\u5728\u64ad\u653e...";
    @org.jetbrains.annotations.NotNull()
    public static final com.shiyin.music.MediaPlaybackService.Companion Companion = null;
    
    public MediaPlaybackService() {
        super();
    }
    
    @java.lang.Override()
    public void onCreate() {
    }
    
    private final java.lang.String getChannelId() {
        return null;
    }
    
    @java.lang.Override()
    public int onStartCommand(@org.jetbrains.annotations.Nullable()
    android.content.Intent intent, int flags, int startId) {
        return 0;
    }
    
    @java.lang.Override()
    public void onDestroy() {
    }
    
    @java.lang.Override()
    @org.jetbrains.annotations.NotNull()
    public androidx.media.MediaBrowserServiceCompat.BrowserRoot onGetRoot(@org.jetbrains.annotations.NotNull()
    java.lang.String clientPackageName, int clientUid, @org.jetbrains.annotations.Nullable()
    android.os.Bundle rootHints) {
        return null;
    }
    
    @java.lang.Override()
    public void onLoadChildren(@org.jetbrains.annotations.NotNull()
    java.lang.String parentId, @org.jetbrains.annotations.NotNull()
    androidx.media.MediaBrowserServiceCompat.Result<java.util.List<error.NonExistentClass>> result) {
    }
    
    private final android.app.Notification createMinimalNotification() {
        return null;
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u001a\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0006\n\u0002\u0010\b\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0007\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\u0004X\u0086T\u00a2\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u000bX\u0086T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\f"}, d2 = {"Lcom/shiyin/music/MediaPlaybackService$Companion;", "", "()V", "ACTION_STOP", "", "ACTION_UPDATE_METADATA", "EXTRA_ARTIST", "EXTRA_COVER", "EXTRA_DURATION", "EXTRA_TITLE", "NOTIFICATION_ID", "", "app_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
}