package com.hmdp.job;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 秒杀数据对账任务
 * <p>
 * 每 10 分钟比对 Redis 与 DB 的库存及有效订单数，只记录差异。
 * 异步落库和关单回补中的在途操作也会产生差异，单次快照不能证明消息丢失，
 * 因此不据此覆盖库存或补单；持续差异需结合订单与消息处理状态复核。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconcileTask {

    private final ISeckillVoucherService seckillVoucherService;
    private final IVoucherOrderService voucherOrderService;
    private final StringRedisTemplate stringRedisTemplate;

    /** 订单状态常量：已取消（不占用库存） */
    private static final int STATUS_CANCELED = 4;

    /**
     * 每 10 分钟执行一次
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void reconcile() {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        if (vouchers.isEmpty()) {
            return;
        }
        for (SeckillVoucher voucher : vouchers) {
            try {
                reconcileOne(voucher);
            } catch (Exception e) {
                log.error("对账失败，voucherId: {}", voucher.getVoucherId(), e);
            }
        }
    }

    private void reconcileOne(SeckillVoucher voucher) {
        Long voucherId = voucher.getVoucherId();
        Integer dbStock = voucher.getStock();

        // 1.Redis 快照：库存 + 已下单用户集合
        String stockStr = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        if (stockStr == null) {
            // Redis 无此券库存（未预热 / 已过期清理），跳过
            return;
        }
        long redisStock = Long.parseLong(stockStr);
        Long redisOrderCount = stringRedisTemplate.opsForSet()
                .size(RedisConstants.SECKILL_ORDER_KEY + voucherId);
        long redisOrder = redisOrderCount == null ? 0L : redisOrderCount;

        // 2.DB 数据：有效订单数（未取消的订单才占用库存）
        long dbValidOrders = voucherOrderService.query()
                .eq("voucher_id", voucherId)
                .ne("status", STATUS_CANCELED)
                .count();

        // 3.差异判断
        boolean stockDiff = dbStock != null && dbStock != redisStock;
        boolean orderDiff = redisOrder != dbValidOrders;
        if (!stockDiff && !orderDiff) {
            return;
        }

        log.warn("秒杀对账发现快照差异，可能存在在途操作或待修复异常，请结合处理状态复核，voucherId: {}, DB库存: {}, Redis库存: {}, Redis下单: {}, DB有效订单: {}",
                voucherId, dbStock, redisStock, redisOrder, dbValidOrders);
    }
}
