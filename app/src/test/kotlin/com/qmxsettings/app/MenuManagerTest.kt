package com.qmxsettings.app

import com.qmxsettings.app.cat.CatClient
import com.qmxsettings.app.cat.CatReply
import com.qmxsettings.app.cat.DemoRadio
import com.qmxsettings.app.menu.MenuProtocol
import com.qmxsettings.app.menu.MenuRepository
import com.qmxsettings.app.menu.MenuType
import com.qmxsettings.app.profiles.EntryKind
import com.qmxsettings.app.profiles.Profile
import com.qmxsettings.app.profiles.ProfileEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MenuManagerTest {
    private fun demo(): Pair<CatClient, MenuRepository> {
        val json = File("src/main/assets/qmx_menu_demo.json").readText()
        val radio = DemoRadio(json)
        val cat = CatClient(radio)
        radio.open()
        return cat to MenuRepository(cat)
    }

    @Test fun parsesDiscoveryFrames() {
        val a = MenuProtocol.parseDiscovery("MM5|3|Keyer mode")!!
        assertEquals(MenuType.LIST, a.type); assertEquals(3, a.param); assertEquals("Keyer mode", a.name)
        val b = MenuProtocol.parseDiscovery("MM0|0|Band config.[16]")!!
        assertEquals("Band config.", b.name); assertEquals(16, b.columns)
        assertEquals(listOf("Straight", "IAMBIC A"), MenuProtocol.parseList("MLStraight|IAMBIC A"))
    }

    @Test fun numericNamesUseIndexes() {
        // Manual: "CW|Choose filters|50" fails; must be addressed as index.
        assertEquals("CW|Choose filters|0", MenuProtocol.encodePath(listOf("CW", "Choose filters", "50"), listOf(1, 10, 0)))
    }

    @Test fun manualExamplesAgainstDemo() = runBlocking {
        val (cat, _) = demo()
        assertEquals(CatReply.Ok("MM0|0|Audio"), cat.query("MM0?;", "MM"))
        assertEquals(CatReply.Ok("MM5|3|Keyer mode"), cat.query("MMCW|CW Keyer|0?;", "MM"))
        assertEquals(CatReply.Ok("MLStraight|IAMBIC A|IAMBIC B|Ultimatic"), cat.query("ML3;", "ML"))
        assertEquals(CatReply.Ok("MM0|0|Band config.[16]"), cat.query("MM12?;", "MM"))
        assertEquals(CatReply.Ok("MM54"), cat.query("MMBand config.|RF gain (db)[3];", "MM"))
        assertEquals(CatReply.Error, cat.query("MMCW|Choose filters|50;", "MM"))
        assertEquals(CatReply.Ok("MMENABLED"), cat.query("MMCW|Choose filters|0;", "MM"))
    }

    @Test fun discoverReadWriteAndProfileRoundTrip() = runBlocking {
        val (_, repo) = demo()
        assertTrue(repo.supportsMenuManager())
        val tree = repo.discover("test")
        assertEquals("Audio", tree.roots.first().name)
        repo.readAll(tree.roots)
        val count = tree.settingCount()
        assertTrue("expected many settings, got $count", count > 100)

        val keyer = tree.find("CW › CW Keyer › Keyer mode")
        assertNotNull(keyer)
        assertEquals("IAMBIC A", keyer!!.value)
        assertTrue(repo.write(keyer, "ultimatic", tree.lists).ok)
        assertEquals("Ultimatic", keyer.value)

        val rf = tree.find("Band config. › RF gain (db)")!!
        assertEquals("54", rf.values!![3])
        assertTrue(repo.write(rf, "63", tree.lists, column = 3).ok)
        assertEquals("63", repo.read(rf, 3))

        val filt = tree.find("CW › Choose filters › 50")!!
        assertTrue(repo.write(filt, "DISABLED", tree.lists).ok)

        // Invalid value is refused by the radio and reported as failed.
        assertTrue(!repo.write(keyer, "Bug", tree.lists).ok)

        val p = Profile(name = "x", entries = listOf(ProfileEntry(EntryKind.MENU, keyer.key, "IAMBIC B"), ProfileEntry(EntryKind.MENU, rf.key, "50", 3)))
        val back = Profile.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
    }
}
