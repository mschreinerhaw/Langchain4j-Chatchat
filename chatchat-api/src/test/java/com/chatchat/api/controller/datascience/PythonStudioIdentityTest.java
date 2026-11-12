package com.chatchat.api.controller.datascience;

import com.chatchat.api.datascience.python.PythonCodeAssistantService;
import com.chatchat.api.security.ApiAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class PythonStudioIdentityTest {
    @Test void assistantLifecycleUsesAccountIdWhileUsernameCannotGrantNativeAccess() {
        var assistant=mock(PythonCodeAssistantService.class);
        var controller=new PythonDataScienceController(null,assistant,null,null,null);
        var request=new MockHttpServletRequest();
        request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"account-id");
        request.setAttribute(ApiAuthenticationFilter.CURRENT_USERNAME,"display-name");
        controller.startCoding(null,request);
        controller.codingResult("task",request);
        controller.cancelCoding("task",request);
        controller.resumeCoding("task",request);
        verify(assistant).startCoding("tenant","account-id",null);
        verify(assistant).codingResult("tenant","account-id","task",false);
        verify(assistant).codingResult("tenant","account-id","task",true);
        verify(assistant).resumeCoding("tenant","account-id","task");
        request.removeAttribute(ApiAuthenticationFilter.CURRENT_USER_ID);
        assertThatThrownBy(()->controller.startCoding(null,request)).isInstanceOf(ResponseStatusException.class);
        verifyNoMoreInteractions(assistant);
    }
}
