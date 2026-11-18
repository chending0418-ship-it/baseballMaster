package cc.baseballmaster.scorekeeper

import android.content.Context
import cc.baseballmaster.core.TeamDirectorySession

/** The normal Activity consults this process-local fixture only in a debuggable build. */
internal var androidTeamDirectoryTestOverride: ((Context) -> TeamDirectorySession)? = null
