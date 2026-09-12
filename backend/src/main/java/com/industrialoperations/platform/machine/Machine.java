package com.industrialoperations.platform.machine;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity 
@Table(name="machines")
public class Machine{

    @Id 
    @Column(name="id", nullable=false, updatable=false)
    private UUID id;
    @Column(name="name", nullable = false)
    private String name;
    @Column(name = "external_reference", unique = true)
    private String externalReference;
    @Column (name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Machine(String name, String externalReference){
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Machine name cannot be null or blank");
        }
        this.id = UUID.randomUUID();
        this.name = name;
        this.externalReference = externalReference;
        this.createdAt = Instant.now();
    }

    protected Machine(){}

    public UUID getId(){
        return this.id;
    }

    public String getName(){
        return this.name;
    }

    public String getExternalReference(){
        return this.externalReference;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }
    
}