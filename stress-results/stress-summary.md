# Wallet 壓力測試總結

## 1. 測試依據

本次總結分成兩部分，因此使用兩組資料：

- **MySQL 的 RowLock vs RedisLock**：使用最新 MySQL 結果與近期補測
  - `stress-results/mysql-20260630-210119.csv`
  - `stress-results/mysql-20260629-214815.csv`
  - `stress-results/mysql-20260629-143504.csv`
- **MySQL vs Postgres**：使用最新一組可直接對照的完整結果
  - `stress-results/mysql-20260629-115912.csv`
  - `stress-results/postgres-20260629-125819.csv`

原因是：
- `mysql-20260630-210119.csv` 是目前最新 MySQL 結果，補了 `threads=20 / 200 / 500`，也加入 `BATCH_CASH_IN` 場景。
- `mysql-20260629-214815.csv` 補的是 `accounts=2` 的更高 thread 與更大 totalOps。
- `mysql-20260629-143504.csv` 補的是 `accounts=1` 的極端單 key 場景。
- 這些補測都沒有同時段 Postgres 對照，因此跨資料庫比較仍以最新完整成對資料為準。

## 2. 結論先講

- **MySQL 內部比較**：最新補測與完整壓測都一致顯示，`RowLock` 仍優於 `RedisLock`；Redis Lock 的主要代價是更長等待、更低 TPS，以及高 thread 下被放大的 lock 協調成本。
- **MySQL vs Postgres**：在可直接對照的完整資料中，`Postgres` 整體吞吐仍高於 `MySQL`，尤其在 `RowLock` 場景更明顯。
- **共同結論**：不論 MySQL 或 Postgres，Wallet 主交易路徑仍比較適合以 `RowLock` 作為主方案，而不是 `RedisLock`。

---

## 3. MySQL：RowLock vs RedisLock

### 3.1 最新補測 A：`accounts=2 / totalOps=10000` 與 `accounts=10000 / totalOps=100000`

**依據**
- `stress-results/mysql-20260630-210119.csv`

#### CASH `accounts=2 totalOps=10000`

**RowLock**
- `20 threads`：TPS `738.07`，`durationMs = 13548`，`directAvgMs = 26.03`，successRatio `100.00%`
- `200 threads`：TPS `746.72`，`durationMs = 13391`，`directAvgMs = 243.44`，successRatio `100.00%`
- `500 threads`：TPS `746.55`，`durationMs = 13394`，`directAvgMs = 583.88`，successRatio `100.00%`

**RedisLock**
- `20 threads`：TPS `224.42`，`durationMs = 44558`，`directAvgMs = 79.44`，successRatio `100.00%`
- `200 threads`：TPS `224.19`，`durationMs = 44547`，`directAvgMs = 706.28`，successRatio `99.87%`，`operationTimeoutRatio = 0.13%`
- `500 threads`：TPS `241.07`，`durationMs = 40970`，`directAvgMs = 1674.64`，successRatio `98.77%`，`operationTimeoutRatio = 1.23%`

**解讀**
- 最新這組 `accounts=2` 補測很穩定地重複前面的結論：RowLock TPS 約在 `738 ~ 747`，RedisLock 約在 `224 ~ 241`。
- Redis Lock 在 `500 threads` 才開始明顯出現 timeout，但即使 `20 threads` 無 timeout，TPS 仍遠低於 RowLock。
- `directAvgMs` 也很清楚顯示 method 執行時間差距：`500 threads` 時 Redis Lock 約是 RowLock 的 `2.9x`。
- 這表示 Redis Lock 的主要問題不只是 timeout，而是**即使成功，也要付出較高 lock 協調成本**。

#### CASH `accounts=10000 totalOps=100000`

**RowLock**
- `20 threads`：TPS `3940.22`，`durationMs = 25379`，`directAvgMs = 4.49`
- `200 threads`：TPS `9928.65`，`durationMs = 10071`，`directAvgMs = 19.30`
- `500 threads`：TPS `9266.54`，`durationMs = 10791`，`directAvgMs = 52.16`

**RedisLock**
- `20 threads`：TPS `2737.89`，`durationMs = 36524`，`directAvgMs = 6.56`
- `200 threads`：TPS `6518.68`，`durationMs = 15340`，`directAvgMs = 29.29`
- `500 threads`：TPS `6565.10`，`durationMs = 15232`，`directAvgMs = 73.85`

**解讀**
- 在大帳戶低競爭場景，RowLock 仍明顯更快。
- 連 `directAvgMs` 也全段較低，代表即使 method 本身的執行時間，RowLock 也比較短。
- Redis Lock 在這裡沒有 timeout，但依然整體較慢，證明額外 hop 成本本身就存在。

#### CASH `accounts=10000 totalOps=5000000 / threads=1000`

**RowLock**
- TPS `8254.60`
- `durationMs = 605722`
- `directAvgMs = 120.13`
- successRatio `100.00%`

**RedisLock**
- TPS `6241.78`
- `durationMs = 801053`
- `directAvgMs = 157.93`
- successRatio `100.00%`

**解讀**
- 即使在超大操作量下，Redis Lock 仍沒有變成更優方案。
- 這組結果很有代表性：**當競爭已經分散，Redis Lock 只剩額外協調成本**。
- `directAvgMs` 也持續高於 RowLock，不只是總時間比較長而已。

### 3.2 最新補測 B：`accounts=2` 的更高 thread / 更大 totalOps

**依據**
- `stress-results/mysql-20260629-214815.csv`

#### CASH `accounts=2 totalOps=10000`

**RowLock**
- `100 threads`：TPS `773.08`，`durationMs = 12935`，`directAvgMs = 124.22`，successRatio `100.00%`
- `500 threads`：TPS `708.25`，`durationMs = 14119`，`directAvgMs = 578.02`，successRatio `100.00%`
- `1000 threads`：TPS `651.29`，`durationMs = 15354`，`directAvgMs = 1246.54`，successRatio `100.00%`

**RedisLock**
- `100 threads`：TPS `222.78`，`durationMs = 44887`，`directAvgMs = 392.28`，successRatio `100.00%`
- `500 threads`：TPS `235.74`，`durationMs = 41579`，`directAvgMs = 1535.30`，successRatio `98.02%`，`operationTimeoutRatio = 1.98%`
- `1000 threads`：TPS `246.05`，`durationMs = 36626`，`directAvgMs = 2367.58`，successRatio `90.12%`，`operationTimeoutRatio = 9.88%`

#### CASH `accounts=2 totalOps=100000 / threads=1000`

**RowLock**
- TPS `596.16`
- `durationMs = 167741`
- `directAvgMs = 1562.45`
- successRatio `100.00%`

**RedisLock**
- TPS `241.88`
- `durationMs = 353468`
- `directAvgMs = 2463.13`
- successRatio `85.50%`
- `operationTimeoutRatio = 14.50%`
- `operationTimeoutCount = 14504`

**解讀**
- 這組最能證明：當競爭再往上推，Redis Lock 的等待最終會轉成大量 `operation timeout`。
- 也就是說，timeout 主因不是 DB 先撐不住，而是 **`tryLock` 的 wait time 被耗盡**。
- 同時 `directAvgMs` 也一路高於 RowLock，說明成功請求本身的 method 執行時間也更重。

### 3.3 最新補測 C：`accounts=1 / totalOps=10000 / threads=20`

**依據**
- `stress-results/mysql-20260629-143504.csv`

**RowLock**
- `durationMs = 31717`
- TPS `315.29`
- `directAvgMs = 62.76`
- successRatio `100.00%`

**RedisLock**
- `durationMs = 101399`
- TPS `96.73`
- `directAvgMs = 160.07`
- successRatio `98.08%`
- `operationTimeoutRatio = 1.92%`
- `retries = 9342`
- `operationTimeoutCount = 192`

**解讀**
- 在最極端單 key 場景，Redis Lock 花了約 `3.2x` 的總時間。
- `directAvgMs` 約是 RowLock 的 `2.5x`，method 執行時間本身也更長。
- `retries = 9342` 清楚說明競爭主要是先變成等待，最後才有一部分轉成 timeout。

### 3.4 新增場景：`BATCH_CASH_IN`

**依據**
- `stress-results/mysql-20260630-210119.csv`

#### `accounts=2 totalOps=100000 threads=100`

**batchSize=100**
- `RowLock`：TPS `2898.43`，`directAvgMs = 3244.38`，successRatio `100.00%`
- `RedisLock`：TPS `2685.11`，`directAvgMs = 2990.97`，successRatio `98.70%`，`operationTimeoutRatio = 1.30%`

**batchSize=1000**
- `RowLock`：TPS `2975.66`，`directAvgMs = 17122.83`，successRatio `100.00%`
- `RedisLock`：TPS `2785.05`，`directAvgMs = 4461.58`，successRatio `24.00%`，`operationTimeoutRatio = 76.00%`

#### `accounts=10000 totalOps=500000 threads=500`

**batchSize=100**
- `RowLock`：TPS `32185.48`，`directAvgMs = 1484.27`
- `RedisLock`：TPS `31730.92`，`directAvgMs = 1510.74`

**batchSize=1000**
- `RowLock`：TPS `32614.68`，`directAvgMs = 14343.81`
- `RedisLock`：TPS `33449.17`，`directAvgMs = 14128.74`，但 successRatio `98.00%`，`operationTimeoutRatio = 2.00%`

**解讀**
- `BATCH_CASH_IN` 更應該看 `directMs`，因為這代表 method 實際執行時間。
- 當 `accounts=10000`、競爭夠分散時，Redis Lock 在某些批次大小下 TPS 與 `directAvgMs` 可以接近甚至略好。
- 但一旦回到 `accounts=2` 這種熱門 key 場景，batch 變大後 timeout 很快爆開。
- 特別是 `batchSize=1000 / accounts=2`：雖然 `directAvgMs` 看起來比 RowLock 小很多，但這是建立在大量請求直接 timeout、只有少數成功請求完成的前提，不能解讀成 Redis Lock 真正更有效率。

### 3.5 MySQL 小結

**MySQL 的結論很一致：**
- `RowLock` 幾乎在所有核心場景都有更高 TPS、更短 `durationMs`，而且多數地方 `directAvgMs` 也更低。
- `RedisLock` 在極端競爭下可以把 success ratio 維持在高位，但 thread 與 totalOps 再往上推後，最終仍可能轉成大量 `operation timeout`。
- timeout 的主因不是 DB 先撐不住，而是 **Redis `tryLock` 的等待時間被耗盡**。
- `BATCH_CASH_IN` 說明 Redis Lock 在低競爭、批次化場景下可以接近可用，但這不改變 Wallet 熱點交易主路徑仍以 `RowLock` 為主的結論。

---

## 4. MySQL vs Postgres

### 4.1 比較範圍

這一段只使用最新一組**可直接對照的完整資料**：
- `stress-results/mysql-20260629-115912.csv`
- `stress-results/postgres-20260629-125819.csv`

### 4.2 共同趨勢

不論 MySQL 或 Postgres，趨勢一致：
- `RowLock` 都優於 `RedisLock`
- 高競爭下 `RedisLock` 都會把 lock 成本放大
- 低競爭下 `RedisLock` 都沒有吞吐優勢

差別在於：
- `Postgres` 整體 TPS 通常更高
- 尤其 `accounts=100`、`accounts=10000` 這類分散場景，Postgres 優勢更明顯
- 在高競爭場景，Postgres 的 `RedisLock` successRatio 通常也比 MySQL 更漂亮一些

### 4.3 RowLock：Postgres 整體更強

#### CASH `accounts=10000 totalOps=100000`

**MySQL RowLock**
- TPS：`2032.74 / 7925.37 / 9841.00 / 9058.16`

**Postgres RowLock**
- TPS：`4389.32 / 16652.31 / 14293.54 / 12932.85`

#### TRANSFER `accounts=10000 totalOps=100000`

**MySQL RowLock**
- TPS：`1477.65 / 4936.67 / 5464.11 / 5076.71`

**Postgres RowLock**
- TPS：`1876.12 / 9234.21 / 8036.69 / 7177.37`

**解讀**
- 在完整對照資料中，Postgres RowLock 幾乎全面領先 MySQL RowLock。
- 代表若只看資料庫吞吐能力，Postgres 表現更漂亮。

### 4.4 RedisLock：Postgres 也較好，但仍不改主結論

#### CASH `accounts=2 totalOps=10000`

**MySQL RedisLock**
- TPS：`236.50 / 260.29 / 260.46 / 276.26`
- successRatio：`95.87% ~ 96.67%`

**Postgres RedisLock**
- TPS：`449.06 / 466.60 / 488.95 / 486.52`
- successRatio：`99.39% ~ 99.95%`

#### TRANSFER `accounts=10000 totalOps=100000`

**MySQL RedisLock**
- TPS：`831.17 / 2972.88 / 3627.65 / 3295.47`

**Postgres RedisLock**
- TPS：`1401.85 / 4161.12 / 4134.18 / 3806.26`

**解讀**
- Postgres 的 RedisLock 整體也比 MySQL RedisLock 高。
- 但若拿來跟各自的 RowLock 比，RedisLock 仍不是主路徑最佳方案。

### 4.5 MySQL vs Postgres 小結

**資料庫層面的結論：**
- `Postgres` 整體吞吐通常高於 `MySQL`。
- 但不論是 MySQL 或 Postgres，主路徑鎖策略的最佳選擇仍然是 `RowLock`。
- 所以架構判斷重點不是「哪個 DB 比較適合 Redis Lock」，而是「這個場景本身就更適合 RowLock」。

---

## 5. 最終建議

### 5.1 MySQL 的 RowLock vs RedisLock
- **建議選 RowLock 當主方案**。
- Redis Lock 可作為特殊協調工具，但不適合當 Wallet 核心交易主路徑的預設鎖策略。

### 5.2 MySQL vs Postgres
- **若只看壓測結果，Postgres 的整體吞吐更漂亮**。
- 但不論 MySQL 或 Postgres，主路徑鎖策略仍建議以 `RowLock` 為主。

一句話版本：

**最新 `2026-06-30` MySQL 補測與 `2026-06-29` 成對資料共同顯示：在 MySQL 內部比較時，RowLock 明顯優於 Redis Lock；而高競爭下 Redis Lock 的 timeout 主因是 `tryLock` 等待時間耗盡，不是 DB 先撐不住。跨資料庫比較時，Postgres 整體又優於 MySQL。但兩邊共同結論一致：Wallet 主交易路徑仍應以 RowLock 為主，而不是 Redis Lock。**
