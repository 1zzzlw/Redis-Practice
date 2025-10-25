package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
    implements IVoucherOrderService {

    @Resource
    ISeckillVoucherService iSeckillVoucherService;
    @Resource
    RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        // 提前读取lua文件，提高效率
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 设置lua脚本文件的位置
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // 指定返回值
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 当前类初始化完毕之后就开始执行
    @PostConstruct
    private void init() {
        // 执行线程池中的线程任务
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 创建线程任务
    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    // 获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.lastConsumed()));
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 如果获取成功，可以下单
                    handlerVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0")));
                    // 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明pending-list没有异常消息，结束循环
                        break;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 如果获取成功，可以下单
                    handlerVoucherOrder(voucherOrder);
                    // ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    // 阻塞队列实现的异步秒杀线程任务
    /*
    // 创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    // 创建线程任务
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 获取阻塞队列中的订单信息，获取不到就阻塞，take具有阻塞功能(take获取和删除队列的头部的阻塞方法)
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handlerVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
    */

    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        // 由于此时是一个新的线程，不是主线程，就取不到UserHolder中的用户id了
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order" + userId);
        // 尝试获得锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象 这里在新的线程中同样拿不到这个代理，因为内部实现也是用的线程，解决方法就是在主线程中提前获取
            // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    // redis中的stream消息队列实现异步秒杀
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(),
            userId.toString(), String.valueOf(orderId));
        // 判断是否等于0
        if (result != 0) {
            return Result.fail(result == 1 ? "秒杀卷库存不足" : "不允许重复下单");
        }
        // 获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();

        return Result.ok(orderId);
    }

    // 阻塞队列实现异步处理
    /*     
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(),
            userId.toString());
        // 判断是否等于0
        if (result != 0) {
            return Result.fail(result == 1 ? "秒杀卷库存不足" : "不允许重复下单");
        }
        // 为0，有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        // 创建对象保存订单信息
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 创建一个阻塞队列，将订单信息添加进去
        orderTasks.add(voucherOrder);
    
        // 获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();
    
        return Result.ok(orderId);
    } 
    */

    // 基于数据库校验一人一单，库存是否足够的代码
    /*
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取优惠卷信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        // 判断优惠卷秒杀活动是否开启
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 活动时间在当前时间后，没有开启，返回异常
            return Result.fail("活动尚未开启");
        }
        // 判断优惠卷秒杀活动是否关闭
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 活动时间在当前时间前，已经结束，返回异常
            return Result.fail("活动已经结束");
        }
        // 活动开启，判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足，返回异常
            return Result.fail("秒杀卷库存不足");
        }
    
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        // 尝试获得锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    
        // 当负载均衡时，多个Tomact服务器并发会出现线程不安全问题
        // synchronized (userId.toString().intern()) {
        // // 获取代理对象
        // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        // return proxy.createVoucherOrder(voucherId);
        // }
    }
    */

    /*
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
    
        // 判断该用户是否下过单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // 用户已经下过单了
            return Result.fail("用户已经购买过一次");
        }
        // 库存充足，且活动开启，扣减库存 （带上了乐观锁）
        boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId)
            .gt("stock", 0).update();
        if (!success) {
            // 库存不足，返回异常
            return Result.fail("秒杀卷库存不足");
        }
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 保存订单到数据库
        save(voucherOrder);
        // 返回订单id
        return Result.ok(userId);
    }
    */

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();
        Long voucherId = voucherOrder.getVoucherId();
        // 判断该用户是否下过单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            // 用户已经下过单了
            log.error("用户已经购买过一次");
            return;
        }
        // 库存充足，且活动开启，扣减库存 （带上了乐观锁）
        boolean success = iSeckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId)
            .gt("stock", 0).update();
        if (!success) {
            // 库存不足，返回异常
            log.error("秒杀卷库存不足");
            return;
        }
        // 保存订单到数据库
        save(voucherOrder);
    }
}
