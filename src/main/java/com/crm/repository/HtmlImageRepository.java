package com.crm.repository;

import com.crm.entity.HtmlImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HtmlImageRepository extends JpaRepository<HtmlImage, Long> {
    List<HtmlImage> findAllByOrderByCreatedAtDesc();
}
