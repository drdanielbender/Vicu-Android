package com.rendyhd.vicu.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectNavigationTest {

    @Test
    fun `different child project produces a route to push`() {
        assertEquals(
            ProjectRoute(projectId = 20L),
            projectRouteToPush(currentProjectId = 10L, targetProjectId = 20L),
        )
    }

    @Test
    fun `current project is ignored to prevent duplicate rapid taps`() {
        assertNull(
            projectRouteToPush(currentProjectId = 20L, targetProjectId = 20L),
        )
    }

    @Test
    fun `missing current project still produces a route`() {
        assertEquals(
            ProjectRoute(projectId = 20L),
            projectRouteToPush(currentProjectId = null, targetProjectId = 20L),
        )
    }
}
