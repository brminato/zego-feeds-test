package dev.feed;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;

@Configuration
class Security {
    @Bean
    PasswordEncoder passwords() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcTemplate db) {
        return email -> db.query("SELECT email,password_hash,role FROM app_user WHERE email=?",
                        (rs, n) -> User.withUsername(rs.getString(1)).password(rs.getString(2)).roles(rs.getString(3)).build(), email)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll().requestMatchers("/actuator/health", "/api/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/articles").hasRole("WRITER")
                        .requestMatchers(HttpMethod.PUT, "/api/articles/*").hasRole("WRITER")
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults()).build();
    }
}
