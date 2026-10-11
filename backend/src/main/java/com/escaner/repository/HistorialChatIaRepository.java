package com.escaner.repository;

import com.escaner.entity.HistorialChatIa;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HistorialChatIaRepository extends JpaRepository<HistorialChatIa, Long> {
    List<HistorialChatIa> findByUsuarioIdOrderByFechaHoraDesc(Long usuarioId);
    List<HistorialChatIa> findByUsuarioIdOrderByFechaHoraAsc(Long usuarioId);
    void deleteByUsuarioId(Long usuarioId);
}

