package com.hb0730.boot.admin.token.impl;

import com.google.gson.*;
import com.hb0730.boot.admin.security.model.User;
import com.hb0730.boot.admin.token.AbstractTokenService;
import com.hb0730.boot.admin.token.configuration.TokenProperties;
import com.hb0730.commons.cache.Cache;
import com.hb0730.commons.json.gson.GsonUtils;
import com.hb0730.commons.lang.StringUtils;
import com.hb0730.commons.lang.date.DateUtils;
import org.springframework.security.core.userdetails.UserDetails;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * redis 实现
 *
 * @author bing_huang
 * @since 3.0.0
 */
public class RedisTokenServiceImpl extends AbstractTokenService {
    @Resource(name = "redisCache")
    private Cache<String, Object> cache;

    public RedisTokenServiceImpl(TokenProperties properties) {
        super(properties);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        String accessToken = getAccessToken(request);
        if (StringUtils.isNotBlank(accessToken)) {
            String accessTokenKey = getAccessTokenKey(accessToken);
            String userTokenKey = String.valueOf(cache.get(accessTokenKey).orElseGet(() -> ""));
            Optional<Object> optional = cache.get(getUserTokenKey(userTokenKey));
            if (optional.isPresent()) {
                try {
                    String json = GsonUtils.objectToJson(optional.get());
                    // 解决序列化date 转long的问题，可能需要设置cache 序列化方案
                    GsonBuilder gb = new GsonBuilder();
                    gb.registerTypeAdapter(Date.class, new DateDeserializer());
                    Gson gson = gb.create();
                    return GsonUtils.jsonToObject(json, User.class, gson);
                } catch (JsonParseException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    @Override
    public String createAccessToken(User user) {
        String token = UUID.randomUUID().toString();
        user.setToken(token);
        refreshAccessToken(user);
        String accessToken = extractKey(token);
        String accessTokenKey = getAccessTokenKey(accessToken);
        cache.put(accessTokenKey, token, super.getProperties().getExpireTime(), super.getProperties().getTimeUnit());
        return accessToken;
    }

    @Override
    public void refreshAccessToken(User user) {
        // 获取过期时长
        long expire = TimeUnit.MILLISECONDS.convert(super.getProperties().getExpireTime(), super.getProperties().getTimeUnit());
        // 设置登录时长与过期时间
        user.setLoginTime(DateUtils.now());
        Date expireTim = DateUtils.add(user.getLoginTime(), expire, TimeUnit.MILLISECONDS);
        user.setExpireTime(expireTim);
        //缓存用户
        String userTokenKey = getUserTokenKey(user.getToken());
        cache.put(userTokenKey, user, expire, TimeUnit.MILLISECONDS);
    }

    @Override
    public void verifyAccessToken(HttpServletRequest request) {
        User loginUser = getLoginUser(request);
        if (Objects.nonNull(loginUser)) {
            Date expireTime = loginUser.getExpireTime();
            Date currentTime = DateUtils.now();
            long refreshTime = TimeUnit.MILLISECONDS.convert(super.getProperties().getRefreshTime(), super.getProperties().getTimeUnit());
            // 校验 刷新
            if (expireTime.getTime() - currentTime.getTime() <= refreshTime) {
                String accessToken = getAccessToken(request);
                String accessTokenKey = getAccessTokenKey(accessToken);
                cache.put(accessTokenKey, loginUser.getToken(), super.getProperties().getExpireTime(), super.getProperties().getTimeUnit());
                refreshAccessToken(loginUser);
            }
        }
    }

    @Override
    public void delLoginUser(HttpServletRequest request) {
        String accessToken = getAccessToken(request);
        if (StringUtils.isNotBlank(accessToken)) {
            deleteAccessToken(accessToken);
        }

    }

    @Override
    public void deleteAccessToken(String accessToken) {
        if (StringUtils.isNotBlank(accessToken)) {
            String token = String.valueOf(cache.get(getAccessTokenKey(accessToken)).orElseGet(() -> ""));
            cache.delete(getUserTokenKey(token));
            cache.delete(getAccessTokenKey(accessToken));
        }
    }

    @Override
    public Map<String, UserDetails> getOnline() {
        return null;
    }


    private static class DateDeserializer implements JsonDeserializer<java.util.Date> {

        @Override
        public Date deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return new java.util.Date(json.getAsJsonPrimitive().getAsLong());
        }
    }

    public static class DateSerializer implements JsonSerializer<Date> {
        @Override
        public JsonElement serialize(Date src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getTime());
        }
    }
}
