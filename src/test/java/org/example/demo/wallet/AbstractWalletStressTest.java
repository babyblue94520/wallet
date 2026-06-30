package org.example.demo.wallet;

import lombok.extern.slf4j.Slf4j;
import org.example.demo.domain.wallet.facade.WalletFacade;
import org.example.demo.domain.wallet.facade.WalletRedisLockFacade;
import org.example.demo.domain.wallet.facade.WalletRowLockFacade;
import org.example.demo.domain.wallet.facade.TransferFacade;
import org.example.demo.domain.wallet.facade.TransferRedisLockFacade;
import org.example.demo.domain.wallet.facade.TransferRowLockFacade;
import org.example.demo.domain.wallet.infrastructure.exception.InsufficientBalanceException;
import org.example.demo.domain.wallet.infrastructure.exception.WalletOperationTimeoutException;
import org.example.demo.domain.wallet.infrastructure.vo.CashRequest;
import org.example.demo.domain.wallet.infrastructure.vo.TransferRequest;
import org.example.demo.domain.wallet.service.WalletFacadeStats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractWalletStressTest {
    protected static final int[] THREADS = {20, 200, 500};
    protected static final StressScenario[] CASH_SCENARIOS = {
            new StressScenario(StressOperation.CASH, 1, 2, 1000, THREADS, 10000)
            ,new StressScenario(StressOperation.CASH, 1, 10000, 1000, THREADS, 100000)
            ,new StressScenario(StressOperation.CASH, 1, 10000, 1000, new int[]{1000}, 5000000)
    };
    protected static final StressScenario[] BATCH_CASH_IN_SCENARIOS = {
            new StressScenario(StressOperation.BATCH_CASH_IN, 1, 2, 1000, new int[]{100}, 100000, 100),
            new StressScenario(StressOperation.BATCH_CASH_IN, 1, 2, 1000, new int[]{100}, 100000, 1000),
            new StressScenario(StressOperation.BATCH_CASH_IN, 1, 10000, 1000, new int[]{500}, 500000, 100),
            new StressScenario(StressOperation.BATCH_CASH_IN, 1, 10000, 1000, new int[]{500}, 500000, 1000),
    };
    
    protected static final StressScenario[] TRANSFER_SCENARIOS = {
            new StressScenario(StressOperation.TRANSFER, 2, 10, 1000, THREADS, 10000),
            new StressScenario(StressOperation.TRANSFER, 2, 10000, 1000, THREADS, 100000)
    };
    private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    protected enum StressOperation {
        CASH,
        BATCH_CASH_IN,
        TRANSFER
    }

    protected abstract String getDbType();

    protected Path CSV_PATH;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected TransactionTemplate transactionTemplate;


    @Autowired
    WalletRowLockFacade walletRowLockFacade;

    @Autowired
    WalletRedisLockFacade walletRedisLockFacade;

    @Autowired
    TransferRowLockFacade transferRowLockFacade;

    @Autowired
    TransferRedisLockFacade transferRedisLockFacade;

    @Autowired
    WalletFacadeStats walletFacadeStats;

    @BeforeAll
    void initCsvPath() {
        CSV_PATH = timestampedCsvPath(getDbType());
    }

    @Order(1)
    @Test
    void rowLock_cash_scenarios() throws Exception {
        runCashScenarioRounds(CSV_PATH, "RowLock", walletRowLockFacade);
    }

    @Order(2)
    @Test
    void redisLock_cash_scenarios() throws Exception {
        runCashScenarioRounds(CSV_PATH, "RedisLock", walletRedisLockFacade);
    }

    @Order(3)
    @Test
    void rowLock_batch_cash_in_scenarios() throws Exception {
        runNamedScenarioRounds(CSV_PATH, "RowLock", walletRowLockFacade, transferRowLockFacade, BATCH_CASH_IN_SCENARIOS);
    }

    @Order(4)
    @Test
    void redisLock_batch_cash_in_scenarios() throws Exception {
        runNamedScenarioRounds(CSV_PATH, "RedisLock", walletRedisLockFacade, transferRedisLockFacade, BATCH_CASH_IN_SCENARIOS);
    }

    @Order(5)
    @Test
    void rowLock_transfer_scenarios() throws Exception {
        runNamedScenarioRounds(CSV_PATH, "RowLock", walletRowLockFacade, transferRowLockFacade, TRANSFER_SCENARIOS);
    }

    @Order(6)
    @Test
    void redisLock_transfer_scenarios() throws Exception {
        runNamedScenarioRounds(CSV_PATH, "RedisLock", walletRedisLockFacade, transferRedisLockFacade, TRANSFER_SCENARIOS);
    }

    protected long[] createAccountsOnce(int count, long initialBalance) {
        long startedAt = System.currentTimeMillis();
        jdbcTemplate.update("TRUNCATE TABLE wallet_history");
        jdbcTemplate.update("TRUNCATE TABLE wallet");
        long[] result = count > 1000
                ? createAccountsParallel(count, initialBalance, 10)
                : createAccountsSequential(count, initialBalance);
        System.out.printf("init accounts [ %d ms ]\n", System.currentTimeMillis() - startedAt);
        return result;
    }

    protected long[] createAccountsSequential(int count, long initialBalance) {
        return transactionTemplate.execute(status -> {
            long[] ids = new long[count];
            for (int i = 0; i < count; i++) {
                long id = ids[i] = i + 1;
                jdbcTemplate.update("INSERT INTO wallet(id, balance) VALUES (?, ?)", id, initialBalance);
            }
            return ids;
        });
    }

    protected long[] createAccountsParallel(int count, long initialBalance, int threads) {
        long[] ids = new long[count];
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, count));
        CountDownLatch done = new CountDownLatch(Math.min(threads, count));
        int batchSize = (count + threads - 1) / threads;

        try {
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                int startIndex = threadIndex * batchSize;
                int endIndex = Math.min(count, startIndex + batchSize);
                if (startIndex >= endIndex) {
                    done.countDown();
                    continue;
                }

                pool.submit(() -> {
                    try {
                        transactionTemplate.executeWithoutResult(status -> {
                            for (int i = startIndex; i < endIndex; i++) {
                                long id = ids[i] = i + 1L;
                                jdbcTemplate.update("INSERT INTO wallet(id, balance) VALUES (?, ?)", id, initialBalance);
                            }
                        });
                    } finally {
                        done.countDown();
                    }
                });
            }

            awaitLatch(done);
            return ids;
        } finally {
            pool.shutdownNow();
        }
    }

    protected void resetRoundState(long initialBalance) {
        jdbcTemplate.update("TRUNCATE TABLE wallet_history");
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE wallet SET balance = ?, version = 0", initialBalance);
        });
    }

    protected StressRunResult stressRun(
            String scenarioName,
            WalletFacade walletCashFacade,
            TransferFacade transferFacade,
            long[] accountIds,
            StressScenario scenario,
            int threads
    ) throws Exception {
        resetRoundState(scenario.balance());
        resetStats();
        int opsPerThread = scenario.totalOps() / threads;
        int remainder = scenario.totalOps() % threads;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        AtomicLong success = new AtomicLong();
        AtomicLong insufficientBalance = new AtomicLong();
        AtomicLong operationTimeout = new AtomicLong();
        AtomicLong otherErrors = new AtomicLong();
        AtomicLong startedAtNs = new AtomicLong();
        AtomicLong finishedAtNs = new AtomicLong();
        ConcurrentLinkedQueue<Long> successDirectLatencyMs = new ConcurrentLinkedQueue<>();

        try {
            CompletableFuture<?>[] futures = new CompletableFuture[threads];
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                int threadOps = opsPerThread + (threadIndex < remainder ? 1 : 0);
                int currentThreadIndex = threadIndex;
                futures[threadIndex] = CompletableFuture.runAsync(() -> {
                    SplittableRandom rnd = new SplittableRandom(System.nanoTime() ^ (currentThreadIndex * 31L));
                    List<BatchCashCommand> batchCashCommands = scenario.operation() == StressOperation.BATCH_CASH_IN
                            ? buildBatchCashCommands(accountIds, threadOps, scenario.batchSize(), rnd)
                            : List.of();
                    ready.countDown();
                    awaitLatch(start);

                    if (scenario.operation() == StressOperation.BATCH_CASH_IN) {
                        for (BatchCashCommand batchCashCommand : batchCashCommands) {
                            try {
                                long directStartedAtNs = System.nanoTime();
                                walletCashFacade.batchCashIn(batchCashCommand.requests());
                                success.addAndGet(batchCashCommand.requests().size());
                                long directMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - directStartedAtNs));
                                successDirectLatencyMs.add(directMs);
                            } catch (InsufficientBalanceException e) {
                                insufficientBalance.addAndGet(batchCashCommand.requests().size());
                            } catch (WalletOperationTimeoutException e) {
                                operationTimeout.addAndGet(batchCashCommand.requests().size());
                            } catch (RuntimeException e) {
                                log.info(e.getMessage());
                                otherErrors.addAndGet(batchCashCommand.requests().size());
                            }
                        }
                    } else {
                        for (int i = 0; i < threadOps; i++) {
                            try {
                                long directStartedAtNs = System.nanoTime();
                                if (scenario.operation() == StressOperation.TRANSFER) {
                                    int payerIndex = rnd.nextInt(accountIds.length);
                                    int payeeIndex = rnd.nextInt(accountIds.length - 1);
                                    if (payeeIndex >= payerIndex) {
                                        payeeIndex++;
                                    }
                                    transferFacade.transfer(new TransferRequest(
                                            accountIds[payerIndex],
                                            accountIds[payeeIndex],
                                            1L + rnd.nextInt(100)
                                    ));
                                } else {
                                    long accountId = accountIds[rnd.nextInt(accountIds.length)];
                                    long amount = 1L + rnd.nextInt(100);
                                    boolean doCashIn = rnd.nextBoolean();
                                    if (doCashIn) {
                                        walletCashFacade.cashIn(new CashRequest(accountId, amount));
                                    } else {
                                        walletCashFacade.cashOut(new CashRequest(accountId, amount));
                                    }
                                }
                                long directMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - directStartedAtNs));
                                success.incrementAndGet();
                                successDirectLatencyMs.add(directMs);
                            } catch (InsufficientBalanceException e) {
                                insufficientBalance.incrementAndGet();
                            } catch (WalletOperationTimeoutException e) {
                                operationTimeout.incrementAndGet();
                            } catch (RuntimeException e) {
                                log.info(e.getMessage());
                                otherErrors.incrementAndGet();
                            }
                        }
                    }
                }, pool);
            }

            ready.await(30, TimeUnit.SECONDS);
            startedAtNs.set(System.nanoTime());
            start.countDown();
            CompletableFuture.allOf(futures).get(15, TimeUnit.MINUTES);
            finishedAtNs.set(System.nanoTime());
        } finally {
            pool.shutdownNow();
        }

        long durationNs = finishedAtNs.get() > 0 ? (finishedAtNs.get() - startedAtNs.get()) : 0;
        Duration duration = Duration.ofNanos(Math.max(durationNs, 1));
        long total = success.get() + insufficientBalance.get() + operationTimeout.get() + otherErrors.get();
        long effectiveSuccess = success.get() + insufficientBalance.get();
        double tps = effectiveSuccess / (duration.toNanos() / 1_000_000_000.0);
        double successRatio = ratio(effectiveSuccess, total);
        double insufficientBalanceRatio = ratio(insufficientBalance.get(), total);
        double operationTimeoutRatio = ratio(operationTimeout.get(), total);
        double otherErrorsRatio = ratio(otherErrors.get(), total);
        LatencySummary insertLatency = new LatencySummary(
                walletFacadeStats.insertMinMs(),
                walletFacadeStats.insertMidMs(),
                walletFacadeStats.insertMaxMs(),
                walletFacadeStats.insertAvgMs()
        );
        LatencySummary updateBalanceLatency = new LatencySummary(
                walletFacadeStats.updateBalanceMinMs(),
                walletFacadeStats.updateBalanceMidMs(),
                walletFacadeStats.updateBalanceMaxMs(),
                walletFacadeStats.updateBalanceAvgMs()
        );
        LatencySummary directLatency = summarizeLatency(successDirectLatencyMs);
        LatencySummary findLatency = new LatencySummary(
                walletFacadeStats.findMinMs(),
                walletFacadeStats.findMidMs(),
                walletFacadeStats.findMaxMs(),
                walletFacadeStats.findAvgMs()
        );
        LatencySummary getLockLatency = new LatencySummary(
                walletFacadeStats.getLockMinMs(),
                walletFacadeStats.getLockMidMs(),
                walletFacadeStats.getLockMaxMs(),
                walletFacadeStats.getLockAvgMs()
        );
        LatencySummary redisTotalLatency = new LatencySummary(
                walletFacadeStats.redisTotalMinMs(),
                walletFacadeStats.redisTotalMidMs(),
                walletFacadeStats.redisTotalMaxMs(),
                walletFacadeStats.redisTotalAvgMs()
        );
        long retryCount = walletFacadeStats.retryCount();
        long operationTimeoutCount = walletFacadeStats.operationTimeoutCount();

        System.out.println("=== " + scenarioName + " (threads " + threads + ") ===");
        System.out.println("accounts=" + scenario.accountCount() + ", threads=" + threads + ", totalOps=" + scenario.totalOps());
        System.out.println("duration=" + duration.toMillis() + " ms, tps=" + String.format("%.2f", tps));
        System.out.println("insertMs min=" + insertLatency.minMs
                           + ", mid=" + insertLatency.midMs
                           + ", max=" + insertLatency.maxMs
                           + ", avg=" + String.format("%.2f", insertLatency.avgMs));
        System.out.println("updateBalanceMs min=" + updateBalanceLatency.minMs
                           + ", mid=" + updateBalanceLatency.midMs
                           + ", max=" + updateBalanceLatency.maxMs
                           + ", avg=" + String.format("%.2f", updateBalanceLatency.avgMs));
        System.out.println("directMs min=" + directLatency.minMs
                           + ", mid=" + directLatency.midMs
                           + ", max=" + directLatency.maxMs
                           + ", avg=" + String.format("%.2f", directLatency.avgMs));
        System.out.println("findMs min=" + findLatency.minMs
                           + ", mid=" + findLatency.midMs
                           + ", max=" + findLatency.maxMs
                           + ", avg=" + String.format("%.2f", findLatency.avgMs));
        System.out.println("getLockMs min=" + getLockLatency.minMs
                           + ", mid=" + getLockLatency.midMs
                           + ", max=" + getLockLatency.maxMs
                           + ", avg=" + String.format("%.2f", getLockLatency.avgMs));
        System.out.println("redisTotalMs min=" + redisTotalLatency.minMs
                           + ", mid=" + redisTotalLatency.midMs
                           + ", max=" + redisTotalLatency.maxMs
                           + ", avg=" + String.format("%.2f", redisTotalLatency.avgMs));
        System.out.println("success=" + effectiveSuccess
                           + ", insufficientBalance=" + insufficientBalance.get()
                           + ", operationTimeout=" + operationTimeout.get()
                           + ", otherErrors=" + otherErrors.get());
        System.out.println("successRatio=" + formatPercent(successRatio)
                           + ", insufficientBalanceRatio=" + formatPercent(insufficientBalanceRatio)
                           + ", operationTimeoutRatio=" + formatPercent(operationTimeoutRatio)
                           + ", otherErrorsRatio=" + formatPercent(otherErrorsRatio));
        System.out.println("retries=" + retryCount
                           + ", operationTimeoutCount=" + operationTimeoutCount);

        assertTrue(
                effectiveSuccess > 0,
                "Stress run produced no successful operations. insufficientBalance="
                + insufficientBalance.get()
                + ", operationTimeout=" + operationTimeout.get()
                + ", otherErrors=" + otherErrors.get()
        );
        assertWalletHistoryContinuity(success.get(), scenario.historyRowsPerSuccess());
        return new StressRunResult(
                scenarioName,
                scenario.operation().name(),
                scenario.accountCount(),
                scenario.balance(),
                threads,
                scenario.totalOps(),
                duration.toMillis(),
                tps,
                effectiveSuccess,
                successRatio,
                insufficientBalance.get(),
                insufficientBalanceRatio,
                operationTimeout.get(),
                operationTimeoutRatio,
                otherErrors.get(),
                otherErrorsRatio,
                insertLatency.minMs,
                insertLatency.midMs,
                insertLatency.maxMs,
                insertLatency.avgMs,
                updateBalanceLatency.minMs,
                updateBalanceLatency.midMs,
                updateBalanceLatency.maxMs,
                updateBalanceLatency.avgMs,
                directLatency.minMs,
                directLatency.midMs,
                directLatency.maxMs,
                directLatency.avgMs,
                findLatency.minMs,
                findLatency.midMs,
                findLatency.maxMs,
                findLatency.avgMs,
                getLockLatency.minMs,
                getLockLatency.midMs,
                getLockLatency.maxMs,
                getLockLatency.avgMs,
                redisTotalLatency.minMs,
                redisTotalLatency.midMs,
                redisTotalLatency.maxMs,
                redisTotalLatency.avgMs,
                retryCount,
                operationTimeoutCount
        );
    }

    protected void runScenarioRounds(
            Path csvPath,
            String scenarioName,
            WalletFacade walletCashFacade,
            TransferFacade transferFacade,
            StressScenario scenario
    ) throws Exception {
        long[] accountIds = createAccountsOnce(scenario.accountCount(), scenario.balance());
        for (int threads : scenario.threads()) {
            writeCsvResult(csvPath, stressRun(scenarioName, walletCashFacade, transferFacade, accountIds, scenario, threads));
        }
    }

    protected void runCashScenarioRounds(
            Path csvPath,
            String facadeName,
            WalletFacade walletCashFacade
    ) throws Exception {
        TransferFacade transferFacade = walletCashFacade instanceof WalletRowLockFacade
                ? transferRowLockFacade
                : transferRedisLockFacade;
        runNamedScenarioRounds(csvPath, facadeName, walletCashFacade, transferFacade, CASH_SCENARIOS);
    }

    protected void runNamedScenarioRounds(
            Path csvPath,
            String facadeName,
            WalletFacade walletCashFacade,
            TransferFacade transferFacade,
            StressScenario[] scenarios
    ) throws Exception {
        for (StressScenario scenario : scenarios) {
            runScenarioRounds(csvPath, buildScenarioName(facadeName, scenario), walletCashFacade, transferFacade, scenario);
        }
    }

    protected String buildScenarioName(String facadeName, StressScenario scenario) {
        return facadeName
               + " "
               + scenario.operation().name()
               + (scenario.batchSize() > 1 ? " batchSize=" + scenario.batchSize() : "")
               + " accounts="
               + scenario.accountCount()
               + " totalOps="
               + scenario.totalOps();
    }

    protected void resetStats() {
        walletFacadeStats.reset();
    }

    protected void writeCsvResult(Path csvPath, StressRunResult result) throws Exception {
        Files.createDirectories(csvPath.getParent());
        boolean needsHeader = Files.notExists(csvPath) || Files.size(csvPath) == 0L;

        StringBuilder line = new StringBuilder();
        if (needsHeader) {
            line.append("scenario,operation,accountCount,balance,threads,totalOps,durationMs,tps,success,successRatio,insufficientBalance,insufficientBalanceRatio,operationTimeout,operationTimeoutRatio,otherErrors,otherErrorsRatio,insertMinMs,insertMidMs,insertMaxMs,insertAvgMs,updateBalanceMinMs,updateBalanceMidMs,updateBalanceMaxMs,updateBalanceAvgMs,directMinMs,directMidMs,directMaxMs,directAvgMs,findMinMs,findMidMs,findMaxMs,findAvgMs,getLockMinMs,getLockMidMs,getLockMaxMs,getLockAvgMs,redisTotalMinMs,redisTotalMidMs,redisTotalMaxMs,redisTotalAvgMs,retries,operationTimeoutCount")
                    .append(System.lineSeparator());
        }
        line.append(csv(result.scenarioName())).append(',')
                .append(csv(result.operation())).append(',')
                .append(result.accountCount()).append(',')
                .append(result.balance()).append(',')
                .append(result.threads()).append(',')
                .append(result.totalOps()).append(',')
                .append(result.durationMs()).append(',')
                .append(String.format("%.2f", result.tps())).append(',')
                .append(result.success()).append(',')
                .append(formatRatio(result.successRatio())).append(',')
                .append(result.insufficientBalance()).append(',')
                .append(formatRatio(result.insufficientBalanceRatio())).append(',')
                .append(result.operationTimeout()).append(',')
                .append(formatRatio(result.operationTimeoutRatio())).append(',')
                .append(result.otherErrors()).append(',')
                .append(formatRatio(result.otherErrorsRatio())).append(',')
                .append(result.insertMinMs()).append(',')
                .append(result.insertMidMs()).append(',')
                .append(result.insertMaxMs()).append(',')
                .append(String.format(Locale.US, "%.2f", result.insertAvgMs())).append(',')
                .append(result.updateBalanceMinMs()).append(',')
                .append(result.updateBalanceMidMs()).append(',')
                .append(result.updateBalanceMaxMs()).append(',')
                .append(String.format(Locale.US, "%.2f", result.updateBalanceAvgMs())).append(',')
                .append(result.directMinMs()).append(',')
                .append(result.directMidMs()).append(',')
                .append(result.directMaxMs()).append(',')
                .append(String.format(Locale.US, "%.2f", result.directAvgMs())).append(',')
                .append(result.findMinMs()).append(',')
                .append(result.findMidMs()).append(',')
                .append(result.findMaxMs()).append(',')
                .append(String.format(Locale.US, "%.2f", result.findAvgMs())).append(',')
                .append(result.getLockMinMs()).append(',')
                .append(result.getLockMidMs()).append(',')
                .append(result.getLockMaxMs()).append(',')
                .append(String.format(Locale.US, "%.2f", result.getLockAvgMs())).append(',')
                .append(result.redisTotalMinMs()).append(',')
                .append(result.redisTotalMidMs()).append(',')
                .append(result.redisTotalMaxMs()).append(',')
                .append(String.format(Locale.US, "%.2f", result.redisTotalAvgMs())).append(',')
                .append(result.retries()).append(',')
                .append(result.operationTimeoutCount()).append(System.lineSeparator());

        Files.writeString(
                csvPath,
                line.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    protected static void resetCsv(Path csvPath) throws Exception {
        Files.deleteIfExists(csvPath);
    }

    protected static Path timestampedCsvPath(String prefix) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP_FORMATTER);
        return Path.of("", "stress-results", prefix + "-" + timestamp + ".csv");
    }

    protected void assertWalletHistoryContinuity(long successCount, int historyRowsPerSuccess) {
        List<WalletHistoryRow> histories = jdbcTemplate.query(
                "SELECT user_id, created_at, id, before_balance, after_balance "
                + "FROM wallet_history "
                + "ORDER BY user_id, id",
                (rs, rowNum) -> new WalletHistoryRow(
                        rs.getLong("user_id"),
                        rs.getLong("id"),
                        rs.getLong("before_balance"),
                        rs.getLong("after_balance")
                )
        );

        assertEquals(
                successCount * historyRowsPerSuccess,
                histories.size(),
                "Every successful operation should persist the expected wallet history rows."
        );

        Map<Long, List<WalletHistoryRow>> historiesByUser = new LinkedHashMap<>();
        for (WalletHistoryRow history : histories) {
            historiesByUser.computeIfAbsent(history.userId(), key -> new ArrayList<>()).add(history);
        }

        Map<Long, Long> finalBalances = jdbcTemplate.query(
                "SELECT id, balance FROM wallet ORDER BY id",
                rs -> {
                    Map<Long, Long> balances = new LinkedHashMap<>();
                    while (rs.next()) {
                        balances.put(rs.getLong("id"), rs.getLong("balance"));
                    }
                    return balances;
                }
        );

        for (Map.Entry<Long, List<WalletHistoryRow>> entry : historiesByUser.entrySet()) {
            Long userId = entry.getKey();
            List<WalletHistoryRow> userHistories = entry.getValue();

            for (int i = 1; i < userHistories.size(); i++) {
                WalletHistoryRow previous = userHistories.get(i - 1);
                WalletHistoryRow current = userHistories.get(i);
                assertEquals(
                        previous.afterBalance(),
                        current.beforeBalance(),
                        "History balance continuity mismatch for userId=" + userId
                        + ", previousHistoryId=" + previous.id()
                        + ", currentHistoryId=" + current.id()
                );
            }

            WalletHistoryRow lastHistory = userHistories.get(userHistories.size() - 1);
            assertEquals(
                    lastHistory.afterBalance(),
                    finalBalances.get(userId),
                    "Latest history balance should match wallet balance for userId=" + userId
            );
        }
    }

    protected static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static double ratio(long value, long total) {
        if (total == 0) {
            return 0D;
        }
        return value / (double) total;
    }

    private static String formatPercent(double ratio) {
        return String.format(Locale.US, "%.2f%%", ratio * 100);
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.US, "%.4f", ratio);
    }

    private static String csv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static List<BatchCashCommand> buildBatchCashCommands(
            long[] accountIds,
            int threadOps,
            int batchSize,
            SplittableRandom rnd
    ) {
        List<BatchCashCommand> commands = new ArrayList<>();
        for (int offset = 0; offset < threadOps; ) {
            int currentBatchSize = Math.min(batchSize, threadOps - offset);
            long accountId = accountIds[rnd.nextInt(accountIds.length)];
            List<CashRequest> requests = new ArrayList<>(currentBatchSize);
            for (int batchIndex = 0; batchIndex < currentBatchSize; batchIndex++) {
                requests.add(new CashRequest(accountId, 1L + rnd.nextInt(100)));
            }
            commands.add(new BatchCashCommand(requests));
            offset += currentBatchSize;
        }
        return commands;
    }

    private static LatencySummary summarizeLatency(ConcurrentLinkedQueue<Long> latencyMs) {
        if (latencyMs.isEmpty()) {
            return new LatencySummary(0L, 0L, 0L, 0D);
        }

        List<Long> sorted = new ArrayList<>(latencyMs);
        sorted.sort(Long::compareTo);
        int trimCount = sorted.size() >= 100 ? Math.max(1, sorted.size() / 100) : 0;
        int fromIndex = trimCount;
        int toIndex = sorted.size() - trimCount;
        if (fromIndex >= toIndex) {
            fromIndex = 0;
            toIndex = sorted.size();
        }

        long sum = 0L;
        for (int index = fromIndex; index < toIndex; index++) {
            sum += sorted.get(index);
        }

        return new LatencySummary(
                sorted.getFirst(),
                sorted.get(sorted.size() / 2),
                sorted.get(sorted.size() - 1),
                sum / (double) (toIndex - fromIndex)
        );
    }

    private record WalletHistoryRow(
            long userId,
            long id,
            long beforeBalance,
            long afterBalance
    ) {
    }

    private record BatchCashCommand(
            List<CashRequest> requests
    ) {
    }

    protected record StressRunResult(
            String scenarioName,
            String operation,
            int accountCount,
            int balance,
            int threads,
            int totalOps,
            long durationMs,
            double tps,
            long success,
            double successRatio,
            long insufficientBalance,
            double insufficientBalanceRatio,
            long operationTimeout,
            double operationTimeoutRatio,
            long otherErrors,
            double otherErrorsRatio,
            long insertMinMs,
            long insertMidMs,
            long insertMaxMs,
            double insertAvgMs,
            long updateBalanceMinMs,
            long updateBalanceMidMs,
            long updateBalanceMaxMs,
            double updateBalanceAvgMs,
            long directMinMs,
            long directMidMs,
            long directMaxMs,
            double directAvgMs,
            long findMinMs,
            long findMidMs,
            long findMaxMs,
            double findAvgMs,
            long getLockMinMs,
            long getLockMidMs,
            long getLockMaxMs,
            double getLockAvgMs,
            long redisTotalMinMs,
            long redisTotalMidMs,
            long redisTotalMaxMs,
            double redisTotalAvgMs,
            long retries,
            long operationTimeoutCount
    ) {
    }

    protected record StressScenario(
            StressOperation operation,
            int historyRowsPerSuccess,
            int accountCount,
            int balance,
            int[] threads,
            int totalOps,
            int batchSize
    ) {
        protected StressScenario(
                StressOperation operation,
                int historyRowsPerSuccess,
                int accountCount,
                int balance,
                int[] threads,
                int totalOps
        ) {
            this(operation, historyRowsPerSuccess, accountCount, balance, threads, totalOps, 1);
        }
    }

    private record LatencySummary(long minMs, long midMs, long maxMs, double avgMs) {
    }
}
