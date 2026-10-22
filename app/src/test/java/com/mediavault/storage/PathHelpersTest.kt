package com.mediavault.storage

import com.mediavault.storage.api.joinPath
import com.mediavault.storage.api.nameOf
import com.mediavault.storage.api.parentOf
import org.junit.Assert.assertEquals
import org.junit.Test

class PathHelpersTest {
    @Test fun joinNormalises() = assertEquals("/a/b/c", joinPath("/a/", "b", "c/"))
    @Test fun joinDropsDotDot() = assertEquals("/a/b", joinPath("a", "../b"))
    @Test fun parentAndName() { assertEquals("/a/b", parentOf("/a/b/c.jpg")); assertEquals("c.jpg", nameOf("/a/b/c.jpg")) }
    @Test fun rootParent() = assertEquals("/", parentOf("/x.jpg"))
}
