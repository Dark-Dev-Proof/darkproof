package it.blackout.mesh

import java.text.DateFormat
import java.util.Date

/** "just now", "5 min ago", "2 h ago", "3 d ago". */
fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
    val s = (now - ts) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/** The day a message was sent, for grouping the chat by day. */
fun dayOf(ts: Long): Int = java.util.Calendar.getInstance().apply { timeInMillis = ts }.let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }

/** Formats a day or time for the chat. */
fun fullTime(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts))
