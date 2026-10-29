package com.hmdp.mapper;

import com.hmdp.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 用户 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供对 tb_user 表的 CRUD 操作。
 * 自定义 SQL 方法请在对应的 XML 文件中定义。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface UserMapper extends BaseMapper<User> {

}
