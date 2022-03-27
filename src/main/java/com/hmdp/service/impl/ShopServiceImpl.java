package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.DeleteProvider;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询店铺
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {

        //使用工具类解决缓存穿透 i -> getById(i) 可以写成 this::getById
        Shop shop1 = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, i -> getById(i),
                RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //使用工具类来解决缓存击穿.(这个有bug,得要商品预热,不然商品为null;具体预热,看测试类的testSaveShop这个方法)
        Shop shop = cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, id, Shop.class,
                this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if(shop != null) {
            return Result.ok(shop);
        }else if(shop1 != null) {
            return Result.ok(shop1);
        }
        return Result.fail("店铺不存在~");
    }


    /**
     * 更新数据,先更新数据库,再删除缓存
     * 考虑事务回滚
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null) {
            return Result.fail("店铺id不能为空~");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 查询附近店铺
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询,按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数,(从哪开始,从哪结束)
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis 按照距离排序 分页 结果:shopId distance
        // GEOSEARCH key bylonlat x y byradius 10 withdistance
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().
                search(key, GeoReference.fromCoordinate(x, y), new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //4.解析出id
        if (results == null)
            return Result.ok(Collections.emptyList());
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from)
            //没有下一页了,结束
            return Result.ok(Collections.emptyList());
        //截取(skip是跳过), 从from 到 end 的部分, 因为上面查询到的是 从0 到 end个
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        String idStr = StrUtil.join(",", ids);
        //5.根据id查询Shop
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6返回
        return Result.ok(shops);
    }


    /**
     * 使用逻辑过期来解决缓存击穿
     */
/*    public Shop queryWithLogicalExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.如果为空,直接返回
        if(StrUtil.isBlank(shopJson)) {
            return null;
        }

        //命中,需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        //这里是JSONObject是因为,上面为json的反序列化得到的
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断逻辑是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            //未过期,直接返回店铺信息
            return shop;
        }
        //已过期,需要重建
        //获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //判断锁是否获取成功
        if(isLock) {
            //成功,开启独立线程,实现缓存重建
            CacheClient.CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存,这里只是为了测试设置为20秒,实际开发建议根据业务需求而设置时间
                    saveShopRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //返回过期的店铺信息
        return shop;
    }*/

/*
    *//**
     *解决缓存击穿和缓存穿透
     *//*
    @Deprecated
    public Shop queryWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在,空字符串不会进入if里
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断是否命中的是空值,上面的那个if已经判断过了,如果是空值,并且能走到这里,直接返回Null
        if (shopJson != null) {
            //返回错误信息
            return null;
        }

        //开始实现缓存重建
        //获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //判断是否获取成功
            if(!isLock) {
                //失败,则休眠并重试
                Thread.sleep(50);
                //递归重试
                return queryWithMutex(id);
            }

            //4.根据id查询数据库
            shop = getById(id);
            //5.返回错误
            if(shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.先写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            //往外抛,不用管
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unLock(lockKey);
        }

        //7.返回
        return shop;
    }


    *//**
     * 解决缓存穿透
     * @return
     *//*
    @Deprecated
    public Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在,空字符串不会进入if里
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        //判断是否命中的是空值,上面的那个if已经判断过了,如果是空值,并且能走到这里,直接返回Null
        if (shopJson != null) {
            //返回错误信息
            return null;
        }

        //4.不存在,根据id查询数据库
        Shop shop = getById(id);
        //5.不存在,返回错误
        if(shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6.存在,先写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }

    *//**
     * 使用逻辑过期来解决缓存击穿
     *//*
    @Deprecated
    public void saveShopRedis(Long id, Long expireSeconds) {
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间

        //3.写入Redis
        RedisData redisData = new RedisData();
        //对象传入进去
        redisData.setData(shop);
        //设置过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入Redis,这里把redisData写入进去,因为包含了Shop和逻辑过期时间,使用json转换一下
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }




    *//**
     * 互斥锁,解决缓存击穿问题
     * 获取锁
     *//*
    @Deprecated
    private boolean tryLock(String key) {
        //相当于redis的 setnx 设置有效期为10秒钟,根据业务而定
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //如果直接返回,会自动拆箱,有可能空指针异常
        return BooleanUtil.isTrue(flag);
    }
    *//**
     * 释放锁
     *//*
    @Deprecated
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }*/

}
