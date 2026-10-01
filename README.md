# 桌宠生成器

一个安卓桌面宠物生成器——上传任意 GIF 表情包，自动变成可互动的桌面悬浮窗桌宠。

> **本项目由 AI（豆包）辅助生成**，代码、图标生成脚本、项目结构均由 AI 完成。欢迎自由使用、修改和分发。

## 下载

**[点此下载最新 APK](https://github.com/fish133/pet-generator/releases/download/latest/pet-generator-latest.apk)**（v2.2.0，5.6MB）

也可在 [Releases 页面](https://github.com/fish133/pet-generator/releases/tag/latest) 查看。

## 功能特性

- **上传 GIF 即桌宠**：从相册选择任意 GIF，自动拆帧
- **帧数可选**：上传时可选 12帧 / 15帧 / 30帧，平衡流畅度与细腻度
- **短按切换 GIF**：点击桌宠切换到下一个 GIF 播放
- **长按菜单**：打开表情菜单，切换待机 GIF、长按删除、关闭桌宠
- **自定义大小**：滑块调节 80~400dp
- **自言自语气泡**：自定义文案（每行一句），10~40秒随机弹出
- **自由拖动**：按住桌宠拖到屏幕任意位置
- **多 GIF 管理**：按需切换，内存友好（只加载当前播放的帧）

## 系统要求

- Android 7.0（API 24）及以上，已测试兼容 Android 16

## 使用说明

1. 安装并打开 App
2. 选择 GIF 帧数（12/15/30）
3. 点击「上传 GIF 表情包」，从相册选择一个 GIF
4. 等待解析完成（提示"已提取 N 帧"）
5. 点击「启动桌宠」，授权悬浮窗权限
6. 桌宠出现在屏幕上

**操作：** 短按切换GIF · 长按打开菜单 · 拖动移动位置

## 更新历史

### v2.2.0
- 上传 GIF 时可选择帧数：12帧 / 15帧 / 30帧
- 12帧更流畅省内存，30帧更细腻

### v2.1.0
- 包名重构：com.doubao.pet.littlewhale → com.petgen.app
- 主题名改为 Theme.PetGen
- 修复长按菜单崩溃（移除主题属性依赖）

### v2.0.9
- 修复长按菜单直接崩溃

### v2.0.8
- 长按改为按住350ms自动弹出，移动阈值放宽到25px

### v2.0.7
- 修复多处位图回收崩溃和内存泄漏

## 构建方法

环境：JDK 17 + Android SDK (platform 34) + Gradle 8.2

```bash
pip install Pillow && python3 generate_icon.py
gradle assembleDebug
```

## 技术要点

- 悬浮窗：WindowManager + TYPE_APPLICATION_OVERLAY
- 前台服务：specialUse 类型，兼容 Android 14+
- GIF 解析：android.graphics.Movie 逐帧提取，均匀采样
- 内存优化：按需加载帧，Bitmap 及时回收
- 广播通信：主界面与 Service 本地广播同步

## 项目结构

```
app/src/main/java/com/petgen/app/
├── MainActivity.kt          # 主界面
├── PetFloatingService.kt    # 悬浮窗服务
└── GifUtils.kt              # GIF解析工具
generate_icon.py             # 图标生成脚本
.github/workflows/build.yml  # 自动发布APK
```

## 开源协议

MIT License
