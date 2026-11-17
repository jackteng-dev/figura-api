package dev.jackteng.figura.mapper;

import dev.jackteng.figura.pojo.Customer;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CustomerMapper {
    @Select("SELECT * FROM customer WHERE username=#{username}") Customer byUsername(String username);
    @Select("SELECT * FROM customer WHERE email=#{email}") Customer byEmail(String email);
    @Select("SELECT * FROM customer WHERE phone=#{phone}") Customer byPhone(String phone);
    @Select("SELECT * FROM customer WHERE id=#{id}") Customer byId(String id);
    @Select("SELECT * FROM customer WHERE id=#{id} FOR UPDATE") Customer lockById(String id);
    @Insert("INSERT INTO customer(id,username,password_hash,display_name,email,phone) VALUES(#{id},#{username},#{passwordHash},#{displayName},#{email},#{phone})") int insert(Customer customer);
    @Update("UPDATE customer SET email_verified_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND email=#{email} AND email_verified_at IS NULL")
    int markEmailVerified(@Param("id") String id,@Param("email") String email);
    @Update("UPDATE customer SET password_hash=#{hash},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND enabled=TRUE")
    int updatePassword(@Param("id") String id,@Param("hash") String hash);
}
