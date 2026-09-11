package com.example.balloon.auth;
import jakarta.persistence.*; import java.time.Instant;
@Entity @Table(name="audit_log", indexes=@Index(name="idx_audit_created",columnList="createdAt"))
public class AuditEntity {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private String id;
 @Column(nullable=false) private String actor; @Column(nullable=false) private String action;
 @Column(length=1000) private String details; @Column(nullable=false) private Instant createdAt=Instant.now();
 protected AuditEntity(){} public AuditEntity(String actor,String action,String details){this.actor=actor;this.action=action;this.details=details;}
 public String getId(){return id;} public String getActor(){return actor;} public String getAction(){return action;} public String getDetails(){return details;} public Instant getCreatedAt(){return createdAt;}
}
