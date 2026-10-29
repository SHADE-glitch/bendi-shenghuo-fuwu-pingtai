package com.hmdp.dto;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void testResultWithTraceId() {
        String expectedTraceId = "test-trace-id";
        MDC.put("traceId", expectedTraceId);
        try {
            Result<String> result = Result.ok("hello");
            assertEquals(expectedTraceId, result.getTraceId());
            assertTrue(result.getSuccess());
            assertEquals("hello", result.getData());
        } finally {
            MDC.remove("traceId");
        }
    }

    @Test
    void testResultWithErrorCode() {
        Result<Void> result = Result.fail(ErrorCode.STOCK_NOT_ENOUGH);
        assertFalse(result.getSuccess());
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), result.getErrorCode());
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getMsg(), result.getErrorMsg());
    }
}
