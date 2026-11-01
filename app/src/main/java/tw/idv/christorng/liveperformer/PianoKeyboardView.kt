package tw.idv.christorng.liveperformer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.util.SparseIntArray
import kotlin.math.floor

/** Direct touch -> JNI path, without Compose recomposition per MIDI note. */
class PianoKeyboardView(context: Context) : View(context) {
    var baseNote = 48
        set(value) {
            val next = value.coerceIn(0, 96) / 12 * 12
            if (field == next) return
            allNotesOff()
            field = next
            invalidate()
        }
    var velocity = 100
    var sendMidi: (Int, Int, Int, Int) -> Unit = NativeEngine::midi
    private fun noteOn(note: Int, velocity: Int) = sendMidi(NativeEngine.NOTE_ON, 0, note, velocity)
    private fun noteOff(note: Int) = sendMidi(NativeEngine.NOTE_OFF, 0, note, 0)
    private val active = SparseIntArray()
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 250, 248) }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 34, 44) }
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(89, 177, 209) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(64, 67, 71); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 13f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER }
    private val whiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    // C#, D#, F#, G#, A# : white-key indexes 0,1,3,4,5.
    private val blackKeys = intArrayOf(1, 3, 6, 8, 10)
    private val whiteBeforeBlack = intArrayOf(0, 1, 3, 4, 5)
    private val whiteCount = 15 // C + two octaves + high C

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ideal = (220 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(ideal, heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        val step = width / whiteCount.toFloat()
        for (i in 0 until whiteCount) {
            val note = baseNote + 12 * (i / 7) + whiteOffsets[i % 7]
            val paint = if (isPressed(note)) pressed else white
            canvas.drawRect(i * step, 0f, (i + 1) * step, height.toFloat(), paint)
            canvas.drawRect(i * step, 0f, (i + 1) * step, height.toFloat(), stroke)
            if (i % 7 == 0) {
                canvas.drawText("C${note / 12 - 1}", i * step + step * .5f,
                    height - 10f * resources.displayMetrics.density, label)
            }
        }
        val bw = step * .62f
        for (oct in 0..1) for (i in blackKeys.indices) {
            val index = oct * 7 + whiteBeforeBlack[i]
            val x = (index + 1) * step
            val note = baseNote + oct * 12 + blackKeys[i]
            canvas.drawRoundRect(x - bw / 2, 0f, x + bw / 2, height * .63f, 6f, 6f,
                if (isPressed(note)) pressed else black)
        }
    }
    private fun isPressed(note: Int): Boolean = (0 until active.size()).any { active.valueAt(it) == note }
    private fun noteAt(x: Float, y: Float): Int {
        val step = width / whiteCount.toFloat()
        val index = floor((x / step).toDouble()).toInt().coerceIn(0, whiteCount - 1)
        if (y < height * .63f) {
            val bw = step * .62f
            for (oct in 0..1) for (i in blackKeys.indices) {
                val key = oct * 7 + whiteBeforeBlack[i]
                val center = (key + 1) * step
                if (x >= center - bw / 2 && x < center + bw / 2)
                    return baseNote + oct * 12 + blackKeys[i]
            }
        }
        return baseNote + 12 * (index / 7) + whiteOffsets[index % 7]
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                updatePointer(event.getPointerId(i), noteAt(event.getX(i), event.getY(i)))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount)
                    updatePointer(event.getPointerId(i), noteAt(event.getX(i), event.getY(i)))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> release(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> allNotesOff()
        }
        invalidate()
        return true
    }
    private fun updatePointer(id: Int, note: Int) {
        val previous = active.get(id, -1)
        if (previous == note) return
        if (previous >= 0) noteOff(previous)
        active.put(id, note)
        noteOn(note, velocity)
    }
    private fun release(id: Int) {
        val old = active.get(id, -1)
        if (old >= 0) noteOff(old)
        active.delete(id)
    }
    fun allNotesOff() {
        for (i in 0 until active.size()) noteOff(active.valueAt(i))
        active.clear()
        invalidate()
    }
    override fun onDetachedFromWindow() {
        allNotesOff()
        super.onDetachedFromWindow()
    }
}
