package com.jaffan.broker.naming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaffan.broker.backend.DatabaseEngine;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    private static final String GUID = "A1B2C3D4-e5f6-7890-ABCD-ef1234567890";

    @Test
    void underscoreLowercasesAndReplacesHyphens() {
        assertThat(Identifiers.underscore(GUID)).isEqualTo("a1b2c3d4_e5f6_7890_abcd_ef1234567890");
    }

    @Test
    void instanceDatabaseIsPrefixedAndSanitised() {
        assertThat(Identifiers.instanceDatabase(GUID, DatabaseEngine.POSTGRES))
                .isEqualTo("si_a1b2c3d4_e5f6_7890_abcd_ef1234567890");
    }

    @Test
    void ownerAndBindingRolesUseTheirPrefixes() {
        assertThat(Identifiers.ownerRole(GUID, DatabaseEngine.POSTGRES))
                .isEqualTo("o_a1b2c3d4_e5f6_7890_abcd_ef1234567890");
        assertThat(Identifiers.bindingRole(GUID, DatabaseEngine.MARIADB))
                .isEqualTo("b_a1b2c3d4_e5f6_7890_abcd_ef1234567890");
    }

    @Test
    void quotingUsesEngineSpecificDelimiters() {
        assertThat(Identifiers.quote("si_abc", DatabaseEngine.POSTGRES)).isEqualTo("\"si_abc\"");
        assertThat(Identifiers.quote("si_abc", DatabaseEngine.MARIADB)).isEqualTo("`si_abc`");
    }

    @Test
    void rejectsCharactersOutsideAllowedSet() {
        // A classic injection attempt must never make it past validation (lower-case, so it is the
        // charset rule — not the lower-case rule — that rejects it).
        assertThatThrownBy(() -> Identifiers.validate("si_x\"; drop database postgres;--",
                DatabaseEngine.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a-z0-9_]");
        // And the same attempt with upper-case is rejected too (by the lower-case rule).
        assertThatThrownBy(() -> Identifiers.validate("si_x\"; DROP DATABASE postgres;--",
                DatabaseEngine.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Identifiers.validate("has space", DatabaseEngine.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.validate("dash-not-allowed", DatabaseEngine.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identifiers.validate("backtick`", DatabaseEngine.MARIADB))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUppercaseBeforeValidation() {
        assertThatThrownBy(() -> Identifiers.validate("si_ABC", DatabaseEngine.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyIdentifier() {
        assertThatThrownBy(() -> Identifiers.validate("", DatabaseEngine.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesPostgresLengthLimitOf63() {
        String sixtyThree = "a".repeat(63);
        assertThat(Identifiers.validate(sixtyThree, DatabaseEngine.POSTGRES)).isEqualTo(sixtyThree);
        assertThatThrownBy(() -> Identifiers.validate("a".repeat(64), DatabaseEngine.POSTGRES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("63");
    }

    @Test
    void enforcesMariaDbLengthLimitOf64() {
        String sixtyFour = "a".repeat(64);
        assertThat(Identifiers.validate(sixtyFour, DatabaseEngine.MARIADB)).isEqualTo(sixtyFour);
        assertThatThrownBy(() -> Identifiers.validate("a".repeat(65), DatabaseEngine.MARIADB))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64");
    }

    @Test
    void deletedNameStaysWithinLengthLimit() {
        String db = Identifiers.instanceDatabase(GUID, DatabaseEngine.POSTGRES);
        String parked = Identifiers.deletedName(db, 1_700_000_000_000L, DatabaseEngine.POSTGRES);
        assertThat(parked).startsWith("deleted_si_");
        assertThat(parked.length()).isLessThanOrEqualTo(63);
    }

    @Test
    void realGuidBasedNamesFitBothEngines() {
        // Worst case: si_<36-char guid underscored> must fit 63 (PG) and 64 (Maria).
        String pg = Identifiers.instanceDatabase(GUID, DatabaseEngine.POSTGRES);
        String maria = Identifiers.instanceDatabase(GUID, DatabaseEngine.MARIADB);
        assertThat(pg.length()).isLessThanOrEqualTo(63);
        assertThat(maria.length()).isLessThanOrEqualTo(64);
    }
}
