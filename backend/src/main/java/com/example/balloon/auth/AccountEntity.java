package com.example.balloon.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "accounts", indexes = {@Index(name = "idx_accounts_username", columnList = "username", unique = true)})
public class AccountEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable=false, unique=true, length=80) private String username;
    @Column(nullable=false, length=160) private String passwordHash;
    @Column(nullable=false, length=20) private String role;
    @Column(nullable=false) private boolean blocked;
    @Column(nullable=false) private long balance;
    @Column(nullable=false) private long points;
    @Column(nullable=false) private int level;
    /**
     * Битовая маска собранных фрагментов текущего пазла.
     * columnDefinition с DEFAULT нужен, чтобы колонка добавлялась к таблице с уже существующими аккаунтами.
     */
    @Column(nullable=false, columnDefinition="integer default 0 not null") private int puzzleMask;
    @Column(nullable=false, columnDefinition="integer default 0 not null") private int puzzlesCompleted;
    @Column(nullable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;

    protected AccountEntity() {}
    public AccountEntity(String username, String passwordHash, String role) {
        this.username=username; this.passwordHash=passwordHash; this.role=role; this.balance=1000; this.level=1;
        this.createdAt=Instant.now(); this.updatedAt=this.createdAt;
    }
    public String getId(){return id;} public String getUsername(){return username;} public String getPasswordHash(){return passwordHash;}
    public String getRole(){return role;} public boolean isBlocked(){return blocked;} public long getBalance(){return balance;}
    public long getPoints(){return points;} public int getLevel(){return level;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
    public int getPuzzleMask(){return puzzleMask;} public int getPuzzlesCompleted(){return puzzlesCompleted;}
    public void setPuzzleMask(int value){puzzleMask=value; touch();} public void setPuzzlesCompleted(int value){puzzlesCompleted=value; touch();}
    public void setRole(String role){this.role=role; touch();} public void setBlocked(boolean blocked){this.blocked=blocked; touch();}
    public void setPasswordHash(String value){passwordHash=value; touch();} public void setBalance(long value){balance=value; touch();}
    public void setPoints(long value){points=value; this.level=Math.max(1,(int)(points/100)+1); touch();}
    private void touch(){updatedAt=Instant.now();}
}
