package com.skul9x.visualmp.util

import android.content.res.Resources
import android.util.DisplayMetrics

/**
 * Utility object for responsive UI calculations.
 * Provides dynamic sizing based on actual screen dimensions.
 */
object ResponsiveUtils {
    
    // Reference design dimensions (based on standard phone 360x640)
    private const val REFERENCE_WIDTH_DP = 360f
    private const val REFERENCE_HEIGHT_DP = 640f
    
    /**
     * Get screen width in dp
     */
    fun getScreenWidthDp(resources: Resources): Int {
        return resources.configuration.screenWidthDp
    }
    
    /**
     * Get screen height in dp
     */
    fun getScreenHeightDp(resources: Resources): Int {
        return resources.configuration.screenHeightDp
    }
    
    /**
     * Calculate scale factor based on screen width
     */
    fun getWidthScaleFactor(resources: Resources): Float {
        val screenWidth = getScreenWidthDp(resources)
        return (screenWidth / REFERENCE_WIDTH_DP).coerceIn(0.8f, 2.5f)
    }
    
    /**
     * Calculate scale factor based on screen height
     */
    fun getHeightScaleFactor(resources: Resources): Float {
        val screenHeight = getScreenHeightDp(resources)
        return (screenHeight / REFERENCE_HEIGHT_DP).coerceIn(0.8f, 2.5f)
    }
    
    /**
     * Get aspect ratio (width / height)
     */
    fun getAspectRatio(resources: Resources): Float {
        val width = getScreenWidthDp(resources).toFloat()
        val height = getScreenHeightDp(resources).toFloat()
        return width / height
    }
    
    /**
     * Check if screen is square-ish (split screen 1:1)
     */
    fun isSquarish(resources: Resources): Boolean {
        val ratio = getAspectRatio(resources)
        return ratio in 0.7f..1.4f
    }
    
    /**
     * Check if screen is wide (landscape or wide split)
     */
    fun isWideScreen(resources: Resources): Boolean {
        return getAspectRatio(resources) > 1.2f
    }
    
    /**
     * Check if screen is tall (portrait or narrow split)
     */
    fun isTallScreen(resources: Resources): Boolean {
        return getAspectRatio(resources) < 0.8f
    }
    
    /**
     * Get optimal grid column count based on screen width
     * Each column needs minimum 150dp, max 200dp for best readability
     */
    fun getOptimalGridColumns(resources: Resources, minItemWidthDp: Int = 150): Int {
        val screenWidth = getScreenWidthDp(resources)
        val columns = (screenWidth / minItemWidthDp).coerceIn(1, 6)
        
        // Limit columns for square screens to prevent items being too small
        return if (isSquarish(resources)) {
            columns.coerceAtMost(3)
        } else {
            columns.coerceAtMost(5)
        }
    }
    
    /**
     * Scale a dp value based on screen width
     */
    fun scaleDpByWidth(resources: Resources, baseDp: Float): Float {
        return baseDp * getWidthScaleFactor(resources)
    }
    
    /**
     * Scale a dp value based on screen height
     */
    fun scaleDpByHeight(resources: Resources, baseDp: Float): Float {
        return baseDp * getHeightScaleFactor(resources)
    }
    
    /**
     * Convert dp to pixels
     */
    fun dpToPx(resources: Resources, dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    /**
     * Get optimal MiniPlayer height based on screen
     */
    fun getMiniPlayerHeight(resources: Resources): Int {
        val heightDp = getScreenHeightDp(resources)
        val baseDp = when {
            heightDp < 300 -> 0f // Hide
            heightDp < 400 -> 48f
            isSquarish(resources) -> 56f
            else -> 72f
        }
        return dpToPx(resources, baseDp)
    }
    
    /**
     * Get optimal header text size in sp
     */
    fun getHeaderTextSize(resources: Resources): Float {
        val widthDp = getScreenWidthDp(resources)
        return when {
            widthDp < 320 -> 24f
            widthDp < 480 -> 28f
            widthDp < 720 -> 32f
            else -> 36f
        }
    }
    
    /**
     * Get optimal album art size for list items
     */
    fun getItemAlbumArtSize(resources: Resources): Int {
        val widthDp = getScreenWidthDp(resources)
        val columns = getOptimalGridColumns(resources)
        
        // If using grid, art should fill the card
        if (columns > 1) {
            return dpToPx(resources, (widthDp / columns - 24).toFloat().coerceIn(80f, 200f))
        }
        
        // For single column (list), use fixed size
        return dpToPx(resources, when {
            widthDp < 360 -> 48f
            widthDp < 600 -> 56f
            else -> 64f
        })
    }
    
    /**
     * Get screen size category
     */
    fun getScreenCategory(resources: Resources): ScreenCategory {
        val widthDp = getScreenWidthDp(resources)
        val heightDp = getScreenHeightDp(resources)
        val smallestDp = minOf(widthDp, heightDp)
        
        return when {
            smallestDp < 320 -> ScreenCategory.COMPACT
            smallestDp < 600 -> ScreenCategory.MEDIUM
            smallestDp < 840 -> ScreenCategory.EXPANDED
            else -> ScreenCategory.LARGE
        }
    }
    
    /**
     * Check if we should use landscape layout (side-by-side)
     * This applies when width is significantly larger than height.
     * We use a lower threshold (1.1) to catch "wide" split screens.
     */
    fun shouldUseLandscapeLayout(resources: Resources): Boolean {
        val width = getScreenWidthDp(resources)
        val height = getScreenHeightDp(resources)
        
        // If width is clearly larger than height, use side-by-side
        return width > height * 1.1f
    }

    /**
     * Get optimal album art size percentage for landscape mode
     */
    fun getAlbumArtSizePercent(resources: Resources): Float {
        val aspectRatio = getAspectRatio(resources)
        return when {
            aspectRatio > 2.0f -> 0.35f // Ultra-wide
            aspectRatio > 1.5f -> 0.4f  // Standard wide
            else -> 0.45f               // Near square/split
        }
    }

    enum class ScreenCategory {
        COMPACT,   // Small phones, very narrow splits
        MEDIUM,    // Regular phones, normal splits
        EXPANDED,  // Tablets, wide desktop
        LARGE      // Large tablets, TV
    }
}
