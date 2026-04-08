/**
 * Fixed Window Counter Rate Limiter
 *
 * 시간을 고정 윈도우로 나누고, 윈도우마다 카운터를 유지.
 * 가장 단순하지만 윈도우 경계에서 2배 트래픽이 통과할 수 있는 약점이 있다.
 */
class FixedWindowCounter(
    private val windowSizeMs: Long,
    private val limit: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var windowStart: Long = 0
    private var count: Int = 0

    fun allow(): Boolean {
        val now = clock()
        val currentWindow = now / windowSizeMs * windowSizeMs

        if (currentWindow != windowStart) {
            windowStart = currentWindow
            count = 0
        }

        return if (count < limit) {
            count++
            true
        } else {
            false
        }
    }
}

/**
 * Fixed Window의 경계 문제를 보여주는 데모.
 *
 * 윈도우 끝(0:59)에 5개 + 다음 윈도우 시작(1:00)에 5개
 * = 실질적으로 1초 안에 10개 통과.
 */
fun main() {
    val limiter = FixedWindowCounter(windowSizeMs = 1000, limit = 5)

    println("=== Fixed Window Counter (1초당 5개) ===")
    repeat(7) { i ->
        println("요청 ${i + 1}: ${if (limiter.allow()) "✓ 허용" else "✗ 거부"}")
    }

    // 윈도우 경계 문제 시뮬레이션
    println("\n--- 윈도우 넘어가길 대기 ---")
    val remaining = 1000 - (System.currentTimeMillis() % 1000)
    Thread.sleep(remaining + 10) // 다음 윈도우 직후

    println("=== 새 윈도우 시작 → 카운터 리셋 ===")
    repeat(5) { i ->
        println("요청 ${i + 8}: ${if (limiter.allow()) "✓ 허용" else "✗ 거부"}")
    }
    println("\n⚠️ 경계 직전+직후로 짧은 시간에 limit×2 요청이 통과할 수 있음")
}
