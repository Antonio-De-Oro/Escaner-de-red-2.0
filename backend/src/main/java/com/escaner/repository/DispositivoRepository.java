package com.escaner.repository;

import com.escaner.entity.Dispositivo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DispositivoRepository extends JpaRepository<Dispositivo, Long> {
    List<Dispositivo> findByAuditoriaId(Long auditoriaId);
    List<Dispositivo> findByNivelRiesgo(String nivelRiesgo);
}

