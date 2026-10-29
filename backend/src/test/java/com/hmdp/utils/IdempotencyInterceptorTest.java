package com.hmdp.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyInterceptorTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private IdempotencyInterceptor interceptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        interceptor = new IdempotencyInterceptor(stringRedisTemplate);
        UserHolder.removeUser();
    }

    @Test
    void shouldPassForGetRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/shop/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertTrue(allowed);
    }

    @Test
    void shouldPassWhenNoIdempotencyHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertTrue(allowed);
    }

    @Test
    void shouldPassOnFirstRequest() throws Exception {
        when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.startsWith("idem:req:"), eq("1"), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(Boolean.TRUE);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/1");
        request.addHeader("Idempotency-Key", "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());
        assertTrue(allowed);
    }

    @Test
    void shouldRejectDuplicateRequest() throws Exception {
        when(valueOperations.setIfAbsent(org.mockito.ArgumentMatchers.startsWith("idem:req:"), eq("1"), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(Boolean.FALSE);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/voucher-order/seckill/1");
        request.addHeader("Idempotency-Key", "dup-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"errorCode\":\"10009\""));
    }
}
