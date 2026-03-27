package com.ahu.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 高并发售票核心压力测试
 *
 * 面试话术要点：
 * - 200 线程模拟除夕夜万人抢票的高并发场景
 * - 验证 Lua 原子扣减的核心算法逻辑：库存精准为 0，绝无超卖
 * - 使用 CountDownLatch 做发令枪，确保所有线程在同一瞬间全部开抢
 * - 使用 AtomicInteger 模拟 Redis Lua 脚本的原子 decr 操作
 *
 * 本测试不依赖 Redis / MySQL / RocketMQ，纯 Java 并发验证
 */
public class TicketConcurrencyTest {

    /**
     * 模拟 Redis Lua 原子扣减脚本的内存版实现
     *
     * 对应生产代码 TicketController 中的 LUA_STOCK_DECREASE:
     *   if (exists key) then
     *       local stock = get(key)
     *       if (stock > 0) then decr(key); return stock - 1; end
     *       return -1;  -- 库存不足
     *   end
     *   return -2;  -- key 不存在
     */
    private static class MockLuaStockEngine {
        private final AtomicInteger stock;

        MockLuaStockEngine(int initialStock) {
            this.stock = new AtomicInteger(initialStock);
        }

        /**
         * 原子扣减：模拟 Lua 脚本的 CAS 语义
         * @return >= 0 表示扣减后的剩余库存，-1 表示库存不足
         */
        int atomicDecrement() {
            while (true) {
                int current = stock.get();
                if (current <= 0) {
                    return -1; // 库存不足
                }
                if (stock.compareAndSet(current, current - 1)) {
                    return current - 1; // 扣减成功，返回剩余库存
                }
                // CAS 失败，自旋重试（模拟 Lua 的单线程原子性）
            }
        }

        int getStock() {
            return stock.get();
        }
    }

    private MockLuaStockEngine stockEngine;

    @BeforeEach
    void setUp() {
        stockEngine = new MockLuaStockEngine(100); // 100 张票
    }

    @Test
    @DisplayName("200 线程并发抢 100 张票 → 恰好卖出 100 张，库存精确归零，无超卖")
    void testConcurrentBooking_noOverselling() throws InterruptedException {
        int threadCount = 200;
        CountDownLatch startGun = new CountDownLatch(1);       // 发令枪：所有线程就绪后同时出发
        CountDownLatch finishLine = new CountDownLatch(threadCount); // 终点线：等所有线程跑完

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGun.await(); // 所有线程在此等待发令枪

                    int result = stockEngine.atomicDecrement();
                    if (result >= 0) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLine.countDown();
                }
            });
        }

        // 发令枪响！200 线程同时冲刺
        startGun.countDown();
        // 等待所有线程完成
        finishLine.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // ========== 核心断言 ==========
        assertEquals(100, successCount.get(), "成功抢到票的线程数必须恰好等于初始库存 100");
        assertEquals(100, failCount.get(), "抢票失败的线程数必须恰好等于 200 - 100 = 100");
        assertEquals(0, stockEngine.getStock(), "最终库存必须精确为 0，绝不允许超卖为负数");

        System.out.println("✅ 并发压测通过！成功: " + successCount.get() +
                ", 失败: " + failCount.get() + ", 剩余库存: " + stockEngine.getStock());
    }

    @Test
    @DisplayName("库存为 0 时继续抢票 → 全部失败，库存始终为 0，绝不出现负库存")
    void testBookingWhenStockIsZero() throws InterruptedException {
        // 先把库存清零
        stockEngine = new MockLuaStockEngine(0);

        int threadCount = 50;
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    if (stockEngine.atomicDecrement() >= 0) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startGun.countDown();
        finishLine.await(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(0, successCount.get(), "库存为 0 时，不允许任何线程抢票成功");
        assertEquals(0, stockEngine.getStock(), "库存必须始终为 0，绝不允许变成负数");

        System.out.println("✅ 零库存防御测试通过！无人突破防线。");
    }

    @Test
    @DisplayName("单线程顺序扣减 → 库存递减正确")
    void testSequentialDecrement() {
        stockEngine = new MockLuaStockEngine(5);

        assertEquals(4, stockEngine.atomicDecrement()); // 5 → 4
        assertEquals(3, stockEngine.atomicDecrement()); // 4 → 3
        assertEquals(2, stockEngine.atomicDecrement()); // 3 → 2
        assertEquals(1, stockEngine.atomicDecrement()); // 2 → 1
        assertEquals(0, stockEngine.atomicDecrement()); // 1 → 0
        assertEquals(-1, stockEngine.atomicDecrement()); // 0 → 失败

        assertEquals(0, stockEngine.getStock(), "扣完后库存为 0");
        System.out.println("✅ 顺序扣减逻辑验证通过！");
    }
}
