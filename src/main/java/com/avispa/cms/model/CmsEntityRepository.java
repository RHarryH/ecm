package com.avispa.cms.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.UUID;

/**
 * @author Rafał Hiszpański
 */
@NoRepositoryBean
public interface CmsEntityRepository<T extends CmsEntity> extends JpaRepository<T, UUID> {
}
