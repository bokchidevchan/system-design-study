# Rate Limiter

## Rate Limiting이란?

클라이언트가 일정 시간 동안 보낼 수 있는 요청 수를 제한하는 메커니즘이다.

일상에서 가장 익숙한 예시를 하나 들어보자. 로그인할 때 비밀번호를 5번 틀리면 "잠시 후 다시 시도해주세요"라는 메시지가 뜬다. 이게 Rate Limiting이다. 누군가 비밀번호를 무한히 대입해보는 걸(brute force) 막기 위해, 일정 횟수 이상 시도하면 차단하는 것이다.

이걸 API 서버 수준으로 확장하면, "이 사용자(또는 IP)는 1분에 100번까지만 API를 호출할 수 있다"라는 규칙을 거는 것이다.

---

## 왜 필요한가?

### 1. 서비스 보호

서버 리소스는 유한하다. DB 커넥션 풀, CPU, 메모리 모두 한계가 있다. 한 사용자가 초당 수만 건의 요청을 보내면, 다른 정상 사용자들이 서비스를 이용하지 못하게 된다.

2016년 GitHub에 660Gbps 규모의 DDoS 공격이 있었다. 이런 공격까지는 아니더라도, 버그가 있는 클라이언트가 무한루프로 API를 호출하는 것만으로도 서비스가 다운될 수 있다. Rate Limiter는 이런 상황에서 첫 번째 방어선 역할을 한다.

### 2. 비용 제어

클라우드 환경에서는 트래픽 = 돈이다. 특히 third-party API를 호출하는 서비스라면 (예: OpenAI API, Google Maps API), 비정상적인 호출 폭주가 곧바로 과금 폭탄으로 이어진다. Rate Limiter가 있으면 예산 범위 내에서 사용량을 통제할 수 있다.

### 3. 공정한 리소스 분배

무료 플랜 사용자와 유료 플랜 사용자에게 다른 rate limit을 걸 수 있다. Twitter(X) API를 예로 들면:

- 무료: 월 1,500 트윗 읽기
- Basic ($100/mo): 월 10,000 트윗 읽기
- Pro ($5,000/mo): 월 1,000,000 트윗 읽기

이처럼 Rate Limiter는 단순한 보안 장치가 아니라 **비즈니스 모델의 핵심 인프라**이기도 하다.

### 4. Cascading Failure 방지

마이크로서비스 아키텍처에서 서비스 A가 서비스 B를 호출하는데, B가 느려지면 A에 요청이 쌓이고, A를 호출하는 C도 느려지고... 이렇게 연쇄적으로 전체 시스템이 무너지는 걸 Cascading Failure라고 한다. Rate Limiter를 서비스 간 경계에 두면 이 전파를 끊을 수 있다.

---

## Rate Limiter를 어디에 둘 것인가?

구현에 들어가기 전에, 먼저 Rate Limiter의 위치를 생각해야 한다.

### 클라이언트 측

클라이언트(모바일 앱, 브라우저)에서 요청 빈도를 조절하는 방식. 하지만 클라이언트 코드는 쉽게 우회할 수 있기 때문에 **신뢰할 수 없다**. 보조 수단일 뿐 주 방어선으로는 부적합하다.

### 서버 측

API 서버 코드 안에 직접 Rate Limiting 로직을 넣는 방식. 간단하지만, 서버가 여러 대면 서버 간 상태 공유가 필요해진다.

### 미들웨어 (API Gateway)

가장 일반적인 방식. 클라이언트와 서버 사이에 Rate Limiter를 둔다. AWS API Gateway, Kong, Nginx 같은 것들이 이 역할을 한다.

```
Client → [API Gateway + Rate Limiter] → Server
```

실무에서는 대부분 API Gateway에서 처리한다. 직접 구현하는 경우는 세밀한 비즈니스 로직이 필요할 때다 (예: "이 사용자는 결제 API는 분당 10회, 조회 API는 분당 100회").

---

## 핵심 알고리즘

### 1. Token Bucket

가장 직관적이고, 실무에서 가장 널리 쓰이는 알고리즘이다. Amazon API Gateway, Stripe가 이 방식을 쓴다.

**동작 원리:**

버킷에 토큰이 들어있다고 상상해보자.

1. 버킷은 최대 `capacity`개의 토큰을 담을 수 있다
2. 일정 간격(`refillRate`)으로 토큰이 자동 충전된다
3. 요청이 들어오면 토큰 1개를 소비하고 통과시킨다
4. 토큰이 0개면 요청을 거부한다

```
           ┌─────────────┐
           │  Token: 3   │  ← 최대 5개, 초당 1개 충전
           │  ● ● ●      │
           └──────┬──────┘
                  │
    요청 도착 ────┤
                  ↓
         토큰 있음? ─── Yes → 토큰 소비, 요청 통과
                  │
                  No → 요청 거부 (429 Too Many Requests)
```

**왜 인기 있는가?**

Token Bucket의 가장 큰 강점은 **버스트 트래픽을 허용**한다는 점이다. 토큰이 5개 쌓여있으면, 한 번에 5개 요청을 보낼 수 있다. 이건 실제 사용 패턴과 잘 맞는다. 사용자는 보통 가만히 있다가 한번에 여러 요청을 몰아서 보내기 때문이다.

**파라미터:**

- `capacity` (버킷 크기): 버스트 허용량을 결정한다. 크면 순간 폭주를 많이 허용하고, 작으면 더 엄격하다.
- `refillRate` (충전 속도): 장기적인 평균 처리량을 결정한다. 초당 10개 충전이면, 장기적으로 초당 10개가 평균 한도다.

**한계:**

이 두 파라미터를 적절히 설정하는 게 생각보다 어렵다. 트래픽 패턴을 잘 분석해야 한다. 너무 여유롭게 잡으면 보호 효과가 떨어지고, 너무 빡빡하면 정상 사용자도 차단당한다.

**코드 구현:** [`TokenBucket.kt`](./TokenBucket.kt)

핵심은 `refill()` 메서드다. 매 요청마다 "마지막 충전 이후 얼마나 시간이 지났는지" 계산해서 토큰을 보충한다. 타이머를 돌리는 게 아니라, 요청이 올 때 lazy하게 계산하는 방식이다.

```kotlin
private fun refill() {
    val now = clock()
    val elapsed = (now - lastRefillTime) / 1_000_000_000.0 // nano → sec
    val newTokens = elapsed * refillRate
    tokens = minOf(capacity.toDouble(), tokens + newTokens)  // capacity 초과 방지
    lastRefillTime = now
}
```

왜 실제 타이머 대신 이렇게 하는가? 사용자가 100만 명이면 타이머도 100만 개 돌려야 한다. lazy 계산은 요청이 올 때만 동작하니까 리소스를 아낄 수 있다.

`allow()` 메서드는 단순하다. 충전 후 토큰이 1개 이상이면 소비하고 허용, 아니면 거부.

```kotlin
fun allow(): Boolean {
    refill()
    return if (tokens >= 1.0) {
        tokens -= 1.0
        true
    } else {
        false
    }
}
```

`main()`을 실행하면 capacity=5인 버킷에 7개 요청을 보내서, 5개는 통과하고 2개는 거부되는 걸 확인할 수 있다. 1초 대기 후에는 토큰 1개가 충전되어 다시 1개 요청이 통과한다.

---

### 2. Leaky Bucket

수도꼭지에서 물이 쏟아져 들어와도, 바닥 구멍으로는 일정 속도로만 빠져나가는 양동이를 상상하면 된다.

**동작 원리:**

1. 요청이 들어오면 큐(FIFO)에 넣는다
2. 큐에서 일정 속도로 요청을 꺼내서 처리한다
3. 큐가 가득 차면 새 요청을 버린다

```
요청들 → [큐: ■ ■ ■ ■ □ □] → 고정 속도로 처리
              ↑ 가득 차면 거부     ↑ 1초에 2개씩
```

**Token Bucket과 뭐가 다른가?**

핵심 차이는 **출력 속도**다.
- Token Bucket: 토큰이 있는 만큼 한번에 처리 가능 (출력이 불규칙할 수 있음)
- Leaky Bucket: 무조건 일정 속도로만 처리 (출력이 항상 균일)

네트워크 트래픽 쉐이핑에 적합하다. 패킷을 일정 속도로 내보내야 할 때 쓴다. 반면 API rate limiting에는 Token Bucket이 더 적합한 경우가 많다. 사용자 입장에서 "토큰만 있으면 바로 응답"이 더 좋은 경험이니까.

---

### 3. Fixed Window Counter

가장 구현이 단순한 알고리즘이다.

**동작 원리:**

1. 시간을 고정 크기의 윈도우로 나눈다 (예: 1분 단위)
2. 각 윈도우마다 카운터를 하나 둔다
3. 요청이 오면 현재 윈도우의 카운터를 1 올린다
4. 카운터가 limit에 도달하면 거부한다
5. 새 윈도우가 시작되면 카운터를 0으로 리셋한다

```
   0:00         1:00         2:00
    |── Window 1 ──|── Window 2 ──|
    [count: 98/100] [count: 0/100]
```

**장점:** Redis `INCR` + `EXPIRE` 두 명령어로 구현 가능. 이보다 간단할 수 없다.

**치명적인 단점 — 윈도우 경계 문제:**

이 알고리즘의 가장 큰 약점이다. 예를 들어 limit이 100/분이라고 하자.

```
   0:00              0:30              1:00              1:30
    |──── Window 1 ────|──── Window 2 ────|
                ↑                ↑
           0:59에 100개 요청   1:01에 100개 요청
```

0:59에 100개, 1:01에 100개를 보내면, 각 윈도우에서는 limit을 넘지 않았으니 전부 통과한다. 하지만 실질적으로 2초 안에 200개 요청이 통과한 셈이다. limit의 2배가 허용되는 것이다.

이 문제가 치명적이냐? 상황에 따라 다르다. "대략적으로 제한"하면 되는 경우에는 괜찮다. 하지만 과금이나 보안처럼 정확성이 중요한 경우에는 쓰면 안 된다.

**코드 구현:** [`FixedWindowCounter.kt`](./FixedWindowCounter.kt)

핵심은 "현재 시간이 어떤 윈도우에 속하는지" 계산하는 부분이다.

```kotlin
val currentWindow = now / windowSizeMs * windowSizeMs
```

이 한 줄로 현재 시간을 윈도우 시작 시간으로 내림(floor)한다. 예를 들어 windowSizeMs=1000이고 now=2,350이면, `2350 / 1000 * 1000 = 2000`이 된다. 윈도우가 바뀌면 카운터를 0으로 리셋한다.

`main()`에서는 윈도우 경계를 넘긴 뒤 카운터가 리셋되는 걸 보여준다. 이 데모를 통해 경계 문제가 실제로 발생하는 상황을 직접 확인할 수 있다.

---

### 4. Sliding Window Log

Fixed Window의 경계 문제를 완전히 해결한 알고리즘이다.

**동작 원리:**

1. 모든 요청의 타임스탬프를 로그(sorted set)에 저장한다
2. 새 요청이 오면, 현재 시간에서 윈도우 크기만큼 이전의 타임스탬프는 삭제한다
3. 남은 로그의 개수가 limit 이하면 허용, 초과면 거부한다

```
현재 시간: 1:15
윈도우: 1분
로그: [0:42, 0:58, 1:01, 1:03, 1:10, 1:14]
       ↑삭제   ↑삭제
남은 로그: [1:01, 1:03, 1:10, 1:14] → 4개, limit 5 이하 → 허용
```

**장점:** 경계 문제가 없다. 어느 시점을 기준으로 잡든, 정확히 윈도우 내 요청 수를 계산한다.

**단점:** 메모리를 많이 먹는다. 요청 하나당 타임스탬프 하나를 저장해야 하니까. limit이 10,000/분이고 사용자가 100만 명이면? 메모리가 어마어마하게 필요하다. 그래서 대규모 서비스에서는 잘 쓰지 않는다.

---

### 5. Sliding Window Counter (하이브리드)

실무에서 가장 실용적인 절충안이다. Fixed Window의 메모리 효율 + Sliding Window의 정확도를 합쳤다. Cloudflare가 이 방식을 쓴다.

**핵심 아이디어:**

이전 윈도우의 카운트를 "겹치는 비율"만큼 가중치를 줘서 현재 윈도우와 합산한다.

```
공식: 추정 요청 수 = (이전 윈도우 카운트 × 겹침 비율) + 현재 윈도우 카운트
```

**예시:**

- limit: 100/분
- 이전 윈도우 (0:00~1:00): 84개 요청
- 현재 윈도우 (1:00~2:00): 36개 요청
- 현재 시간: 1:15 (현재 윈도우의 25% 지점)

```
   0:00              1:00    1:15         2:00
    |── 이전 (84개) ──|─ 현재 (36개) ─────|
                      |←25%→|
                      
겹침 비율 = 1 - 0.25 = 0.75
추정치 = 84 × 0.75 + 36 = 63 + 36 = 99 → limit 100 이하 → 허용
```

왜 0.75인가? 현재 윈도우가 25% 진행됐으니, 이전 윈도우의 75%가 아직 "슬라이딩 윈도우" 안에 들어온다고 보는 것이다.

**정확한가?** 엄밀히 말하면 근사치다. 이전 윈도우 내 요청이 균등하게 분포했다고 가정하기 때문이다. 하지만 Cloudflare의 실제 측정에 따르면 오차율이 0.003% 수준으로, 실용적으로는 충분히 정확하다.

**메모리:** 윈도우당 카운터 2개만 저장하면 되므로, Sliding Window Log보다 압도적으로 적다.

**코드 구현:** [`SlidingWindowCounter.kt`](./SlidingWindowCounter.kt)

이 코드에서 가장 중요한 부분은 가중치 계산이다.

```kotlin
val elapsedInWindow = now - currWindowStart
val prevWeight = 1.0 - elapsedInWindow.toDouble() / windowSizeMs
val estimatedCount = prevCount * maxOf(0.0, prevWeight) + currCount
```

`elapsedInWindow / windowSizeMs`가 현재 윈도우의 진행률이다. 이걸 1에서 빼면 이전 윈도우의 겹침 비율이 나온다. `maxOf(0.0, prevWeight)`는 혹시 음수가 되는 edge case를 방어한다.

윈도우 전환 로직도 주목할 부분이다:

```kotlin
if (currentWindow - currWindowStart >= windowSizeMs * 2) {
    // 2개 윈도우 이상 건너뜀 → 둘 다 리셋
    prevCount = 0
} else {
    prevCount = currCount
}
```

오랫동안 요청이 없다가 갑자기 오면? 2개 윈도우 이상 건너뛴 경우 이전 카운트를 0으로 초기화한다. 안 그러면 아주 오래된 카운트가 가중치에 반영되는 버그가 생긴다.

`main()`에서는 600ms 대기 후 윈도우 경계를 넘기면서, 이전 윈도우 카운트가 40% 가중치로만 반영되어 약 3개 요청이 추가로 허용되는 걸 보여준다.

---

## Trade-off 비교 총정리

| 알고리즘 | 메모리 | 정확도 | 버스트 허용 | 구현 난이도 | 대표 사용처 |
|----------|--------|--------|-------------|-------------|-------------|
| Token Bucket | O(1) | 중간 | O | 중간 | AWS API Gateway, Stripe |
| Leaky Bucket | O(큐 크기) | 높음 | X | 중간 | 네트워크 트래픽 쉐이핑 |
| Fixed Window | O(1) | 낮음 | △ 경계문제 | **낮음** | 단순 API, Redis INCR |
| Sliding Window Log | O(N) | **높음** | X | 중간 | 소규모, 정확도 중요 |
| Sliding Window Counter | O(1) | 중간~높음 | △ | 중간 | **Cloudflare**, 대규모 |

**면접에서 어떤 걸 고를까?**

- 일반적인 API rate limiting → **Token Bucket** (범용적, 설명하기 쉬움)
- 정확도가 중요한 과금 시스템 → **Sliding Window Counter** (실용적 정확도 + 낮은 메모리)
- "가장 단순하게"라는 조건 → **Fixed Window** (경계 문제 언급은 필수)

---

## 심화: Nginx는 어떻게 모든 트래픽을 감당하나?

Rate Limiter를 미들웨어(API Gateway)에 둔다고 했다. 그런데 "모든 트래픽이 Nginx 한 곳을 지나간다면, Nginx가 병목 아닌가?"라는 의문이 자연스럽게 생긴다.

### 이벤트 기반 비동기 아키텍처

일반적인 웹서버(Apache)는 요청 하나당 스레드(또는 프로세스) 하나를 할당한다. 동시 접속 1만 개면 스레드 1만 개가 필요하고, 그만큼 메모리를 먹는다. 이게 유명한 **C10K 문제**다 (동시 접속 10,000개를 처리할 수 없는 문제).

Nginx는 이걸 완전히 다르게 풀었다.

```
Apache 방식:
요청 1 → [스레드 1] → 응답
요청 2 → [스레드 2] → 응답
요청 3 → [스레드 3] → 응답
...1만 개 스레드 = 메모리 폭발

Nginx 방식:
요청 1 ─┐
요청 2 ──┼→ [Worker 프로세스 1개] → 이벤트 루프로 전부 처리
요청 3 ─┘
```

Worker 프로세스가 1~수개뿐인데, 각 Worker가 **이벤트 루프**를 돌면서 수천~수만 개 커넥션을 동시에 처리한다. 요청을 받고 → 백엔드에 전달하고 → 응답 기다리는 동안 **블로킹하지 않고** 다른 요청을 처리한다.

실제 성능:

- 단일 서버에서 **수만~수십만 동시 접속** 처리 가능
- 메모리 사용량이 매우 낮음 (커넥션 1만 개에 ~2.5MB 수준)
- 초당 수만 건의 요청을 rate limit 체크하면서도 지연 거의 없음

### 그래도 Nginx가 병목이 되면?

트래픽이 정말 어마어마하면 Nginx 한 대로는 부족할 수 있다. 그때는 앞에 한 겹 더 둔다.

```
                    ┌→ Nginx 1 → [서버 A, B]
DNS/L4 LB (예: NLB) ┤
                    └→ Nginx 2 → [서버 C, D]
```

- **DNS 라운드 로빈**: 도메인에 IP 여러 개를 물려서 분산
- **L4 로드밸런서** (AWS NLB, LVS): TCP 레벨에서 패킷만 전달하니까 Nginx보다 훨씬 가볍다. 초당 수백만 건도 가능

정리하면 계층 구조가 이렇게 된다:

```
Client
  ↓
DNS 라운드 로빈 / L4 LB (패킷만 전달, 초경량)
  ↓
Nginx (L7, HTTP 파싱 + Rate Limit + 라우팅)
  ↓
Application Server (실제 비즈니스 로직)
```

아래로 갈수록 무거운 일을 하고, 위로 갈수록 가볍지만 트래픽을 많이 감당한다. 각 계층이 자기 역할만 하니까 전체 시스템이 스케일링 가능해지는 구조다.

### Nginx에서 Rate Limiting 설정 예시

Nginx는 기본 모듈로 rate limiting을 지원한다:

```nginx
# IP당 초당 10개 요청 제한
limit_req_zone $binary_remote_addr zone=api:10m rate=10r/s;

server {
    location /api/ {
        limit_req zone=api burst=20 nodelay;
        proxy_pass http://backend;
    }
}
```

이 설정 자체가 Token Bucket 알고리즘이다. `rate=10r/s`가 refill rate, `burst=20`이 bucket capacity에 해당한다. 더 세밀한 제어가 필요하면 (유저별, API별 다른 limit) Nginx 뒤에 Redis를 두고 애플리케이션 레벨에서 처리하는 식으로 조합한다.

---

## 분산 환경에서의 Rate Limiting

단일 서버라면 위 알고리즘을 메모리에 구현하면 끝이다. 하지만 실제 서비스는 서버가 여러 대다. 여기서 두 가지 문제가 생긴다.

### 문제 1: Race Condition

서버 A와 B가 동시에 Redis에서 카운터를 읽어 값이 4라고 하자 (limit은 5). 둘 다 "4 < 5니까 통과!"라고 판단하고 각각 +1 한다. 결과적으로 카운터가 6이 되어 limit을 초과한다.

```
서버 A: READ counter → 4 (< 5, 통과!) → INCR → 5
서버 B: READ counter → 4 (< 5, 통과!) → INCR → 6  ← limit 초과!
```

**해결: Redis Lua Script**

읽기와 쓰기를 하나의 atomic operation으로 묶는다. Redis는 Lua 스크립트를 싱글 스레드로 실행하므로, 중간에 다른 명령이 끼어들 수 없다.

```lua
-- Redis Lua: atomic rate limit check
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
if current > tonumber(ARGV[2]) then
    return 0  -- 거부
end
return 1  -- 허용
```

### 문제 2: 여러 Rate Limit 규칙 조합

실무에서는 하나의 rule만 있는 경우가 드물다. 보통 이렇게 여러 층을 건다:

- 초당 10개 (순간 폭주 방지)
- 분당 200개 (지속적 남용 방지)
- 일당 10,000개 (할당량 제한)

이 경우 각 rule마다 별도의 카운터를 두고, **모든 rule을 통과해야** 요청을 허용한다.

### 문제 3: 분산 환경의 동기화

서버가 각각 로컬 카운터를 가지고 있으면, 사용자가 서버 A에 3번, 서버 B에 3번 요청해서 총 6번인데 각 서버에서는 3번으로 보이는 문제가 생긴다.

**해결:**
- **중앙 저장소 (Redis)**: 가장 일반적. Redis 싱글 스레드 특성상 동시성 문제도 자연스럽게 해결된다.
- **Sticky Session**: 같은 사용자를 항상 같은 서버로 보내는 방식. 간단하지만, 서버가 죽으면 세션이 날아간다.

---

## Rate Limit 응답 설계

서버가 요청을 거부할 때, 클라이언트에게 유용한 정보를 줘야 한다.

### HTTP 상태 코드

- **429 Too Many Requests**: 표준 응답 코드

### 응답 헤더

```http
HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit: 100           # 윈도우당 최대 요청 수
X-RateLimit-Remaining: 0         # 남은 요청 수
X-RateLimit-Reset: 1672531260    # 리셋 시각 (Unix timestamp)
Retry-After: 30                  # 재시도까지 대기 시간 (초)
```

이 헤더를 보고 클라이언트는 "30초 뒤에 다시 시도하면 되는구나"라고 판단할 수 있다. 잘 만들어진 클라이언트 SDK는 이 헤더를 파싱해서 자동 재시도(exponential backoff)를 구현한다.

---

## 실제 서비스들의 Rate Limit

| 서비스 | 제한 | 알고리즘 |
|--------|------|----------|
| GitHub API | 인증: 5,000/h, 비인증: 60/h | - |
| Twitter API | 플랜별 차등 (위 참조) | - |
| Stripe | 100/s (live), 25/s (test) | Token Bucket |
| Cloudflare | 커스텀 rule 설정 | Sliding Window Counter |
| AWS API Gateway | 계정당 10,000/s | Token Bucket |
| OpenAI API | 모델/티어별 TPM/RPM 제한 | Token Bucket |

---

## 코드 파일 가이드

| 파일 | 알고리즘 | 핵심 포인트 |
|------|----------|-------------|
| [`TokenBucket.kt`](./TokenBucket.kt) | Token Bucket | lazy refill 패턴, 버스트 허용 확인 |
| [`FixedWindowCounter.kt`](./FixedWindowCounter.kt) | Fixed Window | 윈도우 floor 계산, 경계 문제 데모 |
| [`SlidingWindowCounter.kt`](./SlidingWindowCounter.kt) | Sliding Window Counter | 가중치 보정 공식, 윈도우 전환 처리 |

각 파일에 `main()` 함수가 있어서 바로 실행해볼 수 있다.

---

## 여담: 코딩테스트의 Sliding Window와 같은 원리인가?

맞다. 근데 살짝 다르다.

**코딩테스트 Sliding Window**는 배열/문자열에서 고정 크기(또는 가변 크기)의 구간을 한 칸씩 밀면서 탐색하는 기법이다.

```
배열: [1, 3, 5, 2, 8, 1, 4]
윈도우 크기: 3

[1, 3, 5] 2  8  1  4  → 합: 9
 1 [3, 5, 2] 8  1  4  → 합: 10
 1  3 [5, 2, 8] 1  4  → 합: 15
```

전체를 매번 다시 계산하면 O(n*k)인데, 윈도우를 밀면서 "빠지는 값 빼고, 들어오는 값 더하면" O(n)에 풀 수 있다.

**Rate Limiter의 Window**는 시간 축에서 같은 개념을 쓰는 것이다.

```
코딩테스트:  배열 인덱스 위에서 구간을 밀면서 → 합/최대/최소 계산
Rate Limiter: 시간 축 위에서 구간을 밀면서    → 요청 수 계산
```

둘 다 결국 **"전체를 매번 다시 세지 않고, 변화량만 추적해서 효율적으로 계산한다"**는 같은 사고방식이다. 코테에서 이 패턴을 체화해두면 시스템 설계에서 Sliding Window Counter가 왜 효율적인지 바로 이해가 된다.

---

## 면접에서 Rate Limiter 설계 시 체크리스트

1. **요구사항 명확화**: 누구를 기준으로 제한? (user ID, IP, API key)
2. **알고리즘 선택 + 근거**: "Token Bucket을 선택한 이유는..."
3. **분산 환경**: Redis 중앙 저장소, atomic 연산
4. **여러 rule 조합**: 초/분/일 다층 제한
5. **응답 설계**: 429 + 헤더 정보
6. **모니터링**: rate limit 히트율 대시보드 (너무 많이 걸리면 limit 조정 필요)
7. **Graceful degradation**: Rate Limiter 자체가 죽으면? → fail-open (통과) vs fail-close (차단)
