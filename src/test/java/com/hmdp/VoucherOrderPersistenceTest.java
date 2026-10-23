package com.hmdp;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.listener.SeckillVoucherListener;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillStockCache;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Uses an explicitly selected, disposable local MySQL database; no Redis or MQ connection. */
@SpringJUnitConfig(VoucherOrderPersistenceTest.Config.class)
@EnabledIfEnvironmentVariable(named = "CITYHUB_TEST_DB_URL", matches = ".+")
class VoucherOrderPersistenceTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private IVoucherOrderService orders;
    @Autowired private SeckillVoucherListener listener;

    @BeforeEach
    void resetTables() throws Exception {
        String schema = resource("db/hmdp.sql");
        for (String table : List.of("tb_voucher_order", "tb_seckill_voucher")) {
            jdbc.execute("DROP TABLE IF EXISTS `" + table + "`");
            var create = Pattern.compile("CREATE TABLE `" + table + "`\\s*\\(.*?;", Pattern.DOTALL)
                    .matcher(schema);
            assertTrue(create.find(), "Missing production schema: " + table);
            jdbc.execute(create.group());
        }
        jdbc.update("INSERT INTO tb_seckill_voucher(voucher_id, stock, begin_time, end_time) "
                + "VALUES (10, 2, NOW(), DATE_ADD(NOW(), INTERVAL 1 HOUR))");
    }

    @Test
    void duplicateDeliveryDoesNotDeductAgainAndConflictingIdentityIsRejected() {
        listener.onMessage(message(100, 1));
        listener.onMessage(message(100, 1));
        assertThrows(DuplicateKeyException.class, () -> listener.onMessage(message(100, 2)));
        assertEquals(1, countOrders());
        assertEquals(1, stock());
    }

    @Test
    void zeroStockRollsBackInsertedOrder() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 0 WHERE voucher_id = 10");
        assertThrows(IllegalStateException.class, () -> orders.createVoucherOrder(100L, 1L, 10L));
        assertEquals(0, countOrders());
        assertEquals(0, stock());
    }

    @Test
    void databaseFailureAfterOrderInsertRollsBackBothWrites() {
        jdbc.execute("CREATE TRIGGER fail_stock_update BEFORE UPDATE ON tb_seckill_voucher "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected stock failure'");
        assertThrows(DataAccessException.class, () -> orders.createVoucherOrder(100L, 1L, 10L));
        assertEquals(0, countOrders());
        assertEquals(2, stock());
    }

    @Test
    void canceledOrderAllowsAnotherPurchaseButOldMessageCannotReopenIt() {
        orders.createVoucherOrder(100L, 1L, 10L);
        jdbc.update("UPDATE tb_voucher_order SET status = 4 WHERE id = 100");
        jdbc.update("UPDATE tb_seckill_voucher SET stock = stock + 1 WHERE voucher_id = 10");
        listener.onMessage(message(101, 1));
        listener.onMessage(message(100, 1));
        assertEquals(2, countOrders());
        assertEquals(1, stock());
        assertEquals(4, jdbc.queryForObject("SELECT status FROM tb_voucher_order WHERE id = 100", Integer.class));
    }

    @Test
    void concurrentDuplicateDeliveryCreatesOnlyOneOrder() throws Exception {
        runConcurrently(() -> { listener.onMessage(message(100, 1)); return true; });
        assertEquals(1, countOrders());
        assertEquals(1, stock());
    }

    @Test
    void concurrentBuyersCannotOversellLastItem() throws Exception {
        jdbc.update("UPDATE tb_seckill_voucher SET stock = 1 WHERE voucher_id = 10");
        var sequence = new java.util.concurrent.atomic.AtomicLong(100);
        List<Boolean> results = runConcurrently(() -> {
            long id = sequence.incrementAndGet();
            try {
                orders.createVoucherOrder(id, id, 10L);
                return true;
            } catch (IllegalStateException soldOut) {
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, countOrders());
        assertEquals(0, stock());
    }

    private static String resource(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int countOrders() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class);
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10", Integer.class);
    }

    private static String message(long orderId, long userId) {
        return "{\"id\":" + orderId + ",\"userId\":" + userId + ",\"voucherId\":10}";
    }

    private List<Boolean> runConcurrently(Callable<Boolean> operation) throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        var ready = new CountDownLatch(4);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
        try {
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    return operation.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<Boolean>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.hmdp.mapper")
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getenv("CITYHUB_TEST_DB_URL");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/cityhub_test_[a-z0-9_]+(\\?.*)?")) {
                throw new IllegalArgumentException("Use a disposable local cityhub_test_* database");
            }
            return new DriverManagerDataSource(url, "root", System.getenv("CITYHUB_TEST_DB_PASSWORD"));
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            return factory.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean IVoucherOrderService voucherOrders() { return new VoucherOrderServiceImpl(); }
        @Bean ISeckillVoucherService vouchers() { return new SeckillVoucherServiceImpl(); }
        @Bean SeckillVoucherListener listener(IVoucherOrderService orders) { return new SeckillVoucherListener(orders); }
        @Bean RocketMQTemplate rocketMQTemplate() { return mock(RocketMQTemplate.class); }
        @Bean RedisIdWorker redisIdWorker() { return mock(RedisIdWorker.class); }
        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
        @Bean SeckillStockCache seckillStockCache() { return mock(SeckillStockCache.class); }
    }
}
