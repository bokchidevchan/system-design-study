/**
 * Token Bucket Rate Limiter
 *
 * 버킷에 최대 capacity개의 토큰을 보관하고,
 * refillRate 속도로 토큰을 보충한다.
 * 요청이 올 때 토큰이 있으면 허용, 없으면 거부.
 */
class TokenBucket(
    private val capacity: Int,
    private val refillRate: Double, // 초당 충전되는 토큰 수
    private val clock: () -> Long = System::nanoTime
) {
    private var tokens: Double = capacity.toDouble()
    private var lastRefillTime: Long = clock()

    fun allow(): Boolean {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    private fun refill() {
        val now = clock()
        val elapsed = (now - lastRefillTime) / 1_000_000_000.0 // nano → sec
        val newTokens = elapsed * refillRate
        tokens = minOf(capacity.toDouble(), tokens + newTokens)
        lastRefillTime = now
    }
}

fun main() {
    val limiter = TokenBucket(capacity = 5, refillRate = 1.0)

    // 버스트: 5개는 즉시 통과
    println("=== 버스트 테스트 (capacity=5) ===")
    repeat(7) { i ->
        println("요청 ${i + 1}: ${if (limiter.allow()) "✓ 허용" else "✗ 거부"}")
    }

    // 1초 대기 후 토큰 1개 충전
    println("\n--- 1초 대기 ---")
    Thread.sleep(1000)
    println("요청 8: ${if (limiter.allow()) "✓ 허용" else "✗ 거부"}")
    println("요청 9: ${if (limiter.allow()) "✓ 허용" else "✗ 거부"}")
}
