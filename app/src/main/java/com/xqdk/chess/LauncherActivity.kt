package com.xqdk.chess

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * 开局分流落地页（2026-11-09 用户拍板，参照象棋鱼 MainActivity 模式）：启动后按
 * "学习模式 / 连线模式"分流，帮助/关于入口与检查更新（2026-11-10 自连线页上移，
 * 版本页脚）都在本页。
 * 免责弹窗随 launcher 语义一并迁入：每次冷启动必弹（savedInstanceState 守卫=
 * 配置重建走恢复路径不重弹；isTaskRoot 守卫=singleTask 复用/通知唤起不重弹），
 * 弹窗在屏时旋转由 onResume 补弹兜底（体检 P1 旁路缺口语义原样保留）。
 */
class LauncherActivity : AppCompatActivity() {

    companion object {

        /**
         * 免责声明（三条条款，对齐 Cabinet 口径；首启弹窗 / About / 帮助页 / README
         * 共用同一文案，任何修改需四处同步，见 法律应对预案.md 模板 E）。
         * 原"24 小时内自行删除"条款已删（2026-11-02）：与 GPL-3.0 永久授权自相矛盾。
         */
        const val DISCLAIMER =
            "本软件（象棋迪克）为象棋学习辅助工具，仅供个人学习、复盘研究与技术交流使用。请在使用前仔细阅读以下条款：\n\n" +
            "一、本软件仅通过系统录屏授权识别画面并以悬浮窗显示走法建议，不包含自动走子、触摸注入、无障碍服务、Root/Xposed 等功能，所有着法均由玩家手动操作。\n\n" +
            "二、严禁将本软件用于任何正式比赛、等级分对局、网络对局平台或赌博等违反平台规则与法律法规的场景；由此产生的一切后果由使用者本人承担。\n\n" +
            "三、本软件按\"现状\"提供，不对软件的可用性与准确性作任何保证，不对因使用本软件造成的任何直接或间接损失承担责任。\n\n" +
            "点击\"同意并继续\"即表示您已阅读、理解并同意以上全部条款。"

        /** 鸣谢（品牌说明中唯一保留"象棋鱼"字眼处） */
        const val CREDITS =
            "鸣谢：\n" +
            "· 本项目基于开源项目\"象棋鱼\"（作者 zfdang，GitHub: zfdang/chinese-chess-android）二次开发；\n" +
            "· YOLO 棋子检测权重文件来自 Vincentzyx/VinXiangQi 项目；\n" +
            "· ONNX 两步式识别引擎（RTMPose 角点 + Swin 分类）的模型与算法来自 " +
            "Shirakawa-Kotone/chinese-chess-helper 项目；\n" +
            "· 引擎框架基座：Pikafish 团队与 DroidFish（Peter Österlund），GPL-3.0 开源授权。"

        /** 免责同意态的 savedInstanceState 键（仅跨配置重建传递，不持久化——冷启动必重弹） */
        private const val KEY_DISCLAIMER_AGREED = "disclaimer_agreed"
    }

    /** 免责同意态（仅本实例内存，经 savedInstanceState 跨配置重建） */
    private var disclaimerAgreed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        // 每次应用启动必弹；savedInstanceState/isTaskRoot 双守卫语义从连线页原样迁入
        if (savedInstanceState == null && isTaskRoot()) {
            maybeShowDisclaimer()
        } else {
            disclaimerAgreed = savedInstanceState?.getBoolean(KEY_DISCLAIMER_AGREED, false) ?: false
            if (!disclaimerAgreed) maybeShowDisclaimer()
        }

        findViewById<LinearLayout>(R.id.card_learn).setOnClickListener {
            startActivity(Intent(this, com.xqdk.chess.learn.LearnActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.card_assist).setOnClickListener {
            startActivity(Intent(this, com.xqdk.chess.assist.ui.AssistActivity::class.java))
        }

        findViewById<Button>(R.id.button_help).setOnClickListener {
            val intent = Intent(this, com.xqdk.chess.views.WebviewActivity::class.java).apply {
                putExtra("url", "file:///android_asset/help.html")
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.button_about).setOnClickListener { showAboutDialog() }

        // 版本页脚：versionName 含构建元数据（describe 推导），构建日期是检查更新的比较口径
        findViewById<android.widget.TextView>(R.id.tv_version_footer).text =
            "v${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.BUILD_DATE}"
        setupUpdateCheck()
    }

    /**
     * 检查更新：仅手动触发（不主动检查是产品拍板），自连线页上移（2026-11-10 用户
     * 拍板"移动到首页"）。查询走独立线程（AppUpdateChecker.check 为网络 IO，3s 超时），
     * 结果回主线程弹窗/Toast；按钮检查期间禁用防重复点击。失败如实提示——
     * api.github.com 在中国大陆通常需可访问 GitHub 的网络环境
     */
    private fun setupUpdateCheck() {
        val btnUpdate = findViewById<Button>(R.id.btn_check_update)
        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false
            btnUpdate.text = "检查中…"
            val checker = com.xqdk.chess.assist.AppUpdateChecker(BuildConfig.BUILD_DATE)
            // 一次性线程（查询完毕自然回收，不留滞留池）
            Thread {
                val result = checker.check()
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "检查更新"
                    when (result) {
                        is com.xqdk.chess.assist.UpdateResult.UpToDate ->
                            android.widget.Toast.makeText(this,
                                "已是最新版本（构建日期 ${BuildConfig.BUILD_DATE}）",
                                android.widget.Toast.LENGTH_SHORT).show()
                        is com.xqdk.chess.assist.UpdateResult.Newer ->
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("发现新版本 ${result.tag}")
                                .setMessage(
                                    "最新稳定版发布于 ${result.date}（当前构建 ${BuildConfig.BUILD_DATE}）。\n\n" +
                                    "APK 请从 GitHub Release 页面下载安装（需要可访问 GitHub 的网络环境）。\n\n" +
                                    "注意：v3.2 起为官方安全版本线（全新签名证书 + 发布指纹门禁）。\n" +
                                    "从本版及更早版本升级需先卸载再安装（仅此一次，之后恢复覆盖升级）。" +
                                    "请只从官方渠道下载，证书指纹可在 README 核验。"
                                )
                                .setPositiveButton("打开下载页") { _, _ ->
                                    runCatching {
                                        startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(
                                                com.xqdk.chess.assist.AppUpdateChecker.RELEASES_PAGE))
                                        )
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        is com.xqdk.chess.assist.UpdateResult.Failure ->
                            android.widget.Toast.makeText(this, result.message,
                                android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DISCLAIMER_AGREED, disclaimerAgreed)
    }

    /**
     * 免责声明弹窗：每次启动必弹（无可跳过、无持久化跳过），明确"仅供学习研究、
     * 禁止用于对局平台"等使用边界；同意后进入落地页，不同意直接退出应用。
     */
    private fun maybeShowDisclaimer() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(DISCLAIMER)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { d, _ ->
                disclaimerAgreed = true
                d.dismiss()
            }
            .setNegativeButton("不同意并退出") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    /** 关于：本地对话框（特色功能 + 免责声明 + 鸣谢），不依赖外部站点 */
    private fun showAboutDialog() {
        val text = "象棋迪克 XQDK v${BuildConfig.VERSION_NAME}\n\n" +
            "特色功能：\n" +
            "· 学习模式：内置 Pikafish 人机对弈、走子纠正教学（幽灵棋子+三解指导）、" +
            "走势曲线，执红/黑任选；\n" +
            "· AI 连线识别：ONNX 角点式（主力）/ YOLO 免校准屏幕识别、自动朝向、" +
            "悬浮窗实时走法建议、开局库速查、多候选与紧急手动模式（只识别提示，不自动走子）；\n\n" +
            DISCLAIMER + "\n\n" +
            CREDITS
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("关于 · 象棋迪克")
            .setIcon(R.drawable.ic_launcher)
            .setMessage(text)
            .setPositiveButton("我知道了", null)
            .show()
    }
}
