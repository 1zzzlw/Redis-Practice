package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    // 注入工具类
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透的代码
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿的代码
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿的代码
        // Shop shop = queryWithLogicalExpire(id);

        // 工具类中的缓存穿透代码
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL,
        // TimeUnit.MINUTES);

        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L,
            TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在!");
        }

        // 返回
        return Result.ok(shop);
    }

    // private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期解决缓存击穿的代码，不用考虑缓存穿透
    /*
    public Shop queryWithLogicalExpire(Long id) {
        // 查看Redis中是否存在
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            // Redis中不存在，直接返回
            return null;
        }
        // Redis中缓存存在，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回店铺信息
            return shop;
        }
        // 过期重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            // 获取锁成功，开启线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 获取锁失败，返回过期信息
        return shop;
    }
    */

    // 互斥锁解决缓存击穿的代码
    /*     public Shop queryWithMutex(Long id) {
        // 查看Redis中是否存在
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // Redis中存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否为空值(缓存穿透关键步骤)
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
    
        // 查询Redis缓存未命中，缓存击穿问题
    
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取锁失败
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 如果Redis中不存在，查询数据库是否存在
            shop = getById(id);
            // Thread.sleep(200);
            if (shop == null) {
                // 将空值写入Redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 数据库不存在
                return null;
            }
            // 数据库中存在此店铺，将店铺信息存入Redis中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放互斥锁
            unlock(lockKey);
        }
    
        // 返回
        return shop;
    } */

    // 解决缓存穿透的代码
    /*     
        public Shop queryWithPassThrough(Long id) {
        // 查看Redis中是否存在
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            // Redis中存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
    
         // 判断缓存时空值还是null，是null就说明缓存未命中，执行数据库查询，否则未空值，直接返回错误
    
        // 判断命中的是否为空值
        if (shopJson != null) {
            // 返回一个错误信息
            return null;
        }
        // 如果Redis中不存在，查询数据库是否存在
        Shop shop = getById(id);
    
         // 缓存穿透关键步骤，缓存未命中并且数据库中不存在时，缓存加入空值 //
    
        if (shop == null) {
            // 将空值写入Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 数据库不存在
            return null;
        }
        // 数据库中存在此店铺，将店铺信息存入Redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 返回
        return shop;
    }
    */

    /*
    // 获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    
    // 释放锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 查询店铺数据
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
    */

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 必须先处理数据库中的数据
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
