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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.ListeningEvent
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ListeningHistoryRepo
import voice.core.data.store.CurrentBookStore
import voice.core.playback.PlayerController
import voice.core.playback.history.ListeningHistoryRecorder
import voice.core.playback.history.PlaybackPosition
import voice.core.playback.notification.MainActivityIntentProvider
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import voice.core.strings.R as StringsR

private const val CHANNEL_ID = "morningCheck"
private const val NOTIFICATION_ID = 4250

/**
 * After a night with a missed listening check, asks in the morning whether the book went back to where
 * the listener lost track: with a notification once the sleep timer hours are over, and in the app until it's answered,
 * dismissed or the day is over. Their answers end up in the listening history, so the rules can get better.
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
  private val historyRepo: ListeningHistoryRepo,
  private val answeredStore: MorningCheckAnsweredStore,
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
    if (answeredStore.data.first() == today()) return
    // the night can start right when the sleep timer hours do, a long time before they end
    val spot = lostTrack.spot(bookId, within = NIGHT) ?: return
    val book = bookRepository.get(bookId) ?: return
    val place = place(book, spot)
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

  /**
   * The question for the current book after a night with a missed check, from the end of the sleep timer hours until
   * it's answered, dismissed, or the day is over.
   */
  fun question(): Flow<MorningQuestion?> = answeredStore.data.map { answered ->
    if (answered == today()) null else pendingQuestion()
  }

  private suspend fun pendingQuestion(): MorningQuestion? {
    val preference = sleepTimerPreferenceStore.data.first()
    if (!preference.morningCheckEnabled || !preference.autoSleepTimerEnabled || !preference.listeningCheckEnabled) return null
    val now = clock.instant().atZone(clock.zone).toLocalDateTime()
    val since = lastNightStart(now, preference.autoSleepStartTime, preference.autoSleepEndTime)?.atZone(clock.zone) ?: return null
    val bookId = currentBookStore.data.first() ?: return null
    val spot = lostTrack.spot(bookId, within = (clock.millis() - since.toInstant().toEpochMilli()).milliseconds) ?: return null
    val book = bookRepository.get(bookId) ?: return null
    return MorningQuestion(spot = spot, bookName = book.content.name, place = place(book, spot))
  }

  suspend fun dismiss() {
    answeredStore.updateData { today() }
    notificationManager.cancel(NOTIFICATION_ID)
  }

  suspend fun onAnswer(
    spot: PlaybackPosition,
    right: Boolean,
    source: ListeningEvent.Source = ListeningEvent.Source.Notification,
  ) {
    answeredStore.updateData { today() }
    historyRecorder.record(
      type = if (right) ListeningEvent.Type.MorningCheckRight else ListeningEvent.Type.MorningCheckWrong,
      source = source,
      position = spot,
    )
    if (right) {
      notificationManager.cancel(NOTIFICATION_ID)
      val live = playerController.livePlaybackState()
      val there = live != null && live.bookId == spot.bookId && live.chapterId == spot.chapterId && live.positionMs == spot.time
      if (currentBookStore.data.first() == spot.bookId && !there && !playedThisMorning(spot.bookId)) {
        playerController.setPosition(spot.time, spot.chapterId, ListeningEvent.Type.Seek, source)
      }
    } else if (source == ListeningEvent.Source.Notification) {
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

  /** Listening after the night moved on from there, so going back would lose that. */
  private suspend fun playedThisMorning(bookId: BookId): Boolean {
    val now = clock.instant().atZone(clock.zone)
    val morning = morning(now).toInstant().toEpochMilli()
    return historyRepo.events(bookId).first()
      .takeWhile { it.atMillis >= morning }
      .any { it.type == ListeningEvent.Type.Play }
  }

  private suspend fun morning(now: ZonedDateTime): ZonedDateTime {
    val end = sleepTimerPreferenceStore.data.first().autoSleepEndTime
    val morning = now.toLocalDate().atTime(end).atZone(clock.zone)
    return if (morning.isAfter(now)) morning.minusDays(1) else morning
  }

  private fun today(): Long = clock.instant().atZone(clock.zone).toLocalDate().toEpochDay()

  private fun place(
    book: Book,
    spot: PlaybackPosition,
  ): String {
    val time = DateUtils.formatElapsedTime(spot.time / 1000)
    val chapterIndex = book.chapters.indexOfFirst { it.id == spot.chapterId }
    return if (book.chapters.size > 1 && chapterIndex >= 0) {
      context.getString(StringsR.string.history_location_chapter, chapterIndex + 1, time)
    } else {
      time
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

data class MorningQuestion(
  val spot: PlaybackPosition,
  val bookName: String,
  val place: String,
)

/**
 * When the last sleep timer hours started, if they are over and tonight's haven't started yet, which is when the
 * morning question is asked in the app.
 */
internal fun lastNightStart(
  now: LocalDateTime,
  start: LocalTime,
  end: LocalTime,
): LocalDateTime? {
  val time = now.toLocalTime()
  if (time < end) return null
  return if (start > end) {
    // the hours wrap around midnight, so they started the day before
    if (time >= start) null else now.toLocalDate().minusDays(1).atTime(start)
  } else {
    now.toLocalDate().atTime(start)
  }
}
