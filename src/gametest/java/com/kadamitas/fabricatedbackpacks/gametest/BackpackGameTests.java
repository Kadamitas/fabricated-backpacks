package com.kadamitas.fabricatedbackpacks.gametest;

import net.fabricmc.api.ModInitializer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.gametest.framework.GameTestHelper;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** The separate test mod's actual Fabric entrypoint; no production registrations are added here. */
public final class BackpackGameTests extends ContentGameTests implements ModInitializer {
    private static final String STRUCTURE = "fabricated_backpacks_tests:platform";

    @Override public void onInitialize() {
        ResourceGameTests.registerFixtures(); WorkstationGameTests.registerFixtures();
        SteamEngineGameTests.registerFixtures(); ConduitGameTests.registerFixtures();
        CompatibilityRuntimeEvidence.register();
    }

    @GameTest(template = STRUCTURE)
    public void requiredTestDiscovery(GameTestHelper helper) {
        helper.assertTrue(helper.getBlockState(new net.minecraft.core.BlockPos(1, 0, 1)).is(net.minecraft.world.level.block.Blocks.STONE)
                        && helper.getBlockState(new net.minecraft.core.BlockPos(1, 1, 1)).isAir(),
                "The native structure content has a solid floor at local zero and free standing volume above it");
        Set<String> expected = Arrays.stream(BackpackGameTests.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GameTest.class))
                .map(BackpackGameTests::testId).collect(Collectors.toSet());
        Set<String> discovered = GameTestRegistry.getAllTestFunctions().stream()
                .map(TestFunction::testName).filter(id -> id.startsWith("backpackgametests.")).collect(Collectors.toSet());
        helper.assertTrue(expected.size() >= 169, "The required server suite is nonempty and contains every declared feature group");
        var missing = new HashSet<>(expected);
        missing.removeAll(discovered);
        var unexpected = new HashSet<>(discovered);
        unexpected.removeAll(expected);
        helper.assertTrue(missing.isEmpty() && unexpected.isEmpty(), "Runtime discovery must match annotations exactly; missing=" + missing + ", unexpected=" + unexpected);
        helper.succeed();
    }

    private static String testId(Method method) {
        return (BackpackGameTests.class.getSimpleName() + "." + method.getName()).toLowerCase(Locale.ROOT);
    }

    @GameTest(template = STRUCTURE) public void tierComponentRoundTrips(GameTestHelper helper) { StorageGameTests.tierComponentRoundTrips(helper); }
    @GameTest(template = STRUCTURE) public void creativePickCopiesAreAuthorizedAndIndependent(GameTestHelper helper) { IdentityGameTests.creativePickCopiesAreAuthorizedAndIndependent(helper); }
    @GameTest(template = STRUCTURE) public void upgradeCapacityAndNesting(GameTestHelper helper) { StorageGameTests.upgradeCapacityAndNesting(helper); }
    @GameTest(template = STRUCTURE) public void memorySortingAndFilters(GameTestHelper helper) { StorageGameTests.memorySortingAndFilters(helper); }
    @GameTest(template = STRUCTURE) public void itemStorageRollbackAndFilters(GameTestHelper helper) { StorageGameTests.itemStorageRollbackAndFilters(helper); }
    @GameTest(template = STRUCTURE) public void sharedInventoryHandles(GameTestHelper helper) { StorageGameTests.sharedInventoryHandles(helper); }
    @GameTest(template = STRUCTURE) public void placementSaveAndDrops(GameTestHelper helper) { BlockGameTests.placementSaveAndDrops(helper); }
    @GameTest(template = STRUCTURE) public void viewersPickupAndComparator(GameTestHelper helper) { BlockGameTests.viewersPickupAndComparator(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 180) public void naturalHopperTransfers(GameTestHelper helper) { BlockGameTests.naturalHopperTransfers(helper); }
    @GameTest(template = STRUCTURE) public void nativeSlotAndDeath(GameTestHelper helper) { EquipmentGameTests.nativeSlotAndDeath(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 60) public void capturePersistenceAndSafeRelease(GameTestHelper helper) { CaptureGameTests.capturePersistenceAndSafeRelease(helper); }
    @GameTest(template = STRUCTURE) public void slotConservationAndAuthorization(GameTestHelper helper) { MenuGameTests.slotConservationAndAuthorization(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 60) public void actualServerPayloads(GameTestHelper helper) { MenuGameTests.actualServerPayloads(helper); }

    @GameTest(template = STRUCTURE) public void upgradeFilters(GameTestHelper helper) { UpgradeGameTests.filters(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 600) public void upgradeCooking(GameTestHelper helper) { UpgradeGameTests.cooking(helper); }
    @GameTest(template = STRUCTURE) public void upgradeCompacting(GameTestHelper helper) { UpgradeGameTests.compacting(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 40) public void upgradeMagnet(GameTestHelper helper) { UpgradeGameTests.magnet(helper); }
    @GameTest(template = STRUCTURE) public void upgradeFeeding(GameTestHelper helper) { UpgradeGameTests.feeding(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 40) public void upgradeTransferConservation(GameTestHelper helper) { UpgradeGameTests.transferConservation(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void upgradeJukebox(GameTestHelper helper) { UpgradeGameTests.jukebox(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 200) public void audioTrackingLifecycle(GameTestHelper helper) { AudioTrackingGameTests.rapidRetracking(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void upgradeAlchemy(GameTestHelper helper) { UpgradeGameTests.alchemy(helper); }
    @GameTest(template = STRUCTURE) public void toolsAndAttackHooks(GameTestHelper helper) { UpgradeGameTests.toolsAndAttackHooks(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void toolModesAndDataRules(GameTestHelper helper) { UpgradeGameTests.toolModesAndDataRules(helper); }

    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamGenerationAndPauses(GameTestHelper helper) { SteamEngineGameTests.generationAndPauses(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamFuelAndContainers(GameTestHelper helper) { SteamEngineGameTests.fuelAndContainerConservation(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamSidedTransactions(GameTestHelper helper) { SteamEngineGameTests.sidedTransactions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamNeighborEnergyOutput(GameTestHelper helper) { SteamEngineGameTests.neighborEnergyOutput(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamPersistenceAndBreaks(GameTestHelper helper) { SteamEngineGameTests.persistenceAndBreaks(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamMenuAuthorityAndCounters(GameTestHelper helper) { SteamEngineGameTests.menuAuthorityAndCounters(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamSideConfigurationAndTransactions(GameTestHelper helper) { SteamEngineGameTests.sideConfigurationAndTransactions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamSideMenuAuthority(GameTestHelper helper) { SteamEngineGameTests.sideMenuAuthority(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void steamSideOutputConnections(GameTestHelper helper) { SteamEngineGameTests.sideOutputConnections(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 160) public void conduitBundlingAndSurvivalDrops(GameTestHelper helper) { ConduitGameTests.bundlingAndSurvivalDrops(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 160) public void conduitIndependentLanesAndSides(GameTestHelper helper) { ConduitGameTests.independentLanesAndSides(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 240) public void conduitBranchedRoutingFairness(GameTestHelper helper) { ConduitGameTests.branchedRoutingFairness(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 160) public void conduitTransactionAbortAndReplacement(GameTestHelper helper) { ConduitGameTests.transactionAbortAndReplacement(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 160) public void conduitSplitModesAndChunkBoundaries(GameTestHelper helper) { ConduitGameTests.splitModesAndChunkBoundaries(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 160) public void conduitForwardingAndSharedBudgets(GameTestHelper helper) { ConduitGameTests.forwardingAndSharedBudgets(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 160) public void conduitRedstoneAndPersistedConfiguration(GameTestHelper helper) { ConduitGameTests.redstoneAndPersistedConfiguration(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void conduitSourceCursorLifecycle(GameTestHelper helper) { ConduitGameTests.sourceCursorLifecycle(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void conduitReboundEndpointViews(GameTestHelper helper) { ConduitGameTests.reboundEndpointViews(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 80) public void conduitUnrelatedTopologyChanges(GameTestHelper helper) { ConduitGameTests.unrelatedTopologyChanges(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 200) public void conduitSeparateSidedEnergyStorage(GameTestHelper helper) { ConduitGameTests.separateSidedEnergyStorage(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 200) public void conduitBackpackFilteredRouting(GameTestHelper helper) { ConduitGameTests.backpackFilteredRouting(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 160) public void conduitBackpackHighSlotRouting(GameTestHelper helper) { ConduitGameTests.backpackHighSlotRouting(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 20) public void conduitBackpackIndexedViewOwnership(GameTestHelper helper) { ConduitGameTests.backpackIndexedViewOwnership(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void conduitBackpackFilteredTransactions(GameTestHelper helper) { ConduitGameTests.backpackFilteredTransactions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 200) public void conduitBackpackFilterPersistence(GameTestHelper helper) { ConduitGameTests.backpackFilterPersistence(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 80) public void conduitFilterMenuAuthority(GameTestHelper helper) { ConduitFilterGameTests.menuAuthority(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 20) public void conduitFilterPersistenceAndViewers(GameTestHelper helper) { ConduitFilterGameTests.persistedPolicyValidationAndViewerState(helper); }

    @GameTest(template = STRUCTURE) public void tankTransactions(GameTestHelper helper) { ResourceGameTests.tankTransactions(helper); }
    @GameTest(template = STRUCTURE) public void tankPersistenceAndCapacity(GameTestHelper helper) { ResourceGameTests.tankPersistenceAndCapacity(helper); }
    @GameTest(template = STRUCTURE) public void itemApiTransactions(GameTestHelper helper) { ResourceGameTests.itemApiTransactions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void sharedItemAndEquipmentApis(GameTestHelper helper) { ResourceGameTests.sharedItemAndEquipmentApis(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void sidedResourceTransactions(GameTestHelper helper) { ResourceGameTests.sidedResourceTransactions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void placedEnergyOutput(GameTestHelper helper) { ResourceGameTests.placedEnergyOutput(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void containerTransfers(GameTestHelper helper) { ResourceGameTests.containerTransfers(helper); }
    @GameTest(template = STRUCTURE) public void energyTransactions(GameTestHelper helper) { ResourceGameTests.energyTransactions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void pumpHandlers(GameTestHelper helper) { ResourceGameTests.pumpHandlers(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void pumpWorld(GameTestHelper helper) { ResourceGameTests.pumpWorld(helper); }
    @GameTest(template = STRUCTURE) public void xpTransfers(GameTestHelper helper) { ResourceGameTests.xpTransfers(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void xpMendingAndCollection(GameTestHelper helper) { ResourceGameTests.xpMendingAndCollection(helper); }

    @GameTest(template = STRUCTURE, timeoutTicks = 200) public void catalogPagesAndAuthority(GameTestHelper helper) { BrowserGameTests.catalogPagesAndAuthority(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void transferPayloads(GameTestHelper helper) { BrowserGameTests.transferPayloads(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 60) public void ghostRegistrySelection(GameTestHelper helper) { BrowserGameTests.ghostRegistrySelection(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 80) public void limitedCraftingTransfer(GameTestHelper helper) { BrowserGameTests.limitedCraftingTransfer(helper); }

    @GameTest(template = STRUCTURE) public void craftingRemaindersAndPersistence(GameTestHelper helper) { WorkstationGameTests.craftingRemaindersAndPersistence(helper); }
    @GameTest(template = STRUCTURE) public void stonecutterResultsAndSelection(GameTestHelper helper) { WorkstationGameTests.stonecutterResultsAndSelection(helper); }
    @GameTest(template = STRUCTURE) public void anvilCostsRepairAndRename(GameTestHelper helper) { WorkstationGameTests.anvilCostsRepairAndRename(helper); }
    @GameTest(template = STRUCTURE) public void smithingPreservesComponents(GameTestHelper helper) { WorkstationGameTests.smithingPreservesComponents(helper); }
    @GameTest(template = STRUCTURE) public void placedSessionAndStaleGuards(GameTestHelper helper) { WorkstationGameTests.placedSessionAndStaleGuards(helper); }
    @GameTest(template = STRUCTURE) public void shapedUpgradeDataRetention(GameTestHelper helper) { RecipeGameTests.shapedUpgradeDataRetention(helper); }
    @GameTest(template = STRUCTURE) public void dyeRegionsBlendsAndRetention(GameTestHelper helper) { RecipeGameTests.dyeRegionsBlendsAndRetention(helper); }
    @GameTest(template = STRUCTURE) public void cauldronWashConservation(GameTestHelper helper) { RecipeGameTests.cauldronWashConservation(helper); }

    @GameTest(template = STRUCTURE) public void infinitySeedRules(GameTestHelper helper) { InfinityGameTests.seedRules(helper); }
    @GameTest(template = STRUCTURE) public void infinityMenuPermissionsAndExtraction(GameTestHelper helper) { InfinityGameTests.menuPermissionsAndExtraction(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void infinityAutomationAndRollback(GameTestHelper helper) { InfinityGameTests.automationAndRollback(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void goldBackpackRules(GameTestHelper helper) { PiglinSafetyGameTests.goldBackpackRules(helper); }
    @GameTest(template = STRUCTURE) public void tooltipSnapshotConservation(GameTestHelper helper) { VisualSnapshotGameTests.tooltipSnapshotConservation(helper); }
    @GameTest(template = STRUCTURE) public void tooltipSnapshotBounds(GameTestHelper helper) { VisualSnapshotGameTests.tooltipSnapshotBounds(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void displaySelectionRules(GameTestHelper helper) { VisualSnapshotGameTests.displaySelectionRules(helper); }
    @GameTest(template = STRUCTURE) public void workstationDestinationsAndRefill(GameTestHelper helper) { WorkstationGameTests.destinationsAndRefill(helper); }
    @GameTest(template = STRUCTURE) public void craftingConflictSelection(GameTestHelper helper) { WorkstationGameTests.craftingConflictSelection(helper); }
    @GameTest(template = STRUCTURE) public void bucketRefillConservation(GameTestHelper helper) { WorkstationGameTests.bucketRefillConservation(helper); }
    @GameTest(template = STRUCTURE) public void stonecutterRefillAndRecents(GameTestHelper helper) { WorkstationGameTests.stonecutterRefillAndRecents(helper); }
    @GameTest(template = STRUCTURE) public void settingsOnlyTemplates(GameTestHelper helper) { SettingsGameTests.settingsOnlyTemplates(helper); }
    @GameTest(template = STRUCTURE) public void templateGeometryAndValidation(GameTestHelper helper) { SettingsGameTests.templateGeometryAndValidation(helper); }
    @GameTest(template = STRUCTURE) public void privateDefaultsAndTemplateNames(GameTestHelper helper) { SettingsGameTests.privateDefaultsAndTemplateNames(helper); }
    @GameTest(template = STRUCTURE) public void menuPreferences(GameTestHelper helper) { SettingsGameTests.menuPreferences(helper); }
    @GameTest(template = STRUCTURE) public void permissionCheckedExport(GameTestHelper helper) { SettingsGameTests.permissionCheckedExport(helper); }
    @GameTest(template = STRUCTURE) public void dataPackSettings(GameTestHelper helper) { SettingsGameTests.dataPackSettings(helper); }

    @GameTest(template = STRUCTURE) public void advancedFilterMatrix(GameTestHelper helper) { UpgradeGameTests.advancedFilterMatrix(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 40) public void automaticCookingFilterControls(GameTestHelper helper) { UpgradeGameTests.automaticCookingFilterControls(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 600) public void cookingPausedPersistence(GameTestHelper helper) { UpgradeGameTests.cookingPausedPersistence(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void alchemyConditions(GameTestHelper helper) { UpgradeGameTests.alchemyConditions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void alchemyEffectMatching(GameTestHelper helper) { UpgradeGameTests.alchemyEffectMatching(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void alchemyConsumableFamilies(GameTestHelper helper) { UpgradeGameTests.alchemyConsumableFamilies(helper); }

    @GameTest(template = STRUCTURE) public void sharedEquipmentAuthority(GameTestHelper helper) { MenuGameTests.sharedEquipmentAuthority(helper); }
    @GameTest(template = STRUCTURE) public void equipmentMenuReadsLiveContents(GameTestHelper helper) { MenuGameTests.equipmentMenuReadsLiveContents(helper); }
    @GameTest(template = STRUCTURE) public void nestedMenuLease(GameTestHelper helper) { MenuGameTests.nestedMenuLease(helper); }
    @GameTest(template = STRUCTURE) public void containerBackpackLease(GameTestHelper helper) { MenuGameTests.containerBackpackLease(helper); }
    @GameTest(template = STRUCTURE) public void placedNestedViewLease(GameTestHelper helper) { MenuGameTests.placedNestedViewLease(helper); }
    @GameTest(template = STRUCTURE) public void workstationBackpackLease(GameTestHelper helper) { MenuGameTests.workstationBackpackLease(helper); }
    @GameTest(template = STRUCTURE) public void creativeCopiesHaveIndependentIdentities(GameTestHelper helper) { BlockGameTests.creativeCopiesHaveIndependentIdentities(helper); }
    @GameTest(template = STRUCTURE) public void displayPacketsExcludePrivateStorage(GameTestHelper helper) { BlockGameTests.displayPacketsExcludePrivateStorage(helper); }
    @GameTest(template = STRUCTURE) public void everlastingItemLifecycle(GameTestHelper helper) { ProtectionGameTests.everlastingItemLifecycle(helper); }
    @GameTest(template = STRUCTURE) public void everlastingLavaAndExplosion(GameTestHelper helper) { ProtectionGameTests.everlastingLavaAndExplosion(helper); }
    @GameTest(template = STRUCTURE) public void directStashBothDirections(GameTestHelper helper) { MenuGameTests.directStashBothDirections(helper); }
    @GameTest(template = STRUCTURE) public void directStashPartialCapacity(GameTestHelper helper) { MenuGameTests.directStashPartialCapacity(helper); }
    @GameTest(template = STRUCTURE) public void filteredViewRejectsHiddenAndMalformedClicks(GameTestHelper helper) { MenuGameTests.filteredViewRejectsHiddenAndMalformedClicks(helper); }
    @GameTest(template = STRUCTURE) public void bulkSettingsPreservePhysicalContents(GameTestHelper helper) { MenuGameTests.bulkSettingsPreservePhysicalContents(helper); }
    @GameTest(template = STRUCTURE) public void bulkTransfersPreserveOwnerAndHotbar(GameTestHelper helper) { MenuGameTests.bulkTransfersPreserveOwnerAndHotbar(helper); }
    @GameTest(template = STRUCTURE) public void shortcutTransfersOnlyFirstBackpack(GameTestHelper helper) { MenuGameTests.shortcutTransfersOnlyFirstBackpack(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void adaptiveRowsPreserveAuthority(GameTestHelper helper) { MenuGameTests.adaptiveRowsPreserveAuthority(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void adaptiveRowsRespectFilteredRanks(GameTestHelper helper) { MenuGameTests.adaptiveRowsRespectFilteredRanks(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void retainedUpgradeSelectionDoesNotOverlapRows(GameTestHelper helper) { MenuGameTests.retainedUpgradeSelectionDoesNotOverlapRows(helper); }

    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void configuredGeometryAndShrink(GameTestHelper helper) { ConfigGameTests.configuredGeometryAndShrink(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void geometryReflowAndComponents(GameTestHelper helper) { ConfigGameTests.geometryReflowAndComponents(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void itemRulesAndCapture(GameTestHelper helper) { ConfigGameTests.itemRulesAndCapture(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void backpackBurden(GameTestHelper helper) { ConfigGameTests.backpackBurden(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void upgradeGeometryAndInstallation(GameTestHelper helper) { ConfigGameTests.upgradeGeometryAndInstallation(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void cookingFilterGeometryAndTemplates(GameTestHelper helper) { ConfigGameTests.cookingFilterGeometryAndTemplates(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 1_000) public void configuredCookingBounds(GameTestHelper helper) { ConfigGameTests.configuredCookingBounds(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void configuredResourceBounds(GameTestHelper helper) { ConfigGameTests.configuredResourceBounds(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void configuredUpgradeRanges(GameTestHelper helper) { ConfigGameTests.configuredUpgradeRanges(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void configuredCompactingShapes(GameTestHelper helper) { ConfigGameTests.configuredCompactingShapes(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void configuredMagnetExperienceCadence(GameTestHelper helper) { ConfigGameTests.configuredMagnetExperienceCadence(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void configuredJukeboxResize(GameTestHelper helper) { ConfigGameTests.configuredJukeboxResize(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void spawnLifecycle(GameTestHelper helper) { WorldGameTests.spawnLifecycle(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void carrierBuffsAndLootMappings(GameTestHelper helper) { WorldGameTests.carrierBuffsAndLootMappings(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void dropsAndFakePlayers(GameTestHelper helper) { WorldGameTests.dropsAndFakePlayers(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void conversionAndMusic(GameTestHelper helper) { WorldGameTests.conversionAndMusic(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void delayedLootConservation(GameTestHelper helper) { WorldGameTests.delayedLootConservation(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void creeperCloudProtection(GameTestHelper helper) { WorldGameTests.creeperCloudProtection(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void chestLootTables(GameTestHelper helper) { WorldGameTests.chestLootTables(helper); }

    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nestedStructureAndOrdering(GameTestHelper helper) { InceptionGameTests.nestedStructureAndOrdering(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nestedSimulationAndStaleViews(GameTestHelper helper) { InceptionGameTests.nestedSimulationAndStaleViews(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nestedProcessingAndFilters(GameTestHelper helper) { InceptionGameTests.nestedProcessingAndFilters(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nestedTickSettings(GameTestHelper helper) { InceptionGameTests.nestedTickSettings(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nestedResourceTransactions(GameTestHelper helper) { InceptionGameTests.nestedResourceTransactions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nestedItemContextsAndConnections(GameTestHelper helper) { InceptionGameTests.nestedItemContextsAndConnections(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nestedCookingAndFeeding(GameTestHelper helper) { InceptionGameTests.nestedCookingAndFeeding(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nativeHopperNestedRouting(GameTestHelper helper) { InceptionGameTests.nativeHopperNestedRouting(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nativeHopperConnectionRules(GameTestHelper helper) { InceptionGameTests.nativeHopperConnectionRules(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void fluidVoidFiltersAndModes(GameTestHelper helper) { FluidVoidGameTests.fluidVoidFiltersAndModes(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void fluidVoidOverflowAndRollback(GameTestHelper helper) { FluidVoidGameTests.fluidVoidOverflowAndRollback(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void fluidVoidItemContextsAndTemplates(GameTestHelper helper) { FluidVoidGameTests.fluidVoidItemContextsAndTemplates(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void fluidVoidNativePumpAndCursor(GameTestHelper helper) { FluidVoidGameTests.fluidVoidNativePumpAndCursor(helper); }

    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void adminCommandPermissions(GameTestHelper helper) { AdminGameTests.adminCommandPermissions(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void archiveSnapshotAndRecovery(GameTestHelper helper) { AdminGameTests.archiveSnapshotAndRecovery(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void archiveDiskAndCleanup(GameTestHelper helper) { AdminGameTests.archiveDiskAndCleanup(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void wholeTemplateCommands(GameTestHelper helper) { AdminGameTests.wholeTemplateCommands(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void dynamicTemplateCommands(GameTestHelper helper) { AdminGameTests.dynamicTemplateCommands(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void templateDatapackExport(GameTestHelper helper) { AdminGameTests.templateDatapackExport(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void archiveAccessLifecycle(GameTestHelper helper) { AdminGameTests.archiveAccessLifecycle(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void duplicateCarriedIdentities(GameTestHelper helper) { IdentityGameTests.duplicateCarriedIdentities(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void duplicateEquippedAndNestedIdentities(GameTestHelper helper) { IdentityGameTests.duplicateEquippedAndNestedIdentities(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void duplicateDroppedIdentities(GameTestHelper helper) { IdentityGameTests.duplicateDroppedIdentities(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void identityRepairAndArchiveCadence(GameTestHelper helper) { IdentityGameTests.identityRepairAndArchiveCadence(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void physicalArchivesIgnoreUpgradeScope(GameTestHelper helper) { IdentityGameTests.physicalArchivesIgnoreUpgradeScope(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void memoryFirstSortingAndConservation(GameTestHelper helper) { SortingGameTests.memoryFirstSortingAndConservation(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void memoryComponentsAndInheritedPreferences(GameTestHelper helper) { SortingGameTests.memoryComponentsAndInheritedPreferences(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void sortingProtectedCellsAndNestedContents(GameTestHelper helper) { SortingGameTests.sortingProtectedCellsAndNestedContents(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void sortingCapacityAndAtomicFailure(GameTestHelper helper) { SortingGameTests.sortingCapacityAndAtomicFailure(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void maximumCraftingTransfers(GameTestHelper helper) { BrowserGameTests.maximumCraftingTransfers(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void portableWorkstationTransfers(GameTestHelper helper) { BrowserGameTests.portableWorkstationTransfers(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void nativeWorkstationTransfers(GameTestHelper helper) { BrowserGameTests.nativeWorkstationTransfers(helper); }
    @GameTest(template = STRUCTURE, timeoutTicks = 100) public void stonecutterReloadTransfers(GameTestHelper helper) { BrowserGameTests.stonecutterReloadTransfers(helper); }
}
