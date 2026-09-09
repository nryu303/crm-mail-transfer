package com.crm.repository;

import com.crm.entity.DiffSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiffScheduleRepository extends JpaRepository<DiffSchedule, Long> {

    /** Coarse candidate filter for "which schedules touched this user" — a plain substring
     *  LIKE, so callers MUST re-check exact CSV membership afterward (a raw LIKE can false-match,
     *  e.g. id 5 against "51,52"). Used by the user-detail page's per-user diff-schedule view. */
    List<DiffSchedule> findByTargetUserIdsContaining(String idFragment);
}
