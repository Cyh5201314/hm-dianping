package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前登陆用户  被关注用户Id followUserId
        Long userId = UserHolder.getUser().getId();
        if (isFollow){
             //关注,新增
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            save(follow);
        }else {
            //取关,删除 delete from tb_follow where userId = ? and follow_user_id = ?
            remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowUserId,followUserId)
                    .eq(Follow::getUserId,userId));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1.获取当前登陆用户  被关注用户Id followUserId
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注 select from tb_follow where userId = ? and follow_user_id = ?
        Integer count = lambdaQuery().eq(Follow::getFollowUserId, followUserId)
                .eq(Follow::getUserId, userId).count();
        return Result.ok(count>0);
    }
}
