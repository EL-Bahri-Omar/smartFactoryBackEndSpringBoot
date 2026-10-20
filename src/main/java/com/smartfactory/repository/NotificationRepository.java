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

    List<AppNotification> findByUserIdAndIsReadFalse(String userId);

    long countByUserIdAndIsReadFalse(String userId);

    void deleteByUserId(String userId);
}
