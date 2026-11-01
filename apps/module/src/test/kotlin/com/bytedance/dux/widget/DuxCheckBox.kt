package com.bytedance.dux.widget

// Test stand-in for the host check box the e-commerce picker uses for "original image".
class DuxCheckBox {
    @JvmField var state = false
    fun setChecked(value: Boolean) { state = value }
}
