package com.theeverthread.identity.internal.entity;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "WORKSPACE")
@Getter
@Setter
public class WorkspaceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    // Null uses the configured default; an workspace may override its active-account cap.
    private Integer maxActiveAccounts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceStatus status;
}
