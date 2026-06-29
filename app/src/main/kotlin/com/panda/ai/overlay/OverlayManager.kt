package com.panda.ai.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class OverlayManager private constructor(context: Context) {

    private val applicationContext = context.applicationContext
    private val windowManager by lazy { applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clientCount = AtomicInteger(0)
    private var bottomOverlayView: View? = null
    private var topOverlayView: View? = null
    private var observeJob: Job? = null

    companion object {
        @Volatile private var INSTANCE: OverlayManager? = null
        fun getInstance(context: Context): OverlayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OverlayManager(context).also { INSTANCE = it }
            }
        }
    }

    @Synchronized
    fun startObserving() {
        val currentCount = clientCount.incrementAndGet()
        Log.d("OverlayManager", "Client added. Total clients: $currentCount")
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            try {
                OverlayDispatcher.activeContent.collect { contentMap ->
                    try {
                        for (position in OverlayPosition.values()) {
                            val content = contentMap[position]
                            if (content != null) updateOverlayView(content)
                            else removeOverlayView(position)
                        }
                    } catch (e: Exception) {
                        Log.e("OverlayManager", "UI Update Error", e)
                    }
                }
            } catch (e: CancellationException) {
                Log.d("OverlayManager", "Observer cancelled normally.")
            } catch (e: Exception) {
                Log.e("OverlayManager", "Fatal Observer Error", e)
            }
        }
    }

    @Synchronized
    fun stopObserving() {
        val remainingClients = clientCount.decrementAndGet()
        Log.d("OverlayManager", "Client removed. Remaining clients: $remainingClients")
        if (remainingClients <= 0) {
            clientCount.set(0)
            observeJob?.cancel()
            observeJob = null
            removeOverlayInternal()
        }
    }

    private fun updateOverlayView(content: OverlayContent) {
        Log.d("OverlayManager", "Updating overlay: ${content.text} at ${content.position}")
        if (content.position == OverlayPosition.TOP) {
            if (topOverlayView == null) createView(OverlayPosition.TOP)
            (topOverlayView as? TextView)?.text = content.text
        } else {
            if (bottomOverlayView == null) createView(OverlayPosition.BOTTOM)
            (bottomOverlayView as? TextView)?.text = content.text
        }
        if (content.duration > 0) {
            mainHandler.postDelayed({ OverlayDispatcher.dismiss(content.id) }, content.duration)
        }
    }

    private fun removeOverlayView(position: OverlayPosition) {
        if (position == OverlayPosition.TOP) {
            removeView(topOverlayView)
            topOverlayView = null
        } else {
            removeView(bottomOverlayView)
            bottomOverlayView = null
        }
    }

    private fun createView(position: OverlayPosition) {
        val textView = TextView(applicationContext).apply {
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 24f
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(24, 16, 24, 16)
        }
        val gravity = if (position == OverlayPosition.TOP) Gravity.TOP or Gravity.CENTER_HORIZONTAL
                      else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val yPos = if (position == OverlayPosition.TOP) 150 else 250
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            y = yPos
        }
        try {
            windowManager.addView(textView, params)
            if (position == OverlayPosition.TOP) topOverlayView = textView
            else bottomOverlayView = textView
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlayInternal() {
        removeView(bottomOverlayView); bottomOverlayView = null
        removeView(topOverlayView); topOverlayView = null
    }

    private fun removeView(view: View?) {
        view?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
    }
}