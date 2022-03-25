package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
@Slf4j
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

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        //获取脚本的路径
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //通知脚本我们接收的返回值是什么
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //这个注解的意思:这个类初始化完毕之后,执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 从消息队列中获取
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            String queueName = "stream.orders";
            while(true) {
                try {
                    //1.获取消息队列中的订单信息 xreadgroup  group    g1 c1    count 1     block 2000  streams s1    >
                    //                       获取      从哪个组获取 组名 消费者 读取消息数量 等待多少毫秒  队列名称      读取未消费的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断订单信息是否为空
                    if(list == null || list.isEmpty()) {
                        //如果为空,说明没有消息,继续下一次循环
                        continue;
                    }
                    //解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    //3.创建订单信息
                    createVoucherOrder(voucherOrder);
                    //4.确认消息 XACK s1 g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlerPendingList();
                }
            }
        }

        private void handlerPendingList() {
            String queueName = "stream.orders";
            while(true) {
                try {
                    //1.获取Pending-List中的订单信息 xreadgroup  group    g1 c1    count 1 streams s1    0
                    //                               获取   从哪个组获取 组名 消费者 读取消息数量   队列名称      读取未消费的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断订单信息是否为空
                    if(list == null || list.isEmpty()) {
                        //如果为空,说明pending-list没有异常消息,结束循环
                        break;
                    }
                    //解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);

                    //3.创建订单信息
                    createVoucherOrder(voucherOrder);
                    //4.确认消息 XACK s1 g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending订单异常", e);
                    try {
                        //避免频率太高,休眠一下
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }


    /**
     * 异步创建订单信息
     */
    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        //空参的话,默认30秒过期
        //有一个是三个参数的,获取锁的最大等待时间(期间会重试), 锁自动释放时间, 时间单位
        boolean isLock = redisLock.tryLock();
        //判断锁
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }

        try {
            //根据用户id和优惠券id查询订单的数量
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //判断用户是否已经有优惠券了
            if (count > 0) {
                log.error("不允许重复下单");
                return;
            }

            //5.扣减库存,setSql("stock = stock - 1")更新的字段,eq("voucher_id", voucherId)更新条件 这两个得要相等,
            //gt("stock", 0)乐观锁
            //sql语言大概是这样的: update xxx set stock = stock - 1 where voucher_id = voucher_id and stock > 0
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                //扣减失败
                log.error("库存不足~");
                return;
            }
            save(voucherOrder);
        } finally {
            //释放锁
            redisLock.unlock();
        }
    }

    /**
     * 使用消息队列来优化功能,基于lua脚本
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        //这里能拿到userID是因为登录的时候,在登录拦截器里保存下来了.
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        //1 执行lua脚本;没有key(因为key在lua脚本里,我们自己手动加上了),不要传null,传一个空集合;
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        int r = result.intValue();
        //2 判断结果是否为0
        if(r != 0) {
            //2.1 不为0,代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //3 返回订单id
        return Result.ok(orderId);
    }







    /**
     * 从阻塞队列中获取
     */
    /*
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    //1.不断获取队列中的订单信息,没有订单信息的话,会一直阻塞在这里
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.创建订单信息
                    createVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
     */



    /**
     * 使用异步的方法来实现秒杀,使用到了lua脚本
     * @param voucherId
     * @return
     */
    /*
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
        // TODO 保存阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 用户id
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 放入阻塞队列
        orderTasks.add(voucherOrder);

        //3 返回订单id
        return Result.ok(orderId);
    }*/


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
