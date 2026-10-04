package dev.mcai.partner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlStateTest {
    @Test void connectionMustBeExplicitlyEnabledAndReconnectInvalidatesPreviousWork() {
        ControlState state = new ControlState();
        assertThrows(IllegalStateException.class, state::enable);
        state.connect(); assertFalse(state.enabled()); state.enable();
        assertTrue(state.submit("采木", ControlState.Priority.LOCAL));
        long token = state.generation(); state.disconnect(); state.connect();
        assertFalse(state.enabled()); assertNotEquals(token, state.generation());
    }
    @Test void allPlayersCanQueueGoalsButCannotOverrideLocalControl() {
        ControlState state = new ControlState(); state.connect(); state.enable();
        state.submit("本地采木", ControlState.Priority.LOCAL);
        long localToken = state.generation();
        assertTrue(state.submit("公共种田", ControlState.Priority.PUBLIC));
        assertEquals(localToken, state.generation());
        assertFalse(state.publicStop()); assertEquals("本地采木", state.goal("自主"));
        state.releaseLocal(); assertEquals("公共种田", state.goal("自主"));
        assertTrue(state.publicStop()); assertEquals("", state.goal("自主"));
    }
}
