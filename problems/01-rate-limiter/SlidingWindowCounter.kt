/**
 * Sliding Window Counter Rate Limiter
 *
 * Fixed Window의 메모리 효율 + Sliding Window의 정확도를 절충.
 * 이전 윈도우 카운트를 현재 윈도우와의 겹침 비율로 가중 합산한다.
 *
 * 공식: count = prev_count × (1 - elapsed/window) + curr_count
 * 이 값이 limit 이하이면 허용.
 */
class SlidingWindowCounter(
    private val windowSizeMs: Long,
    private val limit: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var prevWindowStart: Long = 0
    private var currWindowStart: Long = 0
    private var prevCount: Int = 0
    private var currCount: Int = 0

    fun allow(): Boolean {
        val now = clock()
        val currentWindow = now / windowSizeMs * windowSizeMs

        // 윈도우가 바뀌었으면 카운터 이동
        if (currentWindow != currWindowStart) {
            if (currentWindow - currWindowStart >= windowSizeMs * 2) {
                // 2개 윈도우 이상 건너뜀 → 둘 다 리셋
                prevCount = 0
            } else {
                prevCount = currCount
            }
            currCount = 0
            prevWindowStart = currWindowStart
            currWindowStart = currentWindow
        }

        // 이전 윈도우의 가중치 계산
        val elapsedInWindow = now - currWindowStart
        val prevWeight = 1.0 - elapsedInWindow.toDouble() / windowSizeMs
        val estimatedCount = prevCount * maxOf(0.0, prevWeight) + currCount

        return if (estimatedCount < limit) {
            currCount++
            true
        } else {
            false
        }
    }
}

fun main() {
    // 1초 윈도우, 최대 5개 요청
    val limiter = SlidingWindowCounter(windowSizeMs = 1000, limit = 5)

    println("=== Sliding Window Counter (1초당 5개) ===")
    repeat(7) { i ->
        println("요청 ${i + 1}: ${if (limiter.allow()) "✓ 허용" else "✗ 거부"}")
    }

    println("\n--- 600ms 대기 (윈도우 60% 경과) ---")
    Thread.sleep(600)

    // 이전 윈도우 5개 × 0.4(겹침) = 2.0 → 남은 여유 약 3개
    println("=== 윈도우 경계 테스트 ===")
    repeat(5) { i ->
        println("요청 ${i + 8}: ${if (limiter.allow()) "✓ 허용" else "✗ 거부"}")
    }
}
