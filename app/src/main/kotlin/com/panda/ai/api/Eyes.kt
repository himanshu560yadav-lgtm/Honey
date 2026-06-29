package com.panda.ai.api

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import com.panda.ai.RawScreenData
import com.panda.ai.ScreenInteractionService
import java.io.File

class Eyes(context: Context) {

    private val publicPicturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    private var latestScreenshotFile: File? = null
    private val xmlFile: File = File(context.filesDir, "window_dump.xml")

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun openEyes(): Bitmap? {
        val service = ScreenInteractionService.instance
        if (service == null) {
            Log.e("Eyes", "Accessibility Service is not running!")
            return null
        }
        return service.captureScreenshot()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun openPureXMLEyes(): String {
        val service = ScreenInteractionService.instance
        if (service == null) {
            Log.e("AccessibilityController", "Accessibility Service is not running!")
            return "<hierarchy/>"
        }
        return service.dumpWindowHierarchy(true)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun openXMLEyes(): String {
        val service = ScreenInteractionService.instance
        if (service == null) {
            Log.e("AccessibilityController", "Accessibility Service is not running!")
            return "<hierarchy/>"
        }
        return service.dumpWindowHierarchy()
    }

    fun getKeyBoardStatus(): Boolean {
        val service = ScreenInteractionService.instance
        if (service == null) {
            Log.e("AccessibilityController", "Accessibility Service is not running!")
            return false
        }
        return service.isTypingAvailable()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun getRawScreenData(): RawScreenData? {
        val service = ScreenInteractionService.instance
        if (service == null) {
            Log.e("AccessibilityController", "Accessibility Service is not running!")
            return RawScreenData(null, 0, 0, 0, 0)
        }
        return service.getScreenAnalysisData()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun getAllRawScreenData(): RawScreenData? {
        val service = ScreenInteractionService.instance
        if (service == null) {
            Log.e("AccessibilityController", "Accessibility Service is not running!")
            return RawScreenData(null, 0, 0, 0, 0)
        }
        return service.getAllScreenAnalysisData()
    }

    fun getCurrentActivityName(): String {
        val service = ScreenInteractionService.instance
        if (service == null) {
            Log.e("AccessibilityController", "Accessibility Service is not running!")
            return "Unknown"
        }
        return service.getCurrentActivityName()
    }
}