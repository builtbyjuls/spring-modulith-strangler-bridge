package com.builtbyjuls.grid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureVerificationTest {

    @Test
    @DisplayName("Verify Spring Modulith module boundaries and named interfaces")
    void verifyModulithStructure() {
        ApplicationModules.of(GridSettlementModulithApplication.class).verify();
    }
}
