package com.leyon.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leyon.backend.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper
 * 数据操作接口，对应数据表 audit_logs
 *
 * @author leyon
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

}
