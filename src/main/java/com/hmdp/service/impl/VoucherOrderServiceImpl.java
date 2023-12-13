package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.MqConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.util.concurrent.SettableListenableFuture;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.MqConstants.ORDER_EXCHANGE;
import static com.hmdp.utils.MqConstants.ORDER_QUEUE_NAME;

/**
 * @author 小新
 * date 2023/2/22
 * @apiNode
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


//    private final static ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

//    @PostConstruct
//    private void init() {
//        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
//    }
//
//    private class VoucherOrderHandler implements Runnable {
//        String queueName = "stream.orders";
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order > 读取未消费的队列
//                    //g1表示组名，c1表示消费者名称
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
//                            //这里对应的是COUNT 1 BLOCK 2000
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            //这里对应的就是STREAMS streams.order>
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
//                    //判断消息是否获取成功
//                    if (list == null || list.isEmpty()){
//                        //如果获取失败，说明没有消息，进行下一次循环
//                        continue;
//                    }
//                    //有消息
//                    //解析消息中的订单信息,String指的是消息的id，对应的是key，value
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    //转成订单对象
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    //如果获取成功，创建订单
//                    handleVoucherOrder(voucherOrder);
//                    //ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
//                } catch (Exception e) {
//                    log.error("订单处理异常", e);
//                    handlePendingList();
//                }
//            }
//        }
//
//        private void handlePendingList() {
//            while (true) {
//                try {
//                    //获pendingList中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order 0 读取在pendingList中未确认的消费
//                    //g1表示组名，c1表示消费者名称
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
//                            //这里对应的是COUNT 1 BLOCK 2000
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            //这里对应的就是STREAMS streams.order>
//                            StreamOffset.create(queueName, ReadOffset.from("0")));
//                    //判断消息是否获取成功
//                    if (list == null || list.isEmpty()){
//                        //如果获取失败，说明pendingList没有消息，直接结束循环
//                        break;
//                    }
//                    //有消息
//                    //解析消息中的订单信息,String指的是消息的id，对应的是key，value
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    //转成订单对象
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    //如果获取成功，创建订单
//                    handleVoucherOrder(voucherOrder);
//                    //ACK确认
//                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
//                } catch (Exception e) {
//                    log.error("订单处理异常", e);
//                    try {
//                        Thread.sleep(20);
//                    } catch (InterruptedException interruptedException) {
//                        interruptedException.printStackTrace();
//                    }
//                }
//            }
//        }
//    }

    private IVoucherOrderService proxy;

    //因为这里已经讲订单放到了消息队列所有肯定是拿的到订单的
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean b = lock.tryLock();
        //判断锁是否成功
        if (!b) {
            //获取锁失败
            log.error("业务繁忙请稍等");
            return;
        }
        try {
            //因为是子线程，是不能从ThreadLocal取到这个代理对象的
            //因为前面经过赋值这里就可以使用了
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //防止服务器失效，释放锁
            lock.unlock();
        }

    }


    public Result seckillVoucher(Long voucherId) {
//        1.查询优惠卷
        SeckillVoucher seckillVouchers = seckillVoucherService.getById(voucherId);
        if (seckillVouchers == null) {
            return Result.fail("秒杀不存在");
        }
        //判断秒杀是否开始
        //LocalDateTime beginTime = seckillVouchers.getBeginTime();
        //LocalDateTime endTime = seckillVouchers.getEndTime();
        ////判断开始时间是否在当前时间之后，是就表明秒杀还未开始
        //if (beginTime.isAfter(LocalDateTime.now())) {
        //    return Result.fail("秒杀还未开始");
        //}
        ////判断秒杀是否结束
        //if (endTime.isBefore(LocalDateTime.now())) {
        //    return Result.fail("秒杀已结束");
        //}
        //查询用户
        //生成订单信息
        long orderId = redisIdWorker.nextId("order");
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        /**
         //判断秒杀是否开始
         LocalDateTime beginTime = seckillVouchers.getBeginTime();
         LocalDateTime endTime = seckillVouchers.getEndTime();
         //判断开始时间是否在当前时间之后，是就表明秒杀还未开始
         if (beginTime.isAfter(LocalDateTime.now())) {
         return Result.fail("秒杀还未开始");
         }
         //判断秒杀是否结束
         if (endTime.isBefore(LocalDateTime.now())) {
         return Result.fail("秒杀已结束");
         }
         **/
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),String.valueOf(orderId));
        int r = result.intValue();
        //判断结构是为否0
        if (r != 0) {
            //2.1不为0,又分两种情况
            return Result.fail(r == 1 ? "优惠券已售罄" : "请勿重复下单");
        }

        setOrderAndSent(voucherId, orderId, userId);
        //返回订单消息
        return Result.ok(orderId);
    }

    private void setOrderAndSent(Long voucherId, long orderId, Long userId) {
        VoucherOrder voucherOrder = new VoucherOrder();
        //用户id
        voucherOrder.setUserId(userId);
        //订单id
        voucherOrder.setId(orderId);
        //优惠卷id
        voucherOrder.setVoucherId(voucherId);
        //支付状态：默认未支付
        voucherOrder.setStatus(1);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(voucherOrder,false,false);
        log.error(stringObjectMap.toString());
        CorrelationData correlationData = new CorrelationData();
        SettableListenableFuture<CorrelationData.Confirm> future = correlationData.getFuture();
        future.addCallback(new ListenableFutureCallback<CorrelationData.Confirm>() {
            @Override
            public void onFailure(Throwable ex) {
                log.error("订单异常", ex);
            }

            @Override
            public void onSuccess(CorrelationData.Confirm result) {
                if (result.isAck()){
                    log.info("订单消息接收成功");
                }else {
                    log.info("消息发送失败");
                }
            }
        });
        //发送送到消息队列中
        rabbitTemplate.convertAndSend(ORDER_EXCHANGE,"order",stringObjectMap,message -> {
            //设置15分钟过期
            message.getMessageProperties().setExpiration("100000");
            return message;
        },correlationData);
    }

    /**
     * 由于是异步所以就不用返回订单信息
     *
     * @param voucherOrder 优惠券对象
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean b = lock.tryLock();
        //判断锁是否成功
        if (!b) {
            //获取锁失败
            log.error("业务繁忙请稍等");
            return;
        }
        //扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
        }
        save(voucherOrder);
    }
}
