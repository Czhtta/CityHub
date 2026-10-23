package com.hmdp.listener;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 秒杀订单异步消费者
 * <p>
 * 原 RabbitMQ 版通过 QA(10s TTL 死信 → QD)实现延时落库；RocketMQ 版本落库消息立即投递
 * （delayLevel=0），仅借异步解耦削峰，消费者直接落库 + CAS 扣库存，保证订单尽快可见。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
@RocketMQMessageListener(
        topic = "seckill-order-topic",
        consumerGroup = "seckill-order-consumer",
        selectorExpression = "seckill"
)
public class SeckillVoucherListener implements RocketMQListener<String> {

    private final IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(String msg) {
        log.info("收到秒杀订单消息: {}", msg);
        JSONObject event = JSONUtil.parseObj(msg);
        Long orderId = event.getLong("id");
        Long userId = event.getLong("userId");
        Long voucherId = event.getLong("voucherId");
        try {
            voucherOrderService.createVoucherOrder(orderId, userId, voucherId);
        } catch (DuplicateKeyException e) {
            // 代理事务已回滚；业务唯一键冲突不能当作当前消息已经处理。
            VoucherOrder existing = voucherOrderService.getById(orderId);
            if (existing == null || !Objects.equals(existing.getUserId(), userId)
                    || !Objects.equals(existing.getVoucherId(), voucherId)) {
                throw e;
            }
            // shortcut: 订单存在依赖新事务的完整提交保证，升级旧库前须核查历史半完成订单。
            log.info("订单已完整创建，幂等命中，orderId: {}", orderId);
        }
    }
}
