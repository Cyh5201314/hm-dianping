package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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
}
