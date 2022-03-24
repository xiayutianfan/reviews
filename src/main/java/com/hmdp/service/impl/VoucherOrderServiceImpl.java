package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //获取脚本的路径
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //通知脚本我们接收的返回值是什么
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    /**
     * 使用异步的方法来实现秒杀,使用到了lua脚本
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //这里能拿到userID是因为登录的时候,在登录拦截器里保存下来了.
        Long userId = UserHolder.getUser().getId();
        //1 执行lua脚本;没有key(因为key在lua脚本里,我们自己手动加上了),不要传null,传一个空集合;
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString());
        int r = result.intValue();
        //2 判断结果是否为0
        if(r != 0) {
            //2.1 不为0,代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //2.2 为0,有购买资格,把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        // TODO 保存阻塞队列
        //3 返回订单id
        return Result.ok(orderId);
    }


    /**
     * 实现秒杀优惠券的功能
     * 旧版本,
     * 新版本已经迁移到 基于lua脚本实现异步秒杀
     * @param voucherId
     * @return
     */
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否已经开始,秒杀的开始时间比当前时间还大,那么就是还没开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //尚未开始
            return Result.fail("秒杀尚未开始~");
        }
        //3.判断秒杀是否已经结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束~");
        }
        //4.判断库存是否充足
        if(voucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足~");
        }

        return createVoucherOrder(voucherId);
    }


    *//**
     * 使用Redisson来解决锁的问题
     * @param voucherId
     * @return
     *//*

    public Result createVoucherOrder(Long voucherId) {
        //一人一张优惠券
        //这里之所以能拿到用户id是因为前面有个拦截器
        Long userId = UserHolder.getUser().getId();

        //创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        //空参的话,默认30秒过期
        //有一个是三个参数的,获取锁的最大等待时间(期间会重试), 锁自动释放时间, 时间单位
        boolean isLock = redisLock.tryLock();
        //判断锁
        if (!isLock) {
            //获取锁失败,直接返回失败或者重试
            return Result.fail("不允许重复下单~");
        }

        try {
            //根据用户id和优惠券id查询订单的数量
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断用户是否已经有优惠券了
            if (count > 0) {
                //用户已经购买过了
                return Result.fail("已经购买过了~");
            }

            //5.扣减库存,setSql("stock = stock - 1")更新的字段,eq("voucher_id", voucherId)更新条件 这两个得要相等,
            //gt("stock", 0)乐观锁
            //sql语言大概是这样的: update xxx set stock = stock - 1 where voucher_id = voucher_id and stock > 0
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                //扣减失败
                return Result.fail("库存不足~");
            }

            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //6.1订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //6.2用户id
            voucherOrder.setUserId(userId);
            //6.3代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //7.返回订单id
            return Result.ok(orderId);
        } finally {
            //释放锁
            redisLock.unlock();
            ;
        }
    }*/


    /**
     * 使用分布式锁来解决一人一张优惠券的问题
     *
     * 淘汰了,使用Redisson来解决锁的问题了.
     */
 /*
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一张优惠券
        //这里之所以能拿到用户id是因为前面有个拦截器
        Long userId = UserHolder.getUser().getId();

        //创建锁对象
        SimpleRedisLock redisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
        //尝试获取锁, 过期时间为10秒
        boolean isLock = redisLock.tryLock(10L);
        //判断锁
        if(!isLock) {
            //获取锁失败,直接返回失败或者重试
            return Result.fail("不允许重复下单~");
        }

        try {
            //根据用户id和优惠券id查询订单的数量
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断用户是否已经有优惠券了
            if (count > 0) {
                //用户已经购买过了
                return Result.fail("已经购买过了~");
            }

            //5.扣减库存,setSql("stock = stock - 1")更新的字段,eq("voucher_id", voucherId)更新条件 这两个得要相等,
            //gt("stock", 0)乐观锁
            //sql语言大概是这样的: update xxx set stock = stock - 1 where voucher_id = voucher_id and stock > 0
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                //扣减失败
                return Result.fail("库存不足~");
            }

            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //6.1订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //6.2用户id
            voucherOrder.setUserId(userId);
            //6.3代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //7.返回订单id
            return Result.ok(orderId);
        } finally {
            //释放锁
            redisLock.unlock();;
        }
    }*/






    /**
     *实现秒杀优惠券的功能,这里得要加悲观锁
     * synchronized这个锁,只能保证在一个JVM,如果是集群模式下,会失效.
     *
     * 使用分布式锁来解决
     */
/*    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一张优惠券
        //这里之所以能拿到用户id是因为前面有个拦截器
        Long userId = UserHolder.getUser().getId();

        //加锁,userId.toString()是不行的,因为底层每次都new一个新的,加上intern后会去常量池找.
        synchronized (userId.toString().intern()) {

            //根据用户id和优惠券id查询订单的数量
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断用户是否已经有优惠券了
            if (count > 0) {
                //用户已经购买过了
                return Result.fail("已经购买过了~");
            }

            //5.扣减库存,setSql("stock = stock - 1")更新的字段,eq("voucher_id", voucherId)更新条件 这两个得要相等,
            //gt("stock", 0)乐观锁
            //sql语言大概是这样的: update xxx set stock = stock - 1 where voucher_id = voucher_id and stock > 0
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                //扣减失败
                return Result.fail("库存不足~");
            }

            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //6.1订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //6.2用户id
            voucherOrder.setUserId(userId);
            //6.3代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //7.返回订单id
            return Result.ok(orderId);
        }
    }*/

}
