package com.chatchat.mcpserver.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPasswordStoreTest {

    @Mock
    private AdminUserRepository repository;

    private AdminPasswordStore store;

    @BeforeEach
    void setUp() {
        store = new AdminPasswordStore(repository);
    }

    @Test
    void initializesDefaultAdminWithHashedPassword() {
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AdminUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        store.initializeDefaultUser();

        var captor = org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        verify(repository).saveAndFlush(captor.capture());
        AdminUser user = captor.getValue();
        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPasswordAlgorithm()).isEqualTo("PBKDF2WithHmacSHA256");
        assertThat(user.getPasswordIterations()).isEqualTo(120_000);
        assertThat(user.getPasswordSalt()).isNotBlank();
        assertThat(user.getPasswordHash()).isNotBlank().doesNotContain("admin123");
    }

    @Test
    void doesNotOverwriteExistingAdmin() {
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(new AdminUser()));

        store.initializeDefaultUser();

        verify(repository, never()).saveAndFlush(any(AdminUser.class));
    }

    @Test
    void authenticatesFromDatabaseAndPersistsPasswordChanges() {
        when(repository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(AdminUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        store.initializeDefaultUser();

        var captor = org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        verify(repository).saveAndFlush(captor.capture());
        AdminUser user = captor.getValue();
        when(repository.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.of(user));

        assertThat(store.authenticate("ADMIN", "admin123")).contains("admin");
        assertThat(store.authenticate("admin", "wrong-password")).isEmpty();

        String oldHash = user.getPasswordHash();
        store.save("admin", "newAdmin123");

        assertThat(user.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(store.authenticate("admin", "admin123")).isEmpty();
        assertThat(store.authenticate("admin", "newAdmin123")).contains("admin");
    }
}
