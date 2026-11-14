package com.bowentown.oldtimecamera;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** Guards the version comparison that decides whether an update prompt appears. */
public class VersionMathTest {

    @Test
    public void comparesDottedVersionsNewerOlderAndEqual() {
        assertEquals(1, VersionMath.compare("v1.4.1", "1.4.0"));
        assertEquals(-1, VersionMath.compare("1.3.9", "1.4.0"));
        assertEquals(0, VersionMath.compare("v1.4.0", "1.4.0"));
        assertEquals(0, VersionMath.compare("1.4", "1.4.0"));
        assertEquals(1, VersionMath.compare("v2.0.0", "1.99.99"));
        assertEquals(-1, VersionMath.compare("v1.4.0", "1.4.1"));
    }

    @Test
    public void toleratesPreReleaseSuffixAndGarbage() {
        assertEquals(0, VersionMath.compare("v1.4.0-beta", "1.4.0"));
        assertEquals(0, VersionMath.compare("V1.4.0", "1.4.0"));
        assertEquals(-1, VersionMath.compare("", "1.4.0"));
        assertEquals(-1, VersionMath.compare(null, "0.0.1"));
        assertArrayEquals(new int[]{1, 4, 0}, VersionMath.parse("v1.4.0-rc1"));
        assertArrayEquals(new int[]{0, 0, 0}, VersionMath.parse("nonsense"));
    }
}
