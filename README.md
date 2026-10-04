# 桌宠生成器

一个安卓桌面宠物生成器——上传任意 GIF 表情包，自动变成可互动的桌面悬浮窗桌宠。

> **本项目由 AI（豆包）辅助生成**，代码、图标生成脚本、项目结构均由 AI 完成。欢迎自由使用、修改和分发。

## 下载

**[点此下载最新 APK](https://github.com/fish133/pet-generator/releases/download/latest/pet-generator-latest.apk)**（v2.3.3，4.7MB，正式签名）

也可在 [Releases 页面](https://github.com/fish133/pet-generator/releases/tag/latest) 查看。

## 功能特性

- **上传 GIF 即桌宠**：从相册选择任意 GIF，自动拆帧
- **帧数可选**：上传时可选 12帧 / 15帧 / 30帧，平衡流畅度与细腻度
- **短按切换 GIF**：点击桌宠切换到下一个 GIF 播放
- **点击音效**：可选鸭子音效或冰冰冰音效，开启后短按桌宠发声
- **长按菜单**：打开表情菜单，切换待机 GIF、长按删除、关闭桌宠
- **自定义大小**：滑块调节 80~800dp
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

**音效：** 在主界面气泡文案下方打开「连续点击音效」开关，选择鸭子或冰冰冰音效即可。

## 更新历史

### v2.3.3
- 修复大小存档越界导致 SeekBar 启动崩溃
- 存档尺寸夹取到 80~800dp 合法范围

### v2.3.2
- 正式签名发布（release keystore），不再是 debug 证书
- 安装不再提示安全警告

### v2.3.1
- 修复 loadFramesForGif 立即回收旧 Bitmap 导致动画线程黑屏/崩溃
- 改为延迟 500ms 回收旧帧，等待动画切换到新帧后再释放内存

### v2.3.0
- 新增点击音效：鸭子音效 + 冰冰冰音效（二选一）
- 音效开关位于气泡文案下方，开启后短按桌宠发声
- 桌宠大小范围扩大到 80~800dp，默认 280dp

### v2.2.2
- 修复 loadFramesForGif 多线程竞争导致的 Bitmap 回收崩溃
- getCurrentIdleFrames 不再在主线程执行文件 I/O
- MainActivity GIF 数量统计移到后台线程

### v2.2.0
- 上传 GIF 时可选择帧数：12帧 / 15帧 / 30帧
- 12帧更流畅省内存，30帧更细腻

### v2.1.0
- 包名重构：com.doubao.pet.littlewhale → com.petgen.app
- 主题名改为 Theme.PetGen
- 修复长按菜单崩溃（移除主题属性依赖）

## 构建方法

环境：JDK 17 + Android SDK (platform 34) + Gradle 8.2

```bash
pip install Pillow && python3 generate_icon.py
gradle assembleRelease
```

## 技术要点

- 悬浮窗：WindowManager + TYPE_APPLICATION_OVERLAY
- 前台服务：specialUse 类型，兼容 Android 14+
- GIF 解析：android.graphics.Movie 逐帧提取，均匀采样
- 音效播放：SoundPool 低延迟，支持连续快速点击
- 内存优化：按需加载帧，Bitmap 及时回收
- 广播通信：主界面与 Service 本地广播同步

## 项目结构

```
app/src/main/java/com/petgen/app/
├── MainActivity.kt          # 主界面
├── PetFloatingService.kt    # 悬浮窗服务
└── GifUtils.kt              # GIF解析工具
app/src/main/res/raw/
├── duck.mp3                 # 鸭子音效
└── bingbing.mp3             # 冰冰冰音效
generate_icon.py             # 图标生成脚本
.github/workflows/build.yml  # 自动发布APK
```

## 开源协议

MIT License
