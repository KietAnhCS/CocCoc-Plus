Bản rút gọn dạng cây

Hai loại số liệu, hai vòng đời hoàn toàn khác nhau:

```
UsageAnalyticsService   số liệu SỬ DỤNG   sống trong RAM, mất khi khởi động lại, trần cứng mọi bảng
CorpusStats             số liệu CORPUS    tính lại MỘT lần mỗi khi chỉ mục đổi, không nằm trên đường request
```

Đường ghi — trình duyệt báo sự kiện lên:

```
POST /api/events   công khai, @Valid EventRequest{type, sessionId, query, resultCount, tookMs, url, position}
└─ EventController.record(request, authentication)
   ├─ username lấy từ NGỮ CẢNH BẢO MẬT (authentication.getName()), KHÔNG từ thân request
   │  ↳ để máy khách tự khai tên trong JSON thì bảng "ai dùng nhiều" giả mạo được
   │    bằng một dòng curl — endpoint này công khai
   ├─ type: "visit" | "search" | "click"  (trim + lowercase; @Size max 16)
   └─ UsageAnalyticsService
      ├─ recordVisit(sessionId, username)
      │  └─ touchSession
      │     ├─ trimTo(sessionId, MAX_SESSION_ID_CHARS 64)   ; null → BỎ sự kiện
      │     ├─ recordSessionInHour → hourNow().sessions.add  (trần MAX_SESSIONS_PER_HOUR 5 000)
      │     ├─ đã có → cập nhật lastSeenMillis
      │     └─ sessions.size() ≥ MAX_TRACKED_SESSIONS 20 000 → dropped++ và TRẢ null
      ├─ recordSearch(sessionId, username, query, resultCount, tookMs)
      │  ├─ searches++ , session.searches++ , hourNow().searches++
      │  ├─ username khác rỗng → bump(userSearches, …, MAX_TRACKED_USERS 5 000)
      │  ├─ resultCount ≤ 0 → zeroResultSearches++
      │  ├─ tookMs ≥ 0 → latencySumMs += , latencySamples++ ,
      │  │               latencyBuckets[bucketIndex(tookMs)]++
      │  │               LATENCY_BOUNDS_MS {10, 50, 100, 200, 500, 1000} → 7 khoảng
      │  └─ normalizeQuery(query) → bump(queryCounts, …, MAX_TRACKED_QUERIES 5 000)
      └─ recordClick(sessionId, username, url, position)
         ├─ trimTo(url, MAX_URL_CHARS 300) ; null → bỏ
         ├─ clicks++ , session.clicks++ , hourNow().clicks++
         ├─ linkStats chưa có khoá VÀ đã đầy MAX_TRACKED_LINKS 5 000 → dropped++ , THOÁT
         └─ stat.count++ ; position > 0 → positionSum += position , positionSamples++
            ↳ để tính VỊ TRÍ TRUNG BÌNH được nhấp — chỉ số nói xếp hạng có tốt không

   ⇒ mọi bảng đếm đều đi qua bump(table, key, cap):
      khoá đã có   → tăng
      bảng đã đầy  → dropped++ rồi BỎ, KHÔNG đuổi khoá cũ
      ↳ endpoint này CÔNG KHAI, nên trần cứng là thứ duy nhất ngăn một máy khách
        bịa vô số truy vấn/URL khác nhau làm phình bộ nhớ. Đổi lại: bảng đầy thì
        số liệu bị chệch — nên `truncated` được trả ra để giao diện nói thẳng điều đó.
```

Vòng đếm theo giờ — mảng vòng 24 ô, không phải danh sách nối dài:

```
hourNow()
├─ epochHour = clock.millis() / 3 600 000
├─ hour = hours[floorMod(epochHour, HOURS_TRACKED 24)]
└─ hour.epochHour ≠ epochHour → synchronized (kiểm tra hai lần) → hour.reset(epochHour)
   ↳ ô của "13 giờ hôm nay" tái dùng chính ô của "13 giờ hôm qua"; reset khi bước sang
     giờ mới chính là cách dữ liệu cũ bị quên — bộ nhớ CỐ ĐỊNH đúng 24 ô

hourlySeries(now)
└─ đi ngược 23 giờ → 0 giờ:
   fresh = (hour.epochHour == epochHour) ? số liệu thật : 0
   ↳ ô chưa bị chạm trong 24 giờ qua vẫn giữ dữ liệu của vòng TRƯỚC; kiểm tra `fresh`
     là thứ ngăn số liệu của hôm qua hiện thành số liệu hôm nay
```

Đường đọc — GET /api/admin/analytics (ROLE_ADMIN):

```
AdminAnalyticsController.dashboard(topN)
└─ AdminDashboard(generatedAt, traffic, crawl, index, accounts)
   ├─ traffic = UsageAnalyticsService.snapshot(topN)      limit = clamp(topN, 1, 100)
   │  ├─ duyệt sessions MỘT lượt: active (lastSeen ≥ now − ACTIVE_WINDOW_MINUTES 5),
   │  │  signedIn (username ≠ null), tổng thời lượng phiên
   │  ├─ clickThroughRate  = clicks / searches            (searches = 0 → 0.0)
   │  ├─ avgLatencyMs      = latencySumMs / latencySamples
   │  ├─ zeroResultRate    = zeroResultSearches / searches
   │  ├─ avgSessionMinutes = Σ(lastSeen − firstSeen) / 60 000 / số phiên
   │  ├─ hourly   → 24 điểm
   │  ├─ latency  → 7 khoảng
   │  ├─ topQueries / topUsers → MinHeap.topK(bảng đếm, limit)      ← O(n log k)
   │  ├─ topLinks  → kèm host và vị trí trung bình được nhấp
   │  ├─ topHosts  → gộp linkStats theo host rồi lại topK
   │  └─ truncated = dropped > 0 HOẶC bất kỳ bảng nào đã chạm trần
   ├─ crawl = SearchEngineFacade.getCorpusStats()         ← ĐÃ tính sẵn, chỉ đọc trường
   ├─ index = IndexStats(documents, terms, sizeBytes, cacheHitRate, scorer, bloomFilterBits)
   └─ accounts = AccountStats(total, admins, disabled, activeSessions)

POST /api/admin/analytics/reset
└─ đặt lại mọi bộ đếm, xoá mọi bảng, reset cả 24 ô giờ về Long.MIN_VALUE
```

CorpusStats — tính MỘT lượt duy nhất, trong refreshDerivedState:

```
CorpusStats.from(documents, docId → index.getDocLength(docId), zone)
├─ rỗng → CorpusStats.empty()
├─ MỘT vòng duyệt corpus, gom hết:
│  ├─ hostCounts.merge(hostOf(url), 1)
│  ├─ languageCounts.merge(language ?: "und", 1)
│  ├─ outlinks rỗng → dangling++ ; ngược lại totalOutlinks += size
│  ├─ countDistinctTargets(outlinks, BloomFilter seenTargets)
│  │  ├─ mightContain sai → add + distinct++
│  │  └─ TARGET_FILTER_FPR 1%, sức chứa = docs × ESTIMATED_LINKS_PER_PAGE 64,
│  │     trần MAX_FILTER_ITEMS 5 000 000
│  │     ↳ đếm XẤP XỈ có chủ đích: một HashSet chứa hàng triệu URL đích tốn hàng
│  │       trăm MB, còn con số này chỉ để hiển thị trên bảng điều khiển
│  ├─ lengths[i] = docLength (lấy từ CHỈ MỤC: số token, O(1))
│  │  ↳ KHÔNG đo bodyText: tài liệu trong chỉ mục không mang thân bài, đo ra 0 hết
│  └─ crawledAt → oldest / newest / perDay
├─ Arrays.sort(lengths) → medianDocLength = lengths[n/2]
├─ avgOutlinks = totalOutlinks / n ; avgDocLength = totalTokens / n
├─ topHosts   = MinHeap.topK(hostCounts, TOP_HOSTS 10)
└─ crawledPerDay = DAYS_TRACKED 14 ngày gần nhất
```

Ba nguyên tắc của tầng số liệu:

```
1. Không bao giờ tính lại trên đường request
   CorpusStats là trạng thái DẪN XUẤT, làm mới cùng lúc với chỉ mục. Một lượt duyệt
   toàn corpus (kèm giải nén thân bài) không được phép nằm trong một lần bấm nút.

2. Trần cứng ở mọi bảng, và nói thật khi chạm trần
   MAX_TRACKED_* + dropped + cờ `truncated`. Số liệu chệch mà biết mình chệch còn
   dùng được; số liệu chệch mà im lặng thì không.

3. Đếm bằng cấu trúc rẻ nhất đủ dùng
   LongAdder cho bộ đếm nóng, mảng vòng 24 ô cho chuỗi thời gian, MinHeap.topK cho
   bảng xếp hạng, BloomFilter cho phép đếm xấp xỉ số đích liên kết.
```
