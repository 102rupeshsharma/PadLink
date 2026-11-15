package com.ps2gamepad

import com.ps2gamepad.bluetooth.HidReportManager
import com.ps2gamepad.controller.ControllerInputManager
import com.ps2gamepad.controller.ReportSink
import com.ps2gamepad.controller.StickVector
import com.ps2gamepad.model.Stick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin

/**
 * A faithful port of how Azahar records a box (src/input_common/sdl/sdl_impl.cpp, SDLButtonPoller::GetNextInput,
 * the joystick-axis and joystick-button parts). A new one is made for every box clicked, like Azahar does.
 * Returns the text Azahar shows ("Axis 0+", "Button 12") for the first input it accepts.
 */
class AzaharBoxBinder {
    private val center = HashMap<Int, Int>()
    private val memory = HashMap<Int, Int>()
    private val count = HashMap<Int, Int>()

    private fun c(a: Int) = center[a] ?: 0
    private fun atCenter(v: Int, a: Int) = center.containsKey(a) && abs(v - c(a)) < 367
    private fun pastThreshold(v: Int, a: Int) = abs(v - c(a)) > 32767 / 2
    private fun extreme(v: Int) = abs(v) > 32300
    private fun pole(v: Int) = extreme(v) || abs(v) < 367
    private fun text(a: Int, v: Int) = "Axis $a" + if (v > 0) "+" else "-"

    fun axisEvent(a: Int, value: Int): String? {
        if (!memory.containsKey(a)) {
            count[a] = 1
            if (center.containsKey(a) && extreme(value)) return text(a, value)
            center[a] = when { value < -28000 -> -32768; value > 28000 -> 32767; else -> 0 }
            memory[a] = c(a)
            return null
        }
        count[a] = count.getValue(a) + 1
        if (count[a] == 2 && pole(value) && pole(memory.getValue(a))) {
            count[a] = 0
            if (!atCenter(value, a)) center[a] = when { value > 28000 -> 32767; value < -28000 -> -32768; else -> 0 }
            return text(a, value) // the "release" of a digital axis: sign of the raw value
        }
        if (atCenter(value, a) && pastThreshold(memory.getValue(a), a)) {
            val v = (32767 * sign((memory.getValue(a) - c(a)).toFloat())).toInt()
            memory[a] = c(a); count[a] = 0
            return text(a, v)
        } else if (atCenter(memory.getValue(a), a) && pastThreshold(value, a)) {
            val v = (32767 * sign((value - c(a)).toFloat())).toInt()
            memory[a] = v
            return text(a, v)
        }
        return null
    }

    fun buttonDown(sdlIndex: Int) = "Button $sdlIndex"

    companion object {
        /** HID value -127..127 → what SDL hands Azahar (DirectInput scales the logical range to 0..65535). */
        fun sdl(raw: Int): Int = ((raw + 127) * 65535 / 254) - 32768

        /** Feeds the reports the PC received (in order) to a fresh box; SDL only reports CHANGES. */
        fun record(reports: List<ByteArray>): String? {
            val b = AzaharBoxBinder()
            var prev = HidReportManager.decode(HidReportManager.NEUTRAL_REPORT + byteArrayOf(0))
            for (r in reports) {
                val d = HidReportManager.decode(r)
                val now = listOf(d.x, d.y, d.z, d.rz)
                val before = listOf(prev.x, prev.y, prev.z, prev.rz)
                for (a in 0..3) if (now[a] != before[a]) b.axisEvent(a, sdl(now[a]))?.let { return it }
                for (i in 0 until 8) {
                    val bit = 1 shl i
                    if (d.diagonals and bit != 0 && prev.diagonals and bit == 0) return b.buttonDown(12 + i)
                }
                prev = d
            }
            return null
        }
    }
}

/** Tasks scheduled by the input manager, run when the test advances the clock. */
private class ManualClock {
    var now = 0L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()
    val delayed: (Long, () -> Unit) -> Unit = { ms, t -> tasks += (now + ms) to t }
    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            now = next.first
            next.second()
        }
        now = end
    }
}

private fun dir(deg: Float, s: Float = 1f): StickVector {
    val r = Math.toRadians(deg.toDouble())
    return StickVector((cos(r) * s).toFloat(), (sin(r) * s).toFloat())
}

/** 3.10.1, reported: in Azahar "Right" recorded "Axis 0-" and "Down" "Axis 1-" (all positive directions negative). */
class MappingRampTest {
    private val clock = ManualClock()
    private val sent = mutableListOf<ByteArray>()
    private val m = ControllerInputManager(ReportSink { r, _ -> sent += r.copyOf(); true }, clock = { clock.now }, delayed = clock.delayed)
        .apply { extendedReports = true; setMappingMode(true) }

    /** A real binding: thumb down, drag to [deg], hold [holdMs], let go. Returns what the PC received. */
    private fun gesture(stick: Stick, deg: Float, holdMs: Long = 300): List<ByteArray> {
        val from = sent.size
        m.setStick(stick, dir(deg, 0.3f)) // still a light touch
        m.setStick(stick, dir(deg, 0.9f))
        clock.advance(holdMs)
        m.setStick(stick, StickVector.ZERO)
        clock.advance(300)
        return sent.subList(from, sent.size).toList()
    }

    private val expected = listOf(
        Triple(Stick.LEFT, 180f, "Axis 0-"), Triple(Stick.LEFT, 0f, "Axis 0+"),
        Triple(Stick.LEFT, 270f, "Axis 1-"), Triple(Stick.LEFT, 90f, "Axis 1+"),
        Triple(Stick.LEFT, 225f, "Button 12"), Triple(Stick.LEFT, 315f, "Button 13"),
        Triple(Stick.LEFT, 135f, "Button 14"), Triple(Stick.LEFT, 45f, "Button 15"),
        Triple(Stick.RIGHT, 180f, "Axis 2-"), Triple(Stick.RIGHT, 0f, "Axis 2+"),
        Triple(Stick.RIGHT, 270f, "Axis 3-"), Triple(Stick.RIGHT, 90f, "Axis 3+"),
        Triple(Stick.RIGHT, 225f, "Button 16"), Triple(Stick.RIGHT, 315f, "Button 17"),
        Triple(Stick.RIGHT, 135f, "Button 18"), Triple(Stick.RIGHT, 45f, "Button 19"),
    )

    @Test fun theSimulatedAzaharReproducesTheReportedBug() {
        // what 3.10.0 sent: 0 -> full in one report, then 0 again
        fun jump(axis: Int, raw: Int): String? {
            val r1 = ByteArray(7); r1[1] = 0x80.toByte(); r1[2 + axis] = raw.toByte()
            val r0 = ByteArray(7); r0[1] = 0x80.toByte()
            return AzaharBoxBinder.record(listOf(r1, r0))
        }
        assertEquals("Right came out as Axis 0- (user's screenshot)", "Axis 0-", jump(0, 127))
        assertEquals("Down came out as Axis 1- (user's screenshot)", "Axis 1-", jump(1, 127))
        assertEquals("Left looked fine by luck", "Axis 0-", jump(0, -127))
        assertEquals("C-Stick Right: Axis 2-", "Axis 2-", jump(2, 127))
    }

    @Test fun every16DirectionIsRecordedCorrectlyByAzahar() {
        val recorded = mutableListOf<String?>()
        for ((stick, deg, want) in expected) {
            val got = AzaharBoxBinder.record(gesture(stick, deg))
            assertEquals("${stick.name} $deg°", want, got)
            recorded += got
        }
        assertEquals("16 different codes", 16, recorded.toSet().size)
    }

    @Test fun quickFlicksAreRecordedCorrectlyToo() {
        for ((stick, deg, want) in expected) {
            for (hold in listOf(0L, 10L, 30L, 49L)) {
                assertEquals("${stick.name} $deg° held $hold ms", want, AzaharBoxBinder.record(gesture(stick, deg, holdMs = hold)))
            }
        }
    }

    @Test fun theRampSteps() {
        m.setStick(Stick.LEFT, dir(0f))
        assertEquals(25, HidReportManager.decode(sent.last()).x) // 20 %
        clock.advance(50)
        assertEquals(102, HidReportManager.decode(sent.last()).x) // 80 %
        clock.advance(50)
        assertEquals(127, HidReportManager.decode(sent.last()).x) // full
        val n = sent.size
        clock.advance(1000)
        assertEquals("nothing more while held", n, sent.size)
    }

    @Test fun aFlickStillReachesThePressedStepThenReleases() {
        m.setStick(Stick.RIGHT, dir(90f))
        m.setStick(Stick.RIGHT, StickVector.ZERO) // thumb off at once
        assertEquals("held: the press is not finished", 25, HidReportManager.decode(sent.last()).rz)
        clock.advance(50)
        assertEquals("the pressed step goes out on its own", 102, HidReportManager.decode(sent.last()).rz)
        clock.advance(50)
        assertEquals("then the release, 50 ms later", 0, HidReportManager.decode(sent.last()).rz)
        clock.advance(200)
        assertEquals("no late ramp step after the release", 0, HidReportManager.decode(sent.last()).rz)
    }

    @Test fun slidingToAnotherDirectionMidRampCancelsTheOldRamp() {
        m.setStick(Stick.LEFT, dir(0f)) // RIGHT
        clock.advance(20)
        m.setStick(Stick.LEFT, dir(270f)) // UP before RIGHT reached 80 %
        clock.advance(30) // RIGHT's 80 % step, then UP starts
        clock.advance(300)
        val d = HidReportManager.decode(sent.last())
        assertEquals("up at full", -127, d.y)
        assertEquals("right fully gone", 0, d.x)
        assertTrue("no report ever has both axes", sent.none { HidReportManager.decode(it).let { r -> r.x != 0 && r.y != 0 } })
    }

    @Test fun mappingOffMidRampGivesTheAnalogStickAndIgnoresPendingSteps() {
        m.setStick(Stick.LEFT, dir(0f, 0.6f))
        m.setMappingMode(false)
        val analog = HidReportManager.decode(sent.last()).x
        assertEquals(76, analog) // 0.6 * 127
        clock.advance(500)
        assertEquals(analog, HidReportManager.decode(sent.last()).x)
    }

    @Test fun releaseAllMidRampCancelsEverything() {
        m.setStick(Stick.LEFT, dir(90f))
        m.releaseAll()
        clock.advance(500)
        assertEquals(0, HidReportManager.decode(sent.last()).y)
    }

    @Test fun diagonalsAreOneButtonWithNoRamp() {
        m.setStick(Stick.LEFT, dir(225f))
        val d = HidReportManager.decode(sent.last())
        assertEquals(1, d.diagonals); assertEquals(0, d.x); assertEquals(0, d.y)
        val n = sent.size
        clock.advance(500)
        assertEquals("no ramp reports for a diagonal", n, sent.size)
    }

    @Test fun bothSticksRampIndependently() {
        m.setStick(Stick.LEFT, dir(0f))
        clock.advance(25)
        m.setStick(Stick.RIGHT, dir(180f))
        clock.advance(300)
        val d = HidReportManager.decode(sent.last())
        assertEquals(127, d.x); assertEquals(-127, d.z)
    }

    @Test fun rampValuesSitInTheRightZonesForAzahar() {
        val first = AzaharBoxBinder.sdl(25)
        val press = AzaharBoxBinder.sdl(102)
        assertTrue("first step reads as near the middle: $first", abs(first) in 368..27999)
        assertTrue("pressed step is past half: $press", press > 32767 / 2)
        assertTrue("but not at the very end (would look digital): $press", press <= 32300)
    }
}
