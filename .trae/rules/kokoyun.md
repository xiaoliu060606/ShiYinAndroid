拾音Android - AI编程规则
核心原则
先思考再动手，简洁优先，精准修改，目标驱动。每次修改前明确：改什么、不改什么、为什么改。

修改前必做
复述需求，确认理解一致
说明修改范围和影响范围
列出步骤，确认后再写代码
项目红线（禁止）
禁止创建新 Service 或通知渠道
禁止 @JavascriptInterface 中同步网络请求
禁止修改 WebView 安全配置
禁止添加与当前任务无关的代码
禁止顺手重构、改命名等无关改动
架构约束
MediaSessionManager 是唯一通知源
MediaPlaybackService 只做保活，不处理媒体控制
命令路径：通知栏→MediaButtonReceiver→MediaSessionManager→NativeAudioPlayer
切歌才通知H5，播放/暂停/seek由原生处理
Bridge统一用android-bridge.js
修改后必查
 只改了需要改的部分
 通知ID无冲突
 回调无Web↔Native循环
 Bridge无重复触发
 无内存泄漏
 原有功能未破坏
Bug修复模板
【问题】具体表现 【定位】文件:行号 【方案】怎么改（只改此处） 【影响】是否影响其他组件