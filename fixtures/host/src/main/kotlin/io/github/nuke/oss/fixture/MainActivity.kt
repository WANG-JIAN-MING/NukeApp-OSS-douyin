package io.github.nuke.oss.fixture

import android.app.Activity
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val status = TextView(this).apply { setTextIsSelectable(true); textSize = 16f }
        val input = EditText(this).apply { setText("Alice"); isSingleLine = true }
        val result = TextView(this).apply { setTextIsSelectable(true); textSize = 20f }
        fun refresh() { status.text = ProbeTarget.describe() }
        content.addView(TextView(this).apply { text = "NukeApp-OSS Hook Fixture"; textSize = 24f })
        content.addView(TextView(this).apply {
            text = "启用预期：hooked:original:NukeApp\n关闭预期：original:输入内容\n"
        })
        content.addView(status)
        content.addView(input)
        content.addView(Button(this).apply {
            text = "调用 greet"
            setOnClickListener { result.text = ProbeTarget.greet(input.text.toString()); refresh() }
        })
        content.addView(Button(this).apply {
            text = "开启改写功能"
            setOnClickListener { result.text = ProbeTarget.setEnabled(true); refresh() }
        })
        content.addView(Button(this).apply {
            text = "关闭改写功能"
            setOnClickListener { result.text = ProbeTarget.setEnabled(false); refresh() }
        })
        content.addView(result)
        setContentView(ScrollView(this).apply { addView(content) })
        refresh()
    }
}
