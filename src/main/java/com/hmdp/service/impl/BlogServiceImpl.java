package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BOLG_LIKE_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private UserServiceImpl userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBolgLike(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取用户id
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //判断用户是否已经点赞
        String key = BOLG_LIKE_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //如果用户未点赞
        if (score == null){
            //点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess){
                //stringRedisTemplate.opsForSet().add(key,userId.toString());
                //保存用户到Redis的set集合 zadd key value score
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if (isSuccess){
                //如果已经点赞，就移除用户信息
                //stringRedisTemplate.opsForSet().remove(key,userId.toString());
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBolgLikes(Long id) {
        String key = BOLG_LIKE_KEY + id;
        //先查询到用户的id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //判断是否为空
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> collect = userService.query().in("id",ids).last("ORDER BY FIELD(id,"+idStr+")").list().stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());
        //4.返回
        return  Result.ok(collect);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (isSuccess){
            //先查出所有的粉丝
            List<Follow> follows =followService.query().eq("follow_user_id", user.getId()).list();
            //推送笔记id给所有的粉丝
            for(Follow follow : follows){
                //获取粉丝的id
                Long userId = follow.getUserId();
                //推送到每个粉丝的邮箱
                String key = FEED_KEY + userId;
                stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
            }
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //先查绚出收件箱的每一个新推送
        String key = FEED_KEY + userId;//ZREVRANGEBYSCORE key Max Min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || CollUtil.isEmpty(typedTuples)){
            return Result.ok(Collections.emptyList());
        }
        //解析每一个新的推送,拿到博客的id，min，offset
        //用ArrayList将每个值存起来，并且提前声明容量，避免扩容影响性能
        ArrayList<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os =1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            ids.add(Long.valueOf(typedTuple.getValue()));
            long time = typedTuple.getScore().longValue();
            if(minTime == time){
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }
        //通过博客的id查询到所有的博客
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        blogs.forEach(blog -> {
            //查询博客是否被赞
            this.isBolgLike(blog);
            //查询博客相关用户
            this.queryBlogUser(blog);
        });
        //封装并且返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);

        return Result.ok(r);
    }

    public void isBolgLike(Blog blog){
        //1.获取用户id
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //用户未登录就无需查询点赞
            return;
        }
        Long userId = user.getId();
        //判断用户是否已经点赞
        String key = BOLG_LIKE_KEY + blog.getId();
        //Boolean isLike = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryBolgById(Long id) {
        //先查询到对应的博客
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("博客不存在");
        }
        isBolgLike(blog);
        queryBlogUser(blog);
        return Result.ok(blog);
    }



    public void queryBlogUser(Blog blog) {
        //通过博客获取到对应的用户id
        Long userId = blog.getUserId();
        //查询用户
        User user = userService.getById(userId);
        //获取用户昵称
        blog.setName(user.getNickName());
        //设置用户的头像
        blog.setIcon(user.getIcon());
    }
}
