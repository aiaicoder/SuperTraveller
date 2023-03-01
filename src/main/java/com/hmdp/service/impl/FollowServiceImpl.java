package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FOLLOWS_KEY;

/**
 * @author 小新
 * date 2023/2/27
 * @apiNode
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserServiceImpl userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        //先获取登录用户
        UserDTO user = UserHolder.getUser();
        //获取用户的id
        Long userId = user.getId();
        String key = FOLLOWS_KEY+ userId;
        //判断用户是否关注
        if (Boolean.TRUE.equals(isFollow)){
            //保存关注信息
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess){
                stringRedisTemplate.opsForSet().add(key, String.valueOf(followUserId));
            }
        }else {
            //取关
            boolean isSuccess = remove(new UpdateWrapper<Follow>().eq("user_Id", userId).eq("follow_user_id", followUserId));
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,String.valueOf(followUserId));
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //3.判断
        return Result.ok(count>0);
    }

    /**
     *
     * @param id 你查看博主的id
     * @return 返回共同的关注
     */
    @Override
    public Result followCommons(Integer id) {
        //获取当前用户的id
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWS_KEY+ userId;
        //查看博主的用户
        String key1 = FOLLOWS_KEY+ id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key1);
        if (intersect == null || intersect.isEmpty()){
            //无交集返回空列表
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> collect = userService.listByIds(ids).stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());
        return Result.ok(collect);
    }

}
