package de.sync.app.server

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder

abstract class EndpointTestSupport {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    protected fun authenticated(builder: MockHttpServletRequestBuilder): MockHttpServletRequestBuilder =
        builder
            .header("X-Sync-Token", TEST_TOKEN)
            .requestAttr("accountName", TEST_ACCOUNT)

    protected companion object {
        const val TEST_ACCOUNT = "testuser"
        const val TEST_TOKEN = "test-token"
    }
}
