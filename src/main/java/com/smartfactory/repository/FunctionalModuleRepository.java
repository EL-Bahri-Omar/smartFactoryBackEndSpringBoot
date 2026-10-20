package com.smartfactory.repository;

import com.smartfactory.entity.FunctionalModule;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FunctionalModuleRepository extends MongoRepository<FunctionalModule, String> {

    Optional<FunctionalModule> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<FunctionalModule> findByActiveTrue();
}
