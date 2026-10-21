package com.chatchat.api.security;

import com.chatchat.enterprise.service.EnterpriseAdminService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyUserScopeTest {
    @Test void authenticatedUsernameBridgesLegacyDataButCannotCollideWithAnotherStableIdentity(){
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:legacy-alias-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("create table sys_user(id varchar primary key)");
        var access=new ObjectAccessService(jdbc,mock(EnterpriseAdminService.class));var request=new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"stable-id");request.setAttribute(ApiAuthenticationFilter.CURRENT_USERNAME,"alice");
        assertThat(access.legacyUser(request)).isEqualTo("alice");assertThat(access.ownerAliases(request)).containsExactlyInAnyOrder("stable-id","alice");
        jdbc.update("insert into sys_user values('alice')");
        assertThat(access.ownerAliases(request)).containsExactly("stable-id");
        assertThatThrownBy(()->access.legacyUser(request)).isInstanceOf(com.chatchat.api.exception.RuntimeScopeAccessDeniedException.class);
    }
}
