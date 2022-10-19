package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 根据博客Id查询信息
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 给探店笔记点赞
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 探店笔记是否被点赞
     * @param blog
     */
    void isBlogLiked(Blog blog);

    /**
     * 设置博客对应的用户信息
     * @param blog
     */
    void queryBlogUser(Blog blog);

    /**
     * 探店笔记的点赞列表
     * @param id 博客Id
     * @return
     */
    Result queryBolgLikes(Long id);

    /**
     * 新增探店笔记
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    Result queryBlogOfFollow(Long max, Integer offset);
}
