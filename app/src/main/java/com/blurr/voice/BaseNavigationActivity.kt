package com.blurr.voice

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

abstract class BaseNavigationActivity : AppCompatActivity() {

    protected abstract fun getContentLayoutId(): Int
    protected abstract fun getCurrentNavItem(): NavItem

    enum class NavItem {
        HOME, SETTINGS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
    
    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
    }
}