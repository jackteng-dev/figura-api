package dev.jackteng.figura.service.impl;

import dev.jackteng.figura.dto.UserRegistrationDto;
import dev.jackteng.figura.mapper.AdminUserMapper;
import dev.jackteng.figura.mapper.UserMapper;
import dev.jackteng.figura.pojo.PageBean;
import dev.jackteng.figura.pojo.User;
import dev.jackteng.figura.service.AdminUserService;
import dev.jackteng.figura.service.UserService;
import dev.jackteng.figura.utils.ListPageUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminUserServiceImpl implements AdminUserService {
    @Autowired
    private AdminUserMapper adminUserMapper;
    @Autowired
    private BCryptPasswordEncoder bCryptPasswordEncoder;
    @Override
    public User findByUserName(String username) {
        User u = adminUserMapper.findByUserName(username);
        return u;
    }
    @Override
    public User findAllByUserName(String username) {
        User u = adminUserMapper.findAllByUserName(username);
        return u;
    }

    @Override
    public void register(UserRegistrationDto user) {
        //加密
        String encodedpwd = bCryptPasswordEncoder.encode(user.getPassword());
        adminUserMapper.add(user,encodedpwd);
    }

    @Override
    public void update(User user) {
        user.setModifiedTime(LocalDateTime.now());
        adminUserMapper.update(user);
    }

    @Override
    public void updateAvatar(String avatarUrl,Integer id) {
        adminUserMapper.updateAvatar(avatarUrl,id);
    }

    @Override
    public void updatePwd(String newPwd,Integer id) {
        adminUserMapper.updatePwd(bCryptPasswordEncoder.encode(newPwd),id);
    }

    @Override
    public PageBean<User> findAllUser(Integer pageNum,Integer pageSize,String username,Integer[] role,String isStop) {
//        System.out.println(username);
        if(username != null && username.trim().length() > 0){
            username = username.trim();
        }
        int roleCount = (role == null?0:role.length);
//        PageHelper.startPage(pageNum,pageSize);
        List<User> userList = adminUserMapper.findAllUser(username,role,roleCount,isStop);
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
        adminUserMapper.stopAccount(id,isStop);
    }

    @Override
    public void updateLoginTime(Integer id) {
        adminUserMapper.updateLoginTime(id);
    }
}
