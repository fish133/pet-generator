# 桌宠生成器

一个安卓桌面宠物生成器——上传任意 GIF 表情包，自动变成可互动的桌面悬浮窗桌宠。

## 功能特性

- 上传 GIF 即桌宠：从相册选择任意 GIF，自动拆帧（最多15帧），变成桌宠动画
- 点击互动：短按桌宠切换到下一个 GIF 播放
- 长按菜单：长按打开表情菜单，切换当前待机 GIF、长按删除、关闭桌宠
- 自定义大小：滑块调节 80~400dp
- 自言自语气泡：自定义文案（每行一句），10~40秒随机弹出
- 自由拖动：按住桌宠拖到屏幕任意位置
- 多 GIF 管理：支持上传多个 GIF，按需切换，内存友好（只加载当前播放的帧）

## 系统要求

- Android 7.0（API 24）及以上
- 已测试兼容 Android 16

## 构建方法

### 环境要求
- JDK 17
- Android SDK（platform 34，build-tools 34）
- Gradle 8.2

### 构建命令
```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 安装并打开 App
2. 点击「上传 GIF 表情包」，从相册选择一个 GIF
3. 等待解析完成（提示"已提取 N 帧"）
4. 点击「启动桌宠」，授权悬浮窗权限
5. 桌宠出现在屏幕上，享受互动！

### 操作说明
- 短按：播放下一个 GIF
- 长按：打开表情菜单（切换/删除 GIF、关闭桌宠）
- 拖动：按住移动位置

## 技术要点

- 悬浮窗：WindowManager + TYPE_APPLICATION_OVERLAY（Android 8+）/ TYPE_PHONE（Android 7）
- 前台服务：specialUse 类型，兼容 Android 14+
- GIF 解析：android.graphics.Movie 逐帧提取，均匀采样到15帧
- 内存优化：按需加载帧（只加载当前播放的 GIF），Bitmap 及时回收
- 广播通信：主界面与 Service 通过本地广播同步大小、气泡、素材刷新

## 开源协议

MIT License