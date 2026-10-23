package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderCreationTest {
    @Mock
    private VoucherOrderMapper orderMapper;
    @Mock
    private ISeckillVoucherService stockService;

    private VoucherOrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(orderService, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(orderService, "seckillVoucherService", stockService);
    }

    @Test
    void createsOnlyAnUnpaidOrderWithTheGivenIdentity() {
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(1);
        when(stockService.update(any(UpdateWrapper.class))).thenReturn(true);

        orderService.createVoucherOrder(1L, 2L, 3L);

        ArgumentCaptor<VoucherOrder> order = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(orderMapper).insert(order.capture());
        assertEquals(1L, order.getValue().getId());
        assertEquals(2L, order.getValue().getUserId());
        assertEquals(3L, order.getValue().getVoucherId());
        assertEquals(1, order.getValue().getStatus());
        assertNull(order.getValue().getPayTime());
    }

    @Test
    void rejectsAnInsertThatDidNotCreateAnOrder() {
        assertThrows(IllegalStateException.class, () -> orderService.createVoucherOrder(1L, 2L, 3L));

        verifyNoInteractions(stockService);
    }

    @Test
    void rejectsAnOrderWhenConditionalStockDeductionFails() {
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> orderService.createVoucherOrder(1L, 2L, 3L));
    }

    @ParameterizedTest
    @CsvSource(value = {"NULL, 2, 3", "1, NULL, 3", "1, 2, NULL", "0, 2, 3", "1, -1, 3", "1, 2, 0"}, nullValues = "NULL")
    void rejectsInvalidIdentityBeforeWriting(Long orderId, Long userId, Long voucherId) {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.createVoucherOrder(orderId, userId, voucherId));

        verifyNoInteractions(orderMapper, stockService);
    }
}
