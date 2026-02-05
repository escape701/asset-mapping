package com.example.repository;

import com.example.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 任务数据访问层
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, String> {
    
    /**
     * 根据创建者查询任务，按创建时间倒序
     */
    List<Task> findByCreatedByOrderByCreatedAtDesc(Long createdBy);
    
    /**
     * 查询所有任务，按创建时间倒序
     */
    List<Task> findAllByOrderByCreatedAtDesc();
    
    /**
     * 查询所有任务并加载关联的域名数据，按创建时间倒序
     */
    @Query("SELECT DISTINCT t FROM Task t LEFT JOIN FETCH t.taskDomains ORDER BY t.createdAt DESC")
    List<Task> findAllWithDomainsOrderByCreatedAtDesc();
    
    /**
     * 根据ID查询任务并加载关联的域名数据
     */
    @Query("SELECT t FROM Task t LEFT JOIN FETCH t.taskDomains WHERE t.id = :id")
    Optional<Task> findByIdWithDomains(@Param("id") String id);
}
