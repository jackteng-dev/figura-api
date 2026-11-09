package com.shop.elpsy.mapper;

import com.shop.elpsy.dto.UserRegistrationDto;
import com.shop.elpsy.pojo.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserMapper {
    //用戶名查詢用戶
//    @Select("select * from user where username = #{username}")
    User findByUserName(String username);

    //創建用戶
    @Insert("insert into user(username,password,first_name,last_name,email,telephone,role,created_time,modified_time,is_stop)" +
            " values(#{user.username},#{encodedpwd},#{user.firstName},#{user.lastName},#{user.email},#{user.telephone},#{user.role},now(),now(),#{user.isStop})")
    void add(UserRegistrationDto user, String encodedpwd);

//    @Update("update user set first_name=#{firstName},last_name=#{lastName},email=#{email},telephone=#{telephone},modified_time=#{modifiedTime} where id=#{id}")
    void update(User user);

    @Update("update user set user_pic=#{avatarUrl},modified_time=now() where id = #{id}")
    void updateAvatar(String avatarUrl,Integer id);

    @Update("update user set password=#{password},modified_time=now() where id=#{id}")
    void updatePwd(String password, Integer id);

//    @Select("select * from user")
    List<User> findAllUser(String username,Integer[] role,Integer roleCount,String isStop);

    //停用帳號
    @Update("update user set is_stop = #{isStop} where id = #{id}")
    void stopAccount(Integer id,String isStop);

    //更新登入時間
    @Update("update user set lastLogin_time = now() where id = #{id}")
    void updateLoginTime(Integer id);
}
