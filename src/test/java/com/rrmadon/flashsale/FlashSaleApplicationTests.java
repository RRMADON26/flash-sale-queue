package com.rrmadon.flashsale;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Card 1: proves the application starts cleanly against a real Redis and a
 * real (in-memory) datasource, with zero business logic yet. Everything
 * built in later cards adds to this context, not around it.
 */
@SpringBootTest
class FlashSaleApplicationTests {
    @Test
    void contextLoads() {
    }
}
