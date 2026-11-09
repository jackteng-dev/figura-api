package com.shop.elpsy.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.shop.elpsy.dto.UserRegistrationDto;
import com.shop.elpsy.mapper.UserMapper;
import com.shop.elpsy.pojo.PageBean;
import com.shop.elpsy.pojo.User;
import com.shop.elpsy.service.UserService;
import com.shop.elpsy.utils.ListPageUtil;
import com.shop.elpsy.utils.ThreadLocalUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;
    @Override
    public User findByUserName(String username) {
        User u = userMapper.findByUserName(username);
        return u;
    }

    @Override
    public void register(UserRegistrationDto user) {
        //加密
        String encodedpwd = bCryptPasswordEncoder.encode(user.getPassword());
        userMapper.add(user,encodedpwd);
    }

    @Override
    public void update(User user) {
        user.setModifiedTime(LocalDateTime.now());
        userMapper.update(user);
    }

    @Override
    public void updateAvatar(String avatarUrl,Integer id) {
        userMapper.updateAvatar(avatarUrl,id);
    }

    @Override
    public void updatePwd(String newPwd,Integer id) {
        userMapper.updatePwd(bCryptPasswordEncoder.encode(newPwd),id);
    }

    @Override
    public PageBean<User> findAllUser(Integer pageNum,Integer pageSize,String username,Integer[] role,String isStop) {
//        System.out.println(username);
        if(username != null && username.trim().length() > 0){
            username = username.trim();
        }
        int roleCount = (role == null?0:role.length);
//        PageHelper.startPage(pageNum,pageSize);
        List<User> userList = userMapper.findAllUser(username,role,roleCount,isStop);
        ListPageUtil<User> listPageUtil = new ListPageUtil<>(userList,pageNum,pageSize);
//        System.out.println(listPageUtil.getPageList().size());
        List<User> pageList = listPageUtil.getPageList();
//        PageHelper.startPage(userList);
//        System.out.println("userList.size:::"+userList.size());
//        System.out.println("userList:::"+userList);
        PageBean<User> pb = new PageBean<>();
        pb.setTotal((long) listPageUtil.getTotalCount());
        pb.setItems(listPageUtil.getPageList());
        return pb;
    }

    @Override
    public void stopAccount(Integer id,String isStop) {
        userMapper.stopAccount(id,isStop);
    }

    @Override
    public void updateLoginTime(Integer id) {
        userMapper.updateLoginTime(id);
    }
}
