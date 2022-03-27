package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.TimeoutUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 校验手机号
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            //2.不合符
        return Result.fail("手机号格式错误!");
        }
        //3.符合,生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码
//        session.setAttribute("code", code);

        //保存到redis,加业务前缀,好区分; 两分钟后过期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码
        log.debug("发送短信验证码成功, 验证码:{}", code);
        //返回ok
        return Result.ok();
    }

    /**
     * 手机号验证码登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            //2.不合符
            return Result.fail("手机号格式错误!");
        }
        //2.校验验证码
//        Object cacheCode = session.getAttribute("code");

        //2.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String code = loginForm.getCode();
        if(cacheCode == null || !(cacheCode.equals(code))) {
            //3.不一致,报错
            return Result.fail("验证码错误");
        }
        //4.一致,根据手机号查询用户,数据库 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if(user == null) {
            //6.不存在,创建新用户并且保存
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到session中
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class))

        //保存到Redis
        //随机生成token 作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //将User对象转为HashMap存储,(这个是简化后的User)
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //这里得确保每个值存储到map都是String
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储用户,前缀+token,还加上有效期 (这里的值必须是String String,不然会出现转换异常)StringRedisTemplate extends RedisTemplate<String, String>
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //设置有限期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    /**
     * 实现签到功能
     * @return
     */
    @Override
    public Result sign() {
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis setbit key offset(这个的意思是偏移量), true 1 false 0
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }


    /**
     * 实现连续签到功能
     */
    @Override
    public Result signCount() {
        //获取本月截止今天为止的所有的签到记录
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截止今天为止的所有签到记录,返回的是一个十进制数字,得要处理 sing:5:202203 get u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty())
            //没有签到结果
            return Result.ok(0);
        Long num = result.get(0);
        if (num == null || num == 0)
            //没有签到结果
            return Result.ok(0);
        //6.循环遍历
        int count = 0;
        while(true) {
            //让这个数字和1进行&(与)运算,得到数字的最后一个bit位,判断这个bit位是否为0
            if ((num & 1) == 0) {
                //如果为0,说明未签到,结束
                break;
            } else {
                //如果不为0,说明已签到,计数器+1
                count++;
            }
            //把得到的十进制数字,右移一位,抛弃最后一个bit位,继续下一个bit位,使用无符号右移
            num >>>= 1; //这里的意思是,先无符号右移动一位,然后在赋值给num,把原先的覆盖掉
        }
        return Result.ok(count);
    }

    /**
     * 创建新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(6));
        //2.保存用户
        save(user);
        return user;
    }
}
