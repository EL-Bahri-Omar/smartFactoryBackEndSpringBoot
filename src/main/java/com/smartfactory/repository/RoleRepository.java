package com.smartfactory.repository;

import com.smartfactory.entity.RoleDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends MongoRepository<RoleDocument, String> {

    Optional<RoleDocument> findByName(String name);

    Optional<RoleDocument> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
