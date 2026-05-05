# Music Hub 音乐枢纽

跨平台音乐播放列表管理器和启动器，支持网易云音乐和QQ音乐。

A cross-platform music playlist manager and launcher for Chinese music platforms (NetEase Cloud Music & QQ Music).

## 功能特点 Features

- **统一音乐库** - 将网易云音乐和QQ音乐的歌曲整合到一个库中
- **跨平台播放列表** - 创建包含两个平台歌曲的播放列表
- **一键播放** - 点击歌曲自动打开对应的音乐App播放
- **分享接收** - 从其他App分享链接直接添加歌曲
- **本地存储** - 所有数据保存在本地，无需联网

---

## 快速开始 Quick Start

### 环境要求 Requirements

- Python 3.10+
- [Pixi](https://pixi.sh/) (推荐) 或 pip
- Android 手机 (用于测试APK)

### 安装 Installation

```bash
# 克隆项目
git clone <repository-url>
cd music-hub

# 安装 pixi (如果没有)
curl -fsSL https://pixi.sh/install.sh | bash

# 安装依赖
pixi install
```

### 运行 Running

```bash
# 桌面模拟器 (开发用)
pixi run run

# 运行测试
pixi run test

# 构建 Android APK
pixi run build-android

# 部署到手机
pixi run deploy
```

---

## 开发命令 Development Commands

| 命令 | 说明 |
|---|---|
| `pixi run run` | 在桌面运行应用 (360x640窗口) |
| `pixi run test` | 运行pytest测试 |
| `pixi run build-android` | 构建debug APK |
| `pixi run deploy` | 构建并部署到手机 |
| `pixi run deploy-watch` | 部署并查看日志 |
| `pixi run logcat` | 查看设备日志 |
| `pixi run logcat-python` | 查看Python日志 (过滤后) |
| `pixi run clean` | 清理构建缓存 |
| `pixi run rebuild` | 完全重新构建 |

---

## 项目结构 Project Structure

```
music-hub/
├── main.py                # 入口文件
├── src/
│   ├── app.py             # 主应用类
│   ├── db/                # 数据库层
│   │   ├── database.py    # SQLite封装
│   │   └── models.py      # 数据模型
│   ├── platforms/         # 平台处理器
│   │   ├── netease.py     # 网易云音乐
│   │   └── qqmusic.py     # QQ音乐
│   ├── services/          # 服务层
│   │   ├── launcher.py    # 深度链接启动器
│   │   ├── link_parser.py # 链接解析
│   │   └── share_receiver.py  # 分享接收
│   └── ui/                # 界面
│       ├── screens/       # 屏幕类
│       └── widgets/       # 组件
├── ui/                    # Kivy布局文件 (.kv)
├── assets/                # 资源文件
│   ├── icons/             # 图标
│   └── fonts/             # 字体
├── tests/                 # 测试文件
├── buildozer.spec         # Android构建配置
└── pixi.toml              # Pixi环境配置
```

---

## 技术栈 Tech Stack

| 组件 | 技术 |
|---|---|
| 语言 | Python 3.10+ |
| UI框架 | Kivy 2.3+ |
| Android打包 | Buildozer |
| Java桥接 | Pyjnius |
| 数据库 | SQLite (桌面) / Android原生SQLite (手机) |
| 环境管理 | Pixi |

---

## 支持的平台 Supported Platforms

### 网易云音乐 NetEase Cloud Music
- 支持链接格式: `music.163.com/song?id=xxx`
- 深度链接: `orpheus://song/{id}`

### QQ音乐 QQ Music
- 支持链接格式: `y.qq.com/n/ryqq/songDetail/xxx`
- 深度链接: `qqmusic://qq.com/ui/openUrl?p=...`

---

## 使用方法 Usage

### 添加歌曲

1. **手动添加**: 在"添加歌曲"页面粘贴歌曲链接
2. **分享添加**: 从网易云/QQ音乐App分享歌曲到Music Hub

### 播放歌曲

点击歌曲卡片，自动打开对应的音乐App并播放。

### 管理播放列表

- 创建自定义播放列表
- 添加来自不同平台的歌曲
- 拖拽排序

---

## 常见问题 Troubleshooting

### 构建失败
```bash
pixi run clean
pixi run rebuild
```

### 中文显示异常
确保 `assets/fonts/ChineseFont.ttf` 存在。

### APK安装后闪退
1. 运行 `pixi run logcat-python` 查看错误
2. 检查所有Android代码是否在 `if platform == 'android':` 内

### 深度链接打开浏览器而非App
确保使用自定义URI scheme (`orpheus://`, `qqmusic://`)，而非 `https://`。

---

## 开发说明 Development Notes

### 调试流程

1. 在桌面开发和测试: `pixi run run`
2. 运行单元测试: `pixi run test`
3. 部署到真机测试: `pixi run deploy`
4. 查看日志: `pixi run logcat-python`

### 添加新平台

1. 在 `src/platforms/` 创建新的平台处理器
2. 继承 `PlatformHandler` 基类
3. 实现 `can_handle()`, `parse_song_url()`, `generate_deep_link()` 等方法
4. 在 `link_parser.py` 中注册新平台

---

## 许可证 License

MIT License

---

## 贡献 Contributing

欢迎提交 Issue 和 Pull Request！

1. Fork 项目
2. 创建功能分支
3. 提交更改
4. 发起 Pull Request
