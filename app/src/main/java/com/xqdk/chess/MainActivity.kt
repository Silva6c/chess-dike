package com.xqdk.chess

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xqdk.chess.assist.ui.AssistActivity
import com.xqdk.chess.views.WebviewActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "xqdk"
        private const val KEY_DISCLAIMER_AGREED = "disclaimer_agreed_v1"

        /** 免责声明（首启弹窗 / About / 连线页共用同一口径） */
        const val DISCLAIMER =
            "免责声明：本应用（象棋迪克）仅供个人学习、研究与文化交流使用，" +
            "严禁用于商业用途或任何违反法律法规、游戏平台规则的情形；" +
            "请于获取后 24 小时内自行删除。" +
            "本应用不含自动对弈、点击注入、无障碍服务、Root/Xposed 等功能，" +
            "对局中的所有操作均由玩家本人完成；" +
            "因使用本应用产生的一切后果由使用者自行承担。"

        /** 鸣谢（品牌说明中唯一保留"象棋鱼"字眼处） */
        const val CREDITS =
            "鸣谢：\n" +
            "· 本项目基于开源项目\"象棋鱼\"（作者 zfdang，GitHub: zfdang/chinese-chess-android）二次开发；\n" +
            "· YOLO 棋子检测权重文件来自 VinXiangQi 项目；\n" +
            "· 引擎与对弈框架基座：Pikafish 团队与 DroidFish（Peter Österlund），GPL-3.0 开源授权。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        maybeShowDisclaimer()

        // Bind buttons
        val buttonPlay: Button = findViewById(R.id.button_play)
        val buttonLink: Button = findViewById(R.id.button_link)
        val buttonLearn: Button = findViewById(R.id.button_learn)
        val buttonHelp: Button = findViewById(R.id.button_help)
        val buttonAbout: Button = findViewById(R.id.button_about)

        // Set click listeners
        buttonPlay.setOnClickListener {
            val intent = Intent(this, GameActivity::class.java)
            startActivity(intent)
        }

        buttonLink.setOnClickListener {
            // 连线：屏幕识别 + 悬浮窗走法指导
            val intent = Intent(this, AssistActivity::class.java)
            startActivity(intent)
        }

        buttonLearn.setOnClickListener {
            // launch manual activity
            val intent = Intent(this, ManualActivity::class.java)
            startActivity(intent)
        }

        buttonHelp.setOnClickListener {
            // 帮助：本地页面（使用说明 + 免责声明）
            val intent = Intent(this, WebviewActivity::class.java).apply {
                putExtra("url", "file:///android_asset/help.html")
            }
            startActivity(intent)
        }

        buttonAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    /** 首次启动弹免责声明：同意后记忆，不同意即退出 */
    private fun maybeShowDisclaimer() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_AGREED, false)) return
        AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(DISCLAIMER)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { d, _ ->
                prefs.edit().putBoolean(KEY_DISCLAIMER_AGREED, true).apply()
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
            "· 完整对弈（Pikafish 引擎，固定深度/固定时间/开局库/盘面调节）与打谱；\n" +
            "· AI 连线识别：YOLO 免校准屏幕识别、自动朝向、悬浮窗实时走法建议、" +
            "开局库速查、多候选与紧急手动模式（只识别提示，不自动走子）。\n\n" +
            DISCLAIMER + "\n\n" +
            CREDITS
        AlertDialog.Builder(this)
            .setTitle("关于 · 象棋迪克")
            .setMessage(text)
            .setPositiveButton("我知道了", null)
            .show()
    }
}
