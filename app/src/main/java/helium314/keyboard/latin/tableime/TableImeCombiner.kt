// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.tableime

import helium314.keyboard.event.Combiner
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.Constants
import java.util.ArrayList

class TableImeCombiner(val spec: TableImeSpec) : Combiner {
    private val composingCode = StringBuilder()

    val rawCode: String
        get() = composingCode.toString()

    val isComposing: Boolean
        get() = composingCode.isNotEmpty()

    override val combiningStateFeedback: CharSequence
        get() = spec.getRadicalString(composingCode.toString())

    override fun reset() {
        composingCode.setLength(0)
    }

    override fun processEvent(previousEvents: ArrayList<Event>?, event: Event): Event {
        if (event.keyCode == KeyCode.SHIFT) return event

        if (event.keyCode == KeyCode.DELETE) {
            if (composingCode.isNotEmpty()) {
                composingCode.deleteCharAt(composingCode.length - 1)
                return Event.createConsumedEvent(event)
            }
            return event
        }

        val codePoint = event.codePoint
        val isAsciiLetter = (codePoint in 'a'.code..'z'.code) || (codePoint in 'A'.code..'Z'.code)

        if (isAsciiLetter) {
            if (composingCode.length < spec.maxCodeLength) {
                composingCode.append(codePoint.toChar().lowercaseChar())
            }
            return Event.createConsumedEvent(event)
        }

        return event
    }
}
