package com.chatchat.chat.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TenantNotificationRecipientService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern MOBILE = Pattern.compile("^\\+?[0-9][0-9 -]{5,19}$");
    private static final List<String> CHANNELS = List.of("EMAIL", "SMS", "WECHAT_WORK", "DINGTALK");

    private final TenantNotificationRecipientRepository repository;

    public List<RecipientView> list(String tenantId) {
        return repository.findByTenantIdOrderByChannelTypeAsc(requireTenant(tenantId)).stream()
            .map(RecipientView::from)
            .toList();
    }

    public Optional<String> receiver(String tenantId, String channelType) {
        return repository.findByTenantIdAndChannelType(requireTenant(tenantId), normalizeChannel(channelType))
            .map(TenantNotificationRecipientEntity::getReceiver)
            .filter(value -> value != null && !value.isBlank());
    }

    public List<String> recipients(String tenantId, String channelType) {
        return receiver(tenantId, channelType)
            .map(value -> java.util.Arrays.stream(value.split("[,;，；\\n]+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList())
            .orElseGet(List::of);
    }

    @Transactional
    public RecipientView save(String tenantId, String channelType, String receiver) {
        String normalizedTenant = requireTenant(tenantId);
        String normalizedChannel = normalizeChannel(channelType);
        String normalizedReceiver = validateReceiver(normalizedChannel, receiver);
        TenantNotificationRecipientEntity entity = repository
            .findByTenantIdAndChannelType(normalizedTenant, normalizedChannel)
            .orElseGet(TenantNotificationRecipientEntity::new);
        entity.setTenantId(normalizedTenant);
        entity.setChannelType(normalizedChannel);
        entity.setReceiver(normalizedReceiver);
        return RecipientView.from(repository.save(entity));
    }

    @Transactional
    public void delete(String tenantId, String channelType) {
        repository.findByTenantIdAndChannelType(requireTenant(tenantId), normalizeChannel(channelType))
            .ifPresent(repository::delete);
    }

    private String validateReceiver(String channelType, String receiver) {
        if (receiver == null || receiver.isBlank()) {
            throw new IllegalArgumentException("接收人不能为空");
        }
        String normalized = receiver.trim().replace('，', ',');
        if (normalized.length() > 2000) {
            throw new IllegalArgumentException("接收人长度不能超过2000个字符");
        }
        String[] values = normalized.split("[,;\\n]");
        boolean hasReceiver = false;
        for (String value : values) {
            String item = value.trim();
            if (item.isBlank()) {
                continue;
            }
            hasReceiver = true;
            if ("EMAIL".equals(channelType) && !EMAIL.matcher(item).matches()) {
                throw new IllegalArgumentException("邮箱格式不正确: " + item);
            }
            if ("SMS".equals(channelType) && !MOBILE.matcher(item).matches()) {
                throw new IllegalArgumentException("手机号格式不正确: " + item);
            }
        }
        if (!hasReceiver) {
            throw new IllegalArgumentException("接收人不能为空");
        }
        return normalized;
    }

    private String normalizeChannel(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(normalized)) {
            throw new IllegalArgumentException("不支持的通知类型: " + value);
        }
        return normalized;
    }

    private String requireTenant(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("租户ID不能为空");
        }
        return value.trim();
    }

    public record RecipientView(String id, String tenantId, String channelType, String receiver) {
        static RecipientView from(TenantNotificationRecipientEntity entity) {
            return new RecipientView(entity.getId(), entity.getTenantId(), entity.getChannelType(), entity.getReceiver());
        }
    }
}
