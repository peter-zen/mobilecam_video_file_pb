# Camera Video File Playback — Android Sample App 方案文档

## 1. 项目概述

基于 **MobileCamSDK** 构建一个极简的 Android 测试 App，用于验证相机端视频文件（存储于 `DCIM` 目录的 MP4 文件）通过 RTSP 协议回放的端到端链路。

**核心目标：**
- 输入相机 IP + 文件名，即可播放相机上的视频文件。
- 使用 SDK 现有 API，不修改 SDK。
- 第一步仅验证 "能正常播放"，控制功能极简。

---

## 2. SDK 分析结论

### 2.1 SDK 位置
预编译 SDK 位于项目同级目录，采用 **JAR + .so** 方式引入（与现有 APP 保持一致）：

**JAR 文件（放入 `app/libs/`）：**
```
../MobileCamSDK/dist/android-release/compat-jars/
├── ICatchtekReliant.jar   (基础类型、Transport)
└── ICatchtekVR.jar        (Pancam Session、Streaming、Playback)
```

**.so 文件（按 ABI 放入 `app/src/main/jniLibs/<abi>/`）：**
```
../MobileCamSDK/dist/android-release/integration/vr/jniLibs/
├── arm64-v8a/
│   ├── libpanorama_vr.so
│   ├── libreliant.so
│   ├── libdepth_net_transport.so
│   ├── libusb_transport.so
│   └── libc++_shared.so
├── armeabi-v7a/  (同上)
├── x86/          (同上)
└── x86_64/       (同上)
```

对于纯视频回放，**Reliant + VR 两个 JAR + 对应 .so 即可**。

### 2.2 关键 API

| 层级 | 类 | 作用 |
|------|-----|------|
| Transport | `ICatchINETTransport(host)` | 建立到相机的网络连接 |
| Session | `ICatchPancamSession.createSession()` | 创建 Pancam Session |
| Session | `session.prepareSession(transport, ...)` | 初始化 Session |
| Playback | `session.getVideoPlayback()` | 获取视频回放实例 |
| Playback | `videoPlayback.enableRender(surfaceContext)` | 绑定 Surface，SDK 直接渲染 |
| Playback | `videoPlayback.play(ICatchFile, disableAudio, isRemote)` | 启动回放 |
| Playback | `videoPlayback.stop()` | 停止回放 |

### 2.3 RTSP 链路（SDK 内部）

当调用 `play(ICatchFile, disableAudio, remote=true)` 时：
1. Java 层构造 `ICatchFile`，其中 `filePath = "/DCIM/Go5_xxxx.MP4"`
2. Native 层将路径前缀为 `net:///DCIM/Go5_xxxx.MP4`
3. `StreamingMediaClient.cpp` 将其转换为 RTSP URL：
   ```
   rtsp://<camera_ip>:554/DCIM/Go5_xxxx.MP4
   ```
4. 通过 Live555 RTSP Client 拉流、ffmpeg 解码，SDK 直接渲染到 Surface

**结论：** 无需手动构造 RTSP URL，提供 `ICatchFile` 即可。

---

## 3. 技术方案

### 3.1 播放流程

```
┌─────────────────┐
│  输入 IP + 文件名  │
└────────┬────────┘
         ▼
┌─────────────────────────┐
│ ICatchINETTransport(ip) │
└────────┬────────────────┘
         ▼
┌──────────────────────────────┐
│ ICatchPancamSession.prepare() │
└────────┬─────────────────────┘
         ▼
┌────────────────────────────────────┐
│ 构造 ICatchFile(filePath="/DCIM/xxx") │
└────────┬───────────────────────────┘
         ▼
┌──────────────────────────────────────────┐
│ videoPlayback.enableRender(surfaceContext) │
└────────┬─────────────────────────────────┘
         ▼
┌──────────────────────────────────────────┐
│ videoPlayback.play(file, disableAudio=true, │
│                  remote=true)               │
└────────┬─────────────────────────────────┘
         ▼
┌─────────────────┐
│   SDK 渲染到 Surface │
└─────────────────┘
```

### 3.2 渲染方式

**Path A：SDK 直接渲染（选定）**
- 使用 `SurfaceView` + `ICatchSurfaceContext`
- SDK 内部解码 MJPG/H.264 并直接绘制
- 无需额外播放器库（不使用 `libmediacomponent`）

```kotlin
val surfaceContext = ICatchSurfaceContext(surface)
videoPlayback.enableRender(surfaceContext)
```

### 3.3 文件路径构造

用户输入裸文件名（如 `Go5_0001_0002_0003.MP4`），App 自动拼接：
```kotlin
val filePath = "/DCIM/$inputFileName"
val iCatchFile = ICatchFile(
    0,                                    // fileHandle (手动构造时填 0)
    ICatchFileType.ICH_FILE_TYPE_VIDEO,   // fileType
    filePath,                             // filePath
    inputFileName,                        // fileName
    0,                                    // fileSize
    "",                                   // fileDate
    0.0,                                  // frameRate
    0,                                    // width
    0,                                    // height
    0,                                    // protection
    0                                     // duration
)
```

### 3.4 连接参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 默认 IP | `192.168.1.1` | 预填充，可修改 |
| RTSP 端口 | `554` | SDK 内部固定，无需配置 |
| remote | `true` | 相机端文件回放 |
| disableAudio | `true` | 第一步禁用音频，隔离视频链路 |

---

## 4. UI 方案

### 4.1 界面结构

```
MainActivity (Compose)
├── 顶部：标题栏
├── 输入区域：
│   ├── IP 地址输入框（默认 192.168.1.1，支持历史下拉）
│   ├── 文件名输入框（支持历史下拉）
│   └── [播放] 按钮
├── 状态显示：连接状态 / 错误提示
└── 播放时：跳转到 PlaybackActivity（横屏全屏）

PlaybackActivity（传统 View + SurfaceView）
├── 全屏 SurfaceView（SDK 渲染目标）
├── 轻量级叠加层：文件名、返回按钮
└── 返回手势/按钮 → stop() → finish()
```

### 4.2 为什么 Playback 用传统 Activity 而非 Compose？

SDK 的 `ICatchSurfaceContext` 需要直接操作 Android `Surface`，而 `SurfaceView` 在传统 View 系统中生命周期最清晰。虽然 Compose 可以通过 `AndroidView` 嵌入 `SurfaceView`，但考虑到：
- SDK 对 Surface 的生命周期敏感（create/destroy/resize）
- 播放 Activity 功能极简，无需 Compose 的声明式优势
- 横屏全屏在传统 Activity 中更易控制（`Theme.NoActionBar.Fullscreen` + `setRequestedOrientation`）

**结论：** MainActivity 保持现有 Compose，新增 `PlaybackActivity` 使用传统 `SurfaceView`。

### 4.3 历史记录

使用 `SharedPreferences` 保存：
- `history_ips`：最近使用的 IP 列表（最多 10 条）
- `history_filenames`：最近使用的文件名列表（最多 10 条）

输入框使用 `AutoCompleteTextView`（Compose 用 `DropdownMenu`）展示历史。

---

## 5. 关键代码示例

### 5.1 SDK 初始化和播放

```kotlin
class PlaybackActivity : AppCompatActivity() {
    private lateinit var surfaceView: SurfaceView
    private var session: ICatchPancamSession? = null
    private var videoPlayback: ICatchIPancamVideoPlayback? = null
    private var surface: Surface? = null
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

        surfaceView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surface = holder.surface
                }
                override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surface = null
                }
            })
        }
        setContentView(surfaceView)

        val ip = intent.getStringExtra("ip") ?: return
        val fileName = intent.getStringExtra("filename") ?: return

        executor.execute { startPlayback(ip, fileName) }
    }

    private fun startPlayback(ip: String, fileName: String) {
        try {
            val transport = ICatchINETTransport(ip)
            session = ICatchPancamSession.createSession()
            val ppi = ICatchGLDisplayPPI(320f, 320f)
            session?.prepareSession(transport, ICatchGLColor.BLACK, ppi)

            videoPlayback = session?.videoPlayback

            // 等待 Surface 就绪
            while (surface == null) {
                Thread.sleep(50)
            }

            val surfaceContext = ICatchSurfaceContext(surface)
            videoPlayback?.enableRender(surfaceContext)

            val filePath = "/DCIM/$fileName"
            val iCatchFile = ICatchFile(
                0, ICatchFileType.ICH_FILE_TYPE_VIDEO,
                filePath, fileName, 0, "", 0.0, 0, 0, 0, 0
            )

            videoPlayback?.play(iCatchFile, true, true)
            videoPlayback?.resume()
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "播放失败: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onBackPressed() {
        executor.execute {
            try {
                videoPlayback?.stop()
                session?.destroySession()
            } catch (_: Exception) {}
        }
        super.onBackPressed()
    }
}
```

### 5.2 MainActivity 输入界面（Compose）

```kotlin
@Composable
fun MainScreen(onPlay: (ip: String, filename: String) -> Unit) {
    var ip by remember { mutableStateOf("192.168.1.1") }
    var filename by remember { mutableStateOf("") }
    val ipHistory = remember { loadIpHistory() }
    val fileHistory = remember { loadFileHistory() }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Camera Video Playback", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // IP 输入（带历史下拉）
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            label = { Text("Camera IP") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 文件名输入（带历史下拉）
        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("Filename (e.g. Go5_0001.MP4)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                saveToHistory(ip, filename)
                onPlay(ip, filename)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = ip.isNotBlank() && filename.isNotBlank()
        ) {
            Text("Play")
        }
    }
}
```

---

## 6. 实现步骤

| 步骤 | 内容 | 预计耗时 |
|------|------|----------|
| 1 | 修改 `build.gradle.kts` 引入 SDK AAR（Reliant + VR） | 10 min |
| 2 | 添加 `PlaybackActivity`（传统 SurfaceView + SDK 播放逻辑） | 30 min |
| 3 | 修改 `MainActivity` 为输入界面（IP + 文件名 + 历史记录） | 20 min |
| 4 | 添加横屏全屏主题和 `AndroidManifest` 配置 | 10 min |
| 5 | 测试和调优 | 20 min |

---

## 7. 风险与注意事项

| 风险 | 缓解措施 |
|------|----------|
| SDK 对 Surface 生命周期敏感 | `PlaybackActivity` 使用传统 `SurfaceView`，确保 `surfaceCreated` 后再调用 `enableRender` |
| 相机连接超时 | 所有 SDK 调用放在后台线程，UI 显示 loading / 错误提示 |
| 文件名错误导致播放失败 | UI 显示完整拼接后的路径（如 `/DCIM/Go5_0001.MP4`）供用户核对 |
| Activity 切换导致泄漏 | `onDestroy` / `onBackPressed` 中确保调用 `stop()` + `destroySession()` |

---

## 8. 待确认事项

1. **SDK AAR 引入方式：** 复制 AAR 到 `app/libs/` 并使用 `implementation(files("libs/xxx.aar"))`，还是通过 `flatDir` 仓库引用？
2. **目标相机型号：** 确认测试相机的默认 IP 是否确实是 `192.168.1.1`。
3. **文件命名规范：** 文件名是否一定以 `.MP4` 结尾，是否区分大小写？

---

*方案版本：v1.0*
*日期：2026-05-22*
