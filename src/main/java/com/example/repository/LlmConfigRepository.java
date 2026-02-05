package com.example.repository;

import com.example.entity.LlmConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * LLM配置数据访问层
 */
@Repository
public interface LlmConfigRepository extends JpaRepository<LlmConfig, Long> {
    
    /**
     * 查询所有配置，按创建时间倒序
     */
    List<LlmConfig> findAllByOrderByCreatedAtDesc();
    
    /**
     * 查询激活的配置
     */
    Optional<LlmConfig> findByIsActiveTrue();
    
    /**
     * 取消所有配置的激活状态
     */
    @Modifying
    @Query("UPDATE LlmConfig c SET c.isActive = false WHERE c.isActive = true")
    void deactivateAll();
    
    /**
     * 激活指定配置
     */
    @Modifying
    @Query("UPDATE LlmConfig c SET c.isActive = true WHERE c.id = :id")
    void activateById(Long id);
}
