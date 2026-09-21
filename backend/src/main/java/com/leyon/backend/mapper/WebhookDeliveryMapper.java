package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.WebhookDelivery;
import org.apache.ibatis.annotations.Mapper;

/**
 * Webhook 投递记录 Mapper
 * 对应数据表 webhook_deliveries
 *
 * @author leyon
 */
@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {
}