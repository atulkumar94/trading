package com.rotation.portfolio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class PortfolioTest {

    @Test
    void copyKeepsAccountStateAndIsolatesMutablePositions() {
        Portfolio original = new Portfolio(1000.0);
        original.put("AAA", new Position(5.0, 100.0, 105.0, 120.0));
        original.setCash(75.0);
        original.setDeployedCapital(500.0);

        Portfolio replay = original.copy();
        replay.position("AAA").setQuantity(2.0);
        replay.setCash(0.0);

        assertNotSame(original.position("AAA"), replay.position("AAA"));
        assertEquals(5.0, original.position("AAA").quantity());
        assertEquals(2.0, replay.position("AAA").quantity());
        assertEquals(75.0, original.cash());
        assertEquals(0.0, replay.cash());
        assertEquals(1000.0, replay.accountEquity());
        assertEquals(500.0, replay.deployedCapital());
    }
}