package com.chatchat.runtime.news;

import com.chatchat.runtime.news.api.collection.NewsCollectionController;
import com.chatchat.runtime.news.api.collection.NewsCollectionTaskController;
import com.chatchat.runtime.news.api.health.NewsHealthController;
import com.chatchat.runtime.news.api.record.NewsRecordController;
import com.chatchat.runtime.news.api.source.NewsSourceController;
import com.chatchat.runtime.news.api.tool.NewsToolController;
import com.chatchat.runtime.news.application.collection.NewsCollectionOperations;
import com.chatchat.runtime.news.application.collection.NewsCollectionTasks;
import com.chatchat.runtime.news.application.source.NewsSourceAdministration;
import com.chatchat.runtime.news.application.tool.NewsToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NewsRuntimeControllerSurfaceContractTest {
    @Test
    void internalApiIsSplitByFeatureAndPublishesCompleteAdministrativeSurface() {
        List<Class<?>> controllers = List.of(NewsHealthController.class, NewsSourceController.class,
            NewsCollectionController.class, NewsCollectionTaskController.class, NewsRecordController.class,
            NewsToolController.class);

        assertThat(controllers).allMatch(type -> type.isAnnotationPresent(RestController.class));
        assertThat(controllers.stream()
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .flatMap(method -> Arrays.stream(method.getAnnotations()))
            .filter(annotation -> annotation.annotationType().getSimpleName().endsWith("Mapping")))
            .hasSize(13);
    }

    @Test
    void controllersDependOnUseCaseInterfacesInsteadOfConcreteServices() {
        assertThat(NewsSourceController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(NewsSourceAdministration.class);
        assertThat(NewsRecordController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(NewsSourceAdministration.class);
        assertThat(NewsCollectionController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(NewsCollectionTasks.class, NewsCollectionOperations.class);
        assertThat(NewsCollectionTaskController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(NewsCollectionTasks.class);
        assertThat(NewsToolController.class.getDeclaredConstructors()[0].getParameterTypes())
            .containsExactly(NewsToolRegistry.class);
    }
}
