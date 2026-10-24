package com.smartfactory.repository;

import com.smartfactory.entity.DeviceToken;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceTokenRepository extends MongoRepository<DeviceToken, String> {

    List<DeviceToken> findByUserId(String userId);

    List<DeviceToken> findByUserIdAndActiveTrue(String userId);

    List<DeviceToken> findByUserIdIn(Collection<String> userIds);

    List<DeviceToken> findByUserIdInAndActiveTrue(Collection<String> userIds);

    Optional<DeviceToken> findByToken(String token);

    void deleteByToken(String token);

    void deleteByUserId(String userId);
}
