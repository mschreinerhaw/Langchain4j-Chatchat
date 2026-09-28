package com.chatchat.chat.activity;

import com.chatchat.chat.task.core.AgentTaskLatestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserWorkbenchServiceTest {

    private final UserActivityRepository activityRepository = mock(UserActivityRepository.class);
    private final UserFavoriteRepository favoriteRepository = mock(UserFavoriteRepository.class);
    private final UserFavoriteCategoryRepository categoryRepository = mock(UserFavoriteCategoryRepository.class);
    private final AgentTaskLatestRepository taskRepository = mock(AgentTaskLatestRepository.class);
    private final UserWorkbenchService service = new UserWorkbenchService(
        activityRepository,
        favoriteRepository,
        categoryRepository,
        taskRepository,
        new ObjectMapper()
    );

    @Test
    void createsTenantScopedFavoriteCategory() {
        when(categoryRepository.findByTenantIdAndUserIdAndCategoryName("tenant-1", "alice", "项目资料"))
            .thenReturn(Optional.empty());
        when(categoryRepository.save(any(UserFavoriteCategoryEntity.class))).thenAnswer(invocation -> {
            UserFavoriteCategoryEntity entity = invocation.getArgument(0);
            entity.onCreate();
            return entity;
        });

        UserWorkbenchService.FavoriteCategory category = service.createFavoriteCategory(
            new UserWorkbenchService.FavoriteCategoryRequest("tenant-1", "alice", "项目资料")
        );

        assertThat(category.name()).isEqualTo("项目资料");
        assertThat(category.id()).isNotBlank();
        verify(categoryRepository).save(any(UserFavoriteCategoryEntity.class));
    }

    @Test
    void refusesToDeleteFavoriteOutsideCurrentTenant() {
        when(favoriteRepository.findByIdAndTenantIdAndUserId("favorite-1", "tenant-1", "alice"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeFavorite("favorite-1", "tenant-1", "alice"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("current tenant and user");

        verify(favoriteRepository, never()).delete(any(UserFavoriteEntity.class));
    }

    @Test
    void renamesCategoryAndMovesItsFavorites() {
        UserFavoriteCategoryEntity category = new UserFavoriteCategoryEntity();
        category.setId("category-1");
        category.setTenantId("tenant-1");
        category.setUserId("alice");
        category.setCategoryName("项目资料");
        UserFavoriteEntity favorite = new UserFavoriteEntity();
        favorite.setCategory("项目资料");
        when(categoryRepository.findByTenantIdAndUserIdAndCategoryName("tenant-1", "alice", "客户资料"))
            .thenReturn(Optional.empty());
        when(categoryRepository.findByTenantIdAndUserIdAndCategoryName("tenant-1", "alice", "项目资料"))
            .thenReturn(Optional.of(category));
        when(favoriteRepository.findByTenantIdAndUserIdAndCategory("tenant-1", "alice", "项目资料"))
            .thenReturn(List.of(favorite));
        when(categoryRepository.save(category)).thenReturn(category);

        UserWorkbenchService.FavoriteCategory renamed = service.renameFavoriteCategory(
            "项目资料",
            new UserWorkbenchService.FavoriteCategoryRequest("tenant-1", "alice", "客户资料")
        );

        assertThat(renamed.name()).isEqualTo("客户资料");
        assertThat(favorite.getCategory()).isEqualTo("客户资料");
    }

    @Test
    void deletesCategoryAndMovesItsFavoritesToDefault() {
        UserFavoriteCategoryEntity category = new UserFavoriteCategoryEntity();
        category.setCategoryName("项目资料");
        UserFavoriteCategoryEntity defaultCategory = new UserFavoriteCategoryEntity();
        defaultCategory.setCategoryName("默认");
        UserFavoriteEntity favorite = new UserFavoriteEntity();
        favorite.setCategory("项目资料");
        when(categoryRepository.findByTenantIdAndUserIdAndCategoryName("tenant-1", "alice", "项目资料"))
            .thenReturn(Optional.of(category));
        when(categoryRepository.findByTenantIdAndUserIdAndCategoryName("tenant-1", "alice", "默认"))
            .thenReturn(Optional.of(defaultCategory));
        when(favoriteRepository.findByTenantIdAndUserIdAndCategory("tenant-1", "alice", "项目资料"))
            .thenReturn(List.of(favorite));

        service.deleteFavoriteCategory("项目资料", "tenant-1", "alice");

        assertThat(favorite.getCategory()).isEqualTo("默认");
        verify(categoryRepository).delete(category);
    }

    @Test
    void refusesToModifyDefaultCategory() {
        assertThatThrownBy(() -> service.deleteFavoriteCategory("默认", "tenant-1", "alice"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("default");
        verify(categoryRepository, never()).delete(any(UserFavoriteCategoryEntity.class));
    }
}
