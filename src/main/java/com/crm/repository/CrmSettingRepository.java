package com.crm.repository;

import com.crm.entity.CrmSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CrmSettingRepository extends JpaRepository<CrmSetting, Long> {
    Optional<CrmSetting> findBySettingKey(String settingKey);
}
