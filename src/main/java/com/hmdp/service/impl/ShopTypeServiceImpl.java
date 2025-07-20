package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = "cache:shop-type:list";
        // 1、从redis中查询商铺类型缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        // 2、判断是否存在
        if (StrUtil.isNotBlank(shopTypeJson)) {
            // 3、存在，直接返回
            List<ShopType> shopTypeList = JSONUtil.toList(JSONUtil.parseArray(shopTypeJson), ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 判断命中的是否是空值
        if (shopTypeJson != null) {
            // 返回错误信息
            return Result.fail("店铺类型不存在！");
        }
        // 4、redis中不存在，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        // 5、数据库中不存在，返回错误
        if (shopTypeList == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺类型不存在！");
        }
        // 6、存在，保存到redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7、返回
        return Result.ok(shopTypeList);
    }

}
