package de.rapha149.signgui.version;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionMatcherTest {
    @Test
    void selectsDifferentSignApisFor26_2And26_3() {
        assertEquals("26_2", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.1-R0.1-SNAPSHOT"));
        assertEquals("26_2", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.1.2.build.3-alpha"));
        assertEquals("26_2", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.2.1-R0.1-SNAPSHOT"));
        assertEquals("26_2", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.2-R0.1-SNAPSHOT"));
        assertEquals("26_2", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.2.build.121-alpha"));
        assertEquals("26_R1", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.3-R0.1-SNAPSHOT"));
        assertEquals("26_R1", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.3.build.22-beta"));
    }

    @Test
    void exactPatchMappingTakesPriorityOverMinorFallback() {
        Map<String, String> versions = new HashMap<>();
        versions.put("26.3", "minor");
        versions.put("26.3.1", "patch");
        assertEquals("patch", VersionMatcher.match26Version("26.3.1", versions));
        assertEquals("minor", VersionMatcher.match26Version("26.3.2", versions));
    }

    @Test
    void fallsBackTo26WrapperForUnknown26Versions() {
        assertEquals("26_R1", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.4-R0.1-SNAPSHOT"));
        assertEquals("26_R1", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "26.12.build.1-alpha"));
    }

    @Test
    void preservesLegacyVersionSelection() {
        assertEquals("1_8_R3", VersionMatcher.matchVersion("org.bukkit.craftbukkit.v1_8_R3", "1.8.8-R0.1-SNAPSHOT"));
        assertEquals("1_20_R4", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "1.20.6-R0.1-SNAPSHOT"));
        assertEquals("1_21_R3", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "1.21.4-R0.1-SNAPSHOT"));
        assertEquals("1_21_R5", VersionMatcher.matchVersion("org.bukkit.craftbukkit", "1.21.8-R0.1-SNAPSHOT"));
    }
}
