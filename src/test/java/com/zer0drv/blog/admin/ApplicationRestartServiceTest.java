package com.zer0drv.blog.admin;

import com.zer0drv.blog.admin.service.*;
import com.zer0drv.blog.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationRestartServiceTest {
    @TempDir Path root;
    @Test void repeatedRequestReturnsSameJobAndOtherJobCannotEnter() throws Exception {
        var owners = mock(OwnerOperations.class);
        var jdbc = mock(JdbcTemplate.class);
        var service = new ApplicationRestartService(root.toString(),owners,jdbc,new ObjectMapper());
        Files.writeString(root.resolve("heartbeat"),"ready");
        String id = UUID.randomUUID().toString();
        var request = new ApplicationRestartService.Request(id,"password","123456","RESTART_BACKEND");
        assertEquals("QUEUED",service.request(7,request).get("state"));
        assertEquals("QUEUED",service.request(7,request).get("state"));
        verify(jdbc,times(1)).update(anyString(),eq(7L));
        String queued = Files.readString(root.resolve("request/request.json"));
        assertFalse(queued.contains("password")); assertFalse(queued.contains("123456"));
        assertThrows(BusinessException.class, () -> service.request(7,new ApplicationRestartService.Request(
                UUID.randomUUID().toString(),"password",null,"RESTART_BACKEND")));
        Files.writeString(root.resolve(id+".json"),"{\"id\":\""+id+"\",\"state\":\"RECOVERED\"}");
        assertEquals("RECOVERED",service.request(7,request).get("state"));
    }
    @Test void invalidIdsAndFailedReauthenticationNeverQueue() throws Exception {
        var owners = mock(OwnerOperations.class);
        var service = new ApplicationRestartService(root.toString(),owners,mock(JdbcTemplate.class),new ObjectMapper());
        Files.writeString(root.resolve("heartbeat"),"ready");
        assertThrows(BusinessException.class, () -> service.status("-".repeat(36)));
        doThrow(new BusinessException("40300","denied")).when(owners).verifyOwner(7,"wrong",null);
        assertThrows(BusinessException.class, () -> service.request(7,new ApplicationRestartService.Request(
                UUID.randomUUID().toString(),"wrong",null,"RESTART_BACKEND")));
        assertFalse(Files.exists(root.resolve("request")));
    }
}
