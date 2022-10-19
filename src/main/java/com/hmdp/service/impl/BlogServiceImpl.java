package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
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
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog==null) {
            return Result.fail("博客不存在");
        }
        //2.查询博客相关的用户
        queryBlogUser(blog);
        //3.查询博客是否被当前用户点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    public void isBlogLiked(Blog blog) {
        //1.获取登陆用户
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO==null){
            //用户未登录,无序查询是否点赞
            return;
        }
        Long userId = userDTO.getId();
        //2.判断当前登陆用户是否已经点赞
        String key = "blog:liked:"+blog.getId();
        Double isMember = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        blog.setIsLike(isMember!=null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取登陆用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前登陆用户是否已经点赞
        String key = BLOG_LIKED_KEY+id;
        //isMember 判断set集合中是否存在该value(用户Id)的值
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            //3.  未点赞
            //3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked +1").eq("id", id).update();
            //3.2 保存到redis当中 zadd key value score
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4.如果点赞
            //4.1数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked -1").eq("id", id).update();
            //4.2redis点赞数的set集合移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    public void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


    @Override
    public Result queryBolgLikes(Long id) {
        //1.redis中 查询top5的点赞用户
        String key = BLOG_LIKED_KEY+id;
        //2.最早点赞的用户Id 默认就是按照时间戳升序,所以取0-4
        Set<String> top5Id = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5Id==null||top5Id.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //3.查询出用户信息
        List<Long> userIds = top5Id.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(userIds);
        //4.将用户信息转为DTO,隐藏敏感信息
        List<UserDTO> userDTOS = users.stream().
                map( user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    /**
     * 保存探店笔记
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店笔记
        save(blog);
        //3.查询作者的所有粉丝,谁关注了当前用户
        //select *  from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.lambdaQuery()
                .eq(Follow::getFollowUserId, user.getId()).list();
        //4.推荐笔记id给所有粉丝
        for (Follow follow : follows) {
            //4.1获取粉丝ID
            Long userId = follow.getUserId();
            //4.2推送
            String key = "feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：blogId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        os = minTime == max ? os : os + offset;
        // 5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            queryBlogUser(blog);
            // 5.2.查询blog是否被点赞
            isBlogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }
}
