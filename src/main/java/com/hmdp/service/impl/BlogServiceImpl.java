package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
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
        Long userId = UserHolder.getUser().getId();
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
        String key = "blog:liked:"+id;
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
}
