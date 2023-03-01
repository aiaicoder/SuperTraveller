package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClientRe;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.aspectj.weaver.ast.Var;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

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
    @Resource
    private CacheClientRe cacheClientRe;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = CacheThrough(id);
        //加锁解决缓存击穿
        //Shop shop = CacheHit(id);
        //Shop shop = CacheLogical(id);
            Shop shop = cacheClientRe.CacheLogical(
                    CACHE_SHOP_KEY, 1, Shop.class,10L,TimeUnit.SECONDS,this::getById);
        if (shop == null){
            return Result.fail("店铺不存在");
        }
        //缓存击穿
        return Result.ok(shop);
    }
    //缓存穿透
    public Shop CacheThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.根据id判断redis中是否带有缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否命中

        if (StrUtil.isNotBlank(shopJson)) {
            //命中直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        if (shopJson != null) {
            return null;
        }
        //未命中去数据库中查询
        Shop shop = this.getById(id);
        //判断是否存在
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //不存在
            return null;
        }
        //存在,先写入到redis
        //先把json转化为反序列化为对象
        String shop1 = JSONUtil.toJsonStr(shop);

        //写入到redis,并且加入超时时间
        stringRedisTemplate.opsForValue().set(key, shop1, CACHE_SHOP_TTL + RandomUtil.randomLong(1, 10), TimeUnit.MINUTES);
        //返回信息
        return shop;
    }

    public Shop CacheHit(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.根据id判断redis中是否带有缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            //命中直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            return null;
        }
        String keyLock = LOCK_SHOP_KEY + id;
        //双重校验在判断是否其他线程已经修改了数据库,再此查看是否命中
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //缓存重建
        try {
            boolean lock = getLock(keyLock);
            //尝试获取锁
            if (!lock) {
                //休眠
                Thread.sleep(50);
                return CacheHit(id);
            }
            ////模拟延迟
            //Thread.sleep(200);
            //未命中去数据库中查询
            Shop shop = this.getById(id);
            //判断是否存在
            if (shop == null) {
                return null;
            }
            //存在,先写入到redis
            //先把json转化为反序列化为对象
            String shop1 = JSONUtil.toJsonStr(shop);

            //写入到redis,并且加入超时时间
            stringRedisTemplate.opsForValue().set(key, shop1, CACHE_SHOP_TTL + RandomUtil.randomLong(1, 10), TimeUnit.MINUTES);
            //返回信息
            return shop;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            FreeLock(keyLock);
        }
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop CacheLogical(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.根据id判断redis中是否带有缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否命中
        if (StrUtil.isBlank(shopJson)) {
            //未命中
            return null;
        }
        //判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (expireTime.isAfter(LocalDateTime.now())){
            //如果没有过期就直接返回
            return shop;
        }
        //缓存重建
        String keyLock = LOCK_SHOP_KEY + id;
        //过期尝试获取锁
        if(getLock(keyLock)){
            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())){
                //如果没有过期就直接返回
                return shop;
            }
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    //重建缓存
                    this.redisShop2(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    FreeLock(keyLock);
                }
            });
        }
        return shop;
    }

    public void redisShop2(Long id,Long expireTime){
        String key = CACHE_SHOP_KEY + id;
        //查询店铺的信息
        Shop shop = getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //创建redisData对象
        RedisData redisData = new RedisData();
        //设置过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        //封装shop对象
        redisData.setData(shop);
        //将对象写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {

        //先获取到商铺的id
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if (x==null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from  = (current -1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis、按照距离排序、分页。结果：shopId,distance
        // GEOSEARCH key BYLONLAT X Y BYRADIUS 10 WITHDISTANCE
        String key = SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> search = stringRedisTemplate.opsForGeo().search(key, //指定的key
                GeoReference.fromCoordinate(x, y),//定位点的坐标
                new Distance(5000),//指定的周边距离
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)//这里直接到end就是从0-->end
        );
        //4.解析出redis
        if (search == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent();
        //4.1截取 from-end的部分
        List<Long> ids = new ArrayList<>(content.size());
        Map<String,Distance> map = new HashMap<>();
        content.stream().skip(from).forEach(result ->{
            //将id全部保存起来
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            //拿到距离
            Distance distance = result.getDistance();
            //将每一个店铺对应的相差距离对应到map中
            map.put(shopId,distance);
        });
        //5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        shops.forEach(shop -> {
            String key1 = String.valueOf(shop.getId());
            Distance distance = map.get(key1);
            shop.setDistance(distance.getValue());
        });
        //6.返回
        return Result.ok(shops);
    }


    public boolean getLock(String key) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(b);
    }

    public boolean FreeLock(String key) {
        Boolean delete = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(delete);
    }
}
