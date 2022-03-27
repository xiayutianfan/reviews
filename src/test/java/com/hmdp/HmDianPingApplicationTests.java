package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.apache.ibatis.annotations.Result;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testIdWorker() {
        long test = redisIdWorker.nextId("test");
        System.out.println(test);
    }

/*    @Test
    void testSaveShop() {
        shopService.saveShopRedis(1L, 10L);
    }*/

    /**
     * 商品预热.
     */
    @Test
    void testSaveShop() {
        //这里输入热门商品的id,假如为 1
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }


    /**
     * 加载附近店铺
     */
    @Test
    void loadShopData() {
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //2.把店铺信息分组,按照typeId来分组,id一致分到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批完成写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            //获取同类型的店铺集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //写入redis GEOADD key 经度 维度 member
            for (Shop shop : value) {
                //stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    /**
     * UV统计,测试百万条数据统计,基于redis 实现
     */
    @Test
    void testHyperLogLog() {
        //每个1000条就发送
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            //永远都是0~999
            j = i % 1000;
            values[j] = "user_" + i;
            //判断数组是否满了
            if (j == 999) {
                //发送到redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        //统计数量
        Long hl2 = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println(hl2);
    }


}
