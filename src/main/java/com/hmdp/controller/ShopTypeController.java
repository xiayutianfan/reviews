package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.HashUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;
    @Resource
    StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {

        List<String> range = stringRedisTemplate.opsForList().range("shop:type:", 0, -1);
        List<ShopType> shopTypeList = new ArrayList<>();
        if(!(range.isEmpty())) {
            for(String s : range) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                shopTypeList.add(shopType);
            }
            return Result.ok(shopTypeList);
        }

        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        List<String> list = new ArrayList<>();
        for(ShopType s : typeList) {
            list.add(JSONUtil.toJsonStr(s));
        }
        stringRedisTemplate.opsForList().rightPushAll("shop:type:", list);
        return Result.ok(typeList);
    }
}
