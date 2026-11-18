package tw.idv.christorng.liveperformer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor

/** Key widths adapt to the requested pitch range; monitoring is non-interactive. */
class PianoKeyboardView(context: Context) : View(context) {
    var baseNote = 47
        private set
    var endNote = 71
        private set
    fun configureRange(start: Int, end: Int) {
        val from = start.coerceIn(0, 127)
        val to = end.coerceIn(from, 127)
        if (baseNote == from && endNote == to) return
        allNotesOff()
        baseNote = from
        endNote = to
        invalidate()
    }
    var velocity = 100
    var readOnly = false
    var showExternalNotes: Set<Int> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    var sendMidi: (Int, Int, Int, Int) -> Unit = NativeEngine::midi
    private fun noteOn(note: Int, strength: Int) = sendMidi(NativeEngine.NOTE_ON, 0, note, strength)
    private fun noteOff(note: Int) = sendMidi(NativeEngine.NOTE_OFF, 0, note, 0)
    private val active = SparseIntArray()
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 250, 248) }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 34, 44) }
    private val pressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(89, 177, 209) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(64, 67, 71); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val whitePitchClasses = setOf(0, 2, 4, 5, 7, 9, 11)
    private fun isWhite(note: Int) = note % 12 in whitePitchClasses
    private fun whiteNotes(): List<Int> = (baseNote..endNote).filter(::isWhite)
    private fun blackX(note: Int, whites: List<Int>, step: Float): Float {
        val previous = (note - 1 downTo 0).first(::isWhite)
        val previousIndex = whites.indexOf(previous)
        return if (previousIndex < 0) 0f else (previousIndex + 1) * step
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ideal = (220 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(ideal, heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        val whites = whiteNotes()
        if (whites.isEmpty()) return
        val step = width / whites.size.toFloat()
        whites.forEachIndexed { i, note ->
            canvas.drawRect(i * step, 0f, (i + 1) * step, height.toFloat(),
                if (isPressed(note)) pressed else white)
            canvas.drawRect(i * step, 0f, (i + 1) * step, height.toFloat(), stroke)
            if (note % 12 == 0 || i == 0) {
                val pitch = MidiPitch.name(note)
                canvas.drawText(pitch, (i + .5f) * step,
                    height - 10f * resources.displayMetrics.density, label)
            }
        }
        val bw = step * .62f
        (baseNote..endNote).filterNot(::isWhite).forEach { note ->
            val x = blackX(note, whites, step)
            canvas.drawRoundRect(x - bw / 2, 0f, x + bw / 2, height * .63f,
                6f, 6f, if (isPressed(note)) pressed else black)
        }
    }
    private fun isPressed(note: Int): Boolean =
        note in showExternalNotes || (0 until active.size()).any { active.valueAt(it) == note }
    private fun noteAt(x: Float, y: Float): Int {
        val whites = whiteNotes()
        if (whites.isEmpty()) return baseNote
        val step = width / whites.size.toFloat()
        val index = floor((x / step).toDouble()).toInt().coerceIn(0, whites.lastIndex)
        if (y < height * .63f) {
            val bw = step * .62f
            for (note in baseNote..endNote) if (!isWhite(note)) {
                val center = blackX(note, whites, step)
                if (x >= center - bw / 2 && x < center + bw / 2) return note
            }
        }
        return whites[index]
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (readOnly) return false
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
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP ->
                release(event.getPointerId(event.actionIndex))
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
