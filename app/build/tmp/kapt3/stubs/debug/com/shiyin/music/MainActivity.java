package com.shiyin.music;

/**
 * 扣扣云音乐播放器 - Android WebView 封装
 * 功能：硬件加速 WebView + MediaSession 原生音频控制
 */
@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000~\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u000e\n\u0002\u0010\u000b\n\u0002\b\n\u0018\u00002\u00020\u0001B\u0005\u00a2\u0006\u0002\u0010\u0002J\u0006\u0010\u001b\u001a\u00020\u001cJ\b\u0010\u001d\u001a\u00020\u001cH\u0002J\u0006\u0010\u001e\u001a\u00020\u001aJ\b\u0010\u001f\u001a\u00020\u001cH\u0002J\b\u0010 \u001a\u00020\u001cH\u0002J\b\u0010!\u001a\u00020\u001cH\u0002J$\u0010\"\u001a\u00020\u001c2\u0006\u0010#\u001a\u00020\u00062\u0014\u0010$\u001a\u0010\u0012\u0006\u0012\u0004\u0018\u00010&\u0012\u0004\u0012\u00020\u001c0%J\b\u0010\'\u001a\u00020\u001cH\u0002J\u0012\u0010(\u001a\u00020\u001c2\b\u0010)\u001a\u0004\u0018\u00010*H\u0014J\b\u0010+\u001a\u00020\u001cH\u0014J\u0012\u0010,\u001a\u00020\u001c2\b\u0010-\u001a\u0004\u0018\u00010.H\u0014J\b\u0010/\u001a\u00020\u001cH\u0014J\b\u00100\u001a\u00020\u001cH\u0014J\b\u00101\u001a\u00020\u001cH\u0014J\u0006\u00102\u001a\u00020\u001cJ\u0006\u00103\u001a\u00020\u001cJ\u000e\u00104\u001a\u00020\u001c2\u0006\u00105\u001a\u00020\u0006JK\u00106\u001a\u00020\u001c2\u0006\u00107\u001a\u00020\u00062\b\b\u0002\u00108\u001a\u00020\u00062\b\b\u0002\u00109\u001a\u00020\u00062\n\b\u0002\u0010:\u001a\u0004\u0018\u00010\u00062\b\b\u0002\u0010;\u001a\u00020\t2\n\b\u0002\u0010<\u001a\u0004\u0018\u00010=H\u0002\u00a2\u0006\u0002\u0010>J\b\u0010?\u001a\u00020\u001cH\u0002J\b\u0010@\u001a\u00020\u001cH\u0002J\b\u0010A\u001a\u00020\u001cH\u0002J\u0006\u0010B\u001a\u00020\u001cJ\u0006\u0010C\u001a\u00020\u001cJ(\u0010D\u001a\u00020\u001c2\u0006\u00108\u001a\u00020\u00062\u0006\u00109\u001a\u00020\u00062\b\u0010:\u001a\u0004\u0018\u00010\u00062\u0006\u0010;\u001a\u00020\tJ\u000e\u0010E\u001a\u00020\u001c2\u0006\u0010<\u001a\u00020=J\b\u0010F\u001a\u00020\u001cH\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0005\u001a\u00020\u0006X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0007\u001a\u0004\u0018\u00010\u0006X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\tX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\n\u001a\u00020\u0006X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000b\u001a\u00020\fX\u0082.\u00a2\u0006\u0002\n\u0000R\u001c\u0010\r\u001a\u0010\u0012\f\u0012\n \u000f*\u0004\u0018\u00010\u00060\u00060\u000eX\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0010\u001a\u00020\u0011X\u0082.\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0012\u001a\u0004\u0018\u00010\u0013X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0014\u0010\u0014\u001a\b\u0018\u00010\u0015R\u00020\u0016X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0017\u001a\u00020\u0018X\u0082.\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0019\u001a\u00020\u001aX\u0082.\u00a2\u0006\u0002\n\u0000\u00a8\u0006G"}, d2 = {"Lcom/shiyin/music/MainActivity;", "Landroidx/appcompat/app/AppCompatActivity;", "()V", "clipboardHelper", "Lcom/shiyin/music/ClipboardHelper;", "currentSongArtist", "", "currentSongCover", "currentSongDuration", "", "currentSongTitle", "mediaSessionManager", "Lcom/shiyin/music/MediaSessionManager;", "notificationPermissionLauncher", "Landroidx/activity/result/ActivityResultLauncher;", "kotlin.jvm.PlatformType", "playlistRepository", "Lcom/shiyin/music/PlaylistRepository;", "rootLayout", "Landroid/widget/FrameLayout;", "wakeLock", "Landroid/os/PowerManager$WakeLock;", "Landroid/os/PowerManager;", "webAppInterface", "Lcom/shiyin/music/WebAppInterface;", "webView", "Landroid/webkit/WebView;", "acquireWakeLock", "", "checkNotificationPermission", "getWebView", "initWakeLock", "initWebViewAsync", "injectPlaylistData", "loadAlbumArt", "url", "callback", "Lkotlin/Function1;", "Landroid/graphics/Bitmap;", "loadLocalHtmlAsync", "onCreate", "savedInstanceState", "Landroid/os/Bundle;", "onDestroy", "onNewIntent", "intent", "Landroid/content/Intent;", "onPause", "onResume", "onStop", "releaseWakeLock", "reloadPlaylist", "sendCommandToWeb", "command", "sendServiceIntent", "action", "title", "artist", "cover", "duration", "playing", "", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/Boolean;)V", "setupBackPressedHandler", "setupWebView", "setupWebViewCache", "startPlaybackService", "stopPlaybackService", "updateServiceMetadata", "updateServicePlaybackState", "updateStatusBarIconColor", "app_debug"})
public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    private android.webkit.WebView webView;
    private com.shiyin.music.MediaSessionManager mediaSessionManager;
    private com.shiyin.music.WebAppInterface webAppInterface;
    private com.shiyin.music.PlaylistRepository playlistRepository;
    private com.shiyin.music.ClipboardHelper clipboardHelper;
    @org.jetbrains.annotations.Nullable()
    private android.os.PowerManager.WakeLock wakeLock;
    @org.jetbrains.annotations.NotNull()
    private final androidx.activity.result.ActivityResultLauncher<java.lang.String> notificationPermissionLauncher = null;
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentSongTitle = "\u6263\u6263\u4e91";
    @org.jetbrains.annotations.NotNull()
    private java.lang.String currentSongArtist = "\u6b63\u5728\u64ad\u653e...";
    @org.jetbrains.annotations.Nullable()
    private java.lang.String currentSongCover;
    private long currentSongDuration = 0L;
    
    /**
     * 获取帧布局
     * 用于在 Activity 销毁时清理
     */
    @org.jetbrains.annotations.Nullable()
    private android.widget.FrameLayout rootLayout;
    
    public MainActivity() {
        super();
    }
    
    @java.lang.Override()
    protected void onCreate(@org.jetbrains.annotations.Nullable()
    android.os.Bundle savedInstanceState) {
    }
    
    /**
     * 根据系统主题更新状态栏图标颜色
     */
    private final void updateStatusBarIconColor() {
    }
    
    /**
     * 初始化 WakeLock
     */
    private final void initWakeLock() {
    }
    
    /**
     * 获取 WakeLock
     */
    public final void acquireWakeLock() {
    }
    
    /**
     * 释放 WakeLock
     */
    public final void releaseWakeLock() {
    }
    
    /**
     * 异步初始化 WebView，避免阻塞主线程
     */
    private final void initWebViewAsync() {
    }
    
    /**
     * 配置 WebView - 开启硬件加速和优化设置
     */
    private final void setupWebView() {
    }
    
    /**
     * 异步加载本地 HTML 文件，避免主线程阻塞
     */
    private final void loadLocalHtmlAsync() {
    }
    
    /**
     * 配置 WebView 缓存路径，解决 Android 10+ 缓存权限问题
     * 使用现代缓存策略，避免使用废弃的 API
     */
    private final void setupWebViewCache() {
    }
    
    /**
     * 注入播放列表数据到 H5
     */
    private final void injectPlaylistData() {
    }
    
    /**
     * 重新加载播放列表（在保存新歌曲后调用）
     */
    public final void reloadPlaylist() {
    }
    
    /**
     * 检查通知权限
     */
    private final void checkNotificationPermission() {
    }
    
    /**
     * 从 URL 加载专辑封面并转换为 Bitmap
     */
    public final void loadAlbumArt(@org.jetbrains.annotations.NotNull()
    java.lang.String url, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function1<? super android.graphics.Bitmap, kotlin.Unit> callback) {
    }
    
    /**
     * 发送控制命令到 WebView
     */
    public final void sendCommandToWeb(@org.jetbrains.annotations.NotNull()
    java.lang.String command) {
    }
    
    @java.lang.Override()
    protected void onResume() {
    }
    
    /**
     * 发送服务 Intent 的统一方法
     */
    private final void sendServiceIntent(java.lang.String action, java.lang.String title, java.lang.String artist, java.lang.String cover, long duration, java.lang.Boolean playing) {
    }
    
    /**
     * 启动后台播放服务
     */
    public final void startPlaybackService() {
    }
    
    /**
     * 更新后台服务的歌曲信息
     */
    public final void updateServiceMetadata(@org.jetbrains.annotations.NotNull()
    java.lang.String title, @org.jetbrains.annotations.NotNull()
    java.lang.String artist, @org.jetbrains.annotations.Nullable()
    java.lang.String cover, long duration) {
    }
    
    /**
     * 更新后台服务的播放状态
     */
    public final void updateServicePlaybackState(boolean playing) {
    }
    
    /**
     * 停止后台播放服务
     */
    public final void stopPlaybackService() {
    }
    
    @java.lang.Override()
    protected void onNewIntent(@org.jetbrains.annotations.Nullable()
    android.content.Intent intent) {
    }
    
    @java.lang.Override()
    protected void onPause() {
    }
    
    @java.lang.Override()
    protected void onStop() {
    }
    
    /**
     * 配置返回键行为
     * 使用 OnBackPressedDispatcher 替代废弃的 onBackPressed()
     */
    private final void setupBackPressedHandler() {
    }
    
    /**
     * 获取 WebView 实例
     * 供 WebAppInterface 使用
     */
    @org.jetbrains.annotations.NotNull()
    public final android.webkit.WebView getWebView() {
        return null;
    }
    
    @java.lang.Override()
    protected void onDestroy() {
    }
}