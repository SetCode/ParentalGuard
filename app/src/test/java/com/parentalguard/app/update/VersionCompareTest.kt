package com.parentalguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** @brief 版本号解析与比较的边界测试：正常、缺段、前缀、预发布后缀与格式异常 */
class VersionCompareTest {

    @Test
    fun parse_normalForms() {
        assertEquals(listOf(1, 0, 0), VersionCompare.parse("1.0.0"))
        assertEquals(listOf(1, 2), VersionCompare.parse("v1.2"))
        assertEquals(listOf(1, 2, 0), VersionCompare.parse("V1.2.0"))
        assertEquals(listOf(1, 2, 3), VersionCompare.parse("1.2.3-beta.1"))
        assertEquals(listOf(2, 0, 0, 1), VersionCompare.parse(" 2.0.0.1 "))
    }

    @Test
    fun parse_malformedReturnsNull() {
        assertNull(VersionCompare.parse(null))
        assertNull(VersionCompare.parse(""))
        assertNull(VersionCompare.parse("   "))
        assertNull(VersionCompare.parse("v"))
        assertNull(VersionCompare.parse("1.2.x"))
        assertNull(VersionCompare.parse("1..2"))
        assertNull(VersionCompare.parse("1.2."))
        assertNull(VersionCompare.parse("1.2.3.4.5"))
        assertNull(VersionCompare.parse("1234567890.1"))
    }

    @Test
    fun compare_ordersBySegment() {
        assertTrue(VersionCompare.compare("1.0.0", "1.0.1")!! < 0)
        assertTrue(VersionCompare.compare("1.0.1", "1.0.0")!! > 0)
        assertTrue(VersionCompare.compare("1.9.9", "2.0")!! < 0)
        assertTrue(VersionCompare.compare("1.10.0", "1.9.9")!! > 0)
    }

    @Test
    fun compare_treatsMissingSegmentsAsZero() {
        assertEquals(0, VersionCompare.compare("1.0", "1.0.0")!!)
        assertEquals(0, VersionCompare.compare("v1.0.0", "1.0.0")!!)
        assertEquals(0, VersionCompare.compare("1.0.0-beta.2", "1.0.0")!!)
        assertTrue(VersionCompare.compare("1.0", "1.0.1")!! < 0)
    }

    @Test
    fun compare_malformedReturnsNull() {
        assertNull(VersionCompare.compare("1.0.x", "1.0.0"))
        assertNull(VersionCompare.compare("1.0.0", null))
        assertNull(VersionCompare.compare("", "1.0.0"))
    }

    @Test
    fun format_roundTrip() {
        assertEquals("1.2.3", VersionCompare.format(VersionCompare.parse("v1.2.3")!!))
        assertEquals("2.0", VersionCompare.format(VersionCompare.parse("2.0")!!))
    }
}
