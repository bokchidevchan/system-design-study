import java.security.MessageDigest
import java.util.TreeMap

/**
 * Consistent Hashing + Virtual Node 구현.
 *
 * TreeMap을 해시 링으로 사용한다.
 * - TreeMap의 key: 해시 값 (링 위의 위치)
 * - TreeMap의 value: 물리 서버 이름
 *
 * 키를 찾을 때 ceilingEntry()로 시계 방향 첫 번째 서버를 O(log N)에 찾는다.
 */
class ConsistentHashRing(
    private val virtualNodeCount: Int = 150
) {
    // 해시 링: TreeMap이 정렬을 유지하므로 "시계 방향 다음 노드"를 빠르게 찾을 수 있다
    private val ring = TreeMap<Long, String>()

    // 물리 서버 목록
    private val physicalNodes = mutableSetOf<String>()

    /**
     * 서버를 링에 추가한다.
     * 가상 노드를 virtualNodeCount개 생성해서 링 전체에 분산 배치한다.
     */
    fun addNode(node: String) {
        physicalNodes.add(node)
        for (i in 0 until virtualNodeCount) {
            val virtualKey = "$node-vn$i"
            val hash = hash(virtualKey)
            ring[hash] = node
        }
    }

    /**
     * 서버를 링에서 제거한다.
     * 해당 서버의 가상 노드를 모두 삭제한다.
     */
    fun removeNode(node: String) {
        physicalNodes.remove(node)
        for (i in 0 until virtualNodeCount) {
            val virtualKey = "$node-vn$i"
            val hash = hash(virtualKey)
            ring.remove(hash)
        }
    }

    /**
     * 키가 어떤 서버에 할당되는지 찾는다.
     *
     * 키의 해시 값에서 시계 방향으로 가장 가까운 노드를 찾는다.
     * TreeMap.ceilingEntry()가 "이 값 이상인 첫 번째 엔트리"를 반환하므로,
     * 이게 곧 시계 방향 다음 서버다.
     *
     * 만약 null이면 (링의 끝을 지난 경우) 링의 첫 번째 노드로 돌아간다 (원형).
     */
    fun getNode(key: String): String? {
        if (ring.isEmpty()) return null
        val hash = hash(key)
        // 시계 방향으로 가장 가까운 노드
        val entry = ring.ceilingEntry(hash)
        // 링의 끝을 넘으면 처음으로 (원형 구조)
        return entry?.value ?: ring.firstEntry().value
    }

    fun getPhysicalNodes(): Set<String> = physicalNodes.toSet()

    fun getRingSize(): Int = ring.size

    /**
     * MD5 해시를 사용해 키를 링 위의 위치(Long)로 변환한다.
     *
     * 왜 MD5인가?
     * - String.hashCode()는 분포가 고르지 않아서 가상 노드가 한쪽에 몰릴 수 있다
     * - MD5는 입력이 조금만 달라도 출력이 완전히 달라지므로 (avalanche effect) 균등 분포에 적합
     * - 보안용이 아니라 분산용이므로 MD5로 충분
     */
    private fun hash(key: String): Long {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(key.toByteArray())
        // 앞 8바이트를 Long으로 변환
        return ((digest[0].toLong() and 0xFF) shl 56) or
                ((digest[1].toLong() and 0xFF) shl 48) or
                ((digest[2].toLong() and 0xFF) shl 40) or
                ((digest[3].toLong() and 0xFF) shl 32) or
                ((digest[4].toLong() and 0xFF) shl 24) or
                ((digest[5].toLong() and 0xFF) shl 16) or
                ((digest[6].toLong() and 0xFF) shl 8) or
                (digest[7].toLong() and 0xFF)
    }
}

fun main() {
    val totalKeys = 10_000
    val keys = (0 until totalKeys).map { "user:$it" }

    println("=== Consistent Hashing 데모 ===\n")

    // --- 1. 서버 추가 시 재배치 비율 ---
    println("--- 서버 추가 시 재배치 비율 (vs 단순 해시) ---\n")

    val ring = ConsistentHashRing(virtualNodeCount = 150)
    listOf("server-A", "server-B", "server-C", "server-D").forEach { ring.addNode(it) }

    // 4대일 때 할당
    val assignmentBefore = keys.map { it to ring.getNode(it)!! }

    // 서버 1대 추가
    ring.addNode("server-E")

    // 5대일 때 할당
    val assignmentAfter = keys.map { it to ring.getNode(it)!! }

    val movedCount = assignmentBefore.zip(assignmentAfter).count { (b, a) -> b.second != a.second }
    val movedPercent = movedCount * 100.0 / totalKeys
    val idealPercent = 100.0 / 5  // 이상적으로는 1/N만 이동

    println("서버 4대 → 5대 (Consistent Hashing)")
    println("재배치된 키: $movedCount / $totalKeys (${String.format("%.1f", movedPercent)}%)")
    println("이상적 재배치: ${String.format("%.1f", idealPercent)}% (1/N)")
    println("단순 해시였다면: ~80% 재배치")
    println()

    // --- 2. 서버별 부하 분포 ---
    println("--- 서버별 키 분포 (5대, 가상노드 150개) ---\n")

    val distribution = assignmentAfter.groupBy { it.second }.mapValues { it.value.size }
    for ((server, count) in distribution.toSortedMap()) {
        val percent = count * 100.0 / totalKeys
        val bar = "█".repeat((percent / 2).toInt())
        println("$server: ${String.format("%5d", count)}개 (${String.format("%.1f", percent)}%) $bar")
    }

    // --- 3. 가상 노드 수에 따른 균등도 비교 ---
    println()
    println("--- 가상 노드 수에 따른 분배 균등도 ---\n")

    for (vnCount in listOf(1, 10, 50, 150, 500)) {
        val testRing = ConsistentHashRing(virtualNodeCount = vnCount)
        listOf("server-A", "server-B", "server-C", "server-D", "server-E").forEach { testRing.addNode(it) }

        val dist = keys.groupBy { testRing.getNode(it)!! }.mapValues { it.value.size }
        val counts = dist.values.toList()
        val avg = counts.average()
        val stdDev = Math.sqrt(counts.map { (it - avg) * (it - avg) }.average())
        val maxDiff = counts.max() - counts.min()

        println("가상노드 ${String.format("%3d", vnCount)}개: " +
                "최소 ${String.format("%5d", counts.min())} / " +
                "최대 ${String.format("%5d", counts.max())} / " +
                "편차 ${String.format("%6.1f", stdDev)} / " +
                "최대차이 ${String.format("%5d", maxDiff)}")
    }

    // --- 4. 서버 제거 시 영향 범위 ---
    println()
    println("--- 서버 제거 시 재배치 ---\n")

    val ringForRemoval = ConsistentHashRing(virtualNodeCount = 150)
    listOf("server-A", "server-B", "server-C", "server-D", "server-E").forEach { ringForRemoval.addNode(it) }

    val beforeRemoval = keys.map { it to ringForRemoval.getNode(it)!! }

    // server-C 제거 (장애 시뮬레이션)
    ringForRemoval.removeNode("server-C")

    val afterRemoval = keys.map { it to ringForRemoval.getNode(it)!! }

    val removedMoved = beforeRemoval.zip(afterRemoval).count { (b, a) -> b.second != a.second }
    val removedPercent = removedMoved * 100.0 / totalKeys

    println("server-C 제거 (5대 → 4대)")
    println("재배치된 키: $removedMoved / $totalKeys (${String.format("%.1f", removedPercent)}%)")
    println("→ server-C가 담당하던 키만 다른 서버로 이동, 나머지는 그대로")
}
