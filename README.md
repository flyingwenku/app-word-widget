# 一句（app-word-widget）

自定义句子的桌面小组件：自己写几句话，贴在桌面上，抬手就能换一句。面向旧手机（minSdk 21 / Android 5.0）。

## 功能

| 功能 | 说明 |
|---|---|
| 句子库 | 自己录入（句子 + 出处）；**开箱预置 14 条《论语》**便于测试 |
| 四种显示方式 | **随机下一句** / **顺序下一句** / **每日一句** / **固定某一句**，每个组件各自独立 |
| 放多个 | 支持添加多个组件，**各自独立**（模式、顺序游标、配色都按 `appWidgetId` 分开存） |
| 换一句 | **点组件**即换；另有 30 分钟兜底轮换、每日一句按日期自动换 |
| 软删除 | 勾选 → 批量隐藏，不真删；「已隐藏」列表可批量恢复 |
| 配色 | **跟随壁纸**（取壁纸主色 + 系统给的对比度提示定深浅字），半透明底透出壁纸；也可选纸色 / 深色 |
| 自由缩放 | 长按拖动改大小，字号按高度自动分档（13 / 16 / 20 sp） |

## 换一句的三种来源（为什么没有"开屏自动换"）

| 来源 | 说明 |
|---|---|
| **点击组件**（主力） | 唯一 100% 可控、零耗电的方式 |
| **30 分钟兜底** | `updatePeriodMillis` 系统硬下限，写更小会被忽略 |
| **每日一句** | 以日期取模选句，不需要任何更新动作也能每天换 |

**没有做"每次解锁自动换"**：Android 8 起解锁广播不再投给静态注册接收器，动态注册进程一死就失效，可靠的做法只有常驻前台服务——代价是通知栏常驻图标 + 耗电，对这类小工具不划算（详见 `../桌面句子小组件-评估与方案.md`）。

## 技术方案

```
AppWidgetProvider（onUpdate / onDeleted / onAppWidgetOptionsChanged）
  → RemoteViews 渲染（只有 TextView / ImageView + 半透明圆角底）
  → 配色：WallpaperManager.getWallpaperColors（API 27+）→ 主色 + HINT_SUPPORTS_DARK_TEXT
  → 配置：SharedPreferences，按 appWidgetId 分 key
  → 句子库：私有目录 JSON
```

| 决策 | 选择 | 理由 |
|---|---|---|
| 小组件实现 | 系统 `AppWidgetProvider` + `RemoteViews` | Jetpack Glance 要求 minSdk 23，本项目要 21 |
| 依赖 | **只有 androidx.core-ktx** | 沿用前三个项目的最小依赖原则 |
| 权限 | **零**（连 INTERNET 都不申请） | 纯离线；取壁纸主色不需要权限 |
| 存储 | JSON + SharedPreferences | 句子量级很小，不引数据库 |

## minSdk 21 / 新版 Android 避坑

| API | 处理 |
|---|---|
| `WallpaperColors`（API 27+） | 版本护栏 + 三重回退（取色 → 纸色 → 深色） |
| `PendingIntent.FLAG_IMMUTABLE`（API 23+，targetSdk 31+ 强制） | 版本判断后加 |
| `targetCellWidth/Height`（API 31+） | 老系统由 `minWidth/minHeight` 兜底 |
| `widgetFeatures=reconfigurable`（API 28+） | 老系统自动忽略，退化为删了重加 |
| 自适应图标（API 26+） | 提供了 `mipmap-anydpi` 的图层回退 |
| 圆角 | 用半透明 drawable + `setColorFilter` 染色（直接 `setBackgroundColor` 会把圆角盖成方块） |

## 已知限制

- **换壁纸不会立刻变色**（壁纸变更广播已弃用且受限）：最多 30 分钟后跟随，进 App 改句子也会刷新
- 老 launcher（部分国产桌面）**不支持拖拽缩放**：退路是删掉重加，系统按最小尺寸呈现
- 句子太长会被截断（RemoteViews 无法精细排版，最多 3 行）
- 没有导入 / 导出、分类、在线句子库（按计划放到后面）

## 构建

环境层复用项目一 / 二 / 三（第四次复用），GitHub Actions 云端构建：

```bash
./gradlew assembleDebug
```

## 文档

| 文档 | 内容 |
|---|---|
| `../桌面句子小组件-评估与方案.md` | 需求、平台冲突分析（换句时机四档）、技术方案、坑表、工作量 |
| `../Android应用开发与上架指南.md` | 通用：开发闭环 / 上架技术项 / 国内资质 / 个人开发认知 |
