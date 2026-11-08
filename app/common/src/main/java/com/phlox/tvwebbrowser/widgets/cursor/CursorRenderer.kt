package com.phlox.tvwebbrowser.widgets.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.view.WindowManager
import com.phlox.tvwebbrowser.utils.Utils

/**
 * Draws the virtual cursor (normal, pressed and grab looks).
 * Purely visual: knows nothing about input or the emulated pointer.
 */
class CursorRenderer(context: Context) {
    private val paint = Paint().apply { isAntiAlias = true }
    private val cursorRadius: Int
    private val cursorRadiusPressed: Int
    private val strokeWidth: Float

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displaySize = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getSize(displaySize)
        strokeWidth = (displaySize.x / 400).toFloat()
        cursorRadius = displaySize.x / 100
        cursorRadiusPressed = cursorRadius - Utils.D2P(context, 5f).toInt()
    }

    /** The look of the grab mode of the cursor */
    enum class Grab {
        NONE,
        /** A finger holds the page: a cross */
        DRAG,
        /** The page scrolls under the standing cursor: four arrowheads */
        SCROLL,
    }

    /** [radiusMultiplier] grows the cursor while it appears (not when pressed) */
    fun draw(canvas: Canvas, position: PointF, pressed: Boolean, grab: Grab, radiusMultiplier: Float) {
        val cx = position.x
        val cy = position.y
        val radius = if (pressed) cursorRadiusPressed.toFloat() else cursorRadius * radiusMultiplier

        paint.color = if (grab != Grab.NONE) Color.argb(128, 200, 200, 255) else Color.argb(128, 255, 255, 255)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, radius, paint)

        paint.color = Color.GRAY
        paint.strokeWidth = strokeWidth
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, radius, paint)

        when (grab) {
            Grab.NONE -> Unit
            Grab.DRAG -> {
                val halfRadius = radius / 2
                canvas.drawLine(cx - halfRadius, cy, cx + halfRadius, cy, paint)
                canvas.drawLine(cx, cy - halfRadius, cx, cy + halfRadius, paint)
            }
            Grab.SCROLL -> {
                // an arrowhead toward each side, its tip at 0.6 of the radius
                val tip = radius * 0.6f
                val base = radius * 0.3f
                val wing = radius * 0.2f
                for (side in 0 until 4) {
                    val dx = SIDES_X[side]
                    val dy = SIDES_Y[side]
                    val tipX = cx + dx * tip
                    val tipY = cy + dy * tip
                    val baseX = cx + dx * base
                    val baseY = cy + dy * base
                    // the wings go across the direction of the side
                    canvas.drawLine(baseX - dy * wing, baseY - dx * wing, tipX, tipY, paint)
                    canvas.drawLine(baseX + dy * wing, baseY + dx * wing, tipX, tipY, paint)
                }
            }
        }
    }

    private companion object {
        /** Up, right, down, left */
        val SIDES_X = floatArrayOf(0f, 1f, 0f, -1f)
        val SIDES_Y = floatArrayOf(-1f, 0f, 1f, 0f)
    }
}
