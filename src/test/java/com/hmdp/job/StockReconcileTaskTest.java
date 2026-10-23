package com.hmdp.job;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class StockReconcileTaskTest {

    @Test
    void inFlightOrderDoesNotOverwriteDatabaseStock() {
        ISeckillVoucherService stocks = mock(ISeckillVoucherService.class);
        IVoucherOrderService orders = mock(IVoucherOrderService.class);
        @SuppressWarnings("unchecked")
        QueryChainWrapper<VoucherOrder> query = mock(QueryChainWrapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        SeckillVoucher voucher = new SeckillVoucher().setVoucherId(1L).setStock(100);
        when(stocks.list()).thenReturn(List.of(voucher));
        when(redis.opsForValue().get(RedisConstants.SECKILL_STOCK_KEY + 1L)).thenReturn("99");
        when(redis.opsForSet().size(RedisConstants.SECKILL_ORDER_KEY + 1L)).thenReturn(1L);
        when(orders.query()).thenReturn(query);
        when(query.eq("voucher_id", 1L)).thenReturn(query);
        when(query.ne("status", 4)).thenReturn(query);
        when(query.count()).thenReturn(0L);

        new StockReconcileTask(stocks, orders, redis).reconcile();

        verify(query).count();
        verify(stocks).list();
        verifyNoMoreInteractions(stocks);
    }
}
