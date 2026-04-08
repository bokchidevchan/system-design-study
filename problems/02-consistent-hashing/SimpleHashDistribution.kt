import kotlin.math.abs

/**
 * 단순 해시 (hash % N) 방식의 문제점을 보여주는 데모.
 *
 * 서버 수가 변하면 거의 모든 키가 다른 서버에 재배치된다.
 * 캐시 서버였다면 cache stampede가 발생하는 상황이다.
 */
fun simpleHash(key: String, serverCount: Int): Int {
    return abs(key.hashCode()) % serverCount
}

fun main() {
    val totalKeys = 10_000
    val keys = (0 until totalKeys).map { "user:$it" }

    println("=== 단순 해시 (% N) 의 재배치 문제 ===\n")

    // 서버 4대일 때 각 키의 할당 결과
    val before = keys.map { it to simpleHash(it, 4) }

    // 서버 5대로 늘렸을 때
    val after = keys.map { it to simpleHash(it, 5) }

    // 서버가 바뀐 키 수 계산
    val movedCount = before.zip(after).count { (b, a) -> b.second != a.second }
    val movedPercent = movedCount * 100.0 / totalKeys

    println("서버 4대 → 5대로 변경")
    println("전체 키: $totalKeys")
    println("재배치된 키: $movedCount (${String.format("%.1f", movedPercent)}%)")
    println()

    // 서버별 부하 분포 확인
    println("--- 서버별 키 분포 (4대) ---")
    val dist4 = before.groupBy { it.second }.mapValues { it.value.size }
    for (i in 0 until 4) {
        val count = dist4[i] ?: 0
        val percent = count * 100.0 / totalKeys
        val bar = "█".repeat((percent / 2).toInt())
        println("서버 $i: ${String.format("%5d", count)}개 (${String.format("%.1f", percent)}%) $bar")
    }

    println()
    println("--- 서버별 키 분포 (5대) ---")
    val dist5 = after.groupBy { it.second }.mapValues { it.value.size }
    for (i in 0 until 5) {
        val count = dist5[i] ?: 0
        val percent = count * 100.0 / totalKeys
        val bar = "█".repeat((percent / 2).toInt())
        println("서버 $i: ${String.format("%5d", count)}개 (${String.format("%.1f", percent)}%) $bar")
    }

    // 다양한 서버 수 변화에 대한 재배치 비율
    println()
    println("=== 서버 수 변화별 재배치 비율 ===")
    println()
    for (n in listOf(4, 10, 50, 100)) {
        val beforeN = keys.map { simpleHash(it, n) }
        val afterN = keys.map { simpleHash(it, n + 1) }
        val moved = beforeN.zip(afterN).count { (b, a) -> b != a }
        val pct = moved * 100.0 / totalKeys
        println("${String.format("%3d", n)} → ${String.format("%3d", n + 1)}대: ${String.format("%.1f", pct)}% 재배치")
    }

    println()
    println("→ 서버가 많을수록 재배치 비율이 높아진다. 이게 단순 해시의 한계.")
}
