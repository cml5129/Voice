package voice.core.transcript

/**
 * Concatenates the cues that fall within the [windowMs] preceding [positionMs].
 * Cues are assumed to be ordered by [TranscriptCue.timestampStartMs], matching the
 * order they are transcribed in.
 */
fun List<TranscriptCue>.textForPrecedingWindow(
  positionMs: Long,
  windowMs: Long,
): String {
  val windowStartMs = (positionMs - windowMs).coerceAtLeast(0)
  return asSequence()
    .filter { it.timestampEndMs > windowStartMs && it.timestampStartMs < positionMs }
    .joinToString(separator = " ") { it.text }
}
