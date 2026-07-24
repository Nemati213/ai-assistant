package ru.itmo.nemat.vkconnector.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.itmo.nemat.vkconnector.model.VkUserProfile;

import java.time.Duration;
import java.util.Optional;

@Service
@Slf4j
public class VkUserProfileService {

    private final VkApiService vkApiService;
    private final Cache<String, Optional<VkUserProfile>> cache;

    public VkUserProfileService(
            VkApiService vkApiService,
            @Value("${vk.user-profile.cache-ttl:24h}") Duration cacheTtl,
            @Value("${vk.user-profile.cache-size:100000}") long cacheSize
    ) {
        this.vkApiService = vkApiService;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl)
                .maximumSize(Math.max(1, cacheSize))
                .build();
    }

    public Optional<VkUserProfile> resolve(
            String vkGroupId,
            String vkUserId
    ) {
        if (vkUserId == null || !vkUserId.matches("\\d+")) {
            return Optional.empty();
        }
        String key = vkGroupId + ":" + vkUserId;
        Optional<VkUserProfile> cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Optional<VkUserProfile> loaded = load(vkGroupId, vkUserId);
        loaded.ifPresent(profile -> cache.put(key, loaded));
        return loaded;
    }

    private Optional<VkUserProfile> load(
            String vkGroupId,
            String vkUserId
    ) {
        try {
            return vkApiService.getUserProfile(vkGroupId, vkUserId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to load VK user profile {} for group {}",
                    vkUserId,
                    vkGroupId,
                    exception
            );
            return Optional.empty();
        }
    }
}
