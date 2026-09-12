package com.example.balloon.config;

import jakarta.persistence.*;
import java.time.Instant;

/** Опубликованная администратором конфигурация темы: переживает перезапуск сервера. */
@Entity
@Table(name = "game_config")
public class GameConfigEntity {
  @Id
  @Column(name = "theme", length = 16)
  private String theme;

  @Lob
  @Column(name = "payload", nullable = false)
  private String payload;

  @Column(name = "updated_by", length = 64)
  private String updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  protected GameConfigEntity() {}

  public GameConfigEntity(String theme, String payload, String updatedBy) {
    this.theme = theme;
    this.payload = payload;
    this.updatedBy = updatedBy;
    this.updatedAt = Instant.now();
  }

  public String getTheme() { return theme; }
  public String getPayload() { return payload; }
  public String getUpdatedBy() { return updatedBy; }
  public Instant getUpdatedAt() { return updatedAt; }

  public void update(String payload, String updatedBy) {
    this.payload = payload;
    this.updatedBy = updatedBy;
    this.updatedAt = Instant.now();
  }
}
