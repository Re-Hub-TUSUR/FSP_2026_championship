package com.example.balloon.auth;
import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface AuditRepository extends JpaRepository<AuditEntity,String>{List<AuditEntity> findTop100ByOrderByCreatedAtDesc();}
