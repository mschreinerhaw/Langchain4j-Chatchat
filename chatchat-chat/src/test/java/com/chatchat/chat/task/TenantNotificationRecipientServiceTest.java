package com.chatchat.chat.task;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantNotificationRecipientServiceTest {

    @Test
    void savesRecipientInsideRequestedTenantAndChannel() {
        TenantNotificationRecipientRepository repository = mock(TenantNotificationRecipientRepository.class);
        when(repository.findByTenantIdAndChannelType("tenant-a", "EMAIL")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TenantNotificationRecipientService service = new TenantNotificationRecipientService(repository);

        service.save("tenant-a", "email", "owner-a@example.com");

        verify(repository).findByTenantIdAndChannelType("tenant-a", "EMAIL");
        verify(repository).save(any(TenantNotificationRecipientEntity.class));
    }

    @Test
    void rejectsInvalidEmailBeforePersisting() {
        TenantNotificationRecipientRepository repository = mock(TenantNotificationRecipientRepository.class);
        when(repository.findByTenantIdAndChannelType("tenant-a", "EMAIL")).thenReturn(Optional.empty());
        TenantNotificationRecipientService service = new TenantNotificationRecipientService(repository);

        assertThatThrownBy(() -> service.save("tenant-a", "EMAIL", "not-an-email"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("邮箱格式");
    }
}
