# Wallet Concurrency & Locking Summary

本專案用來比較 Wallet 交易在高併發下的兩種控制方式：

- `WalletRowLockFacade`
- `WalletRedisLockFacade`

以及在 **MySQL** / **PostgreSQL** 上的表現差異。

---

## 1. 最新結論

根據目前最新壓測結果：

- **Wallet 主交易路徑仍建議以 `RowLock` 為主**
- **在 MySQL 內部比較，`RowLock` 明顯優於 `RedisLock`**
- **跨資料庫比較，`PostgreSQL` 整體吞吐通常優於 `MySQL`**

更精確地說：

- 高競爭下，`RedisLock` 可以把 success ratio 維持在不錯的區間
- 但同樣操作數下，通常會付出更長 `durationMs`、更高 `directAvgMs`、更高 `getLockAvgMs`、更高 `redisTotalAvgMs`
- 當競爭再往上推時，`RedisLock` 會開始出現明顯的 `operation timeout`
- 這些 timeout 的主因不是 DB 先撐不住，而是 **Redis `tryLock` 的 wait time 被耗盡**

---

## 2. 兩種方案的差異

### 2.1 `WalletRowLockFacade`

特性：

- 直接使用資料庫 transaction 與 row lock
- 鎖、資料、交易都在同一個一致性邊界
- 主要成本在 DB wait / update / transaction serialization

優點：

- 一致性模型直接
- 主路徑語意清楚
- 同樣操作數下通常更快
- 大多數壓測場景都有更高 TPS

缺點：

- 高競爭時 DB wait 仍會上升
- 鎖壓力集中在 DB

### 2.2 `WalletRedisLockFacade`

特性：

- 先拿 Redis lock，再進 DB transaction
- lock 在 DB 外部，transaction 在 DB 內部
- 主要成本在 lock 協調、等待、額外 hop、timeout

優點：

- 適合跨服務、跨資源的應用層協調
- 在某些高競爭場景可把 success ratio 維持在高位

缺點：

- 同樣操作數下通常更慢
- `getLockAvgMs` / `redisTotalAvgMs` 會隨競爭顯著放大
- 問題分散在 Redis / 應用 / DB 三層，排障較複雜
- 對這個 Wallet 主交易路徑來說，收益不足以抵消成本

---

## 3. 最新壓測判讀

### 3.1 MySQL：`RowLock` vs `RedisLock`

整體趨勢：

- `RowLock` 幾乎全段有更高 TPS
- `RowLock` 幾乎全段有更短 `durationMs`
- 多數核心場景 `directAvgMs` 也更低
- `RedisLock` 的 timeout 主要出現在高競爭熱門 key 場景

關鍵觀察：

- 在 `accounts=1`、`accounts=2` 這種極端競爭場景下，`RedisLock` 雖然能撐住不少成功請求，但 method 執行時間與 lock 等待會明顯放大
- 在 `accounts=10000` 這種低競爭 / 偏吞吐場景，`RedisLock` 幾乎只剩額外協調成本

結論：

- **MySQL 主路徑仍應以 `RowLock` 為主**

### 3.2 MySQL vs PostgreSQL

整體趨勢：

- 兩個 DB 的共同結論都一樣：`RowLock` 優於 `RedisLock`
- 但若只看資料庫吞吐能力，`PostgreSQL` 通常高於 `MySQL`

關鍵觀察：

- `accounts=10000` 這類分散場景中，PostgreSQL 的 `RowLock` TPS 通常最漂亮
- PostgreSQL 的 `RedisLock` 也常比 MySQL 的 `RedisLock` 更高，但仍不改變主路徑更適合 `RowLock` 的結論

結論：

- **若只看效能，PostgreSQL 通常優於 MySQL**
- **若看鎖策略，兩者都仍以 `RowLock` 為主**

---

## 4. `operation timeout` 的真正意義

這批結果中，`RedisLock` 出現 `operation timeout` 時，主因通常不是 DB update 撐不住，而是：

- 熱門 key 競爭太高
- 大量請求卡在 `tryLock(waitTime, leaseTime)` 等待
- 等待時間耗盡後，請求在進 DB 前就 timeout

因此這類 timeout 更接近：

- **lock wait timeout**

而不是：

- **database execution timeout**

判讀上要特別看：

- `getLockAvgMs`
- `redisTotalAvgMs`
- `directAvgMs`
- `operationTimeoutRatio`

而不能只看 success ratio。

---

## 5. `BATCH_CASH_IN` 的補充結論

`BATCH_CASH_IN` 與即時交易主路徑要分開看。

在這類批次場景：

- 若 `accounts` 足夠大、競爭被攤開，`RedisLock` 有機會接近 `RowLock`
- 某些組合下，TPS 甚至可以局部接近或略高

但要注意：

- 這不代表 `RedisLock` 適合 Wallet 熱點交易主路徑
- 一旦回到 `accounts=2` 之類熱門 key 場景，batch 一拉大，`operation timeout` 很快爆開

另外：

- `BATCH_CASH_IN` 更應該看 `directAvgMs`
- 因為這比較接近 method 實際執行時間
- 若 success ratio 掉很多，單看 `directAvgMs` 可能會失真，必須跟 `operationTimeoutRatio` 一起看

---

## 6. 建議

### 6.1 主交易路徑

- 使用 `RowLock`
- 讓一致性與 transaction 留在 DB 邊界內處理

### 6.2 Redis Lock 的定位

- 保留作為特殊協調工具
- 適合跨服務、跨資源、非單一 DB 邊界的場景
- 不建議作為 Wallet 核心交易主路徑的預設鎖策略

### 6.3 資料庫選型

- 若只看目前壓測吞吐，`PostgreSQL` 整體表現通常更好
- 但不論 MySQL 或 PostgreSQL，主路徑鎖策略仍建議以 `RowLock` 為主

---

## 7. 一句話版本

**最新壓測結果顯示：Wallet 主交易路徑仍應以 `RowLock` 為主；`RedisLock` 在高競爭下會把成本轉成 lock wait 與 timeout，而不是帶來更好的主路徑效益。**

---

## 8. 併發控制策略與核心實作

專案中主要實現了以下兩種錢包交易門面（Facade）：

### 8.1 行鎖門面 (`WalletRowLockFacade`)
* **核心更新語句**：
  ```sql
  UPDATE wallet SET balance = balance + ? WHERE id = ?
  UPDATE wallet SET balance = balance - ? WHERE id = ? AND balance >= ?
  ```
* **機制**：不使用應用層鎖。所有併發請求直接送達資料庫，利用資料庫內置的**行鎖（Row Lock）**進行事務排隊。
* **特性**：無額外網絡開銷（無需訪問 Redis），無應用層輪詢等待。

### 8.2 Redis 分布式鎖門面 (`WalletRedisLockFacade`)
* **機制**：先在應用層透過 Redis 取得互斥鎖，取得鎖後再進入資料庫 transaction，完成後於 `finally` 釋放鎖。
* **特性**：
  * **將併發壓力轉移至應用層 / Redis**
  * 對同一帳戶（`userId`）的修改在資料庫層面近似**序列化執行**
  * 每次交易需額外付出 Redis lock acquire / release 的成本
  * 在高競爭下，問題常體現在 lock wait 與 timeout，而不是 DB row update 本身

---

## 9. 核心效能差異深層原因分析

### 9.1 為何在 MySQL 下，Row Lock 通常明顯快於 Redis Lock？

1. **InnoDB 的 row lock 與 transaction 路徑非常短**
   - MySQL InnoDB 對高頻單行更新的處理相對直接。
   - row lock、版本控制、更新都在 DB 內部完成。

2. **Row Lock 沒有額外外部協調 hop**
   - 請求直接進 DB transaction。
   - 不需要先經過 Redis 再回 DB。

3. **Redis Lock 額外增加了 acquire / wait / release 成本**
   - 高競爭下，大量請求卡在 `tryLock(waitTime, leaseTime)`。
   - 即使最後成功，也會把 method 執行時間拉長。

4. **這批最新結果顯示 timeout 主因是 lock wait，不是 DB execution**
   - `updateBalanceAvgMs` 沒有跟著同等幅度暴衝
   - 但 `getLockAvgMs`、`redisTotalAvgMs` 會明顯放大
   - 因此瓶頸主要在 Redis lock wait

### 9.2 為何 PostgreSQL 的整體吞吐通常比 MySQL 更高？

從目前這批完整對照資料來看：

- `accounts=10000` 這類分散競爭場景下
- PostgreSQL 的 `RowLock` TPS 通常比 MySQL 更高

這代表在本專案這組 workload 下：

- PostgreSQL 在中高併發、分散更新的情境裡，整體吞吐能力更好
- 但這不改變主路徑仍然是 `RowLock` 優於 `RedisLock` 的事實

也就是說：

- **資料庫選型層面**：PostgreSQL 較漂亮
- **鎖策略層面**：兩邊都仍以 `RowLock` 為主

### 9.3 為何 `BATCH_CASH_IN` 有時看起來 Redis Lock 不差？

因為 `BATCH_CASH_IN` 跟即時交易主路徑不是同一類 workload：

- 批次寫入可以把競爭攤開
- `accounts` 很大時，熱門 key 問題不明顯
- 某些情況下 Redis Lock 的 TPS 可以接近甚至局部略高

但這種結論不能直接套回主交易路徑，原因是：

- 一旦回到 `accounts=2` 這種熱門 key 場景
- `batchSize` 一變大
- `operationTimeoutRatio` 很快就會爆開

所以：

- `BATCH_CASH_IN` 適合單獨看
- 不應拿來反推即時 Wallet 熱點交易的最佳鎖策略

---

## 10. 延伸探討：移至內網環境（引入 RTT）的影響

若 Redis 與資料庫不在本機，而是在內網另一台主機，通常會引入額外 RTT（Round Trip Time）。

這件事對 `RowLock` 與 `RedisLock` 的影響並不對稱，因為兩者的臨界路徑不同。

### 10.1 臨界路徑（Critical Path）上的 RTT 放大

當網路存在延遲時，**鎖持有時間**會直接受到 RTT 影響。

#### Row Lock

對 `RowLock` 而言，請求進入 transaction 後，主要流程是：

- 進 DB
- 讀取 / 更新資料
- 寫 history
- commit

簡化來看，可以把它想成：

`DB request -> DB execute -> DB commit`

因此可近似理解為：

- **RowLock 持有時間 ≈ DB RTT × 幾次必要往返 + DB 執行時間**

重點是：

- lock 的排隊與移交仍在 DB 內部完成
- 即使有 RTT，真正的 lock handoff 不需要再多經過一層外部系統

#### Redis Lock

對 `RedisLock` 而言，流程會變成：

- 先去 Redis `tryLock`
- 拿到 lock 後進 DB transaction
- DB 做更新
- 回來 unlock Redis

簡化來看，可以把它想成：

`Redis lock -> DB request -> DB execute -> DB commit -> Redis unlock`

因此可近似理解為：

- **RedisLock 持有時間 ≈ Redis RTT × 2 + DB RTT × 幾次必要往返 + 執行時間**

也就是說，Redis Lock 會天然比 Row Lock 多出：

- lock acquire 的 RTT
- unlock 的 RTT
- 高競爭下 retry / re-acquire 的 RTT

### 10.2 鎖遞交（Lock Handoff）差異

真正差距更大的地方，不只是多一次或兩次網路呼叫，而是 **lock handoff 的位置不同**。

#### Row Lock 的 handoff

Row Lock 的排隊與喚醒主要在 DB 內部處理：

- 前一個 transaction commit
- DB 內部把等待者喚醒
- 下一個 transaction 繼續競爭或取得 row lock

也就是說：

- **handoff 主要發生在資料庫記憶體內部**
- 不需要再多經過一次外部網路往返

#### Redis Lock 的 handoff

Redis Lock 的 handoff 則比較像：

- A 釋放 lock
- unlock 請求先送到 Redis
- B 再次 `tryLock`
- B 的拿鎖結果再由 Redis 回傳

若是高競爭情境，還會加上：

- retry
- backoff / wait
- 再試一次 `tryLock`

也就是說：

- **每一次 handoff 都更容易受 RTT 與等待策略影響**
- 一旦 thread 很高，RTT 會直接被放大到 `getLockAvgMs` / `redisTotalAvgMs`

### 10.3 為什麼 RTT 會讓 Redis Lock 更容易出現 timeout？

你現在這批壓測已經顯示：

- `operation timeout` 的主因通常不是 DB 撐不住
- 而是 `tryLock(waitTime, leaseTime)` 的 **wait time 被耗盡**

如果再把 Redis 移到內網主機：

- 每次 acquire 的 RTT 變高
- 每次 retry 的 RTT 變高
- 每次 handoff 的 RTT 也變高

那麼在 **相同 waitTime 設定** 下：

- 可以容納的競爭次數會更少
- request 更容易在進 DB 前就 timeout

因此在內網環境下，`RedisLock` 的 timeout 風險通常只會更高，不會更低。

### 10.4 這對目前結論代表什麼？

如果本機壓測就已經看到：

- `getLockAvgMs` 被放大
- `redisTotalAvgMs` 被放大
- 高競爭時開始出現 `operation timeout`

那麼放到內網後，這些現象通常只會更明顯。

所以：

- **本機環境若 Redis Lock 都沒有贏，搬到有 RTT 的環境後通常更不容易贏**
- `RowLock` 與 `RedisLock` 的差距，在多數情況下只會被進一步放大

---

## 11. 常見誤區澄清

### 11.1 用了 Redis Lock，資料庫就沒有 row lock 了嗎？

不是。

即使有 Redis Lock：

- 進到 DB transaction 時
- `UPDATE` / `SELECT ... FOR UPDATE` 類操作依然會使用資料庫自己的鎖機制

Redis Lock 的真正作用不是消除 DB lock，而是：

- 把競爭提早在應用層攔住
- 盡量減少資料庫內部同一筆資源的爭搶

### 11.2 為什麼 timeout 不等於 DB 撐不住？

這批結果裡，`operation timeout` 更接近：

- **lock wait timeout**

而不是：

- **database execution timeout**

判斷依據是：

- `getLockAvgMs` / `redisTotalAvgMs` 很高
- `updateBalanceAvgMs` 並沒有同等幅度地跟著放大

所以這些 timeout 多半是卡在進 DB 前的 Redis lock wait。

---

## 12. 若 Redis 要上正式環境，建議怎麼做？

如果真的要使用 Redis lock，建議至少做到：

### 12.1 Redis 與快取分離

- 鎖專用 Redis
- 快取專用 Redis

避免快取流量拖垮 lock latency。

### 12.2 強化觀測

至少觀測：

- `getLockAvgMs`
- `redisTotalAvgMs`
- `directAvgMs`
- `operationTimeoutRatio`
- `operationTimeoutCount`

### 12.3 不把 Redis Lock 當預設主路徑

Redis Lock 比較適合：

- 跨服務
- 跨資源
- 非單一 DB transaction 邊界

而不是這種最終一致性仍明確落在單一 DB 寫入上的 Wallet 主交易路徑。

---

## 13. 多業務場景共用 Redis 的風險

如果把 Wallet lock 跟大量 cache / 其他業務鎖共用同一個 Redis，通常還會額外產生 **Noisy Neighbor** 問題：

- 快取流量阻塞 Redis 命令處理
- 慢查詢或大 value 操作拖慢 lock 命令
- CPU 抖動讓 lock latency 不穩定

對 Wallet 這種延遲敏感的交易來說，結果通常是：

- `getLockAvgMs` 更不穩
- `redisTotalAvgMs` 更容易暴增
- `operation timeout` 更容易出現

因此如果真的要上 Redis lock，至少應該：

- **快取 Redis 與鎖 Redis 分離**
- 讓鎖用 Redis 盡量維持低延遲、低抖動
