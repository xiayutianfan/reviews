package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

//为了解决缓存击穿,逻辑过期类
@Data
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;
    private Object data;
}
