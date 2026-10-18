package com.kantv.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChannelBrowseNavTest {

    private data class FakeChannel(val id: String, val identityKey: String?)

    // ---- 页回退链 ----

    @Test
    fun channelsPageRetreatsToGroups() {
        assertEquals(ChannelBrowseNav.Retreat.BROWSE_GROUPS, ChannelBrowseNav.retreat(ChannelBrowseNav.Page.CHANNELS))
    }

    @Test
    fun guidePageRetreatsToChannelsOfSelectedGroup() {
        assertEquals(ChannelBrowseNav.Retreat.BROWSE_CHANNELS, ChannelBrowseNav.retreat(ChannelBrowseNav.Page.GUIDE))
    }

    @Test
    fun groupsPageRetreatDismissesBrowsing() {
        assertEquals(ChannelBrowseNav.Retreat.DISMISS, ChannelBrowseNav.retreat(ChannelBrowseNav.Page.GROUPS))
    }

    @Test
    fun backKeyFollowsRetreatChain() {
        assertEquals(ChannelBrowseNav.Decision.DISMISS, ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.GROUPS, ChannelBrowseNav.Key.BACK))
        assertEquals(ChannelBrowseNav.Decision.RETREAT, ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.CHANNELS, ChannelBrowseNav.Key.BACK))
        assertEquals(ChannelBrowseNav.Decision.RETREAT, ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.GUIDE, ChannelBrowseNav.Key.BACK))
    }

    @Test
    fun leftKeyFollowsRetreatChainButGuidePassesThrough() {
        assertEquals(ChannelBrowseNav.Decision.DISMISS, ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.GROUPS, ChannelBrowseNav.Key.LEFT))
        assertEquals(ChannelBrowseNav.Decision.RETREAT, ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.CHANNELS, ChannelBrowseNav.Key.LEFT))
        assertEquals(
            ChannelBrowseNav.Decision.PASS_THROUGH,
            ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.GUIDE, ChannelBrowseNav.Key.LEFT),
        )
    }

    // ---- 键位映射 ----

    @Test
    fun upDownMoveFocusOnGroupsAndChannelsButGuidePassesThrough() {
        for (page in listOf(ChannelBrowseNav.Page.GROUPS, ChannelBrowseNav.Page.CHANNELS)) {
            assertEquals(ChannelBrowseNav.Decision.MOVE_FOCUS, ChannelBrowseNav.decideKey(page, ChannelBrowseNav.Key.UP))
            assertEquals(ChannelBrowseNav.Decision.MOVE_FOCUS, ChannelBrowseNav.decideKey(page, ChannelBrowseNav.Key.DOWN))
        }
        assertEquals(
            ChannelBrowseNav.Decision.PASS_THROUGH,
            ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.GUIDE, ChannelBrowseNav.Key.DOWN),
        )
    }

    @Test
    fun rightShortPressPlaysFocusedChannel() {
        val decision = ChannelBrowseNav.decideKey(
            ChannelBrowseNav.Page.CHANNELS,
            ChannelBrowseNav.Key.RIGHT,
            repeatCount = ChannelBrowseNav.LONG_PRESS_REPEAT_COUNT - 1,
            focusIsChannel = true,
        )
        assertEquals(ChannelBrowseNav.Decision.PLAY, decision)
    }

    @Test
    fun rightLongPressOpensChannelActionsAtThreshold() {
        val threshold = ChannelBrowseNav.LONG_PRESS_REPEAT_COUNT
        val decision = ChannelBrowseNav.decideKey(
            ChannelBrowseNav.Page.CHANNELS,
            ChannelBrowseNav.Key.RIGHT,
            repeatCount = threshold,
            focusIsChannel = true,
        )
        assertEquals(ChannelBrowseNav.Decision.SHOW_ACTIONS, decision)
    }

    @Test
    fun rightPressWithoutChannelFocusIsConsumedWithoutAction() {
        val short = ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.CHANNELS, ChannelBrowseNav.Key.RIGHT, repeatCount = 0, focusIsChannel = false)
        val long = ChannelBrowseNav.decideKey(
            ChannelBrowseNav.Page.CHANNELS,
            ChannelBrowseNav.Key.RIGHT,
            repeatCount = ChannelBrowseNav.LONG_PRESS_REPEAT_COUNT,
            focusIsChannel = false,
        )
        assertEquals(ChannelBrowseNav.Decision.CONSUME, short)
        assertEquals(ChannelBrowseNav.Decision.CONSUME, long)
    }

    @Test
    fun rightOnGroupRowOpensThatGroupOtherwiseConsumes() {
        val open = ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.GROUPS, ChannelBrowseNav.Key.RIGHT, focusIsGroup = true)
        val consume = ChannelBrowseNav.decideKey(ChannelBrowseNav.Page.GROUPS, ChannelBrowseNav.Key.RIGHT, focusIsGroup = false)
        assertEquals(ChannelBrowseNav.Decision.OPEN_FOCUSED_GROUP, open)
        assertEquals(ChannelBrowseNav.Decision.CONSUME, consume)
    }

    // ---- 打开落点 ----

    @Test
    fun landingWithCurrentChannelOpensItsGroupChannels() {
        val landing = ChannelBrowseNav.landing(ChannelBrowseNav.CurrentChannel(group = "广东"))
        assertEquals(ChannelBrowseNav.Landing.BrowseChannels("广东"), landing)
    }

    @Test
    fun landingWithoutCurrentChannelOpensGroups() {
        assertEquals(ChannelBrowseNav.Landing.BrowseGroups, ChannelBrowseNav.landing(null))
    }

    // ---- 当前频道判定（ChannelIdentity.shouldMerge 口径） ----

    @Test
    fun activeChannelIdWinsBeforeIdentityMerge() {
        val channels = listOf(
            FakeChannel(id = "a", identityKey = "tvb-news"),
            FakeChannel(id = "b", identityKey = "cctv-1"),
        )
        val resolved = ChannelBrowseNav.resolveCurrentChannel(
            channels = channels,
            activeChannelId = "b",
            playedId = "a",
            playedIdentity = "tvb-news",
            idOf = { it.id },
            identityOf = { it.identityKey },
        )
        assertSame(channels[1], resolved)
    }

    @Test
    fun identityMatchFindsSameServiceAcrossDifferentRowIds() {
        val channels = listOf(
            FakeChannel(id = "row-1", identityKey = "tvb-news"),
            FakeChannel(id = "row-2", identityKey = "cctv-1"),
        )
        val resolved = ChannelBrowseNav.resolveCurrentChannel(
            channels = channels,
            activeChannelId = null,
            playedId = "played-row",
            playedIdentity = "tvb-news",
            idOf = { it.id },
            identityOf = { it.identityKey },
        )
        assertSame(channels[0], resolved)
    }

    @Test
    fun fallsBackToRowIdWhenNeitherSideHasIdentity() {
        val channels = listOf(FakeChannel(id = "same-row", identityKey = null), FakeChannel(id = "other", identityKey = null))
        val same = ChannelBrowseNav.resolveCurrentChannel(
            channels = channels,
            activeChannelId = null,
            playedId = "same-row",
            playedIdentity = null,
            idOf = { it.id },
            identityOf = { it.identityKey },
        )
        val different = ChannelBrowseNav.resolveCurrentChannel(
            channels = channels,
            activeChannelId = null,
            playedId = "missing-row",
            playedIdentity = null,
            idOf = { it.id },
            identityOf = { it.identityKey },
        )
        assertSame(channels[0], same)
        assertNull(different)
    }

    @Test
    fun asymmetricIdentityNeverMerges() {
        val channels = listOf(FakeChannel(id = "row-1", identityKey = null))
        val resolved = ChannelBrowseNav.resolveCurrentChannel(
            channels = channels,
            activeChannelId = null,
            playedId = "row-1",
            playedIdentity = "tvb-news",
            idOf = { it.id },
            identityOf = { it.identityKey },
        )
        assertNull(resolved)
    }

    @Test
    fun returnsNullWhenNothingPlayed() {
        val resolved = ChannelBrowseNav.resolveCurrentChannel(
            channels = listOf(FakeChannel(id = "a", identityKey = null)),
            activeChannelId = null,
            playedId = null,
            playedIdentity = null,
            idOf = { it.id },
            identityOf = { it.identityKey },
        )
        assertNull(resolved)
    }
}
