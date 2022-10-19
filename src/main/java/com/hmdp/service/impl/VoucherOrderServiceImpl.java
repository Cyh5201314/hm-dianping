package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService SeckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private IVoucherOrderService proxy;

    /**
     * 分布式锁
     */
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue <VoucherOrder> orderTasks =
            new ArrayBlockingQueue<>(1024*1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR
            = Executors.newSingleThreadExecutor();


    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //从阻塞队列中取出订单消息,对数据库进行修改
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true){
                try {
                    //1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常:",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.获取锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock(); //无参的是获取锁失败立即返回和超过30S自动释放锁
        if (!isLock) {
             //脚步中已经判断过了,这里其实也可以不判断
             //判断也可以,用来兜底
             log.error("不允许重复下单");
             return;
        }
        try{
             proxy.createVoucherr(voucherOrder);
        }finally {
            //必须释放锁
            lock.unlock();
        }

    }

    /**
     * 用消息队列 改造秒杀代码
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);
        //3.给实例变量赋值 = 获取代理对象
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);
    }


//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券(秒杀的)
//        SeckillVoucher voucher = SeckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        //4.库存是否充足
//        if (voucher.getStock() < 1) {
//            //库存不足
//            return Result.fail("库存不足!");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //这种方法加锁,在多节点集群方式下会出现问题 因为把锁放在redis中
//        //实现一个用户一单,只需要锁用户Id
//        /**synchronized (userId.toString().intern()){
//            //拿到代理对象,不然@Transaction事务会失效
//            //因为this.createVoucherr 的this是目标对象(真实对象) 而非代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherr(voucherId);
//        }**/
////        simpleRedisLock lock = new simpleRedisLock(stringRedisTemplate,"order:"+userId);
//
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //锁的时间与业务有关
//        //获取分布式锁
//        boolean isLock = lock.tryLock(); //无参的是获取锁失败立即返回和超过30S自动释放锁
//        if (!isLock) {
//            // 获取锁失败 返回错误信息 或者重试
//            // 但在这里是优惠券秒杀功能,获取锁失败说明有人在重复下单 直接返回错误信息就行
//            return Result.fail("不允许重复下单");
//        }
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherr(voucherId);
//        }finally {
//            //必须释放锁
//            lock.unlock();
//        }
//
//    }

    @Transactional
    public void createVoucherr(VoucherOrder voucherOrder){
        //5 一人一单
        Long userId = voucherOrder.getUserId();
        //5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2 判断订单是否存在
        //不太可能出现重复了,之前判断过
        if (count>0){
            log.error("用户已经购买过一次");
            return;
        }

        //6.扣减库存  必须库存大于0的时候才可以减少库存
        boolean success = SeckillVoucherService.update()
                .setSql("stock = stock -1")  // set stock = stock -1
                .eq("voucher_id", voucherOrder.getVoucherId())
//                .eq("stock",voucher.getStock())  同一时刻只会有一个线程执行成功
                .gt("stock",0) //只有库存大于0,才能扣减库存
                .update();
        if (!success){
            //不太可能出现库存不足,redis脚步中已经判断过了
            log.error("库存不足");
            return ;
        }
        //7.创建订单
        save(voucherOrder);
    }
}
