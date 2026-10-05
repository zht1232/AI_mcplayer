package dev.mcai.partner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryOpenPolicyTest {
    @Test void inspectionAndPerBlockOrganizationNeverOpenScreens() {
        var policy = new InventoryOpenPolicy();
        assertNotNull(policy.rejection(null, 0, false, false));
        assertNotNull(policy.rejection("inspect", 0, false, false));
        assertNotNull(policy.rejection("organize", 0, false, false));
        assertNull(policy.rejection("craft", 0, false, false));
        policy.opened(0);
        assertNotNull(policy.rejection("craft", 300, false, false));
        assertNull(policy.rejection("equip", 300, true, false));
        assertNull(policy.rejection("organize", 1300, false, true));
    }
}
