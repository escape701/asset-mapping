package com.example.repository;

import com.example.entity.TaskDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务域名结果数据访问层
 */
@Repository
public interface TaskDomainRepository extends JpaRepository<TaskDomain, Long> {
    
    /**
     * 根据任务ID查询所有域名结果
     */
    List<TaskDomain> findByTaskId(String taskId);
    
    /**
     * 根据任务ID和域名查询
     */
    Optional<TaskDomain> findByTaskIdAndDomain(String taskId, String domain);
    
    /**
     * 根据任务ID统计已完成的域名数
     */
    long countByTaskIdAndStatus(String taskId, String status);
}
