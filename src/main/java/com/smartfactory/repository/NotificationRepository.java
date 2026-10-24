package com.smartfactory.repository;

import com.smartfactory.entity.AppNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends MongoRepository<AppNotification, String> {

    List<AppNotification> findByUserId(String userId);

    Page<AppNotification> findByUserId(String userId, Pageable pageable);

    List<AppNotification> findByUserIdIn(List<String> userIds);

    Page<AppNotification> findByUserIdIn(List<String> userIds, Pageable pageable);

    List<AppNotification> findByUserIdAndIsReadFalse(String userId);

    List<AppNotification> findByUserIdInAndIsReadFalse(List<String> userIds);

    long countByUserIdAndIsReadFalse(String userId);

    long countByUserIdInAndIsReadFalse(List<String> userIds);

    boolean existsByUserIdAndAlertIdAndType(String userId, String alertId, String type);

    boolean existsByUserIdAndInterventionIdAndType(String userId, String interventionId, String type);

    List<AppNotification> findByUserIdAndAlertId(String userId, String alertId);

    List<AppNotification> findByUserIdAndInterventionId(String userId, String interventionId);

    void deleteByUserId(String userId);
}
