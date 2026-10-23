package com.hmdp.listener;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillVoucherListenerTest {
    private static final String MESSAGE = "{\"id\":1,\"userId\":2,\"voucherId\":3}";

    @Mock
    private IVoucherOrderService orderService;

    private SeckillVoucherListener listener;

    @BeforeEach
    void setUp() {
        listener = new SeckillVoucherListener(orderService);
    }

    @Test
    void passesOnlyOrderIdentityToTransactionalService() {
        listener.onMessage("{\"id\":1,\"userId\":2,\"voucherId\":3,\"status\":4,\"payType\":3}");

        verify(orderService).createVoucherOrder(1L, 2L, 3L);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    void acknowledgesDuplicateOfTheSameOrder() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(orderService).createVoucherOrder(1L, 2L, 3L);
        when(orderService.getById(1L)).thenReturn(new VoucherOrder()
                .setId(1L).setUserId(2L).setVoucherId(3L).setStatus(4));

        assertDoesNotThrow(() -> listener.onMessage(MESSAGE));

        verify(orderService).createVoucherOrder(1L, 2L, 3L);
        verify(orderService).getById(1L);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    void propagatesBusinessUniqueConflictForAnotherOrder() {
        DuplicateKeyException failure = new DuplicateKeyException("active purchase already exists");
        doThrow(failure).when(orderService).createVoucherOrder(1L, 2L, 3L);

        assertSame(failure, assertThrows(DuplicateKeyException.class, () -> listener.onMessage(MESSAGE)));
    }

    @ParameterizedTest
    @CsvSource({"9, 3", "2, 9"})
    void rejectsOrderIdWithDifferentBusinessIdentity(long userId, long voucherId) {
        DuplicateKeyException failure = new DuplicateKeyException("duplicate id");
        doThrow(failure).when(orderService).createVoucherOrder(1L, 2L, 3L);
        when(orderService.getById(1L)).thenReturn(new VoucherOrder()
                .setId(1L).setUserId(userId).setVoucherId(voucherId));

        assertSame(failure, assertThrows(DuplicateKeyException.class, () -> listener.onMessage(MESSAGE)));
    }

    @Test
    void propagatesStockFailureForMqRetry() {
        IllegalStateException failure = new IllegalStateException("no stock");
        doThrow(failure).when(orderService).createVoucherOrder(1L, 2L, 3L);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> listener.onMessage(MESSAGE)));

        verify(orderService).createVoucherOrder(1L, 2L, 3L);
        verifyNoMoreInteractions(orderService);
    }
}
