package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private final static ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order > 读取未消费的队列
                    //g1表示组名，c1表示消费者名称
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            //这里对应的是COUNT 1 BLOCK 2000
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //这里对应的就是STREAMS streams.order>
                            StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    //判断消息是否获取成功
                    if (list == null || list.isEmpty()){
                        //如果获取失败，说明没有消息，进行下一次循环
                        continue;
                    }
                    //有消息
                    //解析消息中的订单信息,String指的是消息的id，对应的是key，value
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    //转成订单对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    //获pendingList中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order 0 读取在pendingList中未确认的消费
                    //g1表示组名，c1表示消费者名称
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            //这里对应的是COUNT 1 BLOCK 2000
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            //这里对应的就是STREAMS streams.order>
                            StreamOffset.create(queueName, ReadOffset.from("0")));
                    //判断消息是否获取成功
                    if (list == null || list.isEmpty()){
                        //如果获取失败，说明pendingList没有消息，直接结束循环
                        break;
                    }
                    //有消息
                    //解析消息中的订单信息,String指的是消息的id，对应的是key，value
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    //转成订单对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //如果获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }


    /**已经放到消息队列中就无需在阻塞队列中取消息了
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //获取阻塞队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                }
            }
        }
    }
     **/

    private IVoucherOrderService proxy;

    //因为这里已经讲订单放到了消息队列所有肯定是拿的到订单的
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
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
        ////1.查询优惠卷
        //SeckillVoucher seckillVouchers = seckillVoucherService.getById(voucherId);
        ////判断秒杀是否开始
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
        //通过后已经将用户id，订单id，秒杀券id放到了消息队列中
        //获取代理对象
        //proxy放到成员变量后这里进行了赋值
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单消息
        return Result.ok(orderId);
    }

    /**使用的jvm的阻塞队列
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        //SeckillVoucher seckillVouchers = seckillVoucherService.getById(voucherId);
        //查询用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
        int r = result.intValue();
        //判断结构是为否0
        if (r != 0) {
            //2.1不为0,又分两种情况
            return Result.fail(r == 1 ? "优惠券已售罄" : "请勿重复下单");
        }

        //2.2为0，有购买资格，把下单信息保存到阻塞队列
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //生成订单信息
        long orderId = redisIdWorker.nextId("order");
        //用户id
        voucherOrder.setUserId(user.getId());
        //秒杀卷id
        voucherOrder.setVoucherId(voucherId);
        //订单id
        voucherOrder.setId(orderId);
        // 保存到阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        //proxy放到成员变量后这里进行了赋值
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单消息
        return Result.ok(orderId);
    }
     **/


    /**
     @Override 未优化版本
     public Result seckillVoucher(Long voucherId) {
     //1.查询优惠卷
     SeckillVoucher seckillVouchers = seckillVoucherService.getById(voucherId);

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
     //判断库存是否充足
     if (seckillVouchers.getStock() < 1) {
     return Result.fail("优惠券售空");
     }
     //充足
     UserDTO user = UserHolder.getUser();
     Long id = user.getId();
     //应该在这里进行加锁
     //创建锁对象
     //SimpleRedisLockRe Lock = new SimpleRedisLockRe(stringRedisTemplate,"order:" + id);

     //获取锁 (可重入)，指定锁的名称
     RLock lock = redissonClient.getLock("lock:order:" + id);
     //这里如果不指定参数的话那么这个默认就是30秒中，超过30s就自动释放
     boolean b = lock.tryLock();
     if (!b) {
     //获取锁失败
     return Result.fail("业务繁忙请稍等");
     }
     try {
     IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
     return proxy.createVoucherOrder(voucherId);
     } finally {
     //防止服务器失效，释放锁
     lock.unlock();
     }
     **/

    /**
     *不支持分布式版本
     **/
    //synchronized (id.toString().intern()){
    //    /*我们是对当前的createVoucherOrder加上了事务,并没有对seckillVoucher加事务如果是用this调用createVoucherOrder，
    //    是用的当前这个VoucherOrderServicelmp这个对象进行调用,事务的生效是应为spring对当前的这个类做了动态代理，拿到的是他的代理对象然后对他进行事务处理，但是他本身是没有事务功能的
    //    所以我们要拿到他的代理对象使用一个API--->AopContext拿到当前的代理对象,这样事务才会生效
    //    知识点
    //    aop代理对象，事务失效，synchronized，
    //    */
    //    IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
    //    return proxy.createVoucherOrder(voucherId);
    //}

    /**
     * 由于是异步所以就不用返回订单信息
     *
     * @param voucher 优惠券对象
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucher) {
        //因为这是子线程是拿不到ThreadLocal里面的东西，所以只能从voucher里面获取用户id
        Long userId = voucher.getUserId();
        int count = query().ge("user_id", userId).eq("voucher_id", voucher.getVoucherId()).count();
        //log.error(String.valueOf(count));
        //if (count > 0) {
        //    log.error("不可重复下单");
        //    return;
        //}
        //扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucher.getVoucherId())
                .gt("stock", 0)//where stock > 0
                .update();
        if (!success) {
            log.error("库存不足");
        }
        //因为在阻塞队列中就一家创建了订单这里就可以直接保存订单
        save(voucher);
    }

    /**
     * 如果只在查询的地方进行加锁那么就会导致，当查询完后在这个查询等待的线程又查询一次，但是前一个线程还未提交事务，就会导致数据库未更新，查出来的订单依然是不存在的
     *
     * @param voucherId 优惠券id
     * @return id
     */
    /**
     @Override
     @Transactional public Result createVoucherOrder(Long voucherId) {
     //一人一单(由于这里也可能出现线程的并发问题，并且这里不能通过乐观锁来解决，所以只能采用悲观锁)
     UserDTO user = UserHolder.getUser();
     int count = query().ge("user_id", user.getId()).eq("voucher_id", voucherId).count();
     if (count > 0) {
     return Result.fail("不可重复下单");
     }
     //扣减库存
     boolean success = seckillVoucherService.update().setSql("stock = stock -1")
     .eq("voucher_id", voucherId)
     .gt("stock", 0)//where stock > 0
     .update();
     if (!success) {
     return Result.fail("库存不足");
     }
     //创建订单
     VoucherOrder voucherOrder = new VoucherOrder();
     //redis的id生成器
     long ordId = redisIdWorker.nextId("ord");

     //用户id
     voucherOrder.setUserId(user.getId());
     //秒杀卷id
     voucherOrder.setVoucherId(voucherId);
     //订单id
     voucherOrder.setId(ordId);
     //保存信息
     save(voucherOrder);
     //返回订单信息
     return Result.ok(ordId);
     }
     **/
}
