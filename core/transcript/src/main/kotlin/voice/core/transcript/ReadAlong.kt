package voice.core.transcript

/**
 * The cue being read at [positionMs]: the last one that started by then, so a pause between two lines keeps the one
 * just read. -1 before the first line. Cues are ordered by their start, as they were transcribed.
 */
fun List<TranscriptCue>.cueIndexAt(positionMs: Long): Int {
  var low = 0
  var high = size - 1
  var found = -1
  while (low <= high) {
    val mid = (low + high) ushr 1
    if (this[mid].timestampStartMs <= positionMs) {
      found = mid
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return found
}
