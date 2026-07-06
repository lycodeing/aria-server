package com.aria.auth.infrastructure.persistence.dept;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 部门数据对象。
 * ancestor_ids 存储从根到当前节点的祖先 ID 链（逗号分隔），便于快速查询子树。
 * 例：根(0) → 技术部(1) → 后端组(5)，则 5 的 ancestor_ids = "0,1,5"
 */
@Getter
@Setter
@TableName("cs_auth.sys_dept")
public class DeptDO {
    /**
     * 主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    private Long parentId;
    private String deptName;
    private String deptCode;
    private String ancestorIds;   // 祖先链，格式：0,1,5
    private Integer sortOrder;
    private String leader;
    private String phone;
    private String email;
    private String status;
}
