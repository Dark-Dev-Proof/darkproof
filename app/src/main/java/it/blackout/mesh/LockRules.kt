package it.blackout.mesh

// Rules of "Lock DarkProof" and of the one-tap answers, kept apart from the screens so unit tests run: plain Kotlin,
// no Android dependencies. The iPhone app follows the same ones (BlackoutCore, LockRules.swift).

/** An "I'm safe" from the app shortcut while DarkProof is locked: it waits for the unlock, but not for long. */
object WaitingSafe {
    /** Long enough to unlock right after tapping the shortcut, too short for someone else to leave it for later. */
    const val MAX_WAIT_MS = 120_000L

    /** True if a send that started waiting at [at] still goes out at [now], both SystemClock.elapsedRealtime(). */
    fun stillWanted(at: Long, now: Long): Boolean = now >= at && now - at <= MAX_WAIT_MS
}

/**
 * Rule Q8 for every way "I'm safe" goes out without the app on screen (final review 2026-11-23, item 1): it stops the
 * sender's check-in clock on every phone, so whoever holds a locked phone must not be able to send it. "I need help" is
 * not held back: asking for help from a locked phone is meant to work.
 */
object LockedSafe {
    /** What a tap on the "I'm safe" Quick Settings tile does. */
    enum class Tap {
        /** No group yet, or "Lock DarkProof" (or high-risk mode) is on: the app opens, behind its own unlock. */
        OPEN_APP,
        /** The phone is locked: Android asks for its unlock first ([android.service.quicksettings.TileService.unlockAndRun]), then it sends. */
        UNLOCK_FIRST,
        SEND,
    }

    /** [ready]: a group is saved; [appLock]: "Lock DarkProof" or high-risk mode; [locked]: the phone's lock screen shows. */
    fun tile(ready: Boolean, appLock: Boolean, locked: Boolean): Tap = when {
        !ready || appLock -> Tap.OPEN_APP
        locked -> Tap.UNLOCK_FIRST
        else -> Tap.SEND
    }

    /**
     * Whether "I'm safe" under an "Are you safe?" alert or under this phone's own clock reminder sends by itself. From
     * Android 12 ([sdk] 31) the button asks for the phone's unlock before it sends. Before that a notification button can't
     * ask for it: the button opens the app instead, which a locked phone shows only after its unlock.
     */
    fun buttonSends(sdk: Int): Boolean = sdk >= 31

    /**
     * The receiver of that button refuses: with "Lock DarkProof" on ([appLock]), and before Android 12 while the phone is
     * locked ([deviceLocked]), for a button posted before this rule (an alert from an older version stays on screen).
     */
    fun receiverRefuses(sdk: Int, appLock: Boolean, deviceLocked: Boolean): Boolean = appLock || (!buttonSends(sdk) && deviceLocked)
}

/** Where a one-tap answer under an "Are you safe?" alert goes. */
sealed interface AnswerTo {
    /** [code]: the saved group's code now; null: the group in use (the alert named no group). */
    data class Group(val code: String?) : AnswerTo
    /** The group that asked isn't saved on this phone any more: the answer goes nowhere, not to another group. */
    object Gone : AnswerTo
}

object AnswerGroup {
    /**
     * [asked]: the group code the alert was made with. [isSaved]: a code saved on this phone. [movedTo]: the code the
     * group of an old code has now, after a removal gave it a new one (spec "Removals and the code"), or null.
     */
    fun of(asked: String?, isSaved: (String) -> Boolean, movedTo: (String) -> String?): AnswerTo = when {
        asked.isNullOrEmpty() -> AnswerTo.Group(null)
        isSaved(asked) -> AnswerTo.Group(asked)
        else -> movedTo(asked)?.takeIf(isSaved)?.let { AnswerTo.Group(it) } ?: AnswerTo.Gone
    }
}
