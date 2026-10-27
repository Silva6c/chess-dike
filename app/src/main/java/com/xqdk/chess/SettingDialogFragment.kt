package com.xqdk.chess

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.xqdk.chess.controllers.GameController
import com.xqdk.chess.openbook.BHDatabase

class SettingDialogFragment : DialogFragment() {
    interface SettingDialogListener {
        fun onDialogPositiveClick()
        fun onDialogNegativeClick()
    }

    // 可空而非 lateinit：DialogFragment 在进程死亡/uiMode 变更后被系统按无参构造重建，
    // 不会重走 setController 注入路径，lateinit 直接抛 UninitializedPropertyAccessException
    private var settings: Settings? = null
    private var controller: GameController? = null
    private var engineInfo: String = ""
    var listener: SettingDialogListener? = null

    fun setController(controller: GameController) {
        this.controller = controller
        this.settings = controller.settings
    }

    fun setEngineInfo(info: String) {
        engineInfo = info
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val settings = this.settings
        val controller = this.controller
        if (settings == null || controller == null) {
            // 重建场景兜底：给出可关闭提示，不再崩溃
            return AlertDialog.Builder(requireActivity())
                .setTitle("游戏设置")
                .setMessage("设置加载异常，请关闭后重新打开设置")
                .setPositiveButton("确定", null)
                .create()
        }

        val builder = AlertDialog.Builder(requireActivity())
        val inflater = requireActivity().layoutInflater
        val view: View = inflater.inflate(R.layout.setting_dialog, null)

        // bind all items in setting_dialog.xml
        val engineInfoTV = view.findViewById<TextView>(R.id.textView_engine_info)
        // limit the length of engineInfo
        if (engineInfo.length > 22) {
            engineInfoTV.text = "引擎: " + engineInfo.substring(0, 22) + ".."
        } else {
            engineInfoTV.text = "引擎: " + engineInfo
        }

        val booleanOpenbook = view.findViewById<CheckBox>(R.id.boolean_openbook)
        val textviewOpenbook = view.findViewById<TextView>(R.id.textViewOpenBook)
        textviewOpenbook.text = "(" + BHDatabase.OPENBOOK_NAME + ")"

        val booleanSound = view.findViewById<CheckBox>(R.id.boolean_sound)

        val historyInput = view.findViewById<SeekBar>(R.id.seekbar_history)
        val historyText = view.findViewById<TextView>(R.id.textview_history)

        val radioDepth = view.findViewById<RadioButton>(R.id.radioButton_depth)
        val depthInput = view.findViewById<SeekBar>(R.id.seekBar_depth)
        val depthText = view.findViewById<TextView>(R.id.textView_depth)

        val radioTime = view.findViewById<RadioButton>(R.id.radioButton_time)
        val timeInput = view.findViewById<SeekBar>(R.id.seekBar_time)
        val timeText = view.findViewById<TextView>(R.id.textView_time)

        val radioInfinite = view.findViewById<RadioButton>(R.id.radioButton_infinite)

        val redgoFirst = view.findViewById<CheckBox>(R.id.boolean_redgofirst)

        val randomMove = view.findViewById<CheckBox>(R.id.boolean_randommove)

        val seekbarHash = view.findViewById<SeekBar>(R.id.seekbar_hash)
        val textviewHash = view.findViewById<TextView>(R.id.textview_hash)

        // set the initial values
        booleanOpenbook.isChecked = settings.openbook
        booleanSound.isChecked = settings.sound_effect
        historyInput.progress = settings.history_moves
        historyText.text = settings.history_moves.toString()

        depthInput.progress = settings.go_depth_value
        depthText.text = settings.go_depth_value.toString()
        timeInput.progress = settings.go_time_value
        timeText.text = settings.go_time_value.toString()
        if (settings.go_depth) {
            radioDepth.isChecked = true
        } else if (settings.go_time) {
            radioTime.isChecked = true
        } else if (settings.go_infinite) {
            radioInfinite.isChecked = true
        }
        redgoFirst.isChecked = settings.red_go_first
        seekbarHash.progress = settings.hash_size
        textviewHash.text = settings.hash_size.toString()
        randomMove.isChecked = settings.random_move

        historyInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                historyText.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })
        depthInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                depthText.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })
        timeInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                timeText.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })
        seekbarHash.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                textviewHash.text = "$progress"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // Do something
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Do something
            }
        })


        builder.setView(view)
            .setTitle("游戏设置")
            .setPositiveButton("确定") { dialog, id ->
                settings.openbook = booleanOpenbook.isChecked
                settings.sound_effect = booleanSound.isChecked
                settings.history_moves = historyInput.progress
                settings.go_depth = radioDepth.isChecked
                settings.go_depth_value = depthInput.progress
                settings.go_time = radioTime.isChecked
                settings.go_time_value = timeInput.progress
                settings.go_infinite = radioInfinite.isChecked
                settings.red_go_first = redgoFirst.isChecked
                settings.hash_size = seekbarHash.progress
                settings.random_move = randomMove.isChecked
                settings.saveSettings()
                controller.applyEngineSetting()
                listener?.onDialogPositiveClick()
            }
            .setNegativeButton("取消") { dialog, id ->
                listener?.onDialogNegativeClick()
                dialog.cancel()
            }

        return builder.create()
    }
}
