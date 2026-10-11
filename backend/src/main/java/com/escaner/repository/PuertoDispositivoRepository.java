package com.escaner.repository;

import com.escaner.entity.PuertoDispositivo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PuertoDispositivoRepository extends JpaRepository<PuertoDispositivo, Long> {
    List<PuertoDispositivo> findByDispositivoId(Long dispositivoId);
}

