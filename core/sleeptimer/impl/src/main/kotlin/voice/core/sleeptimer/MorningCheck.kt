package voice.core.sleeptimer

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import voice.core.data.BookId
import voice.core.data.ListeningEvent
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.playback.PlayerController
import voice.core.playback.history.ListeningHistoryRecorder
import voice.core.playback.history.PlaybackPosition
import voice.core.playback.notification.MainActivityIntentProvider
import java.time.Clock
import kotlin.time.Duration.Companion.hours
import voice.core.strings.R as StringsR

private const val CHANNEL_ID = "morningCheck"
private const val NOTIFICATION_ID = 4250

/**
 * After a night with a missed listening check, asks in the morning whether the book went back to where
 * the listener lost track. Their answers end up in the listening history, so the rules can get better.
 */
@SingleIn(AppScope::class)
@Inject
class MorningCheck(
  private val context: Context,
  private val sleepTimerPreferenceStore: SleepTimerPreferenceStore,
  private val lostTrack: LostTrack,
  private val bookRepository: BookRepository,
  private val currentBookStore: CurrentBookStore,
  private val playerController: PlayerController,
  private val historyRecorder: ListeningHistoryRecorder,
  private val mainActivityIntentProvider: MainActivityIntentProvider,
  private val clock: Clock,
) {

  private val notificationManager = NotificationManagerCompat.from(context)

  /**
   * Asks once the sleep timer hours are over. A later missed check moves the question to the same time.
   */
  suspend fun schedule(bookId: BookId) {
    val preference = sleepTimerPreferenceStore.data.first()
    if (!preference.morningCheckEnabled) return
    val now = clock.instant().atZone(clock.zone)
    var morning = now.toLocalDate().atTime(preference.autoSleepEndTime).atZone(clock.zone)
    if (!morning.isAfter(now)) morning = morning.plusDays(1)
    val alarmManager = context.getSystemService<AlarmManager>() ?: return
    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, morning.toInstant().toEpochMilli(), show(bookId))
  }

  suspend fun ask(bookId: BookId) {
    if (!sleepTimerPreferenceStore.data.first().morningCheckEnabled) return
    // the night can start right when the sleep timer hours do, a long time before they end
    val spot = lostTrack.spot(bookId, within = NIGHT) ?: return
    val book = bookRepository.get(bookId) ?: return
    val time = DateUtils.formatElapsedTime(spot.time / 1000)
    val chapterIndex = book.chapters.indexOfFirst { it.id == spot.chapterId }
    val place = if (book.chapters.size > 1 && chapterIndex >= 0) {
      context.getString(StringsR.string.history_location_chapter, chapterIndex + 1, time)
    } else {
      time
    }
    notify(
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_help)
        .setContentTitle(context.getString(StringsR.string.morning_check_title))
        .setContentText("${book.content.name} · $place")
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setContentIntent(mainActivityIntentProvider.toCurrentBook())
        .setAutoCancel(true)
        .addAction(0, context.getString(StringsR.string.morning_check_right), answer(spot, right = true))
        .addAction(0, context.getString(StringsR.string.morning_check_wrong), answer(spot, right = false))
        .build(),
    )
  }

  suspend fun onAnswer(
    spot: PlaybackPosition,
    right: Boolean,
  ) {
    historyRecorder.record(
      type = if (right) ListeningEvent.Type.MorningCheckRight else ListeningEvent.Type.MorningCheckWrong,
      source = ListeningEvent.Source.Notification,
      position = spot,
    )
    if (right) {
      notificationManager.cancel(NOTIFICATION_ID)
      val live = playerController.livePlaybackState()
      val there = live != null && live.bookId == spot.bookId && live.chapterId == spot.chapterId && live.positionMs == spot.time
      if (currentBookStore.data.first() == spot.bookId && !there) {
        playerController.setPosition(spot.time, spot.chapterId, ListeningEvent.Type.Seek, ListeningEvent.Source.Notification)
      }
    } else {
      // where they really lost track is the next place they go to
      notify(
        NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(android.R.drawable.ic_menu_help)
          .setContentTitle(context.getString(StringsR.string.morning_check_title))
          .setContentText(context.getString(StringsR.string.morning_check_wrong_answered))
          .setCategory(NotificationCompat.CATEGORY_REMINDER)
          .setContentIntent(mainActivityIntentProvider.toCurrentBook())
          .setAutoCancel(true)
          .setSilent(true)
          .build(),
      )
    }
  }

  @SuppressLint("MissingPermission")
  private fun notify(notification: Notification) {
    val allowed = Build.VERSION.SDK_INT < 33 ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!allowed) return
    notificationManager.createNotificationChannel(
      NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
        .setName(context.getString(StringsR.string.morning_check_channel))
        .build(),
    )
    notificationManager.notify(NOTIFICATION_ID, notification)
  }

  private fun show(bookId: BookId): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, MorningCheckReceiver::class.java)
      .setAction(MorningCheckReceiver.ACTION_ASK)
      .putExtra(MorningCheckReceiver.EXTRA_BOOK_ID, bookId.value),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  private fun answer(
    spot: PlaybackPosition,
    right: Boolean,
  ): PendingIntent = PendingIntent.getBroadcast(
    context,
    if (right) 1 else 2,
    Intent(context, MorningCheckReceiver::class.java)
      .setAction(if (right) MorningCheckReceiver.ACTION_RIGHT else MorningCheckReceiver.ACTION_WRONG)
      .putExtra(MorningCheckReceiver.EXTRA_BOOK_ID, spot.bookId.value)
      .putExtra(MorningCheckReceiver.EXTRA_CHAPTER_ID, spot.chapterId.value)
      .putExtra(MorningCheckReceiver.EXTRA_TIME, spot.time),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

  private companion object {
    val NIGHT = 18.hours
  }
}
