package com.example.balloon.auth;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AccountRepository extends JpaRepository<AccountEntity,String> {
    Optional<AccountEntity> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    List<AccountEntity> findAllByOrderByPointsDesc();
}
