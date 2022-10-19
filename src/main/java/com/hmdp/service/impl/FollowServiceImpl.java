package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取当前登陆用户  被关注用户Id followUserId
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        if (isFollow){
             //关注,新增
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess= save(follow);
            if (isSuccess){
                //把关注用户的id,放入redis的set集合中  sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //取关,删除 delete from tb_follow where userId = ? and follow_user_id = ?
            boolean isSuccess = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowUserId, followUserId)
                    .eq(Follow::getUserId, userId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
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

    /**
     * 求共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        //1.当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        //2.求交集
        String key2 = "follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(userIds);
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
