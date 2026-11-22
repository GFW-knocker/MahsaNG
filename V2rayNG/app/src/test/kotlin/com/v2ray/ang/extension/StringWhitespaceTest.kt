package com.v2ray.ang.extension

import org.junit.Assert.assertEquals
import org.junit.Test

class StringWhitespaceTest {
    @Test fun removesAllUnicodeWhitespaceFromDelimitedValues() {
        assertEquals("172.16.0.2/32,2606:4700::1/128",
            " 172.16.0.2/32,\n 2606:4700::1/128 ".removeWhiteSpace())
    }

    @Test fun preservesEveryNonWhitespaceCharacter() {
        assertEquals("password+token:123", "\tpassword+token:123 \r".removeWhiteSpace())
    }
}
