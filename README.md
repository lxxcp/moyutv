# Premium IPTV Player for Android TV

[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%2F%20Leanback-green.svg)](https://developer.android.com/tv)
[![API Level](https://img.shields.io/badge/API-21%2B-blue.svg)](https://developer.android.com/about/dashboards)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()

一款专为 Android TV / 电视盒子打造的**高性能双引擎 IPTV 播放器**。针对大屏及遥控器操作进行了深度定制与极致优化，提供无缝切台、智能 EPG、多协议代理及软硬双解码支持。

---

## 🌟 核心特性 (Key Features)

### 1. 🚀 双播放引擎架构 (Dual-Engine Architecture)
*   **ExoPlayer (Google Media3)**: 作为默认播放引擎，完美支持 HLS (m3u8)、DASH、MP4 等标准流媒体协议，硬件加速解码，超低延迟。
*   **MPV Player (ffmpeg 软解)**: 集成自研 JNI 及 `libmpv.so`，彻底解决部分老旧电视盒子因缺少硬件解码器而导致部分频道**有图无声 (AC3/MP2 音频格式)** 的问题。

### 2. ⚡ 无缝切台体验 (Seamless Channel Switching)
*   **首帧保留技术**: 切换台时，画面会**保持当前频道最后一帧的画面**，直到下一个频道完全加载并渲染出首帧后才进行平滑替换，告别切换过程中的黑屏等待与闪烁。
*   **加载反馈**: 界面右下角配有优雅的「正在加载...」动画与进度状态提示，极具视觉品质感。

### 3. 🎯 智能 EPG 模糊匹配 (Fuzzy Match EPG Engine)
*   **高性能匹配**: 采用高度优化的模糊拼音及字符相似度算法，自动将播放列表中的台名与 EPG 节目单进行高精度匹配。
*   **强兼容性**: EPG 数据抓取支持 HTTP 重定向跟随以及 GZIP 压缩自动解压，大幅降低网络流量并提升提升解析速度。

### 4. 🎛️ 遥控器与 TV UI 极致适配 (D-Pad Optimization)
*   **防丢焦设计**: 专为遥控器十字键 (D-Pad) 优化，列表焦点移动平滑，杜绝按键无响应及焦点丢失问题。
*   **快捷选台**: 稳定支持数字键直接选台及 OK/CENTER 键呼出频道列表与设置菜单。

---

## 🏗️ 编译与运行 (Build & Run)

### 前提条件 (Prerequisites)
1.  **Android Studio** Koala (或更高版本)。
2.  **Android SDK 34**。
3.  **NDK 25+** (项目包含针对 `armeabi-v7a` 和 `arm64-v8a` 的预编译 `libmpv` 和 C++ 代码库)。

### 编译步骤 (Steps)
1.  克隆本项目到本地。
2.  用 Android Studio 打开项目根目录。
3.  确保 `local.properties` 正确指向您的 Android SDK 路径。
4.  **注意**：如果是直接从 GitHub 下载的开源版本，由于缺少源文件，直接编译会报错。您需要自行实现这些占位接口或使用本地备份文件覆盖恢复。

---

## 📜 开源协议 (License)

本项目采用 [MIT License](LICENSE) 开源协议。

*(注意：项目中引用的第三方二进制库如 libmpv, ForceTV 库等其版权归原作者所有)*
