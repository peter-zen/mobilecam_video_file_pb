# MobileCamSDK 视频文件播放集成指南

## 概述

本文档描述如何在 Android 应用中集成 MobileCamSDK，实现对相机存储卡内视频文件的 RTSP 流播放。相机端以 MJPG 编码通过 RTSP 协议提供视频流；SDK 内部负责网络传输、解码和渲染。

## SDK 依赖与模块说明

### 依赖文件

集成需要以下两部分：

| 文件 | 说明 |
|------|------|
| `libs/ICatchtekReliant.jar` | 基础通信层（transport、session、文件类型定义） |
| `libs/ICatchtekVR.jar` | 视频播放与渲染层（pancam control、playback、surface context） |
| `jniLibs/<abi>/*.so` | 原生库，必须打包进 APK |

### 原生库（`.so`）

按 ABI 放入 `app/src/main/jniLibs/`：

```
arm64-v8a/
  libreliant.so              — 核心通信与 RTSP 协议处理
  libpanorama_vr.so          — 视频渲染（OpenGL）
  libdepth_net_transport.so  — 深度网络传输（RTSP 依赖）
  libusb_transport.so        — USB 传输备用
  libc++_shared.so           — C++ 运行时
```

> 至少需要 `arm64-v8a` 和 `armeabi-v7a` 两个 ABI 以覆盖主流设备。

### Gradle 配置

```kotlin
dependencies {
    implementation(files("libs/ICatchtekReliant.jar"))
    implementation(files("libs/ICatchtekVR.jar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
}
```

---

## SDK 架构与核心类

MobileCamSDK 采用三层架构：

1. **Reliant 层** (`ICatchINETTransport`) — 建立到相机的 TCP/RTSP 网络通道。
2. **Control 层** (`ICatchIPancamControl`) — 注册事件监听器，接收播放状态事件。
3. **Pancam/VR 层** (`ICatchIPancamVideoPlayback`) — 视频播放控制（play / pause / resume / seek / stop）。

关键类职责：

| 类 | 职责 |
|----|------|
| `ICatchPancamSession` | 会话管理：`createSession()` / `prepareSession()` / `destroySession()` |
| `ICatchINETTransport` | 网络传输构造器，入参为相机 IP 地址 |
| `ICatchIPancamVideoPlayback` | 播放控制：`play()`, `resume()`, `pause()`, `stop()`, `seek()`, `getLength()` |
| `ICatchIPancamControl` | 事件监听注册：`addEventListener(eventID, listener)` |
| `ICatchSurfaceContext` | 将 Android `Surface` 封装为 SDK 渲染目标 |
| `ICatchFile` | 描述相机端文件，用于 `play()` |
| `ICatchGLEvent` / `ICatchGLEventID` | 事件对象与事件常量定义 |

---

## RTSP 播放链路

### 播放 URL 的映射关系

在 VLC 等播放器中，相机视频可直接用标准 RTSP URL 播放：

```
rtsp://<camera_ip>:554/DCIM/Go5_000001_20260522_165437.MP4
```

使用 MobileCamSDK 时，**不需要手动构造 RTSP URL**。SDK 内部通过以下方式完成协议转换：

- 网络层：`ICatchINETTransport(ip)` 建立到 `ip:554` 的 RTSP 会话。
- 文件层：`ICatchFile` 的 path 字段传入 `/DCIM/<filename>`，SDK 内部将其映射为 `net:///DCIM/xxx.MP4`，再进一步转为上述 RTSP URL 进行拉流。

因此调用方只需提供 **相机 IP** 和 **文件名** 即可，不需要关心 RTSP URL 格式。

### 完整播放流程

```kotlin
// 1. 创建传输与会话
val transport = ICatchINETTransport(ip)
val session = ICatchPancamSession.createSession()

// 2. 准备会话（必须在子线程执行）
val ppi = ICatchGLDisplayPPI(metrics.xdpi, metrics.ydpi)
val prepared = session.prepareSession(transport, ICatchGLColor.BLACK, ppi)

// 3. 获取播放实例
val videoPlayback = session.videoPlayback
val pancamControl = session.getControl()

// 4. 注册事件监听（必须在播放前）
pancamControl.addEventListener(
    ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS,
    listener
)

// 5. 绑定渲染 Surface
val surfaceContext = ICatchSurfaceContext(surface)
videoPlayback.enableRender(surfaceContext)
surfaceContext.setViewPort(0, 0, surfaceWidth, surfaceHeight)

// 6. 构造文件对象并播放
val file = ICatchFile(
    0,
    ICatchFileType.ICH_FILE_TYPE_VIDEO,
    "/DCIM/$fileName",
    fileName,
    0, "", 0.0, 0, 0, 0, 0
)
videoPlayback.play(file, disableAudio = true, remote = true)
videoPlayback.resume()   // 必须调用，否则画面不会开始

// 7. 获取总时长（秒，double）
val totalDuration = videoPlayback.getLength()
```

> `play()` 的第二个参数 `disableAudio` 设为 `true` 表示只拉视频流；`remote` 设为 `true` 表示从相机端文件播放（本地文件则传 `false`）。

---

## 事件监听与 UI 状态同步

播放状态不是通过返回值或轮询获取，而是通过 **事件回调** 驱动。必须注册以下三个事件：

### 1. 播放进度事件 `ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS` (0x43)

```kotlin
ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS -> {
    val pts = event.doubleValue1   // 当前播放位置，单位秒
    // 更新 SeekBar 和当前时间文本
}
```

这是唯一可靠的进度来源。SDK 会以固定频率（约 500ms）推送该事件。

### 2. 播放结束事件 `ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED` (0x45)

```kotlin
ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED -> {
    // 播放到达文件末尾，更新 UI 为停止状态
}
```

### 3. 缓冲状态事件 `ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED` (0x48)

```kotlin
ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED -> {
    val state = event.longValue1.toInt()
    when (state) {
        1 -> showLoading()   // 开始缓冲/解码
        2 -> hideLoading()   // 缓冲完成，开始渲染
    }
}
```

> 不要在 `resume()` 返回成功后立即隐藏 Loading。`resume()` 仅表示命令下发成功，真正的首帧渲染完成应以 `CACHING_CHANGED(2)` 为准。

---

## Seek 实现与同步策略

### 问题背景

调用 `videoPlayback.seek(pos)` 后，SDK 会经历 **命令下发 → 服务端 seek → 缓冲 → 新位置首帧** 的过程，耗时可达数秒。在此期间旧的 `VIDEO_STREAM_PLAYING_STATUS` 事件仍可能继续到来，如果直接用事件更新 UI，SeekBar 会先跳回旧位置，造成闪烁。

### 解决方案

采用 **seek 状态锁 + 目标位置验证**：

```kotlin
private var isSeeking = false
private var seekTargetPosition = -1.0

// 用户开始拖动
override fun onStartTrackingTouch(seekBar: SeekBar?) {
    isSeeking = true
}

// 用户松开
override fun onStopTrackingTouch(seekBar: SeekBar?) {
    val progress = seekBar?.progress ?: 0
    val seekPos = progress / 1000.0 * totalDuration
    seekTargetPosition = seekPos
    // isSeeking 保持 true，等待事件确认
    videoPlayback?.seek(seekPos)
}

// 事件回调中判断 seek 是否生效
if (isSeeking) {
    val jumpedToTarget = seekTargetPosition >= 0 &&
                         kotlin.math.abs(pts - seekTargetPosition) < 2.0
    val bigJump = kotlin.math.abs(pts - currentPosition) > 3.0

    if (jumpedToTarget || bigJump) {
        isSeeking = false
        seekTargetPosition = -1.0
    } else {
        // 还是旧位置，忽略本次事件，保持 SeekBar 在用户拖动的位置
        return
    }
}
currentPosition = pts
// 更新 UI
```

### 判定规则说明

| 条件 | 含义 |
|------|------|
| `jumpedToTarget` | 事件位置与目标位置相差小于 2 秒，认为 seek 已生效 |
| `bigJump` | 事件位置与当前记录位置相差大于 3 秒，即使不完全等于目标值，也视为有效跳转（兼容服务端精度差异） |

---

## SurfaceView 生命周期

SDK 渲染必须在 `SurfaceHolder.Callback.surfaceChanged()` 中启动，因此时 `width` / `height` 已确定，且 `Surface` 有效。

```kotlin
holder.addCallback(object : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        surfaceReady = true
    }

    override fun surfaceChanged(holder, format, width, height) {
        surface = holder.surface
        surfaceWidth = width
        surfaceHeight = height
        if (!hasStarted && surfaceReady) {
            hasStarted = true
            executor.execute { startPlayback(ip, fileName) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        surfaceReady = false
    }
})
```

> 必须在子线程执行 `startPlayback()`，因为 `prepareSession()` 和 `play()` 都是阻塞调用。

---

## 资源释放

Activity 退出时必须按顺序释放资源，防止内存泄漏和 native crash：

```kotlin
private fun release() {
    if (isReleased) return
    isReleased = true

    mainHandler.removeCallbacks(updateProgressRunnable)

    pancamControl?.removeEventListener(
        ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS,
        listener
    )
    pancamControl?.removeEventListener(
        ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED,
        listener
    )
    pancamControl?.removeEventListener(
        ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED,
        listener
    )

    videoPlayback?.pause()
    videoPlayback?.stop()
    session?.destroySession()

    executor.shutdown()
}
```

> 所有 SDK 调用都可能抛异常，建议用 `try/catch` 包裹每个调用。

---

## 常见问题与排查

### 1. `prepareSession()` 返回 false

- 确认手机已连接相机 WiFi。
- 确认 IP 地址可 ping 通（相机 AP 默认地址通常是 `192.168.1.1`）。
- 确认端口 554 未被防火墙拦截。

### 2. `play()` 返回 false

- 确认 `ICatchFile` 的 path 格式为 `/DCIM/<filename>`，且文件确实存在于相机存储卡。
- 确认 `remote = true`。

### 3. `resume()` 成功但没有画面

- 检查 `enableRender(surfaceContext)` 是否在 `resume()` 之前调用。
- 检查 `surfaceContext.setViewPort()` 是否设置了正确的宽高。
- 观察 `VIDEO_PLAYBACK_CACHING_CHANGED` 事件，若始终没有 `state=2`，说明解码器未就绪，可能是相机端文件编码格式不受支持。

### 4. SeekBar 拖动后跳回原位置

- 确保 `isSeeking` 在 `onStopTrackingTouch` 后保持 `true`。
- 确保事件回调中有 seek 生效验证逻辑（见上方 Seek 同步策略）。

### 5. 退出 Activity 时崩溃

- 确保 `release()` 只执行一次（使用 `isReleased` 原子标志）。
- 确保 `executor.shutdown()` 在 SDK 资源释放之后调用。
- 不要在 `onBackPressed()` 和 `onDestroy()` 中重复调用 `release()` 而没有 guard。

### 6. 安装新版本后默认文件名未更新

- `adb install -r` 会保留 SharedPreferences。如需清理旧数据，使用 `adb uninstall` 后再安装。

---

## 最小集成示例

以下代码展示了一个最小可用的 PlaybackActivity 骨架：

```kotlin
class MinimalPlaybackActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private var session: ICatchPancamSession? = null
    private var videoPlayback: ICatchIPancamVideoPlayback? = null
    private var pancamControl: ICatchIPancamControl? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isReleased = false

    private val listener = object : ICatchIPancamListener {
        override fun eventNotify(event: ICatchGLEvent) {
            when (event.eventID) {
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS -> {
                    val pts = event.doubleValue1
                    mainHandler.post { updateProgress(pts) }
                }
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED -> {
                    mainHandler.post { onPlaybackEnded() }
                }
                ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED -> {
                    val state = event.longValue1.toInt()
                    mainHandler.post { updateLoading(state) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... 初始化 SurfaceView
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h: Int) {
                executor.execute { start("192.168.1.1", "Go5_0001.MP4", h.surface, w, h) }
            }
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
    }

    private fun start(ip: String, name: String, surface: Surface, w: Int, h: Int) {
        val transport = ICatchINETTransport(ip)
        val sess = ICatchPancamSession.createSession()
        session = sess

        val prepared = sess.prepareSession(transport, ICatchGLColor.BLACK,
            ICatchGLDisplayPPI(resources.displayMetrics.xdpi, resources.displayMetrics.ydpi))
        if (!prepared) return

        val vp = sess.videoPlayback ?: return
        val ctrl = sess.getControl() ?: return
        videoPlayback = vp
        pancamControl = ctrl

        ctrl.addEventListener(ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS, listener)
        ctrl.addEventListener(ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED, listener)
        ctrl.addEventListener(ICatchGLEventID.ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED, listener)

        val ctx = ICatchSurfaceContext(surface)
        vp.enableRender(ctx)
        ctx.setViewPort(0, 0, w, h)

        val file = ICatchFile(0, ICatchFileType.ICH_FILE_TYPE_VIDEO, "/DCIM/$name", name,
            0, "", 0.0, 0, 0, 0, 0)
        vp.play(file, true, true)
        vp.resume()
    }

    private fun release() {
        if (isReleased) return
        isReleased = true
        try { pancamControl?.removeEventListener(ICatchGLEventID.ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS, listener) } catch (_: Exception) {}
        try { videoPlayback?.pause() } catch (_: Exception) {}
        try { videoPlayback?.stop() } catch (_: Exception) {}
        try { session?.destroySession() } catch (_: Exception) {}
        executor.shutdown()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
```

---

## 附录：事件常量速查

| 常量 | 值 | 说明 |
|------|-----|------|
| `ICH_GL_EVENT_VIDEO_STREAM_PLAYING_STATUS` | `0x43` | 播放进度，`doubleValue1` = 当前秒数 |
| `ICH_GL_EVENT_VIDEO_STREAM_PLAYING_ENDED` | `0x45` | 播放结束 |
| `ICH_GL_EVENT_VIDEO_PLAYBACK_CACHING_CHANGED` | `0x48` | 缓冲状态，`longValue1` = 1(开始) / 2(结束) |
