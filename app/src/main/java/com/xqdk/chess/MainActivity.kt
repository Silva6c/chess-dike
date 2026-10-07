package com.xqdk.chess

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xqdk.chess.assist.ui.AssistActivity
import com.xqdk.chess.assist.ui.QiGridDrawable
import com.xqdk.chess.views.WebviewActivity

class MainActivity : AppCompatActivity() {

    companion object {

        /**
         * 免责声明（四条条款，对齐 Cabinet 口径；首启弹窗 / About / 帮助页 / README
         * 共用同一文案，任何修改需四处同步）。
         */
        const val DISCLAIMER =
            "本软件（象棋迪克）为象棋学习辅助工具，仅供个人学习、复盘研究与技术交流使用。请在使用前仔细阅读以下条款：\n\n" +
            "一、本软件仅通过系统录屏授权识别画面并以悬浮窗显示走法建议，不包含自动走子、触摸注入、无障碍服务、Root/Xposed 等功能，所有着法均由玩家手动操作。\n\n" +
            "二、严禁将本软件用于任何正式比赛、等级分对局、网络对局平台或赌博等违反平台规则与法律法规的场景；由此产生的一切后果由使用者本人承担。\n\n" +
            "三、本软件仅供学习研究，请于获取后 24 小时内自行删除。\n\n" +
            "四、本软件按\"现状\"提供，不对软件的可用性与准确性作任何保证，不对因使用本软件造成的任何直接或间接损失承担责任。\n\n" +
            "点击\"同意并继续\"即表示您已阅读、理解并同意以上全部条款。"

        /** 鸣谢（品牌说明中唯一保留"象棋鱼"字眼处） */
        const val CREDITS =
            "鸣谢：\n" +
            "· 本项目基于开源项目\"象棋鱼\"（作者 zfdang，GitHub: zfdang/chinese-chess-android）二次开发；\n" +
            "· YOLO 棋子检测权重文件来自 Vincentzyx/VinXiangQi 项目；\n" +
            "· ONNX 两步式识别引擎（RTMPose 角点 + Swin 分类）的模型与算法来自 " +
            "Shirakawa-Kotone/chinese-chess-helper 项目；\n" +
            "· 引擎与对弈框架基座：Pikafish 团队与 DroidFish（Peter Österlund），GPL-3.0 开源授权。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 主界面背景：墨底 + 26dp 细网格（自绘 Drawable 零图片资产，对齐 Cabinet；
        // XML 兜底 qi_ink 纯色，代码再铺网格线）
        findViewById<View>(R.id.main).background = QiGridDrawable(this)
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

    /**
     * 免责声明弹窗：每次启动必弹（无可跳过、无持久化跳过），明确"仅供学习研究、
     * 禁止用于对局平台"等使用边界；同意后进入主页，不同意直接退出应用。
     */
    private fun maybeShowDisclaimer() {
        AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage(DISCLAIMER)
            .setCancelable(false)
            .setPositiveButton("同意并继续") { d, _ -> d.dismiss() }
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
