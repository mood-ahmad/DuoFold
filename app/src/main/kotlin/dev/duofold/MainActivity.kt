package dev.duofold

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.layerBackdrop
import dev.duofold.bridge.BridgeClient
import dev.duofold.ui.glass.BlurredBar
import dev.duofold.ui.glass.DuoButton
import dev.duofold.ui.glass.DuoCard
import dev.duofold.ui.glass.DuoSlider
import dev.duofold.ui.glass.DuoToggle
import dev.duofold.ui.glass.GlassBackdropHost
import dev.duofold.ui.glass.PagerScrollGate
import dev.duofold.ui.glass.rememberBlurBackdrop
import dev.duofold.ui.theme.DuoTheme
import dev.duofold.ui.theme.LocalIsDark
import dev.duofold.ui.theme.LocalThemeMode
import dev.duofold.ui.theme.ThemeMode
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var client: BridgeClient
    private lateinit var prefs: SharedPreferences
    private var pendingStart = false
    private var pendingTrial = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全沉浸 edge-to-edge：状态栏/底部小白条透明，内容延伸到屏幕边缘（跟随主题自动明暗）
        enableEdgeToEdge()
        client = BridgeClient.get(this)
        prefs = getSharedPreferences("duo", Context.MODE_PRIVATE)

        // 迁移旧的 UI 数据
        if (prefs.getInt("motionVersion", 0) < 3) {
            prefs.edit().putInt("motionVersion", 3).apply()
        }

        setContent {
            var themeMode by remember {
                mutableStateOf(
                    when (prefs.getString("theme", "system")) {
                        "light" -> ThemeMode.Light
                        "dark" -> ThemeMode.Dark
                        else -> ThemeMode.System
                    }
                )
            }
            DuoTheme(themeMode) {
                MainScreen(
                    toggleTheme = {
                        themeMode = when (themeMode) {
                            ThemeMode.System -> ThemeMode.Light
                            ThemeMode.Light -> ThemeMode.Dark
                            ThemeMode.Dark -> ThemeMode.System
                        }
                        prefs.edit().putString(
                            "theme",
                            when (themeMode) {
                                ThemeMode.System -> "system"
                                ThemeMode.Light -> "light"
                                ThemeMode.Dark -> "dark"
                            }
                        ).apply()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        client.connect()
    }

    @Composable
    private fun MainScreen(toggleTheme: () -> Unit) {
        val context = LocalContext.current
        val isDark = LocalIsDark.current
        val themeMode = LocalThemeMode.current
        var status by remember { mutableStateOf(prefs.getString("lastStatus", "准备就绪后，先体验 10 秒") ?: "") }
        var accessConnected by remember { mutableStateOf(FoldService.current() != null) }
        var active by remember { mutableStateOf(false) }
        var progressText by remember { mutableStateOf("") }
        var zAxis by remember { mutableStateOf(prefs.getBoolean("zAxis", false)) }
        var useVulkan by remember { mutableStateOf(prefs.getString("renderer", "gl") == "vk") }

        // 刷新循环（替代原 Java 版每 400ms 的 Handler postDelayed）
        LaunchedEffect(Unit) {
            while (true) {
                val service = FoldService.current()
                accessConnected = service != null
                active = service?.isActive ?: false
                status = service?.status() ?: prefs.getString("lastStatus", "准备就绪后，先体验 10 秒") ?: ""
                progressText = if (active) "展开 ${Math.round((service?.progress() ?: 0f) * 100)}%  ·  已更新 ${service?.frames() ?: 0} 帧" else ""
                delay(400)
            }
        }

        val scrollBehavior = MiuixScrollBehavior()
        // 手感参数（拖动条防抖提交，且保持 Compose state 实时联动）
        var range by remember { mutableStateOf(prefs.getFloat("range", 80f)) }
        var intensity by remember { mutableStateOf(prefs.getFloat("intensity", 1f) * 100f) }
        var blurScale by remember { mutableStateOf(prefs.getFloat("blurScale", 1f) * 100f) }
        var stretch by remember { mutableStateOf(prefs.getFloat("stretch", 1f) * 100f) }
        var fps by remember { mutableStateOf(prefs.getInt("fps", 60).toFloat()) }
        GlassBackdropHost {
            // —— KernelSU SettingsMiuix 1:1 照抄（仅改 Title 文本 + 保留深色切换按钮）——
            val blurBackdrop = rememberBlurBackdrop(true)
            val blurActive = blurBackdrop != null
            val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
            Scaffold(
                topBar = {
                    BlurredBar(blurBackdrop) {
                        TopAppBar(
                            title = "Duo Fold",
                            largeTitle = "Duo Fold",
                            scrollBehavior = scrollBehavior,
                            color = barColor,
                            titleColor = MiuixTheme.colorScheme.onSurface,
                            actions = {
                                // 深色切换：无涟漪遮罩；随顶栏折叠淡出（折叠后不可见）
                                val collapsedAlpha = 1f - scrollBehavior.state.collapsedFraction.coerceIn(0f, 1f)
                                val interaction = remember { MutableInteractionSource() }
                                Box(
                                    Modifier
                                        .graphicsLayer { alpha = collapsedAlpha }
                                        .clickable(
                                            interactionSource = interaction,
                                            indication = null,
                                            onClick = toggleTheme,
                                        )
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = when (themeMode) {
                                            ThemeMode.System -> if (isDark) "浅色" else "深色"
                                            ThemeMode.Light -> "暗色"
                                            ThemeMode.Dark -> "亮色"
                                        },
                                        fontSize = 14.sp,
                                        color = Color(0xFF3482FF),
                                    )
                                }
                            },
                        )
                    }
                },
                // KSU 原样：仅保留水平 insets，顶部/底部让给 topBar 与列表
                contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
            ) { innerPadding ->
                // 内容录制层：只包内容区（KSU 原样），backdrop 为空时跳过修饰
                Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                    LazyColumn(
                        userScrollEnabled = PagerScrollGate.enabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .padding(horizontal = 16.dp),
                        contentPadding = innerPadding,
                    ) {
                        item(key = "start") {
                        // ---- 开启步骤卡片 ----
                        DuoCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Text(
                                text = "开启步骤",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                            )
                            val bridgeMsg = client.message()
                            Text(
                                text = bridgeMsg,
                                fontSize = 13.sp,
                                color = Color(0xFF9299A6),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            DuoButton(
                                "01   连接并授权 Shizuku",
                                modifier = Modifier.padding(top = 20.dp),
                            ) { client.request() }
                        Text(
                            text = if (accessConnected) "屏幕效果辅助服务已连接" else "等待开启屏幕效果辅助服务",
                            fontSize = 13.sp,
                            color = Color(0xFF9299A6),
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp),
                        )
                        DuoButton("02   开启屏幕效果辅助服务", modifier = Modifier.padding(top = 20.dp)) { openAccessibility(context) }
                        Text(
                            text = "辅助服务用于显示效果与修正触控。画面只在本机内存处理，不保存、不上传。",
                            fontSize = 12.sp,
                            color = Color(0xFF9299A6),
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp),
                        )
                        DuoButton("03   体验 10 秒", modifier = Modifier.padding(top = 20.dp)) {
                            if (active) FoldService.current()?.stopSession("已手动停止")
                            else requestStart(context, trial = true)
                        }
                        DuoButton("04   开启全局效果", modifier = Modifier.padding(top = 19.dp)) {
                            val s = FoldService.current()
                            if (s != null && s.isActive) s.stopSession("已手动停止")
                            else requestStart(context, trial = false)
                        }
                        DuoButton("重新校准当前握姿", modifier = Modifier.padding(top = 19.dp)) {
                            val s = FoldService.current()
                            if (s != null && s.isActive) { s.calibrate(); toast(context, "已校准") }
                        }
                        // 状态提示：跟随三个操作按钮（03/04/校准），标识它们的运行状态
                        Text(
                            text = status,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 20.dp),
                        )
                        Text(
                            text = progressText,
                            fontSize = 12.sp,
                            color = Color(0xFF9299A6),
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 4.dp),
                        )
                        Text(
                            text = if (Build.VERSION.SDK_INT >= 34)
                                "三指同时触屏或使用通知栏按钮，随时停止效果。"
                            else
                                "使用通知栏按钮，随时停止效果。",
                            fontSize = 12.sp,
                            color = Color(0xFFBFD2FF),
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp, bottom = 8.dp),
                        )
                        }
                    }

                    item(key = "tuning") {
                    // ---- 手感调校卡片 ----
                    DuoCard(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                        Text(
                            text = "调到舒服的手感",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )

                        DuoSlider(
                            label = "最大倾斜角度",
                            initial = range,
                            valueRange = 15f..80f,
                            suffix = "°",
                            onValueChange = { range = it; prefs.edit().putFloat("range", it).apply() },
                        )
                        DuoSlider(
                            label = "空间强度",
                            initial = intensity,
                            valueRange = 20f..100f,
                            suffix = "%",
                            onValueChange = { intensity = it; prefs.edit().putFloat("intensity", it / 100f).apply() },
                        )
                        DuoSlider(
                            label = "模糊强度",
                            initial = blurScale,
                            valueRange = 0f..200f,
                            suffix = "%",
                            onValueChange = { blurScale = it; prefs.edit().putFloat("blurScale", it / 100f).apply() },
                        )
                        DuoSlider(
                            label = "拉扯幅度",
                            initial = stretch,
                            valueRange = 0f..150f,
                            suffix = "%",
                            onValueChange = { stretch = it; prefs.edit().putFloat("stretch", it / 100f).apply() },
                        )
                        DuoSlider(
                            label = "帧数上限",
                            initial = fps,
                            valueRange = 30f..180f,
                            suffix = "Hz",
                            onValueChange = { fps = it; prefs.edit().putInt("fps", Math.round(it)).apply() },
                        )

                        DuoDivider()
                        DuoToggle(
                            title = "Z 轴感应 · 前后倾斜",
                            summary = "前后倾斜产生上/下方向的展开",
                            checked = zAxis,
                            onChange = { zAxis = it; prefs.edit().putBoolean("zAxis", it).apply() },
                        )
                        DuoSec(
                            "关闭：只响应左右翻转。开启：前后倾斜也会产生上/下方向的展开；" +
                                "斜着移动时两个方向连续合成。横竖屏会自动切换到当前界面的坐标轴，参数在下次开启时生效。"
                        )

                        DuoDivider()

                        // Vulkan 开关 + 紧邻说明（新增提示）
                        DuoToggle(
                            title = "Vulkan 渲染（直连加速）",
                            summary = "跳过 SurfaceTexture 中间拷贝，采集帧直通 GPU",
                            checked = useVulkan,
                            onChange = { useVulkan = it; prefs.edit().putString("renderer", if (it) "vk" else "gl").apply() },
                        )
                        DuoSec(
                            "OpenGL：兼容性优先，适合 Android 10–13。Vulkan：零拷贝直连，帧率更高，" +
                                "首次开启若遇闪退请切回 OpenGL 并反馈。切换后下次开启效果生效。"
                        )
                    }
                    }

                    item(key = "footer") {
                    Text(
                        text = "作者 jcx  ·  GitHub ↗",
                        fontSize = 11.sp,
                        color = Color(0xFF3482FF),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jcx396905-gif")))
                            }
                            .padding(vertical = 22.dp),
                    )
                    }
                    }
                }
            }
        }
    }

    @Composable
    private fun DuoDivider() {
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    @Composable
    private fun DuoSec(text: String) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = Color(0xFF9299A6),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 8.dp),
        )
    }

    private fun requestStart(context: Context, trial: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            pendingTrial = trial
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 51)
            return
        }
        startSession(context, trial)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 51 && pendingStart) {
            pendingStart = false
            startSession(this, pendingTrial)
        }
    }

    private fun startSession(context: Context, trial: Boolean) {
        val s = FoldService.current()
        if (s == null) { openAccessibility(context); return }
        s.startSession(trial)
        if (s.isActive) moveTaskToBack(true)
    }

    private fun openAccessibility(context: Context) {
        try {
            context.startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(
                        "android.intent.extra.COMPONENT_NAME",
                        ComponentName(this, FoldService::class.java),
                    )
            )
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}