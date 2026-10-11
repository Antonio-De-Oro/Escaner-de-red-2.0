package com.escaner.config;

import com.escaner.entity.Rol;
import com.escaner.entity.Usuario;
import com.escaner.repository.RolRepository;
import com.escaner.repository.UsuarioRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UsuarioRepository usuarioRepository, 
                           RolRepository rolRepository, 
                           PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // 1. Inicializar Roles del Sistema
        Rol adminRol = rolRepository.findByNombre("ROLE_ADMIN")
                .orElseGet(() -> rolRepository.save(new Rol("ROLE_ADMIN", "Administrador de ciberseguridad con control total")));

        Rol auditorRol = rolRepository.findByNombre("ROLE_AUDITOR")
                .orElseGet(() -> rolRepository.save(new Rol("ROLE_AUDITOR", "Auditor de red y consultor de vulnerabilidades")));

        // 2. Administradores solicitados: Antonio y Jose (clave: nombre en minúsculas)
        crearUsuarioSiNoExiste("antonio", "antonio", "Antonio", "antonio@vulnerasolve.local", adminRol);
        crearUsuarioSiNoExiste("jose", "jose", "Jose", "jose@vulnerasolve.local", adminRol);

        // 3. Auditores solicitados: Giovanny y Jesus (clave: nombre en minúsculas)
        crearUsuarioSiNoExiste("giovanny", "giovanny", "Giovanny", "giovanny@vulnerasolve.local", auditorRol);
        crearUsuarioSiNoExiste("jesus", "jesus", "Jesus", "jesus@vulnerasolve.local", auditorRol);
    }

    private void crearUsuarioSiNoExiste(String username, String plainPassword, String nombreCompleto, String email, Rol rol) {
        if (!usuarioRepository.existsByUsername(username)) {
            Usuario user = new Usuario();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(plainPassword));
            user.setNombreCompleto(nombreCompleto);
            user.setEmail(email);
            user.setActivo(true);
            user.setRoles(Set.of(rol));
            usuarioRepository.save(user);
        }
    }
}

