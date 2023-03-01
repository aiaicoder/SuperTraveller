package com.hmdp.service.impl;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import java.util.Collections;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> selectByRedis() {
        //先到redis中查询
        String key = CACHE_SHOP_TYPE_KEY;
        List<String> shopTypes = stringRedisTemplate.opsForList().range(key, 0, -1);
        //查出来List长度的存在就不为0表示存在
        if (shopTypes != null && !shopTypes.isEmpty()){
            return shopTypes.stream().
                    map((x) -> JSONUtil.toBean(x, ShopType.class)).
                    collect(Collectors.toList());
        }
        //没有就到数据库中查询
        List<ShopType> shopTypesList = this.query().orderByAsc("sort").list();
        //判断数据库查询出来的数据不为空
        if (shopTypesList.isEmpty()){
            return Collections.emptyList();
        }
        for (ShopType shopType : shopTypesList) {
            String s = JSONUtil.toJsonStr(shopType);
            shopTypes.add(s);
        }
        //查询到结果存入redis中
        stringRedisTemplate.opsForList().rightPushAll(key,shopTypes);
        //最后进行返回
        return shopTypesList;
    }
}
