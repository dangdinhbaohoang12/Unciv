package com.unciv.logic.map

import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm.AStarPathfinding
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.TestGame
import com.unciv.testing.TestRunnerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.junit.runners.Parameterized.UseParametersRunnerFactory

@RunWith(Parameterized::class)
@UseParametersRunnerFactory(TestRunnerFactory::class)
class HiddenUnitMultiTurnPathTests(private val pathfindingAlgorithm: PathfindingAlgorithm) {
    companion object {
        @Parameters
        @JvmStatic
        fun parameters() = TestRunnerFactory.Parameters.pathfinding
    }

    private lateinit var civInfo: Civilization
    private lateinit var testGame: TestGame

    @Before
    fun initTheWorld() {
        UncivGame.Current.settings.useAStarPathfinding = (pathfindingAlgorithm == AStarPathfinding)
        testGame = TestGame()
        testGame.makeHexagonalMap(4)
        civInfo = testGame.addCiv()
        civInfo.tech.techsResearched.addAll(testGame.ruleset.technologies.keys)
        civInfo.tech.embarkedUnitsCanEnterOcean = true
        civInfo.tech.unitsCanEmbark = true
    }

    @Test
    fun `hidden blocker discovered mid-route does not prevent a later multi-turn route`() {
        val otherCiv = testGame.addCiv()
        civInfo.diplomacy[otherCiv.civName] = DiplomacyManager(civInfo, otherCiv)
        civInfo.getDiplomacyManager(otherCiv)!!.diplomaticStatus = DiplomaticStatus.War

        val origin = testGame.tileMap[0, 0]
        val destination = origin.neighbors.first().neighbors.first { it != origin }
        val ourUnit = testGame.addUnit("Warrior", civInfo, origin)

        // Build the route before the hidden unit exists so the blocker is placed on a tile
        // that the pathfinder would actually choose.
        val initialPath = ourUnit.movement.getDistanceToTiles().getPathToTile(destination).toList()
        assertTrue("The test requires a two-step route", initialPath.size >= 2)
        val hiddenTile = initialPath.first()

        val hiddenUnit = testGame.addDefaultMeleeUnitWithUniques(
            otherCiv,
            hiddenTile,
            UniqueType.Invisible.text
        )
        assertFalse(hiddenUnit.isVisibleTo(civInfo))

        // End the current turn before the second step. The first move attempt must discover
        // the hidden blocker without entering its tile or spending movement for that tile.
        ourUnit.currentMovement = 1f
        ourUnit.movement.moveToTile(destination)

        assertEquals(origin, ourUnit.currentTile)
        assertTrue(civInfo.viewableInvisibleUnitsTiles.contains(hiddenTile))
        assertEquals(hiddenUnit, hiddenTile.militaryUnit)

        // On the next turn the pathfinder must use the updated visibility state and find
        // another valid route instead of rejecting the destination because the old route
        // contained the newly discovered blocker.
        ourUnit.currentMovement = ourUnit.getMaxMovement().toFloat()
        val reroutedPath = ourUnit.movement.getDistanceToTiles().getPathToTile(destination).toList()

        assertEquals(destination, reroutedPath.last())
        assertFalse("The recalculated route must avoid the discovered hidden blocker",
            reroutedPath.contains(hiddenTile))

        ourUnit.movement.moveToTile(destination)
        assertEquals(destination, ourUnit.currentTile)
    }
}
