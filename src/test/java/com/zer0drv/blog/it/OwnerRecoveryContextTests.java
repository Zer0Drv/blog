package com.zer0drv.blog.it;

import com.zer0drv.blog.admin.service.OwnerRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Recovery must be able to start without exposing an HTTP listener. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OwnerRecoveryContextTests {
    @Autowired OwnerRecoveryService recovery;
    @Test void recoveryAvailableWithoutWebServer() { assertNotNull(recovery); }
}
