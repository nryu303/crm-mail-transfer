package com.crm.repository;

import com.crm.entity.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {
    List<MessageTemplate> findAllByOrderByDisplayOrderAscIdAsc();
}
