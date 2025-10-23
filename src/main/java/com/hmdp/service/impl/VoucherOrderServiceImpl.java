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

    @Override
    @Transactional
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
        // 库存充足，且活动开启，扣减库存
        boolean success =
            iSeckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).update();
        if (!success) {
            // 库存不足，返回异常
            return Result.fail("秒杀卷库存不足");
        }
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long userId = redisIdWorker.nextId("order");
        voucherOrder.setId(userId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        // 保存订单到数据库
        save(voucherOrder);
        // 返回订单id
        return Result.ok(userId);
    }
}
