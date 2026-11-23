package it.blackout.mesh

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Check-in clock alerts on a phone (spec/FORMAT.md, "If I go quiet", "Alerts, and what can't be guaranteed"): the engine
 * decides ([MeshEngine.quietPass]); this posts and arms. While the service runs, the engine's own timer posts an alert at
 * its deadline. As a backup there is one inexact alarm ([AlarmManager.setAndAllowWhileIdle]) for the nearest of what is
 * to come, carrying the words decided when it was set (no name and no text with "Lock DarkProof" or high-risk mode on),
 * to [QuietAlarmReceiver], which posts what is due unless its record was cancelled meanwhile. Doze can hold it back by
 * minutes, and no exact-alarm permission is asked for: the chat card says "It can come a few minutes late." A reboot or a
 * force stop loses the alarm; it is set again when the app or its service next starts. Its names in the alarm and the
 * notification lists are [QuietAlerts]' hashes, never a group's or a member's id.
 */
class QuietAlarms(c: Context) : QuietOut {
    private val c: Context = c.applicationContext
    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val CHANNEL = "clocks"
        /** The tag of clock notifications, apart from message notifications ([Notifier]), which go by plain numbers. */
        const val TAG = "quiet"
        private const val REQUEST = 7_701
        private const val EXTRA = "quiet"
        /** Nearer than this, the alarm is set for the deadline itself: Android's window for it is then about 3 minutes. */
        const val STEP_MS = 4 * 60_000L

        /** The channel, and the engine's way out for its alerts. Called with [Notifier.setUp] (screen and service) and by the alarm. */
        fun setUp(c: Context) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Check-in clocks", NotificationManager.IMPORTANCE_HIGH))
            if (MeshEngine.quietOut == null) MeshEngine.quietOut = QuietAlarms(c)
        }

        /** What waits in the alarm, as strings: the alarm's intent can carry nothing else across a restart of the app. */
        fun pack(list: List<QuietAlert>): Array<String> = list.flatMap { a ->
            listOf(a.id, a.mark, a.at.toString(), a.title, a.body, a.bannerTitle, a.banner, a.group, if (a.remind) "1" else "0", if (a.safe) "1" else "0")
        }.toTypedArray()

        fun unpack(s: Array<String>?): List<QuietAlert> = s.orEmpty().toList().chunked(10).mapNotNull { f ->
            if (f.size != 10) null else f[2].toLongOrNull()?.let { at -> QuietAlert(f[0], f[1], at, f[3], f[4], f[5], f[6], f[7], f[8] == "1", f[9] == "1") }
        }

        fun intent(c: Context, list: List<QuietAlert>): Intent = Intent(c, QuietAlarmReceiver::class.java).putExtra(EXTRA, pack(list))
        fun read(i: Intent): List<QuietAlert> = unpack(i.getStringArrayExtra(EXTRA))
    }

    /** An alert now: as the in-app banner while DarkProof is on screen and unlocked, else as a notification. Any thread. */
    override fun post(a: QuietAlert) {
        if (Notifier.onScreen) main.post {
            val show = Notifier.inApp
            if (Notifier.onScreen && show != null) show(Notifier.Alert(a.bannerTitle, a.banner, a.group, urgent = false, ask = false, messageId = "quiet-${a.id}"))
            else notify(a)
        } else notify(a)
    }

    private fun notify(a: QuietAlert) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = a.id.hashCode()
        val open = PendingIntent.getActivity(c, id, Actions.intent(c, if (a.remind) Actions.QUIET else Actions.PEOPLE, a.group),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_schedule).setContentTitle(a.title).setContentText(a.body)
            .setStyle(Notification.BigTextStyle().bigText(a.body)).setContentIntent(open).setAutoCancel(true)
            .setCategory(if (a.remind) Notification.CATEGORY_REMINDER else Notification.CATEGORY_STATUS)
            .setTimeoutAfter(if (a.remind) QuietClock.REMIND_MS + 5 * 60_000L else QuietClock.LATE_MS) // as long as People shows it as overdue
        // "I'm safe" under this phone's own reminder (rule Q8): never with the lock on, and it asks for the phone's unlock
        // first, so that whoever holds a locked phone can't stop its clock. Before Android 12 a notification can't ask for
        // that itself: the button opens "If I go quiet", which a locked phone opens only after its unlock.
        if (a.remind && a.safe) {
            val pi = if (Build.VERSION.SDK_INT >= 31) PendingIntent.getBroadcast(c, id, Intent(c, AnswerReceiver::class.java)
                .putExtra(Actions.EXTRA_GROUP, a.group).putExtra(AnswerReceiver.NOTIFICATION, id).putExtra(AnswerReceiver.TAG, TAG),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getActivity(c, id + 1, Actions.intent(c, Actions.QUIET, a.group), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            n.addAction(Notification.Action.Builder(null, "I'm safe", pi).apply { if (Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build())
        }
        c.getSystemService(NotificationManager::class.java).notify(TAG, id, n.build())
    }

    override fun take(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val nm = c.getSystemService(NotificationManager::class.java)
        ids.forEach { nm.cancel(TAG, it.hashCode()) }
    }

    /**
     * One inexact alarm for the nearest of [coming], carrying all of them; none if nothing is to come. Android may ring an
     * inexact alarm as late as three quarters of the wait after its time (30 minutes ahead: up to 22 minutes late), so while
     * the deadline is more than [STEP_MS] away the alarm rings halfway there, never after the deadline, and is set again
     * from then: a few wake-ups, and an alert at most about 3 minutes late (Doze, which lets an app's alarm ring only once
     * every 9 minutes or so, can add to that). While the app sleeps, the running service's own timer doesn't run either.
     */
    override fun arm(coming: List<QuietAlert>) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        if (coming.isEmpty()) {
            PendingIntent.getBroadcast(c, REQUEST, Intent(c, QuietAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            return
        }
        val pi = PendingIntent.getBroadcast(c, REQUEST, intent(c, coming), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val now = System.currentTimeMillis()
        val first = coming.minOf { it.at } - MeshEngine.quietAhead // by the phone's own clock (debug builds can move the clock the rules use)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ringAt(first, now), pi)
    }

    /** When the alarm for [target] rings, set at [now]: halfway there while it is more than [STEP_MS] away. */
    private fun ringAt(target: Long, now: Long) = if (target - now > STEP_MS) now + (target - now) / 2 else target
}

/** The backup alarm of check-in clocks ([QuietAlarms]): what is due is posted, unless its record was cancelled; the rest is armed again. */
class QuietAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val due = QuietAlarms.read(i)
        val pending = goAsync()
        Thread {
            try {
                QuietAlarms.setUp(c)
                val out = MeshEngine.quietOut ?: QuietAlarms(c)
                val left = MeshEngine.quietAlarmRang(c, due, out)
                if (MeshEngine.router == null) out.arm(left) // with the engine running, its pass armed it already
            } catch (e: Exception) { DebugTools.log("quiet: alarm failed: $e") }
            finally { pending.finish() }
        }.start()
    }
}
