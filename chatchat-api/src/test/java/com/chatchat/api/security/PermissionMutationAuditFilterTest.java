package com.chatchat.api.security;
import com.chatchat.enterprise.repository.audit.SysAuditLogRepository;
import com.chatchat.enterprise.entity.audit.SysAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PermissionMutationAuditFilterTest {
 @Test void rejectedWriteIsAuditedWithoutSecretsOrPayload()throws Exception{
  var audits=mock(SysAuditLogRepository.class);var filter=new PermissionMutationAuditFilter(audits);
  var request=new MockHttpServletRequest("POST","/api/v1/permission-center/tool-grants");request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"actor");request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");request.addHeader("Authorization","secret-token");request.setContent("private-payload".getBytes());
  filter.doFilter(request,new MockHttpServletResponse(),(req,res)->((jakarta.servlet.http.HttpServletResponse)res).setStatus(403));
  var saved=ArgumentCaptor.forClass(SysAuditLog.class);verify(audits).saveAndFlush(saved.capture());assertThat(saved.getValue().getActorId()).isEqualTo("actor");assertThat(saved.getValue().getResult()).isEqualTo("failure");assertThat(saved.getValue().getDetail()).contains("403").doesNotContain("secret-token","private-payload");
 }
 @Test void successfulWriteKeepsExistingTransactionalAuditWithoutDuplicates()throws Exception{
  var audits=mock(SysAuditLogRepository.class);var request=new MockHttpServletRequest("PUT","/api/v1/enterprise/roles/role/authorization");request.setAttribute(ApiAuthenticationFilter.CURRENT_USER_ID,"actor");request.setAttribute(ApiAuthenticationFilter.CURRENT_TENANT_ID,"tenant");new PermissionMutationAuditFilter(audits).doFilter(request,new MockHttpServletResponse(),(req,res)->{});verifyNoInteractions(audits);
 }
}
