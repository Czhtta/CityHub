-- MySQL 8 手动迁移：先选择目标数据库，仅执行一次。
-- 已有数据库请使用本文件，不要执行会 DROP TABLE 的 db/hmdp.sql。
-- 预检若返回记录，请人工确认重复有效订单；迁移不会删除或修改历史订单。
SELECT `user_id`, `voucher_id`, COUNT(*) AS `active_order_count`
FROM `tb_voucher_order`
WHERE `status` <> 4
GROUP BY `user_id`, `voucher_id`
HAVING COUNT(*) > 1;

-- 同一 ALTER 添加生成列和唯一索引；存在重复有效订单时整条语句失败。
-- NULL 允许保留多个已取消订单；其余状态（含退款中、已退款）继续占购买资格。
ALTER TABLE `tb_voucher_order`
  ADD COLUMN `active_order_marker` tinyint UNSIGNED
    GENERATED ALWAYS AS (CASE WHEN `status` = 4 THEN NULL ELSE 1 END) STORED
    COMMENT '仅已取消订单释放购买资格',
  ADD UNIQUE INDEX `uk_voucher_order_active` (`user_id`, `voucher_id`, `active_order_marker`);
