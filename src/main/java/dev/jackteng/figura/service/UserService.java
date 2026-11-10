package dev.jackteng.figura.service;

import dev.jackteng.figura.dto.UserRegistrationDto;
import dev.jackteng.figura.pojo.PageBean;
import dev.jackteng.figura.pojo.User;
import dev.jackteng.figura.utils.ListPageUtil;

import java.util.List;

public interface UserService {
    //用戶名查詢用戶
    User findByUserName(String username);
    //註冊
    void register(UserRegistrationDto user);

    //更新
    void update(User user);

    //更新頭像
    void updateAvatar(String avatarUrl,Integer id);

    //更新密碼
    void updatePwd(String newPwd,Integer id);

    //查詢所有帳號
    PageBean<User> findAllUser(Integer pageNum, Integer pageSize, String username, Integer[] role, String isStop);

    //停用帳號
    void stopAccount(Integer id,String isStop);

    //更新用戶登入時間
    void updateLoginTime(Integer id);
}
