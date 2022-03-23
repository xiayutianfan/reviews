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
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.TimeoutUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
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