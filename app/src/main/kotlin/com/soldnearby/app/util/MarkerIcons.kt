package com.soldnearby.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.res.ResourcesCompat
import com.soldnearby.app.R
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import kotlin.math.roundToInt

private const val STROKE_WIDTH_DP = 1.5f

/** Renders the dot drawable to a bitmap once per (color, size) pair, for reuse across every
 *  marker that shares it. [ic_price_dot.xml] is a plain oval [GradientDrawable] — mutating its
 *  fill color and stroke width at draw time means one drawable resource serves every dot
 *  variant instead of a near-duplicate XML file per color. */
fun createDotIcon(context: Context, colorArgb: Int, diameterDp: Float): Icon {
    val density = context.resources.displayMetrics.density
    val diameterPx = (diameterDp * density).roundToInt()
    val strokeWidthPx = (STROKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1)

    val drawable = ResourcesCompat.getDrawable(context.resources, R.drawable.ic_price_dot, context.theme)!!
        .mutate() as GradientDrawable
    drawable.setColor(colorArgb)
    drawable.setStroke(strokeWidthPx, Color.WHITE)

    val bitmap = Bitmap.createBitmap(diameterPx, diameterPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, diameterPx, diameterPx)
    drawable.draw(canvas)
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}
