package com.hmdp.listener;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Map;

import static com.hmdp.utils.MqConstants.*;

/**
 * @author Administrator
 * @Description: 订单监听器
 */
@Slf4j
@Component
public class OrderListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @RabbitListener(queues = "creatOrder.queue")
    public void orderListener(Map<String, Object> msg) {
        log.info("创建订单：{}", msg);
        VoucherOrder voucherOrder = BeanUtil.toBean(msg, VoucherOrder.class);
        voucherOrderService.createVoucherOrder(voucherOrder);
    }


    @RabbitListener(queues = DLX_QUEUE)
    public void dlxListener(Map<String, Object> msg) {
        VoucherOrder voucherOrder = BeanUtil.toBean(msg, VoucherOrder.class);
        Long id = voucherOrder.getId();
        VoucherOrder order = voucherOrderService.getById(id);
        stringRedisTemplate.opsForSet().remove("seckill:order"+voucherOrder.getVoucherId(),voucherOrder.getUserId());
        if (order.getStatus() == 1){
            voucherOrderService.removeById(id);
        }
    }
}
