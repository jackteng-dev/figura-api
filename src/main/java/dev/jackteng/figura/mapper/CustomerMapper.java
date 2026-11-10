package dev.jackteng.figura.mapper;

import dev.jackteng.figura.pojo.Customer;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CustomerMapper {
    @Select("SELECT * FROM customer WHERE username=#{username}") Customer byUsername(String username);
    @Select("SELECT * FROM customer WHERE id=#{id}") Customer byId(String id);
    @Insert("INSERT INTO customer(id,username,password_hash,display_name) VALUES(#{id},#{username},#{passwordHash},#{displayName})") int insert(Customer customer);
    @Update("UPDATE customer SET display_name=#{name},updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND enabled=TRUE")
    int updateOwnDisplayName(@Param("id") String id,@Param("name") String name);
}
