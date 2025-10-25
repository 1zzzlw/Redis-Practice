package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 保存订单到数据库
        save(voucherOrder);
        // 返回订单id
        return Result.ok(userId);
    }

}
