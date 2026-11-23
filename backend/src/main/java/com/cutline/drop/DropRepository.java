package com.cutline.drop;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DropRepository extends JpaRepository<DropEvent, Long> {

    /**
     * 오픈 시각이 지난 SCHEDULED 드롭을 OPEN으로 바꾼다.
     * 조건부 UPDATE라 여러 서버에서 동시에 돌아도 한 번만 바뀐다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE drop_event SET status = 'OPEN' WHERE status = 'SCHEDULED' AND opens_at <= :now", nativeQuery = true)
    int openDue(@Param("now") Instant now);
}
