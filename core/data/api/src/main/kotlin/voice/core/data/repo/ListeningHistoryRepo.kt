package voice.core.data.repo

import kotlinx.coroutines.flow.Flow
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.ListeningEvent

public interface ListeningHistoryRepo {

  /**
   * Newest first.
   */
  public fun events(bookId: BookId): Flow<List<ListeningEvent>>

  /**
   * The events of all books that were stored after the one with [id], oldest first.
   */
  public suspend fun eventsAfter(
    id: Long,
    limit: Int,
  ): List<ListeningEvent>

  /**
   * The id of the newest event of any book, which changes whenever one is stored.
   */
  public fun newestId(): Flow<Long?>

  /**
   * Stores the event. Only the newest events of each book are kept.
   */
  public suspend fun add(event: ListeningEvent)

  /**
   * Hands the history over when a book shows up under a new id. The events of the [from] books that happened in
   * one of the [chapters] move to the [to] book. [chapters] maps the old chapter ids to the ones of the [to] book,
   * and is also used for where the moved jumps went.
   */
  public suspend fun moveToBook(
    from: List<BookId>,
    to: BookId,
    chapters: Map<ChapterId, ChapterId>,
  )
}
